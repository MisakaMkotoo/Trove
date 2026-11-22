package com.exodidio.tune.sync

import android.content.Context
import com.exodidio.tune.R
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 电脑音乐库接入：用主机现有的远程只读接口（/remote/pair、music:list、
 * music:thumbnail）。远程曲目以 "remote:" 前缀写进同一个曲库，本地扫描仍然照常工作。
 */
object RemoteMusicLibrary {
    private const val PREFS_NAME = "remote_music_library"
    private const val KEY_BASE = "base"
    private const val KEY_TOKEN = "token"
    private const val KEY_HOST = "host"
    private const val KEY_FINGERPRINT = "tlsFingerprint"
    private const val KEY_ADDRESSES = "addresses"
    private const val ADDRESS_REFRESH_MS = 60_000L
    private const val REMOTE_PREFIX = "remote:"
    private const val CACHE_FILE = "remote-library-cache.json"
    private val cacheJson = Json { ignoreUnknownKeys = true }
    private val pinnedCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<SSLContext, X509TrustManager>>()

    @Serializable
    private data class CachedTrack(
        val id: String,
        val title: String,
        val artists: String,
        val album: String,
        val albumId: String,
        val artworkPath: String? = null,
        val audioPath: String? = null,
        val durationMs: Long = 0L,
        val createdAt: String = "",
        val metadataJson: String = "{}",
    )

    @Serializable
    private data class CacheFile(
        val tracks: List<CachedTrack> = emptyList(),
    )

    data class Config(
        val base: String,
        val token: String,
        val host: String,
        /** 电脑自签 HTTPS 证书的 SHA-256 指纹；HTTP 地址为空。 */
        val fingerprint: String = "",
        /** 主机配对时给的全部地址（局域网 / 公网 / 组网，HTTP + HTTPS）。 */
        val addresses: List<String> = listOf(base),
    )

    /** 电脑地址类型；仅用于 UI 文案与探测超时选择。 */
    enum class RemoteEndpointKind { Unknown, Lan, Public, Mesh }

    /** 设置页用的连接状态快照。 */
    data class RemoteStatus(
        val configured: Boolean = false,
        val online: Boolean = false,
        val busy: Boolean = false,
        val base: String = "",
        val host: String = "",
        val kind: RemoteEndpointKind = RemoteEndpointKind.Unknown,
        val secure: Boolean = false,
        val fingerprint: String = "",
        val addresses: List<String> = emptyList(),
        val lastSyncAt: Long = 0L,
        val lastProbeAt: Long = 0L,
        val tracks: Int = 0,
        val message: String = "",
    )

    private val _status = MutableStateFlow(RemoteStatus())
    val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private var lastAddressRefreshAt = 0L
    private var lastProbeAt = 0L
    private var lastSyncAt = 0L
    private var lastTrackCount = 0

