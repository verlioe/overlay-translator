package com.dsh.overlaytranslator.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dsh.overlaytranslator.TranslatorApp
import com.dsh.overlaytranslator.core.AppLog
import com.dsh.overlaytranslator.core.AppSettings
import com.dsh.overlaytranslator.translate.ConversationMemory
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val saved by TranslatorApp.settings().state.collectAsState()
    val context = LocalContext.current

    // draft 是「還沒儲存的編輯內容」。用 saved 當 remember 的 key，
    // 所以儲存完成後會自動同步回真正存下來的内容。
    var draft by remember(saved) { mutableStateOf(saved) }

    var showKey by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        // ------------------------------------------------------------ API
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DeepSeek API", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it.trim()) },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隱藏" else "顯示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 直接把「去哪裡拿 Key」寫在這裡，不要讓使用者去猜、去查、去問人
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("API Key 去哪裡拿？", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "1. 開啟 platform.deepseek.com，註冊或登入\n" +
                                "2. 左側選單點「API keys」\n" +
                                "3. 按「Create new API key」，幫它取個名字\n" +
                                "4. 複製 sk- 開頭的那一串字\n" +
                                "   它只會顯示一次，關掉視窗就再也看不到了，請立刻貼過來\n" +
                                "5. 貼到上面的欄位，再按最下面的「儲存設定」\n\n" +
                                "注意：DeepSeek 是按用量計費，帳戶裡要先有餘額才能翻譯。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { openUrl(context, "https://platform.deepseek.com/api_keys") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("用瀏覽器開啟 DeepSeek 平台") }
                    }
                }
                Text(
                    "存檔時會用 Android Keystore 加密，不會明文寫在檔案裡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it.trim()) },
                    label = { Text("API 端點（Base URL）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "預設 https://api.deepseek.com。任何 OpenAI 相容服務都可以填這裡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it.trim()) },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            listing = true
                            statusLine = ""
                            scope.launch {
                                val result = TranslatorApp.translator().listModels()
                                listing = false
                                statusLine = result.fold(
                                    onSuccess = { list ->
                                        if (list.isEmpty()) {
                                            "伺服器沒有回傳任何模型"
                                        } else {
                                            "可用模型：" + list.joinToString("、")
                                        }
                                    },
                                    onFailure = { "查詢失敗：" + (it.message ?: "未知錯誤") },
                                )
                            }
                        },
                        enabled = !listing && draft.apiKey.isNotBlank(),
                    ) { Text(if (listing) "查詢中…" else "查詢可用模型") }
                }
            }
        }

        // ------------------------------------------------------------ 語言
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("語言", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = draft.myLanguage,
                    onValueChange = { draft = draft.copy(myLanguage = it) },
                    label = { Text("我的語言（對方訊息要翻成這個）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 性別：有些語言會用文法標示「說話者」的性別（泰文句尾 ครับ/ค่ะ、
                // 日文的第一人稱與語尾、韓文的語尾）。這是純文法、從上下文看不出來的事，
                // 猜錯對方一眼就看得出來，所以讓使用者直接指定。
                Text("我的性別", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenderOption(
                        label = "男性",
                        selected = draft.myGender == AppSettings.GENDER_MALE,
                        onClick = { draft = draft.copy(myGender = AppSettings.GENDER_MALE) },
                    )
                    GenderOption(
                        label = "女性",
                        selected = draft.myGender == AppSettings.GENDER_FEMALE,
                        onClick = { draft = draft.copy(myGender = AppSettings.GENDER_FEMALE) },
                    )
                    GenderOption(
                        label = "不指定",
                        selected = draft.myGender.isBlank(),
                        onClick = { draft = draft.copy(myGender = "") },
                    )
                }
                Text(
                    "泰文的句尾 ครับ（男）／ค่ะ（女）、日文與韓文的語尾都會依說話者性別改變。" +
                        "選「不指定」時模型會盡量用中性說法。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 語氣（親疏）**刻意不再放這裡**：得不得體是「每一則訊息」的事，
                // 使用者實測回饋「設好一個固定值之後，跟當下想講的語氣還是有距離」，
                // 所以選擇搬到懸浮窗的「我的輸入」視窗上（見 OverlayService.TONE_CHOICES）。
                Text("聊天語氣（跟對方多熟）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "改成在懸浮窗的「我的輸入」視窗上直接選（自動／${AppSettings.TONE_CASUAL}／" +
                        "${AppSettings.TONE_POLITE}／${AppSettings.TONE_FORMAL}）。" +
                        "因為得不得體是每一則訊息的事 —— 同一句話對熟人很自然、對長輩就很失禮，" +
                        "所以放在你看得到那句譯文的地方，隨時可以換。" +
                        "這個選擇同時是「診斷」判斷得不得體的標準。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = draft.styleNote,
                    onValueChange = { draft = draft.copy(styleNote = it) },
                    label = { Text("自訂翻譯風格（選填）") },
                    placeholder = {
                        Text("例：我跟這個朋友很熟，講話很隨便，可以用 ว่ะ，笑用 555")
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "寫在這裡的內容會直接送進提示詞，而且優先於上面所有風格設定。" +
                        "對方嫌「太假」時，把具體例子寫進來（例如他希望你怎麼講、討厭哪種講法）最有效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.theirLanguage,
                    onValueChange = { draft = draft.copy(theirLanguage = it) },
                    label = { Text("對方語言（留空＝依上下文自動判斷）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "留空時，模型會看對話中對方講什麼語言，就翻成那個語言 —— 這是多語言聊天的推薦設定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ 上下文
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("上下文", style = MaterialTheme.typography.titleMedium)
                Text(
                    "送出 ${draft.contextTurns} 則對話歷史給模型。越多越準，但也越貴。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = draft.contextTurns.toFloat(),
                    onValueChange = { draft = draft.copy(contextTurns = it.roundToInt()) },
                    valueRange = 0f..30f,
                    steps = 29,
                )
                Text(
                    "0＝完全不帶上下文（最便宜，代名詞會翻錯）。8～12 對一般聊天很夠用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(6.dp))

                // 靈活度（temperature）：太低會變成「挑最標準的說法」，
                // 翻出來的句子很正確但很像教科書 —— 就是「太假」的另一半原因。
                Text(
                    "翻譯的靈活度：${"%.1f".format(draft.temperature)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = draft.temperature,
                    onValueChange = { draft = draft.copy(temperature = it) },
                    valueRange = 0f..1.2f,
                    steps = 11,
                )
                Text(
                    "調高＝用詞更活、更像真人打字；調低＝更貼字面、更穩定。" +
                        "覺得譯文「很正確但很假」就調到 0.7 左右試試；" +
                        "需要精準翻譯（合約、地址、數字）時再調回 0.2。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ 行為
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("行為", style = MaterialTheme.typography.titleMedium)

                SwitchRow(
                    title = "關閉模型的思考模式",
                    subtitle = "翻譯不需要思考鏈。關掉可大幅加快回應速度並省錢（僅對 DeepSeek 端點送出這個參數）。",
                    checked = draft.disableThinking,
                    onChange = { draft = draft.copy(disableThinking = it) },
                )

                HorizontalDivider()

                SwitchRow(
                    title = "自動翻譯對方的新訊息",
                    subtitle = "偵測到新訊息就直接翻譯，不必手動點。注意：這會在你沒按按鈕時也產生 API 費用。",
                    checked = draft.autoTranslateIncoming,
                    onChange = { draft = draft.copy(autoTranslateIncoming = it) },
                )

                HorizontalDivider()

                SwitchRow(
                    title = "顯示「最近擷取」候選清單",
                    subtitle = "自動判斷有時會抓錯行，打開這個可以自己點選要翻的那一句。",
                    checked = draft.showCandidates,
                    onChange = { draft = draft.copy(showCandidates = it) },
                )
            }
        }

        // ------------------------------------------------------------ 監聽範圍
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只監聽這些 App", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = draft.watchedPackages,
                    onValueChange = { draft = draft.copy(watchedPackages = it) },
                    label = { Text("套件名稱，用逗號分隔") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "留空＝所有 App 都讀。想限制範圍的話，例如：\n" +
                        "com.whatsapp, jp.naver.line.android, org.telegram.messenger\n" +
                        "（套件名稱可以用 adb shell pm list packages 查，之後我也會加一個 App 選擇器。）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ 儲存
        Button(
            onClick = {
                saving = true
                statusLine = ""
                scope.launch {
                    TranslatorApp.settings().update { draft }
                    // 換了模型或端點，舊的快取就不准了
                    TranslatorApp.translator().clearCache()
                    saving = false
                    statusLine = "已儲存設定"
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "儲存中…" else "儲存設定") }

        OutlinedButton(
            onClick = {
                ConversationMemory.clear()
                TranslatorApp.translator().clearCache()
                statusLine = "已清除對話記憶與翻譯快取"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("清除對話記憶與快取") }

        if (statusLine.isNotBlank()) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            text = "對話記憶只存在記憶體中，關掉 App 就消失，也不會寫進裝置儲存空間。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 性別選項按鈕。選中的用實心按鈕，其餘用外框按鈕 —— 一眼看得出目前選了什麼。
 */
@Composable
private fun GenderOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 開啟外部網址。設定頁與主畫面都會用到，所以放在檔案層級而不是 private。
 * 裝置上沒有瀏覽器時會丟 ActivityNotFoundException，這裡吞掉並寫進紀錄，不讓 App 崩潰。
 */
internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        AppLog.e("SettingsScreen", "無法開啟瀏覽器：$url", t)
    }
}
