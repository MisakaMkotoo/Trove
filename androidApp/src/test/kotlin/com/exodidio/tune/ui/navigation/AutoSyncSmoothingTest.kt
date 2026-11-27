package com.exodidio.tune.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncSmoothingTest {
    @Test
    fun `interpolates forward smoothly between 200ms ticks`() {
        val clock = LyricClockState(
            lastTick = 1_000L,
            base = 1_000L,
            startedAtFrameNs = 0L,
            currentMs = 1_000L,
        )
        val mid = stepLyricClock(clock, tick = 1_000L, isPlaying = true, frameNs = 100_000_000L)
        assertEquals(1_100L, mid)

        val capped = stepLyricClock(clock, tick = 1_000L, isPlaying = true, frameNs = 500_000_000L)
        assertEquals(1_280L, capped)
    }

    @Test
    fun `clamps small backward tick delta without shrinking karaoke fill`() {
        val clock = LyricClockState(
            lastTick = 10_000L,
            base = 10_000L,
            startedAtFrameNs = 0L,
            currentMs = 10_240L,
        )
        // New tick arrives slightly behind current interpolated visual cursor (delta = 140ms <= 500ms).
        val afterBackwardJitter = stepLyricClock(
            clock = clock,
            tick = 10_100L,
            isPlaying = true,
            frameNs = 1_000_000_000L,
        )
        assertTrue(afterBackwardJitter >= 10_240L)

        // Subsequent frame slows advance (elapsed / 2) instead of snapping backward.
        val nextFrame = stepLyricClock(
            clock = clock,
            tick = 10_100L,
            isPlaying = true,
            frameNs = 1_040_000_000L,
        )
        assertEquals(10_260L, nextFrame)
    }

    @Test
    fun `large backward seek and pause snap cleanly`() {
        val clock = LyricClockState(
            lastTick = 20_000L,
            base = 20_000L,
            startedAtFrameNs = 0L,
            currentMs = 20_100L,
        )
        val seekBack = stepLyricClock(clock, tick = 5_000L, isPlaying = true, frameNs = 100_000_000L)
        assertEquals(5_000L, seekBack)

        val paused = stepLyricClock(clock, tick = 5_000L, isPlaying = false, frameNs = 200_000_000L)
        assertEquals(5_000L, paused)
    }
}
