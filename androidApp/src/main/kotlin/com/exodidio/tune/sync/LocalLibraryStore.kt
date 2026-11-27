package com.exodidio.tune.sync

import android.content.Context
import com.exodidio.tune.library.LocalImport
import com.exodidio.tune.mood.MoodRadioTrack
import com.exodidio.tune.player.DailyPlaybackAttemptStat
import com.exodidio.tune.player.DailyTrackListeningStat
import com.exodidio.tune.player.ListeningWrite
import com.exodidio.tune.player.PlaybackEndReason
import com.exodidio.tune.player.TrackAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** ============================================================================
 *  AndroidLibrarySyncStore — local-only replacement for the Room-backed store
 * ============================================================================ */

 class AndroidLibrarySyncStore(private val context: Context) {

    @Serializable
    private data class PersistedPlaylist(
        val id: String = "",
        val name: String = "",
        val trackIds: List<String> = emptyList(),
        val createdAt: String = "",
        val updatedAt: String = "",
        val metadataJson: String = "{}",
    )

    @Serializable
    private data class PersistedState(
        val favorites: List<String> = emptyList(),
        val hidden: List<String> = emptyList(),
        val onlineFavorites: List<ImportedTrackData> = emptyList(),
        val onlinePlaylistTracks: List<ImportedTrackData> = emptyList(),
        val artworkPaths: Map<String, String> = emptyMap(),
        val artistArtworkKeys: Map<String, String> = emptyMap(),
        val playlists: List<PersistedPlaylist> = emptyList(),
    )

    @Serializable
    private data class ImportedTrackData(
        val id: String = "",
        val title: String = "",
        val artists: String = "",
        val album: String = "",
        val albumId: String = "",
        val metadataJson: String = "{}",
        val artworkPath: String? = null,
        val audioPath: String? = null,
        val createdAt: String = "",
    )

    private fun ImportedTrackData.toLibraryTrackOrNull(): LibraryTrack? {
        if (id.isBlank()) return null
        return LibraryTrack(
            id = id,
            title = title,
            artists = artists,
            album = album,
            albumId = albumId,
            metadataJson = metadataJson,
            artworkPath = artworkPath,
            audioPath = audioPath,
            createdAt = createdAt,
            updatedAt = createdAt,
            sortTitle = title,
            sortArtists = artists,
        )
    }

    private fun LibraryTrack.toImportedData(): ImportedTrackData = ImportedTrackData(
        id = id,
        title = title,
        artists = artists,
        album = album,
        albumId = albumId,
        metadataJson = metadataJson,
        artworkPath = artworkPath,
        audioPath = audioPath,
        createdAt = createdAt,
    )

    @Serializable
    private data class PersistedListening(
        val tracks: List<DailyTrackListeningStat> = emptyList(),
        val attempts: List<DailyPlaybackAttemptStat> = emptyList(),
        val recent: List<RecentPlayRecord> = emptyList(),
        val footprints: List<ListeningFootprintRecord> = emptyList(),
    )

    @Serializable
    private data class PersistedTrackAnalysis(
        val trackId: String = "",
        val loudnessLufs: Float = -14f,
        val truePeak: Float = 0f,
    )

    /** 本地最近播放：合格播放开始时记录；快照足以在重启后恢复在线曲目与封面。 */
    @Serializable
    data class RecentPlayRecord(
        val trackId: String = "",
        val playedAt: Long = 0L,
        val title: String = "",
        val artists: String = "",
        val album: String = "",
        val coverUrl: String = "",
    )

    /** 本地听歌时序日记/足迹：每次有效播放结束时记录精确时间与曲目快照，100% 本地存储。 */
    @Serializable
    data class ListeningFootprintRecord(
        val id: String = "",
        val trackId: String = "",
        val title: String = "",
        val artists: String = "",
        val album: String = "",
        val artworkPath: String? = null,
        val coverUrl: String = "",
        val startedAt: Long = 0L,
        val endedAt: Long = 0L,
        val listenedSeconds: Int = 0,
        val endReason: PlaybackEndReason = PlaybackEndReason.COMPLETED,
    )

    private val stateFile = File(context.filesDir, "local-library-state.json")
    private val importedFile = File(context.filesDir, "imported-library.json")
    private val listeningFile = File(context.filesDir, "listening-stats.json")
    private val analysesFile = File(context.filesDir, "track-analyses.json")
    private val trackDayStats = mutableMapOf<Pair<String, String>, DailyTrackListeningStat>()
    private val attemptDayStats = mutableMapOf<String, DailyPlaybackAttemptStat>()
    private val _recentlyPlayed = MutableStateFlow(emptyList<RecentPlayRecord>())
    /** 最近播放（trackId + 播放时间），最新的在前。 */
    val recentlyPlayed: StateFlow<List<RecentPlayRecord>> = _recentlyPlayed.asStateFlow()
    private val _listeningFootprints = MutableStateFlow(emptyList<ListeningFootprintRecord>())
    /** 本地听歌足迹（按播放时间倒序），仅用于本地时序日记/年报/热力图，不上传网络。 */
    val listeningFootprints: StateFlow<List<ListeningFootprintRecord>> = _listeningFootprints.asStateFlow()
    private var listeningDirty = false
    private var lastListeningPersistAt = 0L

    private val _tracks = MutableStateFlow(emptyList<LibraryTrack>())
    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()
    @Volatile private var tracksById: Map<String, LibraryTrack> = emptyMap()

    // 网易云在线歌单按需下载的曲目：可播放、可解析，但不进入曲库列表。
    private val playbackOnlyTracks = MutableStateFlow(emptyList<LibraryTrack>())
    @Volatile private var playbackOnlyById: Map<String, LibraryTrack> = emptyMap()
    private val playbackOnlyLock = Any()
    /** 仅播放曲目（含在线搜索/心动模式/歌单流式条目），封面回填后列表可自动刷新。 */
    val playbackOnlyTracksFlow: StateFlow<List<LibraryTrack>> = playbackOnlyTracks.asStateFlow()

    // 收藏的在线曲目：单独持久化，曲库列表不变，但收藏歌单/详情页能看到。
    private val onlineFavoriteTracks = MutableStateFlow(emptyList<LibraryTrack>())
    val onlineFavorites: StateFlow<List<LibraryTrack>> = onlineFavoriteTracks.asStateFlow()

    fun putPlaybackOnlyTrack(track: LibraryTrack) {
        val favorite = track.id in favorites
        val patched = if (favorite) track.copy(metadataJson = favoriteJson(track.metadataJson, true)) else track
        synchronized(playbackOnlyLock) {
            val next = playbackOnlyTracks.value.filterNot { it.id == track.id } + patched
            playbackOnlyById = next.associateBy { it.id }
            playbackOnlyTracks.value = next
        }
        if (favorite) {
            onlineFavoriteTracks.update { list ->
                if (list.any { it.id == track.id }) list.filterNot { it.id == track.id } + patched else list
            }
        }
    }

    /** 批量合并封面回写：一次只发布一轮 StateFlow，避免大歌单逐首刷新拖垮主线程。 */
    fun putPlaybackOnlyTracks(tracks: List<LibraryTrack>) {
        if (tracks.isEmpty()) return
        val updates = tracks.associateBy { it.id }
        synchronized(playbackOnlyLock) {
            val existing = playbackOnlyTracks.value
            val existingIds = existing.mapTo(HashSet(existing.size)) { it.id }
            val next = existing.map { current ->
                val update = updates[current.id] ?: return@map current
                if (current.id in favorites) update.copy(metadataJson = favoriteJson(update.metadataJson, true)) else update
            } + tracks.filterNot { it.id in existingIds }
            playbackOnlyById = next.associateBy { it.id }
            playbackOnlyTracks.value = next
        }
        onlineFavoriteTracks.update { existing ->
            if (existing.isEmpty()) return@update existing
            existing.map { current ->
                val update = updates[current.id] ?: return@map current
                if (current.id in favorites) update.copy(metadataJson = favoriteJson(update.metadataJson, true)) else update
            }
        }
    }

    /** 冷启动恢复队列时随会话落盘的元数据：本地扫描/远端曲库还没就绪也能解析、显示。 */
    private val restoredQueueTracks = MutableStateFlow<Map<String, LibraryTrack>>(emptyMap())
    val restoredQueueTracksFlow: StateFlow<Map<String, LibraryTrack>> = restoredQueueTracks.asStateFlow()

    fun putRestoredQueueTracks(tracks: List<LibraryTrack>) {
        if (tracks.isEmpty()) return
        restoredQueueTracks.update { it + tracks.associateBy { track -> track.id } }
    }

    /** 播放解析用：先查曲库，再查“仅播放”的在线曲目，最后用恢复的队列元数据兜底。 */
    fun trackForPlayback(trackId: String): LibraryTrack? =
        tracksById[trackId]
            ?: playbackOnlyById[trackId]
            ?: restoredQueueTracks.value[trackId]

    // 本地扫描结果、网易云导入和电脑曲库分开存，任何一方变化后重新拼起来。
    @Volatile private var localTracks: List<LibraryTrack> = emptyList()
    @Volatile private var importedTracks: List<LibraryTrack> = emptyList()
    @Volatile private var remoteTracks: List<LibraryTrack> = emptyList()

    private val _playlists = MutableStateFlow(emptyList<LibraryPlaylist>())
    val playlists: StateFlow<List<LibraryPlaylist>> = _playlists.asStateFlow()

    private val _albums = MutableStateFlow(emptyList<LibraryAlbum>())
    val albums: StateFlow<List<LibraryAlbum>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow(emptyList<LibraryArtist>())
    val artists: StateFlow<List<LibraryArtist>> = _artists.asStateFlow()

    private val _genres = MutableStateFlow(emptyList<LibraryGenre>())
    val genres: StateFlow<List<LibraryGenre>> = _genres.asStateFlow()

    private val _composers = MutableStateFlow(emptyList<LibraryComposer>())
    val composers: StateFlow<List<LibraryComposer>> = _composers.asStateFlow()

    private val _moodRadioEligibleTrackIds = MutableStateFlow(emptySet<String>())
    val moodRadioEligibleTrackIds: StateFlow<Set<String>> = _moodRadioEligibleTrackIds.asStateFlow()

    private val _analysisAvailable = MutableStateFlow(true)
    val analysisAvailable: StateFlow<Boolean> = _analysisAvailable.asStateFlow()

    private val _libraryAnalysisEnabled = MutableStateFlow(false)
    val libraryAnalysisEnabled: StateFlow<Boolean> = _libraryAnalysisEnabled.asStateFlow()

    private val _lastListenedAt = MutableStateFlow(emptyMap<String, Long>())
    val lastListenedAt: StateFlow<Map<String, Long>> = _lastListenedAt.asStateFlow()

    private val _artistArtworkKeys = MutableStateFlow(emptyMap<String, String>())
    val artistArtworkKeys: StateFlow<Map<String, String>> = _artistArtworkKeys.asStateFlow()

    private val _artworkPaths = MutableStateFlow(emptyMap<String, String>())
    val artworkPaths: StateFlow<Map<String, String>> = _artworkPaths.asStateFlow()

    private val _dailyTrackListeningStats = MutableStateFlow(emptyList<DailyTrackListeningStat>())
    val dailyTrackListeningStats: StateFlow<List<DailyTrackListeningStat>> = _dailyTrackListeningStats.asStateFlow()

    private val _dailyPlaybackAttemptStats = MutableStateFlow(emptyList<DailyPlaybackAttemptStat>())
    val dailyPlaybackAttemptStats: StateFlow<List<DailyPlaybackAttemptStat>> = _dailyPlaybackAttemptStats.asStateFlow()

    private val analyses = ConcurrentHashMap<String, TrackAnalysis>()
    val activeAnalyses: Map<String, TrackAnalysis> get() = analyses.toMap()

    private val providerLyricsCache = ProviderLyricsCache()
    fun providerLyrics(trackId: String): Flow<String?> =
        providerLyricsCache.providerLyrics(trackId)

    fun providerLyricsSource(trackId: String): Flow<String?> =
        providerLyricsCache.providerLyricsSource(trackId)

    fun currentProviderLyrics(trackId: String): String? =
        providerLyricsCache.currentProviderLyrics(trackId)

    fun currentProviderLyricsSource(trackId: String): String? =
        providerLyricsCache.currentProviderLyricsSource(trackId)

    fun currentProviderLyricsBaseSource(trackId: String): String? =
        providerLyricsCache.currentProviderLyricsBaseSource(trackId)

    fun restoreAlignedLyrics(trackId: String): Boolean =
        providerLyricsCache.restoreAligned(trackId)

    fun clearProviderLyrics(trackId: String) =
        providerLyricsCache.clearTrack(trackId)

    private val favorites: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()
    private val _favoriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val favoriteOverrides: StateFlow<Map<String, Boolean>> = _favoriteOverrides.asStateFlow()
    fun isFavorite(trackId: String): Boolean = favorites.contains(trackId)

    private val hidden: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _hiddenTracks = MutableStateFlow(emptyList<LibraryTrack>())
    val hiddenTracks: StateFlow<List<LibraryTrack>> = _hiddenTracks.asStateFlow()

    // 启动读盘在后台完成：UI 先用空列表/骨架渲染，主线程不再解析三份 JSON。
    private val initialStateLoaded = java.util.concurrent.atomic.AtomicBoolean(false)
    private val initialStateSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val stateSnapshotLock = Any()
    private val publishStateLock = Any()
    private val stateWriteMutex = Mutex()
    private val listeningMutex = Mutex()
    private val analysesWriteMutex = Mutex()
    private val statePersistVersion = AtomicLong(0L)
    private val statePersistedVersion = AtomicLong(0L)
    @Volatile private var persistBeforeStateLoad = false

    /** 依赖曲库内容的启动流程（首页匹配、电脑曲库恢复）先等这份缓存。 */
    suspend fun awaitLoaded() {
        initialStateSignal.await()
    }

    init {
        AndroidSyncRuntime.backgroundScope.launch { loadInitialState() }
    }

    private suspend fun loadInitialState() {
        var flushPending = false
        try {
            val persisted = runCatching {
                LibrarySyncProtocol.json.decodeFromString<PersistedState>(stateFile.readText())
            }.getOrDefault(PersistedState())
            favorites.addAll(persisted.favorites)
            _favoriteIds.value = favorites.toSet()
            val restoredOnlineFavorites = persisted.onlineFavorites.mapNotNull { it.toLibraryTrackOrNull() }
                .map { restored -> restored.copy(metadataJson = favoriteJson(restored.metadataJson, true)) }
            val restoredOnlinePlaylistTracks = persisted.onlinePlaylistTracks.mapNotNull { it.toLibraryTrackOrNull() }
                .map { restored ->
                    if (restored.id in favorites) restored.copy(metadataJson = favoriteJson(restored.metadataJson, true)) else restored
                }
            val combinedRestoredOnline = (restoredOnlinePlaylistTracks + restoredOnlineFavorites).associateBy { it.id }.values.toList()
            if (restoredOnlineFavorites.isNotEmpty()) {
                val restoredById = restoredOnlineFavorites.associateBy { it.id }
                onlineFavoriteTracks.update { list ->
                    list.filterNot { it.id in restoredById } + restoredOnlineFavorites
                }
            }
            if (combinedRestoredOnline.isNotEmpty()) {
                val restoredById = combinedRestoredOnline.associateBy { it.id }
                synchronized(playbackOnlyLock) {
                    val next = playbackOnlyTracks.value.filterNot { it.id in restoredById } + combinedRestoredOnline
                    playbackOnlyById = next.associateBy { it.id }
                    playbackOnlyTracks.value = next
                }
            }
            hidden.addAll(persisted.hidden)
            _artworkPaths.update { persisted.artworkPaths + it }
            _artistArtworkKeys.update { persisted.artistArtworkKeys + it }
            if (_playlists.value.isEmpty() && persisted.playlists.isNotEmpty()) {
                _playlists.value = persisted.playlists.map { playlist ->
                    LibraryPlaylist(
                        id = playlist.id,
                        name = playlist.name,
                        trackIds = playlist.trackIds,
                        createdAt = playlist.createdAt,
                        updatedAt = playlist.updatedAt,
                        sortTitle = playlist.name,
                        metadataJson = playlist.metadataJson,
                    )
                }
            }
            runCatching {
                LibrarySyncProtocol.json.decodeFromString<PersistedListening>(listeningFile.readText())
            }.getOrNull()?.let { persistedListening ->
                listeningMutex.withLock {
                    persistedListening.tracks.forEach { trackDayStats.putIfAbsent(it.localDate to it.trackId, it) }
                    persistedListening.attempts.forEach { attemptDayStats.putIfAbsent(it.localDate, it) }
                    _dailyTrackListeningStats.value = trackDayStats.values.sortedBy { it.localDate }
                    _dailyPlaybackAttemptStats.value = attemptDayStats.values.sortedBy { it.localDate }
                    if (_recentlyPlayed.value.isEmpty()) _recentlyPlayed.value = persistedListening.recent
                    if (_listeningFootprints.value.isEmpty()) _listeningFootprints.value = persistedListening.footprints
                }
            }
            runCatching {
                LibrarySyncProtocol.json.decodeFromString<List<PersistedTrackAnalysis>>(analysesFile.readText())
            }.getOrDefault(emptyList()).forEach { item ->
                if (item.trackId.isNotBlank()) {
                    analyses[item.trackId] = TrackAnalysis(item.loudnessLufs, item.truePeak)
                }
            }
            importedTracks = runCatching {
                LibrarySyncProtocol.json.decodeFromString<List<ImportedTrackData>>(importedFile.readText())
            }.getOrDefault(emptyList()).map { track ->
                LibraryTrack(
                    id = track.id,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    albumId = track.albumId,
                    metadataJson = track.metadataJson,
                    artworkPath = track.artworkPath,
                    audioPath = track.audioPath,
                    createdAt = track.createdAt,
                    updatedAt = track.createdAt,
                    sortTitle = track.title,
                    sortArtists = track.artists,
                )
            }.filter { track -> track.audioPath?.let { File(it).isFile } == true }
            // 收藏标记是发布时写进每首曲目元数据的：读盘期间如果已有扫描/恢复先发布过，
            // 这里必须重发一次，否则那批曲目会一直显示成未收藏（收藏页缺歌）。
            publishTracksSync()
        } finally {
            // 解析失败也必须放行等待方，否则队列恢复会永远挂着。
            synchronized(stateSnapshotLock) {
                initialStateLoaded.set(true)
                flushPending = persistBeforeStateLoad
                persistBeforeStateLoad = false
            }
            initialStateSignal.complete(Unit)
        }
        if (flushPending) runCatching { persistState() }
        listeningMutex.withLock {
            if (listeningDirty) runCatching { persistListening(System.currentTimeMillis()) }
        }
    }

    /** 网易云导入的曲目；持久化在文件里，重启后自动回到曲库。 */
    fun importedTracksSnapshot(): List<LibraryTrack> = importedTracks

    suspend fun setImportedTracks(tracks: List<LibraryTrack>) {
        initialStateSignal.await()
        importedTracks = tracks
        withContext(Dispatchers.IO) {
            runCatching {
                importedFile.writeTextAtomically(
                    LibrarySyncProtocol.json.encodeToString(
                        tracks.map { track ->
                            ImportedTrackData(
                                id = track.id,
                                title = track.title,
                                artists = track.artists,
                                album = track.album,
                                albumId = track.albumId,
                                metadataJson = track.metadataJson,
                                artworkPath = track.artworkPath,
                                audioPath = track.audioPath,
                                createdAt = track.createdAt,
                            )
                        },
                    ),
                )
            }
        }
        publishTracks()
    }

    private suspend fun persistState() {
        val snapshot: Pair<Long, PersistedState> = synchronized(stateSnapshotLock) {
            if (!initialStateLoaded.get()) {
                // 缓存还没读完就落盘，会把空/半装状态覆盖磁盘；读完后再补写。
                persistBeforeStateLoad = true
                return
            }
            val version = statePersistVersion.incrementAndGet()
            val playlistTrackIds = _playlists.value.flatMapTo(HashSet()) { it.trackIds }
            val persistedOnlinePlaylistTracks = playbackOnlyTracks.value
                .filter { it.id in playlistTrackIds && !isFavorite(it.id) }
                .map { it.toImportedData() }
            val state = PersistedState(
                favorites = favorites.toList(),
                hidden = hidden.toList(),
                onlineFavorites = onlineFavoriteTracks.value.map { it.toImportedData() },
                onlinePlaylistTracks = persistedOnlinePlaylistTracks,
                artworkPaths = _artworkPaths.value,
                artistArtworkKeys = _artistArtworkKeys.value,
                playlists = _playlists.value.map { playlist ->
                    PersistedPlaylist(
                        id = playlist.id,
                        name = playlist.name,
                        trackIds = playlist.trackIds,
                        createdAt = playlist.createdAt,
                        updatedAt = playlist.updatedAt,
                        metadataJson = playlist.metadataJson,
                    )
                },
            )
            version to state
        }
        val (version, state) = snapshot
        withContext(Dispatchers.IO) {
            stateWriteMutex.withLock {
                if (version <= statePersistedVersion.get()) return@withLock
                statePersistedVersion.set(version)
                runCatching { stateFile.writeTextAtomically(LibrarySyncProtocol.json.encodeToString(state)) }
            }
        }
    }

    private suspend fun publishTracks(rebuildEntities: Boolean = true) {
        withContext(Dispatchers.Default) {
            publishTracksSync(rebuildEntities)
        }
    }

    private fun publishTracksSync(rebuildEntities: Boolean = true) {
        synchronized(publishStateLock) {
            val merged = localTracks + importedTracks + remoteTracks
            _hiddenTracks.value = merged.filter { it.id in hidden }
            val visible = merged.filterNot { it.id in hidden }
            val published = if (favorites.isEmpty()) {
                visible
            } else {
                visible.map { track ->
                    if (track.id in favorites) track.copy(metadataJson = favoriteJson(track.metadataJson, true)) else track
                }
            }
            tracksById = published.associateBy { it.id }
            _tracks.value = published
            _analysisAvailable.value = true
            _moodRadioEligibleTrackIds.value = (localTracks + importedTracks).map { it.id }.toSet() - hidden
            if (rebuildEntities) rebuildEntityLists(published)
        }
    }

    /**
     * 隐藏/取消隐藏歌曲：隐藏后从所有可见列表、歌单和队列里消失，
     * 只在设置的“已隐藏歌曲”里能找到。
     */
    suspend fun setHidden(trackId: String, hide: Boolean) = withContext(Dispatchers.IO) {
        if (hide) hidden.add(trackId) else hidden.remove(trackId)
        if (hide) {
            _playlists.update { list ->
                list.map { playlist ->
                    if (trackId in playlist.trackIds) playlist.copy(trackIds = playlist.trackIds - trackId) else playlist
                }
            }
        }
        persistState()
        publishTracks()
    }

    /** 艺术家/专辑/流派/作曲家都从曲目元数据派生，本地和电脑曲目走同一条路（单次 JSON 解析）。 */
    private fun rebuildEntityLists(tracks: List<LibraryTrack>) {
        val artworkPaths = _artworkPaths.value
        val artistsSeen = mutableMapOf<String, LibraryArtist>()
        val albumsSeen = mutableMapOf<String, LibraryAlbum>()
        val composersSeen = mutableMapOf<String, LibraryComposer>()
        val genresSeen = mutableMapOf<String, LibraryGenre>()
        val genreCounts = mutableMapOf<String, Int>()
        for (track in tracks) {
            val root = runCatching { LibrarySyncProtocol.json.parseToJsonElement(track.rawMetadataJson()) as? JsonObject }.getOrNull()
                ?: continue
            (root["artists"] as? JsonArray)?.forEach { entry ->
                val obj = entry as? JsonObject ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
                val explicitSortName = obj["sort_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["normalization_key"]?.jsonPrimitive?.contentOrNull
                val explicitCreatedAt = obj["created_at"]?.jsonPrimitive?.contentOrNull
                val artworkKey = obj["artwork_key"]?.jsonPrimitive?.contentOrNull
                    ?: obj["artwork_key_local"]?.jsonPrimitive?.contentOrNull
                val artworkPath = resolveEntityArtwork(artworkKey, id, artworkPaths, track.artworkPath)
                val existing = artistsSeen[id]
                artistsSeen[id] = LibraryArtist(
                    id = id,
                    name = name,
                    sortName = explicitSortName ?: existing?.sortName ?: name,
                    createdAt = explicitCreatedAt ?: existing?.createdAt ?: track.createdAt,
                    artworkPath = artworkPath ?: existing?.artworkPath,
                )
            }
            (root["album"] as? JsonObject)?.let { albumObj ->
                val albumId = albumObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                val title = albumObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (albumId != null && title.isNotBlank()) {
                    val sortTitle = albumObj["sort_title"]?.jsonPrimitive?.contentOrNull ?: title
                    val year = albumObj["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val copyright = albumObj["copyright"]?.jsonPrimitive?.contentOrNull ?: ""
                    val createdAt = albumObj["created_at"]?.jsonPrimitive?.contentOrNull ?: track.createdAt
                    val artworkKey = albumObj["artwork_key"]?.jsonPrimitive?.contentOrNull
                    val artworkPath = resolveEntityArtwork(artworkKey, albumId, artworkPaths, track.artworkPath)
                    var artist = ""
                    var sortArtist = ""
                    var explicitSortArtist = false
                    val albumArtists = root["album_artists"] as? JsonArray
                    if (!albumArtists.isNullOrEmpty()) {
                        val first = albumArtists.firstOrNull { it is JsonObject } as? JsonObject
                        artist = first?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val explicitSortName = first?.get("sort_name")?.jsonPrimitive?.contentOrNull
                        sortArtist = explicitSortName ?: artist
                        explicitSortArtist = explicitSortName != null
                    }
                    if (artist.isBlank()) {
                        val firstTrackArtist = (root["artists"] as? JsonArray)?.firstOrNull { it is JsonObject } as? JsonObject
                        artist = firstTrackArtist?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val explicitSortName = firstTrackArtist?.get("sort_name")?.jsonPrimitive?.contentOrNull
                        sortArtist = explicitSortName ?: artist
                        explicitSortArtist = explicitSortName != null
                    }
                    val existing = albumsSeen[albumId]
                    albumsSeen[albumId] = LibraryAlbum(
                        id = albumId,
                        title = existing?.title?.takeIf { it.isNotBlank() } ?: title,
                        sortTitle = existing?.sortTitle?.takeIf { it.isNotBlank() } ?: sortTitle,
                        sortArtist = if (explicitSortArtist) sortArtist else existing?.sortArtist ?: sortArtist,
                        artist = existing?.artist?.takeIf { it.isNotBlank() } ?: artist,
                        artworkPath = artworkPath ?: existing?.artworkPath,
                        createdAt = existing?.createdAt?.takeIf { it.isNotBlank() } ?: createdAt,
                        year = existing?.year?.takeIf { it > 0 } ?: year,
                        copyright = existing?.copyright?.takeIf { it.isNotBlank() } ?: copyright,
                    )
                }
            }
            (root["composers"] as? JsonArray)?.forEach { entry ->
                val obj = entry as? JsonObject ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
                val explicitSortName = obj["sort_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["normalization_key"]?.jsonPrimitive?.contentOrNull
                val explicitCreatedAt = obj["created_at"]?.jsonPrimitive?.contentOrNull
                val artworkKey = obj["artwork_key"]?.jsonPrimitive?.contentOrNull
                val artworkPath = resolveEntityArtwork(artworkKey, id, artworkPaths, track.artworkPath)
                val existing = composersSeen[id]
                composersSeen[id] = LibraryComposer(
                    id = id,
                    name = name,
                    sortName = explicitSortName ?: existing?.sortName ?: name,
                    createdAt = explicitCreatedAt ?: existing?.createdAt ?: track.createdAt,
                    artworkPath = artworkPath ?: existing?.artworkPath,
                )
            }
            val trackGenreIds = mutableSetOf<String>()
            (root["genres"] as? JsonArray)?.forEach { entry ->
                val obj = entry as? JsonObject ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
                trackGenreIds.add(id)
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
                val explicitSortName = obj["sort_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["normalization_key"]?.jsonPrimitive?.contentOrNull
                val explicitCreatedAt = obj["created_at"]?.jsonPrimitive?.contentOrNull
                val existing = genresSeen[id]
                genresSeen[id] = LibraryGenre(
                    id = id,
                    name = name,
                    sortName = explicitSortName ?: existing?.sortName ?: name,
                    createdAt = explicitCreatedAt ?: existing?.createdAt ?: track.createdAt,
                )
            }
            trackGenreIds.forEach { gid -> genreCounts[gid] = (genreCounts[gid] ?: 0) + 1 }
        }
        _artists.value = artistsSeen.values.toList()
        _albums.value = albumsSeen.values.toList()
        _genres.value = genresSeen.values.map { genre -> genre.copy(trackCount = genreCounts[genre.id] ?: 0) }
        _composers.value = composersSeen.values.toList()
    }

    private fun LibraryTrack.rawMetadataJson(): String = metadataJson

    private fun resolveEntityArtwork(
        artworkKey: String?,
        entityId: String,
        artworkPaths: Map<String, String>,
        fallbackTrackArtwork: String?,
    ): String? {
        if (artworkKey != null) {
            val direct = artworkPaths[artworkKey]
                ?: artworkPaths["artwork:$entityId"]
                ?: artworkPaths[artworkKey.removePrefix("artwork:")]
            if (direct != null) return direct
        }
        return fallbackTrackArtwork
    }

    private fun favoriteJson(metadataJson: String, favorite: Boolean): String {
        val root = runCatching {
            LibrarySyncProtocol.json.parseToJsonElement(metadataJson) as? JsonObject
        }.getOrNull() ?: return metadataJson
        return JsonObject(root + ("is_favorite" to JsonPrimitive(favorite))).toString()
    }

    /** Commit a local import, replacing all tracks for the plan. */
    suspend fun commitLocalLibrary(import: LocalImport) {
        // 扫描会重建 localTracks：必须等持久化的收藏/隐藏读完，
        // 否则随后的发布会把收藏标记全部刷掉。
        initialStateSignal.await()
        val artworkByKey = import.artworkAssets.associate { it.assetId to it.relativePath }
        if (artworkByKey.isNotEmpty()) {
            _artworkPaths.update { it + artworkByKey }
            persistState()
        }
        localTracks = import.tracks.map { entity ->
            LibraryTrack(
                id = entity.trackId,
                title = entity.title,
                artists = entity.artists,
                album = entity.album,
                albumId = entity.albumId,
                artworkKey = entity.artworkKey,
                playCount = entity.playCount,
                createdAt = entity.createdAt,
                updatedAt = entity.createdAt,
                discNumber = entity.discNumber,
                trackNumber = entity.trackNumber,
                syncOrder = entity.syncOrder,
                metadataJson = entity.rawJson,
                artworkPath = artworkByKey[entity.artworkKey],
                audioPath = import.audioAssets.firstOrNull { it.assetId == "audio:${entity.trackId}" }?.relativePath,
                sortTitle = entity.title,
                sortArtists = entity.artists,
            )
        }
        publishTracks()
    }

    /** 电脑曲库同步完成后写回；本地扫描结果不受影响。 */
    suspend fun setRemoteTracks(tracks: List<LibraryTrack>) {
        remoteTracks = tracks
        publishTracks()
    }

    /** Return playlist entities for a plan. */
    fun playlistEntities(planId: String): List<SyncPlaylistEntity> = _playlists.value.map { playlist ->
        SyncPlaylistEntity(
            planId = planId,
            playlistId = playlist.id,
            name = playlist.name,
            trackIds = playlist.trackIds,
            createdAt = playlist.createdAt,
            updatedAt = playlist.updatedAt,
        )
    }

    /** Find a track by ID. */
    fun trackById(trackId: String): LibraryTrack? = tracksById[trackId]

    /** Tracks eligible for mood radio. */
    fun moodRadioTracks(): List<MoodRadioTrack> =
        tracks.value
            .filter { it.id in moodRadioEligibleTrackIds.value }
            .map { MoodRadioTrack(id = it.id, albumId = it.albumId, primaryArtistId = it.artists, energy = null, danceability = null, brightness = null, tempo = null) }

    /** 收藏落在曲目元数据里（详情页/收藏歌单都读 is_favorite），并落盘保证重启还在。 */
    suspend fun setFavorite(trackId: String, favorite: Boolean) {
        if (favorite) favorites.add(trackId) else favorites.remove(trackId)
        _favoriteIds.value = favorites.toSet()
        _favoriteOverrides.update { it + (trackId to favorite) }
        synchronized(publishStateLock) {
            val next = _tracks.value.map { track ->
                if (track.id == trackId) track.copy(metadataJson = favoriteJson(track.metadataJson, favorite)) else track
            }
            tracksById = next.associateBy { it.id }
            _tracks.value = next
        }
        val online = synchronized(playbackOnlyLock) {
            val existing = playbackOnlyById[trackId]
            if (existing != null) {
                val patched = existing.copy(metadataJson = favoriteJson(existing.metadataJson, favorite))
                val next = playbackOnlyTracks.value.map { if (it.id == trackId) patched else it }
                playbackOnlyById = next.associateBy { it.id }
                playbackOnlyTracks.value = next
                patched
            } else null
        }
        if (online != null) {
            onlineFavoriteTracks.update { list ->
                if (favorite) {
                    list.filterNot { it.id == trackId } + online
                } else {
                    list.filterNot { it.id == trackId }
                }
            }
        } else if (!favorite) {
            onlineFavoriteTracks.update { list -> list.filterNot { it.id == trackId } }
        }
        persistState()
    }

    /** Save provider lyrics for a track. */
    suspend fun saveProviderLyrics(
        trackId: String,
        content: String,
        source: String,
        manual: Boolean = false,
    ) {
        providerLyricsCache.save(trackId, content, source, manual)
    }

    /** 关闭自动对齐后，把本进程内所有已对齐曲目恢复到对齐前的原始歌词。 */
    fun restoreAllAlignedLyrics(): Int =
        providerLyricsCache.restoreAllAligned()

    /** 歌词源优先级或启用项变更后，清理非手动选择的缓存以触发立即重新抓取。 */
    fun clearNonManualProviderLyrics() {
        providerLyricsCache.clearNonManual()
    }

    /** Get cached ITU-R BS.1770 loudness analysis for a track, if already measured. */
    fun analysis(trackId: String): TrackAnalysis? = analyses[trackId]

    /** Persist measured LUFS and true-peak analysis locally for instant future normalization. */
    suspend fun saveTrackAnalysis(trackId: String, analysis: TrackAnalysis) {
        if (trackId.isBlank()) return
        analyses[trackId] = analysis
        withContext(Dispatchers.IO) {
            analysesWriteMutex.withLock {
                val snapshot = analyses.entries.map { (id, value) ->
                    PersistedTrackAnalysis(
                        trackId = id,
                        loudnessLufs = value.loudnessLufs,
                        truePeak = value.truePeak,
                    )
                }
                runCatching {
                    analysesFile.writeTextAtomically(LibrarySyncProtocol.json.encodeToString(snapshot))
                }
            }
        }
    }

    /** 记录播放写入：按天累计每首的收听秒数/播放次数，以及播放尝试统计。 */
    suspend fun recordListening(write: Any) {
        val event = write as? ListeningWrite ?: return
        withContext(Dispatchers.IO) {
            listeningMutex.withLock {
                val now = System.currentTimeMillis()
                when (event) {
                    is ListeningWrite.QualifiedPlay -> {
                        if (event.trackId.isBlank()) return@withLock
                        val date = dateOf(event.occurredAt)
                        val key = date to event.trackId
                        val current = trackDayStats[key]
                        trackDayStats[key] = DailyTrackListeningStat(
                            sourceDeviceId = event.sourceDeviceId,
                            localDate = date,
                            trackId = event.trackId,
                            listenedSeconds = current?.listenedSeconds ?: 0,
                            playCount = (current?.playCount ?: 0) + 1,
                        )
                    }
                    // 近期播放从 3 秒开始记录，与统计口径无关；重复播放会提到最前，最多 100 条。
                    is ListeningWrite.RecentPlay -> {
                        if (event.trackId.isBlank()) return@withLock
                        val track = trackForPlayback(event.trackId)
                        val coverUrl = track?.metadataObject()?.get("coverUrl")
                            ?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                        _recentlyPlayed.update { list ->
                            (
                                listOf(
                                    RecentPlayRecord(
                                        trackId = event.trackId,
                                        playedAt = event.occurredAt,
                                        title = track?.title.orEmpty(),
                                        artists = track?.artists.orEmpty(),
                                        album = track?.album.orEmpty(),
                                        coverUrl = coverUrl,
                                    ),
                                ) + list.filterNot { it.trackId == event.trackId }
                                ).take(100)
                        }
                    }
                    is ListeningWrite.Session -> {
                        val session = event.value
                        if (session.trackId.isBlank() || session.listenedSeconds <= 0) return@withLock
                        val date = dateOf(session.endedAt)
                        val key = date to session.trackId
                        val current = trackDayStats[key]
                        trackDayStats[key] = DailyTrackListeningStat(
                            sourceDeviceId = session.sourceDeviceId,
                            localDate = date,
                            trackId = session.trackId,
                            listenedSeconds = (current?.listenedSeconds ?: 0) + session.listenedSeconds,
                            playCount = current?.playCount ?: 0,
                        )
                    }
                    is ListeningWrite.AttemptFinished -> {
                        val attempt = event.value
                        val endedAt = attempt.endedAt.takeIf { it > 0 } ?: now
                        val date = dateOf(endedAt)
                        val current = attemptDayStats[date] ?: DailyPlaybackAttemptStat(
                            sourceDeviceId = attempt.sourceDeviceId,
                            localDate = date,
                            attempts = 0,
                            completed = 0,
                            skipped = 0,
                            stopped = 0,
                            listenedSeconds = 0,
                        )
                        attemptDayStats[date] = current.copy(
                            attempts = current.attempts + 1,
                            completed = current.completed + if (attempt.endReason == PlaybackEndReason.COMPLETED) 1 else 0,
                            skipped = current.skipped + if (attempt.endReason == PlaybackEndReason.SKIPPED) 1 else 0,
                            stopped = current.stopped + if (attempt.endReason == PlaybackEndReason.STOPPED) 1 else 0,
                            listenedSeconds = current.listenedSeconds + attempt.listenedSeconds.coerceAtLeast(0),
                        )
                        val seconds = attempt.listenedSeconds.coerceAtLeast(0)
                        if (attempt.trackId.isNotBlank() && (seconds >= 3 || attempt.endReason == PlaybackEndReason.COMPLETED)) {
                            val track = trackForPlayback(attempt.trackId)
                            val coverUrl = track?.metadataObject()?.get("coverUrl")
                                ?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                            val startedAt = attempt.startedAt.takeIf { it > 0 }
                                ?: (endedAt - seconds * 1_000L).coerceAtLeast(0L)
                            val entryId = attempt.id.ifBlank { "${attempt.trackId}:$endedAt" }
                            val record = ListeningFootprintRecord(
                                id = entryId,
                                trackId = attempt.trackId,
                                title = track?.title.orEmpty(),
                                artists = track?.artists.orEmpty(),
                                album = track?.album.orEmpty(),
                                artworkPath = track?.artworkPath,
                                coverUrl = coverUrl,
                                startedAt = startedAt,
                                endedAt = endedAt,
                                listenedSeconds = seconds,
                                endReason = attempt.endReason ?: PlaybackEndReason.STOPPED,
                            )
                            _listeningFootprints.update { list ->
                                (listOf(record) + list.filterNot { it.id == entryId }).take(5_000)
                            }
                        }
                    }
                    is ListeningWrite.AttemptStarted -> Unit
                }
                _dailyTrackListeningStats.value = trackDayStats.values.sortedBy { it.localDate }
                _dailyPlaybackAttemptStats.value = attemptDayStats.values.sortedBy { it.localDate }
                listeningDirty = true
                // 写盘节流：尝试结束立即落盘，其余最多 2 秒写一次。
                if (event is ListeningWrite.AttemptFinished || now - lastListeningPersistAt >= 2_000L) {
                    persistListening(now)
                }
            }
        }
    }

    private fun persistListening(nowMs: Long) {
        if (!listeningDirty) return
        // 启动缓存还没读完：内存里已有新统计，等读完后的下一拍再写，别把旧文件盖成半装状态。
        if (!initialStateLoaded.get()) return
        val payload = PersistedListening(
            tracks = trackDayStats.values.toList(),
            attempts = attemptDayStats.values.toList(),
            recent = _recentlyPlayed.value,
            footprints = _listeningFootprints.value,
        )
        runCatching { listeningFile.writeTextAtomically(LibrarySyncProtocol.json.encodeToString(payload)) }
        listeningDirty = false
        lastListeningPersistAt = nowMs
    }

    private fun dateOf(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /** Recover open playback attempts (stubbed — no-op). */
    suspend fun recoverOpenPlaybackAttempts(nowMs: Long) {}

    /** Cleanup old listening records (stubbed — no-op). */
    suspend fun cleanupListening(cutoffMs: Long) {}

    /** Stage artist artwork: hash → filesDir 相对路径。 */
    suspend fun stageArtistArtwork(artwork: StagedArtistArtwork) {
        _artworkPaths.update { it + (artwork.sha256 to artwork.relativePath) }
        persistState()
    }

    /** Queue an artist artwork mutation. */
    suspend fun queueArtistArtworkMutation(mutation: ArtistMutation) {
        when (mutation.operation) {
            ArtistArtworkOperation.SET_ARTWORK -> {
                val sha = mutation.payload?.sha256?.takeIf { it.isNotBlank() } ?: return
                _artistArtworkKeys.update { it + (mutation.artistId to sha) }
            }
            ArtistArtworkOperation.REMOVE_ARTWORK -> {
                _artistArtworkKeys.update { it - mutation.artistId }
            }
        }
        persistState()
    }

    /** Create a local playlist. */
    suspend fun createLocalPlaylist(
        mutation: PlaylistMutation,
        artwork: StagedPlaylistArtwork?,
        artworkMutationId: String?,
        initialTrackIds: List<String>,
    ) {
        val timestamp = Instant.ofEpochMilli(mutation.updatedAt).toString()
        val name = mutation.payload.name.orEmpty().trim()
        val artworkKey = artwork?.let { staged ->
            _artworkPaths.update { it + (staged.sha256 to staged.relativePath) }
            staged.sha256
        }
        val playlist = LibraryPlaylist(
            id = mutation.playlistId,
            name = name,
            artworkKey = artworkKey,
            trackIds = initialTrackIds.distinct(),
            createdAt = timestamp,
            updatedAt = timestamp,
            sortTitle = name,
            metadataJson = artworkKey?.let(::playlistArtworkJson) ?: "{}",
        )
        _playlists.update { it.filterNot { existing -> existing.id == playlist.id } + playlist }
        persistState()
    }

    suspend fun createSmartPlaylist(
        playlistId: String,
        name: String,
        metadataJson: String,
    ) {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val trimmed = name.trim()
        val playlist = LibraryPlaylist(
            id = playlistId,
            name = trimmed,
            artworkKey = null,
            trackIds = emptyList(),
            createdAt = now,
            updatedAt = now,
            sortTitle = trimmed,
            metadataJson = metadataJson,
        )
        _playlists.update { it.filterNot { existing -> existing.id == playlist.id } + playlist }
        persistState()
    }

    suspend fun updateSmartPlaylist(
        playlistId: String,
        name: String,
        metadataJson: String,
    ) {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val trimmed = name.trim()
        _playlists.update { list ->
            list.map { existing ->
                if (existing.id == playlistId) {
                    existing.copy(
                        name = trimmed.ifBlank { existing.name },
                        sortTitle = trimmed.ifBlank { existing.sortTitle },
                        metadataJson = metadataJson,
                        updatedAt = now,
                    )
                } else {
                    existing
                }
            }
        }
        persistState()
    }

    suspend fun replacePlaylistTracks(
        playlistId: String,
        trackIds: List<String>,
    ) {
        if (playlistId.isBlank()) return
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val sanitized = trackIds.filter(String::isNotBlank)
        _playlists.update { list ->
            list.map { existing ->
                if (existing.id == playlistId) {
                    existing.copy(
                        trackIds = sanitized,
                        updatedAt = now,
                    )
                } else {
                    existing
                }
            }
        }
        persistState()
    }

    data class BackupLibraryPayloads(
        val stateJson: String,
        val listeningJson: String,
    )

    suspend fun exportBackupPayloads(): BackupLibraryPayloads {
        initialStateSignal.await()
        val state = synchronized(stateSnapshotLock) {
            val playlistTrackIds = _playlists.value.flatMapTo(HashSet()) { it.trackIds }
            val persistedOnlinePlaylistTracks = playbackOnlyTracks.value
                .filter { it.id in playlistTrackIds && !isFavorite(it.id) }
                .map { it.toImportedData() }
            PersistedState(
                favorites = favorites.toList(),
                hidden = hidden.toList(),
                onlineFavorites = onlineFavoriteTracks.value.map { it.toImportedData() },
                onlinePlaylistTracks = persistedOnlinePlaylistTracks,
                artworkPaths = _artworkPaths.value,
                artistArtworkKeys = _artistArtworkKeys.value,
                playlists = _playlists.value.map { playlist ->
                    PersistedPlaylist(
                        id = playlist.id,
                        name = playlist.name,
                        trackIds = playlist.trackIds,
                        createdAt = playlist.createdAt,
                        updatedAt = playlist.updatedAt,
                        metadataJson = playlist.metadataJson,
                    )
                },
            )
        }
        val listening = listeningMutex.withLock {
            PersistedListening(
                tracks = trackDayStats.values.toList(),
                attempts = attemptDayStats.values.toList(),
                recent = _recentlyPlayed.value,
                footprints = _listeningFootprints.value,
            )
        }
        return withContext(Dispatchers.Default) {
            BackupLibraryPayloads(
                stateJson = LibrarySyncProtocol.json.encodeToString(state),
                listeningJson = LibrarySyncProtocol.json.encodeToString(listening),
            )
        }
    }

    /** 从备份全量恢复曲库状态（收藏、隐藏、歌单/智能歌单、自定义封面映射、听歌统计）并立刻刷新流。 */
    suspend fun restoreFromBackup(stateJson: String?, listeningJson: String?): Pair<Int, Int> {
        initialStateSignal.await()
        val persistedState = stateJson?.takeIf { it.isNotBlank() }?.let { raw ->
            withContext(Dispatchers.Default) {
                LibrarySyncProtocol.json.decodeFromString<PersistedState>(raw)
            }
        }
        val persistedListening = listeningJson?.takeIf { it.isNotBlank() }?.let { raw ->
            withContext(Dispatchers.Default) {
                LibrarySyncProtocol.json.decodeFromString<PersistedListening>(raw)
            }
        }
        if (persistedState != null) {
            synchronized(stateSnapshotLock) {
                favorites.clear()
                favorites.addAll(persistedState.favorites)
                _favoriteIds.value = favorites.toSet()
                _favoriteOverrides.value = emptyMap()

                hidden.clear()
                hidden.addAll(persistedState.hidden)

                val restoredOnlineFavorites = persistedState.onlineFavorites.mapNotNull { it.toLibraryTrackOrNull() }
                    .map { restored -> restored.copy(metadataJson = favoriteJson(restored.metadataJson, true)) }
                val restoredOnlinePlaylistTracks = persistedState.onlinePlaylistTracks.mapNotNull { it.toLibraryTrackOrNull() }
                    .map { restored ->
                        if (restored.id in favorites) restored.copy(metadataJson = favoriteJson(restored.metadataJson, true)) else restored
                    }
                val combinedOnline = (restoredOnlinePlaylistTracks + restoredOnlineFavorites).associateBy { it.id }.values.toList()
                onlineFavoriteTracks.value = restoredOnlineFavorites
                synchronized(playbackOnlyLock) {
                    playbackOnlyById = combinedOnline.associateBy { it.id }
                    playbackOnlyTracks.value = combinedOnline
                }

                _artworkPaths.value = persistedState.artworkPaths
                _artistArtworkKeys.value = persistedState.artistArtworkKeys
                _playlists.value = persistedState.playlists.map { playlist ->
                    LibraryPlaylist(
                        id = playlist.id,
                        name = playlist.name,
                        trackIds = playlist.trackIds,
                        createdAt = playlist.createdAt,
                        updatedAt = playlist.updatedAt,
                        sortTitle = playlist.name,
                        metadataJson = playlist.metadataJson,
                    )
                }
            }
            persistState()
            publishTracks()
        }
        if (persistedListening != null) {
            listeningMutex.withLock {
                trackDayStats.clear()
                attemptDayStats.clear()
                persistedListening.tracks.forEach { trackDayStats[it.localDate to it.trackId] = it }
                persistedListening.attempts.forEach { attemptDayStats[it.localDate] = it }
                _dailyTrackListeningStats.value = trackDayStats.values.sortedBy { it.localDate }
                _dailyPlaybackAttemptStats.value = attemptDayStats.values.sortedBy { it.localDate }
                _recentlyPlayed.value = persistedListening.recent
                _listeningFootprints.value = persistedListening.footprints
                listeningDirty = true
                withContext(Dispatchers.IO) {
                    persistListening(System.currentTimeMillis())
                }
            }
        }
        return _playlists.value.size to favorites.size
    }

    /** Apply a playlist mutation to the in-memory list and persist it. */
    suspend fun queuePlaylistMutation(mutation: PlaylistMutation) {
        _playlists.update { list ->
            val existing = list.firstOrNull { it.id == mutation.playlistId }
                ?: defaultFavoritesPlaylist(mutation)
                ?: return@update list
            val payload = mutation.payload
            val timestamp = Instant.ofEpochMilli(mutation.updatedAt).toString()
            val updated: LibraryPlaylist? = when (mutation.operation) {
                PlaylistMutationOperation.CREATE ->
                    existing.copy(name = payload.name?.trim().orEmpty().ifBlank { existing.name }, updatedAt = timestamp)
                PlaylistMutationOperation.ADD_TRACK ->
                    payload.trackId?.takeIf { it.isNotBlank() }?.let { id ->
                        if (id in existing.trackIds) existing else existing.copy(trackIds = existing.trackIds + id, updatedAt = timestamp)
                    } ?: existing
                PlaylistMutationOperation.REMOVE_TRACK ->
                    payload.trackId?.let { id -> existing.copy(trackIds = existing.trackIds - id, updatedAt = timestamp) } ?: existing
                PlaylistMutationOperation.MOVE_TRACK ->
                    payload.trackId?.let { id ->
                        existing.copy(
                            trackIds = movePlaylistTrack(existing.trackIds, id, payload.previousTrackId, payload.nextTrackId),
                            updatedAt = timestamp,
                        )
                    } ?: existing
                PlaylistMutationOperation.UPDATE ->
                    payload.name?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
                        existing.copy(name = name, sortTitle = name, updatedAt = timestamp)
                    } ?: existing
                PlaylistMutationOperation.SET_ARTWORK ->
                    payload.artworkSha256?.takeIf { it.isNotBlank() }?.let { sha ->
                        existing.copy(artworkKey = sha, metadataJson = playlistArtworkJson(sha), updatedAt = timestamp)
                    } ?: existing
                PlaylistMutationOperation.REMOVE_ARTWORK ->
                    existing.copy(artworkKey = null, metadataJson = "{}", updatedAt = timestamp)
                PlaylistMutationOperation.DELETE -> null
            }
            if (updated == null) {
                list.filterNot { it.id == mutation.playlistId }
            } else {
                val found = list.any { it.id == updated.id }
                if (found) list.map { if (it.id == updated.id) updated else it } else list + updated
            }
        }
        persistState()
    }

    /** No failed-local-playlist staging exists in this build. */
    fun discardFailedLocalPlaylist(playlistId: String): Boolean = false

    /** Stage playlist artwork: hash → filesDir 相对路径。 */
    suspend fun stagePlaylistArtwork(artwork: StagedPlaylistArtwork) {
        _artworkPaths.update { it + (artwork.sha256 to artwork.relativePath) }
        persistState()
    }

    /**
     * 搜索本身按当前曲库在内存里过滤（含电脑曲目），这里不再用预生成候选裁剪，
     * 否则流派等只在运行期解析的字段会漏掉。
     */
    suspend fun searchCandidates(text: String): List<LibrarySearchCandidate> = emptyList()

    /** “收藏”是派生歌单，存储里没有实体；改封面时才为它落一条实体记录。 */
    private fun defaultFavoritesPlaylist(mutation: PlaylistMutation): LibraryPlaylist? {
        if (mutation.playlistId != FavoritesPlaylistId) return null
        if (mutation.operation != PlaylistMutationOperation.SET_ARTWORK &&
            mutation.operation != PlaylistMutationOperation.REMOVE_ARTWORK
        ) {
            return null
        }
        val timestamp = Instant.ofEpochMilli(mutation.updatedAt).toString()
        return LibraryPlaylist(
            id = FavoritesPlaylistId,
            name = "",
            createdAt = timestamp,
            updatedAt = timestamp,
            metadataJson = "{}",
        )
    }

    private fun playlistArtworkJson(sha256: String): String =
        "{\"playlist\":{\"artwork_key\":\"$sha256\"}}"

    private fun movePlaylistTrack(
        trackIds: List<String>,
        trackId: String,
        previousTrackId: String?,
        nextTrackId: String?,
    ): List<String> {
        val without = trackIds.filterNot { it == trackId }
        val index = when {
            previousTrackId != null && previousTrackId in without -> without.indexOf(previousTrackId) + 1
            nextTrackId != null && nextTrackId in without -> without.indexOf(nextTrackId)
            else -> without.size
        }
        return without.toMutableList().apply { add(index.coerceIn(0, size), trackId) }
    }
}

/** 进程内歌词缓存：保留对齐前原文以便关闭自动对齐时回退，同时支持切换源后清非手动缓存。 */
internal class ProviderLyricsCache {
    private val lock = Any()
    private val store = ConcurrentHashMap<String, String>()
    private val sources = ConcurrentHashMap<String, String>()
    private val rawStore = ConcurrentHashMap<String, Pair<String, String>>()
    private val manualTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val sourceFlows = ConcurrentHashMap<String, MutableStateFlow<String?>>()

    fun providerLyrics(trackId: String): Flow<String?> =
        synchronized(lock) {
            flows.getOrPut(trackId) { MutableStateFlow(store[trackId]) }
        }

    fun providerLyricsSource(trackId: String): Flow<String?> =
        synchronized(lock) {
            sourceFlows.getOrPut(trackId) { MutableStateFlow(sources[trackId]) }
        }

    fun currentProviderLyrics(trackId: String): String? =
        synchronized(lock) { store[trackId] }

    fun currentProviderLyricsSource(trackId: String): String? =
        synchronized(lock) { sources[trackId] }

    fun currentProviderLyricsBaseSource(trackId: String): String? =
        synchronized(lock) { rawStore[trackId]?.second }

    fun save(trackId: String, content: String, source: String, manual: Boolean = false) {
        synchronized(lock) {
            if (manual) {
                manualTrackIds.add(trackId)
            }
            if (source != "autosync") {
                rawStore[trackId] = content to source
            }
            store[trackId] = content
            sources[trackId] = source
            flows.getOrPut(trackId) { MutableStateFlow(content) }.value = content
            sourceFlows.getOrPut(trackId) { MutableStateFlow(source) }.value = source
        }
    }

    fun restoreAligned(trackId: String): Boolean {
        synchronized(lock) {
            if (sources[trackId] != "autosync") return false
            val raw = rawStore[trackId]
            if (raw != null) {
                store[trackId] = raw.first
                sources[trackId] = raw.second
                flows[trackId]?.value = raw.first
                sourceFlows[trackId]?.value = raw.second
            } else {
                store.remove(trackId)
                sources.remove(trackId)
                flows[trackId]?.value = null
                sourceFlows[trackId]?.value = null
            }
            return true
        }
    }

    fun restoreAllAligned(): Int {
        var restored = 0
        synchronized(lock) {
            val alignedIds = sources.entries
                .filter { it.value == "autosync" }
                .map { it.key }
            for (trackId in alignedIds) {
                val raw = rawStore[trackId]
                if (raw != null) {
                    store[trackId] = raw.first
                    sources[trackId] = raw.second
                    flows[trackId]?.value = raw.first
                    sourceFlows[trackId]?.value = raw.second
                } else {
                    store.remove(trackId)
                    sources.remove(trackId)
                    flows[trackId]?.value = null
                    sourceFlows[trackId]?.value = null
                }
                restored += 1
            }
        }
        return restored
    }

    fun clearTrack(trackId: String) {
        synchronized(lock) {
            manualTrackIds.remove(trackId)
            store.remove(trackId)
            sources.remove(trackId)
            rawStore.remove(trackId)
            flows[trackId]?.value = null
            sourceFlows[trackId]?.value = null
        }
    }

    fun clearNonManual() {
        synchronized(lock) {
            val toClear = (store.keys + flows.keys + rawStore.keys + sourceFlows.keys)
                .distinct()
                .filter { it !in manualTrackIds }
            for (trackId in toClear) {
                store.remove(trackId)
                sources.remove(trackId)
                rawStore.remove(trackId)
                flows[trackId]?.value = null
                sourceFlows[trackId]?.value = null
            }
        }
    }
}

/** ============================================================================
 *  AndroidSyncRuntime replacement — object with same API as deleted SyncRuntime.kt
 * ============================================================================ */

 object AndroidSyncRuntime {
    @Volatile
    private var currentStore: AndroidLibrarySyncStore? = null

    fun initialize(context: Context) {
        if (currentStore != null) return
        synchronized(this) {
            if (currentStore == null) {
                currentStore = AndroidLibrarySyncStore(context.applicationContext)
            }
        }
    }

    /** 进程级后台任务：Service 销毁后仍要完成的落盘/收尾（听歌统计、会话保存、解码器释放）。 */
    val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val syncStore: AndroidLibrarySyncStore
        get() = currentStore
            ?: error("AndroidLibrarySyncStore not initialized. Call AndroidSyncRuntime.initialize(context) first.")
}
