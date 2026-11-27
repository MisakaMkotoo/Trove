package com.exodidio.tune.netease

import android.util.Log
import com.exodidio.tune.player.PlaybackEndReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal sealed class NeteaseWriteException(message: String) : IllegalStateException(message) {
    class LoginRequired(message: String = "登录已失效，请重新登录网易云") : NeteaseWriteException(message)
    class ServerError(val code: Int, message: String) : NeteaseWriteException(message)
}

internal data class CreatedNeteasePlaylist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val description: String = "",
    val privacyPrivate: Boolean = false,
    val trackCount: Int = 0,
    val creatorId: String = "",
)

/** 网易云写入接口（喜欢/取消喜欢、歌单创建/编辑/删除/增删曲目/收藏）：所有网络请求均在 IO 线程执行且带超时/不可盲目重试。 */
internal object NeteaseWriteApi {
    suspend fun fetchLikeList(uid: String, cookie: String?): Result<Set<Long>> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireCookie(cookie)
                if (uid.isBlank()) throw NeteaseWriteException.LoginRequired("无法获取网易云账号")
                val root = NeteaseWireTransport.requestJson(
                    path = "/api/song/like/get",
                    data = buildJsonObject { put("uid", uid) },
                    cookie = cookie,
                    retry = true,
                )
                val ids = parseLikeListResponse(root)
                Log.d("NeteaseWrite", "likelist uid=$uid count=${ids.size}")
                ids
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun likeTrack(
        songId: Long,
        like: Boolean,
        cookie: String?,
        fallbackPlaylistId: String? = null,
    ): Result<Long?> =
        withContext(Dispatchers.IO) {
            val validCookie = try {
                requireCookie(cookie)
            } catch (err: Throwable) {
                return@withContext Result.failure(err)
            }
            val form = mapOf(
                "trackId" to songId.toString(),
                "like" to like.toString(),
                "alg" to "itembased",
                "time" to "3",
            )
            executeLikeTrackWithFallback(
                songId = songId,
                like = like,
                fallbackPlaylistId = fallbackPlaylistId,
                primaryLike = {
                    val root = NeteaseWireTransport.requestApi(
                        path = "/api/radio/like",
                        form = form,
                        cookie = validCookie,
                        retry = false,
                    )
                    parseLikeTrackResponse(root)
                },
                weapiLike = {
                    val root = NeteaseWireTransport.requestWeapi(
                        path = "/api/radio/like",
                        data = form,
                        cookie = validCookie,
                        retry = false,
                        forceWeapi = true,
                    )
                    parseLikeTrackResponse(root)
                },
                playlistFallback = { pid ->
                    manipulatePlaylistTracks(
                        playlistId = pid,
                        trackIds = listOf(songId),
                        add = like,
                        cookie = validCookie,
                    ).getOrThrow()
                    pid.toLongOrNull()
                },
            )
        }

