package com.dsh.overlaytranslator

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.dsh.overlaytranslator.a11y.A11yBridge
import com.dsh.overlaytranslator.core.AppLog
import com.dsh.overlaytranslator.core.SettingsRepository
import com.dsh.overlaytranslator.translate.DeepSeekEngine
import com.dsh.overlaytranslator.translate.TranslationEngine
import com.dsh.overlaytranslator.ui.DemoChatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 全 App 的單例容器。
 *
 * 為什麼用 Application 而不是 DI 框架？因為這個專案的物件圖只有兩個節點，
 * 引入 Hilt/Koin 只會讓第一次建置更容易失敗，換不到什麼好處。
 */
class TranslatorApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    lateinit var translator: TranslationEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(this, appScope)
        translator = DeepSeekEngine { settings.current() }

        trackOwnScreensInForeground()

        AppLog.d("TranslatorApp", "啟動完成")
    }

    /**
     * 追蹤「本 App 自己的全螢幕畫面是不是在前景」。
     *
     * 為什麼一定要有這個？無障礙事件只看得到 `packageName`，而我們的懸浮球／圓點
     * **也是本套件** —— 所以「使用者切回本 App」與「使用者點了懸浮球」在事件層面
     * 長得一模一樣。無障礙服務要據此決定「該不該把殘留的譯文清掉」，
     * 分不出來的下場就是：點個球，所有 UI 閃一下全消失（實機回報）。
     *
     * 生命週期是目前唯一可靠的訊號：懸浮視窗不是 Activity，點它不會觸發這裡。
     */
    private fun trackOwnScreensInForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // 示範對話要能被無障礙讀取，所以不算「該清空的自己的畫面」
                A11yBridge.ownUiActive = activity !is DemoChatActivity
            }

            override fun onActivityPaused(activity: Activity) {
                // 切換 Activity 時，舊的會先 onPause、新的才 onResume，
                // 所以在這裡歸零是安全的（新畫面緊接著會設回 true）。
                if (activity !is DemoChatActivity) A11yBridge.ownUiActive = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        lateinit var instance: TranslatorApp
            private set

        fun settings(): SettingsRepository = instance.settings

        fun translator(): TranslationEngine = instance.translator
    }
}
