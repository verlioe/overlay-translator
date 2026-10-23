package com.dsh.overlaytranslator.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dsh.overlaytranslator.a11y.A11yBridge
import com.dsh.overlaytranslator.core.AppLog
import kotlin.random.Random

/**
 * 示範對話：一個「假的聊天 App」，但用的是**真的** Android 元件。
 *
 * 為什麼要做這個？
 *  1. 測試時不必把真實的 LINE / WhatsApp 對話暴露出來。
 *  2. 乾淨的裝置（新平板、模擬器）通常沒有灌任何聊天 App，沒有這個就沒東西可測。
 *  3. 這裡刻意用傳統 View（TextView 氣泡 + 真的 EditText）而不是 Compose，
 *     因為真實聊天 App 就是這個結構，用它測才測得到真正的讀取與寫回行為。
 *
 * 這個 Activity 在前景時會把 A11yBridge.demoModeActive 設為 true，
 * 讓無障礙服務願意讀取「自己 App」的畫面（正常情況下會跳過自己）。
 */
class DemoChatActivity : Activity() {

    private lateinit var messageContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var composer: EditText

    private data class DemoMessage(val fromThem: Boolean, val text: String)

    private val messages = mutableListOf(
        DemoMessage(true, "Hey! Are you joining the raid tonight?"),
        DemoMessage(false, "當然，幾點開始？"),
        DemoMessage(true, "8pm server time. We're short one healer."),
        DemoMessage(false, "我可以找朋友來補"),
        DemoMessage(true, "Perfect, see you then. Don't be late \uD83D\uDE04"),
        // 最後這則是「我送出的外語訊息」，是固定的回歸測試案例：
        // 使用者先用中文打字 → 選泰文 → 寫回輸入框 → 送出，然後想知道「我剛剛到底送出了什麼」。
        // 這條路徑曾經壞掉（模型看到上下文裡的「我：ทดสอบ」就判斷「他自己打的，他看得懂」，
        // 於是照抄原文），所以提示詞第 6 條已經改成「只有原文本身已經是目標語言才可原樣輸出」。
        // 把這則留在這裡，以後改提示詞就能立刻看出有沒有又壞掉。
        DemoMessage(false, "ทดสอบ"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1116.toInt())
        }

