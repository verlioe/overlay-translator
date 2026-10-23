package com.dsh.overlaytranslator.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dsh.overlaytranslator.R
import com.dsh.overlaytranslator.a11y.Bounds
import com.dsh.overlaytranslator.a11y.DotEdge
import com.dsh.overlaytranslator.core.AppLog

/** 譯文要貼在參考物件的哪一側。 */
enum class ChipPlacement {
    /** 貼在訊息泡泡的下方（譯文最常這樣顯示）。 */
    BELOW_REFERENCE,

    /** 貼在輸入框的上方。空間不夠時自動改貼下方。 */
    ABOVE_REFERENCE,

    /** 貼在訊息右側；右邊放不下就改貼下方。 */
    RIGHT_OF_REFERENCE,

    /**
     * 貼在訊息的**側邊空位**，垂直置中，永不遮住任何文字。
     *
     * 靠左的訊息（對方的）→ 譯文放右邊；靠右的訊息（自己的）→ 譯文放左邊。
     * 這是實測後改用預設的做法：原本一律貼在訊息下方，但聊天列表行距很窄，
     * 譯文會疊到下一個訊息上，看起來很亂又擋住原文。
     * 兩側真的都塞不下時，才退回貼在下方。
     */
    BESIDE_REFERENCE,
}

/**
 * 一個譯文 chip 要長什麼樣子。
 *
 * 版面固定是橫向的 `[主要文字] [語言] [動作]`：
 * - 對方訊息的提示 chip：只有「譯」
 * - 對方訊息的譯文 chip：只有譯文
 * - 我的輸入 chip：`[譯文或提示] [日本語 ▾] [寫回或翻譯]`
 *
 * @param languageLabel 語言按鈕的文字，例如「日本語」。null 代表不顯示
 */
data class ChipSpec(
    val text: String,
    val actionLabel: String? = null,
    val languageLabel: String? = null,
    /** true 代表這是譯文（強調色外框），false 代表是「點我翻譯」的提示。 */
    val accent: Boolean = true,
    val bold: Boolean = false,
    val onTextClick: (() -> Unit)? = null,
    val onActionClick: (() -> Unit)? = null,
    val onLanguageClick: (() -> Unit)? = null,
)

/**
 * 訊息旁邊那顆小圓點的狀態。
 *
 * 使用者實測回饋：「對話框跟著飄實在太煩了，長訊息又不夠用」——
 * 所以譯文不再直接畫在畫面上，改成先放一顆小點，點開才展開視窗。
 */
enum class DotState {
    /** 正在翻譯（或還沒開始翻）。 */
    LOADING,

    /** 譯文已經好了，點下去就能看。 */
    READY,

    /** 翻譯失敗，點下去可以重試。 */
    FAILED,
}

/**
 * 展開視窗底部的一個動作按鈕。
 *
 * @param primary 主要動作（會用強調色 + 粗體，例如「寫回」）
 */
