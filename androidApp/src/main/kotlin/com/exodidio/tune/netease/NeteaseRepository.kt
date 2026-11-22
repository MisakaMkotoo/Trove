package com.exodidio.tune.netease

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.albumIdFor
import com.exodidio.tune.sync.artistIdFor
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.sync.writeTextAtomically
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** 网易云专辑/歌手 ID：用于“前往专辑/歌手”打开在线实体页。 */
    val albumId: Long = 0L,
    val artistIds: List<Long> = emptyList(),
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
    /** 歌单介绍，首页卡片副标题使用。 */
    val description: String = "",
    /** 从“我的”点开过的歌单：条目写盘缓存，重启后仍在，但不进播放列表。 */
    val persistCache: Boolean = false,
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

/** 一键下载结果：total=开始时待下载数，succeeded=实际下载成功数。 */
data class DownloadAllResult(val total: Int, val succeeded: Int)

internal data class OnlineSearchResult(
    val songs: List<LibraryTrack> = emptyList(),
    val playlists: List<OnlinePlaylistInfo> = emptyList(),
    val artists: List<OnlineArtistInfo> = emptyList(),
    val albums: List<OnlineAlbumInfo> = emptyList(),
    /** 分区级失败：非空表示该分区请求失败，界面必须区别于“真的没有结果”。 */
    val playlistError: String? = null,
    val artistError: String? = null,
    val albumError: String? = null,
)

/** 心动/漫游卡片预览：启动时先取第一首（或一批），只展示封面，不播放。 */
data class NeteaseModePreview(
    val trackIds: List<String> = emptyList(),
    val artworkPath: String? = null,
    val seed: Long? = null,
)

/** 新发现页的歌单卡片数据（榜单/甄选/推荐/我的歌单共用）。 */
@Serializable
data class DiscoverPlaylist(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val coverUrl: String = "",
    val coverPath: String? = null,
    val trackCount: Int = 0,
)

/** 新发现页数据：歌单分区 + 新歌；封面缺失的随后在后台补齐。 */
data class DiscoverData(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val rankings: List<DiscoverPlaylist> = emptyList(),
    val curated: List<DiscoverPlaylist> = emptyList(),
    val newSongs: List<LibraryTrack> = emptyList(),
    val rankingsError: String? = null,
    val curatedError: String? = null,
    val newSongsError: String? = null,
)

/** 首页动态分区（曲风精选/歌曲推荐/艺人新动向）：标题和内容都由服务端下发。 */
data class HomeSongSection(
    val title: String,
    /** 服务端没给标题时使用的本地文案（如“根据你喜爱的歌曲推荐”）。 */
    @androidx.annotation.StringRes val titleRes: Int? = null,
    val songs: List<LibraryTrack> = emptyList(),
)

data class HomeFeedData(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val sections: List<HomeSongSection> = emptyList(),
    val artistNew: HomeSongSection? = null,
    val recommended: List<DiscoverPlaylist> = emptyList(),
    val error: String? = null,
)

/** “我的”页关注的歌手。 */
@Serializable
data class MineArtist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
)

/** “我的”页数据：云端自建/收藏歌单 + 关注歌手（最近播放走本地）。 */
data class MineData(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val created: List<DiscoverPlaylist> = emptyList(),
    val saved: List<DiscoverPlaylist> = emptyList(),
    val artists: List<MineArtist> = emptyList(),
    val error: String? = null,
)

/** “我的”页磁盘缓存：资料头、歌单列表、关注歌手启动时先用缓存渲染。 */
@Serializable
private data class MineCache(
    val created: List<DiscoverPlaylist> = emptyList(),
    val saved: List<DiscoverPlaylist> = emptyList(),
    val artists: List<MineArtist> = emptyList(),
)

/** 首页“雷达歌单”卡片元数据（名称/封面/数量，不含歌曲）。 */
@Serializable
data class NeteaseRadarSummary(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val coverPath: String? = null,
    val trackCount: Int = 0,
    val description: String = "",
)

/** 官方雷达歌单目录；内容按账号在服务端个性化。 */
internal const val RADAR_DISPLAY_NAME = "私人雷达"

