package com.exodidio.tune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerSongLyricsConfigTest {
    @Test
    fun `applyPerSongLyricsConfig prioritizes preferred source and autoAlign override`() {
        val global = LyricsSettings(
            sourceOrder = listOf(LyricsSourceId.Netease, LyricsSourceId.Amll, LyricsSourceId.Lrclib, LyricsSourceId.Kugou),
            enabledSources = setOf(LyricsSourceId.Netease, LyricsSourceId.Lrclib),
            autoAlignWordTiming = true,
            perSongLyricsConfigEnabled = true,
        )
        val override = PerSongLyricsConfig(
            preferredSource = LyricsSourceId.Amll.storageValue,
            autoAlignOverride = false,
        )
        val effective = applyPerSongLyricsConfig(global, override)
        assertEquals(LyricsSourceId.Amll, effective.sourceOrder.first())
        assertTrue(LyricsSourceId.Amll in effective.enabledSources)
        assertFalse(effective.autoAlignWordTiming)
    }

    @Test
    fun `applyPerSongLyricsConfig preserves global settings when config is null or empty`() {
        val global = LyricsSettings(autoAlignWordTiming = true, perSongLyricsConfigEnabled = true)
        assertEquals(global, applyPerSongLyricsConfig(global, null))
        assertTrue(PerSongLyricsConfig().isEmpty())
        assertEquals(global, applyPerSongLyricsConfig(global, PerSongLyricsConfig()))
    }
}
