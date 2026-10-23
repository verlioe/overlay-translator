package com.dsh.overlaytranslator.core

/**
 * 語言選項。
 *
 * 為什麼要有這份清單？因為原本的設定是兩個自由輸入的文字框，使用者得自己
 * 打出「English」「日本語」這種字串。對模型來說那樣的輸入其實也夠用，
 * 但對人來說很容易打錯、也不知道可以填什麼。
 *
 * 所以設定頁與懸浮窗的語言選擇器都用這份清單做快速選取，
 * 但底層仍然是自由文字 —— 清單沒有的語言（例如克羅埃西亞語）還是可以直接打。
 */
object Languages {

    /** 回覆語言選擇器裡代表「不指定，讓模型依上下文判斷」的選項。 */
    const val AUTO = "自動判斷"

    /**
     * 常見語言。順序刻意把繁體中文與東亞語言放前面，
     * 因為這個 App 的主要使用情境是中文使用者跟外語朋友聊天。
     */
    val COMMON = listOf(
        "繁體中文",
        "简体中文",
        "English",
        "日本語",
        "한국어",
        "Français",
        "Deutsch",
        "Español",
        "Português",
        "Italiano",
        "Русский",
        "ไทย",
        "Tiếng Việt",
        "Bahasa Indonesia",
        "Bahasa Melayu",
        "Türkçe",
        "العربية",
        "हिन्दी",
    )
}
