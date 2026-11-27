package com.exodidio.tune.netease

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.exodidio.tune.net.TuneHttp
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal fun shouldEvictCachedStream(
    cachedLength: Long,
    expectedLength: Long,
    previousVariant: String?,
    newVariant: String,
    hasCachedSpans: Boolean = false,
): Boolean {
    if (expectedLength > 0L && cachedLength > 0L && cachedLength != expectedLength) return true
    if (hasCachedSpans && expectedLength > 0L && cachedLength <= 0L) return true
    if (!previousVariant.isNullOrEmpty() && newVariant.isNotEmpty() && previousVariant != newVariant) return true
    return false
}

internal fun shouldClearLegacyStreamCache(
    storedSchemaVersion: Int,
    targetSchemaVersion: Int,
): Boolean = storedSchemaVersion < targetSchemaVersion

internal fun isSpanRangeFullyCovered(
    spans: List<Pair<Long, Long>>,
    totalBytes: Long,
): Boolean {
    if (totalBytes <= 0L || spans.isEmpty()) return false
    var coveredEnd = 0L
    spans.sortedBy { it.first }.forEach { (position, length) ->
        if (length <= 0L) return@forEach
        if (position > coveredEnd) return false
        coveredEnd = maxOf(coveredEnd, position + length)
        if (coveredEnd >= totalBytes) return true
    }
    return false
}

internal fun isPrematureStreamEnd(
    position: Long,
    totalBytes: Long,
    expectedBytes: Long,
): Boolean {
    if (totalBytes > 0L && position < totalBytes) return true
    if (expectedBytes > 0L && position < expectedBytes) return true
    return false
}

