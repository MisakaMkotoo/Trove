package com.exodidio.tune.lyrics

import com.exodidio.tune.BuildConfig
import com.exodidio.tune.net.TuneHttp
import com.exodidio.tune.net.executeCancellable
import java.io.StringReader
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import org.w3c.dom.Node
import okhttp3.Request

/**
 * AMLL TTML DB 歌词源（CC0）。
 * 官方 API + 多个 raw 镜像并行竞速，取最先可用的 TTML；
 * 解析后转成项目内的 tuneLyrics 封装（lrc / yrc / tlyric），逐字时间直接复用现有渲染。
 */
internal class AmlldbLyricsProvider : LyricsProvider() {
    override val isAmll: Boolean get() = true

    override fun enabled(settings: LyricsSettings): Boolean =
        LyricsSourceId.Amll in settings.enabledSources

    override suspend fun fetch(track: LyricsTrack): FetchedLyric? {
        val id = neteaseSongId(track.trackId) ?: return null
        val ttml = fetchTtml(id) ?: return null
        val envelope = ttmlToTuneLyrics(ttml) ?: return null
        return FetchedLyric(envelope, "amll")
    }

    override suspend fun search(title: String, artist: String, duration: Int): List<LyricsSearchResult> =
        emptyList()

    private suspend fun fetchTtml(songId: String): String? = withTimeoutOrNull(AmllTimeoutMs) {
        coroutineScope {
            val attempts: List<suspend () -> String?> = buildList {
                add {
                    val body = httpGet(
                        "https://api.amll.dev/api/v1/lyrics/get?ncmMusicId=$songId",
                        "application/json",
                    )
                    body?.let(::lyricsFieldFromOfficial)
                }
                AmllRawMirrors.forEach { mirror ->
                    add { httpGet("$mirror/ncm-lyrics/$songId.ttml", "application/xml,text/xml,*/*") }
                }
            }
            val result = Channel<String>(Channel.CONFLATED)
            val remaining = AtomicInteger(attempts.size)
            val jobs = attempts.map { attempt ->
                launch(Dispatchers.IO) {
                    val body = runCatching { attempt() }.getOrNull()
                    if (body != null && looksLikeTtml(body)) result.trySend(body)
                    if (remaining.decrementAndGet() == 0) result.close()
                }
            }
            val ttml = result.receiveCatching().getOrNull()
            jobs.forEach(Job::cancel)
            ttml
        }
    }

