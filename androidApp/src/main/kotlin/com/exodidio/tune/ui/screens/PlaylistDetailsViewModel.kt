package com.exodidio.tune.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import com.exodidio.tune.library.SmartPlaylistEngine
import com.exodidio.tune.library.SmartPlaylistSpec
import com.exodidio.tune.player.DailyTrackListeningStat
import com.exodidio.tune.player.PlaybackController
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.LibraryPlaylist
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.sync.PlaylistMutation
import com.exodidio.tune.sync.PlaylistMutationOperation
import com.exodidio.tune.sync.PlaylistMutationPayload
import java.util.UUID
import kotlinx.coroutines.launch

internal data class PlaylistDetailsUiState(
    val playlist: LibraryPlaylist? = null,
    val tracks: List<LibraryTrack> = emptyList(),
    val artworkPaths: List<String> = emptyList(),
    val customArtworkPath: String? = null,
    val smartSpec: SmartPlaylistSpec? = null,
    internal val playlists: List<LibraryPlaylist> = emptyList(),
    internal val allTracks: List<LibraryTrack> = emptyList(),
    internal val artworkPathByKey: Map<String, String> = emptyMap(),
    internal val playCountsByTrackId: Map<String, Int> = emptyMap(),
)

internal class PlaylistDetailsViewModel(
    private val syncStore: AndroidLibrarySyncStore,
    private val playbackController: PlaybackController,
) : ViewModel() {
    class Factory(
        private val store: AndroidLibrarySyncStore,
        private val playback: PlaybackController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistDetailsViewModel(store, playback) as T
    }

    val uiState: StateFlow<PlaylistDetailsUiState> = combine(
        listOf(
            syncStore.playlists,
            syncStore.tracks,
            syncStore.onlineFavorites,
            syncStore.playbackOnlyTracksFlow,
            syncStore.artworkPaths,
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
        val listeningStats = values[5] as List<DailyTrackListeningStat>
        val trackIds = tracks.mapTo(HashSet(tracks.size)) { it.id }
        val onlineFavIds = onlineFavorites.mapTo(HashSet(onlineFavorites.size)) { it.id }
        PlaylistDetailsUiState(
            playlists = playlists,
            // 收藏歌单与本地歌单均可引用在线曲目。
            allTracks = tracks +
                onlineFavorites.filterNot { it.id in trackIds } +
                playbackOnlyTracks.filterNot { it.id in trackIds || it.id in onlineFavIds },
            artworkPathByKey = artworkPaths,
            playCountsByTrackId = SmartPlaylistEngine.aggregatePlayCounts(listeningStats),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistDetailsUiState())

    fun play(playlistId: String, shuffle: Boolean) {
        val tracks = playlistDetailsUiStateFor(uiState.value, playlistId).tracks
        if (tracks.isNotEmpty()) {
            val request = PlaybackRequest(tracks.map { it.id }, 0)
            if (shuffle) playbackController.shuffle(request) else playbackController.play(request)
        }
    }

    fun playTrack(playlistId: String, trackId: String) {
        val tracks = playlistDetailsUiStateFor(uiState.value, playlistId).tracks
        albumPlaybackRequestFor(tracks, trackId)?.let(playbackController::play)
    }

    fun removeTrack(playlistId: String, trackId: String) {
        if (playlistId == FavoritesPlaylistId || trackId.isBlank()) return
        viewModelScope.launch {
            syncStore.queuePlaylistMutation(
                PlaylistMutation(
                    mutationId = UUID.randomUUID().toString(),
                    playlistId = playlistId,
                    operation = PlaylistMutationOperation.REMOVE_TRACK,
                    updatedAt = System.currentTimeMillis(),
                    payload = PlaylistMutationPayload(trackId = trackId),
                ),
            )
        }
    }

    fun moveTrack(playlistId: String, trackId: String, previousTrackId: String?, nextTrackId: String?) {
        if (playlistId == FavoritesPlaylistId || trackId.isBlank()) return
        viewModelScope.launch {
            syncStore.queuePlaylistMutation(
                PlaylistMutation(
                    mutationId = UUID.randomUUID().toString(),
                    playlistId = playlistId,
                    operation = PlaylistMutationOperation.MOVE_TRACK,
                    updatedAt = System.currentTimeMillis(),
                    payload = PlaylistMutationPayload(trackId = trackId, previousTrackId = previousTrackId, nextTrackId = nextTrackId),
                ),
            )
        }
    }

    fun deduplicatePlaylist(playlistId: String): Int {
        if (playlistId == FavoritesPlaylistId || playlistId.isBlank()) return 0
        val current = uiState.value
        val playlist = current.playlists.firstOrNull { it.id == playlistId } ?: return 0
        if (SmartPlaylistEngine.parseSpec(playlist.metadataJson) != null) return 0
        val tracksById = current.allTracks.associateBy { it.id }
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

internal fun normalizedTrackDedupKey(track: LibraryTrack): String {
    val title = track.title.trim().lowercase()
    val artists = track.artists.trim().lowercase()
    val album = track.album.trim().lowercase()
    return if (title.isEmpty() && artists.isEmpty() && album.isEmpty()) "" else "$title|$artists|$album"
}

internal fun deduplicatePlaylistTrackIds(
    trackIds: List<String>,
    tracksById: Map<String, LibraryTrack>,
): List<String> {
    if (trackIds.size <= 1) return trackIds
    val seenTrackIds = HashSet<String>(trackIds.size)
    val seenSignatures = HashSet<String>(trackIds.size)
    val result = ArrayList<String>(trackIds.size)
    for (id in trackIds) {
        if (id.isBlank() || !seenTrackIds.add(id)) continue
        val track = tracksById[id]
        if (track != null) {
            val signature = normalizedTrackDedupKey(track)
            if (signature.isNotEmpty() && !seenSignatures.add(signature)) continue
        }
        result.add(id)
    }
    return result
}

internal fun playlistMoveAnchors(trackIds: List<String>, trackId: String): Pair<String?, String?> {
    val index = trackIds.indexOf(trackId)
    return trackIds.getOrNull(index - 1) to trackIds.getOrNull(index + 1)
}

internal fun playlistDetailsUiStateFor(
    state: PlaylistDetailsUiState,
    playlistId: String,
): PlaylistDetailsUiState {
    val basePlaylist = playlistsWithFavorites(state.playlists).firstOrNull { it.id == playlistId }
        ?: return PlaylistDetailsUiState()
    val smartSpec = SmartPlaylistEngine.parseSpec(basePlaylist.metadataJson)
    val tracksById = state.allTracks.associateBy { it.id }
    val tracks: List<LibraryTrack>
    val playlist: LibraryPlaylist
    when {
        smartSpec != null -> {
            tracks = SmartPlaylistEngine.evaluate(smartSpec, state.allTracks, state.playCountsByTrackId)
            playlist = basePlaylist.copy(trackIds = tracks.map { it.id })
        }
        basePlaylist.id == FavoritesPlaylistId -> {
            playlist = basePlaylist.copy(trackIds = state.allTracks.filter(LibraryTrack::isFavorite).map(LibraryTrack::id))
            tracks = playlist.trackIds.mapNotNull(tracksById::get)
        }
        else -> {
            playlist = basePlaylist
            tracks = playlist.trackIds.mapNotNull(tracksById::get)
        }
    }
    return PlaylistDetailsUiState(
        playlist = playlist,
        tracks = tracks,
        artworkPaths = playlistArtworkPaths(playlist, state.allTracks, state.artworkPathByKey),
        customArtworkPath = playlistManualArtworkPath(playlist, state.artworkPathByKey),
        smartSpec = smartSpec,
    )
}

// Pure helpers take an already-parsed object so composables parse once.
internal fun isFavoriteOf(metadata: kotlinx.serialization.json.JsonObject?): Boolean =
    (metadata?.get("is_favorite") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true

internal fun durationSecondsOf(metadata: kotlinx.serialization.json.JsonObject?): Long? =
    (metadata?.get("duration") as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull?.coerceAtLeast(0L)

internal fun LibraryTrack.isFavorite(): Boolean = isFavoriteOf(metadataObject())

internal fun playlistTotalDurationSeconds(tracks: List<LibraryTrack>): Long = tracks.sumOf { track ->
    durationSecondsOf(track.metadataObject()) ?: 0L
}

internal fun formatPlaylistTotalDuration(
    totalSeconds: Long,
    day: (Long) -> String,
    hour: (Long) -> String,
    minute: (Long) -> String,
    second: (Long) -> String,
): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val days = seconds / 86_400L
    val hours = seconds % 86_400L / 3_600L
    val minutes = seconds % 3_600L / 60L
    val remainder = seconds % 60L
    return when {
        days > 0 -> "${day(days)} ${hour(hours)}"
        hours > 0 -> "${hour(hours)} ${minute(minutes)}"
        minutes > 0 -> minute(minutes)
        else -> second(remainder)
    }
}
