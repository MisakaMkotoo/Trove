package com.exodidio.tune.netease

import android.content.Context
import android.util.Log
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 心动模式、私人漫游与相似歌曲推荐引擎。 */
internal object NeteaseModeEngine {
    private val modeHistoryLock = Any()

    private val _heartbeatEnabled = MutableStateFlow(false)
    val heartbeatEnabled: StateFlow<Boolean> = _heartbeatEnabled.asStateFlow()
    private val recentHeartbeatIds = ArrayDeque<Long>()
    private var lastHeartbeatSongId: Long? = null
    private var cachedHeartbeatPlaylistId: String? = null

    private val _roamEnabled = MutableStateFlow(false)
    val roamEnabled: StateFlow<Boolean> = _roamEnabled.asStateFlow()
    private val recentRoamIds = ArrayDeque<Long>()

    private val _similarEnabled = MutableStateFlow(false)
    val similarEnabled: StateFlow<Boolean> = _similarEnabled.asStateFlow()
    private val recentSimilarIds = ArrayDeque<Long>()
    private var similarSeedSongId: Long? = null

    private val _heartbeatPreview = MutableStateFlow(NeteaseModePreview())
    val heartbeatPreview: StateFlow<NeteaseModePreview> = _heartbeatPreview.asStateFlow()
    private val previewRequestVersion = AtomicLong(0L)
    private val _roamPreview = MutableStateFlow(NeteaseModePreview())
    val roamPreview: StateFlow<NeteaseModePreview> = _roamPreview.asStateFlow()
    private val _similarPreview = MutableStateFlow(NeteaseModePreview())
    val similarPreview: StateFlow<NeteaseModePreview> = _similarPreview.asStateFlow()
    private val similarPreviewRequestVersion = AtomicLong(0L)

    /** 预取心动第一首，使首页卡片封面与即将播放的首曲保持一致；点开时才真正播放。 */
    suspend fun prefetchHeartbeatPreview(
        context: Context,
        seedSongId: Long? = null,
    ) = withContext(Dispatchers.IO) {
        if (_heartbeatEnabled.value) return@withContext
        if (NeteaseRepository.cookie(context) == null) return@withContext
        runCatching {
            val seed = seedSongId
                ?: NeteaseRepository.playlistById(NeteaseRepository.LIKED_ID)?.entries?.firstOrNull()?.songId
                ?: return@withContext
            val current = _heartbeatPreview.value
            if (current.trackIds.isNotEmpty() && current.seed == seed) return@withContext
            val requestVersion = previewRequestVersion.incrementAndGet()
            val entry = fetchNextHeartbeat(context, seed, startSongId = seed) ?: return@withContext
            if (_heartbeatEnabled.value || previewRequestVersion.get() != requestVersion) return@withContext
            val track = NeteaseRepository.entryTrack(entry)
            _heartbeatPreview.value = NeteaseModePreview(
                trackIds = listOf(track.id),
                artworkPath = track.artworkPath,
                seed = seed,
            )
        }.onFailure { Log.w("NeteaseRepo", "hb preview failed: ${it.message}") }
    }

    /** 启动时预取漫游一批，仅用于卡片封面；点开时才真正播放。 */
    suspend fun prefetchRoamPreview(context: Context) = withContext(Dispatchers.IO) {
        if (_roamEnabled.value || _roamPreview.value.trackIds.isNotEmpty()) return@withContext
        if (NeteaseRepository.cookie(context) == null) return@withContext
        runCatching {
            val ids = fetchRoamBatch(context) ?: return@withContext
            val track = AndroidSyncRuntime.syncStore.trackForPlayback(ids.first())
            _roamPreview.value = NeteaseModePreview(
                trackIds = ids,
                artworkPath = track?.artworkPath,
            )
        }.onFailure { Log.w("NeteaseRepo", "roam preview failed: ${it.message}") }
    }

