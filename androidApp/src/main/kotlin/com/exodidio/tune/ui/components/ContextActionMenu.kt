package com.exodidio.tune.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.theme.LocalTuneColors

/** Shared content rows for anchored overflow menus. Hosts decide which actions are available. */
internal sealed interface ContextActionMenuEntry {
    data object Divider : ContextActionMenuEntry
    data class Action(
        val label: String,
        val symbol: String,
        val enabled: Boolean = true,
        val destructive: Boolean = false,
        val filled: Boolean = false,
        val onClick: () -> Unit,
    ) : ContextActionMenuEntry
}

@Composable
internal fun ContextActionMenu(entries: List<ContextActionMenuEntry>, modifier: Modifier = Modifier) {
    val colors = LocalTuneColors.current
    val isLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass && !LocalReduceMotion.current
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        entries.forEachIndexed { index, entry ->
            when (entry) {
                ContextActionMenuEntry.Divider -> {
                    if (isLiquidGlass) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .height(0.5.dp)
                                .background(colors.borderGlass.copy(alpha = 0.55f)),
                        )
                    } else {
                        ActionListDivider(ActionListDividerStyle.FullWidth)
                    }
                }
                is ContextActionMenuEntry.Action -> ContextActionMenuAction(entry, actionKey = index)
            }
        }
    }
}

@Composable
private fun ContextActionMenuAction(
    entry: ContextActionMenuEntry.Action,
    actionKey: Any,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val hoverState = LocalPopupMenuHoverState.current
    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    val currentOnClick by rememberUpdatedState(entry.onClick)
    val isHovered = entry.enabled &&
        hoverState?.pointerRootPosition != null &&
        rowBounds?.contains(hoverState.pointerRootPosition!!) == true

    DisposableEffect(hoverState, actionKey, rowBounds, entry.enabled) {
        val bounds = rowBounds
        if (hoverState != null && bounds != null && entry.enabled) {
            hoverState.registerAction(actionKey, bounds) { currentOnClick() }
        }
        onDispose { hoverState?.unregisterAction(actionKey) }
    }

    val dark = colors.background.luminance() < 0.5f
    val hoverPillColor = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    val animatedBg by animateColorAsState(
        targetValue = if (isHovered) hoverPillColor else Color.Transparent,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 120),
        label = "menu-row-hover-bg",
    )
    val hoverScale by animateFloatAsState(
        targetValue = if (isHovered && !reduceMotion) 1.02f else 1f,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.7f, stiffness = 460f),
        label = "menu-row-hover-scale",
    )

    val contentColor = if (entry.destructive) colors.primary else colors.textMain
    val pillShape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .onGloballyPositioned { coords ->
                if (coords.isAttached) rowBounds = coords.boundsInRoot()
            }
            .graphicsLayer {
                scaleX = hoverScale
                scaleY = hoverScale
            }
            .clip(pillShape)
            .background(animatedBg, pillShape)
            .heightIn(min = 46.dp)
            .semantics { contentDescription = entry.label }
            .clickable(
                enabled = entry.enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = entry.onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialSymbol(
            entry.symbol,
            null,
            size = 21.dp,
            tint = contentColor.copy(alpha = if (entry.enabled) 1f else 0.45f),
            filled = entry.filled,
        )
        Text(
            entry.label,
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor.copy(alpha = if (entry.enabled) 1f else 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
