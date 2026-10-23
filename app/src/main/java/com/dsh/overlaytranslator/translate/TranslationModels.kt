package com.dsh.overlaytranslator.translate

/** 對話中的發言者。 */
enum class Speaker { THEM, ME }

/**
 * 一輪對話紀錄。
 *
 * [sourceText] 永遠是「原文」，[translatedText] 永遠是「實際送到對話裡的那個版本」。
 * 這個區分很重要：判斷對方講什麼語言要靠 translatedText（我送出的），
 * 而不是我原本打的字。
 */
data class ConversationTurn(
    val speaker: Speaker,
    val sourceText: String,
    val translatedText: String,
    val at: Long = System.currentTimeMillis(),
)

/** 翻譯方向決定要用哪一組系統提示。 */
enum class Direction {
    /** 把對方傳來的訊息翻成我的語言。 */
    THEIR_TEXT_TO_ME,

    /** 把我打的訊息翻成對方的語言。 */
    MY_TEXT_TO_THEM,

    /**
     * 不是翻譯，而是**檢查**：把我打在輸入框、準備送出的外語訊息，
     * 檢查語氣、敬語／性別用法、文法與用詞是否合理。
     *
     * 為什麼需要？使用者說的：「為了怕打出來的東西還是沒禮貌」——
     * 翻譯正確不代表對人得體，尤其是泰文／日文／韓文這種看關係決定說法的語言。
     */
    DIAGNOSE_MY_OUTGOING,
}

data class TranslationRequest(
    val text: String,
    val direction: Direction,
    val packageName: String,
)

sealed interface TranslationOutcome {
    data class Success(val text: String, val fromCache: Boolean) : TranslationOutcome

    data class Failure(val message: String) : TranslationOutcome
}
