package com.dsh.overlaytranslator.translate

/**
 * 猜「這段外文是什麼語言」，只給畫面顯示用（例：譯文視窗標題顯示「譯文（泰文）」）。
 *
 * ## 為什麼不在本機猜，而不是叫模型判斷？
 *
 * 叫模型判斷要多一次 API 呼叫（花錢、要等好幾秒），而這個標籤只是讓使用者一眼確認用的。
 * 而且**非拉丁字母的語言靠文字腳本就能幾乎百分之百確定**（泰文、日文假名、韓文、
 * 西里爾字母、阿拉伯文…）——真正不確定的只有拉丁字母那一群，那裡再用
 * 「專屬字母 + 常用詞」啟發式猜，猜不出來就老實說「拉丁字母語言」，
 * 不要硬掰一個錯的語言名稱。
 *
 * 回傳值是**繁體中文的語言名稱**（使用者看的中文），猜不出來回 null。
 */
object LanguageDetector {

    /** 常見外文的顯示名稱（也用在設定頁的語言清單，所以字串要跟那裡一致）。 */
    fun detect(text: String): String? {
        val sample = text.trim()
        if (sample.isEmpty()) return null

        scriptGuess(sample)?.let { return it }
        return latinGuess(sample)
    }

    // ------------------------------------------------------------------ 文字腳本

    /**
     * 靠 Unicode 範圍判斷。只要出現該腳本的字就算 —— 這些腳本不會混用，
     * 所以看到一個泰文字就可以確定是泰文。
     *
     * 順序有意義：**假名要先於漢字判斷**，因為日文句子裡同時有漢字；
     * 反過來說「只有漢字、沒有假名」的句子在中文與日文之間本來就無法區分，
     * 一律當中文（這是保守的選擇，錯了也只是標籤不精確）。
     */
    private fun scriptGuess(t: String): String? {
        fun has(range: IntRange) = t.any { it.code in range }

        if (has(0x3040..0x30FF)) return "日文"          // 平假名／片假名
        if (has(0xAC00..0xD7AF) || has(0x1100..0x11FF)) return "韓文"   // 諺文
        if (has(0x0E00..0x0E7F)) return "泰文"
        if (has(0x0E80..0x0EFF)) return "寮文"
        if (has(0x1000..0x109F)) return "緬甸文"
        if (has(0x1780..0x17FF)) return "高棉文"
        if (has(0x0400..0x04FF)) return "俄文"          // 西里爾字母
        if (has(0x0600..0x06FF) || has(0x0750..0x077F)) return "阿拉伯文"
        if (has(0x0590..0x05FF)) return "希伯來文"
        if (has(0x0370..0x03FF)) return "希臘文"
        if (has(0x0900..0x097F)) return "印地文"        // 天城文
        if (has(0x0C00..0x0C7F)) return "泰盧固文"
        if (has(0x0B80..0x0BFF)) return "坦米爾文"
        if (has(0x4E00..0x9FFF) || has(0x3400..0x4DBF)) return "中文"   // 漢字（無假名）
        return null
    }

    // ------------------------------------------------------------------ 拉丁字母

