package com.exodidio.tune.ui.components

/*
 * Adapted in part from Kyant0/AndroidLiquidGlass (Apache-2.0):
 * https://github.com/Kyant0/AndroidLiquidGlass
 */

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.theme.TuneColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect

/** 玻璃/控件描边宽度：从 1dp 降到约 1 物理像素级别。 */
internal val GlassBorderWidth = 0.5.dp

/**
 * Apple 标准材质：一层亮度介于黑白之间的半透明灰度填充 + 模糊。
 * 浅色外观用浅灰（白底略暗、深底提亮），深色外观用深灰（黑底提亮、白底压暗）。
 * 填充不能太实，否则模糊会被盖住、看起来像纯色块。
 */
private val LightGlassMaterial = Color(0xFFF2F2F3).copy(alpha = 0.45f)
private val DarkGlassMaterial = Color(0xFF2C2C2E).copy(alpha = 0.45f)
private val LightLiquidGlassTint = Color(0xFFF3F4F6).copy(alpha = 0.28f)
private val DarkLiquidGlassTint = Color.Black.copy(alpha = 0.24f)

internal fun liquidGlassDropShadow(
    isDark: Boolean,
    isCircular: Boolean,
    pressProgress: Float,
): Shadow {
    val p = safeGlassProgress(pressProgress)
    val radius = if (isCircular) 12.dp + 4.dp * p else 18.dp + 4.dp * p
    val offsetY = if (isCircular) 4.dp + 2.dp * p else 6.dp + 2.dp * p
    val opacity = when {
        isDark && isCircular -> 0.26f + 0.14f * p
        isDark -> 0.28f + 0.14f * p
        isCircular -> 0.10f + 0.08f * p
        else -> 0.11f + 0.09f * p
    }
    return Shadow(
        radius = radius,
        offset = DpOffset(0.dp, offsetY),
        color = Color.Black.copy(alpha = opacity.coerceIn(0f, 1f)),
        alpha = 1f,
    )
}

internal fun liquidGlassInnerShadow(
    isDark: Boolean,
    pressProgress: Float,
): InnerShadow {
    val p = safeGlassProgress(pressProgress)
    val opacity = if (isDark) 0.22f + 0.12f * p else 0.075f + 0.085f * p
    return InnerShadow(
        radius = 5.dp + 5.dp * p,
        offset = DpOffset(0.dp, 2.dp + 2.dp * p),
        color = Color.Black.copy(alpha = opacity.coerceIn(0f, 1f)),
        alpha = 1f,
    )
}

internal fun liquidGlassHighlight(
    isDark: Boolean,
    pressProgress: Float,
): Highlight {
    val p = safeGlassProgress(pressProgress)
    return if (isDark) {
        Highlight.Default.copy(
            alpha = (0.38f + 0.30f * p).coerceAtMost(1f),
        )
    } else {
        Highlight(
            width = 0.75.dp,
            alpha = (0.24f + 0.18f * p).coerceAtMost(1f),
            style = HighlightStyle.Ambient,
        )
    }
}

@Composable
fun rememberAnimatedGlassTint(
    colors: TuneColors,
    glassTint: Color? = null,
    animate: Boolean = true,
    durationMillis: Int = 380,
): Color {
    val target = glassTint ?: if (colors.background.luminance() < 0.5f) DarkGlassMaterial else LightGlassMaterial
    if (!animate) return target
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        label = "liquid-glass-tint",
    )
    return animated
}

/** The shared backdrop treatment for the persistent navigation and page header (Frosted / Haze mode). */
fun Modifier.liquidGlassBackground(
    hazeState: HazeState?,
    colors: TuneColors,
    hazeBlurRadius: Dp = 24.dp,
    glassTint: Color? = null,
): Modifier {
    val material = if (colors.background.luminance() < 0.5f) DarkGlassMaterial else LightGlassMaterial
    val fill = glassTint ?: material
    return if (hazeState == null) {
        background(fill)
    } else {
        hazeEffect(hazeState) {
            // Downsampling makes small glass surfaces look pixelated after upscaling.
            inputScale = HazeInputScale.None
            blurEffect {
                blurRadius = hazeBlurRadius
                colorEffects = listOf(HazeColorEffect.tint(fill))
            }
        }.background(fill)
    }
}

/**
 * Shared backdrop entry point supporting both [ControlStyle.Frosted] (Haze)
 * and true optical [ControlStyle.LiquidGlass] (Kyant0/AndroidLiquidGlass).
 */
