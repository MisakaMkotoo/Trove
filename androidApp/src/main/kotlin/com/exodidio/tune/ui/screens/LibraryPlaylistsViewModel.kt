package com.exodidio.tune.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.LibraryPlaylist
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.PlaylistMutation
import com.exodidio.tune.sync.PlaylistMutationOperation
import com.exodidio.tune.sync.PlaylistMutationPayload
import com.exodidio.tune.library.SmartPlaylistEngine
import com.exodidio.tune.library.SmartPlaylistSpec
import com.exodidio.tune.library.stagePlaylistArtwork
import com.exodidio.tune.netease.NeteaseRepository
import java.util.UUID

internal data class PlaylistListItem(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val artworkPaths: List<String> = emptyList(),
    val customArtworkPath: String? = null,
    val syncFailed: Boolean = false,
    val isSmart: Boolean = false,
    val smartSpec: SmartPlaylistSpec? = null,
) { val isFavorite: Boolean get() = id == FavoritesPlaylistId }

internal data class NeteasePlaylistSummary(
    val id: String,
    val name: String,
    val count: Int,
    val coverPath: String? = null,
    val coverUrl: String? = null,
)

internal data class LibraryPlaylistsUiState(
    val playlists: List<PlaylistListItem> = emptyList(),
    val neteasePlaylists: List<NeteasePlaylistSummary> = emptyList(),
    val isLoaded: Boolean = true,
)

internal class LibraryPlaylistsViewModel(private val context: Context, syncStore: AndroidLibrarySyncStore) : ViewModel() {
    class Factory(private val context: Context, private val syncStore: AndroidLibrarySyncStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryPlaylistsViewModel(context, syncStore) as T
    }

    private val syncStore = syncStore
    private val _createdPlaylistIds = MutableSharedFlow<String>()
    val createdPlaylistIds: SharedFlow<String> = _createdPlaylistIds.asSharedFlow()

