package com.exodidio.tune.ui.navigation

import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exodidio.tune.R
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.components.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.exodidio.tune.lyrics.RomanizationUiState
import kotlin.math.roundToInt

internal data class PlayerLyricLine(
    val primary: String,
    val secondary: String? = null,
    val timestampSeconds: Float? = null,
)

private val TimestampedLyricLine = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)$")
private val LeadingLyricTimestamp = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")
private val BilingualSeparator = Regex("\\s*\\^\\s*|\\s*/\\s*")
private const val ForwardSeekAnimatedApproachRows = 3
/** 手动翻动歌词后，静止这么久就回到跟随播放的状态。 */
private const val BrowseIdleReturnMs = 5_000L
/** 歌词绘制层满幅到屏幕边，文字内容缩进这段距离。 */
private val LyricsContentHorizontalInset = 20.dp

/** 歌词字号档位：0 小、1 标准（默认）、2 大。 */
internal fun lyricFontScale(level: Int): Float = when (level) {
    0 -> 0.92f
    1 -> 1f
    else -> 1.12f
}

private fun androidx.compose.ui.text.TextStyle.scaledBy(scale: Float): androidx.compose.ui.text.TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

internal enum class LyricsSeekDirection { Backward, Forward }

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
    return parsed
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

internal fun hasSyncedPlayerLyrics(content: String?): Boolean = content != null && parsePlayerLyrics(content).any { it.timestampSeconds != null }

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
    visible: Boolean = true,
    romanization: RomanizationUiState = RomanizationUiState(),
    romanizationAllowed: Boolean = false,
    onRomanizationInput: (List<String>, Boolean) -> Unit = { _, _ -> },
    onRomanizationToggle: () -> Unit = {},
    currentPositionMs: Long,
    pendingSeekPositionMs: Long? = null,
    seekRequestId: Long = 0L,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    /** 底部被遮挡层盖住的高度（px，已乘淡出进度）；用于歌词容器的边缘模糊截止线。 */
    bottomInsetPx: Float = 0f,
    /** 点歌词视图空白处：唤出底部控件。 */
    onShowControls: () -> Unit = {},
    /** 点歌词文字：只跳转并重新计时，不唤出控件。 */
    onSeekInteraction: () -> Unit = {},
    /** 歌词字号档位：0 小、1 标准、2 大。 */
    fontLevel: Int = 1,
) {
    val fontScale = lyricFontScale(fontLevel)
    val parsedLines = remember(lyrics) { lyrics?.let(::parsePlayerLyrics).orEmpty() }
    val syncedLines = remember(parsedLines) { parsedLines.filter { it.timestampSeconds != null } }
    val primary = remember(parsedLines, syncedLines) { (syncedLines.ifEmpty { parsedLines }).map { it.primary } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnInput by rememberUpdatedState(onRomanizationInput)
    LaunchedEffect(trackId, primary, visible, loading, lifecycleOwner) {
        if (visible && !loading) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                currentOnInput(primary, true)
                try {
                    awaitCancellation()
                } finally {
                    currentOnInput(emptyList(), false)
                }
            }
        }
    }
    val current = romanization.input == primary && !loading
    val secondary = if (romanizationAllowed && current && romanization.enabled) romanization.secondary else emptyList()
    val showToggle = romanizationAllowed && current && romanization.supported
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
                secondary,
                showToggle,
                fullscreen = fullscreen,
                bottomInsetPx = bottomInsetPx,
                onShowControls = onShowControls,
                onSeekInteraction = onSeekInteraction,
                fontScale = fontScale,
            )
            else -> PlainLyricsList(
                parsedLines,
                Modifier.fillMaxSize(),
                secondary,
                showToggle,
                fullscreen = fullscreen,
                fontScale = fontScale,
            )
        }
        if (showToggle) {
            RomanizationToggle(
                state = romanization,
                onClick = onRomanizationToggle,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = LyricsContentHorizontalInset),
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
    secondary: List<String?>,
    showToggle: Boolean,
    fullscreen: Boolean = false,
    bottomInsetPx: Float = 0f,
    fontScale: Float = 1f,
    onShowControls: () -> Unit = {},
    onSeekInteraction: () -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val rowHeights = remember(lines) { mutableStateMapOf<Int, Int>() }
    val trailingLineHeights = remember(lines) { mutableStateMapOf<Int, Int>() }
    val density = LocalDensity.current
    // 顶部 / 底部共用同一段 60dp 的边缘渐隐区（无模糊，只做文字渐变）。
    val edgeRangePx = with(density) { 60.dp.toPx() }
    val maskPaints = remember { VerticalMaskPaints() }
    // 距离边缘越近，透明度越低；最后一个点是边缘处（0 = 完全融入背景）。
    val edgeFadeStops = listOf(
        0.00f to 0.00f,
        0.20f to 0.15f,
        0.45f to 0.40f,
        0.70f to 0.75f,
        1.00f to 1.00f,
    )

    val rowBottomPaddingPx = with(density) { 10.dp.roundToPx() }
    val backwardSeekApproachPx = with(LocalDensity.current) { 72.dp.roundToPx() }
    var hasPositionedInitialLine by remember(lines) { mutableStateOf(false) }
    var previousActiveIndex by remember(lines) { mutableStateOf<Int?>(null) }
    var isBrowsing by remember(lines) { mutableStateOf(false) }
    var selectedLineIndex by remember(lines) { mutableStateOf<Int?>(null) }
    var activeIndexWhenLineSelected by remember(lines) { mutableStateOf<Int?>(null) }
    var selectedLineAnimationComplete by remember(lines) { mutableStateOf(false) }
    var isFollowingSelectedLine by remember(lines) { mutableStateOf(false) }
    var returnedToForeground by remember(lines) { mutableStateOf(false) }
    var previousPositionMs by remember(trackId) { mutableLongStateOf(currentPositionMs) }
    // 每次用户翻动歌词自增，用来重置“浏览后静止 5 秒回跟随”的计时。
    var browseIdleVersion by remember(lines) { mutableLongStateOf(0L) }
    var browseReturnTicket by remember(lines) { mutableLongStateOf(0L) }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    // 每次自动滚动的位移与触发计数：下方的行据此从原位慢慢追上。
    // 拖尾用累计量：每次换行只把目标往前推，不归零，下面的行从当前位置继续追，
    // 因此不会出现换行瞬间的起跳；同时逐行弹簧只表现为加速度更小。
    val trailAppliedTotal = remember { mutableFloatStateOf(0f) }
    val trailTargetTotal = remember { mutableFloatStateOf(0f) }
    val trailEnabledState = remember { mutableStateOf(false) }
    var trailEvent by remember { mutableIntStateOf(0) }
    val displayedPositionMs = displayedLyricsPositionMs(currentPositionMs, pendingSeekPositionMs)
    val activeIndex = remember(lines, displayedPositionMs) {
        lines.indexOfLast { (it.timestampSeconds ?: Float.MAX_VALUE) <= displayedPositionMs / 1_000f }
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
        listState.scrollToItem(0)
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
    LaunchedEffect(isUserDragging) {
        // 上下滑动/翻动歌词：唤出底部控件，并重置浏览状态的静止计时。
        if (isUserDragging) {
            browseIdleVersion += 1
            onShowControls()
        }
    }
    suspend fun previousLineOffset(activeLineIndex: Int): Int {
        val previousIndex = (activeLineIndex - 1).coerceAtLeast(0)
        return if (previousIndex < activeLineIndex) {
            snapshotFlow { rowHeights[previousIndex] to trailingLineHeights[previousIndex] }
                .first { (rowHeight, trailingLineHeight) -> rowHeight != null && trailingLineHeight != null }
                .let { (rowHeight, trailingLineHeight) ->
                    (rowHeight!! - trailingLineHeight!! - rowBottomPaddingPx).coerceAtLeast(0)
                }
        } else {
            0
        }
    }

    /**
     * 歌词初始贴顶：从第一行开始按“上一行贴顶”的焦点滚动，不做垂直居中。
     */
    suspend fun positionInitialLine(activeLineIndex: Int) {
        val previousIndex = (activeLineIndex - 1).coerceAtLeast(0)
        listState.scrollToItem(previousIndex)
        listState.scrollToItem(previousIndex, previousLineOffset(activeLineIndex))
    }

    /**
     * 跟随滚动：列表位移单调、不过冲；下面的行用自己的弹簧追累计位移。
     * 累计量只增不清零，所以换行瞬间不会归零起跳；每行的滞后只来自弹簧更低的加速度。
     */
    suspend fun scrollFollow(delta: Float, spec: AnimationSpec<Float>, trail: Boolean) {
        if (delta == 0f || !delta.isFinite()) return
        if (!trail) {
            // 跳转/点选：不做拖尾，累计量归零，行立即回到与列表一致的位置。
            trailEnabledState.value = false
            trailAppliedTotal.floatValue = 0f
            trailTargetTotal.floatValue = 0f
            trailEvent += 1
            val anim = Animatable(0f)
            var applied = 0f
            coroutineScope {
                val motion = launch { anim.animateTo(delta, spec) }
                val follower = launch {
                    snapshotFlow { anim.value }.collect { value ->
                        val step = value - applied
                        applied = value
                        if (step != 0f) listState.scrollBy(step)
                    }
                }
                motion.join()
                follower.cancel()
            }
            val remaining = delta - applied
            if (remaining != 0f) listState.scrollBy(remaining)
            return
        }
        val base = trailAppliedTotal.floatValue
        trailEnabledState.value = true
        trailTargetTotal.floatValue = base + delta
        trailEvent += 1
        val anim = Animatable(0f)
        var applied = 0f
        coroutineScope {
            val motion = launch { anim.animateTo(delta, spec) }
            val follower = launch {
                snapshotFlow { anim.value }.collect { value ->
                    val step = value - applied
                    applied = value
                    if (step != 0f) listState.scrollBy(step)
                    trailAppliedTotal.floatValue = base + value
                }
            }
            motion.join()
            follower.cancel()
        }
        val remaining = delta - applied
        if (remaining != 0f) {
            listState.scrollBy(remaining)
            trailAppliedTotal.floatValue = base + delta
        }
    }

    suspend fun animateToActiveLine(
        activeLineIndex: Int,
        spec: AnimationSpec<Float> = LyricFollowSpring,
        trail: Boolean = false,
    ) {
        val previousIndex = (activeLineIndex - 1).coerceAtLeast(0)
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == previousIndex }
        if (target != null) {
            val offset = previousLineOffset(activeLineIndex)
            scrollFollow((target.offset + offset).toFloat(), spec, trail)
        } else {
            // Do not animate through a long remote list: it makes a fast seek
            // feel sluggish. Jump just before the target so its measured height
            // is available, then perform one final alignment animation. This
            // avoids a visible second correction for wrapped lyric lines.
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: previousIndex
            when (lyricsSeekDirection(previousIndex, firstVisibleIndex)) {
                LyricsSeekDirection.Forward -> {
                    listState.scrollToItem((previousIndex - ForwardSeekAnimatedApproachRows).coerceAtLeast(0))
                    val alignedTarget = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == previousIndex }
                    if (alignedTarget != null) {
                        scrollFollow((alignedTarget.offset + previousLineOffset(activeLineIndex)).toFloat(), spec, trail)
                    } else {
                        listState.scrollToItem(previousIndex, previousLineOffset(activeLineIndex))
                    }
                }
                LyricsSeekDirection.Backward -> {
                    // Measure the remote row, then begin above its focus slot so
                    // the final movement visibly follows the backward seek.
                    listState.scrollToItem(previousIndex)
                    val focusOffset = previousLineOffset(activeLineIndex)
                    listState.scrollToItem(previousIndex, focusOffset + backwardSeekApproachPx)
                    scrollFollow(-backwardSeekApproachPx.toFloat(), spec, trail)
                }
            }
        }
    }

    LaunchedEffect(seekRequestId) {
        if (seekRequestId > 0L && activeIndex >= 0) {
            // Handle every slider seek as its own positioning command. Do not
            // depend on the regular active-line follower: it intentionally
            // respects browse mode after a previous scroll.
            isBrowsing = false
            selectedLineIndex = null
            animateToActiveLine(activeIndex, LyricSeekSpring)
            hasPositionedInitialLine = true
            previousActiveIndex = activeIndex
            returnedToForeground = false
        }
    }

    val latestActiveIndex by rememberUpdatedState(activeIndex)
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
            animateToActiveLine(target, trail = true)
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
        if (activeIndex < 0 || isBrowsing || selectedLineIndex != null) return@LaunchedEffect
        if (!hasPositionedInitialLine) {
            positionInitialLine(activeIndex)
            hasPositionedInitialLine = true
            returnedToForeground = false
        } else {
            val previousActiveLineInViewport = previousActiveIndex
                ?.let { index -> listState.layoutInfo.visibleItemsInfo.any { it.index == index } }
                ?: false
            if (shouldFollowLyricsActiveLine(previousActiveLineInViewport, returnedToForeground)) {
                if (returnedToForeground) positionInitialLine(activeIndex) else animateToActiveLine(activeIndex, trail = true)
                returnedToForeground = false
            }
        }
        previousActiveIndex = activeIndex
    }
    LaunchedEffect(secondary) {
        // Let row/text measurements settle without resetting browse or tap-to-seek state.
        withFrameNanos { }
        withFrameNanos { }
        if (hasPositionedInitialLine && !isBrowsing && selectedLineIndex == null && activeIndex >= 0 &&
            listState.layoutInfo.visibleItemsInfo.any { it.index == activeIndex }
        ) positionInitialLine(activeIndex)
    }
    // 底部留出“最后一行也能滚到焦点位”的空间；否则临近结尾时列表到底，当前行就贴不住焦点位。
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .onSizeChanged { viewportHeightPx = it.height }
            .drawWithContent {
            drawLyricsEdgeFade(
                bottomCutoff = (size.height - bottomInsetPx).coerceIn(0f, size.height),
                edgeRange = edgeRangePx,
                edgeFadeStops = edgeFadeStops,
                maskPaints = maskPaints,
            ) { this@drawWithContent.drawContent() }
        },
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = LyricsContentHorizontalInset,
                end = LyricsContentHorizontalInset,
                // 去掉焦点线以上的那一点余量，最后一行也能到达同一个焦点位。
                bottom = with(LocalDensity.current) {
                    (viewportHeightPx - rowBottomPaddingPx).coerceAtLeast(0).toDp()
                } + if (showToggle) 72.dp else 0.dp,
            ),
            userScrollEnabled = true,
            modifier = Modifier.fillMaxSize().testTag("synced_lyrics_list"),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                SyncedLyricRow(
                    line = line,
                    fullscreen = fullscreen,
                    secondary = secondary.getOrNull(index) ?: line.secondary,
                    distance = if (activeIndex >= 0) kotlin.math.abs(index - activeIndex) else Int.MAX_VALUE,
                    signedDistance = if (activeIndex >= 0) index - activeIndex else 0,
                    trailApplied = trailAppliedTotal,
                    trailTarget = trailTargetTotal,
                    trailEnabled = trailEnabledState.value,
                    trailEvent = trailEvent,
                    onClick = {
                        isBrowsing = false
                        activeIndexWhenLineSelected = activeIndex
                        selectedLineAnimationComplete = false
                        isFollowingSelectedLine = true
                        selectedLineIndex = index
                        onSeekInteraction()
                        onSeek((line.timestampSeconds!! * 1_000).toLong())
                    },
                    onRowHeightChanged = { rowHeights[index] = it },
                    onTrailingLineHeightChanged = { trailingLineHeights[index] = it },
                    focusMode = !isBrowsing,
                    fontScale = fontScale,
                )
            }
        }
    }
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

