package com.wji.meditationplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.wji.meditationplayer.domain.AudioSegment
import com.wji.meditationplayer.domain.EffectiveTimeline
import com.wji.meditationplayer.domain.SilenceSegment

/**
 * 把時間軸組成播放器實際播的 MediaSource —— 原始音檔完全不被修改。
 *
 * 用 ConcatenatingMediaSource2 是關鍵：它把所有子來源併成單一 Timeline.Window，
 * 所以 player.duration 直接就是插入後的總長、currentPosition 是全域位置，
 * seek bar 不需要任何換算。
 */
@UnstableApi
object MediaSourceBuilder {

    fun build(
        context: Context,
        uri: Uri,
        timeline: EffectiveTimeline,
        mediaItem: MediaItem,
    ): MediaSource? {
        if (timeline.segments.isEmpty()) return null

        val factory = DefaultMediaSourceFactory(context)
        val builder = ConcatenatingMediaSource2.Builder().setMediaItem(mediaItem)

        timeline.segments.forEach { segment ->
            when (segment) {
                is AudioSegment -> {
                    // 每個切片都要獨立的來源實例，同一個 MediaSource 不能掛在多個 parent 下。
                    val source = factory.createMediaSource(MediaItem.fromUri(uri))
                    builder.add(
                        ClippingMediaSource.Builder(source)
                            .setStartPositionMs(segment.sourceStartMs)
                            .setEndPositionMs(segment.sourceEndMs)
                            .build(),
                        segment.durationMs,
                    )
                }

                is SilenceSegment -> builder.add(
                    SilenceMediaSource(segment.durationMs * 1_000L),
                    segment.durationMs,
                )
            }
        }
        return builder.build()
    }
}
