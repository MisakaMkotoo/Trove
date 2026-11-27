package com.exodidio.tune.ui.navigation

import android.util.Log
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class PlayerLyricWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
)

internal data class PlayerLyricLine(
    val primary: String,
    val secondary: String? = null,
    val timestampSeconds: Float? = null,
    val endTimestampSeconds: Float? = null,
    val words: List<PlayerLyricWord> = emptyList(),
    /** 开唱前的三连点行：按普通歌词行参与滚动/扫亮/上抬。 */
    val isCountdown: Boolean = false,
    /** 和声/背景人声：挂在主句上，有自己的时间窗与逐字时间，不单独占歌词行。 */
    val accompaniments: List<PlayerLyricAccompaniment> = emptyList(),
    /** 对唱分边：该句由第二位演唱者唱，整行靠右；无演唱者信息的歌词保持左侧。 */
    val alignEnd: Boolean = false,
)

/** 和声片段：可落在主句中间，唱到才开始占位、唱完收起。 */
internal data class PlayerLyricAccompaniment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<PlayerLyricWord> = emptyList(),
)

/** 整行被括号包住时视为和声行（QQ/AMLL 的方括号和声约定）。 */
private val BackingVocalLine = Regex("^[(（](.+)[)）]$")

/** 把整行括号和声挂到上一句，不作为独立歌词行。 */
private fun attachBracketAccompaniments(lines: List<PlayerLyricLine>): List<PlayerLyricLine> {
    val result = ArrayList<PlayerLyricLine>(lines.size)
    for (line in lines) {
        val match = if (line.secondary == null && line.timestampSeconds != null && !line.isCountdown) {
            BackingVocalLine.find(line.primary)
        } else {
            null
        }
        if (match == null || result.isEmpty()) {
            result += line
            continue
        }
        val startMs = (line.timestampSeconds!! * 1_000f).toLong()
        val endMs = line.endTimestampSeconds?.let { (it * 1_000f).toLong() }
            ?: (startMs + (line.primary.length * 320L).coerceIn(800L, 8_000L))
        val text = match.groupValues[1].trim()
        if (text.isBlank()) {
            result += line
            continue
        }
        val words = line.words.mapNotNull { word ->
            val cleaned = word.text.replace('(', ' ').replace(')', ' ')
                .replace('（', ' ').replace('）', ' ').trim()
            cleaned.takeIf { it.isNotEmpty() }?.let { PlayerLyricWord(word.startMs, word.durationMs, it) }
        }
        val parent = result.removeAt(result.lastIndex)
        result += parent.copy(
            accompaniments = parent.accompaniments + PlayerLyricAccompaniment(
                startMs = startMs,
                endMs = maxOf(endMs, startMs + 200L),
                text = text,
                words = words,
            ),
        )
    }
    return result
}

internal data class ParsedPlayerLyrics(
    val lines: List<PlayerLyricLine>,
    val firstVocalStartMs: Long? = null,
)

private val TimestampedLyricLine = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)$")
private val LeadingLyricTimestamp = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")
private val BilingualSeparator = Regex("\\s*\\^\\s*|\\s*/\\s*")
internal const val ForwardSeekAnimatedApproachRows = 3
/** 手动翻动歌词后，静止这么久就回到跟随播放的状态。 */
internal const val BrowseIdleReturnMs = 3_000L
/** 歌词绘制层满幅到屏幕边，文字内容缩进这段距离。 */
internal val LyricsContentHorizontalInset = 35.dp

/** 歌词字号档位：0 小、1 标准（默认）、2 大。 */
internal fun lyricFontScale(level: Int): Float = when (level) {
    0 -> 0.92f
    1 -> 1f
    else -> 1.12f
}

