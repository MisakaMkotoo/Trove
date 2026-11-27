package com.exodidio.tune.netease

import android.os.SystemClock
import android.util.Log
import com.exodidio.tune.net.TuneHttp
import com.exodidio.tune.net.executeCancellable
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request

/**
 * 网易云原生 /api 接口的最小客户端（参考 NeteaseCloudMusicApi 的调用方式）：
 * POST 表单 + 浏览器 UA/Referer，可选携带登录 Cookie。
 * 所有网络与磁盘操作都在 IO 线程执行，并设置连接/读取超时。
 */
internal object NeteaseClient {
    internal const val PrivateRadarPlaylistId = "3136952023"

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
                val root = NeteaseWireTransport.requestApi(
                    "/api/v6/playlist/detail",
                    mapOf("id" to playlistId, "n" to "100000", "s" to "0"),
                    cookie,
                )
                val playlist = root["playlist"]?.jsonObject ?: error("歌单不存在或无法访问")
                val id = playlist.string("id").ifBlank { playlistId }
                val name = playlist.string("name").ifBlank { "网易云歌单" }
                val cover = playlist.string("coverImgUrl")
                val remoteTrackCount = playlist.string("trackCount").toIntOrNull() ?: 0
                val tracks = playlist["tracks"]?.jsonArrayOrNull().orEmpty()
                val trackIds = playlist["trackIds"]?.jsonArrayOrNull().orEmpty()
                    .mapNotNull { it.jsonObjectOrNull()?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull() }
                val parsed = tracks.mapNotNull { parseNeteaseTrack(it.jsonObjectOrNull() ?: return@mapNotNull null, cover) }
                val orderedIds = trackIds.distinct()
                val entries = if (orderedIds.isNotEmpty()) {
                    val parsedById = parsed.associateBy { it.songId }
                    val missing = orderedIds.filterNot { it in parsedById }
                    val fetchedById = if (missing.isNotEmpty()) {
                        fetchTrackDetails(missing, cover, cookie).associateBy { it.songId }
                    } else {
                        emptyMap()
                    }
                    val allById = HashMap<Long, NeteaseEntryPayload>(parsedById.size + fetchedById.size).apply {
                        putAll(parsedById)
                        putAll(fetchedById)
                    }
                    val ordered = orderedIds.mapNotNull { allById[it] }
                    if (ordered.isNotEmpty()) ordered else parsed.distinctBy { it.songId }
                } else {
                    parsed.distinctBy { it.songId }
                }
                val resolvedCount = maxOf(remoteTrackCount, orderedIds.size, entries.size)
                Log.d(
                    "NeteaseClient",
                    "playlist id=$id tracks=${parsed.size} trackIds=${orderedIds.size} hydrated=${entries.size} remoteCount=$remoteTrackCount",
                )
                NeteasePlaylistPayload(
                    id = id,
                    name = name,
                    coverUrl = cover,
                    entries = entries,
                    description = playlist.string("description"),
                    remoteTrackCount = resolvedCount,
                    privacy = playlist.stringOrNull("privacy")?.toIntOrNull() ?: 0,
                )
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w("NeteaseClient", "fetchPlaylist failed id=$playlistId err=${err.message}")
            }
        }

    internal suspend fun fetchTrackDetails(ids: List<Long>, fallbackCover: String, cookie: String?): List<NeteaseEntryPayload> {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return emptyList()
        val semaphore = Semaphore(3)
        return coroutineScope {
            uniqueIds.chunked(500).map { chunk ->
                async(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    semaphore.withPermit {
                        val body = chunk.joinToString(",", prefix = "[", postfix = "]") { """{"id":$it}""" }
                        val root = NeteaseWireTransport.requestApi(
                            "/api/v3/song/detail",
                            mapOf("c" to body),
                            cookie,
                        )
                        val code = root.stringOrNull("code")?.toIntOrNull()
                        if (code != null && code != 200) {
                            val msg = root.stringOrNull("message") ?: root.stringOrNull("msg") ?: "code=$code"
                            error("获取歌曲详情失败（$msg）")
                        }
                        val songs = root["songs"]?.jsonArrayOrNull() ?: error("歌曲详情响应格式异常")
                        songs.mapNotNull { element ->
                            parseNeteaseTrack(element.jsonObjectOrNull() ?: return@mapNotNull null, fallbackCover)
                        }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** 取流结果分类：完整音源、试听、登录失效、不可播放（无版权/下架/会员）。 */
    sealed interface NeteaseStream {
        data class Playable(
            val url: String,
            val type: String,
            val durationMs: Long = 0L,
            val sizeBytes: Long = 0L,
        ) : NeteaseStream

        data class Preview(
            val url: String,
            val type: String,
            val durationMs: Long,
        ) : NeteaseStream

        data object LoginRequired : NeteaseStream

        data class Unavailable(val reason: String) : NeteaseStream
    }

    suspend fun fetchSongStream(
        songId: Long,
        cookie: String?,
        preferLossless: Boolean = false,
    ): Result<NeteaseStream> {
        // 优先无损时按 lossless → exhigh → standard 回退；否则 MP3 档。
        val levels = if (preferLossless) {
            listOf("lossless", "exhigh", "standard")
        } else {
            listOf("exhigh", "standard")
        }
        return fetchSongStreamLevels(songId, cookie, levels)
    }

    /** 按指定音质回退序列解析播放/下载直链；沉浸环绕声 (sky) 自动附带 immerseType=sky。 */
    suspend fun fetchSongStreamLevels(
        songId: Long,
        cookie: String?,
        levels: List<String>,
    ): Result<NeteaseStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolvedLevels = levels.ifEmpty { listOf("standard") }
                var lastReason = "无可用音源"
                for (level in resolvedLevels) {
                    val params = buildMap {
                        put("ids", "[$songId]")
                        put("level", level)
                        put("encodeType", "flac")
                        if (level.equals("sky", ignoreCase = true)) {
                            put("immerseType", "sky")
                        }
                    }
                    val root = NeteaseWireTransport.request(
                        "/api/song/enhance/player/url/v1",
                        params,
                        cookie,
                        connectTimeoutMs = 4_000,
                        readTimeoutMs = 6_000,
                        retry = false,
                    )
                    val code = root.stringOrNull("code")?.toIntOrNull()
                    if (code == 301 || code == 302) {
                        return@runCatching NeteaseStream.LoginRequired
                    }
                    val data = root["data"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
                    if (data == null) {
                        Log.d("NeteaseClient", "url song=$songId level=$level code=$code no-data")
                        continue
                    }
                    val dataId = data.stringOrNull("id")?.toLongOrNull()
                    if (dataId != null && dataId != songId) continue
                    val dataCode = data.stringOrNull("code")?.toIntOrNull()
                    val url = data.string("url")
                    val trial = isNeteaseTrialSource(data)
                    val fee = data.stringOrNull("fee")?.toIntOrNull() ?: 0
                    val timeMs = data.stringOrNull("time")?.toLongOrNull() ?: 0L
                    val sizeBytes = data.stringOrNull("size")?.toLongOrNull() ?: 0L
                    val bitrate = data.stringOrNull("br")?.toLongOrNull() ?: 0L
                    // 按字节数与码率反推实际音频时长；接口偶尔不给试听标记，
                    // 但文件只够 30 秒而 time 是整首，就能识别成未标记试听。
                    val encodedMs = if (sizeBytes > 0L && bitrate > 0L) {
                        sizeBytes * 8_000L / bitrate
                    } else {
                        0L
                    }
                    val unmarkedTrial = encodedMs > 0L && timeMs > 0L && encodedMs < timeMs * 6 / 10
                    Log.d(
                        "NeteaseClient",
                        "url song=$songId level=$level code=${code ?: dataCode} " +
                            "url=${url.isNotBlank()} fee=$fee trial=$trial unmarked=$unmarkedTrial " +
                            "size=$sizeBytes br=$bitrate time=$timeMs encoded=$encodedMs",
                    )
                    if (url.isNotBlank()) {
                        val type = data.string("type").lowercase().ifBlank { "mp3" }
                        if (trial || unmarkedTrial) {
                            // freeTrialInfo.end 是秒；试听接口的 time 本身就是片段长度（如 30040ms）。
                            val trialEndMs = data["freeTrialInfo"]?.jsonObjectOrNull()
                                ?.stringOrNull("end")?.toLongOrNull()
                                ?.takeIf { it > 0L }
                                ?.times(1_000L)
                            val clipMs = if (trial) {
                                timeMs.takeIf { it > 0L }
                                    ?: trialEndMs
                                    ?: encodedMs.takeIf { it > 0L }
                                    ?: 30_000L
                            } else {
                                // 未标记试听：time 是整首，按字节反推的片段长度才是真的。
                                encodedMs.takeIf { it > 0L } ?: 30_000L
                            }
                            return@runCatching NeteaseStream.Preview(url, type, clipMs)
                        }
                        return@runCatching NeteaseStream.Playable(url, type, timeMs, sizeBytes)
                    }
                    // 接口本身成功但没有可播 URL：按会员/版权归到不可播放，不做重试。
                    lastReason = if (fee > 0) "需要会员" else "无可用音源"
                }
                NeteaseStream.Unavailable(lastReason)
            }
        }

    /** 每日推荐歌曲（支持 afresh=true 强制刷新）。 */
    suspend fun fetchDailyRecommend(cookie: String?, afresh: Boolean = false): Result<NeteasePlaylistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val form = if (afresh) mapOf("afresh" to "true") else mapOf("afresh" to "false")
                val root = NeteaseWireTransport.requestWeapi("/api/v3/discovery/recommend/songs", form, cookie)
                val songs = root["data"]?.jsonObjectOrNull()?.get("dailySongs")?.jsonArrayOrNull().orEmpty()
                val entries = songs.mapNotNull { element ->
                    parseNeteaseTrack(element.jsonObjectOrNull() ?: return@mapNotNull null, "")
                }
                if (entries.isEmpty()) error("没有拿到每日推荐（需要登录）")
                NeteasePlaylistPayload("daily", "每日推荐", entries.firstOrNull()?.coverUrl.orEmpty(), entries)
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 私人雷达：固定歌单 id，服务端按登录账号每天轮换内容（不做标题/块兜底）。 */
    suspend fun fetchRadar(cookie: String?): Result<NeteasePlaylistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) error("私人雷达需要登录网易云")
                val playlist = fetchPlaylist(PrivateRadarPlaylistId, cookie).getOrThrow()
                Log.d("NeteaseClient", "radar fixed id entries=${playlist.entries.size}")
                if (playlist.entries.isEmpty()) error("私人雷达返回为空")
                playlist.copy(name = "私人雷达")
            }
        }

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
                val root = NeteaseWireTransport.request("/api/cloudsearch/pc", cloudSearchForm(keyword, 1000, limit), cookie)
                root["result"]?.jsonObjectOrNull()?.get("playlists")?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
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
                val root = NeteaseWireTransport.request("/api/cloudsearch/pc", cloudSearchForm(keyword, 100, limit), cookie)
                root["result"]?.jsonObjectOrNull()?.get("artists")?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
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
                val root = NeteaseWireTransport.request("/api/cloudsearch/pc", cloudSearchForm(keyword, 10, limit), cookie)
                root["result"]?.jsonObjectOrNull()?.get("albums")?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
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
            val root = NeteaseWireTransport.requestWeapi(
                "/api/v1/discovery/simiSong",
                mapOf("songid" to "$songId", "limit" to "$limit", "offset" to "$offset"),
                cookie,
            )
            val songs = root["songs"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
                element.jsonObjectOrNull()?.let { parseNeteaseTrack(it, "") }
            }.distinctBy { it.songId }
            Log.d("NeteaseClient", "simi song=$songId entries=${songs.size} code=${root.string("code")}")
            if (songs.isEmpty()) error("相似歌曲返回为空: ${root.toString().take(200)}")
            songs
        }
    }

    /** 私人漫游（私人 FM）：默认模式，参数与参考实现一致（weapi、空 body）。 */
    suspend fun fetchRoamSongs(cookie: String?): Result<List<NeteaseEntryPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestWeapi("/api/v1/radio/get", emptyMap(), cookie)
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
                    parseNeteaseTrack(song, "")
                }.distinctBy { it.songId }
                Log.d("NeteaseClient", "roam entries=${songs.size} code=${root.string("code")}")
                if (songs.isEmpty()) error("漫游返回为空: ${root.toString().take(200)}")
                songs
            }
        }

    /** 歌单摘要：只取名称/封面/数量，不解析歌曲，用于首页雷达卡片。 */
    suspend fun fetchPlaylistSummary(playlistId: String, cookie: String?): Result<PlaylistSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestApi(
                    "/api/v6/playlist/detail",
                    mapOf("id" to playlistId, "n" to "0", "s" to "0"),
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
                val root = runCatching { NeteaseWireTransport.request("/api/cloudsearch/pc", form, cookie) }
                    .getOrElse { NeteaseWireTransport.request("/api/search/get", form, cookie) }
                val songs = root["result"]?.jsonObjectOrNull()?.get("songs")?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
                    val name = obj.string("name")
                    if (name.isBlank()) return@mapNotNull null
                    val artists = obj["ar"]?.jsonArrayOrNull() ?: obj["artists"]?.jsonArrayOrNull()
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
                Log.d(
                    "NeteaseClient",
                    "search q=$keyword results=${songs.size} covers=${songs.count { it.coverUrl.isNotBlank() }}",
                )
                songs
            }.onFailure { Log.w("NeteaseClient", "search failed q=$keyword err=${it.message}") }
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
                val root = NeteaseWireTransport.requestWeapi("/api/nuser/account/get", emptyMap(), cookie)
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
                    Log.d("NeteaseClient", "account uid=${info.uid} created=${info.createdAtMillis}")
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
        val privacy: Int = 0,
    )

    /** 登录用户的全部歌单（自建 + 收藏），用 creatorId 区分。 */
    suspend fun fetchUserPlaylists(uid: String, cookie: String?): Result<List<UserPlaylistInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) error("需要登录网易云")
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/user/playlist",
                    mapOf("uid" to uid, "offset" to "0", "limit" to "1000"),
                    cookie,
                )
                val list = root["playlist"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
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
                        privacy = obj.stringOrNull("privacy")?.toIntOrNull() ?: 0,
                    )
                }
                Log.d("NeteaseClient", "user playlists uid=$uid playlists=${list.size}")
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
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/artist/sublist",
                    mapOf("limit" to "$limit", "offset" to "0", "total" to "true"),
                    cookie,
                )
                val list = root["data"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj.string("id").ifBlank { return@mapNotNull null }
                    val name = obj.string("name").ifBlank { return@mapNotNull null }
                    // sublist 里部分歌手没有 picUrl，退到头像字段，避免整行没图。
                    val cover = obj.string("picUrl")
                        .ifBlank { obj.string("img1v1Url") }
                        .ifBlank { obj.string("avatar") }
                    SubscribedArtist(id, name, cover)
                }
                Log.d(
                    "NeteaseClient",
                    "artists=${list.size} missingCover=${list.count { it.coverUrl.isBlank() }}",
                )
                list
            }
        }

    /** 专辑详情：专辑信息 + 曲目，用于“前往专辑”的在线页。 */
    suspend fun fetchAlbumDetail(albumId: Long, cookie: String?): Result<NeteasePlaylistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.request("/api/v1/album/$albumId", emptyMap(), cookie)
                val album = root["album"]?.jsonObjectOrNull() ?: error("专辑不存在或无法访问")
                val name = album.string("name").ifBlank { "专辑" }
                val cover = album.string("picUrl").ifBlank { album.string("blurPicUrl") }
                val songs = root["songs"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
                    element.jsonObjectOrNull()?.let { parseNeteaseTrack(it, cover) }
                }.distinctBy { it.songId }
                Log.d("NeteaseClient", "album id=$albumId songs=${songs.size} code=${root.string("code")}")
                if (songs.isEmpty()) error("专辑返回为空（code=${root.string("code")}）")
                NeteasePlaylistPayload(albumId.toString(), name, cover, songs)
            }
        }

    /** 歌手热门歌曲（兼容旧调用）。 */
    suspend fun fetchArtistTopSongs(artistId: String, cookie: String?): Result<List<NeteaseEntryPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestWeapi("/api/artist/top/song", mapOf("id" to artistId), cookie)
                val songs = root["songs"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
                    element.jsonObjectOrNull()?.let { parseNeteaseTrack(it, "") }
                }.distinctBy { it.songId }
                Log.d("NeteaseClient", "artistTop id=$artistId songs=${songs.size} code=${root.string("code")}")
                if (songs.isEmpty()) error("歌手歌曲为空（code=${root.string("code")}）")
                songs
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 歌手详情（头部信息 + 关注状态）。 */
    suspend fun fetchArtistDetail(artistId: String, cookie: String?): Result<NeteaseArtistDetailPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                coroutineScope {
                    val headJob = async {
                        runCatching {
                            NeteaseWireTransport.requestWeapi(
                                "/api/artist/head/info/get",
                                mapOf("id" to artistId),
                                cookie,
                            )
                        }
                    }
                    val profileJob = async {
                        runCatching {
                            NeteaseWireTransport.requestWeapi(
                                "/api/v1/artist/$artistId",
                                emptyMap(),
                                cookie,
                            )
                        }
                    }
                    val headRoot = headJob.await().getOrNull()
                    val profileRoot = profileJob.await().getOrNull()
                    val followed = profileRoot?.get("artist")?.jsonObjectOrNull()
                        ?.stringOrNull("followed")?.toBooleanStrictOrNull()
                    val primary = headRoot?.let {
                        runCatching { parseArtistDetail(it, artistId, followed) }.getOrNull()
                    }
                    val detail = primary ?: profileRoot?.let { parseArtistDetail(it, artistId, followed) }
                        ?: error("无法获取歌手详情")
                    Log.d("NeteaseClient", "artistDetail id=$artistId name=${detail.name} followed=${detail.followed}")
                    detail
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 歌手全部歌曲分页（order: "hot" 或 "time"）。 */
    suspend fun fetchArtistSongsPage(
        artistId: String,
        offset: Int = 0,
        limit: Int = 50,
        order: String = "hot",
        cookie: String?,
    ): Result<NeteaseArtistSongsPagePayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/v1/artist/songs",
                    mapOf(
                        "id" to artistId,
                        "private_cloud" to "true",
                        "work_type" to "1",
                        "order" to order,
                        "offset" to "$offset",
                        "limit" to "$limit",
                    ),
                    cookie,
                )
                val code = root.stringOrNull("code")?.toIntOrNull()
                if (code != null && code != 200) {
                    error(root.stringOrNull("message") ?: root.stringOrNull("msg") ?: "获取歌手歌曲失败（$code）")
                }
                parseArtistSongsPage(root).also {
                    Log.d("NeteaseClient", "artistSongs id=$artistId offset=$offset count=${it.songs.size} more=${it.hasMore} total=${it.total}")
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 歌手专辑列表分页。 */
    suspend fun fetchArtistAlbumsPage(
        artistId: String,
        offset: Int = 0,
        limit: Int = 30,
        cookie: String?,
    ): Result<NeteaseArtistAlbumsPagePayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/artist/albums/$artistId",
                    mapOf("offset" to "$offset", "limit" to "$limit", "total" to "true"),
                    cookie,
                )
                val code = root.stringOrNull("code")?.toIntOrNull()
                if (code != null && code != 200) {
                    error(root.stringOrNull("message") ?: root.stringOrNull("msg") ?: "获取歌手专辑失败（$code）")
                }
                parseArtistAlbumsPage(root).also {
                    Log.d("NeteaseClient", "artistAlbums id=$artistId offset=$offset count=${it.albums.size} more=${it.hasMore}")
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 我收藏的专辑列表分页。 */
    suspend fun fetchSubscribedAlbums(
        cookie: String?,
        offset: Int = 0,
        limit: Int = 50,
    ): Result<NeteaseSavedAlbumsPagePayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) throw NeteaseWriteException.LoginRequired("需要登录网易云")
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/album/sublist",
                    mapOf("offset" to "$offset", "limit" to "$limit", "total" to "true"),
                    cookie,
                )
                NeteaseWriteApi.ensureWriteSuccess(root)
                parseSubscribedAlbumsPage(root).also {
                    Log.d("NeteaseClient", "subAlbums offset=$offset count=${it.albums.size} total=${it.total} more=${it.hasMore}")
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 检查单张专辑的动态收藏状态。 */
    suspend fun fetchAlbumSubscribed(albumId: String, cookie: String?): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) throw NeteaseWriteException.LoginRequired("需要登录网易云")
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/album/detail/dynamic",
                    mapOf("id" to albumId),
                    cookie,
                )
                NeteaseWriteApi.ensureWriteSuccess(root)
                parseAlbumSubscribed(root)
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 云端最近播放单曲（最多 100 条）。 */
    suspend fun fetchRecentPlayedSongs(
        cookie: String?,
        limit: Int = 100,
    ): Result<List<NeteasePlayRecordPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) throw NeteaseWriteException.LoginRequired("需要登录网易云")
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/play-record/song/list",
                    mapOf("limit" to "$limit"),
                    cookie,
                )
                NeteaseWriteApi.ensureWriteSuccess(root)
                parseRecentPlayedSongs(root).also {
                    Log.d("NeteaseClient", "recentPlayed count=${it.size}")
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 听歌排行（allTime = false 为最近一周，true 为所有时间）。 */
    suspend fun fetchUserPlayRecords(
        uid: String,
        allTime: Boolean,
        cookie: String?,
    ): Result<List<NeteasePlayRecordPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) throw NeteaseWriteException.LoginRequired("需要登录网易云")
                if (uid.isBlank()) throw NeteaseWriteException.LoginRequired("无法获取网易云账号")
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/v1/play/record",
                    mapOf("uid" to uid, "type" to if (allTime) "0" else "1"),
                    cookie,
                )
                parseUserPlayRecords(root, allTime).also {
                    Log.d("NeteaseClient", "playRecord allTime=$allTime count=${it.size}")
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    internal fun parseArtistDetail(
        root: JsonObject,
        artistId: String,
        followedOverride: Boolean? = null,
    ): NeteaseArtistDetailPayload {
        val dataObj = root["data"]?.jsonObjectOrNull()
        val artist = dataObj?.get("artist")?.jsonObjectOrNull()
            ?: root["artist"]?.jsonObjectOrNull()
            ?: dataObj
            ?: error("歌手不存在或无法访问")
        val name = artist.string("name").ifBlank { error("歌手名称为空") }
        val cover = artist.string("avatar")
            .ifBlank { artist.string("cover") }
            .ifBlank { artist.string("picUrl") }
            .ifBlank { artist.string("img1v1Url") }
        val alias = buildList {
            (artist["alias"] as? JsonArray).orEmpty().forEach { elem ->
                elem.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
            (artist["transNames"] as? JsonArray).orEmpty().forEach { elem ->
                elem.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct()
        return NeteaseArtistDetailPayload(
            id = artist.string("id").ifBlank { artistId },
            name = name,
            coverUrl = cover,
            description = artist.string("briefDesc").ifBlank { artist.string("description") },
            alias = alias,
            musicCount = artist.stringOrNull("musicSize")?.toIntOrNull() ?: 0,
            albumCount = artist.stringOrNull("albumSize")?.toIntOrNull() ?: 0,
            mvCount = artist.stringOrNull("mvSize")?.toIntOrNull() ?: 0,
            followed = followedOverride ?: artist.stringOrNull("followed")?.toBooleanStrictOrNull(),
        )
    }

    internal fun parseArtistSongsPage(root: JsonObject): NeteaseArtistSongsPagePayload {
        val songs = root["songs"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            element.jsonObjectOrNull()?.let { parseNeteaseTrack(it, "") }
        }.distinctBy { it.songId }
        val total = root.stringOrNull("total")?.toIntOrNull() ?: songs.size
        val hasMore = root.stringOrNull("more")?.toBooleanStrictOrNull() ?: false
        return NeteaseArtistSongsPagePayload(songs = songs, hasMore = hasMore, total = total)
    }

    internal fun parseArtistAlbumsPage(root: JsonObject): NeteaseArtistAlbumsPagePayload {
        val albums = root["hotAlbums"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("id").ifBlank { return@mapNotNull null }
            val name = obj.string("name").ifBlank { return@mapNotNull null }
            val artistName = (obj["artists"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonObjectOrNull()?.string("name")?.takeIf(String::isNotBlank) }
                .joinToString(" / ")
                .ifBlank { obj["artist"]?.jsonObjectOrNull()?.string("name").orEmpty() }
            NeteaseArtistAlbumPayload(
                id = id,
                name = name,
                artistName = artistName,
                coverUrl = obj.string("picUrl").ifBlank { obj.string("blurPicUrl") },
                trackCount = obj.stringOrNull("size")?.toIntOrNull() ?: 0,
                publishTimeMs = obj.stringOrNull("publishTime")?.toLongOrNull() ?: 0L,
            )
        }
        val hasMore = root.stringOrNull("more")?.toBooleanStrictOrNull() ?: false
        return NeteaseArtistAlbumsPagePayload(albums = albums, hasMore = hasMore)
    }

    internal fun parseSubscribedAlbumsPage(root: JsonObject): NeteaseSavedAlbumsPagePayload {
        val albums = root["data"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("id").ifBlank { return@mapNotNull null }
            val name = obj.string("name").ifBlank { return@mapNotNull null }
            val artistName = (obj["artists"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonObjectOrNull()?.string("name")?.takeIf(String::isNotBlank) }
                .joinToString(" / ")
                .ifBlank { obj["artist"]?.jsonObjectOrNull()?.string("name").orEmpty() }
            NeteaseSavedAlbumPayload(
                id = id,
                name = name,
                artistName = artistName,
                coverUrl = obj.string("picUrl").ifBlank { obj.string("blurPicUrl") },
                trackCount = obj.stringOrNull("size")?.toIntOrNull()
                    ?: obj.stringOrNull("songCount")?.toIntOrNull()
                    ?: 0,
                subTimeMs = obj.stringOrNull("subTime")?.toLongOrNull() ?: 0L,
            )
        }
        val hasMore = root.stringOrNull("hasMore")?.toBooleanStrictOrNull() ?: false
        val total = root.stringOrNull("count")?.toIntOrNull() ?: albums.size
        return NeteaseSavedAlbumsPagePayload(albums = albums, hasMore = hasMore, total = total)
    }

    internal fun parseAlbumSubscribed(root: JsonObject): Boolean =
        root.stringOrNull("isSub")?.toBooleanStrictOrNull()
            ?: root.stringOrNull("subscribed")?.toBooleanStrictOrNull()
            ?: false

    internal fun parseRecentPlayedSongs(root: JsonObject): List<NeteasePlayRecordPayload> {
        val list = root["data"]?.jsonObjectOrNull()?.get("list")?.jsonArrayOrNull().orEmpty()
        val dedup = LinkedHashMap<Long, NeteasePlayRecordPayload>()
        for (element in list) {
            val obj = element.jsonObjectOrNull() ?: continue
            val songObj = obj["data"]?.jsonObjectOrNull()
                ?: obj["song"]?.jsonObjectOrNull()
                ?: continue
            val song = parseNeteaseTrack(songObj, "") ?: continue
            val playedAtMs = obj.stringOrNull("playTime")?.toLongOrNull() ?: 0L
            val existing = dedup[song.songId]
            if (existing == null || playedAtMs > existing.playedAtMs) {
                dedup[song.songId] = NeteasePlayRecordPayload(song = song, playedAtMs = playedAtMs)
            }
        }
        return dedup.values.toList()
    }

    internal fun parseUserPlayRecords(root: JsonObject, allTime: Boolean): List<NeteasePlayRecordPayload> {
        val code = root.stringOrNull("code")?.toIntOrNull() ?: 200
        if (code == -2) {
            throw NeteaseWriteException.ServerError(-2, "账号开启了听歌排行隐私保护，无法查看")
        }
        if (code != 200) {
            NeteaseWriteApi.ensureWriteSuccess(root)
        }
        val primaryKey = if (allTime) "allData" else "weekData"
        val fallbackKey = if (allTime) "weekData" else "allData"
        val items = (root[primaryKey] as? JsonArray)
            ?.takeIf { it.isNotEmpty() }
            ?: (root[fallbackKey] as? JsonArray)
            ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val songObj = obj["song"]?.jsonObjectOrNull() ?: return@mapNotNull null
            val song = parseNeteaseTrack(songObj, "") ?: return@mapNotNull null
            NeteasePlayRecordPayload(
                song = song,
                playCount = obj.stringOrNull("playCount")?.toIntOrNull() ?: 0,
                score = obj.stringOrNull("score")?.toIntOrNull() ?: 0,
            )
        }
    }

    private suspend fun requestCloudWithFallback(
        path: String,
        form: Map<String, String>,
        cookie: String,
        retry: Boolean,
    ): JsonObject {
        val attempts: List<suspend () -> JsonObject> = listOf(
            { NeteaseWireTransport.requestWeapi(path, form, cookie, retry = retry, forceWeapi = true) },
            { NeteaseWireTransport.requestApi(path, form, cookie, retry = retry) },
            { NeteaseWireTransport.requestWeapi(path, form, cookie, retry = retry, forceWeapi = false) },
        )
        var lastError: Throwable? = null
        for (attempt in attempts) {
            try {
                val root = attempt()
                NeteaseWriteApi.ensureWriteSuccess(root)
                return root
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
                lastError = err
                Log.w("NeteaseClient", "cloud request $path attempt failed: ${err.message}")
            }
        }
        throw lastError ?: NeteaseWriteException.ServerError(-1, "请求云盘失败")
    }

    /** 音乐云盘列表（分页）。 */
    suspend fun fetchCloudDiskTracks(
        offset: Int = 0,
        limit: Int = 100,
        cookie: String?,
    ): Result<NeteaseCloudDiskPagePayload> = withContext(Dispatchers.IO) {
        runCatching {
            if (cookie.isNullOrBlank()) throw NeteaseWriteException.LoginRequired("请先登录网易云账号")
            val root = requestCloudWithFallback(
                path = "/api/v1/cloud/get",
                form = mapOf("offset" to "$offset", "limit" to "$limit"),
                cookie = cookie,
                retry = true,
            )
            parseCloudDiskPage(root)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** 删除云盘歌曲。 */
    suspend fun deleteCloudTracks(
        songIds: List<Long>,
        cookie: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (cookie.isNullOrBlank()) throw NeteaseWriteException.LoginRequired("请先登录网易云账号")
            val cleanIds = songIds.distinct()
            if (cleanIds.isEmpty()) return@runCatching
            requestCloudWithFallback(
                path = "/api/cloud/del",
                form = mapOf("songIds" to "[${cleanIds.joinToString(",")}]"),
                cookie = cookie,
                retry = false,
            )
            Unit
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** 歌单分类列表。 */
    suspend fun fetchPlaylistCategories(cookie: String?): Result<NeteasePlaylistCatlistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/playlist/catalogue",
                    emptyMap(),
                    cookie,
                )
                parsePlaylistCatlist(root)
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 网友精选碟 / 歌单广场（按分类分页）。 */
    suspend fun fetchTopPlaylists(
        cat: String = "全部",
        order: String = "hot",
        offset: Int = 0,
        limit: Int = 30,
        cookie: String?,
    ): Result<NeteasePlaylistsPagePayload> = withContext(Dispatchers.IO) {
        runCatching {
            val root = NeteaseWireTransport.requestWeapi(
                "/api/playlist/list",
                mapOf(
                    "cat" to cat,
                    "order" to order,
                    "offset" to "$offset",
                    "limit" to "$limit",
                    "total" to "true",
                ),
                cookie,
            )
            parseTopPlaylistsPage(root)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** 精品歌单广场（按分类 & lasttime 游标分页）。 */
    suspend fun fetchHighQualityPlaylists(
        cat: String = "全部",
        lastTime: Long = 0L,
        limit: Int = 30,
        cookie: String?,
    ): Result<NeteasePlaylistsPagePayload> = withContext(Dispatchers.IO) {
        runCatching {
            val root = NeteaseWireTransport.requestWeapi(
                "/api/playlist/highquality/list",
                mapOf(
                    "cat" to cat,
                    "lasttime" to "$lastTime",
                    "limit" to "$limit",
                    "total" to "true",
                ),
                cookie,
            )
            parseHighQualityPlaylistsPage(root)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** 热搜榜详情。 */
    suspend fun fetchSearchHotDetail(cookie: String?): Result<List<NeteaseSearchHotItemPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/hotsearchlist/get",
                    emptyMap(),
                    cookie,
                )
                val parsed = parseSearchHotDetail(root)
                if (parsed.isNotEmpty()) {
                    parsed
                } else {
                    val fallback = NeteaseWireTransport.requestWeapi(
                        "/api/search/hot",
                        mapOf("type" to "1111"),
                        cookie,
                    )
                    parseSearchHotDetail(fallback)
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 实时搜索建议（mobile allMatch）。 */
    suspend fun fetchSearchSuggest(keyword: String, cookie: String?): Result<NeteaseSearchSuggestPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = keyword.trim()
                if (trimmed.isEmpty()) return@runCatching NeteaseSearchSuggestPayload()
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/search/suggest/keyword",
                    mapOf("s" to trimmed),
                    cookie,
                )
                parseSearchSuggest(root)
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 新碟上架。 */
    suspend fun fetchNewAlbums(
        area: String = "ALL",
        offset: Int = 0,
        limit: Int = 30,
        cookie: String?,
    ): Result<NeteaseNewAlbumsPagePayload> = withContext(Dispatchers.IO) {
        runCatching {
            val root = NeteaseWireTransport.requestWeapi(
                "/api/album/new",
                mapOf(
                    "area" to area,
                    "offset" to "$offset",
                    "limit" to "$limit",
                    "total" to "true",
                ),
                cookie,
            )
            parseNewAlbumsPage(root, offset)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** 热门歌手榜。 */
    suspend fun fetchTopArtists(
        offset: Int = 0,
        limit: Int = 50,
        cookie: String?,
    ): Result<NeteaseTopArtistsPagePayload> = withContext(Dispatchers.IO) {
        runCatching {
            val root = NeteaseWireTransport.requestWeapi(
                "/api/artist/top",
                mapOf("offset" to "$offset", "limit" to "$limit", "total" to "true"),
                cookie,
            )
            parseTopArtistsPage(root)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** 私人漫游垃圾桶（不再推荐）。 */
    suspend fun fmTrashTrack(
        songId: Long,
        timeSeconds: Long = 0L,
        cookie: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (cookie.isNullOrBlank()) throw NeteaseWriteException.LoginRequired("请先登录网易云账号")
            val root = NeteaseWireTransport.requestWeapi(
                "/api/radio/trash/add",
                mapOf("songId" to "$songId", "alg" to "RT", "time" to "$timeSeconds"),
                cookie,
                retry = false,
            )
            NeteaseWriteApi.ensureWriteSuccess(root)
            Unit
        }.onFailure { if (it is CancellationException) throw it }
    }

    internal fun parseCloudDiskPage(root: JsonObject): NeteaseCloudDiskPagePayload {
        val data = root["data"]?.jsonArrayOrNull().orEmpty()
        val tracks = data.mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val simpleSong = obj["simpleSong"]?.jsonObjectOrNull()
            val songId = simpleSong?.stringOrNull("id")?.toLongOrNull()
                ?: obj.stringOrNull("songId")?.toLongOrNull()
                ?: return@mapNotNull null
            val simpleAlbum = simpleSong?.get("al")?.jsonObjectOrNull()
                ?: simpleSong?.get("album")?.jsonObjectOrNull()
            val simpleDuration = simpleSong?.stringOrNull("dt")?.toLongOrNull()
                ?: simpleSong?.stringOrNull("duration")?.toLongOrNull()
                ?: 0L
            val simpleCover = simpleAlbum?.string("picUrl").orEmpty()
                .ifBlank { simpleAlbum?.string("blurPicUrl").orEmpty() }
            val parsed = simpleSong?.let { parseNeteaseTrack(it, "") } ?: NeteaseEntryPayload(
                songId = songId,
                title = obj.string("songName").ifBlank { obj.string("fileName").substringBeforeLast('.') }.ifBlank { "云盘曲目" },
                artists = obj.string("artist").ifBlank { "未知歌手" },
                album = obj.string("album").ifBlank { simpleAlbum?.string("name").orEmpty() }.ifBlank { "音乐云盘" },
                durationMs = simpleDuration,
                coverUrl = simpleCover,
            )
            NeteaseCloudDiskTrackPayload(
                song = parsed,
                fileName = obj.string("fileName"),
                fileSizeBytes = obj.stringOrNull("fileSize")?.toLongOrNull() ?: 0L,
                addTimeMs = obj.stringOrNull("addTime")?.toLongOrNull() ?: 0L,
            )
        }
        return NeteaseCloudDiskPagePayload(
            tracks = tracks,
            count = root.stringOrNull("count")?.toIntOrNull() ?: tracks.size,
            sizeBytes = root.stringOrNull("size")?.toLongOrNull() ?: 0L,
            maxSizeBytes = root.stringOrNull("maxSize")?.toLongOrNull() ?: 0L,
            hasMore = root.stringOrNull("hasMore")?.toBooleanStrictOrNull() ?: false,
        )
    }

    internal fun parsePlaylistCatlist(root: JsonObject): NeteasePlaylistCatlistPayload {
        val allName = root["all"]?.jsonObjectOrNull()?.string("name")?.ifBlank { "全部" } ?: "全部"
        val categories = root["sub"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val name = obj.string("name").ifBlank { return@mapNotNull null }
            NeteasePlaylistCategoryPayload(
                name = name,
                category = obj.stringOrNull("category")?.toIntOrNull() ?: 0,
                hot = obj.stringOrNull("hot")?.toBooleanStrictOrNull() ?: false,
            )
        }
        return NeteasePlaylistCatlistPayload(allCategory = allName, categories = categories)
    }

    internal fun parseTopPlaylistsPage(root: JsonObject): NeteasePlaylistsPagePayload {
        val playlists = root["playlists"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("id").ifBlank { return@mapNotNull null }
            val name = obj.string("name").ifBlank { return@mapNotNull null }
            val creator = obj["creator"]?.jsonObjectOrNull()?.string("nickname").orEmpty()
            NeteasePlaylistPayload(
                id = id,
                name = name,
                coverUrl = obj.string("coverImgUrl").ifBlank { obj.string("picUrl") },
                entries = emptyList(),
                description = obj.string("description").ifBlank { creator },
                remoteTrackCount = obj.stringOrNull("trackCount")?.toIntOrNull() ?: 0,
            )
        }
        val hasMore = root.stringOrNull("more")?.toBooleanStrictOrNull() ?: false
        val total = root.stringOrNull("total")?.toIntOrNull() ?: playlists.size
        return NeteasePlaylistsPagePayload(playlists = playlists, hasMore = hasMore, total = total)
    }

    internal fun parseHighQualityPlaylistsPage(root: JsonObject): NeteasePlaylistsPagePayload {
        val playlists = root["playlists"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("id").ifBlank { return@mapNotNull null }
            val name = obj.string("name").ifBlank { return@mapNotNull null }
            NeteasePlaylistPayload(
                id = id,
                name = name,
                coverUrl = obj.string("coverImgUrl").ifBlank { obj.string("picUrl") },
                entries = emptyList(),
                description = obj.string("copywriter").ifBlank { obj.string("description") },
                remoteTrackCount = obj.stringOrNull("trackCount")?.toIntOrNull() ?: 0,
            )
        }
        val hasMore = root.stringOrNull("more")?.toBooleanStrictOrNull() ?: false
        val total = root.stringOrNull("total")?.toIntOrNull() ?: playlists.size
        val lastTime = root.stringOrNull("lasttime")?.toLongOrNull() ?: 0L
        return NeteasePlaylistsPagePayload(
            playlists = playlists,
            hasMore = hasMore,
            total = total,
            lastTime = lastTime,
        )
    }

    internal fun parseSearchHotDetail(root: JsonObject): List<NeteaseSearchHotItemPayload> {
        val dataList = root["data"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val word = obj.string("searchWord").ifBlank { return@mapNotNull null }
            NeteaseSearchHotItemPayload(
                keyword = word,
                score = obj.stringOrNull("score")?.toLongOrNull() ?: 0L,
                content = obj.string("content"),
                iconType = obj.stringOrNull("iconType")?.toIntOrNull() ?: 0,
            )
        }
        if (dataList.isNotEmpty()) return dataList
        return root["result"]?.jsonObjectOrNull()?.get("hots")?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val word = obj.string("first").ifBlank { return@mapNotNull null }
            NeteaseSearchHotItemPayload(keyword = word)
        }
    }

    internal fun parseSearchSuggest(root: JsonObject): NeteaseSearchSuggestPayload {
        val result = root["result"]?.jsonObjectOrNull() ?: return NeteaseSearchSuggestPayload()
        val keywords = LinkedHashSet<String>()
        result["allMatch"]?.jsonArrayOrNull().orEmpty().forEach { elem ->
            elem.jsonObjectOrNull()?.string("keyword")?.trim()?.takeIf(String::isNotBlank)?.let(keywords::add)
        }
        for (key in listOf("songs", "artists", "albums", "playlists")) {
            result[key]?.jsonArrayOrNull().orEmpty().forEach { elem ->
                elem.jsonObjectOrNull()?.string("name")?.trim()?.takeIf(String::isNotBlank)?.let(keywords::add)
            }
        }
        return NeteaseSearchSuggestPayload(keywords = keywords.toList())
    }

    internal fun parseNewAlbumsPage(root: JsonObject, offset: Int): NeteaseNewAlbumsPagePayload {
        val albums = root["albums"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("id").ifBlank { return@mapNotNull null }
            val name = obj.string("name").ifBlank { return@mapNotNull null }
            val artistName = (obj["artists"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonObjectOrNull()?.string("name")?.takeIf(String::isNotBlank) }
                .joinToString(" / ")
                .ifBlank { obj["artist"]?.jsonObjectOrNull()?.string("name").orEmpty() }
            NeteaseArtistAlbumPayload(
                id = id,
                name = name,
                artistName = artistName,
                coverUrl = obj.string("picUrl").ifBlank { obj.string("blurPicUrl") },
                trackCount = obj.stringOrNull("size")?.toIntOrNull() ?: 0,
                publishTimeMs = obj.stringOrNull("publishTime")?.toLongOrNull() ?: 0L,
            )
        }
        val total = root.stringOrNull("total")?.toIntOrNull() ?: albums.size
        val hasMore = albums.isNotEmpty() && offset + albums.size < total
        return NeteaseNewAlbumsPagePayload(albums = albums, total = total, hasMore = hasMore)
    }

    internal fun parseTopArtistsPage(root: JsonObject): NeteaseTopArtistsPagePayload {
        val artists = root["artists"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("id").ifBlank { return@mapNotNull null }
            val name = obj.string("name").ifBlank { return@mapNotNull null }
            NeteaseTopArtistPayload(
                id = id,
                name = name,
                coverUrl = obj.string("picUrl").ifBlank { obj.string("img1v1Url") },
                musicSize = obj.stringOrNull("musicSize")?.toIntOrNull() ?: 0,
                albumSize = obj.stringOrNull("albumSize")?.toIntOrNull() ?: 0,
            )
        }
        val hasMore = root.stringOrNull("more")?.toBooleanStrictOrNull() ?: false
        return NeteaseTopArtistsPagePayload(artists = artists, hasMore = hasMore)
    }

    /** 登录用户“我喜欢的音乐”的摘要：首页只展示封面/名称/数量，不拉整份歌曲列表。 */
    suspend fun fetchLikedPlaylistSummary(cookie: String?): Result<LikedPlaylistSummary?> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cookie.isNullOrBlank()) error("需要登录网易云")
                // 账号与歌单接口都要走 weapi；原生接口会返回 -460/空账号。
                val account = NeteaseWireTransport.requestWeapi("/api/nuser/account/get", emptyMap(), cookie)
                val uid = account["account"]?.jsonObjectOrNull()?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: account["profile"]?.jsonObjectOrNull()?.get("userId")?.jsonPrimitive?.contentOrNull
                Log.d("NeteaseClient", "hb account uid=$uid")
                if (uid.isNullOrBlank()) error("无法获取网易云账号")
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/user/playlist",
                    mapOf("uid" to uid, "offset" to "0", "limit" to "1000", "includeVideo" to "true"),
                    cookie,
                )
                val playlists = root["playlist"]?.jsonArrayOrNull().orEmpty().mapNotNull { it.jsonObjectOrNull() }
                Log.d("NeteaseClient", "hb playlists=${playlists.size}")
                // 只认 specialType=5；“第一个数字 ID”会选中私人雷达等接口不支持的特殊歌单。
                val liked = playlists.firstOrNull { it.stringOrNull("specialType") == "5" }
                Log.d(
                    "NeteaseClient",
                    "hb liked id=${liked?.stringOrNull("id")} total=${playlists.size}",
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
            val root = NeteaseWireTransport.requestWeapi("/api/playmode/intelligence/list", form, cookie)
            val entries = parseHeartbeatEntries(root, songId)
            Log.d(
                "NeteaseClient",
                "hb weapi code=${root.string("code")} entries=${entries.size} " +
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

    internal data class Toplist(
        val id: String,
        val name: String,
        val coverUrl: String,
        val updateFrequency: String,
    )

    /** 排行榜：只要榜单元数据，点开后按普通歌单详情加载。 */
    suspend fun fetchToplists(cookie: String?): Result<List<Toplist>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = NeteaseWireTransport.request("/api/toplist/detail", emptyMap(), cookie)
            root["list"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
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
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/homepage/block/page",
                    // 参考实现只有 refresh（默认 false）与翻页用的 cursor；首屏不能带 cursor=0。
                    mapOf("refresh" to "false"),
                    cookie,
                )
                val blocks = root["data"]?.jsonObjectOrNull()?.get("blocks")?.jsonArrayOrNull().orEmpty()
                    .mapNotNull { it.jsonObjectOrNull() }
                if (blocks.isEmpty()) {
                    error("首页区块为空（code=${root.string("code")}，需要登录网易云）")
                }
                Log.d(
                    "NeteaseClient",
                    "blocks code=${root.string("code")} blocks=${blocks.map { it.string("blockCode") }}",
                )
                blocks.map(::parseHomeBlock)
            }
        }

    /** 每日推荐歌曲（按账号口味），首页“根据你喜爱的歌曲推荐”使用；afresh=true 时强制刷新。 */
    suspend fun fetchRecommendSongs(
        cookie: String?,
        limit: Int = 30,
        afresh: Boolean = false,
    ): Result<List<NeteaseEntryPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestWeapi(
                    "/api/v3/discovery/recommend/songs",
                    mapOf("afresh" to if (afresh) "true" else "false"),
                    cookie,
                )
                val songs = root["data"]?.jsonObjectOrNull()?.get("dailySongs")?.jsonArrayOrNull().orEmpty()
                    .mapNotNull { element -> element.jsonObjectOrNull()?.let { parseNeteaseTrack(it, "") } }
                    .distinctBy { it.songId }
                    .take(limit)
                Log.d("NeteaseClient", "recSongs code=${root.string("code")} entries=${songs.size} afresh=$afresh")
                if (songs.isEmpty()) {
                    error("每日推荐歌曲为空（code=${root.string("code")}，需要登录网易云）")
                }
                songs
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** 新歌：列表项的 song 字段是完整歌曲对象，外层 picUrl 只作封面兜底。 */
    suspend fun fetchNewSongs(cookie: String?, limit: Int = 12): Result<List<NeteaseEntryPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.request(
                    "/api/personalized/newsong",
                    mapOf("type" to "new", "limit" to "$limit", "areaId" to "0"),
                    cookie,
                )
                root["result"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
                    val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val song = obj["song"]?.jsonObjectOrNull() ?: obj
                    parseNeteaseTrack(song, obj.string("picUrl"))
                }.distinctBy { it.songId }
            }
        }

    /** 推荐歌单：登录后的“每日推荐歌单”，按账号口味生成（网页版推荐池不相关，不用）。 */
    suspend fun fetchRecommendedResources(cookie: String?, limit: Int = 30): Result<List<NeteasePlaylistPayload>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = NeteaseWireTransport.requestWeapi("/api/v1/discovery/recommend/resource", emptyMap(), cookie)
                val items = root["recommend"]?.jsonArrayOrNull().orEmpty()
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
                val root = NeteaseWireTransport.requestWeapi("/api/personalized/playlist", mapOf("limit" to "$limit"), cookie)
                root["result"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
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
            val root = NeteaseWireTransport.request(
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
                mergeNeteaseTranslation(lrc, tlyric),
                mergeNeteaseTranslation(klyric, tlyric),
                mergeNeteaseTranslation(yrcToLrc(yrc), tlyric),
            ).filter { it.isNotBlank() }
            if (candidates.isEmpty()) error("没有歌词")
            // 同一首歌不同字段完整度不同：取带时间轴行数最多的一份。
            candidates.maxByOrNull { content -> content.lineSequence().count { it.startsWith("[") && it.getOrNull(1)?.isDigit() == true } }
                ?: error("没有歌词")
        }
    }

    /** 下载到目标文件；每 64KB 检查一次协程取消。 */
    suspend fun download(url: String, cookie: String?, target: File): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .neteaseHeaders(cookie)
                    .get()
                    .build()
                TuneHttp.client(connectTimeoutMs = 10_000L, readTimeoutMs = 30_000L)
                    .executeCancellable(request) { response ->
                        if (!response.isSuccessful) error("下载失败（HTTP ${response.code}）")
                        val body = response.body ?: error("下载内容为空")
                        target.parentFile?.mkdirs()
                        val temp = File(target.parentFile, target.name + ".part")
                        body.byteStream().use { input ->
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
                    }
            }
        }

    /** 流式下载到调用方提供的输出流（供私有文件与 SAF DocumentFile 共用），支持进度回调与协程取消。 */
    suspend fun downloadToOutput(
        url: String,
        cookie: String?,
        expectedBytes: Long = 0L,
        onProgress: (loadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        openOutput: () -> OutputStream,
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .neteaseHeaders(cookie)
                    .get()
                    .build()
                TuneHttp.client(connectTimeoutMs = 10_000L, readTimeoutMs = 30_000L)
                    .executeCancellable(request) { response ->
                        if (!response.isSuccessful) error("下载失败（HTTP ${response.code}）")
                        val body = response.body ?: error("下载内容为空")
                        val contentLength = body.contentLength().takeIf { it > 0L } ?: expectedBytes.coerceAtLeast(0L)
                        var totalRead = 0L
                        var lastCallbackAt = 0L
                        body.byteStream().use { input ->
                            openOutput().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    totalRead += read
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastCallbackAt >= 180L) {
                                        lastCallbackAt = now
                                        onProgress(totalRead, contentLength)
                                    }
                                }
                            }
                        }
                        check(totalRead > 0L) { "下载内容为空" }
                        onProgress(totalRead, contentLength.coerceAtLeast(totalRead))
                        totalRead
                    }
            }
        }

    /** 扫码登录：取 unikey 与下发的 NMTID，二维码内容为网页登录链接。 */
    suspend fun createQrLogin(cookie: String? = null): Result<NeteaseLoginQr> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, setCookies) = NeteaseWireTransport.requestWithCookies(
                "/api/login/qrcode/unikey",
                mapOf("type" to "3"),
                cookie,
                base = NeteaseWireTransport.QR_BASE,
            )
            val key = root.string("unikey").ifBlank { error("无法获取登录二维码") }
            val sessionCookie = NeteaseWireTransport.mergeCookies(cookie, NeteaseWireTransport.joinCookies(setCookies))
            Log.d("NeteaseAuth", "qr unikey nmtid=${sessionCookie?.contains("NMTID=") == true} code=${root.string("code")}")
            NeteaseLoginQr(key = key, url = "https://music.163.com/login?codekey=$key", cookie = sessionCookie)
        }.onFailure { Log.w("NeteaseAuth", "qr unikey failed: ${it.message}") }
    }

    /** 轮询扫码状态：800 过期 / 801 等待 / 802 已扫待确认 / 803 成功。 */
    suspend fun pollQrLogin(key: String, cookie: String?): Result<NeteaseQrStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (root, setCookies) = NeteaseWireTransport.requestWithCookies(
                    "/api/login/qrcode/client/login",
                    mapOf("key" to key, "type" to "3"),
                    cookie,
                    base = NeteaseWireTransport.QR_BASE,
                )
                val code = root.string("code").toIntOrNull() ?: 800
                val message = root.string("message")
                val session = NeteaseWireTransport.mergeCookies(cookie, NeteaseWireTransport.joinCookies(setCookies))
                val verifyUrl = root["verifyUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                Log.d(
                    "NeteaseAuth",
                    "qr poll code=$code setCookie=${setCookies.size} " +
                        "musicU=${session?.contains("MUSIC_U=") == true} verifyUrl=${verifyUrl != null}",
                )
                NeteaseQrStatus(code = code, message = message, cookie = session, verifyUrl = verifyUrl)
            }.onFailure { Log.w("NeteaseAuth", "qr poll failed: ${it.message}") }
        }

    data class NeteasePhoneLogin(
        val code: Int,
        val message: String,
        val cookie: String?,
    )

    data class SmsCodeSent(
        val code: Int,
        val message: String,
        val cookie: String?,
    )

    /** 发送短信验证码；下发的 cookie（NMTID 等）要带回登录请求。 */
    suspend fun sendSmsCode(
        phone: String,
        countryCode: String,
        cookie: String?,
    ): Result<SmsCodeSent> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, setCookies) = NeteaseWireTransport.requestWithCookies(
                path = "/api/sms/captcha/sent",
                form = mapOf("cellphone" to phone, "ctcode" to countryCode),
                cookie = cookie,
            )
            val code = root.stringOrNull("code")?.toIntOrNull() ?: -1
            val merged = NeteaseWireTransport.mergeCookieHeader(cookie, setCookies, null)
            Log.d("NeteaseAuth", "sms code=$code hasNmtid=${merged?.contains("NMTID=") == true}")
            SmsCodeSent(
                code = code,
                message = phoneAuthMessage(code, root),
                cookie = merged,
            )
        }
    }

    /** 短信验证码登录：/api/w/login/cellphone，响应可能把 cookie 放在 body。 */
    suspend fun loginBySmsCode(
        phone: String,
        countryCode: String,
        captcha: String,
        cookie: String?,
    ): Result<NeteasePhoneLogin> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, setCookies) = NeteaseWireTransport.requestWithCookies(
                path = "/api/w/login/cellphone",
                form = mapOf(
                    "phone" to phone,
                    "countrycode" to countryCode,
                    "remember" to "true",
                    "type" to "1",
                    "captcha" to captcha,
                ),
                cookie = cookie,
            )
            val code = root.stringOrNull("code")?.toIntOrNull() ?: -1
            val bodyCookie = root.stringOrNull("cookie")
            val merged = NeteaseWireTransport.mergeCookieHeader(cookie, setCookies, bodyCookie)
            Log.d("NeteaseAuth", "smsLogin code=$code session=${merged?.contains("MUSIC_U=") == true}")
            NeteasePhoneLogin(
                code = code,
                message = phoneAuthMessage(code, root),
                cookie = merged?.takeIf { it.contains("MUSIC_U=") },
            )
        }
    }

    /** 手机号 + 密码登录；走当前线格式（激进模式即 eapi + 客户端身份）。 */
    suspend fun loginCellphone(
        phone: String,
        password: String,
        countryCode: String = "86",
    ): Result<NeteasePhoneLogin> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, setCookies) = NeteaseWireTransport.requestWithCookies(
                path = "/api/login/cellphone",
                form = mapOf(
                    "phone" to phone,
                    "countrycode" to countryCode,
                    "password" to NeteaseWireTransport.md5Hex(password),
                    "rememberLogin" to "true",
                ),
                cookie = null,
            )
            val code = root.stringOrNull("code")?.toIntOrNull() ?: -1
            val message = root.stringOrNull("message")
                ?: root.stringOrNull("msg")
                ?: root.stringOrNull("detail")
                ?: ""
            val joined = NeteaseWireTransport.joinCookies(setCookies)
            Log.d("NeteaseAuth", "login code=$code session=${joined?.contains("MUSIC_U=") == true}")
            NeteasePhoneLogin(
                code = code,
                message = message,
                cookie = joined?.takeIf { it.contains("MUSIC_U=") },
            )
        }
    }

    data class NeteaseSongComment(
        val commentId: Long,
        val userName: String,
        val avatarUrl: String,
        val content: String,
        val likedCount: Int,
        val timeStr: String,
        val timeMs: Long,
        val ipLocation: String,
        val repliedUser: String? = null,
        val repliedContent: String? = null,
    )

    data class NeteaseSongCommentsPage(
        val hotComments: List<NeteaseSongComment>,
        val comments: List<NeteaseSongComment>,
        val totalCount: Int,
        val hasMore: Boolean,
    )

    /** 拉取网易云单曲评论（热门评论 + 最新评论分页），全程在 IO 线程并带超时。 */
    suspend fun fetchSongComments(
        songId: Long,
        limit: Int = 20,
        offset: Int = 0,
        beforeTime: Long = 0L,
        cookie: String?,
    ): Result<NeteaseSongCommentsPage> = withContext(Dispatchers.IO) {
        runCatching {
            val rid = "R_SO_4_$songId"
            val root = NeteaseWireTransport.requestApi(
                path = "/api/v1/resource/comments/$rid",
                form = mapOf(
                    "rid" to rid,
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "beforeTime" to beforeTime.toString(),
                ),
                cookie = cookie,
            )
            val code = root.stringOrNull("code")?.toIntOrNull()
            if (code != null && code != 200) {
                val msg = root.stringOrNull("message") ?: root.stringOrNull("msg") ?: "code=$code"
                error("获取评论失败（$msg）")
            }
            val hotComments = root["hotComments"]?.jsonArrayOrNull().orEmpty().mapNotNull {
                parseNeteaseComment(it.jsonObjectOrNull() ?: return@mapNotNull null)
            }
            val comments = root["comments"]?.jsonArrayOrNull().orEmpty().mapNotNull {
                parseNeteaseComment(it.jsonObjectOrNull() ?: return@mapNotNull null)
            }
            val totalCount = root.stringOrNull("total")?.toIntOrNull()
                ?: maxOf(hotComments.size, comments.size)
            val hasMore = root["more"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            NeteaseSongCommentsPage(
                hotComments = hotComments,
                comments = comments,
                totalCount = totalCount,
                hasMore = hasMore,
            )
        }.onFailure { err ->
            if (err is CancellationException) throw err
            Log.w("NeteaseClient", "fetchSongComments failed songId=$songId err=${err.message}")
        }
    }

    private fun parseNeteaseComment(obj: JsonObject): NeteaseSongComment? {
        val commentId = obj["commentId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val content = obj.string("content").trim()
        if (content.isBlank()) return null
        val user = obj["user"]?.jsonObjectOrNull()
        val userName = user?.string("nickname").orEmpty().ifBlank { "网易云用户" }
        val avatarUrl = user?.string("avatarUrl").orEmpty()
        val likedCount = obj["likedCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val timeMs = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val timeStr = obj.string("timeStr")
        val ipLocation = obj["ipLocation"]?.jsonObjectOrNull()?.string("location").orEmpty()
        val replied = obj["beReplied"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
        val repliedUser = replied?.get("user")?.jsonObjectOrNull()?.string("nickname")?.takeIf { it.isNotBlank() }
        val repliedContent = replied?.string("content")?.trim()?.takeIf { it.isNotBlank() }
        return NeteaseSongComment(
            commentId = commentId,
            userName = userName,
            avatarUrl = avatarUrl,
            content = content,
            likedCount = likedCount,
            timeStr = timeStr,
            timeMs = timeMs,
            ipLocation = ipLocation,
            repliedUser = repliedUser,
            repliedContent = repliedContent,
        )
    }
}
