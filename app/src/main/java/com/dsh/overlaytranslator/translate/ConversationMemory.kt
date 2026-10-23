package com.dsh.overlaytranslator.translate

/**
 * 每個 App 各自一份的對話記憶。
 *
 * 為什麼要按套件分開？因為 WhatsApp 的上下文不該污染 Telegram 的翻譯。
 * 為什麼只存在記憶體？因為這是短期對話上下文，不是聊天紀錄備份；
 * 我們刻意不落地儲存使用者的聊天內容。
 */
object ConversationMemory {

    /** 每個 App 最多保留幾輪。 */
    private const val MAX_TURNS_PER_PACKAGE = 60

    private val store = LinkedHashMap<String, ArrayDeque<ConversationTurn>>()

    @Synchronized
    fun record(packageName: String, turn: ConversationTurn) {
        val deque = store.getOrPut(packageName) { ArrayDeque() }
        deque.addLast(turn)
        while (deque.size > MAX_TURNS_PER_PACKAGE) {
            deque.removeFirst()
        }
    }

    @Synchronized
    fun recent(packageName: String, limit: Int): List<ConversationTurn> {
        if (limit <= 0) return emptyList()
        val deque = store[packageName] ?: return emptyList()
        return deque.toList().takeLast(limit)
    }

    /** 用來避免同一句話被重複翻譯、重複記進上下文。 */
    @Synchronized
    fun hasSeen(packageName: String, speaker: Speaker, sourceText: String): Boolean {
        val deque = store[packageName] ?: return false
        return deque.any { it.speaker == speaker && it.sourceText == sourceText }
    }

    @Synchronized
    fun clear(packageName: String? = null) {
        if (packageName == null) store.clear() else store.remove(packageName)
    }
}