data class WindowAction(
    val label: String,
    val primary: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 展開視窗裡的「一顆可選項」（目前用在聊天語氣：自動／熟人閒聊／普通閒聊／正式禮貌）。
 *
 * 跟 [WindowAction] 的差別：動作按鈕是「按了去做一件事」，
 * 可選項是「按了切換狀態」，所以需要 [selected] 才能把目前選中的那顆畫成強調色。
 */
data class WindowChoice(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * 內嵌譯文的 chip 管理員。
 *
 * ## 為什麼是「每個譯文一個視窗」而不是「一個全螢幕視窗」
 *
 * 最直覺的作法是全螢幕透明視窗畫很多譯文，但那會**吃掉整個畫面的觸控**，
 * 使用者就沒辦法點聊天 App 了（`FLAG_NOT_TOUCH_MODAL` 只對「視窗範圍外」的觸控有效）。
 *
 * 所以這裡改成：**每一則譯文都是一個獨立的小視窗**，尺寸剛好等於那塊譯文。
 * 這樣譯文以外所有觸控都會自然穿透到底下的聊天 App，而譯文本身仍可點。
 *
 * ## 關於 FLAG_NOT_FOCUSABLE
 *
 * 視窗不能取得焦點（否則會搶走聊天 App 的輸入框焦點、讓鍵盤亂跳），
 * 但**這不影響觸控** —— chip 上的按鈕照樣可以點。
 * 只有鍵盤輸入與 Back 鍵會被擋掉，而我們不需要那兩者。
 *
 * ## 捲動時怎麼辦
 *
 * 譯文視窗不會跟著聊天內容捲動，所以每次擷取到新畫面時 [show] 都會被重新呼叫，
 * 用最新的節點座標把 chip 移到新位置。[sweep] 負責移除已經不在畫面上的 chip，
 * 但會等一下（grace period），否則捲動時譯文會一直閃。
 */
class InlineChipManager(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    private class Chip(
        val root: LinearLayout,
        val textView: TextView,
        val languageView: TextView,
        val actionView: TextView,
        val params: WindowManager.LayoutParams,
        var added: Boolean = false,
    )

    /**
     * 一個獨立的懸浮視窗（圓點與展開視窗都用它）。
     *
     * 跟 [Chip] 分開是因為結構完全不同：圓點的 root 是一個 TextView，
     * 展開視窗是「標題 + 可捲動內容 + 釘在底部的按鈕列」，硬塞進 Chip 只會讓兩邊都難讀。
     */
    private class Item(
        val root: View,
        val params: WindowManager.LayoutParams,
        var added: Boolean = false,
        /** 展開視窗才有：用來判斷「內容有沒有變」，沒變就不用重建整個 View。 */
        var windowTitle: String? = null,
        var windowBody: String? = null,
        var windowLabels: List<String> = emptyList(),
        /** 可選項列的內容（標籤＋是否選中），用來判斷「要不要重建整個 View」。 */
        var windowChoices: List<String> = emptyList(),
        /** 可選項列的 TextView，跟 [WindowChoice] 一對一。 */
        var choiceViews: List<TextView> = emptyList(),
        /** 可選項那一列（沒有可選項時整個 GONE）。 */
        var choiceRow: LinearLayout? = null,
        /** 按鈕那一列。 */
        var buttonRow: LinearLayout? = null,
        /** 按鈕列的 TextView，跟 [WindowAction] 一對一。 */
        var actionViews: List<TextView> = emptyList(),
        /**
         * 使用者自己拖過這個視窗了嗎？
         *
         * 一旦拖過就**不再自動擺位置**：實測需求是「寫回的框框讓我自己拖到想放的地方，
         * 不要隨著字飄移」——輸入框長高、鍵盤開關、訊息捲動都不該再動它。
         * 長按標題列可以歸位（恢復自動跟隨）。
         */
        var userPositioned: Boolean = false,
        /**
         * 視窗類別（"msg" / "composer"…）。
         *
         * 「一次只開一扇」只應該在同一類別裡生效：訊息譯文視窗要互斥（開新的就關舊的），
         * 但**輸入框那個視窗必須一直活著**（寫回、診斷都在上面）。
         * 實測踩過：不分類別的話，每次重畫輸入框視窗都會把剛開好的訊息視窗關掉，
         * 症狀就是「點圓點有 log、但視窗根本沒出現」。
         */
        var windowCategory: String = "",
    )

    private val chips = LinkedHashMap<String, Chip>()

    /** 訊息旁邊的小圓點（譯文入口）。key 跟 chip 一樣是 "msg:<hash>"。 */
    private val dots = LinkedHashMap<String, Item>()

    /**
     * 點開之後展開的內容視窗（譯文、我的輸入、診斷共用）。
     *
     * 刻意設計成**一次只開一扇**：展開視窗會蓋住畫面，開一堆就又回到
     * 「對話框跟著飄很煩」的老問題。
     */
    private val windows = LinkedHashMap<String, Item>()

    /**
     * 使用者拖出來的視窗位置，記在**類別**上而不是單一視窗上。
     *
     * 為什麼不能只記在視窗上？因為「我的輸入」視窗的 key 是輸入內容的雜湊 ——
     * 使用者多打一個字，key 就變了、視窗會整個重建，位置記憶就跟著歸零，
     * 症狀是「拖好之後打一個字，視窗又跳回輸入框上面」。
     * 記在類別上就沒有這個問題（訊息譯文視窗不可拖，所以不會被影響）。
     */
    private val dragMemory = HashMap<String, Pair<Int, Int>>()

    private val handler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var pendingSeen: Set<String> = emptySet()

    /**
     * 畫面上「不可以被譯文蓋住」的矩形：所有訊息的座標 + 輸入框。
     *
     * 每次 [OverlayService.renderInline] 都會重新給一份最新的，
     * 擺放譯文時就靠它算出「哪些位置是空的」。
     * 這是「不擋到原字」這件事唯一可靠的依據 —— 猜測式的固定位移沒有用。
     */
    private var obstacles: List<Bounds> = emptyList()

    private val sweepRunnable = Runnable { sweepNow() }

    private val density: Float get() = context.resources.displayMetrics.density

    private val statusBarHeight: Int by lazy {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(28f)
    }

    fun setScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    /** 更新「不可以被蓋住」的矩形清單（訊息泡泡與輸入框）。 */
    fun setObstacles(list: List<Bounds>) {
        obstacles = list
    }

    val activeKeys: Set<String> get() = (chips.keys + dots.keys + windows.keys).toSet()

    /** 目前開著內容視窗的 key（沒開就是 null）。 */
    val openWindowKey: String? get() = windows.keys.firstOrNull()

    // ------------------------------------------------------------------ 對外 API

    /**
     * 在訊息旁邊放一顆小圓點（譯文的入口）。
     *
     * 為什麼是圓點而不是直接顯示譯文？使用者實測回饋：
     * 「對話框跟著飄實在太煩了，遇到長訊息的時候也常常翻譯框根本不夠用」。
     * 圓點只有 24dp，幾乎不佔畫面、也不會蓋到任何字；想看的時候點開就好。
     */
    fun showDot(
        key: String,
        reference: Bounds,
        state: DotState,
        dotEdge: DotEdge = DotEdge.AUTO,
        onClick: () -> Unit,
    ) {
        if (reference.width <= 0) return
        if (screenWidth <= 0 || screenHeight <= 0) return

        val dot = dots.getOrPut(key) { createDot() }
        applyDotState(dot, state)
        dot.root.setOnClickListener { onClick() }

        val size = dotSize()
        dot.params.width = size
        dot.params.height = size
        // 固定在某一側邊緣（Telegram 這種拿不到泡泡位置的 App）：把 x 直接指定成
        // 畫面最左或最右，y 再由「不蓋到字」的評分從候選裡挑。
        val forcedX = when (dotEdge) {
            DotEdge.AUTO -> null
            DotEdge.LEFT -> 0
            DotEdge.RIGHT -> (screenWidth - size).coerceAtLeast(0)
        }
        val stillThere = placeItem(
            key = key,
            root = dot.root,
            params = dot.params,
            isAdded = dot.added,
            reference = reference,
            placement = ChipPlacement.BESIDE_REFERENCE,
            width = size,
            height = size,
            forcedX = forcedX,
        )
        if (stillThere) dot.added = true else dots.remove(key)
    }

    /**
     * 展開一個內容視窗（譯文／我的輸入／診斷都用這個）。
     *
     * 三個關鍵設計：
     *  1. **寬度固定、文字放在 ScrollView 裡** —— 長譯文不會把視窗撐爆，也不會沒地方看。
     *  2. **按鈕列釘在最下面**（不隨文字捲動）—— 這就是「字打多了寫回按不到」的解法：
     *     按鈕永遠在視窗底部，而視窗底部永遠貼在輸入框上方。
     *  3. **同類別一次只開一扇**：呼叫時先把同類別的其他視窗關掉（見 [Item.windowCategory]）。
     *
     * @param maxHeightFraction 視窗最高佔畫面幾成（超過就內部捲動）
     * @param maxHeightPx       直接指定高度上限（給「貼在輸入框上方」用，優先於 fraction）
     * @param draggable         true 時可以按住標題列拖到任何位置；拖過之後就不再自動擺位
     * @param category          視窗類別，例如 "msg"（訊息譯文）或 "composer"（我的輸入）
     * @param choices           可選項列（例：聊天語氣）。放在按鈕列上方，會標示目前選中的那顆
     */
    fun showWindow(
        key: String,
        title: String?,
        body: String,
        actions: List<WindowAction>,
        reference: Bounds,
        placement: ChipPlacement,
        maxHeightFraction: Float = 0.42f,
        maxHeightPx: Int = -1,
        draggable: Boolean = false,
        category: String = "",
        choices: List<WindowChoice> = emptyList(),
    ) {
        if (reference.width <= 0) return
        if (screenWidth <= 0 || screenHeight <= 0) return

        // 同類別只留一扇（訊息視窗互斥；但開訊息視窗不會把輸入框視窗關掉）
        windows.filter { it.key != key && it.value.windowCategory == category }
            .keys.toList()
            .forEach { hideWindow(it) }

        val existing = windows[key]
        val window = if (existing != null && existing.windowTitle == title &&
            existing.windowBody == body && existing.windowLabels == actions.map { it.label } &&
            existing.windowChoices == choices.map { choiceKey(it) }
        ) {
            // 內容沒變就只更新按鈕的動作（閉包每次 render 都是新的，不更新會指到舊狀態）
            existing
        } else {
            if (existing != null) hideWindow(key)
            buildWindow(title, body, actions, choices).also { windows[key] = it }
        }
        bindWindowActions(window, actions)
        bindWindowChoices(window, choices)
        window.windowCategory = category

        // 這一類別的視窗被拖過的話，新視窗直接沿用那個位置（打字換 key 也不會跳回去）
        if (draggable) {
            dragMemory[category]?.let { remembered ->
                window.userPositioned = true
                window.params.x = remembered.first
                window.params.y = remembered.second
            }
        }

        // 只有「新建」的視窗需要綁拖曳；既有視窗已經綁過了（閉包指向同一個 Item）
        if (draggable && window.actionViews.isNotEmpty()) {
            bindDrag(window, title)
        }

        val maxHeight = if (maxHeightPx > 0) {
            maxHeightPx
        } else {
            (screenHeight * maxHeightFraction).toInt()
        }
        val width = (screenWidth - dp(12f)).coerceAtLeast(dp(120f))
        val size = measureWindow(window, width, maxHeight)
        window.params.width = width
        window.params.height = size

        if (window.userPositioned) {
            // 使用者拖過就尊重他的位置：只更新內容與尺寸，然後把座標夾回畫面內。
            // 夾制是必要的 —— 字變多時視窗會變高，不夾的話底部可能被推出畫面。
            clampToScreen(window.params, width, size)
            val ok = try {
                if (window.added) {
                    windowManager.updateViewLayout(window.root, window.params)
                } else {
                    windowManager.addView(window.root, window.params)
                }
                true
            } catch (t: Throwable) {
                false
            }
            if (ok) window.added = true else windows.remove(key)
            return
        }

        val stillThere = placeItem(
            key = key,
            root = window.root,
            params = window.params,
            isAdded = window.added,
            reference = reference,
            placement = placement,
            width = width,
            height = size,
        )
        if (stillThere) window.added = true else windows.remove(key)
    }

    /** 把座標夾回畫面內（拖曳或內容變高之後都可能跑出去）。 */
    private fun clampToScreen(params: WindowManager.LayoutParams, width: Int, height: Int) {
        val margin = dp(6f)
        val maxX = (screenWidth - width - margin).coerceAtLeast(0)
        val minY = statusBarHeight
        val maxY = (screenHeight - height - margin).coerceAtLeast(minY)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(minY, maxY)
    }

    /**
     * 讓視窗可以按住標題列拖動。
     *
     * 用 `rawX/rawY`（螢幕座標）而不是 `x/y`：視窗自己的座標會隨著拖動改變，
     * 用相對座標會愈拖愈快（經典 bug）。
     *
     * 長按標題列 = 歸位（恢復自動跟隨輸入框），這是唯一能「取消」拖曳結果的方式；
     * 不做的話使用者一旦拖歪就只能重開懸浮窗。
     */
    private fun bindDrag(window: Item, title: String?) {
        val root = window.root as LinearLayout
        val handle = root.getChildAt(0) as? TextView ?: return
        if (handle.tag == DRAG_BOUND_TAG) return
        handle.tag = DRAG_BOUND_TAG

        // 沒有標題就沒有把手，補一個可拖曳的細列
        if (title.isNullOrBlank()) handle.visibility = View.VISIBLE

        val slop = dp(6f).toFloat()
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downRawX = 0f
            private var downRawY = 0f
            private var downAt = 0L
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = window.params.x
                        startY = window.params.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downAt = System.currentTimeMillis()
                        dragging = false
                        handle.isPressed = true
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                            dragging = true
                        }
                        if (dragging) {
                            window.params.x = startX + dx
                            window.params.y = startY + dy
                            clampToScreen(window.params, window.params.width, window.params.height)
                            window.userPositioned = true
                            // 記在類別上：視窗之後被重建（打字／切到診斷）也要留在這裡
                            dragMemory[window.windowCategory] =
                                window.params.x to window.params.y
                            try {
                                windowManager.updateViewLayout(window.root, window.params)
                            } catch (t: Throwable) {
                                // 視窗可能已經被系統移除
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handle.isPressed = false
                        if (!dragging && System.currentTimeMillis() - downAt > LONG_PRESS_MS) {
                            // 長按沒拖動 = 歸位（含清掉類別記憶，否則下一個視窗又會被搬回去）
                            window.userPositioned = false
                            dragMemory.remove(window.windowCategory)
                            AppLog.d(TAG, "視窗歸位（恢復自動跟隨）")
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    // ------------------------------------------------------------------ 圓點

    private fun dotSize(): Int = dp(DOT_SIZE_DP)

    /**
     * 建立圓點。
     *
     * 用「譯」這個字當圖示而不是純圓點：純圓點在使用者眼中跟 App 自己的紅點通知沒兩樣，
     * 一顆帶「譯」的小圓點才看得出這是翻譯入口。
     */
    private fun createDot(): Item {
        val view = TextView(context).apply {
            text = "譯"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DOT_TEXT_SP)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
        }
        val params = baseParams().apply {
            width = dotSize()
            height = dotSize()
        }
        return Item(view, params)
    }

    private fun applyDotState(dot: Item, state: DotState) {
        val view = dot.root as TextView
        when (state) {
            DotState.READY -> {
                view.setTextColor(DOT_READY_TEXT)
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(context.getColor(R.color.panel_accent))
                }
            }

            DotState.LOADING -> {
                view.setTextColor(context.getColor(R.color.panel_body))
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(NEUTRAL_FILL)
                    setStroke(dp(1f), STROKE_COLOR)
                }
            }

            DotState.FAILED -> {
                view.setTextColor(FAILED_COLOR)
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(NEUTRAL_FILL)
                    setStroke(dp(2f), FAILED_COLOR)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 展開視窗

    /**
     * 建立展開視窗：`[標題] [可捲動內容] [釘在底部的按鈕列]`。
     *
     * 按鈕列刻意放在 ScrollView **外面**：這樣不論譯文多長、畫面多小，
     * 「寫回」「診斷」永遠待在視窗底部，不會被文字擠到看不見的地方 ——
     * 這正是使用者回報「字打多了，寫回會沒辦法按」的根因。
     */
    private fun buildWindow(
        title: String?,
        body: String,
        actions: List<WindowAction>,
        choices: List<WindowChoice> = emptyList(),
    ): Item {
        // 標題列同時是拖曳把手，所以**一定要佔滿整個寬度**。
        // 實測踩過：用 WRAP_CONTENT 時把手只有文字那麼寬，使用者從右邊空白處按下去完全拖不動，
        // 看起來就像「這個視窗不能拖」。
        val titleView = TextView(context).apply {
            text = title.orEmpty()
            setTextColor(context.getColor(R.color.panel_label))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(36f)
            isClickable = true
            visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val bodyView = TextView(context).apply {
            text = body
            setTextColor(context.getColor(R.color.panel_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(dp(12f), dp(4f), dp(12f), dp(8f))
            // 譯文可以長到需要選取複製，所以不設 maxLines，靠外層 ScrollView 捲動
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(
                bodyView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(6f), 0, dp(6f), dp(6f))
        }

        // 可選項列（例：聊天語氣）。刻意放在按鈕列**上方**：
        // 它改變的是「這個視窗接下來怎麼翻」，不是「對這句話做一個動作」。
        val choiceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(dp(6f), 0, dp(6f), dp(4f))
            visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = chipBackground(accent = true)
            // 標題列撐滿寬度＝整條都能拖（見 titleView 的說明）；按鈕列同理，
            // 這樣「靠右對齊」才有整條可以靠。
            addView(titleView, fullWidth())
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(choiceRow, fullWidth())
            addView(buttonRow, fullWidth())
        }

        val item = Item(root, baseParams())
        item.windowTitle = title
        item.windowBody = body
        item.windowLabels = actions.map { it.label }
        item.windowChoices = choices.map { choiceKey(it) }
        item.choiceRow = choiceRow
        item.buttonRow = buttonRow
        item.choiceViews = choices.map { choice ->
            TextView(context).apply {
                text = choice.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(CHOICE_PADDING_DP), dp(5f), dp(CHOICE_PADDING_DP), dp(5f))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isClickable = true
                applyChoiceStyle(this, choice.selected)
                setOnClickListener { choice.onClick() }
                choiceRow.addView(this)
            }
        }
        item.actionViews = actions.map { action ->
            TextView(context).apply {
                text = action.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(ACTION_PADDING_DP), dp(9f), dp(ACTION_PADDING_DP), dp(9f))
                // 單行 + 省略號：按鈕列再擠也不會折成兩行。
                // 實機踩過：診斷頁 5 顆按鈕塞不下時，最後一顆「關閉」被壓成「關」／「閉」兩行，
                // 看起來像壞掉。真正讓它塞得下的是 fitActionRow()，這裡只是最後一道保險。
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(
                    if (action.primary) {
                        context.getColor(R.color.panel_accent)
                    } else {
                        context.getColor(R.color.panel_body)
                    }
                )
                if (action.primary) typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                setOnClickListener { action.onClick() }
                buttonRow.addView(this)
            }
        }
        return item
    }

    /** 把最新一輪的動作閉包綁到既有按鈕上（每次 render 的閉包都是新的）。 */
    private fun bindWindowActions(window: Item, actions: List<WindowAction>) {
        window.actionViews.forEachIndexed { index, view ->
            val action = actions.getOrNull(index) ?: return@forEachIndexed
            view.text = action.label
            view.setOnClickListener { action.onClick() }
        }
        // 按鈕數量不會在這裡變動：數量（＝標籤清單）不同時，showWindow 會直接重建整個視窗。
    }

    /**
     * 把最新一輪的可選項綁到既有的 chip 上。
     *
     * 這裡**會**更新選中狀態（跟按鈕不同）：使用者按下「熟人閒聊」之後，
     * 強調色必須當場跳到那一顆，否則他不知道自己到底選了什麼。
     */
    private fun bindWindowChoices(window: Item, choices: List<WindowChoice>) {
        window.choiceRow?.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
        window.choiceViews.forEachIndexed { index, view ->
            val choice = choices.getOrNull(index) ?: return@forEachIndexed
            view.text = choice.label
            applyChoiceStyle(view, choice.selected)
            view.setOnClickListener { choice.onClick() }
        }
        // 數量同樣不會在這裡變動（不同就整個重建，見 showWindow）。
    }

    /** 可選項的「標籤＋選中」字串，用來判斷要不要重建視窗。 */
    private fun choiceKey(choice: WindowChoice): String =
        choice.label + if (choice.selected) " *" else ""

    /** 可選項的外觀：選中的用強調色實心，沒選中的用中性底＋細框。 */
    private fun applyChoiceStyle(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            if (selected) {
                setColor(context.getColor(R.color.panel_accent))
            } else {
                setColor(NEUTRAL_FILL)
                setStroke(dp(1f), STROKE_COLOR)
            }
        }
        view.setTextColor(
            if (selected) {
                DOT_READY_TEXT
            } else {
                context.getColor(R.color.panel_body)
            },
        )
        if (selected) view.typeface = Typeface.DEFAULT_BOLD else view.typeface = Typeface.DEFAULT
    }

    /**
     * 量出視窗尺寸：先照內容量一次，超過 [maxHeight] 就把內容區縮短並改成可捲動。
     *
     * 回傳實際要套用到視窗的**高度**（寬度由呼叫端指定）。
     */
    private fun measureWindow(window: Item, width: Int, maxHeight: Int): Int {
        val root = window.root as LinearLayout
        val scroll = root.getChildAt(1) as ScrollView

        // 先讓按鈕列與可選項列自己想辦法塞進視窗寬度，再量整個視窗
        window.buttonRow?.let { fitActionRow(it, width) }
        window.choiceRow?.let { fitActionRow(it, width) }

        // 第一輪：內容不限制高度，看它自然會多高
        scroll.layoutParams = scroll.layoutParams.apply { height = LinearLayout.LayoutParams.WRAP_CONTENT }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val natural = root.measuredHeight
        if (natural <= maxHeight) return natural

        // 第二輪：把「內容區」縮到剩下的空間，其餘（標題＋按鈕列）保持不動
        val chrome = natural - scroll.measuredHeight
        val bodyHeight = (maxHeight - chrome).coerceAtLeast(dp(48f))
        scroll.layoutParams = scroll.layoutParams.apply { height = bodyHeight }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        return root.measuredHeight.coerceAtMost(maxHeight)
    }

    /**
     * 把一列（按鈕列／可選項列）塞進視窗寬度：塞不下就逐級縮小**左右** padding，
     * 最低到 [MIN_ACTION_PADDING_DP]。上下 padding 保持原樣（按鈕與 chip 不同高）。
     *
     * 為什麼非做不可？橫向 `LinearLayout` 塞不下時，**被犧牲的永遠是最後一顆**：
     * 前面的按鈕先量、各拿到自己的自然寬度，最後一顆只剩零頭，
     * 於是標籤就被折成兩行（實機：診斷頁的「關閉」變「關」／「閉」，看起來像壞掉）。
     * 實測那頁 5 顆按鈕在 1280px 寬的手機上需要約 1279px，而視窗只有 1238px。
     *
     * 判斷能不能塞下時**不能用 row.measure(AT_MOST)**：AT_MOST 會讓 LinearLayout
     * 直接把最後一顆壓扁、量出來的寬度剛好等於上限，永遠看不出「其實塞不下」。
     * 所以這裡改成逐顆用 UNSPECIFIED 量自然寬度再自己加總。
     *
     * 每次都從 [ACTION_PADDING_DP] 重算，所以重複呼叫是安全的（不會愈縮愈小）。
     */
    private fun fitActionRow(row: LinearLayout, availableWidth: Int) {
        if (row.childCount == 0) return
        var padDp = ACTION_PADDING_DP
        while (true) {
            val pad = dp(padDp)
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                child.setPadding(pad, child.paddingTop, pad, child.paddingBottom)
            }
            var total = row.paddingLeft + row.paddingRight
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                child.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                total += child.measuredWidth
            }
            if (total <= availableWidth || padDp <= MIN_ACTION_PADDING_DP) return
            padDp -= ACTION_PADDING_STEP_DP
        }
    }

    private fun hideWindow(key: String) {
        val window = windows.remove(key) ?: return
        if (window.added) {
            try {
                windowManager.removeView(window.root)
            } catch (t: Throwable) {
                // 已經不在了，忽略
            }
        }
    }

    /** 建立或更新一個文字 chip。 */
    fun show(key: String, spec: ChipSpec, reference: Bounds, placement: ChipPlacement) {
        if (reference.width <= 0) return
        if (screenWidth <= 0 || screenHeight <= 0) return

        val chip = chips.getOrPut(key) { createChip() }
        applySpec(chip, spec)
        layoutChip(key, chip, reference, placement)
    }

    /**
     * 建立或更新一個「清單」chip，例如語言選擇器。
     *
     * @param items    每一列的文字與點擊動作
     * @param selected 目前選中的項目（會用強調色標示）
     */
    fun showList(
        key: String,
        header: String,
        items: List<Pair<String, () -> Unit>>,
        selected: String?,
        reference: Bounds,
        placement: ChipPlacement,
    ) {
        if (reference.width <= 0) return
        if (screenWidth <= 0 || screenHeight <= 0) return

        // 清單 chip 的結構跟文字 chip 不同，所以直接重建
        hide(key)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
        }

        if (header.isNotBlank()) {
            list.addView(
                TextView(context).apply {
                    text = header
                    setTextColor(context.getColor(R.color.panel_label))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(dp(12f), dp(4f), dp(12f), dp(6f))
                }
            )
        }

        items.forEach { (label, action) ->
            val isSelected = label == selected
            list.addView(
                TextView(context).apply {
                    text = if (isSelected) "✓ $label" else "   $label"
                    setTextColor(
                        if (isSelected) {
                            context.getColor(R.color.panel_accent)
                        } else {
                            context.getColor(R.color.panel_body)
                        }
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    if (isSelected) typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(12f), dp(9f), dp(20f), dp(9f))
                    isClickable = true
                    setOnClickListener { action() }
                }
            )
        }

        val scroll = ScrollView(context).apply {
            addView(
                list,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = chipBackground(accent = true)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val params = baseParams()
        val chip = Chip(root, TextView(context), TextView(context), TextView(context), params)

        val maxWidth = (screenWidth * 0.7f).toInt()
        val maxHeight = (screenHeight * 0.55f).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )

        chips[key] = chip
        layoutChip(key, chip, reference, placement, measuredWidth = root.measuredWidth, measuredHeight = root.measuredHeight)
    }

    /** 收掉某個 key 的所有東西（chip、圓點、展開視窗）。 */
    fun hide(key: String) {
        hideWindow(key)

        val dot = dots.remove(key)
        if (dot != null && dot.added) {
            try {
                windowManager.removeView(dot.root)
            } catch (t: Throwable) {
                // 已經不在了，忽略
            }
        }

        val chip = chips.remove(key) ?: return
        if (chip.added) {
            try {
                windowManager.removeView(chip.root)
            } catch (t: Throwable) {
                // 已經不在了，忽略
            }
        }
    }

    fun hideAll() {
        handler.removeCallbacks(sweepRunnable)
        (chips.keys + dots.keys + windows.keys).toList().forEach { hide(it) }
    }

    /**
     * 收掉某一類別的所有展開視窗（訊息視窗／輸入框視窗）。
     *
     * 為什麼要「按類別收」而不是按 key：使用者按「關閉」或再點一次同一顆圓點時，
     * 服務端只是把 `openMessageKey` 設成 null，**根本沒有 key 可以拿來 hideWindow**。
     * 少了這個方法，那扇視窗就會永遠留在畫面上 —— 實機踩過：
     * log 有印「關閉譯文視窗」、renderInline 也照跑，但視窗就是不消失，
     * 最後只能去主畫面按「停止懸浮窗」才清得掉。
     */
    fun hideWindowCategory(category: String) {
        if (category.isBlank()) return
        windows.filterValues { it.windowCategory == category }.keys.toList().forEach { hideWindow(it) }
    }

    /**
     * 標記這一輪「還看得到的」項目，並安排稍後清掉不在名單裡的。
     * 延遲是為了避免捲動造成閃爍。
     */
    fun sweep(seen: Set<String>) {
        pendingSeen = seen
        handler.removeCallbacks(sweepRunnable)
        handler.postDelayed(sweepRunnable, SWEEP_DELAY_MS)
    }

    private fun sweepNow() {
        (chips.keys + dots.keys + windows.keys)
            .filter { it !in pendingSeen }
            .forEach { hide(it) }
    }

    // ------------------------------------------------------------------ 版面

    private fun layoutChip(
        key: String,
        chip: Chip,
        reference: Bounds,
        placement: ChipPlacement,
        measuredWidth: Int = -1,
        measuredHeight: Int = -1,
    ) {
        var chipWidth: Int
        var chipHeight: Int

        // 每次都從標準字級開始量。這一點很重要：同一顆 chip 的內容會變
        //（「翻譯中…」→ 真正的譯文），如果沿用上一次縮過的字級，字會愈縮愈小。
        applyTextSize(chip, CHIP_TEXT_SP)

        if (measuredWidth > 0 && measuredHeight > 0) {
            chipWidth = measuredWidth
            chipHeight = measuredHeight
        } else {
            // 關鍵：先算出「旁邊有多少空間」，再讓譯文用那個寬度折行。
            // 實測發現靠左的訊息泡泡通常很寬，右側剩餘空間有限，
            // 若先用固定最大寬度量測，譯文就會塞不進旁邊而退回「貼下方」——
            // 而聊天列表行距很窄，貼下方就會疊到下一個訊息上。
            val maxChipWidth = when (placement) {
                ChipPlacement.BESIDE_REFERENCE -> {
                    val gap = dp(4f)
                    val margin = dp(6f)
                    // 旁邊真正可用的空間，完全由「這則訊息實際佔多寬」推導出來，
                    // 沒有為特定機型調過的數字。
                    val available = if (isMessageOnRight(reference)) {
                        reference.left - gap - margin
                    } else {
                        screenWidth - reference.right - gap - margin
                    }
                    val minReadable = minReadableWidth()
                    val maxLimit = (screenWidth * MAX_CHIP_SCREEN_FRACTION)
                        .toInt()
                        .coerceAtLeast(minReadable)
                    available.coerceAtLeast(minReadable).coerceAtMost(maxLimit)
                }

                else -> (screenWidth * 0.78f).toInt()
            }

            fun measureNow(): Pair<Int, Int> {
                chip.root.measure(
                    View.MeasureSpec.makeMeasureSpec(maxChipWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                return chip.root.measuredWidth.coerceAtLeast(dp(24f)) to
                    chip.root.measuredHeight.coerceAtLeast(dp(20f))
            }

            val first = measureNow()
            chipWidth = first.first
            chipHeight = first.second

            // ---- 譯文比列間空隙還高的時候：自動縮字級 ----
            //
            // 實測（demo 對話）：列間空隙只有 136px，而三行譯文是 187px，物理上塞不進去。
            // 此時演算法只能在「擦到鄰居」和「跑遠兩列」之間二選一，兩個都不好看。
            // 縮一級字（13sp → 12sp）就能把譯文壓成兩行塞進去，而且還留在訊息旁邊。
            //
            // 條件寫得很保守，避免字被無謂地縮小：
            //   - 只有「內嵌譯文」（BESIDE）才做
            //   - 只有「最好的位置仍然會蓋到字」才開始縮
            //   - 找到第一個完全不蓋到的字級就停（也就是能塞進去的**最大**字級）
            //   - 怎麼縮都還是會蓋到的話，取蓋得最少的；真的一樣就維持原字級
            if (placement == ChipPlacement.BESIDE_REFERENCE) {
                var bestSp = CHIP_TEXT_SP
                var bestWidth = chipWidth
                var bestHeight = chipHeight
                var best = bestPlacement(key, reference, placement, chipWidth, chipHeight)

                if (!best.clean) {
                    var sp = CHIP_TEXT_SP - TEXT_SIZE_STEP
                    while (sp >= MIN_CHIP_TEXT_SP - 0.001f) {
                        applyTextSize(chip, sp)
                        val (width, height) = measureNow()
                        val candidate = bestPlacement(key, reference, placement, width, height)
                        if (candidate.clean || candidate.covered < best.covered) {
                            bestSp = sp
                            bestWidth = width
                            bestHeight = height
                            best = candidate
                        }
                        if (candidate.clean) break
                        sp -= TEXT_SIZE_STEP
                    }
                    // 把字級定在勝出的那一級，並採用「量測它時得到的尺寸」——
                    // 這樣後面真正用來擺位置的尺寸，跟剛剛評估過的完全是同一組。
                    applyTextSize(chip, bestSp)
                    chipWidth = bestWidth
                    chipHeight = bestHeight
                }
            }
        }

        // 把「我算好的寬度」直接寫進視窗參數，而不是留 WRAP_CONTENT。
        //
        // 為什麼非這樣不可？實測：視窗參數是 WRAP_CONTENT 時，WindowManager 加入視窗
        // 會用**自己的** MeasureSpec 重新量一次，把上面那次 AT_MOST 的上限整個丟掉，
        // 譯文於是變成「單行不折行的自然寬度」（規劃 388px、實際 531px）。
        // 一旦實際寬度大於規劃寬度：
        //   1. 側邊明明有空間卻判斷成塞不下 → 退回「貼下方」→ 蓋到下一個訊息
        //   2. 邊界夾制被高估 → 譯文跑出畫面右緣被切掉
        // 指定固定寬度之後，折行結果與規劃完全一致，「不蓋到原字」才有可能做到。
        chip.params.width = chipWidth

        if (!placeItem(key, chip.root, chip.params, chip.added, reference, placement, chipWidth, chipHeight)) {
            chips.remove(key)
        } else {
            chip.added = true
        }
    }

    /**
     * 把任何一個懸浮視窗擺到位置上（chip、圓點、展開視窗共用）。
     *
     * 為什麼要抽成一個函式？因為「擺哪裡」的邏輯（候選位置 + 重疊評分 + 邊界夾制）
     * 只有一套，而現在有三種視窗要用它。任何一種自己算座標都會再踩一次
     * 「算出來的 y 跟畫面上差一條狀態列」那種坑。
     *
     * @return true 代表視窗現在確實掛在 WindowManager 上
     */
    private fun placeItem(
        key: String,
        root: View,
        params: WindowManager.LayoutParams,
        isAdded: Boolean,
        reference: Bounds,
        placement: ChipPlacement,
        width: Int,
        height: Int,
        forcedX: Int? = null,
    ): Boolean {
        val (x, y) = computePosition(key, reference, placement, width, height, forcedX)
        params.x = x
        params.y = y

        try {
            if (isAdded) {
                windowManager.updateViewLayout(root, params)
            } else {
                windowManager.addView(root, params)
            }
        } catch (t: Throwable) {
            // 權限被撤銷、或視窗已經被系統移除
            return false
        }

        // 再用「視窗配置完成後的實際尺寸」修正一次。
        //
        // 寬度已經被我們指定死了，所以這裡正常會跟量到的一模一樣；
        // 但高度若不同，垂直置中或下方候選位置就會偏掉（實測誤差可以讓譯文壓到下一則訊息）。
        // 所以留這道保險：尺寸真的不一樣才重算。
        root.post {
            if (!root.isAttachedToWindow) return@post
            val realWidth = root.width
            val realHeight = root.height
            if (realWidth <= 0 || realHeight <= 0) return@post
            if (realWidth == width && realHeight == height) return@post

            val (fixedX, fixedY) =
                computePosition(key, reference, placement, realWidth, realHeight, forcedX)
            if (fixedX != params.x || fixedY != params.y) {
                params.x = fixedX
                params.y = fixedY
                try {
                    windowManager.updateViewLayout(root, params)
                } catch (t: Throwable) {
                    // 視窗可能已消失，忽略
                }
            }
        }
        return true
    }

    /** 一個候選位置的評估結果。 */
    private class Placement(
        val x: Int,
        val y: Int,
        /** 會蓋掉多少面積（px²）；0 代表完全沒蓋到任何文字。 */
        val covered: Int,
    ) {
        /** 完全沒蓋到任何既有文字（＝可以安心用這個位置）。 */
        val clean: Boolean get() = covered == 0
    }

    /**
     * 算出 chip 的最終位置（左上角）。
     *
     * 抽成獨立函式是因為它會被呼叫兩次：先用「量到的尺寸」放一次，
     * 再用「視窗配置完成後的實際尺寸」修正一次。兩次必須用同一套邏輯，
     * 否則修正後的位置可能反而更糟。
     */
    private fun computePosition(
        key: String,
        reference: Bounds,
        placement: ChipPlacement,
        chipWidth: Int,
        chipHeight: Int,
        forcedX: Int? = null,
    ): Pair<Int, Int> {
        val best = bestPlacement(key, reference, placement, chipWidth, chipHeight, forcedX)
        return best.x to best.y
    }

    /**
     * 從所有合理的位置裡挑一個最好的。
     *
     * 作法是把「所有合理的位置」列成候選，逐一評估**會蓋掉多少既有文字的面積**，
     * 選蓋得最少的；面積一樣時選候選順序前面的（也就是最符合這個 placement 意圖的）。
     *
     * 為什麼是這樣而不是一串 if/else？因為「旁邊到底塞不塞得下」在不同裝置、
     * 不同字級、不同語言長度下都不一樣（實測同一台平板上譯文就有 336 / 358 / 476 / 531 / 672
     * 多種寬度）。用「實際量到的矩形」互相求交集，就不必為任何機型或 App 寫死規則。
     *
     * 回傳 `covered` 是為了讓「自動縮字級」可以反覆問：「用這個字級量出來的尺寸，
     * 找得到完全不會蓋到字的位置嗎？」—— 找不到就再縮一級。
     */
    private fun bestPlacement(
        key: String,
        reference: Bounds,
        placement: ChipPlacement,
        chipWidth: Int,
        chipHeight: Int,
        forcedX: Int? = null,
    ): Placement {
        val margin = dp(6f)
        val candidates =
            placementCandidates(reference, placement, chipWidth, chipHeight, forcedX)
        // 超寬的參考矩形（例：Telegram 的整列訊息）要**把自己排除在障礙物之外**：
        // 它大部分是空白，而且泡泡位置本來就未知；把它算進去，「列內」的候選會全部
        // 被判成「蓋到字」，圓點只好被擠到列外或畫面邊緣（實機症狀：圓點不在訊息旁邊）。
        val veryWide = reference.width >= (screenWidth * 0.92f).toInt()
        val blockers = blockersFor(key, reference, skipReference = veryWide)

        val maxX = (screenWidth - chipWidth - margin).coerceAtLeast(0)
        val minY = statusBarHeight
        val maxY = (screenHeight - chipHeight - margin).coerceAtLeast(minY)

        var bestX = candidates.first().first.coerceIn(0, maxX)
        var bestY = candidates.first().second.coerceIn(minY, maxY)
        var bestCovered = Int.MAX_VALUE
        var bestScore = Long.MAX_VALUE

        candidates.forEachIndexed { index, candidate ->
            val x = candidate.first.coerceIn(0, maxX)
            val y = candidate.second.coerceIn(minY, maxY)
            val rect = Bounds(x, y, x + chipWidth, y + chipHeight)
            val covered = blockers.sumOf { overlapArea(rect, it) }
            // 完全不蓋到任何文字時只比候選順序；一旦會蓋到，就以「蓋掉的面積」為準。
            // 沒有這個分界的話，「只蓋到一點點但順序很前面」會贏過「完全不蓋但順序後面」。
            val score = if (covered == 0) index.toLong() else COVERED_PENALTY + covered
            if (score < bestScore) {
                bestScore = score
                bestCovered = covered
                bestX = x
                bestY = y
            }
        }

        return Placement(bestX, bestY, bestCovered)
    }

    /**
     * 依 placement 的意圖排出候選位置，越前面越符合意圖。
     *
     * 側邊優先、垂直置中最好看；被擋住時沿著訊息上下滑一點找空位，
     * 再不行才退到訊息的上方／下方（並貼齊訊息靠的那一側，避免超出畫面）。
     */
    private fun placementCandidates(
        reference: Bounds,
        placement: ChipPlacement,
        chipWidth: Int,
        chipHeight: Int,
        forcedX: Int? = null,
    ): List<Pair<Int, Int>> {
        val gap = dp(4f)

        // 指定貼在某一側邊緣時（Telegram：自己的訊息貼最左、對方的貼最右），
        // x 直接固定，只讓評分去挑 y（垂直置中優先，真的會蓋到字才往上／往下讓開）。
        if (forcedX != null) {
            val centeredYForced = reference.top + (reference.height - chipHeight) / 2
            return listOf(
                forcedX to centeredYForced,
                forcedX to reference.top,
                forcedX to (reference.bottom - chipHeight),
                forcedX to (centeredYForced - chipHeight),
                forcedX to (centeredYForced + chipHeight),
            )
        }
        val messageOnRight = isMessageOnRight(reference)
        val besideX = if (messageOnRight) {
            reference.left - chipWidth - gap
        } else {
            reference.right + gap
        }
        val ownSideX = if (messageOnRight) reference.right - chipWidth else reference.left
        val centeredY = reference.top + (reference.height - chipHeight) / 2

        val beside = listOf(
            centeredY,
            reference.top,
            reference.bottom - chipHeight,
            centeredY - chipHeight,
            centeredY + chipHeight,
        ).map { besideX to it }

        // 「幾乎佔滿整個畫面寬度」的參考矩形：實測 Telegram 13.0.2 就是這樣 ——
        // 整則訊息（含頭像與時間）回報成一個 1280 寬的節點，**底下沒有任何子節點**，
        // 所以我們**算不出泡泡在哪一側**。這時兩側候選都會被夾到畫面邊緣，
        // 圓點全部疊在最左邊一排（實機回報「TG 圓點位置不對」）。
        //
        // 退而求其次：放在列的**中央**。短訊息的泡泡一定在左邊或右邊，中間都是空的，
        // 所以兩個方向都「離訊息不遠」又不會壓到字。
        val veryWide = reference.width >= (screenWidth * 0.92f).toInt()
        val ownCenterX = reference.left + (reference.width - chipWidth) / 2
        val centered = listOf(
            ownCenterX to centeredY,
            ownCenterX to reference.top,
            ownCenterX to (reference.bottom - chipHeight),
        )

        val below = listOf(
            ownSideX to (reference.bottom + gap),
            reference.left to (reference.bottom + gap),
            (reference.right - chipWidth) to (reference.bottom + gap),
        )

        val above = listOf(
            ownSideX to (reference.top - chipHeight - gap),
            reference.left to (reference.top - chipHeight - gap),
            (reference.right - chipWidth) to (reference.top - chipHeight - gap),
        )

        return when (placement) {
            // 超寬的參考矩形（拿不到泡泡位置）先試「列的中央」，再退回原本的兩側／上下
            ChipPlacement.BESIDE_REFERENCE ->
                if (veryWide) centered + beside + below + above else beside + below + above

            ChipPlacement.ABOVE_REFERENCE ->
                if (veryWide) centered + above + below else above + below + beside

            ChipPlacement.BELOW_REFERENCE ->
                if (veryWide) centered + below + above else below + above + beside

            ChipPlacement.RIGHT_OF_REFERENCE -> {
                val rightX = reference.right + gap
                (if (veryWide) centered else emptyList()) + listOf(
                    rightX to reference.top,
                    rightX to centeredY,
                    rightX to (reference.bottom - chipHeight),
                ) + below + above + beside
            }
        }
    }

    /** 這則訊息是靠畫面右半邊嗎？（靠右＝我送出的，譯文要放左邊讓開） */
    private fun isMessageOnRight(reference: Bounds): Boolean =
        (reference.left + reference.right) / 2 >= screenWidth / 2

    /**
     * 目前「不可以被蓋住」的矩形：畫面上的訊息與輸入框，加上其他已經擺好的 chip
     * （譯文之間互相重疊看起來一樣很亂，所以也算進來）。
     *
     * 自己的參考矩形預設也包含在內 —— 候選位置被畫面邊界夾回來時有可能反過來壓住自己的訊息，
     * 那正是要避免的情況。
     *
     * @param skipReference true 時不放自己的矩形（用在「超寬的參考矩形」上，見 [bestPlacement]）
     */
    private fun blockersFor(
        key: String,
        reference: Bounds,
        skipReference: Boolean = false,
    ): List<Bounds> = buildList {
        if (!skipReference) add(reference)
        obstacles.forEach { if (it.isValid) add(it) }
        // 其他 chip、其他圓點、其他展開視窗都算障礙物：
        // 譯文互相重疊、或圓點壓在展開視窗上，看起來都一樣像壞掉。
        chips.forEach { (otherKey, other) -> addIfPlaced(otherKey, key, other.params, other.root, other.added) }
        dots.forEach { (otherKey, other) -> addIfPlaced(otherKey, key, other.params, other.root, other.added) }
        windows.forEach { (otherKey, other) -> addIfPlaced(otherKey, key, other.params, other.root, other.added) }
    }.distinct()

    private fun MutableList<Bounds>.addIfPlaced(
        otherKey: String,
        ownKey: String,
        params: WindowManager.LayoutParams,
        root: View,
        added: Boolean,
    ) {
        if (otherKey == ownKey || !added) return
        val width = params.width
        val height = root.height.coerceAtLeast(root.measuredHeight)
        if (width > 0 && height > 0) {
            add(Bounds(params.x, params.y, params.x + width, params.y + height))
        }
    }

    /** 兩個矩形的交集面積（像素²）。沒有交集就是 0。 */
    private fun overlapArea(a: Bounds, b: Bounds): Int {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (width > 0 && height > 0) width * height else 0
    }

    /**
     * 最小可讀寬度＝幾個字 × 譯文字級。
     *
     * 一定要乘上字級（sp），不能只用 scaledDensity：scaledDensity 只是「每 sp 幾 px」，
     * 直接拿它乘字數會得到 16px 這種荒謬的下限，等於沒有下限。
     */
    private fun minReadableWidth(): Int =
        (context.resources.displayMetrics.scaledDensity * CHIP_TEXT_SP * MIN_CHARS_PER_LINE).toInt()

    // ------------------------------------------------------------------ 建立子元件

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不可取得焦點（不搶聊天 App 的輸入焦點），但仍可接收觸控
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // 這一行是關鍵，別刪：沒有它的話，視窗的座標原點不是螢幕左上角，
                // 而是被系統列的 inset 推開之後的位置。
                //
                // 實測（Lenovo TB322FC / Android 16、狀態列高 107px）：
                // 我們算好「貼在訊息旁、垂直置中」的 y=415，畫面上實際出現在 y=522，
                // 每一顆 chip 都固定差 107 —— 剛好是狀態列高度。結果就是譯文往下壓一列，
                // 蓋住下一則訊息（包含使用者自己送出的那句）。
                // 這種整體位移用任何「擺放邏輯」都救不回來，一定要從視窗參數修。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // API 30 起改用 insets 決定視窗可用範圍，這兩者要一起處理才不會又被推開。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

    private fun chipBackground(accent: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10f).toFloat()
        setColor(if (accent) ACCENT_FILL else NEUTRAL_FILL)
        setStroke(
            dp(1f),
            if (accent) context.getColor(R.color.panel_accent) else STROKE_COLOR,
        )
    }

    private fun createChip(): Chip {
        val textView = TextView(context).apply {
            setTextColor(context.getColor(R.color.panel_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2f).toFloat(), 1f)
            maxLines = 6
        }
        val languageView = TextView(context).apply {
            setTextColor(context.getColor(R.color.panel_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), 0, dp(6f), 0)
            visibility = View.GONE
        }
        val actionView = TextView(context).apply {
            setTextColor(context.getColor(R.color.panel_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), 0, dp(2f), 0)
            visibility = View.GONE
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            addView(textView, wrap())
            addView(languageView, wrap())
            addView(actionView, wrap())
        }
        return Chip(root, textView, languageView, actionView, baseParams())
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    /** 撐滿父容器寬度（展開視窗的標題列與按鈕列要用的）。 */
    private fun fullWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun applySpec(chip: Chip, spec: ChipSpec) {
        chip.textView.text = spec.text
        chip.textView.setTypeface(null, if (spec.bold) Typeface.BOLD else Typeface.NORMAL)
        chip.textView.isClickable = spec.onTextClick != null
        chip.textView.setOnClickListener { spec.onTextClick?.invoke() }

        if (spec.languageLabel.isNullOrBlank()) {
            chip.languageView.visibility = View.GONE
            chip.languageView.setOnClickListener(null)
        } else {
            chip.languageView.text = spec.languageLabel + " ▾"
            chip.languageView.visibility = View.VISIBLE
            chip.languageView.isClickable = true
            chip.languageView.setOnClickListener { spec.onLanguageClick?.invoke() }
        }

        if (spec.actionLabel.isNullOrBlank()) {
            chip.actionView.visibility = View.GONE
            chip.actionView.setOnClickListener(null)
        } else {
            chip.actionView.text = spec.actionLabel
            chip.actionView.visibility = View.VISIBLE
            chip.actionView.isClickable = true
            chip.actionView.setOnClickListener { spec.onActionClick?.invoke() }
        }

        chip.root.background = chipBackground(spec.accent)
    }

    /**
     * 把 chip 裡所有文字套用同一個字級。
     *
     * 抽出來是因為「自動縮字級」會反覆改字級再重量（見 [layoutChip]），
     * 三個 TextView 必須永遠同步，不然 chip 會看起來像是壞掉。
     */
    private fun applyTextSize(chip: Chip, sp: Float) {
        chip.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        chip.languageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        chip.actionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    companion object {
        /**
         * 版面參數刻意全部由「裝置與內容」推導，而不是為某台機器調出來的固定值。
         *
         * 為什麼要這樣寫？因為這個 App 會裝在手機、平板、各種解析度與字級的裝置上。
         * 任何寫死的 dp 值在別的裝置上都可能太窄（譯文擠成一團）或太寬（蓋住訊息）。
         *
         * - 邊距、間距 → 用 dp()，依裝置密度換算
         * - 譯文寬度   → 由實際量到的「旁邊剩餘空間」決定，並用幾個字的寬度當下限
         * - 譯文字級   → 用 sp，會跟著系統字體大小設定變
         */
        private const val MIN_CHARS_PER_LINE = 6f

        /**
         * 譯文的標準字級（sp）。這也是 [applyTextSize] 每次重排時的起點，
         * 以及「最小可讀寬度」換算用的字級；三個地方必須一致。
         */
        private const val CHIP_TEXT_SP = 13f

        /**
         * 自動縮字級的下限。
         *
         * 為什麼要下限？因為「塞得下」不該用「字小到看不見」來換。
         * 縮到這裡還塞不下就認了，改去挑「蓋得最少」的位置。
         */
        private const val MIN_CHIP_TEXT_SP = 11f

        /** 自動縮字級的級距。 */
        private const val TEXT_SIZE_STEP = 1f

        /**
         * 候選位置「會蓋到文字」時的基礎罰分。
         *
         * 用意是讓「完全不蓋」永遠贏過「蓋一點點」：候選最多十幾個，
         * 所以只要這個值遠大於候選數量，排序就會先看有沒有蓋到、再看蓋掉多少面積。
         */
        private val COVERED_PENALTY = 1L shl 40

        /** 譯文最多佔畫面多少比例，避免在平板上出現一條超長的譯文。 */
        private const val MAX_CHIP_SCREEN_FRACTION = 0.62f

        /** 捲動時先保留一下再清掉，避免譯文閃爍。 */
        private const val SWEEP_DELAY_MS = 1200L

        /** 譯文 chip 的底色（帶透明才看得到底下的聊天內容）。 */
        private const val ACCENT_FILL = 0xF2181B22.toInt()

        /** 「點我翻譯」提示 chip 的底色。 */
        private const val NEUTRAL_FILL = 0xD9181B22.toInt()

        private const val STROKE_COLOR = 0x33FFFFFF

        // ---- 展開視窗的按鈕列 ----
        //
        // 按鈕標籤長度差很多（「語言：日本語」6 個全形字 vs「關閉」2 個），
        // 顆數也會變（譯文頁 3～4 顆、診斷頁 5 顆），所以左右 padding 不能寫死：
        // 塞不下時由 [fitActionRow] 逐級往下縮，讓每顆都擠一點，
        // 而不是讓最後一顆被壓成兩行（實機踩過「關閉」變「關」／「閉」）。
        private const val ACTION_PADDING_DP = 12f
        private const val MIN_ACTION_PADDING_DP = 5f
        private const val ACTION_PADDING_STEP_DP = 1f

        /** 可選項（聊天語氣）chip 的左右 padding 起點；塞不下時同樣由 fitActionRow 往下縮。 */
        private const val CHOICE_PADDING_DP = 10f

        // ---- 圓點（譯文入口）----
        //
        // 尺寸刻意做得小：使用者的原始抱怨是「對話框跟著飄很煩」，
        // 圓點的存在感必須低到「不看它也不干擾」，所以 24dp 就夠了（不像 chip 會佔一整條）。
        private const val DOT_SIZE_DP = 24f
        private const val DOT_TEXT_SP = 11f

        /** 圓點上「譯」字的顏色：READY 時底色是強調色，所以字要用深色才看得清楚。 */
        private const val DOT_READY_TEXT = 0xFF0F1116.toInt()

        private const val FAILED_COLOR = 0xFFFF6B6B.toInt()

        // ---- 拖曳 ----

        private const val TAG = "InlineChipManager"

        /** 標記「這一列已經綁過拖曳」，避免每次重畫都疊一層 listener。 */
        private const val DRAG_BOUND_TAG = "drag-bound"

        /** 按住超過這個時間且沒有移動 = 歸位（恢復自動跟隨）。 */
        private const val LONG_PRESS_MS = 700L
    }
}
