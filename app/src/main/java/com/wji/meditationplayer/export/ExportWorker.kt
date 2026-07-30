package com.wji.meditationplayer.export

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wji.meditationplayer.container
import com.wji.meditationplayer.domain.EffectiveTimeline
import java.io.File

/**
 * 在背景把插入靜音後的結果寫成新檔案。
 *
 * 用 WorkManager 而非 ViewModel 的 scope：加了 50 分鐘留白的檔案重新編碼可能要跑
 * 好幾分鐘，不該因為使用者切換 app 就中斷。
 */
@UnstableApi
class ExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fileKey = inputData.getString(KEY_FILE_KEY) ?: return Result.failure()
        val destination = inputData.getString(KEY_DESTINATION)?.toUri() ?: return Result.failure()

        val repository = (applicationContext as Application).container.trackRepository
        val track = repository.findByKey(fileKey) ?: return Result.failure()

        val activeGaps = if (track.silenceEnabled) track.gaps else emptyList()
        val timeline = EffectiveTimeline(track.durationMs, activeGaps, track.fadeMs)
        if (timeline.segments.isEmpty()) return Result.failure()

        setForeground(foregroundInfo(0))

        val temp = File(applicationContext.cacheDir, "export-$fileKey.m4a")
        val exported = AudioExporter(applicationContext).export(
            uri = track.uri.toUri(),
            timeline = timeline,
            outputFile = temp,
        ) { progress ->
            setProgressAsync(workDataOf(KEY_PROGRESS to (progress * 100).toInt()))
        }

        if (!exported) {
            temp.delete()
            return Result.failure()
        }

        val copied = runCatching {
            applicationContext.contentResolver.openOutputStream(destination)?.use { output ->
                temp.inputStream().use { it.copyTo(output) }
            } ?: error("cannot open destination")
        }.isSuccess

        temp.delete()
        return if (copied) Result.success() else Result.failure()
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "匯出音檔",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("正在匯出音檔")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_FILE_KEY = "fileKey"
        const val KEY_DESTINATION = "destination"
        const val KEY_PROGRESS = "progress"

        const val UNIQUE_WORK_PREFIX = "export-"

        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 4211
    }
}
