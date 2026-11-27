package com.exodidio.tune.netease

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun parseNeteaseTrack(track: JsonObject, fallbackCover: String): NeteaseEntryPayload? {
    val songId = (track["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
    val title = (track["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (title.isBlank()) return null
    val artistArray = track["ar"]?.jsonArrayOrNull() ?: track["artists"]?.jsonArrayOrNull()
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
    val durationMs = (track["dt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        ?: (track["duration"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        ?: 0L
    return NeteaseEntryPayload(songId, title, artists, album, durationMs, cover, albumId, artistIds)
}

/** 参考实现的两条试听判定：freeTrialInfo 非空，或 freeTrialPrivilege 标记可试听。 */
internal fun isNeteaseTrialSource(data: JsonObject): Boolean {
    if (data["freeTrialInfo"]?.jsonObjectOrNull() != null) return true
    val privilege = data["freeTrialPrivilege"]?.jsonObjectOrNull() ?: return false
    val resConsumable = privilege.stringOrNull("resConsumable")?.toBooleanStrictOrNull() == true
    val userConsumable = privilege.stringOrNull("userConsumable")?.toBooleanStrictOrNull() == true
    val listenType = privilege.stringOrNull("listenType")?.toIntOrNull() ?: 0
    return resConsumable || userConsumable || listenType > 0
}

internal fun parseHeartbeatEntries(root: JsonObject, seedSongId: Long): List<NeteaseEntryPayload> {
    val candidates = mutableListOf<JsonElement>()
    root["data"]?.let { data ->
        when (data) {
            is JsonArray -> candidates.addAll(data)
            is JsonObject -> {
                data["data"]?.let { if (it is JsonArray) candidates.addAll(it) }
                candidates.add(data)
            }
            else -> Unit
        }
    }
    root["recommendations"]?.let { if (it is JsonArray) candidates.addAll(it) }
    val result = mutableListOf<NeteaseEntryPayload>()
    for (element in candidates) {
        val obj = element.jsonObjectOrNull() ?: continue
        val song = obj["songInfo"]?.jsonObjectOrNull()
            ?: obj["song"]?.jsonObjectOrNull()
            ?: obj["recommendSong"]?.jsonObjectOrNull()
            ?: obj
        val parsed = parseNeteaseTrack(song, "") ?: continue
        if (parsed.songId != seedSongId) result += parsed
    }
    return result.distinctBy { it.songId }.take(3)
}

internal fun parseHomeBlock(block: JsonObject): NeteaseClient.HomeBlock {
    val code = block.string("blockCode")
    val ui = block["uiElement"]?.jsonObjectOrNull()
    val mainTitle = ui?.titleText("mainTitle").orEmpty()
    val subTitle = ui?.titleText("subTitle").orEmpty()
    // 部分歌曲块的标题不在 block 级，退到 creative 级再取一次。
    val creativeTitle = block["creatives"]?.jsonArrayOrNull().orEmpty()
        .firstNotNullOfOrNull { creative ->
            val creativeUi = creative.jsonObjectOrNull()?.get("uiElement")?.jsonObjectOrNull()
            creativeUi?.titleText("mainTitle")?.takeIf { it.isNotBlank() }
                ?: creativeUi?.titleText("subTitle")?.takeIf { it.isNotBlank() }
        }
        .orEmpty()
    val useSubTitle = subTitle.isNotBlank() && (
        code.equals("HOMEPAGE_BLOCK_STYLE_RCMD", ignoreCase = true) ||
            mainTitle.isBlank() ||
            subTitle.contains("因为你听过") ||
            subTitle.contains("为你定制") ||
            subTitle.contains("专属") ||
            (subTitle.startsWith("根据") && subTitle.endsWith("为你推荐"))
        )
    val title = when {
        useSubTitle -> subTitle
        mainTitle.isNotBlank() -> mainTitle
        subTitle.isNotBlank() && !subTitle.equals("更多", ignoreCase = true) -> subTitle
        else -> creativeTitle
    }
    val blockCover = ui?.get("image")?.jsonObjectOrNull()?.string("imageUrl").orEmpty()
    val directResources = block["resources"]?.jsonArrayOrNull().orEmpty()
        .mapNotNull { it.jsonObjectOrNull() }
    val creativeResources = block["creatives"]?.jsonArrayOrNull().orEmpty()
        .flatMap { creative -> creative.jsonObjectOrNull()?.get("resources")?.jsonArrayOrNull().orEmpty() }
        .mapNotNull { it.jsonObjectOrNull() }
    val resources = directResources + creativeResources
    val songs = resources.mapNotNull { resource ->
        val ext = resource["resourceExtInfo"]?.jsonObjectOrNull()
        val fullSong = ext?.get("songData")?.jsonObjectOrNull() ?: ext?.get("song")?.jsonObjectOrNull()
        if (fullSong != null) {
            return@mapNotNull parseNeteaseTrack(fullSong, blockCover)
        }
        if (!resource.string("resourceType").equals("song", ignoreCase = true)) {
            return@mapNotNull null
        }
        val songId = resource.stringOrNull("resourceId")?.toLongOrNull()?.takeIf { it > 0L }
            ?: return@mapNotNull null
        val resourceUi = resource["uiElement"]?.jsonObjectOrNull()
        val songTitle = resourceUi?.titleText("mainTitle").orEmpty().ifBlank { return@mapNotNull null }
        val artistsArray = ext?.get("artists")?.jsonArrayOrNull().orEmpty()
        val artists = artistsArray
            .mapNotNull { it.jsonObjectOrNull()?.string("name")?.takeIf(String::isNotBlank) }
            .joinToString(" / ")
        val artistIds = artistsArray.mapNotNull { it.jsonObjectOrNull()?.stringOrNull("id")?.toLongOrNull() }
        val cover = resourceUi?.get("image")?.jsonObjectOrNull()?.string("imageUrl").orEmpty()
            .ifBlank { blockCover }
        NeteaseEntryPayload(
            songId = songId,
            title = songTitle,
            artists = artists,
            album = "",
            durationMs = 0L,
            coverUrl = cover,
            albumId = 0L,
            artistIds = artistIds,
        )
    }.distinctBy { it.songId }
    val playlists = resources
        .filter { resource ->
            // 首页块的歌单资源 resourceType 是小写的 "list" 或 "playlist"。
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
            val subtitle = resourceUi.titleText("subTitle")
                .takeIf { it.isNotBlank() && it != name }
                .orEmpty()
            NeteasePlaylistPayload(
                id = id,
                name = name,
                coverUrl = cover,
                entries = emptyList(),
                description = subtitle,
            )
        }
        .distinctBy { it.id }
    Log.d(
        "NeteaseClient",
        "block $code title=$title songs=${songs.size} playlists=${playlists.size}",
    )
    return NeteaseClient.HomeBlock(code, title, songs, playlists)
}

/** 把翻译行按时间戳并入 LRC，保持应用内“原文 / 译文”双语显示。 */
internal fun mergeNeteaseTranslation(lrc: String, tlyric: String): String {
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
internal fun yrcToLrc(yrc: String): String {
    val linePattern = Regex("^\\[(\\d+),\\d+\\](.*)$")
    val wordPattern = Regex("\\((\\d+,\\d+,\\d+)\\)([^(]*)")
    return yrc.lineSequence().mapNotNull { raw ->
        val line = linePattern.find(raw.trim()) ?: return@mapNotNull null
        val start = line.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val text = wordPattern.findAll(line.groupValues[2]).map { it.groupValues[1] }.joinToString("").trim()
        if (text.isBlank()) null else "[%02d:%02d.%03d]%s".format(start / 60000, start % 60000 / 1000, start % 1000, text)
    }.joinToString("\n")
}

/** 8769/8810 一类风控挑战统一提示走网页登录。 */
internal fun phoneAuthMessage(code: Int, root: JsonObject): String {
    if (code in 200..299) return ""
    val text = root.toString()
    val challenge = code in setOf(702, 8810, 8820, 8830, 8860) ||
        code in 10001..10004 ||
        text.contains("\"checkToken\"") ||
        text.contains("\"redirectUrl\"")
    if (challenge) return "网易云要求额外安全验证，请使用网页登录"
    return root.stringOrNull("message")
        ?: root.stringOrNull("msg")
        ?: "网易云请求失败（$code）"
}
