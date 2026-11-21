package com.exodidio.tune.player

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val state: StateFlow<PlaybackState> get() = PlaybackService.state
    val queue: StateFlow<PlaybackQueueSnapshot> get() = PlaybackService.queueState
    val artworkCrossfade: StateFlow<ArtworkCrossfadeTransition?> get() = PlaybackService.artworkCrossfade
    val moodRadioActive: StateFlow<Boolean> get() = PlaybackService.moodRadioActive
    val crossfadeSeconds: Flow<Int> get() = PlaybackService.crossfadeSeconds

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
        context.startForegroundService(
            PlaybackService.intent(context, PlaybackService.ActionPlay)
            .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
            .putExtra(PlaybackService.StartIndexExtra, request.startIndex),
        )
    }

    fun pause() = command(PlaybackService.ActionPause)
    fun resume() = command(PlaybackService.ActionResume)
    fun togglePlayPause() = command(PlaybackService.ActionToggle)
    fun stop() = command(PlaybackService.ActionStop)
    fun clearQueue() = command(PlaybackService.ActionClearQueue)
    fun next() = command(PlaybackService.ActionNext)
    fun previous() = command(PlaybackService.ActionPrevious)
    /** 心动模式的首次入队：仍是换队列，但不算用户主动切队列，不触发退出。 */
    fun playHeartbeat(request: PlaybackRequest) {
        Log.d(PlaybackLogTag, "Heartbeat play requested size=${request.trackIds.size} startIndex=${request.startIndex}")
        context.startForegroundService(
            PlaybackService.intent(context, PlaybackService.ActionPlay)
                .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
                .putExtra(PlaybackService.StartIndexExtra, request.startIndex)
                .putExtra(PlaybackService.HeartbeatStartExtra, true),
        )
    }

    /** 私人漫游的首次入队：换队列但不触发漫游退出。 */
    fun playRoam(request: PlaybackRequest) {
        Log.d(PlaybackLogTag, "Roam play requested size=${request.trackIds.size} startIndex=${request.startIndex}")
        context.startForegroundService(
            PlaybackService.intent(context, PlaybackService.ActionPlay)
                .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
                .putExtra(PlaybackService.StartIndexExtra, request.startIndex)
                .putExtra(PlaybackService.RoamStartExtra, true),
        )
    }

    fun shuffle(request: PlaybackRequest) = context.startForegroundService(
        PlaybackService.intent(context, PlaybackService.ActionShuffle)
            .putExtra(PlaybackService.TrackIdsExtra, request.trackIds.toTypedArray())
            .putExtra(PlaybackService.StartIndexExtra, request.startIndex),
    )
    fun setShuffle(enabled: Boolean) = context.startForegroundService(
        PlaybackService.intent(context, PlaybackService.ActionSetShuffle).putExtra(PlaybackService.EnabledExtra, enabled),
    )
    fun setRepeatMode(mode: RepeatMode) = context.startForegroundService(
        PlaybackService.intent(context, PlaybackService.ActionSetRepeat).putExtra(PlaybackService.RepeatModeExtra, mode.name),
    )
    fun playNext(trackId: String) = playNext(listOf(trackId))
    fun playNext(trackIds: List<String>) = tracksCommand(PlaybackService.ActionPlayNext, trackIds)
    fun append(trackIds: List<String>) = tracksCommand(PlaybackService.ActionAppend, trackIds)
    fun startMoodRadio(seedTrackId: String) = context.startForegroundService(
        PlaybackService.intent(context, PlaybackService.ActionStartMoodRadio).putExtra(PlaybackService.TrackIdExtra, seedTrackId),
    )
    fun selectQueueTrack(trackId: String) {
        if (shouldDropRepeatedTap(trackId)) return
        context.startForegroundService(
            PlaybackService.intent(context, PlaybackService.ActionSelect).putExtra(PlaybackService.TrackIdExtra, trackId),
        )
    }
    fun removeFromQueue(trackId: String) = context.startForegroundService(
        PlaybackService.intent(context, PlaybackService.ActionRemove).putExtra(PlaybackService.TrackIdExtra, trackId),
    )
    fun reorderQueue(trackIds: List<String>) = tracksCommand(PlaybackService.ActionReorder, trackIds)
    fun seekTo(positionMs: Long) = context.startForegroundService(
        PlaybackService.intent(context, PlaybackService.ActionSeek).putExtra(PlaybackService.PositionMsExtra, positionMs),
    )
    /**
     * A preference edit must not start the media foreground service: when no
     * track is playing it has no notification to promote within Android's
     * foreground-service deadline. A running service observes this DataStore
     * value and resyncs its preload itself.
     */
    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = clampCrossfadeSeconds(seconds)
        CoroutineScope(Dispatchers.IO).launch {
            PlaybackPreferences(context).setCrossfadeSeconds(clamped)
        }
    }

    fun setBlendArtworkDuringCrossfade(enabled: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            PlaybackPreferences(context).setBlendArtworkDuringCrossfade(enabled)
        }
    }

    internal suspend fun resolve(trackId: String): PlaybackItem? = resolver.resolve(trackId)

    private fun command(action: String) = context.startForegroundService(PlaybackService.intent(context, action))
    private fun tracksCommand(action: String, trackIds: List<String>) = context.startForegroundService(
        PlaybackService.intent(context, action).putExtra(PlaybackService.TrackIdsExtra, trackIds.toTypedArray()),
    )
}
