package com.wji.meditationplayer.ui

/** 毫秒轉 `H:MM:SS` 或 `MM:SS`。 */
fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) + 500L) / 1000L
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** 靜默長度用「N 分」表示，不足一分鐘時顯示秒（debug build 的測試長度會用到）。 */
fun formatGapLength(ms: Long): String =
    if (ms >= 60_000L) "${ms / 60_000L} 分" else "${ms / 1000L} 秒"
