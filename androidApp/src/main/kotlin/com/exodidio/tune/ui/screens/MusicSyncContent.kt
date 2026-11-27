package com.exodidio.tune.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.library.LocalLibraryScanner
import com.exodidio.tune.library.ScanFolderPreferences
import com.exodidio.tune.library.ScanPermission
import com.exodidio.tune.library.audioPermission
import com.exodidio.tune.library.scanPathForTreeUriString
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDivider
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun MusicSyncContent(
    modifier: Modifier = Modifier,
    hideShortAudio: Boolean = false,
    onHideShortAudioChanged: (Boolean) -> Unit = {},
    showGenres: Boolean = false,
    onShowGenresChanged: (Boolean) -> Unit = {},
    showComposers: Boolean = false,
    onShowComposersChanged: (Boolean) -> Unit = {},
    onDuplicatesSelected: () -> Unit = {},
    onHiddenTracksSelected: () -> Unit = {},
    onDataAnalysisSelected: () -> Unit = {},
    onNcmImportSelected: () -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember(context.applicationContext) {
        LocalLibraryScanner(context.applicationContext, AndroidSyncRuntime.syncStore)
    }
    val scanState by scanner.state.collectAsState()
    val scanFolders = remember(context.applicationContext) {
        ScanFolderPreferences(context.applicationContext)
    }
    val folder by scanFolders.folder.collectAsState(initial = null)
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            scope.launch { scanFolders.setFolder(uri.toString()) }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context as? Activity
        val rationale = activity?.shouldShowRequestPermissionRationale(audioPermission()) == true
        scope.launch {
            scanner.refreshPermission(granted, rationale)
            if (granted) scanFolders.folder.first()?.let { scanner.scan(setOf(it)) } ?: scanner.scan(emptySet())
        }
    }
    LaunchedEffect(scanner) {
        val granted = context.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
        val activity = context as? Activity
        val rationale = !granted && activity?.shouldShowRequestPermissionRationale(audioPermission()) == true
        scanner.refreshPermission(granted, rationale)
    }
    fun startScan() {
        if (context.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                scanner.refreshPermission(true, false)
                val single = scanFolders.folder.first()
                scanner.scan(if (single.isNullOrBlank()) emptySet() else setOf(single))
            }
        } else {
            permissionLauncher.launch(audioPermission())
        }
    }
    fun clearFolderSelection(previous: String?) {
        if (previous?.startsWith("content://", ignoreCase = true) == true) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(previous),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
        }
        scope.launch { scanFolders.clearFolder() }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            symbol = MaterialSymbols.Refresh,
            title = stringResource(R.string.music_sync_title),
            description = stringResource(R.string.music_sync_desc),
        )
        LabeledCard(label = stringResource(R.string.music_sync_section_scan)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.library_scan_local,
                        leadingSymbol = MaterialSymbols.Refresh,
                        leadingIconTint = colors.primary,
                        trailingContent = if (scanState.scanning) {
                            { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                        } else {
                            null
                        },
                        onClick = ::startScan,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
            when (scanState.permission) {
                ScanPermission.NeedsRationale -> Text(
                    text = stringResource(R.string.music_sync_permission_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ScanPermission.Denied -> Text(
                    text = stringResource(R.string.music_sync_permission_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                else -> Unit
            }
            scanState.lastResult?.let { result ->
                Text(
                    text = stringResource(R.string.music_sync_last_result, result.inserted, result.skipped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            scanState.error?.let { error ->
                Text(
                    text = stringResource(R.string.music_sync_error, error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Text(
                text = stringResource(R.string.music_sync_folder_single_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textMain,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
            if (folder.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.music_sync_folder_single_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                Text(
                    text = scanPathForTreeUriString(folder!!) ?: folder!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = buildList {
                    add(
                        ActionListItem(
                            labelRes = if (folder.isNullOrBlank()) R.string.music_sync_folder_choose else R.string.music_sync_folder_change,
                            leadingSymbol = MaterialSymbols.Add,
                            leadingIconTint = colors.primary,
                            onClick = { folderPicker.launch(null) },
                        ),
                    )
                    if (!folder.isNullOrBlank()) {
                        add(
                            ActionListItem(
                                labelRes = R.string.music_sync_folder_clear,
                                leadingSymbol = MaterialSymbols.Refresh,
                                leadingIconTint = colors.textMuted,
                                onClick = { clearFolderSelection(folder) },
                            ),
                        )
                    }
                },
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
            )
        }
        LabeledCard(label = stringResource(R.string.music_sync_section_display)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.hide_short_audio,
                        trailingContent = {
                            TuneSwitch(
                                checked = hideShortAudio,
                                onCheckedChange = onHideShortAudioChanged,
                            )
                        },
                        onClick = { onHideShortAudioChanged(!hideShortAudio) },
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_show_genres,
                        trailingContent = {
                            TuneSwitch(
                                checked = showGenres,
                                onCheckedChange = onShowGenresChanged,
                            )
                        },
                        onClick = { onShowGenresChanged(!showGenres) },
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_show_composers,
                        trailingContent = {
                            TuneSwitch(
                                checked = showComposers,
                                onCheckedChange = onShowComposersChanged,
                            )
                        },
                        onClick = { onShowComposersChanged(!showComposers) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }
        LabeledCard(label = stringResource(R.string.music_sync_section_tools)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.library_duplicates,
                        leadingSymbol = MaterialSymbols.Duplicates,
                        onClick = onDuplicatesSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_hidden_tracks,
                        leadingSymbol = MaterialSymbols.FilterList,
                        onClick = onHiddenTracksSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_data_analysis,
                        leadingSymbol = MaterialSymbols.BarChart,
                        onClick = onDataAnalysisSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.settings_ncm_import,
                        leadingSymbol = MaterialSymbols.Album,
                        onClick = onNcmImportSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
            )
        }
    }
}
