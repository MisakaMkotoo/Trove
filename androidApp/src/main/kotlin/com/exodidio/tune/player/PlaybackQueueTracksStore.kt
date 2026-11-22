package com.exodidio.tune.player

import android.content.Context
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.writeTextAtomically
import java.io.File
import kotlinx.serialization.Serializable

/**
 * 随播放会话一起落盘的队列曲目元数据（含网易云流式、在线搜索、本地/远端曲目）。
 * 冷启动时本地扫描和远端曲库可能还没就绪，靠这份数据把队列还原成可播放、可显示。
 */
internal object PlaybackQueueTracksStore {
    private const val FILE_NAME = "playback-queue-tracks.json"

    /** LibraryTrack 本身没有 kotlinx 序列化注解，落盘用这个 DTO 映射。 */
    @Serializable
    private data class QueueTrackData(
        val id: String,
        val title: String = "",
        val artists: String = "",
        val album: String = "",
        val albumId: String = "",
        val artworkKey: String? = null,
        val playCount: Int = 0,
        val createdAt: String = "",
        val updatedAt: String = "",
        val discNumber: Int = 0,
        val trackNumber: Int = 0,
        val syncOrder: Int = 0,
        val metadataJson: String = "{}",
        val artworkPath: String? = null,
        val audioPath: String? = null,
        val sortTitle: String = "",
        val sortArtists: String = "",
    )

    fun read(context: Context): List<LibraryTrack> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        val payload = LibrarySyncProtocol.json.decodeFromString<List<QueueTrackData>>(file.readText())
        System.err.println("QUEUETRACKS read count=${payload.size}")
        payload.map { data ->
            LibraryTrack(
                id = data.id,
                title = data.title,
                artists = data.artists,
                album = data.album,
                albumId = data.albumId,
                artworkKey = data.artworkKey,
                playCount = data.playCount,
                createdAt = data.createdAt,
                updatedAt = data.updatedAt,
                discNumber = data.discNumber,
                trackNumber = data.trackNumber,
                syncOrder = data.syncOrder,
                metadataJson = data.metadataJson,
                artworkPath = data.artworkPath,
                audioPath = data.audioPath,
                sortTitle = data.sortTitle,
                sortArtists = data.sortArtists,
            )
        }
    }.onFailure {
        System.err.println("QUEUETRACKS read failed: ${it.message}")
    }.getOrDefault(emptyList())

    fun write(context: Context, tracks: List<LibraryTrack>) {
        runCatching {
            val payload = tracks.map { track ->
                QueueTrackData(
                    id = track.id,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    albumId = track.albumId,
                    artworkKey = track.artworkKey,
                    playCount = track.playCount,
                    createdAt = track.createdAt,
                    updatedAt = track.updatedAt,
                    discNumber = track.discNumber,
                    trackNumber = track.trackNumber,
                    syncOrder = track.syncOrder,
                    metadataJson = track.metadataJson,
                    artworkPath = track.artworkPath,
                    audioPath = track.audioPath,
                    sortTitle = track.sortTitle,
                    sortArtists = track.sortArtists,
                )
            }
            File(context.filesDir, FILE_NAME).writeTextAtomically(
                LibrarySyncProtocol.json.encodeToString(payload),
            )
            System.err.println("QUEUETRACKS write count=${payload.size}")
        }.onFailure {
            System.err.println("QUEUETRACKS write failed: ${it.message}")
        }
    }
}
