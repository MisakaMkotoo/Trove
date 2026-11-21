package com.exodidio.tune.sync

import android.content.Context
import com.exodidio.tune.library.LocalImport
import com.exodidio.tune.mood.MoodRadioTrack
import com.exodidio.tune.player.DailyPlaybackAttemptStat
import com.exodidio.tune.player.DailyTrackListeningStat
import com.exodidio.tune.player.ListeningWrite
import com.exodidio.tune.player.PlaybackEndReason
import com.exodidio.tune.player.TrackAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** ============================================================================
 *  Data classes recreated from deleted SyncDatabase.kt
 * ============================================================================ */

 data class LibraryTrack(
    val id: String = "",
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

/** Row type used by sync mapper tests. */
 data class LibraryTrackRow(
    val id: String = "",
    val title: String = "",
    val artists: String = "",
    val album: String = "",
    val artworkKey: String? = null,
    val playCount: Int = 0,
    val createdAt: String = "",
    val artworkPath: String? = null,
    val audioPath: String? = null,
    val rawJson: String = "{}",
)

 data class LibraryPlaylist(
    val id: String = "",
    val name: String = "",
    val artworkKey: String? = null,
    val trackIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val sortTitle: String = "",
    val metadataJson: String = "{}",
)

 data class LibraryPlaylistRow(
    val id: String = "",
    val name: String = "",
    val artworkKey: String? = null,
    val trackIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
)

 data class LibraryArtist(
    val id: String = "",
    val name: String = "",
    val sortName: String = "",
    val createdAt: String = "",
    val artworkPath: String? = null,
)

 data class LibraryAlbum(
    val id: String = "",
    val title: String = "",
    val sortTitle: String = "",
    val sortArtist: String = "",
    val artist: String = "",
    val artworkPath: String? = null,
    val createdAt: String = "",
    val year: Int = 0,
    val copyright: String = "",
)

val LibraryAlbum.sortName: String
    get() = sortArtist

 data class LibraryGenre(
    val id: String = "",
    val name: String = "",
    val trackCount: Int = 0,
    val sortName: String = "",
    val createdAt: String = "",
)

 data class LibraryComposer(
    val id: String = "",
    val name: String = "",
    val sortName: String = "",
    val createdAt: String = "",
    val artworkPath: String? = null,
)

 data class LibrarySearchCandidate(
    val entityType: String,
    val entityId: String,
    val title: String,
    val subtitle: String = "",
)

 data class SyncTrackEntity(
    val planId: String,
    val trackId: String,
    val title: String,
    val artists: String,
    val album: String = "",
    val albumId: String = "",
    val artworkKey: String? = null,
    val playCount: Int = 0,
    val createdAt: String = "",
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val syncOrder: Int = 0,
    val rawJson: String = "{}",
)

 data class SyncAssetEntity(
    val planId: String,
    val assetId: String,
    val kind: String,
    val sha256: String = "",
    val size: Long = 0L,
    val relativePath: String = "",
)

 data class SyncPlaylistEntity(
    val planId: String,
    val playlistId: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
)

 data class LibrarySearchDocumentEntity(
    val planId: String,
    val entityType: String,
    val entityId: String,
    val title: String,
    val subtitle: String = "",
    val keywords: List<String> = emptyList(),
)

 data class SyncPlanEntity(
    val planId: String,
    val source: String,
    val manifestJson: String,
    val status: String,
    val isActive: Boolean = false,
)

/** ============================================================================
 *  JSON helpers
 * ============================================================================ */

 object LibrarySyncProtocol {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

/** ============================================================================
 *  Extension: LibraryTrack.metadataObject()
 * ============================================================================ */

 fun LibraryTrack.metadataObject(): JsonObject? =
    try {
        LibrarySyncProtocol.json.parseToJsonElement(metadataJson) as? JsonObject
    } catch (e: Exception) {
        null
    }

/** ============================================================================
 *  Display helpers
 * ============================================================================ */

 fun trackDisplayTitle(track: LibraryTrack): String =
    track.title.ifBlank { track.id.ifBlank { "Unknown song" } }

 fun trackDisplayArtists(track: LibraryTrack): String =
    track.artists.ifBlank { "Unknown artist" }

/** ============================================================================
 *  Mapper helpers (recreated for sync mapper tests)
 * ============================================================================ */

 fun libraryArtistsFrom(
    tracks: List<LibraryTrackRow>,
    artworkPaths: Map<String, String> = emptyMap(),
): List<LibraryArtist> {
    val seen = mutableMapOf<String, LibraryArtist>()
    val trackArtwork = tracks.associateBy({ it.id }, { it.artworkPath })
    for (track in tracks) {
        val rawJson = track.rawJson
        val root = runCatching { LibrarySyncProtocol.json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: continue
        val artistsArr = root.get("artists") as? JsonArray ?: continue
        for (entry in artistsArr) {
            val obj = entry as? JsonObject ?: continue
            val id = obj.get("id")?.jsonPrimitive?.contentOrNull ?: continue
            if (id.isBlank()) continue
            val name = obj.get("name")?.jsonPrimitive?.contentOrNull ?: continue
            if (name.isBlank()) continue
            val explicitSortName = obj.get("sort_name")?.jsonPrimitive?.contentOrNull
                ?: obj.get("normalization_key")?.jsonPrimitive?.contentOrNull
            val explicitCreatedAt = obj.get("created_at")?.jsonPrimitive?.contentOrNull
            val artworkKey = obj.get("artwork_key")?.jsonPrimitive?.contentOrNull
                ?: obj.get("artwork_key_local")?.jsonPrimitive?.contentOrNull
            var artworkPath: String? = null
            if (artworkKey != null) {
                artworkPath = artworkPaths[artworkKey] ?: artworkPaths["artwork:$id"] ?: artworkPaths[artworkKey.removePrefix("artwork:")]
                if (artworkPath == null) {
                    val stagedKey = artworkKey.removePrefix("artwork:")
                    artworkPath = artworkPaths[stagedKey]
                }
            }
            if (artworkPath == null) {
                artworkPath = trackArtwork[track.id]
            }
            val existing = seen[id]
            seen[id] = LibraryArtist(
                id = id,
                name = name,
                sortName = explicitSortName ?: existing?.sortName ?: name,
                createdAt = explicitCreatedAt ?: existing?.createdAt ?: track.createdAt,
                artworkPath = artworkPath ?: existing?.artworkPath,
            )
        }
    }
    return seen.values.toList()
}

 fun libraryAlbumsFrom(
    tracks: List<LibraryTrackRow>,
    artworkPaths: Map<String, String> = emptyMap(),
): List<LibraryAlbum> {
    val seen = mutableMapOf<String, LibraryAlbum>()
    val trackArtwork = tracks.associateBy({ it.id }, { it.artworkPath })
    for (track in tracks) {
        val root = runCatching { LibrarySyncProtocol.json.parseToJsonElement(track.rawJson) as? JsonObject }.getOrNull()
            ?: continue
        val albumObj = root.get("album") as? JsonObject ?: continue
        val albumId = albumObj.get("id")?.jsonPrimitive?.contentOrNull ?: continue
        if (albumId.isBlank()) continue
        val title = albumObj.get("title")?.jsonPrimitive?.contentOrNull ?: ""
        if (title.isBlank()) continue
        val sortTitle = albumObj.get("sort_title")?.jsonPrimitive?.contentOrNull ?: title
        val year = albumObj.get("year")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val copyright = albumObj.get("copyright")?.jsonPrimitive?.contentOrNull ?: ""
        val createdAt = albumObj.get("created_at")?.jsonPrimitive?.contentOrNull ?: track.createdAt
        var artworkKey = albumObj.get("artwork_key")?.jsonPrimitive?.contentOrNull
        var artworkPath: String? = null
        if (artworkKey != null) {
            artworkPath = artworkPaths[artworkKey] ?: artworkPaths["artwork:$albumId"]
            if (artworkPath == null) {
                val stagedKey = artworkKey.removePrefix("artwork:")
                artworkPath = artworkPaths[stagedKey]
            }
        }
        if (artworkPath == null) {
            artworkPath = trackArtwork[track.id]
        }
        // Resolve album artist
        var artist = ""
        var sortArtist = ""
        var explicitSortArtist = false
        val albumArtists = root.get("album_artists") as? JsonArray
        if (albumArtists != null && albumArtists.isNotEmpty()) {
            val first = albumArtists.firstOrNull { it is JsonObject } as? JsonObject
            artist = first?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val explicitSortName = first?.get("sort_name")?.jsonPrimitive?.contentOrNull
            sortArtist = explicitSortName ?: artist
            explicitSortArtist = explicitSortName != null
        }
        if (artist.isBlank()) {
            val trackArtists = root.get("artists") as? JsonArray
            val firstTrackArtist = trackArtists?.firstOrNull { it is JsonObject } as? JsonObject
            artist = firstTrackArtist?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val explicitSortName = firstTrackArtist?.get("sort_name")?.jsonPrimitive?.contentOrNull
            sortArtist = explicitSortName ?: artist
            explicitSortArtist = explicitSortName != null
        }
        val existing = seen[albumId]
        seen[albumId] = LibraryAlbum(
            id = albumId,
            title = existing?.title?.takeIf { it.isNotBlank() } ?: title,
            sortTitle = existing?.sortTitle?.takeIf { it.isNotBlank() } ?: sortTitle,
            sortArtist = if (explicitSortArtist) sortArtist else existing?.sortArtist ?: sortArtist,
            artist = existing?.artist?.takeIf { it.isNotBlank() } ?: artist,
            artworkPath = artworkPath ?: existing?.artworkPath,
            createdAt = existing?.createdAt?.takeIf { it.isNotBlank() } ?: createdAt,
            year = existing?.year?.takeIf { it > 0 } ?: year,
            copyright = existing?.copyright?.takeIf { it.isNotBlank() } ?: copyright,
        )
    }
    return seen.values.toList()
}

 fun libraryComposersFrom(
    tracks: List<LibraryTrackRow>,
    artworkPaths: Map<String, String> = emptyMap(),
): List<LibraryComposer> {
    val seen = mutableMapOf<String, LibraryComposer>()
    val trackArtwork = tracks.associateBy({ it.id }, { it.artworkPath })
    for (track in tracks) {
        val root = runCatching { LibrarySyncProtocol.json.parseToJsonElement(track.rawJson) as? JsonObject }.getOrNull()
            ?: continue
        val composersArr = root.get("composers") as? JsonArray ?: continue
        for (entry in composersArr) {
            val obj = entry as? JsonObject ?: continue
            val id = obj.get("id")?.jsonPrimitive?.contentOrNull ?: continue
            if (id.isBlank()) continue
            val name = obj.get("name")?.jsonPrimitive?.contentOrNull ?: continue
            if (name.isBlank()) continue
            val explicitSortName = obj.get("sort_name")?.jsonPrimitive?.contentOrNull
                ?: obj.get("normalization_key")?.jsonPrimitive?.contentOrNull
            val explicitCreatedAt = obj.get("created_at")?.jsonPrimitive?.contentOrNull
            val artworkKey = obj.get("artwork_key")?.jsonPrimitive?.contentOrNull
            var artworkPath: String? = null
            if (artworkKey != null) {
                artworkPath = artworkPaths[artworkKey] ?: artworkPaths["artwork:$id"]
                if (artworkPath == null) {
                    val stagedKey = artworkKey.removePrefix("artwork:")
                    artworkPath = artworkPaths[stagedKey]
                }
            }
            if (artworkPath == null) {
                artworkPath = trackArtwork[track.id]
            }
            val existing = seen[id]
            seen[id] = LibraryComposer(
                id = id,
                name = name,
                sortName = explicitSortName ?: existing?.sortName ?: name,
                createdAt = explicitCreatedAt ?: existing?.createdAt ?: track.createdAt,
                artworkPath = artworkPath ?: existing?.artworkPath,
            )
        }
    }
    return seen.values.toList()
}

fun libraryGenresFrom(
    tracks: List<LibraryTrackRow>,
): List<LibraryGenre> {
    val seen = mutableMapOf<String, LibraryGenre>()
    for (track in tracks) {
        val root = runCatching { LibrarySyncProtocol.json.parseToJsonElement(track.rawJson) as? JsonObject }.getOrNull()
            ?: continue
        val genresArr = root.get("genres") as? JsonArray ?: continue
        for (entry in genresArr) {
            val obj = entry as? JsonObject ?: continue
            val id = obj.get("id")?.jsonPrimitive?.contentOrNull ?: continue
            if (id.isBlank()) continue
            val name = obj.get("name")?.jsonPrimitive?.contentOrNull ?: continue
            if (name.isBlank()) continue
            val explicitSortName = obj.get("sort_name")?.jsonPrimitive?.contentOrNull
                ?: obj.get("normalization_key")?.jsonPrimitive?.contentOrNull
            val explicitCreatedAt = obj.get("created_at")?.jsonPrimitive?.contentOrNull
            val existing = seen[id]
            seen[id] = LibraryGenre(
                id = id,
                name = name,
                sortName = explicitSortName ?: existing?.sortName ?: name,
                createdAt = explicitCreatedAt ?: existing?.createdAt ?: track.createdAt,
            )
        }
    }
    return seen.values.toList()
}

/** ============================================================================
 *  FTS search match builder
 * ============================================================================ */

 fun searchFtsMatch(query: String): String? {
    val normalized = java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    val tokens = normalized
        .lowercase()
        .split(Regex("[\\s/]+"))
        .filter { it.isNotBlank() }
        .map { it.replace(Regex("[^a-z0-9]"), "") }
        .filter { it.isNotBlank() }
    return if (tokens.isEmpty()) null else tokens.joinToString(" AND ") { "$it*" }
}

/** ============================================================================
 *  Search document builder
 * ============================================================================ */

 fun searchDocumentsFor(
    planId: String,
    entities: List<SyncTrackEntity>,
    playlists: List<SyncPlaylistEntity>,
): List<LibrarySearchDocumentEntity> {
    val docs = mutableListOf<LibrarySearchDocumentEntity>()
    for (entity in entities) {
        val root = runCatching { LibrarySyncProtocol.json.parseToJsonElement(entity.rawJson) as? JsonObject }.getOrNull()
        if (root == null) continue
        val title = root.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
        val artists = root.get("artists") as? JsonArray
        val artistNames = artists?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.orEmpty()
        val subtitle = artistNames.joinToString(", ")
        val keywords = mutableListOf(title)
        keywords.addAll(artistNames)
        val albumObj = root.get("album") as? JsonObject
        val albumTitle = albumObj?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (albumTitle.isNotBlank()) keywords.add(albumTitle)
        docs.add(
            LibrarySearchDocumentEntity(
                planId = planId,
                entityType = "track",
                entityId = entity.trackId,
                title = title,
                subtitle = subtitle,
                keywords = keywords.distinct(),
            ),
        )
        // Artist documents
        for (artistName in artistNames) {
            if (artistName.isNotBlank()) {
                docs.add(
                    LibrarySearchDocumentEntity(
                        planId = planId,
                        entityType = "artist",
                        entityId = stableId("local-artist-", artistName),
                        title = artistName,
                        keywords = listOf(artistName),
                    ),
                )
            }
        }
        // Album documents
        val albumId = albumObj?.get("id")?.jsonPrimitive?.contentOrNull
        if (albumId?.isNotBlank() == true && albumTitle.isNotBlank()) {
            docs.add(
                LibrarySearchDocumentEntity(
                    planId = planId,
                    entityType = "album",
                    entityId = albumId,
                    title = albumTitle,
                    keywords = listOf(albumTitle),
                ),
            )
        }
        // Composer documents
        val composersArr = root.get("composers") as? JsonArray
        if (composersArr != null) {
            for (entry in composersArr) {
                val obj = entry as? JsonObject ?: continue
                val composerId = obj.get("id")?.jsonPrimitive?.contentOrNull ?: continue
                if (composerId.isBlank()) continue
                val composerName = obj.get("name")?.jsonPrimitive?.contentOrNull ?: continue
                if (composerName.isBlank()) continue
                docs.add(
                    LibrarySearchDocumentEntity(
                        planId = planId,
                        entityType = "composer",
                        entityId = composerId,
                        title = composerName,
                        keywords = listOf(composerName),
                    ),
                )
            }
        }
    }
    for (playlist in playlists) {
        docs.add(
            LibrarySearchDocumentEntity(
                planId = planId,
                entityType = "playlist",
                entityId = playlist.playlistId,
                title = playlist.name,
                keywords = listOf(playlist.name),
            ),
        )
    }
    return docs
}

/** ============================================================================
 *  Artist artwork mutation types
 * ============================================================================ */

 enum class ArtistArtworkOperation { SET_ARTWORK, REMOVE_ARTWORK }

 data class ArtistArtworkPayload(val sha256: String?)

 data class ArtistMutation(
    val mutationId: String,
    val artistId: String,
    val operation: ArtistArtworkOperation,
    val updatedAt: Long,
    val payload: ArtistArtworkPayload? = null,
) {
    fun validationError(): String? =
        when {
            operation == ArtistArtworkOperation.SET_ARTWORK && (payload?.sha256.isNullOrBlank() == true) ->
                "SET_ARTWORK requires a valid sha256"
            else -> null
        }
}

/** ============================================================================
 *  Staged artwork types (used by ArtistArtworkFiles / PlaylistArtworkFiles)
 * ============================================================================ */

 data class StagedArtistArtwork(
    val sha256: String,
    val mime: String,
    val sizeBytes: Long,
    val relativePath: String,
)

 data class StagedPlaylistArtwork(
    val sha256: String,
    val mime: String,
    val sizeBytes: Long,
    val relativePath: String,
)

/** ============================================================================
 *  Playlist mutation types
 * ============================================================================ */

 enum class PlaylistMutationOperation {
    CREATE, ADD_TRACK, REMOVE_TRACK, UPDATE, SET_ARTWORK, REMOVE_ARTWORK, DELETE, MOVE_TRACK
}

 data class PlaylistMutationPayload(
    val name: String? = null,
    val trackId: String? = null,
    val artworkSha256: String? = null,
    val previousTrackId: String? = null,
    val nextTrackId: String? = null,
)

 data class PlaylistMutation(
    val mutationId: String,
    val playlistId: String,
    val operation: PlaylistMutationOperation,
    val updatedAt: Long,
    val payload: PlaylistMutationPayload = PlaylistMutationPayload(),
)

/** ============================================================================
 *  applyPendingArtistArtworkMutations
 * ============================================================================ */

 fun applyPendingArtistArtworkMutations(
    existing: Map<String, String>,
    pending: List<ArtistMutation>,
): Map<String, String> {
    var result = existing.toMutableMap()
    for (mutation in pending) {
        when (mutation.operation) {
            ArtistArtworkOperation.SET_ARTWORK -> {
                mutation.payload?.sha256?.let { result[mutation.artistId] = it }
            }
            ArtistArtworkOperation.REMOVE_ARTWORK -> {
                result.remove(mutation.artistId)
            }
        }
    }
    return result
}

/** ============================================================================
 *  Stable ID helpers
 * ============================================================================ */

 fun stableId(prefix: String, name: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(name.lowercase().toByteArray())
    return prefix + digest.joinToString("") { "%02x".format(it) }.take(12)
}

/** UI 层的收藏歌单使用同一个固定 id。 */
private const val FavoritesPlaylistId = "favorites"

 fun artistIdFor(name: String): String = stableId("local-artist-", name)
 fun albumIdFor(name: String): String = stableId("local-album-", name)
 fun composerIdFor(name: String): String = stableId("local-composer-", name)
 fun genreIdFor(name: String): String = stableId("local-genre-", name)

/** ============================================================================
 *  AndroidLibrarySyncStore — local-only replacement for the Room-backed store
 * ============================================================================ */

 class AndroidLibrarySyncStore(private val context: Context) {

    @Serializable
    private data class PersistedPlaylist(
        val id: String = "",
        val name: String = "",
        val trackIds: List<String> = emptyList(),
        val createdAt: String = "",
        val updatedAt: String = "",
        val metadataJson: String = "{}",
    )

    @Serializable
    private data class PersistedState(
        val favorites: List<String> = emptyList(),
        val hidden: List<String> = emptyList(),
        val onlineFavorites: List<ImportedTrackData> = emptyList(),
        val artworkPaths: Map<String, String> = emptyMap(),
        val artistArtworkKeys: Map<String, String> = emptyMap(),
        val playlists: List<PersistedPlaylist> = emptyList(),
    )

    @Serializable
    private data class ImportedTrackData(
        val id: String = "",
        val title: String = "",
        val artists: String = "",
        val album: String = "",
        val albumId: String = "",
        val metadataJson: String = "{}",
        val artworkPath: String? = null,
        val audioPath: String? = null,
        val createdAt: String = "",
    )

    private fun ImportedTrackData.toLibraryTrackOrNull(): LibraryTrack? {
        if (id.isBlank()) return null
        return LibraryTrack(
            id = id,
            title = title,
            artists = artists,
            album = album,
            albumId = albumId,
            metadataJson = metadataJson,
            artworkPath = artworkPath,
            audioPath = audioPath,
            createdAt = createdAt,
            updatedAt = createdAt,
            sortTitle = title,
            sortArtists = artists,
        )
    }

    private fun LibraryTrack.toImportedData(): ImportedTrackData = ImportedTrackData(
        id = id,
        title = title,
        artists = artists,
        album = album,
        albumId = albumId,
        metadataJson = metadataJson,
        artworkPath = artworkPath,
        audioPath = audioPath,
        createdAt = createdAt,
    )

    @Serializable
    private data class PersistedListening(
        val tracks: List<DailyTrackListeningStat> = emptyList(),
        val attempts: List<DailyPlaybackAttemptStat> = emptyList(),
    )

    private val stateFile = File(context.filesDir, "local-library-state.json")
    private val importedFile = File(context.filesDir, "imported-library.json")
    private val listeningFile = File(context.filesDir, "listening-stats.json")
    private val trackDayStats = mutableMapOf<Pair<String, String>, DailyTrackListeningStat>()
    private val attemptDayStats = mutableMapOf<String, DailyPlaybackAttemptStat>()
    private var listeningDirty = false
    private var lastListeningPersistAt = 0L

    private val _tracks = MutableStateFlow(emptyList<LibraryTrack>())
    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()

    // 网易云在线歌单按需下载的曲目：可播放、可解析，但不进入曲库列表。
    private val playbackOnlyTracks = MutableStateFlow(emptyList<LibraryTrack>())
    /** 仅播放曲目（含在线搜索/心动模式/歌单流式条目），封面回填后列表可自动刷新。 */
    val playbackOnlyTracksFlow: StateFlow<List<LibraryTrack>> = playbackOnlyTracks.asStateFlow()

    // 收藏的在线曲目：单独持久化，曲库列表不变，但收藏歌单/详情页能看到。
    private val onlineFavoriteTracks = MutableStateFlow(emptyList<LibraryTrack>())
    val onlineFavorites: StateFlow<List<LibraryTrack>> = onlineFavoriteTracks.asStateFlow()

    fun putPlaybackOnlyTrack(track: LibraryTrack) {
        val favorite = track.id in favorites
        val patched = if (favorite) track.copy(metadataJson = favoriteJson(track.metadataJson, true)) else track
        playbackOnlyTracks.value = playbackOnlyTracks.value.filterNot { it.id == track.id } + patched
        if (favorite && onlineFavoriteTracks.value.any { it.id == track.id }) {
            onlineFavoriteTracks.value = onlineFavoriteTracks.value.filterNot { it.id == track.id } + patched
        }
    }

    /** 播放解析用：先查曲库，再查“仅播放”的在线曲目。 */
    fun trackForPlayback(trackId: String): LibraryTrack? =
        _tracks.value.firstOrNull { it.id == trackId }
            ?: playbackOnlyTracks.value.firstOrNull { it.id == trackId }

    // 本地扫描结果、网易云导入和电脑曲库分开存，任何一方变化后重新拼起来。
    private var localTracks: List<LibraryTrack> = emptyList()
    private var importedTracks: List<LibraryTrack> = emptyList()
    private var remoteTracks: List<LibraryTrack> = emptyList()

    private val _playlists = MutableStateFlow(emptyList<LibraryPlaylist>())
    val playlists: StateFlow<List<LibraryPlaylist>> = _playlists.asStateFlow()

    private val _albums = MutableStateFlow(emptyList<LibraryAlbum>())
    val albums: StateFlow<List<LibraryAlbum>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow(emptyList<LibraryArtist>())
    val artists: StateFlow<List<LibraryArtist>> = _artists.asStateFlow()

    private val _genres = MutableStateFlow(emptyList<LibraryGenre>())
    val genres: StateFlow<List<LibraryGenre>> = _genres.asStateFlow()

    private val _composers = MutableStateFlow(emptyList<LibraryComposer>())
    val composers: StateFlow<List<LibraryComposer>> = _composers.asStateFlow()

    private val _moodRadioEligibleTrackIds = MutableStateFlow(emptySet<String>())
    val moodRadioEligibleTrackIds: StateFlow<Set<String>> = _moodRadioEligibleTrackIds.asStateFlow()

    private val _analysisAvailable = MutableStateFlow(false)
    val analysisAvailable: StateFlow<Boolean> = _analysisAvailable.asStateFlow()

    private val _libraryAnalysisEnabled = MutableStateFlow(false)
    val libraryAnalysisEnabled: StateFlow<Boolean> = _libraryAnalysisEnabled.asStateFlow()

    private val _lastListenedAt = MutableStateFlow(emptyMap<String, Long>())
    val lastListenedAt: StateFlow<Map<String, Long>> = _lastListenedAt.asStateFlow()

    private val _artistArtworkKeys = MutableStateFlow(emptyMap<String, String>())
    val artistArtworkKeys: StateFlow<Map<String, String>> = _artistArtworkKeys.asStateFlow()

    private val _artworkPaths = MutableStateFlow(emptyMap<String, String>())
    val artworkPaths: StateFlow<Map<String, String>> = _artworkPaths.asStateFlow()

    private val _dailyTrackListeningStats = MutableStateFlow(emptyList<DailyTrackListeningStat>())
    val dailyTrackListeningStats: StateFlow<List<DailyTrackListeningStat>> = _dailyTrackListeningStats.asStateFlow()

    private val _dailyPlaybackAttemptStats = MutableStateFlow(emptyList<DailyPlaybackAttemptStat>())
    val dailyPlaybackAttemptStats: StateFlow<List<DailyPlaybackAttemptStat>> = _dailyPlaybackAttemptStats.asStateFlow()

    private val analyses = mutableMapOf<String, TrackAnalysis>()
    val activeAnalyses: Map<String, TrackAnalysis> get() = analyses.toMap()

    private val providerLyricsStore = mutableMapOf<String, String>()
    private val _providerLyrics = mutableMapOf<String, MutableStateFlow<String?>>()
    fun providerLyrics(trackId: String): Flow<String?> =
        _providerLyrics.getOrPut(trackId) {
            MutableStateFlow(providerLyricsStore[trackId])
        }

    private val favorites = mutableSetOf<String>()
    fun isFavorite(trackId: String): Boolean = favorites.contains(trackId)

    private val hidden = mutableSetOf<String>()
    private val _hiddenTracks = MutableStateFlow(emptyList<LibraryTrack>())
    val hiddenTracks: StateFlow<List<LibraryTrack>> = _hiddenTracks.asStateFlow()

    init {
        val persisted = runCatching {
            LibrarySyncProtocol.json.decodeFromString<PersistedState>(stateFile.readText())
        }.getOrDefault(PersistedState())
        favorites.addAll(persisted.favorites)
        persisted.onlineFavorites.mapNotNull { it.toLibraryTrackOrNull() }.forEach { restored ->
            val patched = restored.copy(metadataJson = favoriteJson(restored.metadataJson, true))
            onlineFavoriteTracks.value = onlineFavoriteTracks.value.filterNot { it.id == patched.id } + patched
            playbackOnlyTracks.value = playbackOnlyTracks.value.filterNot { it.id == patched.id } + patched
        }
        hidden.addAll(persisted.hidden)
        _artworkPaths.value = persisted.artworkPaths
        _artistArtworkKeys.value = persisted.artistArtworkKeys
        _playlists.value = persisted.playlists.map { playlist ->
            LibraryPlaylist(
                id = playlist.id,
                name = playlist.name,
                trackIds = playlist.trackIds,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
                sortTitle = playlist.name,
                metadataJson = playlist.metadataJson,
            )
        }
        runCatching {
            LibrarySyncProtocol.json.decodeFromString<PersistedListening>(listeningFile.readText())
        }.getOrNull()?.let { persisted ->
            persisted.tracks.forEach { trackDayStats[it.localDate to it.trackId] = it }
            persisted.attempts.forEach { attemptDayStats[it.localDate] = it }
            _dailyTrackListeningStats.value = persisted.tracks
            _dailyPlaybackAttemptStats.value = persisted.attempts
        }
        importedTracks = runCatching {
            LibrarySyncProtocol.json.decodeFromString<List<ImportedTrackData>>(importedFile.readText())
        }.getOrDefault(emptyList()).map { track ->
            LibraryTrack(
                id = track.id,
                title = track.title,
                artists = track.artists,
                album = track.album,
                albumId = track.albumId,
                metadataJson = track.metadataJson,
                artworkPath = track.artworkPath,
                audioPath = track.audioPath,
                createdAt = track.createdAt,
                updatedAt = track.createdAt,
                sortTitle = track.title,
                sortArtists = track.artists,
            )
        }.filter { track -> track.audioPath?.let { File(it).isFile } == true }
        if (importedTracks.isNotEmpty()) publishTracks()
    }

    /** 网易云导入的曲目；持久化在文件里，重启后自动回到曲库。 */
    fun importedTracksSnapshot(): List<LibraryTrack> = importedTracks

    suspend fun setImportedTracks(tracks: List<LibraryTrack>) {
        importedTracks = tracks
        runCatching {
            importedFile.writeText(
                LibrarySyncProtocol.json.encodeToString(
                    tracks.map { track ->
                        ImportedTrackData(
                            id = track.id,
                            title = track.title,
                            artists = track.artists,
                            album = track.album,
                            albumId = track.albumId,
                            metadataJson = track.metadataJson,
                            artworkPath = track.artworkPath,
                            audioPath = track.audioPath,
                            createdAt = track.createdAt,
                        )
                    },
                ),
            )
        }
        publishTracks()
    }

    private fun persistState() {
        synchronized(this) {
        val state = PersistedState(
            favorites = favorites.toList(),
            hidden = hidden.toList(),
            onlineFavorites = onlineFavoriteTracks.value.map { it.toImportedData() },
            artworkPaths = _artworkPaths.value,
            artistArtworkKeys = _artistArtworkKeys.value,
            playlists = _playlists.value.map { playlist ->
                PersistedPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    trackIds = playlist.trackIds,
                    createdAt = playlist.createdAt,
                    updatedAt = playlist.updatedAt,
                    metadataJson = playlist.metadataJson,
                )
            },
        )
        runCatching { stateFile.writeText(LibrarySyncProtocol.json.encodeToString(state)) }
        }
    }

    private fun publishTracks(rebuildEntities: Boolean = true) {
        val merged = localTracks + importedTracks + remoteTracks
        _hiddenTracks.value = merged.filter { it.id in hidden }
        val visible = merged.filterNot { it.id in hidden }
        val published = if (favorites.isEmpty()) {
            visible
        } else {
            visible.map { track ->
                if (track.id in favorites) track.copy(metadataJson = favoriteJson(track.metadataJson, true)) else track
            }
        }
        _tracks.value = published
        _analysisAvailable.value = published.isNotEmpty()
        _moodRadioEligibleTrackIds.value = (localTracks + importedTracks).map { it.id }.toSet() - hidden
        if (rebuildEntities) rebuildEntityLists(published)
    }

    /**
     * 隐藏/取消隐藏歌曲：隐藏后从所有可见列表、歌单和队列里消失，
     * 只在设置的“已隐藏歌曲”里能找到。
     */
    suspend fun setHidden(trackId: String, hide: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (hide) hidden.add(trackId) else hidden.remove(trackId)
        if (hide) {
            _playlists.value = _playlists.value.map { playlist ->
                if (trackId in playlist.trackIds) playlist.copy(trackIds = playlist.trackIds - trackId) else playlist
            }
        }
        persistState()
        publishTracks()
    }

    /** 艺术家/专辑/流派/作曲家都从曲目元数据派生，本地和电脑曲目走同一条路。 */
    private fun rebuildEntityLists(tracks: List<LibraryTrack>) {
        val rows = tracks.map { track ->
            LibraryTrackRow(
                id = track.id,
                title = track.title,
                artists = track.artists,
                album = track.album,
                artworkKey = track.artworkKey,
                playCount = track.playCount,
                createdAt = track.createdAt,
                artworkPath = track.artworkPath,
                audioPath = track.audioPath,
                rawJson = track.metadataJson,
            )
        }
        val artworkPaths = _artworkPaths.value
        val genreCounts = mutableMapOf<String, Int>()
        rows.forEach { row ->
            runCatching {
                val root = LibrarySyncProtocol.json.parseToJsonElement(row.rawJson) as JsonObject
                (root["genres"] as? JsonArray).orEmpty().mapNotNullTo(mutableSetOf()) { entry ->
                    ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                }
            }.getOrNull()?.forEach { genreCounts[it] = (genreCounts[it] ?: 0) + 1 }
        }
        _artists.value = libraryArtistsFrom(rows, artworkPaths)
        _albums.value = libraryAlbumsFrom(rows, artworkPaths)
        _genres.value = libraryGenresFrom(rows).map { genre -> genre.copy(trackCount = genreCounts[genre.id] ?: 0) }
        _composers.value = libraryComposersFrom(rows, artworkPaths)
    }

    private fun favoriteJson(metadataJson: String, favorite: Boolean): String {
        val root = runCatching {
            LibrarySyncProtocol.json.parseToJsonElement(metadataJson) as? JsonObject
        }.getOrNull() ?: return metadataJson
        return JsonObject(root + ("is_favorite" to JsonPrimitive(favorite))).toString()
    }

    /** Commit a local import, replacing all tracks for the plan. */
    suspend fun commitLocalLibrary(import: LocalImport) {
        val artworkByKey = import.artworkAssets.associate { it.assetId to it.relativePath }
        if (artworkByKey.isNotEmpty()) {
            _artworkPaths.value = _artworkPaths.value + artworkByKey
            persistState()
        }
        localTracks = import.tracks.map { entity ->
            LibraryTrack(
                id = entity.trackId,
                title = entity.title,
                artists = entity.artists,
                album = entity.album,
                albumId = entity.albumId,
                artworkKey = entity.artworkKey,
                playCount = entity.playCount,
                createdAt = entity.createdAt,
                updatedAt = entity.createdAt,
                discNumber = entity.discNumber,
                trackNumber = entity.trackNumber,
                syncOrder = entity.syncOrder,
                metadataJson = entity.rawJson,
                artworkPath = artworkByKey[entity.artworkKey],
                audioPath = import.audioAssets.firstOrNull { it.assetId == "audio:${entity.trackId}" }?.relativePath,
                sortTitle = entity.title,
                sortArtists = entity.artists,
            )
        }
        publishTracks()
    }

    /** 电脑曲库同步完成后写回；本地扫描结果不受影响。 */
    suspend fun setRemoteTracks(tracks: List<LibraryTrack>) {
        remoteTracks = tracks
        publishTracks()
    }

    /** Return playlist entities for a plan. */
    fun playlistEntities(planId: String): List<SyncPlaylistEntity> = _playlists.value.map { playlist ->
        SyncPlaylistEntity(
            planId = planId,
            playlistId = playlist.id,
            name = playlist.name,
            trackIds = playlist.trackIds,
            createdAt = playlist.createdAt,
            updatedAt = playlist.updatedAt,
        )
    }

    /** Find a track by ID. */
    fun trackById(trackId: String): LibraryTrack? = tracks.value.firstOrNull { it.id == trackId }

    /** Tracks eligible for mood radio. */
    fun moodRadioTracks(): List<MoodRadioTrack> =
        tracks.value
            .filter { it.id in moodRadioEligibleTrackIds.value }
            .map { MoodRadioTrack(id = it.id, albumId = it.albumId, primaryArtistId = it.artists, energy = null, danceability = null, brightness = null, tempo = null) }

    /** 收藏落在曲目元数据里（详情页/收藏歌单都读 is_favorite），并落盘保证重启还在。 */
    suspend fun setFavorite(trackId: String, favorite: Boolean) {
        if (favorite) favorites.add(trackId) else favorites.remove(trackId)
        val patch = { list: List<LibraryTrack> ->
            list.map { track ->
                if (track.id == trackId) track.copy(metadataJson = favoriteJson(track.metadataJson, favorite)) else track
            }
        }
        _tracks.value = patch(_tracks.value)
        val online = playbackOnlyTracks.value.firstOrNull { it.id == trackId }
        if (online != null) {
            val patched = online.copy(metadataJson = favoriteJson(online.metadataJson, favorite))
            playbackOnlyTracks.value = playbackOnlyTracks.value.map { if (it.id == trackId) patched else it }
            onlineFavoriteTracks.value = if (favorite) {
                onlineFavoriteTracks.value.filterNot { it.id == trackId } + patched
            } else {
                onlineFavoriteTracks.value.filterNot { it.id == trackId }
            }
        } else if (!favorite) {
            onlineFavoriteTracks.value = onlineFavoriteTracks.value.filterNot { it.id == trackId }
        }
        persistState()
    }

    /** Save provider lyrics for a track. */
    suspend fun saveProviderLyrics(trackId: String, content: String, source: String) {
        providerLyricsStore[trackId] = content
        _providerLyrics.getOrPut(trackId) { MutableStateFlow(content) }.value = content
    }

    /** Get analysis for a track (stubbed — returns null). */
    fun analysis(trackId: String): TrackAnalysis? = analyses[trackId]

    /** 记录播放写入：按天累计每首的收听秒数/播放次数，以及播放尝试统计。 */
    suspend fun recordListening(write: Any) {
        val event = write as? ListeningWrite ?: return
        val now = System.currentTimeMillis()
        when (event) {
            is ListeningWrite.QualifiedPlay -> {
                if (event.trackId.isBlank()) return
                val date = dateOf(event.occurredAt)
                val key = date to event.trackId
                val current = trackDayStats[key]
                trackDayStats[key] = DailyTrackListeningStat(
                    sourceDeviceId = event.sourceDeviceId,
                    localDate = date,
                    trackId = event.trackId,
                    listenedSeconds = current?.listenedSeconds ?: 0,
                    playCount = (current?.playCount ?: 0) + 1,
                )
            }
            is ListeningWrite.Session -> {
                val session = event.value
                if (session.trackId.isBlank() || session.listenedSeconds <= 0) return
                val date = dateOf(session.endedAt)
                val key = date to session.trackId
                val current = trackDayStats[key]
                trackDayStats[key] = DailyTrackListeningStat(
                    sourceDeviceId = session.sourceDeviceId,
                    localDate = date,
                    trackId = session.trackId,
                    listenedSeconds = (current?.listenedSeconds ?: 0) + session.listenedSeconds,
                    playCount = current?.playCount ?: 0,
                )
            }
            is ListeningWrite.AttemptFinished -> {
                val attempt = event.value
                val date = dateOf(attempt.endedAt.takeIf { it > 0 } ?: now)
                val current = attemptDayStats[date] ?: DailyPlaybackAttemptStat(
                    sourceDeviceId = attempt.sourceDeviceId,
                    localDate = date,
                    attempts = 0,
                    completed = 0,
                    skipped = 0,
                    stopped = 0,
                    listenedSeconds = 0,
                )
                attemptDayStats[date] = current.copy(
                    attempts = current.attempts + 1,
                    completed = current.completed + if (attempt.endReason == PlaybackEndReason.COMPLETED) 1 else 0,
                    skipped = current.skipped + if (attempt.endReason == PlaybackEndReason.SKIPPED) 1 else 0,
                    stopped = current.stopped + if (attempt.endReason == PlaybackEndReason.STOPPED) 1 else 0,
                    listenedSeconds = current.listenedSeconds + attempt.listenedSeconds.coerceAtLeast(0),
                )
            }
            is ListeningWrite.AttemptStarted -> Unit
        }
        _dailyTrackListeningStats.value = trackDayStats.values.sortedBy { it.localDate }
        _dailyPlaybackAttemptStats.value = attemptDayStats.values.sortedBy { it.localDate }
        listeningDirty = true
        // 写盘节流：尝试结束立即落盘，其余最多 2 秒写一次。
        if (event is ListeningWrite.AttemptFinished || now - lastListeningPersistAt >= 2_000L) {
            persistListening(now)
        }
    }

    private fun persistListening(nowMs: Long) {
        if (!listeningDirty) return
        val payload = PersistedListening(
            tracks = trackDayStats.values.toList(),
            attempts = attemptDayStats.values.toList(),
        )
        runCatching { listeningFile.writeText(LibrarySyncProtocol.json.encodeToString(payload)) }
        listeningDirty = false
        lastListeningPersistAt = nowMs
    }

    private fun dateOf(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /** Recover open playback attempts (stubbed — no-op). */
    suspend fun recoverOpenPlaybackAttempts(nowMs: Long) {}

    /** Cleanup old listening records (stubbed — no-op). */
    suspend fun cleanupListening(cutoffMs: Long) {}

    /** Stage artist artwork: hash → filesDir 相对路径。 */
    suspend fun stageArtistArtwork(artwork: StagedArtistArtwork) {
        _artworkPaths.value = _artworkPaths.value + (artwork.sha256 to artwork.relativePath)
        persistState()
    }

    /** Queue an artist artwork mutation. */
    suspend fun queueArtistArtworkMutation(mutation: ArtistMutation) {
        when (mutation.operation) {
            ArtistArtworkOperation.SET_ARTWORK -> {
                val sha = mutation.payload?.sha256?.takeIf { it.isNotBlank() } ?: return
                _artistArtworkKeys.value = _artistArtworkKeys.value + (mutation.artistId to sha)
            }
            ArtistArtworkOperation.REMOVE_ARTWORK -> {
                _artistArtworkKeys.value = _artistArtworkKeys.value - mutation.artistId
            }
        }
        persistState()
    }

    /** Create a local playlist. */
    suspend fun createLocalPlaylist(
        mutation: PlaylistMutation,
        artwork: StagedPlaylistArtwork?,
        artworkMutationId: String?,
        initialTrackIds: List<String>,
    ) {
        val timestamp = Instant.ofEpochMilli(mutation.updatedAt).toString()
        val name = mutation.payload.name.orEmpty().trim()
        val artworkKey = artwork?.let { staged ->
            _artworkPaths.value = _artworkPaths.value + (staged.sha256 to staged.relativePath)
            staged.sha256
        }
        val playlist = LibraryPlaylist(
            id = mutation.playlistId,
            name = name,
            artworkKey = artworkKey,
            trackIds = initialTrackIds.distinct(),
            createdAt = timestamp,
            updatedAt = timestamp,
            sortTitle = name,
            metadataJson = artworkKey?.let(::playlistArtworkJson) ?: "{}",
        )
        _playlists.value = _playlists.value.filterNot { it.id == playlist.id } + playlist
        persistState()
    }

    /** Apply a playlist mutation to the in-memory list and persist it. */
    suspend fun queuePlaylistMutation(mutation: PlaylistMutation) {
        val existing = _playlists.value.firstOrNull { it.id == mutation.playlistId }
            ?: defaultFavoritesPlaylist(mutation)
            ?: return
        val payload = mutation.payload
        val timestamp = Instant.ofEpochMilli(mutation.updatedAt).toString()
        val updated: LibraryPlaylist? = when (mutation.operation) {
            PlaylistMutationOperation.CREATE ->
                existing.copy(name = payload.name?.trim().orEmpty().ifBlank { existing.name }, updatedAt = timestamp)
            PlaylistMutationOperation.ADD_TRACK ->
                payload.trackId?.takeIf { it.isNotBlank() }?.let { id ->
                    if (id in existing.trackIds) existing else existing.copy(trackIds = existing.trackIds + id, updatedAt = timestamp)
                } ?: existing
            PlaylistMutationOperation.REMOVE_TRACK ->
                payload.trackId?.let { id -> existing.copy(trackIds = existing.trackIds - id, updatedAt = timestamp) } ?: existing
            PlaylistMutationOperation.MOVE_TRACK ->
                payload.trackId?.let { id ->
                    existing.copy(
                        trackIds = movePlaylistTrack(existing.trackIds, id, payload.previousTrackId, payload.nextTrackId),
                        updatedAt = timestamp,
                    )
                } ?: existing
            PlaylistMutationOperation.UPDATE ->
                payload.name?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
                    existing.copy(name = name, sortTitle = name, updatedAt = timestamp)
                } ?: existing
            PlaylistMutationOperation.SET_ARTWORK ->
                payload.artworkSha256?.takeIf { it.isNotBlank() }?.let { sha ->
                    existing.copy(artworkKey = sha, metadataJson = playlistArtworkJson(sha), updatedAt = timestamp)
                } ?: existing
            PlaylistMutationOperation.REMOVE_ARTWORK ->
                existing.copy(artworkKey = null, metadataJson = "{}", updatedAt = timestamp)
            PlaylistMutationOperation.DELETE -> null
        }
        _playlists.value = if (updated == null) {
            _playlists.value.filterNot { it.id == mutation.playlistId }
        } else {
            _playlists.value.map { if (it.id == updated.id) updated else it }
        }
        persistState()
    }

    /** No failed-local-playlist staging exists in this build. */
    fun discardFailedLocalPlaylist(playlistId: String): Boolean = false

    /** Stage playlist artwork: hash → filesDir 相对路径。 */
    suspend fun stagePlaylistArtwork(artwork: StagedPlaylistArtwork) {
        _artworkPaths.value = _artworkPaths.value + (artwork.sha256 to artwork.relativePath)
        persistState()
    }

    /**
     * 搜索本身按当前曲库在内存里过滤（含电脑曲目），这里不再用预生成候选裁剪，
     * 否则流派等只在运行期解析的字段会漏掉。
     */
    suspend fun searchCandidates(text: String): List<LibrarySearchCandidate> = emptyList()

    /** “收藏”是派生歌单，存储里没有实体；改封面时才为它落一条实体记录。 */
    private fun defaultFavoritesPlaylist(mutation: PlaylistMutation): LibraryPlaylist? {
        if (mutation.playlistId != FavoritesPlaylistId) return null
        if (mutation.operation != PlaylistMutationOperation.SET_ARTWORK &&
            mutation.operation != PlaylistMutationOperation.REMOVE_ARTWORK
        ) {
            return null
        }
        val timestamp = Instant.ofEpochMilli(mutation.updatedAt).toString()
        return LibraryPlaylist(
            id = FavoritesPlaylistId,
            name = "",
            createdAt = timestamp,
            updatedAt = timestamp,
            metadataJson = "{}",
        )
    }

    private fun playlistArtworkJson(sha256: String): String =
        "{\"playlist\":{\"artwork_key\":\"$sha256\"}}"

    private fun movePlaylistTrack(
        trackIds: List<String>,
        trackId: String,
        previousTrackId: String?,
        nextTrackId: String?,
    ): List<String> {
        val without = trackIds.filterNot { it == trackId }
        val index = when {
            previousTrackId != null && previousTrackId in without -> without.indexOf(previousTrackId) + 1
            nextTrackId != null && nextTrackId in without -> without.indexOf(nextTrackId)
            else -> without.size
        }
        return without.toMutableList().apply { add(index.coerceIn(0, size), trackId) }
    }
}

/** ============================================================================
 *  AndroidSyncRuntime replacement — object with same API as deleted SyncRuntime.kt
 * ============================================================================ */

 object AndroidSyncRuntime {
    private var currentStore: AndroidLibrarySyncStore? = null

    fun initialize(context: Context) {
        if (currentStore == null) {
            currentStore = AndroidLibrarySyncStore(context.applicationContext)
        }
    }

    val syncStore: AndroidLibrarySyncStore
        get() = currentStore
            ?: error("AndroidLibrarySyncStore not initialized. Call AndroidSyncRuntime.initialize(context) first.")
}

/** Stub: splits listening time across local dates. */
fun splitListeningByDate(startMs: Long, endMs: Long, intervalMs: Long): Map<String, Int> {
    val zone = java.time.ZoneId.of("UTC")
    val startDate = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate().toString()
    val endDate = java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate().toString()
    val totalSeconds = ((endMs - startMs) / 1000).coerceAtLeast(0).toInt()
    val perDate = totalSeconds / 2
    return mapOf(startDate to perDate, endDate to perDate)
}

/** Stub: returns cached asset path if the file exists and path is safe. */
fun cachedAssetPath(filesDir: java.io.File, entity: SyncAssetEntity): String? {
    val file = java.io.File(filesDir, entity.relativePath)
    return if (file.exists() && !entity.relativePath.contains("..")) entity.relativePath else null
}
