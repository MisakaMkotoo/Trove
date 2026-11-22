package com.exodidio.tune.ui.navigation

import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exodidio.tune.R
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.components.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
)

internal data class ParsedPlayerLyrics(
    val lines: List<PlayerLyricLine>,
    val firstVocalStartMs: Long? = null,
)

private val TimestampedLyricLine = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)$")
private val LeadingLyricTimestamp = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")
private val BilingualSeparator = Regex("\\s*\\^\\s*|\\s*/\\s*")
private const val ForwardSeekAnimatedApproachRows = 3
/** 手动翻动歌词后，静止这么久就回到跟随播放的状态。 */
private const val BrowseIdleReturnMs = 5_000L
/** 歌词绘制层满幅到屏幕边，文字内容缩进这段距离。 */
private val LyricsContentHorizontalInset = 35.dp

/** 歌词字号档位：0 小、1 标准（默认）、2 大。 */
internal fun lyricFontScale(level: Int): Float = when (level) {
    0 -> 0.92f
    1 -> 1f
    else -> 1.12f
}

private fun androidx.compose.ui.text.TextStyle.scaledBy(scale: Float): androidx.compose.ui.text.TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

internal enum class LyricsSeekDirection { Backward, Forward }
private enum class LyricsScrollMode { Follow, Browse }
internal fun lyricsSeekDirection(targetIndex: Int, firstVisibleIndex: Int): LyricsSeekDirection =
    if (targetIndex < firstVisibleIndex) LyricsSeekDirection.Backward else LyricsSeekDirection.Forward

