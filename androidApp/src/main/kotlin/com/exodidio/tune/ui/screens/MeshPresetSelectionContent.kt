package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.player.DynamicBackdropSettings
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.TuneSwitch

/** 网格形变多选：6 个固定预设 + 随机 6x6；换封面时从勾选项里随机采用。 */
@Composable
internal fun MeshPresetSelectionContent(
    settings: DynamicBackdropSettings,
    onSettingsChanged: (DynamicBackdropSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun toggle(index: Int, checked: Boolean) {
        val pool = settings.meshPresetPool
        val next = if (checked) pool + index else pool - index
        onSettingsChanged(settings.copy(meshPresetPool = next))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            ActionList(
                items = MeshPresetLabels.mapIndexed { index, labelRes ->
                    val checked = index in settings.meshPresetPool
                    ActionListItem(
                        labelRes = labelRes,
                        trailingContent = {
                            TuneSwitch(
                                checked = checked,
                                onCheckedChange = { toggle(index, it) },
                            )
                        },
                        onClick = { toggle(index, !checked) },
                    )
                },
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
        Card {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.dynamic_backdrop_mesh_all,
                        onClick = {
                            onSettingsChanged(settings.copy(meshPresetPool = (0..7).toSet()))
                        },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
    }
}

/** 与 demo 的 Presets.ALL 顺序一致：Apple 5x5/4x4 A-F、Plain 3x3，最后是随机 6x6。 */
private val MeshPresetLabels = listOf(
    R.string.dynamic_backdrop_mesh_preset_a,
    R.string.dynamic_backdrop_mesh_preset_b,
    R.string.dynamic_backdrop_mesh_preset_c,
    R.string.dynamic_backdrop_mesh_preset_d,
    R.string.dynamic_backdrop_mesh_preset_e,
    R.string.dynamic_backdrop_mesh_preset_f,
    R.string.dynamic_backdrop_mesh_preset_plain,
    R.string.dynamic_backdrop_mesh_preset_random,
)
