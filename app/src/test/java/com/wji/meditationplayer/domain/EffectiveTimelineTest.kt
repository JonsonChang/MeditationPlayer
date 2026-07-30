package com.wji.meditationplayer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EffectiveTimeline 是整個 app 的正確性核心：所有「插入後總長」、seek 映射、
 * 音量淡變都由它決定，因此邊界條件在這裡一次釘死。
 */
class EffectiveTimelineTest {

    private val minute = 60_000L

    private fun timeline(
        sourceDurationMs: Long = 10 * minute,
        gaps: List<Gap> = emptyList(),
        fadeMs: Long = 3_000L,
    ) = EffectiveTimeline(sourceDurationMs, gaps, fadeMs)

    // ---------- 結構 ----------

    @Test
    fun `no gaps produces one audio segment spanning the source`() {
        val t = timeline()
        assertEquals(1, t.segments.size)
        val audio = t.segments[0] as AudioSegment
        assertEquals(0L, audio.sourceStartMs)
        assertEquals(10 * minute, audio.sourceEndMs)
        assertEquals(10 * minute, t.totalDurationMs)
    }

    @Test
    fun `total duration adds only enabled gaps`() {
        val t = timeline(
            gaps = listOf(
                Gap(atMs = 2 * minute, durationMs = 5 * minute, enabled = true),
                Gap(atMs = 6 * minute, durationMs = 20 * minute, enabled = false),
            ),
        )
        assertEquals(15 * minute, t.totalDurationMs)
    }

