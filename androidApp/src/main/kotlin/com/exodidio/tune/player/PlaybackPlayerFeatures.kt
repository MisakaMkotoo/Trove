package com.exodidio.tune.player

import com.exodidio.tune.sync.LibraryTrack
import kotlin.math.abs

internal const val MinPlaybackSpeed = 0.5f
internal const val MaxPlaybackSpeed = 2.0f

internal fun clampPlaybackSpeed(speed: Float): Float {
    val clamped = speed.coerceIn(MinPlaybackSpeed, MaxPlaybackSpeed)
    return if (abs(clamped - 1.0f) < 0.01f) 1.0f else clamped
}

internal const val MinAbLoopSpanMs = 500L

internal data class AbLoopState(
    val trackId: String = "",
    val pointA: Long? = null,
    val pointB: Long? = null,
) {
    val hasPointA: Boolean get() = trackId.isNotEmpty() && pointA != null
    val hasPointB: Boolean get() = trackId.isNotEmpty() && pointB != null
    val isActive: Boolean get() = trackId.isNotEmpty() && pointA != null && pointB != null && pointB > pointA
}

internal fun fineTuneAbLoop(
    state: AbLoopState,
    trackId: String,
    isPointA: Boolean,
    deltaMs: Long,
    durationMs: Long,
): AbLoopState {
    if (trackId.isBlank() || state.trackId != trackId) return state
    val maxDuration = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    return if (isPointA) {
        val oldA = state.pointA ?: return state
        val maxA = state.pointB?.let { (it - MinAbLoopSpanMs).coerceAtLeast(0L) } ?: maxDuration
        state.copy(pointA = (oldA + deltaMs).coerceIn(0L, maxA))
    } else {
        val oldB = state.pointB ?: return state
        val minB = state.pointA?.let { it + MinAbLoopSpanMs } ?: MinAbLoopSpanMs
        val clampedMax = maxDuration.coerceAtLeast(minB)
        state.copy(pointB = (oldB + deltaMs).coerceIn(minB, clampedMax))
    }
}

internal sealed interface SleepTimerMode {
    data object Off : SleepTimerMode
    data class Countdown(val endElapsedMs: Long, val totalDurationMs: Long) : SleepTimerMode
    data object AfterCurrentTrack : SleepTimerMode
}

internal data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.Off,
    val remainingMs: Long = 0L,
) {
    val isActive: Boolean get() = mode !is SleepTimerMode.Off
    val afterCurrentTrack: Boolean get() = mode is SleepTimerMode.AfterCurrentTrack
}

internal fun trackDedupKey(track: LibraryTrack): String {
    val title = track.title.trim().lowercase()
    val artists = track.artists.trim().lowercase()
    val album = track.album.trim().lowercase()
    return if (title.isEmpty() && artists.isEmpty() && album.isEmpty()) "" else "$title|$artists|$album"
}
