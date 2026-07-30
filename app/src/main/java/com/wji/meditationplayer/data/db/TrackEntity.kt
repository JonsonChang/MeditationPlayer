package com.wji.meditationplayer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.wji.meditationplayer.domain.Gap

class GapListConverter {
    @TypeConverter
    fun toRaw(gaps: List<Gap>): String = GapCodec.encode(gaps)

    @TypeConverter
    fun fromRaw(raw: String): List<Gap> = GapCodec.decode(raw)
}

/**
 * 一個音檔的插入設定。
 *
 * 主鍵用內容指紋 [fileKey] 而非 SAF URI：URI 即使取得永久授權，使用者移動或
 * 重新下載檔案後仍可能失效，用指紋能讓設定跟著內容走。
 */
@Entity(tableName = "tracks")
@TypeConverters(GapListConverter::class)
data class TrackEntity(
    @PrimaryKey val fileKey: String,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val gaps: List<Gap> = emptyList(),
    val silenceEnabled: Boolean = true,
    val fadeMs: Long = 3_000L,
    val lastOpenedAt: Long = 0L,
)
