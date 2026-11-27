package com.exodidio.tune.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessPreferencesTest {
    @Test
    fun `crossfade settings enables gapless playback by default`() {
        val defaults = CrossfadeSettings(
            seconds = CrossfadeDisabledSeconds,
            lastEnabledSeconds = CrossfadeDefaultSeconds,
            blendArtworkDuringCrossfade = true,
        )
        assertTrue(defaults.gaplessPlayback)
        assertEquals(CrossfadeDisabledSeconds, defaults.seconds)
    }

    @Test
    fun `gapless preload eligibility triggers even when crossfade is zero`() {
        val gaplessOnly = CrossfadeSettings(
            seconds = 0,
            lastEnabledSeconds = 4,
            blendArtworkDuringCrossfade = true,
            gaplessPlayback = true,
        )
        val disabledBoth = gaplessOnly.copy(gaplessPlayback = false)
        val crossfadeOnly = disabledBoth.copy(seconds = 3)

        assertTrue(gaplessOnly.gaplessPlayback || gaplessOnly.seconds > 0)
        assertFalse(disabledBoth.gaplessPlayback || disabledBoth.seconds > 0)
        assertTrue(crossfadeOnly.gaplessPlayback || crossfadeOnly.seconds > 0)
    }
}
