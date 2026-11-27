package com.exodidio.tune.lyrics.autosync

import android.content.Context
import android.util.Log
import com.exodidio.tune.lyrics.hasWordTimingLyrics
import com.exodidio.tune.ui.navigation.parsePlayerLyricsDocument
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Turns line-timed lyrics into a word-level timeline with local ONNX models.
 *
 * Text is never rewritten: lines the model cannot align keep their provider
 * timing. Output is re-emitted as a yrc document so the existing renderer can
 * consume it unchanged.
 */
internal object AutoSyncCoordinator {
    private const val TAG = "TuneAutoSync"
    private const val SAMPLE_RATE = 16_000
    private const val PADDING_MS = 300L
    private const val MAX_LINE_MS = 12_000L
    private const val MIN_LINE_MS = 250L
    private const val MinWordMs = 40L
    private val lock = Mutex()

    data class SyncLine(val index: Int, val text: String, val startMs: Long, val endMs: Long, val secondary: String?)

    data class Result(val content: String, val timeline: AutoSyncTimeline)

    /** True when the content already carries word timing (yrc/AMLL/enhanced LRC). */
    fun hasWordTiming(content: String?): Boolean =
        content != null && (
            hasWordTimingLyrics(content) ||
                parsePlayerLyricsDocument(content).lines.any { it.words.isNotEmpty() }
            )

    suspend fun align(
        context: Context,
        trackId: String,
        content: String,
        audioSource: String?,
        onProgress: ((Float) -> Unit)? = null,
    ): Result? = lock.withLock {
        try {
            alignLocked(context, trackId, content, audioSource, onProgress)
        } finally {
            // 照 MeloX：任务结束（含取消）后清空共享会话缓存。
            // 调用方不再 close() 共享引擎，否则缓存里会留下已关闭的 session。
            OnnxCtcEngine.releaseAll()
            ZhAlignmentEngine.releaseAll()
        }
    }

