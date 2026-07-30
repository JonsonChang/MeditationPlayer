package com.wji.meditationplayer.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.wji.meditationplayer.domain.FadeCurve
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 對單一音訊切片的頭／尾套用淡變，供匯出使用。
 *
 * 匯出時每個 clip 自己就知道要不要在頭或尾淡變（頭尾是否緊鄰插入的靜默），
 * 所以這裡比播放期的位置輪詢更單純：只要數 frame 就好。
 */
@UnstableApi
class FadeAudioProcessor(
    private val clipDurationMs: Long,
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
) : BaseAudioProcessor() {

    private var framesProcessed = 0L
    private var totalFrames = 0L
    private var fadeInFrames = 0L
    private var fadeOutFrames = 0L

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val sampleRate = inputAudioFormat.sampleRate
        totalFrames = clipDurationMs * sampleRate / 1000L
        // 兩端都要淡變時各分一半，避免互相重疊（與 EffectiveTimeline 同一套規則）。
        val budget = if (fadeInMs > 0L && fadeOutMs > 0L) totalFrames / 2 else totalFrames
        fadeInFrames = minOf(fadeInMs * sampleRate / 1000L, budget)
        fadeOutFrames = minOf(fadeOutMs * sampleRate / 1000L, budget)
        return inputAudioFormat
    }

    /** 不需要淡變的切片直接被略過，不進這個 processor。 */
    override fun isActive(): Boolean = super.isActive() && (fadeInMs > 0L || fadeOutMs > 0L)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channelCount = inputAudioFormat.channelCount
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        if (bytesPerFrame <= 0) return

        val available = inputBuffer.remaining()
        val output = replaceOutputBuffer(available)
        val input = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val out = output.order(ByteOrder.nativeOrder()).asShortBuffer()

        while (input.remaining() >= channelCount) {
            val gain = gainForFrame(framesProcessed)
            repeat(channelCount) {
                val sample = input.get()
                out.put(if (gain >= 1f) sample else (sample * gain).roundToInt().toShort())
            }
            framesProcessed++
        }

        inputBuffer.position(inputBuffer.limit())
        output.position(out.position() * 2)
        output.flip()
    }

    private fun gainForFrame(frame: Long): Float {
        var gain = 1f
        if (fadeInFrames > 0L && frame < fadeInFrames) {
            gain = minOf(gain, FadeCurve.gain(frame.toFloat() / fadeInFrames))
        }
        if (fadeOutFrames > 0L && totalFrames > 0L) {
            val remaining = totalFrames - frame
            if (remaining in 0..fadeOutFrames) {
                gain = minOf(gain, FadeCurve.gain(remaining.toFloat() / fadeOutFrames))
            }
        }
        return gain.coerceIn(0f, 1f)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        framesProcessed = 0L
    }

    override fun onReset() {
        framesProcessed = 0L
        totalFrames = 0L
        fadeInFrames = 0L
        fadeOutFrames = 0L
    }
}
