package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.DiscoverSquareTab
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.pressScale
import com.exodidio.tune.ui.theme.LocalTuneColors

/** 顶部分类入口：映射到发现广场的对应页签和分类。 */
private data class DiscoverCategory(
    val label: Int,
    val symbol: String,
    val tab: DiscoverSquareTab,
    val category: String? = null,
)

private val DiscoverCategories = listOf(
    DiscoverCategory(R.string.discover_category_rankings, MaterialSymbols.BarChart, DiscoverSquareTab.Rankings),
    DiscoverCategory(R.string.discover_category_playlists, MaterialSymbols.QueueMusic, DiscoverSquareTab.Playlists, "全部"),
    DiscoverCategory(R.string.discover_category_japanese, MaterialSymbols.Translate, DiscoverSquareTab.Playlists, "日语"),
    DiscoverCategory(R.string.discover_category_acg, MaterialSymbols.Palette, DiscoverSquareTab.Playlists, "ACG"),
    DiscoverCategory(R.string.discover_category_explore, MaterialSymbols.Sensors, DiscoverSquareTab.NewAlbums),
    DiscoverCategory(R.string.discover_category_vip, MaterialSymbols.Crown, DiscoverSquareTab.HighQuality, "全部"),
    DiscoverCategory(R.string.discover_category_ost, MaterialSymbols.Album, DiscoverSquareTab.Playlists, "影视原声"),
    DiscoverCategory(R.string.discover_category_rock, MaterialSymbols.GraphicEq, DiscoverSquareTab.Playlists, "摇滚"),
)

/**
 * 新发现：排行榜 / 甄选歌单 / 新歌新碟。
 * 这里只放不带个人口味的内容；推荐歌单和动态分区都在首页。
 */
@Composable
internal fun DiscoverContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    rowStates: NeteaseRowStates = remember { NeteaseRowStates() },
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenPlaylist: (DiscoverPlaylist) -> Unit = {},
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenSquare: (DiscoverSquareTab, String?) -> Unit = { _, _ -> },
) {
    val colors = LocalTuneColors.current
    val data by NeteaseRepository.discover.collectAsStateWithLifecycle()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { NeteaseRepository.loadDiscover() }
    LaunchedEffect(loggedIn) { if (loggedIn) NeteaseRepository.loadMine() }

    // 歌曲封面是后台补齐的：按 trackId 合并 store 里的最新封面，晚到也会自动出现。
    val playbackOnlyTracks by AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val songs = remember(data.newSongs, playbackOnlyTracks) {
        if (playbackOnlyTracks.isEmpty()) {
            data.newSongs
        } else {
            val artworkById = playbackOnlyTracks.associate { it.id to it.artworkPath }
            data.newSongs.map { track ->
                val artwork = artworkById[track.id]
                if (artwork != null && artwork != track.artworkPath) track.copy(artworkPath = artwork) else track
            }
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
        item("discover_title") {
            Text(
                text = stringResource(R.string.destination_discover),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 34.sp,
                    lineHeight = 41.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = colors.textMain,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            )
        }
        item("discover_categories") {
            DiscoverCategoryGrid(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                onCategoryClick = { category -> onOpenSquare(category.tab, category.category) },
            )
        }
        if (!data.loaded) {
            item("discover_skeleton_rankings") { NeteasePlaylistRowSkeleton() }
            item("discover_skeleton_curated") { NeteasePlaylistRowSkeleton() }
            item("discover_skeleton_songs") { NeteaseSongGridSkeleton() }
        } else {
            discoverPlaylistSection(
                key = "rankings",
                title = R.string.discover_section_rankings,
                items = data.rankings,
                error = data.rankingsError,
                fallbackSubtitle = R.string.discover_ranking_subtitle,
                horizontalState = rowStates.state("discover_rankings"),
                onOpen = onOpenPlaylist,
            )
            if (!loggedIn && data.curated.isEmpty()) {
                // 甄选来自整页块，未登录拿不到：给登录入口，不显示报错。
                item("discover_curated_login") {
                    NeteaseSectionTitle(stringResource(R.string.discover_section_curated))
                    DiscoverLoginGuide(onOpenAccount)
                }
            } else {
                discoverPlaylistSection(
                    key = "curated",
                    title = R.string.discover_section_curated,
                    items = data.curated,
                    error = data.curatedError,
                    fallbackSubtitle = R.string.discover_curated_subtitle,
                    horizontalState = rowStates.state("discover_curated"),
                    onOpen = onOpenPlaylist,
                )
            }
            if (songs.isNotEmpty()) {
                item("discover_new_songs") {
                    NeteaseSongGridSection(
                        title = stringResource(R.string.discover_section_new_songs),
                        songs = songs,
                        playbackQueue = playbackQueue,
                        onPlayTracks = onPlayTracks,
                        onTrackPlayNext = onTrackPlayNext,
                        onTrackAddToQueue = onTrackAddToQueue,
                        onTrackFavoriteToggle = onTrackFavoriteToggle,
                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                        horizontalState = rowStates.gridState("discover_songs"),
                    )
                }
            } else if (data.newSongsError != null) {
                item("discover_new_songs_error") {
                    DiscoverHint(stringResource(R.string.discover_error, data.newSongsError.orEmpty()))
                }
            } else {
                item("discover_new_songs_empty") {
                    DiscoverHint(stringResource(R.string.discover_new_songs_empty))
                }
            }
        }
    }
}

private fun LazyListScope.discoverPlaylistSection(
    key: String,
    title: Int,
    items: List<DiscoverPlaylist>,
    error: String?,
    fallbackSubtitle: Int,
    horizontalState: LazyListState,
    onOpen: (DiscoverPlaylist) -> Unit,
) {
    if (items.isEmpty() && error == null) return
    if (items.isEmpty()) {
        item("discover_${key}_title") { NeteaseSectionTitle(stringResource(title)) }
        item("discover_${key}_error") {
            DiscoverHint(stringResource(R.string.discover_error, error.orEmpty()))
        }
        return
    }
    item("discover_${key}_row") {
        NeteasePlaylistCardRow(
            title = stringResource(title),
            playlists = items,
            fallbackSubtitle = stringResource(fallbackSubtitle),
            onOpen = onOpen,
            horizontalState = horizontalState,
        )
    }
}

@Composable
private fun DiscoverHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalTuneColors.current.textMuted,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

@Composable
private fun DiscoverLoginGuide(onOpenAccount: () -> Unit) {
    com.exodidio.tune.ui.components.Card(Modifier.padding(horizontal = 24.dp)) {
        com.exodidio.tune.ui.components.ActionList(
            items = listOf(
                com.exodidio.tune.ui.components.ActionListItem(
                    labelRes = R.string.discover_login_curated,
                    leadingSymbol = MaterialSymbols.Person,
                    onClick = onOpenAccount,
                ),
            ),
            containerStyle = com.exodidio.tune.ui.components.ActionListContainerStyle.Plain,
        )
    }
}

/** 2×4 分类入口：直接进入发现广场对应页签与分类。 */
@Composable
private fun DiscoverCategoryGrid(
    modifier: Modifier = Modifier,
    onCategoryClick: (DiscoverCategory) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DiscoverCategories.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { category ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(interactionSource, pressedScale = 0.95f)
                            .height(40.dp)
                            .clip(shape)
                            .background(colors.glassElevated, shape)
                            .border(0.5.dp, colors.borderGlass, shape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) { onCategoryClick(category) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MaterialSymbol(
                            symbol = category.symbol,
                            size = 16.dp,
                            tint = colors.textMain,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(category.label),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = colors.textMain,
                            maxLines = 1,
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