@Composable
fun Modifier.tuneLiquidGlassBackground(
    hazeState: HazeState?,
    colors: TuneColors,
    shape: Shape = RectangleShape,
    hazeBlurRadius: Dp = 24.dp,
    glassBlurRadius: Dp = 6.dp,
    glassTint: Color? = null,
    tintAlphaScale: Float = 1f,
    enableLens: Boolean = true,
    lensRadius: Dp = 24.dp,
    refractionHeight: Dp = 12.dp,
    chromaticAberration: Boolean = false,
    pressProgress: Float = 0f,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    backdropOverride: Backdrop? = null,
    enableEdgeShadow: Boolean = enableLens && shape != RectangleShape,
): Modifier {
    if (LocalControlStyle.current == ControlStyle.LiquidGlass) {
        val backdrop = if (backdropOverride != null && tuneGlassAvailable()) {
            backdropOverride
        } else {
            tuneGlassBackdrop()
        }
        if (backdrop != null) {
            val dark = colors.background.luminance() < 0.5f
            val p = safeGlassProgress(pressProgress)
            val isFloatingGlass = enableLens && shape != RectangleShape
            val isCircular = shape == CircleShape
            val baseSurfaceColor = glassTint ?: if (dark) DarkLiquidGlassTint else LightLiquidGlassTint
            val surfaceColor = if (tintAlphaScale == 1f) {
                baseSurfaceColor
            } else {
                baseSurfaceColor.copy(alpha = (baseSurfaceColor.alpha * tintAlphaScale).coerceIn(0f, 1f))
            }
            val applyLens = enableLens &&
                shape.supportsKyantLens() &&
                lensRadius > 0.5.dp &&
                refractionHeight > 0.5.dp
            return then(
                if (enableEdgeShadow) {
                    Modifier.tuneGlassBoxShadow(
                        shape = { shape },
                        alpha = resolveGlassEdgeShadowAlpha(dark),
                        layerBlock = layerBlock,
                    )
                } else {
                    Modifier
                },
            ).drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (glassBlurRadius > 0.25.dp) {
                        val blurPx = glassBlurRadius.toPx()
                        if (blurPx > 0.5f) {
                            blur(blurPx)
                        }
                    }
                    if (applyLens) {
                        val safeHeight = refractionHeight.toPx().coerceAtLeast(1f)
                        val safeRadius = lensRadius.toPx().coerceAtLeast(1f)
                        lens(
                            refractionHeight = safeHeight,
                            refractionAmount = safeRadius,
                            depthEffect = false,
                            chromaticAberration = chromaticAberration,
                        )
                    }
                },
                highlight = if (isFloatingGlass) {
                    { liquidGlassHighlight(isDark = dark, pressProgress = p) }
                } else {
                    null
                },
                shadow = if (isFloatingGlass) {
                    { liquidGlassDropShadow(isDark = dark, isCircular = isCircular, pressProgress = p) }
                } else {
                    null
                },
                innerShadow = if (isFloatingGlass) {
                    { liquidGlassInnerShadow(isDark = dark, pressProgress = p) }
                } else {
                    null
                },
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawSurface = {
                    drawRect(
                        Color.White.copy(alpha = if (dark) 0.045f else 0.08f),
                        blendMode = BlendMode.Screen,
                    )
                    if (p > 0.001f) {
                        drawRect(Color.White.copy(alpha = 0.08f * p), blendMode = BlendMode.Plus)
                    }
                    drawRect(surfaceColor)
                },
            )
        }
    }
    return then(if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier)
        .then(if (shape != RectangleShape) Modifier.clip(shape) else Modifier)
        .liquidGlassBackground(
            hazeState = hazeState,
            colors = colors,
            hazeBlurRadius = hazeBlurRadius,
            glassTint = glassTint,
        )
}

/**
 * Optical liquid selection capsule for the floating navigation bar indicator in
 * [ControlStyle.LiquidGlass] mode.
 */
@Composable
fun Modifier.tuneLiquidSelectionCapsule(
    shape: Shape,
    surfaceColor: Color,
    panelBackdrop: Backdrop?,
    pressProgress: Float,
): Modifier {
    val backdrop = tuneGlassBackdrop() ?: return background(surfaceColor, shape)
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, panelBackdrop ?: backdrop)
    val p = safeGlassProgress(pressProgress)
    return drawBackdrop(
        backdrop = combinedBackdrop,
        shape = { shape },
        effects = {
            if (p > 0.001f && shape.supportsKyantLens()) {
                val safeScale = p.coerceIn(0.01f, 1.2f)
                lens(
                    refractionHeight = (10.dp.toPx() * safeScale).coerceAtLeast(1f),
                    refractionAmount = (14.dp.toPx() * safeScale).coerceAtLeast(1f),
                    chromaticAberration = true,
                )
            }
        },
        highlight = { Highlight.Default.copy(alpha = (0.28f + 0.72f * p).coerceAtMost(1f)) },
        shadow = {
            Shadow(
                radius = 12.dp,
                color = Color.Black.copy(alpha = (0.12f + 0.14f * p).coerceIn(0f, 1f)),
                alpha = 1f,
            )
        },
        innerShadow = {
            InnerShadow(
                radius = 2.dp + 6.dp * p,
                color = Color.Black.copy(alpha = (0.08f + 0.12f * p).coerceIn(0f, 1f)),
                alpha = 1f,
            )
        },
        onDrawSurface = {
            drawRect(surfaceColor, alpha = (1f - 0.55f * p).coerceIn(0.25f, 1f))
            if (p > 0.001f) {
                drawRect(Color.Black.copy(alpha = 0.03f * p))
            }
        },
    )
}
