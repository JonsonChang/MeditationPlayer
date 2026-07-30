package com.wji.meditationplayer.data.waveform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class PeakScannerTest {

    private fun shortBuffer(vararg values: Short): ByteBuffer {
        val bytes = ByteArray(values.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        values.forEach { buffer.putShort(it) }
        return ByteBuffer.wrap(bytes)
    }

    private fun floatBuffer(vararg values: Float): ByteBuffer {
        val bytes = ByteArray(values.size * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        values.forEach { buffer.putFloat(it) }
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun `finds the largest magnitude regardless of sign`() {
        val scanner = PeakScanner()
        assertEquals(
            9000,
            scanner.maxMagnitude(shortBuffer(100, -9000, 42, 8999), isFloat = false),
        )
    }

    @Test
    fun `clamps the most negative sample`() {
        // abs(Short.MIN_VALUE) = 32768 會溢出 Short，必須夾住
        val scanner = PeakScanner()
        assertEquals(
            Short.MAX_VALUE.toInt(),
            scanner.maxMagnitude(shortBuffer(Short.MIN_VALUE, 0), isFloat = false),
        )
    }

    @Test
    fun `empty buffer yields zero`() {
        val scanner = PeakScanner()
        assertEquals(0, scanner.maxMagnitude(shortBuffer(), isFloat = false))
    }

    @Test
    fun `reused scanner does not carry state between buffers`() {
        // 暫存陣列是重複使用的，前一個大聲的緩衝不能污染後一個安靜的
        val scanner = PeakScanner()
        assertEquals(30000, scanner.maxMagnitude(shortBuffer(30000, -1), isFloat = false))
        assertEquals(5, scanner.maxMagnitude(shortBuffer(5, -3), isFloat = false))
    }

    @Test
    fun `scales and clamps float pcm`() {
        val scanner = PeakScanner()
        assertEquals(
            (0.25f * Short.MAX_VALUE).roundToInt(),
            scanner.maxMagnitude(floatBuffer(0.1f, -0.25f, 0.2f), isFloat = true),
        )
        assertEquals(
            Short.MAX_VALUE.toInt(),
            scanner.maxMagnitude(floatBuffer(-4.5f, 0.1f), isFloat = true),
        )
    }
}
