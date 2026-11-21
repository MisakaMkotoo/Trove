package com.exodidio.tune.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as AndroidMediaPlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.exodidio.tune.MainActivity
import com.exodidio.tune.R
import com.exodidio.tune.library.AudioTarget
import com.exodidio.tune.library.resolveAudioTarget
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.lastfm.AndroidLastFmRuntime
import com.exodidio.tune.lastfm.LastFmService
import com.exodidio.tune.lastfm.LastFmTrack
import com.exodidio.tune.mood.MoodRadioBatchSize
import com.exodidio.tune.mood.MoodRadioRefillThreshold
import com.exodidio.tune.mood.selectMoodRadio

/** 远程音频还没缓存：准备流程要交还给命令锁之外去下载。 */
private class RemoteAudioNotReady(val path: String, val url: String) : Exception("Remote audio not cached")

/** 流播直链还没取到：准备流程交还给命令锁之外去请求。 */
private class StreamUrlNotReady(val songId: Long) : Exception("Streaming url not ready")

/** 用户点播放后等直链取回的托管状态；被新指令取代或取回失败时清空。 */
private class PendingStreamResume(
    val songId: Long,
    val item: PlaybackItem,
    val positionMs: Long,
    val durationMs: Long,
    val token: Int,
)


/** Owns Android transport; queue semantics are delegated to sharedLogic. */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    // 每次指令进来就自增：正在下载的上一首尽快放弃，连续切歌不必排队等整首下完。
    private val loadToken = java.util.concurrent.atomic.AtomicInteger(0)
    // 在线流播放状态：用于识别“打开后立刻结束”的失败流。
    private var currentStreamSongId: Long? = null
    private var currentStreamStartedAt = 0L
    private var lastStreamPositionMs = 0L
    private var lastStreamSongId: Long? = null
    private var pendingStreamResume: PendingStreamResume? = null
    // 点选类指令的代际号：连点十来下时，旧指令轮到自己时已被更新，直接丢弃，
    // 只执行最后点的那一首，避免积压指令依次准备、自动连切。
    private val selectionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val restored = CompletableDeferred<Unit>()
    private lateinit var restoreJob: Job
    private val queue = PlaybackQueue()
    private var decoder: FfmpegDecoder? = null
    private var preloadedItem: PlaybackItem? = null
    private lateinit var sessionStore: PlaybackSessionStore
    private lateinit var playbackPreferences: PlaybackPreferences
    private lateinit var equalizerPreferences: EqualizerPreferences
    private lateinit var normalizationPreferences: NormalizationPreferences
    private lateinit var lastFm: LastFmService
    private lateinit var listeningTracker: ListeningTracker
    private val listeningWrites = Channel<ListeningWrite>(64)
    private lateinit var listeningWriter: Job
    private var listeningFadeOutgoing: String? = null
    private var listeningFadeStartedAt = 0L
    private var listeningFadeStartedElapsed = 0L
    private var listeningFadeMaxMs = 0L
    private var normalizationSettings = NormalizationSettings()
    private var equalizerSettings = EqualizerSettings()
    private var preferencesJob: Job? = null
    private var moodRadioJob: Job? = null
    private var moodRadioSeedId: String? = null
    private var moodRadioLastRefillAttempt: Pair<String?, Int>? = null
    private var resumeOnFocusGain = false
    private var isDucked = false
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession
    private lateinit var focusRequest: AudioFocusRequest
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (audioBecomingNoisyRequiresPause(intent.action)) dispatch(ActionPause)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AndroidPlaybackRuntime.initialize(applicationContext, AndroidSyncRuntime.syncStore)
        lastFm = AndroidLastFmRuntime.initialize(applicationContext, AndroidSyncRuntime.syncStore)
        listeningTracker = ListeningTracker("local") { UUID.randomUUID().toString() }
        listeningWriter = scope.launch {
            for (write in listeningWrites) AndroidSyncRuntime.syncStore.recordListening(write)
        }
        runBlocking {
            val now = System.currentTimeMillis()
            AndroidSyncRuntime.syncStore.recoverOpenPlaybackAttempts(now)
            AndroidSyncRuntime.syncStore.cleanupListening(now - ListeningRetentionMs)
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
        preferencesJob = scope.launch {
            playbackPreferences.settings.collectLatest { settings ->
                commandMutex.withLock {
                    crossfadeSeconds.value = settings.seconds
                    blendArtworkDuringCrossfade.value = settings.blendArtworkDuringCrossfade
                    if (!settings.blendArtworkDuringCrossfade) clearArtworkCrossfade()
                    // A preference update must never change a fade already
                    // running, but it does refresh the idle source afterward.
                    if (decoder?.isCrossfading() != true) preloadNext()
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
            AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow.collect { tracks ->
                val current = when (val snapshot = state.value) {
                    is PlaybackState.Playing -> snapshot.item
                    is PlaybackState.Paused -> snapshot.item
                    is PlaybackState.Preparing -> snapshot.item
                    else -> null
                } ?: return@collect
                val artwork = tracks.firstOrNull { it.id == current.trackId }?.artworkPath ?: return@collect
                if (artwork == current.artworkPath) return@collect
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
        restoreJob = scope.launch {
            try {
                sessionStore.load()?.let { session ->
                    val saved = session.queue
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
                commandMutex.withLock {
                    refreshPlaybackPosition()
                    val playing = state.value as? PlaybackState.Playing
                    if (playing != null) enqueueListening(listeningTracker.tick(
                        playing.positionMs, playing.durationMs, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                    ))
                    consumeNativeTransition()
                    if (listeningFadeOutgoing != null && decoder?.isCrossfading() != true) finishListeningCrossfade()
                    if (decoder?.isCrossfading() != true) clearArtworkCrossfade()
                    if (audioOutputDisconnectRequiresRecovery(decoder?.isOutputDisconnected() == true)) {
                        recoverAfterOutputDisconnect()
                    } else if (maybeStartCrossfade()) {
                        consumeNativeTransition()
                    } else if (decoder?.isFinished() == true && state.value is PlaybackState.Playing) {
                        val current = state.value as PlaybackState.Playing
                        val streamId = currentStreamSongId
                        val proxy = com.exodidio.tune.netease.NeteaseStreamProxy
                        val duration = decoder?.durationMs() ?: current.durationMs
                        val endedImmediately = SystemClock.elapsedRealtime() - currentStreamStartedAt < 3_000L
                        val clampMs = streamId?.let { songId ->
                            val prefixMs = proxy.cachedPrefixMs(songId, duration)
                            if (prefixMs > 0L) prefixMs
                            else if (lastStreamSongId == songId) lastStreamPositionMs
                            else current.positionMs
                        }
                        val failedRecently = streamId != null && proxy.hasRecentUpstreamError(streamId)
                        val incomplete = duration <= 0L || (clampMs ?: 0L) < duration - 500L
                        if (streamId != null && clampMs != null && (endedImmediately || (failedRecently && incomplete))) {
                            // 断网/读失败：卡在已缓存末尾并暂停，不退出播放入口，也不自动跳下一首。
                            System.err.println("NETSTREAM clamp song=$streamId cachedMs=$clampMs duration=$duration failed=$failedRecently")
                            lastStreamPositionMs = clampMs
                            lastStreamSongId = streamId
                            decoder?.seekTo(clampMs)
                            decoder?.pause()
                            state.value = PlaybackState.Paused(current.item, clampMs, duration)
                            publishNowPlaying(current.item, AndroidMediaPlaybackState.STATE_PAUSED, clampMs, duration)
                            updateNotification()
                            currentStreamSongId = null
                        } else {
                            if (streamId != null && endedImmediately) {
                                System.err.println("NETSTREAM ended immediately song=$streamId")
                            }
                            currentStreamSongId = null
                            handleTransition(queue.next(), PlaybackEndReason.COMPLETED)
                            publishQueue()
                        }
                    }
                    if (refillMoodRadioIfNeeded()) publishQueue()
                    maybeAppendHeartbeat()
                    maybeAppendRoam()
                    // A crossfade occupies both native source slots. Once its
                    // callback retires the outgoing item, populate that slot
                    // with the queue's new immediate successor.
                    if (canPreloadNext(decoder?.isCrossfading() == true)) preloadNext()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (playbackActionReplacesRestoredQueue(intent?.action)) {
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
            null -> Unit
            else -> dispatch(intent.action!!)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runBlocking {
            finishListeningCrossfade()
            enqueueListening(listeningTracker.finish(PlaybackEndReason.STOPPED, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
            listeningWrites.close()
            withTimeoutOrNull(2_000) { listeningWriter.join() }
        }
        runBlocking { sessionStore.save(currentSession()) }
        decoder?.close()
        preferencesJob?.cancel()
        moodRadioJob?.cancel()
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
    ): Job {
        // 心动模式/漫游绑定当前队列：任何换成另一个队列的操作都退出，模式自身的入队除外。
        if (action in HeartbeatExitActions) {
            if (!heartbeatStart) com.exodidio.tune.netease.NeteaseRepository.setHeartbeatEnabled(false)
            if (!roamStart) com.exodidio.tune.netease.NeteaseRepository.setRoamEnabled(false)
        }
        if (action in LoadCancellingActions || action == ActionPause) {
            loadToken.incrementAndGet()
            pendingStreamResume = null
            // 只翻标记还不够：阻塞中的网络读取最长要等 readTimeout 才返回，
            // 直接断开连接让旧下载立刻退出，指令才能真正马上处理。
            com.exodidio.tune.sync.cancelRemoteAudioDownloads()
        }
        // 点选类指令取一个代际号；轮到自己时若已有点得更晚的指令，整条丢弃。
        val dispatchToken = loadToken.get()
        val selectionTicket = if (action in SelectionActions) selectionGeneration.incrementAndGet() else 0
        val superseded = {
            selectionTicket != 0 && (selectionGeneration.get() != selectionTicket || loadToken.get() != dispatchToken)
        }
        return scope.launch {
        restored.await()
        // 网络的活儿只放在锁外：先把界面切到目标曲目并停掉旧声，下载完再进锁接管。
        if (selectionTicket != 0) prepareSelectionOffLock(action, trackIds, startIndex, superseded)
        commandMutex.withLock {
            if (superseded()) return@withLock
            consumeNativeTransition()
            Log.d(PlaybackLogTag, "Handling action=$action queueSize=${queue.snapshot().activeTrackIds.size}")
            if (action in MoodRadioStoppingActions) stopMoodRadio()
            when (action) {
                ActionPlay -> if (isAlreadyLoadingOrPlaying(trackIds.getOrNull(startIndex))) {
                    // 连点同一首：已经在准备/播放就不要重新下载、从头再播一遍。
                    QueueTransition.Unchanged
                } else {
                    handleTransition(runCatching { queue.play(PlaybackRequest(trackIds, startIndex)) }
                        .getOrElse { QueueTransition.Stop }, PlaybackEndReason.SKIPPED)
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
                            // 普通准备中再点没有新意义；重连中再点表示取消。
                            if (current.resumeIntent) cancelPendingStreamResume()
                        }
                        else -> resumeCurrent()
                    }
                }
                ActionStop -> stopPlayback()
                ActionClearQueue -> handleTransition(queue.clear())
                ActionNext -> handleTransition(queue.next(), PlaybackEndReason.SKIPPED, preservePlaybackState = true)
                ActionPrevious -> {
                    if ((decoder?.positionMs() ?: 0L) > PreviousRestartThresholdMs) seekCurrent(0L)
                    else handleTransition(queue.previous(), PlaybackEndReason.SKIPPED, preservePlaybackState = true)
                }
                ActionSeek -> seekCurrent(positionMs)
                ActionSetShuffle -> handleTransition(queue.setShuffle(enabled))
                ActionSetRepeat -> repeat?.let(queue::setRepeatMode)
                ActionPlayNext -> queue.playNext(trackIds)
                ActionAppend -> queue.append(trackIds)
                ActionStartMoodRadio -> trackIds.firstOrNull()?.let { startMoodRadio(it) }
                ActionSelect -> trackIds.firstOrNull()?.let { trackId ->
                    if (isAlreadyLoadingOrPlaying(trackId)) QueueTransition.Unchanged
                    else handleTransition(queue.select(trackId), PlaybackEndReason.SKIPPED)
                }
                ActionRemove -> trackIds.firstOrNull()?.let { handleTransition(queue.removeFromQueue(it), PlaybackEndReason.SKIPPED) }
                ActionReorder -> queue.reorderQueue(trackIds)
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

    private suspend fun startMoodRadio(seedId: String) {
        val store = AndroidSyncRuntime.syncStore
        if (!store.libraryAnalysisEnabled.first()) {
            Log.d(PlaybackLogTag, "Mood Radio ignored: library analysis is disabled")
            return
        }
        val tracks = store.moodRadioTracks()
        val selected = selectMoodRadio(seedId, tracks, emptySet(), MoodRadioBatchSize)
        if (selected.isEmpty()) {
            Log.d(PlaybackLogTag, "Mood Radio has no candidates seed=$seedId analyzed=${tracks.count { it.energy != null && it.danceability != null && it.brightness != null && it.tempo != null }}")
            return
        }
        moodRadioSeedId = seedId
        moodRadioLastRefillAttempt = null
        moodRadioActive.value = true
        if (queue.snapshot().currentTrackId == seedId) queue.replaceKeepingCurrent(listOf(seedId) + selected.map { it.id })
        else handleTransition(queue.play(PlaybackRequest(listOf(seedId) + selected.map { it.id })), PlaybackEndReason.SKIPPED)
        Log.d(PlaybackLogTag, "Mood Radio started seed=$seedId added=${selected.size} queueSize=${queue.snapshot().activeTrackIds.size}")
    }

    private suspend fun refillMoodRadioIfNeeded(): Boolean {
        val seedId = moodRadioSeedId ?: return false
        val snapshot = queue.snapshot()
        if (snapshot.activeTrackIds.size - snapshot.currentIndex - 1 >= MoodRadioRefillThreshold) return false
        val attempt = snapshot.currentTrackId to snapshot.activeTrackIds.size
        if (attempt == moodRadioLastRefillAttempt) return false
        moodRadioLastRefillAttempt = attempt
        val store = AndroidSyncRuntime.syncStore
        if (!store.libraryAnalysisEnabled.first()) return false
        val selected = selectMoodRadio(seedId, store.moodRadioTracks(), snapshot.activeTrackIds.toSet(), MoodRadioBatchSize)
        if (selected.isEmpty()) return false
        queue.append(selected.map { it.id })
        Log.d(PlaybackLogTag, "Mood Radio refilled seed=$seedId added=${selected.size} queueSize=${queue.snapshot().activeTrackIds.size}")
        return true
    }

    private fun stopMoodRadio() { moodRadioSeedId = null; moodRadioLastRefillAttempt = null; moodRadioActive.value = false }

    private suspend fun handleTransition(
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

    private suspend fun playCurrent(
        startPositionMs: Long = 0L,
        startPaused: Boolean = false,
        startTracking: Boolean = true,
        prepareIntent: Boolean = false,
    ) {
        finishListeningCrossfade()
        clearArtworkCrossfade()
        val trackId = queue.snapshot().currentTrackId ?: return stopPlayback()
        Log.d(PlaybackLogTag, "Preparing current queue track id=$trackId")
        val item = AndroidPlaybackRuntime.controller().resolve(trackId) ?: return fail(trackId, "Audio asset is not available")
        state.value = PlaybackState.Preparing(item, resumeIntent = prepareIntent)
        publishNowPlaying(item, AndroidMediaPlaybackState.STATE_BUFFERING, positionMs = startPositionMs, durationMs = 0L)
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return fail(trackId, "Audio focus was not granted")
        restoreFocusGain()
        try {
            decoder?.close()
            // A normalization lookup can suspend. Do not leave a closed decoder
            // reachable while a queued session write may read playback state.
            decoder = null
            val preparedDecoder = FfmpegDecoder()
            try {
                preparedDecoder.setGlobalDspConfig(equalizerDspConfig(equalizerSettings))
                preparedDecoder.setFocusGain(if (isDucked) DuckedFocusGain else 1f)
                preparedDecoder.preparePath(item.audioPath, normalizationGain(item, queue.peekNext()))
                if (startPositionMs > 0L) preparedDecoder.seekTo(clampSeekPosition(startPositionMs, preparedDecoder.durationMs()))
                if (startPaused) {
                    preparedDecoder.pause()
                    state.value = PlaybackState.Paused(item, preparedDecoder.positionMs(), preparedDecoder.durationMs())
                    publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, preparedDecoder.positionMs(), preparedDecoder.durationMs())
                } else {
                    preparedDecoder.play()
                    currentStreamSongId = streamSongIdOf(item)
                    currentStreamStartedAt = SystemClock.elapsedRealtime()
                    lastStreamSongId = currentStreamSongId
                    lastStreamPositionMs = preparedDecoder.positionMs()
                    state.value = PlaybackState.Playing(item, preparedDecoder.positionMs(), preparedDecoder.durationMs())
                    publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PLAYING, preparedDecoder.positionMs(), preparedDecoder.durationMs())
                }
                decoder = preparedDecoder
                lastFm.startPlayback(item.trackId, preparedDecoder.positionMs())
                if (!startPaused && startTracking) enqueueListening(listeningTracker.start(item.trackId, preparedDecoder.positionMs(), System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                if (!startPaused && !startTracking) listeningTracker.resumeAfterInterruption(SystemClock.elapsedRealtime())
            } catch (error: Throwable) {
                preparedDecoder.close()
                throw error
            }
            preloadNext()
            showForeground(item)
            Log.d(PlaybackLogTag, "Playback started id=$trackId durationMs=${decoder?.durationMs()}")
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is RemoteAudioNotReady) {
                // 界面已经切到这首歌并显示“准备中”，下载在锁外完成后自动接管播放。
                scheduleDeferredRemotePlay(error.url)
                return
            }
            if (error is StreamUrlNotReady) {
                // 直链在锁外获取，拿到后重新接管播放。
                System.err.println("NETSTREAM not ready song=${error.songId}; deferring")
                scheduleDeferredStreamPlay(error.songId, startPositionMs, prepareIntent)
                return
            }

            val songId = streamSongIdOf(item)
            if (songId != null) {
                // 在线歌曲连不上：退回暂停态而不是把播放入口整个收走，按播放可再次重连。
                settlePausedAfterStreamFailure(item, startPositionMs, 0L, error.message ?: "stream failed")
                return
            }
            fail(trackId, error.message ?: "Unable to decode audio")
        }
    }

    /** 锁外下载远程文件；下载完成且这一首仍是当前曲目时，回到锁内继续准备。 */
    private fun scheduleDeferredRemotePlay(url: String) {
        val token = loadToken.get()
        scope.launch {
            val file = com.exodidio.tune.sync.ensureRemoteAudioFile(applicationContext, url) {
                loadToken.get() != token
            }
            if (file == null || loadToken.get() != token) return@launch
            commandMutex.withLock {
                if (loadToken.get() != token) return@withLock
                val currentId = queue.snapshot().currentTrackId ?: return@withLock
                val item = AndroidPlaybackRuntime.controller().resolve(currentId) ?: return@withLock
                if (resolveAudioTarget(filesDir, item.audioPath) !is AudioTarget.RemoteTarget) return@withLock
                playCurrent()
            }
        }
    }

    /** Restores the selected item paused, so reopening the app never starts audio by itself. */
    private suspend fun restoreCurrent(savedPositionMs: Long) {
        val trackId = queue.snapshot().currentTrackId ?: return clearRestoredSession()
        val item = AndroidPlaybackRuntime.controller().resolve(trackId)
            ?: return clearRestoredSession()
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
            decoder?.close()
            decoder = FfmpegDecoder().also {
                it.setGlobalDspConfig(equalizerDspConfig(equalizerSettings))
                it.setFocusGain(if (isDucked) DuckedFocusGain else 1f)
                it.preparePath(item.audioPath, normalizationGain(item, queue.peekNext()))
                val positionMs = clampSeekPosition(savedPositionMs, it.durationMs())
                if (positionMs > 0L) it.seekTo(positionMs)
                it.pause()
                state.value = PlaybackState.Paused(item, positionMs, it.durationMs())
                publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, positionMs, it.durationMs())
                lastFm.startPlayback(item.trackId, positionMs)
            }
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
        finishListeningCrossfade()
        clearArtworkCrossfade()
        pendingStreamResume = null
        decoder?.pause()
        enqueueListening(listeningTracker.pause(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        (state.value as? PlaybackState.Playing)?.let { current ->
            val positionMs = decoder?.positionMs() ?: current.positionMs
            state.value = PlaybackState.Paused(current.item, positionMs, current.durationMs)
            publishNowPlaying(current.item, AndroidMediaPlaybackState.STATE_PAUSED, positionMs, current.durationMs)
        }
        // 远程歌曲仍在下载（Preparing）时暂停：下载已被取消，先把界面落到暂停态，按播放会续传。
        (state.value as? PlaybackState.Preparing)?.let { current ->
            state.value = PlaybackState.Paused(current.item, 0L, 0L)
            publishNowPlaying(current.item, AndroidMediaPlaybackState.STATE_PAUSED, 0L, 0L)
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
                currentDecoder.close()
                decoder = null
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
     * 在线歌曲的“按播放”：先立刻把按钮切成正在播放，再在锁外重新取直链（过期或被断网
     * 打回过的直链会刷新）。取不到就退回暂停态，用户随时可以再点一次重连。
     */
    private fun beginStreamResume(songId: Long, paused: PlaybackState.Paused) {
        val token = loadToken.get()
        val item = paused.item
        // 停在结尾（缓存刚好完整或上次自然结束）时从 0 重播，否则会一开就 EOF。
        val positionMs = if (paused.durationMs > 0L && paused.positionMs >= paused.durationMs - 500L) 0L
        else paused.positionMs
        decoder?.close()
        decoder = null
        currentStreamSongId = null
        pendingStreamResume = PendingStreamResume(songId, item, positionMs, paused.durationMs, token)
        state.value = PlaybackState.Preparing(item, resumeIntent = true)
        publishNowPlaying(item, AndroidMediaPlaybackState.STATE_BUFFERING, positionMs, paused.durationMs)
        val cachedCover = com.exodidio.tune.netease.NeteaseStreamProxy.hasCache(songId) &&
            com.exodidio.tune.netease.NeteaseStreamProxy.cachedPrefixMs(songId, paused.durationMs) > positionMs + 1_000L
        scope.launch {
            // 缓存已经覆盖当前位置时不必等网络，直接播放；只有够不着缓存才必须重取直链。
            val ready = cachedCover || (withTimeoutOrNull(StreamUrlTimeoutMs) {
                com.exodidio.tune.netease.NeteaseRepository.ensureResumableStreamUrl(songId)
            } ?: false)
            if (pendingStreamResume?.token != token || loadToken.get() != token) return@launch
            commandMutex.withLock {
                if (pendingStreamResume?.token != token || loadToken.get() != token) return@withLock
                pendingStreamResume = null
                if (!ready || queue.snapshot().currentTrackId != item.trackId) {
                    settlePausedAfterStreamFailure(item, positionMs, paused.durationMs, "reconnect failed")
                    return@withLock
                }
                playCurrent(positionMs, prepareIntent = true)
            }
        }
    }

    /** 重连途中再点一次：取消这次尝试，回到暂停态。 */
    private fun cancelPendingStreamResume() {
        val pending = pendingStreamResume ?: return
        pendingStreamResume = null
        state.value = PlaybackState.Paused(pending.item, pending.positionMs, pending.durationMs)
        publishNowPlaying(pending.item, AndroidMediaPlaybackState.STATE_PAUSED, pending.positionMs, pending.durationMs)
        updateNotification()
    }

    /** 在线歌曲连不上：保留曲目并按缓存末尾/原位置暂停，绝不把播放入口收掉。 */
    private fun settlePausedAfterStreamFailure(item: PlaybackItem, positionMs: Long, durationMs: Long, reason: String) {
        Log.w(PlaybackLogTag, "Stream playback unavailable id=${item.trackId} reason=$reason")
        val songId = streamSongIdOf(item)
        decoder?.close()
        decoder = null
        currentStreamSongId = null
        pendingStreamResume = null
        val cachedMs = songId
            ?.let { com.exodidio.tune.netease.NeteaseStreamProxy.cachedPrefixMs(it, durationMs) }
            ?.takeIf { it > 0L }
            ?: positionMs
        val settledMs = cachedMs.coerceAtLeast(0L)
        state.value = PlaybackState.Paused(item, settledMs, durationMs)
        publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, settledMs, durationMs)
        updateNotification()
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
        decoder?.close()
        decoder = null
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
            else -> return
        }
        val targetPositionMs = clampSeekPosition(requestedPositionMs, durationMs)
        finishListeningCrossfade()
        clearArtworkCrossfade()
        decoder?.seekTo(targetPositionMs) ?: return
        lastFm.seek(targetPositionMs)
        if (playing) {
            state.value = PlaybackState.Playing(item, targetPositionMs, durationMs)
            publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PLAYING, targetPositionMs, durationMs)
        } else {
            state.value = PlaybackState.Paused(item, targetPositionMs, durationMs)
            publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, targetPositionMs, durationMs)
        }
        Log.d(PlaybackLogTag, "Seek requested id=${item.trackId} targetMs=$targetPositionMs playing=$playing")
    }

    private fun stopPlayback() {
        finishListeningCrossfade()
        enqueueListening(listeningTracker.finish(PlaybackEndReason.STOPPED, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        clearArtworkCrossfade()
        decoder?.snapCrossfade()
        decoder?.close(); decoder = null; preloadedItem = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        state.value = PlaybackState.Idle
        mediaSession.setPlaybackState(androidPlaybackState(AndroidMediaPlaybackState.STATE_STOPPED, 0L))
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Retains the final item when repeat-off playback or manual navigation exhausts the queue. */
    private fun stopAtCurrentTrack(reason: PlaybackEndReason = PlaybackEndReason.COMPLETED) {
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
        decoder?.snapCrossfade()
        decoder?.close(); decoder = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        val positionMs = stoppedCurrentPosition(reason, durationMs)
        state.value = PlaybackState.Paused(item, positionMs, durationMs)
        publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, positionMs, durationMs)
        updateNotification()
        Log.d(PlaybackLogTag, "Playback stopped at final queue track id=${item.trackId} positionMs=$positionMs")
    }

    private fun fail(trackId: String?, reason: String) {
        Log.e(PlaybackLogTag, "Playback failed id=$trackId reason=$reason")
        finishListeningCrossfade()
        enqueueListening(listeningTracker.finish(PlaybackEndReason.STOPPED, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        clearArtworkCrossfade()
        decoder?.close(); decoder = null; preloadedItem = null
        state.value = PlaybackState.Failed(trackId, reason)
        mediaSession.setPlaybackState(androidPlaybackState(AndroidMediaPlaybackState.STATE_ERROR, 0L))
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun scheduleDeferredStreamPlay(
        songId: Long,
        positionMs: Long = 0L,
        prepareIntent: Boolean = false,
    ) {
        val token = loadToken.get()
        scope.launch {
            val ready = withTimeoutOrNull(StreamUrlTimeoutMs) {
                com.exodidio.tune.netease.NeteaseRepository.ensureResumableStreamUrl(songId)
            } ?: false
            if (loadToken.get() != token) return@launch
            commandMutex.withLock {
                if (loadToken.get() != token) return@withLock
                if (queue.snapshot().currentTrackId != "netease:$songId") return@withLock
                val item = AndroidPlaybackRuntime.controller().resolve("netease:$songId") ?: return@withLock
                if (!ready || resolveAudioTarget(filesDir, item.audioPath) !is AudioTarget.StreamTarget) {
                    // 取不到直链：停在暂停态，按播放会再次尝试联网。
                    System.err.println("NETSTREAM deferred failed song=$songId ready=$ready")
                    settlePausedAfterStreamFailure(item, positionMs, 0L, "stream url unavailable")
                    return@withLock
                }
                playCurrent(positionMs, prepareIntent = prepareIntent)
            }
        }
    }

    private fun streamSongIdOf(item: PlaybackItem): Long? = item.audioPath
        ?.takeIf { it.startsWith("netease://") }
        ?.removePrefix("netease://")
        ?.toLongOrNull()

    private suspend fun publishQueue() {
        val snapshot = queue.snapshot()
        queueState.value = snapshot
        val tracks = AndroidSyncRuntime.syncStore.tracks.first().associateBy { it.id }
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
        // Capture before the asynchronous DataStore write. A later command can
        // close the decoder, but cannot change this immutable session snapshot.
        val session = currentSession(snapshot)
        scope.launch { sessionStore.save(session) }
    }

    private fun currentSession(snapshot: PlaybackQueueSnapshot = queue.snapshot()): PlaybackSession {
        val positionMs = when (val current = state.value) {
            is PlaybackState.Playing -> decoder?.positionMs() ?: current.positionMs
            is PlaybackState.Paused -> decoder?.positionMs() ?: current.positionMs
            else -> 0L
        }
        return PlaybackSession(snapshot, positionMs.coerceAtLeast(0L))
    }

    private suspend fun clearRestoredSession() {
        decoder?.close(); decoder = null; preloadedItem = null
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
    private suspend fun preloadNext() {
        val nextId = queue.peekNext()
        val currentDecoder = decoder ?: return
        if (nextId == preloadedItem?.trackId && currentDecoder.hasPreloaded()) return
        currentDecoder.clearPreloaded()
        val item = nextId?.let { id -> AndroidPlaybackRuntime.controller().resolve(id) }
        if (item == null) {
            preloadedItem = null
            return
        }
        val target = resolveAudioTarget(filesDir, item.audioPath)
        if (target is AudioTarget.StreamTarget) {
            // 在线歌曲只预取直链，不做解码器预加载。
            preloadedItem = null
            com.exodidio.tune.netease.NeteaseRepository.prefetchStreamUrl(item.trackId)
            return
        }
        if (target is AudioTarget.RemoteTarget) {
            // 预取是后台行为：不占命令锁，同一首只跑一个任务，切歌时随令牌失效。
            preloadedItem = null
            if (com.exodidio.tune.sync.remoteAudioCached(applicationContext, target.url)) return
            if (prefetchedTrackId == item.trackId && prefetchJob?.isActive == true) return
            prefetchedTrackId = item.trackId
            prefetchRemoteAudio(item)
            return
        }
        preloadedItem = item
        runCatching { currentDecoder.preloadPath(item.audioPath, normalizationGain(item, queue.peekNext())) }
            .onSuccess { loaded ->
                if (!loaded) preloadedItem = null
            }
            .onFailure { error ->
                Log.w(PlaybackLogTag, "Unable to preload next id=${item.trackId}", error)
                preloadedItem = null
            }
    }

    private var heartbeatJob: Job? = null
    private var heartbeatNextAttemptAtMs = 0L
    private var heartbeatRetryDelayMs = 0L
    private var heartbeatWasEnabled = false

    /**
     * 心动模式：当前歌曲在队尾（没有下一首）时取推荐。
     * 失败不清模式，按退避重试，直到队列被换掉或用户重新开播。
     */
    private fun maybeAppendHeartbeat() {
        val repository = com.exodidio.tune.netease.NeteaseRepository
        if (!repository.heartbeatEnabled.value) {
            heartbeatWasEnabled = false
            heartbeatNextAttemptAtMs = 0L
            heartbeatRetryDelayMs = 0L
            return
        }
        if (!heartbeatWasEnabled) {
            heartbeatWasEnabled = true
            heartbeatNextAttemptAtMs = 0L
            heartbeatRetryDelayMs = 0L
        }
        if (heartbeatJob?.isActive == true) return
        if (queue.peekNext() != null) return
        if (state.value !is PlaybackState.Playing && state.value !is PlaybackState.Paused) return
        if (android.os.SystemClock.elapsedRealtime() < heartbeatNextAttemptAtMs) return
        val snapshot = queue.snapshot()
        val currentId = snapshot.currentTrackId ?: return
        val headId = snapshot.originalTrackIds.firstOrNull()
        heartbeatJob = scope.launch {
            System.err.println("NETHB append trigger current=$currentId head=$headId")
            val appendId = runCatching {
                repository.appendHeartbeatNext(applicationContext, currentId, headId)
            }.getOrNull()
            if (appendId == null) {
                heartbeatRetryDelayMs = if (heartbeatRetryDelayMs == 0L) 2_000L
                else (heartbeatRetryDelayMs * 2).coerceAtMost(HeartbeatRetryMaxDelayMs)
                heartbeatNextAttemptAtMs = android.os.SystemClock.elapsedRealtime() + heartbeatRetryDelayMs
                System.err.println("NETHB append retry in ${heartbeatRetryDelayMs}ms")
                return@launch
            }
            heartbeatRetryDelayMs = 0L
            heartbeatNextAttemptAtMs = 0L
            // 取歌期间用户可能已经切了队列（心动模式随之退出），不要再往新队列里追加。
            if (!repository.heartbeatEnabled.value) return@launch
            commandMutex.withLock {
                if (!repository.heartbeatEnabled.value) return@withLock
                queue.append(listOf(appendId))
                publishQueue()
            }
        }
    }

    private var roamJob: Job? = null
    private var roamNextAttemptAtMs = 0L
    private var roamRetryDelayMs = 0L
    private var roamWasEnabled = false

    /** 私人漫游：当前歌曲在队尾时取下一批推荐；失败按退避重试，不关闭模式。 */
    private fun maybeAppendRoam() {
        val repository = com.exodidio.tune.netease.NeteaseRepository
        if (!repository.roamEnabled.value) {
            roamWasEnabled = false
            roamNextAttemptAtMs = 0L
            roamRetryDelayMs = 0L
            return
        }
        if (!roamWasEnabled) {
            roamWasEnabled = true
            roamNextAttemptAtMs = 0L
            roamRetryDelayMs = 0L
        }
        if (roamJob?.isActive == true) return
        if (queue.peekNext() != null) return
        if (state.value !is PlaybackState.Playing && state.value !is PlaybackState.Paused) return
        if (android.os.SystemClock.elapsedRealtime() < roamNextAttemptAtMs) return
        roamJob = scope.launch {
            val appendIds = runCatching { repository.appendRoamNext(applicationContext) }.getOrNull()
            if (appendIds.isNullOrEmpty()) {
                roamRetryDelayMs = if (roamRetryDelayMs == 0L) 2_000L
                else (roamRetryDelayMs * 2).coerceAtMost(HeartbeatRetryMaxDelayMs)
                roamNextAttemptAtMs = android.os.SystemClock.elapsedRealtime() + roamRetryDelayMs
                System.err.println("NETROAM append retry in ${roamRetryDelayMs}ms")
                return@launch
            }
            roamRetryDelayMs = 0L
            roamNextAttemptAtMs = 0L
            if (!repository.roamEnabled.value) return@launch
            commandMutex.withLock {
                if (!repository.roamEnabled.value) return@withLock
                queue.append(appendIds)
                publishQueue()
            }
        }
    }

    private var prefetchJob: Job? = null
    private var prefetchedTrackId: String? = null

    /** 后台把下一首远程文件拉到本地缓存；不占命令锁，随时可被新的预取取代。 */
    private fun prefetchRemoteAudio(item: PlaybackItem) {
        val target = resolveAudioTarget(filesDir, item.audioPath) as? AudioTarget.RemoteTarget ?: return
        prefetchJob?.cancel()
        val token = loadToken.get()
        prefetchJob = scope.launch {
            runCatching {
                com.exodidio.tune.sync.ensureRemoteAudioFile(applicationContext, target.url) {
                    !isActive || loadToken.get() != token
                }
            }
        }
    }

    /** Native promotes audio first; this service transaction promotes queue/UI metadata. */
    private suspend fun consumeNativeTransition() {
        val transition = decoder?.consumeTransition() ?: return
        val incoming = preloadedItem ?: return
        val queueTransition = queue.next()
        if (queueTransition !is QueueTransition.Play || queueTransition.trackId != incoming.trackId) {
            fail(incoming.trackId, "Native transition no longer matches the playback queue")
            return
        }
        preloadedItem = null
        val currentDecoder = decoder ?: return
        val positionMs = currentDecoder.positionMs()
        val durationMs = currentDecoder.durationMs()
        if (transition == NativeTransition.CrossfadeStarted) {
            listeningFadeOutgoing = (state.value as? PlaybackState.Playing)?.item?.trackId
            listeningFadeStartedAt = System.currentTimeMillis()
            listeningFadeStartedElapsed = SystemClock.elapsedRealtime()
            listeningFadeMaxMs = artworkCrossfade.value?.durationMs ?: 0L
        }
        state.value = PlaybackState.Playing(incoming, positionMs, durationMs)
        enqueueListening(listeningTracker.finish(PlaybackEndReason.COMPLETED, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        enqueueListening(listeningTracker.start(incoming.trackId, positionMs, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        lastFm.startPlayback(incoming.trackId, positionMs)
        publishNowPlaying(incoming, AndroidMediaPlaybackState.STATE_PLAYING, positionMs, durationMs)
        updateNotification()
        // During a crossfade both native slots are live (incoming + outgoing).
        // Loading i+2 here would reuse the outgoing slot and cut i off instead
        // of letting it fade out. The ticker reloads after the fade completes.
        if (canPreloadNext(currentDecoder.isCrossfading())) preloadNext()
        publishQueue()
        Log.d(PlaybackLogTag, "Consumed native transition=$transition id=${incoming.trackId}")
    }

    private suspend fun normalizationGain(item: PlaybackItem, nextId: String?): Float {
        val analyses = AndroidSyncRuntime.syncStore.activeAnalyses
        if (analyses.isEmpty()) {
            if (normalizationSettings.enabled) normalizationPreferences.disable()
            return 0f
        }
        val next = nextId?.let { AndroidPlaybackRuntime.controller().resolve(it) }
        val continuousAlbum = normalizationSettings.mode == NormalizationMode.Album &&
            item.albumId.isNotEmpty() && item.albumId == next?.albumId
        val albumAnalyses = if (continuousAlbum) {
            AndroidSyncRuntime.syncStore.tracks.first().filter { it.albumId == item.albumId }.mapNotNull { analyses[it.id] }
        } else emptyList()
        return normalizationGainDb(normalizationSettings, item.analysis, albumAnalyses, continuousAlbum)
    }

    private suspend fun refreshNormalizationGains() {
        val current = when (val value = state.value) {
            is PlaybackState.Playing -> value.item
            is PlaybackState.Paused -> value.item
            is PlaybackState.Preparing -> value.item
            else -> null
        } ?: return
        val nextId = queue.peekNext()
        decoder?.setNormalizationGains(normalizationGain(current, nextId), preloadedItem?.let { normalizationGain(it, queue.peekNext()) } ?: 0f)
    }

    private fun maybeStartCrossfade(): Boolean {
        val current = state.value as? PlaybackState.Playing ?: return false
        val currentDecoder = decoder ?: return false
        val incoming = preloadedItem ?: return false
        if (currentDecoder.isCrossfading()) return false
        if (!shouldStartCrossfade(
                crossfadeSeconds = crossfadeSeconds.value,
                positionMs = currentDecoder.positionMs(),
                durationMs = current.durationMs,
                hasPreloadedNext = preloadedItem != null && currentDecoder.hasPreloaded(),
            )
        ) return false
        val effectiveDurationMs = crossfadeDurationMs(
            crossfadeSeconds = crossfadeSeconds.value,
            positionMs = currentDecoder.positionMs(),
            durationMs = current.durationMs,
        )
        currentDecoder.beginCrossfade(effectiveDurationMs)
        if (currentDecoder.isCrossfading()) {
            nextArtworkCrossfadeId += 1
            artworkCrossfade.value = ArtworkCrossfadeTransition(
                id = nextArtworkCrossfadeId,
                fromArtworkPath = current.item.artworkPath,
                toArtworkPath = incoming.artworkPath,
                durationMs = effectiveDurationMs.coerceAtLeast(1L),
            )
        }
        return true
    }

    private fun clearArtworkCrossfade() {
        artworkCrossfade.value = null
    }

    private fun showForeground(item: PlaybackItem) {
        createChannel()
        startForeground(NotificationId, notification(item), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun updateNotification() {
        val item = when (val current = state.value) {
            is PlaybackState.Playing -> current.item
            is PlaybackState.Paused -> current.item
            else -> return
        }
        getSystemService(NotificationManager::class.java).notify(NotificationId, notification(item))
    }

    private fun registerNoisyAudioReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyAudioReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisyAudioReceiver, filter)
        }
    }

    /** Publishes metadata and transport state to Android System Now Playing surfaces. */
    private fun publishNowPlaying(item: PlaybackItem, state: Int, positionMs: Long, durationMs: Long) {
        mediaSession.isActive = true
        val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, item.trackId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        loadNowPlayingArtwork(item.artworkPath)?.let { artwork ->
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
        }
        mediaSession.setMetadata(metadata.build())
        mediaSession.setPlaybackState(androidPlaybackState(state, positionMs))
        Log.d(PlaybackLogTag, "Published Android Now Playing id=${item.trackId} state=$state")
    }

    private fun updateNowPlayingTransportState() {
        when (val current = state.value) {
            is PlaybackState.Playing -> mediaSession.setPlaybackState(
                androidPlaybackState(AndroidMediaPlaybackState.STATE_PLAYING, decoder?.positionMs() ?: current.positionMs),
            )
            is PlaybackState.Paused -> mediaSession.setPlaybackState(
                androidPlaybackState(AndroidMediaPlaybackState.STATE_PAUSED, decoder?.positionMs() ?: current.positionMs),
            )
            else -> Unit
        }
    }

    private fun refreshPlaybackPosition() {
        val current = state.value as? PlaybackState.Playing ?: return
        val positionMs = decoder?.positionMs()?.let { clampSeekPosition(it, current.durationMs) } ?: return
        if (currentStreamSongId != null) lastStreamPositionMs = positionMs
        if (positionMs == current.positionMs) return
        state.value = current.copy(positionMs = positionMs)
        mediaSession.setPlaybackState(androidPlaybackState(AndroidMediaPlaybackState.STATE_PLAYING, positionMs))
        lastFm.reportPlayback(current.item.toLastFmTrack(), positionMs, current.durationMs)
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

    private fun PlaybackItem.toLastFmTrack() = LastFmTrack(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        trackNumber = trackNumber,
    )

    private fun androidPlaybackState(state: Int, positionMs: Long): AndroidMediaPlaybackState =
        AndroidMediaPlaybackState.Builder()
            .setActions(
                AndroidMediaPlaybackState.ACTION_PLAY or
                    AndroidMediaPlaybackState.ACTION_PAUSE or
                    AndroidMediaPlaybackState.ACTION_PLAY_PAUSE or
                    AndroidMediaPlaybackState.ACTION_SKIP_TO_NEXT or
                    AndroidMediaPlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    AndroidMediaPlaybackState.ACTION_SEEK_TO or
                    AndroidMediaPlaybackState.ACTION_STOP,
            )
            .setActiveQueueItemId(activeQueueItemId(queue.snapshot()))
            .setState(state, positionMs, if (state == AndroidMediaPlaybackState.STATE_PLAYING) 1f else 0f)
            .build()

    private fun loadNowPlayingArtwork(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        nowPlayingArtworkCache.get(path)?.let { return it }
        val artwork = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= NowPlayingArtworkSizePx &&
                bounds.outHeight / (sampleSize * 2) >= NowPlayingArtworkSizePx
            ) sampleSize *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull()
        if (artwork != null) nowPlayingArtworkCache.put(path, artwork)
        else Log.w(PlaybackLogTag, "Unable to decode Now Playing artwork path=$path")
        return artwork
    }

    private fun FfmpegDecoder.preparePath(path: String, gain: Float) {
        when (val target = resolveAudioTarget(filesDir, path)) {
            is AudioTarget.FileTarget -> prepare(target.file, gain)
            is AudioTarget.ContentTarget -> {
                val fd = contentResolver.openFileDescriptor(Uri.parse(target.uri), "r")?.detachFd()
                    ?: error("Unable to open audio uri")
                prepareFd(fd, gain)
            }
            is AudioTarget.RemoteTarget -> {
                // 锁内绝不下载：未缓存就把这次准备交还给锁外的延迟任务。
                if (!com.exodidio.tune.sync.remoteAudioCached(this@PlaybackService, target.url)) {
                    throw RemoteAudioNotReady(path, target.url)
                }
                val file = com.exodidio.tune.sync.ensureRemoteAudioFile(this@PlaybackService, target.url) { false }
                    ?: error("Unable to read cached remote audio")
                prepare(java.io.File(file), gain)
            }
            is AudioTarget.StreamTarget -> {
                val songId = target.url.removePrefix("netease://").toLongOrNull()
                    ?: error("Bad streaming target")
                val repository = com.exodidio.tune.netease.NeteaseRepository
                if (repository.cachedStreamUrl(songId) == null && !repository.hasCachedStream(songId)) {
                    throw StreamUrlNotReady(songId)
                }
                val url = repository.playbackStreamUrl(songId)
                try {
                    prepareUrl(url, gain)
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    System.err.println("NETSTREAM prepare failed song=$songId: ${error.message}")
                    throw error
                }
            }
            null -> error("Empty audio path")
        }
    }

    private fun FfmpegDecoder.preloadPath(path: String, gain: Float): Boolean {
        return when (val target = resolveAudioTarget(filesDir, path)) {
            is AudioTarget.FileTarget -> preload(target.file, gain)
            is AudioTarget.ContentTarget -> {
                val fd = contentResolver.openFileDescriptor(Uri.parse(target.uri), "r")?.detachFd()
                    ?: error("Unable to open audio uri")
                preloadFd(fd, gain)
            }
            is AudioTarget.RemoteTarget -> {
                val token = loadToken.get()
                val file = com.exodidio.tune.sync.ensureRemoteAudioFile(
                    this@PlaybackService, target.url,
                ) { loadToken.get() != token }
                if (file == null) {
                    if (loadToken.get() != token) throw kotlinx.coroutines.CancellationException("Superseded preload")
                    error("Unable to download remote audio")
                }
                preload(java.io.File(file), gain)
            }
            is AudioTarget.StreamTarget -> false
            null -> error("Empty audio path")
        }
    }

    private fun notification(item: PlaybackItem): Notification = Notification.Builder(this, ChannelId)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(item.title)
        .setContentText(item.artist)
        .setContentIntent(nowPlayingContentIntent())
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken))
        .build()

    private fun nowPlayingContentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ChannelId, getString(R.string.playback_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
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
        internal const val TrackIdsExtra = "track_ids"
        internal const val TrackIdExtra = "track_id"
        internal const val StartIndexExtra = "start_index"
        internal const val HeartbeatStartExtra = "heartbeat_start"
        internal const val RoamStartExtra = "roam_start"
        internal const val PositionMsExtra = "position_ms"
        internal const val EnabledExtra = "enabled"
        internal const val RepeatModeExtra = "repeat_mode"
        private const val PreviousRestartThresholdMs = 3_000L
        private const val DuckedFocusGain = 0.2f
        private const val ChannelId = "playback"
        private const val NotificationId = 2002
        private const val NowPlayingArtworkSizePx = 512
        private const val StreamUrlTimeoutMs = 12_000L
        private const val HeartbeatRetryMaxDelayMs = 15_000L
        private const val ListeningRetentionMs = 180L * 24 * 60 * 60 * 1_000
        private val PreloadResyncActions = setOf(
            ActionSetShuffle, ActionSetRepeat, ActionPlayNext, ActionRemove, ActionReorder, ActionStartMoodRadio,
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
            ActionPlay, ActionShuffle, ActionStop, ActionClearQueue, ActionPlayNext, ActionAppend, ActionRemove, ActionReorder,
        )
        // 换队列即离开心动模式；队列内选歌/上一首/下一首不算。
        private val HeartbeatExitActions = setOf(ActionPlay, ActionShuffle, ActionClearQueue)
        private val nowPlayingArtworkCache = LruCache<String, Bitmap>(20)
        internal val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        internal val queueState = MutableStateFlow(PlaybackQueueSnapshot())
        internal val crossfadeSeconds = MutableStateFlow(CrossfadeDisabledSeconds)
        internal val blendArtworkDuringCrossfade = MutableStateFlow(true)
        internal val artworkCrossfade = MutableStateFlow<ArtworkCrossfadeTransition?>(null)
        internal val moodRadioActive = MutableStateFlow(false)
        private var nextArtworkCrossfadeId = 0L
        internal const val ActionSetCrossfade = "com.exodidio.tune.player.SET_CROSSFADE"
        internal const val CrossfadeSecondsExtra = "crossfade_seconds"
        internal fun intent(context: Context, action: String) = Intent(context, PlaybackService::class.java).setAction(action)
    }
}
