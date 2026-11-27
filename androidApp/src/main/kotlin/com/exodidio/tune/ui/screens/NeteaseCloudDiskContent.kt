package com.exodidio.tune.ui.screens

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseCloudDiskItem
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.LibraryPlaybackActions
import com.exodidio.tune.ui.components.LocalBatchChromeHost
import com.exodidio.tune.ui.components.LocalNeteaseEntityMenuActions
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.MultiSelectActionBar
import com.exodidio.tune.ui.components.MultiSelectCheckBadge
import com.exodidio.tune.ui.components.MultiSelectSelectionHeader
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.TuneDialog
import com.exodidio.tune.ui.components.TunePillButton
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.theme.LocalTuneColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/** 网易云音乐云盘二级页：配额信息 + 分页加载 + 播放/喜欢/歌单/删除云盘文件。 */
@Composable
internal fun NeteaseCloudDiskContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    hazeState: HazeState? = null,
    listState: LazyListState = rememberLazyListState(),
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val neteaseEntityActions = LocalNeteaseEntityMenuActions.current
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val state by NeteaseRepository.cloudDisk.collectAsStateWithLifecycle()
    val libraryTracks by AndroidSyncRuntime.syncStore.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playbackOnlyTracks by AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val localPlaylists by AndroidSyncRuntime.syncStore.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val playableById = remember(libraryTracks, playbackOnlyTracks) {
        (playbackOnlyTracks + libraryTracks).associateBy { it.id }
    }

    var contextTrackId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmItem by remember { mutableStateOf<NeteaseCloudDiskItem?>(null) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val deletedToast = stringResource(R.string.netease_cloud_disk_deleted)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")
    val batchAddedToast = stringResource(R.string.batch_added_to_queue, selectedTrackIds.size)

    LaunchedEffect(loggedIn) {
        if (loggedIn && !state.loaded && !state.loading) {
            NeteaseRepository.loadCloudDisk()
        }
    }

    val nearEnd by remember(listState, state.items.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 5
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.loading, state.loadingMore) {
        if (nearEnd && state.hasMore && !state.loading && !state.loadingMore) {
            NeteaseRepository.loadMoreCloudDisk()
        }
    }

    val allTrackIds = remember(state.items) { state.items.map { it.track.id }.distinct() }
    val appleTitleStyle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    )
    val appleArtistStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp)
    val appleRowPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 0.dp, bottom = 4.dp)

    BackHandler(enabled = multiSelectMode) {
        multiSelectMode = false
        selectedTrackIds = emptySet()
    }
    val batchHost = LocalBatchChromeHost.current
    val batchId = remember { Any() }
    val listHazeState = if (batchHost == null && hazeState != null) rememberHazeState() else null
    val latestAllTrackIds by rememberUpdatedState(allTrackIds)
    val latestOnBottomSheet by rememberUpdatedState(onTrackContextBottomSheet)
    val batchTopExtra by animateDpAsState(
        targetValue = if (multiSelectMode) 60.dp else 0.dp,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "cloud-disk-batch-top-padding",
    )
    if (batchHost != null) {
        DisposableEffect(batchHost, batchId) {
            onDispose { batchHost.dismiss(batchId) }
        }
        SideEffect {
            if (multiSelectMode) {
                batchHost.show(
                    id = batchId,
                    topContent = {
                        val allIds = latestAllTrackIds
                        val currentSelected = selectedTrackIds
                        MultiSelectSelectionHeader(
                            selectedCount = currentSelected.size,
                            allSelected = allIds.isNotEmpty() && currentSelected.size == allIds.size,
                            onToggleSelectAll = {
                                selectedTrackIds = if (currentSelected.size == allIds.size) emptySet() else allIds.toSet()
                            },
                            onExit = {
                                multiSelectMode = false
                                selectedTrackIds = emptySet()
                            },
                        )
                    },
                    bottomContent = {
                        val allIds = latestAllTrackIds
                        val currentSelected = selectedTrackIds
                        val selectedIds = allIds.filter { it in currentSelected }
                        val addedToast = context.getString(R.string.batch_added_to_queue, selectedIds.size)
                        MultiSelectActionBar(
                            selectedCount = selectedIds.size,
                            onPlayNext = {
                                if (selectedIds.isNotEmpty()) {
                                    AndroidPlaybackRuntime.controller().playNext(selectedIds)
                                    Toast.makeText(context, addedToast, Toast.LENGTH_SHORT).show()
                                    multiSelectMode = false
                                    selectedTrackIds = emptySet()
                                }
                            },
                            onAddToPlaylist = {
                                if (selectedIds.isNotEmpty()) {
                                    latestOnBottomSheet(
                                        TrackContextBottomSheetRequest.Playlist(
                                            trackIds = selectedIds,
                                            addOnly = true,
                                            allowNeteasePlaylists = true,
                                        ),
                                    )
                                }
                            },
                            onRemove = {
                                val songIds = selectedIds.mapNotNull { NeteaseRepository.songIdForTrack(it) }
                                if (songIds.isNotEmpty()) {
                                    scope.launch {
                                        NeteaseRepository.deleteCloudDiskTracks(context, songIds)
                                            .onSuccess {
                                                Toast.makeText(context, deletedToast, Toast.LENGTH_SHORT).show()
                                                multiSelectMode = false
                                                selectedTrackIds = emptySet()
                                            }
                                            .onFailure { err ->
                                                Toast.makeText(
                                                    context,
                                                    failedTemplate.replace("{err}", err.message.orEmpty()),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                    }
                                }
                            },
                        )
                    },
                )
            } else {
                batchHost.dismiss(batchId)
            }
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (listHazeState == null) Modifier
                    else Modifier.background(colors.background).hazeSource(listHazeState),
                ),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + batchTopExtra,
                bottom = contentPadding.calculateBottomPadding() + if (batchHost == null && multiSelectMode) 84.dp else 0.dp,
            ),
        ) {
            item("cloud_disk_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.mine_cloud_disk_title),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.textMain,
                        )
                        if (state.maxSizeBytes > 0L || state.totalCount > 0) {
                            val usedText = Formatter.formatShortFileSize(context, state.sizeBytes)
                            val maxText = Formatter.formatShortFileSize(context, state.maxSizeBytes.coerceAtLeast(state.sizeBytes))
                            val count = maxOf(state.totalCount, state.items.size)
                            Text(
                                text = stringResource(R.string.netease_cloud_disk_quota, usedText, maxText, count),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (state.items.isNotEmpty() && !multiSelectMode) {
                        Text(
                            text = stringResource(R.string.batch_select_enter),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                            modifier = Modifier
                                .clickable {
                                    multiSelectMode = true
                                    selectedTrackIds = emptySet()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { NeteaseRepository.loadCloudDisk(force = true) }) {
                            MaterialSymbol(
                                symbol = MaterialSymbols.Refresh,
                                contentDescription = stringResource(R.string.netease_refresh),
                                size = 20.dp,
                                tint = colors.textMuted,
                            )
                        }
                    }
                }
            }

            if (!multiSelectMode && allTrackIds.isNotEmpty()) {
                item("cloud_disk_playback_actions") {
                    LibraryPlaybackActions(
                        playLabel = stringResource(R.string.player_play),
                        shuffleLabel = stringResource(R.string.player_shuffle),
                        onPlay = { onPlayTracks(allTrackIds, 0) },
                        onShuffle = {
                            AndroidPlaybackRuntime.controller().shuffle(PlaybackRequest(allTrackIds, 0))
                        },
                        hazeState = hazeState,
                    )
                }
            }

            when {
                state.loading && state.items.isEmpty() -> item("cloud_disk_loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
                state.items.isEmpty() -> item("cloud_disk_empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.error ?: stringResource(R.string.netease_cloud_disk_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                        if (state.error != null) {
                            TunePillButton(
                                label = stringResource(R.string.discover_retry),
                                variant = TunePillButtonVariant.Secondary,
                                onClick = { NeteaseRepository.loadCloudDisk(force = true) },
                            )
                        }
                    }
                }
                else -> {
                    itemsIndexed(state.items, key = { _, item -> item.songId }) { index, item ->
                        val track = playableById[item.track.id] ?: item.track
                        val isSelected = track.id in selectedTrackIds
                        val subtitleParts = listOfNotNull(
                            track.artists.takeIf { it.isNotBlank() },
                            if (item.fileSizeBytes > 0L) Formatter.formatShortFileSize(context, item.fileSizeBytes) else null,
                        )
                        val subtitle = subtitleParts.joinToString(" · ").ifBlank {
                            stringResource(R.string.common_unknown_artist)
                        }
                        if (multiSelectMode) {
                            val rowTint by animateColorAsState(
                                targetValue = if (isSelected) colors.primary.copy(alpha = 0.08f) else Color.Transparent,
                                animationSpec = if (LocalReduceMotion.current) snap() else tween(180, easing = FastOutSlowInEasing),
                                label = "cloud-disk-batch-row-tint",
                            )
                            TrackRow(
                                title = track.title,
                                artist = subtitle,
                                artworkPath = track.artworkPath,
                                artworkUrl = NeteaseRepository.coverUrlForTrack(track),
                                contentPadding = appleRowPadding,
                                artworkCornerRadius = 4.dp,
                                titleStyle = appleTitleStyle,
                                artistStyle = appleArtistStyle,
                                moreButtonWidth = 38.dp,
                                modifier = Modifier.background(rowTint),
                                onClick = {
                                    selectedTrackIds = if (isSelected) selectedTrackIds - track.id else selectedTrackIds + track.id
                                },
                                trailingContent = {
                                    Box(
                                        modifier = Modifier.padding(end = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        MultiSelectCheckBadge(selected = isSelected)
                                    }
                                },
                            )
                        } else {
                            TrackContextMenu(
                                track = track,
                                expanded = contextTrackId == track.id,
                                onDismiss = { if (contextTrackId == track.id) contextTrackId = null },
                                actions = TrackContextMenuActions(
                                    removeFromQueue = false,
                                    removeFromPlaylist = false,
                                    neteaseLike = true,
                                    allowNeteasePlaylists = true,
                                    hide = false,
                                ),
                                playbackQueue = playbackQueue,
                                onPlayNext = onTrackPlayNext,
                                onAddToQueue = onTrackAddToQueue,
                                onFavoriteChange = onTrackFavoriteToggle,
                                onBottomSheetRequested = onTrackContextBottomSheet,
                                onGoToAlbum = { t ->
                                    val aid = NeteaseRepository.neteaseAlbumIdForTrack(t)
                                    if (aid != null && aid > 0L) {
                                        neteaseEntityActions.onOpenAlbum(aid, t.album, NeteaseRepository.coverUrlForTrack(t))
                                    }
                                },
                                onGoToArtist = { artist ->
                                    val aid = artist.id.toLongOrNull()
                                    if (aid != null && aid > 0L) {
                                        neteaseEntityActions.onOpenArtist(aid, artist.name)
                                    }
                                },
                                playlists = localPlaylists.filter { it.id != FavoritesPlaylistId },
                                anchor = {
                                    TrackRow(
                                        title = track.title,
                                        artist = subtitle,
                                        artworkPath = track.artworkPath,
                                        artworkUrl = NeteaseRepository.coverUrlForTrack(track),
                                        contentPadding = appleRowPadding,
                                        artworkCornerRadius = 4.dp,
                                        titleStyle = appleTitleStyle,
                                        artistStyle = appleArtistStyle,
                                        moreButtonWidth = 38.dp,
                                        onClick = {
                                            val startIndex = allTrackIds.indexOf(track.id).coerceAtLeast(0)
                                            if (allTrackIds.isNotEmpty()) onPlayTracks(allTrackIds, startIndex)
                                        },
                                        onMoreClick = { contextTrackId = track.id },
                                        onLongClick = { contextTrackId = track.id },
                                        trailingContent = {
                                            IconButton(onClick = { deleteConfirmItem = item }) {
                                                MaterialSymbol(
                                                    symbol = MaterialSymbols.Delete,
                                                    contentDescription = stringResource(R.string.netease_cloud_disk_delete),
                                                    size = 18.dp,
                                                    tint = colors.textMuted,
                                                )
                                            }
                                            IconButton(
                                                onClick = { contextTrackId = track.id },
                                                modifier = Modifier.width(38.dp).height(48.dp),
                                            ) {
                                                MaterialSymbol(
                                                    symbol = MaterialSymbols.MoreVert,
                                                    contentDescription = stringResource(R.string.album_row_more_options),
                                                    size = 20.dp,
                                                    tint = colors.textMuted,
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        }
                        if (index < state.items.lastIndex) {
                            HorizontalDivider(
                                color = colors.borderGlass,
                                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                            )
                        }
                    }
                    if (state.loadingMore) {
                        item("cloud_disk_loading_more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
        }

        if (batchHost == null && multiSelectMode) {
            MultiSelectSelectionHeader(
                selectedCount = selectedTrackIds.size,
                allSelected = allTrackIds.isNotEmpty() && selectedTrackIds.size == allTrackIds.size,
                onToggleSelectAll = {
                    selectedTrackIds = if (selectedTrackIds.size == allTrackIds.size) emptySet() else allTrackIds.toSet()
                },
                onExit = {
                    multiSelectMode = false
                    selectedTrackIds = emptySet()
                },
                hazeState = listHazeState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = contentPadding.calculateTopPadding() + 4.dp, end = 16.dp),
            )
            val selectedIds = remember(selectedTrackIds, allTrackIds) {
                allTrackIds.filter { it in selectedTrackIds }
            }
            MultiSelectActionBar(
                selectedCount = selectedIds.size,
                onPlayNext = {
                    if (selectedIds.isNotEmpty()) {
                        AndroidPlaybackRuntime.controller().playNext(selectedIds)
                        Toast.makeText(context, batchAddedToast, Toast.LENGTH_SHORT).show()
                        multiSelectMode = false
                        selectedTrackIds = emptySet()
                    }
                },
                onAddToPlaylist = {
                    if (selectedIds.isNotEmpty()) {
                        onTrackContextBottomSheet(
                            TrackContextBottomSheetRequest.Playlist(
                                trackIds = selectedIds,
                                addOnly = true,
                                allowNeteasePlaylists = true,
                            ),
                        )
                    }
                },
                onRemove = {
                    val songIds = selectedIds.mapNotNull { NeteaseRepository.songIdForTrack(it) }
                    if (songIds.isNotEmpty()) {
                        scope.launch {
                            NeteaseRepository.deleteCloudDiskTracks(context, songIds)
                                .onSuccess {
                                    Toast.makeText(context, deletedToast, Toast.LENGTH_SHORT).show()
                                    multiSelectMode = false
                                    selectedTrackIds = emptySet()
                                }
                                .onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        failedTemplate.replace("{err}", err.message.orEmpty()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                        }
                    }
                },
                hazeState = listHazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }

    deleteConfirmItem?.let { target ->
        TuneDialog(
            title = stringResource(R.string.netease_cloud_disk_delete_confirm_title),
            description = stringResource(R.string.netease_cloud_disk_delete_confirm_desc, target.track.title),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { deleteConfirmItem = null },
            confirmLabel = stringResource(R.string.netease_cloud_disk_delete),
            confirmVariant = TunePillButtonVariant.Destructive,
            onConfirm = {
                val songId = target.songId
                deleteConfirmItem = null
                scope.launch {
                    NeteaseRepository.deleteCloudDiskTracks(context, listOf(songId))
                        .onSuccess {
                            Toast.makeText(context, deletedToast, Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { err ->
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
}
