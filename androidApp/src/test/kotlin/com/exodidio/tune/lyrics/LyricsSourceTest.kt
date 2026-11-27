package com.exodidio.tune.lyrics

import com.exodidio.tune.lyrics.autosync.AutoSyncCoordinator
import com.exodidio.tune.netease.NeteaseClient
import com.exodidio.tune.sync.ProviderLyricsCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSourceTest {
    @Test
    fun defaultOrderPutsNeteaseFirstThenAmll() {
        val settings = LyricsSettings()
        assertEquals(LyricsSourceId.Netease, settings.sourceOrder.first())
        assertEquals(LyricsSourceId.Amll, settings.sourceOrder[1])
        assertNull(settings.wordFallbackSource)
        assertEquals(LyricsSourceId.entries.toSet(), settings.enabledSources)
    }

    @Test
    fun storageRoundTrips() {
        assertEquals(LyricsSourceId.Amll, LyricsSourceId.fromStorage("amll"))
        assertNull(LyricsSourceId.fromStorage("auto_fetch"))
        assertNull(LyricsSourceId.fromStorage(null))
    }

    @Test
    fun orderedSourcesCompletesMissingEntries() {
        val partial = listOf(LyricsSourceId.Kugou, LyricsSourceId.Netease)
        val merged = orderedSources(partial)
        assertEquals(LyricsSourceId.Kugou, merged[0])
        assertEquals(LyricsSourceId.Netease, merged[1])
        assertEquals(LyricsSourceId.entries.toSet(), merged.toSet())
        assertEquals(LyricsSourceId.entries.size, merged.size)
    }

    @Test
    fun preferredLyricsReturnsProviderLyrics() {
        assertEquals("provider", preferredLyrics("provider"))
        assertNull(preferredLyrics(null))
    }

    @Test
    fun neteaseCandidateMatchingRefusesUnrelatedZeroScoreFallback() {
        val track = LyricsTrack(
            title = "Midnight City",
            artist = "M83",
            album = "",
            duration = 244,
            trackId = "local:1",
        )
        val unrelated = listOf(
            NeteaseClient.SearchSong(
                songId = 999L,
                title = "Completely Different Song",
                artists = "Other Artist",
                durationMs = 244_000L,
            ),
        )
        assertNull(selectBestNeteaseSongId(unrelated, track))
    }

    @Test
    fun neteaseCandidateMatchingDoesNotTreatBlankArtistAsMatch() {
        val track = LyricsTrack(
            title = "Blue",
            artist = "",
            album = "",
            duration = 200,
            trackId = "local:2",
        )
        val candidates = listOf(
            NeteaseClient.SearchSong(
                songId = 111L,
                title = "Blue Ocean",
                artists = "Random Singer",
                durationMs = 250_000L,
            ),
            NeteaseClient.SearchSong(
                songId = 222L,
                title = "Blue",
                artists = "Target Artist",
                durationMs = 201_000L,
            ),
        )
        assertEquals(222L, selectBestNeteaseSongId(candidates, track))
    }

    @Test
    fun hasWordTimingProtectsYrcWithParenthesesAndEnhancedLrc() {
        val yrcWithParens = """
            {"tuneLyrics":1,"lrc":"[00:01.00]Hey (woo) now","yrc":"[1000,1500](1000,500,0)Hey (woo) (1500,500,0)now"}
        """.trimIndent()
        val enhancedLrc = "[00:01.00]<00:01.00>Hey <00:01.50>now"
        val metadataOnlyYrc = """
            {"tuneLyrics":1,"lrc":"[00:01.00]Hey now","yrc":"{\"t\":0,\"c\":[{\"tx\":\"作词 : Test\"}]}"}
        """.trimIndent()

        assertTrue(hasWordTimingLyrics(yrcWithParens))
        assertTrue(AutoSyncCoordinator.hasWordTiming(yrcWithParens))
        assertTrue(hasWordTimingLyrics(enhancedLrc))
        assertTrue(AutoSyncCoordinator.hasWordTiming(enhancedLrc))
        assertFalse(hasWordTimingLyrics(metadataOnlyYrc))
        assertFalse(AutoSyncCoordinator.hasWordTiming(metadataOnlyYrc))
    }

    @Test
    fun providerLyricsCacheRevertsAlignedTracksAndPreservesManualSelection() {
        val cache = ProviderLyricsCache()
        cache.save("track:auto", "[00:01.00]Line", "netease")
        cache.save("track:auto", """{"tuneLyrics":1,"yrc":"[1000,500](1000,500,0)Line"}""", "autosync")
        cache.save("track:manual", "[00:02.00]Manual", "lrclib-synced", manual = true)

        assertEquals("autosync", cache.currentProviderLyricsSource("track:auto"))
        assertEquals(1, cache.restoreAllAligned())
        assertEquals("[00:01.00]Line", cache.currentProviderLyrics("track:auto"))
        assertEquals("netease", cache.currentProviderLyricsSource("track:auto"))

        cache.clearNonManual()
        assertNull(cache.currentProviderLyrics("track:auto"))
        assertEquals("[00:02.00]Manual", cache.currentProviderLyrics("track:manual"))
    }

    @Test
    fun lyricsTimingDelaysDefaultToThreeSecondsAndSnapToDiscretePresets() {
        val defaults = LyricsSettings()
        assertEquals(3_000L, defaults.lyricFocusReturnDelayMs)
        assertEquals(3_000L, defaults.lyricAutoFullscreenDelayMs)
        assertEquals(3_000L, com.exodidio.tune.ui.navigation.BrowseIdleReturnMs)
        assertEquals(listOf(2_000L, 3_000L, 5_000L, 8_000L, 10_000L), LyricTimingDelayPresetsMs)

        assertEquals(3_000L, normalizeLyricDelayPresetMs(null))
        assertEquals(3_000L, normalizeLyricDelayPresetMs(0L))
        assertEquals(3_000L, normalizeLyricDelayPresetMs(-1_000L))
        assertEquals(2_000L, normalizeLyricDelayPresetMs(2_000L))
        assertEquals(3_000L, normalizeLyricDelayPresetMs(3_000L))
        assertEquals(5_000L, normalizeLyricDelayPresetMs(4_800L))
        assertEquals(10_000L, normalizeLyricDelayPresetMs(20_000L))
    }
}
