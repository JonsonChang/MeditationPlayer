package com.wji.meditationplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wji.meditationplayer.BuildConfig
import com.wji.meditationplayer.ui.formatDuration

private const val MIN_MINUTES = 1f
private const val MAX_MINUTES = 20f
private const val DEFAULT_MINUTES = 5f

/**
 * 設定要在 [atOriginalMs] 插入多長的空白。
 *
 * 空白長度依需求為 1–20 分鐘、1 分鐘一階，預設 5 分鐘。debug build 另外提供一個極短選項，
 * 否則每次驗證淡變都得等 1 分鐘（見 BuildConfig.MIN_GAP_MS）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GapEditorSheet(
    atOriginalMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (durationMs: Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var minutes by remember { mutableFloatStateOf(DEFAULT_MINUTES) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "在 ${formatDuration(atOriginalMs)} 插入空白",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${minutes.toInt()} 分鐘",
                style = MaterialTheme.typography.headlineSmall,
            )
            Slider(
                value = minutes,
                onValueChange = { minutes = it },
                valueRange = MIN_MINUTES..MAX_MINUTES,
                steps = (MAX_MINUTES - MIN_MINUTES).toInt() - 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onConfirm(minutes.toLong() * 60_000L) }) { Text("插入") }
                if (BuildConfig.MIN_GAP_MS < 60_000L) {
                    OutlinedButton(onClick = { onConfirm(BuildConfig.MIN_GAP_MS) }) {
                        Text("測試 ${BuildConfig.MIN_GAP_MS / 1000} 秒")
                    }
                }
            }
        }
    }
}
