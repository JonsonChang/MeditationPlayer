package com.wji.meditationplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wji.meditationplayer.data.waveform.PeakAccumulator
import com.wji.meditationplayer.data.waveform.WaveformExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * 量測波形計算的熱迴圈成本，新舊實作在**同一次執行、同一批資料**上對比 ——
 * 跨 build 比較會被裝置狀態、JIT、溫控干擾，同一次跑才有可比性。
 *
 * 刻意用合成的 PCM 緩衝而非真實音檔：
 *  - 不需要 100MB+ 的素材（模擬器 /data 只剩幾百 MB，寫進 cacheDir 會被系統回收）
 *  - 排除解碼成本，單純量「我們自己的計算」，這正是本次優化的對象
 *
 * 解碼成本另外由 PlaybackEngineTest 與實機操作觀察，兩者相加才是使用者的等待時間。
 */
@RunWith(AndroidJUnit4::class)
class WaveformBenchmarkTest {

    private companion object {
        const val TAG = "WaveformBenchmark"

        /** 20 分鐘 44.1kHz 立體聲：52,920,000 frames / 105,840,000 samples。 */
        const val MINUTES = 20
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2

        /** 貼近 MediaCodec 實際的輸出緩衝大小。 */
        const val SAMPLES_PER_BUFFER = 4096
    }

    private val totalFrames = MINUTES.toLong() * 60L * SAMPLE_RATE
    private val totalSamples = totalFrames * CHANNELS

    /** 改寫前的實作，原封不動保留為對照組。 */
    private fun legacyRun(chunk: ByteArray, repeats: Int, bucketCount: Int): ShortArray {
        val peaks = ShortArray(bucketCount)
        var frame = 0L
        repeat(repeats) {
            val shorts = ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder()).asShortBuffer()
            while (shorts.remaining() >= CHANNELS) {
                var peak = 0
                repeat(CHANNELS) { peak = max(peak, abs(shorts.get().toInt())) }
                val bucket = (frame * bucketCount / totalFrames).toInt()
                    .coerceIn(0, bucketCount - 1)
                val clamped = peak.coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
                if (clamped > peaks[bucket]) peaks[bucket] = clamped
                frame++
            }
        }
        return peaks
    }

    private fun newRun(chunk: ByteArray, repeats: Int, bucketCount: Int): ShortArray {
        val accumulator = PeakAccumulator(totalFrames, bucketCount, CHANNELS)
        repeat(repeats) {
            accumulator.feed(ByteBuffer.wrap(chunk), isFloat = false)
        }
        return accumulator.snapshot()
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }

    @Test
    fun compareLegacyAndOptimisedAccumulation() {
        val random = Random(1234)
        val chunk = ByteArray(SAMPLES_PER_BUFFER * 2)
        ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder()).also { buffer ->
            repeat(SAMPLES_PER_BUFFER) {
                buffer.putShort(random.nextInt(-32768, 32768).toShort())
            }
        }
        val repeats = (totalSamples / SAMPLES_PER_BUFFER).toInt()

        // 先各跑一次熱身，讓 JIT 編譯完成，避免把編譯時間算進去
        legacyRun(chunk, repeats = 32, bucketCount = 512)
        newRun(chunk, repeats = 32, bucketCount = 512)

        val legacyOld = measure { legacyRun(chunk, repeats, bucketCount = 3000) }
        val legacyAt512 = measure { legacyRun(chunk, repeats, bucketCount = 512) }
        val optimised = measure { newRun(chunk, repeats, bucketCount = 512) }

        // 順便再確認一次：改快不能改變結果
        assertArrayEquals(
            "優化後的輸出必須與舊演算法完全相同",
            legacyRun(chunk, repeats, bucketCount = 512),
            newRun(chunk, repeats, bucketCount = 512),
        )

        Log.i(
            TAG,
            """
            |
            |======== 波形計算成本（$MINUTES 分鐘 ${SAMPLE_RATE}Hz ${CHANNELS}ch）========
            |  frames                = $totalFrames
            |  samples               = $totalSamples
            |  舊實作 (3000 buckets) = ${legacyOld}ms      <- 使用者遇到的版本
            |  舊實作 (512 buckets)  = ${legacyAt512}ms     <- 只改 bucket 數的效果
            |  新實作 (512 buckets)  = ${optimised}ms       <- bulk copy + 遞增 bucket
            |  加速倍數              = ${"%.1f".format(legacyOld.toDouble() / max(optimised, 1L))}x
            |  BUCKETS 常數          = ${WaveformExtractor.BUCKETS}
            |============================================================
            """.trimMargin(),
        )

        assertTrue("新實作不應該比舊實作慢", optimised <= legacyOld)
    }
}