/** 跟随滚动：临界阻尼，单调不过冲，避免换行后来回弹。 */
private val LyricFollowSpring = spring<Float>(stiffness = 140f, dampingRatio = 1f)
/** 拖动/点按跳转回位用更软、不过冲的弹簧。 */
private val LyricSeekSpring = spring<Float>(stiffness = 110f, dampingRatio = 1f)
/** 拖尾涉及的最大行数：越远刚度越低。 */
private const val LyricTrailMaxRows = 6

/** 下面每一行的跟随弹簧：距离越远刚度越低（加速度越小），但都从换行瞬间起步。 */
private val LyricTrailStiffness = floatArrayOf(95f, 78f, 62f, 48f, 38f, 30f)

private fun lyricTrailSpring(signedDistance: Int): AnimationSpec<Float> {
    val index = signedDistance.coerceIn(1, LyricTrailMaxRows)
    return spring(stiffness = LyricTrailStiffness[index - 1], dampingRatio = 1f)
}

@Composable
private fun SyncedLyricRow(
    line: PlayerLyricLine,
    fullscreen: Boolean = false,
    secondary: String?,
    distance: Int,
    signedDistance: Int = 0,
    trailApplied: androidx.compose.runtime.State<Float>,
    trailTarget: androidx.compose.runtime.State<Float>,
    trailEnabled: Boolean,
    trailEvent: Int,
    onClick: () -> Unit,
    onRowHeightChanged: (Int) -> Unit,
    onTrailingLineHeightChanged: (Int) -> Unit,
    focusMode: Boolean,
    fontScale: Float = 1f,
) {
    val colors = LocalTuneColors.current
    // The pointer coroutine remains alive across playback-position and track
    // recompositions. Read the newest callback when a tap finishes so it
    // cannot dispatch through the callback captured for an earlier track.
    val latestOnClick = rememberUpdatedState(onClick)
    val targetOpacity = if (!focusMode) {
        1f
    } else when (distance) {
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
    val primaryStyle = remember(primaryBase, fontScale) { primaryBase.scaledBy(fontScale) }
    val secondaryBase = MaterialTheme.typography.bodyLarge
    val secondaryStyle = remember(secondaryBase, fontScale) { secondaryBase.scaledBy(fontScale) }
    val density = LocalDensity.current
    val opacity by animateFloatAsState(targetOpacity, effectSpring, label = "synced-lyric-opacity")
    val scale by animateFloatAsState(if (focusMode && distance == 0) 1.07f else 1f, activeSpring, label = "synced-lyric-scale")
    val activeOffsetPx = with(density) { 6.dp.toPx() }
    val animatedTranslationY by animateFloatAsState(
        if (focusMode && distance == 0) -activeOffsetPx else 0f,
        activeSpring,
        label = "synced-lyric-offset",
    )
    // 拖尾：下面每一行用自己的弹簧追累计位移（距离越远刚度越低）。
    // 累计量不归零，行从当前位置继续追，不会起跳；屏幕位移只向上。
    val trailActive = signedDistance > 0
    val rowProgress = remember { Animatable(0f) }
    LaunchedEffect(trailEvent, trailEnabled) {
        if (!trailEnabled || !trailActive) {
            if (rowProgress.value != trailApplied.value) rowProgress.snapTo(trailApplied.value)
            return@LaunchedEffect
        }
        rowProgress.animateTo(trailTarget.value, lyricTrailSpring(signedDistance))
    }
    val lyricInteractionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            // 宽度只包住文字：短句右侧的留白不属于歌词行，点那里由容器唤出控件。
            .wrapContentWidth(Alignment.Start)
            // Reserve room for active-line scaling without changing wrapping
            // only when the active state changes.
            .padding(top = 10.dp, bottom = 10.dp, end = 16.dp)
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
                translationY = animatedTranslationY +
                    if (trailActive && trailEnabled) (trailApplied.value - rowProgress.value) else 0f
                transformOrigin = TransformOrigin(0f, 0.5f)
                clip = false
            }
            .onSizeChanged { onRowHeightChanged(it.height) }
            .testTag("synced_lyric_${line.timestampSeconds}"),
    ) {
        Text(
            text = line.primary,
            color = colors.onPrimary.copy(alpha = opacity),
            style = primaryStyle,
            // Keep glyph metrics stable when the line becomes active; changing
            // weight here would re-wrap the same text during scale animation.
            fontWeight = FontWeight.Bold,
            onTextLayout = { layout ->
                if (secondary == null) onTrailingLineHeightChanged((layout.getLineBottom(layout.lineCount - 1) - layout.getLineTop(layout.lineCount - 1)).roundToInt())
            },
        )
        secondary?.let {
            Text(
                text = it,
                color = colors.foregroundSubtle.copy(alpha = opacity),
                style = secondaryStyle,
                modifier = Modifier.padding(top = 4.dp),
                onTextLayout = { layout ->
                    onTrailingLineHeightChanged((layout.getLineBottom(layout.lineCount - 1) - layout.getLineTop(layout.lineCount - 1)).roundToInt())
                },
            )
        }
    }
}

