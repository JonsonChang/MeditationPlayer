package com.wji.meditationplayer.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.wji.meditationplayer.data.db.TrackDao
import com.wji.meditationplayer.data.db.TrackEntity
import com.wji.meditationplayer.domain.Gap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TrackRepository(
    private val context: Context,
    private val dao: TrackDao,
) {
    fun observeRecent(): Flow<List<TrackEntity>> = dao.observeRecent()

    fun observeTrack(fileKey: String): Flow<TrackEntity?> = dao.observeByKey(fileKey)

    suspend fun findByKey(fileKey: String): TrackEntity? = dao.findByKey(fileKey)

    /**
     * 記住使用者剛選的檔案並回傳它的設定。
     *
     * 同一個檔案第二次開啟時會沿用既有的插入點，只更新 URI（可能換了）與開啟時間。
     * 回傳 null 表示無法辨識或讀不到時長。
     */
    suspend fun openPickedFile(uri: Uri, nowMs: Long): TrackEntity? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val fileKey = FileKey.compute(context, uri) ?: return@withContext null
        val duration = readDurationMs(uri) ?: return@withContext null
        val name = FileKey.displayName(context, uri) ?: "unknown"

        val existing = dao.findByKey(fileKey)
        val track = existing?.copy(
            uri = uri.toString(),
            displayName = name,
            durationMs = duration,
            lastOpenedAt = nowMs,
        ) ?: TrackEntity(
            fileKey = fileKey,
            uri = uri.toString(),
            displayName = name,
            durationMs = duration,
            lastOpenedAt = nowMs,
        )
        dao.upsert(track)
        pruneOldPermissions()
        track
    }

    suspend fun touch(fileKey: String, nowMs: Long) {
        val track = dao.findByKey(fileKey) ?: return
        dao.upsert(track.copy(lastOpenedAt = nowMs))
    }

    suspend fun updateGaps(fileKey: String, gaps: List<Gap>) {
        val track = dao.findByKey(fileKey) ?: return
        dao.upsert(track.copy(gaps = gaps.sortedBy { it.atMs }))
    }

    suspend fun setSilenceEnabled(fileKey: String, enabled: Boolean) {
        val track = dao.findByKey(fileKey) ?: return
        dao.upsert(track.copy(silenceEnabled = enabled))
    }

    suspend fun setFadeMs(fileKey: String, fadeMs: Long) {
        val track = dao.findByKey(fileKey) ?: return
        dao.upsert(track.copy(fadeMs = fadeMs))
    }

    private fun readDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * SAF 永久授權有數量上限（新版 Android 為 512 筆），超量後 take 會開始失敗，
     * 所以清單只保留最近 [MAX_REMEMBERED] 筆，其餘連同授權一起釋放。
     */
    private suspend fun pruneOldPermissions() {
        val stale = dao.findBeyond(MAX_REMEMBERED)
        stale.forEach { track ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(track.uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            dao.deleteByKey(track.fileKey)
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 100
    }
}