internal fun TextStyle.scaledBy(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

internal enum class LyricsSeekDirection { Backward, Forward }
internal enum class LyricsScrollMode { Follow, Browse }
internal fun lyricsSeekDirection(targetIndex: Int, firstVisibleIndex: Int): LyricsSeekDirection =
    if (targetIndex < firstVisibleIndex) LyricsSeekDirection.Backward else LyricsSeekDirection.Forward

private val TtmlParagraph = Regex("<p[^>]*begin=\"([^\"]+)\"[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
private val TtmlTag = Regex("<[^>]+>")

internal fun parseTtmlTimestamp(value: String): Float? {
    val v = value.trim()
    val normalized = v.replace(',', '.')
    val parts = normalized.split(':')
    return try {
        when (parts.size) {
            3 -> parts[0].toFloat() * 3600f + parts[1].toFloat() * 60f + parts[2].toFloat()
            2 -> parts[0].toFloat() * 60f + parts[1].toFloat()
            1 -> parts[0].trimEnd('s').toFloat()
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

internal fun parseTtmlLyrics(content: String): List<PlayerLyricLine> =
    TtmlParagraph.findAll(content).mapNotNull { match ->
        val timestamp = parseTtmlTimestamp(match.groupValues[1])
        val text = match.groupValues[2].replace(TtmlTag, "").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim()
        text.takeIf { it.isNotEmpty() }?.let { parsePlayerLyricText(it, timestamp) }
    }.toList()

internal fun parsePlayerLyrics(content: String): List<PlayerLyricLine> {
    val parsed = parsePlayerLyricsInternal(content)
    val timed = parsed.count { it.timestampSeconds != null }
    val last = parsed.mapNotNull { it.timestampSeconds }.maxOrNull()
    Log.d("LYRPARSE", "chars=${content.length} lines=${parsed.size} timed=$timed lastTs=$last")
    if (timed == 0) {
        Log.d("LYRPARSE", "sample=${content.take(160).replace('\n', ' ')}")
    }
    return parsed
}

private val YrcLinePattern = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
private val YrcWordPattern = Regex("\\((\\d+),(\\d+)(?:,(\\d+))?\\)(.*?)(?=\\(\\d+,\\d+(?:,\\d+)?\\)|$)")
private val TimedLinePattern = Regex("^\\[(\\d+):(\\d+(?:[.:]\\d+)?)\\](.*)$")
internal val LyricCreditPattern = Regex(
    "(?i)^\\s*(作词|作曲|编曲|制作人?|监制|出品|录音|混音|母带|和声|吉他|贝斯|鼓|弦乐|管乐|人声|词|曲|" +
        "lyricist|composer|arranger|producer|vocals?|mixed|mastered|recorded|guitar|bass|drums)\\b",
)

/** 解析歌词文本：网易云逐字封装优先，其次普通 LRC。 */
internal fun parsePlayerLyricsDocument(content: String?): ParsedPlayerLyrics {
    if (content.isNullOrBlank()) return ParsedPlayerLyrics(emptyList())
    val envelope = runCatching {
        if (content.trimStart().startsWith("{") && content.contains("\"tuneLyrics\"")) {
            Json.parseToJsonElement(content) as? JsonObject
        } else {
            null
        }
    }.getOrNull()
    if (envelope != null) {
        val yrc = envelope["yrc"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (yrc.isNotBlank()) {
            val translations = envelopeTimedLines(
                envelope["ytlrc"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    .ifBlank {
                        envelope["tlyric"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    },
            )
            val lines = attachBracketAccompaniments(
                attachEnvelopeAccompaniments(
                    attachAgentSides(parseYrcLines(yrc, translations), envelope),
                    envelope,
                ),
            )
            if (lines.isNotEmpty()) {
                return ParsedPlayerLyrics(lines, firstVocalStartMs(lines))
            }
        }
        val fallback = envelope["lrc"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val lines = attachBracketAccompaniments(
            attachAgentSides(parsePlayerLyrics(fallback), envelope),
        )
        return ParsedPlayerLyrics(lines, firstVocalStartMs(lines))
    }
    val lines = attachBracketAccompaniments(parsePlayerLyrics(content))
    return ParsedPlayerLyrics(lines, firstVocalStartMs(lines))
}

/** 封装里的 bg 数组：按主句行起点挂上和声（可落在主句中间）。 */
private fun attachEnvelopeAccompaniments(
    lines: List<PlayerLyricLine>,
    envelope: JsonObject,
): List<PlayerLyricLine> {
    val bg = envelope["bg"] as? JsonArray ?: return lines
    if (bg.isEmpty()) return lines
    val grouped = HashMap<Long, MutableList<PlayerLyricAccompaniment>>()
    bg.forEach { element ->
        val obj = element as? JsonObject ?: return@forEach
        fun field(key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull
        val parentKey = field("line")?.toLongOrNull() ?: return@forEach
        val start = field("start")?.toLongOrNull() ?: return@forEach
        val end = field("end")?.toLongOrNull() ?: (start + 800L)
        val text = field("text")?.takeIf { it.isNotBlank() } ?: return@forEach
        val words = (obj["words"] as? JsonArray)?.mapNotNull { word ->
            val arr = word as? JsonArray ?: return@mapNotNull null
            fun item(index: Int): String? =
                (arr.getOrNull(index) as? JsonPrimitive)?.contentOrNull
            val wordStart = item(0)?.toLongOrNull() ?: return@mapNotNull null
            val wordDuration = item(1)?.toLongOrNull() ?: 0L
            val wordText = item(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlayerLyricWord(wordStart, wordDuration, wordText)
        }.orEmpty()
        grouped.getOrPut(parentKey) { mutableListOf() } += PlayerLyricAccompaniment(
            startMs = start,
            endMs = maxOf(end, start + 200L),
            text = text,
            words = words,
        )
    }
    if (grouped.isEmpty()) return lines
    return lines.map { line ->
        val key = line.timestampSeconds?.let { (it * 1_000f).toLong() }
        val list = key?.let { grouped[it] } ?: return@map line
        line.copy(accompaniments = line.accompaniments + list)
    }
}

/** 封装里的 agents 数组：对唱时第二位演唱者的行靠右；没有记录的歌词一律留在左侧。 */
private fun attachAgentSides(
    lines: List<PlayerLyricLine>,
    envelope: JsonObject,
): List<PlayerLyricLine> {
    val agents = envelope["agents"] as? JsonArray ?: return lines
    if (agents.isEmpty()) return lines
    val rightKeys = HashSet<Long>()
    agents.forEach { element ->
        val obj = element as? JsonObject ?: return@forEach
        val key = (obj["line"] as? JsonPrimitive)?.contentOrNull
            ?.toLongOrNull() ?: return@forEach
        rightKeys += key
    }
    if (rightKeys.isEmpty()) return lines
    return lines.map { line ->
        val key = line.timestampSeconds?.let { (it * 1_000f).toLong() }
        if (key != null && key in rightKeys) line.copy(alignEnd = true) else line
    }
}

private fun envelopeTimedLines(content: String): Map<Long, String> {
    if (content.isBlank()) return emptyMap()
    return content.lineSequence().mapNotNull { raw ->
        val match = TimedLinePattern.find(raw.trim()) ?: return@mapNotNull null
        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        // 毫秒必须按十进制字符串取，不能走 Double：8.133 * 1000 会算成 8132.999…，
        // 截断后和 yrc 行头的整数 8133 对不上，这一行翻译就丢了。
        val secondsText = match.groupValues[2]
        val dot = secondsText.indexOfFirst { it == '.' || it == ':' }
        val wholeSeconds = (if (dot >= 0) secondsText.substring(0, dot) else secondsText)
            .toLongOrNull() ?: return@mapNotNull null
        val fractionMs = if (dot < 0) 0L else {
            secondsText.substring(dot + 1).take(3).padEnd(3, '0').toLongOrNull() ?: return@mapNotNull null
        }
        val text = match.groupValues[3].trim()
        val key = minutes * 60_000L + wholeSeconds * 1000L + fractionMs
        if (text.isBlank()) null else key to text
    }.toMap()
}

private data class RawYrcLine(
    val startMs: Long,
    val durationMs: Long,
    val words: List<PlayerLyricWord>,
    val text: String,
)

private fun parseYrcLines(content: String, translations: Map<Long, String>): List<PlayerLyricLine> {
    val rawLines = content.lineSequence().mapNotNull { raw ->
        val line = YrcLinePattern.find(raw.trim()) ?: return@mapNotNull null
        val lineStartMs = line.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val lineDurationMs = line.groupValues[2].toLongOrNull() ?: 0L
        val words = YrcWordPattern.findAll(line.groupValues[3]).mapNotNull { match ->
            val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: 0L
            val text = match.groupValues[4]
            if (text.isBlank()) null else PlayerLyricWord(start, duration, text)
        }.toList()
        val text = words.joinToString("") { it.text }.trim()
        if (text.isEmpty() || lineDurationMs <= 0L) null
        else RawYrcLine(lineStartMs, lineDurationMs, words, text)
    }.toList()
    if (rawLines.isEmpty()) return emptyList()

    val resolvedTranslations = arrayOfNulls<String>(rawLines.size)
    if (translations.isNotEmpty()) {
        val sortedTranslations = translations.entries.sortedBy { it.key }
        val used = BooleanArray(sortedTranslations.size)
        val exactIndexByKey = HashMap<Long, Int>(sortedTranslations.size)
        sortedTranslations.forEachIndexed { idx, entry -> exactIndexByKey[entry.key] = idx }
        // 优先精确匹配，避免被相邻行抢走。
        rawLines.forEachIndexed { i, raw ->
            val exactIdx = exactIndexByKey[raw.startMs]
            if (exactIdx != null) {
                resolvedTranslations[i] = sortedTranslations[exactIdx].value
                used[exactIdx] = true
            }
        }
        // 次选容差匹配（<= 50ms）：兼容 yrc 毫秒行头与百分秒 tlyric 的舍入差。
        rawLines.forEachIndexed { i, raw ->
            if (resolvedTranslations[i] != null) return@forEachIndexed
            val idx = findNearestUnmatchedTranslation(sortedTranslations, used, raw.startMs)
            if (idx >= 0) {
                resolvedTranslations[i] = sortedTranslations[idx].value
                used[idx] = true
            }
        }
    }

    return rawLines.mapIndexed { index, raw ->
        PlayerLyricLine(
            primary = raw.text,
            secondary = resolvedTranslations[index],
            timestampSeconds = raw.startMs / 1_000f,
            endTimestampSeconds = (raw.startMs + raw.durationMs) / 1_000f,
            words = raw.words,
        )
    }
}

private const val YrcTranslationMatchToleranceMs = 50L

private fun findNearestUnmatchedTranslation(
    sortedTranslations: List<Map.Entry<Long, String>>,
    used: BooleanArray,
    targetMs: Long,
): Int {
    var low = 0
    var high = sortedTranslations.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val key = sortedTranslations[mid].key
        when {
            key < targetMs -> low = mid + 1
            key > targetMs -> high = mid - 1
            else -> {
                low = mid
                break
            }
        }
    }
    var bestIdx = -1
    var bestDelta = Long.MAX_VALUE
    var right = low
    while (right < sortedTranslations.size) {
        val delta = kotlin.math.abs(sortedTranslations[right].key - targetMs)
        if (delta > YrcTranslationMatchToleranceMs) break
        if (!used[right] && delta < bestDelta) {
            bestDelta = delta
            bestIdx = right
        }
        right++
    }
    var left = low - 1
    while (left >= 0) {
        val delta = kotlin.math.abs(sortedTranslations[left].key - targetMs)
        if (delta > YrcTranslationMatchToleranceMs) break
        if (!used[left] && delta < bestDelta) {
            bestDelta = delta
            bestIdx = left
        }
        left--
    }
    return bestIdx
}

/** 开唱前的三连点行：三个点各占开唱前时长的三分之一。 */
internal fun buildCountdownLine(firstVocalStartMs: Long): PlayerLyricLine {
    val segmentMs = (firstVocalStartMs / 3L).coerceAtLeast(1L)
    val words = (0 until 3).map { index ->
        PlayerLyricWord(
            startMs = index * segmentMs,
            durationMs = segmentMs,
            text = "● ",
        )
    }
    return PlayerLyricLine(
        primary = "● ● ●",
        timestampSeconds = 0f,
        endTimestampSeconds = firstVocalStartMs / 1_000f,
        words = words,
        isCountdown = true,
    )
}

/** 第一条非制作名单的正文行开始时间。 */
private fun firstVocalStartMs(lines: List<PlayerLyricLine>): Long? {
    val candidate = lines.firstOrNull { line ->
        val start = line.timestampSeconds ?: return@firstOrNull false
        val end = line.endTimestampSeconds ?: start
        start > 0f && end > start && LyricCreditPattern.find(line.primary) == null
    } ?: return null
    return ((candidate.timestampSeconds ?: 0f) * 1_000f).toLong()
}

private fun parsePlayerLyricsInternal(content: String): List<PlayerLyricLine> {
    if (content.contains("<tt", ignoreCase = true) || TtmlParagraph.containsMatchIn(content)) {
        parseTtmlLyrics(content).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return content.lineSequence().flatMap { rawLine ->
        // 一行可能有多个时间戳（副歌复用），逐个展开。
        val timestamps = mutableListOf<Float>()
        var rest = rawLine.trim()
        while (true) {
            val match = LeadingLyricTimestamp.find(rest) ?: break
            if (match.range.first != 0) break
            timestamps += matchTimestampSeconds(match)
            rest = rest.substring(match.range.last + 1)
        }
        val text = rest.trim()
        if (text.isEmpty()) emptySequence()
        else if (timestamps.isEmpty()) sequenceOf(parsePlayerLyricText(text, null))
        else timestamps.asSequence().map { parsePlayerLyricText(text, it) }
    }.toList()
}

private fun matchTimestampSeconds(match: MatchResult): Float {
    val minutes = match.groupValues[1].toFloatOrNull() ?: 0f
    val seconds = match.groupValues[2].toFloatOrNull() ?: 0f
    val fractionText = match.groupValues[3]
    val fraction = when (fractionText.length) {
        0 -> 0f
        1 -> fractionText.toFloatOrNull()?.div(10f)
        2 -> fractionText.toFloatOrNull()?.div(100f)
        else -> fractionText.take(3).toFloatOrNull()?.div(1000f)
    } ?: 0f
    return minutes * 60f + seconds + fraction
}

internal fun hasSyncedPlayerLyrics(content: String?): Boolean =
    content != null && parsePlayerLyricsDocument(content).lines.any { it.timestampSeconds != null }

/** Resume automatic following once playback reaches the tapped lyric or passes it. */
internal fun shouldResumeLyricsAutoScroll(
    selectedLineIndex: Int?,
    activeIndex: Int,
    activeIndexWhenLineSelected: Int?,
    selectedLineAnimationComplete: Boolean,
): Boolean = selectedLineAnimationComplete && selectedLineIndex != null &&
    activeIndex >= selectedLineIndex && activeIndex != activeIndexWhenLineSelected

/** The active line may advance beyond the visible viewport while the app is backgrounded. */
internal fun shouldFollowLyricsActiveLine(previousActiveLineInViewport: Boolean, returnedToForeground: Boolean): Boolean =
    previousActiveLineInViewport || returnedToForeground

/** A repeat/replay restarts the same track near zero without changing its track ID. */
internal fun shouldResetLyricsForReplay(previousPositionMs: Long, currentPositionMs: Long): Boolean =
    previousPositionMs > 1_000L && currentPositionMs <= 1_000L

/** Prefer a slider's requested position until playback confirms the seek. */
internal fun displayedLyricsPositionMs(playbackPositionMs: Long, pendingSeekPositionMs: Long?): Long =
    pendingSeekPositionMs ?: playbackPositionMs

/** Programmatic lyric positioning must not be interpreted as manual browsing. */
internal fun shouldEnterLyricsBrowseMode(isUserDragging: Boolean, isFollowingSelectedLine: Boolean = false): Boolean =
    isUserDragging && !isFollowingSelectedLine

/** Small finger drift on a lyric row is still a seek, not a manual browse. */
internal fun shouldSeekFromLyricTap(dragDistancePx: Float, tapSlopPx: Float): Boolean = dragDistancePx <= tapSlopPx

internal fun syncedLyricBlurRadius(distance: Int) = when (distance) {
    0 -> 0.dp
    1 -> 0.35.dp
    2 -> 1.25.dp
    else -> 2.dp
}

private fun parsePlayerLyricText(text: String, timestampSeconds: Float?): PlayerLyricLine {
    val parts = BilingualSeparator.split(text, limit = 2)
    val secondary = parts.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
    return PlayerLyricLine(primary = parts.first().trim(), secondary = secondary, timestampSeconds = timestampSeconds)
}
