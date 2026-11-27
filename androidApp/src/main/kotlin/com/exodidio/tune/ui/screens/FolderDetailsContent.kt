package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.ui.components.ArtworkHeroBackdrop
import com.exodidio.tune.ui.components.DetailHero
import com.exodidio.tune.ui.components.GenreContextMenu
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.InsetListDivider
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextArtist
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackRow
import dev.chrisbanes.haze.HazeState

@Composable
internal fun FolderDetailsContent(
    uiState: FolderDetailsUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    hazeState: HazeState? = null,
    reduceTransparency: Boolean = false,
    onHeroColorChanged: (Color) -> Unit = {},
    onPlay: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onTrackClick: (String) -> Unit = {},
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onTrackPlayNext: (String) -> Unit = {},
    onTrackAddToQueue: (String) -> Unit = {},
    onTrackFavoriteToggle: (String, Boolean) -> Unit = { _, _ -> },
    onTrackAlbumClick: (String) -> Unit = {},
    onTrackArtistClick: (TrackContextArtist) -> Unit = {},
    onFolderPlayNext: (List<String>) -> Unit = {},
    onFolderAddToQueue: (List<String>) -> Unit = {},
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
) {
    val folder = uiState.folder
    if (folder == null || uiState.tracks.isEmpty()) {
        HeroCard(
            symbol = MaterialSymbols.Folder,
            title = stringResource(R.string.folder_details_empty_title),
            description = stringResource(R.string.folder_details_empty_description),
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
        return
    }
    var menuExpanded by remember(folder.id) { mutableStateOf(false) }
    var contextTrackId by remember(folder.id) { mutableStateOf<String?>(null) }
    val countLabel = pluralStringResource(
        R.plurals.playlist_details_track_count,
        uiState.tracks.size,
        uiState.tracks.size,
    )
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "hero") {
            ArtworkHeroBackdrop(
                artworkPath = folder.artworkPath,
                modifier = Modifier.fillMaxWidth(),
                onDominantColorChanged = onHeroColorChanged,
                reduceTransparency = reduceTransparency,
            ) {
                GenreContextMenu(
                    trackIds = uiState.tracks.map { it.id },
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onPlayNext = onFolderPlayNext,
                    onAddToQueue = onFolderAddToQueue,
                    onBottomSheetRequested = onTrackContextBottomSheet,
                    addToPlaylistOnly = true,
                    hazeState = hazeState,
                    playbackQueue = playbackQueue,
                ) {
                    DetailHero(
                        title = folder.name,
                        subtitle = folder.displayPath,
                        metadata = countLabel,
                        playLabel = stringResource(R.string.player_play),
                        shuffleLabel = stringResource(R.string.player_shuffle),
                        moreLabel = stringResource(R.string.folder_row_more_options),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 24.dp,
                                top = contentPadding.calculateTopPadding() - 8.dp,
                                end = 24.dp,
                                bottom = 20.dp,
                            ),
                        showArtwork = false,
                        reduceTransparency = reduceTransparency,
                        onPlayClick = onPlay,
                        onShuffleClick = onShuffle,
                        onMoreClick = { menuExpanded = true },
                    )
                }
            }
        }
        itemsIndexed(
            items = uiState.tracks,
            key = { _, track -> track.id },
            contentType = { _, _ -> "folder_track_row" },
        ) { index, track ->
            if (index == 0) InsetListDivider()
            TrackContextMenu(
                track = track,
                expanded = contextTrackId == track.id,
                onDismiss = { if (contextTrackId == track.id) contextTrackId = null },
                playbackQueue = playbackQueue,
                hazeState = hazeState,
                onPlayNext = { onTrackPlayNext(track.id) },
                onAddToQueue = { onTrackAddToQueue(track.id) },
                onFavoriteChange = { _, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                onGoToAlbum = { if (track.albumId.isNotBlank()) onTrackAlbumClick(track.albumId) },
                onGoToArtist = onTrackArtistClick,
                onBottomSheetRequested = onTrackContextBottomSheet,
            ) {
                TrackRow(
                    title = track.title,
                    artist = track.artists,
                    artworkPath = track.artworkPath,
                    onClick = { onTrackClick(track.id) },
                    onMoreClick = { contextTrackId = track.id },
                    onLongClick = { contextTrackId = track.id },
                )
            }
            InsetListDivider()
        }
    }
}