    private suspend fun alignLocked(
        context: Context,
        trackId: String,
        content: String,
        audioSource: String?,
        onProgress: ((Float) -> Unit)?,
    ): Result? {
        if (hasWordTiming(content)) return null
        val parsed = parsePlayerLyricsDocument(content)
        if (parsed.lines.isEmpty()) return null
        if (parsed.lines.any { it.words.isNotEmpty() }) return null
        val lines = buildLines(parsed.lines) ?: return null
        if (lines.isEmpty()) return null
        val sourceHash = AutoSyncTimelineStore.sourceHash(
            lines.joinToString("\n") { "${it.startMs}|${it.text}" },
        )

        val installed = AutoSyncModelStore.installedLanguages(context)
        if (installed.isEmpty()) {
            Log.i(TAG, "no models installed; keeping line lyrics for $trackId")
            return null
        }
        val planned = lines.mapNotNull { line ->
            if (!AutoSyncText.isAlignable(line.text)) {
                AutoSyncTrace.log(context, "line ${line.index} skipped: credit/instrumental")
                return@mapNotNull null
            }
            val language = AutoSyncText.detectLineLanguage(line.text)
            val tag = when (language) {
                AutoSyncLineLanguage.Zh -> "zh"
                AutoSyncLineLanguage.En -> "en"
                AutoSyncLineLanguage.Ja -> "ja"
                AutoSyncLineLanguage.Unknown -> {
                    AutoSyncTrace.log(context, "line ${line.index} skipped: mixed/unknown script")
                    return@mapNotNull null
                }
            }
            if (tag !in installed) null else line.index to language
        }
        if (planned.isEmpty()) {
            AutoSyncTrace.log(context, "align skipped: no alignable lines track=$trackId models=$installed")
            return null
        }

        // Cached timeline for this exact text + models avoids re-running inference.
        val cached = AutoSyncTimelineStore.load(context, trackId)
        val cachedLanguages = cached?.language?.split('+')?.filter { it.isNotBlank() }.orEmpty()
        if (cached != null && cached.sourceHash == sourceHash && cachedLanguages.isNotEmpty()) {
            val expectedModelKey = AutoSyncModelStore.timelineModelKey(context, cachedLanguages)
            if (expectedModelKey == null || cached.modelKey == expectedModelKey) {
                val contentOut = buildContent(content, lines, timingsOf(cached))
                if (contentOut != null) {
                    Log.i(TAG, "reused cached alignment for $trackId")
                    return Result(contentOut, cached)
                }
            }
        }

        // 失败不写“已尝试”标记：MeloX 的接入方式是每次加载都重试，
        // 瞬时失败（取流/解码）不能变成整个进程内永久不生效。
        val pcm = AutoSyncPcm.decode(audioSource) ?: run {
            AutoSyncTrace.log(context, "align skipped: audio decode failed source=$audioSource")
            return null
        }
        if (pcm.isEmpty()) {
            AutoSyncTrace.log(context, "align skipped: decoded audio empty source=$audioSource")
            return null
        }
        val totalMs = pcm.size * 1000L / SAMPLE_RATE
        // 流式解码可能被截断（试听片段/中途断开），歌词尾部会整段落空。
        val lastLineMs = lines.maxOf { it.startMs }
        AutoSyncTrace.log(
            context,
            "decoded audio=${totalMs}ms lastLine=${lastLineMs}ms track=$trackId " +
                (if (totalMs + 1_000L < lastLineMs) "TRUNCATED" else "ok"),
        )

        val timings = LinkedHashMap<Int, List<AutoSyncUnit>>()
        val engineKeys = LinkedHashMap<String, String>()
        val groups = planned.groupBy({ it.second }, { it.first }).entries
            .sortedBy { (_, indices) -> indices.first() }

        groups.forEach { (language, indices) ->
            currentCoroutineContext().ensureActive()
            val engine: LineEngine = when (language) {
                AutoSyncLineLanguage.Zh -> ZhAlignmentEngine.forLanguage(context, "zh")
                    ?.let { ZhEngineHandle(it) }
                    ?: run {
                        AutoSyncTrace.log(context, "zh engine unavailable track=$trackId")
                        return@forEach
                    }
                AutoSyncLineLanguage.En -> OnnxCtcEngine.forLanguage(context, "en")
                    ?.let { CtcEngineHandle(it) }
                    ?: run {
                        AutoSyncTrace.log(context, "en engine unavailable track=$trackId")
                        return@forEach
                    }
                AutoSyncLineLanguage.Ja -> OnnxCtcEngine.forLanguage(context, "ja")
                    ?.let { JaEngineHandle(it, context) }
                    ?: run {
                        AutoSyncTrace.log(context, "ja engine unavailable track=$trackId")
                        return@forEach
                    }
                AutoSyncLineLanguage.Unknown -> return@forEach
            }
            engineKeys[language.tag] = engine.modelLabel
            val timingCountBefore = timings.size
            indices.forEach { lineIndex ->
                currentCoroutineContext().ensureActive()
                val line = lines.firstOrNull { it.index == lineIndex } ?: return@forEach
                val window = windowFor(lines, line, totalMs) ?: run {
                    AutoSyncTrace.log(context, "line $lineIndex skipped: window outside audio")
                    return@forEach
                }
                val startSample = (window.first * SAMPLE_RATE / 1000L).toInt()
                    .coerceIn(0, pcm.size)
                val endSample = (window.second * SAMPLE_RATE / 1000L).toInt()
                    .coerceIn(startSample, pcm.size)
                if (endSample - startSample < SAMPLE_RATE / 10) {
                    AutoSyncTrace.log(context, "line $lineIndex skipped: window shorter than 100ms")
                    return@forEach
                }
                val snippet = pcm.copyOfRange(startSample, endSample)
                val units = runCatching { engine.alignLine(line.text, snippet) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "alignment failed for line $lineIndex", error)
                        AutoSyncTrace.log(context, "line alignment failed index=$lineIndex", error)
                    }
                    .getOrNull()
                val sanitized = sanitize(units.orEmpty(), snippet.size * 1000L / SAMPLE_RATE)
                if (sanitized.isNotEmpty()) {
                    timings[lineIndex] = sanitized.map {
                        AutoSyncUnit(it.text, window.first + it.startMs, window.first + it.endMs)
                    }
                } else {
                    AutoSyncTrace.log(
                        context,
                        "line $lineIndex (${language.tag}) not aligned; keeping line timing",
                    )
                }
                onProgress?.invoke(lineIndex.toFloat() / lines.size.coerceAtLeast(1))
            }
            AutoSyncTrace.log(
                context,
                "group ${language.tag} lines=${indices.size} aligned=${timings.size - timingCountBefore}",
            )
        }
        if (timings.isEmpty()) {
            AutoSyncTrace.log(context, "align produced no timing track=$trackId lines=${lines.size}")
            return null
        }

