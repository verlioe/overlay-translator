package com.dsh.overlaytranslator.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dsh.overlaytranslator.TranslatorApp
import com.dsh.overlaytranslator.core.AppLog

/**
 * 讀取其他聊天 App 畫面上文字的無障礙服務。
 *
 * 這是整個 App 唯一能拿到「別人在講什麼」的管道。Android 沒有提供任何一般權限可以
 * 讀取其他 App 的畫面內容，無障礙服務是官方唯一合法的路子（也是為什麼這類懸浮翻譯
 * App 全都得要求開無障礙）。
 *
 * ## 去抖動（debounce）為什麼必要
 *
 * 聊天 App 在打字、捲動、收到訊息時會噴出非常大量的 typeWindowContentChanged 事件，
 * 一個字一個事件。如果每個事件都去走訪節點樹，會又慢又耗電。
 * 這裡的做法是：事件進來就重置一個 450ms 的計時器，等畫面安靜下來才真的截一次。
 * 但同時設下 2 秒的上限，避免對方連續傳訊息時永遠等不到安靜的瞬間。
 */
class ChatAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val captureRunnable = Runnable { captureNow() }

    private var pendingPackage: String? = null
    private var pendingSince = 0L

    /**
     * 上一次發佈出去的畫面「簽章」。
     *
     * 為什麼需要它？因為譯文 chip 本身也是 App 的視窗，它出現/移動時又會產生新的
     * 無障礙事件，於是「擷取 → 建立 chip → 又有事件 → 再擷取」形成無限迴圈
     * （實測 logcat 每秒都在印「擷取…候選 10 行」）。
     * 內容沒變就不重複發佈，迴圈自然斷掉，也省電。
     */
    private var lastSignature: String? = null

    private val ownPackage: String by lazy { applicationContext.packageName }

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yActions.register(this)
        A11yBridge.setConnected(true)
        AppLog.d(TAG, "無障礙服務已連線，可以開始讀取畫面文字")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val sourcePackage = safeEvent.packageName?.toString().orEmpty()
        if (sourcePackage.isEmpty()) return
        // 平常跳過自己；但「示範對話」在前景時要例外，否則就沒有內建的假聊天可以測。
        //
        // 這裡**不能只是 return**：切回本 App 自己的畫面（主畫面／設定頁）時，
        // 服務手上還留著上一個 App 的快照，懸浮窗就會繼續把圓點畫在設定頁上
        // （實機踩過：6 顆圓點散在「目前狀態」那幾列旁邊，怎麼點都清不掉）。
        //
        // 但也不能「只要是自己套件的事件就清」：**點懸浮球／圓點產生的也是本套件的事件**
        // （懸浮視窗是我們的），那時候前景其實是別的 App；清掉就會變成
        // 「按了球球，所有 UI 閃一下就不見」（實機回報）。
        // 所以用 [A11yBridge.ownUiActive]（由 Activity 生命週期維護）來分辨
        // 「真的切回本 App 的畫面」與「只是點了懸浮球」。
        if (sourcePackage == ownPackage && !A11yBridge.demoModeActive) {
            if (A11yBridge.ownUiActive) publishEmptyScreen(sourcePackage)
            return
        }
        if (sourcePackage in IGNORED_PACKAGES) return
        if (!isWatched(sourcePackage)) return

        val now = SystemClock.uptimeMillis()
        if (pendingPackage != sourcePackage) {
            pendingPackage = sourcePackage
            pendingSince = now
        }

        handler.removeCallbacks(captureRunnable)
        val waited = now - pendingSince
        val delay = if (waited >= MAX_SETTLE_MS) 0L else SETTLE_DELAY_MS
        handler.postDelayed(captureRunnable, delay)
    }

    override fun onInterrupt() {
        AppLog.d(TAG, "無障礙服務被中斷")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        A11yActions.register(null)
        A11yBridge.setConnected(false)
        handler.removeCallbacksAndMessages(null)
        AppLog.d(TAG, "無障礙服務已中斷連線")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yActions.register(null)
        A11yBridge.setConnected(false)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 擷取

    private fun captureNow() {
        val sourcePackage = pendingPackage ?: return
        if (!isWatched(sourcePackage)) return

        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            AppLog.e(TAG, "取得 rootInActiveWindow 失敗", t)
            null
        }
        if (root == null) {
            // 很常見：使用者剛切走 App，或該視窗設了 FLAG_SECURE
            AppLog.d(TAG, "$sourcePackage 目前拿不到畫面節點（已切換或視窗受保護）")
            return
        }

        // 標題列／工具列的範圍：狀態列再往下約 56dp 之內都算介面零件，
        // 不是聊天內容（實測「返回」「示範對話」會被當成訊息翻出來）。
        val topChromeBottom = statusBarHeight() + dp(56f)
        val snapshot = ScreenTextExtractor.extract(root, sourcePackage, topChromeBottom)
        if (!snapshot.hasAnything) {
            lastSignature = null
            return
        }

        // 內容與位置都沒變就不要重複發佈。
        // 這打斷了「建立譯文 chip → chip 產生無障礙事件 → 再擷取」的無限迴圈。
        val signature = buildSignature(snapshot)
        if (signature == lastSignature) return
        lastSignature = signature

        AppLog.d(
            TAG,
            "擷取 $sourcePackage：候選 ${snapshot.incoming.size} 行，輸入框${if (snapshot.composer.isBlank()) "空" else "${snapshot.composer.length} 字"}" +
                snapshot.incoming.joinToString("") { line ->
                    val tail = line.text.split('\n').last().take(18)
                    "\n    y=${line.bounds.top} w=${line.bounds.width} edge=${line.dotEdge} len=${line.text.length} 尾=「$tail」"
                },
        )
        A11yBridge.publish(snapshot)
    }

    /**
     * 送出一份「畫面上沒有可翻譯內容」的空快照。
     *
     * 用在「前景換回本 App 自己的畫面」時：本 App 的畫面平常不讀，
     * 但**不讀不等於可以什麼都不說** —— 不說的話懸浮窗會以為上一個 App 還在，
     * 圓點就一直留在畫面上。
     *
     * 只在上一份不是空的時才送（用 [EMPTY_SIGNATURE] 記住），
     * 免得每個無障礙事件都重發一次、白白重畫。
     */
    private fun publishEmptyScreen(sourcePackage: String) {
        pendingPackage = null
        handler.removeCallbacks(captureRunnable)
        if (lastSignature == EMPTY_SIGNATURE) return
        lastSignature = EMPTY_SIGNATURE
        AppLog.d(TAG, "跳過自己的畫面（$sourcePackage）：清掉殘留的譯文")
        A11yBridge.publish(
            ScreenSnapshot(
                packageName = sourcePackage,
                capturedAt = SystemClock.uptimeMillis(),
                incoming = emptyList(),
                composerText = null,
            )
        )
    }

    /**
     * 把畫面內容濃縮成一個字串，做為「有沒有變」的判斷依據。
     *
     * 刻意**包含座標**：捲動時文字一樣但位置變了，那時必須重新發佈，
     * 懸浮窗才能把譯文 chip 移到新位置。
     * 反過來說，譯文 chip 出現並不會改變訊息的座標，所以簽章不變、
     * 也就不會再觸發一次擷取 —— 這正是打斷迴圈的關鍵。
     */
    private fun buildSignature(snapshot: ScreenSnapshot): String = buildString {
        append(snapshot.packageName).append('|')
        append(snapshot.composer).append('|')
        append(snapshot.composerBounds?.top ?: -1).append('|')
        snapshot.incoming.forEach { line ->
            append(line.text)
            append('@').append(line.bounds.left).append(',').append(line.bounds.top)
            append('\u0001')
        }
    }

    private fun isWatched(sourcePackage: String): Boolean {
        val watched = TranslatorApp.settings().current().watchedPackageSet()
        return watched.isEmpty() || watched.contains(sourcePackage.lowercase())
    }

    // -------------------------------------------------------- 寫回輸入框的動作

    /**
     * 把文字寫進目前有焦點的輸入框。
     *
     * 兩段式策略：
     * 1. 先試 ACTION_SET_TEXT —— 最乾淨，直接把整個輸入框內容換掉。
     * 2. 失敗時退回「寫入剪貼簿 + ACTION_PASTE」—— 有些 App 的輸入框是自製元件，
     *    不支援 ACTION_SET_TEXT。
     *
     * 注意：我們**只寫**剪貼簿，從不讀取剪貼簿。Android 10 之後背景服務沒有焦點時
     * 本來就讀不到剪貼簿，而且讀使用者的剪貼簿是隱私紅線，這個 App 不做這件事。
     */
    fun applyTextToFocusedInput(text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                AppLog.e(TAG, "寫回失敗：拿不到畫面節點")
                return false
            }
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditable(root)
            if (target == null) {
                AppLog.e(TAG, "寫回失敗：這個畫面上找不到輸入框")
                return false
            }

            if (setTextOn(target, text)) {
                AppLog.d(TAG, "已用 ACTION_SET_TEXT 寫回輸入框（${text.length} 字）")
                return true
            }
            if (pasteInto(target, text)) {
                AppLog.d(TAG, "已用 ACTION_PASTE 寫回輸入框（${text.length} 字）")
                return true
            }
            AppLog.e(TAG, "寫回失敗：輸入框不接受設定文字，請用「複製」手動貼上")
            false
        } catch (t: Throwable) {
            AppLog.e(TAG, "寫回輸入框時發生錯誤", t)
            false
        }
    }

    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun pasteInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** 找不到有焦點的輸入框時，退而求其次找第一個可編輯節點。 */
    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        var fallback: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty() && visited < FOCUS_SEARCH_LIMIT) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable) {
                if (node.isFocused) return node
                if (fallback == null) fallback = node
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
        return fallback
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(28f)
    }

    companion object {
        private const val TAG = "ChatA11yService"

        /** 畫面安靜多久之後才真的截圖。 */
        private const val SETTLE_DELAY_MS = 450L

        /** 不論如何，最多等這麼久就一定截一次。 */
        private const val MAX_SETTLE_MS = 2000L

        private const val FOCUS_SEARCH_LIMIT = 600

        /**
         * 「已經送過空快照」的簽章。
         *
         * 特別挑一個不可能是真實簽章的字串（真實簽章一定含 `|`：`套件|輸入框|座標|…`），
         * 這樣就不會跟任何真實畫面撞在一起。
         */
        private const val EMPTY_SIGNATURE = "<empty>"

        /** 這些套件永遠不讀：系統介面和自己。 */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.settings",
        )
    }
}

/**
 * 讓懸浮窗可以呼叫無障礙服務的動作，而不需要持有它的強參考。
 */
object A11yActions {

    @Volatile
    private var service: ChatAccessibilityService? = null

    fun register(service: ChatAccessibilityService?) {
        this.service = service
    }

    fun isAvailable(): Boolean = service != null

    fun applyToFocusedInput(text: String): Boolean =
        service?.applyTextToFocusedInput(text) ?: false
}
