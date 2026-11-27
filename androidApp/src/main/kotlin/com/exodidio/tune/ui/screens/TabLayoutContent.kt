package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.navigation.symbol
import com.exodidio.tune.ui.navigation.titleRes
import com.exodidio.tune.ui.theme.LocalTuneColors

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag

/** Settings page to reorder and show/hide bottom-navigation destinations. */
@Composable
internal fun TabLayoutContent(
    visibleTabs: List<AppDestination>,
    onVisibleTabsChanged: (List<AppDestination>) -> Unit,
    modifier: Modifier = Modifier,
    mergeMineAndLibrary: Boolean = false,
    onMergeMineAndLibraryChanged: (Boolean) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    // 设置列表永远展示全部标签：可见性只由开关控制，关掉的行不能从列表里消失。
    // 用 SnapshotStateList：普通 MutableList 原地移动不会触发重组，箭头会没反应。
    val order = remember { mutableStateListOf<AppDestination>().apply { addAll(mergeTabOrder(visibleTabs)) } }
    var visible by remember { mutableStateOf(visibleTabs.filter { it in AppDestination.dockable }.toSet()) }

    // 外部偏好变化时同步顺序与可见性，但不覆写用户尚未改动的持久化顺序。
    LaunchedEffect(visibleTabs) {
        val nextVisible = visibleTabs.filter { it in AppDestination.dockable }.toSet()
        val currentEffective = order.filter { visible.contains(it) }
        val normalizedIncoming = visibleTabs.distinct().filter { it in AppDestination.dockable }
        if (currentEffective != normalizedIncoming || visible != nextVisible) {
            val merged = mergeTabOrder(normalizedIncoming, order)
            order.clear()
            order.addAll(merged)
            visible = nextVisible
        }
    }

    fun emitUpdated() {
        onVisibleTabsChanged(order.filter { visible.contains(it) })
    }

    val displayedOrder = if (mergeMineAndLibrary) {
        order.filter { it != AppDestination.Mine }
    } else {
        order.toList()
    }

    fun moveDisplayed(displayedIndex: Int, delta: Int) {
        val targetDisplayed = displayedIndex + delta
        if (targetDisplayed !in displayedOrder.indices) return
        val first = displayedOrder[displayedIndex]
        val second = displayedOrder[targetDisplayed]
        val indexA = order.indexOf(first)
        val indexB = order.indexOf(second)
        if (indexA >= 0 && indexB >= 0) {
            order[indexA] = second
            order[indexB] = first
            emitUpdated()
        }
    }

    fun toggleVisible(dest: AppDestination, checked: Boolean) {
        // 设置入口不能隐藏：关掉后就没地方再打开标签设置了。
        if (!checked && dest == AppDestination.Settings) return
        val next = if (checked) visible + dest else visible - dest
        if (next != visible) {
            visible = next
            emitUpdated()
        }
    }

    Column(
        modifier = modifier,
    ) {
        ActionList(
            items = listOf(
                ActionListItem(
                    key = "merge-mine-library",
                    labelRes = R.string.tab_layout_merge_mine_library,
                    leadingSymbol = MaterialSymbols.Library,
                    leadingIconTint = colors.primary,
                    subtitle = stringResource(R.string.tab_layout_merge_mine_library_desc),
                    trailingContent = {
                        TuneSwitch(
                            checked = mergeMineAndLibrary,
                            onCheckedChange = onMergeMineAndLibraryChanged,
                            modifier = Modifier.testTag("tab-layout-merge-mine-library-switch"),
                        )
                    },
                    onClick = { onMergeMineAndLibraryChanged(!mergeMineAndLibrary) },
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.tab_layout_description),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        ActionList(
            items = displayedOrder.withIndex().map { (index, dest) ->
                ActionListItem(
                    key = dest,
                    labelRes = dest.titleRes,
                    leadingSymbol = dest.symbol,
                    leadingIconTint = colors.primary,
                    trailingContent = {
                        Row(
                            // 不能用 fillMaxWidth：尾部内容会把标题压成零宽，文字叠到开关上。
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TuneSwitch(
                                checked = dest == AppDestination.Settings || visible.contains(dest),
                                onCheckedChange = { toggleVisible(dest, it) },
                                enabled = dest != AppDestination.Settings,
                            )
                            IconButton(
                                onClick = { moveDisplayed(index, -1) },
                                enabled = index > 0,
                            ) {
                                MaterialSymbol(
                                    symbol = MaterialSymbols.ArrowUpward,
                                    size = 20.dp,
                                    tint = colors.textMuted,
                                )
                            }
                            IconButton(
                                onClick = { moveDisplayed(index, +1) },
                                enabled = index < displayedOrder.lastIndex,
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

/** 将偏好顺序回填到可见槽位，同时保留隐藏标签的相对位置，并补全全部可停靠标签。 */
internal fun mergeTabOrder(
    preferred: List<AppDestination>,
    current: List<AppDestination> = emptyList(),
): MutableList<AppDestination> {
    val validPreferred = preferred.distinct().filter { it in AppDestination.dockable }
    val preferredSet = validPreferred.toSet()
    val base = buildList {
        current.forEach { if (it !in this && it in AppDestination.dockable) add(it) }
        AppDestination.dockable.forEach { if (it !in this) add(it) }
    }
    val queue = ArrayDeque(validPreferred)
    return base.map { item ->
        if (item in preferredSet && queue.isNotEmpty()) queue.removeFirst() else item
    }.toMutableList()
}
