package com.wji.meditationplayer.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wji.meditationplayer.container
import com.wji.meditationplayer.data.db.TrackEntity
import com.wji.meditationplayer.domain.EffectiveTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 最近清單的一列，總長已算好。 */
data class RecentTrack(
    val fileKey: String,
    val displayName: String,
    val originalDurationMs: Long,
    val totalDurationMs: Long,
    val enabledGapCount: Int,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val tracks = application.container.trackRepository

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val recent: StateFlow<List<RecentTrack>> = tracks.observeRecent()
        .map { list -> list.map { it.toRecentTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openPicked(uri: Uri, onOpened: (String) -> Unit) = viewModelScope.launch {
        val track = tracks.openPickedFile(uri, System.currentTimeMillis())
        if (track == null) {
            _error.value = "無法讀取這個音檔（可能格式不支援或讀不到時長）"
        } else {
            _error.value = null
            onOpened(track.fileKey)
        }
    }

    fun openExisting(fileKey: String, onOpened: (String) -> Unit) = viewModelScope.launch {
        tracks.touch(fileKey, System.currentTimeMillis())
        onOpened(fileKey)
    }

    fun clearError() {
        _error.value = null
    }

    private fun TrackEntity.toRecentTrack(): RecentTrack {
        val activeGaps = if (silenceEnabled) gaps.filter { it.enabled } else emptyList()
        val timeline = EffectiveTimeline(durationMs, activeGaps, fadeMs)
        return RecentTrack(
            fileKey = fileKey,
            displayName = displayName,
            originalDurationMs = durationMs,
            totalDurationMs = timeline.totalDurationMs,
            enabledGapCount = activeGaps.size,
        )
    }
}
