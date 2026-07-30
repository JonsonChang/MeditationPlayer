package com.wji.meditationplayer.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY lastOpenedAt DESC")
    fun observeRecent(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE fileKey = :fileKey")
    fun observeByKey(fileKey: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE fileKey = :fileKey")
    suspend fun findByKey(fileKey: String): TrackEntity?

    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE fileKey = :fileKey")
    suspend fun deleteByKey(fileKey: String)

    /** 最舊的幾筆，用來回收 SAF 永久授權（授權數有上限）。 */
    @Query("SELECT * FROM tracks ORDER BY lastOpenedAt DESC LIMIT -1 OFFSET :keep")
    suspend fun findBeyond(keep: Int): List<TrackEntity>
}
