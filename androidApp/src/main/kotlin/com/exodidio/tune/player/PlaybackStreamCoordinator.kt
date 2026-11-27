package com.exodidio.tune.player

import android.media.session.PlaybackState as AndroidMediaPlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.exodidio.tune.R
import com.exodidio.tune.library.AudioTarget
import com.exodidio.tune.library.resolveAudioTarget
import com.exodidio.tune.mood.MoodRadioBatchSize
import com.exodidio.tune.mood.MoodRadioRefillThreshold
import com.exodidio.tune.mood.selectMoodRadio
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.NeteaseStreamProxy
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.ensureRemoteAudioFile
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.sync.remoteAudioCached
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 远程音频还没缓存：准备流程要交还给命令锁之外去下载。 */
internal class RemoteAudioNotReady(val path: String, val url: String) : Exception("Remote audio not cached")

/** 流播直链还没取到：准备流程交还给命令锁之外去请求。 */
internal class StreamUrlNotReady(val songId: Long) : Exception("Streaming url not ready")

/** 用户点播放后等直链取回的托管状态；被新指令取代或取回失败时清空。 */
internal class PendingStreamResume(
    val songId: Long,
    val item: PlaybackItem,
    val positionMs: Long,
    val durationMs: Long,
    val token: Int,
)

internal fun resolveSettledStreamFailurePosition(
    positionMs: Long,
    durationMs: Long,
): Long = if (durationMs > 0L) clampSeekPosition(positionMs, durationMs) else positionMs.coerceAtLeast(0L)

internal data class StreamClampSample(val songId: Long, val positionMs: Long)

internal enum class StreamCompletionAction {
    NormalCompletion,
    RecoverFromError,
    RecoverFromCleanPrematureEof,
}

internal fun classifyStreamCompletion(
    isPreview: Boolean,
    positionMs: Long,
    durationMs: Long,
    elapsedSinceStartMs: Long,
    hasUpstreamError: Boolean,
    wasStreamIncomplete: Boolean,
    alreadyRetriedCleanEofAtSamePos: Boolean,
): StreamCompletionAction {
    val endedImmediately = elapsedSinceStartMs < 3_000L
    val atEnd = durationMs > 0L && positionMs >= durationMs - 1_000L
    val networkOrProxyFailed = hasUpstreamError || wasStreamIncomplete
    if (endedImmediately && !atEnd) {
        return if (networkOrProxyFailed || !alreadyRetriedCleanEofAtSamePos) {
            if (networkOrProxyFailed) StreamCompletionAction.RecoverFromError
            else StreamCompletionAction.RecoverFromCleanPrematureEof
        } else {
            StreamCompletionAction.NormalCompletion
        }
    }
    if (networkOrProxyFailed && (durationMs <= 0L || positionMs < durationMs - 500L)) {
        return StreamCompletionAction.RecoverFromError
    }
    val farBeforeEnd = !isPreview && durationMs > 15_000L && positionMs < durationMs - 8_000L
    if (farBeforeEnd && !alreadyRetriedCleanEofAtSamePos) {
        return StreamCompletionAction.RecoverFromCleanPrematureEof
    }
    return StreamCompletionAction.NormalCompletion
}

internal const val SeekBufferingGraceMs = 200L
internal const val BufferingStallTimeoutMs = 14_000L
internal const val BufferingErrorStallTimeoutMs = 2_000L

internal data class BufferingStallAnchor(
    val trackId: String,
    val positionMs: Long,
    val bufferedAheadMs: Long,
    val sinceElapsedMs: Long,
)

internal fun effectiveStreamBufferingState(
    nativeBuffering: Boolean,
    nowElapsedMs: Long,
    seekGraceUntilElapsedMs: Long,
): Boolean {
    if (!nativeBuffering) return false
    return nowElapsedMs >= seekGraceUntilElapsedMs
}

internal fun nextBufferingStallAnchor(
    previous: BufferingStallAnchor?,
    trackId: String,
    positionMs: Long,
    bufferedAheadMs: Long,
    nowElapsedMs: Long,
): BufferingStallAnchor {
    if (previous == null ||
        previous.trackId != trackId ||
        positionMs > previous.positionMs + 50L ||
        bufferedAheadMs >= previous.bufferedAheadMs + 100L
    ) {
        return BufferingStallAnchor(trackId, positionMs, bufferedAheadMs, nowElapsedMs)
    }
    return previous
}

