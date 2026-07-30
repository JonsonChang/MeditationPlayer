package com.wji.meditationplayer.data.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遞增推進取代了 per-frame 的除法，這裡的重點就是證明兩者**逐點等價** ——
 * 這種邊界推導最容易差一格，而差一格在波形上幾乎看不出來，所以必須靠測試釘死。
 */
class BucketMapperTest {

    @Test
    fun `endFrameOf is consistent with bucketFor across many shapes`() {
        val shapes = listOf(
            1L to 1, 1L to 512, 512L to 512, 513L to 512, 1000L to 7,
            57_600_000L to 512,     // 20 分鐘 48kHz 單聲道
            52_920_000L to 512,     // 20 分鐘 44.1kHz
            999_983L to 512,        // 質數，逼出取整誤差
            7L to 512,              // bucket 比 frame 還多
        )
        for ((totalFrames, bucketCount) in shapes) {
            val mapper = BucketMapper(totalFrames, bucketCount)
            var bucket = 0
            var end = mapper.endFrameOf(0)
            for (frame in 0 until totalFrames) {
                while (frame >= end && bucket < bucketCount - 1) {
                    bucket++
                    end = mapper.endFrameOf(bucket)
                }
                assertEquals(
                    "totalFrames=$totalFrames bucketCount=$bucketCount frame=$frame",
                    mapper.bucketFor(frame),
                    bucket,
                )
            }
        }
    }

    @Test
    fun `bucketFor stays in range at the boundaries`() {
        val mapper = BucketMapper(totalFrames = 1000L, bucketCount = 10)
        assertEquals(0, mapper.bucketFor(0L))
        assertEquals(9, mapper.bucketFor(999L))
        // 超出範圍要被夾住而不是丟例外或回負數
        assertEquals(9, mapper.bucketFor(1000L))
        assertEquals(9, mapper.bucketFor(10_000_000L))
        assertEquals(0, mapper.bucketFor(-5L))
    }

    @Test
    fun `last bucket never ends so the scan cannot run past it`() {
        val mapper = BucketMapper(totalFrames = 1000L, bucketCount = 10)
        assertEquals(Long.MAX_VALUE, mapper.endFrameOf(9))
        assertEquals(Long.MAX_VALUE, mapper.endFrameOf(99))
    }

    @Test
    fun `bucketsFilledBy reports full coverage at end of stream`() {
        val mapper = BucketMapper(totalFrames = 1000L, bucketCount = 10)
        assertEquals(0, mapper.bucketsFilledBy(0L))
        assertEquals(5, mapper.bucketsFilledBy(500L))
        // 關鍵：掃完整個檔案要回傳 bucketCount，收尾補值才不會蓋掉最後一格
        assertEquals(10, mapper.bucketsFilledBy(1000L))
    }

    @Test
    fun `rejects degenerate construction`() {
        listOf(0L to 512, -1L to 512, 100L to 0, 100L to -3).forEach { (frames, buckets) ->
            val threw = runCatching { BucketMapper(frames, buckets) }.isFailure
            assertTrue("BucketMapper($frames, $buckets) 應該要拒絕", threw)
        }
    }
}
