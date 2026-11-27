package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.player.MediaNotificationStyle
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDivider
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption
import com.exodidio.tune.ui.components.TuneSwitch
import dev.chrisbanes.haze.HazeState

@Composable
internal fun PlayerAppearanceContent(
    playerTheme: PlayerTheme,
    onPlayerThemeSelected: (PlayerTheme) -> Unit,
    adaptiveTextColor: Boolean,
    onAdaptiveTextColorChanged: (Boolean) -> Unit,
    onAppleBackdropSelected: () -> Unit,
    onDynamicBackdropSelected: () -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    lyricsFontLevel: Int,
    onLyricsFontLevelChanged: (Int) -> Unit,
    mediaNotificationStyle: MediaNotificationStyle = MediaNotificationStyle.Default,
    onMediaNotificationStyleSelected: (MediaNotificationStyle) -> Unit = {},
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Selection(
                labelRes = R.string.appearance_player_theme_title,
                options = listOf(
                    SelectionOption(value = PlayerTheme.Standard, labelRes = R.string.appearance_player_standard),
                    SelectionOption(value = PlayerTheme.Adaptive, labelRes = R.string.appearance_player_adaptive),
                    SelectionOption(value = PlayerTheme.Apple, labelRes = R.string.appearance_player_apple),
                    SelectionOption(value = PlayerTheme.Distorted, labelRes = R.string.appearance_player_distorted),
                    SelectionOption(value = PlayerTheme.Dynamic, labelRes = R.string.appearance_player_dynamic),
                ),
                selectedValue = playerTheme,
                onValueSelected = onPlayerThemeSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.appearance_apple_backdrop_settings,
                        onClick = onAppleBackdropSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.appearance_dynamic_backdrop_settings,
                        onClick = onDynamicBackdropSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
        Card {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.appearance_keep_screen_on,
                        trailingContent = {
                            TuneSwitch(
                                checked = keepScreenOn,
                                onCheckedChange = onKeepScreenOnChanged,
                            )
                        },
                        onClick = { onKeepScreenOnChanged(!keepScreenOn) },
                    ),
                    ActionListItem(
                        labelRes = R.string.appearance_adaptive_text,
                        trailingContent = {
                            TuneSwitch(
                                checked = adaptiveTextColor,
                                onCheckedChange = onAdaptiveTextColorChanged,
                            )
                        },
                        onClick = { onAdaptiveTextColorChanged(!adaptiveTextColor) },
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
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.appearance_media_notification_style_title,
                options = MediaNotificationStyle.entries.map { style ->
                    SelectionOption(value = style, labelRes = style.labelRes)
                },
                selectedValue = mediaNotificationStyle,
                onValueSelected = onMediaNotificationStyleSelected,
                hazeState = hazeState,
            )
        }
    }
}
