package com.dsh.overlaytranslator.a11y

/**
 * 螢幕上的一塊矩形區域。
 *
 * 為什麼不直接用 `android.graphics.Rect`？因為 Rect 是可變物件，
 * 放進 data class 之後很容易不小心被別人改掉，造成極難追的 bug。
 * 這裡用不可變的簡單資料類別，而且它是純 Kotlin，不需要 Android 執行環境就能寫單元測試。
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/**
 * 圓點要固定貼在畫面哪一側的邊緣。
 *
 * 為什麼需要這個？有些聊天 App（實測 Telegram 13.0.2）把整則訊息回報成「一個佔滿畫面寬度的
 * 節點、底下沒有子節點」，泡泡本身的左右位置**根本不在無障礙樹裡**。這時與其猜泡泡在哪裡
 * （猜錯就會壓到字），不如照使用者的要求：自己的訊息圓點貼最左、對方的貼最右 ——
 * 位置固定、可預期，而且永遠不會蓋到任何文字。
 */
enum class DotEdge { AUTO, LEFT, RIGHT }

/**
 * 從無障礙節點樹上撈出來的一行文字。
 *
 * @param text   文字內容
 * @param viewId 資源 ID（例如 com.whatsapp:id/message_text）。很多 App 沒有，所以只當加分項
 * @param order  在樹狀結構中的走訪順序
 * @param bounds 這個節點在**螢幕座標**上的位置。內嵌譯文模式靠它把譯文貼在訊息旁邊
 * @param dotEdge 圓點是否要固定貼在某一側邊緣（預設 AUTO＝照矩形自己算）
 */
data class CapturedLine(
    val text: String,
    val viewId: String = "",
    val order: Int = 0,
    val bounds: Bounds = Bounds.EMPTY,
    val dotEdge: DotEdge = DotEdge.AUTO,
)

/**
 * 某一個瞬間、某一個 App 的畫面快照。
 *
 * @param incoming       黑名單過濾後、「可能是對方訊息」的文字，依畫面上到下排序（越後面越新）
 * @param composerText   輸入框裡目前打的字，null 代表畫面上沒有輸入框
 * @param composerBounds 輸入框在螢幕上的位置（內嵌模式的「翻譯我的輸入」要貼在它上方）
 */
data class ScreenSnapshot(
    val packageName: String,
    val capturedAt: Long,
    val incoming: List<CapturedLine>,
    val composerText: String?,
    val composerBounds: Bounds? = null,
) {
    /** 最可能的最新一則對方訊息。 */
    val latestIncoming: String? get() = incoming.lastOrNull()?.text

    val composer: String get() = composerText.orEmpty()

    val hasAnything: Boolean get() = incoming.isNotEmpty() || composer.isNotBlank()
}
