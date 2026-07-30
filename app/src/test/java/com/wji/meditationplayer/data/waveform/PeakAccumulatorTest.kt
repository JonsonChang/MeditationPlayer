package com.wji.meditationplayer.data.waveform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * PeakAccumulator 是為了效能而重寫的熱迴圈，所以主測試就是「與改寫前的演算法
 * 逐格輸出完全相同」——效能改寫最怕的是悄悄改了結果。
 */
class PeakAccumulatorTest {

    /** 改寫前的實作，原封不動保留為對照組。 */
    private fun legacyPeaks(
        chunks: List<ByteArray>,
        totalFrames: Long,
        bucketCount: Int,
        channelCount: Int,
    ): ShortArray {
        val peaks = ShortArray(bucketCount)
        var frame = 0L
        for (chunk in chunks) {
            val shorts = ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder()).asShortBuffer()
            while (shorts.remaining() >= channelCount) {
                var peak = 0
                repeat(channelCount) { peak = max(peak, abs(shorts.get().toInt())) }
                val bucket = (frame * bucketCount / totalFrames).toInt()
                    .coerceIn(0, bucketCount - 1)
                val clamped = peak.coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
                if (clamped > peaks[bucket]) peaks[bucket] = clamped
                frame++
            }
        }
        val filledUpTo = (frame * bucketCount / totalFrames).toInt().coerceIn(0, bucketCount)
        for (i in filledUpTo until bucketCount) {
            peaks[i] = peaks.getOrElse(filledUpTo - 1) { 0 }
        }
        return peaks
    }

    private fun newPeaks(
        chunks: List<ByteArray>,
        totalFrames: Long,
        bucketCount: Int,
        channelCount: Int,
    ): ShortArray {
        val accumulator = PeakAccumulator(totalFrames, bucketCount, channelCount)
        chunks.forEach { chunk ->
            accumulator.feed(ByteBuffer.wrap(chunk), isFloat = false)
        }
        return accumulator.finish()
    }

    private fun randomChunks(
        random: Random,
        chunkCount: Int,
        samplesPerChunk: Int,
    ): List<ByteArray> = List(chunkCount) {
        val bytes = ByteArray(samplesPerChunk * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        repeat(samplesPerChunk) {
            buffer.putShort(random.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE + 1).toShort())
        }
        bytes
    }

    @Test
    fun `matches the legacy algorithm bit for bit`() {
        val random = Random(42)
        // 涵蓋單／立體聲、bucket 多於與少於 frame、緩衝切分不整齊等情形
        val cases = listOf(
            Triple(1, 64, 1024),
            Triple(2, 64, 1024),
            Triple(2, 33, 999),
            Triple(1, 7, 100),
            Triple(2, 128, 512),
            Triple(6, 20, 480),
        )
        for ((channels, chunkCount, samplesPerChunk) in cases) {
            val chunks = randomChunks(random, chunkCount, samplesPerChunk)
            val totalFrames = (chunkCount.toLong() * samplesPerChunk) / channels
            for (bucketCount in listOf(1, 7, 512, 4096)) {
                assertArrayEquals(
                    "channels=$channels chunks=$chunkCount samples=$samplesPerChunk buckets=$bucketCount",
                    legacyPeaks(chunks, totalFrames, bucketCount, channels),
                    newPeaks(chunks, totalFrames, bucketCount, channels),
                )
            }
        }
    }

    @Test
    fun `matches legacy when the stream is shorter than the declared duration`() {
        // 真實情況：MediaMetadataRetriever 的時長與實際解碼出的 frame 數常有落差，
        // 這時尾端補值的行為必須一致。
        val random = Random(7)
        val chunks = randomChunks(random, chunkCount = 10, samplesPerChunk = 500)
        val declaredFrames = 10_000L // 實際只有 5000 frames
        assertArrayEquals(
            legacyPeaks(chunks, declaredFrames, 64, 1),
            newPeaks(chunks, declaredFrames, 64, 1),
        )
    }

    @Test
    fun `follows a loud quiet loud envelope`() {
        val sampleRate = 1000
        val channels = 1
        fun tone(amplitude: Short, frames: Int): ByteArray {
            val bytes = ByteArray(frames * 2)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            repeat(frames) { buffer.putShort(amplitude) }
            return bytes
        }
        val chunks = listOf(
            tone(30_000, sampleRate),
            tone(1_000, sampleRate),
            tone(20_000, sampleRate),
        )
        val peaks = newPeaks(chunks, 3L * sampleRate, bucketCount = 3, channelCount = channels)
        assertEquals(30_000, peaks[0].toInt())
        assertEquals(1_000, peaks[1].toInt())
        assertEquals(20_000, peaks[2].toInt())
    }

    @Test
    fun `clamps the most negative sample instead of overflowing`() {
        // abs(Short.MIN_VALUE) = 32768，超過 Short.MAX_VALUE，若不夾住會變成負數。
        val bytes = ByteArray(4)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).apply {
            putShort(Short.MIN_VALUE)
            putShort(Short.MIN_VALUE)
        }
        val peaks = newPeaks(listOf(bytes), totalFrames = 2L, bucketCount = 1, channelCount = 1)
        assertEquals(Short.MAX_VALUE.toInt(), peaks[0].toInt())
    }

    @Test
    fun `snapshot exposes progress without leaking the live array`() {
        val chunks = randomChunks(Random(1), chunkCount = 4, samplesPerChunk = 256)
        val accumulator = PeakAccumulator(1024L, bucketCount = 4, channelCount = 1)

        accumulator.feed(ByteBuffer.wrap(chunks[0]), isFloat = false)
        val early = accumulator.snapshot()
        assertTrue("第一個 bucket 應已有值", early[0] > 0)
        assertEquals("尚未掃到的 bucket 應為 0", 0, early[3].toInt())

        accumulator.feed(ByteBuffer.wrap(chunks[1]), isFloat = false)
        val later = accumulator.snapshot()
        // 複本必須是獨立的：後續餵資料不能改到先前交出去的陣列
        assertEquals(0, early[3].toInt())
        assertTrue("進度應該前進", later[1] > 0)
    }

    @Test
    fun `float pcm produces the same envelope as equivalent 16 bit input`() {
        val frames = 300
        val bytes = ByteArray(frames * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        repeat(frames) { buffer.putFloat(0.5f) }

        val accumulator = PeakAccumulator(frames.toLong(), bucketCount = 3, channelCount = 1)
        accumulator.feed(ByteBuffer.wrap(bytes), isFloat = true)
        val peaks = accumulator.finish()

        // 與改寫前一致採四捨五入（舊實作用的也是 roundToInt）
        val expected = (0.5f * Short.MAX_VALUE).roundToInt()
        peaks.forEach { assertEquals(expected, it.toInt()) }
    }

    @Test
    fun `float pcm above full scale is clamped`() {
        val bytes = ByteArray(8)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).apply {
            putFloat(3.5f)
            putFloat(-9.0f)
        }
        val accumulator = PeakAccumulator(2L, bucketCount = 1, channelCount = 1)
        accumulator.feed(ByteBuffer.wrap(bytes), isFloat = true)
        assertEquals(Short.MAX_VALUE.toInt(), accumulator.finish()[0].toInt())
    }
}
