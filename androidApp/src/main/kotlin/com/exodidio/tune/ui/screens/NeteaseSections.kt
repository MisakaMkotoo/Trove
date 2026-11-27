package com.exodidio.tune.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.MineAlbum
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.netease.NeteaseImageStore
import com.exodidio.tune.netease.NeteasePlaylistPermission
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.RADAR_DISPLAY_NAME
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.AnchoredPopupMenu
import com.exodidio.tune.ui.components.ContextActionMenu
import com.exodidio.tune.ui.components.ContextActionMenuEntry
import com.exodidio.tune.ui.components.DiscCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.theme.LocalTuneColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

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
    actionLabel: String? = null,
    actionSymbol: String? = null,
    actionContentDescription: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val colors = LocalTuneColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = topPadding, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
        if (actionSymbol != null && onActionClick != null) {
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onActionClick),
                contentAlignment = Alignment.Center,
            ) {
                MaterialSymbol(
                    symbol = actionSymbol,
                    contentDescription = actionContentDescription,
                    size = 22.dp,
                    tint = colors.primary,
                )
            }
        } else if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primary,
                modifier = Modifier.clickable(onClick = onActionClick).padding(start = 12.dp),
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
    actionLabel: String? = null,
    actionSymbol: String? = null,
    actionContentDescription: String? = null,
    onActionClick: (() -> Unit)? = null,
    onEditPlaylist: ((DiscoverPlaylist) -> Unit)? = null,
    onDeletePlaylist: ((DiscoverPlaylist) -> Unit)? = null,
    onActionError: ((String, String?) -> Unit)? = null,
    onLongClick: ((DiscoverPlaylist) -> Unit)? = null,
    errorText: String? = null,
) {
    if (playlists.isEmpty()) return
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cardWidth = (screenWidthDp * 0.40f).coerceIn(140f, 180f).dp
    var contextPlaylistId by remember { mutableStateOf<String?>(null) }
    Column {
        NeteaseSectionTitle(
            text = title,
            horizontalPadding = horizontalPadding,
            topPadding = titleTopPadding,
            onClick = onTitleClick,
            actionLabel = actionLabel,
            actionSymbol = actionSymbol,
            actionContentDescription = actionContentDescription,
            onActionClick = onActionClick,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = horizontalState,
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = playlists, key = { it.id }) { playlist ->
                NeteasePlaylistCardContextMenu(
                    playlist = playlist,
                    expanded = onLongClick == null && contextPlaylistId == playlist.id,
                    onDismiss = { if (contextPlaylistId == playlist.id) contextPlaylistId = null },
                    onEdit = onEditPlaylist?.let { edit -> { edit(playlist) } },
                    onDelete = onDeletePlaylist?.let { del -> { del(playlist) } },
                    onActionError = { err -> onActionError?.invoke(playlist.id, err) },
                ) {
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
                        onLongClick = {
                            if (onLongClick != null) onLongClick(playlist)
                            else contextPlaylistId = playlist.id
                        },
                    )
                }
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
                            favorite = true,
                            neteaseLike = true,
                            allowNeteasePlaylists = true,
                            addToPlaylist = true,
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


/**
 * 网易云歌单卡片长按锚定菜单：与长按歌曲一致的液态玻璃卡片菜单（AnchoredPopupMenu + ContextActionMenu）。
 */
@Composable
internal fun NeteasePlaylistCardContextMenu(
    playlist: DiscoverPlaylist,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    refreshLabelRes: Int = R.string.netease_refresh,
    onRefreshOverride: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onActionError: (String?) -> Unit = {},
    hazeState: HazeState? = null,
    anchor: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val permission = remember(playlist.id, mine.created, loggedIn) {
        NeteaseRepository.playlistPermission(playlist.id)
    }
    val isSubscribed = remember(playlist.id, mine.saved) {
        mine.saved.any { it.id == playlist.id }
    }
    val existingRecord = remember(playlist.id) { NeteaseRepository.playlistById(playlist.id) }
    val isDynamicSnapshot = remember(playlist.id, playlist.name, existingRecord) {
        NeteaseRepository.isDynamicSnapshotPlaylist(
            playlistId = playlist.id,
            dynamicKind = existingRecord?.dynamicKind,
            name = playlist.name,
            sourceUrl = existingRecord?.sourceUrl.orEmpty(),
        )
    }
    val showSaveSnapshot = permission != NeteasePlaylistPermission.OwnedEditable &&
        (NeteaseRepository.isDynamic(playlist.id) || existingRecord?.addedToPlaylists != true)
    val subscribedToast = stringResource(R.string.netease_playlist_subscribed)
    val unsubscribedToast = stringResource(R.string.netease_playlist_unsubscribed)
    val snapshotSavedToast = stringResource(
        if (isDynamicSnapshot) R.string.netease_saved_snapshot else R.string.netease_saved_to_playlists,
    )
    val saveFailedToast = stringResource(R.string.netease_save_failed)
    val refreshFailedToast = stringResource(R.string.netease_refresh_failed)
    val downloadNoneToast = stringResource(R.string.netease_download_none)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")
    val refreshLabel = stringResource(refreshLabelRes)
    val downloadAllLabel = stringResource(R.string.netease_download_all)
    val editLabel = stringResource(R.string.netease_playlist_edit)
    val deleteLabel = stringResource(R.string.netease_playlist_delete)
    val subscribeLabel = stringResource(
        if (isSubscribed) R.string.netease_playlist_unsubscribe else R.string.netease_playlist_subscribe,
    )
    val saveLabel = stringResource(
        if (isDynamicSnapshot) R.string.netease_save_snapshot_dynamic else R.string.netease_save_snapshot,
    )

    fun ensureStaged() {
        if (NeteaseRepository.playlistById(playlist.id) == null) {
            NeteaseRepository.stagePlaylist(
                playlistId = playlist.id,
                name = playlist.name,
                coverUrl = playlist.coverUrl,
                coverPath = playlist.coverPath,
                trackCount = playlist.trackCount,
                dynamicKind = if (playlist.name.contains(RADAR_DISPLAY_NAME)) "radar" else null,
                cacheToDisk = true,
            )
        }
    }

    AnchoredPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        width = 272.dp,
        offset = DpOffset(x = (-8).dp, y = 8.dp),
        hazeState = hazeState,
        anchor = anchor,
    ) {
        ContextActionMenu(buildList {
            add(
                ContextActionMenuEntry.Action(refreshLabel, MaterialSymbols.Refresh) {
                    onDismiss()
                    if (onRefreshOverride != null) {
                        onRefreshOverride()
                    } else {
                        scope.launch {
                            ensureStaged()
                            val res = NeteaseRepository.refresh(context, playlist.id)
                            val errMsg = res.exceptionOrNull()?.message
                            onActionError(errMsg)
                            if (errMsg != null) {
                                Toast.makeText(context, errMsg.ifBlank { refreshFailedToast }, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
            add(
                ContextActionMenuEntry.Action(downloadAllLabel, MaterialSymbols.ArrowDownward) {
                    onDismiss()
                    scope.launch {
                        ensureStaged()
                        NeteaseRepository.ensureDynamicLoaded(context, playlist.id)
                        val result = NeteaseRepository.downloadAll(context, playlist.id)
                        val msg = if (result.total == 0) {
                            downloadNoneToast
                        } else {
                            context.applicationContext.getString(
                                R.string.netease_download_result,
                                result.succeeded,
                                result.total,
                            )
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            if (permission == NeteasePlaylistPermission.ForeignSubscribable && loggedIn) {
                add(
                    ContextActionMenuEntry.Action(
                        subscribeLabel,
                        if (isSubscribed) MaterialSymbols.PlaylistRemove else MaterialSymbols.PlaylistAdd,
                    ) {
                        val nextSubscribed = !isSubscribed
                        onDismiss()
                        scope.launch {
                            NeteaseRepository.setNeteasePlaylistSubscribed(
                                context = context,
                                playlist = playlist,
                                playlistId = playlist.id,
                                subscribe = nextSubscribed,
                            ).onSuccess {
                                Toast.makeText(
                                    context,
                                    if (nextSubscribed) subscribedToast else unsubscribedToast,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    failedTemplate.replace("{err}", err.message.orEmpty()),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
            if (showSaveSnapshot) {
                add(
                    ContextActionMenuEntry.Action(saveLabel, MaterialSymbols.Library) {
                        onDismiss()
                        scope.launch {
                            ensureStaged()
                            NeteaseRepository.ensureDynamicLoaded(context, playlist.id)
                            val saved = NeteaseRepository.saveSnapshot(playlist.id)
                            if (saved == null) {
                                onActionError(saveFailedToast)
                                Toast.makeText(context, saveFailedToast, Toast.LENGTH_SHORT).show()
                            } else {
                                onActionError(null)
                                Toast.makeText(context, snapshotSavedToast, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
            if (permission == NeteasePlaylistPermission.OwnedEditable && loggedIn && (onEdit != null || onDelete != null)) {
                add(ContextActionMenuEntry.Divider)
                if (onEdit != null) {
                    add(
                        ContextActionMenuEntry.Action(editLabel, MaterialSymbols.Edit) {
                            onDismiss()
                            onEdit()
                        },
                    )
                }
                if (onDelete != null) {
                    add(
                        ContextActionMenuEntry.Action(deleteLabel, MaterialSymbols.Delete, destructive = true) {
                            onDismiss()
                            onDelete()
                        },
                    )
                }
            }
        })
    }
}

/** 网易云专辑卡片长按锚定菜单。 */
@Composable
internal fun MineAlbumCardContextMenu(
    album: MineAlbum,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    anchor: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val albumSubscribedMap by NeteaseRepository.albumSubscribedMap.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val subscribed = remember(album.id, albumSubscribedMap, mine.albums) {
        albumSubscribedMap[album.id] ?: mine.albums.any { it.id == album.id }
    }
    val openLabel = stringResource(R.string.netease_open_album_page)
    val subscribeLabel = stringResource(
        if (subscribed) R.string.netease_album_unsubscribe else R.string.netease_album_subscribe,
    )
    val subscribedToast = stringResource(R.string.netease_album_subscribed)
    val unsubscribedToast = stringResource(R.string.netease_album_unsubscribed)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")
    AnchoredPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        width = 272.dp,
        offset = DpOffset(x = (-8).dp, y = 8.dp),
        hazeState = hazeState,
        anchor = anchor,
    ) {
        ContextActionMenu(buildList {
            add(
                ContextActionMenuEntry.Action(openLabel, MaterialSymbols.Album) {
                    onDismiss()
                    onOpen()
                },
            )
            if (loggedIn) {
                add(
                    ContextActionMenuEntry.Action(
                        subscribeLabel,
                        if (subscribed) MaterialSymbols.PlaylistRemove else MaterialSymbols.PlaylistAdd,
                    ) {
                        val nextSubscribed = !subscribed
                        onDismiss()
                        scope.launch {
                            NeteaseRepository.setAlbumSubscribed(
                                context = context,
                                album = album,
                                albumId = album.id,
                                subscribe = nextSubscribed,
                            ).onSuccess {
                                Toast.makeText(
                                    context,
                                    if (nextSubscribed) subscribedToast else unsubscribedToast,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    failedTemplate.replace("{err}", err.message.orEmpty()),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
        })
    }
}

/** 网易云歌手卡片长按锚定菜单。 */
@Composable
internal fun MineArtistCardContextMenu(
    artist: MineArtist,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    anchor: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val followed = remember(artist.id, mine.artists) { mine.artists.any { it.id == artist.id } }
    val openLabel = stringResource(R.string.netease_open_artist_page)
    val followLabel = stringResource(
        if (followed) R.string.netease_artist_unfollow else R.string.netease_artist_follow,
    )
    val followedToast = stringResource(R.string.netease_artist_followed)
    val unfollowedToast = stringResource(R.string.netease_artist_unfollowed)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")
    AnchoredPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        width = 272.dp,
        offset = DpOffset(x = (-8).dp, y = 8.dp),
        hazeState = hazeState,
        anchor = anchor,
    ) {
        ContextActionMenu(buildList {
            add(
                ContextActionMenuEntry.Action(openLabel, MaterialSymbols.Person) {
                    onDismiss()
                    onOpen()
                },
            )
            if (loggedIn) {
                add(
                    ContextActionMenuEntry.Action(
                        followLabel,
                        if (followed) MaterialSymbols.HeartMinus else MaterialSymbols.HeartPlus,
                    ) {
                        val nextFollowed = !followed
                        onDismiss()
                        scope.launch {
                            NeteaseRepository.setArtistFollowed(
                                context = context,
                                artist = artist,
                                artistId = artist.id,
                                follow = nextFollowed,
                            ).onSuccess {
                                Toast.makeText(
                                    context,
                                    if (nextFollowed) followedToast else unfollowedToast,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    failedTemplate.replace("{err}", err.message.orEmpty()),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
        })
    }
}
