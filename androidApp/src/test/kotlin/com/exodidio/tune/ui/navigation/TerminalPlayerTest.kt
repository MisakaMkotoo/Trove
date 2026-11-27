package com.exodidio.tune.ui.navigation

import com.exodidio.tune.player.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPlayerTest {
    @Test
    fun `formatAsciiProgressBar renders idle, midpoint, and completion states`() {
        assertEquals("[----------]", formatAsciiProgressBar(positionMs = 0L, durationMs = 0L, barChars = 10))
        assertEquals("[>---------]", formatAsciiProgressBar(positionMs = 0L, durationMs = 100_000L, barChars = 10))
        assertEquals("[=====>----]", formatAsciiProgressBar(positionMs = 50_000L, durationMs = 100_000L, barChars = 10))
        assertEquals("[==========]", formatAsciiProgressBar(positionMs = 100_000L, durationMs = 100_000L, barChars = 10))
    }

    @Test
    fun `linearAmplitudeToDb and dbToNormalizedMeter map silence and full-scale cleanly`() {
        assertEquals(-60f, linearAmplitudeToDb(0f), 0.001f)
        assertEquals(0f, linearAmplitudeToDb(1f), 0.001f)
        assertEquals(0f, dbToNormalizedMeter(-60f), 0.001f)
        assertEquals(0.5f, dbToNormalizedMeter(-30f), 0.001f)
        assertEquals(1f, dbToNormalizedMeter(0f), 0.001f)
    }

    @Test
    fun `formatAsciiLevelMeter shows filled cells and peak hold marker`() {
        assertEquals("[----------]", formatAsciiLevelMeter(rmsNorm = 0f, peakNorm = 0f, barChars = 10))
        assertEquals("[#####---|-]", formatAsciiLevelMeter(rmsNorm = 0.5f, peakNorm = 0.9f, barChars = 10))
        assertEquals("[##########]", formatAsciiLevelMeter(rmsNorm = 1f, peakNorm = 1f, barChars = 10))
    }

    @Test
    fun `parseTerminalSeekTargetMs supports absolute, mm-ss, and relative offsets`() {
        assertEquals(90_000L, parseTerminalSeekTargetMs("1:30", currentMs = 10_000L, durationMs = 200_000L))
        assertEquals(45_000L, parseTerminalSeekTargetMs("45s", currentMs = 10_000L, durationMs = 200_000L))
        assertEquals(25_000L, parseTerminalSeekTargetMs("+15", currentMs = 10_000L, durationMs = 200_000L))
        assertEquals(0L, parseTerminalSeekTargetMs("-20", currentMs = 10_000L, durationMs = 200_000L))
        assertEquals(200_000L, parseTerminalSeekTargetMs("99:00", currentMs = 10_000L, durationMs = 200_000L))
        assertNull(parseTerminalSeekTargetMs("bad", currentMs = 10_000L, durationMs = 200_000L))
    }

    @Test
    fun `parseTerminalCommand handles core CLI verbs and arguments`() {
        assertEquals(TerminalCommand.Help, parseTerminalCommand("help"))
        assertEquals(TerminalCommand.PlayPause, parseTerminalCommand("pp"))
        assertEquals(TerminalCommand.PlayIndex(4), parseTerminalCommand("play 4"))
        assertEquals(TerminalCommand.Seek("+10"), parseTerminalCommand("seek +10"))
        assertEquals(TerminalCommand.Volume(85), parseTerminalCommand("vol 85%"))
        assertEquals(TerminalCommand.Speed(1.25f), parseTerminalCommand("speed 1.25x"))
        assertEquals(TerminalCommand.Shuffle(true), parseTerminalCommand("shuffle on"))
        assertEquals(TerminalCommand.Repeat(RepeatMode.One), parseTerminalCommand("repeat one"))
        assertEquals(TerminalCommand.ListTracks("bach"), parseTerminalCommand("ls bach"))
        assertEquals(TerminalCommand.Exit, parseTerminalCommand("exit"))
        assertTrue(parseTerminalCommand("foobar") is TerminalCommand.Unknown)
    }
}
