package com.exodidio.tune.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import com.exodidio.tune.R
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryPlaybackActions
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LibraryVirtualList
import com.exodidio.tune.ui.components.LocalBatchChromeHost
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.MultiSelectActionBar
import com.exodidio.tune.ui.components.MultiSelectCheckBadge
import com.exodidio.tune.ui.components.MultiSelectSelectionHeader
import com.exodidio.tune.ui.components.TrackContextArtist
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.theme.LocalTuneColors

@Composable
internal fun LibraryTracksContent(
    uiState: LibraryTracksUiState,
    onSortOptionSelected: (TrackSortOption) -> Unit,
    onToggleSortOrder: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = remember(uiState.sortOption, uiState.sortOrder) { LazyListState() },
    contentPadding: PaddingValues = PaddingValues(),
    onTrackClick: ((LibraryTrack) -> Unit)? = null,
    onTrackMoreClick: ((LibraryTrack) -> Unit)? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackAlbumClick: (LibraryTrack) -> Unit = {},
    onTrackArtistClick: (TrackContextArtist) -> Unit = {},
    hazeState: HazeState? = null,
    onPlayAll: (Boolean) -> Unit = {},
    onFilterQueryChange: (String) -> Unit = {},
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onTracksRemovedFromLibrary: (List<String>) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    var contextTrack by remember { mutableStateOf<LibraryTrack?>(null) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    BackHandler(enabled = multiSelectMode) {
        multiSelectMode = false
        selectedTrackIds = emptySet()
    }
    val deleteFromDevice = rememberDeleteFilesFromDeviceLauncher { confirmedIds ->
        onTracksRemovedFromLibrary(confirmedIds)
        selectedTrackIds = selectedTrackIds - confirmedIds.toSet()
        if (selectedTrackIds.isEmpty()) multiSelectMode = false
    }
    val batchHost = LocalBatchChromeHost.current
    val batchId = remember { Any() }
    val listHazeState = if (batchHost == null && hazeState != null) rememberHazeState() else null
    val latestTracks by rememberUpdatedState(uiState.tracks)
    val latestOnBottomSheet by rememberUpdatedState(onTrackContextBottomSheet)
    val batchTopExtra by animateDpAsState(
        targetValue = if (multiSelectMode) 60.dp else 0.dp,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "tracks-batch-top-padding",
    )
    if (batchHost != null) {
        DisposableEffect(batchHost, batchId) {
            onDispose { batchHost.dismiss(batchId) }
        }
        SideEffect {
            if (multiSelectMode) {
                batchHost.show(
                    id = batchId,
                    topContent = {
                        val tracks = latestTracks
                        val currentSelected = selectedTrackIds
                        MultiSelectSelectionHeader(
                            selectedCount = currentSelected.size,
                            allSelected = tracks.isNotEmpty() && currentSelected.size == tracks.size,
                            onToggleSelectAll = {
                                selectedTrackIds = if (currentSelected.size == tracks.size) {
                                    emptySet()
                                } else {
                                    tracks.map { it.id }.toSet()
                                }
                            },
                            onExit = {
                                multiSelectMode = false
                                selectedTrackIds = emptySet()
                            },
                        )
                    },
                    bottomContent = {
                        val currentSelected = selectedTrackIds
                        val selectedTracks = latestTracks.filter { it.id in currentSelected }
                        val selectedIds = selectedTracks.map { it.id }
                        val addedToast = context.getString(R.string.batch_added_to_queue, selectedIds.size)
                        MultiSelectActionBar(
                            selectedCount = selectedIds.size,
                            onPlayNext = {
                                if (selectedIds.isNotEmpty()) {
                                    AndroidPlaybackRuntime.controller().playNext(selectedIds)
                                    Toast.makeText(context, addedToast, Toast.LENGTH_SHORT).show()
                                    multiSelectMode = false
                                    selectedTrackIds = emptySet()
                                }
                            },
                            onAddToPlaylist = {
                                if (selectedIds.isNotEmpty()) {
                                    latestOnBottomSheet(
                                        TrackContextBottomSheetRequest.Playlist(
                                            trackIds = selectedIds,
                                            addOnly = true,
                                        ),
                                    )
                                }
                            },
                            onRemove = {
                                if (selectedTracks.isNotEmpty()) {
                                    deleteFromDevice(selectedTracks)
                                }
                            },
                        )
                    },
                )
            } else {
                batchHost.dismiss(batchId)
            }
        }
    }
    val allTrackIds = remember(uiState.tracks) { uiState.tracks.map { it.id } }
    val listPadding = remember(contentPadding, batchTopExtra, batchHost, multiSelectMode) {
        PaddingValues(
            top = contentPadding.calculateTopPadding() + batchTopExtra,
            bottom = contentPadding.calculateBottomPadding() + if (batchHost == null && multiSelectMode) 84.dp else 0.dp,
            start = 0.dp,
            end = 0.dp,
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        LibraryVirtualList(
            items = uiState.tracks,
            isLoaded = uiState.isLoaded,
            key = { track -> track.id.ifBlank { "${track.title}_${track.artists}" } },
            contentType = "track_row",
            listState = listState,
            contentPadding = listPadding,
            modifier = Modifier.fillMaxSize(),
            listHazeState = listHazeState,
            dividerTestTag = "track-row-divider",
            filterKey = "tracks",
            filterActive = uiState.filterQuery.isNotBlank(),
            filterContent = {
                LibraryTextFilter(
                    value = uiState.filterQuery,
                    onValueChange = onFilterQueryChange,
                    placeholder = stringResource(R.string.filter_placeholder_search),
                )
            },
            leadingContent = if (multiSelectMode) {
                null
            } else {
                {
                    Column {
                        LibraryPlaybackActions(
                            playLabel = stringResource(R.string.player_play),
                            shuffleLabel = stringResource(R.string.player_shuffle),
                            onPlay = { onPlayAll(false) },
                            onShuffle = { onPlayAll(true) },
                            hazeState = hazeState,
                        )
                        if (uiState.tracks.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = stringResource(R.string.batch_select_enter),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.primary,
                                    modifier = Modifier
                                        .clickable {
                                            multiSelectMode = true
                                            selectedTrackIds = emptySet()
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            },
            alphabeticalIndexKey = when (uiState.sortOption) {
                TrackSortOption.Name -> { track -> track.sortTitle.ifBlank { track.title } }
                TrackSortOption.Artist -> { track -> track.sortArtists.ifBlank { track.artists } }
                else -> null
            },
            emptyContent = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HeroCard(
                        title = stringResource(if (uiState.filterQuery.isBlank()) R.string.tracks_empty_title else R.string.tracks_no_match_title),
                        description = stringResource(if (uiState.filterQuery.isBlank()) R.string.tracks_empty_description else R.string.filter_no_match_description),
                        symbol = MaterialSymbols.MusicNote,
                    )
                }
            },
        ) { track ->
            if (multiSelectMode) {
                val isSelected = track.id in selectedTrackIds
                val rowTint by animateColorAsState(
                    targetValue = if (isSelected) colors.primary.copy(alpha = 0.08f) else Color.Transparent,
                    animationSpec = if (LocalReduceMotion.current) snap() else tween(180, easing = FastOutSlowInEasing),
                    label = "tracks-batch-row-tint",
                )
                TrackRow(
                    title = track.title,
                    artist = track.artists,
                    artworkPath = track.artworkPath,
                    modifier = Modifier.background(rowTint),
                    onClick = {
                        selectedTrackIds = if (isSelected) selectedTrackIds - track.id else selectedTrackIds + track.id
                    },
                    trailingContent = {
                        Box(
                            modifier = Modifier.padding(end = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MultiSelectCheckBadge(selected = isSelected)
                        }
                    },
                )
            } else {
                val onItemClick = remember(onTrackClick, track) {
                    if (onTrackClick != null) { { onTrackClick(track) } } else null
                }
                val onItemMoreClick = remember(onTrackMoreClick, track) {
                    { onTrackMoreClick?.invoke(track); contextTrack = track }
                }
                TrackContextMenu(
                    track = track,
                    expanded = contextTrack?.id == track.id,
                    onDismiss = { if (contextTrack?.id == track.id) contextTrack = null },
                    playbackQueue = playbackQueue,
                    onPlayNext = onTrackPlayNext,
                    onAddToQueue = onTrackAddToQueue,
                    onFavoriteChange = onTrackFavoriteToggle,
                    onGoToAlbum = onTrackAlbumClick,
                    onGoToArtist = onTrackArtistClick,
                    onBottomSheetRequested = onTrackContextBottomSheet,
                    onDeleteFromDevice = { item -> deleteFromDevice(listOf(item)) },
                ) {
                    TrackRow(
                        title = track.title,
                        artist = track.artists,
                        artworkPath = track.artworkPath,
                        onClick = onItemClick,
                        onMoreClick = onItemMoreClick,
                        onLongClick = { contextTrack = track },
                    )
                }
            }
        }
        if (batchHost == null && multiSelectMode) {
            MultiSelectSelectionHeader(
                selectedCount = selectedTrackIds.size,
                allSelected = allTrackIds.isNotEmpty() && selectedTrackIds.size == allTrackIds.size,
                onToggleSelectAll = {
                    selectedTrackIds = if (selectedTrackIds.size == allTrackIds.size) emptySet() else allTrackIds.toSet()
                },
                onExit = {
                    multiSelectMode = false
                    selectedTrackIds = emptySet()
                },
                hazeState = listHazeState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = contentPadding.calculateTopPadding() + 4.dp, end = 16.dp),
            )
            val selectedTracks = remember(selectedTrackIds, uiState.tracks) {
                uiState.tracks.filter { it.id in selectedTrackIds }
            }
            val selectedIds = remember(selectedTracks) { selectedTracks.map { it.id } }
            val batchAddedToast = stringResource(R.string.batch_added_to_queue, selectedTrackIds.size)
            MultiSelectActionBar(
                selectedCount = selectedIds.size,
                onPlayNext = {
                    if (selectedIds.isNotEmpty()) {
                        AndroidPlaybackRuntime.controller().playNext(selectedIds)
                        Toast.makeText(context, batchAddedToast, Toast.LENGTH_SHORT).show()
                        multiSelectMode = false
                        selectedTrackIds = emptySet()
                    }
                },
                onAddToPlaylist = {
                    if (selectedIds.isNotEmpty()) {
                        onTrackContextBottomSheet(
                            TrackContextBottomSheetRequest.Playlist(
                                trackIds = selectedIds,
                                addOnly = true,
                            ),
                        )
                    }
                },
                onRemove = {
                    if (selectedTracks.isNotEmpty()) {
                        deleteFromDevice(selectedTracks)
                    }
                },
                hazeState = listHazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}
