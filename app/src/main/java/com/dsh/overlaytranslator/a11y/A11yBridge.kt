package com.dsh.overlaytranslator.a11y

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 無障礙服務 ↔ 懸浮窗之間的單例橋樑。
 *
 * 為什麼用 object 而不是把服務 bind 給懸浮窗？因為懸浮窗和無障礙服務是兩個獨立的
 * 系統元件，任一個都可能先被使用者開關。用一個行程內的橋樑可以讓兩邊互不依賴，
 * 也不會發生「其中一邊死掉另一邊跟著崩」的情況。
 */
object A11yBridge {

    private val _connected = MutableStateFlow(false)

    /** 無障礙服務是否已連線。 */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _snapshot = MutableStateFlow<ScreenSnapshot?>(null)

    /** 最新一次的畫面快照。 */
    val snapshot: StateFlow<ScreenSnapshot?> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<ScreenSnapshot>(extraBufferCapacity = 8)

    /** 每次擷取到新畫面就發射一次，給懸浮窗做即時反應。 */
    val events: SharedFlow<ScreenSnapshot> = _events.asSharedFlow()

    fun setConnected(connected: Boolean) {
        _connected.value = connected
    }

    fun publish(snapshot: ScreenSnapshot) {
        _snapshot.value = snapshot
        _events.tryEmit(snapshot)
    }

    /**
     * 「示範對話」正在前景時為 true。
     *
     * 平常無障礙服務會跳過自己 App 的畫面，但在示範模式下必須例外，
     * 否則就沒辦法用內建的假聊天來驗證整條流程了。
     * 由 DemoChatActivity 在 onResume/onPause 切換。
     */
    @Volatile
    var demoModeActive: Boolean = false

    /**
     * 本 App 自己的「全螢幕畫面」（主畫面／設定／紀錄）是否在前景。
     *
     * 為什麼需要它？因為無障礙事件**分辨不出**這兩件事：
     *   ① 使用者切回本 App 自己的畫面（該把殘留的譯文清掉）
     *   ② 使用者點了我們的懸浮球／圓點（懸浮視窗也是本套件，但前景其實是別的 App）
     * 兩者的 `packageName` 都是本套件。把 ② 也當成 ① 來清，就會變成
     * 「按了球球，所有 UI 閃一下就不見」（實機回報）—— 因為點球本身就會產生
     * 一個本套件的事件，於是剛畫好的圓點立刻被自己清掉。
     *
     * 所以改用 Activity 生命週期來判斷「到底是誰在前景」，由
     * [com.dsh.overlaytranslator.TranslatorApp] 的 ActivityLifecycleCallbacks 維護。
     * 示範對話不算在內（它走 [demoModeActive]，要能被讀取）。
     */
    @Volatile
    var ownUiActive: Boolean = false
}