internal fun shouldRecoverStalledBuffering(
    anchor: BufferingStallAnchor,
    nowElapsedMs: Long,
    hasUpstreamError: Boolean,
    wasIncomplete: Boolean,
): Boolean {
    val stalledMs = (nowElapsedMs - anchor.sinceElapsedMs).coerceAtLeast(0L)
    if ((hasUpstreamError || wasIncomplete) && anchor.bufferedAheadMs < 250L && stalledMs >= BufferingErrorStallTimeoutMs) {
        return true
    }
    return stalledMs >= BufferingStallTimeoutMs
}

internal suspend fun PlaybackService.startMoodRadio(seedId: String) {
    val store = AndroidSyncRuntime.syncStore
    if (!store.libraryAnalysisEnabled.first()) {
        Log.d(PlaybackLogTag, "Mood Radio ignored: library analysis is disabled")
        return
    }
    val tracks = store.moodRadioTracks()
    val selected = selectMoodRadio(seedId, tracks, emptySet(), MoodRadioBatchSize)
    if (selected.isEmpty()) {
        Log.d(
            PlaybackLogTag,
            "Mood Radio has no candidates seed=$seedId analyzed=${tracks.count { it.energy != null && it.danceability != null && it.brightness != null && it.tempo != null }}",
        )
        return
    }
    moodRadioSeedId = seedId
    moodRadioLastRefillAttempt = null
    PlaybackService.moodRadioActive.value = true
    if (queue.snapshot().currentTrackId == seedId) queue.replaceKeepingCurrent(listOf(seedId) + selected.map { it.id })
    else handleTransition(queue.play(PlaybackRequest(listOf(seedId) + selected.map { it.id })), PlaybackEndReason.SKIPPED)
    Log.d(PlaybackLogTag, "Mood Radio started seed=$seedId added=${selected.size} queueSize=${queue.snapshot().activeTrackIds.size}")
}

/**
 * 锁内只做便宜判断；全库候选与 selectMoodRadio 的计算放到锁外任务里，
 * 完成后再回锁确认 seed 没变、追加队列。
 */
internal fun PlaybackService.maybeRefillMoodRadio() {
    val seedId = moodRadioSeedId ?: return
    if (moodRefillJob?.isActive == true) return
    val snapshot = queue.snapshot()
    if (snapshot.activeTrackIds.size - snapshot.currentIndex - 1 >= MoodRadioRefillThreshold) return
    val attempt = snapshot.currentTrackId to snapshot.activeTrackIds.size
    if (attempt == moodRadioLastRefillAttempt) return
    moodRadioLastRefillAttempt = attempt
    moodRefillJob = scope.launch {
        val store = AndroidSyncRuntime.syncStore
        if (!store.libraryAnalysisEnabled.first()) return@launch
        val selected = selectMoodRadio(seedId, store.moodRadioTracks(), snapshot.activeTrackIds.toSet(), MoodRadioBatchSize)
        if (selected.isEmpty()) return@launch
        commandMutex.withLock {
            if (moodRadioSeedId != seedId) return@withLock
            queue.append(selected.map { it.id })
            publishQueue()
            Log.d(PlaybackLogTag, "Mood Radio refilled seed=$seedId added=${selected.size} queueSize=${queue.snapshot().activeTrackIds.size}")
        }
    }
}

internal fun PlaybackService.stopMoodRadio() {
    moodRadioSeedId = null
    moodRadioLastRefillAttempt = null
    PlaybackService.moodRadioActive.value = false
}

internal fun PlaybackService.streamAwareDuration(item: PlaybackItem, decoderDurationMs: Long): Long {
    val songId = streamSongIdOf(item)
    val streamDuration = songId?.let { NeteaseRepository.streamDurationMs(it) }?.takeIf { it > 0L }
    if (streamDuration != null) return streamDuration
    if (decoderDurationMs > 0L) return decoderDurationMs
    val metaDurationMs = AndroidSyncRuntime.syncStore.trackForPlayback(item.trackId)
        ?.metadataObject()
        ?.get("duration")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
        ?.let { (it * 1_000.0).toLong() }
    return metaDurationMs ?: 0L
}

