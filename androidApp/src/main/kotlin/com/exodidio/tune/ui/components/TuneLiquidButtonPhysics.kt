package com.exodidio.tune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.time.Duration.Companion.milliseconds

internal const val GlassDragCompliance = 0.05f
internal const val GlassDragStiffnessFactor = 20f

internal data class LiquidSquashStretch(
    val scaleX: Float,
    val scaleY: Float,
)

/**
 * Sticky saturating rubber-band displacement matching Glasmio / UIKit Flex:
 * initial compliance 0.05 (20:1 anchor resistance) with smooth tanh saturation.
 */
internal fun applyGlassDragDisplacement(
    rawDeltaPx: Float,
    maxDisplacementPx: Float,
    compliance: Float = GlassDragCompliance,
): Float {
    if (maxDisplacementPx <= 0f || compliance <= 0f || !rawDeltaPx.isFinite()) return 0f
    val normalized = (compliance * rawDeltaPx) / maxDisplacementPx
    return (maxDisplacementPx * tanh(normalized.toDouble()).toFloat()).finiteOrZero()
}

/**
 * Sticky saturating rubber-band displacement using hyperbolic tangent so dragging starts
 * with heavy anchor resistance (default stiffnessFactor = 20f -> 0.05 compliance) and saturates smoothly.
 */
internal fun dampedLiquidDisplacement(
    rawDeltaPx: Float,
    maxDisplacementPx: Float,
    stiffnessFactor: Float = GlassDragStiffnessFactor,
): Float {
    if (maxDisplacementPx <= 0f || stiffnessFactor <= 0f || !rawDeltaPx.isFinite()) return 0f
    val normalized = rawDeltaPx / (maxDisplacementPx * stiffnessFactor)
    return (maxDisplacementPx * tanh(normalized.toDouble()).toFloat()).finiteOrZero()
}

/**
 * Computes angle-projected directional stretch combined with UIKit Flex press expansion.
 */
internal fun computeGlassDragScale(
    rawOffsetPx: Offset,
    widthPx: Float,
    heightPx: Float,
    pressProgress: Float,
    pressedScale: Float,
    maxStretch: Float = 0.16f,
): LiquidSquashStretch {
    val safeProgress = if (pressProgress.isFinite()) pressProgress.coerceIn(-0.25f, 1.35f) else 0f
    val baseScale = 1f + (pressedScale - 1f) * safeProgress
    val maxDim = maxOf(widthPx, heightPx)
    if (maxDim <= 0f || (!rawOffsetPx.x.isFinite() || !rawOffsetPx.y.isFinite())) {
        return LiquidSquashStretch(scaleX = baseScale, scaleY = baseScale)
    }
    val dist = hypot(rawOffsetPx.x, rawOffsetPx.y)
    if (dist <= 1e-3f) {
        return LiquidSquashStretch(scaleX = baseScale, scaleY = baseScale)
    }
    val angle = atan2(rawOffsetPx.y, rawOffsetPx.x)
    val c = cos(angle)
    val s = sin(angle)
    val cos2 = c * c
    val sin2 = s * s
    val mag = maxStretch * tanh((dist / (maxDim * 1.6f)).toDouble()).toFloat()
    val sx = (baseScale * (1f + mag * (cos2 - 0.5f * sin2))).coerceIn(0.80f, 1.38f)
    val sy = (baseScale * (1f + mag * (sin2 - 0.5f * cos2))).coerceIn(0.80f, 1.38f)
    return LiquidSquashStretch(scaleX = sx, scaleY = sy)
}

/**
 * Computes volume-preserving directional stretch combined with press expansion.
 */
internal fun liquidSquashStretch(
    dampedOffsetPx: Offset,
    maxDisplacementPx: Float,
    pressProgress: Float,
    pressedScale: Float,
    stretchAmount: Float = 0.18f,
): LiquidSquashStretch {
    val p = safeGlassProgress(pressProgress)
    val baseScale = 1f + (pressedScale - 1f) * p
    if (maxDisplacementPx <= 0f) {
        return LiquidSquashStretch(scaleX = baseScale, scaleY = baseScale)
    }
    val nx = (abs(dampedOffsetPx.x) / maxDisplacementPx).coerceIn(0f, 1f)
    val ny = (abs(dampedOffsetPx.y) / maxDisplacementPx).coerceIn(0f, 1f)
    val sx = (baseScale * (1f + stretchAmount * nx - stretchAmount * 0.45f * ny)).coerceIn(0.82f, 1.38f)
    val sy = (baseScale * (1f + stretchAmount * ny - stretchAmount * 0.45f * nx)).coerceIn(0.82f, 1.38f)
    return LiquidSquashStretch(scaleX = sx, scaleY = sy)
}