    internal suspend fun executeLikeTrackWithFallback(
        songId: Long,
        like: Boolean,
        fallbackPlaylistId: String?,
        primaryLike: suspend () -> Long?,
        weapiLike: suspend () -> Long?,
        playlistFallback: suspend (playlistId: String) -> Long?,
    ): Result<Long?> = runCatching {
        try {
            val playlistId = primaryLike()
            Log.d("NeteaseWrite", "like api song=$songId like=$like playlistId=$playlistId")
            playlistId
        } catch (primaryErr: Throwable) {
            if (primaryErr is CancellationException) throw primaryErr
            if (isRateLimitError(primaryErr)) {
                val fallbackPid = fallbackPlaylistId?.takeIf(String::isNotBlank) ?: throw primaryErr
                Log.w("NeteaseWrite", "like api rate-limited (405) song=$songId, falling back to liked playlist pid=$fallbackPid")
                return@runCatching playlistFallback(fallbackPid)
            }
            if (!isRiskControlError(primaryErr)) throw primaryErr
            Log.w("NeteaseWrite", "like api risk-control song=$songId code=${(primaryErr as? NeteaseWriteException.ServerError)?.code}, trying weapi")
            try {
                val playlistId = weapiLike()
                Log.d("NeteaseWrite", "like weapi song=$songId like=$like playlistId=$playlistId")
                playlistId
            } catch (weapiErr: Throwable) {
                if (weapiErr is CancellationException) throw weapiErr
                if (!isRiskControlError(weapiErr) && !isRateLimitError(weapiErr)) throw weapiErr
                val fallbackPid = fallbackPlaylistId?.takeIf(String::isNotBlank) ?: throw weapiErr
                Log.w("NeteaseWrite", "like weapi failed (${(weapiErr as? NeteaseWriteException.ServerError)?.code}) song=$songId, falling back to liked playlist pid=$fallbackPid")
                playlistFallback(fallbackPid)
            }
        }
    }.onFailure { err ->
        if (err is CancellationException) throw err
        Log.w("NeteaseWrite", "like failed song=$songId like=$like err=${err.message}")
    }

    private fun isRiskControlError(err: Throwable): Boolean =
        err is NeteaseWriteException.ServerError && err.code in setOf(-460, -462, 460, 462)

    private fun isRateLimitError(err: Throwable): Boolean =
        err is NeteaseWriteException.ServerError && err.code == 405