    /**
     * 拉丁字母只有 26 個基本字母，所以要看「專屬字母」與「常用詞」。
     * 順序：先看只有某個語言在用的字母（最可靠），再看常用詞。
     */
    private fun latinGuess(t: String): String? {
        val lower = t.lowercase()

        // ① 專屬字母。只挑「幾乎只有那個語言在用」的，避免把法文的 ê 當成越南文。
        val byLetter = listOf(
            "越南文" to "ăâđơưạảấầẩẫậắằẳẵặếềểễệốồổỗộớờởỡợụủứừửữựỳỵỷỹ",
            "土耳其文" to "ğışİı",   // İ／ı 是土耳其文專屬（Ğ 大寫也算）
            "波蘭文" to "łąężźćń",
            "捷克文" to "řěů",
            "德文" to "ß",
            "西班牙文" to "ñ¿¡",
            "葡萄牙文" to "ãõ",
            "北歐語言" to "øåæ",
        )
        byLetter.forEach { (language, letters) ->
            // 同時比對「原字元」與「小寫」：土耳其文的 İ 小寫會變成 ASCII 的 i（不是 ı），
            // 只比小寫就永遠命不中 İ。
            if (t.any { it in letters || it.lowercaseChar() in letters }) return language
        }

        // ② 常用詞（用字邊界比對，避免 "no" 命中 "now"）。
        val words = Regex("[\\p{L}']+").findAll(lower).map { it.value }.toList()
        if (words.isNotEmpty()) {
            val scores = STOPWORDS.map { (language, stops) ->
                language to words.count { it in stops }
            }.sortedByDescending { it.second }
            val best = scores.first()
            // 至少命中兩個常用詞才敢下判斷；單詞句（"hola"、"merci"）例外。
            val threshold = if (words.size <= 2) 1 else 2
            if (best.second >= threshold) return best.first
        }

        // ③ 完全沒有重音字母的純 ASCII 句子 → 最可能是英文。
        //    有其他重音字母但認不出來 → 誠實說「拉丁字母語言」，不要亂猜一個。
        val hasAccent = t.any { it.isLetter() && it.code > 0x7F }
        if (!hasAccent && t.any { it.isLetter() }) return "英文"
        return if (hasAccent) "拉丁字母語言" else null
    }

    /**
     * 常用詞表。刻意只放「高頻且專屬」的詞：
     * 像 "no"（英／西）、"a"（英／西／葡）這種共用的不要放，否則會互相拉分。
     */
    private val STOPWORDS = listOf(
        "英文" to setOf(
            "the", "and", "you", "your", "is", "are", "was", "were", "to", "of", "it", "that",
            "this", "what", "for", "not", "have", "has", "with", "will", "would", "there",
            "they", "them", "from", "about", "just", "can", "dont", "im", "its", "got",
        ),
        "西班牙文" to setOf(
            "que", "de", "la", "el", "los", "las", "es", "en", "un", "una", "por", "con", "para",
            "pero", "como", "muy", "está", "estoy", "tengo", "quiero", "gracias", "hola", "bien",
        ),
        "法文" to setOf(
            "le", "les", "des", "une", "est", "et", "je", "tu", "vous", "pour", "pas", "que",
            "dans", "avec", "sur", "mais", "très", "c'est", "j'ai", "merci", "bonjour", "bien",
        ),
        "德文" to setOf(
            "der", "die", "das", "und", "ist", "nicht", "ich", "du", "wir", "mit", "ein", "eine",
            "auch", "aber", "sehr", "haben", "wie", "was", "danke", "hallo", "gut", "guten",
            "geht", "dir", "mir", "es", "hast", "bist", "noch", "schon", "oder", "wenn", "bitte",
            "nein", "ja", "morgen", "heute", "tschüss",
        ),
        "義大利文" to setOf(
            "che", "di", "il", "non", "sono", "per", "una", "come", "questo", "questo", "molto",
            "grazie", "ciao", "bene", "anche", "perché", "dove",
        ),
        "葡萄牙文" to setOf(
            "não", "uma", "você", "está", "obrigado", "obrigada", "para", "com", "isso", "muito",
            "também", "porque", "olá", "bem", "vou",
        ),
        "印尼文" to setOf(
            "yang", "dan", "tidak", "saya", "kamu", "untuk", "dengan", "ini", "itu", "apa",
            "sudah", "belum", "bisa", "terima", "kasih", "selamat", "makan",
        ),
        "土耳其文" to setOf(
            "bir", "ve", "bu", "için", "çok", "değil", "ben", "sen", "ne", "nasıl", "teşekkür",
            "merhaba", "iyi", "var", "ama", "evet", "hayır",
        ),
        "越南文" to setOf(
            "không", "của", "và", "là", "tôi", "bạn", "có", "được", "này", "gì", "cảm", "ơn",
            "chào", "rồi", "nhé", "muốn", "ăn",
        ),
        "馬來文" to setOf(
            "saya", "awak", "tidak", "untuk", "dengan", "ini", "apa", "sudah", "belum",
            "terima", "kasih", "selamat", "makan",
        ),
    )
}
