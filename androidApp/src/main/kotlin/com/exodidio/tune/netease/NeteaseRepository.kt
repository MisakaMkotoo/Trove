package com.exodidio.tune.netease

import android.content.Context
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.albumIdFor
import com.exodidio.tune.sync.artistIdFor
import com.exodidio.tune.sync.metadataObject
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@Serializable
internal data class NeteaseEntryRecord(
    val songId: Long,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val coverPath: String? = null,
    val matchedTrackId: String? = null,
    val downloadedFile: String? = null,
    val downloadFormat: String? = null,
)

@Serializable
internal data class NeteasePlaylistRecord(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val importedAt: String = "",
    val coverPath: String? = null,
    val addedToPlaylists: Boolean = false,
    /** 动态歌单来源（daily/radar/rec）；普通导入为空。 */
    val dynamicKind: String? = null,
    /** 导入时保存的原始链接；刷新按它重新拉取并与远端完全同步。 */
    val sourceUrl: String = "",
    /** 远端歌单的歌曲数量；条目未加载时首页用它显示数量。 */
    val remoteTrackCount: Int = 0,
    val entries: List<NeteaseEntryRecord> = emptyList(),
)

/** 在线搜索返回的歌单/歌手/专辑元数据（封面可为已缓存的本地路径）。 */
data class OnlinePlaylistInfo(
    val id: Long,
    val name: String,
    val creator: String,
    val trackCount: Int,
    val coverUrl: String,
    val artworkPath: String? = null,
)

data class OnlineArtistInfo(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val artworkPath: String? = null,
)

data class OnlineAlbumInfo(
    val id: Long,
    val name: String,
    val artist: String,
    val coverUrl: String,
    val artworkPath: String? = null,
)

internal data class OnlineSearchResult(
    val songs: List<LibraryTrack> = emptyList(),
    val playlists: List<OnlinePlaylistInfo> = emptyList(),
    val artists: List<OnlineArtistInfo> = emptyList(),
    val albums: List<OnlineAlbumInfo> = emptyList(),
)

/** 心动/漫游卡片预览：启动时先取第一首（或一批），只展示封面，不播放。 */
data class NeteaseModePreview(
    val trackIds: List<String> = emptyList(),
    val artworkPath: String? = null,
    val seed: Long? = null,
)

/** 首页“雷达歌单”卡片元数据（名称/封面/数量，不含歌曲）。 */
@Serializable
data class NeteaseRadarSummary(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val coverPath: String? = null,
    val trackCount: Int = 0,
)

/** 官方雷达歌单目录；内容按账号在服务端个性化。 */
internal val RadarCatalog: List<Pair<String, String>> = listOf(
    "3136952023" to "私人雷达",
    "10106461201" to "怀旧雷达",
    "8402996200" to "会员雷达",
    "5300458264" to "新歌雷达",
    "8819359201" to "热点雷达",
    "5362359247" to "宝藏雷达",
    "5341776086" to "神秘雷达",
    "5320167908" to "时光雷达",
)

/**
 * 网易云歌单：只保存元数据和本地绑定，音频按需下载。
 * 网络与磁盘全部在 IO 线程，UI 只观察 StateFlow。
 */
internal object NeteaseRepository {
    private const val PREFS = "netease"
    private const val KEY_COOKIE = "cookie"
    private const val PLAYLIST_FILE = "netease-playlists.json"
    private const val LIKED_FILE = "netease-liked.json"
    private const val RADAR_FILE = "netease-radars.json"

    private val _playlists = MutableStateFlow(emptyList<NeteasePlaylistRecord>())
    val playlists: StateFlow<List<NeteasePlaylistRecord>> = _playlists.asStateFlow()

    private val _radars = MutableStateFlow(emptyList<NeteaseRadarSummary>())
    val radars: StateFlow<List<NeteaseRadarSummary>> = _radars.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<String?>(null)
    val selectedPlaylistId: StateFlow<String?> = _selectedPlaylistId.asStateFlow()

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    // 一键下载进度：歌单 ID -> (已完成, 总数)
    private var _homeStatus = MutableStateFlow("")
    val homeStatus: StateFlow<String> = _homeStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Pair<Int, Int>>> = _downloadProgress.asStateFlow()

