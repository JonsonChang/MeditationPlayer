package com.wji.meditationplayer

import android.content.Context
import androidx.room.Room
import com.wji.meditationplayer.data.TrackRepository
import com.wji.meditationplayer.data.db.AppDatabase
import com.wji.meditationplayer.data.waveform.WaveformRepository

/**
 * 手動 DI。依賴只有 DB / TrackRepository / WaveformRepository 三個，
 * 引入 Hilt 的樣板成本大於收益。
 */
class AppContainer(context: Context) {

    private val database: AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, "meditation-player.db")
        .build()

    val trackRepository: TrackRepository = TrackRepository(context, database.trackDao())

    val waveformRepository: WaveformRepository = WaveformRepository(context)
}
