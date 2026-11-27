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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.library.SmartPlaylistSpec
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LibraryTextFilterSlot
import com.exodidio.tune.ui.components.LibraryVirtualList
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.PlaylistRow
import com.exodidio.tune.ui.components.PlaylistContextMenu
import com.exodidio.tune.ui.components.TuneDialog
import com.exodidio.tune.ui.components.TunePillButton
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.TuneSegmentedTabBar
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneColors
import kotlinx.coroutines.launch

/**
 * 合并模式下的“播放列表”页面：固定 3 个 Tab，从左到右为：
 * 0 = 自建歌单，1 = 收藏歌单，2 = 原播放列表。
 */
@Composable
internal fun MergedLibraryPlaylistsContent(
    uiState: LibraryPlaylistsUiState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    mineCreatedListState: LazyListState = rememberLazyListState(),
    mineSavedListState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    onPlaylistClick: (String) -> Unit = {},
    onPlaylistPlayNext: (List<String>) -> Unit = {},
    onPlaylistAddToQueue: (List<String>) -> Unit = {},
    onPlaylistUpdate: (String, String, android.net.Uri?, Boolean) -> Unit = { _, _, _, _ -> },
    onPlaylistDelete: (String) -> Unit = {},
    onSmartPlaylistCreate: (String, SmartPlaylistSpec) -> Unit = { _, _ -> },
    onSmartPlaylistUpdate: (String, String, SmartPlaylistSpec) -> Unit = { _, _, _ -> },
    onExportM3u: (String) -> Unit = {},
    onPlaylistDeduplicate: (String) -> Int = { 0 },
    onNeteasePlaylistClick: (String) -> Unit = {},
    onOpenMinePlaylist: (DiscoverPlaylist) -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    val safeTab = selectedTab.coerceIn(0, 2)
    val childContentPadding = remember(contentPadding) {
        PaddingValues(top = 0.dp, bottom = contentPadding.calculateBottomPadding())
    }
    Column(modifier = modifier.fillMaxSize()) {
        TuneSegmentedTabBar(
            tabs = listOf(
                stringResource(R.string.mine_created_title),
                stringResource(R.string.mine_saved_title),
                stringResource(R.string.library_playlists_original_tab),
            ),
            selectedIndex = safeTab,
            onSelect = onTabSelected,
            modifier = Modifier.padding(
                start = 24.dp,
                end = 24.dp,
                top = contentPadding.calculateTopPadding() + 4.dp,
                bottom = 8.dp,
            ),
            tabTestTagPrefix = "merged-playlists-tab",
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
            label = "merged-playlists-tab-content",
        ) { tab ->
            when (tab) {
                0 -> MinePlaylistsContent(
                    saved = false,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = childContentPadding,
                    listState = mineCreatedListState,
                    onOpenPlaylist = onOpenMinePlaylist,
                )
                1 -> MinePlaylistsContent(
                    saved = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = childContentPadding,
                    listState = mineSavedListState,
                    onOpenPlaylist = onOpenMinePlaylist,
                )
                else -> LibraryPlaylistsContent(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                    listState = listState,
                    contentPadding = childContentPadding,
                    onPlaylistClick = onPlaylistClick,
                    onPlaylistPlayNext = onPlaylistPlayNext,
                    onPlaylistAddToQueue = onPlaylistAddToQueue,
                    onPlaylistUpdate = onPlaylistUpdate,
                    onPlaylistDelete = onPlaylistDelete,
                    onSmartPlaylistCreate = onSmartPlaylistCreate,
                    onSmartPlaylistUpdate = onSmartPlaylistUpdate,
                    onExportM3u = onExportM3u,
                    onPlaylistDeduplicate = onPlaylistDeduplicate,
                    onNeteasePlaylistClick = onNeteasePlaylistClick,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryPlaylistsContent(
    uiState: LibraryPlaylistsUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState,
    contentPadding: PaddingValues = PaddingValues(),
    onPlaylistClick: (String) -> Unit = {},
    onPlaylistPlayNext: (List<String>) -> Unit = {},
    onPlaylistAddToQueue: (List<String>) -> Unit = {},
    onPlaylistUpdate: (String, String, android.net.Uri?, Boolean) -> Unit = { _, _, _, _ -> },
    onPlaylistDelete: (String) -> Unit = {},
    onSmartPlaylistCreate: (String, SmartPlaylistSpec) -> Unit = { _, _ -> },
    onSmartPlaylistUpdate: (String, String, SmartPlaylistSpec) -> Unit = { _, _, _ -> },
    onExportM3u: (String) -> Unit = {},
    onPlaylistDeduplicate: (String) -> Int = { 0 },
    onNeteasePlaylistClick: (String) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadProgress by NeteaseRepository.downloadProgress.collectAsStateWithLifecycle()
    val listPadding = remember(contentPadding) { PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()) }
    var contextPlaylistId by remember { mutableStateOf<String?>(null) }
    var editingPlaylist by remember { mutableStateOf<PlaylistListItem?>(null) }
    var editingSmartPlaylist by remember { mutableStateOf<PlaylistListItem?>(null) }
    var showCreateSmartSheet by remember { mutableStateOf(false) }
    var deletingPlaylist by remember { mutableStateOf<PlaylistListItem?>(null) }
    var neteaseMenuId by remember { mutableStateOf<String?>(null) }
    var neteaseDeletingId by remember { mutableStateOf<String?>(null) }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    val trimmedQuery = filterQuery.trim()
    val favoritesLabel = stringResource(R.string.library_favorites)
    val dedupDoneTemplate = stringResource(R.string.playlist_deduplicate_done, 987654)
    val dedupNoneMsg = stringResource(R.string.playlist_deduplicate_none)
    val filteredLocalPlaylists = remember(uiState.playlists, trimmedQuery, favoritesLabel) {
        if (trimmedQuery.isEmpty()) {
            uiState.playlists
        } else {
            uiState.playlists.filter { playlist ->
                val displayName = if (playlist.isFavorite) favoritesLabel else playlist.name
                displayName.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }
    val filteredNeteasePlaylists = remember(uiState.neteasePlaylists, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            uiState.neteasePlaylists
        } else {
            uiState.neteasePlaylists.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
        }
    }
    LibraryVirtualList(
        items = filteredLocalPlaylists,
        isLoaded = uiState.isLoaded,
        key = { it.id },
        contentType = "playlist_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        dividerTestTag = "playlist-row-divider",
        filterKey = "playlists",
        filterActive = trimmedQuery.isNotEmpty(),
        hasAnyVisibleItems = filteredLocalPlaylists.isNotEmpty() || filteredNeteasePlaylists.isNotEmpty(),
        filterContent = { showPlaceholder ->
            LibraryTextFilter(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = stringResource(R.string.filter_placeholder_search),
                showPlaceholderAndLeadingSymbol = showPlaceholder,
            )
        },
        leadingContent = if (trimmedQuery.isEmpty()) {
            { SmartPlaylistActionBanner(onClick = { showCreateSmartSheet = true }) }
        } else {
            null
        },
        emptyContent = {
            Column(Modifier.fillMaxSize().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (trimmedQuery.isEmpty()) {
                    SmartPlaylistActionBanner(onClick = { showCreateSmartSheet = true })
                }
                HeroCard(
                    symbol = MaterialSymbols.QueueMusic,
                    title = stringResource(if (trimmedQuery.isEmpty()) R.string.playlists_empty_title else R.string.playlists_no_match_title),
                    description = stringResource(if (trimmedQuery.isEmpty()) R.string.playlists_empty_description else R.string.filter_no_match_description),
                )
            }
        },
        customItemsContent = {
            localPlaylistRows(
                items = filteredLocalPlaylists,
                colors = colors,
                contextPlaylistId = contextPlaylistId,
                setContextPlaylistId = { contextPlaylistId = it },
                onPlaylistPlayNext = onPlaylistPlayNext,
                onPlaylistAddToQueue = onPlaylistAddToQueue,
                onPlaylistClick = onPlaylistClick,
                onEdit = { item ->
                    if (item.smartSpec != null) editingSmartPlaylist = item else editingPlaylist = item
                },
                onDelete = { deletingPlaylist = it },
                onExportM3u = onExportM3u,
                onDeduplicate = { playlistId ->
                    val removed = onPlaylistDeduplicate(playlistId)
                    val msg = if (removed > 0) {
                        dedupDoneTemplate.replace("987654", removed.toString())
                    } else {
                        dedupNoneMsg
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                },
            )
            if (filteredNeteasePlaylists.isNotEmpty()) {
                item(key = "netease-playlists-header", contentType = "playlist_section_header") {
                    Text(
                        text = stringResource(R.string.playlists_netease_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(
                    items = filteredNeteasePlaylists,
                    key = { _, playlist -> "netease:${playlist.id}" },
                    contentType = { _, _ -> "netease_playlist_row" },
                ) { index, playlist ->
                    val progress = downloadProgress[playlist.id]
                    PlaylistContextMenu(
                        playlistId = playlist.id,
                        // 组合期不要全量物化整张歌单；只有菜单真正展开时才计算（结果在仓库内按条目缓存）。
                        trackIds = if (neteaseMenuId == playlist.id) {
                            NeteaseRepository.playableTrackIds(playlist.id)
                        } else {
                            emptyList()
                        },
                        expanded = neteaseMenuId == playlist.id,
                        onDismiss = { if (neteaseMenuId == playlist.id) neteaseMenuId = null },
                        onPlayNext = { ids -> AndroidPlaybackRuntime.controller().playNext(ids) },
                        onAddToQueue = { ids -> AndroidPlaybackRuntime.controller().append(ids) },
                        showReorder = false,
                        showEdit = false,
                        showExport = false,
                        onRefresh = {
                            scope.launch { NeteaseRepository.refresh(context, playlist.id) }
                        },
                        onDownloadAll = {
                            scope.launch { NeteaseRepository.downloadAll(context, playlist.id) }
                        },
                        onRemoveFromPlaylists = { NeteaseRepository.setAdded(playlist.id, false) },
                        onDelete = { neteaseDeletingId = playlist.id },
                        anchor = {
                            Column {
                                PlaylistRow(
                                    playlistId = playlist.id,
                                    name = playlist.name,
                                    artworkPaths = listOfNotNull(playlist.coverPath),
                                    artworkUrl = playlist.coverUrl,
                                    onClick = { onNeteasePlaylistClick(playlist.id) },
                                    onLongClick = { neteaseMenuId = playlist.id },
                                )
                                if (progress != null) {
                                    Text(
                                        text = stringResource(R.string.netease_download_progress, progress.first, progress.second),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        modifier = Modifier.padding(start = 146.dp, bottom = 6.dp),
                                    )
                                }
                            }
                        },
                    )
                    if (index < filteredNeteasePlaylists.lastIndex) PlaylistDivider(colors)
                }
            }
        },
        itemContent = {},
    )
    editingPlaylist?.let { playlist ->
        EditPlaylistBottomSheet(
            initialName = playlist.name,
            artworkPath = playlist.customArtworkPath,
            showNameInput = !playlist.isFavorite,
            onDismiss = { editingPlaylist = null },
            onSave = { name, artwork, clearArtwork -> onPlaylistUpdate(playlist.id, name, artwork, clearArtwork); editingPlaylist = null },
        )
    }
    if (showCreateSmartSheet) {
        SmartPlaylistEditorBottomSheet(
            isEdit = false,
            onDismiss = { showCreateSmartSheet = false },
            onSave = { name, spec ->
                onSmartPlaylistCreate(name, spec)
                showCreateSmartSheet = false
            },
        )
    }
    editingSmartPlaylist?.let { playlist ->
        playlist.smartSpec?.let { spec ->
            SmartPlaylistEditorBottomSheet(
                isEdit = true,
                initialName = playlist.name,
                initialSpec = spec,
                onDismiss = { editingSmartPlaylist = null },
                onSave = { newName, newSpec ->
                    onSmartPlaylistUpdate(playlist.id, newName, newSpec)
                    editingSmartPlaylist = null
                },
            )
        }
    }
    deletingPlaylist?.let { playlist ->
        TuneDialog(
            title = stringResource(R.string.playlist_delete_confirm_title),
            description = stringResource(R.string.playlist_delete_confirm_description),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { deletingPlaylist = null },
            confirmLabel = stringResource(R.string.playlist_delete),
            onConfirm = { onPlaylistDelete(playlist.id); deletingPlaylist = null },
            confirmVariant = TunePillButtonVariant.Destructive,
        )
    }
    neteaseDeletingId?.let { id ->
        TuneDialog(
            title = stringResource(R.string.netease_delete_title),
            description = stringResource(R.string.netease_delete_desc),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { neteaseDeletingId = null },
            confirmLabel = stringResource(R.string.netease_delete),
            onConfirm = {
                scope.launch { NeteaseRepository.delete(id) }
                neteaseDeletingId = null
            },
            confirmVariant = TunePillButtonVariant.Destructive,
        )
    }
}

@Composable
private fun SmartPlaylistActionBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        TunePillButton(
            label = stringResource(R.string.smart_playlist_create_action),
            variant = TunePillButtonVariant.Secondary,
            onClick = onClick,
        )
    }
}

@Composable
private fun PlaylistDivider(colors: TuneColors) {
    HorizontalDivider(
        color = colors.borderGlass,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

private fun LazyListScope.localPlaylistRows(
    items: List<PlaylistListItem>,
    colors: TuneColors,
    contextPlaylistId: String?,
    setContextPlaylistId: (String?) -> Unit,
    onPlaylistPlayNext: (List<String>) -> Unit,
    onPlaylistAddToQueue: (List<String>) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onEdit: (PlaylistListItem) -> Unit,
    onDelete: (PlaylistListItem) -> Unit,
    onExportM3u: (String) -> Unit,
    onDeduplicate: (String) -> Unit,
) {
    itemsIndexed(items = items, key = { _, playlist -> "local:${playlist.id}" }, contentType = { _, _ -> "playlist_row" }) { index, playlist ->
        PlaylistContextMenu(
            playlistId = playlist.id,
            trackIds = playlist.trackIds,
            expanded = contextPlaylistId == playlist.id,
            onDismiss = { if (contextPlaylistId == playlist.id) setContextPlaylistId(null) },
            onPlayNext = onPlaylistPlayNext,
            onAddToQueue = onPlaylistAddToQueue,
            showReorder = !playlist.isSmart,
            showDeduplicate = !playlist.isSmart && !playlist.isFavorite && playlist.trackIds.size > 1,
            onEdit = { onEdit(playlist) },
            onDelete = { onDelete(playlist) },
            onExportM3u = { onExportM3u(playlist.id) },
            onDeduplicate = { onDeduplicate(playlist.id) },
            anchor = {
                PlaylistRow(
                    playlistId = playlist.id,
                    name = if (playlist.isFavorite) stringResource(R.string.library_favorites) else playlist.name,
                    artworkPaths = playlist.artworkPaths,
                    syncFailed = playlist.syncFailed,
                    isSmart = playlist.isSmart,
                    onClick = { onPlaylistClick(playlist.id) },
                    onLongClick = { setContextPlaylistId(playlist.id) },
                )
            },
        )
        if (index < items.lastIndex) PlaylistDivider(colors)
    }
}
