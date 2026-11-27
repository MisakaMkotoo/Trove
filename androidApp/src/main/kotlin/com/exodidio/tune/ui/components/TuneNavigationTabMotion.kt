package com.exodidio.tune.ui.components

/*
 * Adapted from NavigationTabMotion, IosLoupeMotion, and LiquidMenuMorphMotion in NEORUAA/Glasmio (Apache-2.0):
 * https://github.com/NEORUAA/Glasmio
 */

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal fun loupeSpringStiffness(responseSeconds: Float): Float {
    val omega = (2.0 * PI / responseSeconds.coerceAtLeast(1e-4f)).toFloat()
    return omega * omega
}

internal fun loupeLowPassAlpha(sixtyFpsAlpha: Float, dtSeconds: Float): Float {
    val clampedAlpha = sixtyFpsAlpha.coerceIn(0f, 1f)
    val clampedDt = dtSeconds.coerceIn(0f, 0.1f)
    return 1f - (1f - clampedAlpha).pow(clampedDt / (1f / 60f))
}

internal fun loupeLerp(amount: Float, from: Float, to: Float): Float =
    (1f - amount) * from + amount * to

internal const val LoupeSoftClampSlope = 0.55f

internal fun loupeSoftClamp(value: Float, lower: Float, upper: Float): Float {
    if (!value.isFinite()) return ((lower + upper) * 0.5f).finiteOrZero()
    val margin = (upper - lower) / 3f
    if (margin <= 0f) return value.coerceIn(lower, upper)
    return when {
        value > upper -> upper + margin * tanh(((value - upper) / margin) * LoupeSoftClampSlope)
        value < lower -> (lower - margin) + margin * (tanh(((value - (lower - margin)) / margin) * LoupeSoftClampSlope - LoupeSoftClampSlope) + 1f)
        else -> value
    }
}

internal data class TuneGlassSpringSpec(
    val dampingRatio: Float,
    val response: Float,
) {
    val omega: Float = (2.0 * PI / response.coerceAtLeast(1e-4f)).toFloat()
    val alpha: Float = dampingRatio * omega
    val frequency: Float = if (dampingRatio >= 0.9999f) 0f else omega * sqrt(1f - dampingRatio * dampingRatio)
}

/**
 * Closed-form analytical damped harmonic oscillator matching UIKit CASpringAnimation / Glasmio LiquidMenuSpring.
 * Unconditionally stable across 60/90/120Hz frame pacing and preserves velocity cleanly on retargets.
 */
internal class TuneGlassSpring(
    initialValue: Float,
    private val valueTolerance: Float,
    private val velocityTolerance: Float,
) {
    var value: Float = initialValue
        private set
    var target: Float = initialValue
        private set
    var velocity: Float = 0f
        private set
    var isSettled: Boolean = true
        private set

    private var spec: TuneGlassSpringSpec? = null

    fun snapTo(newValue: Float) {
        val safe = newValue.finiteOrZero()
        value = safe
        target = safe
        velocity = 0f
        spec = null
        isSettled = true
    }

    fun target(newTarget: Float, newSpec: TuneGlassSpringSpec) {
        val safeTarget = newTarget.finiteOrZero()
        target = safeTarget
        spec = newSpec
        if (abs(value - safeTarget) <= valueTolerance && abs(velocity) <= velocityTolerance) {
            value = safeTarget
            velocity = 0f
            isSettled = true
        } else {
            isSettled = false
        }
    }

    fun advance(dtSeconds: Float): Boolean {
        if (isSettled) return false
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        if (dt <= 0f) return !isSettled
        val s = spec ?: run {
            snapTo(target)
            return false
        }

        val x0 = value - target
        val v0 = velocity
        val zeta = s.dampingRatio
        val w = s.omega
        val a = s.alpha

        val x: Float
        val v: Float
        when {
            zeta < 0.9999f -> {
                val wd = s.frequency.coerceAtLeast(1e-4f)
                val expTerm = exp(-a * dt)
                val cosTerm = cos(wd * dt)
                val sinTerm = sin(wd * dt)
                val b = (v0 + a * x0) / wd
                x = expTerm * (x0 * cosTerm + b * sinTerm)
                v = expTerm * ((b * wd - a * x0) * cosTerm - (x0 * wd + a * b) * sinTerm)
            }
            zeta <= 1.0001f -> {
                val expTerm = exp(-w * dt)
                val c = v0 + w * x0
                x = expTerm * (x0 + c * dt)
                v = expTerm * (c - w * (x0 + c * dt))
            }
            else -> {
                val root = (w * sqrt(zeta * zeta - 1f)).coerceAtLeast(1e-4f)
                val r1 = -a + root
                val r2 = -a - root
                val c1 = (v0 - r2 * x0) / (2f * root)
                val c2 = x0 - c1
                val e1 = exp(r1 * dt)
                val e2 = exp(r2 * dt)
                x = c1 * e1 + c2 * e2
                v = c1 * r1 * e1 + c2 * r2 * e2
            }
        }

        if (!x.isFinite() || !v.isFinite()) {
            snapTo(target)
            return false
        }

        if (abs(x) <= valueTolerance && abs(v) <= velocityTolerance) {
            value = target
            velocity = 0f
            isSettled = true
            return false
        }

        value = target + x
        velocity = v
        return true
    }
}

