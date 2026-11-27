package com.exodidio.tune.netease

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.exodidio.tune.player.AudioQualityRuntime
import com.exodidio.tune.player.ListeningWrite
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.writeTextAtomically
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * 网易云歌单：只保存元数据和本地绑定，音频按需下载。
 * 网络与磁盘全部在 IO 线程，UI 只观察 StateFlow。
 */
internal object NeteaseRepository {
    private const val PREFS = "netease"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_HISTORY_REPORT = "netease_history_report_enabled"
    private const val KEY_WIFI_AUTO_CACHE_IDS = "netease_wifi_auto_cache_ids"
    private const val PLAYLIST_FILE = "netease-playlists.json"
    private const val CACHE_PLAYLIST_FILE = "netease-cached-playlists.json"
    private const val LIKED_FILE = "netease-liked.json"
    private const val RADAR_FILE = "netease-radars.json"
    private const val ACCOUNT_FILE = "netease-account.json"
    internal const val MINE_FILE = "netease-mine.json"
    /** 导入/镜像歌单的封面属于持久数据，不放进可淘汰的封面缓存目录。 */
    private const val PLAYLIST_COVER_DIR = "netease-playlist-covers"

    internal const val DAILY_ID = "dynamic:daily"
    private const val RADAR_ID = "dynamic:radar"
    internal const val LIKED_ID = "dynamic:liked"
    private const val SNAPSHOT_PREFIX = "snapshot:"
    internal const val DISCOVER_TTL_MS = 10 * 60 * 1000L
    private const val RECO_PREFIX = "dynamic:rec:"
    internal const val ARTIST_PAGE_PREFIX = "artist:"
    internal const val ALBUM_PAGE_PREFIX = "album:"
    internal const val DOWNLOADS_ID = "dynamic:downloads"

    private val _playlists = MutableStateFlow(emptyList<NeteasePlaylistRecord>())
    val playlists: StateFlow<List<NeteasePlaylistRecord>> = _playlists.asStateFlow()

    private val _activePlaylists = MutableStateFlow(emptyList<NeteasePlaylistRecord>())
    /** 持久化歌单 + 动态歌单（每日推荐/雷达/我喜欢的音乐），详情页共用。 */
    val activePlaylists: StateFlow<List<NeteasePlaylistRecord>> = _activePlaylists.asStateFlow()

    private val _radars = MutableStateFlow(emptyList<NeteaseRadarSummary>())
    val radars: StateFlow<List<NeteaseRadarSummary>> = _radars.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<String?>(null)
    val selectedPlaylistId: StateFlow<String?> = _selectedPlaylistId.asStateFlow()

    private val _selectedArtistId = MutableStateFlow<String?>(null)
    val selectedArtistId: StateFlow<String?> = _selectedArtistId.asStateFlow()

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    // 一键下载进度：歌单 ID -> (已完成, 总数)
    private var _homeStatus = MutableStateFlow("")
    val homeStatus: StateFlow<String> = _homeStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Pair<Int, Int>>> = _downloadProgress.asStateFlow()
    internal val downloadProgressState: MutableStateFlow<Map<String, Pair<Int, Int>>>
        get() = _downloadProgress

    private var loaded = false
    internal var appContext: Context? = null
    internal val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 流播直链（10 分钟有效，过期后重新获取）。
    private data class StreamEntry(
        val url: String,
        val expiresAtMs: Long,
        val preview: Boolean,
        val previewDurationMs: Long,
        val durationMs: Long,
    )

    private val streamUrls = ConcurrentHashMap<Long, StreamEntry>()
    private val streamFailures = ConcurrentHashMap<Long, String>()

    /** 取流接口明确返回“需要登录/登录失效”。重登前不把歌判成无版权。 */
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    // 歌单落盘单写者：锁内只取内存快照，编码与原子写在 IO 单协程里做。
    private data class PersistSnapshot(
        val version: Long,
        val added: List<NeteasePlaylistRecord>,
        val cached: List<NeteasePlaylistRecord>,
    )
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val persistMutex = Mutex()
    private val playlistsLock = Any()
    private val coverFlushLock = Any()
    private val pendingPersist = AtomicReference<PersistSnapshot?>(null)
    private val persistVersion = AtomicLong(0L)
    private val persistedVersion = AtomicLong(0L)
    // 启动读盘在 IO 上完成；任何落盘都要等它，避免把空/半装状态写回磁盘。
    private val diskLoaded = CompletableDeferred<Unit>()

    private val _accountInfo = MutableStateFlow<NeteaseClient.AccountInfo?>(null)
    val accountInfo: StateFlow<NeteaseClient.AccountInfo?> = _accountInfo.asStateFlow()
    private var accountInfoJob: Job? = null
    @Volatile private var accountLoadedAt = 0L

    // 短信登录两次请求之间要复用 NMTID 等 cookie。
    @Volatile
    private var phoneAuthCookie: String? = null

    private val pendingCoverUpdates = ConcurrentHashMap<String, LibraryTrack>()
    private var coverFlushJob: Job? = null

    /** 该歌单当前可加入播放队列的曲目（已匹配本地或已下载）。结果按歌单条目缓存，避免组合期重复全量遍历。 */
    private data class PlayableIdsCache(val entries: List<NeteaseEntryRecord>, val ids: List<String>)
    private val playableIdsCache = ConcurrentHashMap<String, PlayableIdsCache>()

    /** 播放页封面信息：URL 给 Coil 直接取图（与列表共用缓存），key 用于判断 600/1600 是否同一首。 */
    data class PlaybackCoverInfo(val url: String?, val key: String)

    // ---- Feed / Mode 状态转发 ----
    val discover: StateFlow<DiscoverData> get() = NeteaseFeedLoader.discover
    val homeFeed: StateFlow<HomeFeedData> get() = NeteaseFeedLoader.homeFeed
    val mine: StateFlow<MineData> get() = NeteaseFeedLoader.mine
    val artistDetail: StateFlow<NeteaseArtistDetailState> get() = NeteaseFeedLoader.artistDetail
    val albumSubscribedMap: StateFlow<Map<String, Boolean>> get() = NeteaseFeedLoader.albumSubscribedMap
    val playRecords: StateFlow<NeteasePlayRecordsState> get() = NeteaseFeedLoader.playRecords
    val recommendedPlaylists: StateFlow<RecommendedPlaylistsState> get() = NeteaseFeedLoader.recommendedPlaylists
    val cloudDisk: StateFlow<NeteaseCloudDiskState> get() = NeteaseFeedLoader.cloudDisk
    val discoverSquare: StateFlow<DiscoverSquareState> get() = NeteaseFeedLoader.discoverSquare
    val searchHot: StateFlow<List<NeteaseSearchHotItem>> get() = NeteaseFeedLoader.searchHot
    val searchHotLoading: StateFlow<Boolean> get() = NeteaseFeedLoader.searchHotLoading
    val searchGuess: StateFlow<List<String>> get() = NeteaseFeedLoader.searchGuess
    val searchGuessLoading: StateFlow<Boolean> get() = NeteaseFeedLoader.searchGuessLoading
    val searchSuggestions: StateFlow<List<String>> get() = NeteaseFeedLoader.searchSuggestions
    val searchHistory: StateFlow<List<String>> get() = NeteaseFeedLoader.searchHistory
    val recentTracks: StateFlow<List<LibraryTrack>> get() = NeteaseFeedLoader.recentTracks

    private val _historyReportEnabled = MutableStateFlow(true)
    val historyReportEnabled: StateFlow<Boolean> = _historyReportEnabled.asStateFlow()

    private val _wifiAutoCacheIds = MutableStateFlow<Set<String>>(emptySet())
    val wifiAutoCacheIds: StateFlow<Set<String>> = _wifiAutoCacheIds.asStateFlow()

