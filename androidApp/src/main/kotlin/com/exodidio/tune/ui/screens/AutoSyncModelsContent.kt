package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.lyrics.autosync.AutoSyncCatalog
import com.exodidio.tune.lyrics.autosync.AutoSyncDictionary
import com.exodidio.tune.lyrics.autosync.AutoSyncDownloads
import com.exodidio.tune.lyrics.autosync.AutoSyncInstaller
import com.exodidio.tune.lyrics.autosync.AutoSyncModelStore
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.theme.LocalTuneColors

/** 对齐模型管理：中文 / 英文 / 日文 + 日文所需 OpenJTalk 词典。 */
@Composable
internal fun AutoSyncModelsContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalTuneColors.current
    val job by AutoSyncDownloads.state.collectAsState()
    var installed by remember { mutableStateOf(AutoSyncModelStore.installedLanguages(context)) }
    var dictionaryInstalled by remember { mutableStateOf(AutoSyncDictionary.isInstalled(context)) }
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var dictionaryBytes by remember { mutableStateOf(23_646_843L) }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(job?.running, job?.error) {
        if (job?.running != true) {
            installed = AutoSyncModelStore.installedLanguages(context)
            dictionaryInstalled = AutoSyncDictionary.isInstalled(context)
        }
    }
    LaunchedEffect(Unit) {
        AutoSyncDownloads.clearFinished()
        runCatching { AutoSyncCatalog.index() }.onSuccess { index ->
            sizes = index.packs.mapValues { (_, pack) -> pack.requiredAssets.sumOf { it.bytes.coerceAtLeast(0L) } }
            if (notice.isBlank()) notice = index.notice
        }
    }

    fun refresh() {
        installed = AutoSyncModelStore.installedLanguages(context)
        dictionaryInstalled = AutoSyncDictionary.isInstalled(context)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (notice.isNotBlank()) {
            Text(
                text = stringResource(R.string.lyrics_auto_align_notice),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        job?.let { state ->
            if (state.running) {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { AutoSyncDownloads.cancel(context) }) {
                            Text(stringResource(R.string.autosync_download_cancel))
                        }
                    }
                }
            } else if (state.error != null) {
                Text(
                    text = stringResource(R.string.autosync_download_failed, state.error ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        LanguageRow(
            title = stringResource(R.string.autosync_language_zh),
            bytes = sizes["zh"],
            installed = "zh" in installed,
            busy = job?.running == true,
            onDownload = { AutoSyncDownloads.start(context, AutoSyncDownloads.KIND_MODEL, "zh") },
            onDelete = { AutoSyncInstaller.remove(context, "zh"); refresh() },
        )
        LanguageRow(
            title = stringResource(R.string.autosync_language_en),
            bytes = sizes["en"],
            installed = "en" in installed,
            busy = job?.running == true,
            onDownload = { AutoSyncDownloads.start(context, AutoSyncDownloads.KIND_MODEL, "en") },
            onDelete = { AutoSyncInstaller.remove(context, "en"); refresh() },
        )
        LanguageRow(
            title = stringResource(R.string.autosync_language_ja),
            bytes = sizes["ja"],
            installed = "ja" in installed,
            busy = job?.running == true,
            onDownload = { AutoSyncDownloads.start(context, AutoSyncDownloads.KIND_MODEL, "ja") },
            onDelete = { AutoSyncInstaller.remove(context, "ja"); refresh() },
        )
        LanguageRow(
            title = stringResource(R.string.autosync_ja_dictionary),
            bytes = dictionaryBytes,
            installed = dictionaryInstalled,
            busy = job?.running == true,
            onDownload = { AutoSyncDownloads.start(context, AutoSyncDownloads.KIND_DICTIONARY) },
            onDelete = { AutoSyncDictionary.remove(context); refresh() },
        )
    }
}

@Composable
private fun LanguageRow(
    title: String,
    bytes: Long?,
    installed: Boolean,
    busy: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalTuneColors.current
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = buildString {
                        append(
                            if (installed) stringResource(R.string.autosync_installed)
                            else stringResource(R.string.autosync_not_installed),
                        )
                        bytes?.takeIf { it > 0 }?.let {
                            append(" · ")
                            append("%.1f MB".format(it / 1024.0 / 1024.0))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (installed) {
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text(stringResource(R.string.autosync_delete))
                }
            } else {
                Button(onClick = onDownload, enabled = !busy) {
                    Text(stringResource(R.string.autosync_download))
                }
            }
        }
    }
}
