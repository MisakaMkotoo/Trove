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
import com.exodidio.tune.player.DynamicBackdropSettings
import com.exodidio.tune.player.DynamicBackdropStyle
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.components.TuneTrackSlider
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlin.math.roundToInt

@Composable
internal fun DynamicBackdropSettingsContent(
    settings: DynamicBackdropSettings,
    onSettingsChanged: (DynamicBackdropSettings) -> Unit,
    modifier: Modifier = Modifier,
    onMeshPresetsSelected: () -> Unit = {},
) {
    // draft 只负责滑杆显示；提交时只把本次改动的字段合并回最新 settings，
    // 避免草稿被旧的读取结果重置后把其他字段（样式/抖动等）覆盖回去。
    val draft = remember(settings) { mutableStateOf(settings) }
    fun commit(block: (DynamicBackdropSettings) -> DynamicBackdropSettings) {
        onSettingsChanged(block(settings))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Selection(
                labelRes = R.string.dynamic_backdrop_style_title,
                options = listOf(
                    SelectionOption(DynamicBackdropStyle.Mesh, R.string.dynamic_backdrop_style_mesh),
                    SelectionOption(DynamicBackdropStyle.Titanium, R.string.dynamic_backdrop_style_titanium),
                ),
                selectedValue = draft.value.style,
                onValueSelected = { style ->
                    draft.value = draft.value.copy(style = style)
                    commit { it.copy(style = style) }
                },
            )
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.dynamic_backdrop_static,
                        trailingContent = {
                            TuneSwitch(
                                checked = draft.value.staticMode,
                                onCheckedChange = { enabled ->
                                    draft.value = draft.value.copy(staticMode = enabled)
                                    commit { it.copy(staticMode = enabled) }
                                },
                            )
                        },
                        onClick = {
                            val enabled = !draft.value.staticMode
                            draft.value = draft.value.copy(staticMode = enabled)
                            commit { it.copy(staticMode = enabled) }
                        },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
        BackdropSlider(
            labelRes = R.string.dynamic_backdrop_flow,
            value = draft.value.flowSpeed,
            valueRange = 0f..3f,
            onValueChange = { draft.value = draft.value.copy(flowSpeed = it) },
            onValueChangeFinished = { commit { it.copy(flowSpeed = draft.value.flowSpeed) } },
        )
        BackdropSlider(
            labelRes = R.string.dynamic_backdrop_volume,
            value = draft.value.volume,
            valueRange = 0f..1f,
            onValueChange = { draft.value = draft.value.copy(volume = it) },
            onValueChangeFinished = { commit { it.copy(volume = draft.value.volume) } },
        )
        BackdropSlider(
            labelRes = R.string.dynamic_backdrop_fps,
            value = draft.value.fps.toFloat(),
            valueRange = 15f..120f,
            integerDisplay = true,
            onValueChange = { draft.value = draft.value.copy(fps = it.roundToInt()) },
            onValueChangeFinished = { commit { it.copy(fps = draft.value.fps) } },
        )
        BackdropSlider(
            labelRes = R.string.dynamic_backdrop_scale,
            value = draft.value.renderScale,
            valueRange = 0.25f..1f,
            onValueChange = { draft.value = draft.value.copy(renderScale = it) },
            onValueChangeFinished = { commit { it.copy(renderScale = draft.value.renderScale) } },
        )

        when (draft.value.style) {
            DynamicBackdropStyle.Mesh -> Card {
                ActionList(
                    items = listOf(
                        ActionListItem(
                            labelRes = R.string.dynamic_backdrop_mesh_shapes,
                            onClick = onMeshPresetsSelected,
                        ),
                    ),
                    containerStyle = ActionListContainerStyle.Plain,
                )
            }
            DynamicBackdropStyle.Titanium -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BackdropSlider(
                    labelRes = R.string.dynamic_backdrop_grain,
                    value = draft.value.titaniumGrain,
                    valueRange = 0f..1f,
                    onValueChange = { draft.value = draft.value.copy(titaniumGrain = it) },
                    onValueChangeFinished = { commit { it.copy(titaniumGrain = draft.value.titaniumGrain) } },
                )
                BackdropSlider(
                    labelRes = R.string.dynamic_backdrop_sparkle,
                    value = draft.value.titaniumSparkle,
                    valueRange = 0f..1f,
                    onValueChange = { draft.value = draft.value.copy(titaniumSparkle = it) },
                    onValueChangeFinished = { commit { it.copy(titaniumSparkle = draft.value.titaniumSparkle) } },
                )
                BackdropSlider(
                    labelRes = R.string.dynamic_backdrop_sheen,
                    value = draft.value.titaniumSheen,
                    valueRange = 0f..1f,
                    onValueChange = { draft.value = draft.value.copy(titaniumSheen = it) },
                    onValueChangeFinished = { commit { it.copy(titaniumSheen = draft.value.titaniumSheen) } },
                )
                BackdropSlider(
                    labelRes = R.string.dynamic_backdrop_metalness,
                    value = draft.value.titaniumMetalness,
                    valueRange = 0f..1f,
                    onValueChange = { draft.value = draft.value.copy(titaniumMetalness = it) },
                    onValueChangeFinished = { commit { it.copy(titaniumMetalness = draft.value.titaniumMetalness) } },
                )
                BackdropSlider(
                    labelRes = R.string.dynamic_backdrop_crest,
                    value = draft.value.titaniumCrestShiftMs.toFloat(),
                    valueRange = 300f..6000f,
                    integerDisplay = true,
                    onValueChange = {
                        draft.value = draft.value.copy(titaniumCrestShiftMs = it.roundToInt())
                    },
                    onValueChangeFinished = {
                        commit { it.copy(titaniumCrestShiftMs = draft.value.titaniumCrestShiftMs) }
                    },
                )
                Card {
                    ActionList(
                        items = listOf(
                            ActionListItem(
                                labelRes = R.string.dynamic_backdrop_dithering,
                                trailingContent = {
                                    TuneSwitch(
                                        checked = draft.value.titaniumDithering,
                                        onCheckedChange = { enabled ->
                                            draft.value = draft.value.copy(titaniumDithering = enabled)
                                            commit { it.copy(titaniumDithering = enabled) }
                                        },
                                    )
                                },
                                onClick = {
                                    val enabled = !draft.value.titaniumDithering
                                    draft.value = draft.value.copy(titaniumDithering = enabled)
                                    commit { it.copy(titaniumDithering = enabled) }
                                },
                            ),
                            ActionListItem(
                                labelRes = R.string.dynamic_backdrop_scrim,
                                trailingContent = {
                                    TuneSwitch(
                                        checked = draft.value.titaniumLyricScrim,
                                        onCheckedChange = { enabled ->
                                            draft.value = draft.value.copy(titaniumLyricScrim = enabled)
                                            commit { it.copy(titaniumLyricScrim = enabled) }
                                        },
                                    )
                                },
                                onClick = {
                                    val enabled = !draft.value.titaniumLyricScrim
                                    draft.value = draft.value.copy(titaniumLyricScrim = enabled)
                                    commit { it.copy(titaniumLyricScrim = enabled) }
                                },
                            ),
                        ),
                        containerStyle = ActionListContainerStyle.Plain,
                    )
                }
            }
        }

        Card {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.dynamic_backdrop_reset,
                        onClick = {
                            draft.value = DynamicBackdropSettings.Default
                            onSettingsChanged(DynamicBackdropSettings.Default)
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
) {
    val colors = LocalTuneColors.current
    val display = if (integerDisplay) value.roundToInt().toString() else "%.2f".format(value)
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
