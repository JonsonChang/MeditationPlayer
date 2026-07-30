package com.wji.meditationplayer.data.waveform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把解碼出來的 PCM 併成每個 bucket 的振幅峰值。
 *
 * 從 [WaveformExtractor] 拆出來的理由有兩個：這段是效能熱點，而且拆出來後只依賴
 * `java.nio`，可以在沒有音檔、沒有解碼器、不開模擬器的情況下直接單元測試與量測。
 *
 * 速度來自兩件事：
 *  1. **bulk copy**：`ShortBuffer.get(array, ...)` 一次 memcpy，之後在原生陣列上跑迴圈，
 *     而不是每個 sample 一次 `ShortBuffer.get()`。
 *  2. **一次掃一個 bucket**：先算出離 bucket 邊界還有幾個 frame，內圈就退化成
 *     「線性掃過一段原生陣列取最大值」——沒有除法，也沒有逐 frame 的邊界判斷。
 *
 * 峰值定義與拆分前完全相同：bucket 內所有聲道所有 sample 的 `max(abs(sample))`。
 * 因為取的是最大值，聲道交錯的結構無關緊要，直接線性掃過整段即可。
 */
class PeakAccumulator(
    private val totalFrames: Long,
    bucketCount: Int,
    private val channelCount: Int,
) {
    private val mapper = BucketMapper(totalFrames, bucketCount)
    private val peaks = ShortArray(bucketCount)

    private var frame = 0L
    private var bucket = 0
    private var bucketEndFrame = mapper.endFrameOf(0)

    private var shortScratch = ShortArray(0)
    private var floatScratch = FloatArray(0)

    val framesProcessed: Long get() = frame

    /** [isFloat] 對應 `ENCODING_PCM_FLOAT`；用 Boolean 而非 android 常數以維持純 JVM。 */
    fun feed(source: ByteBuffer, isFloat: Boolean) {
        if (channelCount <= 0) return
        val buffer = source.order(ByteOrder.nativeOrder())
        if (isFloat) feedFloat(buffer) else feedShort(buffer)
    }

    private fun feedShort(buffer: ByteBuffer) {
        val shorts = buffer.asShortBuffer()
        val count = shorts.remaining()
        if (count < channelCount) return
        if (shortScratch.size < count) shortScratch = ShortArray(count)
        shorts.get(shortScratch, 0, count)

        val limit = count - count % channelCount
        var i = 0
        while (i < limit) {
            val take = framesToTake(limit - i)
            val stop = i + take * channelCount
            var localPeak = peaks[bucket].toInt()
            while (i < stop) {
                val value = shortScratch[i].toInt()
                val magnitude = if (value < 0) -value else value
                if (magnitude > localPeak) localPeak = magnitude
                i++
            }
            store(localPeak)
            advance(take)
        }
    }

    private fun feedFloat(buffer: ByteBuffer) {
        val floats = buffer.asFloatBuffer()
        val count = floats.remaining()
        if (count < channelCount) return
        if (floatScratch.size < count) floatScratch = FloatArray(count)
        floats.get(floatScratch, 0, count)

        val limit = count - count % channelCount
        var i = 0
        while (i < limit) {
            val take = framesToTake(limit - i)
            val stop = i + take * channelCount
            var localPeak = 0f
            while (i < stop) {
                val value = floatScratch[i]
                val magnitude = if (value < 0f) -value else value
                if (magnitude > localPeak) localPeak = magnitude
                i++
            }
            val scaled = (min(localPeak, 1f) * Short.MAX_VALUE).roundToInt()
            store(max(scaled, peaks[bucket].toInt()))
            advance(take)
        }
    }

    /** 這一輪最多能掃幾個 frame：受緩衝剩餘量與 bucket 邊界兩者夾住。 */
    private fun framesToTake(samplesLeft: Int): Int {
        val framesLeftInBuffer = samplesLeft / channelCount
        val framesLeftInBucket = bucketEndFrame - frame
        return if (framesLeftInBucket >= framesLeftInBuffer) {
            framesLeftInBuffer
        } else {
            framesLeftInBucket.toInt().coerceAtLeast(1)
        }
    }

    private fun store(localPeak: Int) {
        peaks[bucket] = min(localPeak, Short.MAX_VALUE.toInt()).toShort()
    }

    private fun advance(frames: Int) {
        frame += frames
        while (frame >= bucketEndFrame && bucket < mapper.bucketCount - 1) {
            bucket++
            bucketEndFrame = mapper.endFrameOf(bucket)
        }
    }

    /** 目前已填部分的複本，供 UI 邊解碼邊畫（不能把仍在被改寫的陣列交給 UI）。 */
    fun snapshot(): ShortArray = peaks.copyOf()

    /**
     * 收尾：把尾端因時長估算誤差而沒被填到的 bucket 用最後一個有效值補起來，
     * 避免波形右緣出現空洞。
     */
    fun finish(): ShortArray {
        val filledUpTo = mapper.bucketsFilledBy(frame)
        val fill = if (filledUpTo > 0) peaks[filledUpTo - 1] else 0
        for (i in filledUpTo until mapper.bucketCount) peaks[i] = fill
        return peaks
    }
}
