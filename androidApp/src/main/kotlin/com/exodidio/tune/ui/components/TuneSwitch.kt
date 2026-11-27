package com.exodidio.tune.ui.components

/*
 * Liquid glass toggle implementation adapted from LiquidToggle in Kyant0/AndroidLiquidGlass (Apache-2.0):
 * https://github.com/Kyant0/AndroidLiquidGlass
 */

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs

private val FrostedSwitchWidth = 52.dp
private val FrostedSwitchHeight = 30.dp
private val FrostedThumbSize = 24.dp
private val FrostedThumbPadding = 3.dp

private val LiquidTrackWidth = 64.dp
private val LiquidTrackHeight = 28.dp
private val LiquidThumbWidth = 40.dp
private val LiquidThumbHeight = 24.dp
private val LiquidThumbPadding = 2.dp
private val LiquidDragWidth = 20.dp

internal fun resolveLiquidToggleReleaseChecked(
    didDrag: Boolean,
    targetFraction: Float,
    currentChecked: Boolean,
): Boolean = if (didDrag) {
    targetFraction >= 0.5f
} else {
    !currentChecked
}

internal fun applyLiquidToggleDragFraction(
    currentFraction: Float,
    dragDeltaPx: Float,
    dragWidthPx: Float,
    isLtr: Boolean,
): Float {
    if (dragWidthPx <= 0f) return currentFraction.coerceIn(0f, 1f)
    val delta = dragDeltaPx / dragWidthPx
    return (if (isLtr) currentFraction + delta else currentFraction - delta).coerceIn(0f, 1f)
}

@Composable
fun TuneSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val reduceMotion = LocalReduceMotion.current
    val canUseKyantToggle = LocalControlStyle.current == ControlStyle.LiquidGlass &&
        !reduceMotion &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        LocalView.current.isHardwareAccelerated

    if (canUseKyantToggle) {
        KyantLiquidSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        FrostedTuneSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            reduceMotion = reduceMotion,
            interactionSource = interactionSource,
        )
    }
}