private val TtmlParagraph = Regex("<p[^>]*begin=\"([^\"]+)\"[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
private val TtmlTag = Regex("<[^>]+>")

internal fun parseTtmlTimestamp(value: String): Float? {
    val v = value.trim()
    // Formats: HH:MM:SS.mmm, MM:SS.mmm, SS.mmm, with comma or dot
    val normalized = v.replace(',', '.')
    val parts = normalized.split(':')
    return try {
        when (parts.size) {
            3 -> parts[0].toFloat() * 3600f + parts[1].toFloat() * 60f + parts[2].toFloat()
            2 -> parts[0].toFloat() * 60f + parts[1].toFloat()
            1 -> parts[0].trimEnd('s').toFloat()
            else -> null
        }
    } catch (_: Exception) { null }
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
    System.err.println("LYRPARSE chars=${content.length} lines=${parsed.size} timed=$timed lastTs=$last")
    if (timed == 0) {
        System.err.println("LYRPARSE sample=${content.take(160).replace('\n', ' ')}")
    }
    return parsed
}

private val YrcLinePattern = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
private val YrcWordPattern = Regex("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)")
private val TimedLinePattern = Regex("^\\[(\\d+):(\\d+(?:[.:]\\d+)?)\\](.*)$")
private val LyricCreditPattern = Regex(
    "(?i)^\\s*(作词|作曲|编曲|制作人?|监制|出品|录音|混音|母带|和声|吉他|贝斯|鼓|弦乐|管乐|人声|词|曲|" +
        "lyricist|composer|arranger|producer|vocals?|mixed|mastered|recorded|guitar|bass|drums)\\b",
)

/** 解析歌词文本：网易云逐字封装优先，其次普通 LRC。 */
internal fun parsePlayerLyricsDocument(content: String?): ParsedPlayerLyrics {
    if (content.isNullOrBlank()) return ParsedPlayerLyrics(emptyList())
    val envelope = runCatching {
        if (content.trimStart().startsWith("{") && content.contains("\"tuneLyrics\"")) {
            kotlinx.serialization.json.Json.parseToJsonElement(content) as? kotlinx.serialization.json.JsonObject
        } else {
            null
        }
    }.getOrNull()
    if (envelope != null) {
        val yrc = envelope["yrc"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty()
        if (yrc.isNotBlank()) {
            val translations = envelopeTimedLines(
                envelope["ytlrc"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty()
                    .ifBlank {
                        envelope["tlyric"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty()
                    },
            )
            val lines = parseYrcLines(yrc, translations)
            if (lines.isNotEmpty()) {
                return ParsedPlayerLyrics(lines, firstVocalStartMs(lines))
            }
        }
        val fallback = envelope["lrc"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty()
        val lines = parsePlayerLyrics(fallback)
        return ParsedPlayerLyrics(lines, firstVocalStartMs(lines))
    }
    val lines = parsePlayerLyrics(content)
    return ParsedPlayerLyrics(lines, firstVocalStartMs(lines))
}

private fun envelopeTimedLines(content: String): Map<Long, String> {
    if (content.isBlank()) return emptyMap()
    return content.lineSequence().mapNotNull { raw ->
        val match = TimedLinePattern.find(raw.trim()) ?: return@mapNotNull null
        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val seconds = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        val text = match.groupValues[3].trim()
        if (text.isBlank()) null else (minutes * 60_000L + (seconds * 1000).toLong()) to text
    }.toMap()
}

private fun parseYrcLines(content: String, translations: Map<Long, String>): List<PlayerLyricLine> =
    content.lineSequence().mapNotNull { raw ->
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
        if (text.isEmpty() || lineDurationMs <= 0L) return@mapNotNull null
        PlayerLyricLine(
            primary = text,
            secondary = translations[lineStartMs],
            timestampSeconds = lineStartMs / 1_000f,
            endTimestampSeconds = (lineStartMs + lineDurationMs) / 1_000f,
            words = words,
        )
    }.toList()

/** 开唱前的三连点行：三个点各占开唱前时长的三分之一。 */
private fun buildCountdownLine(firstVocalStartMs: Long): PlayerLyricLine {
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

@Composable
internal fun FullScreenPlayerLyricsPanel(
    trackId: String,
    lyrics: String?,
    loading: Boolean = false,
    isPlaying: Boolean = true,
    translationVisible: Boolean = true,
    onTranslationToggle: () -> Unit = {},
    springStrength: Float = 170f,
    currentPositionMs: Long,
    pendingSeekPositionMs: Long? = null,
    seekRequestId: Long = 0L,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    /** 底部被遮挡层盖住的高度（px，已乘淡出进度）；用于歌词容器的边缘模糊截止线。 */
    bottomInsetPx: Float = 0f,
    /** 底部播放条的固定高度（px，不含淡出进度），翻译按钮按这个高度固定在其上方。 */
    controlsInsetPx: Float = 0f,
    /** 全屏（控件隐藏）时不显示翻译按钮。 */
    translationButtonVisible: Boolean = true,
    /** 点翻译按钮时重置全屏计时（与其它 UI 区域一致）。 */
    onInteraction: () -> Unit = {},
    /** 点歌词视图空白处：唤出底部控件。 */
    onShowControls: () -> Unit = {},
    /** 向下滑动歌词：进入全屏（隐藏底部控件）。 */
    onHideControls: () -> Unit = {},
    /** 点歌词文字：只跳转并重新计时，不唤出控件。 */
    onSeekInteraction: () -> Unit = {},
    /** 歌词字号档位：0 小、1 标准、2 大。 */
    fontLevel: Int = 1,
) {
    val fontScale = lyricFontScale(fontLevel)
    val parsedDocument = remember(lyrics) { parsePlayerLyricsDocument(lyrics) }
    // 开唱前插入一行“三连点歌词”：开唱前的时间三等分，三个点按普通词依次扫亮/上抬。
    val parsedLines = remember(parsedDocument) {
        val first = parsedDocument.firstVocalStartMs
        if (first != null && first >= 1_500L && parsedDocument.lines.isNotEmpty()) {
            val firstSeconds = first / 1_000f
            // 开唱前的制作人员名单不进歌词列表，避免抢走倒计时行的当前行位置。
            val visibleLines = parsedDocument.lines.filter { line ->
                val timestamp = line.timestampSeconds
                timestamp == null || timestamp >= firstSeconds || LyricCreditPattern.find(line.primary) == null
            }
            listOf(buildCountdownLine(first)) + visibleLines
        } else {
            parsedDocument.lines
        }
    }
    val syncedLines = remember(parsedLines) { parsedLines.filter { it.timestampSeconds != null } }
    val hasTranslation = remember(parsedLines) { parsedLines.any { it.secondary != null } }
    // 每次重绘都投最高刷新率：ARR 默认会把无触摸的自动滚动压到 60Hz。
    val view = LocalView.current
    val maxRefreshRate = remember(view) { view.display?.supportedModes?.maxByOrNull { it.refreshRate }?.refreshRate ?: 120f }
    Box(
        modifier = modifier
            .preferredFrameRate(maxRefreshRate)
            .pointerInput(Unit) {
                // 只有没被歌词文字消费的点击才会到这里：行间、短句右侧、上下留白。
                detectTapGestures { onShowControls() }
            },
    ) {
        when {
            loading -> LyricsLoadingState(
                Modifier.fillMaxSize().padding(horizontal = LyricsContentHorizontalInset),
            )
            lyrics.isNullOrBlank() -> LyricsEmptyState(
                Modifier.fillMaxSize().padding(horizontal = LyricsContentHorizontalInset),
            )
            syncedLines.isNotEmpty() -> SyncedLyricsList(
                trackId,
                syncedLines,
                currentPositionMs,
                pendingSeekPositionMs,
                seekRequestId,
                onSeek,
                Modifier.fillMaxSize(),
                translationVisible,
                isPlaying = isPlaying,
                springStrength = springStrength,
                fullscreen = fullscreen,
                bottomInsetPx = bottomInsetPx,
                onShowControls = onShowControls,
                onHideControls = onHideControls,
                onSeekInteraction = onSeekInteraction,
                fontScale = fontScale,
            )
            else -> PlainLyricsList(
                parsedLines,
                Modifier.fillMaxSize(),
                translationVisible,
                bottomInsetPx = bottomInsetPx,
                fullscreen = fullscreen,
                fontScale = fontScale,
                onShowControls = onShowControls,
                onHideControls = onHideControls,
            )
        }
        if (!loading && !lyrics.isNullOrBlank() && hasTranslation && translationButtonVisible) {
            val controlsInsetDp = with(LocalDensity.current) { controlsInsetPx.toDp() }
            TranslationToggle(
                enabled = translationVisible,
                onClick = {
                    onInteraction()
                    onTranslationToggle()
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = LyricsContentHorizontalInset,
                        bottom = controlsInsetDp + 12.dp + with(LocalDensity.current) { 25f.toDp() },
                    ),
            )
        }
    }
}

@Composable
private fun LyricsLoadingState(modifier: Modifier) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        Column(modifier = modifier.padding(top = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            listOf(280.dp, 108.dp, 238.dp, 84.dp, 264.dp).forEach { width ->
                Box(
                    Modifier
                        .height(20.dp)
                        .width(width)
                        .background(colors.foregroundSubtle.copy(alpha = .10f), RoundedCornerShape(8.dp))
                        .testTag("lyrics_loading_skeleton"),
                )
            }
        }
    } else {
        val transition = rememberInfiniteTransition(label = "lyrics-loading")
        val shimmerOffset by transition.animateFloat(
            initialValue = -220f,
            targetValue = 500f,
            animationSpec = infiniteRepeatable(tween(1_700, easing = LinearEasing), RepeatMode.Restart),
            label = "lyrics-loading-shimmer",
        )
        val shimmer = Brush.linearGradient(
            colors = listOf(
                colors.foregroundSubtle.copy(alpha = .08f),
                colors.foregroundSubtle.copy(alpha = .16f),
                colors.foregroundSubtle.copy(alpha = .08f),
            ),
            start = Offset(shimmerOffset - 220f, 0f),
            end = Offset(shimmerOffset, 0f),
        )
        Column(modifier = modifier.padding(top = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            listOf(280.dp, 108.dp, 238.dp, 84.dp, 264.dp).forEach { width ->
                Box(
                    Modifier
                        .height(20.dp)
                        .width(width)
                        .background(shimmer, RoundedCornerShape(8.dp))
                        .testTag("lyrics_loading_skeleton"),
                )
            }
        }
    }
}

@Composable
private fun SyncedLyricsList(
    trackId: String,
    lines: List<PlayerLyricLine>,
    currentPositionMs: Long,
    pendingSeekPositionMs: Long?,
    seekRequestId: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    translationVisible: Boolean,
    isPlaying: Boolean = true,
    springStrength: Float = 170f,
    fullscreen: Boolean = false,
    bottomInsetPx: Float = 0f,
    fontScale: Float = 1f,
    onShowControls: () -> Unit = {},
    onHideControls: () -> Unit = {},
    onSeekInteraction: () -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var isUserDragging by remember { mutableStateOf(false) }
    // 屏幕空间锚点：稳定态由锚点行 Y 决定；换行时用逐行解析弹簧做“后一行追前一行”。
    var anchorIndex by remember(lines) { mutableIntStateOf(0) }
    var anchorScreenY by remember(lines) { mutableFloatStateOf(0f) }
    var browseScroll by remember { mutableFloatStateOf(0f) }
    var scrollMode by remember(lines) { mutableStateOf(LyricsScrollMode.Follow) }
    var followRunId by remember(lines) { mutableIntStateOf(0) }
    var followCompletedRun by remember(lines) { mutableIntStateOf(0) }
    var transitioning by remember(lines) { mutableStateOf(false) }
    var transitionIndex by remember(lines) { mutableIntStateOf(0) }
    var transitionElapsedMs by remember { mutableFloatStateOf(0f) }
    var transitionStiffness by remember { mutableFloatStateOf(LyricFollowStiffness) }
    // 过渡以屏幕 Y 为状态：行高变化时目标按实时前缀高度重算，切换翻译不会跳。
    var transitionLaunchScreenY by remember(lines) { mutableStateOf<FloatArray?>(null) }
    // 布局期回填的实测行高；只在主线程布局/事件中读写，不参与重组。
    val measuredHeights = remember(lines) { IntArray(lines.size) }
    val edgeRangePx = with(density) { 60.dp.toPx() }
    val maskPaints = remember { VerticalMaskPaints() }
    val edgeFadeStops = listOf(
        0.00f to 0.00f,
        0.20f to 0.15f,
        0.45f to 0.40f,
        0.70f to 0.75f,
        1.00f to 1.00f,
    )
    val rowBottomPaddingPx = with(density) { 10.dp.roundToPx() }
    var hasPositionedInitialLine by remember(lines) { mutableStateOf(false) }
    var previousActiveIndex by remember(lines) { mutableStateOf<Int?>(null) }
    var isBrowsing by remember(lines) { mutableStateOf(false) }
    var selectedLineIndex by remember(lines) { mutableStateOf<Int?>(null) }
    var activeIndexWhenLineSelected by remember(lines) { mutableStateOf<Int?>(null) }
    var selectedLineAnimationComplete by remember(lines) { mutableStateOf(false) }
    var isFollowingSelectedLine by remember(lines) { mutableStateOf(false) }
    var returnedToForeground by remember(lines) { mutableStateOf(false) }
    var previousPositionMs by remember(trackId) { mutableLongStateOf(currentPositionMs) }
    var browseIdleVersion by remember(lines) { mutableLongStateOf(0L) }
    var browseReturnTicket by remember(lines) { mutableLongStateOf(0L) }
    val displayedPositionMs = displayedLyricsPositionMs(currentPositionMs, pendingSeekPositionMs)
    val lyricClock = rememberLyricClock(displayedPositionMs, isPlaying)
    val activeIndex = remember(lines, displayedPositionMs) {
        lines.indexOfLast { (it.timestampSeconds ?: Float.MAX_VALUE) <= displayedPositionMs / 1_000f }
    }
    val latestActiveIndex by rememberUpdatedState(activeIndex)
    // 译文进度只驱动行高与透明度；锚点动画独立运行，不被切换打断。
    val translationProgress = remember { Animatable(if (translationVisible) 1f else 0f) }
    val reduceMotion = LocalReduceMotion.current
    val primaryBaseStyle = if (fullscreen) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
    val primaryStyle = remember(primaryBaseStyle, fontScale) { primaryBaseStyle.scaledBy(fontScale) }
    val countdownPrimaryStyle = remember(primaryBaseStyle, fontScale) {
        primaryBaseStyle
            .copy(
                fontSize = primaryBaseStyle.fontSize * KaraokeDotScale,
                lineHeight = primaryBaseStyle.lineHeight * KaraokeDotScale,
            )
            .scaledBy(fontScale)
    }
    val secondaryBaseStyle = MaterialTheme.typography.bodyLarge
    val secondaryStyle = remember(secondaryBaseStyle, fontScale) { secondaryBaseStyle.scaledBy(fontScale) }
    val textMeasurer = rememberTextMeasurer()
    val rowTextWidthPx = with(density) {
        (viewportWidthPx - LyricsContentHorizontalInset.roundToPx() * 2).coerceAtLeast(0)
    }
    val translationLineHeightsPx = remember(lines, secondaryStyle, rowTextWidthPx) {
        IntArray(lines.size) { index ->
            val text = lines[index].secondary ?: return@IntArray 0
            if (rowTextWidthPx <= 0 || text.isBlank()) 0
            else textMeasurer.measure(
                AnnotatedString(text),
                style = secondaryStyle,
                constraints = Constraints(maxWidth = rowTextWidthPx),
            ).size.height
        }
    }

    /** 当前行顶部的目标屏幕位置：上一行最后一行文字贴在视口顶部；三连点行按点字号计算。 */
    fun focusScreenY(index: Int): Float {
        val previous = lines.getOrNull((index - 1).coerceAtLeast(0))
        val style = if (previous?.isCountdown == true) countdownPrimaryStyle else primaryStyle
        return with(density) { style.lineHeight.toPx() } + rowBottomPaddingPx
    }

    // 顶部留出“正常焦点高度”的空区：第一行/三连点行也能停在播放位，而不是被钳在顶部。
    val topInsetPx = focusScreenY(0).roundToInt()

    fun prefixTop(index: Int): Float {
        var sum = topInsetPx
        val end = index.coerceIn(0, measuredHeights.size)
        for (i in 0 until end) sum += measuredHeights[i]
        return sum.toFloat()
    }

    fun bottomSpacerPx(): Int = (viewportHeightPx - rowBottomPaddingPx).coerceAtLeast(0)

    fun clampScroll(value: Float): Float {
        val contentHeight = measuredHeights.sum() + topInsetPx + bottomSpacerPx()
        val maxScroll = (contentHeight - viewportHeightPx).coerceAtLeast(0).toFloat()
        return value.coerceIn(0f, maxScroll)
    }

    fun currentScroll(): Float = when (scrollMode) {
        LyricsScrollMode.Browse -> clampScroll(browseScroll)
        LyricsScrollMode.Follow -> clampScroll(prefixTop(anchorIndex) - anchorScreenY)
    }

    /** 过渡终点的屏幕 Y：锚点行固定在焦点高度，下方行随实时行高顺延。
     *  注意 prefixTop 已包含顶部空区，放置时不再额外加 topInset。 */
    fun finalScreenY(index: Int): Float =
        prefixTop(index) - prefixTop(transitionIndex) + focusScreenY(transitionIndex)

    /** 某一行当前的屏幕 Y：过渡中按逐行弹簧进度插值到实时目标。 */
    fun rowScreenYAt(index: Int): Float {
        if (!transitioning) return prefixTop(index) - currentScroll()
        val launch = transitionLaunchScreenY ?: return prefixTop(index) - currentScroll()
        val start = launch.getOrElse(index) { prefixTop(index) - currentScroll() }
        val distance = (index - transitionIndex).coerceAtLeast(0)
        val progress = springStepResponse(chaseStiffness(transitionStiffness, distance), transitionElapsedMs / 1_000f)
        return start + (finalScreenY(index) - start) * progress
    }

    fun finishTransition() {
        if (!transitioning) return
        transitioning = false
        transitionLaunchScreenY = null
        anchorIndex = transitionIndex
        anchorScreenY = focusScreenY(transitionIndex)
        followCompletedRun = followRunId
    }

    suspend fun awaitMeasuredHeights() {
        while (measuredHeights.all { it == 0 }) withFrameNanos { }
    }

    /**
     * 换行跟随：逐行按“与当前行的距离”取不同刚度，后一行追前一行；
     * 所有行同一时间轴、同一目标，末端精确对齐。返回本次动画的 runId。
     */
    fun startFollow(index: Int, stiffness: Float = Float.NaN, snap: Boolean = false): Int {
        val effectiveStiffness = if (stiffness.isNaN()) springStrength else stiffness
        val clampedIndex = index.coerceAtLeast(0)
        if (snap) {
            scrollMode = LyricsScrollMode.Follow
            transitioning = false
            transitionLaunchScreenY = null
            anchorIndex = clampedIndex
            anchorScreenY = focusScreenY(clampedIndex)
            followCompletedRun = followRunId
            followRunId += 1
            followCompletedRun = followRunId
            return followRunId
        }
        if (!transitioning && scrollMode == LyricsScrollMode.Follow && clampedIndex == anchorIndex &&
            abs(anchorScreenY - focusScreenY(clampedIndex)) < 0.5f
        ) {
            return followRunId
        }
        // 先按当前状态（可能是浏览模式）快照每行屏幕位置，再切到 Follow 启动过渡，
        // 否则快照会按旧锚点计算，回来后表现为瞬移。
        val launches = FloatArray(lines.size) { i -> rowScreenYAt(i) }
        scrollMode = LyricsScrollMode.Follow
        transitionLaunchScreenY = launches
        transitionIndex = clampedIndex
        transitionElapsedMs = 0f
        transitionStiffness = effectiveStiffness
        transitioning = true
        followCompletedRun = followRunId
        followRunId += 1
        return followRunId
    }

    // 过渡时钟：每帧推进，各行按自己的刚度解析求值，2.4s 兜底收尾。
    LaunchedEffect(followRunId) {
        if (followRunId == 0 || !transitioning) {
            followCompletedRun = followRunId
            return@LaunchedEffect
        }
        val startedAt = withFrameNanos { it }
        while (transitioning && scrollMode == LyricsScrollMode.Follow) {
            val frame = withFrameNanos { it }
            transitionElapsedMs = ((frame - startedAt) / 1_000_000L).toFloat()
            if (transitionElapsedMs >= 2_400f) break
        }
        if (transitioning) finishTransition() else followCompletedRun = followRunId
    }

    suspend fun awaitFollowSettled(runId: Int) {
        if (followCompletedRun >= runId) return
        snapshotFlow { followCompletedRun }.first { it >= runId }
    }

    fun isRowVisible(index: Int): Boolean {
        if (index !in measuredHeights.indices) return false
        val top = prefixTop(index) - currentScroll()
        return top + measuredHeights[index] > 0f && top < viewportHeightPx
    }
    DisposableEffect(lifecycleOwner, lines) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // A StateFlow collector receives only the latest position after a stopped
                // activity resumes. Re-centre it even when the old active row is off-screen.
                returnedToForeground = true
                isBrowsing = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    suspend fun resetToStart() {
        isBrowsing = false
        selectedLineIndex = null
        activeIndexWhenLineSelected = null
        selectedLineAnimationComplete = false
        hasPositionedInitialLine = false
        previousActiveIndex = null
        browseScroll = 0f
        followRunId += 1
        followCompletedRun = followRunId
        transitioning = false
        transitionLaunchScreenY = null
        scrollMode = LyricsScrollMode.Follow
        anchorIndex = 0
        anchorScreenY = focusScreenY(0)
    }
    LaunchedEffect(trackId) {
        resetToStart()
    }
    LaunchedEffect(trackId, currentPositionMs) {
        if (shouldResetLyricsForReplay(previousPositionMs, currentPositionMs)) resetToStart()
        previousPositionMs = currentPositionMs
    }
    LaunchedEffect(isUserDragging, isFollowingSelectedLine) {
        if (shouldEnterLyricsBrowseMode(isUserDragging, isFollowingSelectedLine)) isBrowsing = true
    }

    suspend fun animateToActiveLine(
        activeLineIndex: Int,
        stiffness: Float = Float.NaN,
    ) {
        if (activeLineIndex < 0) return
        awaitMeasuredHeights()
        val runId = startFollow(activeLineIndex, stiffness)
        awaitFollowSettled(runId)
    }

    /** 松手后的惯性滚动；浏览态由滚动模式接管。 */
    suspend fun flingOffset(velocityY: Float) {
        if (abs(velocityY) < 80f) return
        val anim = Animatable(0f)
        var applied = 0f
        finishTransition()
        browseScroll = currentScroll()
        scrollMode = LyricsScrollMode.Browse
        followCompletedRun = followRunId
        coroutineScope {
            val motion = launch {
                anim.animateDecay(
                    initialVelocity = -velocityY,
                    animationSpec = exponentialDecay(frictionMultiplier = 3.2f),
                )
            }
            val follower = launch {
                snapshotFlow { anim.value }.collect { value ->
                    val step = value - applied
                    applied = value
                    val before = browseScroll
                    browseScroll = clampScroll(before + step)
                    if (browseScroll != before + step) motion.cancel()
                }
            }
            motion.join()
            follower.cancel()
        }
    }

    LaunchedEffect(seekRequestId) {
        if (seekRequestId > 0L && activeIndex >= 0) {
            // Handle every slider seek as its own positioning command. Do not
            // depend on the regular active-line follower: it intentionally
            // respects browse mode after a previous scroll.
            isBrowsing = false
            selectedLineIndex = null
            animateToActiveLine(activeIndex, LyricSeekStiffness)
            hasPositionedInitialLine = true
            previousActiveIndex = activeIndex
            returnedToForeground = false
        }
    }

    // 译文开关只改行高与透明度：锚点动画继续跑，当前行轨迹不受影响。
    LaunchedEffect(translationVisible) {
        val target = if (translationVisible) 1f else 0f
        val from = translationProgress.value
        if (from == target) return@LaunchedEffect
        // 手动翻动歌词后切翻译：复用“回焦点”动画，把当前行带回正常位置。
        if (isBrowsing) {
            isBrowsing = false
            val active = latestActiveIndex
            if (active >= 0) {
                hasPositionedInitialLine = true
                previousActiveIndex = active
                scope.launch { animateToActiveLine(active) }
            }
        }
        if (reduceMotion) {
            translationProgress.snapTo(target)
            return@LaunchedEffect
        }
        translationProgress.animateTo(target, tween(durationMillis = 240, easing = FastOutSlowInEasing))
    }

    // 浏览歌词时不再显示当前行、也不跟随；静止 5 秒后回到跟随状态。
    LaunchedEffect(browseIdleVersion, isBrowsing) {
        if (!isBrowsing) return@LaunchedEffect
        delay(BrowseIdleReturnMs)
        if (!isBrowsing) return@LaunchedEffect
        browseReturnTicket += 1
        isBrowsing = false
    }
    LaunchedEffect(browseReturnTicket) {
        if (browseReturnTicket == 0L) return@LaunchedEffect
        val target = latestActiveIndex
        if (target >= 0) {
            hasPositionedInitialLine = true
            previousActiveIndex = target
            animateToActiveLine(target)
        }
    }

    // Keep the tapped line in view and move it into the active slot before
    // seeking. This avoids the abrupt focus jump that otherwise occurs after
    // a listener has manually browsed away from the current lyric.
    LaunchedEffect(selectedLineIndex) {
        val selectedIndex = selectedLineIndex ?: return@LaunchedEffect
        isFollowingSelectedLine = true
        try {
            animateToActiveLine(selectedIndex)
            hasPositionedInitialLine = true
            previousActiveIndex = selectedIndex
            selectedLineAnimationComplete = true
            if (selectedIndex == activeIndexWhenLineSelected) selectedLineIndex = null
        } finally {
            isFollowingSelectedLine = false
        }
    }
    LaunchedEffect(activeIndex, selectedLineIndex, activeIndexWhenLineSelected, selectedLineAnimationComplete) {
        if (shouldResumeLyricsAutoScroll(selectedLineIndex, activeIndex, activeIndexWhenLineSelected, selectedLineAnimationComplete)) {
            selectedLineIndex = null
        }
    }
    LaunchedEffect(lines, activeIndex, isBrowsing, selectedLineIndex, returnedToForeground) {
        if (activeIndex < 0 || isBrowsing || selectedLineIndex != null || isFollowingSelectedLine) return@LaunchedEffect
        awaitMeasuredHeights()
        if (!hasPositionedInitialLine) {
            startFollow(activeIndex, snap = true)
            hasPositionedInitialLine = true
            returnedToForeground = false
        } else {
            val previousActiveLineInViewport = previousActiveIndex?.let(::isRowVisible) ?: false
            if (shouldFollowLyricsActiveLine(previousActiveLineInViewport, returnedToForeground)) {
                if (returnedToForeground) startFollow(activeIndex, snap = true) else animateToActiveLine(activeIndex)
                returnedToForeground = false
            }
        }
        previousActiveIndex = activeIndex
    }
    LaunchedEffect(lines, fontScale) {
        // Let row/text measurements settle without resetting browse or tap-to-seek state.
        withFrameNanos { }
        withFrameNanos { }
        if (hasPositionedInitialLine && !isBrowsing && selectedLineIndex == null && activeIndex >= 0 &&
            isRowVisible(activeIndex)
        ) startFollow(activeIndex, snap = true)
    }
    Box(
        modifier = modifier
            .onSizeChanged {
                viewportHeightPx = it.height
                viewportWidthPx = it.width
            }
            .pointerInput(lines) {
                val velocityTracker = VelocityTracker()
                val directionStepPx = with(density) { 12.dp.toPx() }
                var lastVerticalSign = 0
                var verticalRunPx = 0f
                var lastTriggeredSign = 0
                detectVerticalDragGestures(
                    onDragStart = {
                        isUserDragging = true
                        isBrowsing = true
                        browseIdleVersion += 1
                        finishTransition()
                        browseScroll = currentScroll()
                        scrollMode = LyricsScrollMode.Browse
                        followCompletedRun = followRunId
                        lastVerticalSign = 0
                        verticalRunPx = 0f
                        lastTriggeredSign = 0
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        isUserDragging = false
                        val velocity = velocityTracker.calculateVelocity().y
                        scope.launch { flingOffset(velocity) }
                    },
                    onDragCancel = { isUserDragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    // 同一组触摸不抬手时，每次换向累计到阈值都重新响应：
                    // 手指下滑（上翻）→ 唤出控件；手指上滑（下翻）→ 隐藏控件、进入全屏。
                    val sign = if (dragAmount > 0f) 1 else if (dragAmount < 0f) -1 else 0
                    if (sign != 0) {
                        if (sign != lastVerticalSign) {
                            lastVerticalSign = sign
                            verticalRunPx = 0f
                        }
                        verticalRunPx += abs(dragAmount)
                        if (verticalRunPx >= directionStepPx && lastTriggeredSign != sign) {
                            lastTriggeredSign = sign
                            verticalRunPx = 0f
                            if (sign > 0) onShowControls() else onHideControls()
                        }
                    }
                    browseScroll = clampScroll(browseScroll - dragAmount)
                }
            }
            .drawWithContent {
            val cutoff = (size.height - bottomInsetPx).coerceIn(0f, size.height)
            drawLyricsEdgeFade(
                bottomCutoff = cutoff,
                edgeRange = edgeRangePx,
                edgeFadeStops = edgeFadeStops,
                maskPaints = maskPaints,
            ) {
                // 截止线以下硬裁切：超长当前行也不会透到控件区。
                clipRect(bottom = cutoff) { this@drawWithContent.drawContent() }
            }
        },
        contentAlignment = Alignment.TopStart,
    ) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .testTag("synced_lyrics_list"),
            content = {
                lines.forEachIndexed { index, line ->
                    SyncedLyricRow(
                        line = line,
                        fullscreen = fullscreen,
                        translationProgress = { translationProgress.value },
                        translationHeightPx = translationLineHeightsPx.getOrElse(index) { 0 },
                        words = line.words,
                        clock = lyricClock,
                        textWidthPx = rowTextWidthPx,
                        distance = if (activeIndex >= 0) abs(index - activeIndex) else Int.MAX_VALUE,
                        fontScale = fontScale,
                        onClick = {
                            if (!line.isCountdown) {
                                isBrowsing = false
                                activeIndexWhenLineSelected = activeIndex
                                selectedLineAnimationComplete = false
                                isFollowingSelectedLine = true
                                selectedLineIndex = index
                                onSeekInteraction()
                                onSeek((line.timestampSeconds!! * 1_000).toLong())
                            }
                        },
                    )
                }
            },
        ) { measurables, constraints ->
            // 一次测量拿到所有行高：前缀高度、锚点定位都在同一帧完成。
            val insetPx = LyricsContentHorizontalInset.roundToPx()
            val childWidth = (constraints.maxWidth - insetPx * 2).coerceAtLeast(0)
            val heights = IntArray(measurables.size)
            val placeables = measurables.mapIndexed { index, measurable ->
                measurable.measure(
                    Constraints(maxWidth = childWidth, minHeight = 0, maxHeight = Constraints.Infinity),
                ).also { heights[index] = it.height }
            }
            heights.copyInto(measuredHeights, 0, 0, minOf(heights.size, measuredHeights.size))
            val bottomSpacer = (constraints.maxHeight - rowBottomPaddingPx).coerceAtLeast(0)
            val maxScroll = (heights.sum() + topInsetPx + bottomSpacer - constraints.maxHeight)
                .coerceAtLeast(0).toFloat()
            val baseScroll = when (scrollMode) {
                LyricsScrollMode.Browse -> browseScroll
                LyricsScrollMode.Follow -> prefixTop(anchorIndex) - anchorScreenY
            }.coerceIn(0f, maxScroll)
            val launchScreenY = transitionLaunchScreenY
            val chaseElapsedSec = transitionElapsedMs / 1_000f
            layout(constraints.maxWidth, constraints.maxHeight) {
                var top = 0
                placeables.forEachIndexed { index, placeable ->
                    val screenY = if (transitioning && launchScreenY != null) {
                        val start = launchScreenY.getOrElse(index) { topInsetPx + top - baseScroll }
                        val distance = (index - transitionIndex).coerceAtLeast(0)
                        val progress = springStepResponse(
                            chaseStiffness(transitionStiffness, distance),
                            chaseElapsedSec,
                        )
                        // 目标按实时前缀高度计算：翻译展开行高变化时目标随之移动，过渡不中断。
                        start + (finalScreenY(index) - start) * progress
                    } else {
                        topInsetPx + top - baseScroll
                    }
                    placeable.place(insetPx, screenY.roundToInt())
                    top += heights[index]
                }
            }
        }
    }
}

/** 播放位置插值时钟：200ms 的进度 tick 之间按帧补齐，暂停即冻结。 */
@Composable
private fun rememberLyricClock(positionMs: Long, isPlaying: Boolean): androidx.compose.runtime.State<Long> {
    val state = remember { mutableLongStateOf(positionMs) }
    val latestPosition by rememberUpdatedState(positionMs)
    val latestPlaying by rememberUpdatedState(isPlaying)
    // 常驻单循环：200ms tick 只更新基准，不重启循环，避免每秒周期性掉帧。
    LaunchedEffect(Unit) {
        var base = latestPosition
        var startedAtFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val playing = latestPlaying
            val tick = latestPosition
            if (!playing) {
                state.longValue = tick
                base = tick
                startedAtFrame = frame
                continue
            }
            if (tick != base) {
                base = tick
                startedAtFrame = frame
            }
            state.longValue = base + (frame - startedAtFrame) / 1_000_000L
        }
    }
    return state
}

/** 当前行的逐字扫亮 + 逐段上抬；字形预生成位图，逐帧只做 GPU 合成。 */
@Composable
private fun WordKaraokeLine(
    words: List<PlayerLyricWord>,
    clock: androidx.compose.runtime.State<Long>,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    rowAlpha: Float,
    maxWidthPx: Int,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(words, style, maxWidthPx, textMeasurer) {
        layoutKaraokeWords(words, style, maxWidthPx, textMeasurer, density)
    }
    val liftPx = with(density) { 1.5.dp.toPx() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(with(density) { layout.heightPx.toDp() }),
    ) {
        val positionMs = clock.value
        val native = drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            this.color = android.graphics.Color.WHITE
        }
        layout.items.forEachIndexed { index, item ->
            val duration = item.durationMs.coerceAtLeast(1L)
            val fillProgress = ((positionMs - item.word.startMs).toFloat() / duration).coerceIn(0f, 1f)
            // AMLL 参数：上浮动效时长独立于字时长，最短 1000ms，再乘 1.4。
            val liftDuration = (maxOf(1_000f, duration.toFloat()) * 1.4f).coerceAtLeast(1f)
            val liftProgress = ((positionMs - item.word.startMs).toFloat() / liftDuration).coerceIn(0f, 1f)
            // 0.05em 终值 + sin 中段额外抬升，形成 Apple 的“快起、轻顶、落定”。
            val liftFactor = (liftProgress + sin(Math.PI.toFloat() * liftProgress)).coerceIn(0f, 2f)
            val liftedTop = item.top - liftPx * liftFactor
            val dimAlpha = (255 * rowAlpha * 0.26f).roundToInt().coerceIn(0, 255)
            val brightAlpha = (255 * rowAlpha).roundToInt().coerceIn(0, 255)
            if (positionMs < item.word.startMs) {
                paint.alpha = dimAlpha
                native.drawBitmap(item.bitmap, item.x, item.top, paint)
                return@forEachIndexed
            }
            paint.alpha = dimAlpha
            native.drawBitmap(item.bitmap, item.x, liftedTop, paint)
            if (fillProgress >= 1f) {
                paint.alpha = brightAlpha
                native.drawBitmap(item.bitmap, item.x, liftedTop, paint)
                return@forEachIndexed
            }
            // 真渐变遮罩：渐变宽度 = 0.5 × 词高，以扫过位置为中心，左亮右暗。
            val fadeWidth = (item.height * 0.5f).coerceAtLeast(1f)
            val sweepX = item.x + item.width * fillProgress
            val fadeStart = sweepX - fadeWidth * 0.5f
            val fadeEnd = sweepX + fadeWidth * 0.5f
            val layer = native.saveLayer(item.x, liftedTop, item.x + item.width, liftedTop + item.height, null)
            paint.alpha = brightAlpha
            native.drawBitmap(item.bitmap, item.x, liftedTop, paint)
            val startFraction = ((fadeStart - item.x) / item.width).coerceIn(0f, 1f)
            val endFraction = ((fadeEnd - item.x) / item.width).coerceIn(0f, 1f)
            if (endFraction > startFraction) {
                val maskPaint = android.graphics.Paint().apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                    shader = android.graphics.LinearGradient(
                        item.x,
                        0f,
                        item.x + item.width,
                        0f,
                        intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF),
                        floatArrayOf(0f, startFraction, endFraction, 1f),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                }
                native.drawRect(item.x, liftedTop, item.x + item.width, liftedTop + item.height, maskPaint)
            }
            native.restoreToCount(layer)
            // 跨词光晕：下一词左侧一小段按当前词尾进度提前透出。
            val next = layout.items.getOrNull(index + 1)
            if (next != null && fillProgress > 0.55f) {
                val bleed = ((fillProgress - 0.55f) / 0.45f).coerceIn(0f, 1f)
                paint.alpha = (brightAlpha * 0.28f * bleed).roundToInt().coerceIn(0, 255)
                val bleedWidth = (next.height * 0.5f * 0.6f).coerceAtLeast(1f)
                val save = native.save()
                native.clipRect(next.x, next.top, next.x + bleedWidth, next.top + next.height)
                native.drawBitmap(next.bitmap, next.x, next.top, paint)
                native.restoreToCount(save)
            }
        }
    }
}

private data class KaraokeWordItem(
    val word: PlayerLyricWord,
    val bitmap: android.graphics.Bitmap,
    val x: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** 有效时长：优先用 yrc 给的字时长，缺失时用与下一段的间隔做平均速度。 */
    val durationMs: Long,
)

private data class KaraokeWordLayout(val items: List<KaraokeWordItem>, val heightPx: Int)

private fun layoutKaraokeWords(
    words: List<PlayerLyricWord>,
    style: androidx.compose.ui.text.TextStyle,
    maxWidthPx: Int,
    measurer: androidx.compose.ui.text.TextMeasurer,
    density: androidx.compose.ui.unit.Density,
): KaraokeWordLayout {
    val lineHeightPx = with(density) { style.lineHeight.toPx() }
    val width = maxWidthPx.coerceAtLeast(1)
    var x = 0f
    var lineTop = 0f
    val items = ArrayList<KaraokeWordItem>(words.size)
    words.forEachIndexed { index, word ->
        val result = measurer.measure(
            AnnotatedString(word.text),
            style = style.copy(fontWeight = FontWeight.Bold, color = Color.White),
            constraints = Constraints(maxWidth = width),
        )
        val wordWidth = result.size.width.toFloat()
        if (x > 0f && x + wordWidth > width) {
            x = 0f
            lineTop += lineHeightPx
        }
        val wordHeight = result.size.height.toFloat()
        val imageBitmap = androidx.compose.ui.graphics.ImageBitmap(
            result.size.width.coerceAtLeast(1),
            result.size.height.coerceAtLeast(1),
        )
        result.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(imageBitmap))
        val bitmap = imageBitmap.asAndroidBitmap()
        val nextStart = words.getOrNull(index + 1)?.startMs
        val effectiveDuration = word.durationMs.takeIf { it > 0L }
            ?: nextStart?.let { (it - word.startMs).coerceAtLeast(1L) }
            ?: 1L
        items += KaraokeWordItem(
            word = word,
            bitmap = bitmap,
            x = x,
            top = lineTop + (lineHeightPx - wordHeight) / 2f,
            width = wordWidth,
            height = wordHeight,
            durationMs = effectiveDuration,
        )
        x += wordWidth
    }
    return KaraokeWordLayout(items, (lineTop + lineHeightPx).roundToInt().coerceAtLeast(1))
}

private fun smoothStep(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/** 快起慢停的 easing：抬升更有弹性，但保持单调不过冲。 */
private fun easeOutCubic(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    val inv = 1f - v
    return 1f - inv * inv * inv
}

/** 歌词容器：不模糊，只对内容做顶部/底部竖直渐隐。 */
private class VerticalMaskPaints {
    private var geometryKey: String = ""
    private val paints = mutableMapOf<String, android.graphics.Paint>()

    fun paint(tag: String, geometryKey: String, build: () -> android.graphics.Paint): android.graphics.Paint {
        if (this.geometryKey != geometryKey) {
            paints.clear()
            this.geometryKey = geometryKey
        }
        return paints.getOrPut(tag, build)
    }
}

private fun DrawScope.drawLyricsEdgeFade(
    bottomCutoff: Float,
    edgeRange: Float,
    edgeFadeStops: List<Pair<Float, Float>>,
    maskPaints: VerticalMaskPaints,
    content: DrawScope.() -> Unit,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) {
        content()
        return
    }
    val native = drawContext.canvas.nativeCanvas
    val full = Rect(0f, 0f, w, h)
    val cutoff = bottomCutoff.coerceIn(0f, h)
    val range = edgeRange.coerceAtLeast(1f)
    val geometryKey = "${w}x${h}x${cutoff}x${range}"

    fun top(stops: List<Pair<Float, Float>>) =
        stops.map { (d, a) -> (d * range) to a }.sortedBy { it.first }
    fun bottom(stops: List<Pair<Float, Float>>) =
        stops.map { (d, a) -> (cutoff - d * range) to a }.sortedBy { it.first }

    native.saveLayer(0f, 0f, w, h, null)
    content()
    drawVerticalAlphaMask(full, "fade-top", top(edgeFadeStops), geometryKey, maskPaints)
    drawVerticalAlphaMask(full, "fade-bottom", bottom(edgeFadeStops), geometryKey, maskPaints)
    native.restore()
}

/** 用一个竖直 alpha 渐变遮住当前层的指定区域（DstIn）；Paint/Shader 按几何尺寸缓存复用。 */
private fun DrawScope.drawVerticalAlphaMask(
    bounds: Rect,
    tag: String,
    stops: List<Pair<Float, Float>>,
    geometryKey: String,
    maskPaints: VerticalMaskPaints,
) {
    if (stops.size < 2) return
    val firstY = stops.first().first
    val lastY = stops.last().first
    if (lastY - firstY <= 0.5f) return
    val paint = maskPaints.paint(tag, geometryKey) {
        val colors = stops.map { (_, alpha) ->
            ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0x00FFFFFF
        }.toIntArray()
        val positions = stops.map { (y, _) -> ((y - firstY) / (lastY - firstY)).coerceIn(0f, 1f) }.toFloatArray()
        android.graphics.Paint().apply {
            isAntiAlias = false
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = android.graphics.LinearGradient(
                0f, firstY, 0f, lastY,
                colors, positions, android.graphics.Shader.TileMode.CLAMP,
            )
        }
    }
    drawContext.canvas.nativeCanvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint)
}