internal data class LoupeFlexMotionSpec(
    val minScale: Float,
    val maxScale: Float,
    val scalePoints: Float,
    val normalization: Float,
    val trackingFlexSpring: TuneGlassSpringSpec,
    val restingFlexSpring: TuneGlassSpringSpec,
)

internal fun loupeFlexMotionSpecForSize(widthDp: Float, heightDp: Float): LoupeFlexMotionSpec {
    val shortSide = min(widthDp, heightDp)
    val sizeFraction = ((shortSide - 37f) / 33f).coerceIn(0f, 1f)
    val response = loupeLerp(sizeFraction, 0.444f, 0.50f)
    return LoupeFlexMotionSpec(
        minScale = loupeLerp(sizeFraction, 0.90f, 0.75f),
        maxScale = loupeLerp(sizeFraction, 1.10f, 1.15f),
        scalePoints = loupeLerp(sizeFraction, 10f, 100f),
        normalization = loupeLerp(sizeFraction, 2000f, 2500f),
        trackingFlexSpring = TuneGlassSpringSpec(
            dampingRatio = loupeLerp(sizeFraction, 0.56f, 0.90f),
            response = response,
        ),
        restingFlexSpring = TuneGlassSpringSpec(
            dampingRatio = loupeLerp(sizeFraction, 0.56f, 1f),
            response = response,
        ),
    )
}

internal data class LoupeFlex(
    val scaleX: Float,
    val scaleY: Float,
    val driftXDp: Float,
)

internal fun computeLoupeFlex(
    accelerationX: Float,
    velocityX: Float,
    liveWidthDp: Float,
    liveHeightDp: Float,
): LoupeFlex {
    val safeW = liveWidthDp.coerceAtLeast(1f)
    val safeH = liveHeightDp.coerceAtLeast(1f)
    val spec = loupeFlexMotionSpecForSize(safeW, safeH)
    val nx = if (accelerationX.isFinite()) accelerationX / spec.normalization else 0f

    val widthLower = max(spec.minScale, (safeW - spec.scalePoints) / safeW)
    val widthUpper = min(spec.maxScale, (safeW + spec.scalePoints) / safeW)
    val stretch = loupeSoftClamp(loupeLerp(nx, 1f, widthUpper), widthLower, widthUpper)

    val heightLower = max(spec.minScale, (safeH - spec.scalePoints) / safeH)
    val heightUpper = min(spec.maxScale, (safeH + spec.scalePoints) / safeH)
    val squeeze = loupeSoftClamp(loupeLerp(nx, 1f, heightLower), heightLower, heightUpper)

    val vxSign = if (velocityX.isFinite() && abs(velocityX) > 1e-3f) sign(velocityX) else 0f
    val driftXDp = (1f - stretch) * 0.5f * safeW * vxSign
    return LoupeFlex(scaleX = stretch, scaleY = squeeze, driftXDp = driftXDp)
}

internal object TuneNavigationTabMotionDefaults {
    const val MinimumLiftSeconds = 0.22f
    const val LiftGrowthDp = 16f

    val TrackingCenterSpring = TuneGlassSpringSpec(dampingRatio = 0.85f, response = 0.20f)
    val TopRestingCenterSpring = TuneGlassSpringSpec(dampingRatio = 0.90f, response = 0.40f)
    val BottomRestingCenterSpring = TuneGlassSpringSpec(dampingRatio = 0.85f, response = 0.40f)

