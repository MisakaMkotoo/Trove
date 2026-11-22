package com.exodidio.tune.player

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import com.exodidio.tune.sync.LibraryTrack

class AndroidPlaybackRuntimeTest {
    @Test
    fun `keeps every artist from playback metadata`() {
        val metadata = Json.parseToJsonElement(
            """{"artists":[{"name":"Artist A"},{"name":"Artist B"}]}""",
        ) as JsonObject

        assertEquals("Artist A, Artist B", playbackArtistNames(metadata, "Artist A"))
    }

    @Test
    fun `resolves synced relative audio path under app files directory`() {
        val filesDir = File("/data/user/0/com.exodidio.tune/files")

        assertEquals(
            File(filesDir, "library-sync/assets/audio-file").path,
            resolveSyncedAudioFile(filesDir, "library-sync/assets/audio-file")?.path,
        )
    }

    @Test
    fun `validates restored queue from one snapshot and existing audio files`() {
        val filesDir = File.createTempFile("tune-playback", "").apply {
            delete()
            mkdirs()
        }
        try {
            File(filesDir, "library-sync/assets/kept").apply {
                parentFile?.mkdirs()
                writeText("")
            }

            val available = availableSyncedTrackIds(
                filesDir = filesDir,
                tracks = listOf(
                    LibraryTrack(id = "kept", title = "Kept", artists = "Artist", audioPath = "library-sync/assets/kept"),
                    LibraryTrack(id = "missing-file", title = "Missing", artists = "Artist", audioPath = "library-sync/assets/missing"),
                    LibraryTrack(id = "not-requested", title = "Other", artists = "Artist", audioPath = "library-sync/assets/kept"),
                ),
                requestedTrackIds = setOf("kept", "missing-file", "removed-from-library"),
            )

            // 未知 id（曲库还没恢复完/流式曲目）先保留；只有已知但文件失效的本地曲目被丢弃。
            assertEquals(setOf("kept", "removed-from-library"), available)
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
