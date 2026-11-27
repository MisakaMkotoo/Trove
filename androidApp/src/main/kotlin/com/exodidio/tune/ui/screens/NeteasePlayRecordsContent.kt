package com.exodidio.tune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.LocalNeteaseEntityMenuActions
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.pressScale
import com.exodidio.tune.ui.theme.LocalTuneColors

/** 播放记录二级页：两个一级页签（最近播放 | 云端听歌记录），云端页签下含（云端最近 | 本周排行 | 总排行）。 */
@Composable
internal fun NeteasePlayRecordsContent(
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
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val neteaseEntityActions = LocalNeteaseEntityMenuActions.current
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val state by NeteaseRepository.playRecords.collectAsStateWithLifecycle()
    val recentTracks by NeteaseRepository.recentTracks.collectAsStateWithLifecycle()
    val libraryTracks by AndroidSyncRuntime.syncStore.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playbackOnlyTracks by AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val localPlaylists by AndroidSyncRuntime.syncStore.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val playableById = remember(libraryTracks, playbackOnlyTracks) {
        (playbackOnlyTracks + libraryTracks).associateBy { it.id }
    }

    var selectedMainTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedCloudTab by rememberSaveable { mutableIntStateOf(0) }
    var contextTrackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(loggedIn, selectedMainTab) {
        if (loggedIn && selectedMainTab == 1) NeteaseRepository.loadPlayRecords()
    }

    val resolvedRecentTracks = remember(recentTracks, playableById) {
        recentTracks.map { playableById[it.id] ?: it }
    }
    val recentTrackIds = remember(resolvedRecentTracks) {
        resolvedRecentTracks.map { it.id }.distinct()
    }

    val activeCloudEntries = when (selectedCloudTab) {
        0 -> state.recent
        1 -> state.weekRank
        else -> state.allRank
    }
    val activeCloudError = when (selectedCloudTab) {
        0 -> state.recentError
        1 -> state.weekRankError
        else -> state.allRankError
    }
    val activeCloudTrackIds = remember(activeCloudEntries) { activeCloudEntries.map { it.track.id }.distinct() }

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
        item(key = "records_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.netease_play_records_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textMain,
                )
                if (selectedMainTab == 1 && loggedIn) {
                    Crossfade(
                        targetState = state.loading,
                        animationSpec = if (reduceMotion) snap() else tween(180, easing = FastOutSlowInEasing),
                        label = "records-refresh-loading",
                    ) { loading ->
                        if (loading) {
                            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = { NeteaseRepository.loadPlayRecords(force = true) }) {
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
            }
        }

        item(key = "records_main_tabs") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RecordTabChip(
                    label = stringResource(R.string.mine_recent_section),
                    selected = selectedMainTab == 0,
                    onClick = { selectedMainTab = 0 },
                    modifier = Modifier.weight(1f),
                )
                RecordTabChip(
                    label = stringResource(R.string.mine_cloud_records_title),
                    selected = selectedMainTab == 1,
                    onClick = { selectedMainTab = 1 },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item(key = "records_cloud_subtabs") {
            AnimatedVisibility(
                visible = selectedMainTab == 1,
                enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(180)) + expandVertically(tween(200, easing = FastOutSlowInEasing)),
                exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(140)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecordTabChip(
                        label = stringResource(R.string.netease_play_records_tab_recent),
                        selected = selectedCloudTab == 0,
                        onClick = { selectedCloudTab = 0 },
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    RecordTabChip(
                        label = stringResource(R.string.netease_play_records_tab_week),
                        selected = selectedCloudTab == 1,
                        onClick = { selectedCloudTab = 1 },
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    RecordTabChip(
                        label = stringResource(R.string.netease_play_records_tab_all),
                        selected = selectedCloudTab == 2,
                        onClick = { selectedCloudTab = 2 },
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (selectedMainTab == 0) {
            if (resolvedRecentTracks.isEmpty()) {
                item(key = "local_recent_empty") {
                    Text(
                        text = stringResource(R.string.netease_play_records_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = resolvedRecentTracks,
                    key = { index, track -> "local_recent_${track.id}_$index" },
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
                                onClick = {
                                    val startIdx = recentTrackIds.indexOf(track.id).coerceAtLeast(0)
                                    if (recentTrackIds.isNotEmpty()) onPlayTracks(recentTrackIds, startIdx)
                                },
                                onMoreClick = { contextTrackId = track.id },
                                onLongClick = { contextTrackId = track.id },
                                trailingContent = {
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
                    if (index < resolvedRecentTracks.lastIndex) {
                        HorizontalDivider(
                            color = colors.borderGlass,
                            modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                        )
                    }
                }
            }
        } else {
            if (!loggedIn) {
                item(key = "cloud_records_login_prompt") {
                    Text(
                        text = stringResource(R.string.netease_home_login_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            } else if (state.loading && activeCloudEntries.isEmpty()) {
                item(key = "records_loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
            } else if (activeCloudEntries.isEmpty()) {
                item(key = "records_empty") {
                    Text(
                        text = activeCloudError ?: stringResource(R.string.netease_play_records_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = activeCloudEntries,
                    key = { index, item -> "${selectedCloudTab}_${item.track.id}_$index" },
                ) { index, entry ->
                    val track = playableById[entry.track.id] ?: entry.track
                    val metric = when {
                        entry.playCount > 0 -> stringResource(R.string.netease_play_count_fmt, entry.playCount)
                        selectedCloudTab > 0 && entry.score > 0 -> stringResource(R.string.netease_play_score_fmt, entry.score)
                        else -> null
                    }
                    val prefix = if (selectedCloudTab > 0) "#${index + 1}" else null
                    val subtitle = listOfNotNull(
                        prefix,
                        track.artists.takeIf { it.isNotBlank() } ?: stringResource(R.string.common_unknown_artist),
                        metric,
                    ).joinToString(" · ")
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
                                onClick = {
                                    val startIdx = activeCloudTrackIds.indexOf(track.id).coerceAtLeast(0)
                                    if (activeCloudTrackIds.isNotEmpty()) onPlayTracks(activeCloudTrackIds, startIdx)
                                },
                                onMoreClick = { contextTrackId = track.id },
                                onLongClick = { contextTrackId = track.id },
                                trailingContent = {
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
                    if (index < activeCloudEntries.lastIndex) {
                        HorizontalDivider(
                            color = colors.borderGlass,
                            modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val colorSpec = if (reduceMotion) snap<Color>() else tween(180, easing = FastOutSlowInEasing)
    val bgColor by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.16f) else colors.glassElevated,
        animationSpec = colorSpec,
        label = "record-tab-bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.45f) else colors.borderGlass,
        animationSpec = colorSpec,
        label = "record-tab-border",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.textMain,
        animationSpec = colorSpec,
        label = "record-tab-text",
    )
    val shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
    Box(
        modifier = modifier
            .pressScale(interactionSource, pressedScale = 0.96f)
            .clip(shape)
            .background(bgColor)
            .border(0.5.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = if (compact) 6.dp else 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
