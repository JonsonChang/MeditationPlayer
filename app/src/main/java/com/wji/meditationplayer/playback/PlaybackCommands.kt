package com.wji.meditationplayer.playback

/**
 * UI 與 PlaybackService 之間的載入指令。
 *
 * 需要自訂指令是因為插入靜音要靠 ExoPlayer.setMediaSource()，而 MediaController
 * 只認得 MediaItem，無法傳遞組合好的 MediaSource。
 *
 * 靜音總開關不在這裡：關閉時 UI 直接送空的 [KEY_GAPS]，服務端不必知道這個概念。
 */
object PlaybackCommands {
    const val ACTION_LOAD = "com.wji.meditationplayer.action.LOAD"

    const val KEY_URI = "uri"
    const val KEY_TITLE = "title"

    /** 服務端用來組通知卡片的 PendingIntent，讓點擊能回到這個音檔的播放畫面。 */
    const val KEY_FILE_KEY = "fileKey"

    /**
     * 這次載入的設定指紋。服務端會回吐到 session extras，
     * UI 重新連上時才能判斷「服務是不是已經在播這一份設定」而不必重載。
     */
    const val KEY_SIGNATURE = "signature"

    const val KEY_DURATION_MS = "durationMs"
    const val KEY_GAPS = "gaps"
    const val KEY_FADE_MS = "fadeMs"
    const val KEY_START_POSITION_MS = "startPositionMs"
    const val KEY_PLAY_WHEN_READY = "playWhenReady"
}