    suspend fun createPlaylist(
        name: String,
        description: String = "",
        privacyPrivate: Boolean = false,
        cookie: String?,
    ): Result<CreatedNeteasePlaylist> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val trimmedName = name.trim()
                require(trimmedName.isNotEmpty()) { "歌单名称不能为空" }
                val root = NeteaseWireTransport.requestJson(
                    path = "/api/playlist/create",
                    data = buildJsonObject {
                        put("name", trimmedName)
                        put("privacy", if (privacyPrivate) "10" else "0")
                        put("type", "NORMAL")
                    },
                    cookie = validCookie,
                    retry = false,
                )
                val created = parseCreatePlaylistResponse(root, trimmedName, privacyPrivate)
                val trimmedDesc = description.trim()
                val finalPlaylist = if (trimmedDesc.isNotEmpty()) {
                    val descRes = updatePlaylistDescription(created.id, trimmedDesc, validCookie)
                    if (descRes.isSuccess) {
                        created.copy(description = trimmedDesc)
                    } else {
                        Log.w("NeteaseWrite", "createPlaylist desc update failed id=${created.id}: ${descRes.exceptionOrNull()?.message}")
                        created
                    }
                } else {
                    created
                }
                Log.d("NeteaseWrite", "createPlaylist id=${finalPlaylist.id} name=${finalPlaylist.name} private=$privacyPrivate")
                finalPlaylist
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun updatePlaylistName(
        playlistId: String,
        name: String,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val trimmedName = name.trim()
                require(trimmedName.isNotEmpty()) { "歌单名称不能为空" }
                val root = NeteaseWireTransport.requestJson(
                    path = "/api/playlist/update/name",
                    data = buildJsonObject {
                        put("id", playlistId)
                        put("name", trimmedName)
                    },
                    cookie = validCookie,
                    retry = false,
                )
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "updatePlaylistName id=$playlistId")
                Unit
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun updatePlaylistDescription(
        playlistId: String,
        description: String,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val root = NeteaseWireTransport.requestJson(
                    path = "/api/playlist/desc/update",
                    data = buildJsonObject {
                        put("id", playlistId)
                        put("desc", description.trim())
                    },
                    cookie = validCookie,
                    retry = false,
                )
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "updatePlaylistDescription id=$playlistId")
                Unit
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun deletePlaylist(
        playlistId: String,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val root = NeteaseWireTransport.requestJson(
                    path = "/api/playlist/remove",
                    data = buildJsonObject {
                        put("ids", "[$playlistId]")
                    },
                    cookie = validCookie,
                    retry = false,
                )
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "deletePlaylist id=$playlistId")
                Unit
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun manipulatePlaylistTracks(
        playlistId: String,
        trackIds: List<Long>,
        add: Boolean,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val uniqueIds = trackIds.distinct()
                if (uniqueIds.isEmpty()) return@runCatching
                uniqueIds.chunked(100).forEach { chunk ->
                    currentCoroutineContext().ensureActive()
                    val root = NeteaseWireTransport.requestJson(
                        path = "/api/playlist/manipulate/tracks",
                        data = buildJsonObject {
                            put("op", if (add) "add" else "del")
                            put("pid", playlistId)
                            put("trackIds", "[${chunk.joinToString(",")}]")
                            put("imme", "true")
                        },
                        cookie = validCookie,
                        retry = false,
                    )
                    parseManipulateTracksResponse(root, add)
                }
                Log.d("NeteaseWrite", "manipulateTracks pid=$playlistId add=$add count=${uniqueIds.size}")
                Unit
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun updatePlaylistTrackOrder(
        playlistId: String,
        orderedTrackIds: List<Long>,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val uniqueIds = orderedTrackIds.distinct()
                if (uniqueIds.isEmpty()) return@runCatching
                val root = NeteaseWireTransport.requestWeapi(
                    path = "/api/playlist/manipulate/tracks",
                    data = mapOf(
                        "pid" to playlistId,
                        "trackIds" to "[${uniqueIds.joinToString(",")}]",
                        "op" to "update",
                    ),
                    cookie = validCookie,
                    retry = false,
                    forceWeapi = true,
                )
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "updateTrackOrder pid=$playlistId count=${uniqueIds.size}")
                Unit
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun updatePlaylistPrivacy(
        playlistId: String,
        privacyPrivate: Boolean,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val root = NeteaseWireTransport.requestWeapi(
                    path = "/api/playlist/privacy",
                    data = mapOf(
                        "id" to playlistId,
                        "privacy" to if (privacyPrivate) "10" else "0",
                    ),
                    cookie = validCookie,
                    retry = false,
                    forceWeapi = true,
                )
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "updatePrivacy id=$playlistId private=$privacyPrivate")
                Unit
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun subscribePlaylist(
        playlistId: String,
        subscribe: Boolean,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val path = if (subscribe) "/api/playlist/subscribe" else "/api/playlist/unsubscribe"
                val root = NeteaseWireTransport.requestWeapi(
                    path = path,
                    data = mapOf("id" to playlistId),
                    cookie = validCookie,
                    retry = false,
                    forceWeapi = true,
                )
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "subscribePlaylist id=$playlistId subscribe=$subscribe via=weapi")
                Unit
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w("NeteaseWrite", "subscribePlaylist failed id=$playlistId subscribe=$subscribe err=${err.message}")
            }
        }

    suspend fun subscribeArtist(
        artistId: String,
        subscribe: Boolean,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val path = if (subscribe) "/api/artist/sub" else "/api/artist/unsub"
                val root = NeteaseWireTransport.requestWeapi(
                    path = path,
                    data = mapOf(
                        "artistId" to artistId,
                        "artistIds" to "[$artistId]",
                    ),
                    cookie = validCookie,
                    retry = false,
                    forceWeapi = true,
                )
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "subscribeArtist id=$artistId subscribe=$subscribe")
                Unit
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w("NeteaseWrite", "subscribeArtist failed id=$artistId subscribe=$subscribe err=${err.message}")
            }
        }

    suspend fun subscribeAlbum(
        albumId: String,
        subscribe: Boolean,
        cookie: String?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val path = if (subscribe) "/api/album/sub" else "/api/album/unsub"
                val root = NeteaseWireTransport.requestWeapi(
                    path = path,
                    data = mapOf("id" to albumId),
                    cookie = validCookie,
                    retry = false,
                    forceWeapi = true,
                )
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "subscribeAlbum id=$albumId subscribe=$subscribe")
                Unit
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w("NeteaseWrite", "subscribeAlbum failed id=$albumId subscribe=$subscribe err=${err.message}")
            }
        }

    internal fun buildPlaybackWeblogLogsJson(
        songId: Long,
        start: Boolean,
        startLogTimeMs: Long,
        logTimeMs: Long,
        playedSeconds: Long = 0L,
        endReason: String = "ui",
        sourceId: String = "",
        source: String = "list",
    ): String {
        val resolvedSourceId = sourceId.ifBlank { songId.toString() }
        val resolvedSource = source.ifBlank { "list" }
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("action", if (start) "startplay" else "play")
                    put(
                        "json",
                        buildJsonObject {
                            put("id", songId)
                            put("type", "song")
                            put("sourceId", resolvedSourceId)
                            put("source", resolvedSource)
                            put("sourcetype", resolvedSource)
                            put("content", "id=$resolvedSourceId")
                            put("startlogtime", startLogTimeMs)
                            put("logtime", logTimeMs)
                            if (!start) {
                                put("download", 0)
                                put("end", endReason.ifBlank { "ui" })
                                put("time", playedSeconds.coerceAtLeast(0L))
                                put("wifi", 0)
                            }
                        },
                    )
                },
            )
        }.toString()
    }

    suspend fun reportPlaybackWeblog(
        songId: Long,
        start: Boolean,
        startLogTimeMs: Long,
        logTimeMs: Long,
        cookie: String?,
        playedSeconds: Long = 0L,
        endReason: String = "ui",
        sourceId: String = "",
        source: String = "list",
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val validCookie = requireCookie(cookie)
                val logsJson = buildPlaybackWeblogLogsJson(
                    songId = songId,
                    start = start,
                    startLogTimeMs = startLogTimeMs,
                    logTimeMs = logTimeMs,
                    playedSeconds = playedSeconds,
                    endReason = endReason,
                    sourceId = sourceId,
                    source = source,
                )
                val data = mapOf("logs" to logsJson)
                val root = withTimeoutOrNull(8_000L) {
                    try {
                        NeteaseWireTransport.requestWeapi(
                            path = "/api/feedback/weblog",
                            data = data,
                            cookie = validCookie,
                            retry = false,
                        )
                    } catch (primaryErr: Throwable) {
                        if (primaryErr is CancellationException) throw primaryErr
                        val code = (primaryErr as? NeteaseWriteException.ServerError)?.code
                        if (code in setOf(-460, -462, 403, 460, 462)) {
                            NeteaseWireTransport.requestWeapi(
                                path = "/api/feedback/weblog",
                                data = data,
                                cookie = validCookie,
                                retry = false,
                                forceWeapi = true,
                            )
                        } else {
                            throw primaryErr
                        }
                    }
                } ?: throw NeteaseWriteException.ServerError(-1, "听歌记录上报超时")
                parseSimpleWriteResponse(root)
                Log.d("NeteaseWrite", "weblog song=$songId start=$start sec=$playedSeconds end=$endReason")
                Unit
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w("NeteaseWrite", "weblog failed song=$songId start=$start err=${err.message}")
            }
        }

    private fun requireCookie(cookie: String?): String =
        cookie?.takeIf(String::isNotBlank) ?: throw NeteaseWriteException.LoginRequired("请先登录网易云账号")

    internal fun ensureWriteSuccess(
        root: JsonObject,
        extraAcceptedCodes: Set<Int> = emptySet(),
    ): Int {
        val code = root.stringOrNull("code")?.toIntOrNull() ?: -1
        if (code == 200 || code in extraAcceptedCodes) return code
        val rawMessage = root.stringOrNull("message")?.takeIf(String::isNotBlank)
            ?: root.stringOrNull("msg")?.takeIf(String::isNotBlank)
            ?: when (code) {
                301, 302, -460 -> "登录已失效，请重新登录网易云"
                -462 -> "触发安全验证，请重新登录网易云"
                else -> "请求失败（$code）"
            }
        val message = if (rawMessage.contains(code.toString())) rawMessage else "$rawMessage (code=$code)"
        if (code == 301 || code == 302) {
            throw NeteaseWriteException.LoginRequired(message)
        }
        throw NeteaseWriteException.ServerError(code, message)
    }

    internal fun parseSimpleWriteResponse(root: JsonObject) {
        ensureWriteSuccess(root)
    }

    internal fun parseLikeListResponse(root: JsonObject): Set<Long> {
        ensureWriteSuccess(root)
        return root["ids"]?.jsonArrayOrNull().orEmpty().mapNotNullTo(LinkedHashSet()) { element ->
            (element as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        }
    }

    internal fun parseLikeTrackResponse(root: JsonObject): Long? {
        ensureWriteSuccess(root)
        return root.stringOrNull("playlistId")?.toLongOrNull()
    }

    internal fun parseCreatePlaylistResponse(
        root: JsonObject,
        fallbackName: String = "",
        fallbackPrivacyPrivate: Boolean = false,
    ): CreatedNeteasePlaylist {
        ensureWriteSuccess(root)
        val obj = root["playlist"]?.jsonObjectOrNull()
        val id = obj?.stringOrNull("id")?.takeIf(String::isNotBlank)
            ?: root.stringOrNull("id")?.takeIf(String::isNotBlank)
            ?: throw NeteaseWriteException.ServerError(-1, "创建歌单成功但未返回 ID")
        return CreatedNeteasePlaylist(
            id = id,
            name = obj?.stringOrNull("name")?.takeIf(String::isNotBlank) ?: fallbackName,
            coverUrl = obj?.stringOrNull("coverImgUrl").orEmpty(),
            description = obj?.stringOrNull("description").orEmpty(),
            privacyPrivate = (obj?.stringOrNull("privacy")?.toIntOrNull() ?: if (fallbackPrivacyPrivate) 10 else 0) == 10,
            trackCount = obj?.stringOrNull("trackCount")?.toIntOrNull() ?: 0,
            creatorId = obj?.get("creator")?.jsonObjectOrNull()?.stringOrNull("userId").orEmpty(),
        )
    }

    internal fun parseManipulateTracksResponse(root: JsonObject, add: Boolean): Int =
        ensureWriteSuccess(root, extraAcceptedCodes = if (add) setOf(502) else emptySet())
}

