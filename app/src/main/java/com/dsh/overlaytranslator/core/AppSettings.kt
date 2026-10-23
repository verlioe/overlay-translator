package com.dsh.overlaytranslator.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** DataStore 的委派必須是檔案層級的擴充屬性。 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "overlay_translator_settings"
)

/**
 * 譯文要用什麼方式呈現。
 */
enum class DisplayMode {
    /**
     * 內嵌：譯文直接浮在訊息旁邊、輸入框上方。
     * 不必點開任何面板，最直覺，也是預設值。
     */
    INLINE,

    /** 面板：點懸浮球展開一個固定面板。訊息很多、想要一次看全部時比較方便。 */
    PANEL,
}

/**
 * 全部的使用者設定。
 *
 * @param apiKey         DeepSeek API Key（在 DataStore 內是加密後的字串）
 * @param baseUrl        OpenAI 相容端點，預設 https://api.deepseek.com
 * @param model          模型 ID。故意做成可自由填寫，因為模型 ID 會變動
 * @param myLanguage     我使用的語言（譯文的目標語言）
 * @param myGender       我的性別。有些語言用文法區分**說話者**性別（泰文的 ครับ/ค่ะ、
 *                       日文的第一人稱與語尾、韓文的語尾…），翻錯會非常突兀。
 *                       留空 = 不指定，模型就自己判斷
 * @param theirLanguage  對方語言。留空 = 讓模型依上下文自動判斷
 * @param contextTurns   要送幾則對話歷史給模型當上下文
 * @param disableThinking 關閉模型思考模式。翻譯不需要思考鏈，關掉可大幅加快並省錢
 * @param autoTranslateIncoming 偵測到新訊息時自動翻譯（會產生 API 費用）
 * @param displayMode    譯文呈現方式：內嵌（預設）或面板
 * @param watchedPackages 只監聽這些套件，逗號分隔；留空 = 全部 App
 * @param bubbleX/bubbleY 懸浮球位置
 */
data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val myLanguage: String = DEFAULT_MY_LANGUAGE,
    val myGender: String = DEFAULT_MY_GENDER,
    /** 聊天語氣（親疏）。留空 = 自動。見 [TONE_CASUAL]。 */
    val tone: String = DEFAULT_TONE,
    /**
     * 自訂翻譯風格提示，直接追加到「我 → 對方」的提示詞裡。
     *
     * 為什麼要有這個自由欄位？因為「像不像真人」這件事有太多只有使用者知道的細節
     * （他跟對方多熟、愛不愛用 555、有沒有共同梗、要不要用 ว่ะ），
     * 與其讓我猜一輩子，不如留一個欄位讓他自己寫一句。
     */
    val styleNote: String = "",
    val theirLanguage: String = "",
    val contextTurns: Int = 8,
    val temperature: Float = 0.2f,
    val disableThinking: Boolean = true,
    val autoTranslateIncoming: Boolean = false,
    val displayMode: DisplayMode = DisplayMode.INLINE,
    val showCandidates: Boolean = true,
    val watchedPackages: String = "",
    val bubbleX: Int = -1,
    val bubbleY: Int = 320,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * 解析成小寫的套件清單；空集合代表「全部 App 都監聽」。
     */
    fun watchedPackageSet(): Set<String> =
        watchedPackages.split(',', '\n', ' ', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_MY_LANGUAGE = "繁體中文"

        /** 我的性別選項。留空 = 不指定。 */
        const val GENDER_MALE = "男性"
        const val GENDER_FEMALE = "女性"
        const val DEFAULT_MY_GENDER = ""

        /**
         * 聊天語氣（親疏）。留空 = 自動，由模型從上下文判斷。
         *
         * 為什麼要有這個設定？實測回饋：「跟泰國朋友聊天，對方說**太假了**」。
         * 主因是語域 —— LLM 的泰文預設偏書面，而且句尾助詞用錯親疏會非常突兀：
         * 對熟人每句都加 ครับ/ค่ะ，泰國人聽起來就是「客氣到很假」。
         * 這種事只有使用者知道（他跟對方多熟），所以做成設定而不是讓模型猜。
         *
         * 2026-10-07 改標籤：使用者要求「正式禮貌／普通閒聊／熟人閒聊（不禮貌但打鬧）」。
         * 舊值由 [normalizeTone] 轉過來 —— 不轉的話，舊手機裡存的「正式場合」
         * 會對不到任何選項、悄悄退回「自動」，使用者的設定等於被吃掉。
         */
        const val TONE_CASUAL = "熟人閒聊"
        const val TONE_POLITE = "普通閒聊"
        const val TONE_FORMAL = "正式禮貌"
        const val DEFAULT_TONE = ""

        /** 舊版標籤 → 目前標籤。只在讀取舊設定時用到。 */
        private val LEGACY_TONES = mapOf(
            "朋友閒聊" to TONE_CASUAL,
            "一般禮貌" to TONE_POLITE,
            "正式場合" to TONE_FORMAL,
        )

        /** 把（可能是舊版的）語氣字串正規化成目前的標籤；認不出來就原樣回傳。 */
        fun normalizeTone(raw: String): String {
            val value = raw.trim()
            return LEGACY_TONES[value] ?: value
        }
    }
}

