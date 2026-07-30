package com.wji.meditationplayer.domain

import kotlin.math.min

/** 插入後時間軸上的一段。 */
sealed interface Segment {
    val effectiveStartMs: Long
    val durationMs: Long
    val effectiveEndMs: Long get() = effectiveStartMs + durationMs
}

/** 取自原始音檔 [sourceStartMs, sourceEndMs) 的一段。 */
data class AudioSegment(
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    override val effectiveStartMs: Long,
) : Segment {
    override val durationMs: Long get() = sourceEndMs - sourceStartMs
}

/** 插入的靜默段，[atSourceMs] 是它在原始時間軸上的位置。 */
data class SilenceSegment(
    val atSourceMs: Long,
    override val durationMs: Long,
    override val effectiveStartMs: Long,
) : Segment

/**
 * 把「原始音檔 + 一組插入點」換算成播放器實際看到的時間軸。
 *
 * 原始音檔完全不被修改；這個類別只描述要怎麼把它切片、以及在哪裡塞靜默。
 * 播放端據此組出 ClippingMediaSource / SilenceMediaSource，UI 據此顯示總長與進度。
 */
class EffectiveTimeline(
    val sourceDurationMs: Long,
    gaps: List<Gap>,
    val fadeMs: Long,
) {
    val segments: List<Segment>

    init {
        segments = if (sourceDurationMs <= 0L) emptyList() else buildSegments(gaps)
    }

    val totalDurationMs: Long = segments.sumOf { it.durationMs }

    private fun buildSegments(gaps: List<Gap>): List<Segment> {
        // 正規化：濾掉停用與零長度、夾進合法範圍、同位置合併、依位置排序。
        val merged = gaps
            .asSequence()
            .filter { it.enabled && it.durationMs > 0L }
            .map { it.atMs.coerceIn(0L, sourceDurationMs) to it.durationMs }
            .groupBy({ it.first }, { it.second })
            .map { (at, durations) -> at to durations.sum() }
            .sortedBy { it.first }

        val result = mutableListOf<Segment>()
        var sourceCursor = 0L
        var effectiveCursor = 0L

        for ((at, silenceDuration) in merged) {
            // 長度 0 的音訊段必須略過：ConcatenatingMediaSource2 要求子來源非空。
            if (at > sourceCursor) {
                result += AudioSegment(sourceCursor, at, effectiveCursor)
                effectiveCursor += at - sourceCursor
                sourceCursor = at
            }
            result += SilenceSegment(at, silenceDuration, effectiveCursor)
            effectiveCursor += silenceDuration
        }

        if (sourceCursor < sourceDurationMs) {
            result += AudioSegment(sourceCursor, sourceDurationMs, effectiveCursor)
        }
        return result
    }

    private fun segmentIndexAt(effectiveMs: Long): Int {
        if (segments.isEmpty()) return -1
        if (effectiveMs < 0L) return 0
        val index = segments.indexOfFirst { effectiveMs < it.effectiveEndMs }
        return if (index >= 0) index else segments.lastIndex
    }

    /** 找出包含 [effectiveMs] 的段；超出範圍時夾到頭尾。 */
    fun segmentAt(effectiveMs: Long): Segment? = segments.getOrNull(segmentIndexAt(effectiveMs))

    /**
     * 原始時間軸 → 插入後時間軸：原位置加上所有排在它**之前**的靜默總長。
     *
     * 用嚴格小於，所以位置剛好等於某個插入點時會落在該靜默段的**起點**
     * （即前一段音訊的終點），語意是「seek 到插入點就等於留白開始」。
     */
    fun toEffective(originalMs: Long): Long {
        if (segments.isEmpty()) return 0L
        val clamped = originalMs.coerceIn(0L, sourceDurationMs)
        val shift = segments.filterIsInstance<SilenceSegment>()
            .filter { it.atSourceMs < clamped }
            .sumOf { it.durationMs }
        return clamped + shift
    }

    /**
     * 插入後時間軸 → 原始時間軸。
     *
     * 靜默段內的所有位置都會映射到該插入點的原始位置，因此這個方向是有損的
     * （`toEffective(toOriginal(x))` 會回到靜默段起點，而非 x）。
     */
    fun toOriginal(effectiveMs: Long): Long {
        val segment = segmentAt(effectiveMs) ?: return 0L
        return when (segment) {
            is SilenceSegment -> segment.atSourceMs
            is AudioSegment -> {
                val offset = (effectiveMs - segment.effectiveStartMs).coerceIn(0L, segment.durationMs)
                segment.sourceStartMs + offset
            }
        }
    }

    /**
     * [effectiveMs] 應套用的音量（0..1）。
     *
     * 只有緊鄰插入靜默的邊界才淡變 —— 原始音檔的開頭與結尾保持原本聽感。
     * 這同時也遮蔽了 SilenceMediaSource 固定 44.1kHz/stereo 與來源格式不同時
     * ExoPlayer 重設 AudioTrack 造成的極短不連續。
     */
    fun volumeAt(effectiveMs: Long): Float {
        val index = segmentIndexAt(effectiveMs)
        val segment = segments.getOrNull(index) ?: return 1f
        if (segment is SilenceSegment) return 0f

        val fadesIn = segments.getOrNull(index - 1) is SilenceSegment
        val fadesOut = segments.getOrNull(index + 1) is SilenceSegment
        if (!fadesIn && !fadesOut) return 1f

        // 兩端都要淡變時各分一半，避免互相重疊。
        val budget = if (fadesIn && fadesOut) segment.durationMs / 2 else segment.durationMs
        val fade = min(fadeMs, budget)
        if (fade <= 0L) return 1f

        var volume = 1f
        if (fadesIn) {
            val elapsed = effectiveMs - segment.effectiveStartMs
            if (elapsed < fade) volume = min(volume, FadeCurve.gain(elapsed.toFloat() / fade))
        }
        if (fadesOut) {
            val remaining = segment.effectiveEndMs - effectiveMs
            if (remaining < fade) volume = min(volume, FadeCurve.gain(remaining.toFloat() / fade))
        }
        return volume.coerceIn(0f, 1f)
    }

    /** 若 [effectiveMs] 落在靜默段，回傳剩餘毫秒；否則 null。 */
    fun silenceRemainingMs(effectiveMs: Long): Long? {
        val segment = segmentAt(effectiveMs) ?: return null
        if (segment !is SilenceSegment) return null
        return (segment.effectiveEndMs - effectiveMs).coerceAtLeast(0L)
    }
}
