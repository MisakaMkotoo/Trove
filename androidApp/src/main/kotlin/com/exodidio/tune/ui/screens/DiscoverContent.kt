package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.RADAR_DISPLAY_NAME
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.theme.LocalTuneColors

/** 顶部分类入口：只做样式，内容后续再设计。 */
private data class DiscoverCategory(val label: Int, val symbol: String)

private val DiscoverCategories = listOf(
    DiscoverCategory(R.string.discover_category_rankings, MaterialSymbols.BarChart),
    DiscoverCategory(R.string.discover_category_playlists, MaterialSymbols.QueueMusic),
    DiscoverCategory(R.string.discover_category_japanese, MaterialSymbols.Translate),
    DiscoverCategory(R.string.discover_category_acg, MaterialSymbols.Palette),
    DiscoverCategory(R.string.discover_category_explore, MaterialSymbols.Sensors),
    DiscoverCategory(R.string.discover_category_vip, MaterialSymbols.Crown),
    DiscoverCategory(R.string.discover_category_ost, MaterialSymbols.Album),
    DiscoverCategory(R.string.discover_category_rock, MaterialSymbols.GraphicEq),
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
) {
    val colors = LocalTuneColors.current
    val data by NeteaseRepository.discover.collectAsStateWithLifecycle()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var actionTarget by remember { mutableStateOf<PlaylistActionTarget?>(null) }
    LaunchedEffect(Unit) { NeteaseRepository.loadDiscover() }

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
            DiscoverCategoryGrid(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
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
                onLongClick = { playlist ->
                    actionTarget = PlaylistActionTarget(playlist.id, playlist.name, playlist.coverUrl, playlist.coverPath)
                },
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
                    onLongClick = { playlist ->
                        actionTarget = PlaylistActionTarget(playlist.id, playlist.name, playlist.coverUrl, playlist.coverPath)
                    },
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
    actionTarget?.let { target ->
        NeteasePlaylistActionSheet(
            target = target,
            onDismiss = { actionTarget = null },
            onRefresh = {
                actionTarget = null
                scope.launch {
                    stageActionTarget(target)
                    NeteaseRepository.refresh(context, target.id)
                }
            },
            onDownloadAll = {
                actionTarget = null
                scope.launch {
                    stageActionTarget(target)
                    NeteaseRepository.ensureDynamicLoaded(context, target.id)
                    NeteaseRepository.downloadAll(context, target.id)
                }
            },
            onSave = {
                actionTarget = null
                scope.launch {
                    stageActionTarget(target)
                    NeteaseRepository.saveSnapshot(target.id)
                }
            },
        )
    }
}

private fun stageActionTarget(target: PlaylistActionTarget) {
    if (NeteaseRepository.playlistById(target.id) == null) {
        NeteaseRepository.stagePlaylist(
            playlistId = target.id,
            name = target.name,
            coverUrl = target.coverUrl,
            coverPath = target.coverPath,
            trackCount = 0,
            dynamicKind = if (target.name.contains(RADAR_DISPLAY_NAME)) "radar" else null,
        )
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
    onLongClick: (DiscoverPlaylist) -> Unit,
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
            onLongClick = onLongClick,
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

/** 2×4 分类入口：自制样式（我们没有其他分类页），点击暂不进入内容。 */
@Composable
private fun DiscoverCategoryGrid(modifier: Modifier = Modifier) {
    val colors = LocalTuneColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DiscoverCategories.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { category ->
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(colors.glassElevated, shape)
                            .border(0.5.dp, colors.borderGlass, shape)
                            .clickable { },
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
