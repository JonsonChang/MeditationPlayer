package com.wji.meditationplayer.domain

/**
 * 一個靜默插入點。
 *
 * [atMs] 一律是**原始音檔時間軸**上的位置，不是插入後的位置。這個不變量讓使用者
 * 增刪其他插入點、或切換總開關時，既有插入點都不會漂移。
 */
data class Gap(
    val atMs: Long,
    val durationMs: Long,
    val enabled: Boolean = true,
)
