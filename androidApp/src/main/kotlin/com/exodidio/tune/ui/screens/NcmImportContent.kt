package com.exodidio.tune.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.library.NcmFile
import com.exodidio.tune.library.NcmImportPreferences
import com.exodidio.tune.library.NcmImportSummary
import com.exodidio.tune.library.NcmImporter
import com.exodidio.tune.library.scanPathForTreeUriString
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDivider
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.launch

@Composable
internal fun NcmImportContent(
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context.applicationContext) { NcmImportPreferences(context.applicationContext) }
    val importer = remember(context.applicationContext) { NcmImporter(context.applicationContext) }
    var folder by remember { mutableStateOf(preferences.folderUri) }
    var scanning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<NcmFile>>(emptyList()) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var summary by remember { mutableStateOf<NcmImportSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            preferences.setFolder(uri.toString())
            folder = uri.toString()
            files = emptyList()
            summary = null
            error = null
        }
    }
    fun startScan() {
        val uri = folder?.let(Uri::parse) ?: return
        scanning = true
        error = null
        summary = null
        scope.launch {
            val found = runCatching { importer.scan(uri) }
            files = found.getOrDefault(emptyList())
            found.exceptionOrNull()?.let { error = it.message }
            scanning = false
        }
    }
    fun startImport() {
        if (files.isEmpty() || importing) return
        importing = true
        error = null
        progress = 0 to files.size
        scope.launch {
            val result = runCatching {
                importer.import(files) { done, total, _ ->
                    progress = done to total
                }
            }
            summary = result.getOrNull()
            result.exceptionOrNull()?.let { error = it.message }
            progress = null
            importing = false
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(
            symbol = MaterialSymbols.Album,
            title = stringResource(R.string.ncm_import_title),
            description = stringResource(R.string.ncm_import_desc),
        )
        Card {
            Text(
                text = stringResource(R.string.ncm_import_folder_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textMain,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
            Text(
                text = folder?.let { scanPathForTreeUriString(it) ?: it }
                    ?: stringResource(R.string.ncm_import_folder_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = if (folder == null) colors.textMuted else colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = buildList {
                    add(
                        ActionListItem(
                            labelRes = if (folder == null) R.string.ncm_import_folder_choose else R.string.ncm_import_folder_change,
                            leadingSymbol = MaterialSymbols.Add,
                            leadingIconTint = colors.primary,
                            onClick = { folderPicker.launch(null) },
                        ),
                    )
                    add(
                        ActionListItem(
                            labelRes = R.string.ncm_import_scan,
                            leadingSymbol = MaterialSymbols.Refresh,
                            trailingContent = if (scanning) {
                                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                            } else {
                                null
                            },
                            onClick = if (folder == null || scanning || importing) null else ::startScan,
                        ),
                    )
                    add(
                        ActionListItem(
                            labelRes = R.string.ncm_import_import,
                            leadingSymbol = MaterialSymbols.FileImport,
                            leadingIconTint = colors.primary,
                            trailingContent = if (importing) {
                                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                            } else {
                                null
                            },
                            onClick = if (files.isEmpty() || importing) null else ::startImport,
                        ),
                    )
                },
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
        if (files.isNotEmpty()) {
            Card {
                Text(
                    text = stringResource(R.string.ncm_import_scan_result, files.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textMain,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                )
                files.take(12).forEach { entry ->
                    Text(
                        text = "${entry.name}  ·  ${formatFileSize(entry.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (files.size > 12) {
                    Text(
                        text = stringResource(R.string.ncm_import_more, files.size - 12),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, end = 16.dp),
                    )
                }
            }
        }
        progress?.let { (done, total) ->
            if (importing) {
                Text(
                    text = stringResource(R.string.ncm_import_progress, done, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        summary?.let { result ->
            Text(
                text = stringResource(R.string.ncm_import_done, result.imported, result.skipped, result.failed),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMain,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        error?.let { message ->
            Text(
                text = stringResource(R.string.ncm_import_error, message),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
