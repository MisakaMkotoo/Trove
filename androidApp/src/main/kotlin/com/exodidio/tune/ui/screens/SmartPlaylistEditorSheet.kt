package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.library.SmartPlaylistEngine
import com.exodidio.tune.library.SmartPlaylistField
import com.exodidio.tune.library.SmartPlaylistMatchMode
import com.exodidio.tune.library.SmartPlaylistOperator
import com.exodidio.tune.library.SmartPlaylistRule
import com.exodidio.tune.library.SmartPlaylistSortBy
import com.exodidio.tune.library.SmartPlaylistSpec
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.components.TunePillButton
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.TuneTextField
import com.exodidio.tune.ui.components.TuneTextFieldSize
import com.exodidio.tune.ui.theme.LocalTuneColors

@Composable
internal fun SmartPlaylistEditorBottomSheet(
    isEdit: Boolean = false,
    initialName: String = "",
    initialSpec: SmartPlaylistSpec = SmartPlaylistSpec(
        matchMode = SmartPlaylistMatchMode.All,
        rules = listOf(SmartPlaylistRule(SmartPlaylistField.PlayCount, SmartPlaylistOperator.GreaterThan, "0")),
        sortBy = SmartPlaylistSortBy.PlayCountDesc,
        limit = 25,
    ),
    onDismiss: () -> Unit,
    onSave: (name: String, spec: SmartPlaylistSpec) -> Unit,
) {
    val colors = LocalTuneColors.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var matchMode by remember(initialSpec) { mutableStateOf(initialSpec.matchMode) }
    var sortBy by remember(initialSpec) { mutableStateOf(initialSpec.sortBy) }
    var limitText by remember(initialSpec) { mutableStateOf(initialSpec.limit?.toString().orEmpty()) }
    val rules = remember(initialSpec) {
        mutableStateListOf<SmartPlaylistRule>().apply { addAll(initialSpec.rules) }
    }

    TuneBottomSheet(
        title = {
            Text(
                text = stringResource(if (isEdit) R.string.smart_playlist_edit_title else R.string.smart_playlist_create_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!isEdit) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.smart_playlist_presets_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textMuted,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SmartPlaylistEngine.presets.forEach { preset ->
                            val presetTitle = stringResource(preset.titleRes)
                            SmartChip(
                                label = presetTitle,
                                selected = false,
                                onClick = {
                                    name = presetTitle
                                    matchMode = preset.spec.matchMode
                                    sortBy = preset.spec.sortBy
                                    limitText = preset.spec.limit?.toString().orEmpty()
                                    rules.clear()
                                    rules.addAll(preset.spec.rules)
                                },
                            )
                        }
                    }
                }
            }

            TuneTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.smart_playlist_name_hint),
                size = TuneTextFieldSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.smart_playlist_match_mode),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartChip(
                        label = stringResource(R.string.smart_playlist_match_all),
                        selected = matchMode == SmartPlaylistMatchMode.All,
                        onClick = { matchMode = SmartPlaylistMatchMode.All },
                    )
                    SmartChip(
                        label = stringResource(R.string.smart_playlist_match_any),
                        selected = matchMode == SmartPlaylistMatchMode.Any,
                        onClick = { matchMode = SmartPlaylistMatchMode.Any },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.smart_playlist_rules_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textMuted,
                    )
                    SmartChip(
                        label = stringResource(R.string.smart_playlist_add_rule),
                        selected = false,
                        onClick = {
                            rules.add(
                                SmartPlaylistRule(
                                    field = SmartPlaylistField.Artist,
                                    operator = SmartPlaylistOperator.Contains,
                                    value = "",
                                ),
                            )
                        },
                    )
                }
                rules.forEachIndexed { index, rule ->
                    SmartRuleCard(
                        rule = rule,
                        onRuleChange = { updated -> rules[index] = updated },
                        onDelete = if (rules.size > 1) {
                            { rules.removeAt(index) }
                        } else {
                            null
                        },
                    )
                }
            }

            Selection(
                labelRes = R.string.smart_playlist_sort_title,
                options = SmartPlaylistSortBy.entries.map { option ->
                    SelectionOption(option, sortLabelRes(option))
                },
                selectedValue = sortBy,
                onValueSelected = { sortBy = it },
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.smart_playlist_limit_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textMuted,
                )
                TuneTextField(
                    value = limitText,
                    onValueChange = { text -> limitText = text.filter(Char::isDigit).take(4) },
                    placeholder = stringResource(R.string.smart_playlist_limit_hint),
                    size = TuneTextFieldSize.Medium,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))
            TunePillButton(
                label = stringResource(if (isEdit) R.string.save else R.string.create),
                variant = TunePillButtonVariant.Primary,
                enabled = name.trim().isNotBlank(),
                onClick = {
                    val parsedLimit = limitText.toIntOrNull()?.takeIf { it > 0 }
                    val spec = SmartPlaylistSpec(
                        matchMode = matchMode,
                        rules = rules.toList(),
                        sortBy = sortBy,
                        limit = parsedLimit,
                    )
                    onSave(name.trim(), spec)
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SmartRuleCard(
    rule: SmartPlaylistRule,
    onRuleChange: (SmartPlaylistRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = LocalTuneColors.current
    val allowedOperators = remember(rule.field) { SmartPlaylistEngine.defaultOperatorsFor(rule.field) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.glassElevated)
            .border(0.5.dp, colors.borderGlass, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Selection(
                    labelRes = null,
                    options = SmartPlaylistField.entries.map { f -> SelectionOption(f, fieldLabelRes(f)) },
                    selectedValue = rule.field,
                    onValueSelected = { newField ->
                        val nextOps = SmartPlaylistEngine.defaultOperatorsFor(newField)
                        val nextOp = if (rule.operator in nextOps) rule.operator else nextOps.first()
                        val nextVal = if (newField == SmartPlaylistField.Favorite) "true" else rule.value
                        onRuleChange(rule.copy(field = newField, operator = nextOp, value = nextVal))
                    },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                Selection(
                    labelRes = null,
                    options = allowedOperators.map { op -> SelectionOption(op, operatorLabelRes(op)) },
                    selectedValue = if (rule.operator in allowedOperators) rule.operator else allowedOperators.first(),
                    onValueSelected = { newOp -> onRuleChange(rule.copy(operator = newOp)) },
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    MaterialSymbol(
                        symbol = MaterialSymbols.Delete,
                        size = 18.dp,
                        tint = colors.textMuted,
                    )
                }
            }
        }
        if (rule.field != SmartPlaylistField.Favorite) {
            val numeric = rule.field == SmartPlaylistField.PlayCount ||
                rule.field == SmartPlaylistField.DurationSec ||
                rule.field == SmartPlaylistField.DateAddedDays
            TuneTextField(
                value = rule.value,
                onValueChange = { next ->
                    val cleaned = if (numeric) next.filter(Char::isDigit) else next
                    onRuleChange(rule.copy(value = cleaned))
                },
                placeholder = stringResource(R.string.smart_playlist_value_hint),
                size = TuneTextFieldSize.Medium,
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SmartChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) colors.primary else colors.glassElevated)
            .border(
                width = 0.5.dp,
                color = if (selected) colors.primary else colors.borderGlass,
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (selected) colors.onPrimary else colors.textMain,
            maxLines = 1,
        )
    }
}

private fun fieldLabelRes(field: SmartPlaylistField): Int = when (field) {
    SmartPlaylistField.Favorite -> R.string.smart_playlist_field_favorite
    SmartPlaylistField.Artist -> R.string.smart_playlist_field_artist
    SmartPlaylistField.Album -> R.string.smart_playlist_field_album
    SmartPlaylistField.Folder -> R.string.smart_playlist_field_folder
    SmartPlaylistField.Title -> R.string.smart_playlist_field_title
    SmartPlaylistField.Format -> R.string.smart_playlist_field_format
    SmartPlaylistField.PlayCount -> R.string.smart_playlist_field_play_count
    SmartPlaylistField.DurationSec -> R.string.smart_playlist_field_duration_sec
    SmartPlaylistField.DateAddedDays -> R.string.smart_playlist_field_date_added_days
}

private fun operatorLabelRes(op: SmartPlaylistOperator): Int = when (op) {
    SmartPlaylistOperator.Contains -> R.string.smart_playlist_op_contains
    SmartPlaylistOperator.Equals -> R.string.smart_playlist_op_equals
    SmartPlaylistOperator.GreaterThan -> R.string.smart_playlist_op_gt
    SmartPlaylistOperator.LessThan -> R.string.smart_playlist_op_lt
    SmartPlaylistOperator.IsTrue -> R.string.smart_playlist_op_is_true
    SmartPlaylistOperator.IsFalse -> R.string.smart_playlist_op_is_false
}

private fun sortLabelRes(sort: SmartPlaylistSortBy): Int = when (sort) {
    SmartPlaylistSortBy.DateAddedDesc -> R.string.smart_playlist_sort_date_added
    SmartPlaylistSortBy.PlayCountDesc -> R.string.smart_playlist_sort_play_count
    SmartPlaylistSortBy.TitleAsc -> R.string.smart_playlist_sort_title_asc
    SmartPlaylistSortBy.ArtistAsc -> R.string.smart_playlist_sort_artist_asc
    SmartPlaylistSortBy.DurationDesc -> R.string.smart_playlist_sort_duration_desc
    SmartPlaylistSortBy.Random -> R.string.smart_playlist_sort_random
}
