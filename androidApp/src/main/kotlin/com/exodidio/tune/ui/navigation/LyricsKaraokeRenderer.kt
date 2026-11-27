package com.exodidio.tune.ui.navigation

import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

internal data class LyricClockState(
    var lastTick: Long,
    var base: Long,
    var startedAtFrameNs: Long,
    var currentMs: Long,
)

internal fun stepLyricClock(
    clock: LyricClockState,
    tick: Long,
    isPlaying: Boolean,
    frameNs: Long,
): Long {
    if (!isPlaying) {
        clock.lastTick = tick
        clock.base = tick
        clock.startedAtFrameNs = frameNs
        clock.currentMs = tick
        return tick
    }
    if (tick != clock.lastTick) {
        val current = clock.currentMs
        val backwardDelta = current - tick
        clock.lastTick = tick
        if ((tick == 0L && current > 50L) || backwardDelta > 500L || tick > current + 600L) {
            clock.base = tick
            clock.startedAtFrameNs = frameNs
            clock.currentMs = tick
        } else if (tick >= current) {
            clock.base = tick
            clock.startedAtFrameNs = frameNs
            clock.currentMs = tick
        } else {
            // 轻微超前（<=500ms）时不向后回缩，停在当前视觉进度并降速等待真实音频进度追上。
            clock.base = current
            clock.startedAtFrameNs = frameNs
            clock.currentMs = current
        }
    } else {
        val elapsed = ((frameNs - clock.startedAtFrameNs) / 1_000_000L).coerceAtLeast(0L)
        val step = if (clock.base > clock.lastTick) elapsed / 2L else elapsed
        clock.currentMs = minOf(clock.base + step, clock.lastTick + 280L)
    }
    return clock.currentMs
}

/** 播放位置插值时钟：200ms 的进度 tick 之间按帧补齐，抗抖动单调不回退，暂停即冻结。 */
@Composable
internal fun rememberLyricClock(
    positionMs: Long,
    isPlaying: Boolean,
    trackKey: Any? = Unit,
): State<Long> {
    val state = remember(trackKey) { mutableLongStateOf(positionMs) }
    val latestPosition = rememberUpdatedState(positionMs)
    val latestPlaying = rememberUpdatedState(isPlaying)
    // 常驻单循环：200ms tick 只更新基准，不重启循环，避免每秒周期性掉帧。
    LaunchedEffect(trackKey) {
        val initial = latestPosition.value
        val clock = LyricClockState(
            lastTick = initial,
            base = initial,
            startedAtFrameNs = withFrameNanos { it },
            currentMs = initial,
        )
        while (true) {
            val frame = withFrameNanos { it }
            state.longValue = stepLyricClock(
                clock = clock,
                tick = latestPosition.value,
                isPlaying = latestPlaying.value,
                frameNs = frame,
            )
        }
    }
    return state
}

/**
 * 间奏三点（AMLL 参数）：0.3em 直径、0.18em 间距，未亮 0.2 / 已亮 0.9，
 * 呼吸缩放 0.4–1.25（周期 4s），逐点错峰 80ms 入场、单点淡入 750ms。
 * 每个点按三等分时间依次点亮；呼吸与点亮都由歌词时钟驱动，暂停即冻结。
 */
