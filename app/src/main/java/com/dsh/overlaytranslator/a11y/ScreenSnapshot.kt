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
/**
 * 這個套件是「系統畫布」嗎？（桌面、系統 UI、設定、權限對話框…）
 *
 * 這些畫面一定會被抓到一堆文字（實測桌面抓到 12 行：App 名稱、時鐘…），
 * 但**不可能是聊天**。使用者的描述是「換畫面圓點不會不見」——
 * 實際上是圓點在桌面／系統畫面上被長出來。
 *
 * 這裡只列「絕對不可能是聊天」的系統套件；第三方 App 一律不動，
 * 免得像 Messenger 這種輸入框不是 EditText（Compose 寫的）的 App 被誤殺。
 */
private fun isSystemSurface(packageName: String): Boolean {
    val p = packageName.lowercase()
    return p == "android" ||
        p.contains("launcher") ||
        p.contains("systemui") ||
        p.contains("permissioncontroller") ||
        p.startsWith("com.android.settings") ||
        p.startsWith("com.android.systemui")
}

/**
 * 這張快照看起來像「聊天畫面」嗎？
 *
 * 判斷順序：
 *  1. 系統畫布（桌面／系統 UI／設定）→ 一定不是。
 *  2. 有輸入框 → 是（幾乎所有聊天 App 都有）。
 *  3. 有「整列」型訊息 → 是（Telegram 把整則訊息回報成滿版節點）。
 *  4. 其他情況（例如 Compose 寫的 Messenger，輸入框不是 EditText）：
 *     只要擷取到 ≥2 行有座標的內容，就照舊顯示 —— 這是改動前的行為，不算退步。
 */
fun ScreenSnapshot.looksLikeChatScreen(screenWidth: Int): Boolean {
    if (isSystemSurface(packageName)) return false
    if (composerBounds?.isValid == true) return true
    if (screenWidth > 0 &&
        incoming.any { it.bounds.isValid && it.bounds.width >= screenWidth * 0.91f }
    ) {
        return true
    }
    return incoming.count { it.bounds.isValid } >= 2
}

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
