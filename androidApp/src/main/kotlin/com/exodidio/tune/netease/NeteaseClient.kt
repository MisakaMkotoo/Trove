package com.exodidio.tune.netease

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class NeteaseEntryPayload(
    val songId: Long,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
    val albumId: Long = 0L,
    val artistIds: List<Long> = emptyList(),
)

internal data class NeteasePlaylistPayload(
    val id: String,
    val name: String,
    val coverUrl: String,
    val entries: List<NeteaseEntryPayload>,
    val description: String = "",
)

internal data class NeteaseQrStatus(
    val code: Int,
    val message: String,
    val cookie: String? = null,
    /** 风控 -462 时下发的验证页地址；可在 WebView 里完成人机验证。 */
    val verifyUrl: String? = null,
)

internal data class NeteaseLoginQr(
    val key: String,
    val url: String,
    val cookie: String? = null,
)

/**
 * 网易云原生 /api 接口的最小客户端（参考 NeteaseCloudMusicApi 的调用方式）：
 * POST 表单 + 浏览器 UA/Referer，可选携带登录 Cookie。
 * 所有网络与磁盘操作都在 IO 线程执行，并设置连接/读取超时。
 */
internal object NeteaseClient {
    private const val BASE = "https://music.163.com"
    /** 新版桌面登录接口：旧 music.163.com 的扫码接口已被风控（8821）。 */
    private const val QR_BASE = "https://interface.music.163.com"
    internal const val PrivateRadarPlaylistId = "3136952023"
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true }

    fun playlistIdFrom(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val patterns = listOf(
            Regex("""playlist[/?].*?id=(\d+)"""),
            Regex("""[?&]id=(\d+)"""),
            Regex("""^(\d{5,})$"""),
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
    }

    suspend fun fetchPlaylist(playlistId: String, cookie: String?): Result<NeteasePlaylistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = request(
                    "/api/v6/playlist/detail",
                    mapOf("id" to playlistId, "n" to "100000", "s" to "8"),
                    cookie,
                )
                val playlist = root["playlist"]?.jsonObject ?: error("歌单不存在或无法访问")
                val id = playlist.string("id").ifBlank { playlistId }
                val name = playlist.string("name").ifBlank { "网易云歌单" }
                val cover = playlist.string("coverImgUrl")
                val tracks = playlist["tracks"]?.jsonArray.orEmpty()
                val trackIds = playlist["trackIds"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() }
                val parsed = tracks.mapNotNull { parseTrack(it.jsonObjectOrNull() ?: return@mapNotNull null, cover) }
                val entries = if (trackIds.isNotEmpty() && trackIds.size > parsed.size) {
                    // 大歌单 detail 只回前若干首，用歌曲详情接口补齐剩余部分。
                    val have = parsed.mapTo(mutableSetOf()) { it.songId }
                    val missing = trackIds.filterNot { it in have }
                    parsed + fetchTrackDetails(missing, cover, cookie)
                } else {
                    parsed
                }
                NeteasePlaylistPayload(id, name, cover, entries, playlist.string("description"))
            }
        }

    private fun fetchTrackDetails(ids: List<Long>, fallbackCover: String, cookie: String?): List<NeteaseEntryPayload> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(500).flatMap { chunk ->
            runCatching {
                val body = chunk.joinToString(",", prefix = "[", postfix = "]") { """{"id":$it}""" }
                val root = request("/api/v3/song/detail", mapOf("c" to body), cookie)
                root["songs"]?.jsonArray.orEmpty().mapNotNull { element ->
                    parseTrack(element.jsonObjectOrNull() ?: return@mapNotNull null, fallbackCover)
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun parseTrack(track: JsonObject, fallbackCover: String): NeteaseEntryPayload? {
        val songId = track["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val title = track["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        val artistArray = track["ar"]?.jsonArray ?: track["artists"]?.jsonArray
        val artists = artistArray
            ?.mapNotNull { artist -> artist.jsonObjectOrNull()?.string("name")?.takeIf { it.isNotBlank() } }
            ?.joinToString(" / ")
            .orEmpty()
        val artistIds = artistArray.orEmpty().mapNotNull { artist ->
            artist.jsonObjectOrNull()?.stringOrNull("id")?.toLongOrNull()
        }
        val albumObj = track["al"]?.jsonObjectOrNull() ?: track["album"]?.jsonObjectOrNull()
        val album = albumObj?.string("name").orEmpty()
        val albumId = albumObj?.stringOrNull("id")?.toLongOrNull() ?: 0L
        val cover = albumObj?.string("picUrl").orEmpty()
            .ifBlank { albumObj?.string("blurPicUrl").orEmpty() }
            .ifBlank { fallbackCover }
        val durationMs = track["dt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: track["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: 0L
        return NeteaseEntryPayload(songId, title, artists, album, durationMs, cover, albumId, artistIds)
    }

    data class SongUrl(val url: String, val type: String)

    suspend fun fetchSongUrl(songId: Long, cookie: String?): Result<SongUrl> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 依次尝试高音质与标准音质；取不到代表需要会员或无版权。
                // 点播放等不起长超时：断网时尽快失败，让界面马上退回暂停态。
                for (level in listOf("exhigh", "standard")) {
                    val root = request(
                        "/api/song/enhance/player/url/v1",
                        mapOf("ids" to "[$songId]", "level" to level, "encodeType" to "flac"),
                        cookie,
                        connectTimeoutMs = 4_000,
                        readTimeoutMs = 6_000,
                        retry = false,
                    )
                    val data = root["data"]?.jsonArray?.firstOrNull()?.jsonObjectOrNull()
                    if (data == null) {
                        System.err.println("NETURL song=$songId level=$level code=${root.string("code")} no-data")
                        continue
                    }
                    val url = data.string("url")
                    System.err.println(
                        "NETURL song=$songId level=$level code=${root.string("code")} " +
                            "url=${url.isNotBlank()} fee=${data.string("fee")}",
                    )
                    if (url.isNotBlank()) {
                        return@runCatching SongUrl(url, data.string("type").lowercase().ifBlank { "mp3" })
                    }
                }
                error("这首歌需要会员或已无版权，无法获取播放地址")
            }
        }

    // ---- weapi（推荐类接口需要加密） ----
    private const val WEApiKey = "0CoJUm6Qyw8W8jud"
    private const val WEApiIv = "0102030405060708"
    private const val RSAModulusHex =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val Base62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private fun requestWeapi(path: String, data: Map<String, String>, cookie: String?): JsonObject {
        val csrf = cookie?.let { Regex("__csrf=([^;]+)").find(it)?.groupValues?.getOrNull(1) }
            ?: (0 until 32).joinToString("") { Base62.random().toString() }
        val payload = buildJsonObject {
            put("csrf_token", csrf)
            data.forEach { (key, value) -> put(key, value) }
        }.toString()
        val secret = (0 until 16).map { Base62.random() }.joinToString("")
        val params = aesCbcBase64(aesCbcBase64(payload, WEApiKey), secret)
        val encSecKey = rsaEncrypt(secret.reversed())
        val fullCookie = buildString {
            if (!cookie.isNullOrBlank()) append(cookie)
            if (!fullCookieContains(cookie, "__csrf")) {
                if (isNotEmpty()) append("; ")
                append("__csrf=").append(csrf)
            }
        }
        return postForm(BASE + "/weapi/" + path.removePrefix("/api/"), mapOf("params" to params, "encSecKey" to encSecKey), fullCookie)
    }

    private fun fullCookieContains(cookie: String?, key: String): Boolean =
        cookie?.contains("$key=") == true

    private fun aesCbcBase64(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(WEApiIv.toByteArray(Charsets.UTF_8)),
        )
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private fun rsaEncrypt(text: String): String {
        val modulus = java.math.BigInteger(1, hexBytes(RSAModulusHex))
        val publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.RSAPublicKeySpec(modulus, java.math.BigInteger.valueOf(65537)),
        )
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val size = (modulus.bitLength() + 7) / 8
        val input = text.toByteArray(Charsets.UTF_8)
        val block = ByteArray(size)
        input.copyInto(block, size - input.size)
        return cipher.doFinal(block).joinToString("") { "%02x".format(it) }
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun postForm(url: String, form: Map<String, String>, cookie: String?): JsonObject {
        val attempt = {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                applyHeaders(connection, cookie)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = form.entries.joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val buffer = ByteArrayOutputStream()
                connection.inputStream.use { it.copyTo(buffer) }
                json.parseToJsonElement(buffer.toString(Charsets.UTF_8.name())) as? JsonObject
                    ?: error("响应格式异常")
            } finally {
                connection.disconnect()
            }
        }
        return runCatching(attempt).getOrElse { first -> runCatching(attempt).getOrElse { throw first } }
    }

    /** 每日推荐歌曲。 */
    suspend fun fetchDailyRecommend(cookie: String?): Result<NeteasePlaylistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requestWeapi("/api/v3/discovery/recommend/songs", emptyMap(), cookie)
                val songs = root["data"]?.jsonObjectOrNull()?.get("dailySongs")?.jsonArray.orEmpty()
                val entries = songs.mapNotNull { element ->
                    parseTrack(element.jsonObjectOrNull() ?: return@mapNotNull null, "")
                }
                if (entries.isEmpty()) error("没有拿到每日推荐（需要登录）")
                NeteasePlaylistPayload("daily", "每日推荐", entries.firstOrNull()?.coverUrl.orEmpty(), entries)
            }
        }

    /** 私人雷达：固定歌单 id，服务端按登录账号每天轮换内容（不做标题/块兜底）。 */
    suspend fun fetchRadar(cookie: String?): Result<NeteasePlaylistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) error("私人雷达需要登录网易云")
                val playlist = fetchPlaylist(PrivateRadarPlaylistId, cookie).getOrThrow()
                System.err.println("NETRADAR fixed id entries=${playlist.entries.size}")
                if (playlist.entries.isEmpty()) error("私人雷达返回为空")
                playlist.copy(name = "私人雷达")
            }
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    data class SearchSong(
        val songId: Long,
        val title: String,
        val artists: String,
        val durationMs: Long,
        val album: String = "",
        val coverUrl: String = "",
        val albumId: Long = 0L,
        val artistIds: List<Long> = emptyList(),
    )

    data class SearchPlaylist(
        val id: Long,
        val name: String,
        val creator: String,
        val trackCount: Int,
        val coverUrl: String,
    )

    data class SearchArtist(
        val id: Long,
        val name: String,
        val coverUrl: String,
    )

    data class SearchAlbum(
        val id: Long,
        val name: String,
        val artist: String,
        val coverUrl: String,
    )

    /** 搜索歌单（type=1000）。 */
    suspend fun searchPlaylists(keyword: String, limit: Int = 8, cookie: String?): Result<List<SearchPlaylist>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (keyword.isBlank()) return@runCatching emptyList()
                val root = request("/api/cloudsearch/pc", cloudSearchForm(keyword, 1000, limit), cookie)
                root["result"]?.jsonObjectOrNull()?.get("playlists")?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
                    val name = obj.string("name")
                    if (name.isBlank()) return@mapNotNull null
                    SearchPlaylist(
                        id = id,
                        name = name,
                        creator = obj["creator"]?.jsonObjectOrNull()?.string("nickname").orEmpty(),
                        trackCount = obj["trackCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        coverUrl = obj.string("coverImgUrl"),
                    )
                }
            }
        }

    /** 搜索歌手（type=100）。 */
    suspend fun searchArtists(keyword: String, limit: Int = 8, cookie: String?): Result<List<SearchArtist>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (keyword.isBlank()) return@runCatching emptyList()
                val root = request("/api/cloudsearch/pc", cloudSearchForm(keyword, 100, limit), cookie)
                root["result"]?.jsonObjectOrNull()?.get("artists")?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
                    val name = obj.string("name")
                    if (name.isBlank()) return@mapNotNull null
                    SearchArtist(id = id, name = name, coverUrl = obj.string("picUrl"))
                }
            }
        }

    /** 搜索专辑（type=10）。 */
    suspend fun searchAlbums(keyword: String, limit: Int = 8, cookie: String?): Result<List<SearchAlbum>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (keyword.isBlank()) return@runCatching emptyList()
                val root = request("/api/cloudsearch/pc", cloudSearchForm(keyword, 10, limit), cookie)
                root["result"]?.jsonObjectOrNull()?.get("albums")?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
                    val name = obj.string("name")
                    if (name.isBlank()) return@mapNotNull null
                    SearchAlbum(
                        id = id,
                        name = name,
                        artist = obj["artist"]?.jsonObjectOrNull()?.string("name").orEmpty(),
                        coverUrl = obj.string("picUrl"),
                    )
                }
            }
        }

    /** 相似歌曲：以歌曲 ID 为基准，weapi。 */
    suspend fun fetchSimilarSongs(
        songId: Long,
        limit: Int = 30,
        offset: Int = 0,
        cookie: String?,
    ): Result<List<NeteaseEntryPayload>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = requestWeapi(
                "/api/v1/discovery/simiSong",
                mapOf("songid" to "$songId", "limit" to "$limit", "offset" to "$offset"),
                cookie,
            )
            val songs = root["songs"]?.jsonArray.orEmpty().mapNotNull { element ->
                element.jsonObjectOrNull()?.let { parseTrack(it, "") }
            }.distinctBy { it.songId }
            System.err.println("NETSIMI song=$songId entries=${songs.size} code=${root.string("code")}")
            if (songs.isEmpty()) error("相似歌曲返回为空: ${root.toString().take(200)}")
            songs
        }
    }

    /** 私人漫游（私人 FM）：默认模式，参数与参考实现一致（weapi、空 body）。 */
    suspend fun fetchRoamSongs(cookie: String?): Result<List<NeteaseEntryPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requestWeapi("/api/v1/radio/get", emptyMap(), cookie)
                val candidates = when (val data = root["data"]) {
                    is JsonArray -> data
                    is JsonObject -> data["data"] as? JsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                val songs = candidates.mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val song = obj["songInfo"]?.jsonObjectOrNull()
                        ?: obj["song"]?.jsonObjectOrNull()
                        ?: obj
                    parseTrack(song, "")
                }.distinctBy { it.songId }
                System.err.println("NETROAM entries=${songs.size} code=${root.string("code")}")
                if (songs.isEmpty()) error("漫游返回为空: ${root.toString().take(200)}")
                songs
            }
        }

    /** 歌单摘要：只取名称/封面/数量，不解析歌曲，用于首页雷达卡片。 */
    suspend fun fetchPlaylistSummary(playlistId: String, cookie: String?): Result<PlaylistSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = request(
                    "/api/v6/playlist/detail",
                    mapOf("id" to playlistId, "n" to "0", "s" to "8"),
                    cookie,
                )
                val playlist = root["playlist"]?.jsonObject ?: error("歌单不存在或无法访问")
                PlaylistSummary(
                    id = playlist.string("id").ifBlank { playlistId },
                    name = playlist.string("name").ifBlank { "网易云歌单" },
                    coverUrl = playlist.string("coverImgUrl"),
                    trackCount = playlist.string("trackCount").toIntOrNull() ?: 0,
                    description = playlist.string("description"),
                )
            }
        }

    data class PlaylistSummary(
        val id: String,
        val name: String,
        val coverUrl: String,
        val trackCount: Int,
        val description: String = "",
    )

    private fun cloudSearchForm(keyword: String, type: Int, limit: Int) =
        mapOf("s" to keyword, "type" to "$type", "limit" to "$limit", "offset" to "0")

    /** 按关键词在网易云搜索单曲。 */
    suspend fun searchSongs(keyword: String, limit: Int = 30, cookie: String?): Result<List<SearchSong>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (keyword.isBlank()) return@runCatching emptyList()
                val form = mapOf("s" to keyword, "type" to "1", "limit" to "$limit", "offset" to "0")
                // cloudsearch 才有 al.picUrl（封面）；老 /api/search/get 的 album 只有 picId，拿不到封面地址。
                val root = runCatching { request("/api/cloudsearch/pc", form, cookie) }
                    .getOrElse { request("/api/search/get", form, cookie) }
                val songs = root["result"]?.jsonObjectOrNull()?.get("songs")?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
                    val name = obj.string("name")
                    if (name.isBlank()) return@mapNotNull null
                    val artists = obj["ar"]?.jsonArray ?: obj["artists"]?.jsonArray
                    val artistNames = artists?.mapNotNull { entry ->
                        entry.jsonObjectOrNull()?.string("name")?.takeIf { it.isNotBlank() }
                    }?.joinToString(" / ").orEmpty()
                    val duration = obj["dt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: 0L
                    val albumObj = obj["al"]?.jsonObjectOrNull() ?: obj["album"]?.jsonObjectOrNull()
                    SearchSong(
                        songId = id,
                        title = name,
                        artists = artistNames,
                        durationMs = duration,
                        album = albumObj?.string("name").orEmpty(),
                        coverUrl = albumObj?.string("picUrl").orEmpty(),
                        albumId = albumObj?.stringOrNull("id")?.toLongOrNull() ?: 0L,
                        artistIds = artists.orEmpty().mapNotNull { entry ->
                            entry.jsonObjectOrNull()?.stringOrNull("id")?.toLongOrNull()
                        },
                    )
                }
                System.err.println(
                    "NETSEARCH q=$keyword results=${songs.size} covers=${songs.count { it.coverUrl.isNotBlank() }}",
                )
                songs
            }.onFailure { System.err.println("NETSEARCH failed q=$keyword err=${it.message}") }
        }

    /** 按歌名/歌手在网易云搜索单曲。 */
    suspend fun searchSong(title: String, artist: String, cookie: String?): Result<List<SearchSong>> {
        val keyword = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").ifBlank { title }
        return searchSongs(keyword, limit = 10, cookie = cookie)
    }

    data class LikedPlaylistSummary(
        val id: String,
        val name: String,
        val coverUrl: String,
        val trackCount: Int,
        val description: String = "",
    )

    /** 账号页展示用的登录信息（手机/邮箱由服务端打码后返回）。 */
    @Serializable
    internal data class AccountInfo(
        val nickname: String,
        val uid: String,
        val loginName: String,
        val createdAtMillis: Long,
        val avatarUrl: String = "",
        val signature: String = "",
        val gender: Int = 0,
    ) {
        val isEmailLogin: Boolean get() = loginName.contains("@")
    }

    suspend fun fetchAccountInfo(cookie: String?): Result<AccountInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) error("需要登录网易云")
                val root = requestWeapi("/api/nuser/account/get", emptyMap(), cookie)
                val account = root["account"]?.jsonObjectOrNull() ?: error("无法获取网易云账号")
                val profile = root["profile"]?.jsonObjectOrNull()
                val uid = account.string("id").ifBlank { profile?.string("userId").orEmpty() }
                if (uid.isBlank()) error("无法获取网易云账号")
                AccountInfo(
                    nickname = profile?.string("nickname").orEmpty().ifBlank { "网易云用户" },
                    uid = uid,
                    loginName = account.string("userName"),
                    createdAtMillis = account.stringOrNull("createTime")?.toLongOrNull() ?: 0L,
                    avatarUrl = profile?.string("avatarUrl").orEmpty(),
                    signature = profile?.string("signature").orEmpty(),
                    gender = profile?.stringOrNull("gender")?.toIntOrNull() ?: 0,
                ).also { info ->
                    System.err.println(
                        "NETACCOUNT uid=${info.uid} name=${info.nickname} " +
                            "login=${info.loginName} created=${info.createdAtMillis}",
                    )
                }
            }
        }

    internal data class UserPlaylistInfo(
        val id: String,
        val name: String,
        val coverUrl: String,
        val trackCount: Int,
        val subscribed: Boolean,
        val specialType: Int,
        val creatorId: String,
        val updateTimeMillis: Long = 0L,
    )

    /** 登录用户的全部歌单（自建 + 收藏），用 creatorId 区分。 */
    suspend fun fetchUserPlaylists(uid: String, cookie: String?): Result<List<UserPlaylistInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) error("需要登录网易云")
                val root = requestWeapi(
                    "/api/user/playlist",
                    mapOf("uid" to uid, "offset" to "0", "limit" to "1000"),
                    cookie,
                )
                val list = root["playlist"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj.string("id").ifBlank { return@mapNotNull null }
                    val name = obj.string("name").ifBlank { return@mapNotNull null }
                    UserPlaylistInfo(
                        id = id,
                        name = name,
                        coverUrl = obj.string("coverImgUrl"),
                        trackCount = obj.string("trackCount").toIntOrNull() ?: 0,
                        subscribed = obj.string("subscribed").equals("true", ignoreCase = true),
                        specialType = obj.string("specialType").toIntOrNull() ?: 0,
                        creatorId = obj["creator"]?.jsonObjectOrNull()?.string("userId").orEmpty(),
                        updateTimeMillis = obj.stringOrNull("updateTime")?.toLongOrNull() ?: 0L,
                    )
                }
                System.err.println("NETUSERPLAYLISTS uid=$uid playlists=${list.size}")
                list
            }
        }

    internal data class SubscribedArtist(
        val id: String,
        val name: String,
        val coverUrl: String,
    )

    /** 我关注的歌手。 */
    suspend fun fetchSubscribedArtists(cookie: String?, limit: Int = 200): Result<List<SubscribedArtist>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) error("需要登录网易云")
                val root = requestWeapi(
                    "/api/artist/sublist",
                    mapOf("limit" to "$limit", "offset" to "0", "total" to "true"),
                    cookie,
                )
                val list = root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj.string("id").ifBlank { return@mapNotNull null }
                    val name = obj.string("name").ifBlank { return@mapNotNull null }
                    // sublist 里部分歌手没有 picUrl，退到头像字段，避免整行没图。
                    val cover = obj.string("picUrl")
                        .ifBlank { obj.string("img1v1Url") }
                        .ifBlank { obj.string("avatar") }
                    SubscribedArtist(id, name, cover)
                }
                System.err.println(
                    "NETARTISTS artists=${list.size} missingCover=${list.count { it.coverUrl.isBlank() }} keys=${root.keys}",
                )
                list
            }
        }

    /** 专辑详情：专辑信息 + 曲目，用于“前往专辑”的在线页。 */
    suspend fun fetchAlbumDetail(albumId: Long, cookie: String?): Result<NeteasePlaylistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = request("/api/v1/album/$albumId", emptyMap(), cookie)
                val album = root["album"]?.jsonObjectOrNull() ?: error("专辑不存在或无法访问")
                val name = album.string("name").ifBlank { "专辑" }
                val cover = album.string("picUrl").ifBlank { album.string("blurPicUrl") }
                val songs = root["songs"]?.jsonArray.orEmpty().mapNotNull { element ->
                    element.jsonObjectOrNull()?.let { parseTrack(it, cover) }
                }.distinctBy { it.songId }
                System.err.println("NETALBUM id=$albumId songs=${songs.size} code=${root.string("code")}")
                if (songs.isEmpty()) error("专辑返回为空（code=${root.string("code")}）")
                NeteasePlaylistPayload(albumId.toString(), name, cover, songs)
            }
        }

    /** 歌手热门歌曲（关注的歌手点进来时用）。 */
    suspend fun fetchArtistTopSongs(artistId: String, cookie: String?): Result<List<NeteaseEntryPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requestWeapi("/api/artist/top/song", mapOf("id" to artistId), cookie)
                val songs = root["songs"]?.jsonArray.orEmpty().mapNotNull { element ->
                    element.jsonObjectOrNull()?.let { parseTrack(it, "") }
                }.distinctBy { it.songId }
                System.err.println("NETARTISTTOP id=$artistId songs=${songs.size} code=${root.string("code")}")
                if (songs.isEmpty()) error("歌手歌曲为空（code=${root.string("code")}）")
                songs
            }
        }

    /** 登录用户“我喜欢的音乐”的摘要：首页只展示封面/名称/数量，不拉整份歌曲列表。 */
    suspend fun fetchLikedPlaylistSummary(cookie: String?): Result<LikedPlaylistSummary?> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) error("需要登录网易云")
                // 账号与歌单接口都要走 weapi；原生接口会返回 -460/空账号。
                val account = requestWeapi("/api/nuser/account/get", emptyMap(), cookie)
                val uid = account["account"]?.jsonObjectOrNull()?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: account["profile"]?.jsonObjectOrNull()?.get("userId")?.jsonPrimitive?.contentOrNull
                System.err.println("NETHB account uid=$uid keys=${account.keys}")
                if (uid.isNullOrBlank()) error("无法获取网易云账号")
                val root = requestWeapi(
                    "/api/user/playlist",
                    mapOf("uid" to uid, "offset" to "0", "limit" to "1000", "includeVideo" to "true"),
                    cookie,
                )
                val playlists = root["playlist"]?.jsonArray.orEmpty().mapNotNull { it.jsonObjectOrNull() }
                System.err.println("NETHB playlists=${playlists.size} keys=${root.keys}")
                // 只认 specialType=5；“第一个数字 ID”会选中私人雷达等接口不支持的特殊歌单。
                val liked = playlists.firstOrNull { it.stringOrNull("specialType") == "5" }
                System.err.println(
                    "NETHB liked id=${liked?.stringOrNull("id")} total=${playlists.size}",
                )
                liked?.let { playlist ->
                    val id = playlist.stringOrNull("id")?.takeIf { it.all(Char::isDigit) }
                        ?: return@runCatching null
                    LikedPlaylistSummary(
                        id = id,
                        name = playlist.stringOrNull("name").orEmpty(),
                        coverUrl = playlist.stringOrNull("coverImgUrl").orEmpty(),
                        trackCount = playlist.stringOrNull("trackCount")?.toIntOrNull() ?: 0,
                        description = playlist.stringOrNull("description").orEmpty(),
                    )
                }
            }
        }

    /** 登录用户“我喜欢的音乐”歌单 ID：心动模式的 pid 必须是真实歌单，日志里的“参数错误”就是 pid 为空导致的。 */
    suspend fun fetchLikedPlaylistId(cookie: String?): Result<String?> =
        fetchLikedPlaylistSummary(cookie).map { it?.id }

    /** 心动模式：基于当前歌曲取下一首推荐（参考 playmode_intelligence_list）。 */
    suspend fun fetchHeartbeatNext(
        songId: Long,
        startMusicId: Long,
        playlistId: String?,
        cookie: String?,
        count: Int = 1,
    ): Result<List<NeteaseEntryPayload>> = withContext(Dispatchers.IO) {
        runCatching {
            val form = mapOf(
                "songId" to "$songId",
                "type" to "fromPlayOne",
                "playlistId" to (playlistId ?: ""),
                "startMusicId" to "$startMusicId",
                "count" to "$count",
            )
            val root = requestWeapi("/api/playmode/intelligence/list", form, cookie)
            val entries = parseHeartbeatEntries(root, songId)
            System.err.println(
                "NETHB weapi code=${root.string("code")} entries=${entries.size} " +
                    "pid=${playlistId.orEmpty()} song=$songId start=$startMusicId",
            )
            if (entries.isEmpty()) {
                error(
                    "心动模式接口返回为空（song=$songId start=$startMusicId pid=${playlistId.orEmpty()}）: " +
                        root.toString().take(300),
                )
            }
            entries
        }
    }

    private fun parseHeartbeatEntries(root: JsonObject, seedSongId: Long): List<NeteaseEntryPayload> {
        val candidates = mutableListOf<kotlinx.serialization.json.JsonElement>()
        root["data"]?.let { data ->
            when (data) {
                is kotlinx.serialization.json.JsonArray -> candidates.addAll(data)
                is JsonObject -> {
                    data["data"]?.let { if (it is kotlinx.serialization.json.JsonArray) candidates.addAll(it) }
                    candidates.add(data)
                }
                else -> Unit
            }
        }
        root["recommendations"]?.let { if (it is kotlinx.serialization.json.JsonArray) candidates.addAll(it) }
        val result = mutableListOf<NeteaseEntryPayload>()
        for (element in candidates) {
            val obj = element.jsonObjectOrNull() ?: continue
            val song = obj["songInfo"]?.jsonObjectOrNull()
                ?: obj["song"]?.jsonObjectOrNull()
                ?: obj["recommendSong"]?.jsonObjectOrNull()
                ?: obj
            val parsed = parseTrack(song, "") ?: continue
            if (parsed.songId != seedSongId) result += parsed
        }
        return result.distinctBy { it.songId }.take(3)
    }

    internal data class Toplist(
        val id: String,
        val name: String,
        val coverUrl: String,
        val updateFrequency: String,
    )

    /** 排行榜：只要榜单元数据，点开后按普通歌单详情加载。 */
    suspend fun fetchToplists(cookie: String?): Result<List<Toplist>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = request("/api/toplist/detail", emptyMap(), cookie)
            root["list"]?.jsonArray.orEmpty().mapNotNull { element ->
                val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                val id = obj.string("id").ifBlank { return@mapNotNull null }
                val name = obj.string("name").ifBlank { return@mapNotNull null }
                // 数字专辑售卖榜不是歌曲榜单。
                if (name.contains("数字")) return@mapNotNull null
                Toplist(id, name, obj.string("coverImgUrl"), obj.string("updateFrequency"))
            }
        }
    }

    /** 首页整页块里的一个分区：曲风精选/歌曲推荐/推荐歌单/艺人新动向等都由服务端下发。 */
    internal data class HomeBlock(
        val code: String,
        val title: String,
        val songs: List<NeteaseEntryPayload> = emptyList(),
        val playlists: List<NeteasePlaylistPayload> = emptyList(),
    )

    /**
     * 首页整页块：一次返回 App 推荐页的全部分区。
     * 必须带登录 Cookie；未登录会拿不到区块。
     */
    suspend fun fetchHomeBlocks(cookie: String?): Result<List<HomeBlock>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requestWeapi(
                    "/api/homepage/block/page",
                    // 参考实现只有 refresh（默认 false）与翻页用的 cursor；首屏不能带 cursor=0。
                    mapOf("refresh" to "false"),
                    cookie,
                )
                val blocks = root["data"]?.jsonObjectOrNull()?.get("blocks")?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObjectOrNull() }
                if (blocks.isEmpty()) {
                    error("首页区块为空（code=${root.string("code")}，需要登录网易云）")
                }
                System.err.println(
                    "NETBLOCK code=${root.string("code")} blocks=${blocks.map { it.string("blockCode") }}",
                )
                blocks.map { block ->
                    val ui = block["uiElement"]?.jsonObjectOrNull()
                    // 部分歌曲块的标题不在 block 级，退到 creative 级再取一次。
                    val creativeTitle = block["creatives"]?.jsonArray.orEmpty()
                        .firstNotNullOfOrNull { creative ->
                            creative.jsonObjectOrNull()?.get("uiElement")?.jsonObjectOrNull()
                                ?.titleText("mainTitle")?.takeIf { it.isNotBlank() }
                        }
                        .orEmpty()
                    val title = ui?.titleText("mainTitle").orEmpty().ifBlank { creativeTitle }
                    val blockCover = ui?.get("image")?.jsonObjectOrNull()?.string("imageUrl").orEmpty()
                    val resources = block["creatives"]?.jsonArray.orEmpty()
                        .flatMap { creative -> creative.jsonObjectOrNull()?.get("resources")?.jsonArray.orEmpty() }
                        .mapNotNull { it.jsonObjectOrNull() }
                    val songs = resources.mapNotNull { resource ->
                        val song = resource["resourceExtInfo"]?.jsonObjectOrNull()
                            ?.get("songData")?.jsonObjectOrNull()
                            ?: return@mapNotNull null
                        parseTrack(song, blockCover)
                    }.distinctBy { it.songId }
                    val playlists = resources
                        .filter { resource ->
                            // 首页块的歌单资源 resourceType 是小写的 "list"。
                            resource.string("resourceType").contains("list", ignoreCase = true) ||
                                resource.string("resourceUrl").contains("/playlist", ignoreCase = true)
                        }
                        .mapNotNull { obj ->
                            val id = obj.stringOrNull("resourceId")?.takeIf { it.isNotBlank() }
                                ?: obj.stringOrNull("id") ?: return@mapNotNull null
                            val resourceUi = obj["uiElement"]?.jsonObjectOrNull() ?: return@mapNotNull null
                            val name = resourceUi.titleText("mainTitle")
                                .ifBlank { resourceUi.titleText("subTitle") }
                            if (name.isBlank()) return@mapNotNull null
                            val cover = resourceUi["image"]?.jsonObjectOrNull()?.string("imageUrl").orEmpty()
                                .ifBlank { resourceUi.string("imageUrl") }
                            NeteasePlaylistPayload(id, name, cover, emptyList())
                        }
                        .distinctBy { it.id }
                    System.err.println(
                        "NETBLOCK ${block.string("blockCode")} title=$title " +
                            "songs=${songs.size} playlists=${playlists.size}",
                    )
                    if (title.isBlank() && songs.isNotEmpty()) {
                        System.err.println("NETBLOCK ${block.string("blockCode")} raw=${block.toString().take(1200)}")
                    }
                    HomeBlock(block.string("blockCode"), title, songs, playlists)
                }
            }
        }

    /** 每日推荐歌曲（按账号口味），首页“根据你喜爱的歌曲推荐”使用。 */
    suspend fun fetchRecommendSongs(cookie: String?, limit: Int = 30): Result<List<NeteaseEntryPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requestWeapi(
                    "/api/v3/discovery/recommend/songs",
                    mapOf("afresh" to "false"),
                    cookie,
                )
                val songs = root["data"]?.jsonObjectOrNull()?.get("dailySongs")?.jsonArray.orEmpty()
                    .mapNotNull { element -> element.jsonObjectOrNull()?.let { parseTrack(it, "") } }
                    .distinctBy { it.songId }
                    .take(limit)
                System.err.println("NETRECSONGS code=${root.string("code")} entries=${songs.size}")
                if (songs.isEmpty()) {
                    error("每日推荐歌曲为空（code=${root.string("code")}，需要登录网易云）")
                }
                songs
            }
        }

    /** 新歌：列表项的 song 字段是完整歌曲对象，外层 picUrl 只作封面兜底。 */
    suspend fun fetchNewSongs(cookie: String?, limit: Int = 12): Result<List<NeteaseEntryPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = request(
                    "/api/personalized/newsong",
                    mapOf("type" to "new", "limit" to "$limit", "areaId" to "0"),
                    cookie,
                )
                root["result"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val song = obj["song"]?.jsonObjectOrNull() ?: obj
                    parseTrack(song, obj.string("picUrl"))
                }.distinctBy { it.songId }
            }
        }

    /** 推荐歌单：登录后的“每日推荐歌单”，按账号口味生成（网页版推荐池不相关，不用）。 */
    suspend fun fetchRecommendedResources(cookie: String?, limit: Int = 30): Result<List<NeteasePlaylistPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requestWeapi("/api/v1/discovery/recommend/resource", emptyMap(), cookie)
                val items = root["recommend"]?.jsonArray.orEmpty()
                if (items.isEmpty()) {
                    error("推荐歌单为空（code=${root.string("code")}，需要登录网易云）")
                }
                items.mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj.string("id").ifBlank { return@mapNotNull null }
                    val name = obj.string("name").ifBlank { return@mapNotNull null }
                    NeteasePlaylistPayload(
                        id = id,
                        name = name,
                        coverUrl = obj.string("picUrl").ifBlank { obj.string("coverImgUrl") },
                        entries = emptyList(),
                        description = obj.string("copywriter"),
                    )
                }.take(limit)
            }
        }

    /** 网页版推荐歌单（保留给设置页的旧入口，首页不使用）。 */
    suspend fun fetchRecommendedPlaylists(cookie: String?, limit: Int = 12): Result<List<NeteasePlaylistPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requestWeapi("/api/personalized/playlist", mapOf("limit" to "$limit"), cookie)
                root["result"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj.string("id").ifBlank { return@mapNotNull null }
                    val name = obj.string("name").ifBlank { return@mapNotNull null }
                    val cover = obj.string("picUrl").ifBlank { obj.string("coverImgUrl") }
                    NeteasePlaylistPayload(id, name, cover, emptyList())
                }
            }
        }

    /** 取网易云歌词：有逐字歌词时返回带 yrc 的 JSON 封装，否则返回合并后的 LRC。 */
    suspend fun fetchLyrics(songId: Long, cookie: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = request(
                "/api/song/lyric",
                mapOf(
                    "id" to "$songId",
                    "lv" to "-1",
                    "kv" to "-1",
                    "tv" to "-1",
                    "rv" to "-1",
                    "yv" to "-1",
                    "ytv" to "-1",
                    "yrv" to "-1",
                ),
                cookie,
            )
            val lrc = root["lrc"]?.jsonObjectOrNull()?.string("lyric").orEmpty()
            val tlyric = root["tlyric"]?.jsonObjectOrNull()?.string("lyric").orEmpty()
            val klyric = root["klyric"]?.jsonObjectOrNull()?.string("lyric").orEmpty()
            val yrc = root["yrc"]?.jsonObjectOrNull()?.string("lyric").orEmpty()
            val ytlrc = root["ytlrc"]?.jsonObjectOrNull()?.string("lyric").orEmpty()
            val romalrc = root["romalrc"]?.jsonObjectOrNull()?.string("lyric").orEmpty()
            val yromalrc = root["yromalrc"]?.jsonObjectOrNull()?.string("lyric").orEmpty()
            if (yrc.isNotBlank()) {
                return@runCatching buildJsonObject {
                    put("tuneLyrics", 1)
                    put("lrc", lrc)
                    put("tlyric", tlyric)
                    put("yrc", yrc)
                    put("ytlrc", ytlrc)
                    put("romalrc", romalrc)
                    put("yromalrc", yromalrc)
                }.toString()
            }
            val candidates = listOf(
                mergeTranslation(lrc, tlyric),
                mergeTranslation(klyric, tlyric),
                mergeTranslation(yrcToLrc(yrc), tlyric),
            ).filter { it.isNotBlank() }
            if (candidates.isEmpty()) error("没有歌词")
            // 同一首歌不同字段完整度不同：取带时间轴行数最多的一份。
            candidates.maxByOrNull { content -> content.lineSequence().count { it.startsWith("[") && it.getOrNull(1)?.isDigit() == true } }
                ?: error("没有歌词")
        }
    }

    /** 把翻译行按时间戳并入 LRC，保持应用内“原文 / 译文”双语显示。 */
    private fun mergeTranslation(lrc: String, tlyric: String): String {
        if (tlyric.isBlank()) return lrc
        val linePattern = Regex("^\\[(\\d+):(\\d+(?:[.:]\\d+)?)\\](.*)$")
        val translations = tlyric.lineSequence().mapNotNull { line ->
            val match = linePattern.find(line.trim()) ?: return@mapNotNull null
            val key = "%s:%s".format(match.groupValues[1], match.groupValues[2].replace(':', '.'))
            val text = match.groupValues[3].trim()
            if (text.isBlank()) null else key to text
        }.toMap()
        if (translations.isEmpty()) return lrc
        return lrc.lineSequence().joinToString("\n") { line ->
            val match = linePattern.find(line.trim()) ?: return@joinToString line
            val key = "%s:%s".format(match.groupValues[1], match.groupValues[2].replace(':', '.'))
            val text = match.groupValues[3].trim()
            val translation = translations[key]
            if (text.isBlank() || translation == null || translation == text) {
                line.trim()
            } else {
                val prefix = "[%s:%s]".format(match.groupValues[1], match.groupValues[2])
                "$prefix$text / $translation"
            }
        }
    }

    /** 逐字歌词 `[start,dur](start,dur,0)字...` 转成普通 LRC。 */
    private fun yrcToLrc(yrc: String): String {
        val linePattern = Regex("^\\[(\\d+),\\d+\\](.*)$")
        val wordPattern = Regex("\\((\\d+,\\d+,\\d+)\\)([^(]*)")
        return yrc.lineSequence().mapNotNull { raw ->
            val line = linePattern.find(raw.trim()) ?: return@mapNotNull null
            val start = line.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val text = wordPattern.findAll(line.groupValues[2]).map { it.groupValues[1] }.joinToString("").trim()
            if (text.isBlank()) null else "[%02d:%02d.%03d]%s".format(start / 60000, start % 60000 / 1000, start % 1000, text)
        }.joinToString("\n")
    }

    /** 下载到目标文件；每 64KB 检查一次协程取消。 */
    suspend fun download(url: String, cookie: String?, target: java.io.File): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                // 取消时直接断连：否则阻塞中的 read 要等 readTimeout 才退出，切歌仍会被拖住。
                val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) runCatching { connection.disconnect() }
                }
                try {
                    applyHeaders(connection, cookie)
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 30_000
                    if (connection.responseCode !in 200..299) error("下载失败（HTTP ${connection.responseCode}）")
                    target.parentFile?.mkdirs()
                    val temp = java.io.File(target.parentFile, target.name + ".part")
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    check(temp.length() > 0) { "下载内容为空" }
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        error("无法保存下载文件")
                    }
                    target.length()
                } finally {
                    cancelHandle?.dispose()
                    connection.disconnect()
                }
            }
        }

    /** 扫码登录：取 unikey 与下发的 NMTID，二维码内容为网页登录链接。 */
    suspend fun createQrLogin(cookie: String? = null): Result<NeteaseLoginQr> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, setCookies) = requestWithCookies(
                "/api/login/qrcode/unikey",
                mapOf("type" to "3"),
                cookie,
                base = QR_BASE,
            )
            val key = root.string("unikey").ifBlank { error("无法获取登录二维码") }
            val sessionCookie = mergeCookies(cookie, joinCookies(setCookies))
            System.err.println("NETQR unikey key=$key nmtid=${sessionCookie?.contains("NMTID=") == true} code=${root.string("code")}")
            NeteaseLoginQr(key = key, url = "https://music.163.com/login?codekey=$key", cookie = sessionCookie)
        }.onFailure { System.err.println("NETQR unikey failed: ${it.message}") }
    }

    /** 轮询扫码状态：800 过期 / 801 等待 / 802 已扫待确认 / 803 成功。 */
    suspend fun pollQrLogin(key: String, cookie: String?): Result<NeteaseQrStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (root, setCookies) = requestWithCookies(
                    "/api/login/qrcode/client/login",
                    mapOf("key" to key, "type" to "3"),
                    cookie,
                    base = QR_BASE,
                )
                val code = root.string("code").toIntOrNull() ?: 800
                val message = root.string("message")
                val session = mergeCookies(cookie, joinCookies(setCookies))
                val verifyUrl = root["verifyUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                System.err.println(
                    "NETQR poll code=$code message=$message setCookie=${setCookies.size} " +
                        "musicU=${session?.contains("MUSIC_U=") == true} verifyUrl=${verifyUrl != null}",
                )
                NeteaseQrStatus(code = code, message = message, cookie = session, verifyUrl = verifyUrl)
            }.onFailure { System.err.println("NETQR poll failed: ${it.message}") }
        }

    private fun request(
        path: String,
        form: Map<String, String>,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = true,
    ): JsonObject {
        val (root, _) = requestWithCookies(path, form, cookie, connectTimeoutMs, readTimeoutMs, retry)
        return root
    }

    private fun requestWithCookies(
        path: String,
        form: Map<String, String>,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = true,
        base: String = BASE,
    ): Pair<JsonObject, List<String>> {
        val attempt = {
            val connection = URL(base + path).openConnection() as HttpURLConnection
            try {
                applyHeaders(connection, cookie, UA)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = form.entries.joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val buffer = ByteArrayOutputStream()
                connection.inputStream.use { it.copyTo(buffer) }
                val root = json.parseToJsonElement(buffer.toString(Charsets.UTF_8.name())) as? JsonObject
                    ?: error("响应格式异常")
                val cookies = connection.headerFields.entries
                    .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value.orEmpty() }
                root to cookies
            } finally {
                connection.disconnect()
            }
        }
        return if (!retry) attempt() else runCatching(attempt).getOrElse { first ->
            runCatching(attempt).getOrElse { throw first }
        }
    }

    private fun joinCookies(headers: List<String>): String? = headers
        .mapNotNull { header -> header.substringBefore(';').trim().takeIf { it.contains('=') } }
        .filter { segment -> segment.substringAfter('=').isNotBlank() }
        .distinct()
        .joinToString("; ")
        .takeIf { it.isNotBlank() }

    /** 合并 Cookie 头（按名字去重，后者覆盖前者）。 */
    private fun mergeCookies(vararg headers: String?): String? {
        val byName = LinkedHashMap<String, String>()
        headers.filterNotNull().forEach { header ->
            header.split(';').map(String::trim).filter { it.contains('=') }.forEach { segment ->
                byName[segment.substringBefore('=').trim()] = segment
            }
        }
        return byName.values.joinToString("; ").takeIf { it.isNotBlank() }
    }

    private fun applyHeaders(connection: HttpURLConnection, cookie: String?, userAgent: String = UA) {
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Referer", "https://music.163.com/")
        connection.setRequestProperty("Accept", "*/*")
        if (!cookie.isNullOrBlank()) connection.setRequestProperty("Cookie", cookie)
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** 首页块的标题字段有对象和字符串两种形态（{"title": "..."} / "..."）。 */
    private fun JsonObject.titleText(key: String): String =
        when (val element = this[key]) {
            is JsonObject -> element.string("title").ifBlank { element.string("text") }
            else -> element?.jsonPrimitive?.contentOrNull.orEmpty()
        }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()
}
