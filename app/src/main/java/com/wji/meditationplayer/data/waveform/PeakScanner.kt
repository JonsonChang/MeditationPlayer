package com.wji.meditationplayer.data.waveform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 掃出一個 PCM 緩衝裡的最大振幅。
 *
 * 供抽樣模式使用：每個 bucket 只解一小段**連續**視窗，然後掃過那段裡的每一個
 * sample 取最大值。因為是連續視窗而非固定間隔的單點取樣，所以不會有 aliasing ——
 * 固定間隔抽單點可能持續命中正弦波的零交越點，把大聲段誤判成安靜。
 *
 * 與 [PeakAccumulator] 一樣用 bulk copy 進原生陣列後再跑迴圈，並重複使用暫存陣列。
 */
class PeakScanner {

    private var shortScratch = ShortArray(0)
    private var floatScratch = FloatArray(0)

    fun maxMagnitude(source: ByteBuffer, isFloat: Boolean): Int {
        val buffer = source.order(ByteOrder.nativeOrder())
        return if (isFloat) scanFloat(buffer) else scanShort(buffer)
    }

    private fun scanShort(buffer: ByteBuffer): Int {
        val shorts = buffer.asShortBuffer()
        val count = shorts.remaining()
        if (count == 0) return 0
        if (shortScratch.size < count) shortScratch = ShortArray(count)
        shorts.get(shortScratch, 0, count)

        var peak = 0
        var i = 0
        while (i < count) {
            val value = shortScratch[i].toInt()
            val magnitude = if (value < 0) -value else value
            if (magnitude > peak) peak = magnitude
            i++
        }
        return min(peak, Short.MAX_VALUE.toInt())
    }

    private fun scanFloat(buffer: ByteBuffer): Int {
        val floats = buffer.asFloatBuffer()
        val count = floats.remaining()
        if (count == 0) return 0
        if (floatScratch.size < count) floatScratch = FloatArray(count)
        floats.get(floatScratch, 0, count)

        var peak = 0f
        var i = 0
        while (i < count) {
            val value = floatScratch[i]
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
            i++
        }
        return (min(peak, 1f) * Short.MAX_VALUE).roundToInt()
    }
}
