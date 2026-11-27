package com.exodidio.tune.lyrics

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.File
import com.exodidio.tune.net.TuneHttp
import com.exodidio.tune.net.executeCancellable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import com.exodidio.tune.lyrics.autosync.AutoSyncCoordinator
import com.exodidio.tune.lyrics.autosync.AutoSyncModelStore
import com.exodidio.tune.lyrics.autosync.AutoSyncTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.exodidio.tune.BuildConfig
import com.exodidio.tune.lyrics.LyricsCandidate
import com.exodidio.tune.lyrics.bestLyricsCandidate
import com.exodidio.tune.lyrics.normalizeLyricsText
import com.exodidio.tune.lyrics.removeFeaturedLyricsTitle
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.metadataObject

internal data class FetchedLyric(val content: String, val source: String)

/** 带 `[mm:ss]` 时间轴的行数，用于比较各来源完整度。 */
internal fun countTimedLyricLines(content: String): Int =
    content.lineSequence().count { line -> line.startsWith("[") && line.getOrNull(1)?.isDigit() == true }

private val LyricTimestampTag = Regex("\\[(\\d+):(\\d+(?:[.:]\\d+)?)\\]")

internal fun lyricTimestamps(content: String): List<Float> =
    LyricTimestampTag.findAll(content).mapNotNull { match ->
        val minutes = match.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
        val secondsText = match.groupValues[2].replace(':', '.')
        val seconds = secondsText.toFloatOrNull() ?: return@mapNotNull null
        minutes * 60f + seconds
    }.toList()

internal fun firstLyricTimestamp(content: String): Float? = lyricTimestamps(content).minOrNull()

internal fun lastLyricTimestamp(content: String): Float? = lyricTimestamps(content).maxOrNull()

internal data class LyricsSearchResult(
    val provider: String,
    val trackName: String,
    val artistName: String,
    val duration: Int,
    val content: String,
    val source: String,
)

internal abstract class LyricsProvider {
    /** AMLL 逐字源标记：供网易云歌词的“无逐字升级”流程识别。 */
    open val isAmll: Boolean get() = false
    abstract fun enabled(settings: LyricsSettings): Boolean
    abstract suspend fun fetch(track: LyricsTrack): FetchedLyric?
    abstract suspend fun search(title: String, artist: String, duration: Int): List<LyricsSearchResult>
}

