package com.dsh.overlaytranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.overlaytranslator.core.AppLog

/**
 * 自我診斷頁。
 *
 * 這個 App 最麻煩的地方是「它在別的 App 上面運作」，出問題時使用者正在聊天，
 * 沒辦法接電腦看 Logcat。所以把最近的事件留在記憶體並在這裡顯示。
 *
 * 注意：這裡刻意「不」記錄聊天內容，只記錄事件、數量與錯誤，
 * 避免把使用者的對話留在裝置上。
 */
@Composable
fun LogScreen() {
    val lines by AppLog.lines.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { AppLog.clear() }) { Text("清除紀錄") }
            Text(
                text = "共 ${lines.size} 行",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Text(
            text = "這裡只記錄事件與數量，不會記錄聊天內容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (lines.isEmpty()) {
            Text(
                text = "目前沒有紀錄。啟動懸浮窗後再回來看。",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun BackRow(onBack: () -> Unit) {
    TextButton(onClick = onBack) { Text("返回") }
}
