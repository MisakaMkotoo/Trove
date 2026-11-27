package com.exodidio.tune.player

import android.content.Context
import android.content.Intent
import android.util.Log
import com.exodidio.tune.sync.AndroidSyncRuntime
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val PlayTapDebounceMs = 800L

/**
 * Android-only command boundary for the future UI. It deliberately does not
 * expose a platform decoder or a MediaCodec fallback.
 */
internal class PlaybackController(
    private val context: Context,
    private val resolver: PlaybackItemResolver,
) {
    private val preferences by lazy { PlaybackPreferences(context.applicationContext) }
    val state: StateFlow<PlaybackState> get() = PlaybackService.state
    val queue: StateFlow<PlaybackQueueSnapshot> get() = PlaybackService.queueState
    val artworkCrossfade: StateFlow<ArtworkCrossfadeTransition?> get() = PlaybackService.artworkCrossfade
    val moodRadioActive: StateFlow<Boolean> get() = PlaybackService.moodRadioActive
    val crossfadeSeconds: Flow<Int> get() = PlaybackService.crossfadeSeconds
    val playbackSpeed: StateFlow<Float> get() = PlaybackService.playbackSpeed
    val abLoopState: StateFlow<AbLoopState> get() = PlaybackService.abLoopState
    val sleepTimerState: StateFlow<SleepTimerState> get() = PlaybackService.sleepTimerState

    // 连点同一首的防抖：短时间内重复的同一目标直接吞掉，不再重复下发下载/播放指令。
    private var lastTapTrackId: String? = null
    private var lastTapAtMs: Long = 0L

    private fun shouldDropRepeatedTap(trackId: String): Boolean {
        if (trackId.isBlank()) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val repeated = trackId == lastTapTrackId && now - lastTapAtMs < PlayTapDebounceMs
        lastTapTrackId = trackId
        lastTapAtMs = now
        return repeated
    }

    fun play(request: PlaybackRequest) {
        if (shouldDropRepeatedTap(request.trackIds.getOrNull(request.startIndex).orEmpty())) return
        Log.d(PlaybackLogTag, "Queue play requested size=${request.trackIds.size} startIndex=${request.startIndex} startId=${request.trackIds[request.startIndex]}")
        dispatchPlaybackStart(
            PlaybackService.intent(context, PlaybackService.ActionPlay)
                .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
                .putExtra(PlaybackService.StartIndexExtra, request.startIndex),
        )
    }

    fun pause() {
        if (PlaybackService.isRunning) startServiceSafe(PlaybackService.intent(context, PlaybackService.ActionPause))
    }

    fun resume() {
        if (PlaybackService.isRunning || hasActiveTrack()) {
            dispatchPlaybackStart(PlaybackService.intent(context, PlaybackService.ActionResume))
        }
    }

    fun togglePlayPause() {
        if (PlaybackService.isRunning || hasActiveTrack()) {
            dispatchPlaybackStart(PlaybackService.intent(context, PlaybackService.ActionToggle))
        }
    }

    fun stop() {
        if (PlaybackService.isRunning) startServiceSafe(PlaybackService.intent(context, PlaybackService.ActionStop))
    }

    fun clearQueue() {
        if (PlaybackService.isRunning) startServiceSafe(PlaybackService.intent(context, PlaybackService.ActionClearQueue))
    }

    fun next() {
        if (PlaybackService.isRunning || hasActiveTrack()) {
            dispatchPlaybackStart(PlaybackService.intent(context, PlaybackService.ActionNext))
        }
    }

    fun previous() {
        if (PlaybackService.isRunning || hasActiveTrack()) {
            dispatchPlaybackStart(PlaybackService.intent(context, PlaybackService.ActionPrevious))
        }
    }

    /** 心动模式的首次入队：仍是换队列，但不算用户主动切队列，不触发退出。 */
    fun playHeartbeat(request: PlaybackRequest) {
        Log.d(PlaybackLogTag, "Heartbeat play requested size=${request.trackIds.size} startIndex=${request.startIndex}")
        dispatchPlaybackStart(
            PlaybackService.intent(context, PlaybackService.ActionPlay)
                .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
                .putExtra(PlaybackService.StartIndexExtra, request.startIndex)
                .putExtra(PlaybackService.HeartbeatStartExtra, true),
        )
    }

    /** 私人漫游的首次入队：换队列但不触发漫游退出。 */
    fun playRoam(request: PlaybackRequest) {
        Log.d(PlaybackLogTag, "Roam play requested size=${request.trackIds.size} startIndex=${request.startIndex}")
        dispatchPlaybackStart(
            PlaybackService.intent(context, PlaybackService.ActionPlay)
                .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
                .putExtra(PlaybackService.StartIndexExtra, request.startIndex)
                .putExtra(PlaybackService.RoamStartExtra, true),
        )
    }

    /** 相似歌曲电台的首次入队：换队列但不触发相似模式退出。 */
    fun playSimilar(request: PlaybackRequest) {
        Log.d(PlaybackLogTag, "Similar play requested size=${request.trackIds.size} startIndex=${request.startIndex}")
        dispatchPlaybackStart(
            PlaybackService.intent(context, PlaybackService.ActionPlay)
                .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
                .putExtra(PlaybackService.StartIndexExtra, request.startIndex)
                .putExtra(PlaybackService.SimilarStartExtra, true),
        )
    }

    fun shuffle(request: PlaybackRequest) = dispatchPlaybackStart(
        PlaybackService.intent(context, PlaybackService.ActionShuffle)
            .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
            .putExtra(PlaybackService.StartIndexExtra, request.startIndex),
    )

    fun setShuffle(enabled: Boolean) = startServiceSafe(
        PlaybackService.intent(context, PlaybackService.ActionSetShuffle).putExtra(PlaybackService.EnabledExtra, enabled),
    )

    fun setRepeatMode(mode: RepeatMode) = startServiceSafe(
        PlaybackService.intent(context, PlaybackService.ActionSetRepeat).putExtra(PlaybackService.RepeatModeExtra, mode.name),
    )

    fun playNext(trackId: String) = playNext(listOf(trackId))
    fun playNext(trackIds: List<String>) = tracksCommand(PlaybackService.ActionPlayNext, trackIds)
    fun append(trackIds: List<String>) = tracksCommand(PlaybackService.ActionAppend, trackIds)
    fun startMoodRadio(seedTrackId: String) = dispatchPlaybackStart(
        PlaybackService.intent(context, PlaybackService.ActionStartMoodRadio).putExtra(PlaybackService.TrackIdExtra, seedTrackId),
    )

    fun selectQueueTrack(trackId: String) {
        if (shouldDropRepeatedTap(trackId)) return
        dispatchPlaybackStart(
            PlaybackService.intent(context, PlaybackService.ActionSelect).putExtra(PlaybackService.TrackIdExtra, trackId),
        )
    }

    fun removeFromQueue(trackId: String) {
        if (PlaybackService.isRunning) {
            startServiceSafe(
                PlaybackService.intent(context, PlaybackService.ActionRemove).putExtra(PlaybackService.TrackIdExtra, trackId),
            )
        }
    }

    fun reorderQueue(trackIds: List<String>) = tracksCommand(PlaybackService.ActionReorder, trackIds)
    fun seekTo(positionMs: Long) {
        if (PlaybackService.isRunning) {
            startServiceSafe(
                PlaybackService.intent(context, PlaybackService.ActionSeek).putExtra(PlaybackService.PositionMsExtra, positionMs),
            )
        }
    }
    /**
     * A preference edit must not start the media foreground service: when no
     * track is playing it has no notification to promote within Android's
     * foreground-service deadline. A running service observes this DataStore
     * value and resyncs its preload itself.
     */
    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = clampCrossfadeSeconds(seconds)
        AndroidSyncRuntime.backgroundScope.launch {
            preferences.setCrossfadeSeconds(clamped)
        }
    }

    fun setBlendArtworkDuringCrossfade(enabled: Boolean) {
        AndroidSyncRuntime.backgroundScope.launch {
            preferences.setBlendArtworkDuringCrossfade(enabled)
        }
    }

    fun setGaplessPlayback(enabled: Boolean) {
        AndroidSyncRuntime.backgroundScope.launch {
            preferences.setGaplessPlayback(enabled)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = clampPlaybackSpeed(speed)
        PlaybackService.playbackSpeed.value = clamped
        if (PlaybackService.isRunning) {
            startServiceSafe(
                PlaybackService.intent(context, PlaybackService.ActionSetSpeed)
                    .putExtra(PlaybackService.SpeedExtra, clamped),
            )
        }
    }

    fun setCassetteScrub(active: Boolean, normalizedVelocity: Float) {
        PlaybackService.updateCassetteScrub(
            active = active,
            pitchFactor = computeCassetteScrubPitch(active, normalizedVelocity),
        )
    }

    fun readAudioLevels(outLevels: FloatArray): Boolean = PlaybackService.readAudioLevels(outLevels)

    fun setAbLoopPointA() {
        if (PlaybackService.isRunning) {
            startServiceSafe(PlaybackService.intent(context, PlaybackService.ActionSetAbLoopPointA))
        }
    }

    fun setAbLoopPointB() {
        if (PlaybackService.isRunning) {
            startServiceSafe(PlaybackService.intent(context, PlaybackService.ActionSetAbLoopPointB))
        }
    }

    fun fineTuneAbLoop(isPointA: Boolean, deltaMs: Long) {
        val (trackId, durationMs) = when (val current = PlaybackService.state.value) {
            is PlaybackState.Playing -> current.item.trackId to current.durationMs
            is PlaybackState.Paused -> current.item.trackId to current.durationMs
            else -> return
        }
        PlaybackService.abLoopState.value = fineTuneAbLoop(
            state = PlaybackService.abLoopState.value,
            trackId = trackId,
            isPointA = isPointA,
            deltaMs = deltaMs,
            durationMs = durationMs,
        )
        if (PlaybackService.isRunning) {
            startServiceSafe(
                PlaybackService.intent(context, PlaybackService.ActionFineTuneAbLoop)
                    .putExtra(PlaybackService.AbLoopPointIsAExtra, isPointA)
                    .putExtra(PlaybackService.AbLoopDeltaMsExtra, deltaMs),
            )
        }
    }

    fun clearAbLoop() {
        PlaybackService.abLoopState.value = AbLoopState()
        if (PlaybackService.isRunning) {
            startServiceSafe(PlaybackService.intent(context, PlaybackService.ActionClearAbLoop))
        }
    }

    fun deduplicateQueue(): Int {
        val activeIds = queue.value.activeTrackIds
        if (activeIds.size <= 1) return 0
        val store = AndroidSyncRuntime.syncStore
        val seenKeys = HashSet<String>(activeIds.size)
        var duplicates = 0
        for (id in activeIds) {
            val track = store.trackForPlayback(id)
            val key = if (track != null) trackDedupKey(track).ifEmpty { id } else id
            if (!seenKeys.add(key)) duplicates += 1
        }
        if (duplicates > 0) {
            startServiceSafe(PlaybackService.intent(context, PlaybackService.ActionDeduplicateQueue))
        }
        return duplicates
    }

    fun setSleepTimerMinutes(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        val totalMs = minutes.toLong() * 60_000L
        val endElapsed = android.os.SystemClock.elapsedRealtime() + totalMs
        PlaybackService.sleepTimerState.value = SleepTimerState(
            mode = SleepTimerMode.Countdown(endElapsed, totalMs),
            remainingMs = totalMs,
        )
        if (PlaybackService.isRunning) {
            startServiceSafe(
                PlaybackService.intent(context, PlaybackService.ActionSetSleepTimer)
                    .putExtra(PlaybackService.SleepTimerMinutesExtra, minutes)
                    .putExtra(PlaybackService.SleepTimerAfterTrackExtra, false),
            )
        }
    }

    fun setSleepTimerAfterCurrentTrack() {
        PlaybackService.sleepTimerState.value = SleepTimerState(
            mode = SleepTimerMode.AfterCurrentTrack,
            remainingMs = 0L,
        )
        if (PlaybackService.isRunning) {
            startServiceSafe(
                PlaybackService.intent(context, PlaybackService.ActionSetSleepTimer)
                    .putExtra(PlaybackService.SleepTimerAfterTrackExtra, true),
            )
        }
    }

    fun cancelSleepTimer() {
        PlaybackService.sleepTimerState.value = SleepTimerState()
        if (PlaybackService.isRunning) {
            startServiceSafe(PlaybackService.intent(context, PlaybackService.ActionCancelSleepTimer))
        }
    }

    internal suspend fun resolve(trackId: String): PlaybackItem? = resolver.resolve(trackId)

    private fun hasActiveTrack(): Boolean = when (PlaybackService.state.value) {
        is PlaybackState.Playing, is PlaybackState.Paused, is PlaybackState.Preparing -> true
        else -> false
    }

    private fun dispatchPlaybackStart(intent: Intent) {
        if (PlaybackService.isRunning) {
            startServiceSafe(intent)
        } else {
            context.startForegroundService(intent)
        }
    }

    private fun startServiceSafe(intent: Intent) {
        runCatching { context.startService(intent) }.onFailure { error ->
            Log.w(PlaybackLogTag, "startService failed action=${intent.action}: ${error.message}")
        }
    }

    private fun tracksCommand(action: String, trackIds: List<String>) = startServiceSafe(
        PlaybackService.intent(context, action).putExtra(PlaybackService.TrackIdsExtra, trackIds.toTypedArray()),
    )
}
