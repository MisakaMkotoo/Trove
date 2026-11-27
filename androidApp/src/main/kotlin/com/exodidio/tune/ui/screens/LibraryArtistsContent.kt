package com.exodidio.tune.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.ui.components.ArtistRow
import com.exodidio.tune.ui.components.ArtistContextMenu
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryVirtualList
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneSegmentedTabBar
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.player.PlaybackQueueSnapshot

/** 合并模式下的“艺人”页面：0 = 关注的歌手，1 = 本地歌手。 */
@Composable
internal fun MergedLibraryArtistsContent(
    uiState: LibraryArtistsUiState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = remember(uiState.sortOption, uiState.sortOrder) { LazyListState() },
    mineArtistsListState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    onArtistClick: ((com.exodidio.tune.sync.LibraryArtist) -> Unit)? = null,
    onFilterQueryChange: (String) -> Unit = {},
    orderedTrackIdsForArtist: (String) -> List<String> = { emptyList() },
    onArtistPlayNext: (List<String>) -> Unit = {},
    onArtistAddToQueue: (List<String>) -> Unit = {},
    onArtistUpdate: (String, android.net.Uri?, Boolean) -> Unit = { _, _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onOpenMineArtist: (MineArtist) -> Unit = {},
    hazeState: HazeState? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
) {
    val reduceMotion = LocalReduceMotion.current
    val safeTab = selectedTab.coerceIn(0, 1)
    val childContentPadding = remember(contentPadding) {
        PaddingValues(top = 0.dp, bottom = contentPadding.calculateBottomPadding())
    }
    Column(modifier = modifier.fillMaxSize()) {
        TuneSegmentedTabBar(
            tabs = listOf(
                stringResource(R.string.mine_artists_title),
                stringResource(R.string.library_artists_local_tab),
            ),
            selectedIndex = safeTab,
            onSelect = onTabSelected,
            modifier = Modifier.padding(
                start = 24.dp,
                end = 24.dp,
                top = contentPadding.calculateTopPadding() + 4.dp,
                bottom = 8.dp,
            ),
            tabTestTagPrefix = "merged-artists-tab",
        )
        AnimatedContent(
            targetState = safeTab,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState >= initialState
                    (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { if (forward) it / 3 else -it / 3 } +
                        fadeIn(tween(200, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { if (forward) -it / 3 else it / 3 } +
                            fadeOut(tween(160, easing = FastOutSlowInEasing)))
                }
            },
            label = "merged-artists-tab-content",
        ) { tab ->
            when (tab) {
                0 -> MineArtistsContent(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = childContentPadding,
                    listState = mineArtistsListState,
                    onOpenArtist = onOpenMineArtist,
                )
                else -> LibraryArtistsContent(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                    listState = listState,
                    contentPadding = childContentPadding,
                    onArtistClick = onArtistClick,
                    onFilterQueryChange = onFilterQueryChange,
                    orderedTrackIdsForArtist = orderedTrackIdsForArtist,
                    onArtistPlayNext = onArtistPlayNext,
                    onArtistAddToQueue = onArtistAddToQueue,
                    onArtistUpdate = onArtistUpdate,
                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                    hazeState = hazeState,
                    playbackQueue = playbackQueue,
                )
            }
        }
    }
}

@Composable
internal fun LibraryArtistsContent(
    uiState: LibraryArtistsUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState = remember(uiState.sortOption, uiState.sortOrder) { LazyListState() },
    contentPadding: PaddingValues = PaddingValues(),
    onArtistClick: ((com.exodidio.tune.sync.LibraryArtist) -> Unit)? = null,
    onFilterQueryChange: (String) -> Unit = {},
    orderedTrackIdsForArtist: (String) -> List<String> = { emptyList() },
    onArtistPlayNext: (List<String>) -> Unit = {},
    onArtistAddToQueue: (List<String>) -> Unit = {},
    onArtistUpdate: (String, android.net.Uri?, Boolean) -> Unit = { _, _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    hazeState: HazeState? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
) {
    var contextArtistId by remember { mutableStateOf<String?>(null) }
    var editingArtist by remember { mutableStateOf<com.exodidio.tune.sync.LibraryArtist?>(null) }
    val listPadding = remember(contentPadding) {
        PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
            start = 0.dp,
            end = 0.dp,
        )
    }
    LibraryVirtualList(
        items = uiState.artists,
        isLoaded = uiState.isLoaded,
        key = { artist -> artist.id },
        contentType = "artist_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        dividerTestTag = "artist-row-divider",
        filterKey = "artists",
        filterActive = uiState.filterQuery.isNotBlank(),
        filterContent = {
            LibraryTextFilter(
                value = uiState.filterQuery,
                onValueChange = onFilterQueryChange,
                placeholder = stringResource(R.string.filter_placeholder_search),
            )
        },
        alphabeticalIndexKey = if (uiState.sortOption == ArtistSortOption.Name) ({ artist -> artist.sortName.ifBlank { artist.name } }) else null,
        emptyContent = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroCard(
                    title = stringResource(if (uiState.filterQuery.isBlank()) R.string.artists_empty_title else R.string.artists_no_match_title),
                    description = stringResource(if (uiState.filterQuery.isBlank()) R.string.artists_empty_description else R.string.filter_no_match_description),
                    symbol = MaterialSymbols.People,
                )
            }
        },
    ) { artist ->
        // Track IDs are only needed by the context menu. Avoid rebuilding the
        // artist's full track/album ordering while every row is composed or
        // recomposed during scrolling.
        val trackIds = if (contextArtistId == artist.id) {
            orderedTrackIdsForArtist(artist.id)
        } else {
            emptyList()
        }
        ArtistContextMenu(
            trackIds = trackIds,
            expanded = contextArtistId == artist.id,
            onDismiss = { if (contextArtistId == artist.id) contextArtistId = null },
            onPlayNext = onArtistPlayNext,
            onAddToQueue = onArtistAddToQueue,
            onEdit = { editingArtist = artist },
            onBottomSheetRequested = onTrackContextBottomSheet,
            addToPlaylistOnly = true,
            hazeState = hazeState,
            playbackQueue = playbackQueue,
        ) {
            ArtistRow(
                name = artist.name,
                artworkPath = artist.artworkPath,
                onClick = onArtistClick?.let { callback -> { callback(artist) } },
                onLongClick = { contextArtistId = artist.id },
            )
        }
    }
    editingArtist?.let { artist ->
        EditArtistBottomSheet(
            artworkPath = artistManualArtworkPath(artist.id, uiState.artistArtworkKeys, uiState.artworkPathByKey),
            onDismiss = { editingArtist = null },
            onSave = { artwork, clearArtwork -> onArtistUpdate(artist.id, artwork, clearArtwork); editingArtist = null },
        )
    }
}
