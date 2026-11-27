package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.MaterialSymbols

@Composable
internal fun SettingsContent(
    onAppearanceSelected: () -> Unit,
    onPlaybackSelected: () -> Unit,
    onIntegrationSelected: () -> Unit,
    onMusicSyncSelected: () -> Unit,
    onStorageSelected: () -> Unit,
    onAboutSelected: () -> Unit,
    modifier: Modifier = Modifier,
    onLyricsSelected: () -> Unit = {},
    onDownloadSelected: () -> Unit = {},
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabeledCard(label = stringResource(R.string.settings_section_general)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.settings_appearance,
                        leadingSymbol = MaterialSymbols.Palette,
                        onClick = onAppearanceSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_playback,
                        leadingSymbol = MaterialSymbols.Subwoofer,
                        onClick = onPlaybackSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_download,
                        leadingSymbol = MaterialSymbols.ArrowDownward,
                        onClick = onDownloadSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_service_title,
                        leadingSymbol = MaterialSymbols.Lyrics,
                        onClick = onLyricsSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
            )
        }
        LabeledCard(label = stringResource(R.string.settings_section_library)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.settings_music_sync,
                        leadingSymbol = MaterialSymbols.Sync,
                        onClick = onMusicSyncSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_integration,
                        leadingSymbol = MaterialSymbols.Sensors,
                        onClick = onIntegrationSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
            )
        }
        LabeledCard(label = stringResource(R.string.settings_section_system)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.settings_storage,
                        leadingIconRes = R.drawable.ic_settings_storage,
                        onClick = onStorageSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_about,
                        leadingSymbol = MaterialSymbols.Info,
                        onClick = onAboutSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
            )
        }
    }
}
