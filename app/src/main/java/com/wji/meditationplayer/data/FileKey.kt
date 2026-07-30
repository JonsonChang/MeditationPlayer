package com.wji.meditationplayer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

/**
 * 音檔的內容指紋。用檔名 + 大小 + 修改時間，不必讀完整個檔案。
 *
 * 部分 provider 不回報 lastModified（回 0），此時仍有檔名與大小可用。
 */
object FileKey {

    fun compute(context: Context, uri: Uri): String? {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
        val name = doc.name ?: return null
        val raw = "$name|${doc.length()}|${doc.lastModified()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
    }

    fun displayName(context: Context, uri: Uri): String? =
        DocumentFile.fromSingleUri(context, uri)?.name
}
