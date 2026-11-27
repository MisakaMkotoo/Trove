package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.R
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryAlbum
import com.exodidio.tune.ui.components.AlbumRow
import com.exodidio.tune.ui.components.ArtistContextMenu
import com.exodidio.tune.ui.components.ArtworkHeroBackdrop
import com.exodidio.tune.ui.components.DetailHero
import com.exodidio.tune.ui.components.DetailHeroArtworkShape
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.InsetListDivider
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LibraryTextFilterSlot
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.rememberPullDownFilterState

private const val ArtistAlbumDividerTag = "artist-detail-album-divider"

internal fun filterArtistDetailAlbums(albums: List<LibraryAlbum>, query: String): List<LibraryAlbum> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return albums
    return albums.filter { album ->
        album.title.contains(trimmed, ignoreCase = true) ||
            album.artist.contains(trimmed, ignoreCase = true)
    }
}

@Composable
internal fun ArtistDetailsContent(
    uiState: ArtistDetailsUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState,
    contentPadding: PaddingValues = PaddingValues(),
    hazeState: HazeState? = null,
    reduceTransparency: Boolean = false,
    onHeroColorChanged: (Color) -> Unit = {},
    onPlay: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onPlayNext: (List<String>) -> Unit = {},
    onAddToQueue: (List<String>) -> Unit = {},
    onArtistUpdate: (String, android.net.Uri?, Boolean) -> Unit = { _, _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onAlbumClick: (LibraryAlbum) -> Unit = {},
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
) {
    val artist = uiState.artist
    if (artist == null) {
        HeroCard(
            symbol = MaterialSymbols.Person,
            title = stringResource(R.string.artist_details_empty_title),
            description = stringResource(R.string.artist_details_empty_description),
            modifier = modifier.fillMaxSize().padding(contentPadding),
        )
        return
    }
    var menuExpanded by remember(artist.id) { mutableStateOf(false) }
    var showArtistEditor by remember(artist.id) { mutableStateOf(false) }
    var filterQuery by rememberSaveable(artist.id) { mutableStateOf("") }
    val trimmedQuery = filterQuery.trim()
    val visibleAlbums = remember(uiState.albums, trimmedQuery) {
        filterArtistDetailAlbums(uiState.albums, trimmedQuery)
    }
    val pullDownFilterState = rememberPullDownFilterState(
        listState = listState,
        filterKey = "artist-details:${artist.id}",
        filterActive = trimmedQuery.isNotEmpty(),
    )
    Box(modifier = modifier.fillMaxSize().nestedScroll(pullDownFilterState.scrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item("hero") {
                ArtworkHeroBackdrop(artist.artworkPath, Modifier.fillMaxWidth(), onHeroColorChanged, reduceTransparency = reduceTransparency) {
                    ArtistContextMenu(
                        trackIds = uiState.tracks.map { it.id },
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onEdit = { showArtistEditor = true },
                        onBottomSheetRequested = onTrackContextBottomSheet,
                        addToPlaylistOnly = true,
                        hazeState = hazeState,
                        playbackQueue = playbackQueue,
                    ) {
                        DetailHero(
                            title = artist.name,
                            subtitle = stringResource(
                                R.string.artist_details_summary,
                                pluralStringResource(R.plurals.artist_details_album_count, uiState.albums.size, uiState.albums.size),
                                pluralStringResource(R.plurals.artist_details_track_count, uiState.tracks.size, uiState.tracks.size),
                            ),
                            metadata = null,
                            playLabel = stringResource(R.string.player_play),
                            shuffleLabel = stringResource(R.string.player_shuffle),
                            moreLabel = stringResource(R.string.artist_row_more_options),
                            modifier = Modifier.fillMaxWidth().padding(
                                start = 24.dp,
                                top = contentPadding.calculateTopPadding(),
                                end = 24.dp,
                                bottom = 20.dp,
                            ),
                            artworkPath = artist.artworkPath,
                            artworkShape = DetailHeroArtworkShape.Circle,
                            artworkSize = 144.dp,
                            fallbackSymbol = MaterialSymbols.Person,
                            reduceTransparency = reduceTransparency,
                            onPlayClick = onPlay,
                            onShuffleClick = onShuffle,
                            onMoreClick = { menuExpanded = true },
                        )
                    }
                }
            }
            item(key = "artist-details-text-filter", contentType = "artist-details-text-filter") {
                LibraryTextFilterSlot(state = pullDownFilterState) { showPlaceholder ->
                    LibraryTextFilter(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        placeholder = stringResource(R.string.filter_placeholder_search),
                        showPlaceholderAndLeadingSymbol = showPlaceholder,
                    )
                }
            }
            if (visibleAlbums.isEmpty() && trimmedQuery.isNotEmpty()) {
                item(key = "artist-details-filter-empty", contentType = "artist-details-filter-empty") {
                    HeroCard(
                        symbol = MaterialSymbols.Search,
                        title = stringResource(R.string.albums_no_match_title),
                        description = stringResource(R.string.filter_no_match_description),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            } else {
                itemsIndexed(visibleAlbums, key = { _, album -> album.id }, contentType = { _, _ -> "artist_album_row" }) { index, album ->
                    if (index == 0) InsetListDivider(Modifier.testTag(ArtistAlbumDividerTag))
                    AlbumRow(
                        title = album.title,
                        artist = album.artist.ifBlank { stringResource(R.string.album_unknown_artist) },
                        artworkPath = album.artworkPath,
                        onClick = { onAlbumClick(album) },
                    )
                    InsetListDivider(Modifier.testTag(ArtistAlbumDividerTag))
                }
            }
        }
    }
    if (showArtistEditor) {
        EditArtistBottomSheet(
            artworkPath = artistManualArtworkPath(artist.id, uiState.artistArtworkKeys, uiState.artworkPathByKey),
            onDismiss = { showArtistEditor = false },
            onSave = { artwork, clearArtwork -> onArtistUpdate(artist.id, artwork, clearArtwork); showArtistEditor = false },
        )
    }
}

