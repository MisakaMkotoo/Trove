package com.exodidio.tune.lyrics

import com.exodidio.tune.ui.navigation.parsePlayerLyricsDocument
import java.util.Locale
import kotlin.math.roundToLong

internal object LrcExporter {
    fun formatLrcTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centiseconds = (clamped % 1_000L) / 10L
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centiseconds)
    }

    fun suggestedFileName(title: String, artist: String): String {
        val base = listOf(artist.trim(), title.trim())
            .filter(String::isNotEmpty)
            .joinToString(" - ")
            .ifBlank { "lyrics" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)
        return "$base.lrc"
    }

    fun buildExportableLrc(
        title: String,
        artist: String,
        rawLyrics: String?,
        offsetMs: Long = 0L,
        includeWordTiming: Boolean = false,
    ): String {
        val parsed = parsePlayerLyricsDocument(rawLyrics)
        val builder = StringBuilder()
        if (title.isNotBlank()) builder.append("[ti:").append(title.trim()).append("]\n")
        if (artist.isNotBlank()) builder.append("[ar:").append(artist.trim()).append("]\n")

        val timedLines = parsed.lines
            .filter { !it.isCountdown && it.timestampSeconds != null }
            .sortedBy { it.timestampSeconds ?: 0f }

        if (timedLines.isNotEmpty()) {
            for (line in timedLines) {
                val baseMs = ((line.timestampSeconds ?: 0f) * 1_000f).roundToLong()
                val shiftedMs = (baseMs + offsetMs).coerceAtLeast(0L)
                val tag = formatLrcTimestamp(shiftedMs)
                if (includeWordTiming && line.words.isNotEmpty()) {
                    builder.append('[').append(tag).append(']')
                    for (word in line.words) {
                        val wordStart = (word.startMs + offsetMs).coerceAtLeast(0L)
                        builder.append('<').append(formatLrcTimestamp(wordStart)).append('>').append(word.text)
                    }
                    builder.append('\n')
                } else {
                    val primary = line.primary.trim()
                    if (primary.isNotEmpty()) {
                        builder.append('[').append(tag).append(']').append(primary).append('\n')
                    }
                }
                val translation = line.secondary?.trim().orEmpty()
                if (translation.isNotEmpty()) {
                    builder.append('[').append(tag).append(']').append(translation).append('\n')
                }
            }
            return builder.toString().trimEnd() + "\n"
        }

        // 无时间戳的纯文本歌词回退保留原行。
        for (line in parsed.lines) {
            val primary = line.primary.trim()
            if (primary.isNotEmpty()) builder.append(primary).append('\n')
            val translation = line.secondary?.trim().orEmpty()
            if (translation.isNotEmpty()) builder.append(translation).append('\n')
        }
        return builder.toString().trimEnd() + "\n"
    }
}
