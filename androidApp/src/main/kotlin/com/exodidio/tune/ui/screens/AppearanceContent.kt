package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.R
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDivider
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption

@Composable
internal fun AppearanceContent(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    reduceTransparency: Boolean,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    playerTheme: PlayerTheme = PlayerTheme.Standard,
    onPlayerThemeSelected: (PlayerTheme) -> Unit = {},
    adaptiveTextColor: Boolean = false,
    onAdaptiveTextColorChanged: (Boolean) -> Unit = {},
    onAppleBackdropSelected: () -> Unit = {},
    onTabLayoutSelected: () -> Unit = {},
    rememberTabPage: Boolean = true,
    onRememberTabPageChanged: (Boolean) -> Unit = {},
    mergeBottomBars: Boolean = true,
    onMergeBottomBarsChanged: (Boolean) -> Unit = {},
    showVolumeSlider: Boolean = false,
    onShowVolumeSliderChanged: (Boolean) -> Unit = {},
    fullscreenArtwork: Boolean = false,
    onFullscreenArtworkChanged: (Boolean) -> Unit = {},
    fullscreenLyrics: Boolean = false,
    onFullscreenLyricsChanged: (Boolean) -> Unit = {},
    keepScreenOn: Boolean = false,
    onKeepScreenOnChanged: (Boolean) -> Unit = {},
    lyricsFontLevel: Int = 1,
    onLyricsFontLevelChanged: (Int) -> Unit = {},
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
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.appearance_reduce_transparency,
                        trailingContent = {
                            Switch(
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
            Selection(
                labelRes = R.string.appearance_player_theme_title,
                options = listOf(
                    SelectionOption(value = PlayerTheme.Standard, labelRes = R.string.appearance_player_standard),
                    SelectionOption(value = PlayerTheme.Adaptive, labelRes = R.string.appearance_player_adaptive),
                    SelectionOption(value = PlayerTheme.Apple, labelRes = R.string.appearance_player_apple),
                    SelectionOption(value = PlayerTheme.Distorted, labelRes = R.string.appearance_player_distorted),
                ),
                selectedValue = playerTheme,
                onValueSelected = onPlayerThemeSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.appearance_show_volume,
                        trailingContent = {
                            Switch(
                                checked = showVolumeSlider,
                                onCheckedChange = onShowVolumeSliderChanged,
                            )
                        },
                        onClick = { onShowVolumeSliderChanged(!showVolumeSlider) },
                    ),
                    ActionListItem(
                        labelRes = R.string.appearance_fullscreen_artwork,
                        trailingContent = {
                            Switch(
                                checked = fullscreenArtwork,
                                onCheckedChange = onFullscreenArtworkChanged,
                            )
                        },
                        onClick = { onFullscreenArtworkChanged(!fullscreenArtwork) },
                    ),
                    ActionListItem(
                        labelRes = R.string.appearance_fullscreen_lyrics,
                        trailingContent = {
                            Switch(
                                checked = fullscreenLyrics,
                                onCheckedChange = onFullscreenLyricsChanged,
                            )
                        },
                        onClick = { onFullscreenLyricsChanged(!fullscreenLyrics) },
                    ),
                    ActionListItem(
                        labelRes = R.string.appearance_keep_screen_on,
                        trailingContent = {
                            Switch(
                                checked = keepScreenOn,
                                onCheckedChange = onKeepScreenOnChanged,
                            )
                        },
                        onClick = { onKeepScreenOnChanged(!keepScreenOn) },
                    ),
                    ActionListItem(
                        labelRes = R.string.appearance_adaptive_text,
                        trailingContent = {
                            Switch(
                                checked = adaptiveTextColor,
                                onCheckedChange = onAdaptiveTextColorChanged,
                            )
                        },
                        onClick = { onAdaptiveTextColorChanged(!adaptiveTextColor) },
                    ),
                    ActionListItem(
                        labelRes = R.string.appearance_apple_backdrop_settings,
                        onClick = onAppleBackdropSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_tab_layout,
                        leadingSymbol = MaterialSymbols.Tune,
                        onClick = onTabLayoutSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_remember_tab_page,
                        trailingContent = {
                            Switch(
                                checked = rememberTabPage,
                                onCheckedChange = onRememberTabPageChanged,
                            )
                        },
                        onClick = { onRememberTabPageChanged(!rememberTabPage) },
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_merge_bottom_bars,
                        trailingContent = {
                            Switch(
                                checked = mergeBottomBars,
                                onCheckedChange = onMergeBottomBarsChanged,
                            )
                        },
                        onClick = { onMergeBottomBarsChanged(!mergeBottomBars) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.appearance_lyrics_font_title,
                options = listOf(
                    SelectionOption(0, R.string.appearance_lyrics_font_small),
                    SelectionOption(1, R.string.appearance_lyrics_font_standard),
                    SelectionOption(2, R.string.appearance_lyrics_font_large),
                ),
                selectedValue = lyricsFontLevel,
                onValueSelected = onLyricsFontLevelChanged,
                hazeState = hazeState,
            )
        }
    }
}