internal class AndroidLyricsService(
    private val library: AndroidLibrarySyncStore,
    private val providers: List<LyricsProvider> = listOf(
        NeteaseLyricsProvider(),
        AmlldbLyricsProvider(),
        LrclibLyricsProvider(),
        KugouLyricsProvider(),
    ),
    private val appContext: Context? = null,
    private val localFileReader: ((String) -> String?)? = null,
    private val localPathOverride: ((LyricsTrack) -> String?)? = null,
) {
    private val alignScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var alignJob: Job? = null

    @Volatile
    private var alignJobTrackId: String? = null

    @Volatile
    private var alignJobSourceContent: String? = null

    @Volatile
    private var autoAlignEnabled: Boolean = true

    fun cancelAutoAlign(trackId: String? = null) {
        if (trackId == null) {
            alignJob?.cancel()
            alignJob = null
            alignJobTrackId = null
            alignJobSourceContent = null
        } else if (alignJobTrackId == trackId) {
            alignJob?.cancel()
            alignJob = null
            alignJobTrackId = null
            alignJobSourceContent = null
        }
    }

    fun invalidateSearchCache() {
        providers.filterIsInstance<NeteaseLyricsProvider>().forEach { it.clearCache() }
    }

    /**
     * 缺逐字且开关打开时，后台用本地 ONNX 模型做音频强制对齐。
     * 参照 MeloX：切歌/换歌词立即取消上一单，不为已切走的歌继续推理。
     */
    private fun maybeAutoAlign(
        trackId: String,
        content: String,
        audioPath: String?,
        settings: LyricsSettings,
    ) {
        autoAlignEnabled = settings.autoAlignWordTiming
        if (!settings.autoAlignWordTiming) return
        val context = appContext ?: return
        if (AutoSyncCoordinator.hasWordTiming(content)) {
            if (alignJob?.isActive == true && alignJobTrackId == trackId) {
                cancelAutoAlign(trackId)
            }
            return
        }
        if (alignJob?.isActive == true) {
            // 同一首歌且同一份行级歌词的重复触发只保留一单；若歌词内容已变则取消旧单。
            if (alignJobTrackId == trackId && alignJobSourceContent == content) return
            alignJob?.cancel()
        }
        alignJobTrackId = trackId
        alignJobSourceContent = content
        val stored = audioPath?.trim()?.takeIf { it.isNotBlank() }
        alignJob = alignScope.launch {
            var source: String? = null
            try {
                if (AutoSyncCoordinator.hasWordTiming(content)) return@launch
                if (AutoSyncModelStore.installedLanguages(context).isEmpty()) {
                    AutoSyncTrace.log(context, "auto-align skipped: no models installed track=$trackId")
                    return@launch
                }
                source = runCatching { resolveAlignmentSource(context, trackId, stored) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        AutoSyncTrace.log(context, "auto-align audio resolve failed track=$trackId", error)
                    }
                    .getOrNull()
                    ?: run {
                        AutoSyncTrace.log(context, "auto-align skipped: audio unavailable track=$trackId stored=$stored")
                        return@launch
                    }
                currentCoroutineContext().ensureActive()
                AutoSyncTrace.log(context, "auto-align start track=$trackId source=$source")
                val result = try {
                    AutoSyncCoordinator.align(context, trackId, content, source)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    AutoSyncTrace.log(context, "auto-align failed track=$trackId", error)
                    null
                }
                currentCoroutineContext().ensureActive()
                if (result != null) {
                    if (!autoAlignEnabled || alignJobTrackId != trackId || alignJobSourceContent != content) {
                        AutoSyncTrace.log(context, "auto-align discarded stale result track=$trackId")
                        return@launch
                    }
                    val latest = library.currentProviderLyrics(trackId)
                    if (latest != null && (AutoSyncCoordinator.hasWordTiming(latest) || latest != content)) {
                        AutoSyncTrace.log(context, "auto-align skipped overwrite newer lyrics track=$trackId")
                        return@launch
                    }
                    library.saveProviderLyrics(trackId, result.content, "autosync")
                    AutoSyncTrace.log(context, "auto-aligned lines=${result.timeline.lines.size} track=$trackId")
                } else {
                    AutoSyncTrace.log(context, "auto-align produced no timing track=$trackId")
                }
            } finally {
                source?.let { cleanupAlignmentCache(context, it) }
            }
        }
    }

    fun effectiveSettingsFor(trackId: String, settings: LyricsSettings): LyricsSettings {
        if (!settings.perSongLyricsConfigEnabled) return settings
        return applyPerSongLyricsConfig(settings, PerSongLyricsConfigStore.configFor(trackId))
    }

    /** 已缓存的歌词不会再 fetch：切歌时补一次对齐检查。 */
    suspend fun alignCachedLyrics(trackId: String, content: String, settings: LyricsSettings) {
        val effective = effectiveSettingsFor(trackId, settings)
        autoAlignEnabled = effective.autoAlignWordTiming
        if (effective.autoAlignWordTiming.not()) return
        if (AutoSyncCoordinator.hasWordTiming(content)) return
        val track = library.trackForPlayback(trackId) ?: return
        maybeAutoAlign(trackId, content, track.audioPath, effective)
    }

    /**
     * 显式切换单曲或全局歌词源：
     * - source == null：清掉当前缓存并按默认优先级重抓
     * - source != null：优先从指定源抓取并立即更新展示（不走回退覆盖）
     */
    suspend fun fetchFromSource(
        trackId: String,
        source: LyricsSourceId?,
        settings: LyricsSettings,
    ): Boolean {
        cancelAutoAlign(trackId)
        val effective = effectiveSettingsFor(trackId, settings)
        if (source == null) {
            library.clearProviderLyrics(trackId)
            fetch(trackId, effective)
            return !library.currentProviderLyrics(trackId).isNullOrBlank()
        }
        val track = library.trackForPlayback(trackId) ?: return false
        val isNeteaseTrack = trackId.startsWith("netease:")
        if (!sourceApplicable(source, isNeteaseTrack)) return false
        val provider = providers.firstOrNull { sourceIdOf(it) == source } ?: return false
        val durationSeconds = (track.metadataObject()?.get("duration") as? JsonPrimitive)
            ?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
        val lyricsTrack = LyricsTrack(
            title = track.title,
            artist = track.artists,
            album = track.album,
            duration = durationSeconds,
            audioPath = track.audioPath,
            trackId = trackId,
        )
        val timeout = when (source) {
            LyricsSourceId.Netease -> NeteasePriorityTimeoutMs
            LyricsSourceId.Amll -> AmllUpgradeTimeoutMs
            else -> ProviderTimeoutMs
        }
        val attempts = if (source == LyricsSourceId.Netease) NeteaseLyricAttempts else 1
        var hit: FetchedLyric? = null
        for (attempt in 1..attempts) {
            hit = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(timeout) { provider.fetch(lyricsTrack) }
            }.getOrNull()
            if (hit != null) break
            if (attempt < attempts) kotlinx.coroutines.delay(NeteaseLyricRetryDelayMs * attempt)
        }
        if (hit == null) return false
        currentCoroutineContext().ensureActive()
        library.saveProviderLyrics(trackId, hit.content, hit.source)
        maybeAutoAlign(trackId, hit.content, lyricsTrack.audioPath, effective)
        return true
    }

    /** 对齐需要 FFmpeg 能直接打开的源：本地文件、http(s) 直链或临时拷出的 content://。 */
    private suspend fun resolveAlignmentSource(
        context: Context,
        trackId: String,
        storedPath: String?,
    ): String? {
        val target = com.exodidio.tune.library.resolveAudioTarget(context.filesDir, storedPath)
            ?: return null
        return when (target) {
            is com.exodidio.tune.library.AudioTarget.FileTarget ->
                target.file.takeIf { it.isFile }?.absolutePath
            is com.exodidio.tune.library.AudioTarget.ContentTarget ->
                copyContentToCache(context, trackId, target.uri)
            is com.exodidio.tune.library.AudioTarget.RemoteTarget -> target.url
            is com.exodidio.tune.library.AudioTarget.StreamTarget -> {
                val songId = target.url.removePrefix("netease://").toLongOrNull() ?: return null
                val url = com.exodidio.tune.netease.NeteaseRepository.streamUrl(context, songId)
                    ?: return null
                // 试听只有 30 秒，拿它对齐整首歌词只会产出错误时间轴。
                if (com.exodidio.tune.netease.NeteaseRepository.isPreviewStream(songId)) return null
                url
            }
        }
    }

    private suspend fun copyContentToCache(context: Context, trackId: String, uri: String): String? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "autosync-audio").apply { mkdirs() }
            val name = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".bin"
            val file = File(dir, name)
            runCatching {
                context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
                    file.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                file.absolutePath
            }.onFailure {
                AutoSyncTrace.log(context, "copy audio failed track=$trackId uri=$uri", it)
                file.delete()
            }.getOrNull()
        }

    private fun cleanupAlignmentCache(context: Context, source: String) {
        val cacheDir = File(context.cacheDir, "autosync-audio")
        if (source.startsWith(cacheDir.absolutePath)) File(source).delete()
    }

    suspend fun fetch(trackId: String, settings: LyricsSettings) {
        val effectiveSettings = effectiveSettingsFor(trackId, settings)
        val track = library.trackForPlayback(trackId) ?: run {
            Log.w("TuneLyrics", "Cannot fetch lyrics: track $trackId is unavailable")
            return
        }
        val durationSeconds = (track.metadataObject()?.get("duration") as? JsonPrimitive)
            ?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
        val lyricsTrack = LyricsTrack(
            title = track.title,
            artist = track.artists,
            album = track.album,
            duration = durationSeconds,
            audioPath = track.audioPath,
            trackId = trackId,
        )
        val hasPerSongPreferred = effectiveSettings.perSongLyricsConfigEnabled &&
            PerSongLyricsConfigStore.configFor(trackId)?.preferredSourceId != null
        if (!hasPerSongPreferred) {
            readLocalLrc(trackId, lyricsTrack)?.let { local ->
                library.saveProviderLyrics(trackId, local, "local-lrc")
                maybeAutoAlign(trackId, local, lyricsTrack.audioPath, effectiveSettings)
                Log.d("TuneLyrics", "Using local .lrc lyrics for ${lyricsTrack.title}")
                return
            }
        }
        val active = providers.filter { it.enabled(effectiveSettings) }
        if (active.isEmpty()) return
        val isNeteaseTrack = trackId.startsWith("netease:")
        val providersById = active.mapNotNull { provider -> sourceIdOf(provider)?.let { it to provider } }.toMap()
        val ordered = effectiveSettings.sourceOrder
            .filter { it in effectiveSettings.enabledSources }
            .filter { sourceApplicable(it, isNeteaseTrack) }
        if (ordered.isEmpty()) {
            Log.d("TuneLyrics", "LYR none(no-source) track=$trackId")
            return
        }

        // 按用户排序依次抓取；网易云保留 3 次重试。
        var lyric: FetchedLyric? = null
        var lyricSource: LyricsSourceId? = null
        for (sourceId in ordered) {
            val provider = providersById[sourceId] ?: continue
            val timeout = when (sourceId) {
                LyricsSourceId.Netease -> NeteasePriorityTimeoutMs
                LyricsSourceId.Amll -> AmllUpgradeTimeoutMs
                else -> ProviderTimeoutMs
            }
            val attempts = if (sourceId == LyricsSourceId.Netease) NeteaseLyricAttempts else 1
            var hit: FetchedLyric? = null
            for (attempt in 1..attempts) {
                val started = android.os.SystemClock.elapsedRealtime()
                hit = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(timeout) { provider.fetch(lyricsTrack) }
                }.getOrNull()
                Log.d(
                    "TuneLyrics",
                    "Provider ${sourceId.storageValue} attempt=$attempt " +
                        "took ${android.os.SystemClock.elapsedRealtime() - started}ms hit=${hit != null}",
                )
                if (hit != null) break
                if (attempt < attempts) kotlinx.coroutines.delay(NeteaseLyricRetryDelayMs * attempt)
            }
            if (hit != null) {
                lyric = hit
                lyricSource = sourceId
                break
            }
        }

        // 只有行级歌词（或完全没有）时，按设置去替补源优先找逐字（单曲指定了偏好源时不强行覆盖）。
        val fallbackId = if (hasPerSongPreferred) null else effectiveSettings.wordFallbackSource
        val needsWordUpgrade = lyric == null || !hasWordTimingLyrics(lyric.content)
        if (needsWordUpgrade && fallbackId != null && fallbackId != lyricSource &&
            fallbackId in effectiveSettings.enabledSources && sourceApplicable(fallbackId, isNeteaseTrack)
        ) {
            providersById[fallbackId]?.let { provider ->
                val started = android.os.SystemClock.elapsedRealtime()
                val upgraded = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(AmllUpgradeTimeoutMs) { provider.fetch(lyricsTrack) }
                }.getOrNull()
                Log.d(
                    "TuneLyrics",
                    "Provider word-fallback ${fallbackId.storageValue} " +
                        "took ${android.os.SystemClock.elapsedRealtime() - started}ms " +
                        "hit=${upgraded != null} word=${hasWordTimingLyrics(upgraded?.content)}",
                )
                if (upgraded != null && (lyric == null || hasWordTimingLyrics(upgraded.content))) {
                    lyric = upgraded
                }
            }
        }

        lyric?.let {
            currentCoroutineContext().ensureActive()
            library.saveProviderLyrics(trackId, it.content, it.source)
            maybeAutoAlign(trackId, it.content, lyricsTrack.audioPath, effectiveSettings)
            Log.d(
                "TuneLyrics",
                "LYR source=${it.source} track=$trackId chars=${it.content.length} lines=${countTimedLyricLines(it.content)} " +
                    "first=${firstLyricTimestamp(it.content)} last=${lastLyricTimestamp(it.content)} duration=${lyricsTrack.duration}",
            )
        } ?: Log.d("TuneLyrics", "LYR none track=$trackId title=${lyricsTrack.title} duration=${lyricsTrack.duration}")
    }

    private fun sourceIdOf(provider: LyricsProvider): LyricsSourceId? = when {
        provider.isAmll -> LyricsSourceId.Amll
        provider is NeteaseLyricsProvider -> LyricsSourceId.Netease
        provider is LrclibLyricsProvider -> LyricsSourceId.Lrclib
        provider is KugouLyricsProvider -> LyricsSourceId.Kugou
        else -> null
    }

    private fun sourceApplicable(sourceId: LyricsSourceId, isNeteaseTrack: Boolean): Boolean =
        if (sourceId == LyricsSourceId.Amll) isNeteaseTrack else true

    private fun readLocalLrc(trackId: String, track: LyricsTrack): String? {
        val reader: (String) -> String? = localFileReader ?: { path ->
            try {
                File(path).takeIf { it.isFile }?.readText()
            } catch (_: Exception) {
                null
            }
        }
        val audioPath = localPathOverride?.invoke(track) ?: resolveTrackFilePath(trackId, track)
        return resolveLocalLrcContent(audioPath, track.artist, track.title, reader)
    }

    private fun resolveTrackFilePath(trackId: String, track: LyricsTrack): String? {
        track.audioPath?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            if (!raw.startsWith("content://", ignoreCase = true)) return raw
        }
        val mediaId = mediaIdForLocalTrackId(trackId) ?: return null
        val context = appContext ?: return null
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun search(trackId: String, title: String, artist: String, settings: LyricsSettings): List<LyricsSearchResult> {
        if (title.isBlank()) return emptyList()
        val duration = 0
        return coroutineScope {
            providers.filter { it.enabled(settings) }.map { provider ->
                async {
                    runCatching { provider.search(title.trim(), artist.trim(), duration) }
                        .onFailure { Log.w("TuneLyrics", "Lyrics search provider failed", it) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

}

internal class LrclibLyricsProvider : LyricsProvider() {
    override fun enabled(settings: LyricsSettings) = LyricsSourceId.Lrclib in settings.enabledSources
    override suspend fun fetch(track: LyricsTrack): FetchedLyric? {
        val title = normalizeLyricsText(removeFeaturedLyricsTitle(track.title));
        val artist = normalizeLyricsText(track.artist)
        exact(title, artist, track.album, track.duration)?.let { return it }
        if (track.album.isNotBlank()) exact(title, artist, "", track.duration)?.let { return it }
        val candidates = request(
            "https://lrclib.net/api/search",
            mapOf("track_name" to title, "artist_name" to artist)
        ) ?: return null
        val values = ProviderJson.decodeFromString(ListSerializer, candidates)
        val best = bestLyricsCandidate(values.map {
            LyricsCandidate(
                it.trackName,
                it.artistName,
                it.duration
            )
        }, title, artist, track.duration) ?: return null
        return values.firstOrNull { it.trackName == best.title && it.artistName == best.artist && it.duration == best.durationSeconds }
            ?.toLyric()
    }

    override suspend fun search(title: String, artist: String, duration: Int): List<LyricsSearchResult> = request(
        "https://lrclib.net/api/search",
        mapOf("track_name" to title, "artist_name" to artist),
    )?.let { response ->
        ProviderJson.decodeFromString(ListSerializer, response).mapNotNull { candidate ->
            candidate.toLyric()?.let { lyric ->
                LyricsSearchResult("lrclib", candidate.trackName, candidate.artistName, candidate.duration.toInt(), lyric.content, lyric.source)
            }
        }
    } ?: emptyList()

    private suspend fun exact(
        title: String,
        artist: String,
        album: String,
        duration: Int
    ): FetchedLyric? {
        val params = mutableMapOf(
            "track_name" to title,
            "artist_name" to artist
        ).apply {
            if (album.isNotBlank()) put(
                "album_name",
                album
            ); if (duration > 0) put("duration", duration.toString())
        }
        return request(
            "https://lrclib.net/api/get",
            params
        )?.let {
            ProviderJson.decodeFromString(LrclibCandidate.serializer(), it).toLyric()
        }
    }

    private fun LrclibCandidate.toLyric(): FetchedLyric? = syncedLyrics.takeIf(String::isNotBlank)
        ?.let {
            FetchedLyric(
                decodeLyricsHtml(it),
                "lrclib-synced"
            )
        } ?: plainLyrics.takeIf(String::isNotBlank)?.let {
        FetchedLyric(
            decodeLyricsHtml(it),
            "lrclib-plain"
        )
    }
}

internal class KugouLyricsProvider : LyricsProvider() {
    override fun enabled(settings: LyricsSettings) = LyricsSourceId.Kugou in settings.enabledSources
    override suspend fun fetch(track: LyricsTrack): FetchedLyric? {
        var title = normalizeLyricsText(removeFeaturedLyricsTitle(track.title));
        var artist = normalizeLyricsText(track.artist)
        repeat(3) { attempt ->
            val candidates = kugouSearch("$artist - $title", track.duration * 1000)
            val best = bestLyricsCandidate(candidates.map {
                LyricsCandidate(
                    it.song,
                    it.singer,
                    it.duration / 1000.0,
                    it.score
                )
            }, title, artist, track.duration)
            candidates.firstOrNull { best != null && it.song == best.title && it.singer == best.artist && it.duration / 1000.0 == best.durationSeconds }
                ?.let { candidate ->
                    kugouDownload(
                        candidate.id,
                        candidate.accesskey
                    )?.let { content ->
                        return FetchedLyric(
                            content,
                            if (SyncedLrc.containsMatchIn(content)) "kugou-synced" else "kugou-plain"
                        )
                    }
                }
            if (attempt == 0) {
                title = Brackets.replace(title, ""); artist = Brackets.replace(artist, "")
            } else if (attempt == 1) {
                val old = title; title = artist; artist = old
            }
        }
        return null
    }

    override suspend fun search(title: String, artist: String, duration: Int): List<LyricsSearchResult> = coroutineScope {
        kugouSearch("$artist - $title", duration * 1000).take(5).map { candidate ->
            async {
                kugouDownload(candidate.id, candidate.accesskey)?.takeIf(String::isNotBlank)?.let { content ->
                    LyricsSearchResult(
                        provider = "kugou",
                        trackName = title,
                        artistName = artist,
                        duration = candidate.duration / 1000,
                        content = decodeLyricsHtml(content),
                        source = if (SyncedLrc.containsMatchIn(content)) "kugou-synced" else "kugou-plain",
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun kugouSearch(keyword: String, duration: Int): List<KugouCandidate> = request(
        "http://krcs.kugou.com/search",
        mapOf(
            "ver" to "1",
            "man" to "yes",
            "client" to "mobi",
            "keyword" to keyword,
            "duration" to duration.toString(),
            "hash" to "",
            "album_audio_id" to ""
        )
    )
        ?.let { ProviderJson.decodeFromString(KugouSearch.serializer(), it).candidates }
        ?: emptyList()

    private suspend fun kugouDownload(id: String, key: String): String? = request(
        "https://lyrics.kugou.com/download",
        mapOf(
            "ver" to "1",
            "client" to "pc",
            "id" to id,
            "accesskey" to key,
            "fmt" to "lrc",
            "charset" to "utf8"
        )
    )
        ?.let { ProviderJson.decodeFromString(KugouDownload.serializer(), it).content }
        ?.let { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }

}

private suspend fun request(base: String, params: Map<String, String>): String? {
    val url = base.toHttpUrl().newBuilder().apply {
        params.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Tune-Android/${BuildConfig.VERSION_NAME}")
        .get()
        .build()
    return TuneHttp.client(connectTimeoutMs = 4_000L, readTimeoutMs = 6_000L)
        .executeCancellable(request) { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
}

private const val ProviderTimeoutMs = 8_000L
private const val AmllUpgradeTimeoutMs = 12_000L
private const val NeteasePriorityTimeoutMs = 10_000L
private const val NeteaseLyricAttempts = 3
private const val NeteaseLyricRetryDelayMs = 600L
private const val MissCacheMs = 10 * 60_000L

private val Brackets = Regex("[(（【〔\\[{｛][^)）】〕\\]}｝]*[)）】〕\\]}｝]")
private val SyncedLrc = Regex("(?m)^\\[\\d{2}:\\d{2}\\.\\d+]")
private val HtmlEntity = Regex("&(#(?:x[0-9a-fA-F]+|\\d+)|amp|apos|lt|gt|quot|nbsp);")
private val ListSerializer =
    kotlinx.serialization.builtins.ListSerializer(LrclibCandidate.serializer())
internal val ProviderJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Matches desktop html.UnescapeString without treating LRC line breaks as HTML whitespace. */
internal fun decodeLyricsHtml(content: String): String = HtmlEntity.replace(content) { match ->
    when (val entity = match.groupValues[1]) {
        "amp" -> "&"
        "apos" -> "'"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "nbsp" -> " "
        else -> entity.removePrefix("#").let { value ->
            val radix = if (value.startsWith("x", ignoreCase = true)) 16 else 10
            value.removePrefix("x").removePrefix("X").toIntOrNull(radix)?.toChar()?.toString() ?: match.value
        }
    }
}

internal data class LyricsTrack(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val audioPath: String? = null,
    val trackId: String = "",
)

@Serializable
internal data class LrclibCandidate(
    val trackName: String = "",
    val artistName: String = "",
    val duration: Double = 0.0,
    val syncedLyrics: String = "",
    val plainLyrics: String = ""
)

@Serializable
private data class KugouSearch(val candidates: List<KugouCandidate> = emptyList())
@Serializable
private data class KugouCandidate(
    val id: String,
    val accesskey: String,
    val singer: String = "",
    val song: String = "",
    val duration: Int = 0,
    val score: Int = 0
)

@Serializable
private data class KugouDownload(val content: String = "")
