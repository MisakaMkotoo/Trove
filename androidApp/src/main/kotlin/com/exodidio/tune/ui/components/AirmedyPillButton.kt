package com.exodidio.tune.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.exodidio.tune.ui.theme.LocalTuneColors

enum class TunePillButtonVariant {
    Primary,
    Secondary,
    Destructive,
}

/** A full-width capsule action using the app's primary, neutral, or destructive treatment. */
@Composable
fun TunePillButton(
    label: String,
    onClick: () -> Unit,
    variant: TunePillButtonVariant,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(28.dp)
    val baseBackground = when (variant) {
        TunePillButtonVariant.Primary,
        TunePillButtonVariant.Destructive,
        -> colors.primary
        TunePillButtonVariant.Secondary -> colors.buttonSecondary
    }
    val background by animateColorAsState(
        targetValue = if (enabled) baseBackground else baseBackground.copy(alpha = 0.45f),
        animationSpec = if (reduceMotion) snap() else tween(180, easing = FastOutSlowInEasing),
        label = "pill-button-background",
    )
    val contentColor = when (variant) {
        TunePillButtonVariant.Primary,
        TunePillButtonVariant.Destructive,
        -> colors.onPrimary
        TunePillButtonVariant.Secondary -> colors.textMain
    }
    Box(
        modifier = modifier
            .then(if (enabled) Modifier.pressScale(interactionSource, pressedScale = 0.97f) else Modifier)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(background)
            .semantics { contentDescription = label }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}