    @Test
    fun `disabled gap produces no silence segment`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute, enabled = false)))
        assertEquals(1, t.segments.size)
        assertTrue(t.segments[0] is AudioSegment)
    }

    @Test
    fun `zero duration gap is ignored`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 0L)))
        assertEquals(1, t.segments.size)
        assertTrue(t.segments[0] is AudioSegment)
    }

    @Test
    fun `gap at position zero does not create a leading empty audio segment`() {
        // ConcatenatingMediaSource2 要求所有子來源非空，長度 0 的音訊段會讓播放器爆掉。
        val t = timeline(gaps = listOf(Gap(0L, 5 * minute)))
        assertEquals(2, t.segments.size)
        assertTrue(t.segments[0] is SilenceSegment)
        assertTrue(t.segments[1] is AudioSegment)
    }

    @Test
    fun `gap at end of source does not create a trailing empty audio segment`() {
        val t = timeline(gaps = listOf(Gap(10 * minute, 5 * minute)))
        assertEquals(2, t.segments.size)
        assertTrue(t.segments[0] is AudioSegment)
        assertTrue(t.segments[1] is SilenceSegment)
    }

    @Test
    fun `unsorted gaps are ordered by position`() {
        val t = timeline(
            gaps = listOf(
                Gap(6 * minute, 5 * minute),
                Gap(2 * minute, 5 * minute),
            ),
        )
        val silences = t.segments.filterIsInstance<SilenceSegment>()
        assertEquals(listOf(2 * minute, 6 * minute), silences.map { it.atSourceMs })
    }

    @Test
    fun `gaps at the same position are merged into one silence`() {
        val t = timeline(
            gaps = listOf(
                Gap(2 * minute, 5 * minute),
                Gap(2 * minute, 10 * minute),
            ),
        )
        val silences = t.segments.filterIsInstance<SilenceSegment>()
        assertEquals(1, silences.size)
        assertEquals(15 * minute, silences[0].durationMs)
    }

    @Test
    fun `gap position beyond source duration is clamped to the end`() {
        val t = timeline(gaps = listOf(Gap(99 * minute, 5 * minute)))
        val silence = t.segments.filterIsInstance<SilenceSegment>().single()
        assertEquals(10 * minute, silence.atSourceMs)
    }

    @Test
    fun `negative gap position is clamped to zero`() {
        val t = timeline(gaps = listOf(Gap(-5_000L, 5 * minute)))
        val silence = t.segments.filterIsInstance<SilenceSegment>().single()
        assertEquals(0L, silence.atSourceMs)
    }

    @Test
    fun `segment durations sum to total duration`() {
        val t = timeline(
            gaps = listOf(
                Gap(0L, 5 * minute),
                Gap(3 * minute, 7 * minute),
                Gap(10 * minute, 5 * minute),
            ),
        )
        assertEquals(t.totalDurationMs, t.segments.sumOf { it.durationMs })
    }

    @Test
    fun `segments are contiguous on the effective axis`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute), Gap(6 * minute, 9 * minute)))
        var expected = 0L
        t.segments.forEach { seg ->
            assertEquals(expected, seg.effectiveStartMs)
            expected += seg.durationMs
        }
        assertEquals(t.totalDurationMs, expected)
    }

    @Test
    fun `zero source duration produces no segments`() {
        val t = timeline(sourceDurationMs = 0L, gaps = listOf(Gap(0L, 5 * minute)))
        assertTrue(t.segments.isEmpty())
        assertEquals(0L, t.totalDurationMs)
    }

    // ---------- 位置映射 ----------

    @Test
    fun `toEffective shifts by preceding gaps only`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute)))
        assertEquals(minute, t.toEffective(minute))
        assertEquals(3 * minute + 5 * minute, t.toEffective(3 * minute))
    }

    @Test
    fun `toEffective at a gap position lands on the start of that silence`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute)))
        val silence = t.segments.filterIsInstance<SilenceSegment>().single()
        assertEquals(silence.effectiveStartMs, t.toEffective(2 * minute))
    }

    @Test
    fun `toEffective clamps out of range input`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute)))
        assertEquals(0L, t.toEffective(-1_000L))
        assertEquals(t.totalDurationMs, t.toEffective(99 * minute))
    }

    @Test
    fun `toOriginal inside silence returns the gap position`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute)))
        val silence = t.segments.filterIsInstance<SilenceSegment>().single()
        assertEquals(2 * minute, t.toOriginal(silence.effectiveStartMs + 3 * minute))
    }

    @Test
    fun `toOriginal inside audio maps back linearly`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute)))
        assertEquals(minute, t.toOriginal(minute))
        assertEquals(3 * minute, t.toOriginal(3 * minute + 5 * minute))
    }

    @Test
    fun `original to effective and back is lossless`() {
        val t = timeline(
            gaps = listOf(Gap(2 * minute, 5 * minute), Gap(6 * minute, 9 * minute)),
        )
        listOf(0L, minute, 2 * minute, 4 * minute, 6 * minute, 9 * minute, 10 * minute)
            .forEach { original ->
                assertEquals(
                    "round trip failed for $original",
                    original,
                    t.toOriginal(t.toEffective(original)),
                )
            }
    }

    // ---------- 音量淡變 ----------

    @Test
    fun `volume is zero inside silence`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute)))
        val silence = t.segments.filterIsInstance<SilenceSegment>().single()
        assertEquals(0f, t.volumeAt(silence.effectiveStartMs + minute), 0.0001f)
    }

    @Test
    fun `volume is full far from any boundary`() {
        val t = timeline(gaps = listOf(Gap(5 * minute, 5 * minute)))
        assertEquals(1f, t.volumeAt(minute), 0.0001f)
    }

    @Test
    fun `volume fades out approaching a silence`() {
        val fade = 3_000L
        val t = timeline(gaps = listOf(Gap(5 * minute, 5 * minute)), fadeMs = fade)
        val silenceStart = t.segments.filterIsInstance<SilenceSegment>().single().effectiveStartMs

        // 淡出區間外仍是滿音量
        assertEquals(1f, t.volumeAt(silenceStart - fade), 0.0001f)
        // 邊界上收斂到 0
        assertEquals(0f, t.volumeAt(silenceStart - 1), 0.01f)
        // 中點為 0.5（餘弦 S 曲線的對稱性）
        assertEquals(0.5f, t.volumeAt(silenceStart - fade / 2), 0.01f)
        // 單調遞減
        val samples = (0..10).map { t.volumeAt(silenceStart - fade + it * fade / 10) }
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue("expected monotonic decrease, got $samples", b <= a + 0.0001f)
        }
    }

    @Test
    fun `volume fades in after a silence`() {
        val fade = 3_000L
        val t = timeline(gaps = listOf(Gap(5 * minute, 5 * minute)), fadeMs = fade)
        val silence = t.segments.filterIsInstance<SilenceSegment>().single()
        val resume = silence.effectiveStartMs + silence.durationMs

        assertEquals(0f, t.volumeAt(resume), 0.01f)
        assertEquals(0.5f, t.volumeAt(resume + fade / 2), 0.01f)
        assertEquals(1f, t.volumeAt(resume + fade), 0.0001f)
    }

    @Test
    fun `no fade at track start or end when not adjacent to silence`() {
        // 原始音檔的開頭與結尾不該被改變聽感，只有插入點附近才淡變。
        val t = timeline(gaps = listOf(Gap(5 * minute, 5 * minute)))
        assertEquals(1f, t.volumeAt(0L), 0.0001f)
        assertEquals(1f, t.volumeAt(t.totalDurationMs - 1), 0.0001f)
    }

    @Test
    fun `fade is clamped when the audio segment is shorter than the fade`() {
        // 兩個插入點只隔 2 秒，但 fade 設 10 秒：不可溢出到相鄰段，也不可超出 [0,1]。
        val fade = 10_000L
        val t = timeline(
            gaps = listOf(Gap(minute, 5 * minute), Gap(minute + 2_000L, 5 * minute)),
            fadeMs = fade,
        )
        val shortAudio = t.segments.filterIsInstance<AudioSegment>()
            .single { it.durationMs == 2_000L }

        for (offset in 0..2_000L step 100L) {
            val v = t.volumeAt(shortAudio.effectiveStartMs + offset)
            assertTrue("volume $v out of range at offset $offset", v in 0f..1f)
        }
        // 頭尾都緊鄰靜默，兩端都必須接近 0
        assertEquals(0f, t.volumeAt(shortAudio.effectiveStartMs), 0.01f)
        assertEquals(0f, t.volumeAt(shortAudio.effectiveStartMs + 2_000L - 1), 0.05f)
    }

    @Test
    fun `zero fade keeps full volume up to the boundary`() {
        val t = timeline(gaps = listOf(Gap(5 * minute, 5 * minute)), fadeMs = 0L)
        val silenceStart = t.segments.filterIsInstance<SilenceSegment>().single().effectiveStartMs
        assertEquals(1f, t.volumeAt(silenceStart - 1), 0.0001f)
        assertEquals(0f, t.volumeAt(silenceStart), 0.0001f)
    }

    @Test
    fun `volume stays in range across the whole timeline`() {
        val t = timeline(
            gaps = listOf(Gap(0L, 5 * minute), Gap(3 * minute, 5 * minute), Gap(10 * minute, 5 * minute)),
            fadeMs = 4_000L,
        )
        for (pos in 0..t.totalDurationMs step 500L) {
            val v = t.volumeAt(pos)
            assertTrue("volume $v out of range at $pos", v in 0f..1f)
        }
    }

    // ---------- 靜默剩餘時間 ----------

    @Test
    fun `silence remaining counts down inside a silence`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute)))
        val silence = t.segments.filterIsInstance<SilenceSegment>().single()
        assertEquals(4 * minute, t.silenceRemainingMs(silence.effectiveStartMs + minute))
    }

    @Test
    fun `silence remaining is null outside a silence`() {
        val t = timeline(gaps = listOf(Gap(2 * minute, 5 * minute)))
        assertNull(t.silenceRemainingMs(minute))
    }
}
