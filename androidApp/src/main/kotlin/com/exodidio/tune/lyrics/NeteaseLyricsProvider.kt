package com.exodidio.tune.lyrics

import com.exodidio.tune.netease.NeteaseClient
import com.exodidio.tune.netease.NeteaseRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 所有歌曲都优先走网易云：有 ID 直接取，否则按歌名/歌手搜索匹配后再取。 */
internal class NeteaseLyricsProvider : LyricsProvider() {
    private val resolvedIds = ConcurrentHashMap<String, Long>()

    fun clearCache() {
        resolvedIds.clear()
    }

    override fun enabled(settings: LyricsSettings): Boolean =
        LyricsSourceId.Netease in settings.enabledSources

    override suspend fun fetch(track: LyricsTrack): FetchedLyric? {
        val cookie = NeteaseRepository.cookie()
        val songId = NeteaseRepository.songIdForTrack(track.trackId)
            ?: resolvedIds[track.trackId]
            ?: searchSongId(track, cookie)?.also { resolvedIds[track.trackId] = it }
            ?: return null
        val content = NeteaseClient.fetchLyrics(songId, cookie).getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return FetchedLyric(content, "netease")
    }

    private suspend fun searchSongId(track: LyricsTrack, cookie: String?): Long? {
        val candidates = NeteaseClient.searchSong(track.title, track.artist, cookie).getOrNull().orEmpty()
        return selectBestNeteaseSongId(candidates, track)
    }

    override suspend fun search(title: String, artist: String, duration: Int): List<LyricsSearchResult> =
        emptyList()
}

internal fun selectBestNeteaseSongId(
    candidates: List<NeteaseClient.SearchSong>,
    track: LyricsTrack,
): Long? {
    if (candidates.isEmpty()) return null
    val title = normalizeLyricsText(removeFeaturedLyricsTitle(track.title))
    if (title.isBlank()) return null
    val artist = normalizeLyricsText(track.artist)
    val durationSeconds = track.duration.takeIf { it > 0 }
    val scored = candidates.mapNotNull { candidate ->
        val candidateTitle = normalizeLyricsText(removeFeaturedLyricsTitle(candidate.title))
        if (candidateTitle.isBlank()) return@mapNotNull null
        val exactTitle = candidateTitle == title
        val titleSimilar = !exactTitle && isTitleCloseMatch(candidateTitle, title)
        if (!exactTitle && !titleSimilar) return@mapNotNull null

        if (durationSeconds != null && candidate.durationMs > 0) {
            val diffSec = abs(candidate.durationMs / 1000 - durationSeconds)
            if (diffSec > 15) return@mapNotNull null
        }

        var score = if (exactTitle) 4 else 2
        val candidateArtists = normalizeLyricsText(candidate.artists)
        val artistMatched = artist.isNotBlank() && candidateArtists.isNotBlank() &&
            (candidateArtists.contains(artist) || artist.contains(candidateArtists))
        if (artistMatched) score += 3

        val durationScore = if (durationSeconds != null && candidate.durationMs > 0) {
            val diffSec = abs(candidate.durationMs / 1000 - durationSeconds)
            when {
                diffSec <= 5 -> 2
                diffSec <= 10 -> 1
                else -> 0
            }
        } else {
            0
        }
        score += durationScore

        // 非完全同名时必须有歌手或接近时长的佐证，避免误匹配无关歌曲。
        if (!exactTitle && !artistMatched && durationScore == 0) return@mapNotNull null
        candidate to score
    }
    return scored.maxByOrNull { it.second }?.first?.songId
}

private fun isTitleCloseMatch(left: String, right: String): Boolean {
    val minLen = minOf(left.length, right.length)
    val maxLen = maxOf(left.length, right.length)
    if (minLen >= 4 && (left.contains(right) || right.contains(left)) && minLen.toDouble() / maxLen >= 0.5) {
        return true
    }
    return stringSimilarity(left, right) >= 0.7
}

private fun stringSimilarity(left: String, right: String): Double {
    if (left == right) return 1.0
    if (left.isEmpty() || right.isEmpty()) return 0.0
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { index, l ->
        val current = IntArray(right.length + 1)
        current[0] = index + 1
        right.forEachIndexed { rightIndex, r ->
            current[rightIndex + 1] = if (l == r) {
                previous[rightIndex]
            } else {
                1 + minOf(previous[rightIndex], current[rightIndex], previous[rightIndex + 1])
            }
        }
        previous = current
    }
    return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
}