        val languages = engineKeys.keys.mapNotNull { tag ->
            when (tag) {
                "zh" -> "zh"
                "en" -> "en"
                "ja" -> "ja"
                else -> null
            }
        }.sorted()
        val modelKey = AutoSyncModelStore.timelineModelKey(context, languages)
            ?: engineKeys.entries.joinToString("+") { "${it.key}:${it.value}" }
        val timeline = AutoSyncTimeline(
            trackKey = trackId,
            sourceHash = sourceHash,
            language = engineKeys.keys.sorted().joinToString("+"),
            engine = engineKeys.entries.joinToString("+") { "${it.key}:${it.value}" },
            lines = lines.mapNotNull { line ->
                timings[line.index]?.let { AutoSyncLineTiming(line.index, it, 0.0) }
            },
            createdAtMs = System.currentTimeMillis(),
            modelKey = modelKey,
        )
        AutoSyncTimelineStore.save(context, timeline)
        val contentOut = buildContent(content, lines, timings) ?: return null
        onProgress?.invoke(1f)
        return Result(contentOut, timeline)
    }

    // ---- lyric parsing & output -------------------------------------------

    private fun buildLines(lines: List<com.exodidio.tune.ui.navigation.PlayerLyricLine>): List<SyncLine>? {
        val timed = lines.filter { it.timestampSeconds != null }
        if (timed.isEmpty()) return null
        val out = ArrayList<SyncLine>(timed.size)
        timed.forEachIndexed { index, line ->
            if (line.isCountdown) return@forEachIndexed
            val startMs = ((line.timestampSeconds ?: 0f) * 1000f).toLong()
            val nextStart = timed.getOrNull(index + 1)?.timestampSeconds?.let { (it * 1000f).toLong() }
            val endMs = line.endTimestampSeconds?.let { (it * 1000f).toLong() }
                ?: nextStart
                ?: (startMs + MAX_LINE_MS)
            val text = line.primary.trim()
            if (text.isEmpty()) return@forEachIndexed
            out += SyncLine(
                index = index,
                text = text,
                startMs = startMs,
                endMs = max(endMs, startMs + 1L),
                secondary = line.secondary,
            )
        }
        return out
    }

    private fun timingsOf(timeline: AutoSyncTimeline): Map<Int, List<AutoSyncUnit>> =
        timeline.lines.associate { it.index to it.units }

    /** Re-emits a yrc envelope so the existing word renderer picks it up. */
    private fun buildContent(
        original: String,
        lines: List<SyncLine>,
        timings: Map<Int, List<AutoSyncUnit>>,
    ): String? {
        if (timings.isEmpty()) return null
        val yrc = StringBuilder()
        val lrc = StringBuilder()
        val tlyric = StringBuilder()
        var previousWordEnd = 0L
        lines.forEachIndexed { position, line ->
            val nextStart = lines.getOrNull(position + 1)?.startMs
            lrc.append(formatLrc(line.startMs)).append(line.text).append('\n')
            line.secondary?.takeIf { it.isNotBlank() }?.let {
                tlyric.append(formatLrc(line.startMs)).append(it).append('\n')
            }
            val units = normalizeUnits(timings[line.index].orEmpty(), line.startMs, nextStart, previousWordEnd)
            val duration = if (units.isNotEmpty()) {
                (units.last().endMs - line.startMs).coerceAtLeast(1L)
            } else {
                (line.endMs - line.startMs).coerceAtLeast(1L)
            }
            yrc.append('[').append(line.startMs).append(',').append(duration).append(']')
            if (units.isEmpty()) {
                yrc.append(safeYrcText(line.text))
            } else {
                val spaced = AutoSyncText.detectLineLanguage(line.text) == AutoSyncLineLanguage.En
                units.forEachIndexed { index, unit ->
                    yrc.append('(')
                        .append(unit.startMs.coerceAtLeast(0L))
                        .append(',')
                        .append((unit.endMs - unit.startMs).coerceAtLeast(1L))
                        .append(",0)")
                        .append(safeYrcText(unit.text))
                    if (spaced && index != units.lastIndex) yrc.append(' ')
                }
                previousWordEnd = units.last().endMs
            }
            yrc.append('\n')
        }
        val originalJson = runCatching { JSONObject(original) }.getOrNull()
        val envelope = JSONObject()
            .put("tuneLyrics", 1)
            .put("lrc", lrc.toString())
            .put("yrc", yrc.toString())
        // 优先用按行重组的三位毫秒 tlyric：key 与 yrc 行起点完全一致，
        // 原始 tlyric 的百分秒精度会与毫秒行头对不上，导致部分翻译丢失。
        val existingTranslation = originalJson?.optString("tlyric").orEmpty()
        envelope.put("tlyric", tlyric.toString().ifBlank { existingTranslation })
        return envelope.toString()
    }

    private fun normalizeUnits(
        units: List<AutoSyncUnit>,
        lineStartMs: Long,
        nextLineStartMs: Long?,
        previousWordEndMs: Long,
    ): List<AutoSyncUnit> = normalizeAutoSyncUnits(
        units = units,
        lineStartMs = lineStartMs,
        nextLineStartMs = nextLineStartMs,
        previousWordEndMs = previousWordEndMs,
        minWordMs = MinWordMs,
    )

    /** YRC tokenises text after `(start,dur,0)` up to the next '('; avoid breaking it. */
    private fun safeYrcText(text: String): String = text.replace('(', '（')

    private fun formatLrc(ms: Long): String {
        val totalSeconds = ms / 1000L
        return "[%02d:%02d.%03d]".format(
            totalSeconds / 60,
            totalSeconds % 60,
            ms % 1000L,
        )
    }

    private fun windowFor(lines: List<SyncLine>, line: SyncLine, totalMs: Long): Pair<Long, Long>? {
        val nextStart = lines.firstOrNull { it.index > line.index }?.startMs
        var start = max(0L, line.startMs - PADDING_MS)
        var end = min(nextStart?.takeIf { it > line.startMs } ?: line.endMs, totalMs)
        end = min(totalMs, end + PADDING_MS)
        if (end - line.startMs > MAX_LINE_MS) end = min(totalMs, line.startMs + MAX_LINE_MS)
        if (end <= start) end = min(totalMs, start + MAX_LINE_MS)
        if (end - start < MIN_LINE_MS) return null
        return start to end
    }

    private fun sanitize(units: List<AutoSyncUnit>, windowMs: Long): List<AutoSyncUnit> {
        if (units.isEmpty()) return units
        val result = ArrayList<AutoSyncUnit>(units.size)
        var lastEnd = 0L
        units.forEach { unit ->
            var start = unit.startMs.coerceIn(0L, windowMs)
            var end = unit.endMs.coerceIn(0L, windowMs)
            if (end < start) {
                val swap = start
                start = end
                end = swap
            }
            if (start < lastEnd) start = lastEnd
            if (end <= start) end = min(windowMs, start + 10L)
            if (end <= start) return@forEach
            result += AutoSyncUnit(unit.text, start, end)
            lastEnd = end
        }
        return result
    }

    // ---- engines ----------------------------------------------------------

    private interface LineEngine {
        val modelLabel: String
        fun alignLine(text: String, snippet: FloatArray): List<AutoSyncUnit>
        fun close()
    }

    private class CtcEngineHandle(private val engine: OnnxCtcEngine) : LineEngine {
        override val modelLabel: String get() = engine.sourceModel
        override fun alignLine(text: String, snippet: FloatArray): List<AutoSyncUnit> {
            val normalized = AutoSyncText.normalizeEn(text)
            val tokenIds = AutoSyncText.tokenIds(normalized, engine.manifest)
            if (tokenIds.isEmpty()) return emptyList()
            val alignment = engine.alignWindow(snippet, tokenIds)
            if (!alignment.feasible) return emptyList()
            return AutoSyncText.groupWords(
                originalText = text,
                normalized = normalized,
                tokenIds = tokenIds,
                spans = alignment.spans,
                manifest = engine.manifest,
                frameHopSeconds = engine.manifest.frameHopSeconds,
            )
        }

        override fun close() = engine.close()
    }

    private class JaEngineHandle(
        private val engine: OnnxCtcEngine,
        private val context: Context,
    ) : LineEngine {
        override val modelLabel: String get() = engine.sourceModel
        private var diagnosed = false

        override fun alignLine(text: String, snippet: FloatArray): List<AutoSyncUnit> {
            if (!JaOpenJtalk.ensureDictionaryInstalled(context)) {
                if (!diagnosed) {
                    diagnosed = true
                    val dictionary = AutoSyncDictionary.directory(context)
                    AutoSyncTrace.log(
                        context,
                        "ja G2P unavailable: dictionary=${dictionary?.absolutePath ?: "missing"} " +
                            "library=${JaOpenJtalk.isLibraryAvailable()}",
                    )
                }
                return emptyList()
            }
            val mapping = runCatching { JapaneseG2pMapper.map(text) }
                .onFailure { AutoSyncTrace.log(context, "ja G2P mapper failed", it) }
                .getOrNull() ?: return emptyList()
            if (mapping.phonemes.isEmpty()) {
                if (!diagnosed) {
                    diagnosed = true
                    AutoSyncTrace.log(context, "ja G2P produced no phonemes (dictionary loaded)")
                }
                return emptyList()
            }
            val manifest = engine.manifest
            val lookup = HashMap<String, Int>(manifest.tokens.size * 2)
            manifest.tokens.forEachIndexed { index, token -> lookup.putIfAbsent(token, index) }
            val ids = ArrayList<Int>(mapping.phonemes.size)
            val keptIndices = ArrayList<Int>(mapping.phonemes.size)
            mapping.phonemes.forEachIndexed { index, phoneme ->
                lookup[phoneme]?.let { id ->
                    ids += id
                    keptIndices += index
                }
            }
            if (ids.isEmpty()) return emptyList()
            val alignment = engine.alignWindow(snippet, ids)
            if (!alignment.feasible) return emptyList()
            val spansByPhoneme = HashMap<Int, CtcForcedAligner.Span>(alignment.spans.size)
            alignment.spans.forEachIndexed { position, span ->
                val phonemeIndex = keptIndices.getOrNull(position) ?: return@forEachIndexed
                if (span != null) spansByPhoneme[phonemeIndex] = span
            }
            return mapSpansToCharUnits(
                readings = mapping.readings,
                phonemes = mapping.phonemes,
                spansByPhoneme = spansByPhoneme,
                frameSeconds = manifest.frameHopSeconds,
            ).orEmpty()
        }

        override fun close() = engine.close()
    }

    private class ZhEngineHandle(private val engine: ZhAlignmentEngine) : LineEngine {
        override val modelLabel: String get() = engine.manifest.sourceModel

        override fun alignLine(text: String, snippet: FloatArray): List<AutoSyncUnit> {
            if (!ZhText.isAlignableForChinese(text)) return emptyList()
            val normalized = ZhText.normalize(text)
            val tokenIds = ZhText.tokenIds(normalized, engine.manifest)
            if (tokenIds.isEmpty()) return emptyList()
            val spans = engine.alignWindow(snippet, tokenIds) ?: return emptyList()
            return chineseUnits(text, normalized, engine.manifest, spans)
        }

        override fun close() = engine.close()
    }

    private fun mapSpansToCharUnits(
        readings: List<JapaneseG2pMapper.CharReading>,
        phonemes: List<String>,
        spansByPhoneme: Map<Int, CtcForcedAligner.Span>,
        frameSeconds: Double,
    ): List<AutoSyncUnit>? {
        val inner = readings.flatMap { it.phonemes }
        val offset = locateInnerOffset(phonemes, inner) ?: return null
        val resolved = arrayOfNulls<Pair<Double, Double>>(readings.size)
        var cursor = offset
        readings.forEachIndexed { index, reading ->
            val count = reading.phonemes.size
            var start: Double? = null
            var end = 0.0
            for (k in 0 until count) {
                val span = spansByPhoneme[cursor + k] ?: continue
                val spanStart = span.startFrame * frameSeconds
                val spanEnd = (span.endFrame + 1) * frameSeconds
                if (start == null) start = spanStart
                end = spanEnd
            }
            resolved[index] = start?.let { it to end }
            cursor += count
        }
        val previousEnd = DoubleArray(resolved.size)
        var running = 0.0
        resolved.indices.forEach { index ->
            previousEnd[index] = running
            resolved[index]?.let { running = it.second }
        }
        val nextStart = arrayOfNulls<Double>(resolved.size)
        var upcoming: Double? = null
        for (index in resolved.indices.reversed()) {
            resolved[index]?.let { upcoming = it.first }
            nextStart[index] = upcoming
        }
        val previousKnown = BooleanArray(resolved.size)
        var seen = false
        resolved.indices.forEach { index ->
            previousKnown[index] = seen
            if (resolved[index] != null) seen = true
        }
        return readings.mapIndexed { index, reading ->
            val value = resolved[index]
            if (value != null) {
                AutoSyncUnit(
                    text = reading.char,
                    startMs = (value.first * 1000.0).roundToLong(),
                    endMs = (value.second * 1000.0).roundToLong(),
                )
            } else {
                val position = when {
                    previousKnown[index] -> previousEnd[index]
                    nextStart[index] != null -> nextStart[index]!!
                    else -> 0.0
                }
                val ms = (position * 1000.0).roundToLong()
                AutoSyncUnit(text = reading.char, startMs = ms, endMs = ms)
            }
        }
    }

    private fun locateInnerOffset(full: List<String>, inner: List<String>): Int? {
        if (inner.isEmpty()) return 0
        for (offset in 0..(full.size - inner.size)) {
            if (full.subList(offset, offset + inner.size) == inner) return offset
        }
        return null
    }

    private fun chineseUnits(
        original: String,
        normalized: String,
        manifest: AutoSyncManifest,
        spans: List<ZhCifPostprocess.TokenSpan>,
    ): List<AutoSyncUnit> {
        if (spans.isEmpty() || normalized.isEmpty()) return emptyList()
        val lookup = HashMap<String, Int>(manifest.tokens.size * 2)
        manifest.tokens.forEachIndexed { index, token -> lookup.putIfAbsent(token, index) }
        val keptPositions = ArrayList<Int>(normalized.length)
        for (index in normalized.indices) {
            if (lookup.containsKey(normalized[index].toString())) keptPositions.add(index)
        }
        if (keptPositions.size != spans.size) return emptyList()
        val toOriginal = HashMap<Int, Int>(normalized.length * 2)
        var cursor = 0
        for (index in original.indices) {
            if (cursor < normalized.length && original[index] == normalized[cursor]) {
                toOriginal[cursor] = index
                cursor++
            }
        }
        if (cursor != normalized.length) return emptyList()
        val units = ArrayList<AutoSyncUnit>(spans.size)
        for (index in spans.indices) {
            val originalIndex = toOriginal[keptPositions[index]] ?: return emptyList()
            val character = original[originalIndex]
            if (character.isWhitespace()) continue
            units.add(AutoSyncUnit(character.toString(), spans[index].startMs, spans[index].endMs))
        }
        return units
    }
}

