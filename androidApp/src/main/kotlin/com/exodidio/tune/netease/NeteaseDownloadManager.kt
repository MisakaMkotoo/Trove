package com.exodidio.tune.netease

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.exodidio.tune.settings.DownloadPreferences
import com.exodidio.tune.settings.DownloadQuality
import com.exodidio.tune.sync.AndroidSyncRuntime
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal enum class DownloadTaskStatus {
    Queued,
    Downloading,
    Failed,
}

internal data class DownloadTaskState(
    val songId: Long,
    val playlistId: String,
    val entry: NeteaseEntryRecord,
    val quality: DownloadQuality,
    val status: DownloadTaskStatus = DownloadTaskStatus.Queued,
    val bytesLoaded: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
) {
    val progress: Float?
        get() = totalBytes.takeIf { it > 0L }
            ?.let { (bytesLoaded.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

/**
 * 网易云音频下载管理器：
 * - 所有网络、私有目录与 SAF `DocumentFile` 写入均运行在 [Dispatchers.IO]（最大并发 2）。
 * - 根据 [DownloadPreferences] 解析目标音质档位及私有目录 / 自选 SAF 文件夹。
 * - 切换下载位置只影响新入队任务，已下载曲目的 `audioPath` 保持不变。
 */
internal object NeteaseDownloadManager {
    private const val TAG = "NeteaseDownloadMgr"
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transferSlots = Semaphore(2)

    private val _tasks = MutableStateFlow<Map<Long, DownloadTaskState>>(emptyMap())
    val tasks: StateFlow<Map<Long, DownloadTaskState>> = _tasks.asStateFlow()

    private val _sessionCompleted = MutableStateFlow(0)
    val sessionCompleted: StateFlow<Int> = _sessionCompleted.asStateFlow()

    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val activeDeferreds = ConcurrentHashMap<Long, CompletableDeferred<Result<String>>>()

    fun enqueue(
        context: Context,
        playlistId: String,
        entry: NeteaseEntryRecord,
    ): CompletableDeferred<Result<String>> {
        val existingTask = _tasks.value[entry.songId]
        val existingDeferred = activeDeferreds[entry.songId]
        if (existingTask != null &&
            (existingTask.status == DownloadTaskStatus.Queued || existingTask.status == DownloadTaskStatus.Downloading) &&
            existingDeferred != null
        ) {
            return existingDeferred
        }

        val app = context.applicationContext
        val deferred = CompletableDeferred<Result<String>>()
        activeDeferreds[entry.songId] = deferred

        val snapshot = DownloadPreferences.snapshotFlow.value
        _tasks.update { map ->
            map + (entry.songId to DownloadTaskState(
                songId = entry.songId,
                playlistId = playlistId,
                entry = entry,
                quality = snapshot.quality,
                status = DownloadTaskStatus.Queued,
            ))
        }
        startForegroundDownloadService(app)

        val job = ioScope.launch {
            val result = try {
                transferSlots.withPermit {
                    performDownload(app, playlistId, entry)
                }
            } catch (cancelled: CancellationException) {
                Result.failure<String>(cancelled)
            } catch (error: Throwable) {
                Result.failure<String>(error)
            }

            result.onSuccess {
                _tasks.update { map -> map - entry.songId }
                _sessionCompleted.update { it + 1 }
                if (_tasks.value.values.none {
                        it.status == DownloadTaskStatus.Queued || it.status == DownloadTaskStatus.Downloading
                    }
                ) {
                    _sessionCompleted.value = 0
                }
            }.onFailure { err ->
                if (err is CancellationException) {
                    _tasks.update { map -> map - entry.songId }
                } else {
                    _tasks.update { map ->
                        val prev = map[entry.songId] ?: DownloadTaskState(
                            songId = entry.songId,
                            playlistId = playlistId,
                            entry = entry,
                            quality = snapshot.quality,
                        )
                        map + (entry.songId to prev.copy(
                            status = DownloadTaskStatus.Failed,
                            error = err.message ?: err.javaClass.simpleName,
                        ))
                    }
                }
            }
            activeJobs.remove(entry.songId)
            activeDeferreds.remove(entry.songId, deferred)
            deferred.complete(result)
        }
        activeJobs[entry.songId] = job
        return deferred
    }

    fun cancel(songId: Long) {
        activeJobs.remove(songId)?.cancel()
        activeDeferreds.remove(songId)?.complete(Result.failure(CancellationException("Cancelled")))
        _tasks.update { map -> map - songId }
    }

    fun retry(context: Context, songId: Long) {
        val failed = _tasks.value[songId] ?: return
        enqueue(context, failed.playlistId, failed.entry)
    }

    fun clearFailed() {
        _tasks.update { map -> map.filterValues { it.status != DownloadTaskStatus.Failed } }
    }

    fun cancelAll() {
        activeJobs.keys.toList().forEach { songId ->
            activeJobs.remove(songId)?.cancel()
            activeDeferreds.remove(songId)?.complete(Result.failure(CancellationException("Cancelled")))
        }
        _tasks.update { map -> map.filterValues { it.status == DownloadTaskStatus.Failed } }
        _sessionCompleted.value = 0
    }

    private fun startForegroundDownloadService(app: Context) {
        runCatching {
            app.startForegroundService(
                Intent(app, NeteaseDownloadService::class.java).setAction(NeteaseDownloadService.ACTION_START),
            )
        }.onFailure { err ->
            Log.w(TAG, "Unable to start NeteaseDownloadService: ${err.message}")
        }
    }

    private suspend fun performDownload(
        app: Context,
        playlistId: String,
        initialEntry: NeteaseEntryRecord,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            currentCoroutineContext().ensureActive()
            val settings = DownloadPreferences.readSnapshotBlocking(app)
            val currentPlaylist = NeteaseRepository.playlistById(playlistId)
            val entry = currentPlaylist?.entries?.firstOrNull { it.songId == initialEntry.songId } ?: initialEntry
            val audioDir = File(app.filesDir, "netease-audio").apply { mkdirs() }

            _tasks.update { map ->
                val cur = map[entry.songId]
                if (cur == null) map
                else map + (entry.songId to cur.copy(
                    quality = settings.quality,
                    status = DownloadTaskStatus.Downloading,
                    error = null,
                ))
            }

            // 先检查是否已有有效文件（私有路径或已保存的 SAF URI）。
            val existingLibraryPath = AndroidSyncRuntime.syncStore
                .trackForPlayback("netease:${entry.songId}")
                ?.audioPath
                ?.takeIf { !it.startsWith("netease://") && NeteaseMediaHelper.isAudioPathAvailable(app, it) }
            val existingEntryPath = entry.downloadedFile
                ?.takeIf { NeteaseMediaHelper.isAudioPathAvailable(app, it) }
            val existingPath = existingEntryPath ?: existingLibraryPath

            val resolvedAudioPath: String
            val resolvedExtension: String
            val resolvedFileSize: Long

            if (existingPath != null) {
                resolvedAudioPath = existingPath
                resolvedExtension = entry.downloadFormat?.ifBlank { null }
                    ?: existingPath.substringAfterLast('.', "mp3").substringBefore('?').lowercase()
                resolvedFileSize = if (existingPath.startsWith("content://", ignoreCase = true)) {
                    runCatching { DocumentFile.fromSingleUri(app, Uri.parse(existingPath))?.length() ?: 0L }.getOrDefault(0L)
                } else {
                    File(existingPath).length()
                }
            } else {
                val levels = settings.quality.downloadFallbacks.map { it.apiLevel }
                val cookie = NeteaseRepository.cookie(app)
                val resolved = NeteaseClient.fetchSongStreamLevels(entry.songId, cookie, levels).getOrThrow()
                val full = when (resolved) {
                    is NeteaseClient.NeteaseStream.Playable -> resolved
                    is NeteaseClient.NeteaseStream.Preview ->
                        error("这首歌需要会员，无法下载完整音频")
                    NeteaseClient.NeteaseStream.LoginRequired -> error("登录已失效，请重新登录")
                    is NeteaseClient.NeteaseStream.Unavailable -> error(resolved.reason)
                }
                val ext = full.type.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "mp3" }
                val onProgress: (Long, Long) -> Unit = { loaded, total ->
                    val effectiveTotal = total.takeIf { it > 0L } ?: full.sizeBytes.coerceAtLeast(0L)
                    _tasks.update { map ->
                        val cur = map[entry.songId] ?: return@update map
                        map + (entry.songId to cur.copy(
                            bytesLoaded = loaded,
                            totalBytes = effectiveTotal,
                        ))
                    }
                }

                val customUri = if (!settings.usePrivateFolder) settings.customFolderUri else null
                if (customUri.isNullOrBlank()) {
                    val target = File(audioDir, "${entry.songId}.$ext")
                    val temp = File(audioDir, "${entry.songId}.$ext.part")
                    try {
                        val written = NeteaseClient.downloadToOutput(
                            url = full.url,
                            cookie = cookie,
                            expectedBytes = full.sizeBytes,
                            onProgress = onProgress,
                        ) { temp.outputStream() }.getOrThrow()
                        if (!temp.renameTo(target)) {
                            temp.copyTo(target, overwrite = true)
                            temp.delete()
                        }
                        resolvedAudioPath = target.absolutePath
                        resolvedExtension = ext
                        resolvedFileSize = target.length().takeIf { it > 0L } ?: written
                    } catch (err: Throwable) {
                        withContext(NonCancellable) { temp.delete() }
                        throw err
                    }
                } else {
                    val tree = DocumentFile.fromTreeUri(app, Uri.parse(customUri))
                        ?.takeIf { it.exists() && it.canWrite() }
                        ?: error("所选下载文件夹不可写，请重新选择")
                    val safeName = sanitizeSafFilename("${entry.artists} - ${entry.title} (${entry.songId}).$ext")
                    tree.findFile(safeName)?.delete()
                    val doc = tree.createFile(mimeTypeForExtension(ext), safeName)
                        ?: error("无法在目标文件夹创建文件")
                    try {
                        val written = NeteaseClient.downloadToOutput(
                            url = full.url,
                            cookie = cookie,
                            expectedBytes = full.sizeBytes,
                            onProgress = onProgress,
                        ) {
                            app.contentResolver.openOutputStream(doc.uri, "w")
                                ?: error("无法写入目标文件夹")
                        }.getOrThrow()
                        resolvedAudioPath = doc.uri.toString()
                        resolvedExtension = ext
                        resolvedFileSize = doc.length().takeIf { it > 0L } ?: written
                    } catch (err: Throwable) {
                        withContext(NonCancellable) { runCatching { doc.delete() } }
                        throw err
                    }
                }

                val updatedEntry = entry.copy(downloadedFile = resolvedAudioPath, downloadFormat = ext)
                NeteaseRepository.updatePlaylists { list ->
                    list.map { pl ->
                        if (pl.id == playlistId || pl.entries.any { it.songId == entry.songId }) {
                            pl.copy(entries = pl.entries.map { if (it.songId == entry.songId) updatedEntry else it })
                        } else {
                            pl
                        }
                    }
                }
                NeteaseRepository.persistNow(app)
            }

            val finalEntry = NeteaseRepository.playlistById(playlistId)
                ?.entries?.firstOrNull { it.songId == entry.songId }
                ?: entry.copy(downloadedFile = resolvedAudioPath, downloadFormat = resolvedExtension)
            val cover = NeteaseMediaHelper.downloadCover(app, audioDir, finalEntry)
            val track = NeteaseMediaHelper.buildPlaybackTrack(
                entry = finalEntry,
                audioPath = resolvedAudioPath,
                extension = resolvedExtension,
                fileSize = resolvedFileSize,
                cover = cover,
            )
            val store = AndroidSyncRuntime.syncStore
            store.setImportedTracks(store.importedTracksSnapshot().filterNot { it.id == track.id } + track)
            track.id
        }
    }

    private fun sanitizeSafFilename(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "track" }

    private fun mimeTypeForExtension(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }
}
