package com.exodidio.tune.netease

import android.content.Context
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网易云在线播放的本地缓存代理：
 * FFmpeg 连接 127.0.0.1，代理通过 Media3 SimpleCache（LRU、800MB 默认）读写缓存，
 * 命中时完全不访问网络；未命中时转发上游并写入缓存。
 */
internal object NeteaseStreamProxy {
    private const val PREFS = "netease_stream_cache"
    private const val KEY_SIZE_MB = "size_mb"
    private const val DEFAULT_SIZE_MB = 800
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 NeteaseMusic/9.0"
    /** 一次本地下发请求内允许的上游重连次数：轻微网络波动在代理层吸收掉。 */
    private const val UpstreamMaxAttempts = 3
    private const val UpstreamRetryDelayMs = 300L
    /** 单条上游连接最多连续读取的字节数，超过就用 Range 换新连接，避免被 CDN 半路掐断。 */
    private const val UpstreamMaxBytesPerConnection = 4L * 1024 * 1024

    @Volatile private var appContext: Context? = null
    @Volatile private var cache: SimpleCache? = null
    @Volatile private var databaseProvider: StandaloneDatabaseProvider? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var port: Int = 0
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    private val upstreamUrls = ConcurrentHashMap<Long, String>()
    private val totalBytes = ConcurrentHashMap<Long, Long>()
    private val upstreamErrors = ConcurrentHashMap<Long, Long>()

