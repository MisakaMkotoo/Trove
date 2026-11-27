package com.exodidio.tune.player

import com.exodidio.tune.lyrics.LrcExporter
import com.exodidio.tune.lyrics.MaxLyricOffsetMs
import com.exodidio.tune.lyrics.MinLyricOffsetMs
import com.exodidio.tune.lyrics.clampLyricOffsetMs
import com.exodidio.tune.lyrics.parseLyricOffsets
import com.exodidio.tune.lyrics.serializeLyricOffsets
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.navigation.PlayerLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFeaturesTest {
    @Test
    fun `clampPlaybackSpeed clamps range and snaps near 1x`() {
        assertEquals(0.5f, clampPlaybackSpeed(0.2f), 0.0001f)
        assertEquals(2.0f, clampPlaybackSpeed(3.0f), 0.0001f)
        assertEquals(1.0f, clampPlaybackSpeed(1.004f), 0.0001f)
        assertEquals(1.0f, clampPlaybackSpeed(0.995f), 0.0001f)
        assertEquals(1.25f, clampPlaybackSpeed(1.25f), 0.0001f)
    }

    @Test
    fun `AbLoopState isActive only when both points belong to track and B exceeds A`() {
        assertFalse(AbLoopState().isActive)
        assertFalse(AbLoopState(trackId = "t1", pointA = 1_000L, pointB = null).isActive)
        assertFalse(AbLoopState(trackId = "t1", pointA = 5_000L, pointB = 5_000L).isActive)
        assertFalse(AbLoopState(trackId = "", pointA = 1_000L, pointB = 5_000L).isActive)
        assertTrue(AbLoopState(trackId = "t1", pointA = 1_000L, pointB = 5_000L).isActive)
    }

    @Test
    fun `SleepTimerState reflects active and afterCurrentTrack flags`() {
        assertFalse(SleepTimerState().isActive)
        assertFalse(SleepTimerState().afterCurrentTrack)

        val countdown = SleepTimerState(SleepTimerMode.Countdown(100_000L, 60_000L), 60_000L)
        assertTrue(countdown.isActive)
        assertFalse(countdown.afterCurrentTrack)

        val afterTrack = SleepTimerState(SleepTimerMode.AfterCurrentTrack, 0L)
        assertTrue(afterTrack.isActive)
        assertTrue(afterTrack.afterCurrentTrack)
    }

    @Test
    fun `lyric offset helpers clamp and round-trip JSON`() {
        assertEquals(MinLyricOffsetMs, clampLyricOffsetMs(-99_999L))
        assertEquals(MaxLyricOffsetMs, clampLyricOffsetMs(99_999L))
        val encoded = serializeLyricOffsets(mapOf("a" to 500L, "b" to 0L, "c" to -1200L))
        val decoded = parseLyricOffsets(encoded)
        assertEquals(mapOf("a" to 500L, "c" to -1200L), decoded)
        assertTrue(parseLyricOffsets("not-json").isEmpty())
    }

    @Test
    fun `LrcExporter shifts timestamps by offset and preserves metadata and translations`() {
        val raw = "[00:10.00]First line / 第一行\n[00:15.50]Second line"
        val exported = LrcExporter.buildExportableLrc(
            title = "Song Title",
            artist = "Artist Name",
            rawLyrics = raw,
            offsetMs = -500L,
        )
        assertTrue(exported.contains("[ti:Song Title]"))
        assertTrue(exported.contains("[ar:Artist Name]"))
        assertTrue(exported.contains("[00:09.50]First line"))
        assertTrue(exported.contains("[00:09.50]第一行"))
        assertTrue(exported.contains("[00:15.00]Second line"))
    }

    @Test
    fun `buildNativeLyric shifts lines by offset and skips countdown`() {
        val lines = listOf(
            PlayerLyricLine("● ● ●", timestampSeconds = 0f, isCountdown = true),
            PlayerLyricLine("Hello", timestampSeconds = 10f),
        )
        val native = buildNativeLyric(lines, offsetMs = 500L)
        assertEquals("[00:10.50]Hello\n", native)
    }

    @Test
    fun `fineTuneAbLoop clamps bounds and preserves minimum span`() {
        val initial = AbLoopState(trackId = "t1", pointA = 1_000L, pointB = 2_000L)

        // Move A forward within span, then clamp at B - MinAbLoopSpanMs
        val aPlus = fineTuneAbLoop(initial, "t1", isPointA = true, deltaMs = 500L, durationMs = 10_000L)
        assertEquals(1_500L, aPlus.pointA)
        val aClamped = fineTuneAbLoop(aPlus, "t1", isPointA = true, deltaMs = 500L, durationMs = 10_000L)
        assertEquals(2_000L - MinAbLoopSpanMs, aClamped.pointA)

        // Move A backward clamps at 0
        val aZero = fineTuneAbLoop(initial, "t1", isPointA = true, deltaMs = -2_000L, durationMs = 10_000L)
        assertEquals(0L, aZero.pointA)

        // Move B backward clamps at A + MinAbLoopSpanMs; forward clamps at durationMs
        val bMin = fineTuneAbLoop(initial, "t1", isPointA = false, deltaMs = -800L, durationMs = 10_000L)
        assertEquals(1_000L + MinAbLoopSpanMs, bMin.pointB)
        val bMax = fineTuneAbLoop(initial, "t1", isPointA = false, deltaMs = 20_000L, durationMs = 10_000L)
        assertEquals(10_000L, bMax.pointB)

        // Track mismatch or missing point does nothing
        assertEquals(initial, fineTuneAbLoop(initial, "other", isPointA = true, deltaMs = 500L, durationMs = 10_000L))
        val onlyA = AbLoopState(trackId = "t1", pointA = 1_000L, pointB = null)
        assertEquals(onlyA, fineTuneAbLoop(onlyA, "t1", isPointA = false, deltaMs = 500L, durationMs = 10_000L))
    }

    @Test
    fun `trackDedupKey normalizes case and whitespace`() {
        val trackA = LibraryTrack(id = "1", title = "  Song Name ", artists = "Artist A", album = "Album X")
        val trackB = LibraryTrack(id = "2", title = "song name", artists = "artist a ", album = "ALBUM X")
        assertEquals("song name|artist a|album x", trackDedupKey(trackA))
        assertEquals(trackDedupKey(trackA), trackDedupKey(trackB))
        assertEquals("", trackDedupKey(LibraryTrack(id = "3", title = " ", artists = "", album = "")))
    }
}
