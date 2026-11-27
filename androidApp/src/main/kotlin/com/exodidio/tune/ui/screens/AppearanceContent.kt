package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.R
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.settings.DefaultLiquidGlassBlurDp
import com.exodidio.tune.settings.IconStyle
import com.exodidio.tune.settings.MaxLiquidGlassBlurDp
import com.exodidio.tune.settings.MinLiquidGlassBlurDp
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.settings.ThemeAccentColor
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDivider
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.components.TuneTrackSlider
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlin.math.roundToInt

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AppearanceContent(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    accentColor: ThemeAccentColor = ThemeAccentColor.Red,
    onAccentColorSelected: (ThemeAccentColor) -> Unit = {},
    reduceTransparency: Boolean,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    playerTheme: PlayerTheme = PlayerTheme.Standard,
    onPlayerThemeSelected: (PlayerTheme) -> Unit = {},
    adaptiveTextColor: Boolean = false,
    onAdaptiveTextColorChanged: (Boolean) -> Unit = {},
    onAppleBackdropSelected: () -> Unit = {},
    onDynamicBackdropSelected: () -> Unit = {},
    onPlayerAppearanceSelected: () -> Unit = {},
    onTabLayoutSelected: () -> Unit = {},
    rememberTabPage: Boolean = true,
    onRememberTabPageChanged: (Boolean) -> Unit = {},
    mergeBottomBars: Boolean = true,
    onMergeBottomBarsChanged: (Boolean) -> Unit = {},
    showVolumeSlider: Boolean = false,
    onShowVolumeSliderChanged: (Boolean) -> Unit = {},
    fullscreenArtwork: Boolean = false,
    onFullscreenArtworkChanged: (Boolean) -> Unit = {},
    keepScreenOn: Boolean = false,
    onKeepScreenOnChanged: (Boolean) -> Unit = {},
    lyricsFontLevel: Int = 1,
    onLyricsFontLevelChanged: (Int) -> Unit = {},
    iconStyle: IconStyle = IconStyle.Material,
    onIconStyleSelected: (IconStyle) -> Unit = {},
    controlStyle: ControlStyle = ControlStyle.Frosted,
    onControlStyleSelected: (ControlStyle) -> Unit = {},
    liquidGlassBlurDp: Float = DefaultLiquidGlassBlurDp,
    onLiquidGlassBlurDpChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Selection(
                labelRes = R.string.appearance_theme_title,
                options = ThemeMode.entries.map { mode ->
                    SelectionOption(value = mode, labelRes = mode.labelRes)
                },
                selectedValue = themeMode,
                onValueSelected = onThemeModeSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            AccentColorSwatchRow(
                selected = accentColor,
                onSelect = onAccentColorSelected,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.appearance_icon_style_title,
                options = IconStyle.entries.map { style ->
                    SelectionOption(value = style, labelRes = style.labelRes)
                },
                selectedValue = iconStyle,
                onValueSelected = onIconStyleSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.appearance_control_style_title,
                options = ControlStyle.entries.map { style ->
                    SelectionOption(value = style, labelRes = style.labelRes)
                },
                selectedValue = controlStyle,
                onValueSelected = onControlStyleSelected,
                hazeState = hazeState,
            )
            if (controlStyle == ControlStyle.LiquidGlass) {
                ActionListDivider(style = ActionListDividerStyle.FullWidth)
                LiquidGlassBlurSliderRow(
                    blurDp = liquidGlassBlurDp,
                    onBlurDpChanged = onLiquidGlassBlurDpChanged,
                )
            }
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.appearance_reduce_transparency,
                        trailingContent = {
                            TuneSwitch(
                                checked = reduceTransparency,
                                onCheckedChange = onReduceTransparencyChanged,
                            )
                        },
                        onClick = { onReduceTransparencyChanged(!reduceTransparency) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
        Card {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.appearance_player_settings,
                        leadingSymbol = MaterialSymbols.PlayCircle,
                        onClick = onPlayerAppearanceSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_tab_layout,
                        leadingSymbol = MaterialSymbols.Tune,
                        onClick = onTabLayoutSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_remember_tab_page,
                        trailingContent = {
                            TuneSwitch(
                                checked = rememberTabPage,
                                onCheckedChange = onRememberTabPageChanged,
                            )
                        },
                        onClick = { onRememberTabPageChanged(!rememberTabPage) },
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_merge_bottom_bars,
                        trailingContent = {
                            TuneSwitch(
                                checked = mergeBottomBars,
                                onCheckedChange = onMergeBottomBarsChanged,
                            )
                        },
                        onClick = { onMergeBottomBarsChanged(!mergeBottomBars) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
    }
}

@Composable
private fun AccentColorSwatchRow(
    selected: ThemeAccentColor,
    onSelect: (ThemeAccentColor) -> Unit,
) {
    val colors = LocalTuneColors.current
    val dark = colors.background.luminance() < 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.appearance_accent_color_title),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textMain,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeAccentColor.entries.forEach { option ->
                val swatchColor = Color(if (dark) option.primaryDark else option.primaryLight)
                val isSelected = option == selected
                val optionLabel = stringResource(option.labelRes)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, colors.textMain, CircleShape).padding(3.dp)
                            } else {
                                Modifier
                            },
                        )
                        .clip(CircleShape)
                        .background(swatchColor)
                        .semantics {
                            contentDescription = optionLabel
                            this.selected = isSelected
                        }
                        .clickable(role = Role.RadioButton) { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Check,
                            tint = Color.White,
                            size = 14.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassBlurSliderRow(
    blurDp: Float,
    onBlurDpChanged: (Float) -> Unit,
) {
    val colors = LocalTuneColors.current
    var draftBlur by remember(blurDp) {
        mutableFloatStateOf(blurDp.coerceIn(MinLiquidGlassBlurDp, MaxLiquidGlassBlurDp))
    }
    var lastSentRounded by remember(blurDp) { mutableIntStateOf(draftBlur.roundToInt()) }
    val displayValue = draftBlur.roundToInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.appearance_liquid_glass_blur_title),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textMain,
            )
            Text(
                text = stringResource(R.string.appearance_liquid_glass_blur_value, displayValue),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textMuted,
            )
        }
        TuneTrackSlider(
            value = draftBlur,
            valueRange = MinLiquidGlassBlurDp..MaxLiquidGlassBlurDp,
            onValueChange = { next ->
                val clamped = next.coerceIn(MinLiquidGlassBlurDp, MaxLiquidGlassBlurDp)
                draftBlur = clamped
                val rounded = clamped.roundToInt()
                if (rounded != lastSentRounded) {
                    lastSentRounded = rounded
                    onBlurDpChanged(rounded.toFloat())
                }
            },
            onValueChangeFinished = {
                val rounded = draftBlur.roundToInt()
                lastSentRounded = rounded
                onBlurDpChanged(rounded.toFloat())
            },
            modifier = Modifier.fillMaxWidth(),
            trackHeight = 6.dp,
            activeTrackColor = colors.primary,
            inactiveTrackColor = colors.buttonSecondary,
            trackAlignment = Alignment.TopCenter,
        )
    }
}
