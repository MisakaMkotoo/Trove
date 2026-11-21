package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.player.AppleBackdropSettings
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.TuneTrackSlider
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlin.math.roundToInt

@Composable
internal fun AppleBackdropSettingsContent(
    settings: AppleBackdropSettings,
    onSettingsChanged: (AppleBackdropSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = remember(settings) { mutableStateOf(settings) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackdropSlider(
            labelRes = R.string.apple_backdrop_twist,
            value = draft.value.twist,
            valueRange = 0f..2f,
            onValueChange = { draft.value = draft.value.copy(twist = it) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        BackdropSlider(
            labelRes = R.string.apple_backdrop_flow,
            value = draft.value.flow,
            valueRange = 0f..0.5f,
            onValueChange = { draft.value = draft.value.copy(flow = it) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        BackdropSlider(
            labelRes = R.string.apple_backdrop_saturation,
            value = draft.value.saturation,
            valueRange = 0f..2f,
            onValueChange = { draft.value = draft.value.copy(saturation = it) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        BackdropSlider(
            labelRes = R.string.apple_backdrop_brightness,
            value = draft.value.brightness,
            valueRange = 0.5f..1.2f,
            onValueChange = { draft.value = draft.value.copy(brightness = it) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        BackdropSlider(
            labelRes = R.string.apple_backdrop_harmony,
            value = draft.value.harmony,
            valueRange = 0f..1f,
            onValueChange = { draft.value = draft.value.copy(harmony = it) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        BackdropSlider(
            labelRes = R.string.apple_backdrop_vignette,
            value = draft.value.vignette,
            valueRange = 0f..0.8f,
            onValueChange = { draft.value = draft.value.copy(vignette = it) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        BackdropSlider(
            labelRes = R.string.apple_backdrop_detail,
            value = draft.value.detail,
            valueRange = 0f..0.7f,
            onValueChange = { draft.value = draft.value.copy(detail = it) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        BackdropSlider(
            labelRes = R.string.apple_backdrop_blur,
            value = draft.value.blurPasses.toFloat(),
            valueRange = 0f..20f,
            integerDisplay = true,
            onValueChange = { draft.value = draft.value.copy(blurPasses = it.roundToInt()) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        BackdropSlider(
            labelRes = R.string.apple_backdrop_speed,
            value = draft.value.speed,
            valueRange = 0.05f..2f,
            decimals = 2,
            onValueChange = { draft.value = draft.value.copy(speed = it) },
            onValueChangeFinished = { onSettingsChanged(draft.value) },
        )
        com.exodidio.tune.ui.components.Card {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.apple_backdrop_reset,
                        onClick = {
                            draft.value = AppleBackdropSettings.Default
                            onSettingsChanged(AppleBackdropSettings.Default)
                        },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
    }
}

@Composable
private fun BackdropSlider(
    labelRes: Int,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    integerDisplay: Boolean = false,
    decimals: Int = 2,
) {
    val colors = LocalTuneColors.current
    val display = if (integerDisplay) value.roundToInt().toString() else "%.${decimals}f".format(value)
    LabeledCard(label = stringResource(labelRes)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = display,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                )
            }
            TuneTrackSlider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier.fillMaxWidth(),
                trackHeight = 6.dp,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.buttonSecondary,
                trackAlignment = Alignment.TopCenter,
            )
        }
    }
}
