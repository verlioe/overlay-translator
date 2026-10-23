package com.dsh.overlaytranslator.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dsh.overlaytranslator.R
import kotlin.math.abs

/** 懸浮面板要顯示的所有狀態。UI 完全由這個資料類別驅動。 */
data class PanelState(
    val sourceLabel: String = "對方訊息",
    val sourceText: String = "",
    val outputLabel: String = "譯文",
    val outputText: String = "",
    val status: String = "",
    val busy: Boolean = false,
    val candidates: List<String> = emptyList(),
    val showCandidates: Boolean = false,
    val canWriteBack: Boolean = false,
    val canUndo: Boolean = false,
)

/** 面板把使用者操作往上報給懸浮窗服務。 */
interface OverlayPanelListener {
    /** 短按懸浮球：內嵌模式＝翻譯這一頁；面板模式＝展開/收起面板。 */
    fun onBubbleTap()

    /** 長按懸浮球：打開面板（設定、停止、最近擷取）。 */
    fun onBubbleLongPress()

    /** 手指剛開始拖曳時呼叫一次，讓服務記住起始位置。 */
    fun onBubbleDragStart()

    /** [totalDx]/[totalDy] 是「自從手指按下」以來的總位移，不是相鄰兩次的增量。 */
    fun onBubbleDrag(totalDx: Float, totalDy: Float)
    fun onBubbleDragEnd()
    fun onTranslateIncoming()
    fun onTranslateOutgoing()
    fun onWriteBack()
    fun onCopyOutput()
    fun onUndo()
    fun onOpenApp()
    fun onCollapse()
    fun onSelectCandidate(text: String)
}

/**
 * 懸浮窗的介面。刻意使用原生 View 而不是 Compose。
 *
 * 原因：Compose 要掛進 WindowManager 必須自己提供 ViewTreeLifecycleOwner、
 * ViewTreeSavedStateRegistryOwner 與 ViewTreeViewModelStoreOwner，任何一個接錯，
 * 服務就會在掛上視窗的瞬間崩潰。懸浮窗是這個 App 的核心功能，而面板本身只是
 * 「幾行文字 + 幾個按鈕」，用原生 View 換取絕對的可預測性是值得的。
 * （主畫面是 Activity 裡的 Compose，那裡完全沒有這個顧慮。）
 */
