package com.dsh.overlaytranslator

import com.dsh.overlaytranslator.translate.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 原文語言偵測（譯文視窗標題那個「譯文（泰文）」）。
 *
 * 完全在本機用 Unicode 腳本範圍與常用詞判斷，不呼叫 API —— 所以可以在 JVM 上測。
 * 非拉丁字母幾乎不會錯；拉丁字母是啟發式，這裡只測「有專屬字母」這種高信心的情況。
 */
class LanguageDetectorTest {

    @Test
    fun `detects scripts by unicode range`() {
        assertEquals("泰文", LanguageDetector.detect("ทดสอบ"))
        assertEquals("日文", LanguageDetector.detect("こんにちは"))
        assertEquals("韓文", LanguageDetector.detect("안녕하세요"))
        assertEquals("俄文", LanguageDetector.detect("Привет"))
        assertEquals("中文", LanguageDetector.detect("你好"))
        assertEquals("阿拉伯文", LanguageDetector.detect("مرحبا"))
        assertEquals("希臘文", LanguageDetector.detect("γειά"))
    }

    /** 漢字＋假名時要判成日文，只有漢字才算中文。 */
    @Test
    fun `kana wins over han`() {
        assertEquals("日文", LanguageDetector.detect("日本語のテスト"))
        assertEquals("中文", LanguageDetector.detect("中文測試"))
    }

    /** 越南文有專屬字母，判斷不靠統計。 */
    @Test
    fun `detects latin languages with unique letters`() {
        assertEquals("越南文", LanguageDetector.detect("Xin chào, bạn khỏe không?"))
        assertEquals("德文", LanguageDetector.detect("Straße"))
        assertEquals("土耳其文", LanguageDetector.detect("İyi günler"))
    }

    /** 沒有專屬字母時退而用常用詞。 */
    @Test
    fun `detects english by stopwords`() {
        assertEquals("英文", LanguageDetector.detect("Hey! Are you joining the raid tonight?"))
    }

    @Test
    fun `empty or symbol only returns null`() {
        assertNull(LanguageDetector.detect(""))
        assertNull(LanguageDetector.detect("   "))
        assertNull(LanguageDetector.detect("12345"))
    }

    /** 猜不出來的拉丁字母語言要老實回一個含糊的標籤，而不是亂猜。 */
    @Test
    fun `unknown latin text still returns something`() {
        assertNotNull(LanguageDetector.detect("xyzzy plugh foobar"))
    }
}
