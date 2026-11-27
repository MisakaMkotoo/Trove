package com.exodidio.tune.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.settings.DownloadPreferences
import com.exodidio.tune.settings.DownloadQuality
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDivider
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.theme.LocalTuneColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DownloadSettingsContent(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalTuneColors.current
    val snapshot by DownloadPreferences.snapshotFlow.collectAsStateWithLifecycle()
    var customFolderName by remember(snapshot.customFolderUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        DownloadPreferences.refreshAsync(context)
    }

    LaunchedEffect(snapshot.customFolderUri) {
        customFolderName = DownloadPreferences.resolveFolderDisplayName(context, snapshot.customFolderUri)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(uri, flags)
                    }
                }
                DownloadPreferences.setCustomFolderUri(context, uri.toString())
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabeledCard(label = stringResource(R.string.download_quality_title)) {
            Selection(
                labelRes = R.string.download_quality_title,
                options = DownloadQuality.entries.map { quality ->
                    SelectionOption(quality, quality.labelRes)
                },
                selectedValue = snapshot.quality,
                onValueSelected = { selected ->
                    scope.launch {
                        DownloadPreferences.setDownloadQuality(context, selected)
                    }
                },
                hazeState = hazeState,
            )
        }

        LabeledCard(label = stringResource(R.string.download_location_title)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.download_use_private_folder,
                        subtitle = stringResource(R.string.download_use_private_folder_desc),
                        trailingContent = {
                            TuneSwitch(
                                checked = snapshot.usePrivateFolder,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        DownloadPreferences.setUsePrivateFolder(context, checked)
                                    }
                                },
                            )
                        },
                        onClick = {
                            scope.launch {
                                DownloadPreferences.setUsePrivateFolder(context, !snapshot.usePrivateFolder)
                            }
                        },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            val pickerEnabled = !snapshot.usePrivateFolder
            val pickerSubtitle = when {
                snapshot.usePrivateFolder -> stringResource(R.string.download_location_private)
                !customFolderName.isNullOrBlank() -> customFolderName!!
                else -> stringResource(R.string.download_location_unset)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .alpha(if (pickerEnabled) 1f else 0.45f)
                    .clickable(
                        enabled = pickerEnabled,
                        role = Role.Button,
                        onClick = { folderPickerLauncher.launch(null) },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaterialSymbol(
                    symbol = MaterialSymbols.Folder,
                    contentDescription = null,
                    size = 20.dp,
                    tint = colors.textMain,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.download_location_pick),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pickerSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MaterialSymbol(
                    symbol = MaterialSymbols.ChevronRight,
                    contentDescription = null,
                    size = 20.dp,
                    tint = colors.textMuted,
                )
            }
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Text(
                text = stringResource(R.string.download_location_disabled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
