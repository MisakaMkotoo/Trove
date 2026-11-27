package com.exodidio.tune.player

import kotlin.math.abs

/**
 * Pure helpers for optional novelty playback effects so behavior is deterministic
 * and unit-testable without native AAudio or Compose dependencies.
 */
internal fun computeCassetteScrubPitch(
    active: Boolean,
    normalizedVelocity: Float,
): Float {
    if (!active) return 1f
    val clampedVelocity = normalizedVelocity.coerceIn(-3f, 3f)
    val speed = abs(clampedVelocity)
    if (speed < 0.04f) {
        // Holding finger steady on the slider subtly pinches tape speed.
        return 0.92f
    }
    val factor = if (clampedVelocity > 0f) {
        1.06f + (speed / 2.5f).coerceIn(0f, 1f) * 0.66f
    } else {
        0.92f - (speed / 2.5f).coerceIn(0f, 1f) * 0.24f
    }
    return factor.coerceIn(0.65f, 1.85f)
}

internal fun shouldTriggerRadioCrosstalk(
    enabled: Boolean,
    isStreamingTrack: Boolean,
    elapsedPrepareMs: Long,
    roll: Float,
): Boolean {
    if (!enabled || !isStreamingTrack || elapsedPrepareMs < 320L) return false
    if (elapsedPrepareMs >= 700L) return true
    return roll.coerceIn(0f, 1f) < 0.72f
}

internal fun computeVinylWearProgress(playCount: Int): Float {
    if (playCount <= 0) return 0f
    return (1f - 1f / (1f + playCount / 16f)).coerceIn(0f, 1f)
}
