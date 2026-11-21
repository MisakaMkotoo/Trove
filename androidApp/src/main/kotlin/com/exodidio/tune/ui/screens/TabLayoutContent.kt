package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.AppDestination
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.navigation.symbol
import com.exodidio.tune.ui.navigation.titleRes
import com.exodidio.tune.ui.theme.LocalTuneColors

/** Settings page to reorder and show/hide bottom-navigation destinations. */
@Composable
internal fun TabLayoutContent(
    visibleTabs: List<AppDestination>,
    onVisibleTabsChanged: (List<AppDestination>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    // 设置列表永远展示全部标签：可见性只由开关控制，关掉的行不能从列表里消失。
    // 用 SnapshotStateList：普通 MutableList 原地移动不会触发重组，箭头会没反应。
    val order = remember { mutableStateListOf<AppDestination>().apply { addAll(mergeTabOrder(visibleTabs)) } }
    var visible by remember { mutableStateOf(visibleTabs.filter { it in AppDestination.dockable }.toSet()) }

    // 外部偏好变化时只同步可见性和顺序，hidden 的标签仍保留在 order 里。
    LaunchedEffect(visibleTabs) {
        val merged = mergeTabOrder(visibleTabs, order)
        order.clear()
        order.addAll(merged)
        visible = visibleTabs.filter { it in AppDestination.dockable }.toSet()
    }

    val effectiveTabs = order.filter { visible.contains(it) }

    LaunchedEffect(effectiveTabs) {
        onVisibleTabsChanged(effectiveTabs)
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target in order.indices) {
            val item = order.removeAt(index)
            order.add(target, item)
        }
    }

    fun toggleVisible(dest: AppDestination, checked: Boolean) {
        // 设置入口不能隐藏：关掉后就没地方再打开标签设置了。
        if (!checked && dest == AppDestination.Settings) return
        if (checked) visible += dest else visible -= dest
    }

    Column(
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.tab_layout_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        ActionList(
            items = order.withIndex().map { (index, dest) ->
                ActionListItem(
                    labelRes = dest.titleRes,
                    leadingSymbol = dest.symbol,
                    leadingIconTint = colors.primary,
                    trailingContent = {
                        Row(
                            // 不能用 fillMaxWidth：尾部内容会把标题压成零宽，文字叠到开关上。
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(
                                checked = dest == AppDestination.Settings || visible.contains(dest),
                                onCheckedChange = { toggleVisible(dest, it) },
                                enabled = dest != AppDestination.Settings,
                            )
                            IconButton(
                                onClick = { move(index, -1) },
                                enabled = index > 0,
                            ) {
                                MaterialSymbol(
                                    symbol = MaterialSymbols.ArrowUpward,
                                    size = 20.dp,
                                    tint = colors.textMuted,
                                )
                            }
                            IconButton(
                                onClick = { move(index, +1) },
                                enabled = index < order.lastIndex,
                            ) {
                                MaterialSymbol(
                                    symbol = MaterialSymbols.ArrowDownward,
                                    size = 20.dp,
                                    tint = colors.textMuted,
                                )
                            }
                        }
                    },
                    onClick = {},
                )
            },
            containerStyle = ActionListContainerStyle.Card,
        )
    }
}

/** 顺序 = 当前顺序 + 偏好里新增的 + 全部其余标签，隐藏的标签也永远保留在列表里。 */
private fun mergeTabOrder(
    preferred: List<AppDestination>,
    current: List<AppDestination> = emptyList(),
): MutableList<AppDestination> {
    val result = mutableListOf<AppDestination>()
    current.forEach { if (it !in result && it in AppDestination.dockable) result += it }
    preferred.forEach { if (it !in result && it in AppDestination.dockable) result += it }
    AppDestination.dockable.forEach { if (it !in result) result += it }
    return result
}