    fun config(context: Context): Config? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = prefs.getString(KEY_BASE, "").orEmpty()
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        if (base.isBlank() || token.isBlank()) return null
        val addresses = prefs.getString(KEY_ADDRESSES, "").orEmpty()
            .split('\n')
            .mapNotNull { normalizeBase(it) }
            .filter { it.isNotBlank() }
        return Config(
            base = base,
            token = token,
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            fingerprint = prefs.getString(KEY_FINGERPRINT, "").orEmpty(),
            addresses = (listOf(base) + addresses).distinct(),
        )
    }

    fun fingerprint(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FINGERPRINT, "").orEmpty()

    fun isConfigured(context: Context): Boolean = config(context) != null

    private fun collectAddresses(root: JsonObject): List<String> {
        val urls = mutableListOf<String>()
        for (key in listOf("addressList", "httpsAddressList")) {
            root[key]?.jsonArray?.forEach { element ->
                runCatching { element.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let(urls::add)
            }
        }
        return urls
    }

    /** App 启动时调用：发布当前状态并挂上网络监听。 */
    fun initialize(context: Context) {
        publish(context)
        startNetworkWatch(context)
        // 资源库音频缓存独立维护：清掉过期半成品并按配额淘汰一次。
        runCatching { RemoteAudioCache.cleanupPartials(context) }
        runCatching { RemoteAudioCache.trim(context, force = true) }
    }

    fun startNetworkWatch(context: Context) {
        if (networkCallback != null) return
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = triggerAutoSync(app)
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
        // Wi-Fi 单独再听一路：蜂窝没关时默认网络可能不切，但连上 Wi-Fi 就该回局域网。
        if (wifiCallback == null) {
            val wifi = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = triggerAutoSync(app)
                override fun onLost(network: Network) = triggerAutoSync(app)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            runCatching { manager.registerNetworkCallback(request, wifi) }
                .onSuccess { wifiCallback = wifi }
        }
        // VPN 开关也重探一次：开着代理时主机地址和直连表现不一样。
        if (vpnCallback == null) {
            val vpn = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = triggerAutoSync(app)
                override fun onLost(network: Network) = triggerAutoSync(app)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            runCatching { manager.registerNetworkCallback(request, vpn) }
                .onSuccess { vpnCallback = vpn }
        }
    }

    private fun triggerAutoSync(context: Context) {
        if (!isConfigured(context)) return
        ioScope.launch {
            delay(800)
            syncWithRetry(context, attempts = 3)
        }
    }

    /** 自动同步：失败按 2s / 5s / 10s 退避，最多 3 次。 */
    suspend fun syncWithRetry(context: Context, attempts: Int = 3): Result<Int> {
        val delays = longArrayOf(2_000L, 5_000L, 10_000L)
        var last: Throwable? = null
        for (index in 0 until attempts.coerceAtLeast(1)) {
            val result = sync(context)
            if (result.isSuccess) return result
            last = result.exceptionOrNull()
            if (index < attempts - 1) delay(delays[minOf(index, delays.size - 1)])
        }
        return Result.failure(last ?: IllegalStateException(context.getString(R.string.integration_sync_failed_generic)))
    }

    private suspend fun probeBase(base: String, fingerprint: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$base/favicon.ico").openConnection() as HttpURLConnection
            try {
                applyPinnedTls(connection, fingerprint)
                val timeout = when (kindOf(base)) {
                    RemoteEndpointKind.Lan -> 2_000
                    RemoteEndpointKind.Mesh -> 4_000
                    else -> 5_000
                }
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * 当前地址不通时，按 局域网 > 公网 > 组网、同类 HTTPS 优先 并行探测备用地址，
     * 命中就写回 base；全都不通返回 null。
     */
    suspend fun ensureReachable(
        context: Context,
        initial: Config? = config(context),
    ): Config? {
        var config = initial ?: return null
        if (config.fingerprint.isBlank() && config.addresses.any { isSecure(it) }) {
            val httpsBase = config.addresses.firstOrNull { isSecure(it) }
            if (httpsBase != null) {
                val fetched = fetchFingerprint(httpsBase)
                if (fetched.isNotBlank()) {
                    config = config.copy(fingerprint = fetched)
                    persist(context, config)
                }
            }
        }
        lastProbeAt = System.currentTimeMillis()
        if (probeBase(config.base, config.fingerprint)) {
            publish(context, online = true, message = "已连接")
            refreshAddresses(context, config)
            return config
        }
        val candidates = (listOf(config.base) + config.addresses)
            .distinct()
            .filter { it != config.base }
            .sortedWith(compareBy({ kindRank(it) }, { if (isSecure(it)) 0 else 1 }))
        val hit = if (candidates.isEmpty()) {
            null
        } else {
            coroutineScope {
                candidates
                    .map { candidate -> async { candidate to probeBase(candidate, config.fingerprint) } }
                    .awaitAll()
                    .firstOrNull { (_, ok) -> ok }
                    ?.first
            }
        }
        if (hit == null) {
            publish(context, online = false, message = "电脑当前离线：局域网和公网地址都不通")
            return null
        }
        config = config.copy(
            base = hit,
            addresses = (listOf(hit) + config.addresses).distinct(),
        )
        persist(context, config)
        publish(context, online = true, message = "已切换到${kindOf(hit)}地址")
        refreshAddresses(context, config)
        return config
    }

    /** 手动切到某个已保存地址。 */
    fun useAddress(context: Context, address: String) {
        val config = config(context) ?: return
        if (address.isBlank() || address == config.base) return
        val next = config.copy(
            base = address,
            addresses = (listOf(address) + config.addresses).distinct(),
        )
        persist(context, next)
        publish(context, message = "已切换地址", online = false)
    }

    private suspend fun refreshAddresses(context: Context, config: Config) {
        val now = System.currentTimeMillis()
        if (now - lastAddressRefreshAt < ADDRESS_REFRESH_MS) return
        lastAddressRefreshAt = now
        val body = withContext(Dispatchers.IO) {
            runCatching {
                getJson("${config.base}/remote/info", config.token, 8_000, config.fingerprint)
            }.getOrDefault("")
        }
        if (body.isBlank()) return
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
        val fresh = (collectAddresses(root) + config.addresses)
            .mapNotNull { normalizeBase(it) }
            .filter { it.isNotBlank() }
            .distinct()
        val pinned = root["tlsFingerprint"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: config.fingerprint
        if (fresh == config.addresses && pinned == config.fingerprint) return
        persist(
            context,
            config.copy(
                addresses = (listOf(config.base) + fresh).distinct(),
                fingerprint = pinned,
            ),
        )
        publish(context)
    }

    /** 地址类型：局域网 / 公网 / 组网（Tailscale）。文案由 UI 层本地化。 */
    fun kindOf(address: String): RemoteEndpointKind {
        val host = runCatching { java.net.URI(address).host }.getOrNull()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?: return RemoteEndpointKind.Unknown
        if (host.contains(':')) {
            val lower = host.lowercase()
            val local = lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80")
            return if (local) RemoteEndpointKind.Lan else RemoteEndpointKind.Public
        }
        val tailscale = Regex("""^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.""")
        return if (tailscale.containsMatchIn(host)) RemoteEndpointKind.Mesh else RemoteEndpointKind.Lan
    }

    private fun kindRank(address: String): Int = when (kindOf(address)) {
        RemoteEndpointKind.Lan -> 0
        RemoteEndpointKind.Public -> 1
        RemoteEndpointKind.Mesh -> 2
        RemoteEndpointKind.Unknown -> 3
    }

    private fun isSecure(address: String): Boolean = address.startsWith("https://", true)

    private fun persist(context: Context, config: Config) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE, config.base)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_HOST, config.host)
            .putString(KEY_FINGERPRINT, config.fingerprint)
            .putString(KEY_ADDRESSES, config.addresses.joinToString("\n"))
            .apply()
    }

    private fun publish(context: Context, message: String? = null, online: Boolean? = null, busy: Boolean? = null) {
        val config = config(context)
        _status.value = RemoteStatus(
            configured = config != null,
            online = online ?: _status.value.online,
            busy = busy ?: _status.value.busy,
            base = config?.base.orEmpty(),
            host = config?.host.orEmpty(),
            kind = config?.base?.let(::kindOf) ?: RemoteEndpointKind.Unknown,
            secure = config?.base?.let(::isSecure) == true,
            fingerprint = config?.fingerprint.orEmpty(),
            addresses = config?.addresses.orEmpty(),
            lastSyncAt = lastSyncAt,
            lastProbeAt = lastProbeAt,
            tracks = lastTrackCount,
            message = message ?: _status.value.message,
        )
    }

    private fun normalizeBase(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            value = "http://$value"
        }
        // 裸 IPv6 要补方括号，URI 才能解析出主机。
        val schemeEnd = value.indexOf("://") + 3
        if (schemeEnd > 3) {
            val rest = value.substring(schemeEnd)
            if (!rest.startsWith("[") && rest.count { it == ':' } > 1) {
                val slash = rest.indexOf('/')
                val authority = if (slash >= 0) rest.substring(0, slash) else rest
                val tail = if (slash >= 0) rest.substring(slash) else ""
                value = value.substring(0, schemeEnd) + "[$authority]" + tail
            }
        }
        val trimmed = value.trimEnd('/')
        // 没写端口时按协议给默认值：HTTP 27185、HTTPS 27186。
        return runCatching {
            val uri = java.net.URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return ""
            val host = uri.host ?: return ""
            val defaultPort = if (scheme == "https") 27186 else 27185
            val port = if (uri.port > 0) uri.port else defaultPort
            "$scheme://$host:$port"
        }.getOrDefault(trimmed)
    }

    suspend fun pair(context: Context, baseInput: String, codeInput: String): Result<Config> =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(baseInput)
            val code = codeInput.filter(Char::isDigit)
            if (base.isBlank()) return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.integration_pair_missing_host)))
            if (code.length != 6) return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.integration_pair_missing_code)))
            runCatching {
                val fingerprint = if (base.startsWith("https://", true)) {
                    fetchFingerprint(base).ifBlank { error(context.getString(R.string.integration_pair_fingerprint_failed)) }
                } else {
                    ""
                }
                val response = postJson(
                    "$base/remote/pair",
                    """{"code":"$code"}""",
                    token = null,
                    timeoutMs = 20_000,
                    fingerprint = fingerprint,
                )
                val root = Json.parseToJsonElement(response).jsonObject
                val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
                val token = root["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val host = root["host"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val pinned = root["tlsFingerprint"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: fingerprint
                if (!ok || token.isBlank()) {
                    error(root["error"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.integration_pair_failed))
                }
                // 配对响应直接带 HTTP/HTTPS 两套地址，存下来换网时自动切。
                val addresses = (listOf(base) + collectAddresses(root))
                    .mapNotNull { normalizeBase(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                val config = Config(base, token, host, pinned, addresses)
                persist(context, config)
                lastProbeAt = System.currentTimeMillis()
                publish(context, message = "已连接", online = true)
                config
            }
        }

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        ioScope.launch {
            runCatching { AndroidSyncRuntime.syncStore.setRemoteTracks(emptyList()) }
        }
        _status.value = RemoteStatus()
    }

    /** 上次同步的结果先垫进曲库，打开 App 立刻能看到电脑歌曲，后台再刷新。 */
    fun restoreCached(context: Context) {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.isFile) return
        val cached = runCatching {
            cacheJson.decodeFromString<CacheFile>(file.readText())
        }.getOrNull() ?: return
        if (cached.tracks.isEmpty()) return
        val tracks = cached.tracks.map { track ->
            LibraryTrack(
                id = track.id,
                title = track.title,
                artists = track.artists,
                album = track.album,
                albumId = track.albumId,
                metadataJson = track.metadataJson.takeIf { it != "{}" }
                    ?: "{\"duration\":" + (track.durationMs / 1000) + "}",
                artworkPath = track.artworkPath,
                audioPath = track.audioPath,
                createdAt = track.createdAt,
                updatedAt = track.createdAt,
                sortTitle = track.title,
                sortArtists = track.artists,
            )
        }
        kotlinx.coroutines.runBlocking {
            AndroidSyncRuntime.syncStore.setRemoteTracks(tracks)
        }
    }

    private fun saveCache(context: Context, tracks: List<LibraryTrack>) {
        val payload = CacheFile(
            tracks = tracks.map { track ->
                val durationMs = runCatching {
                    ((track.metadataObject()?.get("duration")?.toString()?.trim('"')?.toDouble()) ?: 0.0) * 1000
                }.getOrDefault(0.0).toLong()
                CachedTrack(
                    id = track.id,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    albumId = track.albumId,
                    artworkPath = track.artworkPath,
                    audioPath = track.audioPath,
                    durationMs = durationMs,
                    createdAt = track.createdAt,
                    metadataJson = track.metadataJson,
                )
            },
        )
        runCatching {
            File(context.filesDir, CACHE_FILE).writeTextAtomically(cacheJson.encodeToString(payload))
        }
    }

    /** 拉取电脑曲库并写进本地曲库；封面顺手下载到私有目录。 */
    suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        val initial = config(context) ?: return@withContext Result.failure(IllegalStateException(context.getString(R.string.integration_not_configured)))
        val config = ensureReachable(context, initial)
            ?: return@withContext Result.failure(IllegalStateException(context.getString(R.string.integration_all_addresses_unreachable)))
        publish(context, online = true, busy = true, message = "正在同步…")
        val result = runCatching {
            val response = postJsonRetry(
                "${config.base}/rpc",
                """{"channel":"music:list","args":[]}""",
                token = config.token,
                timeoutMs = 60_000,
                fingerprint = config.fingerprint,
            )
            val root = Json.parseToJsonElement(response).jsonObject
            val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
            if (!ok) error(root["error"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.integration_library_read_failed))
            val tracks = root["result"]?.jsonObject?.get("tracks")?.jsonArray
                ?: return@runCatching 0
            val artworkDir = File(context.filesDir, "remote-artwork").apply { mkdirs() }
            val remoteTracks = tracks.mapNotNull { element ->
                runCatching { element.jsonObject }.getOrNull()?.let { obj ->
                    val id = obj.string("id") ?: return@let null
                    val name = obj.string("name").orEmpty()
                    val title = obj.string("title").orEmpty()
                    val artist = obj.string("artist").orEmpty()
                    val album = obj.string("album").orEmpty()
                    val durationMs = obj.long("duration") ?: 0L
                    val url = obj.string("url").orEmpty()
                    if (url.isBlank()) return@let null
                    // 主机没有标签时 name 会带扩展名，显示和识别都要去掉。
                    val displayTitle = title.ifBlank { name.substringBeforeLast('.', name) }
                    val extension = name.substringAfterLast('.', "").lowercase()
                    val lossless = extension in setOf("flac", "alac", "wav", "aiff", "ape", "wv")
                    val artistNames = splitNames(artist)
                    val albumTitle = album.ifBlank { context.getString(R.string.common_unknown_album) }
                    val albumId = albumIdFor(albumTitle)
                    val genreNames = splitNames(obj.string("genre").orEmpty())
                    val composerNames = splitNames(obj.string("composer").orEmpty())
                    val year = obj.long("year") ?: 0L
                    // 元数据带上 artist/album/genre/composer 的 id，艺术家、专辑等页面才能对上。
                    val metadataJson = buildJsonObject {
                        put("title", displayTitle)
                        // 时长未知就不要写 0：0 会被当成“极短音频”而误隐藏。
                        if (durationMs > 0) put("duration", durationMs / 1000)
                        put("format", extension)
                        put("codec", extension)
                        put("lossless", lossless)
                        put(
                            "artists",
                            JsonArray(
                                artistNames.map { artistName ->
                                    buildJsonObject {
                                        put("id", artistIdFor(artistName))
                                        put("name", artistName)
                                    }
                                },
                            ),
                        )
                        put(
                            "album",
                            buildJsonObject {
                                put("id", albumId)
                                put("title", albumTitle)
                                if (year > 0) put("year", year.toInt())
                            },
                        )
                        if (genreNames.isNotEmpty()) {
                            put(
                                "genres",
                                JsonArray(
                                    genreNames.map { genreName ->
                                        buildJsonObject {
                                            put("id", genreIdFor(genreName))
                                            put("name", genreName)
                                        }
                                    },
                                ),
                            )
                        }
                        if (composerNames.isNotEmpty()) {
                            put(
                                "composers",
                                JsonArray(
                                    composerNames.map { composerName ->
                                        buildJsonObject {
                                            put("id", composerIdFor(composerName))
                                            put("name", composerName)
                                        }
                                    },
                                ),
                            )
                        }
                    }.toString()
                    val artworkPath = downloadArtwork(
                        config = config,
                        trackId = id,
                        dir = artworkDir,
                    )
                    LibraryTrack(
                        id = REMOTE_PREFIX + id,
                        title = displayTitle,
                        artists = artist.ifBlank { context.getString(R.string.common_unknown_artist) },
                        album = albumTitle,
                        albumId = albumId,
                        artworkKey = null,
                        createdAt = obj.string("createdAt").orEmpty(),
                        updatedAt = obj.string("createdAt").orEmpty(),
                        metadataJson = metadataJson,
                        artworkPath = artworkPath,
                        audioPath = absoluteMediaUrl(config.base, url),
                        sortTitle = displayTitle,
                        sortArtists = artist,
                    )
                }
            }
            AndroidSyncRuntime.syncStore.setRemoteTracks(remoteTracks)
            saveCache(context, remoteTracks)
            remoteTracks.size
        }
        result
            .onSuccess { count ->
                lastSyncAt = System.currentTimeMillis()
                lastTrackCount = count
                publish(context, online = true, busy = false, message = "已同步 $count 首电脑曲目")
            }
            .onFailure { error ->
                publish(context, busy = false, message = "同步失败：${error.message}")
            }
        result
    }

    private fun downloadArtwork(config: Config, trackId: String, dir: File): String? {
        // 本地已有封面就别再问主机，否则每次启动都要把整库封面 RPC 一遍。
        val file = File(dir, "cover-${hash(trackId)}.img")
        if (file.isFile && file.length() > 0) return file.absolutePath
        return runCatching {
            val response = postJson(
                "${config.base}/rpc",
                """{"channel":"music:thumbnail","args":["$trackId"]}""",
                token = config.token,
                timeoutMs = 30_000,
                fingerprint = config.fingerprint,
            )
            val root = Json.parseToJsonElement(response).jsonObject
            val thumbnail = root["result"]?.jsonObject?.get("thumbnail")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val url = absoluteMediaUrl(config.base, thumbnail)
            downloadFile(url, file, config.fingerprint)
            file.absolutePath
        }.getOrNull()
    }

    private fun downloadFile(url: String, target: File, fingerprint: String): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            applyPinnedTls(connection, fingerprint)
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return false
            val temp = File(target.parentFile, target.name + ".part")
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0) {
                temp.delete()
                return false
            }
            temp.renameTo(target)
            true
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun postJson(
        url: String,
        body: String,
        token: String?,
        timeoutMs: Int,
        fingerprint: String = "",
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            applyPinnedTls(connection, fingerprint)
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val text = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun postJsonRetry(
        url: String,
        body: String,
        token: String?,
        timeoutMs: Int,
        fingerprint: String = "",
        attempts: Int = 2,
    ): String {
        var last: Throwable? = null
        for (index in 0 until attempts.coerceAtLeast(1)) {
            try {
                return postJson(url, body, token, timeoutMs, fingerprint)
            } catch (error: Throwable) {
                last = error
                if (index < attempts - 1) delay(1_000L)
            }
        }
        throw last ?: IllegalStateException("请求失败")
    }

    private fun getJson(url: String, token: String, timeoutMs: Int, fingerprint: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            applyPinnedTls(connection, fingerprint)
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            if (token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            return if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 首次连接：用不校验证书的方式读公开的 /remote/tls，拿到指纹后再固定。 */
    private fun fetchFingerprint(base: String): String = runCatching {
        val connection = URL("${base.trimEnd('/')}/remote/tls").openConnection() as HttpsURLConnection
        try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), SecureRandom())
            }
            connection.sslSocketFactory = context.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            connection.connectTimeout = 10_000
            connection.readTimeout = 5_000
            if (connection.responseCode !in 200..299) return ""
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonObject["fingerprint"]
                ?.jsonPrimitive?.contentOrNull.orEmpty()
        } finally {
            connection.disconnect()
        }
    }.getOrDefault("")

    /** HTTPS 连接按固定指纹校验；HTTP 不处理。 */
    internal fun applyPinnedTls(connection: HttpURLConnection, fingerprint: String) {
        if (connection !is HttpsURLConnection || fingerprint.isBlank()) return
        val (context, manager) = pinnedTls(fingerprint)
        connection.sslSocketFactory = context.socketFactory
        val fallback = HttpsURLConnection.getDefaultHostnameVerifier()
        connection.hostnameVerifier = HostnameVerifier { host, session ->
            runCatching {
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                leaf != null && fingerprintOf(leaf).equals(fingerprint, ignoreCase = true)
            }.getOrDefault(false) || fallback.verify(host, session)
        }
    }

    private fun fingerprintOf(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

    internal fun pinnedTls(fingerprint: String): Pair<SSLContext, X509TrustManager> {
        pinnedCache[fingerprint]?.let { return it }
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>()
            .first()
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("不接受客户端证书")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certs = chain ?: emptyArray()
                val leaf = certs.firstOrNull() ?: throw CertificateException("证书链为空")
                if (fingerprintOf(leaf).equals(fingerprint, ignoreCase = true)) return
                system.checkServerTrusted(certs, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(manager), SecureRandom())
        }
        return (context to manager).also { pinnedCache[fingerprint] = it }
    }

    /** 主机的多歌手/流派字段是拼接字符串，按常见分隔符拆开。 */
    private fun splitNames(value: String): List<String> =
        value.split(Regex("\\s*/\\s*|;|；|、")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun absoluteMediaUrl(base: String, raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return base.trimEnd('/') + if (value.startsWith("/")) value else "/$value"
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.longOrNull
}

/**
 * 远程音频下载到本地缓存后交给 FFmpeg 解码（原生解码只吃本地 fd）。
 * 按 URL 加锁而不是全局锁：连续切歌时各首互不排队；[isStale] 变 true 立刻放弃，
 * 避免用户快速点歌时一直在等上一首下完。
 */
internal fun ensureRemoteAudioFile(
    context: Context,
    url: String,
    isStale: () -> Boolean = { false },
): String? {
    RemoteAudioCache.cached(context, url)?.let { return it.absolutePath }
    if (isStale()) return null
    val dir = RemoteAudioCache.dir(context)
    val target = RemoteAudioCache.target(context, url)
    val key = target.name
    val lock = downloadLocks.computeIfAbsent(key) { Any() }
    try {
        synchronized(lock) {
            RemoteAudioCache.cached(context, url)?.let { return it.absolutePath }
            if (isStale()) return null
            val saved = downloadAudio(url, dir, target, isStale, RemoteMusicLibrary.fingerprint(context))
            if (saved != null) RemoteAudioCache.trim(context)
            return saved
        }
    } finally {
        downloadLocks.remove(key, lock)
    }
}

/** 这首歌是否已经在本地缓存里（不触发下载）。 */
internal fun remoteAudioCached(context: Context, url: String): Boolean =
    RemoteAudioCache.cached(context, url) != null

private val downloadLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

/**
 * 正在传输音频的连接。切歌时直接 disconnect，让阻塞中的 read 立刻抛错退出——
 * 只靠 isStale 标记的话，要等一次网络读取返回（最长 readTimeout）才能停下，
 * 用户连点几首就会全部排在后面等下载。
 */
private val activeAudioConnections: MutableSet<HttpURLConnection> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

internal fun cancelRemoteAudioDownloads() {
    activeAudioConnections.forEach { connection -> runCatching { connection.disconnect() } }
    activeAudioConnections.clear()
}

/** 整首下载：失败退避重试 3 次，已下载的部分用 Range 续传。 */
private fun downloadAudio(
    url: String,
    dir: File,
    target: File,
    isStale: () -> Boolean,
    fingerprint: String,
): String? {
    val temp = File(dir, target.name + ".part")
    val delays = longArrayOf(1_000L, 3_000L)
    for (attempt in 0 until 3) {
        if (isStale()) return null
        val existing = if (temp.isFile) temp.length() else 0L
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                RemoteMusicLibrary.applyPinnedTls(connection, fingerprint)
                if (existing > 0) {
                    connection.setRequestProperty("Range", "bytes=$existing-")
                }
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                activeAudioConnections.add(connection)
                val code = connection.responseCode
                if (code == 416) {
                    temp.delete()
                    continue
                }
                if (code !in 200..299) {
                    if (attempt >= 2) return null
                    Thread.sleep(delays[minOf(attempt, delays.size - 1)])
                    continue
                }
                val append = existing > 0 && code == 206
                if (!append) temp.delete()
                var aborted = false
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(temp, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStale()) {
                                aborted = true
                                break
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                // 用户切歌中断：保留 .part，下次点回来还能续传。
                if (aborted) return null
                if (temp.length() <= 0) {
                    temp.delete()
                    return null
                }
                if (temp.renameTo(target)) return target.absolutePath
                temp.delete()
            } finally {
                activeAudioConnections.remove(connection)
                connection.disconnect()
            }
        } catch (error: Throwable) {
            // 被切歌断连导致的异常：不要再退避重试，直接让调用方走取消分支。
            if (isStale()) return null
            if (attempt >= 2) return null
            Thread.sleep(delays[minOf(attempt, delays.size - 1)])
        }
    }
    return null
}