    /** 非阻塞初始化：只登记 context，打开缓存/监听端口放到 IO 上做。 */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (cache != null && server != null) return
        ioScope.launch { awaitReady() }
    }

    /** 播放准备等真正需要代理时等待就绪；重复调用幂等。 */
    suspend fun awaitReady() {
        val app = appContext ?: return
        if (cache != null && server != null) return
        initMutex.withLock {
            if (cache == null) openCacheLocked(app, sizeLimitMb(app))
            if (server == null) startServerLocked()
        }
    }

    private fun openCacheLocked(context: Context, sizeMb: Int) {
        closeCacheLocked()
        val dir = File(context.filesDir, "netease-stream-cache-dir")
        val provider = StandaloneDatabaseProvider(context)
        databaseProvider = provider
        cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(sizeMb.toLong() * 1024 * 1024), provider)
    }

    private fun closeCacheLocked() {
        runCatching { cache?.release() }
        cache = null
        runCatching { databaseProvider?.close() }
        databaseProvider = null
    }

    fun setUpstream(songId: Long, url: String) {
        upstreamUrls[songId] = url
    }

    fun localUrl(songId: Long): String = "http://127.0.0.1:$port/stream/$songId"

    fun hasCache(songId: Long): Boolean {
        val key = keyFor(songId)
        return runCatching { cache?.getCachedSpans(key)?.isNotEmpty() == true }.getOrDefault(false)
    }

    fun usageBytes(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    fun hasRecentUpstreamError(songId: Long, windowMs: Long = 60_000L): Boolean =
        (upstreamErrors[songId] ?: 0L) > System.currentTimeMillis() - windowMs

    /** 从 0 开始连续缓存的时长；断网拖动时用来把进度卡在缓存末尾。 */
    fun cachedPrefixMs(songId: Long, durationMs: Long): Long {
        val total = totalBytes[songId] ?: return 0L
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
        runCatching {
            current.keys.toList().forEach { key ->
                current.getCachedSpans(key).forEach { span -> runCatching { current.removeSpan(span) } }
            }
        }
    }

    private fun keyFor(songId: Long): String = "netease-$songId"

    private fun startServerLocked() {
        if (server != null) return
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                runCatching {
                    val client = socket.accept()
                    Thread({ serve(client) }, "netease-stream-proxy-client").apply { isDaemon = true }.start()
                }
            }
        }, "netease-stream-proxy").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val output = client.getOutputStream()
            var currentSongId: Long? = null
            var headersSent = false
            runCatching {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split <= 0) continue
                    if (line.substring(0, split).equals("Range", ignoreCase = true)) {
                        rangeHeader = line.substring(split + 1).trim()
                    }
                }
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                val songId = path.removePrefix("/stream/").substringBefore('?').toLongOrNull()
                currentSongId = songId
                if (!path.startsWith("/stream/") || songId == null) {
                    writeSimple(output, "404 Not Found", "bad request")
                    return
                }
                streamSong(output, songId, rangeHeader) { headersSent = true }
            }.onFailure {
                // 重连次数用尽才记为上游错误：短暂的网络波动不应触发“卡在缓存末尾并暂停”。
                currentSongId?.let { id -> upstreamErrors[id] = System.currentTimeMillis() }
                if (!headersSent) {
                    runCatching { writeSimple(output, "502 Bad Gateway", it.message ?: "proxy error") }
                }
            }
        }
    }

    private fun streamSong(
        output: OutputStream,
        songId: Long,
        rangeHeader: String?,
        onHeadersSent: () -> Unit,
    ) {
        val currentCache = cache ?: error("cache not initialized")
        val upstream = upstreamUrls[songId] ?: error("no upstream url")
        val range = parseRange(rangeHeader)
        val start = range?.first ?: 0L
        val end = range?.second
        val factory = CacheDataSource.Factory()
            .setCache(currentCache)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(4_000)
                    .setReadTimeoutMs(5_000)
                    .setDefaultRequestProperties(
                        mapOf(
                            "Referer" to "https://music.163.com/",
                            "Accept" to "*/*",
                            "Accept-Encoding" to "identity",
                        ),
                    ),
            )
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(currentCache))

        val buffer = ByteArray(64 * 1024)
        var position = start
        var remaining: Long = if (end != null) end - start + 1 else C.LENGTH_UNSET.toLong()
        var total: Long = C.LENGTH_UNSET.toLong()
        var dataSource: DataSource? = null
        var attempts = 0
        var headersSent = false
        var connectionStart = position
        try {
            while (remaining != 0L) {
                try {
                    if (dataSource == null) {
                        val source = factory.createDataSource()
                        val opened = source.open(
                            DataSpec.Builder()
                                .setUri(upstream)
                                .setKey(keyFor(songId))
                                .setPosition(position)
                                .setLength(remaining)
                                .build(),
                        )
                        dataSource = source
                        connectionStart = position
                        if (end == null && total == C.LENGTH_UNSET.toLong() && opened != C.LENGTH_UNSET.toLong()) {
                            total = position + opened
                            if (total > 0L) totalBytes[songId] = total
                        }
                        if (!headersSent) {
                            writeStreamHeaders(output, start, end, total, opened)
                            headersSent = true
                            onHeadersSent()
                        }
                    }
                    val want = if (remaining == C.LENGTH_UNSET.toLong()) {
                        buffer.size
                    } else {
                        minOf(buffer.size.toLong(), remaining).toInt()
                    }
                    if (want <= 0) break
                    val read = (dataSource ?: error("data source closed")).read(buffer, 0, want)
                    if (read < 0) {
                        if (total != C.LENGTH_UNSET.toLong() && position < total) {
                            error("upstream closed early at $position/$total")
                        }
                        break
                    }
                    try {
                        output.write(buffer, 0, read)
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        // 本地客户端已关闭（切歌/暂停/Media3 放弃读取）：主动取消，不是上游断流。
                        System.err.println("NETSTREAM cancel song=$songId pos=$position err=${error.message}")
                        return
                    }
                    position += read
                    if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
                    // 上游长连接容易被 CDN 半路掐断（~6MB Broken pipe）：定期换新连接续传。
                    if (position - connectionStart >= UpstreamMaxBytesPerConnection) {
                        runCatching { dataSource?.close() }
                        dataSource = null
                    }
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val connBytes = position - connectionStart
                    runCatching { dataSource?.close() }
                    dataSource = null
                    if (remaining == 0L) break
                    attempts += 1
                    if (attempts >= UpstreamMaxAttempts) {
                        System.err.println(
                            "NETSTREAM failed-upstream song=$songId attempts=$attempts pos=$position connBytes=$connBytes err=${error.message}",
                        )
                        throw error
                    }
                    System.err.println(
                        "NETSTREAM retry-upstream song=$songId attempt=$attempts pos=$position connBytes=$connBytes err=${error.message}",
                    )
                    Thread.sleep(UpstreamRetryDelayMs * attempts)
                }
            }
            try {
                output.flush()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                System.err.println("NETSTREAM cancel song=$songId pos=$position err=${error.message}")
            }
        } finally {
            runCatching { dataSource?.close() }
        }
    }

    private fun writeStreamHeaders(
        output: OutputStream,
        start: Long,
        end: Long?,
        total: Long,
        openedLength: Long,
    ) {
        val contentLength = when {
            end != null -> end - start + 1
            total != C.LENGTH_UNSET.toLong() -> total - start
            else -> openedLength
        }
        val status = if (start > 0L || end != null) "206 Partial Content" else "200 OK"
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: audio/mpeg\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (contentLength != C.LENGTH_UNSET.toLong() && contentLength >= 0L) {
                append("Content-Length: ").append(contentLength).append("\r\n")
            }
            if (start > 0L || end != null) {
                if (total != C.LENGTH_UNSET.toLong()) {
                    val contentEnd = end ?: (total - 1)
                    append("Content-Range: bytes ").append(start).append('-').append(contentEnd)
                        .append('/').append(total).append("\r\n")
                }
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
    }

    private fun parseRange(header: String?): Pair<Long, Long?>? {
        val value = header?.trim() ?: return null
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val spec = value.substringAfter('=').trim()
        if (spec.contains(',')) return null
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()
        val start = startText.toLongOrNull() ?: return null
        val end = endText.toLongOrNull()
        if (end != null && end < start) return null
        return start to end
    }

    private fun writeSimple(output: OutputStream, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }
}
