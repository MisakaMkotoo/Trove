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
        // 曲库（含刚下载导入的曲目）优先于仅播放的流式副本，菜单才能立刻变成“删除下载”。
        (playbackOnlyTracks + libraryTracks).associateBy { it.id }
    }
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
            status = result.fold(onSuccess = { "" }, onFailure = { it.message ?: context.getString(R.string.netease_download_failed) })
            busyId = null
        }
    }

    fun playEntry(entry: NeteaseEntryRecord) {
        val current = playlist ?: return
        // 整个歌单作为队列，上一首/下一首才可用。
        val track = NeteaseRepository.entryTrack(entry)
        System.err.println(
            "PLAYENTRY song=${entry.songId} title=${entry.title} -> id=${track.id} audio=${track.audioPath}",
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

    /** 把歌单条目映射成曲目；网易云侧只用自己的下载文件或流式曲目。 */
    fun entryTrack(entry: NeteaseEntryRecord): LibraryTrack = NeteaseRepository.entryTrack(entry)

    val current = playlist
    // 逐行判断下载态/封面要 stat 文件：统一在 IO 做一次，行内只查结果。
    var entryFiles by remember(current?.entries, libraryById) {
        mutableStateOf(optimisticEntryFiles(current?.entries.orEmpty(), libraryById))
    }
    LaunchedEffect(current?.entries, libraryById) {
        val entries = current?.entries ?: return@LaunchedEffect
        entryFiles = withContext(Dispatchers.IO) {
            verifyEntryFiles(entries, libraryById, context.filesDir)
        }
    }
    LaunchedEffect(current?.id) {
        val loaded = current ?: return@LaunchedEffect
        if (loaded.entries.isEmpty()) {
            status = context.getString(R.string.netease_loading)
            val started = System.currentTimeMillis()
            val result = NeteaseRepository.ensureDynamicLoaded(context, loaded.id)
            System.err.println(
                "NETOPEN id=${loaded.id} load=${System.currentTimeMillis() - started}ms " +
                    "error=${result.exceptionOrNull()?.message.orEmpty()}",
            )
            status = result.exceptionOrNull()?.message ?: ""
        } else if (NeteaseRepository.isRadarPlaylist(loaded)) {
            // 雷达每日更新：每次打开都用云端最新内容覆盖，旧条目先展示、后台替换。
            NeteaseRepository.refresh(context, loaded.id)
                .onFailure {
                    System.err.println("NETOPEN radar refresh failed id=${loaded.id} err=${it.message}")
                    // 后台刷新失败要在页面内说清楚：旧条目继续可用，但不能假装刷新成功。
                    status = it.message ?: context.getString(R.string.netease_radar_refresh_failed)
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
    val remoteCover = rememberNeteaseImagePath(current.coverUrl, enabled = storedCover == null, sizePx = 800)
    val heroCoverPath = storedCover ?: remoteCover
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
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
                    metadata = stringResource(
                        R.string.netease_playlist_entry_count,
                        if (current.entries.isNotEmpty()) current.entries.size else current.remoteTrackCount,
                    ),
                    playLabel = stringResource(R.string.player_play),
                    shuffleLabel = stringResource(R.string.player_shuffle),
                    moreLabel = stringResource(R.string.album_row_more_options),
                    artworkPath = heroCoverPath,
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
            val downloaded = entry.songId in entryFiles.downloaded
            // 按条目缓存，避免每次重组都重新物化曲目/排封面下载。
            val baseTrack = remember(entry.songId) { NeteaseRepository.entryTrack(entry) }
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
                                status = context.getString(R.string.netease_refreshing)
                                scope.launch {
                                    status = NeteaseRepository.refresh(context, current.id)
                                        .fold(onSuccess = { "" }, onFailure = { it.message ?: context.getString(R.string.netease_refresh_failed) })
                                }
                            },
                        ),
                    )
                    add(
                        ActionListItem(
                            labelRes = R.string.netease_download_all,
                            leadingSymbol = MaterialSymbols.ArrowDownward,
                            onClick = {
                                heroMenu = false
                                status = context.getString(R.string.netease_download_in_progress)
                                scope.launch {
                                    val result = NeteaseRepository.downloadAll(context, current.id)
                                    status = if (result.total == 0) {
                                        context.getString(R.string.netease_download_none)
                                    } else {
                                        context.getString(
                                            R.string.netease_download_result,
                                            result.succeeded,
                                            result.total,
                                        )
                                    }
                                }
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
                                    scope.launch {
                                        status = NeteaseRepository.saveSnapshot(current.id)
                                            ?.let { context.getString(R.string.netease_saved_to_playlists) }
                                            ?: context.getString(R.string.netease_save_failed)
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

/** 一次性校验真实文件；之后行内只查集合，不再逐行 stat。 */
private fun verifyEntryFiles(
    entries: List<NeteaseEntryRecord>,
    libraryById: Map<String, LibraryTrack>,
    filesDir: File,
): DetailEntryFiles {
    val downloaded = HashSet<Long>()
    val artwork = HashMap<Long, String>()
    entries.forEach { entry ->
        val imported = libraryById["netease:${entry.songId}"]
        val entryFile = existingAbsolutePath(entry.downloadedFile, filesDir)
        val importedFile = existingAbsolutePath(
            imported?.audioPath?.takeIf { !it.startsWith("netease://") },
            filesDir,
        )
        if (entryFile != null || importedFile != null) downloaded += entry.songId
        val downloadedCover = entry.downloadedFile
            ?.let { audio -> File(audio).parentFile?.let { dir -> File(dir, "${entry.songId}.cover.jpg").path } }
        val cover = existingAbsolutePath(entry.coverPath, filesDir)
            ?: existingAbsolutePath(downloadedCover, filesDir)
        if (cover != null) artwork[entry.songId] = cover
    }
    return DetailEntryFiles(downloaded, artwork)
}

private fun existingAbsolutePath(path: String?, filesDir: File): String? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    val absolute = if (file.isAbsolute) file else File(filesDir, path)
    return absolute.takeIf { it.isFile }?.absolutePath
}