private object Keys {
    val API_KEY = stringPreferencesKey("api_key_encrypted")
    val BASE_URL = stringPreferencesKey("base_url")
    val MODEL = stringPreferencesKey("model")
    val MY_LANGUAGE = stringPreferencesKey("my_language")
    val MY_GENDER = stringPreferencesKey("my_gender")
    val TONE = stringPreferencesKey("tone")
    val STYLE_NOTE = stringPreferencesKey("style_note")
    val THEIR_LANGUAGE = stringPreferencesKey("their_language")
    val CONTEXT_TURNS = intPreferencesKey("context_turns")
    val TEMPERATURE = floatPreferencesKey("temperature")
    val DISABLE_THINKING = booleanPreferencesKey("disable_thinking")
    val AUTO_INCOMING = booleanPreferencesKey("auto_translate_incoming")
    val DISPLAY_MODE = stringPreferencesKey("display_mode")
    val SHOW_CANDIDATES = booleanPreferencesKey("show_candidates")
    val WATCHED_PACKAGES = stringPreferencesKey("watched_packages")
    val BUBBLE_X = intPreferencesKey("bubble_x")
    val BUBBLE_Y = intPreferencesKey("bubble_y")
}

/** 舊版沒有這個設定，讀不到時預設用內嵌模式。 */
private fun parseDisplayMode(raw: String?): DisplayMode =
    if (raw == DisplayMode.PANEL.name) DisplayMode.PANEL else DisplayMode.INLINE

private fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        apiKey = SecretVault.decrypt(this[Keys.API_KEY].orEmpty()),
        baseUrl = this[Keys.BASE_URL] ?: defaults.baseUrl,
        model = this[Keys.MODEL] ?: defaults.model,
        myLanguage = this[Keys.MY_LANGUAGE] ?: defaults.myLanguage,
        myGender = this[Keys.MY_GENDER] ?: defaults.myGender,
        tone = AppSettings.normalizeTone(this[Keys.TONE] ?: defaults.tone),
        styleNote = this[Keys.STYLE_NOTE] ?: defaults.styleNote,
        theirLanguage = this[Keys.THEIR_LANGUAGE] ?: defaults.theirLanguage,
        contextTurns = this[Keys.CONTEXT_TURNS] ?: defaults.contextTurns,
        temperature = this[Keys.TEMPERATURE] ?: defaults.temperature,
        disableThinking = this[Keys.DISABLE_THINKING] ?: defaults.disableThinking,
        autoTranslateIncoming = this[Keys.AUTO_INCOMING] ?: defaults.autoTranslateIncoming,
        displayMode = parseDisplayMode(this[Keys.DISPLAY_MODE]),
        showCandidates = this[Keys.SHOW_CANDIDATES] ?: defaults.showCandidates,
        watchedPackages = this[Keys.WATCHED_PACKAGES] ?: defaults.watchedPackages,
        bubbleX = this[Keys.BUBBLE_X] ?: defaults.bubbleX,
        bubbleY = this[Keys.BUBBLE_Y] ?: defaults.bubbleY,
    )
}

/**
 * 設定的單一來源。
 *
 * 重點：除了 suspend 的 [update] 之外，另外提供**同步**的 [current]，
 * 因為無障礙服務的 onAccessibilityEvent() 是主執行緒的同步回呼，
 * 在那裡 suspend 取值會很不方便。
 */
class SettingsRepository(
    private val appContext: Context,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    init {
        scope.launch {
            appContext.settingsDataStore.data.collect { prefs ->
                _state.value = prefs.toAppSettings()
            }
        }
    }

    /** 目前設定的同步快照。 */
    fun current(): AppSettings = _state.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        appContext.settingsDataStore.edit { prefs ->
            val storedKey = SecretVault.decrypt(prefs[Keys.API_KEY].orEmpty())
            val next = transform(prefs.toAppSettings())
            // 只有使用者真的改了 API Key 才重新加密寫回。
            // 否則一旦 Keystore 解密失敗（換手機、金鑰被清除），
            // storedKey 與 next.apiKey 都會是空字串，這時照寫就會無聲地抹掉原本的密文。
            if (next.apiKey != storedKey) {
                prefs[Keys.API_KEY] = SecretVault.encrypt(next.apiKey)
            }
            prefs[Keys.BASE_URL] = next.baseUrl
            prefs[Keys.MODEL] = next.model
            prefs[Keys.MY_LANGUAGE] = next.myLanguage
            prefs[Keys.MY_GENDER] = next.myGender
            prefs[Keys.TONE] = next.tone
            prefs[Keys.STYLE_NOTE] = next.styleNote
            prefs[Keys.THEIR_LANGUAGE] = next.theirLanguage
            prefs[Keys.CONTEXT_TURNS] = next.contextTurns
            prefs[Keys.TEMPERATURE] = next.temperature
            prefs[Keys.DISABLE_THINKING] = next.disableThinking
            prefs[Keys.AUTO_INCOMING] = next.autoTranslateIncoming
            prefs[Keys.DISPLAY_MODE] = next.displayMode.name
            prefs[Keys.SHOW_CANDIDATES] = next.showCandidates
            prefs[Keys.WATCHED_PACKAGES] = next.watchedPackages
            prefs[Keys.BUBBLE_X] = next.bubbleX
            prefs[Keys.BUBBLE_Y] = next.bubbleY
        }

        // 立刻反映到記憶體狀態。DataStore 的 collector 是非同步的，
        // 而懸浮窗按完「語氣」之後要**當場**看得到哪一顆被選中（也要用新的語氣重翻），
        // 不能等下一次收集才生效。
        _state.value = transform(_state.value)
    }
}