    val uiState: StateFlow<LibraryPlaylistsUiState> = combine(
        listOf(
            syncStore.playlists,
            syncStore.tracks,
            syncStore.onlineFavorites,
            syncStore.playbackOnlyTracksFlow,
            syncStore.artworkPaths,
            NeteaseRepository.playlists,
            syncStore.dailyTrackListeningStats,
        ),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val playlists = values[0] as List<LibraryPlaylist>
        @Suppress("UNCHECKED_CAST")
        val tracks = values[1] as List<LibraryTrack>
        @Suppress("UNCHECKED_CAST")
        val onlineFavorites = values[2] as List<LibraryTrack>
        @Suppress("UNCHECKED_CAST")
        val playbackOnlyTracks = values[3] as List<LibraryTrack>
        @Suppress("UNCHECKED_CAST")
        val artworkPaths = values[4] as Map<String, String>
        @Suppress("UNCHECKED_CAST")
        val netease = values[5] as List<com.exodidio.tune.netease.NeteasePlaylistRecord>
        @Suppress("UNCHECKED_CAST")
        val listeningStats = values[6] as List<com.exodidio.tune.player.DailyTrackListeningStat>
        val playCounts = SmartPlaylistEngine.aggregatePlayCounts(listeningStats)
        val trackIds = tracks.mapTo(HashSet(tracks.size)) { it.id }
        val onlineFavIds = onlineFavorites.mapTo(HashSet(onlineFavorites.size)) { it.id }
        val allTracks = tracks +
            onlineFavorites.filterNot { it.id in trackIds } +
            playbackOnlyTracks.filterNot { it.id in trackIds || it.id in onlineFavIds }
        val availableTrackIds = allTracks.mapTo(mutableSetOf(), LibraryTrack::id)
        LibraryPlaylistsUiState(
            isLoaded = true,
            playlists = playlistsWithFavorites(playlists, allTracks).map { playlist ->
                val smartSpec = SmartPlaylistEngine.parseSpec(playlist.metadataJson)
                val resolvedPlaylist = if (smartSpec != null) {
                    val smartTrackIds = SmartPlaylistEngine.evaluate(smartSpec, allTracks, playCounts).map { it.id }
                    playlist.copy(trackIds = smartTrackIds)
                } else {
                    playlist
                }
                PlaylistListItem(
                    id = resolvedPlaylist.id,
                    name = resolvedPlaylist.name,
                    trackIds = resolvedPlaylist.trackIds.filter { it in availableTrackIds },
                    artworkPaths = playlistArtworkPaths(resolvedPlaylist, allTracks, artworkPaths),
                    customArtworkPath = playlistManualArtworkPath(resolvedPlaylist, artworkPaths),
                    syncFailed = false,
                    isSmart = smartSpec != null,
                    smartSpec = smartSpec,
                )
            },
            neteasePlaylists = netease.filter { it.addedToPlaylists }.map { playlist ->
                NeteasePlaylistSummary(
                    id = playlist.id,
                    name = playlist.name,
                    count = playlist.entries.size,
                    coverPath = playlist.coverPath,
                    coverUrl = playlist.coverUrl,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryPlaylistsUiState(isLoaded = false))

    fun createSmartPlaylist(rawName: String, spec: SmartPlaylistSpec) {
        val name = rawName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlistId = UUID.randomUUID().toString()
            val metadata = SmartPlaylistEngine.encodeMetadataJson(spec)
            syncStore.createSmartPlaylist(playlistId, name, metadata)
            _createdPlaylistIds.emit(playlistId)
        }
    }

    fun updateSmartPlaylist(playlistId: String, rawName: String, spec: SmartPlaylistSpec) {
        val name = rawName.trim()
        if (name.isBlank() || playlistId == FavoritesPlaylistId) return
        viewModelScope.launch {
            val existingMetadata = syncStore.playlists.value.firstOrNull { it.id == playlistId }?.metadataJson ?: "{}"
            val metadata = SmartPlaylistEngine.encodeMetadataJson(spec, existingMetadata)
            syncStore.updateSmartPlaylist(playlistId, name, metadata)
        }
    }

    fun createPlaylist(rawName: String, artworkUri: Uri? = null, initialTrackIds: List<String> = emptyList()) {
        val name = rawName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlistId = UUID.randomUUID().toString()
            val stagedArtwork = artworkUri?.let { uri ->
                try {
                    withContext(Dispatchers.IO) { stagePlaylistArtwork(context.contentResolver, context.filesDir, uri) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w("LibraryPlaylists", "Ignoring unreadable playlist artwork", error)
                    null
                }
            }
            syncStore.createLocalPlaylist(
                PlaylistMutation(
                    mutationId = UUID.randomUUID().toString(),
                    playlistId = playlistId,
                    operation = PlaylistMutationOperation.CREATE,
                    updatedAt = System.currentTimeMillis(),
                    payload = PlaylistMutationPayload(name = name),
                ),
                artwork = stagedArtwork,
                artworkMutationId = stagedArtwork?.let { UUID.randomUUID().toString() },
                initialTrackIds = initialTrackIds,
            )
            _createdPlaylistIds.emit(playlistId)
        }
    }

    fun changePlaylistMembership(playlistId: String, trackIds: List<String>, add: Boolean) {
        if (playlistId == FavoritesPlaylistId) return
        val operation = if (add) PlaylistMutationOperation.ADD_TRACK else PlaylistMutationOperation.REMOVE_TRACK
        viewModelScope.launch {
            trackIds.distinct().filter(String::isNotBlank).forEach { trackId ->
                syncStore.queuePlaylistMutation(
                    PlaylistMutation(
                        mutationId = UUID.randomUUID().toString(),
                        playlistId = playlistId,
                        operation = operation,
                        updatedAt = System.currentTimeMillis(),
                        payload = PlaylistMutationPayload(trackId = trackId),
                    ),
                )
            }
        }
    }

    fun updatePlaylist(playlistId: String, rawName: String, artworkUri: Uri? = null, clearArtwork: Boolean = false) {
        val name = rawName.trim()
        val canRename = playlistId != FavoritesPlaylistId
        if (canRename && name.isBlank()) return
        viewModelScope.launch {
            val updatedAt = System.currentTimeMillis()
            if (canRename) {
                syncStore.queuePlaylistMutation(
                    PlaylistMutation(UUID.randomUUID().toString(), playlistId, PlaylistMutationOperation.UPDATE, updatedAt, PlaylistMutationPayload(name = name)),
                )
            }
            artworkUri?.let { uri ->
                val staged = try {
                    withContext(Dispatchers.IO) { stagePlaylistArtwork(context.contentResolver, context.filesDir, uri) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w("LibraryPlaylists", "Ignoring unreadable playlist artwork", error)
                    null
                }
                staged?.let {
                    syncStore.stagePlaylistArtwork(it)
                    syncStore.queuePlaylistMutation(
                        PlaylistMutation(UUID.randomUUID().toString(), playlistId, PlaylistMutationOperation.SET_ARTWORK, updatedAt + 1, PlaylistMutationPayload(artworkSha256 = it.sha256)),
                    )
                }
            }
            if (clearArtwork) {
                syncStore.queuePlaylistMutation(
                    PlaylistMutation(UUID.randomUUID().toString(), playlistId, PlaylistMutationOperation.REMOVE_ARTWORK, updatedAt + 1),
                )
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        if (playlistId == FavoritesPlaylistId) return
        viewModelScope.launch {
            if (syncStore.discardFailedLocalPlaylist(playlistId)) return@launch
            syncStore.queuePlaylistMutation(
                PlaylistMutation(UUID.randomUUID().toString(), playlistId, PlaylistMutationOperation.DELETE, System.currentTimeMillis()),
            )
        }
    }

    fun deduplicatePlaylist(playlistId: String): Int {
        if (playlistId == FavoritesPlaylistId || playlistId.isBlank()) return 0
        val playlist = syncStore.playlists.value.firstOrNull { it.id == playlistId } ?: return 0
        if (SmartPlaylistEngine.parseSpec(playlist.metadataJson) != null) return 0
        val tracksById = buildMap {
            syncStore.tracks.value.forEach { put(it.id, it) }
            syncStore.onlineFavorites.value.forEach { putIfAbsent(it.id, it) }
            playlist.trackIds.forEach { id ->
                if (!containsKey(id)) {
                    syncStore.trackForPlayback(id)?.let { put(id, it) }
                }
            }
        }
        val deduped = deduplicatePlaylistTrackIds(playlist.trackIds, tracksById)
        val removed = playlist.trackIds.size - deduped.size
        if (removed > 0) {
            viewModelScope.launch {
                syncStore.replacePlaylistTracks(playlistId, deduped)
            }
        }
        return removed
    }
}

internal const val FavoritesPlaylistId = "favorites"

internal fun playlistsWithFavorites(
    playlists: List<LibraryPlaylist>,
    tracks: List<LibraryTrack> = emptyList(),
): List<LibraryPlaylist> {
    val favorites = playlists.firstOrNull { it.id == FavoritesPlaylistId }
        ?: LibraryPlaylist(id = FavoritesPlaylistId, name = "", metadataJson = "{}")
    val derivedFavorites = favorites.copy(trackIds = tracks.filter(LibraryTrack::isFavorite).map(LibraryTrack::id))
    return listOf(derivedFavorites) + playlists.filterNot { it.id == FavoritesPlaylistId }
}

internal fun playlistArtworkPaths(
    playlist: LibraryPlaylist,
    tracks: List<LibraryTrack>,
    artworkPaths: Map<String, String>,
): List<String> {
    playlistArtworkKey(playlist.metadataJson)?.let { key ->
        artworkPaths[key]?.let { return listOf(it) }
    }
    val tracksById = tracks.associateBy { it.id }
    return playlist.trackIds.asSequence()
        .mapNotNull { tracksById[it] }
        .mapNotNull { track -> track.artworkKey?.let { key -> key to track.artworkPath } }
        .filter { (_, path) -> !path.isNullOrBlank() }
        .distinctBy { (key, _) -> key }
        .take(4)
        .map { (_, path) -> path!! }
        .toList()
}

internal fun playlistManualArtworkPath(
    playlist: LibraryPlaylist,
    artworkPaths: Map<String, String>,
): String? = playlistArtworkKey(playlist.metadataJson)?.let(artworkPaths::get)

private fun playlistArtworkKey(metadataJson: String): String? = runCatching {
    val root = LibrarySyncProtocol.json.parseToJsonElement(metadataJson) as? JsonObject
    val playlist = root?.get("playlist") as? JsonObject ?: root
    (playlist?.get("artwork_key") as? JsonPrimitive)?.contentOrNull
}.getOrNull()?.takeIf { it.isNotBlank() }