@Composable
private fun InterludeDots(
    words: List<PlayerLyricWord>,
    clock: State<Long>?,
    color: Color,
    rowAlpha: Float,
    emPx: Float,
    slotHeightPx: Float,
) {
    val density = LocalDensity.current
    val diameterPx = emPx * InterludeDotDiameterEm
    val gapPx = emPx * InterludeDotGapEm
    val dotsWidthPx = diameterPx * 3f + gapPx * 2f
    val dotsHeightPx = maxOf(slotHeightPx, diameterPx * InterludeBreathMaxScale)
    val totalEntryMs = InterludeDotFadeInMs + InterludeDotStaggerMs * 2f
    val enterProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = totalEntryMs.toInt(),
                easing = LinearEasing,
            ),
        )
    }
    Canvas(
        Modifier
            .width(with(density) { dotsWidthPx.toDp() })
            .height(with(density) { dotsHeightPx.toDp() }),
    ) {
        val positionMs = clock?.value ?: 0L
        val entryElapsedMs = enterProgress.value * totalEntryMs
        repeat(3) { index ->
            val dotEntry = ((entryElapsedMs - index * InterludeDotStaggerMs) / InterludeDotFadeInMs)
                .coerceIn(0f, 1f)
            if (dotEntry <= 0f) return@repeat
            val startMs = words.getOrNull(index)?.startMs ?: 0L
            val lit = ((positionMs - startMs).toFloat() / InterludeDotLitRampMs).coerceIn(0f, 1f)
            val alpha = (InterludeDotDimAlpha + (InterludeDotLitAlpha - InterludeDotDimAlpha) * lit) *
                dotEntry * rowAlpha
            val phaseRadians = ((positionMs.toFloat() + index * InterludeDotStaggerMs) % InterludeBreathPeriodMs) /
                InterludeBreathPeriodMs * (2f * PI.toFloat())
            val breath = 0.5f - 0.5f * cos(phaseRadians)
            val scale = InterludeBreathMinScale + (InterludeBreathMaxScale - InterludeBreathMinScale) * breath
            drawCircle(
                color = color,
                radius = diameterPx * scale / 2f,
                center = Offset(
                    x = diameterPx / 2f + index * (diameterPx + gapPx),
                    y = size.height / 2f,
                ),
                alpha = alpha,
            )
        }
    }
}

/** 当前行的逐字扫亮 + 逐段上抬；字形预生成位图，逐帧只做 GPU 合成。 */
@Composable
private fun WordKaraokeLine(
    words: List<PlayerLyricWord>,
    lineText: String,
    clock: State<Long>,
    style: TextStyle,
    color: Color,
    rowAlpha: Float,
    maxWidthPx: Int,
    alignEnd: Boolean = false,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(words, lineText, style, maxWidthPx, textMeasurer, alignEnd) {
        layoutKaraokeWords(words, lineText, style, maxWidthPx, textMeasurer, alignEnd)
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
        val dimAlpha = (255 * rowAlpha * 0.26f).roundToInt().coerceIn(0, 255)
        val brightAlpha = (255 * rowAlpha).roundToInt().coerceIn(0, 255)
        layout.items.forEach { item ->
            val duration = item.durationMs.coerceAtLeast(1L)
            val start = item.word.startMs
            val filled = positionMs >= start
            val completed = positionMs >= start + duration
            val liftDuration = (maxOf(1_000f, duration.toFloat()) * 1.4f).coerceAtLeast(1f)
            val liftProgress = if (filled) {
                ((positionMs - start).toFloat() / liftDuration).coerceIn(0f, 1f)
            } else {
                0f
            }
            val liftFactor = (liftProgress + sin(Math.PI.toFloat() * liftProgress)).coerceIn(0f, 2f)
            val top = if (filled) item.top - liftPx * liftFactor else item.top
            paint.alpha = dimAlpha
            native.drawBitmap(item.bitmap, item.x, top, paint)
            when {
                !filled -> Unit
                completed -> {
                    paint.alpha = brightAlpha
                    native.drawBitmap(item.bitmap, item.x, top, paint)
                }
                else -> {
                    val progress = ((positionMs - start).toFloat() / duration).coerceIn(0f, 1f)
                    val fadeWidth = (item.height * 0.5f).coerceAtLeast(1f)
                    val fadeStart = item.x + (item.width + fadeWidth) * progress - fadeWidth
                    val fadeEnd = fadeStart + fadeWidth
                    drawMaskedWord(native, paint, item, top, fadeStart, fadeEnd, brightAlpha)
                }
            }
        }
    }
}

/** 用整行统一的全局渐变给单个词上亮色；渐变位置换算到词内坐标，超出部分自然饱和。 */
private fun drawMaskedWord(
    native: android.graphics.Canvas,
    paint: android.graphics.Paint,
    item: KaraokeWordItem,
    top: Float,
    fadeStart: Float,
    fadeEnd: Float,
    brightAlpha: Int,
) {
    val startFraction = ((fadeStart - item.x) / item.width).coerceIn(0f, 1f)
    val endFraction = ((fadeEnd - item.x) / item.width).coerceIn(0f, 1f)
    if (endFraction <= startFraction) return
    val layer = native.saveLayer(item.x, top, item.x + item.width, top + item.height, null)
    paint.alpha = brightAlpha
    native.drawBitmap(item.bitmap, item.x, top, paint)
    val maskPaint = android.graphics.Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
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
    native.drawRect(item.x, top, item.x + item.width, top + item.height, maskPaint)
    native.restoreToCount(layer)
}

