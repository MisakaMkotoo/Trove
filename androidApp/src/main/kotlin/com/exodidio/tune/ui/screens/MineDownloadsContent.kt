package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.DownloadTaskState
import com.exodidio.tune.netease.DownloadTaskStatus
import com.exodidio.tune.netease.NeteaseDownloadManager
import com.exodidio.tune.netease.NeteaseMediaHelper
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LocalNeteaseEntityMenuActions
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.TuneRemoteImage
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneRadii

/** 用户明确点过下载的网易云歌曲（导入曲库、有本地文件或 SAF URI，不含流缓存；磁盘校验在 IO 线程执行）。 */
@Composable
internal fun rememberNeteaseDownloadedTracks(): List<LibraryTrack> {
    val context = LocalContext.current
    val tracks by AndroidSyncRuntime.syncStore.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val candidates = remember(tracks) {
        tracks.asSequence()
            .filter(NeteaseRepository::isExplicitlyDownloaded)
            .sortedByDescending { it.updatedAt }
            .toList()
    }
    var verified by remember(candidates) { mutableStateOf(candidates) }
    LaunchedEffect(candidates, context) {
        verified = NeteaseMediaHelper.filterAvailableDownloadedTracks(context, candidates)
    }
    return verified
}

/** 专属下载管理页：展示正在下载/排队/失败任务与已下载曲目。 */
@Composable
internal fun MineDownloadsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
) {
    val context = LocalContext.current
    val colors = LocalTuneColors.current
    val neteaseEntityActions = LocalNeteaseEntityMenuActions.current
    val tasksMap by NeteaseDownloadManager.tasks.collectAsStateWithLifecycle()
    val localPlaylists by AndroidSyncRuntime.syncStore.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadedTracks = rememberNeteaseDownloadedTracks()
    val downloadedIds = remember(downloadedTracks) { downloadedTracks.map { it.id } }
    var contextTrackId by remember { mutableStateOf<String?>(null) }

    val orderedTasks = remember(tasksMap) {
        tasksMap.values.sortedWith(
            compareBy<DownloadTaskState> {
                when (it.status) {
                    DownloadTaskStatus.Downloading -> 0
                    DownloadTaskStatus.Queued -> 1
                    DownloadTaskStatus.Failed -> 2
                }
            }.thenBy { it.entry.title },
        )
    }
    val hasFailed = remember(orderedTasks) {
        orderedTasks.any { it.status == DownloadTaskStatus.Failed }
    }

    val appleTitleStyle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    )
    val appleArtistStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp)
    val appleRowPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 0.dp, bottom = 4.dp)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        if (orderedTasks.isNotEmpty()) {
            item(key = "downloads_active_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.downloads_section_active),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textMain,
                    )
                    if (hasFailed) {
                        Text(
                            text = stringResource(R.string.downloads_clear_completed_failed),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { NeteaseDownloadManager.clearFailed() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            items(
                items = orderedTasks,
                key = { task -> "dl_task_${task.songId}" },
            ) { task ->
                DownloadTaskRow(
                    task = task,
                    onCancel = { NeteaseDownloadManager.cancel(task.songId) },
                    onRetry = { NeteaseDownloadManager.retry(context, task.songId) },
                )
            }
        }

        if (downloadedTracks.isNotEmpty()) {
            item(key = "downloads_completed_header") {
                Text(
                    text = stringResource(R.string.downloads_section_completed, downloadedTracks.size),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMain,
                    modifier = Modifier.padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = if (orderedTasks.isNotEmpty()) 20.dp else 12.dp,
                        bottom = 6.dp,
                    ),
                )
            }
            itemsIndexed(
                items = downloadedTracks,
                key = { _, track -> "dl_done_${track.id}" },
            ) { index, track ->
                val subtitle = track.artists.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.common_unknown_artist)
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
                    onGoToAlbum = { item ->
                        val aid = NeteaseRepository.neteaseAlbumIdForTrack(item)
                        if (aid != null && aid > 0L) {
                            neteaseEntityActions.onOpenAlbum(
                                aid,
                                item.album,
                                NeteaseRepository.coverUrlForTrack(item),
                            )
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
                            onClick = { onPlayTracks(downloadedIds, index) },
                            onMoreClick = { contextTrackId = track.id },
                            onLongClick = { contextTrackId = track.id },
                        )
                    },
                )
                if (index < downloadedTracks.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp, end = 20.dp),
                        thickness = 0.5.dp,
                        color = colors.borderGlass.copy(alpha = 0.35f),
                    )
                }
            }
        }

        if (orderedTasks.isEmpty() && downloadedTracks.isEmpty()) {
            item(key = "downloads_empty") {
                Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.ArrowDownward,
                        title = stringResource(R.string.downloads_empty_title),
                        description = stringResource(R.string.downloads_empty_description),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTaskState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val statusText = when (task.status) {
        DownloadTaskStatus.Queued -> stringResource(R.string.downloads_status_queued)
        DownloadTaskStatus.Downloading -> {
            val pct = task.progress?.let { (it * 100f).toInt().coerceIn(0, 100) }
            if (pct != null) {
                "${stringResource(R.string.downloads_status_downloading)} · $pct%"
            } else {
                stringResource(R.string.downloads_status_downloading)
            }
        }
        DownloadTaskStatus.Failed -> stringResource(
            R.string.downloads_status_failed,
            task.error.orEmpty().ifBlank { "Error" },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(TuneRadii.ListArtwork))
                    .background(colors.glassElevated)
                    .border(0.5.dp, colors.borderGlass, RoundedCornerShape(TuneRadii.ListArtwork)),
                contentAlignment = Alignment.Center,
            ) {
                if (task.entry.coverUrl.isNotBlank()) {
                    TuneRemoteImage(
                        url = task.entry.coverUrl,
                        sizePx = 200,
                        modifier = Modifier.matchParentSize(),
                        fallback = {
                            MaterialSymbol(
                                symbol = MaterialSymbols.MusicNote,
                                contentDescription = null,
                                size = 22.dp,
                                tint = colors.textMuted,
                            )
                        },
                    )
                } else {
                    MaterialSymbol(
                        symbol = MaterialSymbols.MusicNote,
                        contentDescription = null,
                        size = 22.dp,
                        tint = colors.textMuted,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = task.entry.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${task.entry.artists.ifBlank { stringResource(R.string.common_unknown_artist) }} · $statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == DownloadTaskStatus.Failed) colors.primary else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (task.status == DownloadTaskStatus.Failed) {
                IconButton(onClick = onRetry) {
                    MaterialSymbol(
                        symbol = MaterialSymbols.Refresh,
                        contentDescription = stringResource(R.string.downloads_retry),
                        size = 20.dp,
                        tint = colors.primary,
                    )
                }
            }
            IconButton(onClick = onCancel) {
                MaterialSymbol(
                    symbol = MaterialSymbols.Close,
                    contentDescription = stringResource(R.string.downloads_cancel),
                    size = 20.dp,
                    tint = colors.textMuted,
                )
            }
        }
        if (task.status == DownloadTaskStatus.Downloading) {
            val fraction = task.progress
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 60.dp, end = 12.dp, top = 4.dp)
                        .height(3.dp),
                    color = colors.primary,
                    trackColor = colors.buttonSecondary,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 60.dp, end = 12.dp, top = 4.dp)
                        .height(3.dp),
                    color = colors.primary,
                    trackColor = colors.buttonSecondary,
                )
            }
        }
    }
}
