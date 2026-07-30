package com.wji.meditationplayer

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * 產生測試用的 WAV 檔，不必在 repo 裡放二進位素材。
 *
 * 預設刻意用 48kHz 單聲道 —— 跟 SilenceMediaSource 寫死的 44.1kHz stereo 不同，
 * 這樣才會走到跨邊界重設 AudioTrack 的那條路徑。
 */
object TestAudio {

    /** 每 4 秒換一次振幅，讓波形有可辨識的結構。 */
    private val ENVELOPE = floatArrayOf(0.9f, 0.25f, 0.7f, 0.1f, 0.85f)

    fun writeWav(
        file: File,
        durationMs: Long,
        sampleRate: Int = 48_000,
        channels: Int = 1,
    ): File {
        val frames = (durationMs * sampleRate / 1000L).toInt()
        val dataSize = frames * channels * 2

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
            fun le16(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
            }
            fun le32(value: Int) {
                le16(value and 0xFFFF)
                le16((value shr 16) and 0xFFFF)
            }

            ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
            ascii("fmt "); le32(16)
            le16(1); le16(channels); le32(sampleRate)
            le32(sampleRate * channels * 2); le16(channels * 2); le16(16)
            ascii("data"); le32(dataSize)

            for (i in 0 until frames) {
                val amplitude = ENVELOPE[((i / sampleRate) / 4) % ENVELOPE.size]
                val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * amplitude * Short.MAX_VALUE)
                    .toInt()
                repeat(channels) { le16(sample and 0xFFFF) }
            }
        }
        return file
    }
}
