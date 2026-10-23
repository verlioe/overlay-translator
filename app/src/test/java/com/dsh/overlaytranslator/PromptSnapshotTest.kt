package com.dsh.overlaytranslator

import com.dsh.overlaytranslator.core.AppSettings
import com.dsh.overlaytranslator.translate.DeepSeekEngine
import com.dsh.overlaytranslator.translate.Direction
import com.dsh.overlaytranslator.translate.TranslationRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 把「評估語料」交給 **App 真正的提示詞產生器**（`DeepSeekEngine.systemPrompt`），
 * 輸出成 `app/build/eval/prompts.json`。
 *
 * 為什麼要從 Kotlin 產生而不是在 Python 那邊另外抄一份提示詞？
 * 因為抄一份就一定會跟 App 漂移；這裡產生的就是使用者手機上跑的那一份。
 * `tools/eval_prompts.py --check` 會離線驗證這份 JSON 的契約（不需要 API key），
 * `--live` 才會真的打模型、檢查輸出。
 *
 * 注意：這是一個「有副作用的測試」——它會寫檔。跑 `./gradlew test` 就會產生，
 * CI 的 eval job 直接讀它。
 */
class PromptSnapshotTest {

    private fun settingsFor(case: EvalCase) = AppSettings(
        myLanguage = case.myLanguage,
        theirLanguage = case.theirLanguage,
        tone = case.tone,
        myGender = "男性",
        contextTurns = 8,
    )

    /** 依照 `buildMessages()` 的格式組出 user 內容（上下文區塊＋最後的任務）。 */
    private fun userContent(case: EvalCase): String {
        val builder = StringBuilder()
        if (case.context.isNotEmpty()) {
            builder.append("【對話上下文】以下是同一段對話中最近的訊息，")
            builder.append("只用來幫你判斷代名詞、被省略的主語、語氣與用語習慣，請不要翻譯它們。\n")
            case.context.forEach { builder.append("對方：").append(it).append('\n') }
            builder.append('\n')
        }
        if (case.direction == Direction.DIAGNOSE_MY_OUTGOING) {
            builder.append("【要檢查的訊息】\n").append(case.text.trim())
            builder.append("\n\n請照剛才規定的【診斷】／【建議】格式輸出，不要加其他內容。")
        } else {
            builder.append("【待翻譯】\n").append(case.text.trim()).append("\n\n只輸出譯文本身。")
        }
        return builder.toString()
    }

    private fun esc(value: String): String = buildString {
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append(String.format("\\u%04x", ch.code))
                else -> append(ch)
            }
        }
    }

    @Test
    fun `dump prompts for the eval corpus`() {
        val engine = DeepSeekEngine { AppSettings() }
        val entries = EvalCorpus.cases.map { case ->
            val settings = settingsFor(case)
            val request = TranslationRequest(
                text = case.text,
                direction = case.direction,
                packageName = "eval.${case.id}",
            )
            val system = engine.systemPrompt(settings, request)
            val toneRule = engine.registerRule(case.tone)
            val user = userContent(case)

            // 離線就能檢查的契約（Python 那邊會再檢查一次，兩邊都便宜）
            assertTrue("$case.id: system prompt is empty", system.isNotBlank())
            assertTrue("$case.id: user content is empty", user.isNotBlank())
            assertTrue("$case.id: prompt too large", system.length + user.length < 24_000)

            """
            {"id":"${esc(case.id)}","direction":"${case.direction}","tone":"${esc(case.tone)}",
             "myLanguage":"${esc(case.myLanguage)}","theirLanguage":"${esc(case.theirLanguage)}",
             "expectScript":"${esc(case.expectScript)}","toneRule":"${esc(toneRule)}",
             "system":"${esc(system)}","user":"${esc(user)}"}
            """.trimIndent().replace("\n", "")
        }

        val out = File("build/eval")
        out.mkdirs()
        File(out, "prompts.json").writeText(
            """{"cases":[${entries.joinToString(",")}]}""",
            Charsets.UTF_8,
        )

        assertTrue("corpus should not be empty", EvalCorpus.cases.isNotEmpty())
        assertTrue("output file missing", File(out, "prompts.json").length() > 0)
    }
}