    val heartbeatEnabled: StateFlow<Boolean> get() = NeteaseModeEngine.heartbeatEnabled
    val roamEnabled: StateFlow<Boolean> get() = NeteaseModeEngine.roamEnabled
    val similarEnabled: StateFlow<Boolean> get() = NeteaseModeEngine.similarEnabled
    val heartbeatPreview: StateFlow<NeteaseModePreview> get() = NeteaseModeEngine.heartbeatPreview
    val roamPreview: StateFlow<NeteaseModePreview> get() = NeteaseModeEngine.roamPreview
    val similarPreview: StateFlow<NeteaseModePreview> get() = NeteaseModeEngine.similarPreview
    val likedSongIds: StateFlow<Set<Long>> get() = NeteaseWriteCoordinator.likedSongIds

    internal fun setAccountInfo(info: NeteaseClient.AccountInfo) {
        _accountInfo.value = info
    }

    internal fun markSessionExpired() {
        _sessionExpired.value = true
    }

    fun isNeteaseLiked(songId: Long): Boolean = NeteaseWriteCoordinator.isLiked(songId)

    fun likedPlaylistRemoteId(): String? = NeteaseWriteCoordinator.likedPlaylistRemoteId()

    fun isNeteasePlaylistSaved(playlistId: String): Boolean = NeteaseFeedLoader.isPlaylistSaved(playlistId)

    fun isArtistFollowed(artistId: String): Boolean = NeteaseFeedLoader.isArtistFollowed(artistId)

    fun isAlbumSubscribed(albumId: String): Boolean = NeteaseFeedLoader.isAlbumSubscribed(albumId)

    fun checkAlbumSubscribed(albumId: String, force: Boolean = false) =
        NeteaseFeedLoader.checkAlbumSubscribed(albumId, force)