    private var loaded = false
    private var appContext: Context? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 流播直链（10 分钟有效，过期后重新获取）。
    private val streamUrls = ConcurrentHashMap<Long, Pair<String, Long>>()

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        NeteaseStreamProxy.initialize(app)
        if (!loaded) {
            loaded = true
            _loggedIn.value = !cookie(app).isNullOrBlank()
            _playlists.value = runCatching {
                LibrarySyncProtocol.json.decodeFromString<List<NeteasePlaylistRecord>>(
                    File(app.filesDir, PLAYLIST_FILE).readText(),
                )
            }.getOrDefault(emptyList()).map { it.copy(addedToPlaylists = true) }
            // 红心歌单单独缓存：启动直接用它，不再自动联网刷新。
            runCatching {
                LibrarySyncProtocol.json.decodeFromString<NeteasePlaylistRecord>(
                    File(app.filesDir, LIKED_FILE).readText(),
                )
            }.getOrNull()?.let { liked ->
                _playlists.value = _playlists.value.filterNot { it.id == LIKED_ID } +
                    liked.copy(addedToPlaylists = false)
            }
            publishActive()
            // 雷达卡片元数据：启动先用缓存渲染，随后 loadHome 更新一次。
            _radars.value = runCatching {
                LibrarySyncProtocol.json.decodeFromString<List<NeteaseRadarSummary>>(
                    File(app.filesDir, RADAR_FILE).readText(),
                )
            }.getOrNull().orEmpty().ifEmpty {
                RadarCatalog.map { (id, name) -> NeteaseRadarSummary(id = id, name = name) }
            }
            _playlists.value.forEach { playlist ->
                ioScope.launch {
                    ensureEntryCovers(app, playlist.id)
                    if (playlist.id == LIKED_ID) persistLiked(app)
                }
            }
            ioScope.launch { backfillPlaybackCovers() }
        }
    }

    fun select(playlistId: String?) {
        _selectedPlaylistId.value = playlistId
    }

    /**
     * 搜索点开歌单：先用搜索结果元数据占位，详情页再后台加载条目。
     * 已有条目时不覆盖，再次点开直接就绪。
     */
    fun stagePlaylist(
        playlistId: String,
        name: String,
        coverUrl: String,
        coverPath: String?,
        trackCount: Int,
        dynamicKind: String? = null,
    ) {
        val existing = playlistById(playlistId)
        if (existing != null && existing.entries.isNotEmpty()) return
        val record = NeteasePlaylistRecord(
            id = playlistId,
            name = name.ifBlank { existing?.name ?: "网易云歌单" },
            coverUrl = coverUrl.ifBlank { existing?.coverUrl.orEmpty() },
            importedAt = existing?.importedAt ?: Instant.now().toString(),
            coverPath = coverPath ?: existing?.coverPath,
            addedToPlaylists = existing?.addedToPlaylists ?: false,
            dynamicKind = dynamicKind ?: existing?.dynamicKind,
            sourceUrl = existing?.sourceUrl?.ifBlank { "https://music.163.com/playlist?id=$playlistId" }
                ?: "https://music.163.com/playlist?id=$playlistId",
            remoteTrackCount = trackCount.takeIf { it > 0 } ?: existing?.remoteTrackCount ?: 0,
            entries = existing?.entries.orEmpty(),
        )
        _playlists.value = _playlists.value.filterNot { it.id == playlistId } + record
        publishActive()
    }

    /** 是否把歌单镜像到“播放列表”页；不影响设置里的导入记录。 */
    fun setAdded(playlistId: String, added: Boolean) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(addedToPlaylists = added) else playlist
        }
        publishActive()
        appContext?.let(::persist)
    }

    fun cookie(context: Context? = appContext): String? {
        context ?: return null
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, "").orEmpty()
        return value.takeIf { it.isNotBlank() }
    }

    fun clearLogin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_COOKIE).apply()
        _loggedIn.value = false
    }

    suspend fun createQrLogin(): Result<NeteaseLoginQr> = NeteaseClient.createQrLogin()

    suspend fun pollQrLogin(key: String, cookie: String?): Result<NeteaseQrStatus> =
        NeteaseClient.pollQrLogin(key, cookie)

    fun saveLogin(context: Context, cookie: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIE, cookie).apply()
        _loggedIn.value = true
    }

    /**
     * 拉取歌单元数据。搜索里点开歌单只导入不加入播放列表，保存动作留给详情页菜单；
     * 设置里粘贴链接导入仍然直接加入。
     */
    suspend fun import(
        context: Context,
        input: String,
        addToPlaylists: Boolean = true,
    ): Result<String> = withContext(Dispatchers.IO) {
        val id = NeteaseClient.playlistIdFrom(input)
            ?: return@withContext Result.failure(IllegalArgumentException("链接里找不到歌单 ID"))
        val started = System.currentTimeMillis()
        val payload = NeteaseClient.fetchPlaylist(id, cookie(context)).getOrElse {
            return@withContext Result.failure(it)
        }
        val fetched = System.currentTimeMillis()
        val base = buildRecord(
            context,
            payload,
            existing = _playlists.value.firstOrNull { it.id == id },
            sourceUrl = "https://music.163.com/playlist?id=$id",
        )
        System.err.println(
            "NETIMPORT id=$id entries=${payload.entries.size} " +
                "fetch=${fetched - started}ms build=${System.currentTimeMillis() - fetched}ms",
        )
        val record = if (addToPlaylists) base.copy(addedToPlaylists = true) else base
        _playlists.value = _playlists.value.filterNot { it.id == id } + record
        publishActive()
        if (record.addedToPlaylists) persist(context)
        ioScope.launch { ensureEntryCovers(context, id) }
        Result.success(id)
    }

    suspend fun refresh(context: Context, playlistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (playlistId == DAILY_ID) return@withContext loadDaily(context, force = true).map { }
        if (playlistId == RADAR_ID) return@withContext loadRadar(context, force = true).map { }
        if (playlistId == LIKED_ID) return@withContext loadLiked(context, force = true).map { }
        val existing = _playlists.value.firstOrNull { it.id == playlistId }
            ?: return@withContext Result.failure(IllegalStateException("歌单不存在"))
        // 每日推荐/私人雷达/我喜欢的音乐的本地快照：重新拉取动态列表，整体替换条目（远端删除的这里也会消失）。
        val snapshotKind = existing.dynamicKind?.takeIf { it.startsWith("snapshot:") }?.removePrefix("snapshot:")
        if (snapshotKind != null) {
            val loaded = when (snapshotKind) {
                "daily" -> loadDaily(context, force = true)
                "radar" -> loadRadar(context, force = true)
                "liked" -> loadLiked(context, force = true)
                else -> return@withContext Result.failure(IllegalStateException("未知快照来源"))
            }
            if (loaded.isFailure) return@withContext Result.failure(loaded.exceptionOrNull()!!)
            val dynamicId = if (snapshotKind == "daily") DAILY_ID else RADAR_ID
            val source = _playlists.value.firstOrNull { it.id == dynamicId }
                ?: return@withContext Result.failure(IllegalStateException("动态歌单未加载"))
            _playlists.value = _playlists.value.map {
                if (it.id == playlistId) {
                    it.copy(entries = source.entries, coverUrl = source.coverUrl, coverPath = source.coverPath)
                } else {
                    it
                }
            }
            publishActive()
            persist(context)
            ioScope.launch { ensureEntryCovers(context, playlistId) }
            return@withContext Result.success(Unit)
        }
        // 雷达歌单快照：重新拉取来源雷达，条目整体同步；名称里的日期保留。
        if (existing.dynamicKind == "radar" && existing.id.startsWith(SNAPSHOT_PREFIX)) {
            val sourceId = NeteaseClient.playlistIdFrom(existing.sourceUrl)
                ?: return@withContext Result.failure(IllegalStateException("快照缺少来源雷达"))
            val payload = NeteaseClient.fetchPlaylist(sourceId, cookie(context)).getOrElse {
                return@withContext Result.failure(it)
            }
            val rebuilt = buildRecord(context, payload, existing)
            val updated = rebuilt.copy(
                id = existing.id,
                name = existing.name,
                importedAt = existing.importedAt,
                dynamicKind = "radar",
                addedToPlaylists = true,
                sourceUrl = existing.sourceUrl,
            )
            _playlists.value = _playlists.value.map { if (it.id == playlistId) updated else it }
            publishActive()
            persist(context)
            ioScope.launch { ensureEntryCovers(context, playlistId) }
            return@withContext Result.success(Unit)
        }
        val payload = NeteaseClient.fetchPlaylist(playlistId, cookie(context)).getOrElse {
            return@withContext Result.failure(it)
        }
        val record = buildRecord(context, payload, existing)
        _playlists.value = _playlists.value.map { if (it.id == playlistId) record else it }
        publishActive()
        if (record.addedToPlaylists) persist(context)
        updateRadarSummary(context, record)
        ioScope.launch { ensureEntryCovers(context, playlistId) }
        Result.success(Unit)
    }

    /**
     * 详情页菜单的“保存到播放列表”：普通歌单直接加入本地播放列表；
     * 每日推荐/私人雷达/漫游/雷达歌单每次保存都生成一份新的带日期快照（不覆盖旧快照）。
     */
    fun saveSnapshot(playlistId: String): String? {
        val source = playlistById(playlistId) ?: return null
        val isRadar = source.dynamicKind == "radar"
        val kind = when {
            playlistId == DAILY_ID -> "daily"
            playlistId == RADAR_ID -> "radar"
            playlistId == LIKED_ID -> "liked"
            isRadar -> "radar"
            else -> {
                if (!source.addedToPlaylists) {
                    _playlists.value = _playlists.value.map {
                        if (it.id == playlistId) it.copy(addedToPlaylists = true) else it
                    }
                    publishActive()
                    appContext?.let(::persist)
                }
                return playlistId
            }
        }
        val date = java.time.LocalDate.now().toString()
        val baseName = "${when {
            kind == "daily" -> "每日推荐"
            kind == "radar" && isRadar -> source.name
            kind == "radar" -> "私人雷达"
            else -> "我喜欢的音乐"
        }} · $date"
        val usedNames = _playlists.value.mapTo(mutableSetOf()) { it.name }
        val name = generateSequence(1) { it + 1 }
            .map { if (it == 1) baseName else "$baseName ($it)" }
            .first { it !in usedNames }
        val snapshot = source.copy(
            id = "$SNAPSHOT_PREFIX$kind:${System.currentTimeMillis()}",
            name = name,
            importedAt = Instant.now().toString(),
            addedToPlaylists = true,
            dynamicKind = if (isRadar) "radar" else "snapshot:$kind",
            sourceUrl = if (isRadar) {
                "https://music.163.com/playlist?id=$playlistId"
            } else {
                source.sourceUrl
            },
        )
        _playlists.value = _playlists.value + snapshot
        appContext?.let(::persist)
        publishActive()
        return snapshot.id
    }

    suspend fun delete(playlistId: String) = withContext(Dispatchers.IO) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        if (_selectedPlaylistId.value == playlistId) _selectedPlaylistId.value = null
        publishActive()
        if (playlistId == LIKED_ID) appContext?.let { context -> File(context.filesDir, LIKED_FILE).delete() }
        appContext?.let(::persist)
    }

    fun bindTrack(playlistId: String, songId: Long, trackId: String?) {
        val playlist = playlistById(playlistId) ?: return
        updateRecord(
            playlist.copy(
                entries = playlist.entries.map { entry ->
                    if (entry.songId == songId) entry.copy(matchedTrackId = trackId) else entry
                },
            ),
        )
        appContext?.let(::persist)
    }

    /** 重新匹配所有未绑定曲目（导入或刷新时调用）。 */
    private fun rematchLibrary(entries: List<NeteaseEntryRecord>, previous: List<NeteaseEntryRecord>): List<NeteaseEntryRecord> {
        val tracks = AndroidSyncRuntime.syncStore.tracks.value
        val tracksById = tracks.associateBy { it.id }
        // 标题索引只建一次：避免每首歌都全量扫描曲库并重复编译正则。
        val tracksByTitle = tracks.groupBy { normalizeNeteaseMatch(it.title) }
        return entries.map { entry ->
            val previousBinding = previous.firstOrNull { it.songId == entry.songId }?.matchedTrackId
            val matched = when {
                previousBinding != null && previousBinding in tracksById -> previousBinding
                else -> bestMatch(entry, tracksByTitle)?.id
            }
            entry.copy(matchedTrackId = matched)
        }
    }

    /** 与导入完全一致的落库流程：匹配、下载歌单封面、下载曲目封面，然后发布。 */
    private suspend fun applyPayload(
        context: Context,
        id: String,
        payload: NeteasePlaylistPayload,
        dynamicKind: String? = null,
        addedToPlaylists: Boolean = true,
        awaitCovers: Boolean = true,
    ) {
        val existing = _playlists.value.firstOrNull { it.id == id }
        val base = buildRecord(context, payload, existing)
        val record = base.copy(id = id, dynamicKind = dynamicKind, addedToPlaylists = addedToPlaylists)
        _playlists.value = _playlists.value.filterNot { it.id == id } + record
        publishActive()
        if (record.addedToPlaylists) persist(context)
        // 与导入一致；等待封面补齐，保证首页/详情一出现就带封面。大歌单可改为后台补齐。
        if (awaitCovers) ensureEntryCovers(context, id)
        publishActive()
        persist(context)
    }

    private suspend fun buildRecord(
        context: Context,
        payload: NeteasePlaylistPayload,
        existing: NeteasePlaylistRecord?,
        sourceUrl: String? = null,
    ): NeteasePlaylistRecord {
        val fresh = payload.entries.map { entry ->
            val previous = existing?.entries?.firstOrNull { it.songId == entry.songId }
            NeteaseEntryRecord(
                songId = entry.songId,
                title = entry.title,
                artists = entry.artists,
                album = entry.album,
                durationMs = entry.durationMs,
                coverUrl = entry.coverUrl,
                // 复用已有封面文件（之前会话下载过），打开列表就能显示。
                coverPath = previous?.coverPath ?: NeteaseCoverStore.cached(context, entry.songId)?.absolutePath,
                matchedTrackId = previous?.matchedTrackId,
                downloadedFile = previous?.downloadedFile,
                downloadFormat = previous?.downloadFormat,
            )
        }
        val rematched = rematchLibrary(fresh, existing?.entries.orEmpty())
        return NeteasePlaylistRecord(
            id = payload.id,
            name = payload.name,
            coverUrl = payload.coverUrl,
            importedAt = existing?.importedAt ?: Instant.now().toString(),
            // 歌单封面按 URL 换文件名：封面变化时路径也变化，绕开图片缓存的旧键。
            coverPath = downloadPlaylistCover(context, payload.id, payload.coverUrl, existing?.coverUrl)
                ?: existing?.coverPath,
            // 保持原状态；首次创建由调用方决定是否加入播放列表。
            addedToPlaylists = existing?.addedToPlaylists ?: false,
            sourceUrl = sourceUrl ?: existing?.sourceUrl.orEmpty(),
            entries = rematched,
        )
    }

    private suspend fun downloadPlaylistCover(
        context: Context,
        playlistId: String,
        url: String,
        previousUrl: String? = null,
    ): String? {
        if (url.isBlank()) return null
        val dir = File(context.filesDir, "netease-covers").apply { mkdirs() }
        val suffix = url.hashCode().toUInt().toString(16)
        val target = File(dir, "${playlistId}_$suffix.img")
        // URL 没变且文件有效就直接复用；URL 变了就重新下载到新文件名。
        if (previousUrl == url && target.isFile && target.length() > 0) return target.absolutePath
        return NeteaseClient.download(url, cookie(context), target).getOrNull()?.let { target.absolutePath }
    }

    /** 下载并按需登记为“仅播放”曲目，返回可交给播放器的曲目 ID。 */
    suspend fun ensureDownloaded(
        context: Context,
        playlistId: String,
        songId: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val playlist = playlistById(playlistId) ?: error("歌单不存在")
            var entry = playlist.entries.firstOrNull { it.songId == songId } ?: error("歌曲不存在")
            val audioDir = File(context.filesDir, "netease-audio").apply { mkdirs() }
            var file = entry.downloadedFile?.let(::File)?.takeIf { it.isFile }
            if (file == null) {
                val url = NeteaseClient.fetchSongUrl(songId, cookie(context)).getOrThrow()
                file = File(audioDir, "$songId.${url.type.ifBlank { "mp3" }}")
                NeteaseClient.download(url.url, cookie(context), file).getOrThrow()
                entry = entry.copy(downloadedFile = file.absolutePath, downloadFormat = url.type)
                updateRecord(playlist.copy(entries = playlist.entries.map { if (it.songId == songId) entry else it }))
                persist(context)
            }
            val cover = downloadCover(context, audioDir, entry)
            val track = buildPlaybackTrack(entry, file, cover)
            // 下载即入曲库：和 NCM 导入走同一条“导入曲目”通道，参与曲目/近期添加/分类/搜索。
            val store = AndroidSyncRuntime.syncStore
            store.setImportedTracks(store.importedTracksSnapshot().filterNot { it.id == track.id } + track)
            track.id
        }
    }

    /** 交给 FFmpeg 的本地代理地址；缓存命中时完全不访问网络。 */
    fun playbackStreamUrl(songId: Long): String {
        NeteaseStreamProxy.initialize(appContext ?: error("repository not initialized"))
        return NeteaseStreamProxy.localUrl(songId)
    }

    fun hasCachedStream(songId: Long): Boolean = NeteaseStreamProxy.hasCache(songId)

    fun cachedStreamUrl(songId: Long): String? =
        streamUrls[songId]?.takeIf { it.second > System.currentTimeMillis() }?.first

    suspend fun streamUrl(context: Context, songId: Long): String? = withContext(Dispatchers.IO) {
        cachedStreamUrl(songId)?.let { return@withContext it }
        NeteaseClient.fetchSongUrl(songId, cookie(context)).getOrNull()?.let { url ->
            streamUrls[songId] = url.url to (System.currentTimeMillis() + 10 * 60_000L)
            NeteaseStreamProxy.setUpstream(songId, url.url)
            url.url
        }
    }

    suspend fun ensureStreamUrlForTrack(trackId: String): Boolean {
        val songId = songIdForTrack(trackId) ?: return false
        val ctx = appContext ?: return false
        return streamUrl(ctx, songId) != null
    }

    /**
     * 点播放时用：直链在有效期内直接复用；被断网打回或已过期就强制重新请求一次。
     * 每次点播放都算一次重连尝试，取不到返回 false（由播放服务退回暂停态）。
     */
    suspend fun ensureResumableStreamUrl(songId: Long): Boolean {
        val ctx = appContext ?: return false
        val reusable = cachedStreamUrl(songId) != null &&
            !NeteaseStreamProxy.hasRecentUpstreamError(songId)
        if (reusable) return true
        return refreshStreamUrl(ctx, songId) != null
    }

    private suspend fun refreshStreamUrl(context: Context, songId: Long): String? =
        withContext(Dispatchers.IO) {
            NeteaseClient.fetchSongUrl(songId, cookie(context)).getOrNull()?.let { url ->
                streamUrls[songId] = url.url to (System.currentTimeMillis() + 10 * 60_000L)
                NeteaseStreamProxy.setUpstream(songId, url.url)
                url.url
            }
        }

    fun playlistIdForSong(songId: Long): String? =
        activePlaylists.value.firstOrNull { playlist -> playlist.entries.any { it.songId == songId } }?.id

    fun prefetchStreamUrl(trackId: String) {
        val songId = songIdForTrack(trackId) ?: return
        val ctx = appContext ?: return
        ioScope.launch { runCatching { streamUrl(ctx, songId) } }
    }

    /**
     * 可播放条目：优先本地匹配/已下载文件；否则登记一条仅播放的流式曲目
     * （audioPath = netease://songId），供播放服务按需取直链。
     */
    fun entryTrack(entry: NeteaseEntryRecord): LibraryTrack {
        entry.matchedTrackId?.let { id -> AndroidSyncRuntime.syncStore.trackForPlayback(id)?.let { return it } }
        val existing = AndroidSyncRuntime.syncStore.trackForPlayback("netease:${entry.songId}")
        if (existing != null && existing.audioPath?.startsWith("netease://") != true) return existing
        return materializeStreamingTrack(entry)
    }

    /**
     * 唯一的流式曲目物化入口：先带已缓存的封面，再在后台补齐缺失封面并回写。
     * 所有歌曲列表（导入、动态歌单、心动模式、在线搜索）都必须走这里。
     */
    private fun materializeStreamingTrack(entry: NeteaseEntryRecord): LibraryTrack {
        val cover = appContext?.let { NeteaseCoverStore.cached(it, entry.songId) }
            ?: entry.coverPath?.let(::File)?.takeIf { it.isFile }
            ?: entry.downloadedFile?.let { File(it).parentFile }
                ?.let { File(it, "${entry.songId}.cover.jpg") }
                ?.takeIf { it.isFile }
        val track = buildStreamingTrack(entry, cover)
        AndroidSyncRuntime.syncStore.putPlaybackOnlyTrack(track)
        scheduleCover(entry)
        return track
    }

    /** 后台补封面：完成后把仅播放曲目的封面回写，列表/播放器自动刷新。 */
    private fun scheduleCover(entry: NeteaseEntryRecord) {
        if (entry.coverUrl.isBlank()) {
            System.err.println("COVER missing url song=${entry.songId} title=${entry.title}")
            return
        }
        val app = appContext ?: return
        if (NeteaseCoverStore.cached(app, entry.songId) != null) return
        ioScope.launch {
            val file = NeteaseCoverStore.ensure(app, entry.songId, entry.coverUrl, cookie(app)).await() ?: return@launch
            val store = AndroidSyncRuntime.syncStore
            val current = store.trackForPlayback("netease:${entry.songId}") ?: return@launch
            if (current.artworkPath != file.absolutePath) {
                store.putPlaybackOnlyTrack(current.copy(artworkPath = file.absolutePath))
                System.err.println("COVER filled song=${entry.songId} path=${file.absolutePath}")
            }
        }
    }

    /** 启动时补齐历史仅播放曲目的封面（老版本导入/心动模式留下的条目）。 */
    private fun backfillPlaybackCovers() {
        val app = appContext ?: return
        AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow.value
            .filter { it.id.startsWith("netease:") && it.artworkPath.isNullOrBlank() }
            .forEach { track ->
                val songId = track.id.removePrefix("netease:").toLongOrNull() ?: return@forEach
                val coverUrl = track.metadataObject()?.get("coverUrl")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                if (coverUrl.isBlank()) {
                    System.err.println("COVER missing url song=$songId id=${track.id}")
                    return@forEach
                }
                scheduleCover(
                    NeteaseEntryRecord(
                        songId = songId,
                        title = track.title,
                        artists = track.artists,
                        album = track.album,
                        coverUrl = coverUrl,
                    ),
                )
            }
    }

    /**
     * 在线搜索：歌曲只登记“仅播放”的流式曲目（不入曲库、不下载），
     * 歌单/歌手/专辑返回元数据，封面按 URL 走统一的图片缓存。
     */
    suspend fun searchOnline(query: String): Result<OnlineSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val app = appContext ?: error("网易云未初始化")
            val cookie = cookie(app)
            coroutineScope {
                val songsJob = async { NeteaseClient.searchSongs(query, limit = 30, cookie = cookie) }
                val playlistsJob = async { NeteaseClient.searchPlaylists(query, limit = 8, cookie = cookie) }
                val artistsJob = async { NeteaseClient.searchArtists(query, limit = 8, cookie = cookie) }
                val albumsJob = async { NeteaseClient.searchAlbums(query, limit = 8, cookie = cookie) }
                val songs = songsJob.await().getOrThrow().map { song ->
                    materializeStreamingTrack(
                        NeteaseEntryRecord(
                            songId = song.songId,
                            title = song.title,
                            artists = song.artists,
                            album = song.album,
                            durationMs = song.durationMs,
                            coverUrl = song.coverUrl,
                        ),
                    )
                }
                fun artwork(url: String): String? =
                    NeteaseImageStore.cached(app, url)?.absolutePath
                val playlists = playlistsJob.await().getOrDefault(emptyList()).map {
                    OnlinePlaylistInfo(it.id, it.name, it.creator, it.trackCount, it.coverUrl, artwork(it.coverUrl))
                }
                val artists = artistsJob.await().getOrDefault(emptyList()).map {
                    OnlineArtistInfo(it.id, it.name, it.coverUrl, artwork(it.coverUrl))
                }
                val albums = albumsJob.await().getOrDefault(emptyList()).map {
                    OnlineAlbumInfo(it.id, it.name, it.artist, it.coverUrl, artwork(it.coverUrl))
                }
                System.err.println(
                    "NETSEARCH q=$query songs=${songs.size} playlists=${playlists.size} artists=${artists.size} albums=${albums.size}",
                )
                OnlineSearchResult(songs, playlists, artists, albums)
            }
        }.onFailure { System.err.println("NETSEARCH failed q=$query err=${it.message}") }
    }

    /** 后台补齐歌单/歌手/专辑封面，完成后回调本地路径。 */
    fun ensureOnlineArtwork(url: String, onReady: (String) -> Unit) {
        if (url.isBlank()) return
        val app = appContext ?: return
        ioScope.launch {
            val file = NeteaseImageStore.ensure(app, url, cookie(app)).await() ?: return@launch
            onReady(file.absolutePath)
        }
    }

    private fun buildStreamingTrack(entry: NeteaseEntryRecord, cover: File?): LibraryTrack {
        val albumTitle = entry.album.ifBlank { "未知专辑" }
        val artists = entry.artists.ifBlank { "未知歌手" }
        val artistNames = artists.split(" / ").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(artists) }
        val albumId = albumIdFor(albumTitle)
        val seconds = entry.durationMs.coerceAtLeast(0L) / 1000L
        val metadata = buildJsonObject {
            put("title", entry.title)
            if (seconds > 0) put("duration", seconds)
            put(
                "artists",
                JsonArray(artistNames.map { name -> buildJsonObject { put("id", artistIdFor(name)); put("name", name) } }),
            )
            put("album", buildJsonObject { put("id", albumId); put("title", albumTitle) })
            // 保留封面地址：封面缓存补齐后可以直接回填，重进列表时无需再查接口。
            if (entry.coverUrl.isNotBlank()) put("coverUrl", entry.coverUrl)
        }.toString()
        val createdAt = Instant.now().toString()
        return LibraryTrack(
            id = "netease:${entry.songId}",
            title = entry.title,
            artists = artists,
            album = albumTitle,
            albumId = albumId,
            metadataJson = metadata,
            artworkPath = cover?.absolutePath,
            audioPath = "netease://${entry.songId}",
            createdAt = createdAt,
            updatedAt = createdAt,
            sortTitle = entry.title,
            sortArtists = artistNames.first(),
        )
    }

    private const val DAILY_ID = "dynamic:daily"
    private const val RADAR_ID = "dynamic:radar"
    private const val LIKED_ID = "dynamic:liked"
    private const val SNAPSHOT_PREFIX = "snapshot:"

    private val _activePlaylists = MutableStateFlow(emptyList<NeteasePlaylistRecord>())
    /** 持久化歌单 + 动态歌单（每日推荐/雷达/我喜欢的音乐），详情页共用。 */
    val activePlaylists: StateFlow<List<NeteasePlaylistRecord>> = _activePlaylists.asStateFlow()

    private fun publishActive() {
        _activePlaylists.value = _playlists.value
    }

    fun playlistById(id: String): NeteasePlaylistRecord? =
        _playlists.value.firstOrNull { it.id == id }

    fun isDynamic(id: String): Boolean = id == DAILY_ID || id == RADAR_ID || id == LIKED_ID

    private fun updateRecord(record: NeteasePlaylistRecord) {
        _playlists.value = _playlists.value.map { if (it.id == record.id) record else it }
        publishActive()
    }

    /** 每日推荐（按天缓存）。 */
    suspend fun loadDaily(context: Context, force: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = NeteaseClient.fetchDailyRecommend(cookie(context)).getOrThrow()
                applyPayload(context, DAILY_ID, payload, dynamicKind = "daily", addedToPlaylists = false)
                DAILY_ID
            }
        }

    /** 私人雷达（会话内缓存，刷新可重新拉取）。 */
    suspend fun loadRadar(context: Context, force: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = NeteaseClient.fetchRadar(cookie(context)).getOrThrow()
                applyPayload(context, RADAR_ID, payload.copy(name = "私人雷达"), dynamicKind = "radar", addedToPlaylists = false)
                RADAR_ID
            }
        }

    /** 登录用户的“我喜欢的音乐”：本质是真实歌单（specialType=5），按普通歌单浏览。 */
    suspend fun loadLiked(context: Context, force: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = cookie(context) ?: error("需要登录网易云")
                val knownId = playlistById(LIKED_ID)?.sourceUrl?.let { NeteaseClient.playlistIdFrom(it) }
                val likedPlaylistId = knownId
                    ?: NeteaseClient.fetchLikedPlaylistId(token).getOrThrow()
                    ?: error("找不到“我喜欢的音乐”")
                val payload = NeteaseClient.fetchPlaylist(likedPlaylistId, token).getOrThrow()
                applyPayload(
                    context,
                    LIKED_ID,
                    payload.copy(name = payload.name.ifBlank { "我喜欢的音乐" }),
                    dynamicKind = "liked",
                    addedToPlaylists = false,
                    awaitCovers = false,
                )
                // 先把列表落盘；封面在后台补齐后再落一次，避免上千首阻塞进入详情页。
                persistLiked(context)
                ioScope.launch {
                    ensureEntryCovers(context, LIKED_ID)
                    persistLiked(context)
                }
                LIKED_ID
            }
        }

    /**
     * 首页探针：只取红心歌单的名称/封面/数量，绝不拉歌曲列表。
     * 红心歌单可能上千首，整份拉取只在点开卡片时发生；有本地缓存时启动完全跳过。
     */
    suspend fun probeLiked(context: Context) = withContext(Dispatchers.IO) {
        val token = cookie(context) ?: return@withContext
        val summary = NeteaseClient.fetchLikedPlaylistSummary(token).getOrNull() ?: return@withContext
        val previous = playlistById(LIKED_ID)
        val record = NeteasePlaylistRecord(
            id = LIKED_ID,
            name = summary.name.ifBlank { "我喜欢的音乐" },
            coverUrl = summary.coverUrl,
            coverPath = downloadPlaylistCover(context, LIKED_ID.replace(':', '_'), summary.coverUrl)
                ?: previous?.coverPath,
            importedAt = previous?.importedAt ?: Instant.now().toString(),
            addedToPlaylists = false,
            dynamicKind = "liked",
            sourceUrl = "https://music.163.com/playlist?id=${summary.id}",
            remoteTrackCount = summary.trackCount,
        )
        _playlists.value = _playlists.value.filterNot { it.id == LIKED_ID } + record
        publishActive()
        persistLiked(context)
    }

    /** 红心歌单元数据（含每首歌封面路径）单独缓存，不走普通歌单文件（不能进播放列表页）。 */
    private fun persistLiked(context: Context) {
        val record = _playlists.value.firstOrNull { it.id == LIKED_ID } ?: return
        runCatching {
            File(context.filesDir, LIKED_FILE).writeText(
                LibrarySyncProtocol.json.encodeToString(record),
            )
        }
    }

    private const val RECO_PREFIX = "dynamic:rec:"

    /** 启动时更新一次雷达卡片元数据（只取摘要，不拉歌曲）。 */
    suspend fun refreshRadarSummaries(context: Context) = withContext(Dispatchers.IO) {
        if (cookie(context) == null) return@withContext
        val previous = _radars.value.associateBy { it.id }
        val fetched = coroutineScope {
            RadarCatalog.map { (id, fallbackName) ->
                async {
                    val summary = NeteaseClient.fetchPlaylistSummary(id, cookie(context)).getOrNull()
                    val coverUrl = summary?.coverUrl.orEmpty()
                    NeteaseRadarSummary(
                        id = id,
                        name = summary?.name?.takeIf { it.isNotBlank() } ?: previous[id]?.name ?: fallbackName,
                        coverUrl = coverUrl.ifBlank { previous[id]?.coverUrl.orEmpty() },
                        coverPath = if (coverUrl.isNotBlank()) {
                            downloadPlaylistCover(context, "radar_$id", coverUrl, previous[id]?.coverUrl)
                        } else {
                            previous[id]?.coverPath
                        },
                        trackCount = summary?.trackCount?.takeIf { it > 0 } ?: previous[id]?.trackCount ?: 0,
                    )
                }
            }.map { it.await() }
        }
        _radars.value = fetched
        persistRadars(context)
        System.err.println("NETRADARS updated=${fetched.size}")
    }

    /** 打开雷达歌单时顺带刷新它自己的卡片元数据。 */
    private fun updateRadarSummary(context: Context, record: NeteasePlaylistRecord) {
        if (RadarCatalog.none { it.first == record.id }) return
        _radars.value = _radars.value.map { radar ->
            if (radar.id == record.id) {
                radar.copy(
                    name = record.name,
                    coverUrl = record.coverUrl,
                    coverPath = record.coverPath ?: radar.coverPath,
                    trackCount = record.entries.size.takeIf { it > 0 } ?: radar.trackCount,
                )
            } else {
                radar
            }
        }
        persistRadars(context)
    }

    private fun persistRadars(context: Context) {
        runCatching {
            File(context.filesDir, RADAR_FILE).writeText(
                LibrarySyncProtocol.json.encodeToString(_radars.value),
            )
        }
    }

    /** 首页数据：每次启动自动拉取每日推荐、雷达和推荐歌单。 */
    suspend fun loadHome(context: Context) = withContext(Dispatchers.IO) {
        if (cookie(context).isNullOrBlank()) {
            _homeStatus.value = "未登录：请先在设置→网易云歌单里登录"
            return@withContext
        }
        // 首页只保留每日推荐与私人雷达；去掉官方通用推荐歌单，避免堆一堆不相干列表。
        if (_playlists.value.any { it.dynamicKind == "rec" }) {
            _playlists.value = _playlists.value.filterNot { it.dynamicKind == "rec" }
            publishActive()
        }
        val errors = mutableListOf<String>()
        runCatching { loadDaily(context) }.onFailure { errors += "每日推荐: ${it.message}" }
        runCatching { loadRadar(context) }.onFailure { errors += "雷达: ${it.message}" }
        // 红心歌单有本地缓存就直接用，启动不联网；没有缓存才取一次摘要。
        if (playlistById(LIKED_ID) == null) {
            runCatching { probeLiked(context) }.onFailure { errors += "我喜欢的音乐: ${it.message}" }
        }
        _homeStatus.value = errors.joinToString("\n")
        // 心动/漫游卡片：启动先各取一首/一批做封面预览，不播放。
        ioScope.launch { prefetchHeartbeatPreview(context) }
        ioScope.launch { prefetchRoamPreview(context) }
        // 雷达歌单：启动更新一次元数据。
        ioScope.launch { refreshRadarSummaries(context) }
        System.err.println(
            "NETHOME loaded daily=${playlistById(DAILY_ID)?.entries?.size} " +
                "radar=${playlistById(RADAR_ID)?.entries?.size} liked=${playlistById(LIKED_ID)?.entries?.size} errors=$errors",
        )
    }

    private suspend fun loadRecommended(context: Context) {
        val payloads = NeteaseClient.fetchRecommendedPlaylists(cookie(context)).getOrThrow().take(8)
        // 推荐歌单每次启动重建，避免累积旧卡片。
        _playlists.value = _playlists.value.filterNot { it.dynamicKind == "rec" }
        publishActive()
        payloads.forEach { summary ->
            runCatching {
                val detail = NeteaseClient.fetchPlaylist(summary.id, cookie(context)).getOrThrow()
                applyPayload(
                    context,
                    RECO_PREFIX + summary.id,
                    detail.copy(name = summary.name.ifBlank { detail.name }),
                    dynamicKind = "rec",
                    addedToPlaylists = false,
                )
            }
        }
    }

    /** 打开推荐歌单卡片时才拉详情 + 匹配 + 封面（与导入流程同一套）。 */
    suspend fun ensureDynamicLoaded(context: Context, playlistId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val existing = playlistById(playlistId)
                ?: return@withContext Result.failure(IllegalStateException("歌单不存在"))
            if (existing.entries.isNotEmpty()) return@withContext Result.success(Unit)
            when {
                playlistId == DAILY_ID -> loadDaily(context, force = true).map { }
                playlistId == RADAR_ID -> loadRadar(context, force = true).map { }
                playlistId == LIKED_ID -> loadLiked(context, force = true).map { }
                playlistId.startsWith(RECO_PREFIX) -> runCatching {
                    val sourceId = playlistId.removePrefix(RECO_PREFIX)
                    val payload = NeteaseClient.fetchPlaylist(sourceId, cookie(context)).getOrThrow()
                    applyPayload(context, playlistId, payload.copy(name = existing.name), dynamicKind = "rec", addedToPlaylists = false)
                }
                // 搜索点开的普通歌单：占位记录已在，这里补拉条目，页面不必等。
                playlistId.all(Char::isDigit) -> runCatching {
                    val started = System.currentTimeMillis()
                    val payload = NeteaseClient.fetchPlaylist(playlistId, cookie(context)).getOrThrow()
                    val fetched = System.currentTimeMillis()
                    applyPayload(
                        context,
                        playlistId,
                        payload,
                        dynamicKind = existing.dynamicKind,
                        addedToPlaylists = existing.addedToPlaylists,
                        awaitCovers = false,
                    )
                    System.err.println(
                        "NETLOAD id=$playlistId entries=${payload.entries.size} " +
                            "fetch=${fetched - started}ms build=${System.currentTimeMillis() - fetched}ms",
                    )
                    playlistById(playlistId)?.let { updateRadarSummary(context, it) }
                    ioScope.launch { ensureEntryCovers(context, playlistId) }
                    Unit
                }
                else -> Result.success(Unit)
            }
        }

    private suspend fun buildDynamicRecord(
        context: Context,
        id: String,
        payload: NeteasePlaylistPayload,
    ): NeteasePlaylistRecord {
        val entries = payload.entries.map { entry ->
            NeteaseEntryRecord(
                songId = entry.songId,
                title = entry.title,
                artists = entry.artists,
                album = entry.album,
                durationMs = entry.durationMs,
                coverUrl = entry.coverUrl,
            )
        }
        val matched = rematchLibrary(entries, emptyList())
        return NeteasePlaylistRecord(
            id = id,
            name = payload.name.ifBlank { if (id == DAILY_ID) "每日推荐" else "私人雷达" },
            coverUrl = payload.coverUrl,
            importedAt = Instant.now().toString(),
            addedToPlaylists = false,
            coverPath = downloadPlaylistCover(context, id.replace(':', '_'), payload.coverUrl),
            entries = matched,
        )
    }

    private val _heartbeatEnabled = MutableStateFlow(false)
    val heartbeatEnabled: StateFlow<Boolean> = _heartbeatEnabled.asStateFlow()
    private val recentHeartbeatIds = ArrayDeque<Long>()
    private var lastHeartbeatSongId: Long? = null
    private var cachedHeartbeatPlaylistId: String? = null

    private val _roamEnabled = MutableStateFlow(false)
    val roamEnabled: StateFlow<Boolean> = _roamEnabled.asStateFlow()
    private val recentRoamIds = ArrayDeque<Long>()

    private val _heartbeatPreview = MutableStateFlow(NeteaseModePreview())
    val heartbeatPreview: StateFlow<NeteaseModePreview> = _heartbeatPreview.asStateFlow()
    private val _roamPreview = MutableStateFlow(NeteaseModePreview())
    val roamPreview: StateFlow<NeteaseModePreview> = _roamPreview.asStateFlow()

    /** 启动时预取心动第一首，仅用于卡片封面；点开时才真正播放。 */
    suspend fun prefetchHeartbeatPreview(context: Context) = withContext(Dispatchers.IO) {
        if (_heartbeatEnabled.value || _heartbeatPreview.value.trackIds.isNotEmpty()) return@withContext
        if (cookie(context) == null) return@withContext
        runCatching {
            // 启动时没有“当前歌曲”，用红心歌单最新添加的一首当预览种子（只用缓存，不拉整份歌单）。
            val seed = playlistById(LIKED_ID)?.entries?.firstOrNull()?.songId ?: return@withContext
            val entry = fetchNextHeartbeat(context, seed, startSongId = seed) ?: return@withContext
            val track = entryTrack(entry)
            _heartbeatPreview.value = NeteaseModePreview(
                trackIds = listOf(track.id),
                artworkPath = track.artworkPath,
                seed = seed,
            )
        }.onFailure { System.err.println("NETHB preview failed: ${it.message}") }
    }

    /** 启动时预取漫游一批，仅用于卡片封面；点开时才真正播放。 */
    suspend fun prefetchRoamPreview(context: Context) = withContext(Dispatchers.IO) {
        if (_roamEnabled.value || _roamPreview.value.trackIds.isNotEmpty()) return@withContext
        if (cookie(context) == null) return@withContext
        runCatching {
            val ids = fetchRoamBatch(context) ?: return@withContext
            val track = AndroidSyncRuntime.syncStore.trackForPlayback(ids.first())
            _roamPreview.value = NeteaseModePreview(
                trackIds = ids,
                artworkPath = track?.artworkPath,
            )
        }.onFailure { System.err.println("NETROAM preview failed: ${it.message}") }
    }

    /** 心动与漫游互斥，同时只保留一个连续推荐模式。 */
    fun setHeartbeatEnabled(enabled: Boolean) {
        _heartbeatEnabled.value = enabled
        if (enabled) {
            _roamEnabled.value = false
            recentRoamIds.clear()
        } else {
            recentHeartbeatIds.clear()
        }
    }

    fun setRoamEnabled(enabled: Boolean) {
        _roamEnabled.value = enabled
        if (enabled) {
            _heartbeatEnabled.value = false
            recentHeartbeatIds.clear()
        } else {
            recentRoamIds.clear()
        }
    }

    /** 开启漫游：取一批推荐，返回可播放曲目 ID 列表。 */
    suspend fun startRoam(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (cookie(context) == null) error("漫游需要登录网易云")
            setRoamEnabled(true)
            val preview = _roamPreview.value
            if (preview.trackIds.isNotEmpty()) {
                _roamPreview.value = preview.copy(trackIds = emptyList())
                return@runCatching preview.trackIds
            }
            fetchRoamBatch(context) ?: error("漫游暂时没有推荐")
        }.onFailure { setRoamEnabled(false) }
    }

    /** 队列末尾自动续一批漫游歌曲；失败返回 null，由播放服务退避重试。 */
    suspend fun appendRoamNext(context: Context): List<String>? = withContext(Dispatchers.IO) {
        if (!_roamEnabled.value) return@withContext null
        fetchRoamBatch(context)
    }

    private suspend fun fetchRoamBatch(context: Context): List<String>? {
        val songs = NeteaseClient.fetchRoamSongs(cookie(context))
            .onFailure { System.err.println("NETROAM failed: ${it.message}") }
            .getOrNull()
            .orEmpty()
        if (songs.isEmpty()) return null
        val fresh = songs.filter { it.songId !in recentRoamIds }.ifEmpty { songs }
        val coverDir = File(context.filesDir, "netease-covers").apply { mkdirs() }
        return fresh.map { entry ->
            recentRoamIds.addLast(entry.songId)
            while (recentRoamIds.size > 30) recentRoamIds.removeFirst()
            val record = NeteaseEntryRecord(
                songId = entry.songId,
                title = entry.title,
                artists = entry.artists,
                album = entry.album,
                durationMs = entry.durationMs,
                coverUrl = entry.coverUrl,
            )
            val cover = downloadCover(context, coverDir, record)
            entryTrack(record.copy(coverPath = cover?.absolutePath)).id
        }
    }

    /** 以一首歌为基准取相似歌曲列表（页面使用）。 */
    suspend fun similarSongs(context: Context, trackId: String): Result<List<LibraryTrack>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val songId = songIdForTrack(trackId) ?: error("无法识别这首歌的网易云 ID")
                val entries = NeteaseClient.fetchSimilarSongs(songId, limit = 30, offset = 0, cookie(context))
                    .getOrThrow()
                entries.map { entry ->
                    entryTrack(
                        NeteaseEntryRecord(
                            songId = entry.songId,
                            title = entry.title,
                            artists = entry.artists,
                            album = entry.album,
                            durationMs = entry.durationMs,
                            coverUrl = entry.coverUrl,
                        ),
                    )
                }
            }
        }

    /** 开启心动模式并从种子里播第一首；返回可播放曲目 ID。 */
    suspend fun startHeartbeat(context: Context, seedSongId: Long? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                setHeartbeatEnabled(true)
                if (cookie(context) == null) error("心动模式需要登录网易云")
                val preview = _heartbeatPreview.value
                if (preview.trackIds.isNotEmpty() && (seedSongId == null || preview.seed == seedSongId)) {
                    _heartbeatPreview.value = preview.copy(trackIds = emptyList())
                    return@runCatching preview.trackIds.first()
                }
                // 预览不匹配当前种子时作废曲目，只保留封面。
                _heartbeatPreview.value = preview.copy(trackIds = emptyList())
                // 没有可用的当前歌曲（本地歌/没在播）时，用红心歌单最新添加的一首当种子。
                val seed = seedSongId ?: likedNewestSongId(context) ?: error("没有可用的种子歌曲")
                val entry = fetchNextHeartbeat(context, seed, startSongId = seed) ?: error("心动模式暂时没有推荐")
                entryTrack(entry).id
            }.onFailure { _heartbeatEnabled.value = false }
        }

    private suspend fun likedNewestSongId(context: Context): Long? {
        playlistById(LIKED_ID)?.entries?.firstOrNull()?.songId?.let { return it }
        loadLiked(context).getOrNull()
        return playlistById(LIKED_ID)?.entries?.firstOrNull()?.songId
    }

    /**
     * 队列末尾自动续歌；失败只返回 null，由播放服务按退避重试，不关闭心动模式。
     * queueHeadTrackId 是心动队列队首，用于接口的 startMusicId。
     */
    suspend fun appendHeartbeatNext(
        context: Context,
        currentTrackId: String,
        queueHeadTrackId: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (!_heartbeatEnabled.value) return@withContext null
        val seed = songIdForTrack(currentTrackId) ?: lastHeartbeatSongId ?: return@withContext null
        val startSongId = queueHeadTrackId?.let(::songIdForTrack) ?: seed
        val entry = fetchNextHeartbeat(context, seed, startSongId) ?: run {
            System.err.println("NETHB append: no next (seed=$seed start=$startSongId)")
            return@withContext null
        }
        entryTrack(entry).id.also { id -> System.err.println("NETHB append: next=${entry.songId} track=$id") }
    }

    private suspend fun fetchNextHeartbeat(context: Context, seed: Long, startSongId: Long): NeteaseEntryRecord? {
        val sourcePlaylistId = heartbeatPlaylistId(context, seed)
        val list = NeteaseClient.fetchHeartbeatNext(seed, startSongId, sourcePlaylistId, cookie(context))
            .onFailure { System.err.println("NETHB repository failed: ${it.message}") }
            .getOrNull()
            .orEmpty()
        val entry = list.firstOrNull { payload ->
            payload.songId !in recentHeartbeatIds &&
                AndroidSyncRuntime.syncStore.trackForPlayback("netease:${payload.songId}") == null
        } ?: list.firstOrNull { it.songId !in recentHeartbeatIds }
        if (entry == null) return null
        lastHeartbeatSongId = entry.songId
        recentHeartbeatIds.addLast(entry.songId)
        while (recentHeartbeatIds.size > 20) recentHeartbeatIds.removeFirst()
        val record = NeteaseEntryRecord(
            songId = entry.songId,
            title = entry.title,
            artists = entry.artists,
            album = entry.album,
            durationMs = entry.durationMs,
            coverUrl = entry.coverUrl,
        )
        // 心动模式没有歌单条目可走 ensureEntryCovers，这里单独把封面落盘，播放器才拿得到 artworkPath。
        val coverDir = File(context.filesDir, "netease-covers").apply { mkdirs() }
        val cover = downloadCover(context, coverDir, record)
        System.err.println("NETHB cover url=${entry.coverUrl.isNotBlank()} file=${cover?.name ?: "none"}")
        return record.copy(coverPath = cover?.absolutePath)
    }

    /** 心动模式的 pid：普通歌单可用；私人雷达等特殊歌单接口会报“不支持该歌单类型”。 */
    private fun isSupportedHeartbeatPid(playlistId: String?): Boolean =
        playlistId != null &&
            playlistId.all(Char::isDigit) &&
            playlistId != NeteaseClient.PrivateRadarPlaylistId

    private suspend fun heartbeatPlaylistId(context: Context, seed: Long): String? {
        val source = playlistIdForSong(seed)
        if (isSupportedHeartbeatPid(source)) {
            System.err.println("NETHB pid source=playlist pid=$source seed=$seed")
            return source
        }
        cachedHeartbeatPlaylistId?.takeIf(::isSupportedHeartbeatPid)?.let {
            System.err.println("NETHB pid source=cache pid=$it seed=$seed")
            return it
        }
        val cookie = cookie(context) ?: return null
        val liked = NeteaseClient.fetchLikedPlaylistId(cookie)
            .onFailure { System.err.println("NETHB liked playlist failed: ${it.message}") }
            .getOrNull()
        cachedHeartbeatPlaylistId = liked
        System.err.println("NETHB pid source=liked pid=$liked seed=$seed sourcePlaylist=$source")
        return liked?.takeIf(::isSupportedHeartbeatPid)
    }

    /** 播放曲目对应的网易云歌曲 ID（用于歌词等按 ID 查询）。 */
    fun songIdForTrack(trackId: String): Long? {
        if (trackId.startsWith("netease:")) return trackId.removePrefix("netease:").toLongOrNull()
        activePlaylists.value.forEach { playlist ->
            playlist.entries.firstOrNull { it.matchedTrackId == trackId }?.let { return it.songId }
        }
        return null
    }

    /** 该歌单当前可加入播放队列的曲目（已匹配本地或已下载）。 */
    fun playableTrackIds(playlistId: String): List<String> {
        val playlist = playlistById(playlistId) ?: return emptyList()
        return playlist.entries.map { entry -> entryTrack(entry).id }.distinct()
    }

    /** 一键下载歌单里所有未匹配本地、未下载的歌；下载就是本地保存，不再需要转存。 */
    suspend fun downloadAll(context: Context, playlistId: String) = withContext(Dispatchers.IO) {
        val playlist = playlistById(playlistId) ?: return@withContext
        val libraryIds = AndroidSyncRuntime.syncStore.tracks.value.mapTo(mutableSetOf()) { it.id }
        val pending = playlist.entries.filter { entry ->
            entry.matchedTrackId !in libraryIds && entry.downloadedFile?.let(::File)?.isFile != true
        }
        if (pending.isEmpty()) return@withContext
        _downloadProgress.update { it + (playlistId to (0 to pending.size)) }
        var done = 0
        for (entry in pending) {
            runCatching { ensureDownloaded(context, playlistId, entry.songId) }
            done++
            _downloadProgress.update { it + (playlistId to (done to pending.size)) }
        }
        _downloadProgress.update { it - playlistId }
    }

    /** 导入后按需补齐每首歌的封面（限制并发，失败忽略，不阻塞界面）。 */
    private suspend fun ensureEntryCovers(context: Context, playlistId: String) {
        val dir = File(context.filesDir, "netease-covers").apply { mkdirs() }
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val pending = playlist.entries.filter { entry ->
            entry.coverPath?.let { File(it).isFile } != true && entry.coverUrl.isNotBlank()
        }
        if (pending.isEmpty()) return
        var done = 0
        pending.chunked(4).forEach { chunk ->
            kotlinx.coroutines.coroutineScope {
                chunk.forEach { entry ->
                    launch {
                        val target = File(dir, "${entry.songId}.jpg")
                        if (!(target.isFile && target.length() > 0)) {
                            NeteaseClient.download(entry.coverUrl, cookie(context), target)
                        }
                        if (target.isFile && target.length() > 0) {
                            synchronized(this@NeteaseRepository) {
                                val current = playlistById(playlistId) ?: return@synchronized
                                updateRecord(current.copy(
                                    entries = current.entries.map {
                                        if (it.songId == entry.songId) it.copy(coverPath = target.absolutePath) else it
                                    },
                                ))
                            }
                        }
                    }
                }
            }
            done += chunk.size
            if (done % 8 == 0) appContext?.let(::persist)
        }
        appContext?.let(::persist)
    }

    private suspend fun downloadCover(context: Context, dir: File, entry: NeteaseEntryRecord): File? {
        if (entry.coverUrl.isBlank()) return null
        val target = File(dir, "${entry.songId}.cover.jpg")
        if (target.isFile && target.length() > 0) return target
        return NeteaseClient.download(entry.coverUrl, cookie(context), target)
            .getOrNull()?.let { target }
    }

    private fun buildPlaybackTrack(entry: NeteaseEntryRecord, file: File, cover: File?): LibraryTrack {
        val albumTitle = entry.album.ifBlank { "未知专辑" }
        val artists = entry.artists.ifBlank { "未知歌手" }
        val artistNames = artists.split(" / ").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(artists) }
        val albumId = albumIdFor(albumTitle)
        val extension = file.extension.lowercase()
        val seconds = entry.durationMs.coerceAtLeast(0L) / 1000L
        val metadata = buildJsonObject {
            put("title", entry.title)
            if (seconds > 0) put("duration", seconds)
            put("format", extension)
            put("codec", extension)
            put("lossless", extension in setOf("flac", "wav", "aiff", "alac"))
            put("file_size", file.length())
            put(
                "artists",
                JsonArray(artistNames.map { name -> buildJsonObject { put("id", artistIdFor(name)); put("name", name) } }),
            )
            put("album", buildJsonObject { put("id", albumId); put("title", albumTitle) })
            // 保留封面地址：封面缓存补齐后可以直接回填，重进列表时无需再查接口。
            if (entry.coverUrl.isNotBlank()) put("coverUrl", entry.coverUrl)
        }.toString()
        val createdAt = Instant.now().toString()
        return LibraryTrack(
            id = "netease:${entry.songId}",
            title = entry.title,
            artists = artists,
            album = albumTitle,
            albumId = albumId,
            metadataJson = metadata,
            artworkPath = cover?.absolutePath,
            audioPath = file.absolutePath,
            createdAt = createdAt,
            updatedAt = createdAt,
            sortTitle = entry.title,
            sortArtists = artistNames.first(),
        )
    }

    /** 只持久化“已加入播放列表”的歌单；暂存区的歌单随进程退出消失。 */
    private fun persist(context: Context) {
        synchronized(this) {
            runCatching {
                File(context.filesDir, PLAYLIST_FILE).writeText(
                    LibrarySyncProtocol.json.encodeToString(_playlists.value.filter { it.addedToPlaylists }),
                )
            }
        }
    }
}