@Composable
private fun KyantLiquidSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
) {
    val colors = LocalTuneColors.current
    val isLightTheme = colors.background.luminance() >= 0.5f
    val accentColor = colors.primary
    val trackColor = if (isLightTheme) {
        Color(0xFF787878).copy(alpha = 0.2f)
    } else {
        Color(0xFF787880).copy(alpha = 0.36f)
    }

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidthPx = with(density) { LiquidDragWidth.toPx() }
    val thumbPaddingPx = with(density) { LiquidThumbPadding.toPx() }
    val dragSlopPx = with(density) { 4.dp.toPx() }

    val animationScope = rememberCoroutineScope()
    val currentChecked by rememberUpdatedState(checked)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)

    var didDrag by remember { mutableStateOf(false) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }

    val dampedDragAnimation = remember(animationScope, dragWidthPx, isLtr) {
        TuneDampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {
                didDrag = false
                totalDragX = 0f
            },
            onDragStopped = {
                val nextChecked = resolveLiquidToggleReleaseChecked(
                    didDrag = didDrag,
                    targetFraction = targetValue,
                    currentChecked = currentChecked,
                )
                didDrag = false
                totalDragX = 0f
                val targetFraction = if (nextChecked) 1f else 0f
                fraction = targetFraction
                updateValue(targetFraction)
                if (nextChecked != currentChecked) {
                    currentOnCheckedChange?.invoke(nextChecked)
                }
            },
            onDragCanceled = {
                didDrag = false
                totalDragX = 0f
                val restoredFraction = if (currentChecked) 1f else 0f
                fraction = restoredFraction
                updateValue(restoredFraction)
            },
            onDrag = { _, dragAmount ->
                totalDragX += dragAmount.x
                if (!didDrag && abs(totalDragX) > dragSlopPx) {
                    didDrag = true
                }
                if (didDrag && dragAmount.x != 0f) {
                    fraction = applyLiquidToggleDragFraction(
                        currentFraction = fraction,
                        dragDeltaPx = dragAmount.x,
                        dragWidthPx = dragWidthPx,
                        isLtr = isLtr,
                    )
                    updateValue(fraction)
                }
            },
            shouldConsumeDrag = { totalDelta ->
                abs(totalDelta.x) > dragSlopPx && abs(totalDelta.x) >= abs(totalDelta.y)
            },
        )
    }

    LaunchedEffect(currentChecked) {
        val target = if (currentChecked) 1f else 0f
        if (target != fraction) {
            fraction = target
            dampedDragAnimation.animateToValue(target)
        }
    }

    val surfaceBackground = colors.background
    val surfaceCard = colors.card
    val outerBackdrop = tuneGlassBackdrop() ?: rememberCanvasBackdrop {
        drawRect(surfaceBackground)
        drawRect(surfaceCard)
    }
    val trackBackdrop = rememberLayerBackdrop()

    val semanticsModifier = Modifier.semantics {
        role = Role.Switch
        toggleableState = ToggleableState(currentChecked)
        if (!enabled) {
            disabled()
        }
        val callback = currentOnCheckedChange
        if (enabled && callback != null) {
            onClick {
                callback(!currentChecked)
                true
            }
        }
    }

    Box(
        modifier = modifier
            .size(width = LiquidTrackWidth, height = LiquidTrackHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .then(semanticsModifier)
            .then(if (enabled && onCheckedChange != null) dampedDragAnimation.modifier else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    val value = dampedDragAnimation.value.coerceIn(0f, 1f)
                    drawRect(lerp(trackColor, accentColor, value))
                }
                .size(width = LiquidTrackWidth, height = LiquidTrackHeight),
        )

        Box(
            modifier = Modifier
                .graphicsLayer {
                    val value = dampedDragAnimation.value
                    translationX = if (isLtr) {
                        lerp(thumbPaddingPx, thumbPaddingPx + dragWidthPx, value)
                    } else {
                        lerp(-thumbPaddingPx, -(thumbPaddingPx + dragWidthPx), value)
                    }
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        outerBackdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = safeGlassProgress(dampedDragAnimation.pressProgress)
                            val scaleX = lerp(2f / 3f, 0.75f, progress)
                            val scaleY = lerp(0f, 0.75f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        },
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = safeGlassProgress(dampedDragAnimation.pressProgress)
                        val blurRadius = 8f.dp.toPx() * (1f - progress)
                        if (blurRadius > 0.5f) {
                            blur(blurRadius)
                        }
                        if (progress > 0.001f) {
                            lens(
                                refractionHeight = (5f.dp.toPx() * progress).coerceAtLeast(0.5f),
                                refractionAmount = (10f.dp.toPx() * progress).coerceAtLeast(0.5f),
                                chromaticAberration = true,
                            )
                        }
                    },
                    highlight = {
                        val progress = safeGlassProgress(dampedDragAnimation.pressProgress)
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress,
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f),
                        )
                    },
                    innerShadow = {
                        val progress = safeGlassProgress(dampedDragAnimation.pressProgress)
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress,
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = safeGlassProgress(dampedDragAnimation.pressProgress)
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    },
                )
                .size(width = LiquidThumbWidth, height = LiquidThumbHeight),
        )
    }
}

@Composable
private fun FrostedTuneSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
    reduceMotion: Boolean,
    interactionSource: MutableInteractionSource,
) {
    val colors = LocalTuneColors.current
    val pressed by interactionSource.collectIsPressedAsState()

    val trackShape = RoundedCornerShape(FrostedSwitchHeight / 2)
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.primary else colors.buttonSecondary,
        animationSpec = if (reduceMotion) snap() else tween(200, easing = FastOutSlowInEasing),
        label = "tune-switch-track",
    )
    val thumbWidth by animateDpAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 28.dp else FrostedThumbSize,
        animationSpec = if (reduceMotion) snap() else tween(140, easing = FastOutSlowInEasing),
        label = "tune-switch-thumb-width",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) FrostedSwitchWidth - FrostedThumbSize - (FrostedThumbPadding * 2) else 0.dp,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
        },
        label = "tune-switch-thumb-offset",
    )
    val resolvedThumbOffset = if (checked && thumbWidth > FrostedThumbSize) {
        (thumbOffset - (thumbWidth - FrostedThumbSize)).coerceAtLeast(0.dp)
    } else {
        thumbOffset
    }

    val toggleModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = null,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(width = FrostedSwitchWidth, height = FrostedSwitchHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(trackShape)
            .background(trackColor)
            .border(
                width = 0.5.dp,
                color = if (checked) colors.primary.copy(alpha = 0.28f) else colors.borderGlass,
                shape = trackShape,
            )
            .then(toggleModifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = FrostedThumbPadding)
                .offset(x = resolvedThumbOffset)
                .size(width = thumbWidth, height = FrostedThumbSize)
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.18f),
                    spotColor = Color.Black.copy(alpha = 0.22f),
                )
                .clip(CircleShape)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White, Color(0xFFF5F5F7)),
                    ),
                )
                .border(
                    width = 0.5.dp,
                    color = Color.White.copy(alpha = 0.4f),
                    shape = CircleShape,
                ),
        )
    }
}
