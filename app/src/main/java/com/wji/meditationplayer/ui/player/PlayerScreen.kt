package com.wji.meditationplayer.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.wji.meditationplayer.domain.Gap
import com.wji.meditationplayer.ui.formatDuration
import com.wji.meditationplayer.ui.formatGapLength

@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    var sheetPosition by remember { mutableStateOf<Long?>(null) }
    val track = state.track

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4"),
    ) { uri ->
        if (uri != null) viewModel.startExport(uri)
    }

    // 內容高度會超過手機一屏，整頁需要可捲動。
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = track?.displayName ?: "載入中…",
            style = MaterialTheme.typography.titleMedium,
        )

        // 原始長度 / 插入後總長度
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("原始長度 ${formatDuration(track?.durationMs ?: 0L)}")
            Text(
                text = "插入後 ${formatDuration(state.totalDurationMs)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.waveformProgress?.let { progress ->
            Column {
                Text("正在分析聲波… ${(progress * 100).toInt()}%")
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        WaveformCanvas(
            peaks = state.peaks,
            durationMs = track?.durationMs ?: 0L,
            gaps = track?.gaps.orEmpty(),
            originalPositionMs = state.originalPositionMs,
            onSeekOriginal = viewModel::seekToOriginal,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        )

        TotalTimelineBar(
            timeline = state.timeline,
            positionMs = state.positionMs,
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
        )

        // 播放位置：靜默中時直接顯示剩餘時間，避免使用者以為卡住了
        val remaining = state.silenceRemainingMs
        Text(
            text = if (remaining != null) {
                "靜默中 · 剩餘 ${formatDuration(remaining)}"
            } else {
                "${formatDuration(state.positionMs)} / ${formatDuration(state.totalDurationMs)}"
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = if (remaining != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { viewModel.skipBy(-15_000L) }) { Text("−15s") }
            Button(onClick = viewModel::togglePlayPause) {
                Text(if (state.isPlaying) "暫停" else "播放")
            }
            OutlinedButton(onClick = { viewModel.skipBy(15_000L) }) { Text("+15s") }
        }

        HorizontalDivider()

        // 靜音總開關
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("使用插入空白")
            Switch(
                checked = track?.silenceEnabled ?: true,
                onCheckedChange = viewModel::setSilenceEnabled,
            )
        }

        // 淡變長度
        Column {
            Text("音量漸變長度 ${(track?.fadeMs ?: 3_000L) / 1000} 秒")
            Slider(
                value = ((track?.fadeMs ?: 3_000L) / 1000f),
                onValueChange = { viewModel.setFadeMs((it * 1000).toLong()) },
                valueRange = 0.5f..10f,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { sheetPosition = state.originalPositionMs },
                modifier = Modifier.weight(1f),
            ) {
                Text("在目前位置插入空白")
            }
            OutlinedButton(
                onClick = {
                    val baseName = track?.displayName?.substringBeforeLast('.') ?: "export"
                    exportPicker.launch("$baseName-含靜默.m4a")
                },
                enabled = exportProgress == null,
            ) {
                Text("匯出")
            }
        }

        exportProgress?.let { percent ->
            Column {
                Text("正在匯出音檔… $percent%")
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        HorizontalDivider()

        Text("插入點（${track?.gaps?.size ?: 0}）", style = MaterialTheme.typography.titleSmall)
        // 插入點數量很少，直接展開；巢狀 LazyColumn 在可捲動的 Column 裡會炸。
        track?.gaps.orEmpty().forEach { gap ->
            GapRow(
                gap = gap,
                onToggle = { enabled -> viewModel.setGapEnabled(gap, enabled) },
                onRemove = { viewModel.removeGap(gap) },
            )
        }
    }

    sheetPosition?.let { position ->
        GapEditorSheet(
            atOriginalMs = position,
            onDismiss = { sheetPosition = null },
            onConfirm = { duration ->
                viewModel.addGap(position, duration)
                sheetPosition = null
            },
        )
    }
}

@Composable
private fun GapRow(
    gap: Gap,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Checkbox(checked = gap.enabled, onCheckedChange = onToggle)
        Text(
            text = "${formatDuration(gap.atMs)} · ${formatGapLength(gap.durationMs)}",
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) { Text("✕") }
    }
}
