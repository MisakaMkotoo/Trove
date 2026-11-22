package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.NeteaseImageStore
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.DiscCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.theme.LocalTuneColors

private val SongColumnMaxWidth = 320.dp
private val SongGridHeight = 183.dp

/**
 * 跨页面保留的横向滚动状态：放在 AppDestinationContent 里，进详情页再返回不会丢；
 * 只有进程退出才重置（不持久化）。
 */
internal class NeteaseRowStates {
    private val listStates = mutableMapOf<String, LazyListState>()
    private val gridStates = mutableMapOf<String, LazyGridState>()

    fun state(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }

    fun gridState(key: String): LazyGridState = gridStates.getOrPut(key) { LazyGridState() }
}

/** 分区标题：首页/新发现共用（22sp 粗体）；传入 onClick 时整行可点并显示箭头。 */
@Composable
internal fun NeteaseSectionTitle(
    text: String,
    horizontalPadding: Dp = 24.dp,
    topPadding: Dp = 28.dp,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalTuneColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = horizontalPadding, end = horizontalPadding, top = topPadding, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            color = colors.textMain,
        )
        if (onClick != null) {
            MaterialSymbol(
                symbol = MaterialSymbols.ChevronRight,
                contentDescription = null,
                size = 18.dp,
                tint = colors.textMuted,
            )
        }
    }
}

/** 歌单横滑行：首页小歌单控件（约 40% 屏宽、7dp 封面圆角、17/15sp 文案）。 */
@Composable
internal fun NeteasePlaylistCardRow(
    title: String,
    playlists: List<DiscoverPlaylist>,
    fallbackSubtitle: String,
    onOpen: (DiscoverPlaylist) -> Unit,
    horizontalPadding: Dp = 24.dp,
    titleTopPadding: Dp = 28.dp,
    horizontalState: LazyListState = rememberLazyListState(),
    onTitleClick: (() -> Unit)? = null,
    onLongClick: ((DiscoverPlaylist) -> Unit)? = null,
    errorText: String? = null,
) {
    if (playlists.isEmpty()) return
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cardWidth = (screenWidthDp * 0.40f).coerceIn(140f, 180f).dp
    Column {
        NeteaseSectionTitle(title, horizontalPadding, titleTopPadding, onClick = onTitleClick)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = horizontalState,
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = playlists, key = { it.id }) { playlist ->
                DiscCard(
                    title = playlist.name,
                    subtitle = playlist.subtitle.ifBlank {
                        if (playlist.trackCount > 0) {
                            stringResource(R.string.netease_playlist_entry_count, playlist.trackCount)
                        } else {
                            fallbackSubtitle
                        }
                    },
                    artworkPath = playlist.coverPath,
                    artworkUrl = playlist.coverUrl,
                    fallbackSymbol = MaterialSymbols.QueueMusic,
                    artworkShape = RoundedCornerShape(7.dp),
                    titleStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    subtitleStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    modifier = Modifier.width(cardWidth),
                    onClick = { onOpen(playlist) },
                    onLongClick = onLongClick?.let { callback -> { callback(playlist) } },
                )
            }
        }
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = LocalTuneColors.current.textMuted,
                modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp),
            )
        }
    }
}