/**
 * 网易云在线播放的进程内流式数据源与本地缓存管理器：
 * 通过 Media3 SimpleCache（LRU、800MB 默认）与 OkHttp 上游拉流，
 * 并由 FFmpeg 自定义 AVIOContext 通过 DirectByteBuffer 直接读取，不再经过本地 HTTP 回环代理。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object NeteaseStreamProxy {
    private const val PREFS = "netease_stream_cache"
    private const val VARIANT_PREFS = "netease_stream_variants"
    private const val KEY_SIZE_MB = "size_mb"
    private const val KEY_CACHE_SCHEMA = "cache_schema_version"
    private const val CURRENT_CACHE_SCHEMA = 2
    private const val DEFAULT_SIZE_MB = 800
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 NeteaseMusic/9.0"

    /** 一次流式读取内允许的上游重连次数：轻微网络波动/直链过期在数据源层吸收掉。 */
    internal const val UpstreamMaxAttempts = 3
    internal const val UpstreamRetryDelayMs = 300L
    private const val DefaultErrorWindowMs = 300_000L
    private const val UpstreamConnectTimeoutMs = 4_000L
    private const val UpstreamReadTimeoutMs = 5_000L

    @Volatile private var appContext: Context? = null
    @Volatile private var cache: SimpleCache? = null
    @Volatile private var databaseProvider: StandaloneDatabaseProvider? = null
    @Volatile private var upstreamRefresher: ((Long, (() -> Unit) -> Unit) -> String?)? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    private val upstreamClient: OkHttpClient by lazy {
        TuneHttp.client(
            connectTimeoutMs = UpstreamConnectTimeoutMs,
            readTimeoutMs = UpstreamReadTimeoutMs,
            followRedirects = true,
        )
    }
    private val upstreamUrls = ConcurrentHashMap<Long, String>()
    private val totalBytes = ConcurrentHashMap<Long, Long>()
    private val expectedBytes = ConcurrentHashMap<Long, Long>()
    private val cacheVariants = ConcurrentHashMap<Long, String>()
    private val upstreamErrors = ConcurrentHashMap<Long, Long>()
    private val incompleteStreams = ConcurrentHashMap<Long, Long>()
    /** 试听直链不落盘：和正片编码不同，混在同一 songId 缓存里会在试听 30 秒处坏流。 */
    private val cacheable = ConcurrentHashMap<Long, Boolean>()

    fun setUpstreamRefresher(refresher: (songId: Long, registerCancel: (() -> Unit) -> Unit) -> String?) {
        upstreamRefresher = refresher
    }

    /** 非阻塞初始化：只登记 context，打开缓存放到 IO 上做。 */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (cache != null) return
        ioScope.launch { awaitReady() }
    }

    /** 播放准备等真正需要读取缓存时等待就绪；重复调用幂等。 */
    suspend fun awaitReady() {
        val app = appContext ?: return
        if (cache != null) return
        initMutex.withLock {
            if (cache == null) openCacheLocked(app, sizeLimitMb(app))
        }
    }

    private fun openCacheLocked(context: Context, sizeMb: Int) {
        closeCacheLocked()
        val dir = File(context.filesDir, "netease-stream-cache-dir")
        val provider = StandaloneDatabaseProvider(context)
        databaseProvider = provider
        val newCache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(sizeMb.toLong() * 1024 * 1024), provider)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedSchema = prefs.getInt(KEY_CACHE_SCHEMA, 1)
        if (shouldClearLegacyStreamCache(storedSchema, CURRENT_CACHE_SCHEMA)) {
            Log.i("NeteaseStream", "migrating stream cache schema $storedSchema -> $CURRENT_CACHE_SCHEMA")
            clearCacheInternal(newCache)
            context.getSharedPreferences(VARIANT_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            prefs.edit().putInt(KEY_CACHE_SCHEMA, CURRENT_CACHE_SCHEMA).apply()
        }
        cache = newCache
    }

    private fun closeCacheLocked() {
        runCatching { cache?.release() }
        cache = null
        runCatching { databaseProvider?.close() }
        databaseProvider = null
    }

    fun setUpstream(
        songId: Long,
        url: String,
        allowCache: Boolean = true,
        expectedSizeBytes: Long = 0L,
        cacheVariant: String = "",
    ) {
        upstreamUrls[songId] = url
        cacheable[songId] = allowCache
        upstreamErrors.remove(songId)
        incompleteStreams.remove(songId)
        if (allowCache) {
            val previousVariant = cacheVariants[songId] ?: appContext
                ?.getSharedPreferences(VARIANT_PREFS, Context.MODE_PRIVATE)
                ?.getString(songId.toString(), null)
            val cachedLen = persistedCacheLength(songId)
            val hasSpans = hasCache(songId)
            if (shouldEvictCachedStream(cachedLen, expectedSizeBytes, previousVariant, cacheVariant, hasSpans)) {
                evictCacheSpans(songId)
                totalBytes.remove(songId)
            }
            if (expectedSizeBytes > 0L) expectedBytes[songId] = expectedSizeBytes
            if (cacheVariant.isNotEmpty()) {
                cacheVariants[songId] = cacheVariant
                appContext?.getSharedPreferences(VARIANT_PREFS, Context.MODE_PRIVATE)
                    ?.edit()?.putString(songId.toString(), cacheVariant)?.apply()
            }
        }
    }

    fun openByteSource(songId: Long): NeteaseStreamByteSource {
        val currentCache = cache ?: error("cache not initialized")
        val initialUpstream = upstreamUrls[songId] ?: upstreamRefresher?.invoke(songId) {}
        val hasCachedSpans = hasCache(songId)
        if (initialUpstream.isNullOrBlank() && !hasCachedSpans) {
            error("no upstream url")
        }
        val allowCache = cacheable[songId] != false
        val knownExpected = expectedBytes[songId]?.takeIf { it > 0L }
        val knownTotal = totalBytes[songId]?.takeIf { it > 0L }
            ?: knownExpected
            ?: persistedCacheLength(songId).takeIf { it > 0L }
        return NeteaseStreamByteSource(
            songId = songId,
            allowCache = allowCache,
            initialUpstream = initialUpstream.orEmpty(),
            knownExpectedBytes = knownExpected,
            knownTotalBytes = knownTotal,
            upstreamRefresher = upstreamRefresher,
            chunkSourceFactory = { onCallChanged, isCancelled ->
                val upstreamFactory = DataSource.Factory {
                    OkHttpStreamDataSource(
                        httpClient = upstreamClient,
                        userAgent = USER_AGENT,
                        onCallChanged = onCallChanged,
                        isCancelled = isCancelled,
                    )
                }
                val readWriteSource = if (allowCache) {
                    CacheDataSource.Factory()
                        .setCache(currentCache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(currentCache))
                        .createDataSource()
                } else {
                    upstreamFactory.createDataSource()
                }
                val probeSource = if (allowCache) {
                    CacheDataSource.Factory()
                        .setCache(currentCache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setCacheWriteDataSinkFactory(null)
                        .createDataSource()
                } else {
                    upstreamFactory.createDataSource()
                }
                Media3StreamChunkSource(
                    songId = songId,
                    cacheKey = keyFor(songId),
                    readWriteDataSource = readWriteSource,
                    probeDataSource = probeSource,
                )
            },
            onEvictCache = { id -> evictCacheSpans(id) },
            onResolvedTotal = { id, resolved -> if (allowCache) totalBytes[id] = resolved },
            onMarkIncomplete = { id -> recordMapTimestamp(incompleteStreams, id) },
            onStreamError = { id ->
                recordMapTimestamp(upstreamErrors, id)
                recordMapTimestamp(incompleteStreams, id)
            },
            onStreamComplete = { id ->
                incompleteStreams.remove(id)
                upstreamErrors.remove(id)
            },
        )
    }

    private fun recordMapTimestamp(target: ConcurrentHashMap<Long, Long>, songId: Long) {
        val now = System.currentTimeMillis()
        target[songId] = now
        if (target.size > 256) {
            val cutoff = now - DefaultErrorWindowMs
            target.entries.removeIf { entry -> entry.value < cutoff }
        }
    }

    fun hasCache(songId: Long): Boolean {
        if (cacheable[songId] == false) return false
        val key = keyFor(songId)
        return runCatching { cache?.getCachedSpans(key)?.isNotEmpty() == true }.getOrDefault(false)
    }

    /** 丢弃一首歌的缓存与上游状态（登录失效、发现试听残留或坏缓存时用）。 */
    fun removeSong(songId: Long) {
        evictCacheSpans(songId)
        upstreamUrls.remove(songId)
        totalBytes.remove(songId)
        expectedBytes.remove(songId)
        cacheVariants.remove(songId)
        upstreamErrors.remove(songId)
        incompleteStreams.remove(songId)
        cacheable.remove(songId)
    }

    fun isFullyCached(songId: Long): Boolean {
        if (cacheable[songId] == false) return false
        val total = totalBytes[songId]
            ?: expectedBytes[songId]?.takeIf { it > 0L }
            ?: persistedCacheLength(songId).takeIf { it > 0L }
            ?: return false
        val spans = runCatching { cache?.getCachedSpans(keyFor(songId)) }.getOrNull().orEmpty()
            .map { it.position to it.length }
        return isSpanRangeFullyCovered(spans, total)
    }

    /** 校验从 0 字节起是否已有连续足够的缓冲字节，供下一首无锁预加载判定。 */
    fun hasWarmedPrefix(songId: Long, minBytes: Long = 128L * 1024L): Boolean {
        if (cacheable[songId] == false) return false
        val spans = runCatching { cache?.getCachedSpans(keyFor(songId)) }.getOrNull().orEmpty()
        if (spans.isEmpty()) return false
        var end = 0L
        spans.sortedBy { it.position }.forEach { span ->
            if (span.position > end) return@forEach
            end = maxOf(end, span.position + span.length)
        }
        val total = totalBytes[songId]
            ?: expectedBytes[songId]?.takeIf { it > 0L }
            ?: persistedCacheLength(songId).takeIf { it > 0L }
        if (total != null && total > 0L && end >= total) return true
        return end >= minBytes
    }

    private val warmingSongIds = ConcurrentHashMap.newKeySet<Long>()

    /** 在 IO 线程限时预热下一首头部字节到 SimpleCache，不占用播放器命令锁。 */
    suspend fun warmHeadBytes(songId: Long, targetBytes: Long = 256L * 1024L): Boolean {
        if (cacheable[songId] == false) return false
        if (hasWarmedPrefix(songId, minBytes = 128L * 1024L)) return true
        awaitReady()
        if (cache == null) return false
        if (!warmingSongIds.add(songId)) return hasWarmedPrefix(songId)
        return try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                    var source: NeteaseStreamByteSource? = null
                    try {
                        source = openByteSource(songId)
                        var totalRead = 0L
                        while (totalRead < targetBytes) {
                            val chunk = source.read(32 * 1024)
                            if (chunk <= 0) break
                            totalRead += chunk
                        }
                        hasWarmedPrefix(songId, minBytes = 64L * 1024L) || totalRead > 0L
                    } catch (_: Throwable) {
                        false
                    } finally {
                        runCatching { source?.close() }
                    }
                } ?: false
            }
        } finally {
            warmingSongIds.remove(songId)
        }
    }

    private fun evictCacheSpans(songId: Long) {
        val current = cache ?: return
        runCatching {
            current.getCachedSpans(keyFor(songId)).forEach { span ->
                runCatching { current.removeSpan(span) }
            }
        }
        totalBytes.remove(songId)
        cacheVariants.remove(songId)
        appContext?.getSharedPreferences(VARIANT_PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(songId.toString())?.apply()
    }

    private fun persistedCacheLength(songId: Long): Long {
        val current = cache ?: return C.LENGTH_UNSET.toLong()
        return runCatching {
            ContentMetadata.getContentLength(current.getContentMetadata(keyFor(songId)))
        }.getOrDefault(C.LENGTH_UNSET.toLong())
    }

    /** 登录/登出后清空所有上游绑定；缓存 span 由调用方按需 removeSong。 */
    fun invalidateUpstreams() {
        upstreamUrls.clear()
        totalBytes.clear()
        expectedBytes.clear()
        cacheVariants.clear()
        upstreamErrors.clear()
        incompleteStreams.clear()
        cacheable.clear()
    }

    fun usageBytes(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    fun hasRecentUpstreamError(songId: Long, windowMs: Long = DefaultErrorWindowMs): Boolean =
        (upstreamErrors[songId] ?: 0L) > System.currentTimeMillis() - windowMs

    fun wasStreamIncomplete(songId: Long, windowMs: Long = DefaultErrorWindowMs): Boolean =
        (incompleteStreams[songId] ?: 0L) > System.currentTimeMillis() - windowMs

    /** 从 0 开始连续缓存的时长；断网拖动时用来把进度卡在缓存末尾。 */
    fun cachedPrefixMs(songId: Long, durationMs: Long): Long {
        if (cacheable[songId] == false) return 0L
        val total = totalBytes[songId]
            ?: expectedBytes[songId]?.takeIf { it > 0L }
            ?: persistedCacheLength(songId).takeIf { it > 0L }
            ?: return 0L
        if (total <= 0L || durationMs <= 0L) return 0L
        val spans = runCatching { cache?.getCachedSpans(keyFor(songId)) }.getOrNull().orEmpty()
        if (spans.isEmpty()) return 0L
        var end = 0L
        spans.sortedBy { it.position }.forEach { span ->
            if (span.position > end) return@forEach
            end = maxOf(end, span.position + span.length)
        }
        if (end <= 0L) return 0L
        return (durationMs.toDouble() * end.coerceAtMost(total) / total).toLong().coerceIn(0L, durationMs)
    }

    fun sizeLimitMb(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SIZE_MB, DEFAULT_SIZE_MB).coerceIn(100, 8192)

    suspend fun setSizeLimitMb(context: Context, sizeMb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SIZE_MB, sizeMb.coerceIn(100, 8192)).apply()
        val app = appContext ?: return
        initMutex.withLock { openCacheLocked(app, sizeMb) }
    }

    fun clearCache() {
        val current = cache ?: return
        clearCacheInternal(current)
        appContext?.getSharedPreferences(VARIANT_PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        cacheVariants.clear()
        totalBytes.clear()
    }

    private fun clearCacheInternal(targetCache: SimpleCache) {
        runCatching {
            targetCache.keys.toList().forEach { key ->
                targetCache.getCachedSpans(key).forEach { span -> runCatching { targetCache.removeSpan(span) } }
            }
        }
    }

    private fun keyFor(songId: Long): String = "netease-$songId"
}

internal interface StreamChunkSource {
    fun open(upstream: String, position: Long): Long
    fun openRange(upstream: String, position: Long, length: Long, writeCache: Boolean): Long =
        open(upstream, position)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun close()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class Media3StreamChunkSource(
    private val songId: Long,
    private val cacheKey: String,
    private val readWriteDataSource: DataSource,
    private val probeDataSource: DataSource,
) : StreamChunkSource {
    private var activeDataSource: DataSource? = null

    override fun open(upstream: String, position: Long): Long =
        openRange(upstream, position, C.LENGTH_UNSET.toLong(), writeCache = true)

    override fun openRange(upstream: String, position: Long, length: Long, writeCache: Boolean): Long {
        val effectiveUri = upstream.ifBlank { "tune-cache://offline/$songId" }
        val target = if (writeCache) readWriteDataSource else probeDataSource
        activeDataSource = target
        return target.open(
            DataSpec.Builder()
                .setUri(effectiveUri)
                .setKey(cacheKey)
                .setPosition(position)
                .setLength(length)
                .build(),
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val target = activeDataSource ?: return C.RESULT_END_OF_INPUT
        return target.read(buffer, offset, length)
    }

    override fun close() {
        val target = activeDataSource ?: return
        activeDataSource = null
        target.close()
    }
}

/**
 * 供 FFmpeg AVIOContext 直接调用的进程内字节流会话。
 * 方法名与签名被 native 层通过 JNI 反射调用，必须配合 ProGuard keep 规则保留。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NeteaseStreamByteSource(
    private val songId: Long,
    private val allowCache: Boolean,
    initialUpstream: String,
    private val knownExpectedBytes: Long?,
    knownTotalBytes: Long?,
    private val upstreamRefresher: ((Long, (() -> Unit) -> Unit) -> String?)?,
    private val chunkSourceFactory: (onCallChanged: (Call?) -> Unit, isCancelled: () -> Boolean) -> StreamChunkSource,
    private val onEvictCache: (Long) -> Unit = {},
    private val onResolvedTotal: (Long, Long) -> Unit = { _, _ -> },
    private val onMarkIncomplete: (Long) -> Unit = {},
    private val onStreamError: (Long) -> Unit = {},
    private val onStreamComplete: (Long) -> Unit = {},
    private val retryDelayMs: Long = NeteaseStreamProxy.UpstreamRetryDelayMs,
    private val maxAttempts: Int = NeteaseStreamProxy.UpstreamMaxAttempts,
) {
    companion object {
        internal const val BUFFER_SIZE = 64 * 1024
        internal const val SEEK_PAGE_SIZE = 128 * 1024
        internal const val MAX_SEEK_PAGES = 32
        internal const val FORWARD_SKIP_LIMIT = 128 * 1024L
        internal const val SEEK_MODE_SET = 0
        internal const val SEEK_MODE_CUR = 1
        internal const val SEEK_MODE_END = 2
        internal const val SEEK_MODE_SIZE = 3
        internal const val RESULT_EOF = 0
        internal const val RESULT_ERROR = -1
        internal const val RESULT_CANCELLED = -2
    }

    val directBuffer: ByteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
    private val scratch = ByteArray(BUFFER_SIZE)
    private val cancelled = AtomicBoolean(false)
    private val latestSeekGeneration = AtomicLong(0L)
    private val currentReadGeneration = AtomicLong(0L)
    private val inSeekGeneration = AtomicLong(-1L)
    private val retryWakeLock = ReentrantLock()
    private val retryWakeCondition = retryWakeLock.newCondition()
    @Volatile private var activeCall: Call? = null
    @Volatile private var activeRefreshCancel: (() -> Unit)? = null
    private var upstream: String = initialUpstream
    private var chunkSource: StreamChunkSource? = null
    private var position: Long = 0L
    private var total: Long = knownTotalBytes ?: C.LENGTH_UNSET.toLong()
    private var cacheEvictedThisSession = false
    private var pendingProbeFetch = false
    private val pageCache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > MAX_SEEK_PAGES
    }

    fun beginSeek(seekGeneration: Long) {
        currentReadGeneration.updateAndGet { current -> maxOf(current, seekGeneration) }
        inSeekGeneration.updateAndGet { current -> maxOf(current, seekGeneration) }
    }

    fun endSeek(seekGeneration: Long) {
        inSeekGeneration.updateAndGet { current -> if (current <= seekGeneration) -1L else current }
        if (currentReadGeneration.get() <= seekGeneration) {
            pendingProbeFetch = false
        }
    }

    fun abortReadForSeek(seekGeneration: Long) {
        latestSeekGeneration.updateAndGet { current -> maxOf(current, seekGeneration) }
        if (currentReadGeneration.get() < seekGeneration) {
            runCatching { activeRefreshCancel?.invoke() }
            runCatching { activeCall?.cancel() }
            retryWakeLock.withLock {
                retryWakeCondition.signalAll()
            }
        }
    }

    private fun isAbortedForRead(readGeneration: Long): Boolean =
        cancelled.get() || latestSeekGeneration.get() > readGeneration

    private fun isActivelySeeking(readGeneration: Long): Boolean =
        inSeekGeneration.get() == readGeneration

    private fun refreshUpstream(readGeneration: Long): String? {
        val refresher = upstreamRefresher ?: return null
        if (isAbortedForRead(readGeneration)) return null
        return try {
            refresher.invoke(songId) { cancelAction ->
                activeRefreshCancel = cancelAction
                if (isAbortedForRead(readGeneration)) {
                    runCatching { cancelAction() }
                }
            }
        } finally {
            activeRefreshCancel = null
        }
    }

    fun read(maxBytes: Int): Int {
        if (cancelled.get()) return RESULT_CANCELLED
        val readGeneration = currentReadGeneration.get()
        if (isAbortedForRead(readGeneration)) return RESULT_CANCELLED
        val want = maxBytes.coerceIn(1, BUFFER_SIZE)
        if (total > 0L && position >= total) {
            closeCurrentDataSource()
            onStreamComplete(songId)
            return RESULT_EOF
        }
        var attempts = 0
        var refreshedUrlThisRead = false
        while (!isAbortedForRead(readGeneration)) {
            try {
                if (chunkSource == null) {
                    val cachedRead = readFromPageCache(want)
                    if (cachedRead > 0) return cachedRead
                    if (pendingProbeFetch || isActivelySeeking(readGeneration)) {
                        if (!fetchProbePage(readGeneration)) {
                            if (total > 0L && position >= total) {
                                onStreamComplete(songId)
                            }
                            return RESULT_EOF
                        }
                        val afterProbeRead = readFromPageCache(want)
                        if (afterProbeRead > 0) return afterProbeRead
                        if (total > 0L && position >= total) {
                            onStreamComplete(songId)
                        }
                        return RESULT_EOF
                    }
                }
                val currentSource = chunkSource ?: openAtCurrentPosition(readGeneration)
                val read = currentSource.read(scratch, 0, want)
                if (read > 0) {
                    position += read
                    directBuffer.clear()
                    directBuffer.put(scratch, 0, read)
                    return read
                }
                if (read == 0) continue
                val expectedEnd = total.takeIf { it > 0L } ?: knownExpectedBytes
                if (isPrematureStreamEnd(position, total, knownExpectedBytes ?: -1L) &&
                    (expectedEnd == null || position < expectedEnd)
                ) {
                    onMarkIncomplete(songId)
                    if (allowCache && !cacheEvictedThisSession) {
                        onEvictCache(songId)
                        cacheEvictedThisSession = true
                    }
                    error("upstream closed early at $position/${expectedEnd ?: -1L}")
                }
                if (position == 0L) {
                    onMarkIncomplete(songId)
                    error("empty upstream response at 0")
                }
                closeCurrentDataSource()
                if (total > 0L && position >= total) {
                    onStreamComplete(songId)
                }
                return RESULT_EOF
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                closeCurrentDataSource()
                if (isAbortedForRead(readGeneration)) return RESULT_CANCELLED
                attempts += 1
                val httpCode = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val authOrRangeFailure = httpCode in listOf(403, 404, 410, 416)
                if (httpCode == 416 && allowCache && !cacheEvictedThisSession) {
                    onEvictCache(songId)
                    cacheEvictedThisSession = true
                }
                if (!refreshedUrlThisRead && (authOrRangeFailure || attempts >= 1) && !isAbortedForRead(readGeneration)) {
                    val refreshed = runCatching { refreshUpstream(readGeneration) }.getOrNull()
                    if (!refreshed.isNullOrBlank()) {
                        upstream = refreshed
                        refreshedUrlThisRead = true
                        Log.w("NeteaseStream", "refreshed-upstream song=$songId code=$httpCode pos=$position")
                    }
                }
                if (isAbortedForRead(readGeneration)) return RESULT_CANCELLED
                if (attempts >= maxAttempts) {
                    onStreamError(songId)
                    Log.w(
                        "NeteaseStream",
                        "failed-upstream song=$songId attempts=$attempts pos=$position err=${error.message}",
                    )
                    return RESULT_ERROR
                }
                Log.w(
                    "NeteaseStream",
                    "retry-upstream song=$songId attempt=$attempts pos=$position err=${error.message}",
                )
                if (Thread.currentThread().isInterrupted) return RESULT_CANCELLED
                val waitMs = (retryDelayMs * attempts).coerceAtLeast(0L)
                if (waitMs > 0L) {
                    retryWakeLock.withLock {
                        if (!isAbortedForRead(readGeneration)) {
                            runCatching { retryWakeCondition.await(waitMs, TimeUnit.MILLISECONDS) }
                        }
                    }
                    if (isAbortedForRead(readGeneration)) return RESULT_CANCELLED
                }
            }
        }
        return RESULT_CANCELLED
    }

    fun seek(offset: Long, mode: Int): Long {
        val readGeneration = currentReadGeneration.get()
        if (isAbortedForRead(readGeneration)) return RESULT_CANCELLED.toLong()
        return runCatching {
            when (mode) {
                SEEK_MODE_SIZE -> resolveTotalSize(readGeneration)
                SEEK_MODE_SET -> applySeek(offset, readGeneration)
                SEEK_MODE_CUR -> applySeek(position + offset, readGeneration)
                SEEK_MODE_END -> {
                    val knownSize = resolveTotalSize(readGeneration)
                    if (isAbortedForRead(readGeneration)) {
                        RESULT_CANCELLED.toLong()
                    } else if (knownSize <= 0L) {
                        -1L
                    } else {
                        applySeek(knownSize + offset, readGeneration)
                    }
                }
                else -> -1L
            }
        }.getOrElse { error ->
            Log.w("NeteaseStream", "seek failed song=$songId offset=$offset mode=$mode err=${error.message}")
            if (isAbortedForRead(readGeneration)) RESULT_CANCELLED.toLong() else -1L
        }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { activeRefreshCancel?.invoke() }
        runCatching { activeCall?.cancel() }
        retryWakeLock.withLock {
            retryWakeCondition.signalAll()
        }
    }

    fun close() {
        cancel()
        closeCurrentDataSource()
        pageCache.clear()
    }

    private fun resolveTotalSize(readGeneration: Long): Long {
        total.takeIf { it > 0L }?.let { return it }
        knownExpectedBytes?.takeIf { it > 0L }?.let { return it }
        if (chunkSource == null && !isAbortedForRead(readGeneration)) {
            runCatching { openAtCurrentPosition(readGeneration) }
        }
        return total.takeIf { it > 0L } ?: -1L
    }

    private fun applySeek(targetPos: Long, readGeneration: Long): Long {
        if (isAbortedForRead(readGeneration)) return RESULT_CANCELLED.toLong()
        if (targetPos < 0L) return -1L
        val limit = total.takeIf { it > 0L } ?: knownExpectedBytes
        if (limit != null && targetPos > limit) return -1L
        if (targetPos == position) return targetPos

        if (hasCachedPageAt(targetPos)) {
            closeCurrentDataSource()
            position = targetPos
            pendingProbeFetch = false
            return targetPos
        }

        val currentSource = chunkSource
        if (currentSource != null && targetPos > position && targetPos - position <= FORWARD_SKIP_LIMIT) {
            val skipResult = skipForwardInPlace(currentSource, targetPos, readGeneration)
            if (skipResult > 0) return targetPos
            if (skipResult < 0) {
                return if (isAbortedForRead(readGeneration)) RESULT_CANCELLED.toLong() else -1L
            }
        }

        closeCurrentDataSource()
        position = targetPos
        pendingProbeFetch = targetPos > 0L || isActivelySeeking(readGeneration)
        return targetPos
    }

    private fun hasCachedPageAt(pos: Long): Boolean {
        for ((start, bytes) in pageCache) {
            if (pos >= start && pos < start + bytes.size) return true
        }
        return false
    }

    private fun readFromPageCache(want: Int): Int {
        var matchedStart = -1L
        var matchedBytes: ByteArray? = null
        for ((start, bytes) in pageCache) {
            if (position >= start && position < start + bytes.size) {
                matchedStart = start
                matchedBytes = bytes
                break
            }
        }
        val bytes = matchedBytes ?: return 0
        pageCache[matchedStart]
        val offset = (position - matchedStart).toInt()
        val count = minOf(want, bytes.size - offset)
        directBuffer.clear()
        directBuffer.put(bytes, offset, count)
        position += count
        return count
    }

    private fun skipForwardInPlace(
        source: StreamChunkSource,
        targetPos: Long,
        readGeneration: Long,
    ): Int {
        while (position < targetPos) {
            if (isAbortedForRead(readGeneration)) {
                closeCurrentDataSource()
                return -1
            }
            val step = minOf(scratch.size.toLong(), targetPos - position).toInt()
            val read = try {
                source.read(scratch, 0, step)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                closeCurrentDataSource()
                return if (isAbortedForRead(readGeneration)) -1 else 0
            }
            if (read > 0) {
                position += read
            } else if (read == 0) {
                continue
            } else {
                closeCurrentDataSource()
                return 0
            }
        }
        return 1
    }

    private fun fetchProbePage(readGeneration: Long): Boolean {
        val fetchStart = if (position >= SEEK_PAGE_SIZE) {
            (position / SEEK_PAGE_SIZE) * SEEK_PAGE_SIZE
        } else {
            position
        }
        val limit = total.takeIf { it > 0L } ?: knownExpectedBytes
        if (limit != null && fetchStart >= limit) {
            pendingProbeFetch = false
            return false
        }
        val fetchLen = if (limit != null) {
            minOf(SEEK_PAGE_SIZE.toLong(), limit - fetchStart)
        } else {
            SEEK_PAGE_SIZE.toLong()
        }
        val targetBytes = fetchLen.toInt().coerceAtLeast(1)
        if (isAbortedForRead(readGeneration)) throw IOException("Stream cancelled")

        val source = chunkSourceFactory(
            { call -> activeCall = call },
            { isAbortedForRead(readGeneration) },
        )
        try {
            source.openRange(upstream, fetchStart, fetchLen, writeCache = allowCache)
            val pageBuf = ByteArray(targetBytes)
            var fetched = 0
            while (fetched < targetBytes) {
                if (isAbortedForRead(readGeneration)) throw IOException("Stream cancelled")
                val read = source.read(pageBuf, fetched, targetBytes - fetched)
                if (read > 0) {
                    fetched += read
                } else if (read == 0) {
                    continue
                } else {
                    break
                }
            }
            if (isAbortedForRead(readGeneration)) throw IOException("Stream cancelled")
            if (fetched == 0) {
                val expectedEnd = total.takeIf { it > 0L } ?: knownExpectedBytes
                if (isPrematureStreamEnd(fetchStart, total, knownExpectedBytes ?: -1L) &&
                    (expectedEnd == null || fetchStart < expectedEnd)
                ) {
                    onMarkIncomplete(songId)
                    if (allowCache && !cacheEvictedThisSession) {
                        onEvictCache(songId)
                        cacheEvictedThisSession = true
                    }
                    error("upstream closed early at $fetchStart/${expectedEnd ?: -1L}")
                }
                if (fetchStart == 0L) {
                    onMarkIncomplete(songId)
                    error("empty upstream response at 0")
                }
                pendingProbeFetch = false
                return false
            }
            val endPos = fetchStart + fetched
            if (fetched < targetBytes) {
                val expectedEnd = total.takeIf { it > 0L } ?: knownExpectedBytes
                if (isPrematureStreamEnd(endPos, total, knownExpectedBytes ?: -1L) &&
                    (expectedEnd == null || endPos < expectedEnd)
                ) {
                    onMarkIncomplete(songId)
                    if (allowCache && !cacheEvictedThisSession) {
                        onEvictCache(songId)
                        cacheEvictedThisSession = true
                    }
                    error("upstream closed early at $endPos/${expectedEnd ?: -1L}")
                }
                if (total <= 0L) {
                    total = endPos
                    onResolvedTotal(songId, endPos)
                }
            }
            val finalBytes = if (fetched == targetBytes) pageBuf else pageBuf.copyOf(fetched)
            pageCache[fetchStart] = finalBytes
            pendingProbeFetch = false
            return true
        } finally {
            runCatching { source.close() }
        }
    }

    private fun openAtCurrentPosition(readGeneration: Long): StreamChunkSource {
        while (true) {
            if (isAbortedForRead(readGeneration)) throw IOException("Stream cancelled")
            val source = chunkSourceFactory(
                { call -> activeCall = call },
                { isAbortedForRead(readGeneration) },
            )
            var keepSource = false
            try {
                val opened = source.open(upstream, position)
                if (opened != C.LENGTH_UNSET.toLong()) {
                    val resolvedTotal = position + opened
                    if (allowCache && knownExpectedBytes != null && resolvedTotal > 0L &&
                        resolvedTotal < knownExpectedBytes && !cacheEvictedThisSession
                    ) {
                        Log.w(
                            "NeteaseStream",
                            "evict-truncated-cache song=$songId resolved=$resolvedTotal expected=$knownExpectedBytes",
                        )
                        onEvictCache(songId)
                        cacheEvictedThisSession = true
                        continue
                    }
                    if (resolvedTotal > 0L) {
                        total = resolvedTotal
                        onResolvedTotal(songId, resolvedTotal)
                    }
                }
                chunkSource = source
                keepSource = true
                return source
            } finally {
                if (!keepSource) {
                    runCatching { source.close() }
                }
            }
        }
    }

    private fun closeCurrentDataSource() {
        val current = chunkSource ?: return
        chunkSource = null
        runCatching { current.close() }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class OkHttpStreamDataSource(
    private val httpClient: OkHttpClient,
    private val userAgent: String,
    private val onCallChanged: (Call?) -> Unit,
    private val isCancelled: () -> Boolean,
) : BaseDataSource(/* isNetwork = */ true) {
    private var dataSpec: DataSpec? = null
    private var activeCall: Call? = null
    private var response: Response? = null
    private var responseStream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var reachedEof = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") {
            throw IOException("No upstream url")
        }
        this.dataSpec = dataSpec
        reachedEof = false
        transferInitializing(dataSpec)
        val requestBuilder = Request.Builder()
            .url(uri.toString())
            .header("User-Agent", userAgent)
            .header("Referer", "https://music.163.com/")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
        if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val rangeHeader = buildString {
                append("bytes=").append(dataSpec.position).append('-')
                if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    append(dataSpec.position + dataSpec.length - 1L)
                }
            }
            requestBuilder.header("Range", rangeHeader)
        }
        val call = httpClient.newCall(requestBuilder.build())
        activeCall = call
        onCallChanged(call)
        if (isCancelled()) {
            call.cancel()
            throw IOException("Stream cancelled")
        }
        val resp = call.execute()
        response = resp
        if (!resp.isSuccessful) {
            val headers = resp.headers.toMultimap()
            val code = resp.code
            val message = resp.message
            resp.close()
            response = null
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                message,
                null,
                headers,
                dataSpec,
                ByteArray(0),
            )
        }
        if (dataSpec.position > 0L && resp.code == 200) {
            resp.close()
            response = null
            throw IOException("Unexpected 200 for range request at ${dataSpec.position}")
        }
        val body = resp.body ?: run {
            resp.close()
            response = null
            throw IOException("Empty response body")
        }
        responseStream = body.byteStream()
        val contentLength = body.contentLength()
        val bytesToRead = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() && contentLength >= 0L ->
                minOf(dataSpec.length, contentLength)
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength >= 0L -> contentLength
            else -> C.LENGTH_UNSET.toLong()
        }
        bytesRemaining = bytesToRead
        if (bytesToRead == 0L) {
            reachedEof = true
        }
        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) {
            reachedEof = true
            return C.RESULT_END_OF_INPUT
        }
        val stream = responseStream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = stream.read(buffer, offset, toRead)
        if (read < 0) {
            reachedEof = true
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read
            if (bytesRemaining == 0L) {
                reachedEof = true
            }
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? =
        response?.request?.url?.toString()?.let(Uri::parse) ?: dataSpec?.uri

    override fun close() {
        dataSpec = null
        runCatching { responseStream?.close() }
        responseStream = null
        runCatching { response?.close() }
        response = null
        if (!reachedEof) {
            activeCall?.cancel()
        }
        activeCall = null
        reachedEof = false
        onCallChanged(null)
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
