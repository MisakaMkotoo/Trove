package com.exodidio.tune.ui.screens

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.library.stagePlaylistArtwork
import com.exodidio.tune.netease.NeteaseEntryRecord
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.PlaylistMutation
import com.exodidio.tune.sync.PlaylistMutationOperation
import com.exodidio.tune.sync.PlaylistMutationPayload
import com.exodidio.tune.sync.albumIdFor
import com.exodidio.tune.sync.artistIdFor
import com.exodidio.tune.sync.trackDisplayArtists
import com.exodidio.tune.sync.trackDisplayTitle
import com.exodidio.tune.ui.components.ArtworkHeroBackdrop
import com.exodidio.tune.ui.components.DetailHero
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.components.TuneDialog
import com.exodidio.tune.ui.components.TuneTextField
import com.exodidio.tune.ui.components.TuneTextFieldSize
import com.exodidio.tune.ui.theme.LocalTuneColors
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NeteasePlaylistDetailsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reduceTransparency: Boolean = false,
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onHeroColorChanged: (androidx.compose.ui.graphics.Color) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by NeteaseRepository.activePlaylists.collectAsStateWithLifecycle()
    val selectedId by NeteaseRepository.selectedPlaylistId.collectAsStateWithLifecycle()
    val libraryTracks by AndroidSyncRuntime.syncStore.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playbackOnlyTracks by AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val localPlaylists by AndroidSyncRuntime.syncStore.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val playbackQueue by AndroidPlaybackRuntime.controller().queue.collectAsStateWithLifecycle()
    val playlist = remember(playlists, selectedId) { playlists.firstOrNull { it.id == selectedId } }
    val libraryById = remember(libraryTracks) { libraryTracks.associateBy { it.id } }
    val playableById = remember(libraryTracks, playbackOnlyTracks) {
        (libraryTracks + playbackOnlyTracks).associateBy { it.id }
    }
    var matchEntry by remember { mutableStateOf<NeteaseEntryRecord?>(null) }
    var contextEntryId by remember { mutableStateOf<Long?>(null) }
    var createTracks by remember { mutableStateOf<List<String>?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("") }
    var heroMenu by remember { mutableStateOf(false) }

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
            status = result.fold(onSuccess = { "" }, onFailure = { it.message ?: "下载失败" })
            busyId = null
        }
    }

    fun playEntry(entry: NeteaseEntryRecord) {
        val current = playlist ?: return
        // 整个歌单作为队列，上一首/下一首才可用。
        playQueue(current, playableId(entry))
    }

    fun playAll(shuffle: Boolean) {
        val current = playlist ?: return
        val ids = current.entries.map(::playableId).distinct()
        if (ids.isEmpty()) return
        val request = PlaybackRequest(ids, 0)
        if (shuffle) AndroidPlaybackRuntime.controller().shuffle(request)
        else AndroidPlaybackRuntime.controller().play(request)
    }

    /** 把歌单条目映射成曲目；优先本地匹配/已下载，否则登记流式曲目。 */
    fun entryTrack(entry: NeteaseEntryRecord): LibraryTrack = NeteaseRepository.entryTrack(entry)

    val current = playlist
    LaunchedEffect(current?.id) {
        val loaded = current ?: return@LaunchedEffect
        if (loaded.entries.isEmpty()) {
            status = "正在加载…"
            val started = System.currentTimeMillis()
            val result = NeteaseRepository.ensureDynamicLoaded(context, loaded.id)
            System.err.println(
                "NETOPEN id=${loaded.id} load=${System.currentTimeMillis() - started}ms " +
                    "error=${result.exceptionOrNull()?.message.orEmpty()}",
            )
            status = result.exceptionOrNull()?.message ?: ""
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
    // Apple Music 播放列表行：高 56dp、封面 48dp/圆角 4dp、标题 17sp、歌手 15sp、右侧菜单贴边 38dp。
    val appleTitleStyle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    )
    val appleArtistStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp)
    val appleRowPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 0.dp, bottom = 4.dp)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "netease-hero") {
            ArtworkHeroBackdrop(
                current.coverPath,
                Modifier.fillMaxWidth(),
                onDominantColorChanged = onHeroColorChanged,
                reduceTransparency = reduceTransparency,
            ) {
                DetailHero(
                    title = current.name,
                    metadata = stringResource(
                        R.string.netease_playlist_entry_count,
                        if (current.entries.isNotEmpty()) current.entries.size else current.remoteTrackCount,
                    ),
                    playLabel = stringResource(R.string.player_play),
                    shuffleLabel = stringResource(R.string.player_shuffle),
                    moreLabel = stringResource(R.string.album_row_more_options),
                    artworkPath = current.coverPath,
                    fallbackSymbol = MaterialSymbols.QueueMusic,
                    reduceTransparency = reduceTransparency,
                    modifier = Modifier.fillMaxWidth().padding(
                        start = 24.dp,
                        top = contentPadding.calculateTopPadding(),
                        end = 24.dp,
                        bottom = 20.dp,
                    ),
                    onPlayClick = { playAll(false) },
                    onShuffleClick = { playAll(true) },
                    onMoreClick = { heroMenu = true },
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
        itemsIndexed(current.entries, key = { _, entry -> entry.songId }) { index, entry ->
            val matched = entry.matchedTrackId?.let(libraryById::get)
            val downloaded = entry.downloadedFile?.let(::File)?.isFile == true
            val baseTrack = NeteaseRepository.entryTrack(entry)
            // 菜单读取 store 里的最新曲目：收藏状态切换后能立刻刷新。
            val track = playableById[baseTrack.id] ?: baseTrack
            TrackContextMenu(
                track = track,
                expanded = contextEntryId == entry.songId,
                onDismiss = { if (contextEntryId == entry.songId) contextEntryId = null },
                actions = TrackContextMenuActions(removeFromQueue = false, removeFromPlaylist = false),
                playbackQueue = playbackQueue,
                onPlayNext = { AndroidPlaybackRuntime.controller().playNext(playableId(entry)) },
                onAddToQueue = { AndroidPlaybackRuntime.controller().append(listOf(playableId(entry))) },
                onFavoriteChange = { item, favorite ->
                    scope.launch { AndroidSyncRuntime.syncStore.setFavorite(item.id, favorite) }
                },
                onDownload = { downloadOnly(entry) },
                onMatchSong = { matchEntry = entry },
                onGoToAlbum = { item -> if (item.albumId.isNotBlank()) onOpenAlbum(item.albumId) },
                onGoToArtist = { artist -> onOpenArtist(artist.id) },
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
                        artist = entry.artists.ifBlank { "未知歌手" },
                        artworkPath = matched?.artworkPath
                            ?: entry.coverPath?.let(::File)?.takeIf { it.isFile }?.absolutePath
                            ?: entry.downloadedFile?.let { File(it).parentFile }?.let { File(it, "${entry.songId}.cover.jpg") }?.takeIf { it.isFile }?.absolutePath,
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
                                MaterialSymbol(
                                    symbol = when {
                                        matched != null -> MaterialSymbols.Check
                                        downloaded -> MaterialSymbols.MusicNote
                                        else -> MaterialSymbols.ArrowDownward
                                    },
                                    contentDescription = stringResource(
                                        when {
                                            matched != null -> R.string.netease_state_local
                                            downloaded -> R.string.netease_state_downloaded
                                            else -> R.string.netease_state_missing
                                        },
                                    ),
                                    size = 18.dp,
                                    tint = if (matched != null || downloaded) colors.primary else colors.textMuted,
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
            if (index < current.entries.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                    color = colors.borderGlass,
                )
            }
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
    if (heroMenu) {
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
                                scope.launch { NeteaseRepository.refresh(context, current.id) }
                            },
                        ),
                    )
                    add(
                        ActionListItem(
                            labelRes = R.string.netease_download_all,
                            leadingSymbol = MaterialSymbols.ArrowDownward,
                            onClick = {
                                heroMenu = false
                                scope.launch { NeteaseRepository.downloadAll(context, current.id) }
                            },
                        ),
                    )
                    if (NeteaseRepository.isDynamic(current.id) || !current.addedToPlaylists) {
                        add(
                            ActionListItem(
                                labelRes = R.string.netease_save_snapshot,
                                leadingSymbol = MaterialSymbols.FavoriteBorder,
                                onClick = {
                                    heroMenu = false
                                    status = NeteaseRepository.saveSnapshot(current.id)
                                        ?.let { "已保存到播放列表" }
                                        ?: "保存失败"
                                },
                            ),
                        )
                    }
                },
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
    }
    matchEntry?.let { entry ->
        MatchTrackSheet(
            entry = entry,
            tracks = libraryTracks,
            boundTrackId = entry.matchedTrackId,
            onDismiss = { matchEntry = null },
            onSelect = { trackId ->
                NeteaseRepository.bindTrack(current.id, entry.songId, trackId)
                matchEntry = null
            },
        )
    }
}

@Composable
private fun MatchTrackSheet(
    entry: NeteaseEntryRecord,
    tracks: List<LibraryTrack>,
    boundTrackId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val colors = LocalTuneColors.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(tracks, query) {
        val text = query.trim()
        val list = if (text.isBlank()) tracks else tracks.filter {
            it.title.contains(text, true) || it.artists.contains(text, true)
        }
        list.take(80)
    }
    TuneBottomSheet(
        title = { Text(stringResource(R.string.netease_match_title, entry.title), color = colors.textMain) },
        onDismiss = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                TuneTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.netease_match_search),
                    size = TuneTextFieldSize.Medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            if (boundTrackId != null) {
                item {
                    Text(
                        text = stringResource(R.string.netease_clear_binding),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
            }
            itemsIndexed(filtered, key = { _, track -> track.id }) { _, track ->
                TrackRow(
                    title = trackDisplayTitle(track),
                    artist = trackDisplayArtists(track),
                    artworkPath = track.artworkPath,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    onClick = { onSelect(track.id) },
                )
            }
        }
    }
}