@Composable
private fun PlainLyricsList(
    lines: List<PlayerLyricLine>,
    modifier: Modifier,
    secondary: List<String?>,
    showToggle: Boolean,
    fullscreen: Boolean = false,
    fontScale: Float = 1f,
) {
    val colors = LocalTuneColors.current
    val primaryBase = if (fullscreen) {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.bodyLarge
    }
    val secondaryBase = if (fullscreen) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val primaryStyle = remember(primaryBase, fontScale) { primaryBase.scaledBy(fontScale) }
    val secondaryStyle = remember(secondaryBase, fontScale) { secondaryBase.scaledBy(fontScale) }
    LazyColumn(
        modifier = modifier.testTag("plain_lyrics_list"),
        contentPadding = PaddingValues(
            start = LyricsContentHorizontalInset,
            end = LyricsContentHorizontalInset,
            bottom = if (showToggle) 72.dp else 0.dp,
        ),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            Column(Modifier.fillMaxWidth().padding(vertical = if (fullscreen) 12.dp else 8.dp)) {
                Text(text = line.primary, color = colors.onPrimary, style = primaryStyle)
                (secondary.getOrNull(index) ?: line.secondary)?.let {
                    Text(text = it, color = colors.foregroundSubtle, style = secondaryStyle, modifier = Modifier.padding(top = 2.dp))
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
