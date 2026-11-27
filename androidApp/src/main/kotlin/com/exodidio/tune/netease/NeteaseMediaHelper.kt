package com.exodidio.tune.netease

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.albumIdFor
import com.exodidio.tune.sync.artistIdFor
import com.exodidio.tune.sync.metadataObject
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 网易云封面补齐、音频下载与曲目元数据辅助。 */
internal object NeteaseMediaHelper {
    fun ensurePlaybackCover(trackId: String) {
        val app = NeteaseRepository.appContext ?: return
        val songId = NeteaseRepository.songIdForTrack(trackId) ?: return
        val cached = NeteaseCoverStore.cached(app, songId)
        if (cached != null) {
            // 文件已在盘上但队列条目还没带上封面（预取过的曲目）：直接回写，播放器立刻能显示。
            val track = AndroidSyncRuntime.syncStore.trackForPlayback(trackId) ?: return
            if (track.artworkPath != cached.absolutePath) {
                NeteaseRepository.queueCoverUpdate(track.copy(artworkPath = cached.absolutePath))
            }
            return
        }
        val track = AndroidSyncRuntime.syncStore.trackForPlayback(trackId) ?: return
        val coverUrl = coverUrlForTrackId(trackId, songId) ?: return
        NeteaseRepository.scheduleCover(
            NeteaseEntryRecord(
                songId = songId,
                title = track.title,
                artists = track.artists,
                album = track.album,
                coverUrl = coverUrl,
            ),
        )
    }

    suspend fun playbackCoverInfo(trackId: String): NeteaseRepository.PlaybackCoverInfo =
        withContext(Dispatchers.IO) {
            val songId = NeteaseRepository.songIdForTrack(trackId)
            NeteaseRepository.PlaybackCoverInfo(
                url = songId?.let { coverUrlForTrackId(trackId, it) },
                key = songId?.let { "netease:$it" } ?: trackId,
            )
        }

    /** 曲目的网易云封面地址：先看曲目元数据，再回查歌单条目（本地匹配曲目）。 */
    fun coverUrlForTrackId(trackId: String, songId: Long): String? =
        AndroidSyncRuntime.syncStore.trackForPlayback(trackId)?.let(::coverUrlForTrack)
            ?: NeteaseRepository.activePlaylists.value.asSequence()
                .flatMap { it.entries.asSequence() }
                .firstOrNull { it.songId == songId }
                ?.coverUrl
                ?.takeIf { it.isNotBlank() }