    /** 预取相似歌曲首批，使首页“相似歌曲”卡片展示与实际首曲一致的封面。 */
    suspend fun prefetchSimilarPreview(
        context: Context,
        seedSongId: Long? = null,
    ) = withContext(Dispatchers.IO) {
        if (_similarEnabled.value) return@withContext
        if (NeteaseRepository.cookie(context) == null) return@withContext
        runCatching {
            val seed = seedSongId
                ?: NeteaseRepository.playlistById(NeteaseRepository.LIKED_ID)?.entries?.firstOrNull()?.songId
                ?: NeteaseRepository.playlistById(NeteaseRepository.DAILY_ID)?.entries?.firstOrNull()?.songId
                ?: return@withContext
            val current = _similarPreview.value
            if (current.trackIds.isNotEmpty() && current.seed == seed) return@withContext
            val version = similarPreviewRequestVersion.incrementAndGet()
            val ids = fetchSimilarBatch(context, seed, recordHistory = false) ?: return@withContext
            if (_similarEnabled.value || similarPreviewRequestVersion.get() != version) return@withContext
            val track = AndroidSyncRuntime.syncStore.trackForPlayback(ids.first())
            _similarPreview.value = NeteaseModePreview(
                trackIds = ids,
                artworkPath = track?.artworkPath,
                seed = seed,
            )
        }.onFailure { Log.w("NeteaseRepo", "similar preview failed: ${it.message}") }
    }

    /** 心动、漫游与相似歌曲电台互斥，同时只保留一个连续推荐模式。 */
    fun setHeartbeatEnabled(enabled: Boolean) {
        _heartbeatEnabled.value = enabled
        synchronized(modeHistoryLock) {
            if (enabled) {
                _roamEnabled.value = false
                recentRoamIds.clear()
                _similarEnabled.value = false
                recentSimilarIds.clear()
                similarSeedSongId = null
            } else {
                recentHeartbeatIds.clear()
            }
        }
    }

    fun setRoamEnabled(enabled: Boolean) {
        _roamEnabled.value = enabled
        synchronized(modeHistoryLock) {
            if (enabled) {
                _heartbeatEnabled.value = false
                recentHeartbeatIds.clear()
                _similarEnabled.value = false
                recentSimilarIds.clear()
                similarSeedSongId = null
            } else {
                recentRoamIds.clear()
            }
        }
    }

    fun setSimilarEnabled(enabled: Boolean) {
        _similarEnabled.value = enabled
        synchronized(modeHistoryLock) {
            if (enabled) {
                _heartbeatEnabled.value = false
                recentHeartbeatIds.clear()
                _roamEnabled.value = false
                recentRoamIds.clear()
            } else {
                recentSimilarIds.clear()
                similarSeedSongId = null
            }
        }
    }

