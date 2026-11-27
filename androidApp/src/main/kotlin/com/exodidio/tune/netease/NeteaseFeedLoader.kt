package com.exodidio.tune.netease

import android.content.Context
import android.util.Log
import com.exodidio.tune.R
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.albumIdFor
import com.exodidio.tune.sync.artistIdFor
import com.exodidio.tune.sync.writeTextAtomically
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 首页动态分区、新发现页、“我的”页、在线搜索、歌手详情、云端听歌记录加载器。 */
internal object NeteaseFeedLoader {
    private val minePersistRequests = Channel<Unit>(Channel.CONFLATED)
    private val pendingMinePersist = AtomicReference<Pair<Context, MineCache>?>(null)
    private val minePersistStarted = AtomicBoolean(false)
    private val minePersistMutex = Mutex()
    private val _discover = MutableStateFlow(DiscoverData())
    val discover: StateFlow<DiscoverData> = _discover.asStateFlow()
    private var discoverJob: Job? = null
    @Volatile private var discoverLoadedAt = 0L

    private val _homeFeed = MutableStateFlow(HomeFeedData())
    val homeFeed: StateFlow<HomeFeedData> = _homeFeed.asStateFlow()
    private var homeFeedJob: Job? = null
    @Volatile private var homeFeedLoadedAt = 0L

    private val _mine = MutableStateFlow(MineData())
    val mine: StateFlow<MineData> = _mine.asStateFlow()
    private var mineJob: Job? = null
    private var loadMoreAlbumsJob: Job? = null
    @Volatile private var mineLoadedAt = 0L

    private val _artistDetail = MutableStateFlow(NeteaseArtistDetailState())
    val artistDetail: StateFlow<NeteaseArtistDetailState> = _artistDetail.asStateFlow()
    private var artistDetailJob: Job? = null
    private var artistMoreSongsJob: Job? = null
    private var artistMoreAlbumsJob: Job? = null

    private val _albumSubscribedMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val albumSubscribedMap: StateFlow<Map<String, Boolean>> = _albumSubscribedMap.asStateFlow()

    private val _playRecords = MutableStateFlow(NeteasePlayRecordsState())
    val playRecords: StateFlow<NeteasePlayRecordsState> = _playRecords.asStateFlow()
    private var playRecordsJob: Job? = null
    @Volatile private var playRecordsLoadedAt = 0L

    private val _recommendedPlaylists = MutableStateFlow(RecommendedPlaylistsState())
    val recommendedPlaylists: StateFlow<RecommendedPlaylistsState> = _recommendedPlaylists.asStateFlow()
    private var recommendedPlaylistsJob: Job? = null
    @Volatile private var recommendedPlaylistsLoadedAt = 0L

    private val _cloudDisk = MutableStateFlow(NeteaseCloudDiskState())
    val cloudDisk: StateFlow<NeteaseCloudDiskState> = _cloudDisk.asStateFlow()
    private var cloudDiskJob: Job? = null
    private var cloudDiskMoreJob: Job? = null

    private val _discoverSquare = MutableStateFlow(DiscoverSquareState())
    val discoverSquare: StateFlow<DiscoverSquareState> = _discoverSquare.asStateFlow()
    private var squarePlaylistsJob: Job? = null
    private var squareHqJob: Job? = null
    private var squareRankingsJob: Job? = null
    private var squareAlbumsJob: Job? = null
    private var squareArtistsJob: Job? = null
    private var squareCatsLoaded = false

    private val _searchHot = MutableStateFlow<List<NeteaseSearchHotItem>>(emptyList())
    val searchHot: StateFlow<List<NeteaseSearchHotItem>> = _searchHot.asStateFlow()
    private val _searchHotLoading = MutableStateFlow(false)
    val searchHotLoading: StateFlow<Boolean> = _searchHotLoading.asStateFlow()
    private var searchHotJob: Job? = null
    @Volatile private var searchHotLoadedAt = 0L

    private val _searchGuess = MutableStateFlow<List<String>>(emptyList())
    val searchGuess: StateFlow<List<String>> = _searchGuess.asStateFlow()
    private val _searchGuessLoading = MutableStateFlow(false)
    val searchGuessLoading: StateFlow<Boolean> = _searchGuessLoading.asStateFlow()
    private var searchGuessJob: Job? = null
    @Volatile private var searchGuessLoadedAt = 0L

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private const val SEARCH_HISTORY_PREFS = "netease_search_prefs"
    private const val KEY_SEARCH_HISTORY = "search_history"
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _recentTracks = MutableStateFlow(emptyList<LibraryTrack>())
    /** “最近播放”：本地历史，可为本地曲目或在线曲目；封面晚到会自动回填。 */
    val recentTracks: StateFlow<List<LibraryTrack>> = _recentTracks.asStateFlow()
    @Volatile private var recentSubscribed = false

    fun applyCachedMine(cache: MineCache) {
        val hasData = cache.created.isNotEmpty() ||
            cache.saved.isNotEmpty() ||
            cache.albums.isNotEmpty() ||
            cache.artists.isNotEmpty()
        val current = _mine.value
        val untouched = !current.loaded &&
            current.created.isEmpty() &&
            current.saved.isEmpty() &&
            current.albums.isEmpty() &&
            current.artists.isEmpty()
        if (hasData && untouched) {
            _mine.value = MineData(
                loaded = true,
                created = cache.created,
                saved = cache.saved,
                albums = cache.albums,
                artists = cache.artists,
                albumsTotal = cache.albums.size,
            )
            _albumSubscribedMap.update { map ->
                map + cache.albums.associate { it.id to true }
            }
        }
    }

    fun resetOnLogout() {
        discoverJob?.cancel()
        homeFeedJob?.cancel()
        mineJob?.cancel()
        loadMoreAlbumsJob?.cancel()
        artistDetailJob?.cancel()
        artistMoreSongsJob?.cancel()
        artistMoreAlbumsJob?.cancel()
        playRecordsJob?.cancel()
        recommendedPlaylistsJob?.cancel()
        cloudDiskJob?.cancel()
        cloudDiskMoreJob?.cancel()
        squarePlaylistsJob?.cancel()
        squareHqJob?.cancel()
        squareRankingsJob?.cancel()
        squareAlbumsJob?.cancel()
        squareArtistsJob?.cancel()
        searchHotJob?.cancel()
        searchGuessJob?.cancel()
        discoverLoadedAt = 0L
        homeFeedLoadedAt = 0L
        mineLoadedAt = 0L
        playRecordsLoadedAt = 0L
        recommendedPlaylistsLoadedAt = 0L
        searchHotLoadedAt = 0L
        searchGuessLoadedAt = 0L
        squareCatsLoaded = false
        _homeFeed.value = HomeFeedData()
        _discover.value = DiscoverData()
        _mine.value = MineData()
        _artistDetail.value = NeteaseArtistDetailState()
        _albumSubscribedMap.value = emptyMap()
        _playRecords.value = NeteasePlayRecordsState()
        _recommendedPlaylists.value = RecommendedPlaylistsState()
        _cloudDisk.value = NeteaseCloudDiskState()
        _discoverSquare.value = DiscoverSquareState()
        _searchGuess.value = emptyList()
        _searchSuggestions.value = emptyList()
    }

