package com.exodidio.tune.player

import com.exodidio.tune.netease.NeteaseEntryRecord
import com.exodidio.tune.netease.decodeWifiAutoCacheIds
import com.exodidio.tune.netease.encodeWifiAutoCacheIds
import com.exodidio.tune.netease.selectPendingAutoCacheEntries
import com.exodidio.tune.sync.LibraryTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAndWifiCacheTest {

    @Test
    fun formatWidgetMetadataReflectsPlayingPausedAndIdleStates() {
        val item = PlaybackItem(
            trackId = "netease:1001",
            title = "Night Song",
            artist = "Artist A",
            artworkPath = "/tmp/cover.jpg",
            audioPath = "netease://1001",
        )

        val playing = formatWidgetMetadata(
            state = PlaybackState.Playing(item, 1_000L, 120_000L),
            defaultTitle = "Music",
            defaultSubtitle = "Tap to open",
            unknownArtistLabel = "Unknown",
        )
        assertTrue(playing.isPlaying)
        assertTrue(playing.hasTrack)
        assertEquals("netease:1001", playing.trackId)
        assertEquals("Night Song", playing.title)
        assertEquals("Artist A", playing.artist)

        val paused = formatWidgetMetadata(
            state = PlaybackState.Paused(item.copy(artist = ""), 1_000L, 120_000L),
            defaultTitle = "Music",
            defaultSubtitle = "Tap to open",
            unknownArtistLabel = "Unknown",
        )
        assertFalse(paused.isPlaying)
        assertTrue(paused.hasTrack)
        assertEquals("Unknown", paused.artist)

        val idle = formatWidgetMetadata(
            state = PlaybackState.Idle,
            defaultTitle = "Music",
            defaultSubtitle = "Tap to open",
            unknownArtistLabel = "Unknown",
        )
        assertFalse(idle.isPlaying)
        assertFalse(idle.hasTrack)
        assertNull(idle.trackId)
        assertEquals("Music", idle.title)
        assertEquals("Tap to open", idle.artist)
    }

    @Test
    fun wifiAutoCacheIdsRoundTripAndIgnoreBlank() {
        val encoded = encodeWifiAutoCacheIds(setOf("dynamic:daily", " 12345 ", "", "99"))
        assertEquals("12345,99,dynamic:daily", encoded)
        assertEquals(
            setOf("12345", "99", "dynamic:daily"),
            decodeWifiAutoCacheIds(encoded),
        )
        assertEquals(emptySet<String>(), decodeWifiAutoCacheIds(null))
        assertEquals(emptySet<String>(), decodeWifiAutoCacheIds(" , "))
    }

    @Test
    fun selectPendingAutoCacheEntriesSkipsExistingDownloads() {
        val downloadedEntry = NeteaseEntryRecord(
            songId = 1L,
            title = "Cached",
            artists = "A",
            album = "Al",
            downloadedFile = "/data/1.flac",
        )
        val missingFileEntry = NeteaseEntryRecord(
            songId = 2L,
            title = "Missing",
            artists = "B",
            album = "Al",
            downloadedFile = "/data/deleted.flac",
        )
        val importedEntry = NeteaseEntryRecord(
            songId = 3L,
            title = "Imported",
            artists = "C",
            album = "Al",
        )
        val streamOnlyEntry = NeteaseEntryRecord(
            songId = 4L,
            title = "StreamOnly",
            artists = "D",
            album = "Al",
        )
        val library = mapOf(
            "netease:3" to LibraryTrack(
                id = "netease:3",
                title = "Imported",
                artists = "C",
                album = "Al",
                albumId = "a3",
                metadataJson = "{}",
                artworkPath = null,
                audioPath = "/data/imported3.mp3",
                createdAt = "",
                updatedAt = "",
                sortTitle = "Imported",
                sortArtists = "C",
            ),
            "netease:4" to LibraryTrack(
                id = "netease:4",
                title = "StreamOnly",
                artists = "D",
                album = "Al",
                albumId = "a4",
                metadataJson = "{}",
                artworkPath = null,
                audioPath = "netease://4",
                createdAt = "",
                updatedAt = "",
                sortTitle = "StreamOnly",
                sortArtists = "D",
            ),
        )

        val pending = selectPendingAutoCacheEntries(
            entries = listOf(downloadedEntry, missingFileEntry, importedEntry, streamOnlyEntry),
            libraryById = library,
            fileExists = { path -> path == "/data/1.flac" || path == "/data/imported3.mp3" },
        )
        assertEquals(listOf(2L, 4L), pending.map { it.songId })
    }
}
