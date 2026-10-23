package com.dsh.overlaytranslator.translate

import com.dsh.overlaytranslator.core.AppLog
import com.dsh.overlaytranslator.core.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

interface TranslationEngine {
    suspend fun translate(request: TranslationRequest): TranslationOutcome

    /** 讀取端點支援的模型清單，用來填「模型」欄位。 */
    suspend fun listModels(): Result<List<String>>

    fun clearCache()
}

/**
 * DeepSeek（OpenAI 相容）翻譯引擎。
 *
 * 幾個刻意的設計：
 * 1. 用 org.json 而不是 kotlinx.serialization —— 少一個 Gradle plugin、少一層出錯機會，
 *    而我們只需要組一個很簡單的 JSON。
 * 2. 系統提示把「只輸出譯文」講死，並明確禁止輸出思考過程，否則懸浮窗會被廢話塞滿。
 * 3. 帶入對話上下文，讓模型能判斷代名詞與語氣 —— 這是這個 App 存在的理由。
 * 4. 有 LRU 快取，同一句話重複翻譯不會重複付費。
 */
class DeepSeekEngine(
    private val settingsProvider: () -> AppSettings,
) : TranslationEngine {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** accessOrder = true 的 LinkedHashMap 就是一個 80 筆的 LRU。 */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    override suspend fun translate(request: TranslationRequest): TranslationOutcome =
        withContext(Dispatchers.IO) { performTranslation(request) }

    override suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsProvider()
            if (settings.apiKey.isBlank()) throw IOException("請先填入 API Key")
            val url = settings.baseUrl.trim().trimEnd('/') + "/models"
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + settings.apiKey.trim())
                .get()
                .build()

            var code = 0
            var raw = ""
            client.newCall(httpRequest).execute().use { response ->
                code = response.code
                raw = response.body?.string().orEmpty()
            }
            if (code !in 200..299) throw IOException("HTTP $code｜${raw.take(300)}")

            val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
            val ids = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id", "").orEmpty()
                if (id.isNotEmpty()) ids.add(id)
            }
            ids
        }
    }

    override fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    // ---------------------------------------------------------------- 內部實作

    private fun performTranslation(request: TranslationRequest): TranslationOutcome {
        val settings = settingsProvider()
        if (settings.apiKey.isBlank()) {
            return TranslationOutcome.Failure("尚未設定 API Key，請回到主畫面填寫。")
        }
        val text = request.text.trim()
        if (text.isEmpty()) {
            return TranslationOutcome.Failure("沒有可翻譯的文字。")
        }

        val cacheKey = buildString {
            append(settings.model).append('|')
            append(settings.myLanguage).append('|')
            // 性別一定要進 key：同一個句子在「男性」與「不指定」下的正確譯文不一樣
            //（泰文的 ครับ/ค่ะ 就是最明顯的例子），共用快取會拿到錯的譯文。
            append(settings.myGender).append('|')
            // 語氣與自訂風格也一樣：改了設定就必須重新翻，不能吃到舊風格的快取。
            append(settings.tone).append('|')
            append(settings.styleNote).append('|')
            append(settings.theirLanguage).append('|')
            append(request.direction).append('|')
            append(text)
        }
        synchronized(cache) { cache[cacheKey] }?.let { cached ->
            return TranslationOutcome.Success(cached, fromCache = true)
        }

        return try {
            val translated = callChatCompletion(settings, request)
            synchronized(cache) { cache[cacheKey] = translated }
            TranslationOutcome.Success(translated, fromCache = false)
        } catch (t: Throwable) {
            AppLog.e(TAG, "翻譯失敗", t)
            TranslationOutcome.Failure(describeFailure(t))
        }
    }

    private fun callChatCompletion(settings: AppSettings, request: TranslationRequest): String {
        val payload = JSONObject().apply {
            put("model", settings.model.trim())
            put("messages", buildMessages(settings, request))
            put("stream", false)
            put("temperature", settings.temperature.toDouble())
            put("max_tokens", MAX_OUTPUT_TOKENS)
            // DeepSeek 預設會開思考模式；翻譯不需要，而且 reasoning_content 會拖慢回應。
            // 只在 DeepSeek 端點才送這個欄位，避免其他 OpenAI 相容服務因為不認得而回 400。
            if (settings.disableThinking && isDeepSeekEndpoint(settings.baseUrl)) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
        }

        val url = settings.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + settings.apiKey.trim())
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var code = 0
        var statusMessage = ""
        var raw = ""
        client.newCall(httpRequest).execute().use { response ->
            code = response.code
            statusMessage = response.message
            raw = response.body?.string().orEmpty()
        }

        if (code !in 200..299) {
            throw IOException("HTTP $code $statusMessage｜${raw.take(400)}")
        }

        val message = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")

        // 注意：當 JSON 裡是 "content": null 時，optString(name, "") 會回傳「字串 "null"」，
        // 所以必須先用 isNull() 判斷，否則懸浮窗上會顯示 "null"。
        val content =
            if (message.isNull("content")) "" else message.optString("content", "").trim()
        if (content.isNotEmpty()) return content

        // 萬一思考模式沒被關掉，有些模型會把結果放在 reasoning_content。
        val reasoning =
            if (message.isNull("reasoning_content")) {
                ""
            } else {
                message.optString("reasoning_content", "").trim()
            }
        if (reasoning.isNotEmpty()) return reasoning

        throw IOException("模型回傳了空內容：${raw.take(400)}")
    }

    private fun buildMessages(settings: AppSettings, request: TranslationRequest): JSONArray {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt(settings, request))
        )

        val context = ConversationMemory.recent(request.packageName, settings.contextTurns)
        if (context.isNotEmpty()) {
            val builder = StringBuilder()
            builder.append("【對話上下文】以下是同一段對話中最近的訊息，")
            builder.append("只用來幫你判斷代名詞、被省略的主語、語氣與用語習慣，請不要翻譯它們。\n")
            context.forEach { turn ->
                when (turn.speaker) {
                    Speaker.THEM -> builder.append("對方：").append(turn.sourceText).append('\n')
                    // 「我」送出的實際內容是譯文，這也順便讓模型知道對方語言是什麼
                    Speaker.ME -> builder.append("我：").append(turn.translatedText).append('\n')
                }
            }
            messages.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", builder.toString().trimEnd())
            )
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "上下文已理解，我會據此判斷語意與語氣。")
            )
        }

        val task = if (request.direction == Direction.DIAGNOSE_MY_OUTGOING) {
            "【要檢查的訊息】\n" + request.text.trim() +
                "\n\n請照剛才規定的【診斷】／【建議】格式輸出，不要加其他內容。"
        } else {
            "【待翻譯】\n" + request.text.trim() + "\n\n只輸出譯文本身。"
        }
        messages.put(JSONObject().put("role", "user").put("content", task))
        return messages
    }

    internal fun systemPrompt(settings: AppSettings, request: TranslationRequest): String {
        val myLanguage = settings.myLanguage.ifBlank { AppSettings.DEFAULT_MY_LANGUAGE }
        val genderRule = genderRule(settings.myGender)
        val toneRule = registerRule(settings.tone)
        val styleBlock = settings.styleNote.trim().takeIf { it.isNotEmpty() }?.let { note ->
            "\n\n【使用者自己指定的翻譯風格，優先於以上任何風格指示】\n$note"
        }.orEmpty()
        return when (request.direction) {
            Direction.DIAGNOSE_MY_OUTGOING -> diagnosePrompt(settings)

            Direction.THEIR_TEXT_TO_ME -> """
                你是一個嵌在聊天軟體裡的即時翻譯引擎。使用者正在跟外國朋友聊天，你要把「對方」傳來的訊息翻譯成$myLanguage。

                嚴格規則：
                1. 只輸出譯文本身。不要輸出原文，不要加引號，不要加「翻譯：」之類的前綴，不要加任何解釋、說明或 markdown 標記。
                2. 充分利用提供的對話上下文：代名詞（他/她/它/這個/那個）、被省略的主語、一詞多義、慣用語與雙關，都要依上下文判斷，不要逐字直譯。
                   有些語言會用文法標示**說話者的性別**（泰文的 ครับ/ค่ะ、日文的語尾、韓文的語尾…）——
                   那是判斷「對方是男是女」的重要線索，翻成$myLanguage 時要挑對應的代名詞（他／她）。
                3. 保留原本的語氣與語域。對方在撒嬌就翻得口語，對方在生氣就翻得直接，不要自行淨化、緩和或修飾。
                4. emoji、顏文字、貼圖代稱一律原樣保留，不要翻譯也不要刪除。
                5. 專有名詞、品牌名、人名、遊戲術語、程式碼、網址、@提及、#標籤 保持原樣不翻譯。
                6. 只有原文**本身已經是$myLanguage** 時才原樣輸出（或整句只是數字、網址、emoji 這種不需要翻譯的內容）。
                   其他語言一律翻成$myLanguage。**不要因為那句話是使用者自己打的就假設他看得懂而照抄原文** ——
                   使用者常常是先用$myLanguage 打字、再翻成外語送出去，回頭想看的就是「我剛剛到底送出了什麼」。
                7. 不要輸出你的思考過程。
            """.trimIndent()

            Direction.MY_TEXT_TO_THEM -> {
                val explicit = settings.theirLanguage.trim()
                // 使用者明確指定回覆語言時，那個選擇必須比上下文推斷更優先。
                // 例如對方講英文、但使用者就是想用日文回 —— 這時候不能「聰明地」翻成英文。
                val languageRule = if (explicit.isEmpty()) {
                    "對方使用的語言請從提供的對話上下文判斷：看「對方」說過的話是什麼語言，" +
                        "就翻成那個語言。若真的無法判斷，就翻成英文。"
                } else {
                    "一律翻譯成$explicit。使用者已明確指定回覆語言，" +
                        "這個選擇優先於上下文推斷 —— 即使對方講的是別的語言，也不要改變。"
                }
                """
                    你是一個嵌在聊天軟體裡的即時翻譯引擎。使用者正在跟外國朋友**聊天**，你要把「使用者（我）」寫的訊息翻譯成對方使用的語言。

                    嚴格規則：
                    1. 只輸出譯文本身。不要輸出原文，不要加引號，不要加「翻譯：」之類的前綴，不要加任何解釋、說明或 markdown 標記。
                    2. $languageRule
                    3. **最重要：要像真人在手機上打字，不要像翻譯。** 使用者回報過「對方說太假了」，所以：
                       - 用母語者在聊天室裡真的會打出來的短句。書面語、新聞體、課本例句一律不要。
                       - 不要逐字照譯中文的客套與連接詞。「其實」「我覺得」「所以說」「的話」「然後」這些
                         中文口頭禪，很多時候在對方語言裡根本就不該出現，不要硬翻。
                       - 不要用完整句構，可以省略主詞、省略助動詞、用逗號或空格斷句。
                       - 不要加中文式敬語，也不要把原文變得比原文更有禮貌或更熱情。
                       - 笑聲用當地網路用法（例如泰文的 555、5555），不要翻成「哈哈哈」的直譯。
                    4. $toneRule
                    5. $genderRule
                    6. emoji、顏文字、貼圖代稱一律原樣保留。
                    7. 專有名詞、品牌名、人名、遊戲術語、程式碼、網址、@提及、#標籤 保持原樣不翻譯。
                    8. 不要輸出你的思考過程。$styleBlock
                """.trimIndent()
            }
        }
    }

    /**
     * 語氣（親疏）的使用規則。
     *
     * 這是「太假」的主要來源：LLM 的泰文預設偏書面，而且句尾助詞的**密度**取決於親疏 ——
     * 對熟人每句都加 ครับ/ค่ะ，泰國人聽起來就是客氣到很假；反過來對長輩不加也很失禮。
     * 這件事只有使用者知道，所以做成設定；留空則讓模型從上下文（對方怎麼講話）判斷。
     */
    internal fun registerRule(tone: String): String = when (tone.trim()) {
        AppSettings.TONE_CASUAL ->
            "使用者跟對方是**熟人、可以打鬧**。請完全用朋友之間的講法：" +
                "泰文的句尾助詞用 นะ／สิ／ดิ／อ่ะ／จ้า／ป่ะ 這類，" +
                "**不要每句都加 ครับ／ค่ะ**（那是禮貌體，對朋友會顯得客氣又疏遠，正是「太假」的主因）；" +
                "原文在吐槽、開玩笑、鬧對方的時候可以用 ว่ะ／วะ 這類粗一點的講法 —— " +
                "熟人互相漏氣是正常的，**不要自動把它淨化成客氣的說法**。" +
                "日文用常體（だ／じゃん／〜だよ），韓文用 반말。用詞要短、要口語，甚至可以有點隨便。"

        AppSettings.TONE_POLITE ->
            "一般社交距離：用普通禮貌體。該加 ครับ／ค่ะ 的時候加，但不要每句都加，" +
                "也不要加得像服務業。日文用です・ます，韓文用 해요체。"

        AppSettings.TONE_FORMAL ->
            "正式禮貌（對方是長輩、客戶或不熟的人）：用完整的禮貌體與敬語，" +
                "泰文句尾確實加 ครับ／ค่ะ，日文用です・ます＋敬語，韓文用 존댓말。"

        else ->
            "**請從上下文判斷親疏，並盡量跟對方一致**：對方用常體、반말、口語助詞、沒什麼標點，" +
                "你就用同樣的隨性講法（泰文這時通常用 นะ／สิ／ดิ／จ้า，而不是每句都加 ครับ／ค่ะ）；" +
                "對方用 ครับ／ค่ะ、です・ます、존댓말，你才跟著用禮貌體。判斷不出來時，用輕鬆但不算粗魯的講法。"
    }

    /**
     * 「診斷」用的系統提示：不是翻譯，而是檢查打好的外語訊息得不得體。
     *
     * 為什麼值得獨立一組提示？因為翻譯與審查要看的東西不一樣：
     * 翻譯只要「意思對」，審查要判斷「這個關係、這個性別、這個場合這樣講會不會失禮」。
     * 使用者要的是後者，所以提示詞必須明確要求它**挑毛病**，而不是重翻一次。
     */
    private fun diagnosePrompt(settings: AppSettings): String {
        val target = settings.theirLanguage.trim().ifBlank { "對方使用的語言（請從上下文判斷）" }
        val myLanguage = settings.myLanguage.ifBlank { AppSettings.DEFAULT_MY_LANGUAGE }
        // 語氣（＝跟對方多熟）在這裡的角色跟翻譯時不同：翻譯是「怎麼講」，
        // 診斷是「拿什麼標準判斷得不得體」。留空時請模型自己從上下文判斷親疏。
        val relationshipBlock = settings.tone.trim().ifEmpty {
            "未指定 —— 請從上下文判斷親疏，並以你判斷出來的關係當標準"
        }
        return """
            你是一個嚴格的語言教練，正在幫一個外國人檢查他**準備送出**的訊息。
            目標語言：$target。

            使用者的背景：
            - 性別：${settings.myGender.trim().ifBlank { "未指定" }}
            - 與對方的關係：$relationshipBlock

            判斷「得不得體」時**一律以「與對方的關係」為準**，不要套用你自己的禮貌標準：
            - 「${AppSettings.TONE_CASUAL}」＝熟人、可以打鬧。吐槽、互相漏氣、不禮貌的玩笑、
              省略主詞、用粗一點的句尾（泰文 ว่ะ／วะ）都是**正常而且自然**的，
              **不要**把這些當成缺點、也不要建議改成客氣的講法；
              要挑的是「不夠像熟人」或「玩笑開過頭、真的會傷人」。
            - 「${AppSettings.TONE_POLITE}」＝一般社交距離，普通禮貌就夠，不要過度敬語。
            - 「${AppSettings.TONE_FORMAL}」＝正式禮貌，這裡才要求完整敬語與禮貌體。

            請依序檢查這則訊息，並且**直接指出問題**（不要只是重述內容、也不要客套）：
            1. 敬語與親疏：對這個關係來說，會不會太隨便、或客氣到很假？
            2. 說話者性別用法：句尾助詞、第一人稱、語尾有沒有用錯性別？
            3. 文法與用詞：有沒有不自然的直譯、錯誤搭配、書面語混進口語？
            4. 有沒有可能冒犯對方、或造成誤會的說法？

            輸出格式（務必遵守，不要輸出其他東西、不要用 markdown）：
            【診斷】2～4 句，**用${myLanguage}寫**（使用者是講${myLanguage}的人；
                    不要因為被檢查的那句話是${target}，就整段跟著用${target}回答）。
                    有問題就講清楚哪裡不對、為什麼；真的沒問題就說沒問題並說明理由。
            【建議】把整則訊息改寫成更自然得體的版本，**必須用${target}寫**（不要用${myLanguage}）。
                    只寫改寫後的那句話本身。若原文已經很好，就只輸出「無」。
            【建議翻譯】把上面【建議】那句話翻成${myLanguage}，讓使用者確認意思。
                    **這一段也用${myLanguage}寫**。若【建議】是「無」，這裡也輸出「無」。
        """.trimIndent()
    }

    /**
     * 說話者性別的使用規則。
     *
     * 為什麼一定要讓使用者自己指定？因為這件事「猜錯的代價很高」：
     * 泰文的句尾 ครับ（男）／ค่ะ（女）用錯，對方會直接覺得你講話很怪；
     * 日文的第一人稱與語尾、韓文的語尾也一樣。這是純文法層面的事，上下文再怎麼看都看不出來。
     */
    private fun genderRule(gender: String): String = when (gender.trim()) {
        AppSettings.GENDER_MALE ->
            "使用者是**男性**。凡是會區分說話者性別的文法，一律用男性版本：" +
                "泰文用 ครับ（不要 ค่ะ）、คับ、ว่ะ 這類男性形式，日文用男性的第一人稱與語尾" +
                "（僕／俺／だ／だぜ，依語域選），韓文用男性常用的語尾。絕對不要用女性專用形式。" +
                "**但要注意：這裡講的是「用哪一種」，不是「每句都要加」。**" +
                "泰文要不要加句尾助詞、加得多密，請照第 4 條的親疏判斷 —— " +
                "跟熟人聊天時整句都不加助詞，往往比每句加 ครับ 自然得多。"

        AppSettings.GENDER_FEMALE ->
            "使用者是**女性**。凡是會區分說話者性別的文法，一律用女性版本：" +
                "泰文用 ค่ะ／ค่า／จ้า 這類女性形式（不要 ครับ）、日文用女性的第一人稱與語尾" +
                "（私／わ／のよ，依語域選），韓文用女性常用的語尾。絕對不要用男性專用形式。" +
                "**但要注意：這裡講的是「用哪一種」，不是「每句都要加」。**" +
                "句尾助詞的密度請照第 4 條的親疏判斷，跟熟人聊天不必每句都加 ค่ะ。"

        else ->
            "如果目標語言會用文法區分說話者性別（泰文的 ครับ/ค่ะ、日文的語尾、韓文的語尾…），" +
                "請選擇中性、不標示性別的說法；真的無法避免時，以對話上下文推斷。"
    }

    private fun isDeepSeekEndpoint(baseUrl: String): Boolean =
        baseUrl.contains("deepseek", ignoreCase = true)

    private fun describeFailure(t: Throwable): String = when (t) {
        is UnknownHostException -> "連不上伺服器，請檢查網路。"
        is SocketTimeoutException -> "連線逾時，請稍後再試。"
        is IOException -> t.message?.takeIf { it.isNotBlank() } ?: "網路錯誤。"
        else -> "${t.javaClass.simpleName}: ${t.message ?: "未知錯誤"}"
    }

    companion object {
        private const val TAG = "DeepSeekEngine"
        private const val MAX_OUTPUT_TOKENS = 2048
        private const val MAX_CACHE_ENTRIES = 80
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