/**
 * 逐字时间整形：跨行单调、行内不越过下一行起点、按比例压缩超出预算的句尾单元，
 * 避免句尾多字被压进 1ms 造成的闪烁和“前进后又退回”。
 */
internal fun normalizeAutoSyncUnits(
    units: List<AutoSyncUnit>,
    lineStartMs: Long,
    nextLineStartMs: Long?,
    previousWordEndMs: Long,
    minWordMs: Long = 40L,
): List<AutoSyncUnit> {
    if (units.isEmpty()) return units
    val floorStart = maxOf(previousWordEndMs, lineStartMs)
    if (nextLineStartMs == null) {
        val out = ArrayList<AutoSyncUnit>(units.size)
        var cursor = floorStart
        units.forEach { unit ->
            val start = maxOf(unit.startMs, cursor)
            val end = maxOf(unit.endMs, start + minWordMs)
            out += AutoSyncUnit(unit.text, start, end)
            cursor = end
        }
        return out
    }
    if (nextLineStartMs <= floorStart) {
        return units.mapIndexed { idx, unit ->
            val start = floorStart + idx.toLong()
            AutoSyncUnit(unit.text, start, start + 1L)
        }
    }
    val budget = nextLineStartMs - floorStart
    if (budget < units.size) {
        return units.mapIndexed { idx, unit ->
            val start = floorStart + idx.toLong()
            AutoSyncUnit(unit.text, start, start + 1L)
        }
    }
    val rawGaps = LongArray(units.size)
    val rawDurs = LongArray(units.size)
    val minEach = minOf(minWordMs, maxOf(1L, budget / units.size))
    var prevRawEnd = floorStart
    for (i in units.indices) {
        val u = units[i]
        rawGaps[i] = (u.startMs - prevRawEnd).coerceAtLeast(0L)
        val rawDur = (u.endMs - u.startMs).coerceAtLeast(1L)
        rawDurs[i] = maxOf(rawDur, minEach)
        prevRawEnd = maxOf(u.startMs + rawDur, prevRawEnd + rawDur)
    }
    val totalRawDurs = rawDurs.sum()
    val totalRawGaps = rawGaps.sum()
    if (totalRawGaps + totalRawDurs <= budget) {
        val out = ArrayList<AutoSyncUnit>(units.size)
        var cursor = floorStart
        for (i in units.indices) {
            val start = cursor + rawGaps[i]
            val end = start + rawDurs[i]
            out += AutoSyncUnit(units[i].text, start, end)
            cursor = end
        }
        return out
    }
    if (totalRawDurs <= budget && totalRawGaps > 0L) {
        val remainingForGaps = budget - totalRawDurs
        val out = ArrayList<AutoSyncUnit>(units.size)
        var cursor = floorStart
        var gapUsed = 0L
        var gapCum = 0L
        for (i in units.indices) {
            gapCum += rawGaps[i]
            val targetGapTotal = (gapCum.toDouble() * remainingForGaps / totalRawGaps).roundToLong()
            val gap = (targetGapTotal - gapUsed).coerceAtLeast(0L)
            gapUsed += gap
            val start = cursor + gap
            val end = start + rawDurs[i]
            out += AutoSyncUnit(units[i].text, start, end)
            cursor = end
        }
        return out
    }
    val floorEach = 1L
    val distributable = budget - floorEach * units.size
    val totalWeight = totalRawDurs.coerceAtLeast(1L)
    val out = ArrayList<AutoSyncUnit>(units.size)
    var cursor = floorStart
    var distributed = 0L
    var cumWeight = 0L
    for (i in units.indices) {
        cumWeight += rawDurs[i]
        val targetDist = if (i == units.lastIndex) {
            distributable
        } else {
            (cumWeight.toDouble() * distributable / totalWeight).roundToLong()
        }
        val extra = (targetDist - distributed).coerceAtLeast(0L)
        distributed += extra
        val start = cursor
        val end = start + floorEach + extra
        out += AutoSyncUnit(units[i].text, start, end)
        cursor = end
    }
    return out
}
