package com.exodidio.tune.ui.screens

import android.os.StatFs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseHiResCoverStore
import com.exodidio.tune.netease.NeteaseImageCache
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.NeteaseStreamProxy
import com.exodidio.tune.netease.NeteaseWifiAutoCacheManager
import com.exodidio.tune.sync.RemoteAudioCache
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
import com.exodidio.tune.ui.components.TuneDialog
import com.exodidio.tune.ui.components.TunePillButton
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.TuneImages
import com.exodidio.tune.ui.theme.LocalTuneColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ClearCacheTarget {
    Stream,
    Prefetch,
    Images,
    HiRes,
}

/**
 * 存储管理：按类别展示占用，支持 Selection 下拉调整缓存上限与单项/全部清理。
 * 所有统计、上限写入与文件删除都在 IO 线程执行；已下载歌曲仅统计不在此删除。
 */
@Composable
internal fun StorageManagementContent(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onExportBackup: () -> Unit = {},
    onRestoreBackup: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalTuneColors.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var pendingSingleClear by remember { mutableStateOf<ClearCacheTarget?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmRestoreBackup by remember { mutableStateOf(false) }
    var streamLimit by remember { mutableIntStateOf(800) }
    var prefetchLimit by remember { mutableIntStateOf(1024) }
    var imageLimit by remember { mutableIntStateOf(800) }
    val wifiCacheIds by NeteaseRepository.wifiAutoCacheIds.collectAsStateWithLifecycle()
    val wifiCacheStatus by NeteaseWifiAutoCacheManager.status.collectAsStateWithLifecycle()
    val neteasePlaylists by NeteaseRepository.activePlaylists.collectAsStateWithLifecycle()

    LaunchedEffect(refreshKey) {
        val loaded = withContext(Dispatchers.IO) {
            NeteaseStreamProxy.initialize(context)
            runCatching { NeteaseStreamProxy.awaitReady() }
            Triple(
                NeteaseStreamProxy.sizeLimitMb(context),
                RemoteAudioCache.sizeLimitMb(context),
                NeteaseImageCache.sizeLimitMb(context),
            ) to measureStorage(context)
        }
        streamLimit = loaded.first.first
        prefetchLimit = loaded.first.second
        imageLimit = loaded.first.third
        snapshot = loaded.second
    }
    val refresh: () -> Unit = { refreshKey += 1 }
    val data = snapshot
    val reclaimableBytes = data?.let { it.stream + it.prefetch + it.images + it.hires } ?: 0L

    fun performSingleClear(target: ClearCacheTarget) {
        if (clearing) return
        clearing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (target) {
                        ClearCacheTarget.Stream -> NeteaseStreamProxy.clearCache()
                        ClearCacheTarget.Prefetch -> RemoteAudioCache.clear(context)
                        ClearCacheTarget.Images -> {
                            NeteaseImageCache.clear(context)
                            TuneImages.clearDisk()
                        }
                        ClearCacheTarget.HiRes -> NeteaseHiResCoverStore.clear(context)
                    }
                }
                refresh()
            } finally {
                clearing = false
            }
        }
    }

    fun performClearAll() {
        if (clearing) return
        clearing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NeteaseStreamProxy.clearCache()
                    RemoteAudioCache.clear(context)
                    NeteaseImageCache.clear(context)
                    TuneImages.clearDisk()
                    NeteaseHiResCoverStore.clear(context)
                }
                refresh()
            } finally {
                clearing = false
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabeledCard(label = stringResource(R.string.storage_section_overview)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.storage_device_title,
                        trailingContent = {
                            Text(
                                text = data?.let {
                                    stringResource(
                                        R.string.storage_device_usage,
                                        formatStorageSize(it.freeBytes),
                                        formatStorageSize(it.totalBytes),
                                    )
                                } ?: "…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMuted,
                            )
                        },
                    ),
                    ActionListItem(
                        labelRes = R.string.storage_section_caches,
                        trailingContent = {
                            Text(
                                text = data?.let { formatStorageSize(reclaimableBytes) } ?: "…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMuted,
                            )
                        },
                    ),
                    ActionListItem(
                        labelRes = R.string.storage_downloads_title,
                        trailingContent = {
                            Text(
                                text = data?.downloads?.let(::formatStorageSize) ?: "…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMuted,
                            )
                        },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }

        LabeledCard(label = stringResource(R.string.storage_section_caches)) {
            CacheCategoryRow(
                title = stringResource(R.string.storage_stream_title),
                usageBytes = data?.stream,
                limitMb = streamLimit,
                presets = listOf(200, 500, 800, 1536, 3072),
                hazeState = hazeState,
                clearing = clearing,
                onLimitSelected = { preset ->
                    streamLimit = preset
                    scope.launch {
                        withContext(Dispatchers.IO) { NeteaseStreamProxy.setSizeLimitMb(context, preset) }
                        refresh()
                    }
                },
                onClearClick = { pendingSingleClear = ClearCacheTarget.Stream },
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            CacheCategoryRow(
                title = stringResource(R.string.storage_prefetch_title),
                usageBytes = data?.prefetch,
                limitMb = prefetchLimit,
                presets = listOf(500, 1024, 2048, 4096),
                hazeState = hazeState,
                clearing = clearing,
                onLimitSelected = { preset ->
                    prefetchLimit = preset
                    scope.launch {
                        withContext(Dispatchers.IO) { RemoteAudioCache.setSizeLimitMb(context, preset) }
                        refresh()
                    }
                },
                onClearClick = { pendingSingleClear = ClearCacheTarget.Prefetch },
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            CacheCategoryRow(
                title = stringResource(R.string.storage_image_title),
                usageBytes = data?.images,
                limitMb = imageLimit,
                presets = listOf(200, 500, 800, 1536, 3072),
                hazeState = hazeState,
                clearing = clearing,
                onLimitSelected = { preset ->
                    imageLimit = preset
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            NeteaseImageCache.setSizeLimitMb(context, preset)
                            TuneImages.applyDiskLimit(context)
                        }
                        refresh()
                    }
                },
                onClearClick = { pendingSingleClear = ClearCacheTarget.Images },
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            CacheCategoryRow(
                title = stringResource(R.string.storage_hires_title),
                usageBytes = data?.hires,
                fixedLimitLabel = stringResource(R.string.storage_fixed_limit),
                clearing = clearing,
                onClearClick = { pendingSingleClear = ClearCacheTarget.HiRes },
            )
        }

        TunePillButton(
            label = stringResource(R.string.storage_clear_all),
            variant = TunePillButtonVariant.Secondary,
            enabled = reclaimableBytes > 0L && !clearing,
            onClick = { confirmClearAll = true },
        )

        LabeledCard(label = stringResource(R.string.storage_section_wifi_cache)) {
            val statusSummary = when {
                wifiCacheIds.isEmpty() -> stringResource(R.string.storage_wifi_cache_empty)
                wifiCacheStatus.active -> stringResource(
                    R.string.storage_wifi_cache_active,
                    wifiCacheStatus.currentPlaylistName,
                    wifiCacheStatus.completed,
                    wifiCacheStatus.total,
                )
                !wifiCacheStatus.onUnmeteredWifi -> stringResource(
                    R.string.storage_wifi_cache_waiting,
                    wifiCacheIds.size,
                )
                else -> stringResource(R.string.storage_wifi_cache_idle, wifiCacheIds.size)
            }
            Text(
                text = statusSummary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (wifiCacheIds.isNotEmpty()) {
                wifiCacheIds.sorted().forEach { id ->
                    ActionListDivider(style = ActionListDividerStyle.FullWidth)
                    val title = neteasePlaylists.firstOrNull { it.id == id }?.name ?: id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable { NeteaseRepository.setWifiAutoCacheEnabled(context, id, false) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.storage_wifi_cache_remove),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                    }
                }
                ActionListDivider(style = ActionListDividerStyle.FullWidth)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable { NeteaseRepository.clearWifiAutoCache(context) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.storage_clear),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textMain,
                        modifier = Modifier.weight(1f),
                    )
                    MaterialSymbol(
                        symbol = MaterialSymbols.Delete,
                        contentDescription = null,
                        size = 20.dp,
                        tint = colors.textMuted,
                    )
                }
            }
        }

        LabeledCard(label = stringResource(R.string.storage_section_backup)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.backup_export_title,
                        leadingSymbol = MaterialSymbols.FileExport,
                        subtitle = stringResource(R.string.backup_export_subtitle),
                        onClick = onExportBackup,
                    ),
                    ActionListItem(
                        labelRes = R.string.backup_restore_title,
                        leadingSymbol = MaterialSymbols.FileImport,
                        subtitle = stringResource(R.string.backup_restore_subtitle),
                        onClick = { confirmRestoreBackup = true },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
            )
        }
    }

    if (confirmRestoreBackup) {
        TuneDialog(
            title = stringResource(R.string.backup_restore_confirm_title),
            description = stringResource(R.string.backup_restore_confirm_desc),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { confirmRestoreBackup = false },
            confirmLabel = stringResource(R.string.backup_restore_title),
            confirmVariant = TunePillButtonVariant.Destructive,
            onConfirm = {
                confirmRestoreBackup = false
                onRestoreBackup()
            },
        )
    }

    pendingSingleClear?.let { target ->
        val (titleRes, bytes) = when (target) {
            ClearCacheTarget.Stream -> R.string.storage_stream_title to (data?.stream ?: 0L)
            ClearCacheTarget.Prefetch -> R.string.storage_prefetch_title to (data?.prefetch ?: 0L)
            ClearCacheTarget.Images -> R.string.storage_image_title to (data?.images ?: 0L)
            ClearCacheTarget.HiRes -> R.string.storage_hires_title to (data?.hires ?: 0L)
        }
        val targetTitle = stringResource(titleRes)
        TuneDialog(
            title = stringResource(R.string.storage_clear_single_title, targetTitle),
            description = stringResource(R.string.storage_clear_single_desc, formatStorageSize(bytes)),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { pendingSingleClear = null },
            confirmLabel = stringResource(R.string.storage_clear),
            confirmVariant = TunePillButtonVariant.Destructive,
            onConfirm = {
                pendingSingleClear = null
                performSingleClear(target)
            },
        )
    }

    if (confirmClearAll) {
        TuneDialog(
            title = stringResource(R.string.storage_clear_all_title),
            description = stringResource(R.string.storage_clear_all_desc, formatStorageSize(reclaimableBytes)),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { confirmClearAll = false },
            confirmLabel = stringResource(R.string.storage_clear),
            confirmVariant = TunePillButtonVariant.Destructive,
            onConfirm = {
                confirmClearAll = false
                performClearAll()
            },
        )
    }
}