/** 锁外下载远程文件；下载完成且这一首仍是当前曲目时，回到锁内继续准备。 */
internal fun PlaybackService.scheduleDeferredRemotePlay(url: String) {
    val token = loadToken.get()
    scope.launch {
        val file = ensureRemoteAudioFile(applicationContext, url) {
            loadToken.get() != token
        }
        if (loadToken.get() != token) return@launch
        if (file == null) {
            // 资源库不可达或下载失败：不能停在 Preparing，落到暂停态让用户重试。
            val currentId = queue.snapshot().currentTrackId ?: return@launch
            val item = AndroidPlaybackRuntime.controller().resolve(currentId) ?: return@launch
            if (resolveAudioTarget(filesDir, item.audioPath) !is AudioTarget.RemoteTarget) return@launch
            settlePausedAfterRemoteFailure(item, "remote library unavailable")
            return@launch
        }
        commandMutex.withLock {
            if (loadToken.get() != token) return@withLock
            val currentId = queue.snapshot().currentTrackId ?: return@withLock
            val item = AndroidPlaybackRuntime.controller().resolve(currentId) ?: return@withLock
            if (resolveAudioTarget(filesDir, item.audioPath) !is AudioTarget.RemoteTarget) return@withLock
            playCurrent()
        }
    }
}

/** 资源库曲目准备失败/被取消：回暂停态，播放键可再次重试。 */
internal fun PlaybackService.settlePausedAfterRemoteFailure(item: PlaybackItem, reason: String) {
    if (queue.snapshot().currentTrackId != item.trackId) return
    Log.w(PlaybackLogTag, "PLAYREMOTEFAIL id=${item.trackId} path=${item.audioPath} reason=$reason")
    preparingStartedAt = null
    closeCurrentDecoderSafely()
    PlaybackService.state.value = PlaybackState.Paused(item, 0L, 0L)
    publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, 0L, 0L)
    updateNotification()
}

/**
 * 在线歌曲的“按播放”：先立刻把按钮切成正在播放，再在锁外重新取直链（过期或被断网
 * 打回过的直链会刷新）。取不到就退回暂停态，用户随时可以再点一次重连。
 */
internal fun PlaybackService.beginStreamResume(songId: Long, paused: PlaybackState.Paused) {
    val token = loadToken.get()
    val item = paused.item
    val effectiveDurationMs = streamAwareDuration(item, paused.durationMs)
    // 停在结尾（缓存刚好完整或上次自然结束）时从 0 重播，否则会一开就 EOF。
    val positionMs = if (effectiveDurationMs > 0L && paused.positionMs >= effectiveDurationMs - 500L) 0L
    else paused.positionMs
    closeCurrentDecoderSafely()
    currentStreamSongId = null
    pendingRecoverySeek = null
    if (preparingStartedAt?.first != item.trackId) {
        preparingStartedAt = item.trackId to SystemClock.elapsedRealtime()
    }
    pendingStreamResume = PendingStreamResume(songId, item, positionMs, effectiveDurationMs, token)
    PlaybackService.state.value = PlaybackState.Preparing(item, resumeIntent = true)
    publishNowPlaying(item, AndroidMediaPlaybackState.STATE_BUFFERING, positionMs, effectiveDurationMs)
    val cachedCover = NeteaseStreamProxy.hasCache(songId) &&
        !NeteaseStreamProxy.hasRecentUpstreamError(songId) &&
        !NeteaseStreamProxy.wasStreamIncomplete(songId) &&
        NeteaseStreamProxy.cachedPrefixMs(songId, effectiveDurationMs) > positionMs + 1_000L
    scope.launch {
        // 缓存已经覆盖当前位置时不必等网络，直接播放；只有够不着缓存才必须重取直链。
        var ready = cachedCover || (withTimeoutOrNull(PlaybackService.StreamUrlTimeoutMs) {
            NeteaseRepository.ensureResumableStreamUrl(songId)
        } ?: false)
        val latestPending = pendingStreamResume
        if (latestPending?.token != token || loadToken.get() != token) return@launch
        val latestTargetPos = latestPending.positionMs
        val latestDurationMs = latestPending.durationMs
        if (!ready &&
            NeteaseRepository.streamFailureReason(songId) == null &&
            NeteaseStreamProxy.hasCache(songId) &&
            NeteaseStreamProxy.cachedPrefixMs(songId, latestDurationMs) > latestTargetPos + 1_000L
        ) {
            ready = true
        }
        commandMutex.withLock {
            val activePending = pendingStreamResume
            if (activePending?.token != token || loadToken.get() != token) return@withLock
            val resumePos = activePending.positionMs
            val resumeDuration = activePending.durationMs
            pendingStreamResume = null
            if (!ready || queue.snapshot().currentTrackId != item.trackId) {
                settlePausedAfterStreamFailure(item, resumePos, resumeDuration, "reconnect failed")
                return@withLock
            }
            playCurrent(resumePos, prepareIntent = true)
        }
    }
}