    /**
     * 在线搜索：歌曲只登记“仅播放”的流式曲目（不入曲库、不下载），
     * 歌单/歌手/专辑返回元数据，封面按 URL 走统一的图片缓存。
     */
    suspend fun searchOnline(query: String): Result<OnlineSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val app = NeteaseRepository.appContext ?: error("网易云未初始化")
            val cookie = NeteaseRepository.cookie(app)
            coroutineScope {
                val songsJob = async { NeteaseClient.searchSongs(query, limit = 30, cookie = cookie) }
                val playlistsJob = async { NeteaseClient.searchPlaylists(query, limit = 24, cookie = cookie) }
                val artistsJob = async { NeteaseClient.searchArtists(query, limit = 24, cookie = cookie) }
                val albumsJob = async { NeteaseClient.searchAlbums(query, limit = 24, cookie = cookie) }
                val songs = songsJob.await().getOrThrow().map { song ->
                    NeteaseRepository.materializeStreamingTrack(
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
                Log.d(
                    "NeteaseRepo",
                    "search q=$query songs=${songs.size} playlists=${playlists.size} artists=${artists.size} albums=${albums.size} " +
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
        }.onFailure { Log.w("NeteaseRepo", "search failed q=$query err=${it.message}") }
    }

    /** 后台补齐歌单/歌手/专辑封面，完成后回调本地路径。 */
    fun ensureOnlineArtwork(url: String, onReady: (String) -> Unit) {
        if (url.isBlank()) return
        val app = NeteaseRepository.appContext ?: return
        NeteaseRepository.ioScope.launch {
            val file = NeteaseImageStore.ensure(app, url, NeteaseRepository.cookie(app)).await() ?: return@launch
            onReady(file.absolutePath)
        }
    }

    /**
     * 新发现页：排行榜 + 甄选歌单（首页整页块）+ 新歌一次并行拉取。
     * 歌单封面与歌曲封面都走已有缓存管线；同一批数据 10 分钟内不重复请求。
     */
    fun loadDiscover(force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        if (discoverJob?.isActive == true) return
        if (!force && _discover.value.loaded &&
            System.currentTimeMillis() - discoverLoadedAt < NeteaseRepository.DISCOVER_TTL_MS
        ) {
            return
        }
        discoverJob = NeteaseRepository.ioScope.launch {
            _discover.update { it.copy(loading = true) }
            val cookie = NeteaseRepository.cookie(app)
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
            Log.d(
                "NeteaseRepo",
                "discover rankings=${rankings.size} curated=${curated.size} songs=${songs.size} " +
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
        val app = NeteaseRepository.appContext ?: return
        if (homeFeedJob?.isActive == true) return
        if (!force && _homeFeed.value.loaded &&
            System.currentTimeMillis() - homeFeedLoadedAt < NeteaseRepository.DISCOVER_TTL_MS
        ) {
            return
        }
        homeFeedJob = NeteaseRepository.ioScope.launch {
            _homeFeed.update { it.copy(loading = true) }
            val cookie = NeteaseRepository.cookie(app)
            val blocksJob = async { NeteaseClient.fetchHomeBlocks(cookie) }
            val recommendedJob = async { NeteaseClient.fetchRecommendedResources(cookie) }
            val recommendSongsJob = async { NeteaseClient.fetchRecommendSongs(cookie, limit = 30) }
            val personalizedPlaylistsJob = async { NeteaseClient.fetchRecommendedPlaylists(cookie, limit = 12) }

            val blocksResult = blocksJob.await()
            val recommendedResult = recommendedJob.await()
            val recommendSongsResult = recommendSongsJob.await()
            val personalizedResult = personalizedPlaylistsJob.await()
            val blocks = blocksResult.getOrNull().orEmpty()

            val blockSections = blocks
                .filter { block ->
                    block.songs.isNotEmpty() &&
                        !block.code.contains("NEW_ALBUM", ignoreCase = true) &&
                        !block.code.contains("SUBSCRIBE", ignoreCase = true)
                }
                .map { block ->
                    val isTailoredBlock = block.code.equals("HOMEPAGE_BLOCK_STYLE_RCMD", ignoreCase = true) ||
                        block.title.contains("因为你听过") ||
                        block.title.contains("为你定制") ||
                        block.title.startsWith("根据")
                    HomeSongSection(
                        title = block.title,
                        titleRes = if (isTailoredBlock && block.title.isBlank()) R.string.home_section_tailored_fallback else null,
                        songs = materializeEntries(block.songs).take(18),
                    )
                }
                .filter { it.songs.isNotEmpty() }
            // “根据你喜爱的歌曲推荐”：整页块里没有对应分区，用登录态的每日推荐歌曲补上。
            val favoriteSongs = materializeEntries(recommendSongsResult.getOrNull().orEmpty()).take(30)
            val favoriteSection = favoriteSongs.takeIf { it.isNotEmpty() }?.let { songs ->
                HomeSongSection(
                    title = "",
                    titleRes = R.string.home_section_favorite_rec,
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
            val exclusiveBlock = blocks.firstOrNull { block ->
                block.playlists.isNotEmpty() && (
                    block.code.equals("HOMEPAGE_BLOCK_PLAYLIST_RCMD", ignoreCase = true) ||
                        block.title.contains("专属") ||
                        block.title.contains("推荐歌单")
                    )
            }
            val exclusivePayloads = exclusiveBlock?.playlists?.takeIf { it.isNotEmpty() }
                ?: personalizedResult.getOrNull().orEmpty()
            val recommendedIds = recommended.mapTo(HashSet()) { it.id }
            val exclusivePlaylists = exclusivePayloads
                .filterNot { it.id in recommendedIds }
                .ifEmpty { exclusivePayloads }
                .take(12)
                .map { payload ->
                    DiscoverPlaylist(
                        id = payload.id,
                        name = payload.name,
                        subtitle = payload.description,
                        coverUrl = payload.coverUrl,
                        coverPath = NeteaseImageStore.cached(app, payload.coverUrl)?.absolutePath,
                    )
                }
            val exclusiveTitle = exclusiveBlock?.title?.takeIf { it.isNotBlank() && it != "推荐歌单" }.orEmpty()

            _homeFeed.value = HomeFeedData(
                loading = false,
                loaded = true,
                sections = sections,
                artistNew = artistNew,
                recommended = recommended,
                exclusiveTitle = exclusiveTitle,
                exclusivePlaylists = exclusivePlaylists,
                error = blocksResult.exceptionOrNull()?.message
                    ?: recommendSongsResult.exceptionOrNull()?.message
                    ?: recommendedResult.exceptionOrNull()?.message,
            )
            homeFeedLoadedAt = System.currentTimeMillis()
            Log.d(
                "NeteaseRepo",
                "homeFeed sections=${sections.map { "${it.title.ifBlank { it.titleRes }}:${it.songs.size}" }} " +
                    "artist=${artistNew?.songs?.size} recommended=${recommended.size} exclusive=${exclusivePlaylists.size} " +
                    "err=${blocksResult.exceptionOrNull()?.message ?: recommendSongsResult.exceptionOrNull()?.message ?: recommendedResult.exceptionOrNull()?.message}",
            )
            fillCovers(app, cookie, recommended + exclusivePlaylists)
        }
    }

    fun subscribeRecentTracks() {
        if (recentSubscribed) return
        recentSubscribed = true
        val store = AndroidSyncRuntime.syncStore
        NeteaseRepository.ioScope.launch {
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
                NeteaseRepository.materializeStreamingTrack(
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
        val app = NeteaseRepository.appContext
        val store = AndroidSyncRuntime.syncStore
        _recentTracks.value = store.recentlyPlayed.value.mapNotNull { record ->
            val track = store.trackForPlayback(record.trackId) ?: return@mapNotNull null
            val cover = track.artworkPath ?: NeteaseRepository.songIdForTrack(track.id)?.let { songId ->
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
        val app = NeteaseRepository.appContext ?: return
        val session = NeteaseRepository.cookie(app)
        if (session.isNullOrBlank()) {
            _mine.value = MineData()
            return
        }
        if (mineJob?.isActive == true) return
        if (!force && _mine.value.loaded &&
            System.currentTimeMillis() - mineLoadedAt < NeteaseRepository.DISCOVER_TTL_MS
        ) {
            return
        }
        mineJob = NeteaseRepository.ioScope.launch {
            _mine.update { it.copy(loading = true) }
            NeteaseRepository.refreshLikedIds(force = force)
            val accountResult = NeteaseClient.fetchAccountInfo(session)
            accountResult.getOrNull()?.let { NeteaseRepository.setAccountInfo(it) }
            val (playlistsResult, artistsResult, albumsResult) = coroutineScope {
                val pJob = async {
                    accountResult.getOrNull()?.let { info ->
                        NeteaseClient.fetchUserPlaylists(info.uid, session)
                    }
                }
                val arJob = async { NeteaseClient.fetchSubscribedArtists(session) }
                val alJob = async { NeteaseClient.fetchSubscribedAlbums(session, offset = 0, limit = 50) }
                Triple(pJob.await(), arJob.await(), alJob.await())
            }
            val uid = accountResult.getOrNull()?.uid.orEmpty()
            val playlists = playlistsResult?.getOrNull().orEmpty()

            fun convert(payload: NeteaseClient.UserPlaylistInfo) = DiscoverPlaylist(
                id = payload.id,
                name = payload.name,
                coverUrl = payload.coverUrl,
                trackCount = payload.trackCount,
            )

            val likedPayload = playlists.firstOrNull { it.specialType == 5 }
            likedPayload?.id?.let { NeteaseWriteCoordinator.updateLikedPlaylistRemoteId(it) }
            likedPayload?.let { NeteaseRepository.updateLikedSummaryFromMine(app, it) }
            val likedCard = NeteaseRepository.playlistById(NeteaseRepository.LIKED_ID)?.let { record ->
                NeteaseClient.playlistIdFrom(record.sourceUrl)?.let { NeteaseWriteCoordinator.updateLikedPlaylistRemoteId(it) }
                val resolvedCount = likedPayload?.trackCount?.takeIf { it > 0 }
                    ?: record.remoteTrackCount.takeIf { it > 0 }
                    ?: record.entries.size
                DiscoverPlaylist(
                    id = record.id,
                    name = record.name.ifBlank { "我喜欢的音乐" },
                    coverUrl = record.coverUrl,
                    coverPath = record.coverPath,
                    trackCount = resolvedCount,
                )
            } ?: likedPayload?.let { payload ->
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
            val albumsPage = albumsResult.getOrNull()
            val albums = albumsPage?.albums?.map {
                MineAlbum(
                    id = it.id,
                    name = it.name,
                    artistName = it.artistName,
                    coverUrl = it.coverUrl,
                    trackCount = it.trackCount,
                    subTimeMs = it.subTimeMs,
                )
            } ?: _mine.value.albums

            _mine.value = MineData(
                loading = false,
                loaded = true,
                created = listOfNotNull(likedCard) + created,
                saved = saved,
                albums = albums,
                artists = artists,
                albumsHasMore = albumsPage?.hasMore ?: _mine.value.albumsHasMore,
                albumsTotal = albumsPage?.total ?: albums.size,
                loadingMoreAlbums = false,
                error = accountResult.exceptionOrNull()?.message
                    ?: playlistsResult?.exceptionOrNull()?.message
                    ?: artistsResult.exceptionOrNull()?.message
                    ?: albumsResult.exceptionOrNull()?.message,
            )
            if (albumsPage != null) {
                _albumSubscribedMap.update { map ->
                    map + albums.associate { it.id to true }
                }
            }
            mineLoadedAt = System.currentTimeMillis()
            persistMine(app, _mine.value)
            Log.d(
                "NeteaseRepo",
                "mine created=${_mine.value.created.size} saved=${saved.size} albums=${albums.size} artists=${artists.size} " +
                    "err=${_mine.value.error}",
            )
        }
    }

    fun loadMoreSavedAlbums() {
        val app = NeteaseRepository.appContext ?: return
        val session = NeteaseRepository.cookie(app) ?: return
        val snapshot = _mine.value
        if (snapshot.loadingMoreAlbums || !snapshot.albumsHasMore || loadMoreAlbumsJob?.isActive == true) return
        loadMoreAlbumsJob = NeteaseRepository.ioScope.launch {
            _mine.update { it.copy(loadingMoreAlbums = true) }
            val offset = _mine.value.albums.size
            val res = NeteaseClient.fetchSubscribedAlbums(session, offset = offset, limit = 50)
            res.fold(
                onSuccess = { page ->
                    val appended = page.albums.map {
                        MineAlbum(
                            id = it.id,
                            name = it.name,
                            artistName = it.artistName,
                            coverUrl = it.coverUrl,
                            trackCount = it.trackCount,
                            subTimeMs = it.subTimeMs,
                        )
                    }
                    _mine.update { cur ->
                        val existingIds = cur.albums.mapTo(HashSet()) { it.id }
                        val merged = cur.albums + appended.filterNot { it.id in existingIds }
                        cur.copy(
                            albums = merged,
                            albumsHasMore = page.hasMore && appended.isNotEmpty(),
                            albumsTotal = maxOf(page.total, merged.size),
                            loadingMoreAlbums = false,
                        )
                    }
                    _albumSubscribedMap.update { map -> map + appended.associate { it.id to true } }
                    persistMine(app, _mine.value)
                },
                onFailure = {
                    _mine.update { it.copy(loadingMoreAlbums = false) }
                },
            )
        }
    }

    fun isPlaylistSaved(playlistId: String): Boolean =
        _mine.value.saved.any { it.id == playlistId }

    fun isPlaylistCreated(playlistId: String): Boolean =
        _mine.value.created.any { it.id == playlistId }

    fun onPlaylistCreated(context: Context, playlist: DiscoverPlaylist, likedRemoteId: String?) {
        _mine.update { data ->
            val current = data.created.filterNot { it.id == playlist.id }
            val hasLikedFirst = current.firstOrNull()?.let {
                it.id == NeteaseRepository.LIKED_ID || (likedRemoteId != null && it.id == likedRemoteId)
            } == true
            val nextCreated = if (hasLikedFirst) {
                listOf(current.first(), playlist) + current.drop(1)
            } else {
                listOf(playlist) + current
            }
            data.copy(created = nextCreated)
        }
        if (_mine.value.loaded) persistMine(context, _mine.value)
    }

    fun onPlaylistUpdated(context: Context, playlistId: String, name: String, description: String) {
        _mine.update { data ->
            data.copy(
                created = data.created.map {
                    if (it.id == playlistId) it.copy(name = name, subtitle = description.ifBlank { it.subtitle }) else it
                },
                saved = data.saved.map {
                    if (it.id == playlistId) it.copy(name = name, subtitle = description.ifBlank { it.subtitle }) else it
                },
            )
        }
        if (_mine.value.loaded) persistMine(context, _mine.value)
    }

    fun onPlaylistDeleted(context: Context, playlistId: String) {
        _mine.update { data ->
            data.copy(
                created = data.created.filterNot { it.id == playlistId },
                saved = data.saved.filterNot { it.id == playlistId },
            )
        }
        if (_mine.value.loaded) persistMine(context, _mine.value)
    }

    fun onPlaylistTrackCountChanged(
        context: Context,
        playlistId: String,
        likedRemoteId: String?,
        nextCount: Int,
    ) {
        val isLikedTarget = playlistId == NeteaseRepository.LIKED_ID ||
            (likedRemoteId != null && playlistId == likedRemoteId)
        _mine.update { data ->
            data.copy(
                created = data.created.map { item ->
                    val matches = item.id == playlistId ||
                        (isLikedTarget && (item.id == NeteaseRepository.LIKED_ID || (likedRemoteId != null && item.id == likedRemoteId)))
                    if (matches) item.copy(trackCount = nextCount) else item
                },
                saved = data.saved.map { item ->
                    if (item.id == playlistId) item.copy(trackCount = nextCount) else item
                },
            )
        }
        if (_mine.value.loaded) persistMine(context, _mine.value)
    }

    fun onPlaylistSubscribed(context: Context, playlist: DiscoverPlaylist, subscribe: Boolean) {
        _mine.update { data ->
            val nextSaved = if (subscribe) {
                listOf(playlist) + data.saved.filterNot { it.id == playlist.id }
            } else {
                data.saved.filterNot { it.id == playlist.id }
            }
            data.copy(saved = nextSaved)
        }
        if (_mine.value.loaded) persistMine(context, _mine.value)
    }

    fun isArtistFollowed(artistId: String): Boolean =
        _mine.value.artists.any { it.id == artistId } ||
            (_artistDetail.value.artistId == artistId && _artistDetail.value.followed)

    fun artistCardFallback(artistId: String): MineArtist {
        val detail = _artistDetail.value.takeIf { it.artistId == artistId }
        val existing = _mine.value.artists.firstOrNull { it.id == artistId }
        return existing ?: MineArtist(
            id = artistId,
            name = detail?.name?.ifBlank { artistId } ?: artistId,
            coverUrl = detail?.coverUrl.orEmpty(),
        )
    }

    fun onArtistFollowed(context: Context, artist: MineArtist, follow: Boolean) {
        _mine.update { data ->
            val nextArtists = if (follow) {
                listOf(artist) + data.artists.filterNot { it.id == artist.id }
            } else {
                data.artists.filterNot { it.id == artist.id }
            }
            data.copy(artists = nextArtists)
        }
        _artistDetail.update { detail ->
            if (detail.artistId == artist.id) detail.copy(followed = follow) else detail
        }
        if (_mine.value.loaded) persistMine(context, _mine.value)
    }

    fun isAlbumSubscribed(albumId: String): Boolean =
        _albumSubscribedMap.value[albumId] ?: _mine.value.albums.any { it.id == albumId }

    fun albumCardFallback(albumId: String): MineAlbum {
        val existing = _mine.value.albums.firstOrNull { it.id == albumId }
        if (existing != null) return existing
        val record = NeteaseRepository.playlistById("album:$albumId")
        val firstTrack = record?.entries?.firstOrNull()
        return MineAlbum(
            id = albumId,
            name = record?.name?.ifBlank { albumId } ?: albumId,
            artistName = firstTrack?.artists.orEmpty(),
            coverUrl = record?.coverUrl.orEmpty(),
            trackCount = record?.entries?.size?.takeIf { it > 0 } ?: record?.remoteTrackCount ?: 0,
            subTimeMs = System.currentTimeMillis(),
        )
    }

    fun checkAlbumSubscribed(albumId: String, force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        val session = NeteaseRepository.cookie(app) ?: return
        if (!force && _albumSubscribedMap.value.containsKey(albumId)) return
        NeteaseRepository.ioScope.launch {
            NeteaseClient.fetchAlbumSubscribed(albumId, session).onSuccess { sub ->
                _albumSubscribedMap.update { it + (albumId to sub) }
            }
        }
    }

    fun onAlbumSubscribed(context: Context, album: MineAlbum, subscribe: Boolean) {
        _albumSubscribedMap.update { it + (album.id to subscribe) }
        _mine.update { data ->
            val hadBefore = data.albums.any { it.id == album.id }
            val nextAlbums = if (subscribe) {
                listOf(album.copy(subTimeMs = album.subTimeMs.takeIf { it > 0 } ?: System.currentTimeMillis())) +
                    data.albums.filterNot { it.id == album.id }
            } else {
                data.albums.filterNot { it.id == album.id }
            }
            val nextTotal = when {
                subscribe && !hadBefore -> (data.albumsTotal + 1).coerceAtLeast(nextAlbums.size)
                !subscribe && hadBefore -> (data.albumsTotal - 1).coerceAtLeast(nextAlbums.size)
                else -> maxOf(data.albumsTotal, nextAlbums.size)
            }
            data.copy(albums = nextAlbums, albumsTotal = nextTotal)
        }
        if (_mine.value.loaded) persistMine(context, _mine.value)
    }

    fun loadArtistDetail(
        artistId: String,
        initialName: String = "",
        initialCoverUrl: String = "",
        force: Boolean = false,
        order: String? = null,
    ) {
        val app = NeteaseRepository.appContext ?: return
        val cleanId = artistId.trim()
        if (cleanId.isEmpty()) return
        val current = _artistDetail.value
        val targetOrder = order ?: if (current.artistId == cleanId) current.order else "hot"
        if (!force &&
            current.artistId == cleanId &&
            current.order == targetOrder &&
            (current.songs.isNotEmpty() || current.albums.isNotEmpty()) &&
            current.error == null
        ) {
            return
        }
        artistDetailJob?.cancel()
        artistMoreSongsJob?.cancel()
        artistMoreAlbumsJob?.cancel()
        val cookie = NeteaseRepository.cookie(app)
        val cachedFollowed = _mine.value.artists.any { it.id == cleanId }
        _artistDetail.value = NeteaseArtistDetailState(
            artistId = cleanId,
            name = initialName.ifBlank { if (current.artistId == cleanId) current.name else "歌手" },
            coverUrl = initialCoverUrl.ifBlank { if (current.artistId == cleanId) current.coverUrl else "" },
            description = if (current.artistId == cleanId) current.description else "",
            alias = if (current.artistId == cleanId) current.alias else emptyList(),
            followed = if (current.artistId == cleanId) current.followed else cachedFollowed,
            order = targetOrder,
            loading = true,
        )
        artistDetailJob = NeteaseRepository.ioScope.launch {
            val (detailRes, songsRes, albumsRes) = coroutineScope {
                val dJob = async { NeteaseClient.fetchArtistDetail(cleanId, cookie) }
                val sJob = async { NeteaseClient.fetchArtistSongsPage(cleanId, offset = 0, limit = 50, order = targetOrder, cookie = cookie) }
                val aJob = async { NeteaseClient.fetchArtistAlbumsPage(cleanId, offset = 0, limit = 30, cookie = cookie) }
                Triple(dJob.await(), sJob.await(), aJob.await())
            }
            if (_artistDetail.value.artistId != cleanId) return@launch
            val detail = detailRes.getOrNull()
            val songsPage = songsRes.getOrNull()
            val albumsPage = albumsRes.getOrNull()
            val songs = materializeEntries(songsPage?.songs.orEmpty())
            val albums = albumsPage?.albums.orEmpty().map {
                NeteaseArtistAlbumItem(
                    id = it.id,
                    name = it.name,
                    artistName = it.artistName,
                    coverUrl = it.coverUrl,
                    trackCount = it.trackCount,
                    publishTimeMs = it.publishTimeMs,
                )
            }
            val firstError = songsRes.exceptionOrNull()?.message
                ?: detailRes.exceptionOrNull()?.message
                ?: albumsRes.exceptionOrNull()?.message
            _artistDetail.value = NeteaseArtistDetailState(
                artistId = cleanId,
                name = detail?.name?.ifBlank { initialName } ?: initialName.ifBlank { "歌手" },
                coverUrl = detail?.coverUrl?.ifBlank { initialCoverUrl } ?: initialCoverUrl,
                description = detail?.description.orEmpty(),
                alias = detail?.alias.orEmpty(),
                musicCount = detail?.musicCount ?: songsPage?.total ?: songs.size,
                albumCount = detail?.albumCount ?: albums.size,
                mvCount = detail?.mvCount ?: 0,
                followed = detail?.followed ?: _mine.value.artists.any { it.id == cleanId },
                order = targetOrder,
                loading = false,
                songs = songs,
                songsTotal = songsPage?.total ?: songs.size,
                songsHasMore = songsPage?.hasMore ?: false,
                albums = albums,
                albumsHasMore = albumsPage?.hasMore ?: false,
                error = if (songs.isEmpty() && albums.isEmpty()) firstError else null,
            )
        }
    }

    fun switchArtistSongsOrder(order: String) {
        val current = _artistDetail.value
        if (current.artistId.isBlank() || current.order == order) return
        loadArtistDetail(
            artistId = current.artistId,
            initialName = current.name,
            initialCoverUrl = current.coverUrl,
            force = true,
            order = order,
        )
    }

    fun loadMoreArtistSongs() {
        val app = NeteaseRepository.appContext ?: return
        val cur = _artistDetail.value
        if (cur.artistId.isBlank() || cur.loading || cur.loadingMoreSongs || !cur.songsHasMore || artistMoreSongsJob?.isActive == true) {
            return
        }
        val targetId = cur.artistId
        val targetOrder = cur.order
        val offset = cur.songs.size
        val cookie = NeteaseRepository.cookie(app)
        artistMoreSongsJob = NeteaseRepository.ioScope.launch {
            _artistDetail.update { if (it.artistId == targetId) it.copy(loadingMoreSongs = true) else it }
            val res = NeteaseClient.fetchArtistSongsPage(targetId, offset = offset, limit = 50, order = targetOrder, cookie = cookie)
            res.fold(
                onSuccess = { page ->
                    val appended = materializeEntries(page.songs)
                    _artistDetail.update { state ->
                        if (state.artistId != targetId || state.order != targetOrder) return@update state
                        val existingIds = state.songs.mapTo(HashSet()) { it.id }
                        val nextSongs = state.songs + appended.filterNot { it.id in existingIds }
                        state.copy(
                            loadingMoreSongs = false,
                            songs = nextSongs,
                            songsTotal = maxOf(page.total, nextSongs.size),
                            songsHasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                },
                onFailure = {
                    _artistDetail.update { state ->
                        if (state.artistId == targetId) state.copy(loadingMoreSongs = false) else state
                    }
                },
            )
        }
    }

    fun loadMoreArtistAlbums() {
        val app = NeteaseRepository.appContext ?: return
        val cur = _artistDetail.value
        if (cur.artistId.isBlank() || cur.loading || cur.loadingMoreAlbums || !cur.albumsHasMore || artistMoreAlbumsJob?.isActive == true) {
            return
        }
        val targetId = cur.artistId
        val offset = cur.albums.size
        val cookie = NeteaseRepository.cookie(app)
        artistMoreAlbumsJob = NeteaseRepository.ioScope.launch {
            _artistDetail.update { if (it.artistId == targetId) it.copy(loadingMoreAlbums = true) else it }
            val res = NeteaseClient.fetchArtistAlbumsPage(targetId, offset = offset, limit = 30, cookie = cookie)
            res.fold(
                onSuccess = { page ->
                    val appended = page.albums.map {
                        NeteaseArtistAlbumItem(
                            id = it.id,
                            name = it.name,
                            artistName = it.artistName,
                            coverUrl = it.coverUrl,
                            trackCount = it.trackCount,
                            publishTimeMs = it.publishTimeMs,
                        )
                    }
                    _artistDetail.update { state ->
                        if (state.artistId != targetId) return@update state
                        val existingIds = state.albums.mapTo(HashSet()) { it.id }
                        state.copy(
                            loadingMoreAlbums = false,
                            albums = state.albums + appended.filterNot { it.id in existingIds },
                            albumsHasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                },
                onFailure = {
                    _artistDetail.update { state ->
                        if (state.artistId == targetId) state.copy(loadingMoreAlbums = false) else state
                    }
                },
            )
        }
    }

    fun loadPlayRecords(force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        if (cookie.isNullOrBlank()) {
            _playRecords.value = NeteasePlayRecordsState()
            return
        }
        if (playRecordsJob?.isActive == true) return
        if (!force && _playRecords.value.loaded &&
            System.currentTimeMillis() - playRecordsLoadedAt < NeteaseRepository.DISCOVER_TTL_MS
        ) {
            return
        }
        playRecordsJob = NeteaseRepository.ioScope.launch {
            _playRecords.update { it.copy(loading = true) }
            val uid = NeteaseRepository.accountInfo.value?.uid?.takeIf(String::isNotBlank)
                ?: NeteaseClient.fetchAccountInfo(cookie).getOrNull()?.also { NeteaseRepository.setAccountInfo(it) }?.uid
                ?: ""
            val (recentRes, weekRes, allRes) = coroutineScope {
                val rJob = async { NeteaseClient.fetchRecentPlayedSongs(cookie, limit = 100) }
                val wJob = async {
                    if (uid.isBlank()) Result.failure(NeteaseWriteException.LoginRequired("无法获取网易云账号"))
                    else NeteaseClient.fetchUserPlayRecords(uid, allTime = false, cookie = cookie)
                }
                val aJob = async {
                    if (uid.isBlank()) Result.failure(NeteaseWriteException.LoginRequired("无法获取网易云账号"))
                    else NeteaseClient.fetchUserPlayRecords(uid, allTime = true, cookie = cookie)
                }
                Triple(rJob.await(), wJob.await(), aJob.await())
            }
            fun convertRecords(items: List<NeteasePlayRecordPayload>): List<NeteasePlayRecordEntry> {
                if (items.isEmpty()) return emptyList()
                val materialized = materializeEntries(items.map { it.song }).associateBy { it.id }
                return items.mapNotNull { item ->
                    val track = materialized["netease:${item.song.songId}"] ?: return@mapNotNull null
                    NeteasePlayRecordEntry(
                        track = track,
                        playCount = item.playCount,
                        score = item.score,
                        playedAtMs = item.playedAtMs,
                    )
                }
            }
            _playRecords.value = NeteasePlayRecordsState(
                loading = false,
                loaded = true,
                recent = convertRecords(recentRes.getOrNull().orEmpty()),
                weekRank = convertRecords(weekRes.getOrNull().orEmpty()),
                allRank = convertRecords(allRes.getOrNull().orEmpty()),
                recentError = recentRes.exceptionOrNull()?.message,
                weekRankError = weekRes.exceptionOrNull()?.message,
                allRankError = allRes.exceptionOrNull()?.message,
            )
            playRecordsLoadedAt = System.currentTimeMillis()
        }
    }

    fun loadRecommendedPlaylistsPage(force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        if (recommendedPlaylistsJob?.isActive == true) return
        if (!force && _recommendedPlaylists.value.loaded &&
            System.currentTimeMillis() - recommendedPlaylistsLoadedAt < NeteaseRepository.DISCOVER_TTL_MS
        ) {
            return
        }
        val cookie = NeteaseRepository.cookie(app)
        recommendedPlaylistsJob = NeteaseRepository.ioScope.launch {
            _recommendedPlaylists.update { it.copy(loading = true, error = null) }
            val (resourceRes, personalizedRes) = coroutineScope {
                val resJob = async { NeteaseClient.fetchRecommendedResources(cookie, limit = 40) }
                val perJob = async { NeteaseClient.fetchRecommendedPlaylists(cookie, limit = 60) }
                resJob.await() to perJob.await()
            }
            val merged = (resourceRes.getOrNull().orEmpty() + personalizedRes.getOrNull().orEmpty())
                .distinctBy { it.id }
                .map { payload ->
                    DiscoverPlaylist(
                        id = payload.id,
                        name = payload.name,
                        subtitle = payload.description,
                        coverUrl = payload.coverUrl,
                        coverPath = NeteaseImageStore.cached(app, payload.coverUrl)?.absolutePath,
                        trackCount = payload.remoteTrackCount,
                    )
                }
            val err = if (merged.isEmpty()) {
                resourceRes.exceptionOrNull()?.message ?: personalizedRes.exceptionOrNull()?.message
            } else {
                null
            }
            _recommendedPlaylists.value = RecommendedPlaylistsState(
                loading = false,
                loaded = true,
                items = merged,
                error = err,
            )
            recommendedPlaylistsLoadedAt = System.currentTimeMillis()
            fillCovers(app, cookie, merged)
        }
    }

    fun updateFavoriteRecFromDaily(entries: List<NeteaseEntryRecord>) {
        val payloads = entries.take(30).map {
            NeteaseEntryPayload(
                songId = it.songId,
                title = it.title,
                artists = it.artists,
                album = it.album,
                durationMs = it.durationMs,
                coverUrl = it.coverUrl,
                albumId = it.albumId,
                artistIds = it.artistIds,
            )
        }
        val tracks = materializeEntries(payloads)
        if (tracks.isEmpty()) return
        _homeFeed.update { feed ->
            val updatedSections = feed.sections.map { section ->
                if (section.titleRes == R.string.home_section_favorite_rec) {
                    section.copy(songs = tracks)
                } else {
                    section
                }
            }
            feed.copy(sections = updatedSections)
        }
    }

    // ---- 音乐云盘 ----

    fun loadCloudDisk(force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        if (cookie.isNullOrBlank()) {
            _cloudDisk.value = NeteaseCloudDiskState(error = "请先登录网易云账号")
            return
        }
        if (cloudDiskJob?.isActive == true) return
        if (!force && _cloudDisk.value.loaded && _cloudDisk.value.items.isNotEmpty()) return
        cloudDiskMoreJob?.cancel()
        cloudDiskJob = NeteaseRepository.ioScope.launch {
            _cloudDisk.update { it.copy(loading = true, error = null) }
            val res = NeteaseClient.fetchCloudDiskTracks(offset = 0, limit = 100, cookie = cookie)
            res.fold(
                onSuccess = { page ->
                    val tracksById = materializeEntries(page.tracks.map { it.song }).associateBy { it.id }
                    val items = page.tracks.mapNotNull { entry ->
                        val track = tracksById["netease:${entry.song.songId}"] ?: return@mapNotNull null
                        NeteaseCloudDiskItem(
                            songId = entry.song.songId,
                            track = track,
                            fileName = entry.fileName,
                            fileSizeBytes = entry.fileSizeBytes,
                            addTimeMs = entry.addTimeMs,
                        )
                    }
                    _cloudDisk.value = NeteaseCloudDiskState(
                        loading = false,
                        loaded = true,
                        items = items,
                        totalCount = page.count,
                        sizeBytes = page.sizeBytes,
                        maxSizeBytes = page.maxSizeBytes,
                        hasMore = page.hasMore,
                        error = null,
                    )
                },
                onFailure = { err ->
                    _cloudDisk.update {
                        it.copy(loading = false, loaded = true, error = err.message ?: "加载云盘失败")
                    }
                },
            )
        }
    }

    fun loadMoreCloudDisk() {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app) ?: return
        val cur = _cloudDisk.value
        if (cur.loading || cur.loadingMore || !cur.hasMore || cloudDiskMoreJob?.isActive == true) return
        val offset = cur.items.size
        cloudDiskMoreJob = NeteaseRepository.ioScope.launch {
            _cloudDisk.update { it.copy(loadingMore = true) }
            NeteaseClient.fetchCloudDiskTracks(offset = offset, limit = 100, cookie = cookie).fold(
                onSuccess = { page ->
                    val tracksById = materializeEntries(page.tracks.map { it.song }).associateBy { it.id }
                    val appended = page.tracks.mapNotNull { entry ->
                        val track = tracksById["netease:${entry.song.songId}"] ?: return@mapNotNull null
                        NeteaseCloudDiskItem(
                            songId = entry.song.songId,
                            track = track,
                            fileName = entry.fileName,
                            fileSizeBytes = entry.fileSizeBytes,
                            addTimeMs = entry.addTimeMs,
                        )
                    }
                    _cloudDisk.update { state ->
                        val existingIds = state.items.mapTo(HashSet()) { it.songId }
                        val merged = state.items + appended.filterNot { it.songId in existingIds }
                        state.copy(
                            loadingMore = false,
                            items = merged,
                            totalCount = maxOf(page.count, merged.size),
                            sizeBytes = page.sizeBytes.takeIf { it > 0 } ?: state.sizeBytes,
                            maxSizeBytes = page.maxSizeBytes.takeIf { it > 0 } ?: state.maxSizeBytes,
                            hasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                },
                onFailure = {
                    _cloudDisk.update { it.copy(loadingMore = false) }
                },
            )
        }
    }

    suspend fun deleteCloudDiskTracks(context: Context, songIds: List<Long>): Result<Unit> {
        val cookie = NeteaseRepository.cookie(context)
        val cleanIds = songIds.distinct()
        if (cleanIds.isEmpty()) return Result.success(Unit)
        return NeteaseClient.deleteCloudTracks(cleanIds, cookie).onSuccess {
            val idSet = cleanIds.toSet()
            _cloudDisk.update { state ->
                val remaining = state.items.filterNot { it.songId in idSet }
                val removedSize = state.items.filter { it.songId in idSet }.sumOf { it.fileSizeBytes }
                state.copy(
                    items = remaining,
                    totalCount = (state.totalCount - cleanIds.size).coerceAtLeast(remaining.size),
                    sizeBytes = (state.sizeBytes - removedSize).coerceAtLeast(0L),
                )
            }
        }
    }

    // ---- 发现广场（歌单广场 / 精品 / 排行榜 / 新碟上架 / 热门歌手）----

    fun openDiscoverSquare(
        tab: DiscoverSquareTab = DiscoverSquareTab.Playlists,
        category: String? = null,
    ) {
        val nextCat = category?.takeIf(String::isNotBlank) ?: _discoverSquare.value.selectedCategory
        _discoverSquare.update { it.copy(activeTab = tab, selectedCategory = nextCat) }
        ensureSquareTabLoaded(tab, categoryChanged = category != null)
    }

    fun selectSquareTab(tab: DiscoverSquareTab) {
        _discoverSquare.update { it.copy(activeTab = tab) }
        ensureSquareTabLoaded(tab, categoryChanged = false)
    }

    fun selectSquareCategory(category: String) {
        val clean = category.trim().ifBlank { "全部" }
        _discoverSquare.update { it.copy(selectedCategory = clean) }
        when (_discoverSquare.value.activeTab) {
            DiscoverSquareTab.HighQuality -> loadSquareHighQualityPlaylists(clean, force = true)
            else -> loadSquareTopPlaylists(clean, force = true)
        }
    }

    fun selectNewAlbumsArea(area: String) {
        val clean = area.trim().ifBlank { "ALL" }
        _discoverSquare.update { it.copy(newAlbumsArea = clean) }
        loadSquareNewAlbums(clean, force = true)
    }

    private fun ensureSquareTabLoaded(tab: DiscoverSquareTab, categoryChanged: Boolean) {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        if (!squareCatsLoaded) {
            squareCatsLoaded = true
            NeteaseRepository.ioScope.launch {
                NeteaseClient.fetchPlaylistCategories(cookie).onSuccess { catlist ->
                    val hotOrFirst = catlist.categories.filter { it.hot }.ifEmpty { catlist.categories.take(20) }
                    val items = listOf(NeteasePlaylistCategory(name = catlist.allCategory, hot = true)) +
                        hotOrFirst.filterNot { it.name == catlist.allCategory }.map {
                            NeteasePlaylistCategory(it.name, it.category, it.hot)
                        }
                    _discoverSquare.update { it.copy(categories = items) }
                }
            }
        }
        val state = _discoverSquare.value
        when (tab) {
            DiscoverSquareTab.Playlists -> if (categoryChanged || state.playlists.isEmpty()) {
                loadSquareTopPlaylists(state.selectedCategory, force = categoryChanged)
            }
            DiscoverSquareTab.HighQuality -> if (categoryChanged || state.highQuality.isEmpty()) {
                loadSquareHighQualityPlaylists(state.selectedCategory, force = categoryChanged)
            }
            DiscoverSquareTab.Rankings -> if (state.rankings.isEmpty()) loadSquareRankings()
            DiscoverSquareTab.NewAlbums -> if (state.newAlbums.isEmpty()) loadSquareNewAlbums(state.newAlbumsArea)
            DiscoverSquareTab.TopArtists -> if (state.topArtists.isEmpty()) loadSquareTopArtists()
        }
    }

    fun loadSquareTopPlaylists(category: String = _discoverSquare.value.selectedCategory, force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        if (!force && _discoverSquare.value.playlists.isNotEmpty() && squarePlaylistsJob?.isActive == true) return
        squarePlaylistsJob?.cancel()
        squarePlaylistsJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(playlistsLoading = true, playlistsError = null) }
            NeteaseClient.fetchTopPlaylists(cat = category, offset = 0, limit = 30, cookie = cookie).fold(
                onSuccess = { page ->
                    val cards = page.playlists.map { p ->
                        DiscoverPlaylist(
                            id = p.id,
                            name = p.name,
                            subtitle = p.description,
                            coverUrl = p.coverUrl,
                            coverPath = NeteaseImageStore.cached(app, p.coverUrl)?.absolutePath,
                            trackCount = p.remoteTrackCount,
                        )
                    }
                    _discoverSquare.update {
                        it.copy(
                            playlists = cards,
                            playlistsLoading = false,
                            playlistsLoadingMore = false,
                            playlistsHasMore = page.hasMore,
                            playlistsError = null,
                        )
                    }
                    fillSquareCovers(app, cookie, cards)
                },
                onFailure = { err ->
                    _discoverSquare.update {
                        it.copy(playlistsLoading = false, playlistsError = err.message ?: "加载歌单失败")
                    }
                },
            )
        }
    }

    fun loadMoreSquareTopPlaylists() {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        val cur = _discoverSquare.value
        if (cur.playlistsLoading || cur.playlistsLoadingMore || !cur.playlistsHasMore || squarePlaylistsJob?.isActive == true) return
        squarePlaylistsJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(playlistsLoadingMore = true) }
            NeteaseClient.fetchTopPlaylists(
                cat = cur.selectedCategory,
                offset = cur.playlists.size,
                limit = 30,
                cookie = cookie,
            ).fold(
                onSuccess = { page ->
                    val appended = page.playlists.map { p ->
                        DiscoverPlaylist(
                            id = p.id,
                            name = p.name,
                            subtitle = p.description,
                            coverUrl = p.coverUrl,
                            coverPath = NeteaseImageStore.cached(app, p.coverUrl)?.absolutePath,
                            trackCount = p.remoteTrackCount,
                        )
                    }
                    _discoverSquare.update { state ->
                        val existing = state.playlists.mapTo(HashSet()) { it.id }
                        val merged = state.playlists + appended.filterNot { it.id in existing }
                        state.copy(
                            playlists = merged,
                            playlistsLoadingMore = false,
                            playlistsHasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                    fillSquareCovers(app, cookie, appended)
                },
                onFailure = {
                    _discoverSquare.update { it.copy(playlistsLoadingMore = false) }
                },
            )
        }
    }

    fun loadSquareHighQualityPlaylists(category: String = _discoverSquare.value.selectedCategory, force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        if (!force && _discoverSquare.value.highQuality.isNotEmpty() && squareHqJob?.isActive == true) return
        squareHqJob?.cancel()
        squareHqJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(highQualityLoading = true, highQualityError = null) }
            NeteaseClient.fetchHighQualityPlaylists(cat = category, lastTime = 0L, limit = 30, cookie = cookie).fold(
                onSuccess = { page ->
                    val cards = page.playlists.map { p ->
                        DiscoverPlaylist(
                            id = p.id,
                            name = p.name,
                            subtitle = p.description,
                            coverUrl = p.coverUrl,
                            coverPath = NeteaseImageStore.cached(app, p.coverUrl)?.absolutePath,
                            trackCount = p.remoteTrackCount,
                        )
                    }
                    _discoverSquare.update {
                        it.copy(
                            highQuality = cards,
                            highQualityLastTime = page.lastTime,
                            highQualityLoading = false,
                            highQualityLoadingMore = false,
                            highQualityHasMore = page.hasMore,
                            highQualityError = null,
                        )
                    }
                    fillSquareCovers(app, cookie, cards)
                },
                onFailure = { err ->
                    _discoverSquare.update {
                        it.copy(highQualityLoading = false, highQualityError = err.message ?: "加载精品歌单失败")
                    }
                },
            )
        }
    }

    fun loadMoreSquareHighQualityPlaylists() {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        val cur = _discoverSquare.value
        if (cur.highQualityLoading || cur.highQualityLoadingMore || !cur.highQualityHasMore || squareHqJob?.isActive == true) return
        squareHqJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(highQualityLoadingMore = true) }
            NeteaseClient.fetchHighQualityPlaylists(
                cat = cur.selectedCategory,
                lastTime = cur.highQualityLastTime,
                limit = 30,
                cookie = cookie,
            ).fold(
                onSuccess = { page ->
                    val appended = page.playlists.map { p ->
                        DiscoverPlaylist(
                            id = p.id,
                            name = p.name,
                            subtitle = p.description,
                            coverUrl = p.coverUrl,
                            coverPath = NeteaseImageStore.cached(app, p.coverUrl)?.absolutePath,
                            trackCount = p.remoteTrackCount,
                        )
                    }
                    _discoverSquare.update { state ->
                        val existing = state.highQuality.mapTo(HashSet()) { it.id }
                        val merged = state.highQuality + appended.filterNot { it.id in existing }
                        state.copy(
                            highQuality = merged,
                            highQualityLastTime = page.lastTime.takeIf { it > 0L } ?: state.highQualityLastTime,
                            highQualityLoadingMore = false,
                            highQualityHasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                    fillSquareCovers(app, cookie, appended)
                },
                onFailure = {
                    _discoverSquare.update { it.copy(highQualityLoadingMore = false) }
                },
            )
        }
    }

    fun loadSquareRankings() {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        if (squareRankingsJob?.isActive == true) return
        squareRankingsJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(rankingsLoading = true, rankingsError = null) }
            NeteaseClient.fetchToplists(cookie).fold(
                onSuccess = { list ->
                    val cards = list.map { item ->
                        DiscoverPlaylist(
                            id = item.id,
                            name = item.name,
                            subtitle = item.updateFrequency,
                            coverUrl = item.coverUrl,
                            coverPath = NeteaseImageStore.cached(app, item.coverUrl)?.absolutePath,
                        )
                    }
                    _discoverSquare.update {
                        it.copy(rankings = cards, rankingsLoading = false, rankingsError = null)
                    }
                    fillSquareCovers(app, cookie, cards)
                },
                onFailure = { err ->
                    _discoverSquare.update {
                        it.copy(rankingsLoading = false, rankingsError = err.message ?: "加载排行榜失败")
                    }
                },
            )
        }
    }

    fun loadSquareNewAlbums(area: String = _discoverSquare.value.newAlbumsArea, force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        if (!force && _discoverSquare.value.newAlbums.isNotEmpty() && squareAlbumsJob?.isActive == true) return
        squareAlbumsJob?.cancel()
        squareAlbumsJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(newAlbumsLoading = true, newAlbumsError = null) }
            NeteaseClient.fetchNewAlbums(area = area, offset = 0, limit = 30, cookie = cookie).fold(
                onSuccess = { page ->
                    val items = page.albums.map {
                        NeteaseArtistAlbumItem(
                            id = it.id,
                            name = it.name,
                            artistName = it.artistName,
                            coverUrl = it.coverUrl,
                            trackCount = it.trackCount,
                            publishTimeMs = it.publishTimeMs,
                        )
                    }
                    _discoverSquare.update {
                        it.copy(
                            newAlbums = items,
                            newAlbumsLoading = false,
                            newAlbumsLoadingMore = false,
                            newAlbumsHasMore = page.hasMore,
                            newAlbumsError = null,
                        )
                    }
                },
                onFailure = { err ->
                    _discoverSquare.update {
                        it.copy(newAlbumsLoading = false, newAlbumsError = err.message ?: "加载新碟失败")
                    }
                },
            )
        }
    }

    fun loadMoreSquareNewAlbums() {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        val cur = _discoverSquare.value
        if (cur.newAlbumsLoading || cur.newAlbumsLoadingMore || !cur.newAlbumsHasMore || squareAlbumsJob?.isActive == true) return
        squareAlbumsJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(newAlbumsLoadingMore = true) }
            NeteaseClient.fetchNewAlbums(
                area = cur.newAlbumsArea,
                offset = cur.newAlbums.size,
                limit = 30,
                cookie = cookie,
            ).fold(
                onSuccess = { page ->
                    val appended = page.albums.map {
                        NeteaseArtistAlbumItem(
                            id = it.id,
                            name = it.name,
                            artistName = it.artistName,
                            coverUrl = it.coverUrl,
                            trackCount = it.trackCount,
                            publishTimeMs = it.publishTimeMs,
                        )
                    }
                    _discoverSquare.update { state ->
                        val existing = state.newAlbums.mapTo(HashSet()) { it.id }
                        val merged = state.newAlbums + appended.filterNot { it.id in existing }
                        state.copy(
                            newAlbums = merged,
                            newAlbumsLoadingMore = false,
                            newAlbumsHasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                },
                onFailure = {
                    _discoverSquare.update { it.copy(newAlbumsLoadingMore = false) }
                },
            )
        }
    }

    fun loadSquareTopArtists(force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        if (!force && _discoverSquare.value.topArtists.isNotEmpty() && squareArtistsJob?.isActive == true) return
        squareArtistsJob?.cancel()
        squareArtistsJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(topArtistsLoading = true, topArtistsError = null) }
            NeteaseClient.fetchTopArtists(offset = 0, limit = 50, cookie = cookie).fold(
                onSuccess = { page ->
                    val items = page.artists.map {
                        NeteaseTopArtistItem(it.id, it.name, it.coverUrl, it.musicSize, it.albumSize)
                    }
                    _discoverSquare.update {
                        it.copy(
                            topArtists = items,
                            topArtistsLoading = false,
                            topArtistsLoadingMore = false,
                            topArtistsHasMore = page.hasMore,
                            topArtistsError = null,
                        )
                    }
                },
                onFailure = { err ->
                    _discoverSquare.update {
                        it.copy(topArtistsLoading = false, topArtistsError = err.message ?: "加载热门歌手失败")
                    }
                },
            )
        }
    }

    fun loadMoreSquareTopArtists() {
        val app = NeteaseRepository.appContext ?: return
        val cookie = NeteaseRepository.cookie(app)
        val cur = _discoverSquare.value
        if (cur.topArtistsLoading || cur.topArtistsLoadingMore || !cur.topArtistsHasMore || squareArtistsJob?.isActive == true) return
        squareArtistsJob = NeteaseRepository.ioScope.launch {
            _discoverSquare.update { it.copy(topArtistsLoadingMore = true) }
            NeteaseClient.fetchTopArtists(offset = cur.topArtists.size, limit = 50, cookie = cookie).fold(
                onSuccess = { page ->
                    val appended = page.artists.map {
                        NeteaseTopArtistItem(it.id, it.name, it.coverUrl, it.musicSize, it.albumSize)
                    }
                    _discoverSquare.update { state ->
                        val existing = state.topArtists.mapTo(HashSet()) { it.id }
                        val merged = state.topArtists + appended.filterNot { it.id in existing }
                        state.copy(
                            topArtists = merged,
                            topArtistsLoadingMore = false,
                            topArtistsHasMore = page.hasMore && appended.isNotEmpty(),
                        )
                    }
                },
                onFailure = {
                    _discoverSquare.update { it.copy(topArtistsLoadingMore = false) }
                },
            )
        }
    }

    private suspend fun fillSquareCovers(app: Context, cookie: String?, playlists: List<DiscoverPlaylist>) {
        playlists.distinctBy { it.coverUrl }.take(24).forEach { playlist ->
            if (playlist.coverPath != null || playlist.coverUrl.isBlank()) return@forEach
            val file = NeteaseImageStore.ensure(app, playlist.coverUrl, cookie).await() ?: return@forEach
            val path = file.absolutePath
            _discoverSquare.update { state ->
                state.copy(
                    playlists = state.playlists.withCover(playlist.id, path),
                    highQuality = state.highQuality.withCover(playlist.id, path),
                    rankings = state.rankings.withCover(playlist.id, path),
                )
            }
        }
    }

    // ---- 热搜榜 & 实时搜索建议 & 搜索历史 ----

    fun loadSearchHot(force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        if (searchHotJob?.isActive == true) return
        if (!force && _searchHot.value.isNotEmpty() &&
            System.currentTimeMillis() - searchHotLoadedAt < NeteaseRepository.DISCOVER_TTL_MS
        ) {
            return
        }
        val cookie = NeteaseRepository.cookie(app)
        searchHotJob = NeteaseRepository.ioScope.launch {
            _searchHotLoading.value = true
            NeteaseClient.fetchSearchHotDetail(cookie).onSuccess { items ->
                _searchHot.value = items.map {
                    NeteaseSearchHotItem(it.keyword, it.score, it.content, it.iconType)
                }
                searchHotLoadedAt = System.currentTimeMillis()
            }
            _searchHotLoading.value = false
        }
    }

    /** 搜索“猜你喜欢”：优先基于账号每日推荐歌曲（提炼歌名与常听歌手），未登录或不足时降级为新歌/热门词。 */
    fun loadSearchGuess(force: Boolean = false) {
        val app = NeteaseRepository.appContext ?: return
        if (!force && searchGuessJob?.isActive == true) return
        if (!force && _searchGuess.value.isNotEmpty() &&
            System.currentTimeMillis() - searchGuessLoadedAt < NeteaseRepository.DISCOVER_TTL_MS
        ) {
            return
        }
        if (force) searchGuessJob?.cancel()
        val cookie = NeteaseRepository.cookie(app)
        searchGuessJob = NeteaseRepository.ioScope.launch {
            _searchGuessLoading.value = true
            try {
                val recommend = if (!cookie.isNullOrBlank()) {
                    NeteaseClient.fetchRecommendSongs(cookie, limit = 24, afresh = force).getOrNull().orEmpty()
                } else {
                    emptyList()
                }
                val newSongs = if (recommend.size < 6) {
                    NeteaseClient.fetchNewSongs(cookie, limit = 16).getOrNull().orEmpty()
                } else {
                    emptyList()
                }
                val hot = if (recommend.size + newSongs.size < 6) {
                    NeteaseClient.fetchSearchHotDetail(cookie).getOrNull().orEmpty()
                } else {
                    emptyList()
                }
                val keywords = buildSearchGuessKeywords(
                    recommendSongs = recommend,
                    newSongs = newSongs,
                    hotItems = hot,
                    shuffle = force,
                )
                if (keywords.isNotEmpty()) {
                    _searchGuess.value = keywords
                    searchGuessLoadedAt = System.currentTimeMillis()
                }
            } finally {
                _searchGuessLoading.value = false
            }
        }
    }

    internal fun buildSearchGuessKeywords(
        recommendSongs: List<NeteaseEntryPayload>,
        newSongs: List<NeteaseEntryPayload>,
        hotItems: List<NeteaseSearchHotItemPayload>,
        shuffle: Boolean = false,
        limit: Int = 12,
    ): List<String> {
        fun List<NeteaseEntryPayload>.toKeywords(): List<String> {
            val source = if (shuffle) shuffled() else this
            val titles = source.map { it.title.trim() }.filter { it.isNotBlank() && it.length <= 28 }
            val artists = source.flatMap { entry ->
                entry.artists.split(" / ").map { it.trim() }.take(1)
            }.filter { it.isNotBlank() && it.length <= 24 }
            val merged = mutableListOf<String>()
            val maxLen = maxOf(titles.size, artists.size)
            for (i in 0 until maxLen) {
                if (i < titles.size) merged += titles[i]
                if (i < artists.size && i % 2 == 0) merged += artists[i]
            }
            return merged
        }
        val combined = buildList {
            addAll(recommendSongs.toKeywords())
            addAll(newSongs.toKeywords())
            val hots = hotItems.map { it.keyword.trim() }.filter { it.isNotBlank() && it.length <= 28 }
            addAll(if (shuffle) hots.shuffled() else hots)
        }
        return combined
            .distinctBy { it.lowercase() }
            .take(limit)
    }

    suspend fun fetchSearchSuggestions(keyword: String): List<String> {
        val app = NeteaseRepository.appContext ?: return emptyList()
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            _searchSuggestions.value = emptyList()
            return emptyList()
        }
        val list = NeteaseClient.fetchSearchSuggest(trimmed, NeteaseRepository.cookie(app))
            .getOrNull()?.keywords.orEmpty()
        _searchSuggestions.value = list
        return list
    }

    fun loadSearchHistory(context: Context) {
        val raw = context.applicationContext
            .getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SEARCH_HISTORY, null)
        _searchHistory.value = decodeSearchHistory(raw)
    }

    fun recordSearchHistory(context: Context, keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val next = (listOf(clean) + _searchHistory.value.filterNot { it.equals(clean, ignoreCase = true) })
            .take(15)
        _searchHistory.value = next
        persistSearchHistory(context, next)
    }

    fun removeSearchHistory(context: Context, keyword: String) {
        val next = _searchHistory.value.filterNot { it == keyword }
        _searchHistory.value = next
        persistSearchHistory(context, next)
    }

    fun clearSearchHistory(context: Context) {
        _searchHistory.value = emptyList()
        context.applicationContext
            .getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SEARCH_HISTORY)
            .apply()
    }

    private fun persistSearchHistory(context: Context, items: List<String>) {
        context.applicationContext
            .getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SEARCH_HISTORY, LibrarySyncProtocol.json.encodeToString(items))
            .apply()
    }

    internal fun decodeSearchHistory(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            LibrarySyncProtocol.json.decodeFromString<List<String>>(raw)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(15)
        }.getOrDefault(emptyList())
    }

    private fun persistMine(context: Context, data: MineData) {
        val cache = MineCache(
            created = data.created,
            saved = data.saved,
            albums = data.albums,
            artists = data.artists,
        )
        pendingMinePersist.set(context.applicationContext to cache)
        if (minePersistStarted.compareAndSet(false, true)) {
            NeteaseRepository.ioScope.launch {
                for (ignored in minePersistRequests) {
                    val snapshot = pendingMinePersist.getAndSet(null) ?: continue
                    minePersistMutex.withLock {
                        runCatching {
                            File(snapshot.first.filesDir, NeteaseRepository.MINE_FILE).writeTextAtomically(
                                LibrarySyncProtocol.json.encodeToString(snapshot.second),
                            )
                        }
                    }
                }
            }
        }
        minePersistRequests.trySend(Unit)
    }

    /** 统一的流式曲目物化：失败的单条直接跳过，不影响整块。 */
    private fun materializeEntries(entries: List<NeteaseEntryPayload>): List<LibraryTrack> =
        entries.mapNotNull { song ->
            runCatching {
                NeteaseRepository.materializeStreamingTrack(
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
            _recommendedPlaylists.update { data ->
                data.copy(items = data.items.withCover(playlist.id, path))
            }
        }
    }

    private fun List<DiscoverPlaylist>.withCover(id: String, path: String): List<DiscoverPlaylist> =
        map { if (it.id == id && it.coverPath == null) it.copy(coverPath = path) else it }

    fun buildStreamingTrack(entry: NeteaseEntryRecord, cover: File?): LibraryTrack {
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
                put("neteaseArtistIds", JsonArray(entry.artistIds.map { JsonPrimitive(it) }))
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
}
