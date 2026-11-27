package com.exodidio.tune.library

import com.exodidio.tune.player.DailyTrackListeningStat
import com.exodidio.tune.sync.LibraryTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlaylistEngineTest {
    private val flacFav = LibraryTrack(
        id = "t1",
        title = "Aurora",
        artists = "Artist A",
        album = "Sky",
        createdAt = "2026-11-20T10:00:00Z",
        audioPath = "/storage/emulated/0/Music/Ambient/aurora.flac",
        metadataJson = """{"is_favorite":true,"duration":320}""",
    )
    private val mp3Fav = LibraryTrack(
        id = "t2",
        title = "Breeze",
        artists = "Artist B",
        album = "Wind",
        createdAt = "2026-11-22T10:00:00Z",
        audioPath = "/storage/emulated/0/Music/Pop/breeze.mp3",
        metadataJson = """{"is_favorite":true,"duration":180}""",
    )
    private val flacNormal = LibraryTrack(
        id = "t3",
        title = "Cascade",
        artists = "Artist A",
        album = "Water",
        createdAt = "2026-11-24T10:00:00Z",
        audioPath = "/storage/emulated/0/Music/Ambient/cascade.flac",
        metadataJson = """{"is_favorite":false,"duration":260}""",
    )

    @Test
    fun `encodeMetadataJson and parseSpec round trip and keep artwork key`() {
        val spec = SmartPlaylistSpec(
            matchMode = SmartPlaylistMatchMode.All,
            rules = listOf(
                SmartPlaylistRule(SmartPlaylistField.Favorite, SmartPlaylistOperator.IsTrue),
                SmartPlaylistRule(SmartPlaylistField.Format, SmartPlaylistOperator.Contains, "lossless"),
            ),
            sortBy = SmartPlaylistSortBy.PlayCountDesc,
            limit = 15,
        )
        val encoded = SmartPlaylistEngine.encodeMetadataJson(
            spec = spec,
            existingMetadataJson = """{"playlist":{"artwork_key":"abc123"}}""",
        )
        assertTrue(SmartPlaylistEngine.isSmartPlaylist(encoded))
        assertTrue(encoded.contains("abc123"))
        val parsed = SmartPlaylistEngine.parseSpec(encoded)
        assertNotNull(parsed)
        assertEquals(spec, parsed)
        assertFalse(SmartPlaylistEngine.isSmartPlaylist("""{"playlist":{"artwork_key":"abc123"}}"""))
    }

    @Test
    fun `evaluate filters all and any rules, sorts, and applies limit`() {
        val counts = SmartPlaylistEngine.aggregatePlayCounts(
            listOf(
                DailyTrackListeningStat("local", "2026-11-20", "t1", listenedSeconds = 300, playCount = 5),
                DailyTrackListeningStat("local", "2026-11-21", "t1", listenedSeconds = 420, playCount = 7),
                DailyTrackListeningStat("local", "2026-11-21", "t2", listenedSeconds = 1000, playCount = 20),
                DailyTrackListeningStat("local", "2026-11-21", "t3", listenedSeconds = 200, playCount = 3),
            ),
        )
        val tracks = listOf(flacFav, mp3Fav, flacNormal)

        // All: Favorite + Lossless -> only t1
        val allSpec = SmartPlaylistSpec(
            matchMode = SmartPlaylistMatchMode.All,
            rules = listOf(
                SmartPlaylistRule(SmartPlaylistField.Favorite, SmartPlaylistOperator.IsTrue),
                SmartPlaylistRule(SmartPlaylistField.Format, SmartPlaylistOperator.Contains, "lossless"),
            ),
        )
        assertEquals(listOf("t1"), SmartPlaylistEngine.evaluate(allSpec, tracks, counts).map { it.id })

        // Any: Folder contains Pop OR Duration > 300, sorted by playCount desc, limit 2
        val anySpec = SmartPlaylistSpec(
            matchMode = SmartPlaylistMatchMode.Any,
            rules = listOf(
                SmartPlaylistRule(SmartPlaylistField.Folder, SmartPlaylistOperator.Contains, "Pop"),
                SmartPlaylistRule(SmartPlaylistField.DurationSec, SmartPlaylistOperator.GreaterThan, "300"),
            ),
            sortBy = SmartPlaylistSortBy.PlayCountDesc,
            limit = 2,
        )
        assertEquals(listOf("t2", "t1"), SmartPlaylistEngine.evaluate(anySpec, tracks, counts).map { it.id })
    }
}