/** 跟随滚动刚度：屏幕锚点向焦点做临界阻尼弹簧（单调不过冲）。 */
private const val LyricFollowStiffness = 90f
/** 拖动/点按跳转回位用更软的刚度（AMLL seek 参数）。 */
private const val LyricSeekStiffness = 90f
/** 三连点字号系数：约为普通歌词字号的 0.8，视觉直径约为原始小点的 3 倍。 */
private const val KaraokeDotScale = 0.8f
/** 逐行跟随的刚度衰减：越靠后的行越软，形成“后一行追前一行”的滞后感。 */
private val LyricChaseFactors = floatArrayOf(1f, 0.55f, 0.35f, 0.24f, 0.18f, 0.15f)

private fun chaseStiffness(base: Float, distance: Int): Float =
    base * LyricChaseFactors[distance.coerceIn(0, LyricChaseFactors.lastIndex)]

/** 临界阻尼弹簧的阶跃响应（AMLL 阻尼：c = 2.2×√k，即 ζ=1.1，略过阻尼、不回弹）。 */
private fun springStepResponse(stiffness: Float, seconds: Float): Float {
    if (seconds <= 0f) return 0f
    val omega = sqrt(stiffness)
    val zeta = 1.1f
    val decay = kotlin.math.exp(-zeta * omega * seconds)
    val damped = omega * sqrt(zeta * zeta - 1f)
    return 1f - decay * (
        kotlin.math.cosh(damped * seconds) +
            (zeta * omega / damped) * kotlin.math.sinh(damped * seconds)
        )
}
/** 拖尾涉及的最大行数：越远滞后越大。 */
private const val LyricTrailMaxRows = 6

