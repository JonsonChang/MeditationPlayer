package com.wji.meditationplayer.domain

import kotlin.math.PI
import kotlin.math.cos

/**
 * 淡變曲線。播放期（EffectiveTimeline）與匯出期（FadeAudioProcessor）共用同一份，
 * 免得兩邊的聽感隨時間走鐘。
 */
object FadeCurve {

    /** 餘弦 S 曲線：gain(0)=0、gain(1)=1，頭尾斜率為 0，聽不出轉折。 */
    fun gain(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return (0.5 * (1.0 - cos(PI * clamped))).toFloat()
    }
}
