package com.exodidio.tune.lyrics

import com.exodidio.tune.netease.NeteaseClient
import com.exodidio.tune.netease.NeteaseRepository
import kotlin.math.abs

/** 所有歌曲都优先走网易云：有 ID 直接取，否则按歌名/歌手搜索匹配后再取。 */
internal class NeteaseLyricsProvider : LyricsProvider() {
    private val resolvedIds = mutableMapOf<String, Long>()

    override fun enabled(settings: LyricsSettings): Boolean = settings.netease

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
        if (candidates.isEmpty()) return null
        val title = normalizeLyricsText(removeFeaturedLyricsTitle(track.title))
        val artist = normalizeLyricsText(track.artist)
        val durationSeconds = track.duration.takeIf { it > 0 }
        val scored = candidates.map { candidate ->
            var score = 0
            if (normalizeLyricsText(candidate.title) == title) score += 4
            if (normalizeLyricsText(candidate.artists).contains(artist) || artist.contains(normalizeLyricsText(candidate.artists))) score += 3
            if (durationSeconds != null && candidate.durationMs > 0 &&
                abs(candidate.durationMs / 1000 - durationSeconds) <= 5
            ) {
                score += 2
            }
            candidate to score
        }
        return scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first?.songId
            ?: candidates.firstOrNull()?.songId
    }

    override suspend fun search(title: String, artist: String, duration: Int): List<LyricsSearchResult> =
        emptyList()
}