/** 标题去掉括号版后缀与标点，用于与本地曲库匹配。 */
private val NeteaseBracketRegex = Regex("""[（(\[【].*?[)）\]】]""")
private val NeteasePunctRegex = Regex("""[\s\-_/.,，。'’!！?？:：]""")

internal fun normalizeNeteaseMatch(text: String): String = text.lowercase()
    .replace(NeteaseBracketRegex, "")
    .replace(NeteasePunctRegex, "")

internal fun bestMatch(entry: NeteaseEntryRecord, tracksByTitle: Map<String, List<LibraryTrack>>): LibraryTrack? {
    val title = normalizeNeteaseMatch(entry.title)
    if (title.isBlank()) return null
    val candidates = tracksByTitle[title].orEmpty()
    if (candidates.isEmpty()) return null
    if (entry.durationMs > 0) {
        val seconds = entry.durationMs / 1000
        candidates.firstOrNull { track ->
            val duration = trackDurationSeconds(track)
            duration != null && kotlin.math.abs(duration - seconds) <= 3
        }?.let { return it }
    }
    val artists = entry.artists.split("/", ",", "&")
        .map { normalizeNeteaseMatch(it) }
        .filter { it.isNotBlank() }
    if (artists.isEmpty()) return candidates.first()
    return candidates.firstOrNull { track ->
        val trackArtist = normalizeNeteaseMatch(track.artists)
        artists.any { trackArtist.contains(it) }
    } ?: candidates.first()
}

internal fun trackDurationSeconds(track: LibraryTrack): Long? {
    val metadata = track.metadataObject() ?: return null
    val seconds = (metadata["duration"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.contentOrNull?.toDoubleOrNull()?.toLong()
    if (seconds != null && seconds > 0) return seconds
    val ms = (metadata["duration_ms"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.contentOrNull?.toLong()
    return ms?.takeIf { it > 0 }?.div(1000)
}
