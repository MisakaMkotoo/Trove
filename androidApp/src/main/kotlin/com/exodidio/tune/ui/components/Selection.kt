package com.exodidio.tune.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.ui.theme.LocalTuneColors

data class SelectionOption<T>(
    val value: T,
    @StringRes val labelRes: Int? = null,
    val label: String? = null,
    /** 收起态右侧显示用的短文案；为空时回落到 labelRes。下拉列表仍显示完整 labelRes。 */
    @StringRes val shortLabelRes: Int? = null,
    val shortLabel: String? = null,
)

@Composable
fun <T> Selection(
    @StringRes labelRes: Int? = null,
    options: List<SelectionOption<T>>,
    selectedValue: T,
    onValueSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val colors = LocalTuneColors.current
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.value == selectedValue }

    AnchoredPopupMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = modifier,
        offset = DpOffset.Zero,
        width = 252.dp,
        hazeState = hazeState,
        shape = RoundedCornerShape(28.dp),
        anchor = {
            if (labelRes == null) {
                SelectionValue(selectedOption) { expanded = true }
            } else ActionList(
                items = listOf(ActionListItem(labelRes, trailingContent = {
                    SelectionValue(selectedOption) { expanded = true }
                })),
                containerStyle = ActionListContainerStyle.Plain,
            )
        },
        menu = {
            SelectionMenu(
                options = options,
                selectedValue = selectedValue,
                onOptionSelected = {
                    onValueSelected(it)
                    expanded = false
                },
            )
        },
    )
}

@Composable
private fun <T> SelectionValue(
    option: SelectionOption<T>?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(
                selected = false,
                onClick = onClick,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = option?.shortLabel.orEmpty()
                    .ifBlank { option?.shortLabelRes?.let { stringResource(it) }.orEmpty() }
                    .ifBlank { option?.label.orEmpty() }
                    .ifBlank { option?.labelRes?.let { stringResource(it) }.orEmpty() },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textMuted,
            )
            MaterialSymbol(
                symbol = MaterialSymbols.UnfoldMore,
                contentDescription = null,
                size = 18.dp,
                tint = colors.textMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun <T> SelectionMenu(
    options: List<SelectionOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val hoverState = LocalPopupMenuHoverState.current
    val dark = colors.background.luminance() < 0.5f
    val hoverPillColor = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    val pillShape = RoundedCornerShape(14.dp)

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .width(252.dp)
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = option.value == selectedValue
            var rowBounds by remember(index) { mutableStateOf<Rect?>(null) }
            val isHovered = hoverState?.pointerRootPosition != null &&
                rowBounds?.contains(hoverState.pointerRootPosition!!) == true
            val animatedBg by animateColorAsState(
                targetValue = if (isHovered) hoverPillColor else Color.Transparent,
                animationSpec = if (reduceMotion) snap() else tween(durationMillis = 120),
                label = "selection-row-hover-bg",
            )
            val hoverScale by animateFloatAsState(
                targetValue = if (isHovered && !reduceMotion) 1.02f else 1f,
                animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.7f, stiffness = 460f),
                label = "selection-row-hover-scale",
            )
            Row(
                modifier = Modifier
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
                    .heightIn(min = 44.dp)
                    .selectable(
                        selected = selected,
                        onClick = { onOptionSelected(option.value) },
                        role = Role.RadioButton,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    MaterialSymbol(
                        symbol = MaterialSymbols.Check,
                        contentDescription = null,
                        size = 18.dp,
                        tint = colors.textMain,
                    )
                } else {
                    Box(modifier = Modifier.size(18.dp))
                }
                Text(
                    text = option.label.orEmpty().ifBlank { option.labelRes?.let { stringResource(it) }.orEmpty() },
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textMain,
                )
            }
            if (index < options.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 46.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
                        .height(0.5.dp)
                        .background(colors.borderGlass.copy(alpha = 0.55f)),
                )
            }
        }
    }
}
