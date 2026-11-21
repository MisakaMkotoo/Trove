package com.exodidio.tune.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryVirtualList
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.PlaylistRow
import com.exodidio.tune.ui.components.PlaylistContextMenu
import com.exodidio.tune.ui.components.TuneDialog
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneColors
import kotlinx.coroutines.launch

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
    onExportM3u: (String) -> Unit = {},
    onNeteasePlaylistClick: (String) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadProgress by NeteaseRepository.downloadProgress.collectAsStateWithLifecycle()
    val listPadding = remember(contentPadding) { PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()) }
    var contextPlaylistId by remember { mutableStateOf<String?>(null) }
    var editingPlaylist by remember { mutableStateOf<PlaylistListItem?>(null) }
    var deletingPlaylist by remember { mutableStateOf<PlaylistListItem?>(null) }
    var neteaseMenuId by remember { mutableStateOf<String?>(null) }
    var neteaseDeletingId by remember { mutableStateOf<String?>(null) }
    LibraryVirtualList(
        items = uiState.playlists,
        isLoaded = uiState.isLoaded,
        key = { it.id },
        contentType = "playlist_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        dividerTestTag = "playlist-row-divider",
        emptyContent = {
            Column(Modifier.fillMaxSize().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroCard(
                    symbol = MaterialSymbols.QueueMusic,
                    title = stringResource(R.string.playlists_empty_title),
                    description = stringResource(R.string.playlists_empty_description),
                )
            }
        },
        customItemsContent = {
            localPlaylistRows(
                items = uiState.playlists,
                colors = colors,
                contextPlaylistId = contextPlaylistId,
                setContextPlaylistId = { contextPlaylistId = it },
                onPlaylistPlayNext = onPlaylistPlayNext,
                onPlaylistAddToQueue = onPlaylistAddToQueue,
                onPlaylistClick = onPlaylistClick,
                onEdit = { editingPlaylist = it },
                onDelete = { deletingPlaylist = it },
                onExportM3u = onExportM3u,
            )
            if (uiState.neteasePlaylists.isNotEmpty()) {
                item(key = "netease-playlists-header", contentType = "playlist_section_header") {
                    Text(
                        text = stringResource(R.string.playlists_netease_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(
                    items = uiState.neteasePlaylists,
                    key = { _, playlist -> "netease:${playlist.id}" },
                    contentType = { _, _ -> "netease_playlist_row" },
                ) { index, playlist ->
                    val progress = downloadProgress[playlist.id]
                    PlaylistContextMenu(
                        playlistId = playlist.id,
                        trackIds = NeteaseRepository.playableTrackIds(playlist.id),
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
                    if (index < uiState.neteasePlaylists.lastIndex) PlaylistDivider(colors)
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
) {
    itemsIndexed(items = items, key = { _, playlist -> "local:${playlist.id}" }, contentType = { _, _ -> "playlist_row" }) { index, playlist ->
        PlaylistContextMenu(
            playlistId = playlist.id,
            trackIds = playlist.trackIds,
            expanded = contextPlaylistId == playlist.id,
            onDismiss = { if (contextPlaylistId == playlist.id) setContextPlaylistId(null) },
            onPlayNext = onPlaylistPlayNext,
            onAddToQueue = onPlaylistAddToQueue,
            onEdit = { onEdit(playlist) },
            onDelete = { onDelete(playlist) },
            onExportM3u = { onExportM3u(playlist.id) },
            anchor = {
                PlaylistRow(
                    playlist.id,
                    if (playlist.isFavorite) stringResource(R.string.library_favorites) else playlist.name,
                    playlist.artworkPaths,
                    syncFailed = playlist.syncFailed,
                    onClick = { onPlaylistClick(playlist.id) },
                    onLongClick = { setContextPlaylistId(playlist.id) },
                )
            },
        )
        if (index < items.lastIndex) PlaylistDivider(colors)
    }
}