/** 重连途中再点一次：取消这次尝试，回到暂停态。 */
internal fun PlaybackService.cancelPendingStreamResume() {
    val pending = pendingStreamResume ?: return
    pendingStreamResume = null
    PlaybackService.state.value = PlaybackState.Paused(pending.item, pending.positionMs, pending.durationMs)
    publishNowPlaying(pending.item, AndroidMediaPlaybackState.STATE_PAUSED, pending.positionMs, pending.durationMs)
    updateNotification()
}

/**
 * 参考 MeiloX 的临时失败恢复：延迟后失效直链、重新取流，再在原位置续播。
 * 只对同一首歌生效，连续失败有上限；切歌后自动作废。
 */
internal fun PlaybackService.scheduleTemporaryStreamRecovery(
    item: PlaybackItem,
    positionMs: Long,
    durationMsHint: Long = 0L,
): Boolean {
    val songId = streamSongIdOf(item) ?: return false
    if (recoveryTrackId != item.trackId) {
        recoveryTrackId = item.trackId
        temporaryRecoveryAttempts = 0
    }
    if (temporaryRecoveryAttempts >= PlaybackService.MaxTemporaryRecoveryAttempts) return false
    temporaryRecoveryAttempts += 1
    val attempt = temporaryRecoveryAttempts
    val token = loadToken.get()
    val fallbackDurationMs = streamAwareDuration(item, durationMsHint)
    recoveryJob?.cancel()
    recoveryJob = scope.launch {
        delay(PlaybackService.TemporaryRecoveryDelayMs * attempt)
        if (loadToken.get() != token) return@launch
        commandMutex.withLock {
            if (loadToken.get() != token) return@withLock
            if (queue.snapshot().currentTrackId != item.trackId) return@withLock
            val targetPosMs = pendingRecoverySeek?.takeIf { it.first == item.trackId }?.second ?: positionMs
            pendingRecoverySeek = null
            Log.d(PlaybackLogTag, "NETSTREAM retry song=$songId attempt=$attempt pos=$targetPosMs")
            NeteaseRepository.invalidateStreamUrl(songId)
            val ready = withTimeoutOrNull(PlaybackService.StreamUrlTimeoutMs) {
                NeteaseRepository.ensureResumableStreamUrl(songId)
            } ?: false
            if (!ready) {
                settlePausedAfterStreamFailure(item, targetPosMs, fallbackDurationMs, "retry failed")
                return@withLock
            }
            playCurrent(targetPosMs, startTracking = false, prepareIntent = true)
        }
    }
    return true
}

/**
 * 试听提示挂在播放服务，不依赖全屏播放页：只有小播放条时同样会弹。
 * 同一曲目同一次进入播放只提示一次，暂停/拖动不重复。
 */
