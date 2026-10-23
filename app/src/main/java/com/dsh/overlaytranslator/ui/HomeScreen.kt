package com.dsh.overlaytranslator.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dsh.overlaytranslator.TranslatorApp
import com.dsh.overlaytranslator.a11y.A11yBridge
import com.dsh.overlaytranslator.a11y.ChatAccessibilityService
import com.dsh.overlaytranslator.core.AppLog
import com.dsh.overlaytranslator.overlay.OverlayService
import com.dsh.overlaytranslator.translate.Direction
import com.dsh.overlaytranslator.translate.TranslationOutcome
import com.dsh.overlaytranslator.translate.TranslationRequest
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val context = LocalContext.current
    val settings by TranslatorApp.settings().state.collectAsState()
    val a11yConnected by A11yBridge.connected.collectAsState()

    // 每次回到前景就重讀一次系統權限狀態（使用者可能是剛從系統設定頁回來的）
    var refreshTick by remember { mutableStateOf(0) }
    var overlayRunning by remember { mutableStateOf(OverlayService.isRunning) }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshTick++
                    overlayRunning = OverlayService.isRunning
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }

    val hasOverlay = remember(refreshTick) { Settings.canDrawOverlays(context) }
    val a11yEnabled = remember(refreshTick) { isAccessibilityServiceEnabled(context) }
    val hasNotification = remember(refreshTick) { hasNotificationPermission(context) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        // ------------------------------------------------------------ 狀態總覽
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("目前狀態", style = MaterialTheme.typography.titleMedium)
                StatusRow("顯示在其他 App 上層", hasOverlay)
                StatusRow("無障礙讀取服務", a11yEnabled, extra = if (a11yConnected) "已連線" else "未連線")
                StatusRow("懸浮窗服務", overlayRunning)
                StatusRow("DeepSeek API Key", settings.isConfigured)
                StatusRow("常駐通知權限", hasNotification)
            }
        }

        // ------------------------------------------------------------ 開關懸浮窗
        Button(
            onClick = {
                if (OverlayService.isRunning) {
                    OverlayService.stop(context)
                    overlayRunning = false
                } else {
                    OverlayService.start(context)
                    overlayRunning = true
                }
            },
            // 刻意不要求先填 API Key：使用者可以先看到懸浮球長什麼樣子，
            // 面板會自己提示「尚未設定 API Key」。這裡只要求懸浮窗權限。
            enabled = hasOverlay,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (overlayRunning) "停止懸浮窗" else "啟動懸浮窗")
        }
        if (!hasOverlay) {
            Text(
                text = "按鈕變灰色是因為還沒完成「步驟 1：允許顯示在其他 App 上層」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------------ 權限步驟
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("設定步驟", style = MaterialTheme.typography.titleMedium)

                StepHeader(1, "允許顯示在其他 App 上層", hasOverlay)
                Text(
                    "懸浮球需要這個權限才能蓋在聊天 App 上面。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { safeStartActivity(context, overlayPermissionIntent(context)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (hasOverlay) "已允許（可再點一次修改）" else "開啟懸浮窗權限") }

                HorizontalDivider()

                StepHeader(2, "開啟無障礙讀取服務", a11yEnabled)
                Text(
                    "這是唯一能讀取其他 App 畫面上文字的方法，Android 沒有提供其他一般權限。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        safeStartActivity(
                            context,
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (a11yEnabled) "已開啟（可再點一次修改）" else "前往無障礙設定") }

                // 這是側載 App 最容易卡住的地方，尤其是小米/HyperOS
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "小米 / HyperOS 使用者注意",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "因為這個 APK 是側載的，Android 13 以上會把無障礙開關鎖住。" +
                                "如果開關是灰的、點不動，請先到：\n" +
                                "設定 → 應用程式 → 管理應用程式 → 懸浮翻譯 → 右上角三點 → " +
                                "「允許受限制的設定」。\n" +
                                "做完之後再回來開無障礙服務。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HorizontalDivider()

                StepHeader(3, "允許常駐通知", hasNotification)
                Text(
                    "懸浮窗是前景服務，Android 要求顯示一個常駐通知。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (hasNotification) "已允許" else "允許通知") }

                HorizontalDivider()

                StepHeader(4, "填入 DeepSeek API Key", settings.isConfigured)
                Text(
                    "去 platform.deepseek.com 註冊 → 左側選單「API keys」→「Create new API key」→ " +
                        "複製 sk- 開頭的字串（它只會顯示一次）。設定頁裡有完整步驟說明。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { openUrl(context, "https://platform.deepseek.com/api_keys") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("開啟 DeepSeek 取得 API Key") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(if (settings.isConfigured) "修改設定" else "前往設定貼上 Key")
                }
            }
        }

        // ------------------------------------------------------------ 示範對話
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("示範對話（推薦先測這個）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "內建的假聊天畫面，用真的輸入框與訊息氣泡。你可以在完全不碰 " +
                        "LINE / WhatsApp 的情況下，驗證「讀取對方訊息 → 翻譯 → 寫回輸入框」整條流程。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        safeStartActivity(context, Intent(context, DemoChatActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("開啟示範對話") }
            }
        }

        // ------------------------------------------------------------ 測試翻譯
        TestTranslationCard()

        // ------------------------------------------------------------ 其他
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenLog) { Text("看紀錄") }
        }

        Text(
            text = "隱私：只有在按下「翻譯」時，那一句原文與最近幾輪上下文才會送到你設定的 API。" +
                "其他時間都在本機處理，對話內容不會寫入裝置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 不碰任何聊天 App 就能驗證 API Key 是否正確的地方。
 * 用一個假的套件名稱送出去，避免污染真實對話的上下文記憶。
 */
@Composable
private fun TestTranslationCard() {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings by TranslatorApp.settings().state.collectAsState()

    val samples = listOf(
        "Hey, are you free tonight?",
        "このゲームめっちゃ面白いよ",
        "Je suis désolé, je ne peux pas venir",
    )

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("測試翻譯", style = MaterialTheme.typography.titleMedium)
            Text(
                "不用開任何聊天 App，直接在這裡確認 API Key 能不能用。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("要翻譯的文字") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                samples.forEach { sample ->
                    TextButton(onClick = { input = sample }) {
                        Text(sample.take(8) + "…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Button(
                onClick = {
                    testing = true
                    output = ""
                    scope.launch {
                        val outcome = TranslatorApp.translator().translate(
                            TranslationRequest(
                                text = input,
                                direction = Direction.THEIR_TEXT_TO_ME,
                                packageName = "__home_screen_test__",
                            )
                        )
                        testing = false
                        output = when (outcome) {
                            is TranslationOutcome.Success -> outcome.text
                            is TranslationOutcome.Failure -> "錯誤：" + outcome.message
                        }
                    }
                },
                enabled = !testing && input.isNotBlank() && settings.isConfigured,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (testing) "翻譯中…" else "翻譯成${settings.myLanguage}")
            }

            if (testing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text("正在呼叫 API…", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (output.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = output,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, extra: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = extra ?: if (ok) "✅ 完成" else "⏳ 未設定",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StepHeader(index: Int, title: String, done: Boolean) {
    Text(
        text = "${if (done) "✅" else "⬜"} 步驟 $index：$title",
        style = MaterialTheme.typography.titleSmall,
    )
}

// ---------------------------------------------------------------------- 系統工具

private fun overlayPermissionIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + context.packageName),
    )

/**
 * 判斷無障礙服務是否已由使用者開啟。
 *
 * 系統把已開啟的服務存在 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES，
 * 格式是「套件/類別」以 ':' 分隔。這裡同時比對長短兩種寫法，
 * 因為不同廠牌的 ROM 寫入的格式不一致。
 */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    if (enabled.isEmpty()) return false

    val component = ComponentName(context, ChatAccessibilityService::class.java)
    val longForm = component.flattenToString()
    val shortForm = component.flattenToShortString()

    return enabled.split(':').any { entry ->
        entry.equals(longForm, ignoreCase = true) || entry.equals(shortForm, ignoreCase = true)
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

private fun safeStartActivity(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (t: Throwable) {
        AppLog.e("HomeScreen", "無法開啟系統設定頁：${intent.action}", t)
    }
}
