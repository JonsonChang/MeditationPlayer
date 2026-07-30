package com.wji.meditationplayer.data.db

import com.wji.meditationplayer.domain.Gap

/**
 * 把插入點清單編碼成單一字串存進 Room。
 *
 * 刻意用純 Kotlin 的簡易格式而非 JSON 函式庫：沒有跨 gap 查詢需求，
 * 這樣不必為了一個欄位多帶一個依賴，也能直接寫 JVM 單元測試。
 */
internal object GapCodec {

    fun encode(gaps: List<Gap>): String =
        gaps.joinToString(separator = ";") { "${it.atMs},${it.durationMs},${it.enabled}" }

    fun decode(raw: String?): List<Gap> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            val duration = parts[1].toLongOrNull() ?: return@mapNotNull null
            Gap(atMs = at, durationMs = duration, enabled = parts[2].toBooleanStrictOrNull() ?: true)
        }
    }
}
