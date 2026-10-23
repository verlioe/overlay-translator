package com.dsh.overlaytranslator

import com.dsh.overlaytranslator.a11y.Bounds
import com.dsh.overlaytranslator.a11y.CapturedLine
import com.dsh.overlaytranslator.a11y.DotEdge
import com.dsh.overlaytranslator.a11y.ScreenTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 擷取規則的單元測試。
 *
 * 這些規則全部是純函式（不碰 Android framework、不需要裝置），所以可以在 JVM 上跑：
 * `./gradlew test`。它們是這個 App 最容易被改壞、也最難用眼睛驗的地方 ——
 * 每一條都對應一個真實踩過的坑（見 HANDOVER §0-6 ~ §0-9）。
 */
class ExtractorRulesTest {

    private fun line(text: String, width: Int = 1280, left: Int = 0): CapturedLine =
        CapturedLine(text = text, bounds = Bounds(left, 100, left + width, 200))

    // ───────────────────────── stripUiNoise ─────────────────────────

    /** Telegram 自己送出的訊息：第二行是時間＋讀取狀態，不該被當成訊息內容。 */
    @Test
    fun `telegram sent row keeps only the message`() {
        assertEquals(
            "但GPT適合審計xDDDDDD",
            ScreenTextExtractor.stripUiNoise("但GPT適合審計xDDDDDD\nSent at 15:30, Seen"),
        )
    }

    /** Telegram 對方的訊息：第二行是時間，沒有讀取狀態。 */
    @Test
    fun `telegram received row keeps only the message`() {
        assertEquals(
            "謝謝寶",
            ScreenTextExtractor.stripUiNoise("謝謝寶\nReceived at 15:29"),
        )
    }

    /** Messenger 把「名字＋訊息＋兩段操作說明」串成同一個無障礙標籤。 */
    @Test
    fun `messenger composed label drops hints and sender name`() {
        val raw = "Alice, 但GPT適合審計xDDDDDD, " +
            "雙擊可查看傳送/接收的日期和時間, 雙擊並按住可對訊息做出回應"
        assertEquals("但GPT適合審計xDDDDDD", ScreenTextExtractor.stripUiNoise(raw))
    }

    /** 普通訊息裡的逗號不能被當成「App 組的標籤」而拆掉。 */
    @Test
    fun `normal message with comma is untouched`() {
        assertEquals("OK, 明天見", ScreenTextExtractor.stripUiNoise("OK, 明天見"))
    }

    /** 長訊息剛好含 "tap to" 時不能誤殺（提示語只認開頭或很短的字串）。 */
    @Test
    fun `long message containing tap to is untouched`() {
        val raw = "I'll tap to pay tomorrow at the store, sounds good to me"
        assertEquals(raw, ScreenTextExtractor.stripUiNoise(raw))
    }

    /** 沒有任何提示語、時間列的文字不應該被動到。 */
    @Test
    fun `plain message is untouched`() {
        assertEquals("酷耶", ScreenTextExtractor.stripUiNoise("酷耶"))
    }

    // ───────────────────────── dotEdgeFor ─────────────────────────

    /** 自己送出的「整列」訊息 → 圓點貼畫面最左。 */
    @Test
    fun `sent full width row pins dot to the left edge`() {
        val row = line("我很好\nSent at 15:30, Seen")
        assertEquals(DotEdge.LEFT, ScreenTextExtractor.dotEdgeFor(row, 1280))
    }

    /** 對方送出的「整列」訊息 → 圓點貼畫面最右。 */
    @Test
    fun `received full width row pins dot to the right edge`() {
        val row = line("你好嗎\nReceived at 15:29")
        assertEquals(DotEdge.RIGHT, ScreenTextExtractor.dotEdgeFor(row, 1280))
    }

    /** 一般 App 的訊息節點本來就精準，不要干預它的位置。 */
    @Test
    fun `narrow message keeps automatic placement`() {
        val bubble = line("Hey! Are you joining the raid tonight?", width = 998)
        assertEquals(DotEdge.AUTO, ScreenTextExtractor.dotEdgeFor(bubble, 1280))
    }

    /** 沒有時間列（例如純圖片訊息）就不要猜方向。 */
    @Test
    fun `row without timestamp keeps automatic placement`() {
        val row = line("只有一行沒有時間")
        assertEquals(DotEdge.AUTO, ScreenTextExtractor.dotEdgeFor(row, 1280))
    }

