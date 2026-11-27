package com.exodidio.tune.ui.navigation

import android.content.res.Resources
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val CornerPollAttempts = 20
private const val CornerPollDelayMs = 50L

internal const val PhoneFallbackDisplayCornerDp = 46f
internal const val TabletFallbackDisplayCornerDp = 18f

internal const val PlayerRestCornerDp = 24f
internal const val PlayerCornerRiseStart = 0.15f
internal const val PlayerCornerRiseEnd = 0.65f
internal const val PlayerCornerFlattenMillis = 140

private val FrameworkCornerDimens = listOf(
    "rounded_corner_radius_top" to "dimen:top",
    "rounded_corner_radius_bottom" to "dimen:bottom",
    "rounded_corner_radius" to "dimen:default",
)

internal data class DisplayCornerInfo(
    val runtimePx: Float?,
    val usedPx: Float,
    val source: String = "fallback",
)

internal fun systemDisplayCornerRadiusPx(view: View): Pair<Float, String>? {
    val dm = view.resources.displayMetrics
    val maxValidPx = minOf(dm.widthPixels, dm.heightPixels).toFloat() * 0.5f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val fromInsets = displayCornerRadiusFromInsetsS(view)
        if (fromInsets != null && fromInsets > 0.5f && fromInsets <= maxValidPx) {
            return fromInsets to "insets"
        }
    }
    return frameworkDisplayCornerRadiusPx()
}

@RequiresApi(Build.VERSION_CODES.S)
private fun displayCornerRadiusFromInsetsS(view: View): Float? {
    val insets = ViewCompat.getRootWindowInsets(view) ?: return null
    val radii = listOf(
        RoundedCorner.POSITION_TOP_LEFT,
        RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_LEFT,
        RoundedCorner.POSITION_BOTTOM_RIGHT,
    ).mapNotNull { insets.getRoundedCorner(it)?.radius?.takeIf { radius -> radius > 0 } }
    return radii.maxOrNull()?.toFloat()
}

private fun frameworkDisplayCornerRadiusPx(): Pair<Float, String>? {
    val res = Resources.getSystem()
    val dm = res.displayMetrics
    val maxValidPx = minOf(dm.widthPixels, dm.heightPixels).toFloat() * 0.5f
    var bestPx = 0
    var bestLabel = ""
    for ((name, label) in FrameworkCornerDimens) {
        val id = runCatching { res.getIdentifier(name, "dimen", "android") }.getOrDefault(0)
        if (id == 0) continue
        val px = runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0)
        if (px > bestPx && px <= maxValidPx) {
            bestPx = px
            bestLabel = label
        }
    }
    val arrayId = runCatching {
        res.getIdentifier("config_roundedCornerRadiusArray", "array", "android")
    }.getOrDefault(0)
    if (arrayId != 0) {
        val maxPx = runCatching { res.getIntArray(arrayId).maxOrNull() ?: 0 }.getOrDefault(0)
        if (maxPx > bestPx && maxPx <= maxValidPx) {
            bestPx = maxPx
            bestLabel = "dimen:array"
        }
    }
    return if (bestPx > 0) bestPx.toFloat() to bestLabel else null
}

@Composable
internal fun rememberDisplayCornerInfo(): DisplayCornerInfo {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val fallbackPx = with(density) { PhoneFallbackDisplayCornerDp.dp.toPx() }
    val tabletPx = with(density) { TabletFallbackDisplayCornerDp.dp.toPx() }
    var info by remember(view, isTablet) {
        mutableStateOf(
            DisplayCornerInfo(
                runtimePx = null,
                usedPx = if (isTablet) tabletPx else fallbackPx,
                source = if (isTablet) "tablet" else "fallback",
            ),
        )
    }
    LaunchedEffect(view, configuration.screenWidthDp, configuration.screenHeightDp, isTablet) {
        if (isTablet) {
            var runtime: Float? = null
            var source = "tablet"
            for (attempt in 1..CornerPollAttempts) {
                val hit = systemDisplayCornerRadiusPx(view)
                if (hit != null) {
                    runtime = hit.first
                    source = hit.second
                    break
                }
                delay(CornerPollDelayMs)
            }
            info = DisplayCornerInfo(runtimePx = runtime, usedPx = tabletPx, source = source)
            return@LaunchedEffect
        }
        repeat(CornerPollAttempts) {
            val hit = systemDisplayCornerRadiusPx(view)
            if (hit != null) {
                val (radius, source) = hit
                if (info.source == "fallback") {
                    info = DisplayCornerInfo(runtimePx = radius, usedPx = radius, source = source)
                }
                return@LaunchedEffect
            }
            delay(CornerPollDelayMs)
        }
    }
    return info
}

@Composable
internal fun rememberDisplayCornerPx(): Float = rememberDisplayCornerInfo().usedPx

internal fun playerShellCornerRadiusDp(
    progress: Float,
    peakDp: Float = PhoneFallbackDisplayCornerDp,
    rectHalfHeightDp: Float = Float.NaN,
): Float {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val safePeak = if (peakDp.isFinite()) peakDp.coerceAtLeast(0f) else PhoneFallbackDisplayCornerDp
    val rise = smoothStep01(p, PlayerCornerRiseStart, PlayerCornerRiseEnd)
    val byProgress = (PlayerRestCornerDp + (safePeak - PlayerRestCornerDp) * rise).coerceAtLeast(0f)
    return if (rectHalfHeightDp.isNaN()) {
        byProgress
    } else {
        val safeHalfH = if (rectHalfHeightDp.isFinite()) rectHalfHeightDp.coerceAtLeast(0f) else byProgress
        minOf(byProgress, safeHalfH).coerceAtLeast(0f)
    }
}

