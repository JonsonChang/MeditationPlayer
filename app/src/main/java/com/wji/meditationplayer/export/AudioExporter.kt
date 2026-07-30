package com.wji.meditationplayer.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableSet
import com.wji.meditationplayer.domain.AudioSegment
import com.wji.meditationplayer.domain.EffectiveTimeline
import com.wji.meditationplayer.domain.SilenceSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.coroutines.resume

/**
 * 把「原始音檔 + 插入點」匯出成一個新音檔。原始檔全程唯讀。
 *
 * 輸出容器是 Transformer 支援的 MP4（副檔名 .m4a / AAC 音訊）；來源是 mp3 時
 * 會重新編碼一次，這是 Transformer 的限制，不影響「不修改原檔」的要求。
 */
@UnstableApi
class AudioExporter(private val context: Context) {

    suspend fun export(
        uri: Uri,
        timeline: EffectiveTimeline,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.Main) {
        if (timeline.segments.isEmpty()) return@withContext false

        val sequenceBuilder = EditedMediaItemSequence.Builder(
            ImmutableSet.of(C.TRACK_TYPE_AUDIO),
        )

        timeline.segments.forEachIndexed { index, segment ->
            when (segment) {
                is AudioSegment -> {
                    // 只有緊鄰插入靜默的那一端才淡變，與播放期規則一致。
                    val fadeIn = if (timeline.segments.getOrNull(index - 1) is SilenceSegment) {
                        timeline.fadeMs
                    } else {
                        0L
                    }
                    val fadeOut = if (timeline.segments.getOrNull(index + 1) is SilenceSegment) {
                        timeline.fadeMs
                    } else {
                        0L
                    }
                    val clip = MediaItem.Builder()
                        .setUri(uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(segment.sourceStartMs)
                                .setEndPositionMs(segment.sourceEndMs)
                                .build(),
                        )
                        .build()
                    sequenceBuilder.addItem(
                        EditedMediaItem.Builder(clip)
                            .setRemoveVideo(true)
                            .setEffects(
                                Effects(
                                    listOf(
                                        FadeAudioProcessor(
                                            clipDurationMs = segment.durationMs,
                                            fadeInMs = fadeIn,
                                            fadeOutMs = fadeOut,
                                        ),
                                    ),
                                    emptyList(),
                                ),
                            )
                            .build(),
                    )
                }

                is SilenceSegment -> sequenceBuilder.addGap(segment.durationMs * 1_000L)
            }
        }

        val composition = Composition.Builder(sequenceBuilder.build()).build()
        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()

        coroutineScope {
            val poller = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    delay(PROGRESS_POLL_MS)
                }
            }
            try {
                awaitCompletion(transformer, composition, outputFile)
            } finally {
                poller.cancel()
            }
        }
    }

    private suspend fun awaitCompletion(
        transformer: Transformer,
        composition: Composition,
        outputFile: File,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                Log.e(TAG, "匯出失敗: ${exportException.errorCode}", exportException)
                if (continuation.isActive) continuation.resume(false)
            }
        })
        continuation.invokeOnCancellation { transformer.cancel() }
        transformer.start(composition, outputFile.absolutePath)
    }

    private companion object {
        const val TAG = "AudioExporter"
        const val PROGRESS_POLL_MS = 300L
    }
}
