package com.wji.meditationplayer.data.db

import com.wji.meditationplayer.domain.Gap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapCodecTest {

    @Test
    fun `round trips a gap list`() {
        val gaps = listOf(
            Gap(atMs = 0L, durationMs = 300_000L, enabled = true),
            Gap(atMs = 125_000L, durationMs = 3_000_000L, enabled = false),
        )
        assertEquals(gaps, GapCodec.decode(GapCodec.encode(gaps)))
    }

    @Test
    fun `empty list round trips`() {
        assertTrue(GapCodec.decode(GapCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `blank and null decode to empty`() {
        assertTrue(GapCodec.decode(null).isEmpty())
        assertTrue(GapCodec.decode("").isEmpty())
    }

    @Test
    fun `malformed entries are skipped rather than crashing`() {
        // 舊版格式或資料損壞時，寧可少讀幾個插入點也不要讓 app 開不起來。
        val decoded = GapCodec.decode("100,200,true;garbage;;5,x,true;300,400,false")
        assertEquals(
            listOf(Gap(100L, 200L, true), Gap(300L, 400L, false)),
            decoded,
        )
    }
}
