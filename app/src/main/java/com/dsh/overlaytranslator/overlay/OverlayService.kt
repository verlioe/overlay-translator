package com.dsh.overlaytranslator.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.dsh.overlaytranslator.R
import com.dsh.overlaytranslator.TranslatorApp
import com.dsh.overlaytranslator.a11y.A11yActions
import com.dsh.overlaytranslator.a11y.A11yBridge
import com.dsh.overlaytranslator.a11y.Bounds
import com.dsh.overlaytranslator.a11y.ScreenSnapshot
import com.dsh.overlaytranslator.core.AppLog
import com.dsh.overlaytranslator.core.AppSettings
import com.dsh.overlaytranslator.core.DisplayMode
import com.dsh.overlaytranslator.core.Languages
import com.dsh.overlaytranslator.translate.ConversationMemory
import com.dsh.overlaytranslator.translate.ConversationTurn
import com.dsh.overlaytranslator.translate.Direction
import com.dsh.overlaytranslator.translate.LanguageDetector
import com.dsh.overlaytranslator.translate.Speaker
import com.dsh.overlaytranslator.translate.TranslationOutcome
import com.dsh.overlaytranslator.translate.TranslationRequest
import com.dsh.overlaytranslator.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 懸浮窗的前景服務。
 *
 * 為什麼一定要前景服務？因為 Android 會殺掉背景服務。使用者可能在別的 App 裡待很久，
 * 懸浮窗必須一直活著，所以只能靠 foreground service + 常駐通知。
 *
 * 這個類別同時是整個 App 的協調中心：它把無障礙服務抓到的文字、翻譯引擎、以及
 * 懸浮面板串在一起，並保管「使用者目前在看哪一句、譯文是什麼」的狀態。
 */
