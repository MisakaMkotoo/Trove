package com.exodidio.tune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.LibraryPlaylist
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.ui.screens.NeteasePlaylistEditorBottomSheet
import com.exodidio.tune.ui.screens.isFavoriteOf
import com.exodidio.tune.ui.theme.LocalTuneColors

/** Per-host visibility policy for the reusable track overflow sheet. */
data class TrackContextMenuActions(
    val removeFromQueue: Boolean = false,
    val playNext: Boolean = true,
    val addToQueue: Boolean = true,
    val trackInfo: Boolean = true,
    val findLyrics: Boolean = false,
    val lyricsControl: Boolean = false,
    val comments: Boolean = true,
    val favorite: Boolean = true,
    val neteaseLike: Boolean = false,
    val allowNeteasePlaylists: Boolean = false,
    val addToPlaylist: Boolean = true,
    val goToAlbum: Boolean = true,
    val goToArtists: Boolean = true,
    val removeFromPlaylist: Boolean = false,
    val moodRadio: Boolean = false,
    val similarSongs: Boolean = true,
    val hide: Boolean = true,
)

data class SimilarSongsMenuActions(
    val available: Boolean = false,
    val isAvailable: (String) -> Boolean = { false },
    val onOpen: (String) -> Unit = {},
)

val LocalSimilarSongsMenuActions = staticCompositionLocalOf { SimilarSongsMenuActions() }

/** 在线实体跳转：曲目带网易云 ID 时，菜单里的“前往专辑/歌手”走在线页面。 */
data class NeteaseEntityMenuActions(
    val onOpenArtist: (artistId: Long, name: String) -> Unit = { _, _ -> },
    val onOpenAlbum: (albumId: Long, name: String, coverUrl: String?) -> Unit = { _, _, _ -> },
)

val LocalNeteaseEntityMenuActions = staticCompositionLocalOf { NeteaseEntityMenuActions() }

data class MoodRadioMenuActions(
    val eligibleTrackIds: Set<String> = emptySet(),
    val onStart: (String) -> Unit = {},
)

val LocalMoodRadioMenuActions = staticCompositionLocalOf { MoodRadioMenuActions() }

data class TrackContextArtist(val id: String, val name: String)

/** Queue-derived availability mirrors the desktop track context menu. */
internal data class TrackContextQueueAvailability(
    val showPlayNext: Boolean,
    val showAddToQueue: Boolean,
    val addToQueueEnabled: Boolean,
)

internal fun trackContextQueueAvailability(
    trackId: String,
    queue: PlaybackQueueSnapshot,
): TrackContextQueueAvailability = TrackContextQueueAvailability(
    showPlayNext = trackId != queue.currentTrackId,
    showAddToQueue = trackId != queue.currentTrackId,
    addToQueueEnabled = trackId != queue.currentTrackId && trackId !in queue.activeTrackIds,
)

