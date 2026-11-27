package com.exodidio.tune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.DiscoverSquareTab
import com.exodidio.tune.netease.MineAlbum
import com.exodidio.tune.netease.NeteaseArtistAlbumItem
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.NeteaseTopArtistItem
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.components.DiscCard
import com.exodidio.tune.ui.components.LocalControlStyle
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneRemoteImage
import com.exodidio.tune.ui.components.TuneSegmentedTabBar
import com.exodidio.tune.ui.components.pressScale
import com.exodidio.tune.ui.theme.LocalTuneColors

private val AlbumAreaFilters = listOf(
    "ALL" to R.string.discover_square_area_all,
    "ZH" to R.string.discover_square_area_zh,
    "EA" to R.string.discover_square_area_ea,
    "KR" to R.string.discover_square_area_kr,
    "JP" to R.string.discover_square_area_jp,
)

/** 发现广场：歌单广场 / 精品歌单 / 排行榜 / 新碟上架 / 热门歌手。 */
@Composable
internal fun DiscoverSquareContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    onOpenPlaylist: (DiscoverPlaylist) -> Unit = {},
    onOpenAlbum: (albumId: Long, name: String, coverUrl: String) -> Unit = { _, _, _ -> },
    onOpenArtist: (artistId: Long, name: String, coverUrl: String) -> Unit = { _, _, _ -> },
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val state by NeteaseRepository.discoverSquare.collectAsStateWithLifecycle()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    var contextPlaylistId by remember { mutableStateOf<String?>(null) }
    var contextAlbumId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        NeteaseRepository.selectSquareTab(state.activeTab)
    }
    LaunchedEffect(loggedIn) {
        if (loggedIn) NeteaseRepository.loadMine()
    }

    val nearEnd by remember(listState, state.activeTab) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 4
        }
    }
    LaunchedEffect(
        nearEnd,
        state.activeTab,
        state.playlistsHasMore,
        state.playlistsLoadingMore,
        state.highQualityHasMore,
        state.highQualityLoadingMore,
        state.newAlbumsHasMore,
        state.newAlbumsLoadingMore,
        state.topArtistsHasMore,
        state.topArtistsLoadingMore,
    ) {
        if (!nearEnd) return@LaunchedEffect
        when (state.activeTab) {
            DiscoverSquareTab.Playlists -> if (state.playlistsHasMore && !state.playlistsLoadingMore) {
                NeteaseRepository.loadMoreSquareTopPlaylists()
            }
            DiscoverSquareTab.HighQuality -> if (state.highQualityHasMore && !state.highQualityLoadingMore) {
                NeteaseRepository.loadMoreSquareHighQualityPlaylists()
            }
            DiscoverSquareTab.NewAlbums -> if (state.newAlbumsHasMore && !state.newAlbumsLoadingMore) {
                NeteaseRepository.loadMoreSquareNewAlbums()
            }
            DiscoverSquareTab.TopArtists -> if (state.topArtistsHasMore && !state.topArtistsLoadingMore) {
                NeteaseRepository.loadMoreSquareTopArtists()
            }
            DiscoverSquareTab.Rankings -> Unit
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item("square_title") {
            Text(
                text = stringResource(R.string.discover_square_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textMain,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item("square_tabs") {
            val tabs = listOf(
                DiscoverSquareTab.Playlists to R.string.discover_square_tab_playlists,
                DiscoverSquareTab.HighQuality to R.string.discover_square_tab_high_quality,
                DiscoverSquareTab.Rankings to R.string.discover_square_tab_rankings,
                DiscoverSquareTab.NewAlbums to R.string.discover_square_tab_new_albums,
                DiscoverSquareTab.TopArtists to R.string.discover_square_tab_top_artists,
            )
            val tabLabels = tabs.map { stringResource(it.second) }
            val selectedTabIndex = tabs.indexOfFirst { it.first == state.activeTab }.coerceAtLeast(0)
            TuneSegmentedTabBar(
                tabs = tabLabels,
                selectedIndex = selectedTabIndex,
                onSelect = { index ->
                    tabs.getOrNull(index)?.let { (tab, _) -> NeteaseRepository.selectSquareTab(tab) }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        item("square_categories") {
            AnimatedVisibility(
                visible = (state.activeTab == DiscoverSquareTab.Playlists || state.activeTab == DiscoverSquareTab.HighQuality) &&
                    state.categories.isNotEmpty(),
                enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(180)) + expandVertically(tween(200, easing = FastOutSlowInEasing)),
                exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(140)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing)),
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.categories, key = { it.name }) { cat ->
                        SquareFilterChip(
                            label = cat.name,
                            selected = state.selectedCategory == cat.name,
                            onClick = { NeteaseRepository.selectSquareCategory(cat.name) },
                        )
                    }
                }
            }
        }

        item("square_album_areas") {
            AnimatedVisibility(
                visible = state.activeTab == DiscoverSquareTab.NewAlbums,
                enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(180)) + expandVertically(tween(200, easing = FastOutSlowInEasing)),
                exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(140)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing)),
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(AlbumAreaFilters, key = { it.first }) { (area, labelRes) ->
                        SquareFilterChip(
                            label = stringResource(labelRes),
                            selected = state.newAlbumsArea == area,
                            onClick = { NeteaseRepository.selectNewAlbumsArea(area) },
                        )
                    }
                }
            }
        }

        when (state.activeTab) {
            DiscoverSquareTab.Playlists -> {
                playlistSquareBody(
                    items = state.playlists,
                    loading = state.playlistsLoading,
                    loadingMore = state.playlistsLoadingMore,
                    hasMore = state.playlistsHasMore,
                    error = state.playlistsError,
                    contextPlaylistId = contextPlaylistId,
                    onContextPlaylistChange = { contextPlaylistId = it },
                    onOpenPlaylist = onOpenPlaylist,
                    onLoadMore = { NeteaseRepository.loadMoreSquareTopPlaylists() },
                )
            }
            DiscoverSquareTab.HighQuality -> {
                playlistSquareBody(
                    items = state.highQuality,
                    loading = state.highQualityLoading,
                    loadingMore = state.highQualityLoadingMore,
                    hasMore = state.highQualityHasMore,
                    error = state.highQualityError,
                    contextPlaylistId = contextPlaylistId,
                    onContextPlaylistChange = { contextPlaylistId = it },
                    onOpenPlaylist = onOpenPlaylist,
                    onLoadMore = { NeteaseRepository.loadMoreSquareHighQualityPlaylists() },
                )
            }
            DiscoverSquareTab.Rankings -> {
                playlistSquareBody(
                    items = state.rankings,
                    loading = state.rankingsLoading,
                    loadingMore = false,
                    hasMore = false,
                    error = state.rankingsError,
                    contextPlaylistId = contextPlaylistId,
                    onContextPlaylistChange = { contextPlaylistId = it },
                    onOpenPlaylist = onOpenPlaylist,
                    onLoadMore = {},
                )
            }
            DiscoverSquareTab.NewAlbums -> {
                when {
                    state.newAlbumsLoading && state.newAlbums.isEmpty() -> item("albums_loading") {
                        SquareLoadingIndicator()
                    }
                    state.newAlbums.isEmpty() -> item("albums_empty") {
                        SquareEmptyHint(state.newAlbumsError ?: stringResource(R.string.discover_square_empty))
                    }
                    else -> {
                        val rows = state.newAlbums.chunked(2)
                        items(rows, key = { row -> row.joinToString("_") { it.id } }) { row ->
                            val itemAnim = if (reduceMotion) Modifier else Modifier.animateItem(
                                fadeInSpec = tween(200, easing = FastOutSlowInEasing),
                                placementSpec = null,
                                fadeOutSpec = null,
                            )
                            SquareTwoColumnRow(
                                first = row[0],
                                second = row.getOrNull(1),
                                modifier = itemAnim,
                            ) { album ->
                                val mineAlbum = MineAlbum(
                                    id = album.id,
                                    name = album.name,
                                    artistName = album.artistName,
                                    coverUrl = album.coverUrl,
                                    trackCount = album.trackCount,
                                    subTimeMs = 0L,
                                )
                                MineAlbumCardContextMenu(
                                    album = mineAlbum,
                                    expanded = contextAlbumId == album.id,
                                    onDismiss = { if (contextAlbumId == album.id) contextAlbumId = null },
                                    onOpen = {
                                        album.id.toLongOrNull()?.let { id ->
                                            onOpenAlbum(id, album.name, album.coverUrl)
                                        }
                                    },
                                ) {
                                    AlbumSquareCard(
                                        album = album,
                                        onClick = {
                                            album.id.toLongOrNull()?.let { id ->
                                                onOpenAlbum(id, album.name, album.coverUrl)
                                            }
                                        },
                                        onLongClick = { contextAlbumId = album.id },
                                    )
                                }
                            }
                        }
                        if (state.newAlbumsLoadingMore) {
                            item("albums_loading_more") { SquareLoadingMoreFooter() }
                        } else if (state.newAlbumsHasMore) {
                            item("albums_load_more") {
                                SquareLoadMoreButton { NeteaseRepository.loadMoreSquareNewAlbums() }
                            }
                        }
                    }
                }
            }
            DiscoverSquareTab.TopArtists -> {
                when {
                    state.topArtistsLoading && state.topArtists.isEmpty() -> item("artists_loading") {
                        SquareLoadingIndicator()
                    }
                    state.topArtists.isEmpty() -> item("artists_empty") {
                        SquareEmptyHint(state.topArtistsError ?: stringResource(R.string.discover_square_empty))
                    }
                    else -> {
                        items(state.topArtists, key = { it.id }) { artist ->
                            val itemAnim = if (reduceMotion) Modifier else Modifier.animateItem(
                                fadeInSpec = tween(200, easing = FastOutSlowInEasing),
                                placementSpec = null,
                                fadeOutSpec = null,
                            )
                            TopArtistRow(
                                rank = state.topArtists.indexOf(artist) + 1,
                                artist = artist,
                                modifier = itemAnim,
                                onClick = {
                                    artist.id.toLongOrNull()?.let { id ->
                                        onOpenArtist(id, artist.name, artist.coverUrl)
                                    }
                                },
                            )
                            HorizontalDivider(
                                color = colors.borderGlass,
                                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                            )
                        }
                        if (state.topArtistsLoadingMore) {
                            item("artists_loading_more") { SquareLoadingMoreFooter() }
                        } else if (state.topArtistsHasMore) {
                            item("artists_load_more") {
                                SquareLoadMoreButton { NeteaseRepository.loadMoreSquareTopArtists() }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlistSquareBody(
    items: List<DiscoverPlaylist>,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    contextPlaylistId: String?,
    onContextPlaylistChange: (String?) -> Unit,
    onOpenPlaylist: (DiscoverPlaylist) -> Unit,
    onLoadMore: () -> Unit,
) {
    when {
        loading && items.isEmpty() -> item("sq_loading") { SquareLoadingIndicator() }
        items.isEmpty() -> item("sq_empty") {
            SquareEmptyHint(error ?: stringResource(R.string.discover_square_empty))
        }
        else -> {
            val rows = items.chunked(2)
            items(rows, key = { row -> row.joinToString("_") { it.id } }) { row ->
                val reduceMotion = LocalReduceMotion.current
                val itemAnim = if (reduceMotion) Modifier else Modifier.animateItem(
                    fadeInSpec = tween(200, easing = FastOutSlowInEasing),
                    placementSpec = null,
                    fadeOutSpec = null,
                )
                SquareTwoColumnRow(
                    first = row[0],
                    second = row.getOrNull(1),
                    modifier = itemAnim,
                ) { playlist ->
                    val subtitle = playlist.subtitle.ifBlank {
                        if (playlist.trackCount > 0) stringResource(R.string.netease_track_count, playlist.trackCount)
                        else stringResource(R.string.netease_playlists_title)
                    }
                    NeteasePlaylistCardContextMenu(
                        playlist = playlist,
                        expanded = contextPlaylistId == playlist.id,
                        onDismiss = { if (contextPlaylistId == playlist.id) onContextPlaylistChange(null) },
                    ) {
                        DiscCard(
                            title = playlist.name,
                            subtitle = subtitle,
                            artworkPath = playlist.coverPath,
                            artworkUrl = playlist.coverUrl,
                            fallbackSymbol = MaterialSymbols.QueueMusic,
                            artworkShape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenPlaylist(playlist) },
                            onLongClick = { onContextPlaylistChange(playlist.id) },
                        )
                    }
                }
            }
            if (loadingMore) {
                item("sq_loading_more") { SquareLoadingMoreFooter() }
            } else if (hasMore) {
                item("sq_load_more") { SquareLoadMoreButton(onLoadMore) }
            }
        }
    }
}

@Composable
private fun <T> SquareTwoColumnRow(
    first: T,
    second: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.weight(1f)) { content(first) }
        if (second != null) {
            Box(Modifier.weight(1f)) { content(second) }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AlbumSquareCard(
    album: NeteaseArtistAlbumItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val subtitle = album.artistName.ifBlank {
        if (album.trackCount > 0) stringResource(R.string.netease_track_count, album.trackCount)
        else stringResource(R.string.album_details_title)
    }
    DiscCard(
        title = album.name,
        subtitle = subtitle,
        artworkUrl = album.coverUrl,
        fallbackSymbol = MaterialSymbols.Album,
        artworkShape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun TopArtistRow(
    rank: Int,
    artist: NeteaseTopArtistItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressTint by animateColorAsState(
        targetValue = if (pressed && !reduceMotion) colors.textMain.copy(alpha = 0.05f) else Color.Transparent,
        animationSpec = if (reduceMotion) snap() else tween(140, easing = FastOutSlowInEasing),
        label = "top-artist-press-tint",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(pressTint)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = if (rank <= 3) colors.primary else colors.textMuted,
            modifier = Modifier.padding(end = 12.dp),
        )
        TuneRemoteImage(
            url = artist.coverUrl,
            sizePx = 300,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, CircleShape),
            fallback = {
                MaterialSymbol(symbol = MaterialSymbols.Person, contentDescription = null, size = 24.dp, tint = colors.textMuted)
            },
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.musicSize > 0 || artist.albumSize > 0) {
                Text(
                    text = stringResource(R.string.netease_artist_counts, artist.musicSize, artist.albumSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun SquareFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val isLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass && !reduceMotion
    val interactionSource = remember { MutableInteractionSource() }
    val colorSpec = if (reduceMotion) snap<Color>() else tween(180, easing = FastOutSlowInEasing)
    val bgColor by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.16f) else colors.glassElevated,
        animationSpec = colorSpec,
        label = "square-chip-bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.45f) else colors.borderGlass,
        animationSpec = colorSpec,
        label = "square-chip-border",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.textMain,
        animationSpec = colorSpec,
        label = "square-chip-text",
    )
    val shape = if (isLiquidGlass) CircleShape else RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .pressScale(interactionSource, pressedScale = 0.95f)
            .clip(shape)
            .background(bgColor)
            .border(0.5.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SquareLoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
    }
}

@Composable
private fun SquareEmptyHint(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalTuneColors.current.textMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
    )
}

@Composable
private fun SquareLoadingMoreFooter() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun SquareLoadMoreButton(onClick: () -> Unit) {
    val colors = LocalTuneColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(shape)
            .background(colors.glassElevated)
            .border(0.5.dp, colors.borderGlass, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.discover_square_load_more),
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
        )
    }
}
