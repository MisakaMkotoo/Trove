package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.player.FunFeaturesSettings
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneSwitch

@Composable
internal fun FunFeaturesSettingsContent(
    settings: FunFeaturesSettings,
    onSettingsChanged: (FunFeaturesSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabeledCard(label = stringResource(R.string.fun_features_section_audio)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.fun_cassette_title,
                        leadingSymbol = MaterialSymbols.Refresh,
                        subtitle = stringResource(R.string.fun_cassette_desc),
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.cassetteMode,
                                onCheckedChange = { onSettingsChanged(settings.copy(cassetteMode = it)) },
                            )
                        },
                        onClick = { onSettingsChanged(settings.copy(cassetteMode = !settings.cassetteMode)) },
                    ),
                    ActionListItem(
                        labelRes = R.string.fun_radio_crosstalk_title,
                        leadingSymbol = MaterialSymbols.Sensors,
                        subtitle = stringResource(R.string.fun_radio_crosstalk_desc),
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.radioCrosstalk,
                                onCheckedChange = { onSettingsChanged(settings.copy(radioCrosstalk = it)) },
                            )
                        },
                        onClick = { onSettingsChanged(settings.copy(radioCrosstalk = !settings.radioCrosstalk)) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
            )
        }
        LabeledCard(label = stringResource(R.string.fun_features_section_visual)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.fun_vinyl_wear_title,
                        leadingSymbol = MaterialSymbols.Album,
                        subtitle = stringResource(R.string.fun_vinyl_wear_desc),
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.vinylRingWear,
                                onCheckedChange = { onSettingsChanged(settings.copy(vinylRingWear = it)) },
                            )
                        },
                        onClick = { onSettingsChanged(settings.copy(vinylRingWear = !settings.vinylRingWear)) },
                    ),
                    ActionListItem(
                        labelRes = R.string.fun_gyro_knob_title,
                        leadingSymbol = MaterialSymbols.Tune,
                        subtitle = stringResource(R.string.fun_gyro_knob_desc),
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.gyroSheenAndKnobDetent,
                                onCheckedChange = { onSettingsChanged(settings.copy(gyroSheenAndKnobDetent = it)) },
                            )
                        },
                        onClick = { onSettingsChanged(settings.copy(gyroSheenAndKnobDetent = !settings.gyroSheenAndKnobDetent)) },
                    ),
                    ActionListItem(
                        labelRes = R.string.fun_lyrics_gravity_title,
                        leadingSymbol = MaterialSymbols.Lyrics,
                        subtitle = stringResource(R.string.fun_lyrics_gravity_desc),
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.lyricsGravity,
                                onCheckedChange = { onSettingsChanged(settings.copy(lyricsGravity = it)) },
                            )
                        },
                        onClick = { onSettingsChanged(settings.copy(lyricsGravity = !settings.lyricsGravity)) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
            )
        }
    }
}