    /** 开启漫游：取一批推荐，返回可播放曲目 ID 列表。 */
    suspend fun startRoam(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (NeteaseRepository.cookie(context) == null) error("漫游需要登录网易云")
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

    /** 开启相似歌曲电台：优先当前播放歌曲（或我喜欢的音乐/每日推荐）作为种子。 */
    suspend fun startSimilar(context: Context, seedSongId: Long? = null): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (NeteaseRepository.cookie(context) == null) error("相似歌曲电台需要登录网易云")
                setSimilarEnabled(true)
                similarPreviewRequestVersion.incrementAndGet()
                val preview = _similarPreview.value
                if (preview.trackIds.isNotEmpty() && (seedSongId == null || preview.seed == seedSongId)) {
                    synchronized(modeHistoryLock) {
                        similarSeedSongId = preview.seed
                        preview.trackIds.mapNotNull(NeteaseRepository::songIdForTrack).forEach { id ->
                            recentSimilarIds.addLast(id)
                            while (recentSimilarIds.size > 30) recentSimilarIds.removeFirst()
                        }
                    }
                    _similarPreview.value = preview.copy(trackIds = emptyList())
                    return@runCatching preview.trackIds
                }
                val seed = seedSongId
                    ?: likedNewestSongId(context)
                    ?: NeteaseRepository.playlistById(NeteaseRepository.DAILY_ID)?.entries?.firstOrNull()?.songId
                    ?: error("没有可用的种子歌曲")
                synchronized(modeHistoryLock) { similarSeedSongId = seed }
                fetchSimilarBatch(context, seed, recordHistory = true) ?: error("相似歌曲暂时没有推荐")
            }.onFailure { setSimilarEnabled(false) }
        }

    /** 队列末尾自动续一批相似歌曲；失败返回 null，由播放服务退避重试。 */
    suspend fun appendSimilarNext(context: Context, currentTrackId: String): List<String>? =
        withContext(Dispatchers.IO) {
            if (!_similarEnabled.value) return@withContext null
            val seed = NeteaseRepository.songIdForTrack(currentTrackId)
                ?: synchronized(modeHistoryLock) { similarSeedSongId }
                ?: return@withContext null
            synchronized(modeHistoryLock) { similarSeedSongId = seed }
            fetchSimilarBatch(context, seed, recordHistory = true)
        }

    private suspend fun fetchSimilarBatch(
        context: Context,
        seedSongId: Long,
        recordHistory: Boolean,
    ): List<String>? {
        val songs = NeteaseClient.fetchSimilarSongs(
            songId = seedSongId,
            limit = 30,
            offset = 0,
            cookie = NeteaseRepository.cookie(context),
        ).onFailure { Log.w("NeteaseRepo", "similar failed seed=$seedSongId: ${it.message}") }
            .getOrNull()
            .orEmpty()
        if (songs.isEmpty()) return null
        val fresh = synchronized(modeHistoryLock) {
            val selected = songs.filter { it.songId !in recentSimilarIds }.ifEmpty { songs }
            if (recordHistory) {
                selected.forEach { entry ->
                    recentSimilarIds.addLast(entry.songId)
                    while (recentSimilarIds.size > 30) recentSimilarIds.removeFirst()
                }
            }
            selected
        }
        val coverDir = File(context.filesDir, "netease-covers").apply { mkdirs() }
        return fresh.map { entry ->
            val record = NeteaseEntryRecord(
                songId = entry.songId,
                title = entry.title,
                artists = entry.artists,
                album = entry.album,
                durationMs = entry.durationMs,
                coverUrl = entry.coverUrl,
                albumId = entry.albumId,
                artistIds = entry.artistIds,
            )
            val cover = NeteaseMediaHelper.downloadCover(context, coverDir, record)
            NeteaseRepository.entryTrack(record.copy(coverPath = cover?.absolutePath)).id
        }
    }

    private suspend fun fetchRoamBatch(context: Context): List<String>? {
        val songs = NeteaseClient.fetchRoamSongs(NeteaseRepository.cookie(context))
            .onFailure { Log.w("NeteaseRepo", "roam failed: ${it.message}") }
            .getOrNull()
            .orEmpty()
        if (songs.isEmpty()) return null
        val fresh = synchronized(modeHistoryLock) {
            val selected = songs.filter { it.songId !in recentRoamIds }.ifEmpty { songs }
            selected.forEach { entry ->
                recentRoamIds.addLast(entry.songId)
                while (recentRoamIds.size > 30) recentRoamIds.removeFirst()
            }
            selected
        }
        val coverDir = File(context.filesDir, "netease-covers").apply { mkdirs() }
        return fresh.map { entry ->
            val record = NeteaseEntryRecord(
                songId = entry.songId,
                title = entry.title,
                artists = entry.artists,
                album = entry.album,
                durationMs = entry.durationMs,
                coverUrl = entry.coverUrl,
            )
            val cover = NeteaseMediaHelper.downloadCover(context, coverDir, record)
            NeteaseRepository.entryTrack(record.copy(coverPath = cover?.absolutePath)).id
        }
    }

    /** 以一首歌为基准取相似歌曲列表（页面使用）。 */
    suspend fun similarSongs(context: Context, trackId: String): Result<List<LibraryTrack>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val songId = NeteaseRepository.songIdForTrack(trackId) ?: error("无法识别这首歌的网易云 ID")
                val entries = NeteaseClient.fetchSimilarSongs(
                    songId,
                    limit = 30,
                    offset = 0,
                    NeteaseRepository.cookie(context),
                ).getOrThrow()
                entries.map { entry ->
                    NeteaseRepository.entryTrack(
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

    /** 开启心动模式并从种子里播第一首；返回可播放曲目 ID，同时确保卡片封面与实际开播曲目一致。 */
    suspend fun startHeartbeat(context: Context, seedSongId: Long? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                setHeartbeatEnabled(true)
                if (NeteaseRepository.cookie(context) == null) error("心动模式需要登录网易云")
                previewRequestVersion.incrementAndGet()
                resolveHeartbeatStart(
                    seedSongId = seedSongId,
                    currentPreview = _heartbeatPreview.value,
                    resolveLikedSeed = { likedNewestSongId(context) },
                    resolveDailySeed = {
                        NeteaseRepository.playlistById(NeteaseRepository.DAILY_ID)
                            ?.entries?.firstOrNull()?.songId
                    },
                    fetchTrackForSeed = { seed ->
                        fetchNextHeartbeat(context, seed, startSongId = seed)?.let(NeteaseRepository::entryTrack)
                    },
                    onPreviewUpdated = { updated -> _heartbeatPreview.value = updated },
                )
            }.onFailure { _heartbeatEnabled.value = false }
        }

    internal suspend fun resolveHeartbeatStart(
        seedSongId: Long?,
        currentPreview: NeteaseModePreview,
        resolveLikedSeed: suspend () -> Long?,
        resolveDailySeed: suspend () -> Long?,
        fetchTrackForSeed: suspend (Long) -> LibraryTrack?,
        onPreviewUpdated: (NeteaseModePreview) -> Unit,
    ): String {
        if (currentPreview.trackIds.isNotEmpty() && (seedSongId == null || currentPreview.seed == seedSongId)) {
            val trackId = currentPreview.trackIds.first()
            onPreviewUpdated(currentPreview.copy(trackIds = emptyList()))
            return trackId
        }
        val primarySeed = seedSongId ?: resolveLikedSeed() ?: error("没有可用的种子歌曲")
        var usedSeed = primarySeed
        var resolved = fetchTrackForSeed(primarySeed)
        if (resolved == null) {
            val likedSeed = resolveLikedSeed()?.takeIf { it != primarySeed }
            if (likedSeed != null) {
                usedSeed = likedSeed
                resolved = fetchTrackForSeed(likedSeed)
            }
        }
        if (resolved == null) {
            val dailySeed = resolveDailySeed()?.takeIf { it != primarySeed }
            if (dailySeed != null) {
                usedSeed = dailySeed
                resolved = fetchTrackForSeed(dailySeed)
            }
        }
        val track = resolved ?: error("心动模式暂时没有推荐")
        onPreviewUpdated(
            NeteaseModePreview(
                trackIds = emptyList(),
                artworkPath = track.artworkPath,
                seed = usedSeed,
            ),
        )
        return track.id
    }

    private suspend fun likedNewestSongId(context: Context): Long? {
        NeteaseRepository.playlistById(NeteaseRepository.LIKED_ID)?.entries?.firstOrNull()?.songId?.let { return it }
        NeteaseRepository.loadLiked(context).getOrNull()
        return NeteaseRepository.playlistById(NeteaseRepository.LIKED_ID)?.entries?.firstOrNull()?.songId
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
        val seed = NeteaseRepository.songIdForTrack(currentTrackId)
            ?: synchronized(modeHistoryLock) { lastHeartbeatSongId }
            ?: return@withContext null
        val startSongId = queueHeadTrackId?.let(NeteaseRepository::songIdForTrack) ?: seed
        val entry = fetchNextHeartbeat(context, seed, startSongId) ?: run {
            Log.d("NeteaseRepo", "hb append: no next (seed=$seed start=$startSongId)")
            return@withContext null
        }
        val id = NeteaseRepository.entryTrack(entry).id
        Log.d("NeteaseRepo", "hb append: next=${entry.songId} track=$id")
        id
    }

    private suspend fun fetchNextHeartbeat(context: Context, seed: Long, startSongId: Long): NeteaseEntryRecord? {
        val primaryPid = heartbeatPlaylistId(context, seed)
        val cookie = NeteaseRepository.cookie(context)
        val fallbackPid = cachedHeartbeatPlaylistId?.takeIf { it != primaryPid }
            ?: NeteaseClient.fetchLikedPlaylistId(cookie).getOrNull()
                ?.takeIf { it != primaryPid }
                ?.also { cachedHeartbeatPlaylistId = it }
        val pids = listOfNotNull(primaryPid, fallbackPid).ifEmpty { listOf(null) }
        var list: List<NeteaseEntryPayload> = emptyList()
        for (round in 0 until 2) {
            for (pid in pids) {
                list = NeteaseClient.fetchHeartbeatNext(seed, startSongId, pid, cookie, count = 10)
                    .onFailure { Log.w("NeteaseRepo", "hb repository failed pid=$pid: ${it.message}") }
                    .getOrNull()
                    .orEmpty()
                if (list.isNotEmpty()) break
            }
            if (list.isNotEmpty()) break
            delay(300)
        }
        val entry = synchronized(modeHistoryLock) {
            val picked = list.firstOrNull { payload ->
                payload.songId !in recentHeartbeatIds &&
                    AndroidSyncRuntime.syncStore.trackForPlayback("netease:${payload.songId}") == null
            } ?: list.firstOrNull { it.songId !in recentHeartbeatIds }
                ?: list.firstOrNull()
            if (picked != null) {
                lastHeartbeatSongId = picked.songId
                recentHeartbeatIds.addLast(picked.songId)
                while (recentHeartbeatIds.size > 20) recentHeartbeatIds.removeFirst()
            }
            picked
        }
        if (entry == null) return null
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
        val cover = NeteaseMediaHelper.downloadCover(context, coverDir, record)
        Log.d("NeteaseRepo", "hb cover url=${entry.coverUrl.isNotBlank()} file=${cover?.name ?: "none"}")
        return record.copy(coverPath = cover?.absolutePath)
    }

    /** 心动模式的 pid：普通歌单可用；私人雷达等特殊歌单接口会报“不支持该歌单类型”。 */
    private fun isSupportedHeartbeatPid(playlistId: String?): Boolean =
        playlistId != null &&
            playlistId.all(Char::isDigit) &&
            playlistId != NeteaseClient.PrivateRadarPlaylistId

    private suspend fun heartbeatPlaylistId(context: Context, seed: Long): String? {
        val source = NeteaseRepository.playlistIdForSong(seed)
        if (isSupportedHeartbeatPid(source)) {
            Log.d("NeteaseRepo", "hb pid source=playlist pid=$source seed=$seed")
            return source
        }
        cachedHeartbeatPlaylistId?.takeIf(::isSupportedHeartbeatPid)?.let {
            Log.d("NeteaseRepo", "hb pid source=cache pid=$it seed=$seed")
            return it
        }
        val cookie = NeteaseRepository.cookie(context) ?: return null
        val liked = NeteaseClient.fetchLikedPlaylistId(cookie)
            .onFailure { Log.w("NeteaseRepo", "hb liked playlist failed: ${it.message}") }
            .getOrNull()
        cachedHeartbeatPlaylistId = liked
        Log.d("NeteaseRepo", "hb pid source=liked pid=$liked seed=$seed sourcePlaylist=$source")
        return liked?.takeIf(::isSupportedHeartbeatPid)
    }
}
