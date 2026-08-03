package com.wji.meditationplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.wji.meditationplayer.MainActivity
import com.wji.meditationplayer.data.db.GapCodec
import com.wji.meditationplayer.domain.EffectiveTimeline

/**
 * 背景播放服務。負責把「原始音檔 + 插入點」組成播放來源，並在邊界做音量淡變。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var fadeController: FadeController
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .apply {
                // 靜默段可長達 50 分鐘，沒有這行會在 doze 期間被中斷。
                setWakeMode(C.WAKE_MODE_LOCAL)
            }

        fadeController = FadeController(player).apply { start() }

        session = MediaSession.Builder(this, player)
            .setCallback(LoadCommandCallback())
            .setSessionActivity(openPlayerIntent(null))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        fadeController.stop()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    /**
     * 通知卡片的點擊目標。帶上 [fileKey] 才能讓 [MainActivity] 導回該音檔的播放畫面
     * （fileKey 為 null 時退回原本行為：開 app 落在最近清單）。
     *
     * requestCode 固定 0 配 `FLAG_UPDATE_CURRENT` 是刻意的：`Intent.filterEquals` 不看 extras，
     * 所以換音檔時正好會**更新**既有 PendingIntent 的 extras。若改成每個 fileKey 一組
     * requestCode，會留下一堆各自指向舊音檔的 PendingIntent。
     */
    private fun openPlayerIntent(fileKey: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!fileKey.isNullOrEmpty()) {
            intent.putExtra(MainActivity.EXTRA_FILE_KEY, fileKey)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private inner class LoadCommandCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(PlaybackCommands.ACTION_LOAD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != PlaybackCommands.ACTION_LOAD) {
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED),
                )
            }
            val loaded = load(args)
            return Futures.immediateFuture(
                SessionResult(
                    if (loaded) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_BAD_VALUE,
                ),
            )
        }
    }

    private fun load(args: Bundle): Boolean {
        val uriString = args.getString(PlaybackCommands.KEY_URI) ?: return false
        val durationMs = args.getLong(PlaybackCommands.KEY_DURATION_MS)
        if (durationMs <= 0L) return false

        val uri = uriString.toUri()
        val timeline = EffectiveTimeline(
            sourceDurationMs = durationMs,
            gaps = GapCodec.decode(args.getString(PlaybackCommands.KEY_GAPS)),
            fadeMs = args.getLong(PlaybackCommands.KEY_FADE_MS),
        )

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(args.getString(PlaybackCommands.KEY_TITLE).orEmpty())
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

        val source = MediaSourceBuilder.build(this, uri, timeline, mediaItem) ?: return false

        // 先掛上時間軸，避免 prepare 後的第一個 tick 用到舊的淡變曲線。
        fadeController.setTimeline(timeline)
        player.setMediaSource(source)
        player.prepare()
        player.seekTo(args.getLong(PlaybackCommands.KEY_START_POSITION_MS))
        player.playWhenReady = args.getBoolean(PlaybackCommands.KEY_PLAY_WHEN_READY, false)

        // 通知卡片要指回這個音檔；指紋則讓重新連上的 UI 知道不必重載（重載會把位置歸零）。
        session?.setSessionActivity(openPlayerIntent(args.getString(PlaybackCommands.KEY_FILE_KEY)))
        session?.setSessionExtras(
            Bundle().apply {
                putString(
                    PlaybackCommands.KEY_SIGNATURE,
                    args.getString(PlaybackCommands.KEY_SIGNATURE),
                )
            },
        )
        return true
    }
}