    // ───────────────────────── isChrome ─────────────────────────

    @Test
    fun `read receipts are chrome`() {
        assertTrue(ScreenTextExtractor.isChrome(line("已讀")))
        assertTrue(ScreenTextExtractor.isChrome(line("Seen")))
        assertTrue(ScreenTextExtractor.isChrome(line("已讀 12:34")))
    }

    @Test
    fun `accessibility hints are chrome`() {
        assertTrue(ScreenTextExtractor.isChrome(line("Double tap it to see more")))
        assertTrue(ScreenTextExtractor.isChrome(line("雙擊可查看傳送/接收的日期和時間")))
    }

    /** Messenger 的「Delivered 10 minutes ago」被當成訊息抓出來過（實機回報）。 */
    @Test
    fun `relative time status lines are chrome`() {
        assertTrue(ScreenTextExtractor.isChrome(line("Delivered 10 minutes ago")))
        assertTrue(ScreenTextExtractor.isChrome(line("Sent 2 hours ago")))
        assertTrue(ScreenTextExtractor.isChrome(line("Seen 5 minutes ago")))
        assertTrue(ScreenTextExtractor.isChrome(line("just now")))
        assertTrue(ScreenTextExtractor.isChrome(line("已送達 10 分鐘前")))
        // 但真正的訊息不能被誤殺
        assertFalse(ScreenTextExtractor.isChrome(line("I delivered it 10 minutes ago")))
        assertFalse(ScreenTextExtractor.isChrome(line("再 10 分鐘前就到")))
    }

    @Test
    fun `date separators are chrome`() {
        assertTrue(ScreenTextExtractor.isChrome(line("October 15")))
        assertTrue(ScreenTextExtractor.isChrome(line("THU AT 21:18")))
        assertTrue(ScreenTextExtractor.isChrome(line("2026.10.15")))
        assertTrue(ScreenTextExtractor.isChrome(line("10月15日")))
    }

    @Test
    fun `real messages are not chrome`() {
        assertFalse(ScreenTextExtractor.isChrome(line("謝謝寶")))
        assertFalse(ScreenTextExtractor.isChrome(line("酷耶")))
        assertFalse(ScreenTextExtractor.isChrome(line("Perfect, see you then. Don't be late 😊")))
        assertFalse(ScreenTextExtractor.isChrome(line("https://www.instagram.com/reel/abc/")))
    }

    /** 泰式笑聲是純數字，emoji 單獨一則也是真的訊息 —— 都不能被當成雜訊。 */
    @Test
    fun `numbers and emoji only messages are not chrome`() {
        assertFalse(ScreenTextExtractor.isChrome(line("555")))
        assertFalse(ScreenTextExtractor.isChrome(line("😊")))
        assertFalse(ScreenTextExtractor.isChrome(line("酷")))
    }

    /** 只有標點的行是裝飾，不是發言。 */
    @Test
    fun `punctuation only is chrome`() {
        assertTrue(ScreenTextExtractor.isChrome(line("...")))
        assertTrue(ScreenTextExtractor.isChrome(line("——")))
    }

    /** 太長的字串（多半是把整頁內容塞成一個節點）直接丟掉。 */
    @Test
    fun `overlong text is chrome`() {
        assertTrue(ScreenTextExtractor.isChrome(line("字".repeat(5000))))
    }

    // ───────────────────────── 邊界 ─────────────────────────

    @Test
    fun `screen width zero does not crash`() {
        val row = line("我很好\nSent at 15:30, Seen")
        assertEquals(DotEdge.AUTO, ScreenTextExtractor.dotEdgeFor(row, 0))
    }

    @Test
    fun `empty text is chrome and strips to empty safely`() {
        assertTrue(ScreenTextExtractor.isChrome(line("")))
        assertEquals("", ScreenTextExtractor.stripUiNoise(""))
    }

    /** 只有時間列、沒有訊息時不可以回傳空字串以外的東西（要能安全退化）。 */
    @Test
    fun `text that is only a timestamp keeps original`() {
        assertEquals("Sent at 15:30, Seen", ScreenTextExtractor.stripUiNoise("Sent at 15:30, Seen"))
    }

}
