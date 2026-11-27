package com.exodidio.tune.sync

import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** ============================================================================
 *  Library & Sync domain models, JSON protocol, mappers, and mutation types
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

object LibrarySyncProtocol {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

fun LibraryTrack.metadataObject(): JsonObject? =
    try {
        LibrarySyncProtocol.json.parseToJsonElement(metadataJson) as? JsonObject
    } catch (_: Exception) {
        null
    }

fun trackDisplayTitle(track: LibraryTrack): String =
    track.title.ifBlank { track.id.ifBlank { "Unknown song" } }

fun trackDisplayArtists(track: LibraryTrack): String =
    track.artists.ifBlank { "Unknown artist" }

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
        val artistsArr = root["artists"] as? JsonArray ?: continue
        for (entry in artistsArr) {
            val obj = entry as? JsonObject ?: continue
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            if (id.isBlank()) continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (name.isBlank()) continue
            val explicitSortName = obj["sort_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["normalization_key"]?.jsonPrimitive?.contentOrNull
            val explicitCreatedAt = obj["created_at"]?.jsonPrimitive?.contentOrNull
            val artworkKey = obj["artwork_key"]?.jsonPrimitive?.contentOrNull
                ?: obj["artwork_key_local"]?.jsonPrimitive?.contentOrNull
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
        val albumObj = root["album"] as? JsonObject ?: continue
        val albumId = albumObj["id"]?.jsonPrimitive?.contentOrNull ?: continue
        if (albumId.isBlank()) continue
        val title = albumObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
        if (title.isBlank()) continue
        val sortTitle = albumObj["sort_title"]?.jsonPrimitive?.contentOrNull ?: title
        val year = albumObj["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val copyright = albumObj["copyright"]?.jsonPrimitive?.contentOrNull ?: ""
        val createdAt = albumObj["created_at"]?.jsonPrimitive?.contentOrNull ?: track.createdAt
        val artworkKey = albumObj["artwork_key"]?.jsonPrimitive?.contentOrNull
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
        var artist = ""
        var sortArtist = ""
        var explicitSortArtist = false
        val albumArtists = root["album_artists"] as? JsonArray
        if (albumArtists != null && albumArtists.isNotEmpty()) {
            val first = albumArtists.firstOrNull { it is JsonObject } as? JsonObject
            artist = first?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val explicitSortName = first?.get("sort_name")?.jsonPrimitive?.contentOrNull
            sortArtist = explicitSortName ?: artist
            explicitSortArtist = explicitSortName != null
        }
        if (artist.isBlank()) {
            val trackArtists = root["artists"] as? JsonArray
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
        val composersArr = root["composers"] as? JsonArray ?: continue
        for (entry in composersArr) {
            val obj = entry as? JsonObject ?: continue
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            if (id.isBlank()) continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (name.isBlank()) continue
            val explicitSortName = obj["sort_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["normalization_key"]?.jsonPrimitive?.contentOrNull
            val explicitCreatedAt = obj["created_at"]?.jsonPrimitive?.contentOrNull
            val artworkKey = obj["artwork_key"]?.jsonPrimitive?.contentOrNull
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
        val genresArr = root["genres"] as? JsonArray ?: continue
        for (entry in genresArr) {
            val obj = entry as? JsonObject ?: continue
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            if (id.isBlank()) continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (name.isBlank()) continue
            val explicitSortName = obj["sort_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["normalization_key"]?.jsonPrimitive?.contentOrNull
            val explicitCreatedAt = obj["created_at"]?.jsonPrimitive?.contentOrNull
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

fun searchFtsMatch(query: String): String? {
    val normalized = Normalizer.normalize(query, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    val tokens = normalized
        .lowercase()
        .split(Regex("[\\s/]+"))
        .filter { it.isNotBlank() }
        .map { it.replace(Regex("[^a-z0-9]"), "") }
        .filter { it.isNotBlank() }
    return if (tokens.isEmpty()) null else tokens.joinToString(" AND ") { "$it*" }
}

fun searchDocumentsFor(
    planId: String,
    entities: List<SyncTrackEntity>,
    playlists: List<SyncPlaylistEntity>,
): List<LibrarySearchDocumentEntity> {
    val docs = mutableListOf<LibrarySearchDocumentEntity>()
    for (entity in entities) {
        val root = runCatching { LibrarySyncProtocol.json.parseToJsonElement(entity.rawJson) as? JsonObject }.getOrNull()
            ?: continue
        val title = root["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val artists = root["artists"] as? JsonArray
        val artistNames = artists?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.orEmpty()
        val subtitle = artistNames.joinToString(", ")
        val keywords = mutableListOf(title)
        keywords.addAll(artistNames)
        val albumObj = root["album"] as? JsonObject
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
        val composersArr = root["composers"] as? JsonArray
        if (composersArr != null) {
            for (entry in composersArr) {
                val obj = entry as? JsonObject ?: continue
                val composerId = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                if (composerId.isBlank()) continue
                val composerName = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
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
            operation == ArtistArtworkOperation.SET_ARTWORK && payload?.sha256.isNullOrBlank() ->
                "SET_ARTWORK requires a valid sha256"
            else -> null
        }
}

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

fun applyPendingArtistArtworkMutations(
    existing: Map<String, String>,
    pending: List<ArtistMutation>,
): Map<String, String> {
    val result = existing.toMutableMap()
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

fun stableId(prefix: String, name: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(name.lowercase().toByteArray())
    return prefix + digest.joinToString("") { "%02x".format(it) }.take(12)
}

/** UI 层的收藏歌单使用同一个固定 id。 */
internal const val FavoritesPlaylistId = "favorites"

fun artistIdFor(name: String): String = stableId("local-artist-", name)
fun albumIdFor(name: String): String = stableId("local-album-", name)
fun composerIdFor(name: String): String = stableId("local-composer-", name)
fun genreIdFor(name: String): String = stableId("local-genre-", name)

/** Splits listening duration across local calendar dates. */
fun splitListeningByDate(startMs: Long, endMs: Long, intervalMs: Long): Map<String, Int> {
    if (endMs <= startMs) return emptyMap()
    val zone = ZoneId.systemDefault()
    val result = linkedMapOf<String, Int>()
    var cursor = startMs
    while (cursor < endMs) {
        val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val nextMidnightMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sliceEnd = minOf(endMs, nextMidnightMs)
        val seconds = ((sliceEnd - cursor) / 1000).coerceAtLeast(0).toInt()
        if (seconds > 0) {
            val key = date.toString()
            result[key] = (result[key] ?: 0) + seconds
        }
        cursor = sliceEnd
    }
    return result
}

/** Stub: returns cached asset path if the file exists and path is safe. */
fun cachedAssetPath(filesDir: File, entity: SyncAssetEntity): String? {
    val file = File(filesDir, entity.relativePath)
    return if (file.exists() && !entity.relativePath.contains("..")) entity.relativePath else null
}
