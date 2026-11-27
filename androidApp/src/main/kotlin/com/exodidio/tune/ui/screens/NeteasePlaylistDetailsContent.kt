package com.exodidio.tune.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.library.stagePlaylistArtwork
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.MineAlbum
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.netease.NeteaseEntryRecord
import com.exodidio.tune.netease.NeteasePlaylistPermission
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.PlaylistMutation
import com.exodidio.tune.sync.PlaylistMutationOperation
import com.exodidio.tune.sync.PlaylistMutationPayload
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.ArtworkHeroBackdrop
import com.exodidio.tune.ui.components.DetailHero
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LibraryTextFilterSlot
import com.exodidio.tune.ui.components.LocalBatchChromeHost
import com.exodidio.tune.ui.components.LocalControlStyle
import com.exodidio.tune.ui.components.LocalLiquidGlassBlurDp
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
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.components.TuneDialog
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.rememberAnimatedGlassTint
import com.exodidio.tune.ui.components.rememberPullDownFilterState
import com.exodidio.tune.ui.components.tuneLiquidGlassBackground
import com.exodidio.tune.ui.theme.LocalTuneColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NeteasePlaylistDetailsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    hazeState: HazeState? = null,
    reduceTransparency: Boolean = false,
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onTrackContextBottomSheet: ((TrackContextBottomSheetRequest) -> Unit)? = null,
    onNavigateBack: () -> Unit = {},
    onHeroColorChanged: (androidx.compose.ui.graphics.Color) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by NeteaseRepository.activePlaylists.collectAsStateWithLifecycle()
    val selectedId by NeteaseRepository.selectedPlaylistId.collectAsStateWithLifecycle()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val albumSubscribedMap by NeteaseRepository.albumSubscribedMap.collectAsStateWithLifecycle()
    val libraryTracks by AndroidSyncRuntime.syncStore.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playbackOnlyTracks by AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val localPlaylists by AndroidSyncRuntime.syncStore.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val playbackQueue by AndroidPlaybackRuntime.controller().queue.collectAsStateWithLifecycle()
    val playlist = remember(playlists, selectedId) { playlists.firstOrNull { it.id == selectedId } }
    val libraryById = remember(libraryTracks) { libraryTracks.associateBy { it.id } }
    val playableById = remember(libraryTracks, playbackOnlyTracks) {
        // 曲库（含刚下载导入的曲目）优先于仅播放的流式副本，菜单才能立刻变成“删除下载”。
        (playbackOnlyTracks + libraryTracks).associateBy { it.id }
    }
    var contextEntryId by remember { mutableStateOf<Long?>(null) }
    var createTracks by remember { mutableStateOf<List<String>?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("") }
    var heroMenu by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var editBusy by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isReordering by remember(selectedId) { mutableStateOf(false) }
    var reorderBusy by remember(selectedId) { mutableStateOf(false) }
    var reorderedEntries by remember(selectedId) { mutableStateOf(playlist?.entries.orEmpty()) }
    var multiSelectMode by remember(selectedId) { mutableStateOf(false) }
    var selectedSongIds by remember(selectedId) { mutableStateOf<Set<Long>>(emptySet()) }
    var filterQuery by rememberSaveable(selectedId) { mutableStateOf("") }

    val updatedToast = stringResource(R.string.netease_playlist_updated)
    val deletedToast = stringResource(R.string.netease_playlist_deleted)
    val subscribedToast = stringResource(R.string.netease_playlist_subscribed)
    val unsubscribedToast = stringResource(R.string.netease_playlist_unsubscribed)
    val albumSubscribedToast = stringResource(R.string.netease_album_subscribed)
    val albumUnsubscribedToast = stringResource(R.string.netease_album_unsubscribed)
    val artistFollowedToast = stringResource(R.string.netease_artist_followed)
    val artistUnfollowedToast = stringResource(R.string.netease_artist_unfollowed)
    val wifiAutoCacheIds by NeteaseRepository.wifiAutoCacheIds.collectAsStateWithLifecycle()
    val wifiCacheEnabledToast = stringResource(R.string.netease_wifi_auto_cache_enabled)
    val wifiCacheDisabledToast = stringResource(R.string.netease_wifi_auto_cache_disabled)
    val dailyRefreshedToast = stringResource(R.string.netease_daily_refreshed)
    val removedTrackToast = stringResource(R.string.netease_track_removed_from_playlist)
    val reorderSavedToast = stringResource(R.string.netease_playlist_reorder_saved)
    val privacyPublicDoneToast = stringResource(R.string.netease_playlist_privacy_public_done)
    val privacyPrivateDoneToast = stringResource(R.string.netease_playlist_privacy_private_done)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")
    val loadingLabel = stringResource(R.string.netease_loading)
    val radarRefreshFailedLabel = stringResource(R.string.netease_radar_refresh_failed)
    val downloadFailedLabel = stringResource(R.string.netease_download_failed)
    val refreshingLabel = stringResource(R.string.netease_refreshing)
    val refreshFailedLabel = stringResource(R.string.netease_refresh_failed)
    val downloadInProgressLabel = stringResource(R.string.netease_download_in_progress)
    val downloadNoneLabel = stringResource(R.string.netease_download_none)
    val saveFailedLabel = stringResource(R.string.netease_save_failed)

    fun playableId(entry: NeteaseEntryRecord): String = NeteaseRepository.entryTrack(entry).id

    fun playQueue(current: com.exodidio.tune.netease.NeteasePlaylistRecord, trackId: String, shuffle: Boolean = false) {
        val ids = current.entries.map(::playableId).distinct()
        if (ids.isEmpty()) return
        val index = ids.indexOf(trackId).coerceAtLeast(0)
        val request = PlaybackRequest(ids, index)
        if (shuffle) AndroidPlaybackRuntime.controller().shuffle(request)
        else AndroidPlaybackRuntime.controller().play(request)
    }

    fun downloadOnly(entry: NeteaseEntryRecord) {
        val id = playlist?.id ?: return
        busyId = entry.songId
        scope.launch {
            val result = NeteaseRepository.ensureDownloaded(context, id, entry.songId)
            status = result.fold(onSuccess = { "" }, onFailure = { it.message ?: downloadFailedLabel })
            busyId = null
        }
    }

    fun playEntry(entry: NeteaseEntryRecord) {
        val current = playlist ?: return
        // 整个歌单作为队列，上一首/下一首才可用。
        val track = NeteaseRepository.entryTrack(entry)
        Log.d(
            "NeteasePlaylist",
            "play entry song=${entry.songId} title=${entry.title} -> id=${track.id} audio=${track.audioPath}",
        )
        playQueue(current, track.id)
    }

    fun playAll(shuffle: Boolean) {
        val current = playlist ?: return
        val ids = current.entries.map(::playableId).distinct()
        if (ids.isEmpty()) return
        val request = PlaybackRequest(ids, 0)
        if (shuffle) AndroidPlaybackRuntime.controller().shuffle(request)
        else AndroidPlaybackRuntime.controller().play(request)
    }

    val current = playlist
    // 逐行判断下载态/封面要 stat 文件：统一在 IO 做一次，行内只查结果。
    var entryFiles by remember(current?.entries, libraryById) {
        mutableStateOf(optimisticEntryFiles(current?.entries.orEmpty(), libraryById))
    }
    LaunchedEffect(current?.entries, libraryById) {
        val entries = current?.entries ?: return@LaunchedEffect
        entryFiles = withContext(Dispatchers.IO) {
            verifyEntryFiles(context, entries, libraryById, context.filesDir)
        }
    }
    LaunchedEffect(loggedIn) {
        if (loggedIn) NeteaseRepository.loadMine()
    }
    LaunchedEffect(current?.id) {
        val loaded = current ?: return@LaunchedEffect
        val needsHydration = NeteaseRepository.needsPlaylistHydration(loaded)
        if (needsHydration) {
            status = if (loaded.entries.isEmpty()) loadingLabel else refreshingLabel
            val started = System.currentTimeMillis()
            val result = NeteaseRepository.ensureDynamicLoaded(context, loaded.id)
            Log.d(
                "NeteasePlaylist",
                "open id=${loaded.id} load=${System.currentTimeMillis() - started}ms " +
                    "error=${result.exceptionOrNull()?.message.orEmpty()}",
            )
            status = result.exceptionOrNull()?.message ?: ""
        } else if (NeteaseRepository.isRadarPlaylist(loaded)) {
            // 雷达每日更新：每次打开都用云端最新内容覆盖，旧条目先展示、后台替换。
            NeteaseRepository.refresh(context, loaded.id)
                .onFailure {
                    Log.w("NeteasePlaylist", "radar refresh failed id=${loaded.id} err=${it.message}")
                    // 后台刷新失败要在页面内说清楚：旧条目继续可用，但不能假装刷新成功。
                    status = it.message ?: radarRefreshFailedLabel
                }
        }
    }
    if (current == null) {
        Text(
            text = stringResource(R.string.netease_playlists_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            modifier = modifier.padding(horizontal = 16.dp),
        )
        return
    }
    val permission = remember(current.id, current.dynamicKind, mine.created, loggedIn) {
        NeteaseRepository.playlistPermission(current.id)
    }
    val canRemoveTrack = loggedIn &&
        (permission == NeteasePlaylistPermission.Liked || permission == NeteasePlaylistPermission.OwnedEditable)
    val isOwnedPlaylist = loggedIn && permission == NeteasePlaylistPermission.OwnedEditable
    val isForeignPlaylist = loggedIn && permission == NeteasePlaylistPermission.ForeignSubscribable
    val isSubscribed = remember(current.id, mine.saved) {
        mine.saved.any { it.id == current.id }
    }
    val albumId = remember(current.id) {
        if (current.id.startsWith(NeteaseRepository.ALBUM_PAGE_PREFIX)) {
            current.id.removePrefix(NeteaseRepository.ALBUM_PAGE_PREFIX).takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
    val artistId = remember(current.id) {
        if (current.id.startsWith(NeteaseRepository.ARTIST_PAGE_PREFIX)) {
            current.id.removePrefix(NeteaseRepository.ARTIST_PAGE_PREFIX).takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
    LaunchedEffect(albumId, loggedIn) {
        if (loggedIn && albumId != null) {
            NeteaseRepository.checkAlbumSubscribed(albumId)
        }
    }
    val isAlbumSubscribed = remember(albumId, albumSubscribedMap, mine.albums) {
        albumId != null && (albumSubscribedMap[albumId] ?: mine.albums.any { it.id == albumId })
    }
    val isArtistFollowed = remember(artistId, mine.artists) {
        artistId != null && mine.artists.any { it.id == artistId }
    }

    // Apple Music 播放列表行：高 56dp、封面 48dp/圆角 4dp、标题 17sp、歌手 15sp、右侧菜单贴边 38dp。
    val appleTitleStyle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    )
    val appleArtistStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp)
    val appleRowPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 0.dp, bottom = 4.dp)
    // 封面缓存会被淘汰：先按记录乐观展示，再在 IO 线程确认文件是否还在，失效才回退到 URL。
    var storedCover by remember(current.coverPath) { mutableStateOf(current.coverPath) }
    LaunchedEffect(current.coverPath) {
        val path = current.coverPath
        storedCover = withContext(Dispatchers.IO) {
            path?.let { candidate -> File(candidate).takeIf { it.isFile }?.absolutePath }
        }
    }
    LaunchedEffect(current.id, current.entries, isReordering) {
        if (!isReordering) {
            reorderedEntries = current.entries
        } else if (filterQuery.isNotEmpty()) {
            filterQuery = ""
        }
    }
    val headerCount = 1 + (if (status.isNotBlank()) 1 else 0)
    val latestHeaderCount = rememberUpdatedState(headerCount)
    val latestReorderedEntries = rememberUpdatedState(reorderedEntries)
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val pullDownFilterState = rememberPullDownFilterState(
        listState = listState,
        filterKey = "netease-playlist:${current.id}",
        filterActive = filterQuery.isNotBlank(),
        enabled = !isReordering,
    )
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val list = latestReorderedEntries.value
        val offset = latestHeaderCount.value
        val fromIndex = from.index - offset
        val toIndex = to.index - offset
        if (fromIndex in list.indices && toIndex in list.indices && fromIndex != toIndex) {
            reorderedEntries = list.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    fun saveReorderedTracks() {
        if (reorderBusy) return
        val orderedIds = reorderedEntries.map { it.songId }
        if (orderedIds == current.entries.map { it.songId }) {
            isReordering = false
            return
        }
        reorderBusy = true
        scope.launch {
            NeteaseRepository.reorderNeteasePlaylistTracks(
                context = context,
                playlistId = current.id,
                orderedSongIds = orderedIds,
            ).onSuccess {
                reorderBusy = false
                isReordering = false
                Toast.makeText(context, reorderSavedToast, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                reorderBusy = false
                Toast.makeText(
                    context,
                    failedTemplate.replace("{err}", err.message.orEmpty()),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val remoteCover = rememberNeteaseImagePath(current.coverUrl, enabled = storedCover == null, sizePx = 800)
    val heroCoverPath = storedCover ?: remoteCover
    val displayEntries = if (isReordering) reorderedEntries else current.entries
    val trimmedQuery = filterQuery.trim()
    val visibleEntries = remember(displayEntries, trimmedQuery, isReordering) {
        if (isReordering || trimmedQuery.isEmpty()) {
            displayEntries
        } else {
            displayEntries.filter { entry ->
                entry.title.contains(trimmedQuery, ignoreCase = true) ||
                    entry.artists.contains(trimmedQuery, ignoreCase = true) ||
                    entry.album.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }
    val countText = stringResource(
        R.string.netease_playlist_entry_count,
        maxOf(current.entries.size, current.remoteTrackCount),
    )
    val privateBadge = stringResource(R.string.netease_playlist_private_badge)
    val heroMetadata = if (isOwnedPlaylist && current.privacy == 10) {
        "$countText · $privateBadge"
    } else {
        countText
    }
    BackHandler(enabled = multiSelectMode) {
        multiSelectMode = false
        selectedSongIds = emptySet()
    }
    val batchHost = LocalBatchChromeHost.current
    val batchId = remember(current.id) { Any() }
    val listHazeState = if (hazeState != null && (isReordering || batchHost == null)) rememberHazeState() else null
    val latestCurrentEntries by rememberUpdatedState(current.entries)
    val latestPlayableById by rememberUpdatedState(playableById)
    val latestLoggedIn by rememberUpdatedState(loggedIn)
    val latestCanRemoveTrack by rememberUpdatedState(canRemoveTrack)
    val latestOnTrackContextBottomSheet by rememberUpdatedState(onTrackContextBottomSheet)
    val batchHeroTopExtra by animateDpAsState(
        targetValue = if (multiSelectMode) 56.dp else 0.dp,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "netease-playlist-batch-top-padding",
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
                        val entries = latestCurrentEntries
                        val allSongIds = entries.map { it.songId }.toSet()
                        val currentSelected = selectedSongIds
                        MultiSelectSelectionHeader(
                            selectedCount = currentSelected.size,
                            allSelected = allSongIds.isNotEmpty() && currentSelected.size == allSongIds.size,
                            onToggleSelectAll = {
                                selectedSongIds = if (currentSelected.size == allSongIds.size) emptySet() else allSongIds
                            },
                            onExit = {
                                multiSelectMode = false
                                selectedSongIds = emptySet()
                            },
                        )
                    },
                    bottomContent = {
                        val entries = latestCurrentEntries
                        val currentSelected = selectedSongIds
                        val selectedEntries = entries.filter { it.songId in currentSelected }
                        val selectedTrackIds = selectedEntries.map(::playableId)
                        val addedToast = context.getString(R.string.batch_added_to_queue, selectedEntries.size)
                        val downloadToast = context.getString(R.string.batch_download_started, selectedEntries.size)
                        val removedToast = context.getString(R.string.batch_removed_done, selectedEntries.size)
                        val bottomSheetCallback = latestOnTrackContextBottomSheet
                        val mapPlayable = latestPlayableById
                        MultiSelectActionBar(
                            selectedCount = selectedEntries.size,
                            onPlayNext = {
                                if (selectedTrackIds.isNotEmpty()) {
                                    AndroidPlaybackRuntime.controller().playNext(selectedTrackIds)
                                    Toast.makeText(context, addedToast, Toast.LENGTH_SHORT).show()
                                    multiSelectMode = false
                                    selectedSongIds = emptySet()
                                }
                            },
                            onAddToPlaylist = if (bottomSheetCallback != null) {
                                {
                                    if (selectedTrackIds.isNotEmpty()) {
                                        bottomSheetCallback(
                                            TrackContextBottomSheetRequest.Playlist(
                                                trackIds = selectedTrackIds,
                                                addOnly = true,
                                                allowNeteasePlaylists = true,
                                            ),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            onNeteaseLike = if (latestLoggedIn) {
                                {
                                    if (selectedEntries.isNotEmpty()) {
                                        val snapshot = selectedEntries
                                        multiSelectMode = false
                                        selectedSongIds = emptySet()
                                        scope.launch {
                                            var likedCount = 0
                                            var lastError: Throwable? = null
                                            snapshot.forEach { entry ->
                                                val baseTrack = NeteaseRepository.entryTrack(entry)
                                                NeteaseRepository.setTrackLiked(context, baseTrack, entry.songId, true)
                                                    .onSuccess { likedCount++ }
                                                    .onFailure { lastError = it }
                                            }
                                            if (likedCount > 0) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.batch_liked_done, likedCount),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                lastError?.let { err ->
                                                    Toast.makeText(
                                                        context,
                                                        failedTemplate.replace("{err}", err.message.orEmpty()),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                            onDownload = {
                                if (selectedEntries.isNotEmpty()) {
                                    val snapshot = selectedEntries
                                    Toast.makeText(context, downloadToast, Toast.LENGTH_SHORT).show()
                                    multiSelectMode = false
                                    selectedSongIds = emptySet()
                                    scope.launch {
                                        snapshot.forEach { entry ->
                                            NeteaseRepository.ensureDownloaded(context, current.id, entry.songId)
                                        }
                                    }
                                }
                            },
                            onRemove = if (latestCanRemoveTrack) {
                                {
                                    if (selectedEntries.isNotEmpty()) {
                                        val pairs = selectedEntries.map { entry ->
                                            val baseTrack = NeteaseRepository.entryTrack(entry)
                                            entry.songId to (mapPlayable[baseTrack.id] ?: baseTrack)
                                        }
                                        scope.launch {
                                            NeteaseRepository.manipulateNeteasePlaylistTracks(
                                                context = context,
                                                playlistId = current.id,
                                                tracks = pairs,
                                                add = false,
                                            ).onSuccess {
                                                Toast.makeText(context, removedToast, Toast.LENGTH_SHORT).show()
                                                multiSelectMode = false
                                                selectedSongIds = emptySet()
                                            }.onFailure { err ->
                                                Toast.makeText(
                                                    context,
                                                    failedTemplate.replace("{err}", err.message.orEmpty()),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    },
                )
            } else {
                batchHost.dismiss(batchId)
            }
        }
    }
    Box(modifier.fillMaxSize().nestedScroll(pullDownFilterState.scrollConnection)) {
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
                bottom = contentPadding.calculateBottomPadding() + if ((batchHost == null && multiSelectMode) || isReordering) 84.dp else 0.dp,
            ),
        ) {
            item(key = "netease-hero") {
                ArtworkHeroBackdrop(
                    heroCoverPath,
                    Modifier.fillMaxWidth(),
                    onDominantColorChanged = onHeroColorChanged,
                    reduceTransparency = reduceTransparency,
                ) {
                    DetailHero(
                        title = current.name,
                        metadata = heroMetadata,
                        playLabel = stringResource(R.string.player_play),
                        shuffleLabel = stringResource(R.string.player_shuffle),
                        moreLabel = stringResource(
                            when {
                                isReordering -> R.string.netease_playlist_reorder_save
                                multiSelectMode -> R.string.batch_select_exit
                                else -> R.string.album_row_more_options
                            },
                        ),
                        moreSymbol = if (isReordering || multiSelectMode) MaterialSymbols.Check else MaterialSymbols.MoreVert,
                        artworkPath = heroCoverPath,
                        fallbackSymbol = MaterialSymbols.QueueMusic,
                        reduceTransparency = reduceTransparency,
                        modifier = Modifier.fillMaxWidth().padding(
                            start = 24.dp,
                            top = contentPadding.calculateTopPadding() + batchHeroTopExtra,
                            end = 24.dp,
                            bottom = 20.dp,
                        ),
                        onPlayClick = { playAll(false) },
                        onShuffleClick = { playAll(true) },
                        onMoreClick = {
                            when {
                                isReordering -> saveReorderedTracks()
                                multiSelectMode -> {
                                    multiSelectMode = false
                                    selectedSongIds = emptySet()
                                }
                                else -> heroMenu = true
                            }
                        },
                    )
                }
            }
            if (status.isNotBlank()) {
                item(key = "netease-status") {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
            if (!isReordering) {
                item(key = "netease-playlist-text-filter", contentType = "netease-playlist-text-filter") {
                    LibraryTextFilterSlot(state = pullDownFilterState) { showPlaceholder ->
                        LibraryTextFilter(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            placeholder = stringResource(R.string.filter_placeholder_search),
                            showPlaceholderAndLeadingSymbol = showPlaceholder,
                        )
                    }
                }
            }
            if (visibleEntries.isEmpty() && trimmedQuery.isNotEmpty()) {
                item(key = "netease-playlist-filter-empty", contentType = "netease-playlist-filter-empty") {
                    HeroCard(
                        symbol = MaterialSymbols.Search,
                        title = stringResource(R.string.tracks_no_match_title),
                        description = stringResource(R.string.filter_no_match_description),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
            itemsIndexed(visibleEntries, key = { _, entry -> entry.songId }) { index, entry ->
                val downloaded = entry.songId in entryFiles.downloaded
                // 按条目缓存，避免每次重组都重新物化曲目/排封面下载。
                val baseTrack = remember(entry.songId) { NeteaseRepository.entryTrack(entry) }
                // 菜单读取 store 里的最新曲目：收藏状态切换后能立刻刷新。
                val track = playableById[baseTrack.id] ?: baseTrack
                if (isReordering) {
                    ReorderableItem(reorderableState, key = entry.songId) { isDragging ->
                        TrackRow(
                            title = entry.title,
                            artist = entry.artists.ifBlank { stringResource(R.string.common_unknown_artist) },
                            artworkPath = entryFiles.artwork[entry.songId],
                            artworkUrl = entry.coverUrl,
                            contentPadding = appleRowPadding,
                            artworkCornerRadius = 4.dp,
                            titleStyle = appleTitleStyle,
                            artistStyle = appleArtistStyle,
                            moreButtonWidth = 38.dp,
                            modifier = Modifier.fillMaxWidth().then(
                                if (isDragging) {
                                    if (reduceTransparency) {
                                        Modifier.background(colors.glassOpaque).border(0.5.dp, colors.borderGlass)
                                    } else {
                                        Modifier.tuneLiquidGlassBackground(
                                            hazeState = hazeState,
                                            colors = colors,
                                            shape = androidx.compose.ui.graphics.RectangleShape,
                                            hazeBlurRadius = 30.dp,
                                            glassTint = colors.glassElevated,
                                            enableLens = false,
                                        ).border(0.5.dp, colors.borderGlass)
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        if (index > 0 && !reorderBusy) {
                                            reorderedEntries = reorderedEntries.toMutableList().apply {
                                                add(index - 1, removeAt(index))
                                            }
                                        }
                                    },
                                    enabled = index > 0 && !reorderBusy,
                                    modifier = Modifier.width(34.dp).height(48.dp),
                                ) {
                                    MaterialSymbol(
                                        symbol = MaterialSymbols.ArrowUpward,
                                        contentDescription = stringResource(R.string.netease_playlist_move_up),
                                        size = 18.dp,
                                        tint = if (index > 0 && !reorderBusy) colors.textMain else colors.textMuted.copy(alpha = 0.35f),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < displayEntries.lastIndex && !reorderBusy) {
                                            reorderedEntries = reorderedEntries.toMutableList().apply {
                                                add(index + 1, removeAt(index))
                                            }
                                        }
                                    },
                                    enabled = index < displayEntries.lastIndex && !reorderBusy,
                                    modifier = Modifier.width(34.dp).height(48.dp),
                                ) {
                                    MaterialSymbol(
                                        symbol = MaterialSymbols.ArrowDownward,
                                        contentDescription = stringResource(R.string.netease_playlist_move_down),
                                        size = 18.dp,
                                        tint = if (index < displayEntries.lastIndex && !reorderBusy) colors.textMain else colors.textMuted.copy(alpha = 0.35f),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .longPressDraggableHandle(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MaterialSymbol(
                                        symbol = MaterialSymbols.Menu,
                                        contentDescription = stringResource(R.string.playlist_reorder_drag_handle),
                                        size = 20.dp,
                                        tint = if (isDragging) colors.textMain else colors.textMuted,
                                    )
                                }
                            },
                        )
                    }
                } else if (multiSelectMode) {
                    val isSelected = entry.songId in selectedSongIds
                    val rowTint by animateColorAsState(
                        targetValue = if (isSelected) colors.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent,
                        animationSpec = if (LocalReduceMotion.current) snap() else tween(180, easing = FastOutSlowInEasing),
                        label = "netease-playlist-batch-row-tint",
                    )
                    TrackRow(
                        title = entry.title,
                        artist = entry.artists.ifBlank { stringResource(R.string.common_unknown_artist) },
                        artworkPath = entryFiles.artwork[entry.songId],
                        artworkUrl = entry.coverUrl,
                        contentPadding = appleRowPadding,
                        artworkCornerRadius = 4.dp,
                        titleStyle = appleTitleStyle,
                        artistStyle = appleArtistStyle,
                        moreButtonWidth = 38.dp,
                        modifier = Modifier.background(rowTint),
                        onClick = {
                            selectedSongIds = if (isSelected) selectedSongIds - entry.songId else selectedSongIds + entry.songId
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
                        expanded = contextEntryId == entry.songId,
                        onDismiss = { if (contextEntryId == entry.songId) contextEntryId = null },
                        actions = TrackContextMenuActions(
                            removeFromQueue = false,
                            removeFromPlaylist = canRemoveTrack,
                            neteaseLike = true,
                            allowNeteasePlaylists = true,
                        ),
                        hazeState = hazeState,
                        playbackQueue = playbackQueue,
                        onPlayNext = { AndroidPlaybackRuntime.controller().playNext(playableId(entry)) },
                        onAddToQueue = { AndroidPlaybackRuntime.controller().append(listOf(playableId(entry))) },
                        onFavoriteChange = { item, favorite ->
                            scope.launch { AndroidSyncRuntime.syncStore.setFavorite(item.id, favorite) }
                        },
                        onRemoveFromPlaylist = { item ->
                            scope.launch {
                                NeteaseRepository.manipulateNeteasePlaylistTracks(
                                    context = context,
                                    playlistId = current.id,
                                    tracks = listOf(entry.songId to item),
                                    add = false,
                                ).onSuccess {
                                    Toast.makeText(context, removedTrackToast, Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        failedTemplate.replace("{err}", err.message.orEmpty()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onDownload = { downloadOnly(entry) },
                        onGoToAlbum = { item -> if (item.albumId.isNotBlank()) onOpenAlbum(item.albumId) },
                        onGoToArtist = { artist -> onOpenArtist(artist.id) },
                        onBottomSheetRequested = onTrackContextBottomSheet,
                        playlists = localPlaylists.filter { it.id != FavoritesPlaylistId },
                        onPlaylistMembershipChange = { playlistId, trackIds, add ->
                            scope.launch {
                                trackIds.distinct().filter(String::isNotBlank).forEach { trackId ->
                                    AndroidSyncRuntime.syncStore.queuePlaylistMutation(
                                        PlaylistMutation(
                                            mutationId = UUID.randomUUID().toString(),
                                            playlistId = playlistId,
                                            operation = if (add) PlaylistMutationOperation.ADD_TRACK else PlaylistMutationOperation.REMOVE_TRACK,
                                            updatedAt = System.currentTimeMillis(),
                                            payload = PlaylistMutationPayload(trackId = trackId),
                                        ),
                                    )
                                }
                            }
                        },
                        onCreatePlaylistRequested = { trackIds -> createTracks = trackIds },
                        anchor = {
                            TrackRow(
                                title = entry.title,
                                artist = entry.artists.ifBlank { stringResource(R.string.common_unknown_artist) },
                                artworkPath = entryFiles.artwork[entry.songId],
                                artworkUrl = entry.coverUrl,
                                contentPadding = appleRowPadding,
                                artworkCornerRadius = 4.dp,
                                titleStyle = appleTitleStyle,
                                artistStyle = appleArtistStyle,
                                moreButtonWidth = 38.dp,
                                onClick = { playEntry(entry) },
                                onMoreClick = { contextEntryId = entry.songId },
                                onLongClick = { contextEntryId = entry.songId },
                                trailingContent = {
                                    if (busyId == entry.songId) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        // 网易云歌单只看自己的下载状态：已下载=音符，未下载=下载标志。
                                        // 本地/电脑曲库的匹配与这里无关，不再显示打勾。
                                        MaterialSymbol(
                                            symbol = if (downloaded) MaterialSymbols.MusicNote else MaterialSymbols.ArrowDownward,
                                            contentDescription = stringResource(
                                                if (downloaded) R.string.netease_state_downloaded else R.string.netease_state_missing,
                                            ),
                                            size = 18.dp,
                                            tint = if (downloaded) colors.primary else colors.textMuted,
                                        )
                                    }
                                    IconButton(
                                        onClick = { contextEntryId = entry.songId },
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
                if (index < visibleEntries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                        color = colors.borderGlass,
                    )
                }
            }
        }
        }

        if (isReordering) {
            val barShape = RoundedCornerShape(28.dp)
            val isReorderLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass
            val reorderBlurDp = LocalLiquidGlassBlurDp.current.dp
            val animatedReorderGlassTint = rememberAnimatedGlassTint(colors, animate = !LocalReduceMotion.current)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp,
                    )
                    .clip(barShape)
                    .border(0.5.dp, colors.borderGlass, barShape),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .tuneLiquidGlassBackground(
                            hazeState = listHazeState,
                            colors = colors,
                            shape = barShape,
                            hazeBlurRadius = 30.dp,
                            glassBlurRadius = reorderBlurDp,
                            glassTint = if (isReorderLiquidGlass) null else animatedReorderGlassTint,
                            enableLens = true,
                            lensRadius = 20.dp,
                            refractionHeight = 12.dp,
                            chromaticAberration = isReorderLiquidGlass && !LocalReduceMotion.current,
                        ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !reorderBusy) {
                                reorderedEntries = current.entries
                                isReordering = false
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    if (reorderBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = stringResource(R.string.netease_playlist_reorder_save),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = ::saveReorderedTracks)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        if (batchHost == null && multiSelectMode) {
            val allSongIds = remember(current.entries) { current.entries.map { it.songId }.toSet() }
            MultiSelectSelectionHeader(
                selectedCount = selectedSongIds.size,
                allSelected = allSongIds.isNotEmpty() && selectedSongIds.size == allSongIds.size,
                onToggleSelectAll = {
                    selectedSongIds = if (selectedSongIds.size == allSongIds.size) emptySet() else allSongIds
                },
                onExit = {
                    multiSelectMode = false
                    selectedSongIds = emptySet()
                },
                hazeState = listHazeState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = contentPadding.calculateTopPadding() + 4.dp, end = 16.dp),
            )
            val selectedEntries = remember(selectedSongIds, current.entries) {
                current.entries.filter { it.songId in selectedSongIds }
            }
            val selectedTrackIds = remember(selectedEntries) {
                selectedEntries.map(::playableId)
            }
            val batchAddedToast = stringResource(R.string.batch_added_to_queue, selectedEntries.size)
            val batchDownloadToast = stringResource(R.string.batch_download_started, selectedEntries.size)
            val batchRemovedToast = stringResource(R.string.batch_removed_done, selectedEntries.size)
            MultiSelectActionBar(
                selectedCount = selectedEntries.size,
                onPlayNext = {
                    if (selectedTrackIds.isNotEmpty()) {
                        AndroidPlaybackRuntime.controller().playNext(selectedTrackIds)
                        Toast.makeText(context, batchAddedToast, Toast.LENGTH_SHORT).show()
                        multiSelectMode = false
                        selectedSongIds = emptySet()
                    }
                },
                onAddToPlaylist = if (onTrackContextBottomSheet != null) {
                    {
                        if (selectedTrackIds.isNotEmpty()) {
                            onTrackContextBottomSheet(
                                TrackContextBottomSheetRequest.Playlist(
                                    trackIds = selectedTrackIds,
                                    addOnly = true,
                                    allowNeteasePlaylists = true,
                                ),
                            )
                        }
                    }
                } else {
                    null
                },
                onNeteaseLike = if (loggedIn) {
                    {
                        if (selectedEntries.isNotEmpty()) {
                            val snapshot = selectedEntries
                            multiSelectMode = false
                            selectedSongIds = emptySet()
                            scope.launch {
                                var likedCount = 0
                                var lastError: Throwable? = null
                                snapshot.forEach { entry ->
                                    val baseTrack = NeteaseRepository.entryTrack(entry)
                                    NeteaseRepository.setTrackLiked(context, baseTrack, entry.songId, true)
                                        .onSuccess { likedCount++ }
                                        .onFailure { lastError = it }
                                }
                                if (likedCount > 0) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.batch_liked_done, likedCount),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    lastError?.let { err ->
                                        Toast.makeText(
                                            context,
                                            failedTemplate.replace("{err}", err.message.orEmpty()),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                } else {
                    null
                },
                onDownload = {
                    if (selectedEntries.isNotEmpty()) {
                        val snapshot = selectedEntries
                        Toast.makeText(context, batchDownloadToast, Toast.LENGTH_SHORT).show()
                        multiSelectMode = false
                        selectedSongIds = emptySet()
                        scope.launch {
                            snapshot.forEach { entry ->
                                NeteaseRepository.ensureDownloaded(context, current.id, entry.songId)
                            }
                        }
                    }
                },
                onRemove = if (canRemoveTrack) {
                    {
                        if (selectedEntries.isNotEmpty()) {
                            val pairs = selectedEntries.map { entry ->
                                val baseTrack = NeteaseRepository.entryTrack(entry)
                                entry.songId to (playableById[baseTrack.id] ?: baseTrack)
                            }
                            scope.launch {
                                NeteaseRepository.manipulateNeteasePlaylistTracks(
                                    context = context,
                                    playlistId = current.id,
                                    tracks = pairs,
                                    add = false,
                                ).onSuccess {
                                    Toast.makeText(context, batchRemovedToast, Toast.LENGTH_SHORT).show()
                                    multiSelectMode = false
                                    selectedSongIds = emptySet()
                                }.onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        failedTemplate.replace("{err}", err.message.orEmpty()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                } else {
                    null
                },
                hazeState = listHazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }

    createTracks?.let { trackIds ->
        CreatePlaylistBottomSheet(
            onDismiss = { createTracks = null },
            onCreate = { name, artworkUri ->
                createTracks = null
                scope.launch {
                    val staged = artworkUri?.let { uri ->
                        withContext(Dispatchers.IO) {
                            runCatching { stagePlaylistArtwork(context.contentResolver, context.filesDir, uri) }.getOrNull()
                        }
                    }
                    val now = System.currentTimeMillis()
                    val playlistId = UUID.randomUUID().toString()
                    AndroidSyncRuntime.syncStore.createLocalPlaylist(
                        PlaylistMutation(
                            mutationId = UUID.randomUUID().toString(),
                            playlistId = playlistId,
                            operation = PlaylistMutationOperation.CREATE,
                            updatedAt = now,
                            payload = PlaylistMutationPayload(name = name),
                        ),
                        artwork = staged,
                        artworkMutationId = staged?.let { UUID.randomUUID().toString() },
                        initialTrackIds = trackIds,
                    )
                }
            },
        )
    }

    if (showEditSheet) {
        NeteasePlaylistEditorBottomSheet(
            isCreate = false,
            initialName = current.name,
            initialDescription = current.description,
            busy = editBusy,
            errorMessage = editError,
            onDismiss = {
                if (!editBusy) {
                    showEditSheet = false
                    editError = null
                }
            },
            onSubmit = { name, description, _ ->
                if (editBusy) return@NeteasePlaylistEditorBottomSheet
                editBusy = true
                editError = null
                scope.launch {
                    NeteaseRepository.updateNeteasePlaylist(
                        context = context,
                        playlistId = current.id,
                        name = name,
                        description = description,
                    ).onSuccess {
                        editBusy = false
                        showEditSheet = false
                        Toast.makeText(context, updatedToast, Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        editBusy = false
                        val msg = err.message.orEmpty()
                        editError = msg
                        Toast.makeText(context, failedTemplate.replace("{err}", msg), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    if (showDeleteConfirm) {
        TuneDialog(
            title = stringResource(R.string.netease_playlist_delete_confirm_title),
            description = stringResource(R.string.netease_playlist_delete_confirm_desc, current.name),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = stringResource(R.string.netease_playlist_delete),
            confirmVariant = TunePillButtonVariant.Destructive,
            onConfirm = {
                val targetId = current.id
                showDeleteConfirm = false
                scope.launch {
                    NeteaseRepository.deleteNeteasePlaylist(context, targetId)
                        .onSuccess {
                            Toast.makeText(context, deletedToast, Toast.LENGTH_SHORT).show()
                            onNavigateBack()
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

    // 已下载的音乐是本机文件生成的列表，刷新/下载全部/保存快照都没有意义。
    if (heroMenu && !NeteaseRepository.isDownloadsPage(current.id)) {
        TuneBottomSheet(
            title = { Text(current.name, color = colors.textMain) },
            onDismiss = { heroMenu = false },
        ) {
            ActionList(
                items = buildList {
                    add(
                        ActionListItem(
                            labelRes = R.string.netease_refresh,
                            leadingSymbol = MaterialSymbols.Refresh,
                            onClick = {
                                heroMenu = false
                                status = refreshingLabel
                                scope.launch {
                                    status = NeteaseRepository.refresh(context, current.id)
                                        .fold(onSuccess = { "" }, onFailure = { it.message ?: refreshFailedLabel })
                                }
                            },
                        ),
                    )
                    if (current.id == NeteaseRepository.DAILY_ID && loggedIn) {
                        add(
                            ActionListItem(
                                labelRes = R.string.netease_daily_refresh,
                                leadingSymbol = MaterialSymbols.Sync,
                                onClick = {
                                    heroMenu = false
                                    status = refreshingLabel
                                    scope.launch {
                                        NeteaseRepository.refreshDailyRecommend(context)
                                            .onSuccess {
                                                status = ""
                                                Toast.makeText(context, dailyRefreshedToast, Toast.LENGTH_SHORT).show()
                                            }
                                            .onFailure { err ->
                                                val msg = err.message ?: refreshFailedLabel
                                                status = msg
                                                Toast.makeText(
                                                    context,
                                                    failedTemplate.replace("{err}", msg),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                    }
                                },
                            ),
                        )
                    }
                    if (albumId != null && loggedIn) {
                        add(
                            ActionListItem(
                                labelRes = if (isAlbumSubscribed) {
                                    R.string.netease_album_unsubscribe
                                } else {
                                    R.string.netease_album_subscribe
                                },
                                leadingSymbol = if (isAlbumSubscribed) MaterialSymbols.PlaylistRemove else MaterialSymbols.PlaylistAdd,
                                onClick = {
                                    val nextSubscribed = !isAlbumSubscribed
                                    heroMenu = false
                                    scope.launch {
                                        val albumCard = MineAlbum(
                                            id = albumId,
                                            name = current.name,
                                            artistName = current.entries.firstOrNull()?.artists.orEmpty(),
                                            coverUrl = current.coverUrl,
                                            trackCount = current.entries.size.takeIf { it > 0 } ?: current.remoteTrackCount,
                                            subTimeMs = System.currentTimeMillis(),
                                        )
                                        NeteaseRepository.setAlbumSubscribed(
                                            context = context,
                                            album = albumCard,
                                            albumId = albumId,
                                            subscribe = nextSubscribed,
                                        ).onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (nextSubscribed) albumSubscribedToast else albumUnsubscribedToast,
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
                            ),
                        )
                    }
                    if (artistId != null && loggedIn) {
                        add(
                            ActionListItem(
                                labelRes = if (isArtistFollowed) {
                                    R.string.netease_artist_unfollow
                                } else {
                                    R.string.netease_artist_follow
                                },
                                leadingSymbol = if (isArtistFollowed) MaterialSymbols.HeartMinus else MaterialSymbols.HeartPlus,
                                onClick = {
                                    val nextFollowed = !isArtistFollowed
                                    heroMenu = false
                                    scope.launch {
                                        val artistCard = MineArtist(
                                            id = artistId,
                                            name = current.name,
                                            coverUrl = current.coverUrl,
                                        )
                                        NeteaseRepository.setArtistFollowed(
                                            context = context,
                                            artist = artistCard,
                                            artistId = artistId,
                                            follow = nextFollowed,
                                        ).onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (nextFollowed) artistFollowedToast else artistUnfollowedToast,
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
                            ),
                        )
                    }
                    add(
                        ActionListItem(
                            labelRes = R.string.netease_download_all,
                            leadingSymbol = MaterialSymbols.ArrowDownward,
                            onClick = {
                                heroMenu = false
                                status = downloadInProgressLabel
                                scope.launch {
                                    val result = NeteaseRepository.downloadAll(context, current.id)
                                    status = if (result.total == 0) {
                                        downloadNoneLabel
                                    } else {
                                        context.applicationContext.getString(
                                            R.string.netease_download_result,
                                            result.succeeded,
                                            result.total,
                                        )
                                    }
                                }
                            },
                        ),
                    )
                    if (current.entries.isNotEmpty()) {
                        add(
                            ActionListItem(
                                labelRes = R.string.batch_select_enter,
                                leadingSymbol = MaterialSymbols.Check,
                                onClick = {
                                    heroMenu = false
                                    isReordering = false
                                    multiSelectMode = true
                                    selectedSongIds = emptySet()
                                },
                            ),
                        )
                    }
                    if (isOwnedPlaylist) {
                        if (current.entries.size > 1) {
                            add(
                                ActionListItem(
                                    labelRes = R.string.netease_playlist_reorder,
                                    leadingSymbol = MaterialSymbols.Menu,
                                    onClick = {
                                        heroMenu = false
                                        multiSelectMode = false
                                        selectedSongIds = emptySet()
                                        reorderedEntries = current.entries
                                        isReordering = true
                                    },
                                ),
                            )
                        }
                        val currentlyPrivate = current.privacy == 10
                        add(
                            ActionListItem(
                                labelRes = if (currentlyPrivate) {
                                    R.string.netease_playlist_privacy_make_public
                                } else {
                                    R.string.netease_playlist_privacy_make_private
                                },
                                leadingSymbol = MaterialSymbols.Info,
                                onClick = {
                                    val makePrivate = !currentlyPrivate
                                    heroMenu = false
                                    scope.launch {
                                        NeteaseRepository.updateNeteasePlaylistPrivacy(
                                            context = context,
                                            playlistId = current.id,
                                            privacyPrivate = makePrivate,
                                        ).onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (makePrivate) privacyPrivateDoneToast else privacyPublicDoneToast,
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
                            ),
                        )
                        add(
                            ActionListItem(
                                labelRes = R.string.netease_playlist_edit,
                                leadingSymbol = MaterialSymbols.Edit,
                                onClick = {
                                    heroMenu = false
                                    editError = null
                                    showEditSheet = true
                                },
                            ),
                        )
                        add(
                            ActionListItem(
                                labelRes = R.string.netease_playlist_delete,
                                leadingSymbol = MaterialSymbols.Delete,
                                leadingIconTint = colors.primary,
                                onClick = {
                                    heroMenu = false
                                    showDeleteConfirm = true
                                },
                            ),
                        )
                    }
                    if (isForeignPlaylist) {
                        add(
                            ActionListItem(
                                labelRes = if (isSubscribed) {
                                    R.string.netease_playlist_unsubscribe
                                } else {
                                    R.string.netease_playlist_subscribe
                                },
                                leadingSymbol = if (isSubscribed) MaterialSymbols.PlaylistRemove else MaterialSymbols.PlaylistAdd,
                                onClick = {
                                    val nextSubscribed = !isSubscribed
                                    heroMenu = false
                                    scope.launch {
                                        val card = DiscoverPlaylist(
                                            id = current.id,
                                            name = current.name,
                                            subtitle = current.description,
                                            coverUrl = current.coverUrl,
                                            coverPath = current.coverPath,
                                            trackCount = current.entries.size.takeIf { it > 0 } ?: current.remoteTrackCount,
                                        )
                                        NeteaseRepository.setNeteasePlaylistSubscribed(
                                            context = context,
                                            playlist = card,
                                            playlistId = current.id,
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
                            ),
                        )
                    }
                    if (artistId == null) {
                        val wifiAutoEnabled = current.id in wifiAutoCacheIds
                        add(
                            ActionListItem(
                                labelRes = if (wifiAutoEnabled) {
                                    R.string.netease_wifi_auto_cache_off
                                } else {
                                    R.string.netease_wifi_auto_cache_on
                                },
                                leadingSymbol = MaterialSymbols.Sync,
                                onClick = {
                                    val nextEnabled = !wifiAutoEnabled
                                    heroMenu = false
                                    NeteaseRepository.setWifiAutoCacheEnabled(context, current.id, nextEnabled)
                                    Toast.makeText(
                                        context,
                                        if (nextEnabled) wifiCacheEnabledToast else wifiCacheDisabledToast,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ),
                        )
                    }
                    if (!isOwnedPlaylist && (NeteaseRepository.isDynamic(current.id) || !current.addedToPlaylists)) {
                        val isDynamicSnapshot = NeteaseRepository.isDynamicSnapshotPlaylist(
                            playlistId = current.id,
                            dynamicKind = current.dynamicKind,
                            name = current.name,
                            sourceUrl = current.sourceUrl,
                        )
                        val savedSnapshotLabel = stringResource(
                            if (isDynamicSnapshot) {
                                R.string.netease_saved_snapshot
                            } else {
                                R.string.netease_saved_to_playlists
                            },
                        )
                        add(
                            ActionListItem(
                                labelRes = if (isDynamicSnapshot) {
                                    R.string.netease_save_snapshot_dynamic
                                } else {
                                    R.string.netease_save_snapshot
                                },
                                leadingSymbol = MaterialSymbols.Library,
                                onClick = {
                                    heroMenu = false
                                    scope.launch {
                                        val savedId = NeteaseRepository.saveSnapshot(current.id)
                                        status = if (savedId != null) {
                                            Toast.makeText(context, savedSnapshotLabel, Toast.LENGTH_SHORT).show()
                                            savedSnapshotLabel
                                        } else {
                                            saveFailedLabel
                                        }
                                    }
                                },
                            ),
                        )
                    }
                },
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
    }

}

private data class DetailEntryFiles(
    val downloaded: Set<Long> = emptySet(),
    val artwork: Map<Long, String> = emptyMap(),
)

/** 先按歌单记录做纯内存判断，界面首帧不必等磁盘。 */
private fun optimisticEntryFiles(
    entries: List<NeteaseEntryRecord>,
    libraryById: Map<String, LibraryTrack>,
): DetailEntryFiles {
    val downloaded = HashSet<Long>()
    val artwork = HashMap<Long, String>()
    entries.forEach { entry ->
        val imported = libraryById["netease:${entry.songId}"]
        if (entry.downloadedFile != null || imported?.audioPath != null) downloaded += entry.songId
        val path = entry.coverPath
        if (!path.isNullOrBlank()) artwork[entry.songId] = path
    }
    return DetailEntryFiles(downloaded, artwork)
}

/** 一次性校验真实文件（支持私有文件与 SAF content:// URI）；之后行内只查集合，不再逐行 stat。 */
private fun verifyEntryFiles(
    context: android.content.Context,
    entries: List<NeteaseEntryRecord>,
    libraryById: Map<String, LibraryTrack>,
    filesDir: File,
): DetailEntryFiles {
    val downloaded = HashSet<Long>()
    val artwork = HashMap<Long, String>()
    entries.forEach { entry ->
        val imported = libraryById["netease:${entry.songId}"]
        val entryFile = existingAbsolutePath(context, entry.downloadedFile, filesDir)
        val importedFile = existingAbsolutePath(
            context,
            imported?.audioPath?.takeIf { !it.startsWith("netease://") },
            filesDir,
        )
        if (entryFile != null || importedFile != null) downloaded += entry.songId
        val downloadedCover = entry.downloadedFile
            ?.takeIf { !it.startsWith("content://", ignoreCase = true) }
            ?.let { audio -> File(audio).parentFile?.let { dir -> File(dir, "${entry.songId}.cover.jpg").path } }
        val cover = existingAbsolutePath(context, entry.coverPath, filesDir)
            ?: existingAbsolutePath(context, downloadedCover, filesDir)
        if (cover != null) artwork[entry.songId] = cover
    }
    return DetailEntryFiles(downloaded, artwork)
}

private fun existingAbsolutePath(
    context: android.content.Context,
    path: String?,
    filesDir: File,
): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("content://", ignoreCase = true)) {
        return path.takeIf { com.exodidio.tune.netease.NeteaseMediaHelper.isAudioPathAvailable(context, it) }
    }
    val file = File(path)
    val absolute = if (file.isAbsolute) file else File(filesDir, path)
    return absolute.takeIf { it.isFile }?.absolutePath
}
