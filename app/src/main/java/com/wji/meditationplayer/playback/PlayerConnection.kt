package com.wji.meditationplayer.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 與 PlaybackService 的連線。UI 透過它下載入指令與一般的播放控制。
 *
 * 必須在主執行緒使用 MediaController。
 */
@UnstableApi
class PlayerConnection(private val context: Context) {

    var controller: MediaController? = null
        private set

    suspend fun connect(): MediaController? {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val result = suspendCancellableCoroutine { continuation ->
            future.addListener(
                { continuation.resume(runCatching { future.get() }.getOrNull()) },
                ContextCompat.getMainExecutor(context),
            )
        }
        controller = result
        return result
    }

    /** 服務端目前載入的設定指紋；null 表示尚未載入或尚未連線。 */
    fun loadedSignature(): String? =
        controller?.sessionExtras?.getString(PlaybackCommands.KEY_SIGNATURE)

    fun load(
        uri: String,
        title: String,
        fileKey: String,
        signature: String,
        durationMs: Long,
        encodedGaps: String,
        fadeMs: Long,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        val target = controller ?: return
        val args = Bundle().apply {
            putString(PlaybackCommands.KEY_URI, uri)
            putString(PlaybackCommands.KEY_TITLE, title)
            putString(PlaybackCommands.KEY_FILE_KEY, fileKey)
            putString(PlaybackCommands.KEY_SIGNATURE, signature)
            putLong(PlaybackCommands.KEY_DURATION_MS, durationMs)
            putString(PlaybackCommands.KEY_GAPS, encodedGaps)
            putLong(PlaybackCommands.KEY_FADE_MS, fadeMs)
            putLong(PlaybackCommands.KEY_START_POSITION_MS, startPositionMs)
            putBoolean(PlaybackCommands.KEY_PLAY_WHEN_READY, playWhenReady)
        }
        target.sendCustomCommand(SessionCommand(PlaybackCommands.ACTION_LOAD, Bundle.EMPTY), args)
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