class OverlayPanelView(
    context: Context,
    private val listener: OverlayPanelListener,
) : FrameLayout(context) {

    // ---- 子元件（在 init 內建立） ----
    private val bubble: FrameLayout
    private val badge: View
    private val panel: LinearLayout
    private val statusText: TextView
    private val sourceLabelView: TextView
    private val sourceTextView: TextView
    private val outputLabelView: TextView
    private val outputTextView: TextView
    private val candidateTitle: TextView
    private val candidateContainer: LinearLayout
    private val writeBackButton: TextView
    private val undoButton: TextView
    private val translateIncomingButton: TextView
    private val translateOutgoingButton: TextView

    // ---- 狀態 ----
    private var expanded = false
    private var lastCandidates: List<String> = emptyList()

    // ---- 拖曳狀態：必須宣告在 init 之前，因為 init 會用到 bubbleTouchListener ----
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        listener.onBubbleLongPress()
    }

    private val bubbleTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                longPressFired = false
                downRawX = event.rawX
                downRawY = event.rawY
                downAt = SystemClock.uptimeMillis()
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    // 開始拖曳就不算長按了
                    handler.removeCallbacks(longPressRunnable)
                    listener.onBubbleDragStart()
                }
                if (dragging) {
                    listener.onBubbleDrag(dx, dy)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    listener.onBubbleDragEnd()
                } else if (!longPressFired && SystemClock.uptimeMillis() - downAt < 500L) {
                    listener.onBubbleTap()
                }
                dragging = false
                true
            }

            else -> false
        }
    }

    init {
        // ------------------------------------------------------------ 懸浮球
        badge = View(context).apply {
            background = circleDrawable(colorOf(R.color.panel_badge))
            visibility = GONE
            layoutParams = LayoutParams(dp(11f), dp(11f), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(2f)
                marginEnd = dp(2f)
            }
        }

        bubble = FrameLayout(context).apply {
            background = circleDrawable(colorOf(R.color.bubble_idle))
            elevation = dp(6f).toFloat()
            layoutParams = LayoutParams(dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP))
            addView(
                TextView(context).apply {
                    text = "譯"
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT,
                    )
                }
            )
            addView(badge)
            setOnTouchListener(bubbleTouchListener)
        }
        addView(bubble)

        // ------------------------------------------------------------ 面板內容
        statusText = label("", R.color.panel_accent, 12f)
        sourceLabelView = label("對方訊息", R.color.panel_label, 11f)
        sourceTextView = bodyText(R.color.panel_source)
        outputLabelView = label("譯文", R.color.panel_label, 11f)
        outputTextView = bodyText(R.color.panel_body)

        translateIncomingButton = chip("翻譯對方訊息") { listener.onTranslateIncoming() }
        translateOutgoingButton = chip("翻譯我的輸入") { listener.onTranslateOutgoing() }
        writeBackButton = chip("寫回輸入框") { listener.onWriteBack() }
        undoButton = chip("復原") { listener.onUndo() }
        val copyButton = chip("複製譯文") { listener.onCopyOutput() }

        candidateTitle = label("最近擷取（點一下改翻這一行）", R.color.panel_label, 11f)
        candidateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            addView(statusText)
            addView(sourceLabelView)
            addView(sourceTextView)
            addView(outputLabelView)
            addView(outputTextView)
            addView(row(translateIncomingButton, translateOutgoingButton))
            addView(row(writeBackButton, copyButton))
            addView(row(undoButton))
            addView(candidateTitle)
            addView(candidateContainer)
        }

        val scroll = MaxHeightScrollView(context, dp(MAX_PANEL_HEIGHT_DP)).apply {
            addView(
                scrollContent,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(colorOf(R.color.panel_background))
                setStroke(dp(1f), colorOf(R.color.panel_stroke))
            }
            elevation = dp(8f).toFloat()
            visibility = GONE
            layoutParams = LayoutParams(dp(PANEL_WIDTH_DP), LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(BUBBLE_SIZE_DP + 8)
            }
            addView(header())
            addView(scroll)
        }
        addView(panel)
    }

    // ------------------------------------------------------------------ 對外 API

    fun render(state: PanelState) {
        statusText.text = state.status
        statusText.visibility = if (state.status.isBlank()) GONE else VISIBLE

        sourceLabelView.text = state.sourceLabel
        sourceTextView.text = state.sourceText.ifBlank { "（還沒抓到文字）" }
        outputLabelView.text = state.outputLabel
        outputTextView.text = state.outputText.ifBlank {
            if (state.busy) "翻譯中…" else "（譯文會顯示在這裡）"
        }

        translateIncomingButton.isEnabled = !state.busy
        translateOutgoingButton.isEnabled = !state.busy
        writeBackButton.isEnabled = !state.busy
        writeBackButton.visibility = if (state.canWriteBack) VISIBLE else GONE
        undoButton.visibility = if (state.canUndo) VISIBLE else GONE

        val showList = state.showCandidates && state.candidates.size > 1
        candidateTitle.visibility = if (showList) VISIBLE else GONE
        candidateContainer.visibility = if (showList) VISIBLE else GONE
        if (showList) {
            if (state.candidates != lastCandidates) {
                lastCandidates = state.candidates
                candidateContainer.removeAllViews()
                state.candidates.reversed().forEach { line ->
                    candidateContainer.addView(
                        chip(preview(line)) { listener.onSelectCandidate(line) }
                    )
                }
            }
        } else {
            lastCandidates = emptyList()
        }
    }

    fun setBadge(visible: Boolean) {
        badge.visibility = if (visible) VISIBLE else GONE
    }

    /**
     * 切換懸浮球的「即時翻譯中」外觀。
     *
     * 關閉＝品牌藍，開啟＝綠色並加上一圈光暈。
     * 這個狀態必須一眼可辨 —— 因為開啟時每一則訊息都會產生 API 費用。
     */
    fun setActive(active: Boolean) {
        bubble.background = circleDrawable(
            colorOf(if (active) R.color.bubble_active else R.color.bubble_idle)
        ).apply {
            if (active) setStroke(dp(3f), 0x8000C853.toInt())
        }
    }

    var isExpanded: Boolean
        get() = expanded
        set(value) {
            if (expanded == value) return
            expanded = value
            panel.visibility = if (value) VISIBLE else GONE
        }

    // ------------------------------------------------------------------ 建立子元件的工具

    private fun header(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f), dp(10f), dp(8f), dp(6f))
        addView(
            TextView(context).apply {
                text = "懸浮翻譯"
                setTextColor(colorOf(R.color.panel_title))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        addView(chip("設定") { listener.onOpenApp() })
        addView(chip("收起") { listener.onCollapse() })
    }

    private fun label(text: String, colorRes: Int, sizeSp: Float): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(colorOf(colorRes))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setPadding(0, dp(8f), 0, dp(2f))
        }

    private fun bodyText(colorRes: Int): TextView = TextView(context).apply {
        setTextColor(colorOf(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextIsSelectable(true)
        setLineSpacing(dp(2f).toFloat(), 1f)
        setPadding(0, dp(2f), 0, dp(4f))
    }

    private fun chip(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(colorOf(R.color.panel_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10f).toFloat()
                setColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                        intArrayOf(
                            colorOf(R.color.panel_button_pressed),
                            colorOf(R.color.panel_button),
                        ),
                    )
                )
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6f), 0, 0)
        views.forEachIndexed { index, view ->
            val params = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) params.marginStart = dp(6f)
            addView(view, params)
        }
    }

    private fun circleDrawable(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun preview(text: String): String =
        if (text.length <= 30) text else text.take(30) + "…"

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /** 讓 dp(BUBBLE_SIZE_DP) 這種整數常數也能直接用，不必到處補 .toFloat()。 */
    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun colorOf(resId: Int): Int = context.getColor(resId)

    /**
     * ScrollView 沒有 maxHeight，這裡自己加一個：
     * 內容短的時候面板貼合內容，內容長的時候最高停在 maxHeight 並開始捲動。
     */
    private class MaxHeightScrollView(
        context: Context,
        private val maxHeightPx: Int,
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    companion object {
        const val BUBBLE_SIZE_DP = 56
        const val PANEL_WIDTH_DP = 296
        const val MAX_PANEL_HEIGHT_DP = 360

        /** 按住多久算長按。 */
        private const val LONG_PRESS_MS = 550L
    }
}