/** 歌曲分区：与搜索页一致，固定三行、横向翻页。 */
@Composable
internal fun NeteaseSongGridSection(
    title: String,
    songs: List<LibraryTrack>,
    playbackQueue: PlaybackQueueSnapshot,
    onPlayTracks: (List<String>, Int) -> Unit,
    onTrackPlayNext: (LibraryTrack) -> Unit,
    onTrackAddToQueue: (LibraryTrack) -> Unit,
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit,
    horizontalPadding: Dp = 24.dp,
    titleTopPadding: Dp = 28.dp,
    horizontalState: LazyGridState = rememberLazyGridState(),
    onTitleClick: (() -> Unit)? = null,
) {
    if (songs.isEmpty()) return
    val colors = LocalTuneColors.current
    var contextTrack by remember { mutableStateOf<LibraryTrack?>(null) }
    val songIds = remember(songs) { songs.map { it.id } }
    Column {
        NeteaseSectionTitle(title, horizontalPadding, titleTopPadding, onClick = onTitleClick)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().height(SongGridHeight),
            state = horizontalState,
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItemsIndexed(
                items = songs,
                key = { _, track -> "section_song_${track.id}" },
            ) { index, track ->
                Box(Modifier.widthIn(max = SongColumnMaxWidth)) {
                    TrackContextMenu(
                        track = track,
                        expanded = contextTrack?.id == track.id,
                        onDismiss = { contextTrack = null },
                        playbackQueue = playbackQueue,
                        onPlayNext = onTrackPlayNext,
                        onAddToQueue = onTrackAddToQueue,
                        onFavoriteChange = onTrackFavoriteToggle,
                        onBottomSheetRequested = onTrackContextBottomSheet,
                        actions = TrackContextMenuActions(
                            favorite = false,
                            addToPlaylist = false,
                            goToAlbum = true,
                            goToArtists = true,
                            hide = false,
                        ),
                    ) {
                        Column {
                            TrackRow(
                                track.title,
                                track.artists,
                                artworkPath = track.artworkPath,
                                artworkUrl = NeteaseRepository.coverUrlForTrack(track),
                                contentPadding = PaddingValues(vertical = 6.dp),
                                onClick = { if (index in songIds.indices) onPlayTracks(songIds, index) },
                                onMoreClick = { contextTrack = track },
                                onLongClick = { contextTrack = track },
                            )
                            if (trackHasDivider(index, songs.lastIndex)) {
                                HorizontalDivider(
                                    color = colors.borderGlass,
                                    modifier = Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- 加载占位：容器与真实卡片同尺寸，数据到达后原位替换 ----

@Composable
private fun SkeletonBlock(modifier: Modifier, corner: Dp = 6.dp) {
    // 玻璃底色在浅色背景上几乎看不见；占位用文字色的低透明度，浅/深色下都清晰。
    Box(
        modifier.clip(RoundedCornerShape(corner))
            .background(LocalTuneColors.current.textMuted.copy(alpha = 0.14f)),
    )
}

@Composable
internal fun NeteaseSectionTitleSkeleton(width: Dp = 120.dp, horizontalPadding: Dp = 24.dp, topPadding: Dp = 28.dp) {
    SkeletonBlock(
        modifier = Modifier.padding(start = horizontalPadding, top = topPadding, bottom = 14.dp).width(width).height(26.dp),
        corner = 8.dp,
    )
}

@Composable
internal fun NeteasePlaylistRowSkeleton(horizontalPadding: Dp = 24.dp, titleTopPadding: Dp = 28.dp) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cardWidth = (screenWidthDp * 0.40f).coerceIn(140f, 180f).dp
    Column {
        NeteaseSectionTitleSkeleton(horizontalPadding = horizontalPadding, topPadding = titleTopPadding)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false,
        ) {
            items(4) {
                Column(Modifier.width(cardWidth)) {
                    SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), corner = 7.dp)
                    SkeletonBlock(Modifier.padding(top = 8.dp).fillMaxWidth(0.85f).height(16.dp), corner = 4.dp)
                    SkeletonBlock(Modifier.padding(top = 5.dp).fillMaxWidth(0.6f).height(14.dp), corner = 4.dp)
                }
            }
        }
    }
}

@Composable
internal fun NeteaseSongGridSkeleton(horizontalPadding: Dp = 24.dp, titleTopPadding: Dp = 28.dp) {
    Column {
        NeteaseSectionTitleSkeleton(horizontalPadding = horizontalPadding, topPadding = titleTopPadding)
        SkeletonBlock(
            modifier = Modifier.padding(horizontal = horizontalPadding).fillMaxWidth().height(SongGridHeight),
            corner = 12.dp,
        )
    }
}

/** 按需缓存网易云图片（歌单/歌手/头像），只加载当前组合出来的行。 */
@Composable
internal fun rememberNeteaseImagePath(url: String, enabled: Boolean = true, sizePx: Int = 400): String? {
    if (!enabled || url.isBlank()) return null
    val context = LocalContext.current
    val sized = com.exodidio.tune.ui.components.tuneSizedUrl(url, sizePx)
    var path by remember(sized) {
        mutableStateOf(NeteaseImageStore.cached(context, sized)?.absolutePath)
    }
    LaunchedEffect(sized, enabled) {
        if (enabled && path == null) {
            path = NeteaseImageStore.ensure(context, sized, NeteaseRepository.cookie(context))
                .await()
                ?.absolutePath
        }
    }
    return path
}


/** 首页/新发现歌单长按菜单的目标。 */
internal data class PlaylistActionTarget(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val coverPath: String? = null,
)

/** 首页/新发现歌单长按菜单：刷新、下载全部、保存到播放列表。 */
@Composable
internal fun NeteasePlaylistActionSheet(
    target: PlaylistActionTarget,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onDownloadAll: () -> Unit,
    onSave: () -> Unit,
) {
    TuneBottomSheet(
        title = { Text(target.name, color = LocalTuneColors.current.textMain) },
        onDismiss = onDismiss,
    ) {
        ActionList(
            items = listOf(
                ActionListItem(
                    labelRes = R.string.netease_refresh,
                    leadingSymbol = MaterialSymbols.Refresh,
                    onClick = onRefresh,
                ),
                ActionListItem(
                    labelRes = R.string.netease_download_all,
                    leadingSymbol = MaterialSymbols.ArrowDownward,
                    onClick = onDownloadAll,
                ),
                ActionListItem(
                    labelRes = R.string.netease_save_snapshot,
                    leadingSymbol = MaterialSymbols.FavoriteBorder,
                    onClick = onSave,
                ),
            ),
            containerStyle = ActionListContainerStyle.Plain,
        )
    }
}
