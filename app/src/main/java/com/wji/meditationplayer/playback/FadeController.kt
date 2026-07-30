package com.wji.meditationplayer.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.wji.meditationplayer.domain.EffectiveTimeline

/**
 * 依當前播放位置持續調整音量，讓進出插入靜默時不會有突然的變化。
 *
 * 用位置輪詢而非 sample-accurate 的 AudioProcessor：3 秒的淡變下 40ms 的誤差
 * 聽不出來，程式碼少很多。使用者 seek 進淡變區時走的是同一條路徑，
 * 所以不需要額外處理。
 */
class FadeController(private val player: ExoPlayer) {

    private val handler = Handler(Looper.getMainLooper())
    private var timeline: EffectiveTimeline? = null

    private val tick = object : Runnable {
        override fun run() {
            applyNow()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        player.volume = 1f
    }

    fun setTimeline(timeline: EffectiveTimeline?) {
        this.timeline = timeline
        applyNow()
    }

    private fun applyNow() {
        val current = timeline ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val target = current.volumeAt(player.currentPosition)
        if (player.volume != target) player.volume = target
    }

    private companion object {
        const val INTERVAL_MS = 40L
    }
}