internal fun PlaybackService.notePreviewPlayback(songId: Long) {
    if (!NeteaseRepository.isPreviewStream(songId)) return
    val trackId = "netease:$songId"
    if (lastPreviewToastTrackId == trackId) return
    lastPreviewToastTrackId = trackId
    val seconds = (NeteaseRepository.previewDurationMs(songId) / 1_000L).coerceAtLeast(1L)
    mainHandler.post {
        Toast.makeText(
            applicationContext,
            getString(R.string.netease_preview_toast, seconds),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

internal fun PlaybackService.settlePausedAfterStreamFailure(
    item: PlaybackItem,
    positionMs: Long,
    durationMs: Long,
    reason: String,
) {
    Log.w(PlaybackLogTag, "Stream playback unavailable id=${item.trackId} reason=$reason")
    val stateDurationMs = when (val latest = PlaybackService.state.value) {
        is PlaybackState.Playing -> if (latest.item.trackId == item.trackId) latest.durationMs else 0L
        is PlaybackState.Paused -> if (latest.item.trackId == item.trackId) latest.durationMs else 0L
        else -> 0L
    }
    val resolvedDurationMs = maxOf(
        durationMs,
        stateDurationMs,
        streamAwareDuration(item, 0L),
    )
    val targetPosMs = pendingRecoverySeek?.takeIf { it.first == item.trackId }?.second ?: positionMs
    pendingRecoverySeek = null
    preparingStartedAt = null
    closeCurrentDecoderSafely()
    currentStreamSongId = null
    pendingStreamResume = null
    val settledMs = resolveSettledStreamFailurePosition(targetPosMs, resolvedDurationMs)
    PlaybackService.state.value = PlaybackState.Paused(item, settledMs, resolvedDurationMs)
    publishNowPlaying(item, AndroidMediaPlaybackState.STATE_PAUSED, settledMs, resolvedDurationMs)
    updateNotification()
}

internal fun PlaybackService.scheduleDeferredStreamPlay(
    songId: Long,
    positionMs: Long = 0L,
    prepareIntent: Boolean = false,
    fallbackDurationMs: Long = 0L,
) {
    val token = loadToken.get()
    scope.launch {
        val ready = withTimeoutOrNull(PlaybackService.StreamUrlTimeoutMs) {
            NeteaseRepository.ensureResumableStreamUrl(songId)
        } ?: false
        if (loadToken.get() != token) return@launch
        commandMutex.withLock {
            if (loadToken.get() != token) return@withLock
            if (queue.snapshot().currentTrackId != "netease:$songId") return@withLock
            val item = AndroidPlaybackRuntime.controller().resolve("netease:$songId") ?: return@withLock
            val targetPosMs = pendingRecoverySeek?.takeIf { it.first == item.trackId }?.second ?: positionMs
            pendingRecoverySeek = null
            if (!ready || resolveAudioTarget(filesDir, item.audioPath) !is AudioTarget.StreamTarget) {
                // 取不到直链：停在暂停态，按播放会再次尝试联网。
                Log.w(PlaybackLogTag, "NETSTREAM deferred failed song=$songId ready=$ready")
                settlePausedAfterStreamFailure(item, targetPosMs, fallbackDurationMs, "stream url unavailable")
                return@withLock
            }
            playCurrent(targetPosMs, prepareIntent = prepareIntent)
        }
    }
}

internal fun streamSongIdOf(item: PlaybackItem): Long? = item.audioPath
    ?.takeIf { it.startsWith("netease://") }
    ?.removePrefix("netease://")
    ?.toLongOrNull()

/** 歌曲刚结束时在命令锁外机会式读取解码器真实位置；避免被陈旧缓存前缀拉回或误判到结尾。 */
internal fun PlaybackService.sampleStreamClampIfFinished(): StreamClampSample? {
    if (!commandMutex.tryLock()) return null
    return try {
        val current = PlaybackService.state.value as? PlaybackState.Playing ?: return null
        if (decoder?.isFinished() != true) return null
        val songId = currentStreamSongId ?: return null
        val duration = current.durationMs.takeIf { it > 0L } ?: decoder?.durationMs() ?: 0L
        val pos = maxOf(current.positionMs, decoder?.positionMs() ?: 0L)
        StreamClampSample(songId, clampSeekPosition(pos, duration))
    } finally {
        commandMutex.unlock()
    }
}

internal suspend fun PlaybackService.prepareDecoderPath(
    decoder: FfmpegDecoder,
    path: String,
    gain: Float,
    hasStaticAnalysis: Boolean = false,
) {
    when (val target = resolveAudioTarget(filesDir, path)) {
        is AudioTarget.FileTarget -> decoder.prepare(target.file, gain, hasStaticAnalysis)
        is AudioTarget.ContentTarget -> {
            val fd = contentResolver.openFileDescriptor(Uri.parse(target.uri), "r")?.detachFd()
                ?: error("Unable to open audio uri")
            decoder.prepareFd(fd, gain, hasStaticAnalysis)
        }
        is AudioTarget.RemoteTarget -> {
            // 锁内绝不下载：未缓存就把这次准备交还给锁外的延迟任务。
            if (!remoteAudioCached(this, target.url)) {
                throw RemoteAudioNotReady(path, target.url)
            }
            val file = ensureRemoteAudioFile(this, target.url) { false }
                ?: error("Unable to read cached remote audio")
            decoder.prepare(File(file), gain, hasStaticAnalysis)
        }
        is AudioTarget.StreamTarget -> {
            val songId = target.url.removePrefix("netease://").toLongOrNull()
                ?: error("Bad streaming target")
            val hasCachedUrl = NeteaseRepository.cachedStreamUrl(songId) != null
            val hasCache = NeteaseRepository.hasCachedStream(songId)
            if (!hasCachedUrl && !hasCache) {
                throw StreamUrlNotReady(songId)
            }
            // 若仅命中了部分缓存（未完整落盘）且暂无有效直链，提前后台刷新直链与期望大小，
            // 避免播到缓存边界才同步阻塞刷新 CDN。
            if (!hasCachedUrl && !NeteaseStreamProxy.isFullyCached(songId)) {
                NeteaseRepository.prefetchStreamUrl("netease:$songId")
            }
            try {
                decoder.prepareStream(NeteaseRepository.openStreamSource(songId), gain, hasStaticAnalysis)
                notePreviewPlayback(songId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(PlaybackLogTag, "NETSTREAM prepare failed song=$songId: ${error.message}")
                // 打开流失败可能是陈旧直链过期或本地残缺/污染缓存导致：清理缓存与直链后重拉再试。
                NeteaseStreamProxy.removeSong(songId)
                NeteaseRepository.invalidateStreamUrl(songId)
                val refreshed = NeteaseRepository.ensureResumableStreamUrl(songId)
                if (!refreshed) throw error
                Log.d(PlaybackLogTag, "NETSTREAM retry with fresh url song=$songId")
                decoder.prepareStream(NeteaseRepository.openStreamSource(songId), gain, hasStaticAnalysis)
                notePreviewPlayback(songId)
            }
        }
        null -> error("Empty audio path")
    }
}

internal suspend fun PlaybackService.preloadDecoderPath(
    decoder: FfmpegDecoder,
    path: String,
    gain: Float,
    hasStaticAnalysis: Boolean = false,
): Boolean {
    return when (val target = resolveAudioTarget(filesDir, path)) {
        is AudioTarget.FileTarget -> decoder.preload(target.file, gain, hasStaticAnalysis)
        is AudioTarget.ContentTarget -> {
            val fd = contentResolver.openFileDescriptor(Uri.parse(target.uri), "r")?.detachFd()
                ?: error("Unable to open audio uri")
            decoder.preloadFd(fd, gain, hasStaticAnalysis)
        }
        is AudioTarget.RemoteTarget -> {
            val token = loadToken.get()
            val file = ensureRemoteAudioFile(this, target.url) { loadToken.get() != token }
            if (file == null) {
                if (loadToken.get() != token) throw CancellationException("Superseded preload")
                error("Unable to download remote audio")
            }
            decoder.preload(File(file), gain, hasStaticAnalysis)
        }
        is AudioTarget.StreamTarget -> {
            val songId = target.url.removePrefix("netease://").toLongOrNull() ?: return false
            if (NeteaseRepository.isPreviewStream(songId) || !NeteaseStreamProxy.hasWarmedPrefix(songId)) {
                return false
            }
            decoder.preloadStream(NeteaseRepository.openStreamSource(songId), gain, hasStaticAnalysis)
        }
        null -> error("Empty audio path")
    }
}

/**
 * 心动模式：当前歌曲在队尾（没有下一首）时取推荐。
 * 失败不清模式，按退避重试，直到队列被换掉或用户重新开播。
 */
internal fun PlaybackService.maybeAppendHeartbeat() {
    if (!NeteaseRepository.heartbeatEnabled.value) {
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
    if (PlaybackService.state.value !is PlaybackState.Playing && PlaybackService.state.value !is PlaybackState.Paused) return
    if (SystemClock.elapsedRealtime() < heartbeatNextAttemptAtMs) return
    val snapshot = queue.snapshot()
    val currentId = snapshot.currentTrackId ?: return
    val headId = snapshot.originalTrackIds.firstOrNull()
    heartbeatJob = scope.launch {
        Log.d(PlaybackLogTag, "NETHB append trigger current=$currentId head=$headId")
        val appendId = runCatching {
            NeteaseRepository.appendHeartbeatNext(applicationContext, currentId, headId)
        }.getOrNull()
        if (appendId == null) {
            heartbeatRetryDelayMs = if (heartbeatRetryDelayMs == 0L) 2_000L
            else (heartbeatRetryDelayMs * 2).coerceAtMost(PlaybackService.HeartbeatRetryMaxDelayMs)
            heartbeatNextAttemptAtMs = SystemClock.elapsedRealtime() + heartbeatRetryDelayMs
            Log.d(PlaybackLogTag, "NETHB append retry in ${heartbeatRetryDelayMs}ms")
            return@launch
        }
        heartbeatRetryDelayMs = 0L
        heartbeatNextAttemptAtMs = 0L
        // 取歌期间用户可能已经切了队列（心动模式随之退出），不要再往新队列里追加。
        if (!NeteaseRepository.heartbeatEnabled.value) return@launch
        commandMutex.withLock {
            if (!NeteaseRepository.heartbeatEnabled.value) return@withLock
            queue.append(listOf(appendId))
            publishQueue()
        }
    }
}

/** 私人漫游：当前歌曲在队尾时取下一批推荐；失败按退避重试，不关闭模式。 */
internal fun PlaybackService.maybeAppendRoam() {
    if (!NeteaseRepository.roamEnabled.value) {
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
    if (PlaybackService.state.value !is PlaybackState.Playing && PlaybackService.state.value !is PlaybackState.Paused) return
    if (SystemClock.elapsedRealtime() < roamNextAttemptAtMs) return
    roamJob = scope.launch {
        val appendIds = runCatching { NeteaseRepository.appendRoamNext(applicationContext) }.getOrNull()
        if (appendIds.isNullOrEmpty()) {
            roamRetryDelayMs = if (roamRetryDelayMs == 0L) 2_000L
            else (roamRetryDelayMs * 2).coerceAtMost(PlaybackService.HeartbeatRetryMaxDelayMs)
            roamNextAttemptAtMs = SystemClock.elapsedRealtime() + roamRetryDelayMs
            Log.d(PlaybackLogTag, "NETROAM append retry in ${roamRetryDelayMs}ms")
            return@launch
        }
        roamRetryDelayMs = 0L
        roamNextAttemptAtMs = 0L
        if (!NeteaseRepository.roamEnabled.value) return@launch
        commandMutex.withLock {
            if (!NeteaseRepository.roamEnabled.value) return@withLock
            queue.append(appendIds)
            publishQueue()
        }
    }
}

/** 相似歌曲电台：当前歌曲在队尾时根据当前歌曲取下一批相似歌曲；失败按退避重试，不关闭模式。 */
internal fun PlaybackService.maybeAppendSimilar() {
    if (!NeteaseRepository.similarEnabled.value) {
        similarWasEnabled = false
        similarNextAttemptAtMs = 0L
        similarRetryDelayMs = 0L
        return
    }
    if (!similarWasEnabled) {
        similarWasEnabled = true
        similarNextAttemptAtMs = 0L
        similarRetryDelayMs = 0L
    }
    if (similarJob?.isActive == true) return
    if (queue.peekNext() != null) return
    if (PlaybackService.state.value !is PlaybackState.Playing && PlaybackService.state.value !is PlaybackState.Paused) return
    if (SystemClock.elapsedRealtime() < similarNextAttemptAtMs) return
    val currentId = queue.snapshot().currentTrackId ?: return
    similarJob = scope.launch {
        val appendIds = runCatching { NeteaseRepository.appendSimilarNext(applicationContext, currentId) }.getOrNull()
        if (appendIds.isNullOrEmpty()) {
            similarRetryDelayMs = if (similarRetryDelayMs == 0L) 2_000L
            else (similarRetryDelayMs * 2).coerceAtMost(PlaybackService.HeartbeatRetryMaxDelayMs)
            similarNextAttemptAtMs = SystemClock.elapsedRealtime() + similarRetryDelayMs
            Log.d(PlaybackLogTag, "NETSIMILAR append retry in ${similarRetryDelayMs}ms")
            return@launch
        }
        similarRetryDelayMs = 0L
        similarNextAttemptAtMs = 0L
        if (!NeteaseRepository.similarEnabled.value) return@launch
        commandMutex.withLock {
            if (!NeteaseRepository.similarEnabled.value) return@withLock
            queue.append(appendIds)
            publishQueue()
        }
    }
}

/** 后台把下一首远程文件拉到本地缓存；不占命令锁，随时可被新的预取取代。 */
internal fun PlaybackService.prefetchRemoteAudio(item: PlaybackItem) {
    val target = resolveAudioTarget(filesDir, item.audioPath) as? AudioTarget.RemoteTarget ?: return
    prefetchJob?.cancel()
    val token = loadToken.get()
    prefetchJob = scope.launch {
        runCatching {
            val file = ensureRemoteAudioFile(applicationContext, target.url) {
                !isActive || loadToken.get() != token
            }
            if (!file.isNullOrBlank() &&
                isActive &&
                loadToken.get() == token &&
                queue.peekNext() == item.trackId &&
                (PlaybackService.gaplessPlayback.value || PlaybackService.crossfadeSeconds.value > 0)
            ) {
                commandMutex.withLock {
                    if (decoder?.isCrossfading() != true &&
                        loadToken.get() == token &&
                        queue.peekNext() == item.trackId
                    ) {
                        preloadNext()
                    }
                }
            }
        }
    }
}

/** 锁外预取在线歌曲直链并预热头部字节，完成后回到锁内触发解码器预加载。 */
internal fun PlaybackService.prefetchStreamAudio(item: PlaybackItem) {
    val target = resolveAudioTarget(filesDir, item.audioPath) as? AudioTarget.StreamTarget ?: return
    val songId = target.url.removePrefix("netease://").toLongOrNull() ?: return
    prefetchJob?.cancel()
    val token = loadToken.get()
    prefetchJob = scope.launch {
        runCatching {
            val warmed = NeteaseRepository.warmStreamForPreload(songId)
            if (warmed &&
                isActive &&
                loadToken.get() == token &&
                queue.peekNext() == item.trackId &&
                (PlaybackService.gaplessPlayback.value || PlaybackService.crossfadeSeconds.value > 0)
            ) {
                commandMutex.withLock {
                    if (decoder?.isCrossfading() != true &&
                        loadToken.get() == token &&
                        queue.peekNext() == item.trackId
                    ) {
                        preloadNext()
                    }
                }
            }
        }
    }
}

internal data class ResolvedNormalizationGain(
    val gainDb: Float,
    val hasStaticAnalysis: Boolean,
)

internal suspend fun PlaybackService.normalizationGain(
    item: PlaybackItem,
    nextId: String?,
): ResolvedNormalizationGain {
    val store = AndroidSyncRuntime.syncStore
    val analyses = store.activeAnalyses
    val analysis = store.analysis(item.trackId) ?: item.analysis
    if (analysis == null || !normalizationSettings.enabled) {
        return ResolvedNormalizationGain(0f, analysis != null)
    }
    val next = nextId?.let { AndroidPlaybackRuntime.controller().resolve(it) }
    val continuousAlbum = normalizationSettings.mode == NormalizationMode.Album &&
        item.albumId.isNotEmpty() && item.albumId == next?.albumId
    val albumAnalyses = if (continuousAlbum) {
        store.tracks.first().filter { it.albumId == item.albumId }.mapNotNull { analyses[it.id] }
    } else emptyList()
    val gainDb = normalizationGainDb(normalizationSettings, analysis, albumAnalyses, continuousAlbum)
    return ResolvedNormalizationGain(gainDb, true)
}

internal suspend fun PlaybackService.refreshNormalizationGains() {
    val activeDecoder = decoder ?: return
    activeDecoder.setNormalizationConfig(normalizationSettings)
    val current = when (val value = PlaybackService.state.value) {
        is PlaybackState.Playing -> value.item
        is PlaybackState.Paused -> value.item
        is PlaybackState.Preparing -> value.item
        else -> null
    } ?: return
    val nextId = queue.peekNext()
    val activeResolved = normalizationGain(current, nextId)
    val preloadedResolved = preloadedItem?.let { normalizationGain(it, queue.peekNext()) }
        ?: ResolvedNormalizationGain(0f, false)
    activeDecoder.setNormalizationGains(
        activeResolved.gainDb,
        activeResolved.hasStaticAnalysis,
        preloadedResolved.gainDb,
        preloadedResolved.hasStaticAnalysis,
    )
}

internal fun PlaybackService.maybeStartCrossfade(): Boolean {
    if (PlaybackService.sleepTimerState.value.afterCurrentTrack) return false
    val current = PlaybackService.state.value as? PlaybackState.Playing ?: return false
    val ab = PlaybackService.abLoopState.value
    if (ab.isActive && ab.trackId == current.item.trackId) return false
    val currentDecoder = decoder ?: return false
    val incoming = preloadedItem ?: return false
    if (currentDecoder.isCrossfading()) return false
    if (shouldPreferGaplessForAlbum(
            gaplessEnabled = PlaybackService.gaplessPlayback.value,
            current = current.item,
            next = incoming,
            isShuffled = queue.snapshot().shuffle,
        )
    ) return false
    if (!shouldStartCrossfade(
            crossfadeSeconds = PlaybackService.crossfadeSeconds.value,
            positionMs = currentDecoder.positionMs(),
            durationMs = current.durationMs,
            hasPreloadedNext = preloadedItem != null && currentDecoder.hasPreloaded(),
        )
    ) return false
    val effectiveDurationMs = crossfadeDurationMs(
        crossfadeSeconds = PlaybackService.crossfadeSeconds.value,
        positionMs = currentDecoder.positionMs(),
        durationMs = current.durationMs,
    )
    currentDecoder.beginCrossfade(effectiveDurationMs)
    if (currentDecoder.isCrossfading()) {
        PlaybackService.nextArtworkCrossfadeId += 1
        PlaybackService.artworkCrossfade.value = ArtworkCrossfadeTransition(
            id = PlaybackService.nextArtworkCrossfadeId,
            fromArtworkPath = current.item.artworkPath,
            toArtworkPath = incoming.artworkPath,
            durationMs = effectiveDurationMs.coerceAtLeast(1L),
        )
    }
    return true
}

internal fun clearArtworkCrossfade() {
    PlaybackService.artworkCrossfade.value = null
}