internal fun resolveWeblogSource(playlistId: String?, likedRemoteId: String?): Pair<String, String> = when {
    playlistId == null -> "list" to ""
    playlistId == NeteaseRepository.DAILY_ID -> "daily" to ""
    playlistId == NeteaseRepository.LIKED_ID -> "list" to likedRemoteId.orEmpty()
    playlistId.startsWith(NeteaseRepository.ALBUM_PAGE_PREFIX) -> {
        "album" to playlistId.removePrefix(NeteaseRepository.ALBUM_PAGE_PREFIX).takeIf { it.all(Char::isDigit) }.orEmpty()
    }
    playlistId.startsWith(NeteaseRepository.ARTIST_PAGE_PREFIX) -> {
        "artist" to playlistId.removePrefix(NeteaseRepository.ARTIST_PAGE_PREFIX).takeIf { it.all(Char::isDigit) }.orEmpty()
    }
    playlistId.all(Char::isDigit) -> "list" to playlistId
    else -> "list" to ""
}

internal fun mapPlaybackEndReasonToWeblog(reason: PlaybackEndReason?): String = when (reason) {
    PlaybackEndReason.COMPLETED -> "playend"
    PlaybackEndReason.SKIPPED, PlaybackEndReason.STOPPED, null -> "ui"
}
