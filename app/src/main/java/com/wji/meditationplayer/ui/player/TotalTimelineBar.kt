package com.wji.meditationplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wji.meditationplayer.domain.EffectiveTimeline
import com.wji.meditationplayer.domain.SilenceSegment

/**
 * 插入後的總時間軸，依真實比例畫出音訊段與靜默段。
 *
 * 波形圖是原始時間軸，看不出留白佔了多少；這條讓使用者對總長有正確直覺。
 */
@Composable
fun TotalTimelineBar(
    timeline: EffectiveTimeline?,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val audioColor = MaterialTheme.colorScheme.secondary
    val silenceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

    Canvas(modifier = modifier) {
        val total = timeline?.totalDurationMs ?: 0L
        if (timeline == null || total <= 0L) return@Canvas

        timeline.segments.forEach { segment ->
            val startRatio = segment.effectiveStartMs.toFloat() / total
            val widthRatio = segment.durationMs.toFloat() / total
            drawRect(
                color = if (segment is SilenceSegment) silenceColor else audioColor,
                topLeft = Offset(startRatio * size.width, 0f),
                size = Size(widthRatio * size.width, size.height),
            )
        }

        val playedRatio = (positionMs.toFloat() / total).coerceIn(0f, 1f)
        drawLine(
            color = Color.White,
            start = Offset(playedRatio * size.width, 0f),
            end = Offset(playedRatio * size.width, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}