private data class KaraokeWordItem(
    val word: PlayerLyricWord,
    val bitmap: android.graphics.Bitmap,
    val x: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val durationMs: Long,
)

private data class KaraokeWordLayout(val items: List<KaraokeWordItem>, val heightPx: Int)

/**
 * 整行一次排版决定换行，词的位置从这份排版结果里取。
 * 聚焦态（逐字位图）与 非聚焦态（Text）因此共用同一套换行规则，
 * 升出焦点时不会再突然换行或跳位置。
 */
private fun layoutKaraokeWords(
    words: List<PlayerLyricWord>,
    lineText: String,
    style: TextStyle,
    maxWidthPx: Int,
    measurer: TextMeasurer,
    alignEnd: Boolean = false,
): KaraokeWordLayout {
    val boldStyle = style.copy(
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
    )
    val width = maxWidthPx.coerceAtLeast(1)
    val paragraph = measurer.measure(
        AnnotatedString(lineText),
        style = boldStyle,
        constraints = Constraints(maxWidth = width),
    )
    val offsetX = if (alignEnd) (width - paragraph.size.width).coerceAtLeast(0).toFloat() else 0f
    val items = ArrayList<KaraokeWordItem>(words.size)
    var cursor = 0
    words.forEachIndexed { index, word ->
        var text = word.text
        var start = lineText.indexOf(text, cursor)
        if (start < 0) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                val found = lineText.indexOf(trimmed, cursor)
                if (found >= 0) {
                    start = found
                    text = trimmed
                }
            }
        }
        if (start < 0) return@forEachIndexed
        val end = start + text.length
        cursor = end
        val nextStart = words.getOrNull(index + 1)?.startMs
        val effectiveDuration = word.durationMs.takeIf { it > 0L }
            ?: nextStart?.let { (it - word.startMs).coerceAtLeast(1L) }
            ?: 1L
        val firstLine = paragraph.getLineForOffset(start)
        val lastLine = paragraph.getLineForOffset((end - 1).coerceAtLeast(start))
        for (lineIndex in firstLine..lastLine) {
            val segmentStart = maxOf(start, paragraph.getLineStart(lineIndex))
            val segmentEnd = minOf(end, paragraph.getLineEnd(lineIndex, visibleEnd = false))
            if (segmentEnd <= segmentStart) continue
            val segmentText = lineText.substring(segmentStart, segmentEnd)
            if (segmentText.isBlank()) continue
            val segment = measurer.measure(
                AnnotatedString(segmentText),
                style = boldStyle,
                constraints = Constraints(),
            )
            val segmentTop = paragraph.getLineBaseline(lineIndex) - segment.getLineBaseline(0)
            val imageBitmap = ImageBitmap(
                segment.size.width.coerceAtLeast(1),
                segment.size.height.coerceAtLeast(1),
            )
            segment.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(imageBitmap))
            items += KaraokeWordItem(
                word = word,
                bitmap = imageBitmap.asAndroidBitmap(),
                x = paragraph.getBoundingBox(segmentStart).left + offsetX,
                top = segmentTop,
                width = segment.size.width.toFloat(),
                height = segment.size.height.toFloat(),
                durationMs = effectiveDuration,
            )
        }
    }
    return KaraokeWordLayout(items, paragraph.size.height.coerceAtLeast(1))
}

