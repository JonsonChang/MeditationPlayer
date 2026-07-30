package com.wji.meditationplayer.ui.player

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wji.meditationplayer.container
import com.wji.meditationplayer.export.ExportWorker
import com.wji.meditationplayer.data.db.GapCodec
import com.wji.meditationplayer.data.db.TrackEntity
import com.wji.meditationplayer.domain.EffectiveTimeline
import com.wji.meditationplayer.domain.Gap
import com.wji.meditationplayer.playback.PlayerConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val track: TrackEntity? = null,
    val peaks: ShortArray? = null,
    /** null 表示不在解碼中；否則為 0..1 的進度。 */
    val waveformProgress: Float? = null,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val timeline: EffectiveTimeline? = null,
) {
    val totalDurationMs: Long get() = timeline?.totalDurationMs ?: 0L
    val originalPositionMs: Long get() = timeline?.toOriginal(positionMs) ?: 0L
    val silenceRemainingMs: Long? get() = timeline?.silenceRemainingMs(positionMs)
}

@UnstableApi
class PlayerViewModel(
    application: Application,
    private val fileKey: String,
) : AndroidViewModel(application) {

    private val tracks = application.container.trackRepository
    private val waveforms = application.container.waveformRepository
    private val connection = PlayerConnection(application)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val workManager = WorkManager.getInstance(application)
    private val exportWorkName = ExportWorker.UNIQUE_WORK_PREFIX + fileKey

    /** 匯出進度百分比；null 表示目前沒有進行中的匯出。 */
    val exportProgress: StateFlow<Int?> = workManager
        .getWorkInfosForUniqueWorkFlow(exportWorkName)
        .map { infos ->
            infos.firstOrNull()
                ?.takeUnless { it.state.isFinished }
                ?.progress
                ?.getInt(ExportWorker.KEY_PROGRESS, 0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 已送給服務的載入設定，用來判斷是否需要重建播放來源。 */
    private var loadedSignature: String? = null

    /**
     * 服務端目前實際在用的時間軸。必須跟 UI 的 [state] 分開存：
     * state 會在重建前就更新成新時間軸，換算舊位置時只能靠這個欄位。
     */
    private var loadedTimeline: EffectiveTimeline? = null
    private var waveformLoadedFor: String? = null

    init {
        observeTrack()
        connectAndPoll()
    }

    private fun observeTrack() = viewModelScope.launch {
        tracks.observeTrack(fileKey).filterNotNull().collect { track ->
            val timeline = track.toTimeline()
            _state.value = _state.value.copy(track = track, timeline = timeline)
            loadWaveformIfNeeded(track)
            syncPlaybackIfNeeded(track, timeline)
        }
    }

    private fun connectAndPoll() = viewModelScope.launch {
        val controller = connection.connect() ?: return@launch
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }
        })
        // 送出當前設定（observeTrack 可能已先於連線完成）。
        _state.value.track?.let { syncPlaybackIfNeeded(it, it.toTimeline(), force = true) }

        while (true) {
            _state.value = _state.value.copy(
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                isPlaying = controller.isPlaying,
            )
            delay(POSITION_POLL_MS)
        }
    }

    private fun loadWaveformIfNeeded(track: TrackEntity) {
        if (waveformLoadedFor == track.fileKey) return
        waveformLoadedFor = track.fileKey
        viewModelScope.launch {
            _state.value = _state.value.copy(waveformProgress = 0f)
            val peaks = waveforms.load(
                fileKey = track.fileKey,
                uri = track.uri.toUri(),
                durationMs = track.durationMs,
                // 一併帶出已填部分，讓波形從左到右長出來，而不是一片空白等到最後。
                onProgress = { progress, partial ->
                    _state.value = _state.value.copy(
                        waveformProgress = progress,
                        peaks = partial,
                    )
                },
            )
            _state.value = _state.value.copy(peaks = peaks, waveformProgress = null)
        }
    }

    /**
     * 設定變更後重建播放來源，並把播放位置接回原本聽到的地方。
     *
     * 位置要先用**舊**的時間軸換回原始時間，再用**新**的時間軸換算回去，
     * 否則插入點一改，位置就會整段偏掉。
     */
    private fun syncPlaybackIfNeeded(
        track: TrackEntity,
        timeline: EffectiveTimeline,
        force: Boolean = false,
    ) {
        val signature = track.signature()
        if (!force && signature == loadedSignature) return

        val controller = connection.controller ?: return
        val originalPosition = loadedTimeline
            ?.toOriginal(controller.currentPosition.coerceAtLeast(0L))
            ?: 0L
        val wasPlaying = controller.isPlaying

        loadedSignature = signature
        loadedTimeline = timeline
        connection.load(
            uri = track.uri,
            title = track.displayName,
            durationMs = track.durationMs,
            encodedGaps = GapCodec.encode(track.activeGaps()),
            fadeMs = track.fadeMs,
            startPositionMs = timeline.toEffective(originalPosition),
            playWhenReady = wasPlaying,
        )
    }

    // ---------- 使用者操作 ----------

    fun togglePlayPause() {
        val controller = connection.controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun seekToOriginal(originalMs: Long) {
        val timeline = _state.value.timeline ?: return
        connection.controller?.seekTo(timeline.toEffective(originalMs))
    }

    fun skipBy(deltaMs: Long) {
        val controller = connection.controller ?: return
        val target = (controller.currentPosition + deltaMs)
            .coerceIn(0L, _state.value.totalDurationMs)
        controller.seekTo(target)
    }

    fun addGap(atOriginalMs: Long, durationMs: Long) = updateGaps { current ->
        current + Gap(atMs = atOriginalMs, durationMs = durationMs, enabled = true)
    }

    fun removeGap(gap: Gap) = updateGaps { current -> current - gap }

    fun setGapEnabled(gap: Gap, enabled: Boolean) = updateGaps { current ->
        current.map { if (it == gap) it.copy(enabled = enabled) else it }
    }

    fun setSilenceEnabled(enabled: Boolean) = viewModelScope.launch {
        tracks.setSilenceEnabled(fileKey, enabled)
    }

    fun setFadeMs(fadeMs: Long) = viewModelScope.launch {
        tracks.setFadeMs(fileKey, fadeMs)
    }

    fun startExport(destination: Uri) {
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInputData(
                workDataOf(
                    ExportWorker.KEY_FILE_KEY to fileKey,
                    ExportWorker.KEY_DESTINATION to destination.toString(),
                ),
            )
            .build()
        workManager.enqueueUniqueWork(exportWorkName, ExistingWorkPolicy.REPLACE, request)
    }

    private fun updateGaps(transform: (List<Gap>) -> List<Gap>) {
        val track = _state.value.track ?: return
        viewModelScope.launch { tracks.updateGaps(fileKey, transform(track.gaps)) }
    }

    override fun onCleared() {
        connection.release()
        super.onCleared()
    }

    private fun TrackEntity.activeGaps(): List<Gap> = if (silenceEnabled) gaps else emptyList()

    private fun TrackEntity.toTimeline() = EffectiveTimeline(durationMs, activeGaps(), fadeMs)

    private fun TrackEntity.signature() =
        "$uri|$durationMs|$fadeMs|${GapCodec.encode(activeGaps())}"

    private companion object {
        const val POSITION_POLL_MS = 200L
    }
}