        // Android 15 起，targetSdk 35 的 App 會被強制 edge-to-edge，內容會畫到狀態列與
        // 導覽列底下。那兩塊是「系統的」觸控區域 —— 實測結果是標題列的「返回」按鈕
        // 完全按不動（點擊被狀態列吃掉），輸入框也會被導覽列蓋住。
        // 所以要把系統列的內距補回來。
        // （Compose 的 Scaffold 會自己處理這件事，這裡是手寫 View 才得自己來。）
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // ---------------------------------------------------------- 標題列（含返回）
        // 這個 Activity 用的是 NoActionBar 主題，畫面上不會有系統返回鍵，
        // 所以必須自己放一個 —— 否則使用者會被卡在示範對話裡出不來。
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(8f), dp(16f), dp(2f))
                addView(
                    TextView(this@DemoChatActivity).apply {
                        text = "‹ 返回"
                        setTextColor(0xFF7C9CFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                        isClickable = true
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(14f).toFloat()
                            setColor(0x1FFFFFFF)
                        }
                        setOnClickListener { finish() }
                    }
                )
                addView(
                    TextView(this@DemoChatActivity).apply {
                        text = "示範對話"
                        setTextColor(0xFFFFFFFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(10f), 0, 0, 0)
                    }
                )
            }
        )
        root.addView(
            TextView(this).apply {
                text = "這不是真實聊天。用來驗證整條流程：\n" +
                    "① 先在主畫面啟動懸浮窗 → ② 回這裡按「模擬對方傳來訊息」→ " +
                    "③ 懸浮球會亮紅點，點它看譯文 → ④ 在最下面輸入框打字，用「翻譯我的輸入」與「寫回輸入框」。"
                setTextColor(0xFF9AA3B2.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(16f), 0, dp(16f), dp(10f))
            }
        )

        // ---------------------------------------------------------- 訊息區
        messageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        scrollView = ScrollView(this).apply {
            addView(
                messageContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        // ---------------------------------------------------------- 操作按鈕
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
                addView(
                    Button(this@DemoChatActivity).apply {
                        text = "模擬對方傳來訊息"
                        setOnClickListener { appendMessage(true, randomIncoming()) }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    Button(this@DemoChatActivity).apply {
                        text = "模擬我送出"
                        setOnClickListener {
                            val text = composer.text.toString().trim()
                            if (text.isNotEmpty()) {
                                appendMessage(false, text)
                                composer.setText("")
                            }
                        }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        )

        // ---------------------------------------------------------- 輸入框
        composer = EditText(this).apply {
            hint = "在這裡打字，再用懸浮窗翻譯並寫回"
            setTextColor(0xFFEDEFF5.toInt())
            setHintTextColor(0xFF6B7280.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(0xFF232733.toInt())
            }
        }
        root.addView(
            composer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(12f), dp(4f), dp(12f), dp(12f))
            },
        )

        setContentView(root)

        // 自動化測試入口：`am start -n .../.ui.DemoChatActivity --es composer "測試"`
        // 就能把文字直接塞進輸入框。
        //
        // 為什麼需要這個？因為 `adb shell input text` 在本機的 Gboard／Lenovo 手寫輸入法下
        // 會被轉成中文亂碼（連停用兩個輸入法都不一定有效），導致「翻譯我的輸入 → 寫回」
        // 這條路徑幾乎沒辦法自動驗證。直接塞字是最確定性的做法。
        intent?.getStringExtra(EXTRA_COMPOSER)?.let { composer.setText(it) }

        messages.forEach { renderMessage(it) }

        // 系統文字的回歸測試固定案例（**不應該**出現圓點、也不應該被翻譯）。
        //
        // 為什麼要放在示範對話裡？實機回報過兩次：
        //   「Telegram 翻譯到已讀」「Messenger 翻譯到 Double tap it to …」
        // 那些是聊天 App 自己畫的狀態／提示，不是任何人的發言；擷取端現在有對應的
        // 過濾（見 ScreenTextExtractor 的 STATUS_TEXT_PATTERNS / HINT_TEXT_PATTERNS）。
        // 把這兩種字串擺在這裡，以後改擷取邏輯時一眼就能看出有沒有又跑出來。
        renderSystemFixture("已讀")
        renderSystemFixture("Double tap it to see more")
    }

    /**
     * 畫一行「系統文字」——外觀刻意跟真的訊息泡泡不同（灰字、沒有泡泡底色），
     * 就像 Telegram 的已讀或 Messenger 的操作提示。
     */
    private fun renderSystemFixture(text: String) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(1f), dp(14f), dp(1f))
        }
        wrapper.addView(
            TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(0xFF8A8F9C.toInt())
                setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            }
        )
        messageContainer.addView(wrapper)
    }

    override fun onResume() {
        super.onResume()
        A11yBridge.demoModeActive = true
        AppLog.d(TAG, "示範對話進入前景，無障礙服務現在可以讀取本 App")
    }

    override fun onPause() {
        A11yBridge.demoModeActive = false
        super.onPause()
    }

    // ------------------------------------------------------------------ 內部

    private fun appendMessage(fromThem: Boolean, text: String) {
        messages.add(DemoMessage(fromThem, text))
        renderMessage(DemoMessage(fromThem, text))
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun renderMessage(message: DemoMessage) {
        val bubble = TextView(this).apply {
            text = message.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            setTextColor(if (message.fromThem) 0xFFEDEFF5.toInt() else 0xFF10131A.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f).toFloat()
                setColor(if (message.fromThem) 0xFF232733.toInt() else 0xFF7C9CFF.toInt())
            }
            // 讓無障礙節點帶著清楚的文字
            contentDescription = null
        }

        val maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (message.fromThem) Gravity.START else Gravity.END
            setPadding(0, dp(3f), 0, dp(3f))
        }
        wrapper.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                if (message.text.length > 24) {
                    // 長訊息才限制寬度，短訊息讓它自然貼合
                    this.width = maxWidth
                }
            },
        )
        messageContainer.addView(wrapper)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun randomIncoming(): String = INCOMING_SAMPLES[Random.nextInt(INCOMING_SAMPLES.size)]

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "DemoChatActivity"

        /** 自動化測試用：把文字直接塞進輸入框（見 [onCreate] 的說明）。 */
        const val EXTRA_COMPOSER = "composer"

        private val INCOMING_SAMPLES = listOf(
            "Sorry, I'm running about ten minutes late.",
            "この前の件、どうなった？",
            "Tu veux qu'on se retrouve devant la gare ?",
            "That boss fight was insane, we barely made it",
            "¿Puedes enviarme el enlace otra vez?",
            "Ok let me check with the others and get back to you",
            // 非拉丁文字也要能翻。泰文沒有空白分詞，這種語言最容易在折行與量測上出問題。
            "สวัสดีครับ พรุ่งนี้ว่างไหม",
        )
    }
}