@Stable
internal class TuneLiquidButtonPhysics(
    private val scope: CoroutineScope,
    private val maxDisplacementPx: Float,
    private val pressedScale: Float,
) {
    private val pressAnim = Animatable(0f)
    private val offsetAnim = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var dragJob: Job? = null

    // UIKit _UIFlexInteraction compact-button springs (damping 0.625 / 0.262s press, 0.375 / 0.40s release)
    private val pressSpring = spring<Float>(
        dampingRatio = 0.625f,
        stiffness = loupeSpringStiffness(0.262f),
        visibilityThreshold = 0.001f,
    )
    private val releaseSpring = spring<Float>(
        dampingRatio = 0.375f,
        stiffness = loupeSpringStiffness(0.400f),
        visibilityThreshold = 0.001f,
    )
    private val offsetSpring = spring(
        dampingRatio = 0.5f,
        stiffness = 300f,
        visibilityThreshold = Offset.VisibilityThreshold,
    )

    val pressProgress: Float get() = pressAnim.value
    val offsetPx: Offset get() = offsetAnim.value

    val layerBlock: GraphicsLayerScope.() -> Unit = {
        val rawOffset = offsetAnim.value
        val minDim = min(size.width, size.height)
        val effectiveLimit = if (minDim > 0f) {
            minOf(minDim * 0.45f, (maxDisplacementPx * 2.4f).coerceAtLeast(minDim * 0.3f))
        } else {
            maxDisplacementPx
        }
        val stretch = computeGlassDragScale(
            rawOffsetPx = rawOffset,
            widthPx = size.width,
            heightPx = size.height,
            pressProgress = pressAnim.value,
            pressedScale = pressedScale,
        )
        scaleX = stretch.scaleX
        scaleY = stretch.scaleY
        translationX = applyGlassDragDisplacement(rawOffset.x, effectiveLimit)
        translationY = applyGlassDragDisplacement(rawOffset.y, effectiveLimit)
    }

    fun onPressStart() {
        scope.launch {
            pressAnim.animateTo(1f, pressSpring)
        }
    }

    fun onDragOffset(rawDelta: Offset) {
        val safeDelta = Offset(rawDelta.x.finiteOrZero(), rawDelta.y.finiteOrZero())
        dragJob?.cancel()
        dragJob = scope.launch {
            offsetAnim.snapTo(safeDelta)
        }
    }

    fun onRelease() {
        dragJob?.cancel()
        dragJob = null
        scope.launch {
            launch { pressAnim.animateTo(0f, releaseSpring) }
            launch { offsetAnim.animateTo(Offset.Zero, offsetSpring) }
        }
    }
}

@Composable
internal fun rememberTuneLiquidButtonPhysics(
    maxDisplacementDp: Dp = 5.dp,
    pressedScale: Float = 1.15f,
): TuneLiquidButtonPhysics {
    val scope = rememberCoroutineScope()
    val maxDispPx = with(LocalDensity.current) { maxDisplacementDp.toPx() }
    return remember(scope, maxDispPx, pressedScale) {
        TuneLiquidButtonPhysics(
            scope = scope,
            maxDisplacementPx = maxDispPx,
            pressedScale = pressedScale,
        )
    }
}

@Composable
internal fun Modifier.liquidButtonGesture(
    physics: TuneLiquidButtonPhysics,
    enabled: Boolean = true,
    reduceMotion: Boolean = LocalReduceMotion.current,
    allowParentVerticalScroll: Boolean = false,
    cancelRadiusDp: Dp = 44.dp,
    onClick: () -> Unit,
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val currentAllowParentScroll by rememberUpdatedState(allowParentVerticalScroll)
    val cancelRadiusPx = with(LocalDensity.current) { cancelRadiusDp.toPx() }
    val scope = rememberCoroutineScope()

    return pointerInput(physics, cancelRadiusPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            if (!currentEnabled) return@awaitEachGesture

            if (!currentReduceMotion) {
                physics.onPressStart()
            }

            var activePointerId = down.id
            var longPressEngaged = false
            val holdJob = scope.launch {
                delay(90.milliseconds)
                longPressEngaged = true
            }

            var cancelledByMove = false
            val touchSlop = viewConfiguration.touchSlop

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == activePointerId }
                        ?: event.changes.firstOrNull { it.pressed }
                        ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        if (!cancelledByMove && !change.isConsumed) {
                            change.consume()
                            currentOnClick()
                        }
                        break
                    }
                    activePointerId = change.id
                    if (change.isConsumed) {
                        break
                    }
                    val totalDelta = change.position - down.position
                    val distance = hypot(totalDelta.x, totalDelta.y)
                    if (currentAllowParentScroll && !longPressEngaged &&
                        abs(totalDelta.y) > touchSlop && abs(totalDelta.y) > abs(totalDelta.x) * 1.4f
                    ) {
                        // Let enclosing LazyColumn/ScrollView scroll cleanly.
                        break
                    }
                    if (distance > cancelRadiusPx) {
                        cancelledByMove = true
                    }
                    if (longPressEngaged || distance > touchSlop * 0.5f) {
                        change.consume()
                    }
                    if (!currentReduceMotion) {
                        physics.onDragOffset(totalDelta)
                    }
                }
            } finally {
                holdJob.cancel()
                if (!currentReduceMotion) {
                    physics.onRelease()
                }
            }
        }
    }
}