    private suspend fun httpGet(url: String, accept: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Tune-Android/${BuildConfig.VERSION_NAME}")
            .header("Accept", accept)
            .get()
            .build()
        TuneHttp.client(connectTimeoutMs = 6_000L, readTimeoutMs = 10_000L)
            .executeCancellable(request) { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
    }.getOrNull()

    private fun lyricsFieldFromOfficial(body: String): String? = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        (root["data"] as? JsonObject)
            ?.get("lyrics")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun looksLikeTtml(body: String): Boolean =
        body.contains("http://www.w3.org/ns/ttml") || body.trimStart().startsWith("<tt")

    private fun neteaseSongId(trackId: String): String? {
        if (!trackId.startsWith("netease:")) return null
        val id = trackId.removePrefix("netease:").substringBefore(':')
        return id.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private companion object {
        const val AmllTimeoutMs = 12_000L

        val AmllRawMirrors = listOf(
            "https://amll.mirror.dimeta.top/api/db",
            "https://amll-ttml-db.gbclstudio.cn",
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main",
            "https://amll-ttml-db.stevexmh.net",
        )
    }
}

private val YrcWordTagPattern = Regex("\\(\\d+,\\d+(?:,\\d+)?\\)")
private val RawYrcLineTagPattern = Regex("^\\[\\d+,\\d+].*\\(\\d+,\\d+(?:,\\d+)?\\)", RegexOption.MULTILINE)
private val EnhancedLrcWordTagPattern = Regex("<\\d{1,3}:\\d{2}[.:]\\d{1,3}>")

/** 是否包含逐字时间轴：用于判断能否替换行级歌词，以及保护逐字歌词不被自动对齐覆盖。 */
internal fun hasWordTimingLyrics(content: String?): Boolean {
    if (content.isNullOrBlank()) return false
    val trimmed = content.trimStart()
    if (trimmed.startsWith("{")) {
        val envelopeHasYrc = runCatching {
            val root = Json.parseToJsonElement(trimmed) as? JsonObject ?: return@runCatching false
            val yrc = (root["yrc"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            yrc.isNotBlank() && YrcWordTagPattern.containsMatchIn(yrc)
        }.getOrDefault(false)
        if (envelopeHasYrc) return true
    }
    return RawYrcLineTagPattern.containsMatchIn(trimmed) ||
        EnhancedLrcWordTagPattern.containsMatchIn(trimmed)
}

private data class TtmlWord(val startMs: Long, val endMs: Long, val text: String)

private data class TtmlLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<TtmlWord>,
    val text: String,
    val translation: String?,
    /** x-bg 和声：写进封装的 bg 数组，由播放器挂到主句上。 */
    val backing: Boolean = false,
    /** 和声所属主句的原始起点（毫秒），用于和主句 key 对齐。 */
    val backingParentStartMs: Long? = null,
    /** 对唱分边：非首位演唱者（ttm:agent）的行靠右显示。 */
    val alignEnd: Boolean = false,
)

/**
 * AMLL/Apple TTML → tuneLyrics 封装（与网易云 yrc 同一套 JSON）。
 * 逐字 span 取 begin/end；x-bg 和声暂不计入主行；翻译优先内联 x-translation，其次 <translations>。
 */
internal fun ttmlToTuneLyrics(ttml: String): String? {
    val lines = runCatching { parseTtmlLines(ttml) }.getOrNull() ?: return null
    if (lines.isEmpty()) return null

    val lrc = StringBuilder()
    val yrc = StringBuilder()
    val ytlrc = StringBuilder()
    val accompanimentObjects = mutableListOf<JsonObject>()
    val agentObjects = mutableListOf<JsonObject>()
    for (line in lines.sortedBy { it.startMs }) {
        if (line.backing) {
            val parentStart = line.backingParentStartMs ?: continue
            accompanimentObjects += accompanimentJson(parentStart, line)
            continue
        }
        val rawStart = (line.startMs / 10L) * 10L
        val end = maxOf(line.endMs, rawStart + 10L)
        // 译文按 [mm:ss.xx] 解析回来可能截断 1ms；用解析后的值作为 yrc 行起点，
        // 保证逐字行与译文行的 key 完全一致。
        val lrcTime = formatLrcTime(rawStart)
        val start = lrcTimeToMs(lrcTime)
        val duration = maxOf(end - start, 10L)
        lrc.append('[').append(lrcTime).append(']').append(line.text).append('\n')
        line.translation?.takeIf { it.isNotBlank() }?.let {
            ytlrc.append('[').append(lrcTime).append(']').append(it).append('\n')
        }
        if (line.alignEnd) {
            agentObjects += buildJsonObject { put("line", start) }
        }
        if (line.words.isNotEmpty()) {
            yrc.append('[').append(start).append(',').append(duration).append(']')
            for (word in line.words) {
                val wordStart = word.startMs.coerceAtLeast(start)
                val wordEnd = word.endMs.coerceAtLeast(wordStart + 10L)
                val safeText = word.text.replace('(', ' ').replace(')', ' ')
                yrc.append('(').append(wordStart).append(',').append(wordEnd - wordStart).append(",0)")
                    .append(safeText)
            }
            yrc.append('\n')
        }
    }
    if (lrc.isEmpty() && accompanimentObjects.isEmpty()) return null
    return buildJsonObject {
        put("tuneLyrics", 1)
        put("format", "amll-ttml")
        put("lrc", lrc.toString())
        if (yrc.isNotEmpty()) put("yrc", yrc.toString())
        if (ytlrc.isNotEmpty()) {
            put("tlyric", ytlrc.toString())
            put("ytlrc", ytlrc.toString())
        }
        if (accompanimentObjects.isNotEmpty()) {
            put("bg", kotlinx.serialization.json.JsonArray(accompanimentObjects))
        }
        if (agentObjects.isNotEmpty()) {
            put("agents", kotlinx.serialization.json.JsonArray(agentObjects))
        }
    }.toString()
}

/** 和声挂到主句：line = 主句 key（与 yrc 行起点同一换算），words 带逐字时间。 */
private fun accompanimentJson(parentRawStartMs: Long, line: TtmlLine): JsonObject {
    val parentKey = lrcTimeToMs(formatLrcTime((parentRawStartMs / 10L) * 10L))
    val words = stripBackingBrackets(line.words)
    val wordsJson = words.map { word ->
        kotlinx.serialization.json.JsonArray(
            listOf(
                kotlinx.serialization.json.JsonPrimitive(word.startMs),
                kotlinx.serialization.json.JsonPrimitive(word.endMs - word.startMs),
                kotlinx.serialization.json.JsonPrimitive(word.text),
            ),
        )
    }
    return buildJsonObject {
        put("line", parentKey)
        put("start", line.startMs)
        put("end", line.endMs)
        put("text", joinWords(words))
        put("words", kotlinx.serialization.json.JsonArray(wordsJson))
    }
}

/** x-bg 的括号会落在第一个/最后一个音节里，去掉外层括号。 */
private fun stripBackingBrackets(words: List<TtmlWord>): List<TtmlWord> {
    if (words.isEmpty()) return words
    val cleaned = words.toMutableList()
    val first = cleaned.first()
    cleaned[0] = first.copy(text = first.text.trimStart('(', '（', ' ', '\u3000'))
    val lastIndex = cleaned.lastIndex
    cleaned[lastIndex] = cleaned[lastIndex].copy(
        text = cleaned[lastIndex].text.trimEnd(')', '）', ' ', '\u3000'),
    )
    return cleaned.filter { it.text.isNotBlank() }
}

private fun parseTtmlLines(ttml: String): List<TtmlLine> {
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val document = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(ttml)))
    val translations = parseLineTranslations(document)
    val rightAgents = parseRightSideAgents(document)
    val paragraphs = document.getElementsByTagNameNS("*", "p")
    val lines = ArrayList<TtmlLine>(paragraphs.length)
    for (index in 0 until paragraphs.length) {
        val paragraph = paragraphs.item(index) as? Element ?: continue
        val key = paragraph.attribute("xml:id") ?: paragraph.attribute("itunes:key") ?: paragraph.attribute("id")
        val begin = parseTtmlTime(paragraph.attribute("begin"))
        val end = parseTtmlTime(paragraph.attribute("end"))
        val alignEnd = paragraph.attribute("ttm:agent")?.let { it in rightAgents } == true
        val words = ArrayList<TtmlWord>()
        val backingWords = ArrayList<TtmlWord>()
        var inlineTranslation: String? = null
        collectParagraphContent(paragraph, words, backingWords) { translation ->
            if (inlineTranslation == null) inlineTranslation = translation
        }
        val text = joinWords(words).ifBlank { paragraph.textContent.cleanText() }
        if (text.isBlank()) continue
        val startMs = begin ?: words.minOfOrNull { it.startMs } ?: continue
        val endMs = end ?: words.maxOfOrNull { it.endMs } ?: (startMs + 3_000L)
        val translation = inlineTranslation ?: key?.let { translations[it] }
        lines += TtmlLine(
            startMs = startMs,
            endMs = endMs,
            words = words.sortedBy { it.startMs },
            text = text,
            translation = translation?.cleanText()?.takeIf { it.isNotBlank() },
            alignEnd = alignEnd,
        )
        if (backingWords.isNotEmpty()) {
            val backingText = joinWords(backingWords)
            if (backingText.isNotBlank()) {
                lines += TtmlLine(
                    startMs = backingWords.minOf { it.startMs },
                    endMs = backingWords.maxOf { it.endMs },
                    words = backingWords.sortedBy { it.startMs },
                    text = backingText,
                    translation = null,
                    backing = true,
                    backingParentStartMs = startMs,
                )
            }
        }
    }
    return lines
}

/** ttm:agent 声明顺序：第一个演唱者正常（左），其余演唱者靠右（与参考项目一致）。 */
private fun parseRightSideAgents(document: org.w3c.dom.Document): Set<String> {
    val agents = document.getElementsByTagNameNS("*", "agent")
    val right = HashSet<String>()
    var firstSeen = false
    for (index in 0 until agents.length) {
        val element = agents.item(index) as? Element ?: continue
        val id = element.attribute("xml:id") ?: element.attribute("id") ?: continue
        if (!firstSeen) {
            firstSeen = true
            continue
        }
        right += id
    }
    return right
}

private fun collectParagraphContent(
    element: Element,
    words: MutableList<TtmlWord>,
    backingWords: MutableList<TtmlWord>,
    onTranslation: (String) -> Unit,
) {
    val childNodes: Node = element
    for (index in 0 until childNodes.childNodes.length) {
        val child = childNodes.childNodes.item(index) as? Element ?: continue
        val role = child.attribute("ttm:role")
        when (role) {
            "x-bg" -> collectBackingWords(child, backingWords)
            "x-roman" -> Unit
            "x-translation" -> onTranslation(child.textContent.cleanText())
            else -> {
                val begin = parseTtmlTime(child.attribute("begin"))
                val end = parseTtmlTime(child.attribute("end"))
                if (begin != null && end != null && end > begin) {
                    val text = child.textContent.cleanText()
                    if (text.isNotBlank()) words += TtmlWord(begin, end, text)
                } else {
                    collectParagraphContent(child, words, backingWords, onTranslation)
                }
            }
        }
    }
}

/** 收集 x-bg 里的和声片段；没有逐字时退化为整段一个词。 */
private fun collectBackingWords(element: Element, out: MutableList<TtmlWord>) {
    var hasTimedChild = false
    for (index in 0 until element.childNodes.length) {
        val child = element.childNodes.item(index) as? Element ?: continue
        hasTimedChild = true
        val begin = parseTtmlTime(child.attribute("begin"))
        val end = parseTtmlTime(child.attribute("end"))
        if (begin != null && end != null && end > begin) {
            val text = child.textContent.cleanText()
            if (text.isNotBlank()) out += TtmlWord(begin, end, text)
        } else {
            collectBackingWords(child, out)
        }
    }
    if (!hasTimedChild) {
        val begin = parseTtmlTime(element.attribute("begin"))
        val end = parseTtmlTime(element.attribute("end"))
        val text = element.textContent.cleanText()
        if (begin != null && end != null && end > begin && text.isNotBlank()) {
            out += TtmlWord(begin, end, text)
        }
    }
}

private fun parseLineTranslations(document: org.w3c.dom.Document): Map<String, String> {
    val result = HashMap<String, String>()
    val translations = document.getElementsByTagNameNS("*", "translation")
    for (index in 0 until translations.length) {
        val translation = translations.item(index) as? Element ?: continue
        val language = translation.attribute("xml:lang")?.lowercase().orEmpty()
        if (language.isNotEmpty() && !language.startsWith("zh")) continue
        val texts = translation.getElementsByTagNameNS("*", "text")
        for (textIndex in 0 until texts.length) {
            val text = texts.item(textIndex) as? Element ?: continue
            val key = text.attribute("for") ?: continue
            result.putIfAbsent(key.removePrefix("#"), text.textContent.cleanText())
        }
    }
    return result
}

private fun joinWords(words: List<TtmlWord>): String {
    val builder = StringBuilder()
    words.forEachIndexed { index, word ->
        val text = word.text
        if (index > 0) {
            val previous = builder.lastOrNull()
            val first = text.firstOrNull()
            if (previous != null && first != null && !previous.isWhitespace() && !first.isWhitespace() &&
                isLatin(previous) && isLatin(first)
            ) {
                builder.append(' ')
            }
        }
        builder.append(text)
    }
    return builder.toString().replace("  ", " ").trim()
}

private fun isLatin(char: Char): Boolean =
    char.isLetterOrDigit() && char.code < 0x2010

private fun parseTtmlTime(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        value.endsWith("ms") -> value.dropLast(2).toDoubleOrNull()?.toLong()
        value.endsWith("s") -> value.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        value.contains(':') -> {
            val parts = value.split(':')
            val seconds = parts.lastOrNull()?.toDoubleOrNull() ?: return null
            val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0L
            val hours = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0L
            ((hours * 60 + minutes) * 60) * 1000L + (seconds * 1000).toLong()
        }
        else -> value.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }
}

private fun formatLrcTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val totalSeconds = safe / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val centis = (safe % 1000) / 10
    return String.format(java.util.Locale.US, "%02d:%02d.%02d", minutes, seconds, centis)
}

/** 与 player 侧 envelopeTimedLines 相同的换算逻辑。 */
private fun lrcTimeToMs(time: String): Long {
    val colon = time.indexOf(':')
    if (colon <= 0) return 0L
    val minutes = time.substring(0, colon).toLongOrNull() ?: 0L
    val seconds = time.substring(colon + 1).toDoubleOrNull() ?: 0.0
    return minutes * 60_000L + (seconds * 1000).toLong()
}

private fun String.cleanText(): String =
    replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace(Regex("\\s+"), " ").trim()

private fun Element.attribute(name: String): String? {
    getAttribute(name)?.takeIf { it.isNotBlank() }?.let { return it }
    val attributes = attributes
    for (index in 0 until attributes.length) {
        val attr = attributes.item(index) ?: continue
        val localName = attr.localName ?: attr.nodeName.substringAfter(':')
        if (localName == name.substringAfter(':')) {
            return attr.nodeValue?.takeIf { it.isNotBlank() }
        }
    }
    return null
}