internal fun trackContextArtists(track: LibraryTrack): List<TrackContextArtist> {
    val root = track.metadataObject() ?: return emptyList()
    return (root["artists"] as? JsonArray).orEmpty().mapNotNull { value ->
        val artist = value as? JsonObject ?: return@mapNotNull null
        val id = (artist["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val name = (artist["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        TrackContextArtist(id, name).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
    }.distinctBy(TrackContextArtist::id)
}

sealed interface TrackContextBottomSheetRequest {
    data class Info(val track: LibraryTrack) : TrackContextBottomSheetRequest
    data class FindLyrics(val track: LibraryTrack) : TrackContextBottomSheetRequest
    data class LyricsControl(val track: LibraryTrack) : TrackContextBottomSheetRequest
    data class Comments(val track: LibraryTrack) : TrackContextBottomSheetRequest
    data class Playlist(
        val trackIds: List<String>,
        val addOnly: Boolean = false,
        val allowNeteasePlaylists: Boolean = false,
    ) : TrackContextBottomSheetRequest
    data class Artists(val artists: List<TrackContextArtist>) : TrackContextBottomSheetRequest
}

private sealed interface TrackContextSheet {
    data class Root(
        val request: TrackContextBottomSheetRequest,
        val lyricsState: FindLyricsSearchState? = (request as? TrackContextBottomSheetRequest.FindLyrics)?.let { FindLyricsSearchState(it.track) },
    ) : TrackContextSheet

    data class LyricsPreview(
        val track: LibraryTrack,
        val lyric: com.exodidio.tune.lyrics.LyricsSearchResult,
    ) : TrackContextSheet
}

@Composable
fun TrackContextMenu(
    track: LibraryTrack,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: TrackContextMenuActions = TrackContextMenuActions(),
    hazeState: HazeState? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onRemoveFromQueue: (LibraryTrack) -> Unit = {},
    onPlayNext: (LibraryTrack) -> Unit = {},
    onAddToQueue: (LibraryTrack) -> Unit = {},
    onStartMoodRadio: (LibraryTrack) -> Unit = {},
    onFavoriteChange: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onRemoveFromPlaylist: (LibraryTrack) -> Unit = {},
    onGoToAlbum: (LibraryTrack) -> Unit = {},
    onGoToArtist: (TrackContextArtist) -> Unit = {},
    onBottomSheetRequested: ((TrackContextBottomSheetRequest) -> Unit)? = null,
    onDeleteFromDevice: ((LibraryTrack) -> Unit)? = null,
    onHide: ((LibraryTrack) -> Unit)? = null,
    onDownload: ((LibraryTrack) -> Unit)? = null,
    onMatchSong: ((LibraryTrack) -> Unit)? = null,
    onCloseFullscreenThen: ((() -> Unit) -> Unit) = { action -> action() },
    playlists: List<LibraryPlaylist> = emptyList(),
    onPlaylistMembershipChange: (playlistId: String, trackIds: List<String>, add: Boolean) -> Unit = { _, _, _ -> },
    onCreatePlaylistRequested: (trackIds: List<String>) -> Unit = {},
    anchor: @Composable () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val defaultHide: (LibraryTrack) -> Unit = { hiddenTrack ->
        scope.launch { AndroidSyncRuntime.syncStore.setHidden(hiddenTrack.id, true) }
        AndroidPlaybackRuntime.controller().removeFromQueue(hiddenTrack.id)
    }
    var detailSheet by remember(track.id) { mutableStateOf<TrackContextBottomSheetRequest?>(null) }
    val artists = remember(track.metadataJson) { trackContextArtists(track) }
    val hasAlbum = track.albumId.isNotBlank() && track.album.isNotBlank()
    val hasArtists = track.artists.isNotBlank() && artists.isNotEmpty()
    val hasNavigationActions = actions.goToAlbum && hasAlbum || actions.goToArtists && hasArtists
    val queueAvailability = remember(track.id, playbackQueue) {
        trackContextQueueAvailability(track.id, playbackQueue)
    }
    val metadata = remember(track.metadataJson) { track.metadataObject() }
    val storeFavoriteIds by AndroidSyncRuntime.syncStore.favoriteIds.collectAsStateWithLifecycle()
    val storeFavoriteOverrides by AndroidSyncRuntime.syncStore.favoriteOverrides.collectAsStateWithLifecycle()
    val favorite = remember(metadata, storeFavoriteIds, storeFavoriteOverrides, track.id) {
        storeFavoriteOverrides[track.id] ?: (track.id in storeFavoriteIds || isFavoriteOf(metadata))
    }
    val neteaseSongId = remember(track.id) {
        if (actions.neteaseLike) NeteaseRepository.songIdForTrack(track.id) else null
    }
    val neteaseLikedIds by NeteaseRepository.likedSongIds.collectAsStateWithLifecycle()
    val neteaseLiked = neteaseSongId != null && neteaseSongId in neteaseLikedIds
    val moodRadio = LocalMoodRadioMenuActions.current
    val showMoodRadio = actions.moodRadio || track.id in moodRadio.eligibleTrackIds
    val similarMenu = LocalSimilarSongsMenuActions.current
    val showSimilarSongs = actions.similarSongs && similarMenu.available && similarMenu.isAvailable(track.id)
    val neteaseEntityActions = LocalNeteaseEntityMenuActions.current
    val neteaseArtistId = remember(track.metadataJson) {
        NeteaseRepository.neteaseArtistIdForTrack(track)
    }
    val neteaseAlbumId = remember(track.metadataJson) {
        NeteaseRepository.neteaseAlbumIdForTrack(track)
    }
    val dismissAll = {
        detailSheet = null
        onDismiss()
    }
    val closeAfter: ((() -> Unit) -> Unit) = { action -> action(); dismissAll() }
    val presentAfterFullscreenCloses: ((() -> Unit) -> Unit) = { action ->
        dismissAll()
        onCloseFullscreenThen(action)
    }
    val requestBottomSheet: (TrackContextBottomSheetRequest) -> Unit = { request ->
        if (onBottomSheetRequested == null) {
            presentAfterFullscreenCloses { detailSheet = request }
        } else {
            dismissAll()
            onBottomSheetRequested(request)
        }
    }

    // The anchor must remain in composition while a sheet is open. In a LazyColumn,
    // replacing it with the Dialog would make the row measure to zero and disappear.
    AnchoredPopupMenu(
        expanded = expanded && detailSheet == null,
        onDismissRequest = dismissAll,
        modifier = modifier,
        width = 272.dp,
        offset = DpOffset(x = (-8).dp, y = 8.dp),
        hazeState = hazeState,
        anchor = anchor,
    ) {
        val entries = buildList {
            if (actions.removeFromQueue) {
                add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_remove_from_queue), MaterialSymbols.RemoveFromQueue, destructive = true) { closeAfter { onRemoveFromQueue(track) } })
                add(ContextActionMenuEntry.Divider)
            }
            val showPlayNext = actions.playNext && queueAvailability.showPlayNext
            val showAddToQueue = actions.addToQueue && queueAvailability.showAddToQueue && queueAvailability.addToQueueEnabled
            if (showPlayNext) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_play_next), MaterialSymbols.QueuePlayNext) { closeAfter { onPlayNext(track) } })
            if (showAddToQueue) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_add_to_queue), MaterialSymbols.AddToQueue) { closeAfter { onAddToQueue(track) } })
            if (actions.trackInfo) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_track_info), MaterialSymbols.Info) { requestBottomSheet(TrackContextBottomSheetRequest.Info(track)) })
            if (actions.findLyrics) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_find_lyrics), MaterialSymbols.Search) { requestBottomSheet(TrackContextBottomSheetRequest.FindLyrics(track)) })
            if (actions.lyricsControl) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_lyrics_control), MaterialSymbols.Lyrics) { requestBottomSheet(TrackContextBottomSheetRequest.LyricsControl(track)) })
            if (actions.comments) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_comments), MaterialSymbols.Chat) { requestBottomSheet(TrackContextBottomSheetRequest.Comments(track)) })
            val hasTopSectionEntries = showPlayNext ||
                showAddToQueue ||
                actions.trackInfo ||
                actions.findLyrics ||
                actions.lyricsControl ||
                actions.comments
            if (showMoodRadio && hasTopSectionEntries) add(ContextActionMenuEntry.Divider)
            if (showMoodRadio) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_start_mood_radio), MaterialSymbols.Sensors) { closeAfter { if (actions.moodRadio) onStartMoodRadio(track) else moodRadio.onStart(track.id) } })
            if (showSimilarSongs) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_similar_songs), MaterialSymbols.Sensors) { closeAfter { similarMenu.onOpen(track.id) } })
            if (showMoodRadio && (actions.favorite || neteaseSongId != null || actions.addToPlaylist || hasNavigationActions || actions.removeFromPlaylist)) add(ContextActionMenuEntry.Divider)
            if (!showMoodRadio && hasTopSectionEntries) add(ContextActionMenuEntry.Divider)
            if (actions.favorite) {
                add(
                    ContextActionMenuEntry.Action(
                        label = stringResource(if (favorite) R.string.track_context_remove_from_library else R.string.track_context_add_to_library),
                        symbol = MaterialSymbols.Library,
                        filled = favorite,
                    ) {
                        closeAfter {
                            if (!favorite) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onFavoriteChange(track, !favorite)
                        }
                    },
                )
            }
            if (neteaseSongId != null) {
                val targetLiked = !neteaseLiked
                val likeFailedTemplate = stringResource(R.string.netease_like_failed, "{err}")
                add(
                    ContextActionMenuEntry.Action(
                        label = stringResource(if (neteaseLiked) R.string.netease_unlike_track else R.string.netease_like_track),
                        symbol = if (neteaseLiked) MaterialSymbols.HeartMinus else MaterialSymbols.HeartPlus,
                        filled = neteaseLiked,
                    ) {
                        closeAfter {
                            if (targetLiked) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            if (NeteaseRepository.cookie(context).isNullOrBlank()) {
                                Toast.makeText(context, R.string.netease_login_required_action, Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    NeteaseRepository.setTrackLiked(context, track, neteaseSongId, targetLiked)
                                        .onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (targetLiked) R.string.netease_liked_toast else R.string.netease_unliked_toast,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        .onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                likeFailedTemplate.replace("{err}", err.message.orEmpty()),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                }
                            }
                        }
                    },
                )
            }
            if (actions.addToPlaylist) add(
                ContextActionMenuEntry.Action(stringResource(R.string.track_context_add_to_playlist), MaterialSymbols.PlaylistAdd) {
                    requestBottomSheet(
                        TrackContextBottomSheetRequest.Playlist(
                            trackIds = listOf(track.id),
                            allowNeteasePlaylists = actions.allowNeteasePlaylists,
                        ),
                    )
                },
            )
            if ((actions.favorite || neteaseSongId != null || actions.addToPlaylist) && (hasNavigationActions || actions.removeFromPlaylist)) {
                add(ContextActionMenuEntry.Divider)
            }
            if (actions.goToAlbum && hasAlbum) add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_go_to_album), MaterialSymbols.Album) {
                presentAfterFullscreenCloses {
                    if (neteaseAlbumId != null) {
                        neteaseEntityActions.onOpenAlbum(
                            neteaseAlbumId,
                            track.album,
                            NeteaseRepository.coverUrlForTrack(track),
                        )
                    } else {
                        onGoToAlbum(track)
                    }
                }
            })
            if (actions.goToArtists && hasArtists) {
                if (neteaseArtistId != null) {
                    val name = artists.firstOrNull()?.name.orEmpty()
                    add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_go_to_artist, name), MaterialSymbols.Person) {
                        presentAfterFullscreenCloses { neteaseEntityActions.onOpenArtist(neteaseArtistId, name) }
                    })
                } else if (artists.size == 1) {
                    add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_go_to_artist, artists.single().name), MaterialSymbols.Person) { presentAfterFullscreenCloses { onGoToArtist(artists.single()) } })
                } else {
                    add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_go_to_artists), MaterialSymbols.People) { requestBottomSheet(TrackContextBottomSheetRequest.Artists(artists)) })
                }
            }
            if (actions.removeFromPlaylist) {
                if (hasNavigationActions) add(ContextActionMenuEntry.Divider)
                add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_remove_from_playlist), MaterialSymbols.PlaylistRemove, destructive = true) { closeAfter { onRemoveFromPlaylist(track) } })
            }
            val downloadedNetease = track.id.startsWith("netease:") &&
                track.audioPath?.let { !it.startsWith("netease://") } == true
            if ((onDownload != null && !downloadedNetease) || onMatchSong != null) {
                add(ContextActionMenuEntry.Divider)
                if (onDownload != null && !downloadedNetease) {
                    add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_download), MaterialSymbols.ArrowDownward) { closeAfter { onDownload(track) } })
                }
                if (onMatchSong != null) {
                    add(ContextActionMenuEntry.Action(stringResource(R.string.netease_match_track), MaterialSymbols.Search) { closeAfter { onMatchSong(track) } })
                }
            }
            if (actions.hide) {
                add(ContextActionMenuEntry.Divider)
                add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_hide), MaterialSymbols.FilterList) {
                    closeAfter { (onHide ?: defaultHide)(track) }
                })
            }
            if (onDeleteFromDevice != null) {
                add(ContextActionMenuEntry.Divider)
                add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_delete_from_device), MaterialSymbols.Delete, destructive = true) { closeAfter { onDeleteFromDevice(track) } })
            } else if (downloadedNetease) {
                // 已下载的网易云歌曲：不再显示下载，改为删除下载。
                add(ContextActionMenuEntry.Divider)
                add(ContextActionMenuEntry.Action(stringResource(R.string.track_context_delete_download), MaterialSymbols.Delete, destructive = true) {
                    closeAfter {
                        scope.launch {
                            com.exodidio.tune.netease.NeteaseRepository.deleteDownload(context, track.id)
                            AndroidPlaybackRuntime.controller().removeFromQueue(track.id)
                        }
                    }
                })
            }
        }
        ContextActionMenu(entries)
    }
    detailSheet?.let { request ->
        // The anchor can carry parent-data modifiers (for example RowScope.weight
        // in Library Home's recent-track grid). A Dialog must not inherit those
        // constraints, or its sheet can be measured at the top of the window.
        TrackContextBottomSheet(
            request = request,
            onDismiss = dismissAll,
            onArtistSelected = { artist -> presentAfterFullscreenCloses { onGoToArtist(artist) } },
            playlists = playlists,
            onPlaylistMembershipChange = onPlaylistMembershipChange,
            onCreatePlaylistRequested = onCreatePlaylistRequested,
        )
    }
}

