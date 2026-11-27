package com.exodidio.tune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.theme.LocalTuneColors

enum class TuneIconButtonVariant { Ghost, Glass }

/** A theme-safe icon-only action with either an unfilled or liquid-glass surface. */
@Composable
fun TuneIconButton(
    symbol: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: TuneIconButtonVariant = TuneIconButtonVariant.Ghost,
    tint: Color? = null,
    glassColor: Color? = null,
    hazeState: HazeState? = null,
    showGlassBorder: Boolean = true,
    size: Dp = 48.dp,
    circleSize: Dp = size,
    iconSize: Dp = 22.dp,
    filled: Boolean = false,
    suppressPressedIndication: Boolean = false,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val isLiquidGlass = variant == TuneIconButtonVariant.Glass &&
        LocalControlStyle.current == ControlStyle.LiquidGlass &&
        !reduceMotion
    val useOpticalGlass = isLiquidGlass && glassColor == null && tuneGlassBackdrop() != null
    val glassBlurDp = LocalLiquidGlassBlurDp.current.dp
    val physics = if (variant == TuneIconButtonVariant.Glass) {
        rememberTuneLiquidButtonPhysics(
            maxDisplacementDp = 5.dp,
            pressedScale = if (isLiquidGlass) 1.15f else 1.06f,
        )
    } else {
        null
    }

    @Composable fun Content() {
        Box(
            modifier = Modifier
                .size(circleSize)
                .then(
                    if (variant == TuneIconButtonVariant.Glass) {
                        Modifier
                            .then(
                                if (glassColor != null) {
                                    Modifier
                                        .then(physics?.let { Modifier.graphicsLayer(it.layerBlock) } ?: Modifier)
                                        .clip(CircleShape)
                                        .background(glassColor)
                                } else {
                                    Modifier.tuneLiquidGlassBackground(
                                        hazeState = hazeState,
                                        colors = colors,
                                        shape = CircleShape,
                                        glassBlurRadius = glassBlurDp,
                                        enableLens = true,
                                        lensRadius = 16.dp,
                                        refractionHeight = 10.dp,
                                        chromaticAberration = isLiquidGlass,
                                        pressProgress = physics?.pressProgress ?: 0f,
                                        layerBlock = physics?.layerBlock,
                                    )
                                },
                            )
                            .then(
                                if (showGlassBorder && !useOpticalGlass) {
                                    Modifier.border(0.5.dp, colors.borderGlass, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(
                symbol = symbol,
                contentDescription = if (variant == TuneIconButtonVariant.Glass) null else label,
                tint = tint ?: if (enabled) colors.textMain else colors.textMuted,
                size = iconSize,
                filled = filled,
            )
        }
    }
    if (variant == TuneIconButtonVariant.Glass && physics != null) {
        Box(
            modifier = modifier
                .size(size.coerceAtLeast(48.dp))
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                    onClick(label = label) {
                        if (enabled) {
                            onClick()
                            true
                        } else {
                            false
                        }
                    }
                }
                .liquidButtonGesture(
                    physics = physics,
                    enabled = enabled,
                    reduceMotion = reduceMotion,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) { Content() }
    } else if (suppressPressedIndication) {
        Box(
            modifier = modifier
                .size(size.coerceAtLeast(48.dp))
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) { Content() }
    } else {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(size.coerceAtLeast(48.dp))) { Content() }
    }
}