    fun isHistoryReportEnabled(context: Context? = appContext): Boolean {
        val ctx = context ?: return _historyReportEnabled.value
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HISTORY_REPORT, true)
            .also { _historyReportEnabled.value = it }
    }

    fun setHistoryReportEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HISTORY_REPORT, enabled).apply()
        _historyReportEnabled.value = enabled
    }

    fun isWifiAutoCacheEnabled(playlistId: String): Boolean =
        playlistId in _wifiAutoCacheIds.value

    fun setWifiAutoCacheEnabled(context: Context, playlistId: String, enabled: Boolean) {
        val next = if (enabled) {
            _wifiAutoCacheIds.value + playlistId
        } else {
            _wifiAutoCacheIds.value - playlistId
        }
        _wifiAutoCacheIds.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIFI_AUTO_CACHE_IDS, encodeWifiAutoCacheIds(next))
            .apply()
        NeteaseWifiAutoCacheManager.refreshNow(context.applicationContext)
    }

    fun clearWifiAutoCache(context: Context) {
        _wifiAutoCacheIds.value = emptySet()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_WIFI_AUTO_CACHE_IDS)
            .apply()
    }

    fun playlistPermission(playlistId: String): NeteasePlaylistPermission {
        val record = playlistById(playlistId)
        val isDynamicOrVirtual = isDynamic(playlistId) ||
            isDownloadsPage(playlistId) ||
            playlistId.startsWith(SNAPSHOT_PREFIX) ||
            playlistId.startsWith(ARTIST_PAGE_PREFIX) ||
            playlistId.startsWith(ALBUM_PAGE_PREFIX) ||
            playlistId.startsWith(RECO_PREFIX) ||
            record?.dynamicKind in setOf("daily", "radar", "artist", "album", "rec") ||
            record?.dynamicKind?.startsWith("snapshot:") == true
        val isRadar = RadarCatalog.any { it.first == playlistId } || (record != null && isRadarPlaylist(record))
        val isOwned = NeteaseFeedLoader.isPlaylistCreated(playlistId)
        return resolveNeteasePlaylistPermission(
            playlistId = playlistId,
            likedRemoteId = NeteaseWriteCoordinator.likedPlaylistRemoteId(),
            isDynamicOrVirtual = isDynamicOrVirtual,
            isRadar = isRadar,
            isOwnedCreated = isOwned,
        )
    }

    fun refreshLikedIds(force: Boolean = false) = NeteaseWriteCoordinator.refreshLikedIds(force)

    suspend fun setTrackLiked(
        context: Context,
        track: LibraryTrack?,
        songId: Long,
        liked: Boolean,
    ): Result<Unit> = NeteaseWriteCoordinator.setTrackLiked(context, track, songId, liked)

    suspend fun createNeteasePlaylist(
        context: Context,
        name: String,
        description: String = "",
        privacyPrivate: Boolean = false,
        initialSongs: List<Pair<Long, LibraryTrack?>> = emptyList(),
    ): Result<String> = NeteaseWriteCoordinator.createPlaylist(context, name, description, privacyPrivate, initialSongs)

    suspend fun updateNeteasePlaylist(
        context: Context,
        playlistId: String,
        name: String,
        description: String,
    ): Result<Unit> = NeteaseWriteCoordinator.updatePlaylist(context, playlistId, name, description)

    suspend fun deleteNeteasePlaylist(
        context: Context,
        playlistId: String,
    ): Result<Unit> = NeteaseWriteCoordinator.deletePlaylist(context, playlistId)

    suspend fun manipulateNeteasePlaylistTracks(
        context: Context,
        playlistId: String,
        tracks: List<Pair<Long, LibraryTrack?>>,
        add: Boolean,
    ): Result<Unit> = NeteaseWriteCoordinator.manipulatePlaylistTracks(context, playlistId, tracks, add)

    suspend fun setNeteasePlaylistSubscribed(
        context: Context,
        playlist: DiscoverPlaylist?,
        playlistId: String,
        subscribe: Boolean,
    ): Result<Unit> = NeteaseWriteCoordinator.setPlaylistSubscribed(context, playlist, playlistId, subscribe)

    suspend fun setArtistFollowed(
        context: Context,
        artist: MineArtist?,
        artistId: String,
        follow: Boolean,
    ): Result<Unit> = NeteaseWriteCoordinator.setArtistFollowed(context, artist, artistId, follow)

    suspend fun setAlbumSubscribed(
        context: Context,
        album: MineAlbum?,
        albumId: String,
        subscribe: Boolean,
    ): Result<Unit> = NeteaseWriteCoordinator.setAlbumSubscribed(context, album, albumId, subscribe)

    suspend fun reorderNeteasePlaylistTracks(
        context: Context,
        playlistId: String,
        orderedSongIds: List<Long>,
    ): Result<Unit> = NeteaseWriteCoordinator.reorderPlaylistTracks(context, playlistId, orderedSongIds)

    suspend fun updateNeteasePlaylistPrivacy(
        context: Context,
        playlistId: String,
        privacyPrivate: Boolean,
    ): Result<Unit> = NeteaseWriteCoordinator.updatePlaylistPrivacy(context, playlistId, privacyPrivate)

    fun loadCloudDisk(force: Boolean = false) = NeteaseFeedLoader.loadCloudDisk(force)
    fun loadMoreCloudDisk() = NeteaseFeedLoader.loadMoreCloudDisk()
    suspend fun deleteCloudDiskTracks(context: Context, songIds: List<Long>): Result<Unit> =
        NeteaseFeedLoader.deleteCloudDiskTracks(context, songIds)

    fun openDiscoverSquare(tab: DiscoverSquareTab = DiscoverSquareTab.Playlists, category: String? = null) =
        NeteaseFeedLoader.openDiscoverSquare(tab, category)
    fun selectSquareTab(tab: DiscoverSquareTab) = NeteaseFeedLoader.selectSquareTab(tab)
    fun selectSquareCategory(category: String) = NeteaseFeedLoader.selectSquareCategory(category)
    fun loadMoreSquareTopPlaylists() = NeteaseFeedLoader.loadMoreSquareTopPlaylists()
    fun loadMoreSquareHighQualityPlaylists() = NeteaseFeedLoader.loadMoreSquareHighQualityPlaylists()
    fun loadSquareRankings() = NeteaseFeedLoader.loadSquareRankings()
    fun selectNewAlbumsArea(area: String) = NeteaseFeedLoader.selectNewAlbumsArea(area)
    fun loadMoreSquareNewAlbums() = NeteaseFeedLoader.loadMoreSquareNewAlbums()
    fun loadMoreSquareTopArtists() = NeteaseFeedLoader.loadMoreSquareTopArtists()

    fun loadSearchHot(force: Boolean = false) = NeteaseFeedLoader.loadSearchHot(force)
    fun loadSearchGuess(force: Boolean = false) = NeteaseFeedLoader.loadSearchGuess(force)
    suspend fun fetchSearchSuggestions(keyword: String): List<String> =
        NeteaseFeedLoader.fetchSearchSuggestions(keyword)
    fun recordSearchHistory(context: Context, keyword: String) =
        NeteaseFeedLoader.recordSearchHistory(context, keyword)
    fun removeSearchHistory(context: Context, keyword: String) =
        NeteaseFeedLoader.removeSearchHistory(context, keyword)
    fun clearSearchHistory(context: Context) =
        NeteaseFeedLoader.clearSearchHistory(context)

    suspend fun fmTrashTrack(context: Context, songId: Long, timeSeconds: Long = 0L): Result<Unit> =
        NeteaseClient.fmTrashTrack(songId, timeSeconds, cookie(context))

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        NeteaseWireConfig.initialize(app)
        NeteaseStreamProxy.setUpstreamRefresher { songId, registerCancel ->
            refreshStreamUrlBlockingWithTimeout(app, songId, registerCancel)
        }
        NeteaseStreamProxy.initialize(app)
        NeteaseFeedLoader.loadSearchHistory(app)
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
            NeteaseFeedLoader.subscribeRecentTracks()
            if (_loggedIn.value) {
                NeteaseWriteCoordinator.refreshLikedIds(force = false)
            }
            _playlists.value.forEach { playlist ->
                ioScope.launch {
                    NeteaseMediaHelper.ensureEntryCovers(app, playlist.id)
                    if (playlist.id == LIKED_ID) persistLiked(app)
                }
            }
            ioScope.launch { NeteaseMediaHelper.backfillPlaybackCovers() }
            ioScope.launch { NeteaseMediaHelper.backfillPlaylistCovers() }
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
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _historyReportEnabled.value = prefs.getBoolean(KEY_HISTORY_REPORT, true)
        _wifiAutoCacheIds.value = decodeWifiAutoCacheIds(prefs.getString(KEY_WIFI_AUTO_CACHE_IDS, null))
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
        updatePlaylists { current ->
            current.forEach { merged[it.id] = it }
            merged.values.toList()
        }
        NeteaseWriteCoordinator.loadFromDisk(app, merged[LIKED_ID])
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
            NeteaseFeedLoader.applyCachedMine(cache)
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

    internal inline fun updatePlaylists(
        transform: (List<NeteasePlaylistRecord>) -> List<NeteasePlaylistRecord>,
    ): List<NeteasePlaylistRecord> = synchronized(playlistsLock) {
        val next = transform(_playlists.value)
        _playlists.value = next
        _activePlaylists.value = next
        next
    }

    internal fun updateRecord(record: NeteasePlaylistRecord) {
        updatePlaylists { list -> list.map { if (it.id == record.id) record else it } }
    }

    fun select(playlistId: String?) {
        _selectedPlaylistId.value = playlistId
    }

    fun playlistById(id: String): NeteasePlaylistRecord? =
        _playlists.value.firstOrNull { it.id == id }

    fun isDynamic(id: String): Boolean = id == DAILY_ID || id == RADAR_ID || id == LIKED_ID

    /** 动态歌单 / 每日推荐 / 雷达歌单：保存到本地时应生成“日期快照”，文案内显示为“保存快照”。 */
    fun isDynamicSnapshotPlaylist(
        playlistId: String,
        dynamicKind: String? = null,
        name: String = "",
        sourceUrl: String = "",
    ): Boolean {
        if (isDynamic(playlistId)) return true
        val record = playlistById(playlistId)
        val kind = dynamicKind ?: record?.dynamicKind
        val resolvedName = name.ifBlank { record?.name.orEmpty() }
        val resolvedSourceUrl = sourceUrl.ifBlank { record?.sourceUrl.orEmpty() }
        val sourceId = NeteaseClient.playlistIdFrom(resolvedSourceUrl)
        return kind == "radar" ||
            resolvedName.contains(RADAR_DISPLAY_NAME) ||
            playlistId == NeteaseClient.PrivateRadarPlaylistId ||
            sourceId == NeteaseClient.PrivateRadarPlaylistId ||
            RadarCatalog.any { it.first == playlistId || it.first == sourceId }
    }

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
        if (existing != null && existing.entries.isNotEmpty()) {
            val remoteChanged = trackCount > 0 && trackCount != existing.remoteTrackCount
            if (remoteChanged) {
                updateRecord(
                    existing.copy(
                        name = name.ifBlank { existing.name },
                        coverUrl = coverUrl.ifBlank { existing.coverUrl },
                        coverPath = coverPath ?: existing.coverPath,
                        remoteTrackCount = trackCount,
                        fullyHydrated = false,
                    ),
                )
                if (existing.persistCache && !existing.addedToPlaylists) persistSoon()
            }
            return
        }
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
            fullyHydrated = existing?.fullyHydrated ?: false,
            entries = existing?.entries.orEmpty(),
        )
        updatePlaylists { list -> list.filterNot { it.id == playlistId } + record }
        if (record.persistCache && !record.addedToPlaylists) {
            persistSoon()
        }
    }

    /** 是否把歌单镜像到“播放列表”页；不影响设置里的导入记录。 */
    fun setAdded(playlistId: String, added: Boolean) {
        updatePlaylists { list ->
            list.map { playlist ->
                if (playlist.id == playlistId) playlist.copy(addedToPlaylists = added) else playlist
            }
        }
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
        invalidateStreamState()
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
        updatePlaylists { list -> list.filterNot { it.persistCache } }
        _loggedIn.value = false
        _accountInfo.value = null
        NeteaseWriteCoordinator.resetOnLogout(context)
        NeteaseFeedLoader.resetOnLogout()
        _homeStatus.value = ""
    }

    suspend fun createQrLogin(cookie: String? = null): Result<NeteaseLoginQr> = NeteaseClient.createQrLogin(cookie)

    suspend fun pollQrLogin(key: String, cookie: String?): Result<NeteaseQrStatus> =
        NeteaseClient.pollQrLogin(key, cookie)

    fun saveLogin(context: Context, cookie: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIE, cookie).apply()
        // 未登录时拿到的可能是 30 秒试听直链，登录后必须重新取流。
        invalidateStreamState()
        _sessionExpired.value = false
        _loggedIn.value = true
        NeteaseWriteCoordinator.onLoginChanged()
        loadAccountInfo(force = true)
        // 登录后立刻补齐首页/新发现/红心 IDs 的个人化内容。
        ioScope.launch {
            NeteaseWriteCoordinator.refreshLikedIds(force = true)
            runCatching { loadHome(context.applicationContext) }
            loadHomeFeed(force = true)
            loadDiscover(force = true)
            loadMine(force = true)
        }
    }

    /** 原生手机号密码登录：成功后拿到 MUSIC_U 会话并落盘。 */
    suspend fun loginByCellphone(context: Context, phone: String, password: String): Result<Unit> =
        NeteaseClient.loginCellphone(phone, password).mapCatching { result ->
            if (result.code != 200) {
                error(result.message.ifBlank { "登录失败（${result.code}）" })
            }
            val cookie = result.cookie ?: error("登录成功但未拿到会话，请改用网页登录")
            saveLogin(context, cookie)
        }

    suspend fun sendSmsCode(phone: String, countryCode: String): Result<Unit> =
        NeteaseClient.sendSmsCode(phone, countryCode, phoneAuthCookie).mapCatching { result ->
            if (result.code != 200) {
                error(result.message.ifBlank { "验证码发送失败（${result.code}）" })
            }
            phoneAuthCookie = result.cookie
        }

    suspend fun loginBySmsCode(
        context: Context,
        phone: String,
        countryCode: String,
        captcha: String,
    ): Result<Unit> =
        NeteaseClient.loginBySmsCode(phone, countryCode, captcha, phoneAuthCookie).mapCatching { result ->
            if (result.code != 200) {
                error(result.message.ifBlank { "登录失败（${result.code}）" })
            }
            val cookie = result.cookie ?: error("登录成功但未拿到会话，请改用网页登录")
            saveLogin(context, cookie)
            phoneAuthCookie = null
        }

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
                Log.w("NeteaseRepo", "account failed ${it.message}")
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
        Log.d(
            "NeteaseRepo",
            "import id=$id entries=${payload.entries.size} " +
                "fetch=${fetched - started}ms build=${System.currentTimeMillis() - fetched}ms",
        )
        val record = if (addToPlaylists) base.copy(addedToPlaylists = true) else base
        updatePlaylists { list -> list.filterNot { it.id == id } + record }
        if (record.addedToPlaylists) persistNow(context)
        ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, id) }
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
            updatePlaylists { list ->
                list.map {
                    if (it.id == playlistId) {
                        it.copy(entries = source.entries, coverUrl = source.coverUrl, coverPath = source.coverPath)
                    } else {
                        it
                    }
                }
            }
            persistNow(context)
            ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, playlistId) }
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
            updatePlaylists { list -> list.map { if (it.id == playlistId) updated else it } }
            persistNow(context)
            ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, playlistId) }
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
            ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, playlistId) }
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
            ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, playlistId) }
            return@withContext Result.success(Unit)
        }
        val payload = NeteaseClient.fetchPlaylist(playlistId, cookie(context)).getOrElse {
            return@withContext Result.failure(it)
        }
        val record = buildRecord(context, payload, existing).copy(
            dynamicKind = existing.dynamicKind,
            persistCache = existing.persistCache,
        )
        updatePlaylists { list -> list.map { if (it.id == playlistId) record else it } }
        if (record.addedToPlaylists) persistNow(context)
        updateRadarSummary(context, record)
        ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, playlistId) }
        Result.success(Unit)
    }

    /**
     * 详情页菜单的“保存到播放列表”：普通歌单直接加入本地播放列表；
     * 每日推荐/私人雷达/漫游/雷达歌单每次保存都生成一份新的带日期快照（不覆盖旧快照）。
     */
    suspend fun saveSnapshot(playlistId: String): String? {
        val source = playlistById(playlistId) ?: return null
        val sourceId = NeteaseClient.playlistIdFrom(source.sourceUrl)
        val isRadar = source.dynamicKind == "radar" ||
            source.name.contains(RADAR_DISPLAY_NAME) ||
            source.id == NeteaseClient.PrivateRadarPlaylistId ||
            sourceId == NeteaseClient.PrivateRadarPlaylistId ||
            RadarCatalog.any { it.first == playlistId || it.first == sourceId }
        val kind = when {
            playlistId == DAILY_ID -> "daily"
            playlistId == RADAR_ID -> "radar"
            playlistId == LIKED_ID -> "liked"
            isRadar -> "radar"
            else -> {
                if (!source.addedToPlaylists) {
                    updatePlaylists { list ->
                        list.map {
                            if (it.id == playlistId) it.copy(addedToPlaylists = true) else it
                        }
                    }
                    appContext?.let { persistNow(it) }
                }
                return playlistId
            }
        }
        val date = java.time.LocalDate.now().toString()
        val baseName = "${when {
            kind == "daily" -> "每日推荐"
            kind == "radar" && isRadar -> source.name.ifBlank { RADAR_DISPLAY_NAME }
            kind == "radar" -> RADAR_DISPLAY_NAME
            else -> "我喜欢的音乐"
        }} · $date"
        var createdSnapshotId = ""
        updatePlaylists { list ->
            val usedNames = list.mapTo(mutableSetOf()) { it.name }
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
            createdSnapshotId = snapshot.id
            list + snapshot
        }
        appContext?.let { persistNow(it) }
        return createdSnapshotId
    }

    suspend fun delete(playlistId: String) = withContext(Dispatchers.IO) {
        updatePlaylists { list -> list.filterNot { it.id == playlistId } }
        if (_selectedPlaylistId.value == playlistId) _selectedPlaylistId.value = null
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
        val base = buildRecord(
            context,
            payload,
            existing,
            fullyHydrated = payload.entries.isNotEmpty() || payload.remoteTrackCount == 0,
        )
        val record = base.copy(
            id = id,
            dynamicKind = dynamicKind,
            addedToPlaylists = addedToPlaylists,
            persistCache = existing?.persistCache ?: false,
        )
        updatePlaylists { list -> list.filterNot { it.id == id } + record }
        // 与导入一致；等待封面补齐，保证首页/详情一出现就带封面。大歌单可改为后台补齐。
        if (awaitCovers) NeteaseMediaHelper.ensureEntryCovers(context, id)
        persistSoon()
    }

    private suspend fun buildRecord(
        context: Context,
        payload: NeteasePlaylistPayload,
        existing: NeteasePlaylistRecord?,
        sourceUrl: String? = null,
        fullyHydrated: Boolean = true,
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
            remoteTrackCount = maxOf(payload.remoteTrackCount, fresh.size),
            description = payload.description.ifBlank { existing?.description.orEmpty() },
            fullyHydrated = fullyHydrated,
            privacy = payload.privacy,
            entries = fresh,
        )
    }

    internal suspend fun downloadPlaylistCover(
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
        if (path == null) Log.w("NeteaseRepo", "playlist cover failed id=$playlistId")
        return path
    }

    /** 打开交给 FFmpeg AVIOContext 的进程内流式数据源；缓存命中时完全不访问网络。 */
    suspend fun openStreamSource(songId: Long): NeteaseStreamByteSource {
        NeteaseStreamProxy.initialize(appContext ?: error("repository not initialized"))
        // 缓存初始化为非阻塞；真正播放前在这里等它就绪。
        NeteaseStreamProxy.awaitReady()
        return NeteaseStreamProxy.openByteSource(songId)
    }

    fun hasCachedStream(songId: Long): Boolean = NeteaseStreamProxy.hasCache(songId)

    fun cachedStreamUrl(songId: Long): String? =
        streamUrls[songId]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.url

    fun isPreviewStream(songId: Long): Boolean = streamUrls[songId]?.preview == true

    fun previewDurationMs(songId: Long): Long = streamUrls[songId]?.previewDurationMs ?: 0L

    /** 接口给出的整首时长；在线歌的展示/拖动用它，避免流式 MP3 时长估算跳动。 */
    fun streamDurationMs(songId: Long): Long = streamUrls[songId]?.durationMs ?: 0L

    /** 最近一次取流失败的原因（无版权/会员/登录失效…），供播放器提示。 */
    fun streamFailureReason(songId: Long): String? = streamFailures[songId]

    /** 打开流失败时丢弃缓存直链，下一次会重新向网易云取。 */
    fun invalidateStreamUrl(songId: Long) {
        streamUrls.remove(songId)
    }

    suspend fun streamUrl(context: Context, songId: Long): String? = withContext(Dispatchers.IO) {
        cachedStreamUrl(songId)?.let { return@withContext it }
        NeteaseClient.fetchSongStream(songId, cookie(context), AudioQualityRuntime.preferLossless(context))
            .getOrNull()?.let { applyStreamResolution(songId, it) }
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
        if (reusable) {
            streamFailures.remove(songId)
            return true
        }
        return refreshStreamUrl(ctx, songId) != null
    }

    /** 代理专用（仅在代理后台线程调用）：限时刷新过期/失效直链，避免同步阻塞主线程或无超时等待；支持被新 seek/cancel 立即打断。 */
    private fun refreshStreamUrlBlockingWithTimeout(
        context: Context,
        songId: Long,
        registerCancel: (() -> Unit) -> Unit = {},
    ): String? {
        val now = System.currentTimeMillis()
        val lastAt = lastProxyRefreshAt[songId] ?: 0L
        if (now - lastAt < 1_500L) return cachedStreamUrl(songId)
        val future = java.util.concurrent.CompletableFuture<String?>()
        val cancelledByCaller = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = ioScope.launch {
            try {
                future.complete(refreshStreamUrl(context, songId))
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        registerCancel {
            cancelledByCaller.set(true)
            future.cancel(true)
            job.cancel()
        }
        return try {
            val refreshed = future.get(8L, java.util.concurrent.TimeUnit.SECONDS)
            lastProxyRefreshAt[songId] = System.currentTimeMillis()
            refreshed
        } catch (_: Throwable) {
            if (!cancelledByCaller.get()) {
                lastProxyRefreshAt[songId] = System.currentTimeMillis()
            }
            future.cancel(true)
            job.cancel()
            null
        }
    }

    private val lastProxyRefreshAt = ConcurrentHashMap<Long, Long>()

    private suspend fun refreshStreamUrl(context: Context, songId: Long): String? =
        withContext(Dispatchers.IO) {
            NeteaseClient.fetchSongStream(songId, cookie(context), AudioQualityRuntime.preferLossless(context))
                .getOrNull()?.let { applyStreamResolution(songId, it) }
        }

    private fun applyStreamResolution(
        songId: Long,
        resolution: NeteaseClient.NeteaseStream,
    ): String? = when (resolution) {
        is NeteaseClient.NeteaseStream.Playable -> {
            streamFailures.remove(songId)
            streamUrls[songId] = StreamEntry(
                url = resolution.url,
                expiresAtMs = System.currentTimeMillis() + 10 * 60_000L,
                preview = false,
                previewDurationMs = 0L,
                durationMs = resolution.durationMs,
            )
            NeteaseStreamProxy.setUpstream(
                songId = songId,
                url = resolution.url,
                allowCache = true,
                expectedSizeBytes = resolution.sizeBytes,
                cacheVariant = "${resolution.type}-${resolution.sizeBytes}",
            )
            resolution.url
        }
        is NeteaseClient.NeteaseStream.Preview -> {
            streamFailures.remove(songId)
            streamUrls[songId] = StreamEntry(
                url = resolution.url,
                expiresAtMs = System.currentTimeMillis() + 10 * 60_000L,
                preview = true,
                previewDurationMs = resolution.durationMs,
                durationMs = resolution.durationMs,
            )
            // 试听只透传：清掉该曲目残留旧字节，避免试听与正片混在同一缓存 key。
            NeteaseStreamProxy.removeSong(songId)
            NeteaseStreamProxy.setUpstream(songId, resolution.url, allowCache = false)
            resolution.url
        }
        NeteaseClient.NeteaseStream.LoginRequired -> {
            _sessionExpired.value = true
            streamFailures[songId] = "登录已失效，请重新登录"
            null
        }
        is NeteaseClient.NeteaseStream.Unavailable -> {
            streamFailures[songId] = resolution.reason
            null
        }
    }

    /** 登录态变化后，旧直链/旧缓存都不再可信：丢弃全部取流缓存与上游绑定。 */
    private fun invalidateStreamState() {
        val songIds = streamUrls.keys.toList()
        streamUrls.clear()
        streamFailures.clear()
        songIds.forEach { NeteaseStreamProxy.removeSong(it) }
        NeteaseStreamProxy.invalidateUpstreams()
    }

    fun playlistIdForSong(songId: Long): String? =
        activePlaylists.value.firstOrNull { playlist -> playlist.entries.any { it.songId == songId } }?.id

    fun prefetchStreamUrl(trackId: String) {
        val songId = songIdForTrack(trackId) ?: return
        val ctx = appContext ?: return
        ioScope.launch {
            runCatching {
                val url = streamUrl(ctx, songId)
                if (!url.isNullOrBlank() && !isPreviewStream(songId)) {
                    NeteaseStreamProxy.warmHeadBytes(songId)
                }
            }
        }
    }

    /** 锁外确保直链有效并预热头部字节，成功后才能把在线下一首装入解码器预加载槽。 */
    suspend fun warmStreamForPreload(songId: Long): Boolean {
        val ctx = appContext ?: return false
        if (NeteaseStreamProxy.hasWarmedPrefix(songId)) return true
        val url = cachedStreamUrl(songId) ?: streamUrl(ctx, songId) ?: return false
        if (url.isBlank() || isPreviewStream(songId)) return false
        return NeteaseStreamProxy.warmHeadBytes(songId)
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
    internal fun materializeStreamingTrack(entry: NeteaseEntryRecord, scheduleCover: Boolean = true): LibraryTrack {
        val cover = appContext?.let { NeteaseCoverStore.cached(it, entry.songId) }
            ?: entry.coverPath?.let(::File)?.takeIf { it.isFile }
            ?: entry.downloadedFile?.let { File(it).parentFile }
                ?.let { File(it, "${entry.songId}.cover.jpg") }
                ?.takeIf { it.isFile }
        val track = NeteaseFeedLoader.buildStreamingTrack(entry, cover)
        AndroidSyncRuntime.syncStore.putPlaybackOnlyTrack(track)
        if (scheduleCover) scheduleCover(entry)
        return track
    }

    /** 后台补封面：完成后批量回写仅播放曲目，列表/播放器自动刷新。 */
    internal fun scheduleCover(entry: NeteaseEntryRecord) {
        if (entry.coverUrl.isBlank()) {
            Log.d("NeteaseRepo", "cover missing url song=${entry.songId}")
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

    /** 封面回写攒 150ms 合并成一批：整份歌单补图时不再逐首触发 UI 重组。 */
    internal fun queueCoverUpdate(track: LibraryTrack) {
        pendingCoverUpdates[track.id] = track
        synchronized(coverFlushLock) {
            if (coverFlushJob?.isActive == true) return
            coverFlushJob = ioScope.launch {
                delay(150)
                val keys = pendingCoverUpdates.keys.toList()
                val batch = keys.mapNotNull { pendingCoverUpdates.remove(it) }
                if (batch.isEmpty()) return@launch
                AndroidSyncRuntime.syncStore.putPlaybackOnlyTracks(batch)
                Log.d("NeteaseRepo", "cover batch=${batch.size}")
            }
        }
    }

    // ---- 委托给 Feed / Mode / Media 辅助模块 ----

    suspend fun searchOnline(query: String): Result<OnlineSearchResult> = NeteaseFeedLoader.searchOnline(query)

    fun ensureOnlineArtwork(url: String, onReady: (String) -> Unit) =
        NeteaseFeedLoader.ensureOnlineArtwork(url, onReady)

    fun loadDiscover(force: Boolean = false) = NeteaseFeedLoader.loadDiscover(force)

    fun loadHomeFeed(force: Boolean = false) = NeteaseFeedLoader.loadHomeFeed(force)

    fun loadMine(force: Boolean = false) = NeteaseFeedLoader.loadMine(force)

    fun loadMoreSavedAlbums() = NeteaseFeedLoader.loadMoreSavedAlbums()

    fun loadArtistDetail(
        artistId: String,
        initialName: String = "",
        initialCoverUrl: String = "",
        force: Boolean = false,
    ) = NeteaseFeedLoader.loadArtistDetail(artistId, initialName, initialCoverUrl, force)

    fun switchArtistSongsOrder(order: String) = NeteaseFeedLoader.switchArtistSongsOrder(order)

    fun loadMoreArtistSongs() = NeteaseFeedLoader.loadMoreArtistSongs()

    fun loadMoreArtistAlbums() = NeteaseFeedLoader.loadMoreArtistAlbums()

    fun loadPlayRecords(force: Boolean = false) = NeteaseFeedLoader.loadPlayRecords(force)

    fun loadRecommendedPlaylistsPage(force: Boolean = false) = NeteaseFeedLoader.loadRecommendedPlaylistsPage(force)

    fun reportListeningWrite(write: ListeningWrite) {
        when (write) {
            is ListeningWrite.AttemptStarted -> {
                val songId = songIdForTrack(write.value.trackId) ?: return
                val (source, sourceId) = resolveWeblogSource(
                    playlistId = playlistIdForSong(songId),
                    likedRemoteId = likedPlaylistRemoteId(),
                )
                reportPlaybackStart(
                    songId = songId,
                    startLogTimeMs = write.value.startedAt,
                    sourceId = sourceId,
                    source = source,
                )
            }
            is ListeningWrite.AttemptFinished -> {
                val songId = songIdForTrack(write.value.trackId) ?: return
                val playedSeconds = write.value.listenedSeconds.toLong()
                if (playedSeconds <= 0L) return
                val (source, sourceId) = resolveWeblogSource(
                    playlistId = playlistIdForSong(songId),
                    likedRemoteId = likedPlaylistRemoteId(),
                )
                reportPlaybackEnd(
                    songId = songId,
                    startLogTimeMs = write.value.startedAt,
                    playedSeconds = playedSeconds,
                    endReason = mapPlaybackEndReasonToWeblog(write.value.endReason),
                    sourceId = sourceId,
                    source = source,
                )
            }
            else -> Unit
        }
    }

    fun reportPlaybackStart(
        songId: Long,
        startLogTimeMs: Long = System.currentTimeMillis(),
        sourceId: String = "",
        source: String = "list",
    ) {
        val ctx = appContext ?: return
        if (!isHistoryReportEnabled(ctx)) return
        val session = cookie(ctx) ?: return
        ioScope.launch {
            try {
                NeteaseWriteApi.reportPlaybackWeblog(
                    songId = songId,
                    start = true,
                    startLogTimeMs = startLogTimeMs,
                    logTimeMs = startLogTimeMs,
                    cookie = session,
                    sourceId = sourceId,
                    source = source,
                )
            } catch (err: CancellationException) {
                throw err
            } catch (_: Throwable) {
            }
        }
    }

    fun reportPlaybackEnd(
        songId: Long,
        startLogTimeMs: Long,
        playedSeconds: Long,
        endReason: String = "ui",
        sourceId: String = "",
        source: String = "list",
    ) {
        val ctx = appContext ?: return
        if (!isHistoryReportEnabled(ctx)) return
        val session = cookie(ctx) ?: return
        val now = System.currentTimeMillis()
        ioScope.launch {
            try {
                NeteaseWriteApi.reportPlaybackWeblog(
                    songId = songId,
                    start = false,
                    startLogTimeMs = startLogTimeMs,
                    logTimeMs = now,
                    cookie = session,
                    playedSeconds = playedSeconds,
                    endReason = endReason,
                    sourceId = sourceId,
                    source = source,
                )
            } catch (err: CancellationException) {
                throw err
            } catch (_: Throwable) {
            }
        }
    }

    /** 每日推荐（按天缓存，支持 afresh=true 强制换一批）。 */
    suspend fun loadDaily(context: Context, force: Boolean = false, afresh: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = NeteaseClient.fetchDailyRecommend(cookie(context), afresh = afresh).getOrThrow()
                applyPayload(context, DAILY_ID, payload, dynamicKind = "daily", addedToPlaylists = false)
                if (afresh) {
                    playlistById(DAILY_ID)?.entries?.let { entries ->
                        NeteaseFeedLoader.updateFavoriteRecFromDaily(entries)
                    }
                }
                DAILY_ID
            }
        }

    /** 强制换一批每日推荐并同步刷新首页推荐区块。 */
    suspend fun refreshDailyRecommend(context: Context): Result<Unit> =
        loadDaily(context, force = true, afresh = true).map { }

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
                val expectedRevision = NeteaseWriteCoordinator.currentRevision()
                val token = cookie(context) ?: error("需要登录网易云")
                val knownId = playlistById(LIKED_ID)?.sourceUrl?.let { NeteaseClient.playlistIdFrom(it) }
                val likedPlaylistId = knownId
                    ?: NeteaseClient.fetchLikedPlaylistId(token).getOrThrow()
                    ?: error("找不到“我喜欢的音乐”")
                NeteaseWriteCoordinator.updateLikedPlaylistRemoteId(likedPlaylistId)
                val payload = NeteaseClient.fetchPlaylist(likedPlaylistId, token).getOrThrow()
                applyPayload(
                    context,
                    LIKED_ID,
                    payload.copy(name = payload.name.ifBlank { "我喜欢的音乐" }),
                    dynamicKind = "liked",
                    addedToPlaylists = false,
                    awaitCovers = false,
                )
                val loadedEntries = playlistById(LIKED_ID)?.entries.orEmpty()
                if (!NeteaseWriteCoordinator.isLikeListLoaded() ||
                    loadedEntries.size >= NeteaseWriteCoordinator.likedSongIds.value.size
                ) {
                    NeteaseWriteCoordinator.syncFromLikedPlaylist(
                        loadedEntries,
                        expectedRevision = expectedRevision,
                    )
                }
                val resolvedCount = playlistById(LIKED_ID)?.let { maxOf(it.entries.size, it.remoteTrackCount) }
                    ?: payload.entries.size
                NeteaseFeedLoader.onPlaylistTrackCountChanged(context, LIKED_ID, likedPlaylistId, resolvedCount)
                // 先把列表落盘；封面在后台补齐后再落一次，避免上千首阻塞进入详情页。
                persistLiked(context)
                ioScope.launch {
                    NeteaseMediaHelper.ensureEntryCovers(context, LIKED_ID)
                    persistLiked(context)
                }
                LIKED_ID
            }
        }

    /** “我的”页同步到远端我喜欢的音乐元数据：修正旧缓存的曲目数量并标记需补全。 */
    internal fun updateLikedSummaryFromMine(context: Context, summary: NeteaseClient.UserPlaylistInfo) {
        val existing = playlistById(LIKED_ID) ?: return
        val remoteCountChanged = summary.trackCount > 0 && summary.trackCount != existing.remoteTrackCount
        val coverChanged = summary.coverUrl.isNotBlank() && summary.coverUrl != existing.coverUrl
        val nameChanged = summary.name.isNotBlank() && summary.name != existing.name
        if (!remoteCountChanged && !coverChanged && !nameChanged) return
        val updated = existing.copy(
            name = summary.name.ifBlank { existing.name },
            coverUrl = summary.coverUrl.ifBlank { existing.coverUrl },
            remoteTrackCount = summary.trackCount.takeIf { it > 0 } ?: existing.remoteTrackCount,
            fullyHydrated = if (remoteCountChanged) false else existing.fullyHydrated,
        )
        updateRecord(updated)
        persistLiked(context)
    }

    /**
     * 首页探针：只取红心歌单的名称/封面/数量，绝不拉歌曲列表。
     * 红心歌单可能上千首，整份拉取只在点开卡片时发生；有本地缓存时启动完全跳过。
     */
    suspend fun probeLiked(context: Context) = withContext(Dispatchers.IO) {
        val token = cookie(context) ?: return@withContext
        val summary = NeteaseClient.fetchLikedPlaylistSummary(token).getOrNull() ?: return@withContext
        NeteaseWriteCoordinator.updateLikedPlaylistRemoteId(summary.id)
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
            fullyHydrated = false,
        )
        updatePlaylists { list -> list.filterNot { it.id == LIKED_ID } + record }
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

    internal fun applyLikedPlaylistDelta(
        songId: Long,
        liked: Boolean,
        track: LibraryTrack?,
        persist: Boolean,
    ) {
        var updatedRecord: NeteasePlaylistRecord? = null
        updatePlaylists { list ->
            list.map { record ->
                if (record.id != LIKED_ID) return@map record
                val nextEntries = if (liked) {
                    if (record.entries.isEmpty()) {
                        record.entries
                    } else if (record.entries.any { it.songId == songId }) {
                        record.entries
                    } else {
                        val entry = track?.let { buildEntryRecord(songId, it) }
                        if (entry != null) listOf(entry) + record.entries else record.entries
                    }
                } else {
                    record.entries.filterNot { it.songId == songId }
                }
                val baseCount = record.remoteTrackCount.takeIf { it > 0 } ?: record.entries.size
                val addedMissingTrack = liked && record.entries.isNotEmpty() &&
                    record.entries.none { it.songId == songId } && track == null
                val nextCount = if (record.fullyHydrated && !addedMissingTrack && nextEntries.isNotEmpty()) {
                    nextEntries.size
                } else if (liked) {
                    baseCount + 1
                } else {
                    (baseCount - 1).coerceAtLeast(0)
                }
                val updated = record.copy(
                    entries = nextEntries,
                    remoteTrackCount = nextCount,
                    fullyHydrated = record.fullyHydrated && !addedMissingTrack,
                )
                updatedRecord = updated
                updated
            }
        }
        if (persist && updatedRecord != null) {
            appContext?.let { persistLiked(it) }
        }
    }

    internal fun applyLikedPlaylistBatchDelta(
        songIds: List<Long>,
        liked: Boolean,
        tracksById: Map<Long, LibraryTrack?>,
        persist: Boolean,
    ) {
        if (songIds.isEmpty()) return
        val idSet = songIds.toSet()
        var updatedRecord: NeteasePlaylistRecord? = null
        updatePlaylists { list ->
            list.map { record ->
                if (record.id != LIKED_ID) return@map record
                val nextEntries = if (liked) {
                    if (record.entries.isEmpty()) {
                        record.entries
                    } else {
                        val existingIds = record.entries.mapTo(HashSet()) { it.songId }
                        val added = songIds.filterNot { it in existingIds }
                            .mapNotNull { id -> tracksById[id]?.let { buildEntryRecord(id, it) } }
                        added + record.entries
                    }
                } else {
                    record.entries.filterNot { it.songId in idSet }
                }
                val baseCount = record.remoteTrackCount.takeIf { it > 0 } ?: record.entries.size
                val nextCount = if (record.fullyHydrated && nextEntries.isNotEmpty()) {
                    nextEntries.size
                } else if (liked) {
                    baseCount + songIds.size
                } else {
                    (baseCount - songIds.size).coerceAtLeast(0)
                }
                record.copy(entries = nextEntries, remoteTrackCount = nextCount).also { updatedRecord = it }
            }
        }
        if (persist && updatedRecord != null) {
            appContext?.let { persistLiked(it) }
        }
    }

    internal fun applyPlaylistTracksDelta(
        playlistId: String,
        songIds: List<Long>,
        add: Boolean,
        tracksById: Map<Long, LibraryTrack?>,
        persist: Boolean,
    ): Int? {
        if (songIds.isEmpty()) return null
        val idSet = songIds.toSet()
        var updatedRecord: NeteasePlaylistRecord? = null
        var nextCountResult: Int? = null
        updatePlaylists { list ->
            list.map { record ->
                if (record.id != playlistId) return@map record
                val nextEntries = if (add) {
                    if (record.entries.isEmpty() && record.remoteTrackCount > 0) {
                        record.entries
                    } else {
                        val existingIds = record.entries.mapTo(HashSet()) { it.songId }
                        val added = songIds.filterNot { it in existingIds }
                            .mapNotNull { id -> tracksById[id]?.let { buildEntryRecord(id, it) } }
                        added + record.entries
                    }
                } else {
                    record.entries.filterNot { it.songId in idSet }
                }
                val baseCount = record.remoteTrackCount.takeIf { it > 0 } ?: record.entries.size
                val nextCount = if (record.fullyHydrated && nextEntries.isNotEmpty()) {
                    nextEntries.size
                } else if (add) {
                    baseCount + songIds.size
                } else {
                    (baseCount - songIds.size).coerceAtLeast(0)
                }
                nextCountResult = nextCount
                record.copy(entries = nextEntries, remoteTrackCount = nextCount).also { updatedRecord = it }
            }
        }
        val rec = updatedRecord
        if (persist && rec != null && (rec.addedToPlaylists || rec.persistCache)) {
            persistSoon()
        }
        return nextCountResult
    }

    internal fun applyPlaylistOrderDelta(
        playlistId: String,
        orderedSongIds: List<Long>,
        persist: Boolean,
    ) {
        if (orderedSongIds.isEmpty()) return
        var updatedRecord: NeteasePlaylistRecord? = null
        updatePlaylists { list ->
            list.map { record ->
                if (record.id != playlistId) return@map record
                val byId = record.entries.associateBy { it.songId }
                val reordered = orderedSongIds.mapNotNull { byId[it] }
                val remainder = record.entries.filterNot { it.songId in orderedSongIds }
                record.copy(entries = reordered + remainder).also { updatedRecord = it }
            }
        }
        val rec = updatedRecord
        if (persist && rec != null && (rec.addedToPlaylists || rec.persistCache)) {
            persistSoon()
        }
    }

    internal fun applyPlaylistPrivacyDelta(
        playlistId: String,
        privacyPrivate: Boolean,
        persist: Boolean,
    ) {
        val nextPrivacy = if (privacyPrivate) 10 else 0
        var updatedRecord: NeteasePlaylistRecord? = null
        updatePlaylists { list ->
            list.map { record ->
                if (record.id != playlistId) record
                else record.copy(privacy = nextPrivacy).also { updatedRecord = it }
            }
        }
        val rec = updatedRecord
        if (persist && rec != null && (rec.addedToPlaylists || rec.persistCache)) {
            persistSoon()
        }
    }

    private fun buildEntryRecord(songId: Long, track: LibraryTrack): NeteaseEntryRecord =
        NeteaseEntryRecord(
            songId = songId,
            title = track.title,
            artists = track.artists,
            album = track.album,
            durationMs = 0L,
            coverUrl = coverUrlForTrack(track).orEmpty(),
            coverPath = track.artworkPath,
            albumId = neteaseAlbumIdForTrack(track) ?: 0L,
            artistIds = neteaseArtistIdForTrack(track)?.let(::listOf).orEmpty(),
        )

    /** 在线歌手详情页：先占位并并行拉取歌手详情、歌曲与专辑分页。 */
    fun stageArtistPage(artistId: Long, name: String, coverUrl: String = "") {
        if (artistId <= 0) return
        Log.d("NeteaseRepo", "entity artist=$artistId name=$name")
        val idStr = artistId.toString()
        _selectedArtistId.value = idStr
        NeteaseFeedLoader.loadArtistDetail(
            artistId = idStr,
            initialName = name.ifBlank { "歌手" },
            initialCoverUrl = coverUrl,
        )
    }

    /** 在线专辑页：同样复用播放列表界面。 */
    fun stageAlbumPage(albumId: Long, name: String, coverUrl: String = "") {
        if (albumId <= 0) return
        Log.d("NeteaseRepo", "entity album=$albumId name=$name")
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
        Log.d("NeteaseRepo", "radars updated=${fetched.size}")
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
            updatePlaylists { list -> list.filterNot { it.dynamicKind == "rec" } }
        }
        val errors = mutableListOf<String>()
        runCatching { loadDaily(context) }.onFailure { errors += "每日推荐: ${it.message}" }
        runCatching { loadRadar(context) }.onFailure { errors += "雷达: ${it.message}" }
        // 红心歌单有本地缓存就直接用，启动不联网；没有缓存才取一次摘要。
        if (playlistById(LIKED_ID) == null) {
            runCatching { probeLiked(context) }.onFailure { errors += "我喜欢的音乐: ${it.message}" }
        }
        _homeStatus.value = errors.joinToString("\n")
        // 心动/漫游/相似歌曲卡片：启动先各取一首/一批做封面预览，不播放。
        ioScope.launch { NeteaseModeEngine.prefetchHeartbeatPreview(context) }
        ioScope.launch { NeteaseModeEngine.prefetchRoamPreview(context) }
        ioScope.launch { NeteaseModeEngine.prefetchSimilarPreview(context) }
        // 红心歌曲 ID 列表：快速拉取用于首页/发现/播放器红心状态展示。
        NeteaseWriteCoordinator.refreshLikedIds(force = false)
        // 雷达歌单：启动更新一次元数据。
        ioScope.launch { refreshRadarSummaries(context) }
        // 首页动态分区（曲风精选/歌曲推荐/艺人新动向/推荐歌单）：启动后台加载。
        ioScope.launch { loadHomeFeed() }
        Log.d(
            "NeteaseRepo",
            "home loaded daily=${playlistById(DAILY_ID)?.entries?.size} " +
                "radar=${playlistById(RADAR_ID)?.entries?.size} liked=${playlistById(LIKED_ID)?.entries?.size} errors=$errors",
        )
    }

    /** 判断歌单是否需要联网拉取或修复旧版截断缓存。 */
    internal fun needsPlaylistHydration(record: NeteasePlaylistRecord): Boolean {
        if (record.entries.isEmpty()) return true
        val isHydratablePlaylist = record.id == LIKED_ID || record.id.all(Char::isDigit)
        if (!isHydratablePlaylist) return false
        return !record.fullyHydrated
    }

    /** 打开推荐歌单卡片时才拉详情 + 匹配 + 封面（与导入流程同一套）。 */
    suspend fun ensureDynamicLoaded(context: Context, playlistId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val existing = playlistById(playlistId)
                ?: return@withContext Result.failure(IllegalStateException("歌单不存在"))
            if (!needsPlaylistHydration(existing)) {
                return@withContext Result.success(Unit)
            }
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
                    ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, playlistId) }
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
                    ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, playlistId) }
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
                    Log.d(
                        "NeteaseRepo",
                        "load id=$playlistId entries=${payload.entries.size} " +
                            "fetch=${fetched - started}ms build=${System.currentTimeMillis() - fetched}ms",
                    )
                    playlistById(playlistId)?.let { updateRadarSummary(context, it) }
                    ioScope.launch { NeteaseMediaHelper.ensureEntryCovers(context, playlistId) }
                    Unit
                }
                else -> Result.success(Unit)
            }
        }

    fun setHeartbeatEnabled(enabled: Boolean) = NeteaseModeEngine.setHeartbeatEnabled(enabled)

    fun setRoamEnabled(enabled: Boolean) = NeteaseModeEngine.setRoamEnabled(enabled)

    fun setSimilarEnabled(enabled: Boolean) = NeteaseModeEngine.setSimilarEnabled(enabled)

    suspend fun startRoam(context: Context): Result<List<String>> = NeteaseModeEngine.startRoam(context)

    suspend fun appendRoamNext(context: Context): List<String>? = NeteaseModeEngine.appendRoamNext(context)

    suspend fun prefetchSimilarPreview(context: Context, seedSongId: Long? = null) =
        NeteaseModeEngine.prefetchSimilarPreview(context, seedSongId)

    suspend fun startSimilar(context: Context, seedSongId: Long? = null): Result<List<String>> =
        NeteaseModeEngine.startSimilar(context, seedSongId)

    suspend fun appendSimilarNext(context: Context, currentTrackId: String): List<String>? =
        NeteaseModeEngine.appendSimilarNext(context, currentTrackId)

    suspend fun similarSongs(context: Context, trackId: String): Result<List<LibraryTrack>> =
        NeteaseModeEngine.similarSongs(context, trackId)

    suspend fun prefetchHeartbeatPreview(context: Context, seedSongId: Long? = null) =
        NeteaseModeEngine.prefetchHeartbeatPreview(context, seedSongId)

    suspend fun startHeartbeat(context: Context, seedSongId: Long? = null): Result<String> =
        NeteaseModeEngine.startHeartbeat(context, seedSongId)

    suspend fun appendHeartbeatNext(
        context: Context,
        currentTrackId: String,
        queueHeadTrackId: String?,
    ): String? = NeteaseModeEngine.appendHeartbeatNext(context, currentTrackId, queueHeadTrackId)

    /** 播放曲目对应的网易云歌曲 ID（用于歌词等按 ID 查询）；仅网易云自己的曲目有映射。 */
    fun songIdForTrack(trackId: String): Long? =
        if (trackId.startsWith("netease:")) trackId.removePrefix("netease:").toLongOrNull() else null

    data class TrackCommentsResult(
        val songId: Long,
        val matchedTitle: String,
        val matchedBySearch: Boolean,
        val page: NeteaseClient.NeteaseSongCommentsPage,
    )

    suspend fun fetchTrackComments(
        context: Context,
        track: LibraryTrack,
        limit: Int = 20,
        offset: Int = 0,
        beforeTime: Long = 0L,
        knownSongId: Long? = null,
        knownMatchedTitle: String? = null,
    ): Result<TrackCommentsResult> = withContext(Dispatchers.IO) {
        val cookie = cookie(context)
        val directId = knownSongId ?: songIdForTrack(track.id)
        var resolvedTitle = knownMatchedTitle ?: track.title
        var matchedBySearch = knownSongId != null && songIdForTrack(track.id) == null
        val songId = if (directId != null) {
            directId
        } else {
            val candidates = NeteaseClient.searchSong(track.title, track.artists, cookie)
                .getOrElse { return@withContext Result.failure(it) }
            val best = candidates.firstOrNull {
                it.title.equals(track.title, ignoreCase = true)
            } ?: candidates.firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("未在网易云找到匹配曲目"))
            resolvedTitle = if (best.artists.isNotBlank()) "${best.title} · ${best.artists}" else best.title
            matchedBySearch = true
            best.songId
        }
        NeteaseClient.fetchSongComments(
            songId = songId,
            limit = limit,
            offset = offset,
            beforeTime = beforeTime,
            cookie = cookie,
        ).map { page ->
            TrackCommentsResult(
                songId = songId,
                matchedTitle = resolvedTitle,
                matchedBySearch = matchedBySearch,
                page = page,
            )
        }
    }

    fun ensurePlaybackCover(trackId: String) = NeteaseMediaHelper.ensurePlaybackCover(trackId)

    suspend fun playbackCoverInfo(trackId: String): PlaybackCoverInfo =
        NeteaseMediaHelper.playbackCoverInfo(trackId)

    fun isExplicitlyDownloaded(track: LibraryTrack): Boolean = NeteaseMediaHelper.isExplicitlyDownloaded(track)

    fun downloadedTracks(): List<LibraryTrack> = NeteaseMediaHelper.downloadedTracks()

    fun stageDownloadsPage(name: String) = NeteaseMediaHelper.stageDownloadsPage(name)

    suspend fun deleteDownload(context: Context, trackId: String): Result<Unit> =
        NeteaseMediaHelper.deleteDownload(context, trackId)

    fun coverUrlForTrack(track: LibraryTrack): String? = NeteaseMediaHelper.coverUrlForTrack(track)

    fun cachedHiResCover(trackId: String): String? = NeteaseMediaHelper.cachedHiResCover(trackId)

    suspend fun ensureHiResCover(trackId: String): String? = NeteaseMediaHelper.ensureHiResCover(trackId)

    fun neteaseAlbumIdForTrack(track: LibraryTrack): Long? = NeteaseMediaHelper.neteaseAlbumIdForTrack(track)

    fun neteaseArtistIdForTrack(track: LibraryTrack): Long? = NeteaseMediaHelper.neteaseArtistIdForTrack(track)

    fun playableTrackIds(playlistId: String): List<String> {
        val playlist = playlistById(playlistId) ?: return emptyList()
        playableIdsCache[playlistId]?.takeIf { it.entries === playlist.entries }?.let { return it.ids }
        val ids = playlist.entries.map { entry -> entryTrack(entry, ensureCover = false).id }.distinct()
        playableIdsCache[playlistId] = PlayableIdsCache(playlist.entries, ids)
        return ids
    }

    suspend fun ensureDownloaded(
        context: Context,
        playlistId: String,
        songId: Long,
    ): Result<String> = NeteaseMediaHelper.ensureDownloaded(context, playlistId, songId)

    suspend fun downloadAll(context: Context, playlistId: String): DownloadAllResult =
        NeteaseMediaHelper.downloadAll(context, playlistId)

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
    internal fun persistSoon() {
        pendingPersist.set(snapshotForPersist())
        persistRequests.trySend(Unit)
    }

    /** 用户动作落盘：等到真正写完再返回，失败仍由内部 runCatching 兜住。 */
    internal suspend fun persistNow(context: Context) {
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
