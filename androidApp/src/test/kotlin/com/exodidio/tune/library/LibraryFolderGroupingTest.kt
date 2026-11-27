package com.exodidio.tune.library

import com.exodidio.tune.sync.LibraryTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryFolderGroupingTest {
    @Test
    fun `canonicalFolderForPath ignores streaming URIs and extracts parent folder`() {
        assertNull(canonicalFolderForPath(null))
        assertNull(canonicalFolderForPath("netease://12345"))
        assertNull(canonicalFolderForPath("https://example.com/song.mp3"))
        assertNull(canonicalFolderForPath("content://media/external/audio/media/42"))

        assertEquals(
            "/storage/emulated/0/Music/Rock",
            canonicalFolderForPath("file:///storage/emulated/0/Music/Rock/track.flac"),
        )
        assertEquals(
            "/storage/emulated/0/Music/Rock",
            canonicalFolderForPath("/storage/emulated/0/Music/Rock/track.flac"),
        )
    }

    @Test
    fun `folderDisplayNameAndPath formats primary and secondary storage paths`() {
        assertEquals("Internal Storage" to "/", folderDisplayNameAndPath("/storage/emulated/0"))
        assertEquals("Albums" to "Music/Albums", folderDisplayNameAndPath("/storage/emulated/0/Music/Albums"))
        assertEquals("FLAC" to "ABCD-EF12/FLAC", folderDisplayNameAndPath("/storage/ABCD-EF12/FLAC"))
    }

    @Test
    fun `groupTracksByFolder groups tracks and sorts within each folder`() {
        val tracks = listOf(
            LibraryTrack(
                id = "t2",
                title = "Second",
                trackNumber = 2,
                audioPath = "/storage/emulated/0/Music/Jazz/02.flac",
            ),
            LibraryTrack(
                id = "t1",
                title = "First",
                trackNumber = 1,
                audioPath = "/storage/emulated/0/Music/Jazz/01.flac",
            ),
            LibraryTrack(
                id = "t3",
                title = "Single",
                metadataJson = """{"folder_path":"/storage/emulated/0/Music/LoFi"}""",
                audioPath = "content://media/external/audio/media/99",
            ),
            LibraryTrack(
                id = "online",
                title = "Cloud",
                audioPath = "netease://777",
            ),
        )
        val folders = groupTracksByFolder(tracks)
        assertEquals(2, folders.size)
        val jazz = folders.first { it.name == "Jazz" }
        assertEquals(listOf("t1", "t2"), jazz.trackIds)
        assertEquals("Music/Jazz", jazz.displayPath)
        val lofi = folders.first { it.name == "LoFi" }
        assertEquals(listOf("t3"), lofi.trackIds)
    }
}
