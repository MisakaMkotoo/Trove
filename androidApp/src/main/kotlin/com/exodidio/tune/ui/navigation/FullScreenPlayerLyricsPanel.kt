package com.exodidio.tune.ui.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.lyrics.LyricOffsetStore
import com.exodidio.tune.lyrics.LyricsGravitySolver
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
internal fun FullScreenPlayerLyricsPanel(
    trackId: String,
    lyrics: String?,
    loading: Boolean = false,
    isPlaying: Boolean = true,
    translationVisible: Boolean = true,
    onTranslationToggle: () -> Unit = {},
    springStrength: Float = 170f,
    browseIdleReturnMs: Long = BrowseIdleReturnMs,
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
    /** 歌词重力彩蛋：唱完的词条掉到底部堆叠，摇晃散开。 */
    lyricsGravityEnabled: Boolean = false,
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
    val lyricOffsets by LyricOffsetStore.offsets.collectAsStateWithLifecycle()
    val lyricOffsetMs = lyricOffsets[trackId] ?: 0L
    val effectivePositionMs = (currentPositionMs - lyricOffsetMs).coerceAtLeast(0L)
    val effectivePendingSeekMs = pendingSeekPositionMs?.let { (it - lyricOffsetMs).coerceAtLeast(0L) }
    // 每次重绘都投最高刷新率：ARR 默认会把无触摸的自动滚动压到 60Hz。
    val view = LocalView.current
    val maxRefreshRate = remember(view) { view.display?.supportedModes?.maxByOrNull { it.refreshRate }?.refreshRate ?: 120f }
    Box(
        modifier = modifier
            .clipToBounds()
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
                effectivePositionMs,
                effectivePendingSeekMs,
                seekRequestId,
                { lineMs -> onSeek((lineMs + lyricOffsetMs).coerceAtLeast(0L)) },
                Modifier.fillMaxSize(),
                translationVisible,
                isPlaying = isPlaying,
                springStrength = springStrength,
                browseIdleReturnMs = browseIdleReturnMs,
                fullscreen = fullscreen,
                bottomInsetPx = bottomInsetPx,
                onShowControls = onShowControls,
                onHideControls = onHideControls,
                onSeekInteraction = onSeekInteraction,
                fontScale = fontScale,
                lyricsGravityEnabled = lyricsGravityEnabled,
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
        val controlsInsetDp = with(LocalDensity.current) { controlsInsetPx.toDp() }
        val buttonExtraBottomDp = with(LocalDensity.current) { 25f.toDp() }
        AnimatedVisibility(
            visible = !loading && !lyrics.isNullOrBlank() && hasTranslation && translationButtonVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = LyricsContentHorizontalInset,
                    bottom = controlsInsetDp + 12.dp + buttonExtraBottomDp,
                ),
        ) {
            TranslationToggle(
                enabled = translationVisible,
                onClick = {
                    onInteraction()
                    onTranslationToggle()
                },
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
    browseIdleReturnMs: Long = BrowseIdleReturnMs,
    fullscreen: Boolean = false,
    bottomInsetPx: Float = 0f,
    fontScale: Float = 1f,
    onShowControls: () -> Unit = {},
    onHideControls: () -> Unit = {},
    onSeekInteraction: () -> Unit = {},
    lyricsGravityEnabled: Boolean = false,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var isUserDragging by remember { mutableStateOf(false) }
    // 松手后的惯性滚动任务：再次按下必须先取消，否则衰减动画会和拖动抢写 browseScroll。
    val flingJobState = remember { mutableStateOf<Job?>(null) }
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
    // 布局期回填的实测行高与屏幕 Y；只在主线程布局/事件中读写，不参与重组。
    val measuredHeights = remember(lines) { IntArray(lines.size) }
    val placedScreenYs = remember(lines) { FloatArray(lines.size) }
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
    val lyricClock = rememberLyricClock(displayedPositionMs, isPlaying, trackKey = trackId)
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
    val gravityBlockStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val textMeasurer = rememberTextMeasurer()
    val gravitySolver = remember(trackId, density.density) {
        LyricsGravitySolver(maxBlocks = 56, pixelsPerDp = density.density)
    }
    val gravityLayouts = remember(gravitySolver) { HashMap<Long, TextLayoutResult>() }
    val gravityWakeChannel = remember(gravitySolver) { Channel<Unit>(Channel.CONFLATED) }
    var gravityRenderVersion by remember(gravitySolver) { mutableIntStateOf(0) }
    var lastGravityActiveIndex by remember(gravitySolver, lines, lyricsGravityEnabled) {
        mutableIntStateOf(-1)
    }
    var lastSensorGravityX by remember { mutableFloatStateOf(0f) }
    var lastSensorGravityY by remember { mutableFloatStateOf(Float.NaN) }
    val latestBottomInsetPx by rememberUpdatedState(bottomInsetPx)
    val pillPadHPx = with(density) { 12.dp.roundToPx() }
    val pillPadVPx = with(density) { 6.dp.roundToPx() }
    val horizInsetPx = with(density) { LyricsContentHorizontalInset.roundToPx() }
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

    /** 屏幕 Y 落在哪一行：拖动起手时用这一行当锚点，抓取时画面不跳。 */
    fun rowIndexAtScreenY(y: Float): Int {
        for (index in lines.indices) {
            val top = rowScreenYAt(index)
            if (y < top + measuredHeights.getOrElse(index) { 0 }) return index
        }
        return lines.lastIndex.coerceAtLeast(0)
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
        gravitySolver.clear()
        gravityLayouts.clear()
        lastGravityActiveIndex = latestActiveIndex
        gravityRenderVersion += 1
    }
    LaunchedEffect(trackId) {
        resetToStart()
    }
    LaunchedEffect(gravitySolver) {
        if (!lastSensorGravityY.isNaN()) {
            gravitySolver.setGravity(lastSensorGravityX, lastSensorGravityY)
        }
    }
    LaunchedEffect(lyricsGravityEnabled) {
        if (!lyricsGravityEnabled) {
            gravitySolver.clear()
            gravityLayouts.clear()
            lastGravityActiveIndex = -1
            gravityRenderVersion += 1
        }
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
            if (lyricsGravityEnabled) {
                gravitySolver.clear()
                gravityLayouts.clear()
                lastGravityActiveIndex = latestActiveIndex
                gravityRenderVersion += 1
            }
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

    // 浏览歌词时不再显示当前行、也不跟随；手指离开后静止设定时长回到跟随状态。
    // isUserDragging 参与 key：按住期间的计时会被取消，避免手指还在屏上就被切回 Follow。
    LaunchedEffect(browseIdleVersion, isBrowsing, isUserDragging, browseIdleReturnMs) {
        if (!isBrowsing || isUserDragging) return@LaunchedEffect
        delay(browseIdleReturnMs.coerceAtLeast(500L))
        if (!isBrowsing || isUserDragging) return@LaunchedEffect
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
    LaunchedEffect(activeIndex, lines, lyricsGravityEnabled, gravitySolver, viewportWidthPx, viewportHeightPx) {
        if (!lyricsGravityEnabled || activeIndex < 0 || viewportWidthPx <= 0 || viewportHeightPx <= 0) return@LaunchedEffect
        if (lastGravityActiveIndex < 0) {
            lastGravityActiveIndex = activeIndex
            return@LaunchedEffect
        }
        if (activeIndex > lastGravityActiveIndex) {
            if (activeIndex - lastGravityActiveIndex <= 2) {
                val maxTextW = (viewportWidthPx - horizInsetPx * 2 - pillPadHPx * 2).coerceAtLeast(80)
                val floorHeight = (viewportHeightPx - latestBottomInsetPx).coerceAtLeast(120f)
                for (idx in lastGravityActiveIndex until activeIndex) {
                    val line = lines.getOrNull(idx) ?: continue
                    if (line.isCountdown || line.primary.isBlank()) continue
                    val layout = textMeasurer.measure(
                        text = AnnotatedString(line.primary),
                        style = gravityBlockStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = maxTextW),
                    )
                    val pillW = (layout.size.width + pillPadHPx * 2).toFloat().coerceAtLeast(48f)
                    val pillH = (layout.size.height + pillPadVPx * 2).toFloat().coerceAtLeast(28f)
                    val maxY = (floorHeight - pillH).coerceAtLeast(0f)
                    val startY = placedScreenYs.getOrElse(idx) { focusScreenY(idx) }.coerceIn(0f, maxY)
                    val startX = if (line.alignEnd) {
                        (viewportWidthPx - horizInsetPx - pillW).coerceAtLeast(8f)
                    } else {
                        horizInsetPx.toFloat()
                    }
                    gravitySolver.spawnBlock(
                        lineIndex = idx,
                        text = line.primary,
                        startX = startX,
                        startY = startY,
                        width = pillW,
                        height = pillH,
                        viewportWidth = viewportWidthPx.toFloat(),
                    )
                    gravitySolver.blocks.lastOrNull()?.let { spawned ->
                        gravityLayouts[spawned.id] = layout
                    }
                }
                val activeIds = gravitySolver.blocks.mapTo(HashSet()) { it.id }
                gravityLayouts.keys.retainAll(activeIds)
                gravityWakeChannel.trySend(Unit)
                gravityRenderVersion += 1
            } else {
                gravitySolver.clear()
                gravityLayouts.clear()
                gravityRenderVersion += 1
            }
        } else if (activeIndex < lastGravityActiveIndex) {
            gravitySolver.clear()
            gravityLayouts.clear()
            gravityRenderVersion += 1
        }
        lastGravityActiveIndex = activeIndex
    }
    LaunchedEffect(bottomInsetPx, gravitySolver, lyricsGravityEnabled, viewportHeightPx) {
        if (!lyricsGravityEnabled || viewportHeightPx <= 0) return@LaunchedEffect
        val simHeight = (viewportHeightPx - bottomInsetPx).coerceAtLeast(120f)
        if (gravitySolver.onFloorBoundsChanging(simHeight)) {
            gravityWakeChannel.trySend(Unit)
        }
    }
    LaunchedEffect(lyricsGravityEnabled, gravitySolver, viewportWidthPx, viewportHeightPx) {
        if (!lyricsGravityEnabled || viewportWidthPx <= 0 || viewportHeightPx <= 0) return@LaunchedEffect
        while (true) {
            if (!gravitySolver.hasAwakeBlocks()) {
                gravityWakeChannel.receive()
                continue
            }
            var lastNanos = 0L
            while (gravitySolver.hasAwakeBlocks()) {
                withFrameNanos { frameNanos ->
                    if (lastNanos != 0L) {
                        val dtSec = (frameNanos - lastNanos) / 1_000_000_000f
                        val simHeight = (viewportHeightPx - latestBottomInsetPx).coerceAtLeast(120f)
                        gravitySolver.step(dtSec, viewportWidthPx.toFloat(), simHeight)
                        gravityRenderVersion += 1
                    }
                    lastNanos = frameNanos
                }
            }
        }
    }
    DisposableEffect(context, view, lyricsGravityEnabled, reduceMotion, density.density, gravitySolver) {
        if (!lyricsGravityEnabled || reduceMotion) {
            return@DisposableEffect onDispose {}
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (manager == null) {
            return@DisposableEffect onDispose {}
        }
        val gravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val linearSensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accelFallback = if (gravitySensor == null) manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        if (gravitySensor == null && accelFallback == null) {
            return@DisposableEffect onDispose {}
        }

        val fullGravityPx = with(density) { 920.dp.toPx() }
        var hasLowPassGravity = false
        var lpGx = 0f
        var lpGy = 9.81f
        var lastLinearEventNs = 0L
        var lastHapticAtMs = 0L

        fun toScreenAxes(sx: Float, sy: Float): Pair<Float, Float> {
            return when (view.display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90 -> sy to -sx
                Surface.ROTATION_180 -> sx to -sy
                Surface.ROTATION_270 -> -sy to sx
                else -> -sx to sy
            }
        }

        fun updateGravityFromSensor(rawGx: Float, rawGy: Float) {
            val (screenGx, screenGy) = toScreenAxes(rawGx, rawGy)
            val planeMag = hypot(screenGx, screenGy)
            // When laid nearly flat on a table (< ~9°), blend a gentle downward component so lines still drift to the bottom.
            val flatFactor = ((1.6f - planeMag) / 1.6f).coerceIn(0f, 1f)
            val effectiveGy = screenGy + flatFactor * 2.8f
            val gxPx = (screenGx / 9.81f) * fullGravityPx
            val gyPx = (effectiveGy / 9.81f) * fullGravityPx
            lastSensorGravityX = gxPx
            lastSensorGravityY = gyPx
            val wasAwake = gravitySolver.hasAwakeBlocks()
            gravitySolver.setGravity(axPxPerSec2 = gxPx, ayPxPerSec2 = gyPx)
            if (!wasAwake && gravitySolver.hasAwakeBlocks()) {
                gravityWakeChannel.trySend(Unit)
            }
        }

        fun applyLinearAccelerationSample(rawAx: Float, rawAy: Float, timestampNs: Long) {
            if (gravitySolver.blocks.isEmpty()) {
                lastLinearEventNs = timestampNs
                return
            }
            val (screenAx, screenAy) = toScreenAxes(rawAx, rawAy)
            val linMag = hypot(screenAx, screenAy)
            val dtSensor = if (lastLinearEventNs != 0L) {
                ((timestampNs - lastLinearEventNs) / 1_000_000_000f).coerceIn(0.005f, 0.035f)
            } else {
                0.02f
            }
            lastLinearEventNs = timestampNs
            if (linMag <= 1.45f) return

            val excessScale = (linMag - 1.45f) / linMag
            val impulseScale = (fullGravityPx / 9.81f) * 1.45f * dtSensor * excessScale
            val angNoise = if (linMag > 8.0f) ((linMag - 8.0f) * 0.25f).coerceAtMost(4.5f) else 0f
            val wasAwake = gravitySolver.hasAwakeBlocks()
            gravitySolver.applyLinearImpulse(
                dvxPxPerSec = screenAx * impulseScale,
                dvyPxPerSec = screenAy * impulseScale,
                angularNoiseFactor = angNoise,
            )
            if (!wasAwake && gravitySolver.hasAwakeBlocks()) {
                gravityWakeChannel.trySend(Unit)
            }
            if (linMag > 14f) {
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nowMs - lastHapticAtMs > 420L) {
                    lastHapticAtMs = nowMs
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        updateGravityFromSensor(event.values[0], event.values[1])
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        applyLinearAccelerationSample(event.values[0], event.values[1], event.timestamp)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val ax = event.values[0]
                        val ay = event.values[1]
                        if (!hasLowPassGravity) {
                            lpGx = ax
                            lpGy = ay
                            hasLowPassGravity = true
                        } else {
                            val alpha = 0.82f
                            lpGx = alpha * lpGx + (1f - alpha) * ax
                            lpGy = alpha * lpGy + (1f - alpha) * ay
                        }
                        updateGravityFromSensor(lpGx, lpGy)
                        applyLinearAccelerationSample(ax - lpGx, ay - lpGy, event.timestamp)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (gravitySensor != null) {
            manager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
            if (linearSensor != null) {
                manager.registerListener(listener, linearSensor, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            manager.registerListener(listener, accelFallback, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose {
            manager.unregisterListener(listener)
        }
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged {
                viewportHeightPx = it.height
                viewportWidthPx = it.width
            }
            .pointerInput(lines) {
                val velocityTracker = VelocityTracker()
                val directionGate = density.lyricsDirectionGate()
                detectVerticalDragGestures(
                    onDragStart = { startOffset ->
                        isUserDragging = true
                        isBrowsing = true
                        browseIdleVersion += 1
                        flingJobState.value?.cancel()
                        // 先按起手位置那一行的当前画面坐标取滚动量，再收尾过渡；
                        // 否则拿到的是过渡终点，列表会瞬跳一下。
                        val grabRow = rowIndexAtScreenY(startOffset.y)
                        browseScroll = clampScroll(prefixTop(grabRow) - rowScreenYAt(grabRow))
                        finishTransition()
                        scrollMode = LyricsScrollMode.Browse
                        followCompletedRun = followRunId
                        directionGate.reset()
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        isUserDragging = false
                        val velocity = velocityTracker.calculateVelocity().y
                        flingJobState.value = scope.launch { flingOffset(velocity) }
                    },
                    onDragCancel = { isUserDragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    // 同一组触摸不抬手时，每次真实换向都重新响应：
                    // 手指下滑（上翻）→ 唤出控件；手指上滑（下翻）→ 隐藏控件、进入全屏。
                    when (directionGate.onDrag(change.position.y, dragAmount, change.uptimeMillis)) {
                        1 -> onShowControls()
                        -1 -> onHideControls()
                    }
                    browseScroll = clampScroll(browseScroll - dragAmount)
                }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val cutoff = (size.height - bottomInsetPx).coerceIn(0f, size.height)
                    drawLyricsEdgeFade(
                        bottomCutoff = cutoff,
                        edgeRange = edgeRangePx,
                        edgeFadeStops = edgeFadeStops,
                        maskPaints = maskPaints,
                    ) {
                        // 顶部与截止线以下硬裁切：滚出顶部和超长当前行都不越界绘制。
                        clipRect(top = 0f, bottom = cutoff) { this@drawWithContent.drawContent() }
                    }
                },
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
                        hiddenAsFallen = lyricsGravityEnabled && activeIndex >= 0 && index < activeIndex,
                        onClick = {
                            if (!line.isCountdown && isLyricRowClickableWithinViewport(
                                    rowTopY = placedScreenYs.getOrElse(index) { 0f },
                                    rowHeightPx = measuredHeights.getOrElse(index) { 0 },
                                    viewportHeightPx = viewportHeightPx,
                                    bottomInsetPx = bottomInsetPx,
                                )
                            ) {
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
                    // 对唱行靠右：按实测宽度补左偏移，未分边的行仍在左侧。
                    val x = insetPx + if (lines[index].alignEnd) (childWidth - placeable.width) else 0
                    if (index in placedScreenYs.indices) placedScreenYs[index] = screenY
                    placeable.place(x, screenY.roundToInt())
                    top += heights[index]
                }
            }
        }
        }
        if (lyricsGravityEnabled) {
            val colors = LocalTuneColors.current
            val blockBgColor = colors.glassElevated.copy(alpha = 0.78f)
            val blockBorderColor = colors.borderGlass
            val blockTextColor = colors.onPrimary.copy(alpha = 0.92f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION")
                gravityRenderVersion
                val blocks = gravitySolver.blocks
                for (block in blocks) {
                    val layout = gravityLayouts[block.id] ?: continue
                    val cx = block.x + block.width / 2f
                    val cy = block.y + block.height / 2f
                    withTransform({
                        rotate(degrees = block.angleDeg, pivot = Offset(cx, cy))
                    }) {
                        val radius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                        drawRoundRect(
                            color = blockBgColor,
                            topLeft = Offset(block.x, block.y),
                            size = Size(block.width, block.height),
                            cornerRadius = radius,
                        )
                        drawRoundRect(
                            color = blockBorderColor,
                            topLeft = Offset(block.x, block.y),
                            size = Size(block.width, block.height),
                            cornerRadius = radius,
                            style = Stroke(width = 0.8.dp.toPx()),
                        )
                        drawText(
                            textLayoutResult = layout,
                            color = blockTextColor,
                            topLeft = Offset(
                                block.x + pillPadHPx,
                                block.y + (block.height - layout.size.height) / 2f,
                            ),
                        )
                    }
                }
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
    val directionGate = remember(density) { density.lyricsDirectionGate() }
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
            .clipToBounds()
            .lyricsOverlayDirections(
                gate = directionGate,
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
                    clipRect(top = 0f, bottom = cutoff) { this@drawWithContent.drawContent() }
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

internal fun isLyricRowClickableWithinViewport(
    rowTopY: Float,
    rowHeightPx: Int,
    viewportHeightPx: Int,
    bottomInsetPx: Float,
): Boolean {
    if (rowHeightPx <= 0 || viewportHeightPx <= 0) return false
    val cutoff = (viewportHeightPx.toFloat() - bottomInsetPx).coerceIn(0f, viewportHeightPx.toFloat())
    val rowBottomY = rowTopY + rowHeightPx.toFloat()
    return rowBottomY > 0f && rowTopY < cutoff
}