@Composable
private fun CacheCategoryRow(
    title: String,
    usageBytes: Long?,
    clearing: Boolean,
    onClearClick: () -> Unit,
    limitMb: Int = 0,
    presets: List<Int> = emptyList(),
    hazeState: HazeState? = null,
    onLimitSelected: (Int) -> Unit = {},
    fixedLimitLabel: String? = null,
) {
    val colors = LocalTuneColors.current
    val usedText = stringResource(
        R.string.storage_used_value,
        usageBytes?.let(::formatStorageSize) ?: "…",
    )
    val canClear = (usageBytes ?: 0L) > 0L && !clearing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = usedText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (fixedLimitLabel != null) {
            Text(
                text = fixedLimitLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            Selection(
                labelRes = null,
                options = presets.map { preset ->
                    SelectionOption(
                        value = preset,
                        label = formatStorageSize(preset.toLong() * 1024L * 1024L),
                    )
                },
                selectedValue = limitMb,
                onValueSelected = onLimitSelected,
                hazeState = hazeState,
            )
        }
        IconButton(
            onClick = onClearClick,
            enabled = canClear,
        ) {
            MaterialSymbol(
                symbol = MaterialSymbols.Delete,
                contentDescription = stringResource(R.string.storage_clear),
                size = 20.dp,
                tint = if (canClear) colors.textMuted else colors.textMuted.copy(alpha = 0.35f),
            )
        }
    }
}

private data class StorageSnapshot(
    val stream: Long,
    val prefetch: Long,
    val images: Long,
    val hires: Long,
    val downloads: Long,
    val freeBytes: Long,
    val totalBytes: Long,
)

private fun measureStorage(context: android.content.Context): StorageSnapshot {
    val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
    return StorageSnapshot(
        stream = NeteaseStreamProxy.usageBytes(),
        prefetch = RemoteAudioCache.usageBytes(context),
        images = NeteaseImageCache.usageBytes(context),
        hires = NeteaseHiResCoverStore.usageBytes(context),
        downloads = directorySize(File(context.filesDir, "netease-audio")),
        freeBytes = stat?.availableBytes ?: 0L,
        totalBytes = stat?.totalBytes ?: 0L,
    )
}

private fun directorySize(dir: File): Long =
    dir.listFiles()?.sumOf { file -> if (file.isDirectory) directorySize(file) else file.length() } ?: 0L

private fun formatStorageSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes.toDouble() / (1024L * 1024))
    else -> "%.0f KB".format(bytes.toDouble() / 1024)
}
