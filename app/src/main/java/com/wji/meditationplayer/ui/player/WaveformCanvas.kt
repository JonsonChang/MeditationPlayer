package com.wji.meditationplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.wji.meditationplayer.domain.Gap
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 波形圖。刻意畫在**原始時間軸**上，不按插入後的比例。
 *
 * 若按插入後比例，30 分鐘的音檔插入 50 分鐘靜默後音訊只會佔畫面 37% 寬，
 * 拖放插入點會變得幾乎不能用。插入後的真實比例交給 TotalTimelineBar 呈現。
 */
@Composable
fun WaveformCanvas(
    peaks: ShortArray?,
    durationMs: Long,
    gaps: List<Gap>,
    originalPositionMs: Long,
    onSeekOriginal: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val waveColor = MaterialTheme.colorScheme.secondary
    val playedColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.primary
    val disabledMarkerColor = waveColor.copy(alpha = 0.35f)

    Canvas(
        modifier = modifier.pointerInput(durationMs) {
            fun seek(x: Float) {
                if (durationMs <= 0L || size.width <= 0) return
                val ratio = (x / size.width).coerceIn(0f, 1f)
                onSeekOriginal((ratio * durationMs).roundToLong())
            }
            detectTapGestures { offset -> seek(offset.x) }
        }.pointerInput(durationMs) {
            detectDragGestures { change, _ ->
                if (durationMs > 0L && size.width > 0) {
                    val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                    onSeekOriginal((ratio * durationMs).roundToLong())
                }
            }
        },
    ) {
        val width = size.width
        val height = size.height
        val middle = height / 2f
        val playedRatio = if (durationMs > 0L) {
            (originalPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

        if (peaks != null && peaks.isNotEmpty()) {
            val barWidth = 2.dp.toPx()
            val step = max(barWidth + 1.dp.toPx(), 1f)
            var x = 0f
            while (x < width) {
                val bucket = ((x / width) * peaks.size).toInt().coerceIn(0, peaks.size - 1)
                val magnitude = peaks[bucket].toFloat() / Short.MAX_VALUE
                val barHeight = max(magnitude * middle, 1f)
                drawLine(
                    color = if (x / width <= playedRatio) playedColor else waveColor,
                    start = Offset(x, middle - barHeight),
                    end = Offset(x, middle + barHeight),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
                x += step
            }
        } else {
            drawLine(
                color = waveColor.copy(alpha = 0.3f),
                start = Offset(0f, middle),
                end = Offset(width, middle),
                strokeWidth = 2.dp.toPx(),
            )
        }

        // 插入點標記
        if (durationMs > 0L) {
            gaps.forEach { gap ->
                val ratio = (gap.atMs.toFloat() / durationMs).coerceIn(0f, 1f)
                drawLine(
                    color = if (gap.enabled) markerColor else disabledMarkerColor,
                    start = Offset(ratio * width, 0f),
                    end = Offset(ratio * width, height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }

        // 播放位置
        drawLine(
            color = Color.White,
            start = Offset(playedRatio * width, 0f),
            end = Offset(playedRatio * width, height),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}