    /** 纯路径规则判断：支持私有本地文件与 SAF `content://` URI（不做磁盘/ContentResolver 阻塞检查）。 */
    fun isDownloadedAudioPathSyntaxValid(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.startsWith("netease://", ignoreCase = true)) return false
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return true
    }

    /** 实际文件存在性检查：私有文件或 SAF DocumentFile，必须在 IO 线程中调用。 */
    fun isAudioPathAvailable(context: Context?, path: String?): Boolean {
        if (!isDownloadedAudioPathSyntaxValid(path)) return false
        val raw = path!!
        if (raw.startsWith("content://", ignoreCase = true)) {
            val app = context?.applicationContext ?: return true
            return runCatching { DocumentFile.fromSingleUri(app, Uri.parse(raw))?.exists() == true }
                .getOrDefault(false)
        }
        return File(raw).isFile
    }

    /** 用户明确点过下载的网易云歌曲（纯路径语法判断；实际磁盘/SAF 存在性校验通过 [filterAvailableDownloadedTracks] 在 IO 线程完成）。 */
    fun isExplicitlyDownloaded(track: LibraryTrack): Boolean =
        track.id.startsWith("netease:") && isDownloadedAudioPathSyntaxValid(track.audioPath)

    /** 在 IO 线程中校验已下载曲目的本地文件 / SAF DocumentFile 是否仍存在，按更新时间倒序返回。 */
    suspend fun filterAvailableDownloadedTracks(
        context: Context?,
        tracks: List<LibraryTrack>,
    ): List<LibraryTrack> = withContext(Dispatchers.IO) {
        tracks.asSequence()
            .filter { isExplicitlyDownloaded(it) && isAudioPathAvailable(context, it.audioPath) }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    fun downloadedTracks(): List<LibraryTrack> =
        AndroidSyncRuntime.syncStore.tracks.value
            .asSequence()
            .filter(::isExplicitlyDownloaded)
            .sortedByDescending { it.updatedAt }
            .toList()

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
            id = NeteaseRepository.DOWNLOADS_ID,
            name = name,
            coverUrl = "",
            coverPath = entries.firstOrNull()?.coverPath,
            importedAt = Instant.now().toString(),
            addedToPlaylists = false,
            dynamicKind = "downloads",
            entries = entries,
        )
        NeteaseRepository.updatePlaylists { list -> list.filterNot { it.id == NeteaseRepository.DOWNLOADS_ID } + record }
        NeteaseRepository.select(NeteaseRepository.DOWNLOADS_ID)
    }

    /** 删除一首已下载的网易云歌曲：移除导入曲目与本地文件（含 SAF URI），并把歌单条目改回未下载。 */
    suspend fun deleteDownload(context: Context, trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val songId = trackId.removePrefix("netease:").toLongOrNull()
                ?: error("不是网易云曲目")
            val store = AndroidSyncRuntime.syncStore
            val track = store.trackForPlayback(trackId) ?: error("曲目不存在")
            val path = track.audioPath?.takeIf(::isDownloadedAudioPathSyntaxValid)
            if (path != null) {
                if (path.startsWith("content://", ignoreCase = true)) {
                    runCatching { DocumentFile.fromSingleUri(context.applicationContext, Uri.parse(path))?.delete() }
                } else {
                    File(path).delete()
                }
            }
            store.setImportedTracks(store.importedTracksSnapshot().filterNot { it.id == trackId })
            NeteaseRepository.updatePlaylists { list ->
                list.map { playlist ->
                    if (playlist.id == NeteaseRepository.DOWNLOADS_ID) {
                        playlist.copy(entries = playlist.entries.filterNot { it.songId == songId })
                    } else {
                        playlist.copy(
                            entries = playlist.entries.map { entry ->
                                if (entry.songId == songId) entry.copy(downloadedFile = null, downloadFormat = null) else entry
                            },
                        )
                    }
                }
            }
            NeteaseRepository.persistNow(context)
            Unit
        }.onFailure { Log.w("NeteaseRepo", "delete download track=$trackId err=${it.message}") }
    }

    /** 曲目可用的远端封面地址（供图片加载器直接按尺寸请求）。 */
    fun coverUrlForTrack(track: LibraryTrack): String? =
        track.metadataObject()?.get("coverUrl")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }

    /** 全屏播放器用的高清封面：命中缓存时直接返回路径。 */
    fun cachedHiResCover(trackId: String): String? {
        val app = NeteaseRepository.appContext ?: return null
        val songId = NeteaseRepository.songIdForTrack(trackId) ?: return null
        return NeteaseHiResCoverStore.cached(app, songId)?.absolutePath
    }

    /** 打开全屏播放器时按需补一张 1600² 封面；失败返回 null，界面继续用 600² 图。 */
    suspend fun ensureHiResCover(trackId: String): String? = withContext(Dispatchers.IO) {
        val app = NeteaseRepository.appContext ?: return@withContext null
        val songId = NeteaseRepository.songIdForTrack(trackId) ?: return@withContext null
        val cached = NeteaseHiResCoverStore.cached(app, songId)
        if (cached != null) return@withContext cached.absolutePath
        val coverUrl = coverUrlForTrackId(trackId, songId) ?: return@withContext null
        NeteaseHiResCoverStore.ensure(app, songId, coverUrl, NeteaseRepository.cookie(app))?.absolutePath
    }

    /** 曲目关联的网易云专辑 ID（有则菜单可进在线专辑页）。 */
    fun neteaseAlbumIdForTrack(track: LibraryTrack): Long? {
        track.metadataObject()?.get("neteaseAlbumId")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.toLongOrNull()
            ?.let { return it }
        // 匹配到本地的曲目本身没有网络元数据：按 songId 回查歌单条目。
        val songId = NeteaseRepository.songIdForTrack(track.id) ?: return null
        NeteaseRepository.activePlaylists.value.forEach { playlist ->
            playlist.entries.firstOrNull { it.songId == songId && it.albumId > 0 }?.let { return it.albumId }
        }
        return null
    }

    /** 曲目关联的第一个网易云歌手 ID（含本地匹配曲目的回查）。 */
    fun neteaseArtistIdForTrack(track: LibraryTrack): Long? {
        track.metadataObject()?.get("neteaseArtistId")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.toLongOrNull()
            ?.let { return it }
        val songId = NeteaseRepository.songIdForTrack(track.id) ?: return null
        NeteaseRepository.activePlaylists.value.forEach { playlist ->
            playlist.entries.firstOrNull { it.songId == songId && it.artistIds.isNotEmpty() }
                ?.let { return it.artistIds.first() }
        }
        return null
    }

    /** 下载并登记为已下载曲目（统一委托给 NeteaseDownloadManager，支持自选音质与 SAF 路径）。 */
    suspend fun ensureDownloaded(
        context: Context,
        playlistId: String,
        songId: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        val playlist = NeteaseRepository.playlistById(playlistId)
            ?: return@withContext Result.failure(IllegalStateException("歌单不存在"))
        val entry = playlist.entries.firstOrNull { it.songId == songId }
            ?: return@withContext Result.failure(IllegalStateException("歌曲不存在"))
        NeteaseDownloadManager.enqueue(context, playlistId, entry).await()
    }

    suspend fun downloadAll(context: Context, playlistId: String): DownloadAllResult =
        withContext(Dispatchers.IO) {
            val playlist = NeteaseRepository.playlistById(playlistId) ?: return@withContext DownloadAllResult(0, 0)
            val app = context.applicationContext
            val pending = playlist.entries.filter { entry ->
                !isAudioPathAvailable(app, entry.downloadedFile)
            }
            if (pending.isEmpty()) return@withContext DownloadAllResult(0, 0)
            NeteaseRepository.downloadProgressState.update { it + (playlistId to (0 to pending.size)) }
            var done = 0
            var succeeded = 0
            val deferreds = pending.map { entry ->
                entry to NeteaseDownloadManager.enqueue(app, playlistId, entry)
            }
            for ((_, deferred) in deferreds) {
                val res = runCatching { deferred.await() }.getOrElse { Result.failure(it) }
                if (res.isSuccess) succeeded++
                done++
                NeteaseRepository.downloadProgressState.update { it + (playlistId to (done to pending.size)) }
            }
            NeteaseRepository.downloadProgressState.update { it - playlistId }
            DownloadAllResult(total = pending.size, succeeded = succeeded)
        }

    /** 导入后按需补齐每首歌的封面（限制并发，失败忽略，不阻塞界面）。 */
    suspend fun ensureEntryCovers(context: Context, playlistId: String, limit: Int = 20) {
        val playlist = NeteaseRepository.playlists.value.firstOrNull { it.id == playlistId } ?: return
        val pending = playlist.entries.filter { entry ->
            entry.coverPath?.let { File(it).isFile } != true && entry.coverUrl.isNotBlank()
        }.take(limit)
        if (pending.isEmpty()) return
        pending.chunked(4).forEach { chunk ->
            // 统一走 NeteaseCoverStore：同一首歌的封面下载不会和播放器补图互相覆盖。
            val resolved = coroutineScope {
                chunk.map { entry ->
                    async {
                        entry.songId to NeteaseCoverStore
                            .ensure(context, entry.songId, entry.coverUrl, NeteaseRepository.cookie(context))
                            .await()
                            ?.absolutePath
                    }
                }.awaitAll()
            }
            val paths = resolved.filter { it.second != null }.associate { it.first to it.second!! }
            if (paths.isEmpty()) return@forEach
            NeteaseRepository.updatePlaylists { list ->
                list.map { current ->
                    if (current.id != playlistId) {
                        current
                    } else {
                        current.copy(
                            entries = current.entries.map { entry ->
                                paths[entry.songId]?.let { entry.copy(coverPath = it) } ?: entry
                            },
                        )
                    }
                }
            }
        }
        // 单写者会合并多次请求：这里只需在结束时投递一次最新快照。
        NeteaseRepository.persistSoon()
    }

    suspend fun downloadCover(context: Context, dir: File, entry: NeteaseEntryRecord): File? {
        if (entry.coverUrl.isBlank()) return null
        val target = File(dir, "${entry.songId}.cover.jpg")
        if (target.isFile && target.length() > 0) return target
        return NeteaseClient.download(entry.coverUrl, NeteaseRepository.cookie(context), target)
            .getOrNull()?.let { target }
    }

    fun backfillPlaybackCovers() {
        NeteaseRepository.appContext ?: return
        AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow.value
            .filter { it.id.startsWith("netease:") && it.artworkPath.isNullOrBlank() }
            .forEach { track ->
                val songId = track.id.removePrefix("netease:").toLongOrNull() ?: return@forEach
                val coverUrl = coverUrlForTrack(track).orEmpty()
                if (coverUrl.isBlank()) {
                    Log.d("NeteaseRepo", "cover missing url song=$songId id=${track.id}")
                    return@forEach
                }
                NeteaseRepository.scheduleCover(
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

    fun backfillPlaylistCovers() {
        val app = NeteaseRepository.appContext ?: return
        NeteaseRepository.ioScope.launch {
            val pending = NeteaseRepository.playlists.value.filter { record ->
                (record.addedToPlaylists || record.persistCache) &&
                    record.coverUrl.isNotBlank() &&
                    record.coverPath?.let { File(it).isFile } != true
            }.take(30)
            if (pending.isEmpty()) return@launch
            pending.forEach { record ->
                val path = NeteaseRepository.downloadPlaylistCover(
                    app,
                    record.id,
                    record.coverUrl,
                    record.coverUrl,
                ) ?: return@forEach
                NeteaseRepository.updatePlaylists { list ->
                    list.map { if (it.id == record.id) it.copy(coverPath = path) else it }
                }
            }
            NeteaseRepository.persistSoon()
            Log.d("NeteaseRepo", "playlist cover backfill=${pending.size}")
        }
    }

    internal fun buildPlaybackTrack(
        entry: NeteaseEntryRecord,
        audioPath: String,
        extension: String,
        fileSize: Long,
        cover: File?,
    ): LibraryTrack {
        val albumTitle = entry.album.ifBlank { "未知专辑" }
        val artists = entry.artists.ifBlank { "未知歌手" }
        val artistNames = artists.split(" / ").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(artists) }
        val albumId = albumIdFor(albumTitle)
        val ext = extension.lowercase()
        val seconds = entry.durationMs.coerceAtLeast(0L) / 1000L
        val metadata = buildJsonObject {
            put("title", entry.title)
            if (seconds > 0) put("duration", seconds)
            put("format", ext)
            put("codec", ext)
            put("lossless", ext in setOf("flac", "wav", "aiff", "alac"))
            put("file_size", fileSize)
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
            audioPath = audioPath,
            createdAt = createdAt,
            updatedAt = createdAt,
            sortTitle = entry.title,
            sortArtists = artistNames.first(),
        )
    }
}