@Composable
private fun SyncedLyricRow(
    line: PlayerLyricLine,
    fullscreen: Boolean = false,
    translationProgress: () -> Float,
    translationHeightPx: Int,
    words: List<PlayerLyricWord> = emptyList(),
    clock: androidx.compose.runtime.State<Long>? = null,
    textWidthPx: Int = 0,
    distance: Int,
    onClick: () -> Unit,
    fontScale: Float = 1f,
) {
    val colors = LocalTuneColors.current
    // The pointer coroutine remains alive across playback-position and track
    // recompositions. Read the newest callback when a tap finishes so it
    // cannot dispatch through the callback captured for an earlier track.
    val latestOnClick = rememberUpdatedState(onClick)
    // 浏览态也保持“当前行高亮、其余按距离变淡”的正常显示。
    val targetOpacity = when (distance) {
        0 -> 1f
        1 -> 0.25f
        2 -> 0.15f
        else -> 0.10f
    }
    // 弹簧而非固定时长：越远的行刚度越低，淡入/淡出自然错峰。
    val effectSpring = remember(distance) {
        spring<Float>(
            stiffness = (240f - distance.coerceIn(0, LyricTrailMaxRows) * 35f).coerceAtLeast(130f),
            dampingRatio = 1f,
        )
    }
    val activeSpring = remember { spring<Float>(stiffness = 160f, dampingRatio = 1f) }
    val primaryBase = if (fullscreen) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
    // 三连点行把点当作一个字：字号放大，点直径约为普通字号的 3 倍视觉大小。
    val linePrimaryBase = if (line.isCountdown) {
        primaryBase.copy(
            fontSize = primaryBase.fontSize * KaraokeDotScale,
            lineHeight = primaryBase.lineHeight * KaraokeDotScale,
        )
    } else {
        primaryBase
    }
    val primaryStyle = remember(linePrimaryBase, fontScale) { linePrimaryBase.scaledBy(fontScale) }
    val secondaryBase = MaterialTheme.typography.bodyLarge
    val secondaryStyle = remember(secondaryBase, fontScale) { secondaryBase.scaledBy(fontScale) }
    val density = LocalDensity.current
    val opacity by animateFloatAsState(targetOpacity, effectSpring, label = "synced-lyric-opacity")
    val scale by animateFloatAsState(if (distance == 0) 1.07f else 1f, activeSpring, label = "synced-lyric-scale")
    val activeOffsetPx = with(density) { 6.dp.toPx() }
    val animatedTranslationY by animateFloatAsState(
        if (distance == 0) -activeOffsetPx else 0f,
        activeSpring,
        label = "synced-lyric-offset",
    )
    // 追随/拖尾位移全部由列表的布局统一计算，行本身只负责颜色、缩放与点击。
    val lyricInteractionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            // 宽度只包住文字：短句右侧的留白不属于歌词行，点那里由容器唤出控件。
            .wrapContentWidth(Alignment.Start)
            // Reserve room for active-line scaling without changing wrapping
            // only when the active state changes.
            .padding(top = 10.dp, bottom = 10.dp)
            // 只有文字本身可点：行间、短句右侧与上下留白留给容器。
            .clickable(
                interactionSource = lyricInteractionSource,
                indication = null,
                role = Role.Button,
            ) { latestOnClick.value() }
            // Transform after text layout so the active line grows subtly
            // without changing its wrapping or displacing adjacent lyrics.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = animatedTranslationY
                transformOrigin = TransformOrigin(0f, 0.5f)
                clip = false
            }
            .testTag("synced_lyric_${line.timestampSeconds}"),
    ) {
        if (distance == 0 && line.words.isNotEmpty() && clock != null && textWidthPx > 0) {
            WordKaraokeLine(
                words = line.words,
                clock = clock,
                style = primaryStyle,
                color = colors.onPrimary,
                rowAlpha = opacity,
                maxWidthPx = textWidthPx,
            )
        } else {
            Text(
                text = line.primary,
                color = colors.onPrimary.copy(alpha = opacity),
                style = primaryStyle,
                // Keep glyph metrics stable when the line becomes active; changing
                // weight here would re-wrap the same text during scale animation.
                fontWeight = FontWeight.Bold,
            )
        }
        val translation = line.secondary
        if (translation != null) {
            val fullHeightPx = (translationHeightPx + with(density) { 4.dp.roundToPx() }).coerceAtLeast(0)
            Box(
                Modifier
                    .fillMaxWidth()
                    // 行高在布局阶段读取进度：整列不会因为每一帧的进度变化而重组。
                    .layout { measurable, constraints ->
                        val height = (fullHeightPx * translationProgress()).roundToInt().coerceIn(0, fullHeightPx)
                        val placeable = measurable.measure(
                            Constraints.fixed(constraints.maxWidth, height),
                        )
                        layout(placeable.width, height) { placeable.place(0, 0) }
                    }
                    .clipToBounds(),
            ) {
                Text(
                    text = translation,
                    color = colors.foregroundSubtle,
                    style = secondaryStyle,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .graphicsLayer { alpha = translationProgress() * opacity },
                )
            }
        }
    }
}

