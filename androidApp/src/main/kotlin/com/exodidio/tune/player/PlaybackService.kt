package com.exodidio.tune.player

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.session.MediaSession
import android.media.session.PlaybackState as AndroidMediaPlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import android.util.LruCache
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.exodidio.tune.library.AudioTarget
import com.exodidio.tune.library.resolveAudioTarget
import com.exodidio.tune.lyrics.LyricOffsetStore
import com.exodidio.tune.lyrics.LyricsPreferences
import com.exodidio.tune.lyrics.LyricsSettings
import com.exodidio.tune.lyrics.SystemLyricMode
import com.exodidio.tune.ui.navigation.PlayerLyricLine
import com.exodidio.tune.ui.navigation.parsePlayerLyricsDocument
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.netease.NeteaseRepository

/** Owns Android transport; queue semantics are delegated to sharedLogic. */
class PlaybackService : Service() {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val commandMutex = Mutex()
    // 每次指令进来就自增：正在下载的上一首尽快放弃，连续切歌不必排队等整首下完。
    internal val loadToken = java.util.concurrent.atomic.AtomicInteger(0)
    // 在线流播放状态：用于识别“打开后立刻结束”的失败流。
    // 位置 ticker 在锁外读写这几个字段：用 @Volatile 保证跨线程可见性。
    @Volatile internal var currentStreamSongId: Long? = null
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal var lastPreviewToastTrackId: String? = null
    // 参考 MeiloX：临时失败的原地重试状态，同一首歌连续失败有上限。
    internal var recoveryTrackId: String? = null
    internal var temporaryRecoveryAttempts = 0
    internal var lastRecoveryPositionMs = -1L
    internal var cleanEofRetriedTrackId: String? = null
    internal var cleanEofRetriedPositionMs = -1L
    internal var recoveryJob: Job? = null
    private var currentStreamStartedAt = 0L
    @Volatile internal var lastStreamPositionMs = 0L
    @Volatile internal var lastStreamSongId: Long? = null
    @Volatile internal var pendingRecoverySeek: Pair<String, Long>? = null
    @Volatile internal var lastSeekAtMs = 0L
    @Volatile internal var lastSeekTrackId: String? = null
    @Volatile internal var seekBufferingGraceUntilMs = 0L
    internal var bufferingStallAnchor: BufferingStallAnchor? = null
    internal var pendingStreamResume: PendingStreamResume? = null
    // 点选类指令的代际号：连点十来下时，旧指令轮到自己时已被更新，直接丢弃，
    // 只执行最后点的那一首，避免积压指令依次准备、自动连切。
    private val selectionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val seekGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val restored = CompletableDeferred<Unit>()
    private lateinit var restoreJob: Job
    internal val queue = PlaybackQueue()
    @Volatile internal var decoder: FfmpegDecoder? = null
    internal val decoderAccessLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private var pauseSpinDownJob: Job? = null
    @Volatile internal var preparingStartedAt: Pair<String, Long>? = null
    internal var preloadedItem: PlaybackItem? = null
    private lateinit var sessionStore: PlaybackSessionStore
    private lateinit var playbackPreferences: PlaybackPreferences
    private lateinit var equalizerPreferences: EqualizerPreferences
    internal lateinit var normalizationPreferences: NormalizationPreferences
    private lateinit var lyricsPreferences: LyricsPreferences
    internal var lyriconPublisher: LyriconPublisher? = null
    // 系统歌词（媒体通知/独立通知/超级岛）的状态：ticker 与歌词流协程共享，
    // 只在本服务进程内读写，用 @Volatile 保证跨协程可见。
    @Volatile internal var lyricSettings = LyricsSettings()
    @Volatile internal var trackedLyricLines: List<PlayerLyricLine> = emptyList()
    @Volatile internal var trackedNativeLyric: String? = null
    @Volatile internal var trackedLyricTrackId: String? = null
    @Volatile internal var lastLyricLineIndex = -1
    @Volatile internal var lastLyricSurfaceTrackId: String? = null
    @Volatile internal var lastLyricPlaying = false
    @Volatile internal var currentLyricText = ""
    @Volatile internal var currentNextLyricText = ""
    @Volatile internal var currentLyricOffsetMs = 0L
    @Volatile internal var mediaNotificationStyle = MediaNotificationStyle.Default
    private lateinit var listeningTracker: ListeningTracker
    private val listeningWrites = Channel<ListeningWrite>(64)
    // 命令锁内只投递状态；Binder/全库索引等发布动作交给锁外单消费者，合并成最新一次。
    private val queuePublishRequests = Channel<PlaybackQueueSnapshot>(Channel.CONFLATED)
    internal val notificationRequests = Channel<Unit>(Channel.CONFLATED)
    // 连点的上一首/下一首合并处理：锁内只推进队列下标，只对最终曲目启动播放。
    private val pendingSkips = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
    private val skipRequests = Channel<Unit>(Channel.CONFLATED)
    // 连续快速点击时先不启动：等最后一次点击后静默一小段，避免逐首拉直链/歌词。
    @Volatile private var lastSkipAtMs = 0L
    @Volatile private var skipBurstActive = false
    // UI/队列推进立即执行；只有“真正开始播放”这一步走静默窗防抖。
    private var skipStartJob: Job? = null
    @Volatile private var pendingStartPaused = false
    private var lastSessionPositionAtMs = 0L
    internal var moodRefillJob: Job? = null
    private var listeningFadeOutgoing: String? = null
    private var listeningFadeStartedAt = 0L
    private var listeningFadeStartedElapsed = 0L
    private var listeningFadeMaxMs = 0L
    internal var normalizationSettings = NormalizationSettings()
    private var equalizerSettings = EqualizerSettings()
    private var preferencesJob: Job? = null
    private var moodRadioJob: Job? = null
    internal var moodRadioSeedId: String? = null
    internal var moodRadioLastRefillAttempt: Pair<String?, Int>? = null
    private var resumeOnFocusGain = false
    @Volatile internal var isForegroundStarted = false
    private var isDucked = false
    private lateinit var audioManager: AudioManager
    internal lateinit var mediaSession: MediaSession
    private lateinit var focusRequest: AudioFocusRequest
    internal val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (audioBecomingNoisyRequiresPause(intent.action)) dispatch(ActionPause)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        activeInstance = this
        AndroidPlaybackRuntime.initialize(applicationContext, AndroidSyncRuntime.syncStore)
        LyricOffsetStore.initialize(applicationContext)
        com.exodidio.tune.netease.NeteaseWifiAutoCacheManager.initialize(applicationContext)
        listeningTracker = ListeningTracker("local") { UUID.randomUUID().toString() }
        // 写盘协程挂在进程级 scope：Service 销毁时不能把它一起取消，否则最后一条听歌记录会丢。
        AndroidSyncRuntime.backgroundScope.launch {
            for (write in listeningWrites) {
                AndroidSyncRuntime.syncStore.recordListening(write)
                com.exodidio.tune.netease.NeteaseRepository.reportListeningWrite(write)
            }
        }
        sessionStore = PlaybackSessionStore(applicationContext)
        playbackPreferences = PlaybackPreferences(applicationContext)
        equalizerPreferences = EqualizerPreferences(applicationContext)
        normalizationPreferences = NormalizationPreferences(applicationContext)
        moodRadioJob = scope.launch {
            AndroidSyncRuntime.syncStore.libraryAnalysisEnabled.collectLatest { enabled ->
                if (!enabled) commandMutex.withLock { stopMoodRadio() }
            }
        }
        lyricsPreferences = LyricsPreferences(applicationContext)
        lyriconPublisher = LyriconPublisher(applicationContext).also { publisher ->
            scope.launch { publisher.start() }
        }
        scope.launch {
            lyricsPreferences.settings.collectLatest { settings ->
                val previous = lyricSettings
                lyricSettings = settings
                if (settings.systemLyricMode == SystemLyricMode.Off && previous.systemLyricMode != SystemLyricMode.Off) {
                    currentLyricText = ""
                    currentNextLyricText = ""
                    notificationRequests.trySend(Unit)
                }
                if (!settings.standaloneLyricNotification) cancelStandaloneLyricNotification()
                if (!settings.hyperosSuperIsland) SuperIslandLyrics.stop(applicationContext)
                lastLyricLineIndex = -1
                updateLyricSurfaces()
            }
        }
        // 当前曲目有歌词就解析成时间轴；歌词在 UI 线程抓取后写回进程内曲库，这里持续跟随。
        scope.launch {
            state
                .map { snapshot ->
                    when (snapshot) {
                        is PlaybackState.Playing -> snapshot.item.trackId
                        is PlaybackState.Paused -> snapshot.item.trackId
                        is PlaybackState.Preparing -> snapshot.item.trackId
                        else -> null
                    }
                }
                .distinctUntilChanged()
                .collectLatest { trackId ->
                    trackedLyricTrackId = trackId
                    trackedLyricLines = emptyList()
                    trackedNativeLyric = null
                    lastLyricLineIndex = -1
                    currentLyricOffsetMs = LyricOffsetStore.offsetFor(trackId)
                    if (trackId == null) {
                        clearLyricSurfaces()
                        return@collectLatest
                    }
                    AndroidSyncRuntime.syncStore.providerLyrics(trackId).collect { content ->
                        val lines = content
                            ?.let { parsePlayerLyricsDocument(it).lines }
                            .orEmpty()
                            .filter { it.timestampSeconds != null }
                        trackedLyricLines = lines
                        trackedNativeLyric = buildNativeLyric(lines, currentLyricOffsetMs)
                        lastLyricLineIndex = -1
                        lastLyricSurfaceTrackId = null
                        republishCurrentMetadata()
                    }
                }
        }
        scope.launch {
            LyricOffsetStore.offsets.collect { map ->
                val tid = trackedLyricTrackId
                val nextOffset = if (tid != null) map[tid] ?: 0L else 0L
                if (nextOffset != currentLyricOffsetMs) {
                    currentLyricOffsetMs = nextOffset
                    trackedNativeLyric = buildNativeLyric(trackedLyricLines, nextOffset)
                    lastLyricLineIndex = -1
                    lastLyricSurfaceTrackId = null
                    republishCurrentMetadata()
                    updateLyricSurfaces()
                }
            }
        }
        preferencesJob = scope.launch {
            playbackPreferences.settings.collectLatest { settings ->
                commandMutex.withLock {
                    crossfadeSeconds.value = settings.seconds
                    blendArtworkDuringCrossfade.value = settings.blendArtworkDuringCrossfade
                    gaplessPlayback.value = settings.gaplessPlayback
                    if (!settings.blendArtworkDuringCrossfade) clearArtworkCrossfade()
                    // A preference update must never change a fade already
                    // running, but it does refresh the idle source afterward.
                    if (decoder?.isCrossfading() != true) preloadNext()
                }
            }
        }
        scope.launch {
            playbackPreferences.funFeatures.collectLatest { settings ->
                funFeaturesSettings = settings
                commandMutex.withLock {
                    decoder?.setCassetteEnabled(settings.cassetteMode)
                }
            }
        }
        scope.launch {
            playbackPreferences.mediaNotificationStyle.collectLatest { style ->
                if (mediaNotificationStyle != style) {
                    mediaNotificationStyle = style
                    republishCurrentMetadata()
                    notificationRequests.trySend(Unit)
                }
            }
        }
        scope.launch {
            equalizerPreferences.settings.collectLatest { settings ->
                commandMutex.withLock {
                    equalizerSettings = settings
                    decoder?.setGlobalDspConfig(equalizerDspConfig(settings))
                }
            }
        }
        scope.launch {
            normalizationPreferences.settings.collectLatest { settings ->
                commandMutex.withLock {
                    normalizationSettings = settings
                    refreshNormalizationGains()
                }
            }
        }
        // 在线曲目封面异步补齐后，正在播放的曲目也补上封面（只改状态，不重启播放）。
        scope.launch {
            combine(
                AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow,
                AndroidSyncRuntime.syncStore.tracks,
            ) { _, _ -> Unit }.collectLatest {
                val current = when (val snapshot = state.value) {
                    is PlaybackState.Playing -> snapshot.item
                    is PlaybackState.Paused -> snapshot.item
                    is PlaybackState.Preparing -> snapshot.item
                    else -> null
                } ?: return@collectLatest
                val artwork = AndroidSyncRuntime.syncStore.trackForPlayback(current.trackId)?.artworkPath
                    ?: return@collectLatest
                if (artwork == current.artworkPath) return@collectLatest
                commandMutex.withLock {
                    val now = state.value
                    val item = when (now) {
                        is PlaybackState.Playing -> now.item
                        is PlaybackState.Paused -> now.item
                        is PlaybackState.Preparing -> now.item
                        else -> null
                    } ?: return@withLock
                    if (item.trackId != current.trackId || item.artworkPath == artwork) return@withLock
                    val updated = item.copy(artworkPath = artwork)
                    state.value = when (now) {
                        is PlaybackState.Playing -> now.copy(item = updated)
                        is PlaybackState.Paused -> now.copy(item = updated)
                        is PlaybackState.Preparing -> now.copy(item = updated)
                        else -> now
                    }
                    when (now) {
                        is PlaybackState.Playing -> publishNowPlaying(updated, AndroidMediaPlaybackState.STATE_PLAYING, now.positionMs, now.durationMs)
                        is PlaybackState.Paused -> publishNowPlaying(updated, AndroidMediaPlaybackState.STATE_PAUSED, now.positionMs, now.durationMs)
                        is PlaybackState.Preparing -> publishNowPlaying(updated, AndroidMediaPlaybackState.STATE_BUFFERING, 0L, 0L)
                        else -> Unit
                    }
                    updateNotification()
                }
            }
        }
        // 只给正在播放/准备中的在线曲目补文件封面：整队列不再批量下载封面。
        scope.launch {
            var requestedTrackId: String? = null
            state.collect { snapshot ->
                val item = when (snapshot) {
                    is PlaybackState.Playing -> snapshot.item
                    is PlaybackState.Paused -> snapshot.item
                    is PlaybackState.Preparing -> snapshot.item
                    else -> null
                } ?: return@collect
                if (item.trackId == requestedTrackId) return@collect
                requestedTrackId = item.trackId
                if (item.trackId.startsWith("netease:")) {
                    val hasValidFile = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val path = item.artworkPath
                        !path.isNullOrBlank() && java.io.File(path).isFile
                    }
                    if (!hasValidFile) {
                        NeteaseRepository.ensurePlaybackCover(item.trackId)
                    }
                }
            }
        }
        audioManager = getSystemService(AudioManager::class.java)
        registerNoisyAudioReceiver()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (audioFocusChangeAction(change)) {
                    AudioFocusChangeAction.Pause -> dispatch(ActionPause)
                    AudioFocusChangeAction.PauseAndResumeOnGain -> dispatch(ActionPauseForTransientFocusLoss)
                    AudioFocusChangeAction.Duck -> dispatch(ActionDuck)
                    AudioFocusChangeAction.Restore -> dispatch(ActionRestoreFocus)
                    AudioFocusChangeAction.Ignore -> Unit
                }
            }
            .build()
        mediaSession = MediaSession(this, "TunePlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { dispatch(ActionResume) }
                override fun onPause() { dispatch(ActionPause) }
                override fun onSkipToNext() { dispatch(ActionNext) }
                override fun onSkipToPrevious() { dispatch(ActionPrevious) }
                override fun onSkipToQueueItem(id: Long) {
                    queue.snapshot().activeTrackIds.getOrNull(id.toInt())?.let { dispatch(ActionSelect, trackIds = listOf(it)) }
                }
                override fun onSeekTo(pos: Long) { dispatch(ActionSeek, positionMs = pos) }
                override fun onStop() { dispatch(ActionStop) }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        ActionToggleFavorite -> toggleCurrentFavoriteAsync()
                        ActionCycleRepeat -> dispatch(ActionCycleRepeat)
                    }
                }
            })
            setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            isActive = false
        }
        AndroidPlaybackSession.publish(mediaSession.sessionToken)
        // 发布副作用与位置刷新都走锁外：命令锁只管内存状态机，不再被 Binder/全库索引占着。
        scope.launch {
            for (snapshot in queuePublishRequests) publishQueueSideEffects(snapshot)
        }
        scope.launch {
            for (ignored in notificationRequests) updateNotification()
        }
        scope.launch {
            for (ignored in skipRequests) runCatching { advanceSkipsAndScheduleStart() }
        }
        scope.launch {
            val store = AndroidSyncRuntime.syncStore
            combine(
                state.map { snapshot ->
                    when (snapshot) {
                        is PlaybackState.Playing -> listOf("P", snapshot.item.trackId, snapshot.item.title, snapshot.item.artist, snapshot.item.artworkPath)
                        is PlaybackState.Paused -> listOf("S", snapshot.item.trackId, snapshot.item.title, snapshot.item.artist, snapshot.item.artworkPath)
                        is PlaybackState.Preparing -> listOf("B", snapshot.item.trackId, snapshot.item.title, snapshot.item.artist, snapshot.item.artworkPath)
                        else -> listOf("I")
                    }
                }.distinctUntilChanged(),
                store.favoriteIds,
                store.favoriteOverrides,
                NeteaseRepository.likedSongIds,
            ) { _, _, _, _ -> Unit }
                .collectLatest {
                    updateNowPlayingTransportState()
                    notificationRequests.trySend(Unit)
                    runCatching { TuneAppWidgetProvider.pushUpdate(applicationContext) }
                    runCatching {
                        android.service.quicksettings.TileService.requestListeningState(
                            applicationContext,
                            android.content.ComponentName(applicationContext, PlaybackQuickTileService::class.java),
                        )
                    }
                }
        }
        scope.launch {
            while (true) {
                delay(200)
                tickPlaybackPosition()
                updateLyricSurfaces()
                syncLyricon()
            }
        }
        restoreJob = scope.launch {
            // 曲库读盘在后台：等它完成再判断可用曲目，否则冷启动会把整条恢复队列判成不可用。
            runCatching { AndroidSyncRuntime.syncStore.awaitLoaded() }
            try {
                sessionStore.load()?.let { session ->
                    val saved = session.queue
                    // 流式/搜索曲目只存在内存：先把随会话落盘的曲目放回曲库，恢复时才不会被过滤掉。
                    val restoredQueueTracks = PlaybackQueueTracksStore.read(applicationContext)
                    if (restoredQueueTracks.isNotEmpty()) {
                        // 全部曲目先放进“恢复队列元数据”，曲库/扫描没就绪也能解析；
                        // 流式曲目额外放回仅播放表，保证封面回写与可用性判断照常工作。
                        AndroidSyncRuntime.syncStore.putRestoredQueueTracks(restoredQueueTracks)
                        val streaming = restoredQueueTracks.filter { it.audioPath?.startsWith("netease://") == true }
                        if (streaming.isNotEmpty()) {
                            AndroidSyncRuntime.syncStore.putPlaybackOnlyTracks(streaming)
                        }
                    }
                    val availableTrackIds = AndroidPlaybackRuntime.availableTrackIds(saved.originalTrackIds)
                    commandMutex.withLock {
                        if (availableTrackIds.isEmpty()) {
                            clearRestoredSession()
                            return@withLock
                        }
                        queue.restore(queueForAvailableTracks(saved, availableTrackIds))
                        restoreCurrent(session.positionMs)
                    }
                }
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    Log.w(PlaybackLogTag, "Unable to restore playback session; clearing it", error)
                    commandMutex.withLock { clearRestoredSession() }
                }
            } finally {
                restored.complete(Unit)
            }
        }
        scope.launch {
            while (true) {
                delay(200)
                // 歌曲结束分支要查 Media3 缓存跨度（SQLite）：在锁外先取好，锁内只做状态变更。
                val clampSample = sampleStreamClampIfFinished()
                commandMutex.withLock {
                    val playing = state.value as? PlaybackState.Playing
                    if (playing != null && !playing.isBuffering) enqueueListening(listeningTracker.tick(
                        playing.positionMs, playing.durationMs, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                    ))
                    // 参考 MeiloX：持续播够 2 秒才算恢复，清零临时重试计数。
                    if (playing != null && !playing.isBuffering && playing.positionMs >= HealthyPlaybackMs &&
                        playing.item.trackId == recoveryTrackId
                    ) {
                        temporaryRecoveryAttempts = 0
                    }
                    consumeNativeTransition()
                    if (listeningFadeOutgoing != null && decoder?.isCrossfading() != true) finishListeningCrossfade()
                    if (decoder?.isCrossfading() != true) clearArtworkCrossfade()
                    if (audioOutputDisconnectRequiresRecovery(decoder?.isOutputDisconnected() == true)) {
                        recoverAfterOutputDisconnect()
                    } else if (maybeStartCrossfade()) {
                        consumeNativeTransition()
                    } else if (decoder?.isFinished() == true && state.value is PlaybackState.Playing) {
                        val current = state.value as PlaybackState.Playing
                        val ab = abLoopState.value
                        if (ab.isActive && ab.trackId == current.item.trackId) {
                            seekCurrent(ab.pointA!!)
                            decoder?.play()
                        } else if (sleepTimerState.value.afterCurrentTrack) {
                            currentStreamSongId = null
                            sleepTimerState.value = SleepTimerState()
                            stopAtCurrentTrack(PlaybackEndReason.COMPLETED)
                            publishQueue()
                        } else {
                        val streamId = currentStreamSongId
                        val proxy = com.exodidio.tune.netease.NeteaseStreamProxy
                        // 优先用状态里的时长（在线歌来自接口），避免解码器流式估算跳变影响判断。
                        val duration = current.durationMs.takeIf { it > 0L } ?: decoder?.durationMs() ?: 0L
                        val elapsedMs = SystemClock.elapsedRealtime() - maxOf(
                            currentStreamStartedAt,
                            if (lastSeekTrackId == current.item.trackId) lastSeekAtMs else 0L,
                        )
                        val actualPosMs = streamId?.let { songId ->
                            val raw = clampSample?.takeIf { it.songId == songId }?.positionMs
                                ?: maxOf(
                                    current.positionMs,
                                    decoder?.positionMs() ?: 0L,
                                    if (lastStreamSongId == songId) lastStreamPositionMs else 0L,
                                )
                            clampSeekPosition(raw, duration)
                        } ?: current.positionMs
                        val hasUpstreamError = streamId != null && proxy.hasRecentUpstreamError(streamId)
                        val wasIncomplete = streamId != null && proxy.wasStreamIncomplete(streamId)
                        val isPreview = streamId != null && NeteaseRepository.isPreviewStream(streamId)
                        val alreadyRetriedCleanEof = cleanEofRetriedTrackId == current.item.trackId &&
                            cleanEofRetriedPositionMs >= 0L &&
                            kotlin.math.abs(actualPosMs - cleanEofRetriedPositionMs) < 3_000L
                        val completionAction = if (streamId != null) {
                            classifyStreamCompletion(
                                isPreview = isPreview,
                                positionMs = actualPosMs,
                                durationMs = duration,
                                elapsedSinceStartMs = elapsedMs,
                                hasUpstreamError = hasUpstreamError,
                                wasStreamIncomplete = wasIncomplete,
                                alreadyRetriedCleanEofAtSamePos = alreadyRetriedCleanEof,
                            )
                        } else {
                            StreamCompletionAction.NormalCompletion
                        }
                        if (streamId != null && completionAction != StreamCompletionAction.NormalCompletion) {
                            Log.w(
                                PlaybackLogTag,
                                "NETSTREAM premature stop song=$streamId action=$completionAction " +
                                    "posMs=$actualPosMs duration=$duration err=$hasUpstreamError incomplete=$wasIncomplete",
                            )
                            if (completionAction == StreamCompletionAction.RecoverFromCleanPrematureEof) {
                                cleanEofRetriedTrackId = current.item.trackId
                                cleanEofRetriedPositionMs = actualPosMs
                                proxy.removeSong(streamId)
                                NeteaseRepository.invalidateStreamUrl(streamId)
                            } else if (
                                temporaryRecoveryAttempts >= 1 ||
                                (recoveryTrackId == current.item.trackId &&
                                    lastRecoveryPositionMs >= 0L &&
                                    kotlin.math.abs(actualPosMs - lastRecoveryPositionMs) < 3_000L)
                            ) {
                                // 同一位置反复失败：淘汰可能受损的本地缓存，强迫下一次全量重拉。
                                proxy.removeSong(streamId)
                                NeteaseRepository.invalidateStreamUrl(streamId)
                            }
                            lastRecoveryPositionMs = actualPosMs
                            lastStreamPositionMs = actualPosMs
                            lastStreamSongId = streamId
                            decoder?.seekTo(actualPosMs)
                            decoder?.pause()
                            state.value = PlaybackState.Paused(current.item, actualPosMs, duration)
                            publishNowPlaying(current.item, AndroidMediaPlaybackState.STATE_PAUSED, actualPosMs, duration)
                            notificationRequests.trySend(Unit)
                            currentStreamSongId = null
                            val atEnd = duration > 0L && actualPosMs >= duration - 1_000L
                            if (!atEnd) scheduleTemporaryStreamRecovery(current.item, actualPosMs, duration)
                        } else {
                            if (streamId != null && elapsedMs < 3_000L) {
                                Log.w(PlaybackLogTag, "NETSTREAM ended immediately song=$streamId")
                            }
                            currentStreamSongId = null
                            enqueueBackgroundLoudnessAnalysis(current.item)
                            handleTransition(queue.next(), PlaybackEndReason.COMPLETED)
                            publishQueue()
                        }
                        }
                    } else if (playing != null && playing.isBuffering && currentStreamSongId != null) {
                        val streamId = currentStreamSongId!!
                        val now = SystemClock.elapsedRealtime()
                        val aheadMs = decoder?.bufferedAheadMs() ?: 0L
                        val anchor = nextBufferingStallAnchor(
                            previous = bufferingStallAnchor,
                            trackId = playing.item.trackId,
                            positionMs = playing.positionMs,
                            bufferedAheadMs = aheadMs,
                            nowElapsedMs = now,
                        )
                        bufferingStallAnchor = anchor
                        val proxy = com.exodidio.tune.netease.NeteaseStreamProxy
                        val hasUpstreamError = proxy.hasRecentUpstreamError(streamId)
                        val wasIncomplete = proxy.wasStreamIncomplete(streamId)
                        if (shouldRecoverStalledBuffering(anchor, now, hasUpstreamError, wasIncomplete)) {
                            Log.w(
                                PlaybackLogTag,
                                "NETSTREAM buffering stall song=$streamId posMs=${playing.positionMs} " +
                                    "aheadMs=$aheadMs err=$hasUpstreamError incomplete=$wasIncomplete",
                            )
                            bufferingStallAnchor = null
                            if (hasUpstreamError || wasIncomplete || temporaryRecoveryAttempts >= 1) {
                                proxy.removeSong(streamId)
                                NeteaseRepository.invalidateStreamUrl(streamId)
                            }
                            val actualPosMs = clampSeekPosition(playing.positionMs, playing.durationMs)
                            lastRecoveryPositionMs = actualPosMs
                            lastStreamPositionMs = actualPosMs
                            lastStreamSongId = streamId
                            decoder?.seekTo(actualPosMs)
                            decoder?.pause()
                            state.value = PlaybackState.Paused(playing.item, actualPosMs, playing.durationMs)
                            publishNowPlaying(playing.item, AndroidMediaPlaybackState.STATE_PAUSED, actualPosMs, playing.durationMs)
                            notificationRequests.trySend(Unit)
                            currentStreamSongId = null
                            val atEnd = playing.durationMs > 0L && actualPosMs >= playing.durationMs - 1_000L
                            if (!atEnd) scheduleTemporaryStreamRecovery(playing.item, actualPosMs, playing.durationMs)
                        }
                    } else {
                        bufferingStallAnchor = null
                    }
                    tickSleepTimerLocked()
                    maybeRefillMoodRadio()
                    maybeAppendHeartbeat()
                    maybeAppendRoam()
                    maybeAppendSimilar()
                    // A crossfade occupies both native source slots. Once its
                    // callback retires the outgoing item, populate that slot
                    // with the queue's new immediate successor.
                    if (canPreloadNext(decoder?.isCrossfading() == true)) preloadNext()
                }
            }
        }
    }

    private fun resolveImmediatePlaybackItem(intent: Intent?): PlaybackItem? {
        when (val current = state.value) {
            is PlaybackState.Playing -> return current.item
            is PlaybackState.Paused -> return current.item
            is PlaybackState.Preparing -> return current.item
            else -> Unit
        }
        val action = intent?.action ?: return null
        val targetId = when (action) {
            ActionPlay, ActionShuffle -> {
                val trackIds = intent.getStringArrayExtra(TrackIdsExtra).orEmpty()
                val startIndex = intent.getIntExtra(StartIndexExtra, 0)
                trackIds.getOrNull(startIndex) ?: trackIds.firstOrNull()
            }
            ActionSelect, ActionStartMoodRadio -> intent.getStringExtra(TrackIdExtra)
            else -> queue.snapshot().currentTrackId
        } ?: return null
        val track = AndroidSyncRuntime.syncStore.trackForPlayback(targetId) ?: return null
        return PlaybackItem(
            trackId = track.id,
            title = track.title,
            artist = track.artists,
            audioPath = track.audioPath.orEmpty(),
            artworkPath = track.artworkPath,
            albumId = track.albumId,
            album = track.album,
            trackNumber = track.trackNumber,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action in PlaybackForegroundPromotionActions && !isForegroundStarted) {
            resolveImmediatePlaybackItem(intent)?.let { item ->
                showForeground(item, fromCacheOnly = true)
            }
        }
        if (playbackActionReplacesRestoredQueue(action)) {
            // A tap is more important than reconstructing the prior session. In
            // particular, do not delay its Preparing state (and mini-player)
            // behind DataStore I/O and validation of every saved queue entry.
            restoreJob.cancel()
            restored.complete(Unit)
        }
        when (intent?.action) {
            ActionPlay, ActionShuffle -> dispatch(
                action = intent.action!!,
                trackIds = intent.getStringArrayExtra(TrackIdsExtra).orEmpty().toList(),
                startIndex = intent.getIntExtra(StartIndexExtra, 0),
                heartbeatStart = intent.getBooleanExtra(HeartbeatStartExtra, false),
                roamStart = intent.getBooleanExtra(RoamStartExtra, false),
                similarStart = intent.getBooleanExtra(SimilarStartExtra, false),
            )
            ActionSeek -> dispatch(ActionSeek, positionMs = intent.getLongExtra(PositionMsExtra, 0L))
            ActionSetShuffle -> dispatch(ActionSetShuffle, enabled = intent.getBooleanExtra(EnabledExtra, false))
            ActionSetRepeat -> dispatch(
                ActionSetRepeat,
                repeat = intent.getStringExtra(RepeatModeExtra)?.let { value -> runCatching { RepeatMode.valueOf(value) }.getOrNull() },
            )
            ActionSetCrossfade -> scope.launch {
                playbackPreferences.setCrossfadeSeconds(
                    intent.getIntExtra(CrossfadeSecondsExtra, CrossfadeDisabledSeconds),
                )
            }
            ActionPlayNext, ActionAppend, ActionReorder -> dispatch(
                action = intent.action!!,
                trackIds = intent.getStringArrayExtra(TrackIdsExtra).orEmpty().toList(),
            )
            ActionStartMoodRadio -> dispatch(ActionStartMoodRadio, trackIds = listOfNotNull(intent.getStringExtra(TrackIdExtra)))
            ActionSelect -> dispatch(ActionSelect, trackIds = listOfNotNull(intent.getStringExtra(TrackIdExtra)))
            ActionRemove -> dispatch(ActionRemove, trackIds = listOfNotNull(intent.getStringExtra(TrackIdExtra)))
            ActionSetSpeed -> {
                val speed = clampPlaybackSpeed(intent.getFloatExtra(SpeedExtra, 1f))
                playbackSpeed.value = speed
                if (commandMutex.tryLock()) {
                    try {
                        decoder?.setSpeed(speed)
                        updateNowPlayingTransportState()
                    } finally {
                        commandMutex.unlock()
                    }
                } else {
                    dispatch(action = ActionSetSpeed, speed = speed)
                }
            }
            ActionSetSleepTimer -> dispatch(
                action = ActionSetSleepTimer,
                sleepMinutes = intent.getIntExtra(SleepTimerMinutesExtra, 0),
                sleepAfterTrack = intent.getBooleanExtra(SleepTimerAfterTrackExtra, false),
            )
            ActionCancelSleepTimer,
            ActionSetAbLoopPointA,
            ActionSetAbLoopPointB,
            ActionClearAbLoop,
            ActionDeduplicateQueue,
            ActionCycleRepeat -> dispatch(intent.action!!)
            ActionFineTuneAbLoop -> dispatch(
                action = ActionFineTuneAbLoop,
                abIsA = intent.getBooleanExtra(AbLoopPointIsAExtra, true),
                abDeltaMs = intent.getLongExtra(AbLoopDeltaMsExtra, 0L),
            )
            ActionToggleFavorite -> toggleCurrentFavoriteAsync()
            null -> Unit
            else -> dispatch(intent.action!!)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        activeInstance = null
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        preparingStartedAt = null
        // 会话快照必须在解码器关闭前取；之后所有落盘/释放都交给进程级 IO，不再阻塞主线程。
        val finalSession = currentSession()
        finishListeningCrossfade()
        enqueueListening(listeningTracker.finish(PlaybackEndReason.STOPPED, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        listeningWrites.close()
        val closingDecoder = decoder
        decoder = null
        AndroidSyncRuntime.backgroundScope.launch {
            runCatching { sessionStore.save(finalSession) }
            // FfmpegDecoder 无内部同步：位置 ticker 用 tryLock 访问它，
            // 所以关闭必须同样在命令锁 + writeLock 内，否则可能与最后几拍/UI线程读取并发。
            runCatching { commandMutex.withLock { closeDecoderSafely(closingDecoder) } }
        }
        preferencesJob?.cancel()
        moodRadioJob?.cancel()
        lyriconPublisher?.destroy()
        lyriconPublisher = null
        unregisterReceiver(noisyAudioReceiver)
        AndroidPlaybackSession.clear()
        mediaSession.release()
        audioManager.abandonAudioFocusRequest(focusRequest)
        scope.cancel()
        clearArtworkCrossfade()
        state.value = PlaybackState.Idle
        super.onDestroy()
    }

    private fun isAlreadyLoadingOrPlaying(trackId: String?): Boolean {
        if (trackId.isNullOrBlank()) return false
        if (queue.snapshot().currentTrackId != trackId) return false
        return state.value is PlaybackState.Preparing || state.value is PlaybackState.Playing
    }

    /**
     * 点选后的“先切界面、后下载”：短暂进锁把当前曲目切过去并关掉旧解码器，
     * 然后在锁外下载目标文件（可被下一次点击取消），最后才在锁内正式接管播放。
     * 这样即使网络卡住，后续点击也永远不需要等这把锁。
     */
    private suspend fun prepareSelectionOffLock(
        action: String,
        trackIds: List<String>,
        startIndex: Int,
        superseded: () -> Boolean,
    ) {
        val targetId = when (action) {
            ActionPlay, ActionShuffle -> trackIds.getOrNull(startIndex)
            ActionSelect, ActionStartMoodRadio -> trackIds.firstOrNull()
            else -> null
        } ?: return
        val currentId = when (val current = state.value) {
            is PlaybackState.Preparing -> current.item.trackId
            is PlaybackState.Playing -> current.item.trackId
            is PlaybackState.Paused -> current.item.trackId
            else -> ""
        }
        if (targetId == currentId) return
        if (targetId == queue.snapshot().currentTrackId && state.value is PlaybackState.Playing) return
        val item = AndroidPlaybackRuntime.controller().resolve(targetId) ?: return
        if (superseded()) return
        if (preparingStartedAt?.first != targetId) {
            preparingStartedAt = targetId to SystemClock.elapsedRealtime()
        }
        // 只写状态、不碰解码器，也不拿命令锁：点下去界面立刻变，旧声晚一点停。
        state.value = PlaybackState.Preparing(item)
        publishNowPlaying(item, AndroidMediaPlaybackState.STATE_BUFFERING, 0L, 0L)
        when (val target = resolveAudioTarget(filesDir, item.audioPath)) {
            is AudioTarget.RemoteTarget -> {
                if (com.exodidio.tune.sync.remoteAudioCached(applicationContext, target.url)) return
                com.exodidio.tune.sync.ensureRemoteAudioFile(applicationContext, target.url) { superseded() }
            }
            // 流播直链不在这里同步等：网络慢时会把“切到这首歌”拖到几十秒之后。
            // playCurrent 发现直链未取到会立刻交给锁外任务，界面即时切换。
            is AudioTarget.StreamTarget -> Unit
            else -> Unit
        }
    }

    private fun dispatch(
        action: String,
        positionMs: Long = 0L,
        trackIds: List<String> = emptyList(),
        startIndex: Int = 0,
        enabled: Boolean = false,
        repeat: RepeatMode? = null,
        heartbeatStart: Boolean = false,
        roamStart: Boolean = false,
        similarStart: Boolean = false,
        speed: Float = 1f,
        sleepMinutes: Int = 0,
        sleepAfterTrack: Boolean = false,
        abIsA: Boolean = true,
        abDeltaMs: Long = 0L,
    ): Job? {
        // 心动模式/漫游/相似电台绑定当前队列：任何换成另一个队列的操作都退出，模式自身的入队除外。
        if (action in HeartbeatExitActions) {
            if (!heartbeatStart) com.exodidio.tune.netease.NeteaseRepository.setHeartbeatEnabled(false)
            if (!roamStart) com.exodidio.tune.netease.NeteaseRepository.setRoamEnabled(false)
            if (!similarStart) com.exodidio.tune.netease.NeteaseRepository.setSimilarEnabled(false)
        }
        if (action in LoadCancellingActions || action == ActionPause) {
            loadToken.incrementAndGet()
            pendingStreamResume = null
            // 只翻标记还不够：阻塞中的网络读取最长要等 readTimeout 才返回，
            // 直接断开连接让旧下载立刻退出，指令才能真正马上处理。
            com.exodidio.tune.sync.cancelRemoteAudioDownloads()
        }
        // 上一首/下一首不进单条指令队列：整批合并推进，只启动最终那一首。
        if (action == ActionNext || action == ActionPrevious) {
            val now = android.os.SystemClock.elapsedRealtime()
            skipBurstActive = now - lastSkipAtMs <= SkipBurstGapMs
            lastSkipAtMs = now
            pendingSkips.add(action == ActionNext)
            skipRequests.trySend(Unit)
            return null
        }
        // 点选类指令取一个代际号；轮到自己时若已有点得更晚的指令，整条丢弃。
        val dispatchToken = loadToken.get()
        val selectionTicket = if (action in SelectionActions) selectionGeneration.incrementAndGet() else 0
        val seekTicket = if (action == ActionSeek) seekGeneration.incrementAndGet() else 0
        val superseded = {
            (selectionTicket != 0 && (selectionGeneration.get() != selectionTicket || loadToken.get() != dispatchToken)) ||
                (seekTicket != 0 && seekGeneration.get() != seekTicket)
        }
        return scope.launch {
        restored.await()
        // 网络的活儿只放在锁外：先把界面切到目标曲目并停掉旧声，下载完再进锁接管。
        if (selectionTicket != 0) prepareSelectionOffLock(action, trackIds, startIndex, superseded)
        val lockWaitStartedAt = android.os.SystemClock.elapsedRealtime()
        commandMutex.withLock {
            val lockWaitMs = android.os.SystemClock.elapsedRealtime() - lockWaitStartedAt
            if (lockWaitMs >= 50L) Log.w(PlaybackLogTag, "LOCKWAIT action=$action ms=$lockWaitMs")
            if (superseded()) return@withLock
            consumeNativeTransition()
            Log.d(PlaybackLogTag, "Handling action=$action queueSize=${queue.snapshot().activeTrackIds.size}")
            if (action in MoodRadioStoppingActions) stopMoodRadio()
            when (action) {
                ActionPlay -> if (isAlreadyLoadingOrPlaying(trackIds.getOrNull(startIndex))) {
                    // 连点同一首：已经在准备/播放就不要重新下载、从头再播一遍。
                    Log.d(PlaybackLogTag, "PLAYACTION play skip-already head=${trackIds.firstOrNull()} state=${state.value::class.simpleName}")
                    QueueTransition.Unchanged
                } else {
                    Log.d(
                        PlaybackLogTag,
                        "PLAYACTION play head=${trackIds.firstOrNull()} size=${trackIds.size} " +
                            "prev=${state.value::class.simpleName} hb=$heartbeatStart roam=$roamStart",
                    )
                    handleTransition(runCatching { queue.play(PlaybackRequest(trackIds, startIndex)) }
                        .getOrElse { QueueTransition.Stop }, PlaybackEndReason.SKIPPED)
                    Log.d(PlaybackLogTag, "PLAYACTION play done state=${state.value::class.simpleName}")
                }
                ActionShuffle -> handleTransition(runCatching { queue.playShuffled(PlaybackRequest(trackIds, startIndex)) }
                    .getOrElse { QueueTransition.Stop }, PlaybackEndReason.SKIPPED)
                ActionPause -> {
                    resumeOnFocusGain = false
                    restoreFocusGain()
                    pauseCurrent()
                }
                ActionPauseForTransientFocusLoss -> pauseForTransientFocusLoss()
                ActionDuck -> duckForFocusLoss()
                ActionRestoreFocus -> restoreAfterFocusGain()
                ActionResume -> {
                    resumeOnFocusGain = false
                    resumeCurrent()
                }
                ActionToggle -> {
                    resumeOnFocusGain = false
                    when (val current = state.value) {
                        is PlaybackState.Playing -> {
                            restoreFocusGain()
                            pauseCurrent()
                        }
                        is PlaybackState.Preparing -> {
                            // 流重连中再点=取消；远程曲目下载中再点=取消下载并回暂停态。
                            if (current.resumeIntent) {
                                cancelPendingStreamResume()
                            } else {
                                com.exodidio.tune.sync.cancelRemoteAudioDownloads()
                                loadToken.incrementAndGet()
                                settlePausedAfterRemoteFailure(current.item, "cancelled by user")
                            }
                        }
                        else -> resumeCurrent()
                    }
                }
                ActionStop -> stopPlayback()
                ActionClearQueue -> handleTransition(queue.clear())
                ActionSeek -> seekCurrent(positionMs)
                ActionSetShuffle -> handleTransition(queue.setShuffle(enabled))
                ActionSetRepeat -> {
                    repeat?.let(queue::setRepeatMode)
                    notificationRequests.trySend(Unit)
                }
                ActionCycleRepeat -> {
                    val nextRepeat = when (queue.snapshot().repeatMode) {
                        RepeatMode.Off -> RepeatMode.All
                        RepeatMode.All -> RepeatMode.One
                        RepeatMode.One -> RepeatMode.Off
                    }
                    queue.setRepeatMode(nextRepeat)
                    notificationRequests.trySend(Unit)
                }
                ActionPlayNext -> queue.playNext(trackIds)
                ActionAppend -> queue.append(trackIds)
                ActionStartMoodRadio -> trackIds.firstOrNull()?.let { startMoodRadio(it) }
                ActionSelect -> trackIds.firstOrNull()?.let { trackId ->
                    if (isAlreadyLoadingOrPlaying(trackId)) QueueTransition.Unchanged
                    else handleTransition(queue.select(trackId), PlaybackEndReason.SKIPPED)
                }
                ActionRemove -> trackIds.firstOrNull()?.let { handleTransition(queue.removeFromQueue(it), PlaybackEndReason.SKIPPED) }
                ActionReorder -> queue.reorderQueue(trackIds)
                ActionSetSpeed -> {
                    val clamped = clampPlaybackSpeed(speed)
                    playbackSpeed.value = clamped
                    decoder?.setSpeed(clamped)
                    updateNowPlayingTransportState()
                }
                ActionSetAbLoopPointA -> setAbLoopPointLocked(isA = true)
                ActionSetAbLoopPointB -> setAbLoopPointLocked(isA = false)
                ActionFineTuneAbLoop -> fineTuneAbLoopPointLocked(isA = abIsA, deltaMs = abDeltaMs)
                ActionClearAbLoop -> abLoopState.value = AbLoopState()
                ActionDeduplicateQueue -> deduplicateQueueLocked()
                ActionSetSleepTimer -> setSleepTimerLocked(sleepMinutes, sleepAfterTrack)
                ActionCancelSleepTimer -> cancelSleepTimerLocked()
            }
            if (action in PreloadResyncActions) {
                // The old source must not remain part of a fade whose queued
                // successor has just changed.
                if (decoder?.isCrossfading() == true) {
                    clearArtworkCrossfade()
                    decoder?.snapCrossfade()
                }
                preloadNext()
            }
            publishQueue()
        }
        }
    }

    internal suspend fun handleTransition(
        transition: QueueTransition,
        previousReason: PlaybackEndReason = PlaybackEndReason.STOPPED,
        preservePlaybackState: Boolean = false,
    ) {
        when (transition) {
            is QueueTransition.Play -> {
                finishListeningCrossfade()
                enqueueListening(listeningTracker.finish(previousReason, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                // 先把队列/当前曲目切过去：远程歌曲下载期间界面立刻显示新歌，而不是停在上一首。
                publishQueue()
                playCurrent(startPaused = preservePlaybackState && state.value is PlaybackState.Paused)
            }
            QueueTransition.StopAtCurrent -> stopAtCurrentTrack(previousReason)
            QueueTransition.Stop -> stopPlayback()
            QueueTransition.Unchanged -> Unit
        }
    }

    private class PreparedPlayback(
        val item: PlaybackItem,
        val decoder: FfmpegDecoder,
        val positionMs: Long,
        val durationMs: Long,
        val startPaused: Boolean,
    )

    /**
     * 创建并打开解码器插槽。普通指令在锁内调用（保持原行为）；
     * 连点合并路径在锁外调用，避免打开流阻塞命令锁。
     */
    private suspend fun prepareDecoder(
        item: PlaybackItem,
        startPositionMs: Long,
        startPaused: Boolean,
        dspConfig: GlobalDspConfig,
        focusGain: Float,
        normalizedGain: ResolvedNormalizationGain,
    ): PreparedPlayback {
        val prepared = FfmpegDecoder()
        try {
            prepared.setGlobalDspConfig(dspConfig)
            prepared.setFocusGain(focusGain)
            prepared.setSpeed(playbackSpeed.value)
            prepared.setNormalizationConfig(normalizationSettings)
            prepared.setCassetteEnabled(funFeaturesSettings.cassetteMode)
            prepareDecoderPath(prepared, item.audioPath, normalizedGain.gainDb, normalizedGain.hasStaticAnalysis)
            val effectiveDurationMs = streamAwareDuration(item, prepared.durationMs())
            if (startPositionMs > 0L) prepared.seekTo(clampSeekPosition(startPositionMs, effectiveDurationMs))
            if (!startPaused) {
                val startedAt = preparingStartedAt?.takeIf { it.first == item.trackId }?.second
                val elapsedMs = if (startedAt != null) (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) else 0L
                if (shouldTriggerRadioCrosstalk(
                        enabled = funFeaturesSettings.radioCrosstalk,
                        isStreamingTrack = streamSongIdOf(item) != null,
                        elapsedPrepareMs = elapsedMs,
                        roll = kotlin.random.Random.nextFloat(),
                    )
                ) {
                    prepared.triggerRadioCrosstalk(500)
                }
            }
            if (startPaused) prepared.pause() else prepared.play()
            return PreparedPlayback(item, prepared, prepared.positionMs(), effectiveDurationMs, startPaused)
        } catch (error: Throwable) {
            runCatching { prepared.close() }
            throw error
        }
    }

    /** 锁内接管已打开的插槽：状态、通知、听歌统计与前台。 */
    private fun commitPrepared(prepared: PreparedPlayback, startTracking: Boolean) {
        val item = prepared.item
        preparingStartedAt = null
        clearAbLoopIfTrackChanged(item.trackId)
        pendingRecoverySeek = null
        lastSeekAtMs = 0L
        lastSeekTrackId = null
        seekBufferingGraceUntilMs = 0L
        bufferingStallAnchor = null
        // 在线流优先用接口给出的整首时长：流式 MP3 的解码器时长会按码率估算，
        // 开头偶尔会算出 11 分钟再自我修正，不能让这个值进 UI 和 seek 判定。
        val durationMs = streamAwareDuration(item, prepared.durationMs)
        if (prepared.startPaused) {
            state.value = PlaybackState.Paused(item, prepared.positionMs, durationMs)
            publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, prepared.positionMs, durationMs)
        } else {
            val isBuffering = prepared.decoder.isBuffering()
            currentStreamSongId = streamSongIdOf(item)
            currentStreamStartedAt = SystemClock.elapsedRealtime()
            lastStreamSongId = currentStreamSongId
            lastStreamPositionMs = prepared.positionMs
            state.value = PlaybackState.Playing(item, prepared.positionMs, durationMs, isBuffering = isBuffering)
            publishNowPlaying(
                item,
                if (isBuffering) AndroidMediaPlaybackState.STATE_BUFFERING else AndroidMediaPlaybackState.STATE_PLAYING,
                prepared.positionMs,
                durationMs,
            )
        }
        decoder = prepared.decoder
        updateNowPlayingTransportState()
        enqueueBackgroundLoudnessAnalysis(item)
        if (!prepared.startPaused && startTracking) {
            if (recoveryTrackId != item.trackId) {
                recoveryTrackId = item.trackId
                temporaryRecoveryAttempts = 0
            }
            lastRecoveryPositionMs = -1L
            cleanEofRetriedTrackId = null
            cleanEofRetriedPositionMs = -1L
            enqueueListening(
                listeningTracker.start(item.trackId, prepared.positionMs, System.currentTimeMillis(), SystemClock.elapsedRealtime()),
            )
        }
        if (!prepared.startPaused && !startTracking) {
            listeningTracker.resumeAfterInterruption(SystemClock.elapsedRealtime())
        }
    }

    private fun enqueueBackgroundLoudnessAnalysis(item: PlaybackItem) {
        TrackLoudnessAnalyzer.enqueue(applicationContext, item) {
            scope.launch {
                commandMutex.withLock {
                    if (decoder != null) refreshNormalizationGains()
                }
            }
        }
    }

    /** 播放启动失败的统一收尾；调用方持有命令锁。 */
    private fun handleStartFailure(item: PlaybackItem, error: Throwable, startPositionMs: Long, prepareIntent: Boolean) {
        val fallbackDurationMs = streamAwareDuration(item, 0L)
        if (error is RemoteAudioNotReady) {
            // 界面已经切到这首歌并显示“准备中”，下载在锁外完成后自动接管播放。
            scheduleDeferredRemotePlay(error.url)
            return
        }
        if (error is StreamUrlNotReady) {
            // 直链在锁外获取，拿到后重新接管播放。
            Log.d(PlaybackLogTag, "NETSTREAM not ready song=${error.songId}; deferring")
            scheduleDeferredStreamPlay(error.songId, startPositionMs, prepareIntent, fallbackDurationMs)
            return
        }
        val songId = streamSongIdOf(item)
        if (songId != null) {
            // 参考 MeiloX：临时失败原地有界重试；明确不可播放/登录失效则不重试。
            val terminal = com.exodidio.tune.netease.NeteaseRepository.streamFailureReason(songId)
            if (terminal == null && scheduleTemporaryStreamRecovery(item, startPositionMs, fallbackDurationMs)) {
                Log.w(PlaybackLogTag, "NETSTREAM retry scheduled song=$songId reason=${error.message}")
                return
            }
            // 在线歌曲连不上：退回暂停态而不是把播放入口整个收走，按播放可再次重连。
            settlePausedAfterStreamFailure(
                item,
                startPositionMs,
                fallbackDurationMs,
                terminal ?: error.message ?: "stream failed",
            )
            return
        }
        fail(item.trackId, error.message ?: "Unable to decode audio")
    }

    internal suspend fun playCurrent(
        startPositionMs: Long = 0L,
        startPaused: Boolean = false,
        startTracking: Boolean = true,
        prepareIntent: Boolean = false,
    ) {
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        finishListeningCrossfade()
        clearArtworkCrossfade()
        val trackId = queue.snapshot().currentTrackId ?: return stopPlayback()
        Log.d(PlaybackLogTag, "Preparing current queue track id=$trackId")
        val item = AndroidPlaybackRuntime.controller().resolve(trackId) ?: return fail(trackId, "Audio asset is not available")
        Log.d(
            PlaybackLogTag,
            "PLAYPREP id=$trackId path=${item.audioPath} " +
                "target=${runCatching { resolveAudioTarget(filesDir, item.audioPath)?.let { it::class.simpleName } }.getOrNull()}",
        )
        if (preparingStartedAt?.first != trackId) {
            preparingStartedAt = trackId to SystemClock.elapsedRealtime()
        }
        state.value = PlaybackState.Preparing(item, resumeIntent = prepareIntent)
        showForeground(item)
        publishNowPlaying(item, AndroidMediaPlaybackState.STATE_BUFFERING, positionMs = startPositionMs, durationMs = 0L)
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return fail(trackId, "Audio focus was not granted")
        restoreFocusGain()
        try {
            // A normalization lookup can suspend. Do not leave a closed decoder
            // reachable while a queued session write may read playback state.
            closeCurrentDecoderSafely()
            val prepared = prepareDecoder(
                item,
                startPositionMs,
                startPaused,
                equalizerDspConfig(equalizerSettings),
                if (isDucked) DuckedFocusGain else 1f,
                normalizationGain(item, queue.peekNext()),
            )
            commitPrepared(prepared, startTracking)
            preloadNext()
            showForeground(item)
            Log.d(PlaybackLogTag, "Playback started id=$trackId durationMs=${decoder?.durationMs()}")
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            handleStartFailure(item, error, startPositionMs, prepareIntent)
        }
    }

    /** 连点合并的切歌现场：锁内已切到“准备中”并关掉旧声。 */
    private class SkipSwitch(
        val item: PlaybackItem,
        val startPaused: Boolean,
        val token: Int,
        val dspConfig: GlobalDspConfig,
        val focusGain: Float,
        val normalizedGain: ResolvedNormalizationGain,
    )

    private suspend fun <T> timedLock(phase: String, block: suspend () -> T): T = commandMutex.withLock {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            block()
        } finally {
            // 锁内停留时间（探针）：验证批量切歌/提交没有长期占锁。
            val heldMs = android.os.SystemClock.elapsedRealtime() - startedAt
            if (heldMs >= 50L) Log.w(PlaybackLogTag, "DRAINHOLD phase=$phase ms=$heldMs")
        }
    }

    private enum class SkipAdvance { None, Stopped, Play }

    /**
     * 点击立即推进队列并刷新界面（不碰解码器/网络）；真正的启动交给静默窗后的 startPendingSkip。
     */
    private suspend fun advanceSkipsAndScheduleStart() {
        runCatching { restored.await() }
        if (pendingSkips.isEmpty()) return
        val advance = timedLock("steps") { advancePendingSkipsLocked() }
        if (advance != SkipAdvance.Play) return
        scheduleSkipStart()
    }

    private suspend fun advancePendingSkipsLocked(): SkipAdvance {
        consumeNativeTransition()
        val wasPaused = state.value is PlaybackState.Paused
        var transition: QueueTransition? = null
        var restartedCurrent = false
        var stepped = false
        while (true) {
            val next = pendingSkips.poll() ?: break
            if (next) {
                transition = queue.next()
                restartedCurrent = false
                stepped = true
            } else if (!stepped && (decoder?.positionMs() ?: 0L) > PreviousRestartThresholdMs) {
                // 连点的第一次“上一首”与单点一致：先回到本曲开头。
                seekCurrent(0L)
                transition = QueueTransition.Unchanged
                restartedCurrent = true
            } else {
                transition = queue.previous()
                restartedCurrent = false
                stepped = true
            }
        }
        val finalTransition = transition ?: return SkipAdvance.None
        if (restartedCurrent) return SkipAdvance.None
        when (finalTransition) {
            is QueueTransition.Play -> Unit
            QueueTransition.StopAtCurrent -> {
                stopAtCurrentTrack(PlaybackEndReason.SKIPPED)
                return SkipAdvance.Stopped
            }
            QueueTransition.Stop -> {
                stopPlayback()
                return SkipAdvance.Stopped
            }
            QueueTransition.Unchanged -> return SkipAdvance.None
        }
        val trackId = queue.snapshot().currentTrackId ?: return SkipAdvance.None
        val item = AndroidPlaybackRuntime.controller().resolve(trackId) ?: return SkipAdvance.None
        publishQueue()
        pendingStartPaused = wasPaused
        if (preparingStartedAt?.first != trackId) {
            preparingStartedAt = trackId to SystemClock.elapsedRealtime()
        }
        // 只切界面：不关旧解码器、不取流，连点期间旧歌继续放，启动交给静默窗。
        state.value = PlaybackState.Preparing(item)
        return SkipAdvance.Play
    }

    /** 连点时取消上一轮待启动任务；手停下 250ms 后才真正拉流/下载并出声。 */
    private fun scheduleSkipStart() {
        skipStartJob?.cancel()
        val quietMs = if (skipBurstActive) SkipStartQuietMs else 0L
        val shouldSpinDown = !skipBurstActive && funFeaturesSettings.cassetteMode && !pendingStartPaused && decoder != null
        skipStartJob = scope.launch {
            if (quietMs > 0L) {
                delay(quietMs)
            } else if (shouldSpinDown) {
                triggerActiveSpinDown(120)
                delay(105L)
            }
            if (pendingSkips.isNotEmpty()) return@launch
            startPendingSkip()
        }
    }

    private suspend fun startPendingSkip() {
        runCatching { restored.await() }
        if (pendingSkips.isNotEmpty()) return
        val switch = timedLock("switch") { prepareSkipSwitchLocked(pendingStartPaused) } ?: return
        val prepared = try {
            prepareDecoder(switch.item, 0L, switch.startPaused, switch.dspConfig, switch.focusGain, switch.normalizedGain)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            timedLock("error") {
                if (loadToken.get() != switch.token) return@timedLock
                handleStartFailure(switch.item, error, 0L, prepareIntent = false)
            }
            return
        }
        timedLock("commit") {
            if (queue.snapshot().currentTrackId != switch.item.trackId || loadToken.get() != switch.token) {
                runCatching { prepared.decoder.close() }
                return@timedLock
            }
            commitPrepared(prepared, startTracking = true)
            preloadNext()
            showForeground(switch.item)
            Log.d(PlaybackLogTag, "Playback started id=${switch.item.trackId} durationMs=${prepared.durationMs}")
        }
    }

    /** 锁内：切到“准备中”并关掉旧声；返回 null 表示本批放弃。 */
    private suspend fun prepareSkipSwitchLocked(wasPaused: Boolean): SkipSwitch? {
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        finishListeningCrossfade()
        enqueueListening(
            listeningTracker.finish(PlaybackEndReason.SKIPPED, System.currentTimeMillis(), SystemClock.elapsedRealtime()),
        )
        val trackId = queue.snapshot().currentTrackId ?: run {
            stopPlayback()
            return null
        }
        val item = AndroidPlaybackRuntime.controller().resolve(trackId) ?: run {
            fail(trackId, "Audio asset is not available")
            return null
        }
        Log.d(
            PlaybackLogTag,
            "PLAYPREP id=$trackId path=${item.audioPath} " +
                "target=${runCatching { resolveAudioTarget(filesDir, item.audioPath)?.let { it::class.simpleName } }.getOrNull()}",
        )
        state.value = PlaybackState.Preparing(item)
        publishNowPlaying(item, AndroidMediaPlaybackState.STATE_BUFFERING, positionMs = 0L, durationMs = 0L)
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(trackId, "Audio focus was not granted")
            return null
        }
        restoreFocusGain()
        val token = loadToken.get()
        val dspConfig = equalizerDspConfig(equalizerSettings)
        val focusGain = if (isDucked) DuckedFocusGain else 1f
        val normalizedGain = normalizationGain(item, queue.peekNext())
        closeCurrentDecoderSafely()
        return SkipSwitch(item, wasPaused, token, dspConfig, focusGain, normalizedGain)
    }

    /** Restores the selected item paused, so reopening the app never starts audio by itself. */
    private suspend fun restoreCurrent(savedPositionMs: Long) {
        val trackId = queue.snapshot().currentTrackId ?: return clearRestoredSession()
        val item = AndroidPlaybackRuntime.controller().resolve(trackId)
            ?: run {
                // 本地扫描/远端曲库可能还没恢复完：保留整个队列，别因为当前曲目暂时解析不了就清空。
                Log.w(PlaybackLogTag, "Queue restored; current track not resolvable yet: $trackId")
                publishQueue()
                return
            }
        // Publish the retained item before FFmpeg opens it, so Compose can mount the mini player.
        state.value = PlaybackState.Paused(item, savedPositionMs.coerceAtLeast(0L), durationMs = 0L)
        val restoredTarget = resolveAudioTarget(filesDir, item.audioPath)
        if (restoredTarget is AudioTarget.StreamTarget) {
            val songId = restoredTarget.url.removePrefix("netease://").toLongOrNull() ?: 0L
            val repository = com.exodidio.tune.netease.NeteaseRepository
            if (repository.cachedStreamUrl(songId) == null && !repository.hasCachedStream(songId)) {
                // 在线歌曲重启后不主动预取直链，先恢复成暂停态，按播放再取。
                publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, savedPositionMs.coerceAtLeast(0L), 0L)
                showForeground(item)
                publishQueue()
                return
            }
        }
        if (restoredTarget is AudioTarget.RemoteTarget &&
            !com.exodidio.tune.sync.remoteAudioCached(applicationContext, restoredTarget.url)
        ) {
            // 上次播放的是尚未缓存的电脑歌曲：先恢复为暂停态，按播放时再下载。
            // 否则启动时会占着命令锁下载整首文件，点什么都像卡住。
            publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, savedPositionMs.coerceAtLeast(0L), 0L)
            showForeground(item)
            publishQueue()
            return
        }
        try {
            closeCurrentDecoderSafely()
            decoder = FfmpegDecoder().also {
                it.setGlobalDspConfig(equalizerDspConfig(equalizerSettings))
                it.setFocusGain(if (isDucked) DuckedFocusGain else 1f)
                it.setSpeed(playbackSpeed.value)
                it.setNormalizationConfig(normalizationSettings)
                it.setCassetteEnabled(funFeaturesSettings.cassetteMode)
                val norm = normalizationGain(item, queue.peekNext())
                prepareDecoderPath(it, item.audioPath, norm.gainDb, norm.hasStaticAnalysis)
                val positionMs = clampSeekPosition(savedPositionMs, it.durationMs())
                if (positionMs > 0L) it.seekTo(positionMs)
                it.pause()
                state.value = PlaybackState.Paused(item, positionMs, it.durationMs())
                publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, positionMs, it.durationMs())
            }
            enqueueBackgroundLoudnessAnalysis(item)
            preloadNext()
            showForeground(item)
            publishQueue()
            Log.d(PlaybackLogTag, "Restored paused playback id=$trackId positionMs=${decoder?.positionMs()}")
        } catch (error: Throwable) {
            Log.w(PlaybackLogTag, "Unable to restore playback id=$trackId; clearing session", error)
            clearRestoredSession()
        }
    }

    private fun pauseCurrent() {
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        finishListeningCrossfade()
        clearArtworkCrossfade()
        pendingStreamResume = null
        pendingRecoverySeek = null
        enqueueListening(listeningTracker.pause(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        val playing = state.value as? PlaybackState.Playing
        val activeDecoder = decoder
        seekBufferingGraceUntilMs = 0L
        bufferingStallAnchor = null
        if (playing != null && activeDecoder != null && funFeaturesSettings.cassetteMode &&
            !playing.isBuffering && !activeDecoder.isFinished()
        ) {
            val positionMs = activeDecoder.positionMs()
            state.value = PlaybackState.Paused(playing.item, positionMs, playing.durationMs)
            publishNowPlaying(playing.item, AndroidMediaPlaybackState.STATE_PAUSED, positionMs, playing.durationMs)
            activeDecoder.triggerCassetteSpinDown(220)
            val token = loadToken.get()
            pauseSpinDownJob = scope.launch {
                delay(210L)
                commandMutex.withLock {
                    if (loadToken.get() == token && state.value is PlaybackState.Paused && decoder === activeDecoder) {
                        activeDecoder.pause()
                    }
                }
            }
        } else {
            activeDecoder?.pause()
            playing?.let { current ->
                val positionMs = activeDecoder?.positionMs() ?: current.positionMs
                state.value = PlaybackState.Paused(current.item, positionMs, current.durationMs)
                publishNowPlaying(current.item, AndroidMediaPlaybackState.STATE_PAUSED, positionMs, current.durationMs)
            }
            // 远程歌曲仍在下载（Preparing）时暂停：下载已被取消，先把界面落到暂停态，按播放会续传。
            (state.value as? PlaybackState.Preparing)?.let { current ->
                preparingStartedAt = null
                state.value = PlaybackState.Paused(current.item, 0L, 0L)
                publishNowPlaying(current.item, AndroidMediaPlaybackState.STATE_PAUSED, 0L, 0L)
            }
        }
        updateNotification()
    }

    private fun pauseForTransientFocusLoss() {
        resumeOnFocusGain = state.value is PlaybackState.Playing
        restoreFocusGain()
        pauseCurrent()
    }

    private fun duckForFocusLoss() {
        isDucked = true
        decoder?.setFocusGain(DuckedFocusGain)
    }

    private fun restoreFocusGain() {
        isDucked = false
        decoder?.setFocusGain(1f)
    }

    private suspend fun restoreAfterFocusGain() {
        restoreFocusGain()
        if (!resumeOnFocusGain) return
        resumeOnFocusGain = false
        resumeCurrent()
    }

    private suspend fun resumeCurrent() {
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        val paused = state.value as? PlaybackState.Paused
        val currentDecoder = decoder
        if (paused != null && shouldRestartQueueOnResume(paused.positionMs, paused.durationMs, currentDecoder != null)) {
            handleTransition(queue.restart())
            return
        }
        val streamSongId = paused?.let { streamSongIdOf(it.item) }
        if (paused != null && streamSongId != null) {
            // 在线歌曲：点播放一律重新尝试联网，界面同时给出响应。
            beginStreamResume(streamSongId, paused)
            return
        }
        if (paused == null || currentDecoder == null ||
            currentDecoder.isOutputDisconnected() || currentDecoder.isFinished()
        ) {
            // 断网暂停在缓存末尾时解码器已到 EOF：按当前位置重新准备，而不是 resume 一个结束的流。
            if (currentDecoder?.isOutputDisconnected() == true || currentDecoder?.isFinished() == true) {
                closeCurrentDecoderSafely()
            }
            currentStreamSongId = null
            playCurrent(paused?.positionMs ?: 0L)
            return
        }
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(paused.item.trackId, "Audio focus was not granted")
            return
        }
        restoreFocusGain()
        currentDecoder.cancelCassetteSpinDown()
        currentDecoder.play()
        val positionMs = currentDecoder.positionMs()
        if (listeningTracker.activeTrackId == paused.item.trackId) {
            listeningTracker.resume(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        } else {
            enqueueListening(listeningTracker.start(paused.item.trackId, positionMs, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }
        state.value = PlaybackState.Playing(paused.item, positionMs, paused.durationMs)
        publishNowPlaying(paused.item, AndroidMediaPlaybackState.STATE_PLAYING, positionMs, paused.durationMs)
        updateNotification()
        Log.d(PlaybackLogTag, "Playback resumed id=${paused.item.trackId} positionMs=$positionMs")
    }

    /**
     * A manual route change invalidates the old AAudio stream without being a
     * user pause. Recreate it on the new route and retain its rendered position.
     * A real device removal sends ACTION_AUDIO_BECOMING_NOISY, whose queued
     * pause action wins and leaves playback paused instead.
     */
    private suspend fun recoverAfterOutputDisconnect() {
        val current = state.value as? PlaybackState.Playing ?: return
        val positionMs = decoder?.positionMs() ?: current.positionMs
        listeningTracker.suspendForInterruption(SystemClock.elapsedRealtime())
        closeCurrentDecoderSafely()
        Log.w(PlaybackLogTag, "Audio output changed; recreating stream id=${current.item.trackId} positionMs=$positionMs")
        playCurrent(positionMs, startTracking = false)
    }

    private fun seekCurrent(requestedPositionMs: Long) {
        val current = state.value
        val item: PlaybackItem
        val durationMs: Long
        val playing: Boolean
        when (current) {
            is PlaybackState.Playing -> {
                item = current.item
                durationMs = current.durationMs
                playing = true
            }
            is PlaybackState.Paused -> {
                item = current.item
                durationMs = current.durationMs
                playing = false
            }
            is PlaybackState.Preparing -> {
                val resolvedDuration = streamAwareDuration(current.item, 0L)
                val targetPositionMs = clampSeekPosition(requestedPositionMs, resolvedDuration)
                pendingRecoverySeek = current.item.trackId to targetPositionMs
                lastSeekTrackId = current.item.trackId
                lastSeekAtMs = SystemClock.elapsedRealtime()
                pendingStreamResume?.takeIf { it.item.trackId == current.item.trackId }?.let { pending ->
                    pendingStreamResume = PendingStreamResume(
                        pending.songId,
                        pending.item,
                        targetPositionMs,
                        maxOf(pending.durationMs, resolvedDuration),
                        pending.token,
                    )
                }
                publishNowPlaying(
                    current.item,
                    AndroidMediaPlaybackState.STATE_BUFFERING,
                    targetPositionMs,
                    resolvedDuration,
                )
                return
            }
            else -> return
        }
        val effectiveDurationMs = streamAwareDuration(item, durationMs)
        val targetPositionMs = clampSeekPosition(requestedPositionMs, effectiveDurationMs)
        finishListeningCrossfade()
        clearArtworkCrossfade()
        lastSeekTrackId = item.trackId
        lastSeekAtMs = SystemClock.elapsedRealtime()
        val streamId = streamSongIdOf(item)
        if (streamId != null) {
            temporaryRecoveryAttempts = 0
            lastRecoveryPositionMs = -1L
            cleanEofRetriedTrackId = null
            cleanEofRetriedPositionMs = -1L
        }
        val activeDecoder = decoder
        if (activeDecoder == null) {
            if (!playing) {
                pendingRecoverySeek = item.trackId to targetPositionMs
                state.value = PlaybackState.Paused(item, targetPositionMs, effectiveDurationMs)
                publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, targetPositionMs, effectiveDurationMs)
            } else if (streamId != null) {
                pendingRecoverySeek = null
                state.value = PlaybackState.Preparing(item, resumeIntent = true)
                publishNowPlaying(item, AndroidMediaPlaybackState.STATE_BUFFERING, targetPositionMs, effectiveDurationMs)
                scheduleDeferredStreamPlay(streamId, targetPositionMs, prepareIntent = true, fallbackDurationMs = effectiveDurationMs)
            }
            return
        }
        if (streamId != null && activeDecoder.isFinished()) {
            closeCurrentDecoderSafely()
            currentStreamSongId = null
            pendingRecoverySeek = null
            if (playing) {
                state.value = PlaybackState.Preparing(item, resumeIntent = true)
                publishNowPlaying(item, AndroidMediaPlaybackState.STATE_BUFFERING, targetPositionMs, effectiveDurationMs)
                scheduleDeferredStreamPlay(streamId, targetPositionMs, prepareIntent = true, fallbackDurationMs = effectiveDurationMs)
            } else {
                state.value = PlaybackState.Paused(item, targetPositionMs, effectiveDurationMs)
                publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, targetPositionMs, effectiveDurationMs)
            }
            return
        }
        pendingRecoverySeek = null
        bufferingStallAnchor = null
        seekBufferingGraceUntilMs = if (playing && streamId != null) SystemClock.elapsedRealtime() + SeekBufferingGraceMs else 0L
        activeDecoder.seekTo(targetPositionMs)
        if (playing) {
            state.value = PlaybackState.Playing(item, targetPositionMs, effectiveDurationMs, isBuffering = false)
            mediaSession.setPlaybackState(androidPlaybackState(AndroidMediaPlaybackState.STATE_PLAYING, targetPositionMs))
        } else {
            state.value = PlaybackState.Paused(item, targetPositionMs, effectiveDurationMs)
            mediaSession.setPlaybackState(androidPlaybackState(AndroidMediaPlaybackState.STATE_PAUSED, targetPositionMs))
        }
        Log.d(PlaybackLogTag, "Seek requested id=${item.trackId} targetMs=$targetPositionMs playing=$playing")
    }

    internal fun stopPlayback() {
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        preparingStartedAt = null
        finishListeningCrossfade()
        enqueueListening(listeningTracker.finish(PlaybackEndReason.STOPPED, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        clearArtworkCrossfade()
        pendingRecoverySeek = null
        lastSeekAtMs = 0L
        lastSeekTrackId = null
        abLoopState.value = AbLoopState()
        decoder?.snapCrossfade()
        closeCurrentDecoderSafely(); preloadedItem = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        state.value = PlaybackState.Idle
        mediaSession.setPlaybackState(androidPlaybackState(AndroidMediaPlaybackState.STATE_STOPPED, 0L))
        mediaSession.isActive = false
        isForegroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Retains the final item when repeat-off playback or manual navigation exhausts the queue. */
    private fun stopAtCurrentTrack(reason: PlaybackEndReason = PlaybackEndReason.COMPLETED) {
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        preparingStartedAt = null
        val current = state.value
        val item: PlaybackItem
        val durationMs: Long
        when (current) {
            is PlaybackState.Playing -> {
                item = current.item
                durationMs = current.durationMs
            }
            is PlaybackState.Paused -> {
                item = current.item
                durationMs = current.durationMs
            }
            else -> return stopPlayback()
        }
        enqueueListening(listeningTracker.finish(reason, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        clearArtworkCrossfade()
        pendingRecoverySeek = null
        lastSeekAtMs = 0L
        lastSeekTrackId = null
        abLoopState.value = AbLoopState()
        decoder?.snapCrossfade()
        closeCurrentDecoderSafely()
        audioManager.abandonAudioFocusRequest(focusRequest)
        val positionMs = stoppedCurrentPosition(reason, durationMs)
        state.value = PlaybackState.Paused(item, positionMs, durationMs)
        publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, positionMs, durationMs)
        updateNotification()
        Log.d(PlaybackLogTag, "Playback stopped at final queue track id=${item.trackId} positionMs=$positionMs")
    }

    internal fun fail(trackId: String?, reason: String) {
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        preparingStartedAt = null
        Log.e(PlaybackLogTag, "Playback failed id=$trackId reason=$reason")
        finishListeningCrossfade()
        enqueueListening(listeningTracker.finish(PlaybackEndReason.STOPPED, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        clearArtworkCrossfade()
        pendingRecoverySeek = null
        lastSeekAtMs = 0L
        lastSeekTrackId = null
        abLoopState.value = AbLoopState()
        closeCurrentDecoderSafely(); preloadedItem = null
        state.value = PlaybackState.Failed(trackId, reason)
        mediaSession.setPlaybackState(androidPlaybackState(AndroidMediaPlaybackState.STATE_ERROR, 0L))
        mediaSession.isActive = false
        isForegroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    internal fun closeCurrentDecoderSafely() {
        val closing = decoder
        decoder = null
        closeDecoderSafely(closing)
    }

    private fun closeDecoderSafely(target: FfmpegDecoder?) {
        if (target == null) return
        val writeLock = decoderAccessLock.writeLock()
        writeLock.lock()
        try {
            target.close()
        } finally {
            writeLock.unlock()
        }
    }

    private fun triggerActiveSpinDown(durationMs: Int) {
        val readLock = decoderAccessLock.readLock()
        if (!readLock.tryLock()) return
        try {
            decoder?.triggerCassetteSpinDown(durationMs)
        } finally {
            readLock.unlock()
        }
    }

    internal suspend fun publishQueue() {
        val snapshot = queue.snapshot()
        queueState.value = snapshot
        // 全库索引 + Binder 发布交给锁外单消费者；这里只投递最新快照。
        queuePublishRequests.trySend(snapshot)
        // Capture before the asynchronous DataStore write. A later command can
        // close the decoder, but cannot change this immutable session snapshot.
        val session = currentSession(snapshot)
        scope.launch {
            sessionStore.save(session)
            persistQueueTracks(snapshot)
        }
    }

    private fun publishQueueSideEffects(snapshot: PlaybackQueueSnapshot) {
        val store = AndroidSyncRuntime.syncStore
        val tracks = store.restoredQueueTracksFlow.value +
            store.playbackOnlyTracksFlow.value.associateBy { it.id } +
            store.tracks.value.associateBy { it.id }
        mediaSession.setQueue(snapshot.activeTrackIds.mapIndexedNotNull { index, trackId ->
            tracks[trackId]?.let { track ->
                MediaSession.QueueItem(
                    MediaDescription.Builder()
                        .setMediaId(track.id)
                        .setTitle(track.title)
                        .setSubtitle(track.artists)
                        .setDescription(track.album)
                        .build(),
                    index.toLong(),
                )
            }
        })
        updateNowPlayingTransportState()
    }

    private fun currentSession(snapshot: PlaybackQueueSnapshot = queue.snapshot()): PlaybackSession {
        val positionMs = when (val current = state.value) {
            is PlaybackState.Playing -> decoder?.positionMs() ?: current.positionMs
            is PlaybackState.Paused -> decoder?.positionMs() ?: current.positionMs
            else -> 0L
        }
        return PlaybackSession(snapshot, positionMs.coerceAtLeast(0L))
    }

    /** 只持久化“仅播放”曲目：本地/导入/远端曲库各自有持久化，不在这里重复。 */
    private fun persistQueueTracks(snapshot: PlaybackQueueSnapshot) {
        val store = AndroidSyncRuntime.syncStore
        // 元数据一起存：冷启动时本地扫描/远端曲库可能还没就绪，队列也要能显示和解析。
        val byId = (store.tracks.value + store.playbackOnlyTracksFlow.value).associateBy { it.id }
        val tracks = snapshot.activeTrackIds.mapNotNull(byId::get)
        if (tracks.isEmpty()) return
        val signature = tracks.joinToString("|") { it.id }.hashCode()
        if (signature == lastQueueTracksSignature) return
        lastQueueTracksSignature = signature
        PlaybackQueueTracksStore.write(applicationContext, tracks)
    }

    @Volatile private var lastQueueTracksSignature: Int = Int.MIN_VALUE

    private suspend fun clearRestoredSession() {
        pauseSpinDownJob?.cancel()
        pauseSpinDownJob = null
        preparingStartedAt = null
        closeCurrentDecoderSafely(); preloadedItem = null
        queue.clear()
        queueState.value = queue.snapshot()
        mediaSession.setQueue(emptyList())
        state.value = PlaybackState.Idle
        mediaSession.setPlaybackState(androidPlaybackState(AndroidMediaPlaybackState.STATE_NONE, 0L))
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        sessionStore.clear()
    }

    /** Keep native idle slot aligned with the queue's immediate next item. */
    internal suspend fun preloadNext() {
        val currentDecoder = decoder ?: return
        if (sleepTimerState.value.afterCurrentTrack) {
            currentDecoder.clearPreloaded()
            preloadedItem = null
            return
        }
        val shouldPreload = gaplessPlayback.value || crossfadeSeconds.value > 0
        val nextId = queue.peekNext()
        if (!shouldPreload) {
            currentDecoder.clearPreloaded()
            preloadedItem = null
            return
        }
        if (nextId == preloadedItem?.trackId && currentDecoder.hasPreloaded()) return
        currentDecoder.clearPreloaded()
        currentDecoder.setSpeed(playbackSpeed.value)
        val item = nextId?.let { id -> AndroidPlaybackRuntime.controller().resolve(id) }
        if (item == null) {
            preloadedItem = null
            return
        }
        val target = resolveAudioTarget(filesDir, item.audioPath)
        if (target is AudioTarget.StreamTarget) {
            val songId = target.url.removePrefix("netease://").toLongOrNull()
            val canPreloadNow = songId != null &&
                !NeteaseRepository.isPreviewStream(songId) &&
                com.exodidio.tune.netease.NeteaseStreamProxy.hasWarmedPrefix(songId) &&
                (NeteaseRepository.cachedStreamUrl(songId) != null || NeteaseRepository.hasCachedStream(songId))
            if (!canPreloadNow) {
                preloadedItem = null
                if (prefetchedTrackId == item.trackId && prefetchJob?.isActive == true) return
                prefetchedTrackId = item.trackId
                prefetchStreamAudio(item)
                return
            }
        }
        if (target is AudioTarget.RemoteTarget) {
            // 预取是后台行为：不占命令锁，同一首只跑一个任务，切歌时随令牌失效。
            if (!com.exodidio.tune.sync.remoteAudioCached(applicationContext, target.url)) {
                preloadedItem = null
                if (prefetchedTrackId == item.trackId && prefetchJob?.isActive == true) return
                prefetchedTrackId = item.trackId
                prefetchRemoteAudio(item)
                return
            }
        }
        preloadedItem = item
        val norm = normalizationGain(item, queue.peekNext())
        runCatching { preloadDecoderPath(currentDecoder, item.audioPath, norm.gainDb, norm.hasStaticAnalysis) }
            .onSuccess { loaded ->
                if (!loaded) preloadedItem = null
            }
            .onFailure { error ->
                Log.w(PlaybackLogTag, "Unable to preload next id=${item.trackId}", error)
                preloadedItem = null
            }
    }

    internal var heartbeatJob: Job? = null
    internal var heartbeatNextAttemptAtMs = 0L
    internal var heartbeatRetryDelayMs = 0L
    internal var heartbeatWasEnabled = false

    internal var roamJob: Job? = null
    internal var roamNextAttemptAtMs = 0L
    internal var roamRetryDelayMs = 0L
    internal var roamWasEnabled = false

    internal var similarJob: Job? = null
    internal var similarNextAttemptAtMs = 0L
    internal var similarRetryDelayMs = 0L
    internal var similarWasEnabled = false

    internal var prefetchJob: Job? = null
    internal var prefetchedTrackId: String? = null

    /** Native promotes audio first; this service transaction promotes queue/UI metadata. */
    private suspend fun consumeNativeTransition() {
        val transition = decoder?.consumeTransition() ?: return
        val incoming = preloadedItem ?: return
        val previousItem = (state.value as? PlaybackState.Playing)?.item
        val queueTransition = queue.next()
        if (queueTransition !is QueueTransition.Play || queueTransition.trackId != incoming.trackId) {
            fail(incoming.trackId, "Native transition no longer matches the playback queue")
            return
        }
        preloadedItem = null
        clearAbLoopIfTrackChanged(incoming.trackId)
        val currentDecoder = decoder ?: return
        val positionMs = currentDecoder.positionMs()
        val durationMs = streamAwareDuration(incoming, currentDecoder.durationMs())
        if (transition == NativeTransition.CrossfadeStarted) {
            listeningFadeOutgoing = previousItem?.trackId
            listeningFadeStartedAt = System.currentTimeMillis()
            listeningFadeStartedElapsed = SystemClock.elapsedRealtime()
            listeningFadeMaxMs = artworkCrossfade.value?.durationMs ?: 0L
        }
        currentStreamSongId = streamSongIdOf(incoming)
        currentStreamStartedAt = SystemClock.elapsedRealtime()
        lastStreamSongId = currentStreamSongId
        lastStreamPositionMs = positionMs
        pendingRecoverySeek = null
        lastSeekAtMs = 0L
        lastSeekTrackId = null
        if (recoveryTrackId != incoming.trackId) {
            recoveryTrackId = incoming.trackId
            temporaryRecoveryAttempts = 0
        }
        lastRecoveryPositionMs = -1L
        cleanEofRetriedTrackId = null
        cleanEofRetriedPositionMs = -1L
        currentStreamSongId?.let { notePreviewPlayback(it) }

        state.value = PlaybackState.Playing(incoming, positionMs, durationMs)
        enqueueListening(listeningTracker.finish(PlaybackEndReason.COMPLETED, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        enqueueListening(listeningTracker.start(incoming.trackId, positionMs, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        publishNowPlaying(incoming, AndroidMediaPlaybackState.STATE_PLAYING, positionMs, durationMs)
        updateNowPlayingTransportState()
        showForeground(incoming)
        previousItem?.let { enqueueBackgroundLoudnessAnalysis(it) }
        enqueueBackgroundLoudnessAnalysis(incoming)
        // During a crossfade both native slots are live (incoming + outgoing).
        // Loading i+2 here would reuse the outgoing slot and cut i off instead
        // of letting it fade out. The ticker reloads after the fade completes.
        if (canPreloadNext(currentDecoder.isCrossfading())) preloadNext()
        publishQueue()
        Log.d(PlaybackLogTag, "Consumed native transition=$transition id=${incoming.trackId}")
    }



    /**
     * 位置刷新走独立 200ms ticker。解码器仍未做线程安全封装，所以这里用 tryLock 机会式读取：
     * 拿不到锁（正在切歌/准备）就跳过这一拍，绝不排队等锁。
     */
    private fun tickPlaybackPosition() {
        if (!commandMutex.tryLock()) return
        try {
            val current = state.value as? PlaybackState.Playing ?: return
            val activeDecoder = decoder ?: return
            val positionMs = clampSeekPosition(activeDecoder.positionMs(), current.durationMs)
            val ab = abLoopState.value
            if (ab.isActive && ab.trackId == current.item.trackId && positionMs >= ab.pointB!!) {
                seekCurrent(ab.pointA!!)
                return
            }
            val now = SystemClock.elapsedRealtime()
            val nativeBuffering = activeDecoder.isBuffering()
            if (!nativeBuffering) seekBufferingGraceUntilMs = 0L
            val isBuffering = effectiveStreamBufferingState(nativeBuffering, now, seekBufferingGraceUntilMs)
            if (currentStreamSongId != null) lastStreamPositionMs = positionMs
            if (positionMs != current.positionMs || isBuffering != current.isBuffering) {
                // 只在状态没被 seek/暂停改过时前移，避免用旧读数覆盖新位置。
                state.update { latest ->
                    if (latest is PlaybackState.Playing &&
                        latest.item.trackId == current.item.trackId &&
                        latest.positionMs == current.positionMs &&
                        latest.isBuffering == current.isBuffering
                    ) {
                        latest.copy(positionMs = positionMs, isBuffering = isBuffering)
                    } else {
                        latest
                    }
                }
            }
            val bufferingChanged = isBuffering != current.isBuffering
            if (!bufferingChanged && now - lastSessionPositionAtMs < 1_000L) return
            lastSessionPositionAtMs = now
            val sessionState = if (isBuffering) AndroidMediaPlaybackState.STATE_BUFFERING else AndroidMediaPlaybackState.STATE_PLAYING
            mediaSession.setPlaybackState(androidPlaybackState(sessionState, positionMs))
        } finally {
            commandMutex.unlock()
        }
    }



    private fun enqueueListening(writes: List<ListeningWrite>) {
        writes.forEach { write ->
            if (listeningWrites.trySend(write).isFailure) Log.w(PlaybackLogTag, "Listening write queue is full")
        }
    }

    private fun finishListeningCrossfade() {
        val outgoing = listeningFadeOutgoing ?: return
        val elapsed = (SystemClock.elapsedRealtime() - listeningFadeStartedElapsed).coerceAtLeast(0).coerceAtMost(listeningFadeMaxMs)
        enqueueListening(listeningTracker.splitCrossfadeOverlap(outgoing, listeningFadeStartedAt, elapsed))
        listeningFadeOutgoing = null
        listeningFadeStartedAt = 0
        listeningFadeStartedElapsed = 0
        listeningFadeMaxMs = 0
    }

    private fun clearAbLoopIfTrackChanged(trackId: String) {
        val current = abLoopState.value
        if (current.trackId.isNotEmpty() && current.trackId != trackId) {
            abLoopState.value = AbLoopState()
        }
    }

    private fun setAbLoopPointLocked(isA: Boolean) {
        val (item, fallbackPosMs) = when (val current = state.value) {
            is PlaybackState.Playing -> current.item to current.positionMs
            is PlaybackState.Paused -> current.item to current.positionMs
            else -> return
        }
        val posMs = decoder?.positionMs() ?: fallbackPosMs
        val current = abLoopState.value
        if (isA) {
            val keepB = current.trackId == item.trackId && current.pointB != null && current.pointB > posMs + 200L
            abLoopState.value = AbLoopState(
                trackId = item.trackId,
                pointA = posMs,
                pointB = if (keepB) current.pointB else null,
            )
            return
        }
        val existingA = if (current.trackId == item.trackId) current.pointA else null
        if (existingA == null) {
            abLoopState.value = AbLoopState(
                trackId = item.trackId,
                pointA = 0L,
                pointB = posMs.coerceAtLeast(500L),
            )
        } else if (posMs > existingA + 200L) {
            abLoopState.value = AbLoopState(trackId = item.trackId, pointA = existingA, pointB = posMs)
            if (decoder?.isCrossfading() == true) {
                clearArtworkCrossfade()
                decoder?.snapCrossfade()
            }
            seekCurrent(existingA)
        } else if (existingA > posMs + 200L) {
            abLoopState.value = AbLoopState(trackId = item.trackId, pointA = posMs, pointB = existingA)
        }
    }

    private fun fineTuneAbLoopPointLocked(isA: Boolean, deltaMs: Long) {
        val (item, durationMs) = when (val current = state.value) {
            is PlaybackState.Playing -> current.item to current.durationMs
            is PlaybackState.Paused -> current.item to current.durationMs
            else -> return
        }
        val effectiveDurationMs = (decoder?.durationMs()?.takeIf { it > 0L } ?: durationMs)
        val updated = fineTuneAbLoop(
            state = abLoopState.value,
            trackId = item.trackId,
            isPointA = isA,
            deltaMs = deltaMs,
            durationMs = effectiveDurationMs,
        )
        abLoopState.value = updated
        if (updated.isActive) {
            if (decoder?.isCrossfading() == true) {
                clearArtworkCrossfade()
                decoder?.snapCrossfade()
            }
            val currentPosMs = decoder?.positionMs() ?: 0L
            val pointA = updated.pointA ?: return
            val pointB = updated.pointB ?: return
            if (currentPosMs < pointA || currentPosMs >= pointB) {
                seekCurrent(pointA)
            }
        }
    }

    private fun deduplicateQueueLocked() {
        val store = com.exodidio.tune.sync.AndroidSyncRuntime.syncStore
        val removed = queue.deduplicateQueue { trackId ->
            val track = store.trackForPlayback(trackId)
            if (track != null) trackDedupKey(track).ifEmpty { trackId } else trackId
        }
        if (removed > 0) {
            notificationRequests.trySend(Unit)
        }
    }

    private suspend fun setSleepTimerLocked(minutes: Int, afterTrack: Boolean) {
        if (afterTrack) {
            sleepTimerState.value = SleepTimerState(SleepTimerMode.AfterCurrentTrack, 0L)
            decoder?.setFocusGain(if (isDucked) DuckedFocusGain else 1f)
            decoder?.clearPreloaded()
            preloadedItem = null
            return
        }
        if (minutes <= 0) {
            cancelSleepTimerLocked()
            return
        }
        val totalMs = minutes.toLong() * 60_000L
        val endElapsed = SystemClock.elapsedRealtime() + totalMs
        sleepTimerState.value = SleepTimerState(SleepTimerMode.Countdown(endElapsed, totalMs), totalMs)
        decoder?.setFocusGain(if (isDucked) DuckedFocusGain else 1f)
        if (canPreloadNext(decoder?.isCrossfading() == true)) preloadNext()
    }

    private suspend fun cancelSleepTimerLocked() {
        val wasAfterTrack = sleepTimerState.value.afterCurrentTrack
        sleepTimerState.value = SleepTimerState()
        decoder?.setFocusGain(if (isDucked) DuckedFocusGain else 1f)
        if (wasAfterTrack && canPreloadNext(decoder?.isCrossfading() == true)) preloadNext()
    }

    private suspend fun tickSleepTimerLocked() {
        val current = sleepTimerState.value
        val mode = current.mode as? SleepTimerMode.Countdown ?: return
        val remaining = (mode.endElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (remaining <= 0L) {
            sleepTimerState.value = SleepTimerState()
            resumeOnFocusGain = false
            pauseCurrent()
            decoder?.setFocusGain(if (isDucked) DuckedFocusGain else 1f)
            return
        }
        if (remaining != current.remainingMs) {
            sleepTimerState.value = current.copy(remainingMs = remaining)
        }
        if (remaining <= 5_000L && state.value is PlaybackState.Playing) {
            val fade = (remaining / 5_000f).coerceIn(0.05f, 1f)
            val base = if (isDucked) DuckedFocusGain else 1f
            decoder?.setFocusGain(base * fade)
        }
    }

    companion object {
        internal const val ActionPlay = "com.exodidio.tune.player.PLAY"
        internal const val ActionShuffle = "com.exodidio.tune.player.SHUFFLE"
        internal const val ActionPause = "com.exodidio.tune.player.PAUSE"
        private const val ActionPauseForTransientFocusLoss = "com.exodidio.tune.player.PAUSE_FOR_TRANSIENT_FOCUS_LOSS"
        private const val ActionDuck = "com.exodidio.tune.player.DUCK"
        private const val ActionRestoreFocus = "com.exodidio.tune.player.RESTORE_FOCUS"
        internal const val ActionResume = "com.exodidio.tune.player.RESUME"
        internal const val ActionToggle = "com.exodidio.tune.player.TOGGLE"
        internal const val ActionStop = "com.exodidio.tune.player.STOP"
        internal const val ActionClearQueue = "com.exodidio.tune.player.CLEAR_QUEUE"
        internal const val ActionNext = "com.exodidio.tune.player.NEXT"
        internal const val ActionPrevious = "com.exodidio.tune.player.PREVIOUS"
        internal const val ActionSeek = "com.exodidio.tune.player.SEEK"
        internal const val ActionSetShuffle = "com.exodidio.tune.player.SET_SHUFFLE"
        internal const val ActionSetRepeat = "com.exodidio.tune.player.SET_REPEAT"
        internal const val ActionPlayNext = "com.exodidio.tune.player.PLAY_NEXT"
        internal const val ActionAppend = "com.exodidio.tune.player.APPEND"
        internal const val ActionSelect = "com.exodidio.tune.player.SELECT"
        internal const val ActionRemove = "com.exodidio.tune.player.REMOVE"
        internal const val ActionReorder = "com.exodidio.tune.player.REORDER"
        internal const val ActionStartMoodRadio = "com.exodidio.tune.player.START_MOOD_RADIO"
        internal const val ActionToggleFavorite = "com.exodidio.tune.player.TOGGLE_FAVORITE"
        internal const val ActionCycleRepeat = "com.exodidio.tune.player.CYCLE_REPEAT"
        internal const val TrackIdsExtra = "track_ids"
        internal const val TrackIdExtra = "track_id"
        internal const val StartIndexExtra = "start_index"
        internal const val HeartbeatStartExtra = "heartbeat_start"
        internal const val RoamStartExtra = "roam_start"
        internal const val SimilarStartExtra = "similar_start"
        internal const val PositionMsExtra = "position_ms"
        internal const val EnabledExtra = "enabled"
        internal const val RepeatModeExtra = "repeat_mode"
        private const val PreviousRestartThresholdMs = 3_000L
        // 连点静默窗：最后一次点击后停 250ms 才启动；两击间隔 ≤400ms 视为连点。
        private const val SkipStartQuietMs = 250L
        private const val SkipBurstGapMs = 400L
        private const val DuckedFocusGain = 0.2f
        internal const val ChannelId = "playback"
        /** 小米系统媒体控件读取的“原生歌词” MediaMetadata 键（值为标准 LRC 文本）。 */
        internal const val NativeLyricMetadataKey = "android.media.metadata.LYRIC"
        internal const val LyricsChannelId = "lyrics"
        internal const val NotificationId = 2002
        internal const val LyricsNotificationId = 1702
        internal const val NowPlayingArtworkSizePx = 512
        internal const val LargeNowPlayingArtworkSizePx = 1024
        internal const val StreamUrlTimeoutMs = 12_000L
        // 参考 MeiloX：临时失败最多 3 次原地重试，线性退避；持续播放 10 秒才算恢复。
        internal const val MaxTemporaryRecoveryAttempts = 3
        internal const val TemporaryRecoveryDelayMs = 1_000L
        private const val HealthyPlaybackMs = 10_000L
        internal const val HeartbeatRetryMaxDelayMs = 15_000L
        private val PreloadResyncActions = setOf(
            ActionSetShuffle, ActionSetRepeat, ActionCycleRepeat, ActionPlayNext, ActionRemove, ActionReorder, ActionDeduplicateQueue, ActionStartMoodRadio,
        )
        // 只有换曲指令才取消进行中的远程下载，暂停/跳转不该打断。
        private val LoadCancellingActions = setOf(
            ActionPlay, ActionShuffle, ActionNext, ActionPrevious, ActionSelect, ActionStartMoodRadio, ActionClearQueue,
        )
        // 连点时后一条取代前一条；上一首/下一首例外，点几下就走几步。
        private val SelectionActions = setOf(
            ActionPlay, ActionShuffle, ActionSelect, ActionStartMoodRadio,
        )
        private val MoodRadioStoppingActions = setOf(
            ActionPlay, ActionShuffle, ActionStop, ActionClearQueue, ActionPlayNext, ActionAppend, ActionRemove, ActionReorder, ActionDeduplicateQueue,
        )
        // 换队列即离开心动模式；队列内选歌/上一首/下一首不算。
        private val HeartbeatExitActions = setOf(ActionPlay, ActionShuffle, ActionClearQueue)
        private val PlaybackForegroundPromotionActions = setOf(
            ActionPlay, ActionShuffle, ActionSelect, ActionStartMoodRadio, ActionToggle, ActionResume, ActionNext, ActionPrevious,
        )
        internal val nowPlayingArtworkCache = LruCache<String, Bitmap>(20)
        internal val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        internal val queueState = MutableStateFlow(PlaybackQueueSnapshot())
        internal val crossfadeSeconds = MutableStateFlow(CrossfadeDisabledSeconds)
        internal val blendArtworkDuringCrossfade = MutableStateFlow(true)
        internal val gaplessPlayback = MutableStateFlow(true)
        internal val artworkCrossfade = MutableStateFlow<ArtworkCrossfadeTransition?>(null)
        internal val moodRadioActive = MutableStateFlow(false)
        internal val playbackSpeed = MutableStateFlow(1f)
        internal val abLoopState = MutableStateFlow(AbLoopState())
        internal val sleepTimerState = MutableStateFlow(SleepTimerState())
        @Volatile internal var isRunning = false
        @Volatile internal var activeInstance: PlaybackService? = null
        @Volatile internal var funFeaturesSettings = FunFeaturesSettings()

        internal fun updateCassetteScrub(active: Boolean, pitchFactor: Float) {
            if (active && !funFeaturesSettings.cassetteMode) return
            val service = activeInstance ?: return
            val readLock = service.decoderAccessLock.readLock()
            if (!readLock.tryLock()) return
            try {
                service.decoder?.setCassetteScrub(active, pitchFactor)
            } finally {
                readLock.unlock()
            }
        }

        internal fun readAudioLevels(outLevels: FloatArray): Boolean {
            val service = activeInstance ?: return false
            val readLock = service.decoderAccessLock.readLock()
            if (!readLock.tryLock()) return false
            return try {
                service.decoder?.readAudioLevels(outLevels) == true
            } finally {
                readLock.unlock()
            }
        }
        internal var nextArtworkCrossfadeId = 0L
        internal const val ActionSetCrossfade = "com.exodidio.tune.player.SET_CROSSFADE"
        internal const val CrossfadeSecondsExtra = "crossfade_seconds"
        internal const val ActionSetSpeed = "com.exodidio.tune.player.SET_SPEED"
        internal const val SpeedExtra = "speed"
        internal const val ActionSetAbLoopPointA = "com.exodidio.tune.player.SET_AB_POINT_A"
        internal const val ActionSetAbLoopPointB = "com.exodidio.tune.player.SET_AB_POINT_B"
        internal const val ActionFineTuneAbLoop = "com.exodidio.tune.player.FINE_TUNE_AB_LOOP"
        internal const val AbLoopPointIsAExtra = "ab_loop_is_a"
        internal const val AbLoopDeltaMsExtra = "ab_loop_delta_ms"
        internal const val ActionClearAbLoop = "com.exodidio.tune.player.CLEAR_AB_LOOP"
        internal const val ActionDeduplicateQueue = "com.exodidio.tune.player.DEDUPLICATE_QUEUE"
        internal const val ActionSetSleepTimer = "com.exodidio.tune.player.SET_SLEEP_TIMER"
        internal const val ActionCancelSleepTimer = "com.exodidio.tune.player.CANCEL_SLEEP_TIMER"
        internal const val SleepTimerMinutesExtra = "sleep_timer_minutes"
        internal const val SleepTimerAfterTrackExtra = "sleep_timer_after_track"
        internal fun intent(context: Context, action: String) = Intent(context, PlaybackService::class.java).setAction(action)
    }
}
