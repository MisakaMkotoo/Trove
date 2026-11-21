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

    private var appContext: Context? = null
    private var cache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private var server: ServerSocket? = null
    private var port: Int = 0
    private val upstreamUrls = ConcurrentHashMap<Long, String>()
    private val totalBytes = ConcurrentHashMap<Long, Long>()
    private val upstreamErrors = ConcurrentHashMap<Long, Long>()

    @Synchronized
    fun initialize(context: Context) {
        if (cache != null) return
        appContext = context.applicationContext
        openCache(context.applicationContext, sizeLimitMb(context))
        startServer()
    }

    @Synchronized
    private fun openCache(context: Context, sizeMb: Int) {
        closeCache()
        val dir = File(context.filesDir, "netease-stream-cache-dir")
        val provider = StandaloneDatabaseProvider(context)
        databaseProvider = provider
        cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(sizeMb.toLong() * 1024 * 1024), provider)
    }

    @Synchronized
    private fun closeCache() {
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

    @Synchronized
    fun setSizeLimitMb(context: Context, sizeMb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SIZE_MB, sizeMb.coerceIn(100, 8192)).apply()
        val app = appContext ?: return
        openCache(app, sizeMb)
    }

    @Synchronized
    fun clearCache() {
        val current = cache ?: return
        runCatching {
            current.keys.toList().forEach { key ->
                current.getCachedSpans(key).forEach { span -> runCatching { current.removeSpan(span) } }
            }
        }
    }

    private fun keyFor(songId: Long): String = "netease-$songId"

    private fun startServer() {
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
                    .setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/")),
            )
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(currentCache))

        val buffer = ByteArray(64 * 1024)
        var position = start
        var remaining: Long = if (end != null) end - start + 1 else C.LENGTH_UNSET.toLong()
        var total: Long = C.LENGTH_UNSET.toLong()
        var dataSource: DataSource? = null
        var attempts = 0
        var headersSent = false
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
                    output.write(buffer, 0, read)
                    position += read
                    if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    runCatching { dataSource?.close() }
                    dataSource = null
                    if (remaining == 0L) break
                    attempts += 1
                    if (attempts >= UpstreamMaxAttempts) throw error
                    System.err.println(
                        "NETSTREAM retry song=$songId attempt=$attempts pos=$position err=${error.message}",
                    )
                    Thread.sleep(UpstreamRetryDelayMs * attempts)
                }
            }
            output.flush()
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
