package com.wji.meditationplayer.data.waveform

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 波形峰值的取得與快取。解碼一次後以 fileKey 存到 cacheDir，
 * 之後開同一個檔案就是一次檔案讀取，不再重新解碼。
 */
class WaveformRepository(private val context: Context) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, "waveforms").apply { mkdirs() }
    }

    /**
     * @param onProgress 進度與已填部分的複本，讓 UI 能邊解碼邊畫。命中快取時不會被呼叫。
     */
    suspend fun load(
        fileKey: String,
        uri: Uri,
        durationMs: Long,
        onProgress: (Float, ShortArray) -> Unit,
    ): ShortArray? {
        readCache(fileKey)?.let { return it }
        val peaks = WaveformExtractor.extract(context, uri, durationMs, onProgress) ?: return null
        writeCache(fileKey, peaks)
        return peaks
    }

    private suspend fun readCache(fileKey: String): ShortArray? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$fileKey.bin")
        if (!file.exists()) return@withContext null
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != FORMAT_VERSION) return@use null
                val count = input.readInt()
                if (count != WaveformExtractor.BUCKETS) return@use null
                ShortArray(count) { input.readShort() }
            }
        }.getOrNull()
    }

    private suspend fun writeCache(fileKey: String, peaks: ShortArray) = withContext(Dispatchers.IO) {
        runCatching {
            DataOutputStream(File(cacheDir, "$fileKey.bin").outputStream().buffered()).use { out ->
                out.writeInt(FORMAT_VERSION)
                out.writeInt(peaks.size)
                peaks.forEach { out.writeShort(it.toInt()) }
            }
        }
        Unit
    }

    private companion object {
        /** 4：bucket 數與取樣視窗調整過，舊快取一併淘汰。 */
        const val FORMAT_VERSION = 4
    }
}
