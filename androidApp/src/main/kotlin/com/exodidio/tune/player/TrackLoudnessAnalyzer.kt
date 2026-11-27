package com.exodidio.tune.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.exodidio.tune.library.AudioTarget
import com.exodidio.tune.library.resolveAudioTarget
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.NeteaseStreamProxy
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.ensureRemoteAudioFile
import com.exodidio.tune.sync.remoteAudioCached
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-concurrency background ITU-R BS.1770-4 loudness & true-peak analyzer.
 * Runs exclusively on [Dispatchers.IO] without touching playback locks; once a track's
 * analysis is persisted, live playback receives updated static gain via callback.
 */
internal object TrackLoudnessAnalyzer {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val queue = Channel<Pair<PlaybackItem, suspend (String) -> Unit>>(capacity = 64)
    @Volatile private var workerStarted = false
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (workerStarted) return
        synchronized(this) {
            if (workerStarted) return
            workerStarted = true
            AndroidSyncRuntime.backgroundScope.launch {
                for ((item, onReady) in queue) {
                    try {
                        analyzeItem(item, onReady)
                    } catch (error: Throwable) {
                        Log.w(PlaybackLogTag, "LUFS analysis skipped id=${item.trackId}: ${error.message}")
                    } finally {
                        inFlight.remove(item.trackId)
                    }
                }
            }
        }
    }

    fun enqueue(
        context: Context,
        item: PlaybackItem,
        onReady: suspend (String) -> Unit = {},
    ) {
        initialize(context)
        val store = AndroidSyncRuntime.syncStore
        if (store.analysis(item.trackId) != null) return
        if (!inFlight.add(item.trackId)) return
        if (queue.trySend(item to onReady).isFailure) {
            inFlight.remove(item.trackId)
        }
    }

    private suspend fun analyzeItem(item: PlaybackItem, onReady: suspend (String) -> Unit) {
        val ctx = appContext ?: return
        val store = AndroidSyncRuntime.syncStore
        if (store.analysis(item.trackId) != null) return
        val analysis = withContext(Dispatchers.IO) {
            when (val target = resolveAudioTarget(ctx.filesDir, item.audioPath)) {
                is AudioTarget.FileTarget -> FfmpegDecoder.analyzeFile(target.file)
                is AudioTarget.ContentTarget -> {
                    val pfd = runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(target.uri), "r") }.getOrNull()
                    if (pfd != null) {
                        FfmpegDecoder.analyzeFd(pfd.detachFd())
                    } else null
                }
                is AudioTarget.RemoteTarget -> {
                    if (remoteAudioCached(ctx, target.url)) {
                        val cached = ensureRemoteAudioFile(ctx, target.url) { false }
                        cached?.let { FfmpegDecoder.analyzeFile(File(it)) }
                    } else null
                }
                is AudioTarget.StreamTarget -> {
                    val songId = target.url.removePrefix("netease://").toLongOrNull() ?: return@withContext null
                    // Never download full tracks twice: only scan Netease streams once SimpleCache holds 100%.
                    if (!NeteaseStreamProxy.isFullyCached(songId) || NeteaseRepository.isPreviewStream(songId)) {
                        return@withContext null
                    }
                    val source = runCatching { NeteaseRepository.openStreamSource(songId) }.getOrNull()
                        ?: return@withContext null
                    FfmpegDecoder.analyzeStream(source)
                }
                null -> null
            }
        } ?: return
        store.saveTrackAnalysis(item.trackId, analysis)
        Log.d(PlaybackLogTag, "LUFS analyzed id=${item.trackId} lufs=${analysis.loudnessLufs} peak=${analysis.truePeak}")
        onReady(item.trackId)
    }
}
