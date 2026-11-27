package com.exodidio.tune.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.exodidio.tune.ui.navigation.PlayerLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import kotlin.math.abs

/**
 * 把播放器和整歌歌词推给 Lyricon 中心服务（开源系统歌词生态，状态栏/锁屏/背屏等展示端读取）。
 * 中心未安装或未连接时所有调用都是空操作；首次初始化失败后本进程不再重试。
 */
internal class LyriconPublisher(context: Context) {
    private val appContext = context.applicationContext
    private var provider: LyriconProvider? = null

    @Volatile
    private var unavailable = false

    private var lastSongKey: String? = null
    private var lastPlaying: Boolean? = null
    private var lastTranslation: Boolean? = null
    private var lastPositionMs = -1L
    private var lastPositionPushedAt = 0L

    fun start() {
        if (unavailable || provider != null) return
        runCatching {
            LyriconFactory.createProvider(appContext).apply {
                autoSync = true
                register()
            }
        }.onSuccess { provider = it }
            .onFailure {
                unavailable = true
                Log.w(Tag, "Lyricon provider unavailable", it)
            }
    }

    fun sync(
        trackId: String?,
        title: String,
        artist: String,
        durationMs: Long,
        lyrics: List<PlayerLyricLine>,
        positionMs: Long,
        playing: Boolean,
        displayTranslation: Boolean,
    ) {
        if (unavailable) return
        if (provider == null) start()
        val player = provider?.player ?: return
        if (trackId.isNullOrBlank() || title.isBlank()) {
            stop()
            return
        }
        val songKey = "$trackId|$durationMs|${lyrics.hashCode()}"
        if (songKey != lastSongKey) {
            val song = buildSong(trackId, title, artist, durationMs, lyrics)
            lastSongKey = songKey
            lastPlaying = null
            lastTranslation = null
            lastPositionMs = -1L
            runCatching { player.setSong(song) }
        }
        if (playing != lastPlaying) {
            lastPlaying = playing
            runCatching { player.setPlaybackState(playing) }
        }
        if (displayTranslation != lastTranslation) {
            lastTranslation = displayTranslation
            runCatching { player.setDisplayTranslation(displayTranslation) }
        }
        val now = SystemClock.elapsedRealtime()
        // 进度按 500ms（播放）/1000ms（暂停）刷新；seek 跳变立即同步。
        val seekJumped = lastPositionMs >= 0 && abs(positionMs - lastPositionMs) > 1_500L
        if (seekJumped || lastPositionMs < 0 || now - lastPositionPushedAt >= if (playing) 500L else 1_000L) {
            lastPositionMs = positionMs
            lastPositionPushedAt = now
            runCatching { player.setPosition(positionMs) }
        }
    }

    fun stop() {
        if (lastSongKey == null && lastPlaying == null) return
        lastSongKey = null
        lastPlaying = null
        lastTranslation = null
        lastPositionMs = -1L
        val player = provider?.player ?: return
        runCatching {
            player.setPlaybackState(false)
            player.setSong(null)
        }
    }

    fun destroy() {
        provider?.let { active ->
            runCatching { active.unregister() }
            runCatching { active.destroy() }
        }
        provider = null
    }

    private fun buildSong(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long,
        lyrics: List<PlayerLyricLine>,
    ): Song = Song(
        id = trackId,
        name = title,
        artist = artist,
        duration = durationMs.coerceAtLeast(0L),
        lyrics = buildLines(lyrics),
    )

    private fun buildLines(lyrics: List<PlayerLyricLine>): List<RichLyricLine> {
        val timed = lyrics
            .filter { !it.isCountdown }
            .mapNotNull { line ->
                val begin = line.timestampSeconds?.let { (it * 1_000f).toLong() } ?: return@mapNotNull null
                line.primary.trim().takeIf { it.isNotEmpty() }?.let { text -> Triple(begin, text, line) }
            }
            .sortedBy { it.first }
        return timed.mapIndexed { index, (begin, text, line) ->
            val explicitEnd = line.endTimestampSeconds?.let { (it * 1_000f).toLong() }
            val nextBegin = timed.getOrNull(index + 1)?.first
            val end = explicitEnd?.takeIf { it > begin }
                ?: nextBegin?.takeIf { it > begin }
                ?: (begin + 3_000L)
            RichLyricLine(
                begin = begin,
                end = end,
                text = text,
                words = line.words.mapNotNull { word ->
                    val wordBegin = word.startMs
                    val wordEnd = (word.startMs + word.durationMs).coerceAtLeast(wordBegin + 1L)
                    LyricWord(
                        begin = wordBegin.coerceAtLeast(begin),
                        end = wordEnd.coerceAtMost(end).coerceAtLeast(wordBegin.coerceAtLeast(begin) + 1L),
                        text = word.text,
                    )
                }.takeIf { it.isNotEmpty() },
                translation = line.secondary?.takeIf { it.isNotBlank() },
                isAlignedRight = line.alignEnd,
            )
        }
    }

    private companion object {
        const val Tag = "LyriconPublisher"
    }
}
