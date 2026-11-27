package com.exodidio.tune.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunPlaybackEffectsTest {
    @Test
    fun cassetteScrubPitch_returnsUnityWhenInactive() {
        assertEquals(1f, computeCassetteScrubPitch(active = false, normalizedVelocity = 1.8f), 0.0001f)
    }

    @Test
    fun cassetteScrubPitch_pinchesSlightlyOnSteadyHold() {
        val hold = computeCassetteScrubPitch(active = true, normalizedVelocity = 0.01f)
        assertTrue(hold in 0.85f..0.98f)
    }

    @Test
    fun cassetteScrubPitch_increasesForwardAndDropsBackward() {
        val forward = computeCassetteScrubPitch(active = true, normalizedVelocity = 1.5f)
        val backward = computeCassetteScrubPitch(active = true, normalizedVelocity = -1.5f)
        assertTrue(forward > 1.15f)
        assertTrue(backward < 0.88f)
        assertTrue(forward <= 1.85f)
        assertTrue(backward >= 0.65f)
    }

    @Test
    fun radioCrosstalk_respectsToggleStreamAndPrepareTime() {
        assertFalse(shouldTriggerRadioCrosstalk(enabled = false, isStreamingTrack = true, elapsedPrepareMs = 1000L, roll = 0.1f))
        assertFalse(shouldTriggerRadioCrosstalk(enabled = true, isStreamingTrack = false, elapsedPrepareMs = 1000L, roll = 0.1f))
        assertFalse(shouldTriggerRadioCrosstalk(enabled = true, isStreamingTrack = true, elapsedPrepareMs = 200L, roll = 0.1f))
        assertTrue(shouldTriggerRadioCrosstalk(enabled = true, isStreamingTrack = true, elapsedPrepareMs = 800L, roll = 0.99f))
        assertTrue(shouldTriggerRadioCrosstalk(enabled = true, isStreamingTrack = true, elapsedPrepareMs = 450L, roll = 0.50f))
        assertFalse(shouldTriggerRadioCrosstalk(enabled = true, isStreamingTrack = true, elapsedPrepareMs = 450L, roll = 0.85f))
    }

    @Test
    fun vinylWearProgress_scalesMonotonicallyAndCapsAtOne() {
        assertEquals(0f, computeVinylWearProgress(0), 0.0001f)
        val low = computeVinylWearProgress(3)
        val mid = computeVinylWearProgress(16)
        val high = computeVinylWearProgress(80)
        assertTrue(low > 0f)
        assertTrue(mid > low)
        assertTrue(high > mid)
        assertTrue(high < 1f)
    }
}