private fun smoothStep01(value: Float, start: Float, end: Float): Float {
    if (end <= start) return if (value >= end) 1f else 0f
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal class MorphSmoothCornerShape(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val cornerRadiusPx: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val clampedLeft = left.coerceIn(0f, size.width)
        val clampedTop = top.coerceIn(0f, size.height)
        val clampedRight = right.coerceIn(clampedLeft, size.width)
        val clampedBottom = bottom.coerceIn(clampedTop, size.height)
        val rectW = clampedRight - clampedLeft
        val rectH = clampedBottom - clampedTop
        val fit = minOf(rectW, rectH) * 0.5f
        val rect = Rect(clampedLeft, clampedTop, clampedRight, clampedBottom)
        if (cornerRadiusPx <= 0.5f || fit <= 0.5f) {
            return Outline.Rectangle(rect)
        }
        // When the corner radius saturates the half-height (MiniPlayer pill height),
        // match MiniPlayer's RoundedCornerShape(24.dp) exactly and use the fast RoundRect path.
        if (cornerRadiusPx >= fit - SaturationTolerancePx) {
            return Outline.Rounded(RoundRect(rect, CornerRadius(fit, fit)))
        }
        val smoothingEff = minOf(
            SmoothCornerMath.DefaultSmoothing,
            SmoothCornerMath.inverseSpanFactor(fit / cornerRadiusPx),
        )
        val e = 2f / SmoothCornerMath.exponent(smoothingEff)
        val span = (cornerRadiusPx * SmoothCornerMath.spanFactor(smoothingEff)).coerceAtMost(fit)
        if (span <= 0.5f) return Outline.Rectangle(rect)
        val rx = span.coerceIn(0f, rectW * 0.5f)
        val ry = span.coerceIn(0f, rectH * 0.5f)

        val path = Path()
        fun corner(cx: Float, cy: Float, dirX: Float, dirY: Float, tFrom: Double, tTo: Double) {
            for (i in 0..Steps) {
                val t = tFrom + (tTo - tFrom) * i / Steps
                val cxv = cos(t).toFloat().coerceAtLeast(0f).pow(e)
                val cyv = sin(t).toFloat().coerceAtLeast(0f).pow(e)
                path.lineTo(cx + dirX * rx * cxv, cy + dirY * ry * cyv)
            }
        }

        path.moveTo(clampedLeft + rx, clampedTop)
        path.lineTo(clampedRight - rx, clampedTop)
        corner(clampedRight - rx, clampedTop + ry, dirX = 1f, dirY = -1f, tFrom = Math.PI / 2, tTo = 0.0)
        path.lineTo(clampedRight, clampedBottom - ry)
        corner(clampedRight - rx, clampedBottom - ry, dirX = 1f, dirY = 1f, tFrom = 0.0, tTo = Math.PI / 2)
        path.lineTo(clampedLeft + rx, clampedBottom)
        corner(clampedLeft + rx, clampedBottom - ry, dirX = -1f, dirY = 1f, tFrom = Math.PI / 2, tTo = 0.0)
        path.lineTo(clampedLeft, clampedTop + ry)
        corner(clampedLeft + rx, clampedTop + ry, dirX = -1f, dirY = -1f, tFrom = 0.0, tTo = Math.PI / 2)
        path.close()
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean =
        other is MorphSmoothCornerShape &&
            other.left == left &&
            other.top == top &&
            other.right == right &&
            other.bottom == bottom &&
            other.cornerRadiusPx == cornerRadiusPx

    override fun hashCode(): Int {
        var result = left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        result = 31 * result + cornerRadiusPx.hashCode()
        return result
    }

    private companion object {
        const val Steps = 16
        const val SaturationTolerancePx = 0.5f
    }
}

private object SmoothCornerMath {
    const val DefaultSmoothing = 1f
    private const val MaxExponent = 2.5f
    private val CircleDiagonalCut: Double = sqrt(2.0) * (1.0 - 2.0.pow(-0.5))
    private val MaxSpanFactor: Float = spanFactor(1f)

    fun exponent(smoothing: Float): Float =
        2f + (MaxExponent - 2f) * smoothing.coerceIn(0f, 1f)

    fun spanFactor(smoothing: Float): Float {
        val n = exponent(smoothing)
        val cut = sqrt(2.0) * (1.0 - 2.0.pow(-1.0 / n.toDouble()))
        return (CircleDiagonalCut / cut).toFloat()
    }

    fun inverseSpanFactor(ratio: Float): Float {
        if (!(ratio > 1f)) return 0f
        if (ratio >= MaxSpanFactor) return 1f
        val inner = 1.0 - (1.0 - 2.0.pow(-0.5)) / ratio
        if (inner <= 0.0) return 0f
        val n = -1.0 / log2(inner)
        return ((n - 2.0) / (MaxExponent - 2.0)).toFloat().coerceIn(0f, 1f)
    }
}