    val TopTrackingWidthSpring = TuneGlassSpringSpec(dampingRatio = 0.90f, response = 0.30f)
    val BottomTrackingWidthSpring = TuneGlassSpringSpec(dampingRatio = 0.85f, response = 0.30f)
    val RestingWidthSpring = TuneGlassSpringSpec(dampingRatio = 0.85f, response = 0.60f)

    val LensLiftSpring = TuneGlassSpringSpec(dampingRatio = 1f, response = 0.25f)
}

@Stable
internal class TuneNavigationTabMotion(
    private val scope: CoroutineScope,
    density: Density,
    private val isTopBar: Boolean,
    private val reduceMotion: Boolean = false,
) {
    private val densityScale = density.density.coerceAtLeast(0.5f)
    private val restingCenterSpring = if (isTopBar) {
        TuneNavigationTabMotionDefaults.TopRestingCenterSpring
    } else {
        TuneNavigationTabMotionDefaults.BottomRestingCenterSpring
    }
    private val trackingWidthSpring = if (isTopBar) {
        TuneNavigationTabMotionDefaults.TopTrackingWidthSpring
    } else {
        TuneNavigationTabMotionDefaults.BottomTrackingWidthSpring
    }

    private val centerSpring = TuneGlassSpring(0f, 0.05f * densityScale, 0.5f * densityScale)
    private val widthSpring = TuneGlassSpring(0f, 0.05f * densityScale, 0.5f * densityScale)
    private val liftSpring = TuneGlassSpring(0f, 0.001f, 0.015f)
    private val horizontalFlexSpring = TuneGlassSpring(1f, 0.001f, 0.015f)
    private val verticalFlexSpring = TuneGlassSpring(1f, 0.001f, 0.015f)
    private val flexDriftSpring = TuneGlassSpring(0f, 0.05f * densityScale, 0.5f * densityScale)

    var centerPx by mutableFloatStateOf(0f)
        private set
    var widthPx by mutableFloatStateOf(0f)
        private set
    var pressProgress by mutableFloatStateOf(0f)
        private set
    var scaleX by mutableFloatStateOf(1f)
        private set
    var scaleY by mutableFloatStateOf(1f)
        private set
    var flexOffsetPx by mutableFloatStateOf(0f)
        private set
    var isPressed by mutableStateOf(false)
        private set
    var targetCenterPx: Float = 0f
        private set
    var targetWidthPx: Float = 0f
        private set

    private var trackHeightPx: Float = 0f
    private var filteredPositionDp: Float = 0f
    private var filteredVelocityDp: Float = 0f
    private var filteredAccelerationDp: Float = 0f
    private var liftedForSeconds: Float = 0f
    private var releasePending: Boolean = false
    private var movementPending: Boolean = false
    private var frameJob: Job? = null

    fun snapTo(
        centerPx: Float,
        widthPx: Float,
        trackHeightPx: Float = this.trackHeightPx,
    ) {
        this.targetCenterPx = centerPx.finiteOrZero()
        this.targetWidthPx = widthPx.coerceAtLeast(0f).finiteOrZero()
        if (trackHeightPx > 0f) this.trackHeightPx = trackHeightPx

        centerSpring.snapTo(this.targetCenterPx)
        widthSpring.snapTo(this.targetWidthPx)
        liftSpring.snapTo(0f)
        horizontalFlexSpring.snapTo(1f)
        verticalFlexSpring.snapTo(1f)
        flexDriftSpring.snapTo(0f)

        filteredPositionDp = this.targetCenterPx / densityScale
        filteredVelocityDp = 0f
        filteredAccelerationDp = 0f
        liftedForSeconds = 0f
        isPressed = false
        releasePending = false
        movementPending = false
        frameJob?.cancel()
        frameJob = null
        syncOutputs()
    }

    fun updateTrackHeight(trackHeightPx: Float) {
        if (trackHeightPx > 0f) {
            this.trackHeightPx = trackHeightPx
        }
    }

    fun press() {
        if (reduceMotion) return
        isPressed = true
        releasePending = false
        liftedForSeconds = 0f
        liftSpring.target(1f, TuneNavigationTabMotionDefaults.LensLiftSpring)
        centerSpring.target(targetCenterPx, TuneNavigationTabMotionDefaults.TrackingCenterSpring)
        widthSpring.target(targetWidthPx, trackingWidthSpring)
        ensureRunning()
    }

    fun moveTo(
        centerPx: Float,
        widthPx: Float,
        trackHeightPx: Float = this.trackHeightPx,
    ) {
        val safeCenter = centerPx.finiteOrZero()
        val safeWidth = widthPx.coerceAtLeast(0f).finiteOrZero()
        this.targetCenterPx = safeCenter
        this.targetWidthPx = safeWidth
        if (trackHeightPx > 0f) this.trackHeightPx = trackHeightPx

        if (reduceMotion) {
            snapTo(safeCenter, safeWidth, this.trackHeightPx)
            return
        }

        val cSpec = if (isPressed) TuneNavigationTabMotionDefaults.TrackingCenterSpring else restingCenterSpring
        val wSpec = if (isPressed) trackingWidthSpring else TuneNavigationTabMotionDefaults.RestingWidthSpring
        centerSpring.target(safeCenter, cSpec)
        widthSpring.target(safeWidth, wSpec)
        ensureRunning()
    }

    fun release() {
        if (reduceMotion) {
            snapTo(targetCenterPx, targetWidthPx, trackHeightPx)
            return
        }
        isPressed = false
        releasePending = true
        centerSpring.target(targetCenterPx, restingCenterSpring)
        widthSpring.target(targetWidthPx, TuneNavigationTabMotionDefaults.RestingWidthSpring)
        ensureRunning()
    }

    fun cancelPress() {
        isPressed = false
        releasePending = false
        liftSpring.target(0f, TuneNavigationTabMotionDefaults.LensLiftSpring)
        centerSpring.target(targetCenterPx, restingCenterSpring)
        widthSpring.target(targetWidthPx, TuneNavigationTabMotionDefaults.RestingWidthSpring)
        ensureRunning()
    }

    internal fun advance(dtSeconds: Float): Boolean {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        if (isPressed || releasePending) {
            liftedForSeconds += dt
        }
        if (releasePending) {
            val closeEnoughToTarget = abs(centerSpring.value - targetCenterPx) <= 8f * densityScale
            if (liftedForSeconds >= TuneNavigationTabMotionDefaults.MinimumLiftSeconds && closeEnoughToTarget) {
                releasePending = false
                liftSpring.target(0f, TuneNavigationTabMotionDefaults.LensLiftSpring)
            }
        }

        centerSpring.advance(dt)
        widthSpring.advance(dt)
        liftSpring.advance(dt)
        updateFlex(dt)
        horizontalFlexSpring.advance(dt)
        verticalFlexSpring.advance(dt)
        flexDriftSpring.advance(dt)
        syncOutputs()

        return hasPendingWork()
    }

    private fun updateFlex(dtSeconds: Float) {
        if (dtSeconds <= 1e-4f) return
        val positionDp = centerSpring.value / densityScale
        val alpha = loupeLowPassAlpha(0.3f, dtSeconds)
        val nextPosition = loupeLerp(alpha, filteredPositionDp, positionDp)
        val rawVelocity = (nextPosition - filteredPositionDp) / dtSeconds
        val nextVelocity = loupeLerp(alpha, filteredVelocityDp, rawVelocity)
        val rawAcceleration = (abs(nextVelocity) - abs(filteredVelocityDp)) / dtSeconds
        val nextAcceleration = loupeLerp(alpha, filteredAccelerationDp, rawAcceleration)

        filteredPositionDp = nextPosition
        filteredVelocityDp = nextVelocity
        filteredAccelerationDp = nextAcceleration

        movementPending = !centerSpring.isSettled ||
            abs(filteredPositionDp - positionDp) > 0.01f ||
            abs(filteredVelocityDp) > 0.1f ||
            abs(filteredAccelerationDp) > 2f

        val lift = liftSpring.value.coerceIn(0f, 1f)
        val liveWidthDp = (widthSpring.value / densityScale + TuneNavigationTabMotionDefaults.LiftGrowthDp * lift).coerceAtLeast(1f)
        val baseHeightDp = if (trackHeightPx > 0f) trackHeightPx / densityScale else if (isTopBar) 34f else 54f
        val liveHeightDp = (baseHeightDp + TuneNavigationTabMotionDefaults.LiftGrowthDp * lift).coerceAtLeast(1f)

        val spec = loupeFlexMotionSpecForSize(liveWidthDp, liveHeightDp)
        val flex = computeLoupeFlex(
            accelerationX = filteredAccelerationDp,
            velocityX = filteredVelocityDp,
            liveWidthDp = liveWidthDp,
            liveHeightDp = liveHeightDp,
        )
        val flexSpring = if (isPressed) spec.trackingFlexSpring else spec.restingFlexSpring
        horizontalFlexSpring.target(flex.scaleX, flexSpring)
        verticalFlexSpring.target(flex.scaleY, flexSpring)
        flexDriftSpring.target(flex.driftXDp * densityScale, flexSpring)
    }

    private fun hasPendingWork(): Boolean =
        isPressed ||
            releasePending ||
            movementPending ||
            !centerSpring.isSettled ||
            !widthSpring.isSettled ||
            !liftSpring.isSettled ||
            !horizontalFlexSpring.isSettled ||
            !verticalFlexSpring.isSettled ||
            !flexDriftSpring.isSettled

    private fun syncOutputs() {
        centerPx = centerSpring.value.finiteOrZero()
        widthPx = widthSpring.value.coerceAtLeast(0f).finiteOrZero()
        pressProgress = liftSpring.value.coerceIn(0f, 1.25f).finiteOrZero()
        scaleX = horizontalFlexSpring.value.coerceIn(0.65f, 1.45f).finiteOrZero()
        scaleY = verticalFlexSpring.value.coerceIn(0.65f, 1.45f).finiteOrZero()
        flexOffsetPx = flexDriftSpring.value.finiteOrZero()
    }

    private fun ensureRunning() {
        if (frameJob?.isActive == true || !hasPendingWork()) return
        frameJob = scope.launch {
            var lastNanos = withFrameNanos { it }
            while (isActive) {
                val frameNanos = withFrameNanos { it }
                val dtSeconds = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(1f / 240f, 0.05f)
                lastNanos = frameNanos
                if (!advance(dtSeconds)) {
                    break
                }
            }
            frameJob = null
        }
    }
}