/** 纯文本歌词没有自己的拖动处理，用不消费事件的观察器实现同样的方向手势。 */
private fun Modifier.lyricsOverlayDirections(
    stepPx: Float,
    onPullDown: () -> Unit,
    onPullUp: () -> Unit,
): Modifier = pointerInput(stepPx) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var lastSign = 0
            var runPx = 0f
            var lastTriggered = 0
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dy = change.position.y - change.previousPosition.y
                val sign = if (dy > 0f) 1 else if (dy < 0f) -1 else 0
                if (sign != 0) {
                    if (sign != lastSign) {
                        lastSign = sign
                        runPx = 0f
                    }
                    runPx += abs(dy)
                    if (runPx >= stepPx && lastTriggered != sign) {
                        lastTriggered = sign
                        runPx = 0f
                        if (sign > 0) onPullDown() else onPullUp()
                    }
                }
                if (!change.pressed) break
            }
        }
    }
}

@Composable
private fun PlainLyricsList(
    lines: List<PlayerLyricLine>,
    modifier: Modifier,
    translationVisible: Boolean,
    bottomInsetPx: Float = 0f,
    fullscreen: Boolean = false,
    fontScale: Float = 1f,
    onShowControls: () -> Unit = {},
    onHideControls: () -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val directionStepPx = with(density) { 12.dp.toPx() }
    val edgeRangePx = with(density) { 60.dp.toPx() }
    val maskPaints = remember { VerticalMaskPaints() }
    val edgeFadeStops = listOf(
        0.00f to 0.00f,
        0.20f to 0.15f,
        0.45f to 0.40f,
        0.70f to 0.75f,
        1.00f to 1.00f,
    )
    val primaryBase = if (fullscreen) {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.bodyLarge
    }
    val secondaryBase = if (fullscreen) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val primaryStyle = remember(primaryBase, fontScale) { primaryBase.scaledBy(fontScale) }
    val secondaryStyle = remember(secondaryBase, fontScale) { secondaryBase.scaledBy(fontScale) }
    val enter = if (reduceMotion) fadeIn(tween(0)) else fadeIn(tween(180)) + expandVertically(tween(220))
    val exit = if (reduceMotion) fadeOut(tween(0)) else fadeOut(tween(140)) + shrinkVertically(tween(200))
    Box(
        modifier = modifier
            .lyricsOverlayDirections(
                stepPx = directionStepPx,
                onPullDown = onShowControls,
                onPullUp = onHideControls,
            )
            .drawWithContent {
                val cutoff = (size.height - bottomInsetPx).coerceIn(0f, size.height)
                drawLyricsEdgeFade(
                    bottomCutoff = cutoff,
                    edgeRange = edgeRangePx,
                    edgeFadeStops = edgeFadeStops,
                    maskPaints = maskPaints,
                ) {
                    clipRect(bottom = cutoff) { this@drawWithContent.drawContent() }
                }
            },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("plain_lyrics_list"),
            contentPadding = PaddingValues(
                start = LyricsContentHorizontalInset,
                end = LyricsContentHorizontalInset,
            ),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                Column(Modifier.fillMaxWidth().padding(vertical = if (fullscreen) 12.dp else 8.dp)) {
                    Text(text = line.primary, color = colors.onPrimary, style = primaryStyle)
                    AnimatedVisibility(
                        visible = translationVisible && line.secondary != null,
                        enter = enter,
                        exit = exit,
                    ) {
                        Text(
                            text = line.secondary.orEmpty(),
                            color = colors.foregroundSubtle,
                            style = secondaryStyle,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsEmptyState(modifier: Modifier) {
    val colors = LocalTuneColors.current
    Text(
        text = stringResource(R.string.player_lyrics_not_available),
        color = colors.foregroundSubtle,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.padding(top = 24.dp),
    )
}
