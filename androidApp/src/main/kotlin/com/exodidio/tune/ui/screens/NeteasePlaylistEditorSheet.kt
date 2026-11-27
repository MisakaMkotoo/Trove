package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.components.TuneTextField
import com.exodidio.tune.ui.components.TuneTextFieldSize
import com.exodidio.tune.ui.theme.LocalTuneColors

@Composable
internal fun NeteasePlaylistEditorBottomSheet(
    isCreate: Boolean,
    initialName: String = "",
    initialDescription: String = "",
    busy: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (name: String, description: String, isPrivate: Boolean) -> Unit,
) {
    val colors = LocalTuneColors.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var isPrivate by remember { mutableStateOf(false) }
    val valid = name.trim().isNotEmpty() && !busy

    TuneBottomSheet(
        title = {
            Text(
                text = stringResource(
                    if (isCreate) R.string.netease_playlist_create_title else R.string.netease_playlist_edit_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = { if (!busy) onDismiss() },
        leadingAction = { dismiss ->
            NeteaseEditorSheetIconButton(
                symbol = MaterialSymbols.Close,
                label = stringResource(R.string.cancel),
                enabled = !busy,
                onClick = dismiss,
            )
        },
        trailingAction = {
            if (busy) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                NeteaseEditorSheetIconButton(
                    symbol = MaterialSymbols.Check,
                    label = stringResource(if (isCreate) R.string.create else R.string.save),
                    enabled = valid,
                    primary = true,
                    onClick = { onSubmit(name.trim(), description.trim(), isPrivate) },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TuneTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.netease_playlist_name_hint),
                size = TuneTextFieldSize.Medium,
                enabled = !busy,
                onDone = { if (valid) onSubmit(name.trim(), description.trim(), isPrivate) },
            )
            TuneTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = stringResource(R.string.netease_playlist_desc_hint),
                size = TuneTextFieldSize.Medium,
                enabled = !busy,
            )
            if (isCreate) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !busy) { isPrivate = !isPrivate }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.netease_playlist_private),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textMain,
                        modifier = Modifier.weight(1f),
                    )
                    TuneSwitch(
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it },
                        enabled = !busy,
                    )
                }
            }
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun NeteaseEditorSheetIconButton(
    symbol: String,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val colors = LocalTuneColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (primary) if (enabled) colors.primary else colors.buttonSecondary else colors.glassElevated)
            .border(0.5.dp, if (primary && enabled) colors.primary else colors.borderGlass, RoundedCornerShape(24.dp))
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(
            symbol = symbol,
            contentDescription = null,
            size = 22.dp,
            tint = if (primary && enabled) colors.onPrimary else if (enabled) colors.textMain else colors.textMuted,
        )
    }
}