internal val RadarCatalog: List<Pair<String, String>> = listOf(
    "3136952023" to RADAR_DISPLAY_NAME,
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
    private const val CACHE_PLAYLIST_FILE = "netease-cached-playlists.json"
    private const val LIKED_FILE = "netease-liked.json"
    private const val RADAR_FILE = "netease-radars.json"
    private const val ACCOUNT_FILE = "netease-account.json"
    private const val MINE_FILE = "netease-mine.json"
    /** 导入/镜像歌单的封面属于持久数据，不放进可淘汰的封面缓存目录。 */
    private const val PLAYLIST_COVER_DIR = "netease-playlist-covers"

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

    // 歌单落盘单写者：锁内只取内存快照，编码与原子写在 IO 单协程里做。
    private data class PersistSnapshot(
        val version: Long,
        val added: List<NeteasePlaylistRecord>,
        val cached: List<NeteasePlaylistRecord>,
    )
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val persistMutex = Mutex()
    private val pendingPersist = AtomicReference<PersistSnapshot?>(null)
    private val persistVersion = AtomicLong(0L)
    private val persistedVersion = AtomicLong(0L)
    // 启动读盘在 IO 上完成；任何落盘都要等它，避免把空/半装状态写回磁盘。
    private val diskLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        NeteaseStreamProxy.initialize(app)
        if (loaded) return
        loaded = true
        // 雷达目录先占位，磁盘/网络缓存就绪后覆盖；首帧不用等 6 个 JSON 解析。
        _radars.value = RadarCatalog.map { (id, name) -> NeteaseRadarSummary(id = id, name = name) }
        ioScope.launch {
            try {
                runCatching { loadFromDisk(app) }
            } finally {
                diskLoaded.complete(Unit)
            }
            runCatching { AndroidSyncRuntime.syncStore.awaitLoaded() }
            subscribeRecentTracks()
            _playlists.value.forEach { playlist ->
                ioScope.launch {
                    ensureEntryCovers(app, playlist.id)
                    if (playlist.id == LIKED_ID) persistLiked(app)
                }
            }
            ioScope.launch { backfillPlaybackCovers() }
            ioScope.launch { backfillPlaylistCovers() }
            ioScope.launch { NeteaseImageCache.trim(app) }
        }
        ioScope.launch {
            diskLoaded.await()
            for (ignored in persistRequests) {
                val snapshot = pendingPersist.getAndSet(null) ?: continue
                persistMutex.withLock { writePersist(app, snapshot) }
            }
        }
    }

    /** 启动读盘完成信号：首页刷新等依赖缓存的操作先等它，避免拿空歌单做匹配。 */
    suspend fun awaitDiskLoaded() {
        diskLoaded.await()
    }

    private fun loadFromDisk(app: Context) {
        _loggedIn.value = !cookie(app).isNullOrBlank()
        val merged = linkedMapOf<String, NeteasePlaylistRecord>()
        runCatching {
            LibrarySyncProtocol.json.decodeFromString<List<NeteasePlaylistRecord>>(
                File(app.filesDir, PLAYLIST_FILE).readText(),
            )
        }.getOrDefault(emptyList()).map { it.copy(addedToPlaylists = true) }.forEach { merged[it.id] = it }
        // 红心歌单单独缓存：启动直接用它，不再自动联网刷新。
        runCatching {
            LibrarySyncProtocol.json.decodeFromString<NeteasePlaylistRecord>(
                File(app.filesDir, LIKED_FILE).readText(),
            )
        }.getOrNull()?.let { liked ->
            merged.remove(LIKED_ID)
            merged.remove(liked.id)
            merged[liked.id] = liked.copy(addedToPlaylists = false)
        }
        // “我的”里点开过的歌单：独立文件缓存，加载后仍不进播放列表。
        runCatching {
            LibrarySyncProtocol.json.decodeFromString<List<NeteasePlaylistRecord>>(
                File(app.filesDir, CACHE_PLAYLIST_FILE).readText(),
            )
        }.getOrNull()?.forEach { cached ->
            if (!merged.containsKey(cached.id)) {
                merged[cached.id] = cached.copy(addedToPlaylists = false, persistCache = true)
            }
        }
        // 读盘期间可能已有网络写入（loadHome）：内存里的更新，保留内存版本。
        _playlists.value.forEach { merged[it.id] = it }
        _playlists.value = merged.values.toList()
        publishActive()
        // 账户资料与“我的”页列表也走磁盘缓存，启动即可先渲染；网络已回填的更新优先。
        runCatching {
            LibrarySyncProtocol.json.decodeFromString<NeteaseClient.AccountInfo>(
                File(app.filesDir, ACCOUNT_FILE).readText(),
            )
        }.getOrNull()?.let { info ->
            if (_accountInfo.value == null) _accountInfo.value = info
        }
        runCatching {
            LibrarySyncProtocol.json.decodeFromString<MineCache>(
                File(app.filesDir, MINE_FILE).readText(),
            )
        }.getOrNull()?.let { cache ->
            val hasData = cache.created.isNotEmpty() || cache.saved.isNotEmpty() || cache.artists.isNotEmpty()
            val current = _mine.value
            val untouched = !current.loaded && current.created.isEmpty() && current.saved.isEmpty() && current.artists.isEmpty()
            if (hasData && untouched) {
                _mine.value = MineData(
                    loaded = true,
                    created = cache.created,
                    saved = cache.saved,
                    artists = cache.artists,
                )
            }
        }
        // 雷达卡片元数据：启动先用缓存渲染，随后 loadHome 更新一次。
        val cachedRadars = runCatching {
            LibrarySyncProtocol.json.decodeFromString<List<NeteaseRadarSummary>>(
                File(app.filesDir, RADAR_FILE).readText(),
            )
        }.getOrNull().orEmpty()
        if (cachedRadars.isNotEmpty()) {
            val current = _radars.value
            val placeholderIds = RadarCatalog.map { it.first }.toSet()
            val stillPlaceholder = current.map { it.id }.toSet() == placeholderIds &&
                current.all { it.coverUrl.isBlank() && it.trackCount == 0 }
            if (stillPlaceholder) _radars.value = cachedRadars
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
        cacheToDisk: Boolean = false,
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
            persistCache = existing?.persistCache ?: cacheToDisk,
            entries = existing?.entries.orEmpty(),
        )
        _playlists.value = _playlists.value.filterNot { it.id == playlistId } + record
        publishActive()
        if (record.persistCache && !record.addedToPlaylists) {
            persistSoon()
        }
    }

    /** 是否把歌单镜像到“播放列表”页；不影响设置里的导入记录。 */
    fun setAdded(playlistId: String, added: Boolean) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(addedToPlaylists = added) else playlist
        }
        publishActive()
        persistSoon()
    }

    fun cookie(context: Context? = appContext): String? {
        context ?: return null
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, "").orEmpty()
        return value.takeIf { it.isNotBlank() }
    }

    fun clearLogin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_COOKIE).apply()
        // 网页登录的登录态存在 WebView 的 Cookie 里：只清应用内 cookie 的话，
        // 下次打开网页登录页会直接是已登录状态，必须把 WebView 的 Cookie/存储一起清掉。
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies { cookieManager.flush() }
            WebStorage.getInstance().deleteAllData()
        }
        listOf(ACCOUNT_FILE, MINE_FILE, CACHE_PLAYLIST_FILE).forEach { name ->
            File(context.filesDir, name).delete()
        }
        _playlists.value = _playlists.value.filterNot { it.persistCache }
        publishActive()
        _loggedIn.value = false
        _accountInfo.value = null
        _homeFeed.value = HomeFeedData()
        _discover.value = DiscoverData()
        _mine.value = MineData()
        _homeStatus.value = ""
    }

    suspend fun createQrLogin(cookie: String? = null): Result<NeteaseLoginQr> = NeteaseClient.createQrLogin(cookie)

    suspend fun pollQrLogin(key: String, cookie: String?): Result<NeteaseQrStatus> =
        NeteaseClient.pollQrLogin(key, cookie)

    fun saveLogin(context: Context, cookie: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIE, cookie).apply()
        _loggedIn.value = true
        loadAccountInfo(force = true)
        // 登录后立刻补齐首页/新发现的个人化内容。
        ioScope.launch {
            runCatching { loadHome(context.applicationContext) }
            loadHomeFeed(force = true)
            loadDiscover(force = true)
            loadMine(force = true)
        }
    }

    private val _accountInfo = MutableStateFlow<NeteaseClient.AccountInfo?>(null)
    val accountInfo: StateFlow<NeteaseClient.AccountInfo?> = _accountInfo.asStateFlow()
    private var accountInfoJob: Job? = null
    @Volatile private var accountLoadedAt = 0L

    /** 账户页信息：进入页面时刷新一次；未登录直接清空。 */
    fun loadAccountInfo(force: Boolean = false) {
        val app = appContext ?: return
        val c = cookie(app)
        if (c.isNullOrBlank()) {
            _accountInfo.value = null
            return
        }
        if (accountInfoJob?.isActive == true) return
        if (!force && _accountInfo.value != null &&
            System.currentTimeMillis() - accountLoadedAt < DISCOVER_TTL_MS
        ) {
            return
        }
        accountInfoJob = ioScope.launch {
            NeteaseClient.fetchAccountInfo(c).onFailure {
                System.err.println("NETACCOUNT failed ${it.message}")
            }.onSuccess { info ->
                _accountInfo.value = info
                accountLoadedAt = System.currentTimeMillis()
                persistAccount(app, info)
            }
        }
    }

    private fun persistAccount(context: Context, info: NeteaseClient.AccountInfo) {
        runCatching {
            File(context.filesDir, ACCOUNT_FILE).writeTextAtomically(LibrarySyncProtocol.json.encodeToString(info))
        }
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
        if (record.addedToPlaylists) persistNow(context)
        ioScope.launch { ensureEntryCovers(context, id) }
        Result.success(id)
    }

    suspend fun refresh(context: Context, playlistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (playlistId == DAILY_ID) return@withContext loadDaily(context, force = true).map { }
        if (playlistId == RADAR_ID) return@withContext loadRadar(context, force = true).map { }
        if (playlistId == LIKED_ID) return@withContext loadLiked(context, force = true).map { }
        if (playlistId == DOWNLOADS_ID) {
            return@withContext Result.failure(IllegalStateException("“已下载的音乐”来自本机文件，无需刷新"))
        }
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
            persistNow(context)
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
            persistNow(context)
            ioScope.launch { ensureEntryCovers(context, playlistId) }
            return@withContext Result.success(Unit)
        }
        // 在线歌手/专辑页是虚拟歌单：刷新就是重新拉热门歌曲/专辑曲目。
        if (playlistId.startsWith(ARTIST_PAGE_PREFIX)) {
            val artistId = playlistId.removePrefix(ARTIST_PAGE_PREFIX).toLongOrNull()
                ?: return@withContext Result.failure(IllegalStateException("歌手 ID 无效"))
            val entries = NeteaseClient.fetchArtistTopSongs(artistId.toString(), cookie(context))
                .getOrElse { return@withContext Result.failure(it) }
            applyPayload(
                context,
                playlistId,
                NeteasePlaylistPayload(
                    id = playlistId,
                    name = existing.name,
                    coverUrl = entries.firstOrNull()?.coverUrl.orEmpty().ifBlank { existing.coverUrl },
                    entries = entries,
                ),
                dynamicKind = "artist",
                addedToPlaylists = existing.addedToPlaylists,
                awaitCovers = false,
            )
            ioScope.launch { ensureEntryCovers(context, playlistId) }
            return@withContext Result.success(Unit)
        }
        if (playlistId.startsWith(ALBUM_PAGE_PREFIX)) {
            val albumId = playlistId.removePrefix(ALBUM_PAGE_PREFIX).toLongOrNull()
                ?: return@withContext Result.failure(IllegalStateException("专辑 ID 无效"))
            val payload = NeteaseClient.fetchAlbumDetail(albumId, cookie(context))
                .getOrElse { return@withContext Result.failure(it) }
            applyPayload(
                context,
                playlistId,
                payload.copy(name = existing.name.ifBlank { payload.name }),
                dynamicKind = "album",
                addedToPlaylists = existing.addedToPlaylists,
                awaitCovers = false,
            )
            ioScope.launch { ensureEntryCovers(context, playlistId) }
            return@withContext Result.success(Unit)
        }
        val payload = NeteaseClient.fetchPlaylist(playlistId, cookie(context)).getOrElse {
            return@withContext Result.failure(it)
        }
        val record = buildRecord(context, payload, existing).copy(
            dynamicKind = existing.dynamicKind,
            persistCache = existing.persistCache,
        )
        _playlists.value = _playlists.value.map { if (it.id == playlistId) record else it }
        publishActive()
        if (record.addedToPlaylists) persistNow(context)
        updateRadarSummary(context, record)
        ioScope.launch { ensureEntryCovers(context, playlistId) }
        Result.success(Unit)
    }

    /**
     * 详情页菜单的“保存到播放列表”：普通歌单直接加入本地播放列表；
     * 每日推荐/私人雷达/漫游/雷达歌单每次保存都生成一份新的带日期快照（不覆盖旧快照）。
     */
    suspend fun saveSnapshot(playlistId: String): String? {
        val source = playlistById(playlistId) ?: return null
        val isRadar = source.dynamicKind == "radar" ||
            source.name.contains("私人雷达") ||
            source.id == NeteaseClient.PrivateRadarPlaylistId ||
            NeteaseClient.playlistIdFrom(source.sourceUrl) == NeteaseClient.PrivateRadarPlaylistId
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
                    appContext?.let { persistNow(it) }
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
        appContext?.let { persistNow(it) }
        publishActive()
        return snapshot.id
    }

    suspend fun delete(playlistId: String) = withContext(Dispatchers.IO) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        if (_selectedPlaylistId.value == playlistId) _selectedPlaylistId.value = null
        publishActive()
        if (playlistId == LIKED_ID) appContext?.let { context -> File(context.filesDir, LIKED_FILE).delete() }
        appContext?.let { persistNow(it) }
    }

    /** 与导入完全一致的落库流程：下载歌单封面、下载曲目封面，然后发布。 */
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
        val record = base.copy(
            id = id,
            dynamicKind = dynamicKind,
            addedToPlaylists = addedToPlaylists,
            persistCache = existing?.persistCache ?: false,
        )
        _playlists.value = _playlists.value.filterNot { it.id == id } + record
        publishActive()
        // 与导入一致；等待封面补齐，保证首页/详情一出现就带封面。大歌单可改为后台补齐。
        if (awaitCovers) ensureEntryCovers(context, id)
        publishActive()
        persistSoon()
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
                matchedTrackId = null,
                downloadedFile = previous?.downloadedFile,
                downloadFormat = previous?.downloadFormat,
                albumId = entry.albumId,
                artistIds = entry.artistIds,
            )
        }
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
            persistCache = existing?.persistCache ?: false,
            sourceUrl = sourceUrl ?: existing?.sourceUrl.orEmpty(),
            description = payload.description.ifBlank { existing?.description.orEmpty() },
            entries = fresh,
        )
    }

    private suspend fun downloadPlaylistCover(
        context: Context,
        playlistId: String,
        url: String,
        previousUrl: String? = null,
    ): String? {
        if (url.isBlank()) return null
        // 持久目录：歌单封面视同本地歌单数据，不参与封面缓存的 LRU 淘汰。
        val dir = File(context.filesDir, PLAYLIST_COVER_DIR).apply { mkdirs() }
        val suffix = url.hashCode().toUInt().toString(16)
        val fileName = "${playlistId}_$suffix.img"
        val target = File(dir, fileName)
        // URL 没变且文件有效就直接复用；URL 变了就重新下载到新文件名。
        if (previousUrl == url && target.isFile && target.length() > 0) return target.absolutePath
        // 老版本把歌单封面存在会被清理的封面缓存目录：先搬过来，搬不到再重新下载。
        val legacy = File(context.filesDir, "netease-covers/$fileName")
        if (legacy.isFile && legacy.length() > 0) {
            legacy.renameTo(target)
            if (target.isFile && target.length() > 0) return target.absolutePath
        }
        val saved = NeteaseClient.download(url, cookie(context), target)
        val path = saved.getOrNull()?.let { target.takeIf(File::isFile)?.absolutePath }
        if (path == null) System.err.println("PLAYLISTCOVER failed id=$playlistId")
        return path
    }

    /** 启动时补齐“导入/镜像歌单”缺失的持久封面（限 30 个，避免一次拉太多）。 */
    private fun backfillPlaylistCovers() {
        val app = appContext ?: return
        ioScope.launch {
            val pending = _playlists.value.filter { record ->
                (record.addedToPlaylists || record.persistCache) &&
                    record.coverUrl.isNotBlank() &&
                    record.coverPath?.let { File(it).isFile } != true
            }.take(30)
            if (pending.isEmpty()) return@launch
            pending.forEach { record ->
                val path = downloadPlaylistCover(app, record.id, record.coverUrl, record.coverUrl) ?: return@forEach
                _playlists.value = _playlists.value.map { if (it.id == record.id) it.copy(coverPath = path) else it }
                publishActive()
            }
            persistSoon()
            System.err.println("PLAYLISTCOVER backfill=${pending.size}")
        }
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
                persistNow(context)
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
    suspend fun playbackStreamUrl(songId: Long): String {
        NeteaseStreamProxy.initialize(appContext ?: error("repository not initialized"))
        // 代理初始化为非阻塞；真正播放前在这里等它就绪。
        NeteaseStreamProxy.awaitReady()
        return NeteaseStreamProxy.localUrl(songId)
    }

    fun hasCachedStream(songId: Long): Boolean = NeteaseStreamProxy.hasCache(songId)

    fun cachedStreamUrl(songId: Long): String? =
        streamUrls[songId]?.takeIf { it.second > System.currentTimeMillis() }?.first

    /** 打开流失败时丢弃缓存直链，下一次会重新向网易云取。 */
    fun invalidateStreamUrl(songId: Long) {
        streamUrls.remove(songId)
    }

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
     * 可播放条目：网易云侧只认自己的曲目。已下载就复用本曲目下载文件，
     * 否则登记一条仅播放的流式曲目（audioPath = netease://songId）。
     * 不再绑定本地/电脑曲库的同名曲目——两个来源保持单向关系。
     */
    fun entryTrack(entry: NeteaseEntryRecord, ensureCover: Boolean = true): LibraryTrack {
        val existing = AndroidSyncRuntime.syncStore.trackForPlayback("netease:${entry.songId}")
        if (existing != null) {
            // 已经物化过就直接复用；只在补到新封面时更新一次，避免每次组合重复写 store / 排下载。
            val cachedCover = appContext?.let { NeteaseCoverStore.cached(it, entry.songId) }?.absolutePath
            if (cachedCover != null && existing.artworkPath != cachedCover) {
                val updated = existing.copy(artworkPath = cachedCover)
                AndroidSyncRuntime.syncStore.putPlaybackOnlyTrack(updated)
                return updated
            }
            return existing
        }
        return materializeStreamingTrack(entry, scheduleCover = ensureCover)
    }

    /**
     * 唯一的流式曲目物化入口：先带已缓存的封面，再在后台补齐缺失封面并回写。
     * 所有歌曲列表（导入、动态歌单、心动模式、在线搜索）都必须走这里。
     */
    private fun materializeStreamingTrack(entry: NeteaseEntryRecord, scheduleCover: Boolean = true): LibraryTrack {
        val cover = appContext?.let { NeteaseCoverStore.cached(it, entry.songId) }
            ?: entry.coverPath?.let(::File)?.takeIf { it.isFile }
            ?: entry.downloadedFile?.let { File(it).parentFile }
                ?.let { File(it, "${entry.songId}.cover.jpg") }
                ?.takeIf { it.isFile }
        val track = buildStreamingTrack(entry, cover)
        AndroidSyncRuntime.syncStore.putPlaybackOnlyTrack(track)
        if (scheduleCover) scheduleCover(entry)
        return track
    }

    /** 后台补封面：完成后批量回写仅播放曲目，列表/播放器自动刷新。 */
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
                queueCoverUpdate(current.copy(artworkPath = file.absolutePath))
            }
        }
    }

    private val pendingCoverUpdates = ConcurrentHashMap<String, LibraryTrack>()
    private var coverFlushJob: Job? = null

    /** 封面回写攒 150ms 合并成一批：整份歌单补图时不再逐首触发 UI 重组。 */
    private fun queueCoverUpdate(track: LibraryTrack) {
        pendingCoverUpdates[track.id] = track
        if (coverFlushJob?.isActive == true) return
        coverFlushJob = ioScope.launch {
            delay(150)
            val batch = pendingCoverUpdates.values.toList()
            pendingCoverUpdates.clear()
            if (batch.isEmpty()) return@launch
            AndroidSyncRuntime.syncStore.putPlaybackOnlyTracks(batch)
            System.err.println("COVER batch=${batch.size}")
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
                            albumId = song.albumId,
                            artistIds = song.artistIds,
                        ),
                    )
                }
                fun artwork(url: String): String? =
                    NeteaseImageStore.cached(app, url)?.absolutePath
                val playlistsResult = playlistsJob.await()
                val artistsResult = artistsJob.await()
                val albumsResult = albumsJob.await()
                val playlists = playlistsResult.getOrDefault(emptyList()).map {
                    OnlinePlaylistInfo(it.id, it.name, it.creator, it.trackCount, it.coverUrl, artwork(it.coverUrl))
                }
                val artists = artistsResult.getOrDefault(emptyList()).map {
                    OnlineArtistInfo(it.id, it.name, it.coverUrl, artwork(it.coverUrl))
                }
                val albums = albumsResult.getOrDefault(emptyList()).map {
                    OnlineAlbumInfo(it.id, it.name, it.artist, it.coverUrl, artwork(it.coverUrl))
                }
                fun Result<*>.sectionError(): String? =
                    exceptionOrNull()?.message?.ifBlank { "请稍后重试" }
                System.err.println(
                    "NETSEARCH q=$query songs=${songs.size} playlists=${playlists.size} artists=${artists.size} albums=${albums.size} " +
                        "err=${listOf(playlistsResult, artistsResult, albumsResult).map { it.exceptionOrNull()?.message }}",
                )
                OnlineSearchResult(
                    songs = songs,
                    playlists = playlists,
                    artists = artists,
                    albums = albums,
                    playlistError = playlistsResult.sectionError(),
                    artistError = artistsResult.sectionError(),
                    albumError = albumsResult.sectionError(),
                )
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

    private val _discover = MutableStateFlow(DiscoverData())
    val discover: StateFlow<DiscoverData> = _discover.asStateFlow()
    private var discoverJob: Job? = null
    @Volatile private var discoverLoadedAt = 0L

    private val _homeFeed = MutableStateFlow(HomeFeedData())
    val homeFeed: StateFlow<HomeFeedData> = _homeFeed.asStateFlow()
    private var homeFeedJob: Job? = null
    @Volatile private var homeFeedLoadedAt = 0L

    /**
     * 新发现页：排行榜 + 甄选歌单（首页整页块）+ 新歌一次并行拉取。
     * 歌单封面与歌曲封面都走已有缓存管线；同一批数据 10 分钟内不重复请求。
     */
    fun loadDiscover(force: Boolean = false) {
        val app = appContext ?: return
        if (discoverJob?.isActive == true) return
        if (!force && _discover.value.loaded &&
            System.currentTimeMillis() - discoverLoadedAt < DISCOVER_TTL_MS
        ) {
            return
        }
        discoverJob = ioScope.launch {
            _discover.update { it.copy(loading = true) }
            val cookie = cookie(app)
            val rankingsJob = async { NeteaseClient.fetchToplists(cookie) }
            // 甄选来自整页块，必须登录；未登录不发请求，页面显示登录引导。
            val blocksJob = cookie?.let { session -> async { NeteaseClient.fetchHomeBlocks(session) } }
            val newSongsJob = async { NeteaseClient.fetchNewSongs(cookie, limit = 12) }

            val rankingsResult = rankingsJob.await()
            val blocksResult = blocksJob?.await()
            val newSongsResult = newSongsJob.await()

            val rankings = rankingsResult.getOrNull().orEmpty().take(10).map { list ->
                DiscoverPlaylist(
                    id = list.id,
                    name = list.name,
                    subtitle = list.updateFrequency,
                    coverUrl = list.coverUrl,
                    coverPath = NeteaseImageStore.cached(app, list.coverUrl)?.absolutePath,
                )
            }
            val curated = blocksResult?.getOrNull().orEmpty()
                .firstOrNull { it.code.contains("OFFICIAL_PLAYLIST", true) || it.title.contains("甄选") }
                ?.playlists.orEmpty()
                .take(10)
                .map { payload ->
                    DiscoverPlaylist(
                        id = payload.id,
                        name = payload.name,
                        subtitle = payload.description,
                        coverUrl = payload.coverUrl,
                        coverPath = NeteaseImageStore.cached(app, payload.coverUrl)?.absolutePath,
                    )
                }
            val songs = materializeEntries(newSongsResult.getOrNull().orEmpty())

            _discover.value = DiscoverData(
                loading = false,
                loaded = true,
                rankings = rankings,
                curated = curated,
                newSongs = songs,
                rankingsError = rankingsResult.exceptionOrNull()?.message,
                curatedError = blocksResult?.exceptionOrNull()?.message,
                newSongsError = newSongsResult.exceptionOrNull()?.message,
            )
            discoverLoadedAt = System.currentTimeMillis()
            System.err.println(
                "NETDISCOVER rankings=${rankings.size} curated=${curated.size} songs=${songs.size} " +
                    "errors=${listOfNotNull(rankingsResult.exceptionOrNull()?.message, blocksResult?.exceptionOrNull()?.message).size}",
            )
            fillCovers(app, cookie, rankings + curated)
        }
    }

    /**
     * 首页动态分区：整页块里的歌曲分区 + 推荐歌单（30 个）。
     * 分区标题/内容由服务端按账号下发，10 分钟内不重复请求。
     */
    fun loadHomeFeed(force: Boolean = false) {
        val app = appContext ?: return
        if (homeFeedJob?.isActive == true) return
        if (!force && _homeFeed.value.loaded &&
            System.currentTimeMillis() - homeFeedLoadedAt < DISCOVER_TTL_MS
        ) {
            return
        }
        homeFeedJob = ioScope.launch {
            _homeFeed.update { it.copy(loading = true) }
            val cookie = cookie(app)
            val blocksJob = async { NeteaseClient.fetchHomeBlocks(cookie) }
            val recommendedJob = async { NeteaseClient.fetchRecommendedResources(cookie) }
            val recommendSongsJob = async { NeteaseClient.fetchRecommendSongs(cookie, limit = 30) }

            val blocksResult = blocksJob.await()
            val recommendedResult = recommendedJob.await()
            val recommendSongsResult = recommendSongsJob.await()
            val blocks = blocksResult.getOrNull().orEmpty()

            val blockSections = blocks
                .filter { block ->
                    block.songs.isNotEmpty() &&
                        !block.code.contains("NEW_ALBUM", ignoreCase = true) &&
                        !block.code.contains("SUBSCRIBE", ignoreCase = true)
                }
                .map { block -> HomeSongSection(block.title, songs = materializeEntries(block.songs).take(18)) }
                .filter { it.songs.isNotEmpty() }
            // “根据你喜爱的歌曲推荐”：整页块里没有对应分区，用登录态的每日推荐歌曲补上。
            val favoriteSongs = materializeEntries(recommendSongsResult.getOrNull().orEmpty()).take(30)
            val favoriteSection = favoriteSongs.takeIf { it.isNotEmpty() }?.let { songs ->
                HomeSongSection(
                    title = "",
                    titleRes = com.exodidio.tune.R.string.home_section_favorite_rec,
                    songs = songs,
                )
            }
            val sections = blockSections + listOfNotNull(favoriteSection)
            val artistBlock = blocks.firstOrNull {
                it.code.contains("SUBSCRIBE", ignoreCase = true) || it.title.contains("关注")
            }
            val artistNew = artistBlock?.takeIf { it.songs.isNotEmpty() }?.let { block ->
                HomeSongSection(block.title, songs = materializeEntries(block.songs).take(18))
            }
            val recommended = recommendedResult.getOrNull().orEmpty().map { payload ->
                DiscoverPlaylist(
                    id = payload.id,
                    name = payload.name,
                    subtitle = payload.description,
                    coverUrl = payload.coverUrl,
                    coverPath = NeteaseImageStore.cached(app, payload.coverUrl)?.absolutePath,
                )
            }

            _homeFeed.value = HomeFeedData(
                loading = false,
                loaded = true,
                sections = sections,
                artistNew = artistNew,
                recommended = recommended,
                error = blocksResult.exceptionOrNull()?.message
                    ?: recommendSongsResult.exceptionOrNull()?.message
                    ?: recommendedResult.exceptionOrNull()?.message,
            )
            homeFeedLoadedAt = System.currentTimeMillis()
            System.err.println(
                "NETHOMEFEED sections=${sections.map { "${it.title.ifBlank { it.titleRes }}:${it.songs.size}" }} " +
                    "artist=${artistNew?.songs?.size} recommended=${recommended.size} " +
                    "err=${blocksResult.exceptionOrNull()?.message ?: recommendSongsResult.exceptionOrNull()?.message ?: recommendedResult.exceptionOrNull()?.message}",
            )
            fillCovers(app, cookie, recommended)
        }
    }

    private val _mine = MutableStateFlow(MineData())
    val mine: StateFlow<MineData> = _mine.asStateFlow()
    private var mineJob: Job? = null
    @Volatile private var mineLoadedAt = 0L

    private val _recentTracks = MutableStateFlow(emptyList<LibraryTrack>())
    /** “最近播放”：本地历史，可为本地曲目或在线曲目；封面晚到会自动回填。 */
    val recentTracks: StateFlow<List<LibraryTrack>> = _recentTracks.asStateFlow()
    @Volatile private var recentSubscribed = false

    private fun subscribeRecentTracks() {
        if (recentSubscribed) return
        recentSubscribed = true
        val store = AndroidSyncRuntime.syncStore
        ioScope.launch {
            combine(
                store.recentlyPlayed,
                store.playbackOnlyTracksFlow,
                store.tracks,
            ) { records, _, _ -> records }
                .collect { records ->
                    restoreRecentTracks(records)
                    publishRecentTracks()
                }
        }
    }

    /** 重启后仅播放曲目不在内存里：按历史快照恢复，播放与封面都走正常流式管线。 */
    private fun restoreRecentTracks(records: List<AndroidLibrarySyncStore.RecentPlayRecord>) {
        val store = AndroidSyncRuntime.syncStore
        records.forEach { record ->
            if (store.trackForPlayback(record.trackId) != null) return@forEach
            if (!record.trackId.startsWith("netease:")) return@forEach
            val songId = record.trackId.removePrefix("netease:").toLongOrNull() ?: return@forEach
            if (record.title.isBlank()) return@forEach
            runCatching {
                materializeStreamingTrack(
                    NeteaseEntryRecord(
                        songId = songId,
                        title = record.title,
                        artists = record.artists,
                        album = record.album,
                        coverUrl = record.coverUrl,
                    ),
                )
            }
        }
    }

    private fun publishRecentTracks() {
        val app = appContext
        val store = AndroidSyncRuntime.syncStore
        _recentTracks.value = store.recentlyPlayed.value.mapNotNull { record ->
            val track = store.trackForPlayback(record.trackId) ?: return@mapNotNull null
            val cover = track.artworkPath ?: songIdForTrack(track.id)?.let { songId ->
                app?.let { NeteaseCoverStore.cached(it, songId)?.absolutePath }
            }
            if (cover != null && cover != track.artworkPath) track.copy(artworkPath = cover) else track
        }.take(30)
    }

    /**
     * “我的”页：自建歌单 / 收藏歌单 / 关注歌手。
     * 我喜欢的音乐固定作为自建歌单第一张；10 分钟内不重复请求。
     */
    fun loadMine(force: Boolean = false) {
        val app = appContext ?: return
        val session = cookie(app)
        if (session.isNullOrBlank()) {
            _mine.value = MineData()
            return
        }
        if (mineJob?.isActive == true) return
        if (!force && _mine.value.loaded &&
            System.currentTimeMillis() - mineLoadedAt < DISCOVER_TTL_MS
        ) {
            return
        }
        mineJob = ioScope.launch {
            _mine.update { it.copy(loading = true) }
            val accountResult = NeteaseClient.fetchAccountInfo(session)
            accountResult.getOrNull()?.let { _accountInfo.value = it }
            val playlistsResult = accountResult.getOrNull()?.let { info ->
                NeteaseClient.fetchUserPlaylists(info.uid, session)
            }
            val artistsResult = NeteaseClient.fetchSubscribedArtists(session)
            val uid = accountResult.getOrNull()?.uid.orEmpty()
            val playlists = playlistsResult?.getOrNull().orEmpty()

            fun convert(payload: NeteaseClient.UserPlaylistInfo) = DiscoverPlaylist(
                id = payload.id,
                name = payload.name,
                coverUrl = payload.coverUrl,
                trackCount = payload.trackCount,
            )

            val likedCard = playlistById(LIKED_ID)?.let { record ->
                DiscoverPlaylist(
                    id = record.id,
                    name = record.name.ifBlank { "我喜欢的音乐" },
                    coverUrl = record.coverUrl,
                    coverPath = record.coverPath,
                    trackCount = record.entries.size.takeIf { it > 0 } ?: record.remoteTrackCount,
                )
            } ?: playlists.firstOrNull { it.specialType == 5 }?.let { payload ->
                DiscoverPlaylist(
                    id = payload.id,
                    name = payload.name,
                    coverUrl = payload.coverUrl,
                    trackCount = payload.trackCount,
                )
            }
            val created = playlists
                .filter { it.specialType == 0 && !it.subscribed && it.creatorId == uid }
                .sortedByDescending { it.updateTimeMillis }
                .map(::convert)
            val saved = playlists
                .filter { it.specialType == 0 && (it.subscribed || it.creatorId != uid) }
                .sortedByDescending { it.updateTimeMillis }
                .map(::convert)
            val artists = artistsResult.getOrNull().orEmpty().map { MineArtist(it.id, it.name, it.coverUrl) }

            _mine.value = MineData(
                loading = false,
                loaded = true,
                created = listOfNotNull(likedCard) + created,
                saved = saved,
                artists = artists,
                error = accountResult.exceptionOrNull()?.message
                    ?: playlistsResult?.exceptionOrNull()?.message
                    ?: artistsResult.exceptionOrNull()?.message,
            )
            mineLoadedAt = System.currentTimeMillis()
            persistMine(app, _mine.value)
            System.err.println(
                "NETMINE created=${_mine.value.created.size} saved=${saved.size} artists=${artists.size} " +
                    "err=${_mine.value.error}",
            )
        }
    }

    private fun persistMine(context: Context, data: MineData) {
        runCatching {
            File(context.filesDir, MINE_FILE).writeTextAtomically(
                LibrarySyncProtocol.json.encodeToString(
                    MineCache(created = data.created, saved = data.saved, artists = data.artists),
                ),
            )
        }
    }

    /** 统一的流式曲目物化：失败的单条直接跳过，不影响整块。 */
    private fun materializeEntries(entries: List<NeteaseEntryPayload>): List<LibraryTrack> =
        entries.mapNotNull { song ->
            runCatching {
                materializeStreamingTrack(
                    NeteaseEntryRecord(
                        songId = song.songId,
                        title = song.title,
                        artists = song.artists,
                        album = song.album,
                        durationMs = song.durationMs,
                        coverUrl = song.coverUrl,
                        albumId = song.albumId,
                        artistIds = song.artistIds,
                    ),
                )
            }.getOrNull()
        }

    /** 歌单封面按缓存顺序逐个补齐，避免一次并发几十个下载。 */
    private suspend fun fillCovers(app: Context, cookie: String?, playlists: List<DiscoverPlaylist>) {
        playlists.distinctBy { it.coverUrl }.forEach { playlist ->
            if (playlist.coverPath != null || playlist.coverUrl.isBlank()) return@forEach
            val file = NeteaseImageStore.ensure(app, playlist.coverUrl, cookie).await() ?: return@forEach
            val path = file.absolutePath
            _discover.update { data ->
                data.copy(
                    rankings = data.rankings.withCover(playlist.id, path),
                    curated = data.curated.withCover(playlist.id, path),
                )
            }
            _homeFeed.update { data ->
                data.copy(recommended = data.recommended.withCover(playlist.id, path))
            }
        }
    }

    private fun List<DiscoverPlaylist>.withCover(id: String, path: String): List<DiscoverPlaylist> =
        map { if (it.id == id && it.coverPath == null) it.copy(coverPath = path) else it }

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
            // 网易云实体 ID：歌曲菜单“前往专辑/歌手”据此打开在线页。
            if (entry.albumId > 0) put("neteaseAlbumId", entry.albumId)
            if (entry.artistIds.isNotEmpty()) {
                put("neteaseArtistId", entry.artistIds.first())
                put("neteaseArtistIds", JsonArray(entry.artistIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
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
    private const val DISCOVER_TTL_MS = 10 * 60 * 1000L

    private val _activePlaylists = MutableStateFlow(emptyList<NeteasePlaylistRecord>())
    /** 持久化歌单 + 动态歌单（每日推荐/雷达/我喜欢的音乐），详情页共用。 */
    val activePlaylists: StateFlow<List<NeteasePlaylistRecord>> = _activePlaylists.asStateFlow()

    private fun publishActive() {
        _activePlaylists.value = _playlists.value
    }

    fun playlistById(id: String): NeteasePlaylistRecord? =
        _playlists.value.firstOrNull { it.id == id }

    fun isDynamic(id: String): Boolean = id == DAILY_ID || id == RADAR_ID || id == LIKED_ID

    fun isDownloadsPage(id: String): Boolean = id == DOWNLOADS_ID

    /** 是否值得“每次打开都刷新”的实时雷达（8 个固定 ID / 首页私人雷达；日期快照不算）。 */
    fun isRadarPlaylist(record: NeteasePlaylistRecord): Boolean {
        if (record.id.startsWith(SNAPSHOT_PREFIX)) return false
        if (record.name.contains("私人雷达")) return true
        if (record.id == RADAR_ID) return true
        if (RadarCatalog.any { it.first == record.id }) return true
        val sourceId = NeteaseClient.playlistIdFrom(record.sourceUrl) ?: return false
        return RadarCatalog.any { it.first == sourceId }
    }

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
            description = summary.description.ifBlank { previous?.description.orEmpty() },
        )
        _playlists.value = _playlists.value.filterNot { it.id == LIKED_ID } + record
        publishActive()
        persistLiked(context)
    }

    /** 红心歌单元数据（含每首歌封面路径）单独缓存，不走普通歌单文件（不能进播放列表页）。 */
    private fun persistLiked(context: Context) {
        val record = _playlists.value.firstOrNull { it.id == LIKED_ID } ?: return
        runCatching {
            File(context.filesDir, LIKED_FILE).writeTextAtomically(
                LibrarySyncProtocol.json.encodeToString(record),
            )
        }
    }

    private const val RECO_PREFIX = "dynamic:rec:"
    private const val ARTIST_PAGE_PREFIX = "artist:"
    private const val ALBUM_PAGE_PREFIX = "album:"
    private const val DOWNLOADS_ID = "dynamic:downloads"

    /** 在线歌手页：复用播放列表界面，先占位进入，页内再拉热门歌曲。 */
    fun stageArtistPage(artistId: Long, name: String, coverUrl: String = "") {
        if (artistId <= 0) return
        System.err.println("NETENTITY artist=$artistId name=$name")
        val id = "$ARTIST_PAGE_PREFIX$artistId"
        stagePlaylist(id, name.ifBlank { "歌手" }, coverUrl, null, 0, dynamicKind = "artist")
        select(id)
    }

    /** 在线专辑页：同样复用播放列表界面。 */
    fun stageAlbumPage(albumId: Long, name: String, coverUrl: String = "") {
        if (albumId <= 0) return
        System.err.println("NETENTITY album=$albumId name=$name")
        val id = "$ALBUM_PAGE_PREFIX$albumId"
        stagePlaylist(id, name.ifBlank { "专辑" }, coverUrl, null, 0, dynamicKind = "album")
        select(id)
    }

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
                        description = summary?.description?.takeIf { it.isNotBlank() }
                            ?: previous[id]?.description.orEmpty(),
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
            File(context.filesDir, RADAR_FILE).writeTextAtomically(
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
        // 首页动态分区（曲风精选/歌曲推荐/艺人新动向/推荐歌单）：启动后台加载。
        ioScope.launch { loadHomeFeed() }
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
                playlistId.startsWith(ARTIST_PAGE_PREFIX) -> runCatching {
                    val artistId = playlistId.removePrefix(ARTIST_PAGE_PREFIX).toLongOrNull()
                        ?: error("歌手 ID 无效")
                    val entries = NeteaseClient.fetchArtistTopSongs(artistId.toString(), cookie(context)).getOrThrow()
                    val cover = entries.firstOrNull()?.coverUrl.orEmpty()
                    applyPayload(
                        context,
                        playlistId,
                        NeteasePlaylistPayload(
                            id = playlistId,
                            name = existing.name,
                            coverUrl = cover.ifBlank { existing.coverUrl },
                            entries = entries,
                        ),
                        dynamicKind = "artist",
                        addedToPlaylists = false,
                        awaitCovers = false,
                    )
                    ioScope.launch { ensureEntryCovers(context, playlistId) }
                    Unit
                }
                playlistId.startsWith(ALBUM_PAGE_PREFIX) -> runCatching {
                    val albumId = playlistId.removePrefix(ALBUM_PAGE_PREFIX).toLongOrNull()
                        ?: error("专辑 ID 无效")
                    val payload = NeteaseClient.fetchAlbumDetail(albumId, cookie(context)).getOrThrow()
                    applyPayload(
                        context,
                        playlistId,
                        payload.copy(name = existing.name.ifBlank { payload.name }),
                        dynamicKind = "album",
                        addedToPlaylists = false,
                        awaitCovers = false,
                    )
                    ioScope.launch { ensureEntryCovers(context, playlistId) }
                    Unit
                }
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
                albumId = entry.albumId,
                artistIds = entry.artistIds,
            )
        }
        return NeteasePlaylistRecord(
            id = id,
            name = payload.name.ifBlank { if (id == DAILY_ID) "每日推荐" else "私人雷达" },
            coverUrl = payload.coverUrl,
            importedAt = Instant.now().toString(),
            addedToPlaylists = false,
            coverPath = downloadPlaylistCover(context, id.replace(':', '_'), payload.coverUrl),
            entries = entries,
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
                // 当前种子拿不到推荐时，依次回退到红心最新、每日推荐第一首，避免官方有推荐我们却没有。
                var entry = fetchNextHeartbeat(context, seed, startSongId = seed)
                if (entry == null) {
                    val likedSeed = likedNewestSongId(context)?.takeIf { it != seed }
                    if (likedSeed != null) entry = fetchNextHeartbeat(context, likedSeed, startSongId = likedSeed)
                }
                if (entry == null) {
                    val dailySeed = playlistById(DAILY_ID)?.entries?.firstOrNull()?.songId
                        ?.takeIf { it != seed }
                    if (dailySeed != null) entry = fetchNextHeartbeat(context, dailySeed, startSongId = dailySeed)
                }
                val resolved = entry ?: error("心动模式暂时没有推荐")
                entryTrack(resolved).id
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
        val primaryPid = heartbeatPlaylistId(context, seed)
        val fallbackPid = cachedHeartbeatPlaylistId?.takeIf { it != primaryPid }
            ?: NeteaseClient.fetchLikedPlaylistId(cookie(context)).getOrNull()
                ?.takeIf { it != primaryPid }
                ?.also { cachedHeartbeatPlaylistId = it }
        val pids = listOfNotNull(primaryPid, fallbackPid).ifEmpty { listOf(null) }
        var list: List<NeteaseEntryPayload> = emptyList()
        for (round in 0 until 2) {
            for (pid in pids) {
                list = NeteaseClient.fetchHeartbeatNext(seed, startSongId, pid, cookie(context), count = 10)
                    .onFailure { System.err.println("NETHB repository failed pid=$pid: ${it.message}") }
                    .getOrNull()
                    .orEmpty()
                if (list.isNotEmpty()) break
            }
            if (list.isNotEmpty()) break
            delay(300)
        }
        val entry = list.firstOrNull { payload ->
            payload.songId !in recentHeartbeatIds &&
                AndroidSyncRuntime.syncStore.trackForPlayback("netease:${payload.songId}") == null
        } ?: list.firstOrNull { it.songId !in recentHeartbeatIds }
            ?: list.firstOrNull()
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

    /** 播放曲目对应的网易云歌曲 ID（用于歌词等按 ID 查询）；仅网易云自己的曲目有映射。 */
    fun songIdForTrack(trackId: String): Long? =
        if (trackId.startsWith("netease:")) trackId.removePrefix("netease:").toLongOrNull() else null

    /** 当前播放曲目需要文件封面时按需补一张，不随整单队列批量下载。 */
    fun ensurePlaybackCover(trackId: String) {
        val app = appContext ?: return
        val songId = songIdForTrack(trackId) ?: return
        val cached = NeteaseCoverStore.cached(app, songId)
        if (cached != null) {
            // 文件已在盘上但队列条目还没带上封面（预取过的曲目）：直接回写，播放器立刻能显示。
            val track = AndroidSyncRuntime.syncStore.trackForPlayback(trackId) ?: return
            if (track.artworkPath != cached.absolutePath) {
                queueCoverUpdate(track.copy(artworkPath = cached.absolutePath))
            }
            return
        }
        val track = AndroidSyncRuntime.syncStore.trackForPlayback(trackId) ?: return
        val coverUrl = coverUrlForTrackId(trackId, songId) ?: return
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

    /** 播放页封面信息：URL 给 Coil 直接取图（与列表共用缓存），key 用于判断 600/1600 是否同一首。 */
    data class PlaybackCoverInfo(val url: String?, val key: String)

    suspend fun playbackCoverInfo(trackId: String): PlaybackCoverInfo = withContext(Dispatchers.IO) {
        val songId = songIdForTrack(trackId)
        PlaybackCoverInfo(
            url = songId?.let { coverUrlForTrackId(trackId, it) },
            key = songId?.let { "netease:$it" } ?: trackId,
        )
    }

    /** 曲目的网易云封面地址：先看曲目元数据，再回查歌单条目（本地匹配曲目）。 */
    private fun coverUrlForTrackId(trackId: String, songId: Long): String? =
        AndroidSyncRuntime.syncStore.trackForPlayback(trackId)?.let(::coverUrlForTrack)
            ?: activePlaylists.value.asSequence()
                .flatMap { it.entries.asSequence() }
                .firstOrNull { it.songId == songId }
                ?.coverUrl
                ?.takeIf { it.isNotBlank() }

    /** 用户明确点过下载的网易云歌曲（本地文件，不是流缓存）。 */
    fun isExplicitlyDownloaded(track: LibraryTrack): Boolean =
        track.id.startsWith("netease:") &&
            track.audioPath?.let { path -> !path.startsWith("netease://") && File(path).isFile } == true

    fun downloadedTracks(): List<LibraryTrack> =
        AndroidSyncRuntime.syncStore.tracks.value
            .filter(::isExplicitlyDownloaded)
            .sortedByDescending { it.updatedAt }

    /** “已下载的音乐”复用播放列表页：把下载曲目映射成临时歌单，不写盘、不进播放列表。 */
    fun stageDownloadsPage(name: String) {
        val tracks = downloadedTracks()
        if (tracks.isEmpty()) return
        val entries = tracks.mapNotNull { track ->
            val songId = track.id.removePrefix("netease:").toLongOrNull() ?: return@mapNotNull null
            NeteaseEntryRecord(
                songId = songId,
                title = track.title,
                artists = track.artists,
                album = track.album,
                coverUrl = coverUrlForTrack(track).orEmpty(),
                coverPath = track.artworkPath,
                downloadedFile = track.audioPath,
                albumId = neteaseAlbumIdForTrack(track) ?: 0L,
                artistIds = listOfNotNull(neteaseArtistIdForTrack(track)),
            )
        }
        if (entries.isEmpty()) return
        val record = NeteasePlaylistRecord(
            id = DOWNLOADS_ID,
            name = name,
            coverUrl = "",
            coverPath = entries.firstOrNull()?.coverPath,
            importedAt = Instant.now().toString(),
            addedToPlaylists = false,
            dynamicKind = "downloads",
            entries = entries,
        )
        _playlists.value = _playlists.value.filterNot { it.id == DOWNLOADS_ID } + record
        publishActive()
        select(DOWNLOADS_ID)
    }

    /** 删除一首已下载的网易云歌曲：移除导入曲目与本地文件，并把歌单条目改回未下载。 */
    suspend fun deleteDownload(context: Context, trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val songId = trackId.removePrefix("netease:").toLongOrNull()
                ?: error("不是网易云曲目")
            val store = AndroidSyncRuntime.syncStore
            val track = store.trackForPlayback(trackId) ?: error("曲目不存在")
            track.audioPath
                ?.takeIf { !it.startsWith("netease://") }
                ?.let(::File)
                ?.delete()
            store.setImportedTracks(store.importedTracksSnapshot().filterNot { it.id == trackId })
            _playlists.value = _playlists.value.map { playlist ->
                if (playlist.id == DOWNLOADS_ID) {
                    playlist.copy(entries = playlist.entries.filterNot { it.songId == songId })
                } else {
                    playlist.copy(
                        entries = playlist.entries.map { entry ->
                            if (entry.songId == songId) entry.copy(downloadedFile = null, downloadFormat = null) else entry
                        },
                    )
                }
            }
            publishActive()
            persistNow(context)
            Unit
        }.onFailure { System.err.println("NETDELETE download track=$trackId err=${it.message}") }
    }

    /** 曲目可用的远端封面地址（供图片加载器直接按尺寸请求）。 */
    fun coverUrlForTrack(track: LibraryTrack): String? =
        track.metadataObject()?.get("coverUrl")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }

    /** 全屏播放器用的高清封面：命中缓存时直接返回路径。 */
    fun cachedHiResCover(trackId: String): String? {
        val app = appContext ?: return null
        val songId = songIdForTrack(trackId) ?: return null
        return NeteaseHiResCoverStore.cached(app, songId)?.absolutePath
    }

    /** 打开全屏播放器时按需补一张 1600² 封面；失败返回 null，界面继续用 600² 图。 */
    suspend fun ensureHiResCover(trackId: String): String? = withContext(Dispatchers.IO) {
        val app = appContext ?: return@withContext null
        val songId = songIdForTrack(trackId) ?: return@withContext null
        val cached = NeteaseHiResCoverStore.cached(app, songId)
        if (cached != null) return@withContext cached.absolutePath
        val coverUrl = coverUrlForTrackId(trackId, songId) ?: return@withContext null
        NeteaseHiResCoverStore.ensure(app, songId, coverUrl, cookie(app))?.absolutePath
    }

    /** 曲目关联的网易云专辑 ID（有则菜单可进在线专辑页）。 */
    fun neteaseAlbumIdForTrack(track: LibraryTrack): Long? {
        track.metadataObject()?.get("neteaseAlbumId")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?.toLongOrNull()
            ?.let { return it }
        // 匹配到本地的曲目本身没有网络元数据：按 songId 回查歌单条目。
        val songId = songIdForTrack(track.id) ?: return null
        activePlaylists.value.forEach { playlist ->
            playlist.entries.firstOrNull { it.songId == songId && it.albumId > 0 }?.let { return it.albumId }
        }
        return null
    }

    /** 曲目关联的第一个网易云歌手 ID（含本地匹配曲目的回查）。 */
    fun neteaseArtistIdForTrack(track: LibraryTrack): Long? {
        track.metadataObject()?.get("neteaseArtistId")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?.toLongOrNull()
            ?.let { return it }
        val songId = songIdForTrack(track.id) ?: return null
        activePlaylists.value.forEach { playlist ->
            playlist.entries.firstOrNull { it.songId == songId && it.artistIds.isNotEmpty() }
                ?.let { return it.artistIds.first() }
        }
        return null
    }

    /** 该歌单当前可加入播放队列的曲目（已匹配本地或已下载）。结果按歌单条目缓存，避免组合期重复全量遍历。 */
    private data class PlayableIdsCache(val entries: List<NeteaseEntryRecord>, val ids: List<String>)
    private val playableIdsCache = ConcurrentHashMap<String, PlayableIdsCache>()

    fun playableTrackIds(playlistId: String): List<String> {
        val playlist = playlistById(playlistId) ?: return emptyList()
        playableIdsCache[playlistId]?.takeIf { it.entries === playlist.entries }?.let { return it.ids }
        val ids = playlist.entries.map { entry -> entryTrack(entry, ensureCover = false).id }.distinct()
        playableIdsCache[playlistId] = PlayableIdsCache(playlist.entries, ids)
        return ids
    }

    /** 一键下载歌单里尚未下载的歌；下载就是本地保存，不再需要转存。 */
    suspend fun downloadAll(context: Context, playlistId: String): DownloadAllResult =
        withContext(Dispatchers.IO) {
            val playlist = playlistById(playlistId) ?: return@withContext DownloadAllResult(0, 0)
            val pending = playlist.entries.filter { entry ->
                entry.downloadedFile?.let(::File)?.isFile != true
            }
            if (pending.isEmpty()) return@withContext DownloadAllResult(0, 0)
            _downloadProgress.update { it + (playlistId to (0 to pending.size)) }
            var done = 0
            var succeeded = 0
            for (entry in pending) {
                if (runCatching { ensureDownloaded(context, playlistId, entry.songId) }.isSuccess) succeeded++
                done++
                _downloadProgress.update { it + (playlistId to (done to pending.size)) }
            }
            _downloadProgress.update { it - playlistId }
            DownloadAllResult(total = pending.size, succeeded = succeeded)
        }

    /** 导入后按需补齐每首歌的封面（限制并发，失败忽略，不阻塞界面）。 */
    private suspend fun ensureEntryCovers(context: Context, playlistId: String, limit: Int = 20) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val pending = playlist.entries.filter { entry ->
            entry.coverPath?.let { File(it).isFile } != true && entry.coverUrl.isNotBlank()
        }.take(limit)
        if (pending.isEmpty()) return
        pending.chunked(4).forEachIndexed { index, chunk ->
            // 统一走 NeteaseCoverStore：同一首歌的封面下载不会和播放器补图互相覆盖。
            val resolved = kotlinx.coroutines.coroutineScope {
                chunk.map { entry ->
                    async {
                        entry.songId to NeteaseCoverStore
                            .ensure(context, entry.songId, entry.coverUrl, cookie(context))
                            .await()
                            ?.absolutePath
                    }
                }.awaitAll()
            }
            val paths = resolved.filter { it.second != null }.associate { it.first to it.second!! }
            if (paths.isEmpty()) return@forEachIndexed
            synchronized(this@NeteaseRepository) {
                val current = playlistById(playlistId) ?: return@synchronized
                updateRecord(
                    current.copy(
                        entries = current.entries.map { entry ->
                            paths[entry.songId]?.let { entry.copy(coverPath = it) } ?: entry
                        },
                    ),
                )
            }
        }
        // 单写者会合并多次请求：这里只需在结束时投递一次最新快照。
        persistSoon()
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
            // 网易云实体 ID：歌曲菜单“前往专辑/歌手”据此打开在线页。
            if (entry.albumId > 0) put("neteaseAlbumId", entry.albumId)
            if (entry.artistIds.isNotEmpty()) {
                put("neteaseArtistId", entry.artistIds.first())
                put("neteaseArtistIds", JsonArray(entry.artistIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
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

    /** 只持久化“已加入播放列表”的歌单；我的页缓存歌单写单独文件，随进程退出保留。 */
    private fun snapshotForPersist(): PersistSnapshot {
        val all = _playlists.value
        return PersistSnapshot(
            version = persistVersion.incrementAndGet(),
            added = all.filter { it.addedToPlaylists },
            cached = all.filter { it.persistCache && !it.addedToPlaylists },
        )
    }

    /** 后台落盘：合并多次请求，只写最新快照；调用线程不碰磁盘。 */
    private fun persistSoon() {
        pendingPersist.set(snapshotForPersist())
        persistRequests.trySend(Unit)
    }

    /** 用户动作落盘：等到真正写完再返回，失败仍由内部 runCatching 兜住。 */
    private suspend fun persistNow(context: Context) {
        diskLoaded.await()
        val snapshot = snapshotForPersist()
        withContext(Dispatchers.IO) {
            persistMutex.withLock { writePersist(context.applicationContext, snapshot) }
        }
    }

    private fun writePersist(context: Context, snapshot: PersistSnapshot) {
        // 单写者 + 版本号：更旧的排队快照不会覆盖刚写下的新快照。
        if (snapshot.version <= persistedVersion.get()) return
        persistedVersion.set(snapshot.version)
        runCatching {
            File(context.filesDir, PLAYLIST_FILE).writeTextAtomically(
                LibrarySyncProtocol.json.encodeToString(snapshot.added),
            )
        }
        runCatching {
            File(context.filesDir, CACHE_PLAYLIST_FILE).writeTextAtomically(
                LibrarySyncProtocol.json.encodeToString(snapshot.cached),
            )
        }
    }
}