@Composable
internal fun rememberTuneNavigationTabMotion(
    isTopBar: Boolean,
    reduceMotion: Boolean = LocalReduceMotion.current,
): TuneNavigationTabMotion {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scope, density, isTopBar, reduceMotion) {
        TuneNavigationTabMotion(
            scope = scope,
            density = density,
            isTopBar = isTopBar,
            reduceMotion = reduceMotion,
        )
    }
}

internal data class PlatterPuffSpec(
    val scaleDeltaPerUnitProgress: Float,
    val pressSpring: SpringSpec<Float>,
    val releaseSpring: SpringSpec<Float>,
)

/**
 * UIKit _UIFlexInteraction size-dependent platter puff specification (14–16pt expansion).
 */
internal fun computePlatterPuffSpec(
    widthPx: Float,
    heightPx: Float,
    density: Float,
): PlatterPuffSpec {
    val safeDensity = density.coerceAtLeast(0.5f)
    val shortDimDp = min(widthPx, heightPx) / safeDensity
    val longDimDp = max(widthPx, heightPx) / safeDensity
    val flexSizeProgress = if (longDimDp < 120f) 0f else ((shortDimDp - 44f) / 116f).coerceIn(0f, 1f)
    val flexSpringProgress = if (longDimDp < 120f) 0f else ((longDimDp - 44f) / 116f).coerceIn(0f, 1f)
    val puffLiftPoints = 16f - 12f * flexSizeProgress
    val scaleDelta = (puffLiftPoints / longDimDp.coerceAtLeast(44f)).finiteOrZero()
    return PlatterPuffSpec(
        scaleDeltaPerUnitProgress = scaleDelta,
        pressSpring = spring(
            dampingRatio = 0.625f,
            stiffness = loupeSpringStiffness(loupeLerp(flexSpringProgress, 0.262f, 0.314f)),
            visibilityThreshold = 0.001f,
        ),
        releaseSpring = spring(
            dampingRatio = loupeLerp(flexSpringProgress, 0.375f, 0.60f),
            stiffness = loupeSpringStiffness(loupeLerp(flexSpringProgress, 0.40f, 0.36f)),
            visibilityThreshold = 0.001f,
        ),
    )
}

/**
 * Glasmio outer platter horizontal follow: 4.dp * sign * EaseOut(abs(dx / width)).
 */
internal fun computePlatterDragTranslationX(
    rawDeltaXPx: Float,
    barWidthPx: Float,
    maxOffsetPx: Float,
): Float {
    if (barWidthPx <= 0f || maxOffsetPx <= 0f || !rawDeltaXPx.isFinite()) return 0f
    val fraction = (rawDeltaXPx / barWidthPx).coerceIn(-1f, 1f)
    return (maxOffsetPx * fraction.sign * EaseOut.transform(abs(fraction))).finiteOrZero()
}
