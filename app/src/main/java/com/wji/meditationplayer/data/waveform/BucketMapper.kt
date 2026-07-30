package com.wji.meditationplayer.data.waveform

/**
 * frame 位置與波形 bucket 之間的對映。
 *
 * 存在的理由是效能：原本每個 frame 都做一次 `frame * bucketCount / totalFrames`，
 * 20 分鐘立體聲就是五千萬次 64-bit 除法。改成先問「這個 bucket 到哪個 frame 結束」，
 * 就能一整段一整段地掃，除法次數從「每個 frame 一次」降到「每個 bucket 一次」。
 *
 * [bucketFor] 保留原本的公式，供測試驗證遞增版本逐點等價。
 */
class BucketMapper(
    private val totalFrames: Long,
    val bucketCount: Int,
) {
    init {
        require(totalFrames > 0L) { "totalFrames must be positive, was $totalFrames" }
        require(bucketCount > 0) { "bucketCount must be positive, was $bucketCount" }
    }

    /** 原始定義：frame 落在哪個 bucket。 */
    fun bucketFor(frame: Long): Int =
        (frame * bucketCount / totalFrames).toInt().coerceIn(0, bucketCount - 1)

    /**
     * 第 [bucket] 個 bucket 的結束 frame（exclusive）。
     *
     * 由 `bucketFor` 反推：`frame * bucketCount / totalFrames == bucket` 的最大 frame
     * 滿足 `frame < (bucket + 1) * totalFrames / bucketCount`，所以邊界取上取整。
     */
    fun endFrameOf(bucket: Int): Long {
        if (bucket >= bucketCount - 1) return Long.MAX_VALUE
        val numerator = (bucket + 1).toLong() * totalFrames
        return (numerator + bucketCount - 1) / bucketCount
    }

    /**
     * 掃到 [frame] 為止，有幾個 bucket 已經填滿。
     *
     * 刻意**不**夾到 `bucketCount - 1`（[bucketFor] 會）：掃完整個檔案時要回傳
     * `bucketCount`，收尾補值才不會把最後一個正常填好的 bucket 蓋掉。
     */
    fun bucketsFilledBy(frame: Long): Int =
        (frame * bucketCount / totalFrames).toInt().coerceIn(0, bucketCount)
}