internal fun smoothStep(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

internal fun easeOutCubic(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    val inv = 1f - v
    return 1f - inv * inv * inv
}

/** 歌词容器：不模糊，只对内容做顶部/底部竖直渐隐。 */
internal class VerticalMaskPaints {
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

internal fun DrawScope.drawLyricsEdgeFade(
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

internal const val LyricFollowStiffness = 90f
internal const val LyricSeekStiffness = 90f
internal const val KaraokeDotScale = 0.8f
private const val AccompanimentScale = 0.8f
private const val AccompanimentAlpha = 0.75f
private const val AccompanimentFadeMs = 400
private const val InterludeDotDiameterEm = 0.3f
private const val InterludeDotGapEm = 0.18f
private const val InterludeDotDimAlpha = 0.2f
private const val InterludeDotLitAlpha = 0.9f
private const val InterludeBreathMinScale = 0.4f
private const val InterludeBreathMaxScale = 1.25f
private const val InterludeBreathPeriodMs = 4_000f
private const val InterludeDotStaggerMs = 80f
private const val InterludeDotFadeInMs = 750f
private const val InterludeDotLitRampMs = 240f
private val LyricChaseFactors = floatArrayOf(1f, 0.55f, 0.35f, 0.24f, 0.18f, 0.15f)

internal fun chaseStiffness(base: Float, distance: Int): Float =
    base * LyricChaseFactors[distance.coerceIn(0, LyricChaseFactors.lastIndex)]

/** 临界阻尼弹簧的阶跃响应（AMLL 阻尼：c = 2.2×√k，即 ζ=1.1，略过阻尼、不回弹）。 */
internal fun springStepResponse(stiffness: Float, seconds: Float): Float {
    if (seconds <= 0f) return 0f
    val omega = sqrt(stiffness)
    val zeta = 1.1f
    val decay = exp(-zeta * omega * seconds)
    val damped = omega * sqrt(zeta * zeta - 1f)
    return 1f - decay * (
        cosh(damped * seconds) +
            (zeta * omega / damped) * sinh(damped * seconds)
        )
}

internal const val LyricTrailMaxRows = 6

@Composable
internal fun SyncedLyricRow(
    line: PlayerLyricLine,
    fullscreen: Boolean = false,
    translationProgress: () -> Float,
    translationHeightPx: Int,
    words: List<PlayerLyricWord> = emptyList(),
    clock: State<Long>? = null,
    textWidthPx: Int = 0,
    distance: Int,
    onClick: () -> Unit,
    fontScale: Float = 1f,
    hiddenAsFallen: Boolean = false,
) {
    val colors = LocalTuneColors.current
    val latestOnClick = rememberUpdatedState(onClick)
    val targetOpacity = when (distance) {
        0 -> 1f
        1 -> 0.25f
        2 -> 0.15f
        else -> 0.10f
    }
    val effectSpring = remember(distance) {
        spring<Float>(
            stiffness = (240f - distance.coerceIn(0, LyricTrailMaxRows) * 35f).coerceAtLeast(130f),
            dampingRatio = 1f,
        )
    }
    val activeSpring = remember { spring<Float>(stiffness = 160f, dampingRatio = 1f) }
    val primaryBase = if (fullscreen) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
    val linePrimaryBase = if (line.isCountdown) {
        primaryBase.copy(
            fontSize = primaryBase.fontSize * KaraokeDotScale,
            lineHeight = primaryBase.lineHeight * KaraokeDotScale,
        )
    } else {
        primaryBase
    }
    val primaryStyle = remember(linePrimaryBase, fontScale) { linePrimaryBase.scaledBy(fontScale) }
    val accompanimentStyle = remember(primaryBase, fontScale) {
        primaryBase.copy(
            fontSize = primaryBase.fontSize * AccompanimentScale,
            lineHeight = primaryBase.lineHeight * AccompanimentScale,
        ).scaledBy(fontScale)
    }
    val secondaryBase = MaterialTheme.typography.bodyLarge
    val secondaryStyle = remember(secondaryBase, fontScale) { secondaryBase.scaledBy(fontScale) }
    val density = LocalDensity.current
    val opacity by animateFloatAsState(targetOpacity, effectSpring, label = "synced-lyric-opacity")
    val fallenAlpha by animateFloatAsState(
        targetValue = if (hiddenAsFallen) 0f else 1f,
        animationSpec = tween(200),
        label = "synced-lyric-fallen-alpha",
    )
    val scale by animateFloatAsState(if (distance == 0) 1.07f else 1f, activeSpring, label = "synced-lyric-scale")
    val activeOffsetPx = with(density) { 6.dp.toPx() }
    val animatedTranslationY by animateFloatAsState(
        if (distance == 0) -activeOffsetPx else 0f,
        activeSpring,
        label = "synced-lyric-offset",
    )
    val lyricInteractionSource = remember { MutableInteractionSource() }
    val textAlign = if (line.alignEnd) TextAlign.End else TextAlign.Start
    Column(
        modifier = Modifier
            .then(
                if (line.alignEnd) Modifier.fillMaxWidth()
                else Modifier.wrapContentWidth(Alignment.Start),
            )
            .padding(top = 10.dp, bottom = 10.dp)
            .clickable(
                enabled = !hiddenAsFallen,
                interactionSource = lyricInteractionSource,
                indication = null,
                role = Role.Button,
            ) { latestOnClick.value() }
            .graphicsLayer {
                alpha = fallenAlpha
                scaleX = scale
                scaleY = scale
                translationY = animatedTranslationY
                transformOrigin = TransformOrigin(if (line.alignEnd) 1f else 0f, 0.5f)
                clip = false
            }
            .testTag("synced_lyric_${line.timestampSeconds}"),
    ) {
        if (line.isCountdown) {
            InterludeDots(
                words = words,
                clock = clock,
                color = colors.onPrimary,
                rowAlpha = opacity,
                emPx = with(density) { primaryBase.scaledBy(fontScale).fontSize.toPx() },
                slotHeightPx = with(density) { primaryStyle.lineHeight.toPx() },
            )
        } else if (distance == 0 && line.words.isNotEmpty() && clock != null && textWidthPx > 0) {
            WordKaraokeLine(
                words = line.words,
                lineText = line.primary,
                clock = clock,
                style = primaryStyle,
                color = colors.onPrimary,
                rowAlpha = opacity,
                maxWidthPx = textWidthPx,
                alignEnd = line.alignEnd,
            )
        } else {
            Text(
                text = line.primary,
                color = colors.onPrimary.copy(alpha = opacity),
                style = primaryStyle,
                textAlign = textAlign,
                modifier = if (line.alignEnd) Modifier.fillMaxWidth() else Modifier,
                fontWeight = FontWeight.Bold,
            )
        }
        line.accompaniments.forEach { accompaniment ->
            AccompanimentRow(
                accompaniment = accompaniment,
                clock = clock,
                style = accompanimentStyle,
                color = colors.onPrimary,
                rowAlpha = opacity,
                maxWidthPx = textWidthPx,
                alignEnd = line.alignEnd,
            )
        }
        val translation = line.secondary
        if (translation != null) {
            val fullHeightPx = (translationHeightPx + with(density) { 4.dp.roundToPx() }).coerceAtLeast(0)
            Box(
                Modifier
                    .fillMaxWidth()
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
                    textAlign = textAlign,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = translationProgress() * opacity },
                )
            }
        }
    }
}

/**
 * 和声行：挂主句下方，只在唱到 [start,end] 时展开占位，唱完收起；
 * 有逐字时间时按自己的时间扫亮，没有就整行淡入淡出。
 */
@Composable
private fun AccompanimentRow(
    accompaniment: PlayerLyricAccompaniment,
    clock: State<Long>?,
    style: TextStyle,
    color: Color,
    rowAlpha: Float,
    maxWidthPx: Int,
    alignEnd: Boolean = false,
) {
    val visible = remember(clock, accompaniment) {
        derivedStateOf {
            val position = clock?.value ?: return@derivedStateOf true
            position in accompaniment.startMs..accompaniment.endMs
        }
    }
    val progress = remember { Animatable(if (visible.value) 1f else 0f) }
    LaunchedEffect(visible.value) {
        progress.animateTo(if (visible.value) 1f else 0f, tween(AccompanimentFadeMs))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        maxWidth = constraints.maxWidth,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    ),
                )
                val height = (placeable.height * progress.value).roundToInt().coerceAtLeast(0)
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
    ) {
        val alpha = (progress.value * AccompanimentAlpha * rowAlpha).coerceIn(0f, 1f)
        if (accompaniment.words.isNotEmpty() && clock != null && maxWidthPx > 0) {
            WordKaraokeLine(
                words = accompaniment.words,
                lineText = accompaniment.text,
                clock = clock,
                style = style,
                color = color,
                rowAlpha = alpha,
                maxWidthPx = maxWidthPx,
                alignEnd = alignEnd,
            )
        } else {
            Text(
                text = accompaniment.text,
                color = color.copy(alpha = alpha),
                style = style,
                fontWeight = FontWeight.Bold,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