class OverlayService : Service(), OverlayPanelListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: OverlayPanelView
    private lateinit var layoutParams: WindowManager.LayoutParams

    // ---- 面板狀態 ----
    private var latestSnapshot: ScreenSnapshot? = null
    private var selectedSource: String = ""
    private var outputText: String = ""
    private var currentDirection: Direction = Direction.THEIR_TEXT_TO_ME
    private var composerOriginal: String? = null
    private var undoText: String? = null
    private var status: String = ""
    private var busy: Boolean = false
    private var userPickedSource: Boolean = false
    private var lastAutoTranslated: String? = null

    // ---- 拖曳基準 ----
    private var dragStartX = 0
    private var dragStartY = 0

    // ---- 內嵌譯文模式 ----
    private lateinit var chipManager: InlineChipManager

    /** key → 譯文（或錯誤訊息）。key 是 "msg:<hash>" 或 "composer:<hash>"。 */
    private val inlineResults = HashMap<String, String>()

    /** 翻譯失敗的 key；留著是為了讓 chip 顯示「重試」而不是裝死。 */
    private val inlineFailed = HashSet<String>()

    /** 正在翻譯中的 key，避免同一句被重複送出請求（會浪費錢）。 */
    private val inlineInFlight = HashSet<String>()

    private var languagePickerVisible = false

    /**
     * 語言選單是在**哪個 App 的前景下**打開的。
     *
     * 為什麼要記這個？以前 renderInline 每次都被呼叫時就無條件收起選單，
     * 結果是選單一出現就被下一個無障礙事件收掉 —— 使用者得連點兩三次才選得到語言
     * （實機回報）。真正該收的時機只有「換了 App」：位置是貼在畫面頂端、跟內容無關。
     */
    private var languagePickerPackage: String? = null

    /** 語言選單的保險：使用者開著不理它時，過一段時間自己收掉。 */
    private val pickerTimeout = Handler(Looper.getMainLooper())
    private val pickerTimeoutRunnable = Runnable {
        if (languagePickerVisible) {
            languagePickerVisible = false
            languagePickerPackage = null
            chipManager.hide(LANG_PICKER_KEY)
        }
    }

    // ---- 圓點 + 展開視窗（2026-10-07 改版）----

    /** 目前開著譯文視窗的訊息 key（null＝沒開）。一次只開一則。 */
    private var openMessageKey: String? = null

    /** 「我的輸入」視窗目前顯示的是譯文還是診斷。 */
    private var composerShowDiagnosis = false

    /** 診斷結果：composerKey → 【診斷】那一段。 */
    private val diagnoses = HashMap<String, String>()

    /** 診斷給出的改寫建議（有的話就能一鍵採用＝直接寫回）。 */
    private val diagnosisSuggestions = HashMap<String, String>()

    /** 建議那句翻回使用者母語的意思，給「譯文預覽」確認用。 */
    private val diagnosisMeanings = HashMap<String, String>()

    /** 正在診斷中的 composer key。 */
    private val diagnosisInFlight = HashSet<String>()

    /**
     * 使用者把「我的輸入」視窗關掉時，記下當時的輸入內容。
     *
     * 為什麼要記？因為那個視窗是每次畫面更新都會重新顯示的（它貼在輸入框上方），
     * 如果使用者按了關閉、下一個事件又把它畫回來，就會變成「關不掉」。
     * 記住「這個內容已經被關掉了」就不會再自己彈出來，直到使用者改字。
     */
    private var composerWindowDismissedFor: String? = null

    /**
     * 即時翻譯模式是否開啟。
     *
     * 刻意**不持久化**：每次服務啟動都從關閉開始。
     * 因為開啟時每一則訊息都會呼叫 API（要花錢），
     * 不該在使用者不知情的情況下自動被打開。
     */
    private var liveModeActive = false

    /** 輸入框文字的防抖：打字過程中不該每個字都送一次 API。 */
    private val composerDebounce = Handler(Looper.getMainLooper())
    private var pendingComposerKey: String? = null
    private var pendingComposerText: String? = null
    private val composerDebounceRunnable = Runnable {
        val key = pendingComposerKey
        val text = pendingComposerText
        if (key != null && text != null && liveModeActive) {
            startInlineTranslation(
                key,
                text,
                Direction.MY_TEXT_TO_THEM,
                latestSnapshot?.packageName.orEmpty(),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 先滿足 Android 對前景服務的契約：用 startForegroundService() 啟動之後，
        // 必須在時限內呼叫 startForeground()，即使接下來馬上就要停止服務也一樣。
        // 否則系統會丟 ForegroundServiceDidNotStartInTimeException 把 App 殺掉。
        createNotificationChannel()
        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            AppLog.e(TAG, "沒有懸浮窗權限，服務結束。請回到 App 主畫面開啟權限。")
            stopSelf()
            return
        }

        if (!attachOverlay()) {
            stopSelf()
            return
        }

        chipManager = InlineChipManager(this, windowManager)
        val screen = screenSize()
        chipManager.setScreenSize(screen.x, screen.y)

        observeAccessibility()

        // 無障礙服務通常比懸浮窗更早開始擷取，而 A11yBridge.events 是 SharedFlow
        // （沒有 replay）—— 那些事件發給「還沒訂閱的人」就消失了。
        // 再加上擷取端有「內容沒變就不重複發佈」的去重，懸浮窗會一直停在
        // 「沒有快照」的狀態，直到畫面再次變動。
        //
        // 症狀：譯文 chip 用上一個畫面的座標去擺，看起來就是「跑很遠」或亂擋。
        // A11yBridge.snapshot 是 StateFlow，會保留最後一次快照，所以這裡主動補讀一次。
        A11yBridge.snapshot.value?.let { seed ->
            AppLog.d(TAG, "補讀既有快照：${seed.packageName} / ${seed.incoming.size} 行")
            onSnapshot(seed)
        }

        renderPanel()
        AppLog.d(TAG, "懸浮窗已啟動（模式：${TranslatorApp.settings().current().displayMode}）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.d(TAG, "收到停止指令")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_OPEN_PANEL -> {
                if (::panelView.isInitialized) {
                    panelView.isExpanded = true
                    renderPanel()
                    safeUpdateLayout()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        composerDebounce.removeCallbacks(composerDebounceRunnable)
        pickerTimeout.removeCallbacks(pickerTimeoutRunnable)
        languagePickerVisible = false
        languagePickerPackage = null
        if (::chipManager.isInitialized) {
            chipManager.hideAll()
        }
        if (::panelView.isInitialized) {
            try {
                windowManager.removeView(panelView)
            } catch (t: Throwable) {
                AppLog.e(TAG, "移除懸浮窗失敗", t)
            }
        }
        super.onDestroy()
        AppLog.d(TAG, "懸浮窗已停止")
    }

    // ------------------------------------------------------------------ 啟動／停止

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun attachOverlay(): Boolean {
        val settings = TranslatorApp.settings().current()
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.bubbleX.coerceAtLeast(0)
            y = settings.bubbleY.coerceAtLeast(0)
        }
        clampPosition()

        panelView = OverlayPanelView(this, this)
        return try {
            windowManager.addView(panelView, layoutParams)
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "無法加上懸浮窗（權限可能被撤銷）", t)
            false
        }
    }

    private fun observeAccessibility() {
        scope.launch {
            A11yBridge.events.collect { snapshot -> onSnapshot(snapshot) }
        }
    }

    // ------------------------------------------------------------------ 畫面事件

    private fun onSnapshot(snapshot: ScreenSnapshot) {
        latestSnapshot = snapshot

        if (snapshot.composer.isNotBlank()) {
            composerOriginal = snapshot.composer
        }

        if (TranslatorApp.settings().current().displayMode == DisplayMode.INLINE) {
            renderInline(snapshot)
        } else {
            renderPanelMode(snapshot)
        }
    }

    /** 面板模式的既有行為。 */
    private fun renderPanelMode(snapshot: ScreenSnapshot) {
        val settings = TranslatorApp.settings().current()
        val newest = snapshot.latestIncoming

        if (settings.autoTranslateIncoming && !newest.isNullOrBlank() && newest != lastAutoTranslated) {
            lastAutoTranslated = newest
            userPickedSource = false
            translate(Direction.THEIR_TEXT_TO_ME, newest)
            return
        }

        if (!userPickedSource) {
            selectedSource = newest.orEmpty()
        }

        if (panelView.isExpanded) {
            renderPanel()
        } else if (!newest.isNullOrBlank()) {
            panelView.setBadge(true)
        }
    }

    // ------------------------------------------------------------------ 翻譯

    private fun translate(direction: Direction, text: String) {
        val source = text.trim()
        if (source.isEmpty()) return

        val settings = TranslatorApp.settings().current()
        if (!settings.isConfigured) {
            status = "尚未設定 API Key，請點「設定」填入。"
            renderPanel()
            return
        }

        currentDirection = direction
        selectedSource = source
        outputText = ""
        busy = true
        status = "翻譯中…"
        renderPanel()

        val packageName = latestSnapshot?.packageName.orEmpty()

        scope.launch {
            val outcome = TranslatorApp.translator().translate(
                TranslationRequest(
                    text = source,
                    direction = direction,
                    packageName = packageName,
                )
            )
            busy = false
            when (outcome) {
                is TranslationOutcome.Success -> {
                    outputText = outcome.text
                    status = if (outcome.fromCache) "（來自快取）" else ""
                    if (direction == Direction.THEIR_TEXT_TO_ME) {
                        rememberTurn(packageName, Speaker.THEM, source, outcome.text)
                    }
                }

                is TranslationOutcome.Failure -> {
                    status = outcome.message
                }
            }
            if (!panelView.isExpanded && outputText.isNotBlank()) {
                panelView.setBadge(true)
            }
            renderPanel()
        }
    }

    private fun rememberTurn(
        packageName: String,
        speaker: Speaker,
        sourceText: String,
        translatedText: String,
    ) {
        if (packageName.isEmpty()) return
        if (ConversationMemory.hasSeen(packageName, speaker, sourceText)) return
        ConversationMemory.record(
            packageName,
            ConversationTurn(
                speaker = speaker,
                sourceText = sourceText,
                translatedText = translatedText,
            ),
        )
    }

    private fun renderPanel() {
        val settings = TranslatorApp.settings().current()
        val showingTheirText = currentDirection == Direction.THEIR_TEXT_TO_ME

        val candidates = if (showingTheirText) {
            val fromScreen = latestSnapshot?.incoming?.map { it.text }.orEmpty()
            if (selectedSource.isNotBlank() && !fromScreen.contains(selectedSource)) {
                listOf(selectedSource) + fromScreen
            } else {
                fromScreen
            }
        } else {
            emptyList()
        }

        panelView.render(
            PanelState(
                sourceLabel = if (showingTheirText) "對方訊息" else "我的輸入",
                sourceText = selectedSource,
                outputLabel = if (showingTheirText) {
                    "譯文（${settings.myLanguage}）"
                } else {
                    "譯文（對方的語言）"
                },
                outputText = outputText,
                status = status,
                busy = busy,
                candidates = candidates,
                showCandidates = settings.showCandidates && showingTheirText,
                canWriteBack = outputText.isNotBlank() && !showingTheirText,
                canUndo = undoText != null,
            )
        )
    }

    // ------------------------------------------------------------------ 內嵌譯文模式

    private fun msgKey(text: String) = "msg:" + text.hashCode()

    private fun composerKey(text: String) = "composer:" + text.hashCode()

    /** 畫面最上方的一條假參考線，用來把提示與語言選單貼在頂端。 */
    private fun topLine(): Bounds {
        val screen = screenSize()
        val y = statusBarHeight()
        return Bounds(0, y, screen.x, y)
    }

    /** 叫目前這份快照重畫一次（改狀態之後用）。 */
    private fun refreshInline() {
        latestSnapshot?.let { if (isRunning) renderInline(it) }
    }

    /**
     * 點圓點：開／關那一則訊息的譯文視窗。
     *
     * 同一時間只開一則 —— 展開視窗會蓋住畫面，開一堆就又回到「跟著飄很煩」的老問題。
     */
    private fun toggleMessageWindow(key: String) {
        openMessageKey = if (openMessageKey == key) null else key
        AppLog.d(TAG, if (openMessageKey == null) "關閉譯文視窗" else "展開譯文視窗：$key")
        refreshInline()
    }

    /**
     * 診斷「準備送出去的那句話」（語氣、性別用法、文法、會不會失禮）。
     *
     * 為什麼值得獨立一個動作？因為「翻得對」與「講得得體」是兩件事：
     * 翻譯只要意思對，得體與否要看關係（熟不熟）、說話者性別、以及當地語言的禮貌慣例。
     * 使用者要的正是後者。
     *
     * **要被檢查的是「譯文」，不是輸入框原文。** 使用者是用自己的語言打字的，
     * 拿原文去問「這句外語得不得體」只會得到沒用的回答 ——
     * 實機就發生過：輸入框是「你好，今天晚上一起吃飯嗎」，模型回
     * 「這則訊息是中文，不是日文，無法作為送出的日語訊息」。
     * 譯文還沒好時才退回原文（此時若使用者本來就打外語，原文就是他要送出的那句，也正確）。
     *
     * @param force true 代表「重新診斷」：忽略快取結果，重送一次 API
     */
    private fun diagnoseComposer(force: Boolean = false) {
        val snapshot = latestSnapshot ?: return
        val composer = snapshot.composer
        if (composer.isBlank()) return

        val target = composerTranslationFor(composer)
        val tone = TranslatorApp.settings().current().tone
        val diagKey = diagnosisKey(target, tone)
        composerShowDiagnosis = true

        if (force) {
            diagnoses.remove(diagKey)
            diagnosisSuggestions.remove(diagKey)
            diagnosisMeanings.remove(diagKey)
        }

        if (diagnoses.containsKey(diagKey) || !diagnosisInFlight.add(diagKey)) {
            refreshInline()
            return
        }

        refreshInline()
        scope.launch {
            val outcome = TranslatorApp.translator().translate(
                TranslationRequest(
                    text = target,
                    direction = Direction.DIAGNOSE_MY_OUTGOING,
                    packageName = snapshot.packageName,
                )
            )
            diagnosisInFlight.remove(diagKey)
            when (outcome) {
                is TranslationOutcome.Success -> {
                    val diagnosis = parseDiagnosis(outcome.text)
                    diagnoses[diagKey] = diagnosis.review
                    if (diagnosis.suggestion.isNotBlank()) {
                        diagnosisSuggestions[diagKey] = diagnosis.suggestion
                    } else {
                        diagnosisSuggestions.remove(diagKey)
                    }
                    if (diagnosis.meaning.isNotBlank()) {
                        diagnosisMeanings[diagKey] = diagnosis.meaning
                    } else {
                        diagnosisMeanings.remove(diagKey)
                    }
                }

                is TranslationOutcome.Failure -> {
                    diagnoses[diagKey] = "⚠ " + outcome.message
                }
            }
            refreshInline()
        }
    }

    /**
     * 切換「我的輸入」視窗上的聊天語氣（自動／熟人閒聊／普通閒聊／正式禮貌）。
     *
     * 為什麼選擇放在懸浮視窗，而不是留在設定頁？使用者實測回饋：設定頁那個固定值
     * 跟他當下想講的語氣對不上（「禮貌打中文之後，跟診斷的禮貌還是有距離」）——
     * 得不得體是**每一則訊息**的事，選擇就該在看得見那句譯文的地方。
     *
     * 語氣一改，舊的譯文與診斷都不算數（兩邊的快取 key 都含語氣），所以直接清掉重跑。
     */
    private fun setComposerTone(value: String) {
        val snapshot = latestSnapshot ?: return
        val composer = snapshot.composer
        AppLog.d(TAG, "聊天語氣改為：${value.ifBlank { "自動" }}")
        scope.launch {
            TranslatorApp.settings().update { it.copy(tone = value) }
            if (composer.isNotBlank()) {
                val key = composerKey(composer)
                inlineResults.remove(key)
                inlineFailed.remove(key)
                if (composerShowDiagnosis) {
                    // 正在看診斷就重新診斷（診斷的判斷標準就是這個語氣）
                    diagnoseComposer(force = true)
                } else {
                    startInlineTranslation(key, composer, Direction.MY_TEXT_TO_THEM, snapshot.packageName)
                }
            }
            refreshInline()
        }
    }

    /**
     * 輸入框那句話「準備送出去」的版本：翻好的譯文，還沒翻好才退回原文。
     *
     * 抽出來是因為「診斷」與「看診斷結果」兩個地方都必須用**同一份文字**，
     * 不然快取 key 會對不上（改一個字、譯文變了，卻還拿到上一版的診斷）。
     */
    private fun composerTranslationFor(composer: String): String =
        inlineResults[composerKey(composer)]?.takeIf { it.isNotBlank() } ?: composer

    /**
     * 診斷結果的快取 key：以「實際被診斷的那段文字」**加上語氣**為準。
     *
     * 為什麼一定要含語氣？因為得不得體是相對的 —— 同一句話在「熟人閒聊」沒問題、
     * 在「正式禮貌」很失禮。不含語氣的話，使用者改了設定再按「重新診斷」
     * 會拿到上一輪的快取，看起來就像設定根本沒生效。
     */
    private fun diagnosisKey(diagnosedText: String, tone: String) =
        "diag:" + tone.trim() + "|" + diagnosedText.hashCode()

    /** 診斷的三段結果。 */
    private data class Diagnosis(
        /** 【診斷】那一段（給使用者看的評語）。 */
        val review: String,
        /** 【建議】改寫後的外語句子（＝按「採用建議」會寫回輸入框的內容）。 */
        val suggestion: String,
        /** 【建議翻譯】上面那句翻回使用者母語的意思（給「譯文預覽」確認用）。 */
        val meaning: String,
    )

    /**
     * 拆開診斷回覆的三段。
     *
     * 提示詞要求模型輸出「【診斷】…【建議】…【建議翻譯】…」，但模型不一定乖乖照做，
     * 所以這裡刻意寫得寬容：找不到標記就整段當診斷、建議與翻譯當成沒有，
     * 絕不因為格式不符就讓使用者什麼都看不到。
     */
    private fun parseDiagnosis(raw: String): Diagnosis {
        val text = raw.trim()
        val diagnosisMarker = "【診斷】"
        val suggestionMarker = "【建議】"
        val meaningMarker = "【建議翻譯】"

        val suggestionIndex = text.indexOf(suggestionMarker)
        val meaningIndex = text.indexOf(meaningMarker)

        val review = if (text.contains(diagnosisMarker)) {
            val start = text.indexOf(diagnosisMarker) + diagnosisMarker.length
            val end = listOf(suggestionIndex, meaningIndex)
                .filter { it > start }
                .minOrNull() ?: text.length
            text.substring(start, end).trim()
        } else {
            val firstMarker = listOf(suggestionIndex, meaningIndex).filter { it > 0 }.minOrNull()
            if (firstMarker != null) text.substring(0, firstMarker).trim() else text
        }

        // 【建議】的內容從標記之後開始，到【建議翻譯】為止（沒有下一個標記就吃到結尾）
        val suggestion = if (suggestionIndex >= 0) {
            val start = suggestionIndex + suggestionMarker.length
            val end = if (meaningIndex > start) meaningIndex else text.length
            text.substring(start, end).trim()
        } else {
            ""
        }

        val meaning = if (meaningIndex >= 0) {
            text.substring(meaningIndex + meaningMarker.length).trim()
        } else {
            ""
        }

        // 「無」代表模型認為原文已經很好，那就不該出現一個可以按的「採用建議」
        val usable = if (suggestion.length <= 4 && suggestion.contains("無")) "" else suggestion
        val usableMeaning = when {
            usable.isEmpty() -> ""
            meaning.length <= 4 && meaning.contains("無") -> ""
            else -> meaning
        }
        return Diagnosis(review.ifBlank { text }, usable, usableMeaning)
    }

    /**
     * 內嵌模式的主要畫面更新。
     *
     * 這個函式每次擷取到新畫面都會被呼叫，所以它必須是**幂等**的：
     * 已經翻好的直接重用，而且每次都用最新的節點座標重新擺放 chip
     * —— 這樣使用者捲動時譯文才會跟著跑。
     */
    private fun renderInline(snapshot: ScreenSnapshot) {
        val settings = TranslatorApp.settings().current()
        val screen = screenSize()
        chipManager.setScreenSize(screen.x, screen.y)

        // 告訴 chip 管理員「畫面上哪些地方是文字」。譯文在決定位置時會避開這些矩形，
        // 這是「不擋到原字」唯一的可靠依據，所以每次畫面更新都要重新給一次
        // （使用者一捲動，這些座標就全變了）。
        chipManager.setObstacles(
            buildList {
                snapshot.incoming.forEach { if (it.bounds.isValid) add(it.bounds) }
                snapshot.composerBounds?.let { if (it.isValid) add(it) }
            }
        )

        // 即時翻譯沒開啟時，畫面上只留懸浮球，什麼都不顯示。
        // 這是刻意的：沒按開啟就不該看到任何東西，也不該產生任何 API 費用。
        if (!liveModeActive) {
            if (chipManager.activeKeys.isNotEmpty()) chipManager.hideAll()
            // 關掉即時翻譯時把視窗狀態一起歸零，下次打開才是乾淨的
            openMessageKey = null
            composerShowDiagnosis = false
            composerWindowDismissedFor = null
            panelView.setBadge(false)
            return
        }

        // 語言選單：只有「換了 App」才收（它貼在畫面頂端，位置跟聊天內容無關）。
        // 以前是每次 render 都無條件收 —— 選單一出現就被下一個無障礙事件收掉，
        // 使用者得連點兩三次才選得到語言（實機回報）。
        if (languagePickerVisible &&
            languagePickerPackage != null &&
            snapshot.packageName != languagePickerPackage
        ) {
            languagePickerVisible = false
            languagePickerPackage = null
            pickerTimeout.removeCallbacks(pickerTimeoutRunnable)
            chipManager.hide(LANG_PICKER_KEY)
        }

        val seen = HashSet<String>()
        val packageName = snapshot.packageName

        val validCount = snapshot.incoming.count { it.bounds.isValid }
        AppLog.d(
            TAG,
            "renderInline：套件=$packageName 候選=${snapshot.incoming.size} 有座標=$validCount " +
                "輸入框=${if (snapshot.composer.isBlank()) "空" else "${snapshot.composer.length}字"} " +
                "螢幕=${screen.x}x${screen.y}",
        )

        // ---- 對方訊息：每句旁邊放一顆小圓點，點開才展開譯文視窗 ----
        //
        // 為什麼不再直接把譯文畫在訊息旁邊？使用者實測回饋：
        // 「對話框跟著飄實在太煩了，遇到長訊息的時候也常常翻譯框根本不夠用」。
        // 圓點只有 24dp，不擋字也不搶注意力；譯文改在展開視窗裡顯示，
        // 長文可以捲動，就不會再有「不夠用」的問題。
        snapshot.incoming.forEach { line ->
            if (!line.bounds.isValid) return@forEach
            val key = msgKey(line.text)
            seen.add(key)

            val result = inlineResults[key]
            val failed = inlineFailed.contains(key)
            val retry = {
                inlineFailed.remove(key)
                startInlineTranslation(key, line.text, Direction.THEIR_TEXT_TO_ME, packageName)
            }

            val state = when {
                failed -> DotState.FAILED
                result != null -> DotState.READY
                else -> DotState.LOADING
            }
            chipManager.showDot(key, line.bounds, state, line.dotEdge) { toggleMessageWindow(key) }

            if (openMessageKey == key) {
                chipManager.showWindow(
                    key = key,
                    // 標題順便告訴使用者「這是什麼外文」。辨識在本機做（見 LanguageDetector）：
                    // 非拉丁字母的語言靠文字腳本幾乎不會錯，拉丁字母那群才用常用詞猜。
                    title = LanguageDetector.detect(line.text)?.let { "譯文（$it）" } ?: "譯文",
                    body = when {
                        result != null -> result
                        failed -> "⚠ 翻譯失敗。按「重試」再送一次。"
                        else -> "翻譯中…"
                    },
                    actions = buildList {
                        if (result != null) add(WindowAction("複製") { copyToClipboard(result) })
                        add(WindowAction(if (failed) "重試" else "重新翻譯") { retry() })
                        add(WindowAction("關閉") {
                            openMessageKey = null
                            latestSnapshot?.let { if (isRunning) renderInline(it) }
                        })
                    },
                    reference = line.bounds,
                    placement = ChipPlacement.BELOW_REFERENCE,
                    category = WINDOW_CATEGORY_MESSAGE,
                )
            }

            // 即時翻譯模式：看到新句子就直接翻，不必一句一句點
            if (result == null && !failed && !inlineInFlight.contains(key)) {
                startInlineTranslation(key, line.text, Direction.THEIR_TEXT_TO_ME, packageName)
            }
        }

        // 開著視窗的那則訊息若已經捲出畫面，順手把視窗關掉
        if (openMessageKey != null && openMessageKey !in seen) openMessageKey = null

        // 沒有要開的訊息視窗時，**一定要把上一扇收掉**。
        //
        // 為什麼非寫不可？訊息迴圈裡只有 `if (openMessageKey == key) showWindow(...)`，
        // 而 showWindow 的「同類別只留一扇」只在「開另一扇」時才會動手 ——
        // 所以「按關閉」「再點一次圓點」「訊息捲出畫面」這三條路徑
        // 以前都只清掉了 openMessageKey、視窗本身永遠留在畫面上。
        // 實機症狀：log 印了「關閉譯文視窗」、renderInline 也照跑，視窗就是不消失，
        // 只能去主畫面按「停止懸浮窗」才清得掉。
        if (openMessageKey == null) chipManager.hideWindowCategory(WINDOW_CATEGORY_MESSAGE)

        // ---- 我的輸入：貼在輸入框上方，附語言選擇與寫回 ----
        val composer = snapshot.composer
        val composerBounds = snapshot.composerBounds
        if (composer.isNotBlank() && composerBounds != null && composerBounds.isValid) {
            val key = composerKey(composer)
            seen.add(key)

            val result = inlineResults[key]
            val failed = inlineFailed.contains(key)
            val replyLabel = settings.theirLanguage.ifBlank { Languages.AUTO }
            val translateAction = {
                inlineFailed.remove(key)
                startInlineTranslation(key, composer, Direction.MY_TEXT_TO_THEM, packageName)
            }
            val openPicker = { showLanguagePicker() }

            // 打字中不要每個字都送 API：等使用者停下來一下再送
            if (result == null && !failed && !inlineInFlight.contains(key) &&
                composer.length >= MIN_COMPOSER_LENGTH
            ) {
                if (pendingComposerKey != key) {
                    pendingComposerKey = key
                    pendingComposerText = composer
                    composerDebounce.removeCallbacks(composerDebounceRunnable)
                    composerDebounce.postDelayed(composerDebounceRunnable, COMPOSER_DEBOUNCE_MS)
                }
            }

            // 只保留最新的那一個輸入框譯文，避免打字過程中累積一堆
            inlineResults.keys.filter { it.startsWith("composer:") && it != key }.forEach {
                inlineResults.remove(it)
                inlineFailed.remove(it)
            }

            if (composerWindowDismissedFor != composer) {
                // 診斷的是「要送出去的譯文」，快取 key 當然也要跟著譯文走
                // （見 diagnoseComposer / composerTranslationFor）。
                val diagKey = diagnosisKey(composerTranslationFor(composer), settings.tone)
                val diagnosing = diagnosisInFlight.contains(diagKey)
                val review = diagnoses[diagKey]
                val suggestion = diagnosisSuggestions[diagKey]
                val suggestionMeaning = diagnosisMeanings[diagKey]

                val title = if (composerShowDiagnosis) {
                    // 標題帶上目前用哪個語氣在判斷 —— 得不得體是相對的，
                    // 使用者要一眼看出「現在是拿哪個標準在挑毛病」。
                    "⠿ 診斷（${settings.tone.trim().ifEmpty { "自動判斷" }}）"
                } else {
                    "⠿ 我的輸入 → $replyLabel"
                }
                val body = when {
                    composerShowDiagnosis -> when {
                        diagnosing -> "診斷中…"
                        review != null -> buildString {
                            append(review)
                            // 譯文預覽：按「採用建議」寫回的就是這一串，
                            // 下面再附上它翻回母語的意思，讓使用者確認過再送。
                            if (!suggestion.isNullOrBlank()) {
                                append("\n\n建議版本（會寫回）\n").append(suggestion)
                                if (!suggestionMeaning.isNullOrBlank()) {
                                    append("\n\n這句的意思\n").append(suggestionMeaning)
                                }
                            }
                        }

                        else -> "按「診斷」檢查這句話的語氣、性別用法與文法是否得體。"
                    }

                    failed -> "⚠ 翻譯失敗，可以按「重試」。"
                    result != null -> result
                    inlineInFlight.contains(key) -> "翻譯中…"
                    else -> "按「翻譯」把輸入框的字翻成 $replyLabel。"
                }

                chipManager.showWindow(
                    key = key,
                    title = title,
                    body = body,
                    actions = buildList {
                        add(WindowAction("語言：$replyLabel") { openPicker() })

                        if (composerShowDiagnosis) {
                            if (!suggestion.isNullOrBlank()) {
                                add(WindowAction("採用建議", primary = true) {
                                    writeBackInline(suggestion, composer)
                                    composerShowDiagnosis = false
                                })
                            }
                            if (diagnosing) {
                                // 診斷中就不再重複送
                            } else {
                                // force = true：真的是「重新」診斷，不能被上一輪的快取擋住
                                add(WindowAction("重新診斷") { diagnoseComposer(force = true) })
                            }
                            add(WindowAction("看譯文") { composerShowDiagnosis = false; refreshInline() })
                        } else {
                            add(WindowAction("診斷") { diagnoseComposer() })
                            when {
                                failed -> add(WindowAction("重試", primary = true) { translateAction() })
                                result != null -> add(WindowAction("寫回", primary = true) {
                                    writeBackInline(result, composer)
                                })
                                else -> add(WindowAction("翻譯", primary = true) { translateAction() })
                            }
                        }

                        add(WindowAction("關閉") {
                            composerWindowDismissedFor = composer
                            refreshInline()
                        })
                    },
                    reference = composerBounds,
                    placement = ChipPlacement.ABOVE_REFERENCE,
                    // 高度上限＝輸入框上方到狀態列之間的全部空間：
                    // 這樣視窗預設貼在輸入框正上方、按鈕列永遠按得到，
                    // 文字再長也只是內部捲動（原本「打多了就沒地方按寫回」的解法）。
                    maxHeightPx = (composerBounds.top - statusBarHeight() - dp(16f))
                        .coerceAtLeast(dp(120f)),
                    // 可以按住標題列拖到任何位置；拖過之後就不再跟著輸入框跑
                    draggable = true,
                    category = WINDOW_CATEGORY_COMPOSER,
                    // 聊天語氣直接在這個視窗選（設定頁已經拿掉了）：
                    // 「得不得體」是每則訊息的事，選在這個視窗才對得上使用者當下的情境。
                    choices = TONE_CHOICES.map { (label, value) ->
                        WindowChoice(
                            label = label,
                            selected = settings.tone == value,
                            onClick = { setComposerTone(value) },
                        )
                    },
                )
            }
        }

        // 輸入框空掉、輸入框座標失效、或使用者按了「關閉」→ 把輸入框視窗收掉。
        // 跟訊息視窗是同一個坑：showWindow 只在「要開」的時候被呼叫，
        // 少了這一段，清空輸入框（或按關閉）之後視窗還是會留在畫面上。
        if (composer.isBlank() || composerBounds == null || !composerBounds.isValid ||
            composerWindowDismissedFor == composer
        ) {
            chipManager.hideWindowCategory(WINDOW_CATEGORY_COMPOSER)
        }

        // 內嵌模式沒有面板可以顯示訊息，所以把提示放在畫面頂端。
        // 不加入 seen，所以它會在一段時間後被 sweep 清掉。
        if (status.isNotBlank()) {
            chipManager.show(
                STATUS_KEY,
                ChipSpec(text = status, accent = false),
                topLine(),
                ChipPlacement.BELOW_REFERENCE,
            )
        }

        // 避免 inlineResults 無上限成長（每一句都會留一筆）
        if (inlineResults.size > MAX_INLINE_RESULTS) {
            inlineResults.keys
                .filter { it !in seen }
                .take(inlineResults.size - MAX_INLINE_RESULTS)
                .forEach { inlineResults.remove(it) }
        }

        chipManager.sweep(seen)

        // 懸浮球的紅點＝正在翻譯中（讓使用者知道有事情在跑）
        panelView.setBadge(inlineInFlight.isNotEmpty())
    }

    /**
     * 把目前畫面上所有還沒翻的句子一次翻掉，譯文各自貼在自己那一句下面。
     *
     * 這是內嵌模式下「點懸浮球」的主要動作 —— 使用者不必一句一句點。
     */
    private fun translateAllVisible() {
        val snapshot = latestSnapshot
        if (snapshot == null || snapshot.incoming.isEmpty()) {
            status = "⚠ 目前畫面上沒有抓到文字"
            snapshot?.let { renderInline(it) }
            return
        }
        if (!TranslatorApp.settings().current().isConfigured) {
            status = "⚠ 尚未設定 API Key（長按懸浮球可開啟設定）"
            renderInline(snapshot)
            return
        }

        status = ""
        var started = 0
        snapshot.incoming.forEach { line ->
            if (!line.bounds.isValid) return@forEach
            val key = msgKey(line.text)
            if (inlineFailed.contains(key)) {
                inlineFailed.remove(key)
                inlineResults.remove(key)
            }
            if (inlineResults.containsKey(key)) return@forEach
            if (inlineInFlight.contains(key)) return@forEach
            startInlineTranslation(key, line.text, Direction.THEIR_TEXT_TO_ME, snapshot.packageName)
            started++
        }

        AppLog.d(TAG, "全頁翻譯：送出 $started 句")
        if (started == 0) {
            status = "這一頁的句子都已經翻好了"
            renderInline(snapshot)
        }
    }

    private fun startInlineTranslation(
        key: String,
        text: String,
        direction: Direction,
        packageName: String,
    ) {
        if (inlineResults.containsKey(key) && !inlineFailed.contains(key)) return
        // 同一個 key 只允許一個進行中的請求，避免重複付費
        if (!inlineInFlight.add(key)) return

        if (!TranslatorApp.settings().current().isConfigured) {
            inlineInFlight.remove(key)
            inlineFailed.add(key)
            inlineResults[key] = "⚠ 尚未設定 API Key"
            latestSnapshot?.let { renderInline(it) }
            return
        }

        scope.launch {
            val outcome = TranslatorApp.translator().translate(
                TranslationRequest(text = text, direction = direction, packageName = packageName)
            )
            inlineInFlight.remove(key)
            when (outcome) {
                is TranslationOutcome.Success -> {
                    inlineFailed.remove(key)
                    inlineResults[key] = outcome.text
                    if (direction == Direction.THEIR_TEXT_TO_ME) {
                        rememberTurn(packageName, Speaker.THEM, text, outcome.text)
                    }
                }

                is TranslationOutcome.Failure -> {
                    inlineFailed.add(key)
                    status = "⚠ " + outcome.message
                }
            }
            latestSnapshot?.let { if (isRunning) renderInline(it) }
        }
    }

    private fun writeBackInline(translated: String, sourceText: String) {
        if (!A11yActions.isAvailable()) {
            status = "⚠ 無障礙服務未連線；點譯文可以複製後手動貼上"
            latestSnapshot?.let { renderInline(it) }
            return
        }
        val previous = composerOriginal
        if (A11yActions.applyToFocusedInput(translated)) {
            undoText = previous
            composerOriginal = translated
            status = ""
            rememberTurn(latestSnapshot?.packageName.orEmpty(), Speaker.ME, sourceText, translated)
            AppLog.d(TAG, "內嵌模式：已寫回輸入框（${translated.length} 字）")
        } else {
            status = "⚠ 寫回失敗；點譯文可以複製後手動貼上"
            latestSnapshot?.let { renderInline(it) }
        }
    }

    private fun showLanguagePicker() {
        val current = TranslatorApp.settings().current().theirLanguage.ifBlank { Languages.AUTO }
        val items = ArrayList<Pair<String, () -> Unit>>(Languages.COMMON.size + 1)
        items.add(Languages.AUTO to { setReplyLanguage("") })
        Languages.COMMON.forEach { language ->
            items.add(language to { setReplyLanguage(language) })
        }

        languagePickerVisible = true
        languagePickerPackage = latestSnapshot?.packageName
        pickerTimeout.removeCallbacks(pickerTimeoutRunnable)
        pickerTimeout.postDelayed(pickerTimeoutRunnable, PICKER_TIMEOUT_MS)
        chipManager.showList(
            key = LANG_PICKER_KEY,
            header = "回覆語言（我打的字要翻成）",
            items = items,
            selected = current,
            reference = topLine(),
            placement = ChipPlacement.BELOW_REFERENCE,
        )
    }

    /**
     * 切換回覆語言。
     *
     * 這個選擇會**蓋掉**自動判斷：提示詞裡明確寫了「使用者指定優先於上下文推斷」，
     * 所以對方講英文但你選日文時，會真的翻成日文。
     */
    private fun setReplyLanguage(language: String) {
        languagePickerVisible = false
        languagePickerPackage = null
        pickerTimeout.removeCallbacks(pickerTimeoutRunnable)
        chipManager.hide(LANG_PICKER_KEY)

        // 語言換了，舊的「我的輸入」譯文就不算數了
        inlineResults.keys.filter { it.startsWith("composer:") }.forEach { key ->
            inlineResults.remove(key)
            inlineFailed.remove(key)
        }

        scope.launch {
            TranslatorApp.settings().update { it.copy(theirLanguage = language) }
            TranslatorApp.translator().clearCache()
            latestSnapshot?.let { if (isRunning) renderInline(it) }
        }
        AppLog.d(TAG, "回覆語言改為：${language.ifBlank { Languages.AUTO }}")
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
    }

    // ------------------------------------------------------------------ 面板回呼

    override fun onBubbleTap() {
        if (panelView.isExpanded) {
            collapse()
            return
        }
        if (TranslatorApp.settings().current().displayMode == DisplayMode.INLINE) {
            toggleLiveMode()
        } else {
            expand()
        }
    }

    /**
     * 切換即時翻譯模式。
     *
     * 開啟後會**一直跟著使用者的操作自動翻譯**（對方的新訊息、以及自己打的字），
     * 直到使用者再按一次關掉為止 —— 不是翻一句就要點一下。
     */
    private fun toggleLiveMode() {
        liveModeActive = !liveModeActive
        panelView.setActive(liveModeActive)

        if (liveModeActive) {
            status = "已開啟即時翻譯 · 再點懸浮球可關閉"
            val snap = latestSnapshot
            AppLog.d(TAG, "即時翻譯：開啟；目前快照=${if (snap == null) "無" else "${snap.packageName}/${snap.incoming.size}行"}")
            if (snap != null) renderInline(snap)
            AppLog.d(TAG, "即時翻譯：開啟")
        } else {
            status = ""
            composerDebounce.removeCallbacks(composerDebounceRunnable)
            pendingComposerKey = null
            pendingComposerText = null
            chipManager.hideAll()
            panelView.setBadge(false)
            AppLog.d(TAG, "即時翻譯：關閉")
        }
    }

    override fun onBubbleLongPress() {
        // 長按：打開面板（設定、停止、最近擷取清單）
        if (panelView.isExpanded) collapse() else expand()
    }

    private fun expand() {
        panelView.isExpanded = true
        panelView.setBadge(false)
        renderPanel()

        // 展開之後視窗同時變寬也變高，要把位置拉回螢幕內，否則面板會有一半在畫面外。
        val screen = screenSize()
        val neededWidth = dp(OverlayPanelView.PANEL_WIDTH_DP.toFloat())
        val neededHeight = dp(
            (OverlayPanelView.BUBBLE_SIZE_DP + 8 + OverlayPanelView.MAX_PANEL_HEIGHT_DP).toFloat()
        )
        val minY = statusBarHeight()
        val maxX = (screen.x - neededWidth).coerceAtLeast(0)
        val maxY = (screen.y - neededHeight).coerceAtLeast(minY)
        layoutParams.x = layoutParams.x.coerceIn(0, maxX)
        layoutParams.y = layoutParams.y.coerceIn(minY, maxY)
        safeUpdateLayout()
    }

    private fun collapse() {
        panelView.isExpanded = false
        userPickedSource = false
        safeUpdateLayout()
    }

    override fun onCollapse() = collapse()

    override fun onBubbleDragStart() {
        dragStartX = layoutParams.x
        dragStartY = layoutParams.y
    }

    override fun onBubbleDrag(totalDx: Float, totalDy: Float) {
        layoutParams.x = dragStartX + totalDx.toInt()
        layoutParams.y = dragStartY + totalDy.toInt()
        clampPosition()
        safeUpdateLayout()
    }

    override fun onBubbleDragEnd() {
        val x = layoutParams.x
        val y = layoutParams.y
        scope.launch {
            TranslatorApp.settings().update { it.copy(bubbleX = x, bubbleY = y) }
        }
    }

    override fun onTranslateIncoming() {
        val text = selectedSource.ifBlank { latestSnapshot?.latestIncoming.orEmpty() }
        if (text.isBlank()) {
            status = "還沒抓到對方訊息，請先切到聊天畫面並讓訊息出現。"
            renderPanel()
            return
        }
        userPickedSource = true
        lastAutoTranslated = text
        translate(Direction.THEIR_TEXT_TO_ME, text)
    }

    override fun onTranslateOutgoing() {
        val text = latestSnapshot?.composer.orEmpty().ifBlank { composerOriginal.orEmpty() }
        if (text.isBlank()) {
            status = "聊天輸入框目前是空的，請先在聊天 App 裡打字。"
            renderPanel()
            return
        }
        translate(Direction.MY_TEXT_TO_THEM, text)
    }

    override fun onSelectCandidate(text: String) {
        userPickedSource = true
        lastAutoTranslated = text
        translate(Direction.THEIR_TEXT_TO_ME, text)
    }

    override fun onWriteBack() {
        if (outputText.isBlank()) return
        if (!A11yActions.isAvailable()) {
            status = "無障礙服務未連線，無法寫回。請改用「複製譯文」手動貼上。"
            renderPanel()
            return
        }

        val previous = composerOriginal
        val written = outputText
        if (A11yActions.applyToFocusedInput(written)) {
            undoText = previous
            composerOriginal = written
            status = if (previous.isNullOrBlank()) {
                "已寫回輸入框。"
            } else {
                "已寫回輸入框，可按「復原」還原成原本打的字。"
            }
            rememberTurn(latestSnapshot?.packageName.orEmpty(), Speaker.ME, selectedSource, written)
        } else {
            status = "寫回失敗，請改用「複製譯文」手動貼上。"
        }
        renderPanel()
    }

    override fun onUndo() {
        val previous = undoText
        if (previous.isNullOrBlank()) {
            status = "沒有可以復原的內容。"
            renderPanel()
            return
        }
        val ok = A11yActions.applyToFocusedInput(previous)
        status = if (ok) "已還原成你原本打的字。" else "還原失敗，請手動修改。"
        if (ok) {
            composerOriginal = previous
            undoText = null
        }
        renderPanel()
    }

    override fun onCopyOutput() {
        if (outputText.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            status = "無法取得剪貼簿。"
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("translation", outputText))
            status = "已複製譯文，去聊天 App 長按貼上即可。"
        }
        renderPanel()
    }

    override fun onOpenApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "開啟主畫面失敗", t)
        }
    }

    // ------------------------------------------------------------------ 位置與版面

    private fun safeUpdateLayout() {
        try {
            windowManager.updateViewLayout(panelView, layoutParams)
        } catch (t: Throwable) {
            AppLog.e(TAG, "更新懸浮窗位置失敗", t)
        }
    }

    private fun clampPosition() {
        val screen = screenSize()
        val width = if (::panelView.isInitialized && panelView.width > 0) {
            panelView.width
        } else {
            dp(OverlayPanelView.BUBBLE_SIZE_DP.toFloat())
        }
        val height = if (::panelView.isInitialized && panelView.height > 0) {
            panelView.height
        } else {
            dp(OverlayPanelView.BUBBLE_SIZE_DP.toFloat())
        }
        layoutParams.x = layoutParams.x.coerceIn(0, (screen.x - width).coerceAtLeast(0))
        // 不要讓懸浮球跑到狀態列底下。
        // 注意上下界：橫向或分割畫面時 height 可能比螢幕還高，
        // 這時若讓下界大於上界，coerceIn 會丟 IllegalArgumentException。
        // onBubbleDrag 每一次 ACTION_MOVE 都會走到這裡，所以一定要夾好。
        val minY = statusBarHeight()
        val maxY = (screen.y - height).coerceAtLeast(minY)
        layoutParams.y = layoutParams.y.coerceIn(minY, maxY)
    }

    /**
     * 目前畫面的實際大小（**已套用旋轉**）。
     *
     * 這裡刻意不用 `windowManager.currentWindowMetrics`。
     * 實測（Lenovo TB322FC / Android 16）發現：從 **Service** 取得 currentWindowMetrics 時，
     * 它回傳的是顯示器的「自然方向」尺寸 —— 平板直立使用時畫面是 1904x3040，
     * 它卻回 3040x1904。
     *
     * 後果很嚴重：所有水平夾制的上限都變成 3040 起跳，譯文視窗就跑出畫面右緣：
     *     frame=[Rect(1529, 736 - 2201, 817)]   ← 2201 > 1904
     *
     * `Display.getRealSize()` 會跟著目前旋轉回報，才是正確的來源。
     * （已 deprecated，但現階段仍是 Service 取得「旋轉後實際尺寸」最可靠的方法。）
     */
    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        val point = Point()
        val display = windowManager.defaultDisplay
        display.getRealSize(point)
        return point
    }

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(28f)
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_translator_running"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.dsh.overlaytranslator.action.STOP"
        const val ACTION_OPEN_PANEL = "com.dsh.overlaytranslator.action.OPEN_PANEL"

        /** 內嵌模式的提示 chip（會自動被 sweep 清掉）。 */
        private const val STATUS_KEY = "status"

        /** 內嵌模式的語言選擇清單。 */
        private const val LANG_PICKER_KEY = "lang-picker"

        /** 輸入框至少幾個字才值得翻譯（太短通常代表還沒打完）。 */
        private const val MIN_COMPOSER_LENGTH = 2

        /**
         * 展開視窗的類別。同類別互斥（開新的關舊的），不同類別可以並存。
         *
         * 訊息譯文視窗與「我的輸入」視窗必須分開：訊息視窗是看完就關的，
         * 輸入框那個要一直留著（寫回、診斷、語言選擇都在上面）。         */
        private const val WINDOW_CATEGORY_MESSAGE = "msg"
        private const val WINDOW_CATEGORY_COMPOSER = "composer"

        /**
         * 「我的輸入」視窗上的聊天語氣選項（標籤 → 存進設定的值；空字串＝自動）。
         *
         * 為什麼在懸浮視窗而不是設定頁？使用者的實測回饋：設定頁那個固定值跟他當下
         * 想講的語氣對不上（「禮貌打中文之後，跟診斷的禮貌還是有距離」）。
         * 得不得體是每一則訊息的事，所以選擇要在看得見那句譯文的地方。
         */
        private val TONE_CHOICES = listOf(
            "自動" to AppSettings.DEFAULT_TONE,
            AppSettings.TONE_CASUAL to AppSettings.TONE_CASUAL,
            AppSettings.TONE_POLITE to AppSettings.TONE_POLITE,
            AppSettings.TONE_FORMAL to AppSettings.TONE_FORMAL,
        )

        /** 打字停下來多久之後才送出翻譯，避免每個字都打一次 API。 */
        private const val COMPOSER_DEBOUNCE_MS = 900L

        /**
         * 語言選單開著多久之後自己收掉。
         *
         * 選單不再因為「畫面有變化」被收掉（那會讓使用者連點兩三次），
         * 所以需要這個保險：使用者開著不理它時不要一直留在畫面頂端。
         */
        private const val PICKER_TIMEOUT_MS = 15000L

        /** 內嵌譯文的快取上限，避免長時間使用後無限成長。 */
        private const val MAX_INLINE_RESULTS = 120

        /** 給 UI 查詢懸浮窗是否正在運作。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            // 先確認權限再啟動。否則 startForegroundService() 之後服務會因為沒有權限
            // 立刻 stopSelf()，在 Android 12+ 會直接丟例外。
            if (!Settings.canDrawOverlays(context)) {
                AppLog.e(TAG, "還沒有懸浮窗權限，不啟動服務")
                return
            }
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