@Composable
internal fun TrackContextBottomSheet(
    request: TrackContextBottomSheetRequest,
    onDismiss: () -> Unit,
    onArtistSelected: (TrackContextArtist) -> Unit,
    onSearchLyrics: suspend (LibraryTrack, String, String) -> List<com.exodidio.tune.lyrics.LyricsSearchResult> = { _, _, _ -> emptyList() },
    onLyricsSelected: suspend (String, com.exodidio.tune.lyrics.LyricsSearchResult) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    playlists: List<LibraryPlaylist> = emptyList(),
    onPlaylistMembershipChange: (playlistId: String, trackIds: List<String>, add: Boolean) -> Unit = { _, _, _ -> },
    onCreatePlaylistRequested: (trackIds: List<String>) -> Unit = {},
) {
    val root: TrackContextSheet = remember(request) { TrackContextSheet.Root(request) }
    val stack = rememberTuneBottomSheetStack(root)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var createNeteaseTarget by remember(request) { mutableStateOf<List<Pair<Long, LibraryTrack?>>?>(null) }
    var creatingNetease by remember(request) { mutableStateOf(false) }
    var createNeteaseError by remember(request) { mutableStateOf<String?>(null) }
    val createdToast = stringResource(R.string.netease_playlist_created)
    val addedToast = stringResource(R.string.netease_track_added_to_playlist)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")

    val activeCreateTarget = createNeteaseTarget
    if (activeCreateTarget != null) {
        NeteasePlaylistEditorBottomSheet(
            isCreate = true,
            busy = creatingNetease,
            errorMessage = createNeteaseError,
            onDismiss = {
                createNeteaseTarget = null
                createNeteaseError = null
            },
            onSubmit = { name, description, isPrivate ->
                if (creatingNetease) return@NeteasePlaylistEditorBottomSheet
                creatingNetease = true
                createNeteaseError = null
                scope.launch {
                    NeteaseRepository.createNeteasePlaylist(
                        context = context,
                        name = name,
                        description = description,
                        privacyPrivate = isPrivate,
                        initialSongs = activeCreateTarget,
                    ).onSuccess {
                        creatingNetease = false
                        createNeteaseTarget = null
                        val message = if (activeCreateTarget.isNotEmpty()) addedToast else createdToast
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }.onFailure { err ->
                        creatingNetease = false
                        val message = err.message.orEmpty()
                        createNeteaseError = message
                        Toast.makeText(context, failedTemplate.replace("{err}", message), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        return
    }

    TuneBottomSheetStack(
        stack = stack,
        onDismiss = onDismiss,
        modifier = modifier,
        title = { sheet ->
            TrackContextSheetTitle(
                when (sheet) {
                    is TrackContextSheet.Root -> when (sheet.request) {
                        is TrackContextBottomSheetRequest.Info -> R.string.track_context_track_info_title
                        is TrackContextBottomSheetRequest.FindLyrics -> R.string.track_context_find_lyrics_title
                        is TrackContextBottomSheetRequest.LyricsControl -> R.string.lyrics_control_title
                        is TrackContextBottomSheetRequest.Comments -> R.string.comments_sheet_title
                        is TrackContextBottomSheetRequest.Playlist -> R.string.track_context_playlist_picker_title
                        is TrackContextBottomSheetRequest.Artists -> R.string.track_context_artist_picker_title
                    }
                    is TrackContextSheet.LyricsPreview -> R.string.find_lyrics_preview
                },
            )
        },
        content = { sheet ->
            when (sheet) {
                is TrackContextSheet.Root -> when (val rootRequest = sheet.request) {
                    is TrackContextBottomSheetRequest.Info -> TrackInfoContent(rootRequest.track)
                    is TrackContextBottomSheetRequest.FindLyrics -> FindLyricsContent(
                        track = rootRequest.track,
                        state = requireNotNull(sheet.lyricsState),
                        onSearch = onSearchLyrics,
                        onPreview = { lyric -> stack.push(TrackContextSheet.LyricsPreview(rootRequest.track, lyric)) },
                    )
                    is TrackContextBottomSheetRequest.LyricsControl -> LyricsControlSection(
                        track = rootRequest.track,
                    )
                    is TrackContextBottomSheetRequest.Comments -> SongCommentsSheetContent(
                        track = rootRequest.track,
                    )
                    is TrackContextBottomSheetRequest.Playlist -> PlaylistPickerContent(
                        trackIds = rootRequest.trackIds,
                        addOnly = rootRequest.addOnly,
                        allowNeteasePlaylists = rootRequest.allowNeteasePlaylists,
                        playlists = playlists,
                        onPlaylistMembershipChange = onPlaylistMembershipChange,
                        onCreatePlaylistRequested = onCreatePlaylistRequested,
                        onCreateNeteasePlaylistRequested = { songs -> createNeteaseTarget = songs },
                    )
                    is TrackContextBottomSheetRequest.Artists -> ContextActionMenu(
                        rootRequest.artists.map { artist ->
                            ContextActionMenuEntry.Action(artist.name, MaterialSymbols.Person) { onArtistSelected(artist) }
                        },
                    )
                }
                is TrackContextSheet.LyricsPreview -> FindLyricsPreviewContent(
                    track = sheet.track,
                    lyric = sheet.lyric,
                    onSelected = onLyricsSelected,
                    onDismiss = onDismiss,
                )
            }
        },
    )
}

@Composable private fun TrackContextSheetTitle(title: Int) = Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = LocalTuneColors.current.textMain)

@Composable
private fun PlaylistPickerContent(
    trackIds: List<String>,
    addOnly: Boolean,
    allowNeteasePlaylists: Boolean,
    playlists: List<LibraryPlaylist>,
    onPlaylistMembershipChange: (playlistId: String, trackIds: List<String>, add: Boolean) -> Unit,
    onCreatePlaylistRequested: (trackIds: List<String>) -> Unit,
    onCreateNeteasePlaylistRequested: (List<Pair<Long, LibraryTrack?>>) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editable = remember(playlists) { playlists.filter(::playlistIsEditable) }
    val singleTrack = trackIds.distinct().singleOrNull()
    var locallyAddedPlaylistIds by remember(trackIds, addOnly) { mutableStateOf<Set<String>>(emptySet()) }

    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val activeNeteasePlaylists by NeteaseRepository.activePlaylists.collectAsStateWithLifecycle()
    val likedSongIds by NeteaseRepository.likedSongIds.collectAsStateWithLifecycle()
    val neteaseSongs = remember(trackIds, allowNeteasePlaylists) {
        if (!allowNeteasePlaylists) {
            emptyList()
        } else {
            trackIds.distinct().mapNotNull { id ->
                val songId = NeteaseRepository.songIdForTrack(id) ?: return@mapNotNull null
                songId to AndroidSyncRuntime.syncStore.trackForPlayback(id)
            }
        }
    }
    val showNeteaseSection = allowNeteasePlaylists && loggedIn && neteaseSongs.isNotEmpty()
    LaunchedEffect(showNeteaseSection, mine.loaded) {
        if (showNeteaseSection && !mine.loaded) {
            NeteaseRepository.loadMine()
        }
    }
    var busyNeteasePlaylistId by remember(trackIds) { mutableStateOf<String?>(null) }
    var locallyAddedNeteaseIds by remember(trackIds) { mutableStateOf<Set<String>>(emptySet()) }
    var locallyRemovedNeteaseIds by remember(trackIds) { mutableStateOf<Set<String>>(emptySet()) }
    val activeNeteaseById = remember(activeNeteasePlaylists) { activeNeteasePlaylists.associateBy { it.id } }
    val likedRemoteId = NeteaseRepository.likedPlaylistRemoteId()
    val addedToast = stringResource(R.string.netease_track_added_to_playlist)
    val removedToast = stringResource(R.string.netease_track_removed_from_playlist)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 420.dp)) {
        if (showNeteaseSection) {
            item("netease_section_header") {
                Text(
                    text = stringResource(R.string.netease_playlists_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            item("netease_create_playlist") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(enabled = busyNeteasePlaylistId == null) {
                            onCreateNeteasePlaylistRequested(neteaseSongs)
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        MaterialSymbol(MaterialSymbols.Add, null, size = 18.dp, tint = colors.primary)
                    }
                    Text(
                        text = stringResource(R.string.netease_create_playlist_action),
                        modifier = Modifier.padding(start = 12.dp),
                        color = colors.textMain,
                    )
                }
            }
            items(mine.created, key = { "netease_${it.id}" }, contentType = { "netease_playlist_picker_row" }) { playlist ->
                val isLikedPlaylist = playlist.id == NeteaseRepository.LIKED_ID ||
                    (likedRemoteId != null && playlist.id == likedRemoteId)
                val record = activeNeteaseById[playlist.id]
                    ?: if (isLikedPlaylist) activeNeteaseById[NeteaseRepository.LIKED_ID] else null
                val entryIds = remember(record?.entries) {
                    record?.entries?.mapTo(HashSet()) { it.songId }
                }
                val membershipKnown = isLikedPlaylist ||
                    (record != null && (record.entries.isNotEmpty() || record.remoteTrackCount == 0))
                val checked = when {
                    isLikedPlaylist -> neteaseSongs.all { (sid, _) -> sid in likedSongIds }
                    record != null && (record.entries.isNotEmpty() || record.remoteTrackCount == 0) -> {
                        val ids = entryIds.orEmpty()
                        neteaseSongs.all { (sid, _) -> sid in ids }
                    }
                    playlist.id in locallyAddedNeteaseIds -> true
                    playlist.id in locallyRemovedNeteaseIds -> false
                    else -> false
                }
                val add = addOnly || !checked
                val targetSongs = when {
                    !add -> neteaseSongs
                    membershipKnown && entryIds != null -> neteaseSongs.filter { (sid, _) -> sid !in entryIds }.ifEmpty { neteaseSongs }
                    isLikedPlaylist -> neteaseSongs.filter { (sid, _) -> sid !in likedSongIds }.ifEmpty { neteaseSongs }
                    else -> neteaseSongs
                }
                val isBusy = busyNeteasePlaylistId == playlist.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(enabled = busyNeteasePlaylistId == null) {
                            busyNeteasePlaylistId = playlist.id
                            scope.launch {
                                NeteaseRepository.manipulateNeteasePlaylistTracks(
                                    context = context,
                                    playlistId = playlist.id,
                                    tracks = targetSongs,
                                    add = add,
                                ).onSuccess {
                                    if (add) {
                                        locallyAddedNeteaseIds += playlist.id
                                        locallyRemovedNeteaseIds -= playlist.id
                                    } else {
                                        locallyRemovedNeteaseIds += playlist.id
                                        locallyAddedNeteaseIds -= playlist.id
                                    }
                                    Toast.makeText(
                                        context,
                                        if (add) addedToast else removedToast,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }.onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        failedTemplate.replace("{err}", err.message.orEmpty()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                busyNeteasePlaylistId = null
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isBusy) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        PlaylistPickerCheckbox(
                            checked = checked,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(playlist.name, color = colors.textMain)
                        val count = record?.entries?.size?.takeIf { it > 0 } ?: record?.remoteTrackCount ?: playlist.trackCount
                        if (count > 0) {
                            Text(
                                text = stringResource(R.string.netease_playlist_entry_count, count),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }
            item("netease_local_divider") {
                HorizontalDivider(
                    color = colors.borderGlass,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            item("local_section_header") {
                Text(
                    text = stringResource(R.string.local_playlists_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        item("local_create_playlist") {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onCreatePlaylistRequested(trackIds) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    MaterialSymbol(MaterialSymbols.Add, null, size = 18.dp, tint = colors.primary)
                }
                Text(stringResource(R.string.track_context_create_playlist), modifier = Modifier.padding(start = 12.dp), color = colors.textMain)
            }
        }
        if (editable.isEmpty()) {
            item("local_empty_playlists") {
                Text(stringResource(R.string.track_context_no_editable_playlists), modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp), color = colors.textMuted)
            }
        } else {
            items(editable, key = LibraryPlaylist::id, contentType = { "playlist_picker_row" }) { playlist ->
                // Genre-scope adds deliberately skip membership inspection. The
                // mutation/projection layer deduplicates playlist track IDs, so
                // checking every selected track here only adds query/work cost.
                val checked = if (addOnly) {
                    playlist.id in locallyAddedPlaylistIds
                } else if (singleTrack != null) {
                    singleTrack in playlist.trackIds
                } else {
                    trackIds.isNotEmpty() && trackIds.all { it in playlist.trackIds }
                }
                val add = addOnly || !checked
                val targetIds = when {
                    addOnly -> if (checked) emptyList() else trackIds
                    singleTrack != null -> listOf(singleTrack)
                    add -> trackIds.filter { it !in playlist.trackIds }
                    else -> trackIds
                }
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        if (targetIds.isNotEmpty()) {
                            if (addOnly) locallyAddedPlaylistIds += playlist.id
                            onPlaylistMembershipChange(playlist.id, targetIds, add)
                        }
                    }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistPickerCheckbox(
                        checked = checked,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(playlist.name, modifier = Modifier.padding(start = 12.dp), color = colors.textMain)
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalTuneColors.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (checked) colors.primary else colors.glassElevated)
            .border(0.5.dp, if (checked) colors.primary else colors.borderGlass, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) MaterialSymbol(MaterialSymbols.Check, null, size = 16.dp, tint = colors.onPrimary, filled = true)
    }
}

internal fun playlistIsEditable(playlist: LibraryPlaylist): Boolean {
    if (playlist.id == "favorites") return false
    val root = runCatching { LibrarySyncProtocol.json.parseToJsonElement(playlist.metadataJson) as? JsonObject }.getOrNull()
    val value = (root?.get("playlist") as? JsonObject ?: root)?.get("is_smart") as? JsonPrimitive
    return value?.contentOrNull != "true"
}
