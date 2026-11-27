package com.exodidio.tune.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.exodidio.tune.ui.theme.TuneColors
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Shared full-width control track for playback position and volume.
 *
 * This intentionally does not use Material Slider: fullscreen playback needs a
 * stable translucent track with a white value fill and no Material-style
 * circular thumb. The track deliberately does not blur the artwork behind it:
 * rendering Haze within a 6dp-high clipped shape produces visible sampling
 * artifacts on the unfilled portion on some Android GPUs.
 */
@Composable
fun TuneTrackSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    onInteractionChange: (Boolean) -> Unit = {},
    trackHeight: Dp = 3.dp,
    activeTrackColor: Color? = null,
    inactiveTrackColor: Color? = null,
    trackAlignment: Alignment = Alignment.Center,
    highlightRange: ClosedFloatingPointRange<Float>? = null,
    highlightColor: Color? = null,
    scrubTooltipText: ((Float) -> String)? = null,
    knobDetentAndInertia: Boolean = false,
    onScrubVelocityChange: ((active: Boolean, normalizedVelocity: Float) -> Unit)? = null,
    isBuffering: Boolean = false,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var isInteracting by remember { mutableStateOf(false) }
    var dragPreviewValue by remember { mutableStateOf<Float?>(null) }
    var inertiaPreviewValue by remember { mutableStateOf<Float?>(null) }
    var gestureToken by remember { mutableIntStateOf(0) }
    val inertiaJob = remember { mutableStateOf<Job?>(null) }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var tooltipWidthPx by remember { mutableIntStateOf(0) }
    val bufferingActive = isBuffering && !isInteracting
    val bufferingBreath = if (bufferingActive) {
        if (reduceMotion) {
            0.5f
        } else {
            val transition = rememberInfiniteTransition(label = "tune-track-slider-buffering")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1300, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "tune-track-slider-buffering-phase",
            )
            bufferingBreathProgress(phase)
        }
    } else {
        0f
    }
    val animatedTrackHeight by animateDpAsState(
        targetValue = if (isInteracting) trackHeight + 3.dp else trackHeight,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tune-track-slider-height",
    )
    val animatedTrackScaleX by animateFloatAsState(
        targetValue = if (isInteracting) 1.03f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tune-track-slider-scale-x",
    )
    val animatedTrackScaleY by animateFloatAsState(
        targetValue = if (isInteracting) 1.10f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tune-track-slider-scale-y",
    )
    val filledTrackColor by animateColorAsState(
        targetValue = activeTrackColor ?: sliderFilledTrackColor(colors, isInteracting),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tune-track-slider-fill-colour",
    )
    val resolvedHighlightColor = highlightColor ?: colors.primary
    // The pointer-input coroutine is intentionally not restarted when a new
    // track changes only the callback's captured duration. Always dispatch to
    // the most recent lambdas instead.
    val currentOnValueChange = rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished = rememberUpdatedState(onValueChangeFinished)
    val currentOnInteractionChange = rememberUpdatedState(onInteractionChange)
    val currentKnobDetentAndInertia = rememberUpdatedState(knobDetentAndInertia)
    val currentOnScrubVelocityChange = rememberUpdatedState(onScrubVelocityChange)
    val safeValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    // pointerInput is intentionally long-lived. Keep its press-time anchor
    // current as playback progress recomposes beneath it.
    val currentSafeValue = rememberUpdatedState(safeValue)
    val displayedValue = dragPreviewValue ?: inertiaPreviewValue ?: safeValue
    val animatedDisplayedValue by animateFloatAsState(
        targetValue = displayedValue,
        // Keep the fill locked to the finger during a drag, then gently settle
        // onto Android's discrete system-volume step after release.
        animationSpec = if (isInteracting || inertiaPreviewValue != null) snap() else tween(140, easing = FastOutSlowInEasing),
        label = "tune-track-slider-value",
    )
    val rangeSize = valueRange.endInclusive - valueRange.start
    val fraction = if (rangeSize > 0f) {
        ((animatedDisplayedValue - valueRange.start) / rangeSize).coerceIn(0f, 1f)
    } else {
        0f
    }
    val updateValueFromDrag: (Float, Float, Float) -> Float? = { startValue, horizontalDistance, width ->
        if (enabled && width > 0f) {
            val nextValue = (startValue + rangeSize * (horizontalDistance / width))
                .coerceIn(valueRange.start, valueRange.endInclusive)
            dragPreviewValue = nextValue
            currentOnValueChange.value(nextValue)
            nextValue
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { trackWidthPx = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(safeValue, valueRange, 0)
                if (enabled) {
                    setProgress { requestedValue ->
                        currentOnValueChange.value(requestedValue.coerceIn(valueRange.start, valueRange.endInclusive))
                        true
                    }
                }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val activeToken = ++gestureToken
                    val interruptedPreview = inertiaPreviewValue
                    inertiaJob.value?.cancel()
                    inertiaJob.value = null
                    inertiaPreviewValue = null
                    val dragStartValue = dragPreviewValue ?: interruptedPreview ?: currentSafeValue.value
                    isInteracting = true
                    currentOnInteractionChange.value(true)
                    var didDrag = false
                    var horizontalDragActive = false
                    var activePointerId = down.id
                    val velocityTracker = VelocityTracker().apply {
                        addPosition(down.uptimeMillis, down.position)
                    }
                    var lastNotch = if (rangeSize > 0f) {
                        (((dragStartValue - valueRange.start) / rangeSize).coerceIn(0f, 1f) * 44f).roundToInt()
                    } else 0
                    val holdScrubJob = if (currentOnScrubVelocityChange.value != null) {
                        coroutineScope.launch {
                            delay(140L)
                            if (!didDrag) {
                                currentOnScrubVelocityChange.value?.invoke(true, 0f)
                            }
                        }
                    } else null
                    var launchedInertia = false
                    try {
                        val touchSlop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == activePointerId }
                                ?: event.changes.firstOrNull { it.pressed }
                            if (change == null || change.changedToUpIgnoreConsumed()) {
                                break
                            }
                            activePointerId = change.id
                            if (change.isConsumed) {
                                break
                            }
                            val totalDx = change.position.x - down.position.x
                            val totalDy = change.position.y - down.position.y
                            if (!horizontalDragActive) {
                                if (abs(totalDy) > touchSlop * 1.35f && abs(totalDy) > abs(totalDx) * 1.5f) {
                                    break
                                }
                                if (abs(totalDx) >= touchSlop * 0.45f && abs(totalDx) >= abs(totalDy) * 0.7f) {
                                    horizontalDragActive = true
                                    didDrag = true
                                    holdScrubJob?.cancel()
                                } else {
                                    if (abs(totalDx) >= abs(totalDy) * 0.65f) {
                                        change.consume()
                                    }
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    continue
                                }
                            }
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val velPxPerSec = velocityTracker.calculateVelocity().x
                            val velFracPerSec = if (size.width > 0) (velPxPerSec / size.width.toFloat()).coerceIn(-4f, 4f) else 0f
                            currentOnScrubVelocityChange.value?.invoke(true, velFracPerSec)
                            val updated = updateValueFromDrag(
                                dragStartValue,
                                totalDx,
                                size.width.toFloat(),
                            )
                            if (updated != null && currentKnobDetentAndInertia.value && rangeSize > 0f) {
                                val notch = (((updated - valueRange.start) / rangeSize).coerceIn(0f, 1f) * 44f).roundToInt()
                                if (notch != lastNotch) {
                                    lastNotch = notch
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                        if (didDrag) {
                            val finalValue = dragPreviewValue ?: dragStartValue
                            val releaseVelPxPerSec = velocityTracker.calculateVelocity().x
                            val velocityFracPerSec = if (size.width > 0) {
                                (releaseVelPxPerSec / size.width.toFloat()).coerceIn(-4f, 4f)
                            } else {
                                0f
                            }
                            val coastTarget = if (currentKnobDetentAndInertia.value) {
                                computeKnobInertiaTarget(
                                    finalValue = finalValue,
                                    netDragDelta = finalValue - dragStartValue,
                                    velocityFracPerSec = velocityFracPerSec,
                                    valueRange = valueRange,
                                )
                            } else {
                                finalValue
                            }
                            if (currentKnobDetentAndInertia.value && rangeSize > 0f && abs(coastTarget - finalValue) > rangeSize * 0.002f) {
                                val startNotch = lastNotch
                                inertiaPreviewValue = finalValue
                                launchedInertia = true
                                inertiaJob.value = coroutineScope.launch {
                                    val anim = Animatable(finalValue)
                                    var inertiaNotch = startNotch
                                    try {
                                        anim.animateTo(
                                            targetValue = coastTarget,
                                            animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
                                        ) {
                                            inertiaPreviewValue = value
                                            currentOnValueChange.value(value)
                                            val notch = (((value - valueRange.start) / rangeSize).coerceIn(0f, 1f) * 44f).roundToInt()
                                            if (notch != inertiaNotch) {
                                                inertiaNotch = notch
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                        currentOnValueChangeFinished.value?.invoke()
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } finally {
                                        if (gestureToken == activeToken) {
                                            inertiaPreviewValue = null
                                            dragPreviewValue = null
                                            isInteracting = false
                                            currentOnInteractionChange.value(false)
                                        }
                                    }
                                }
                            } else {
                                currentOnValueChangeFinished.value?.invoke()
                            }
                        }
                    } finally {
                        holdScrubJob?.cancel()
                        currentOnScrubVelocityChange.value?.invoke(false, 0f)
                        if (!launchedInertia && gestureToken == activeToken) {
                            isInteracting = false
                            dragPreviewValue = null
                            currentOnInteractionChange.value(false)
                        }
                    }
                }
            },
        contentAlignment = trackAlignment,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedTrackHeight)
                .graphicsLayer {
                    scaleX = animatedTrackScaleX
                    scaleY = animatedTrackScaleY
                }
                .clip(CircleShape)
                .background(inactiveTrackColor ?: colors.sliderInactive)
                .testTag(TuneTrackSliderTrackTestTag),
        ) {
            // 无论是否可交互都按当前进度绘制；缓冲中对底轨和已播进度施加同步呼吸光感。
            if (bufferingActive) {
                val radius = size.height / 2f
                drawRoundRect(
                    color = sliderBufferingInactiveOverlayColor(colors.onPrimary, bufferingBreath),
                    size = size,
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            val effectiveFillColor = if (bufferingActive) {
                sliderBufferingFilledColor(filledTrackColor, colors.onPrimary, bufferingBreath)
            } else {
                filledTrackColor
            }
            drawTrack(effectiveFillColor, fraction)
            if (highlightRange != null) {
                drawHighlightRange(
                    highlightColor = resolvedHighlightColor,
                    range = highlightRange,
                    valueRange = valueRange,
                )
            }
        }
        if (scrubTooltipText != null) {
            val tooltipOffsetYPx = with(LocalDensity.current) { (-20).dp.roundToPx() }
            val tooltipOffsetXPx = if (trackWidthPx > 0 && tooltipWidthPx > 0) {
                ((trackWidthPx * fraction) - tooltipWidthPx / 2f)
                    .roundToInt()
                    .coerceIn(0, (trackWidthPx - tooltipWidthPx).coerceAtLeast(0))
            } else {
                0
            }
            AnimatedVisibility(
                visible = isInteracting && enabled,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(tooltipOffsetXPx, tooltipOffsetYPx) },
            ) {
                val shape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .onSizeChanged { tooltipWidthPx = it.width }
                        .clip(shape)
                        .background(colors.glassOpaque.copy(alpha = 0.92f))
                        .border(0.5.dp, colors.borderGlass, shape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = scrubTooltipText(animatedDisplayedValue),
                        color = colors.textMain,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal fun computeKnobInertiaTarget(
    finalValue: Float,
    netDragDelta: Float,
    velocityFracPerSec: Float,
    valueRange: ClosedFloatingPointRange<Float>,
): Float {
    val rangeSize = valueRange.endInclusive - valueRange.start
    if (rangeSize <= 0f) return finalValue.coerceIn(valueRange.start, valueRange.endInclusive)
    if (abs(velocityFracPerSec) <= 0.25f) return finalValue.coerceIn(valueRange.start, valueRange.endInclusive)
    if (abs(netDragDelta) > rangeSize * 0.008f && netDragDelta * velocityFracPerSec < 0f) {
        return finalValue.coerceIn(valueRange.start, valueRange.endInclusive)
    }
    val maxCoast = rangeSize * 0.03f
    val coastDelta = (velocityFracPerSec * 0.032f * rangeSize).coerceIn(-maxCoast, maxCoast)
    return (finalValue + coastDelta).coerceIn(valueRange.start, valueRange.endInclusive)
}

internal const val TuneTrackSliderTrackTestTag = "tune-track-slider-track"

/** The fill tint shared by fullscreen slider supporting controls. */
internal fun sliderFilledTrackColor(
    colors: TuneColors,
    isInteracting: Boolean,
): Color = if (isInteracting) {
    colors.onPrimary
} else {
    lerp(colors.foregroundSubtle, colors.onPrimary, 0.5f)
}

internal fun bufferingBreathProgress(phase: Float): Float {
    val clamped = phase.coerceIn(0f, 1f)
    return 0.5f - 0.5f * cos(clamped * (2f * PI.toFloat()))
}

internal fun sliderBufferingFilledColor(
    baseColor: Color,
    highlightColor: Color,
    breath: Float,
): Color {
    val t = breath.coerceIn(0f, 1f)
    val pulsed = lerp(baseColor, highlightColor, 0.55f * t)
    return pulsed.copy(alpha = (baseColor.alpha * (0.48f + 0.52f * t)).coerceIn(0f, 1f))
}

internal fun sliderBufferingInactiveOverlayColor(
    highlightColor: Color,
    breath: Float,
): Color {
    val t = breath.coerceIn(0f, 1f)
    return highlightColor.copy(alpha = (0.04f + 0.20f * t).coerceIn(0f, 1f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrack(
    color: Color,
    fraction: Float,
) {
    val width = size.width * fraction
    if (width <= 0f) return
    val radius = size.height / 2f
    drawRoundRect(
        color = color,
        size = Size(width, size.height),
        cornerRadius = CornerRadius(radius, radius),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHighlightRange(
    highlightColor: Color,
    range: ClosedFloatingPointRange<Float>,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    val span = valueRange.endInclusive - valueRange.start
    if (span <= 0f) return
    val startF = ((range.start - valueRange.start) / span).coerceIn(0f, 1f)
    val endF = ((range.endInclusive - valueRange.start) / span).coerceIn(startF, 1f)
    val startX = size.width * startF
    val endX = size.width * endF
    val radius = size.height / 2f
    if (endX > startX) {
        drawRoundRect(
            color = highlightColor.copy(alpha = 0.55f),
            topLeft = Offset(startX, 0f),
            size = Size((endX - startX).coerceAtLeast(2.dp.toPx()), size.height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
    val markerW = 2.5.dp.toPx().coerceAtMost(size.width)
    val startMarkerLeft = (startX - markerW / 2f).coerceIn(0f, (size.width - markerW).coerceAtLeast(0f))
    drawRoundRect(
        color = highlightColor,
        topLeft = Offset(startMarkerLeft, 0f),
        size = Size(markerW, size.height),
        cornerRadius = CornerRadius(markerW / 2f, markerW / 2f),
    )
    if (endF > startF) {
        val endMarkerLeft = (endX - markerW / 2f).coerceIn(0f, (size.width - markerW).coerceAtLeast(0f))
        drawRoundRect(
            color = highlightColor,
            topLeft = Offset(endMarkerLeft, 0f),
            size = Size(markerW, size.height),
            cornerRadius = CornerRadius(markerW / 2f, markerW / 2f),
        )
    }
}
