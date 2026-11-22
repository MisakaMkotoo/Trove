package com.exodidio.tune.ui.screens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseImageCache
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.NeteaseStreamProxy
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneTextField
import com.exodidio.tune.ui.components.TuneTextFieldSize
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NeteasePlaylistsContent(
    modifier: Modifier = Modifier,
    onOpenPlaylist: (String) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by NeteaseRepository.playlists.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var cacheSizeMb by remember { mutableStateOf(NeteaseStreamProxy.sizeLimitMb(context)) }
    // 用量要遍历缓存目录/读 Media3 索引：一律放到 IO，组合期先显示占位。
    var cacheUsage by remember { mutableStateOf<Long?>(null) }
    var coverCacheSizeMb by remember { mutableStateOf(NeteaseImageCache.sizeLimitMb(context)) }
    var coverCacheUsage by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        val usage = withContext(Dispatchers.IO) {
            NeteaseStreamProxy.usageBytes() to NeteaseImageCache.usageBytes(context)
        }
        cacheUsage = usage.first
        coverCacheUsage = usage.second
    }

    fun importPlaylist() {
        if (busy || link.isBlank()) return
        busy = true
        status = ""
        scope.launch {
            val result = NeteaseRepository.import(context, link)
            status = result.fold(
                onSuccess = { context.getString(R.string.netease_playlist_imported) },
                onFailure = { it.message ?: context.getString(R.string.netease_import_failed) },
            )
            if (result.isSuccess) link = ""
            busy = false
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(
            symbol = MaterialSymbols.MusicNote,
            title = stringResource(R.string.netease_playlists_title),
            description = stringResource(R.string.netease_playlists_desc),
        )
        Card {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TuneTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = stringResource(R.string.netease_playlist_link_hint),
                    size = TuneTextFieldSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(horizontal = 12.dp).size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = ::importPlaylist, enabled = link.isNotBlank()) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Add,
                            contentDescription = stringResource(R.string.netease_import_action),
                            size = 22.dp,
                            tint = colors.primary,
                        )
                    }
                }
            }
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        Card {
            Text(
                text = stringResource(R.string.netease_cache_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textMain,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
            Text(
                text = stringResource(
                    R.string.netease_cache_usage,
                    cacheUsage?.let(::formatCacheSize) ?: "…",
                    formatCacheSize(cacheSizeMb.toLong() * 1024 * 1024),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            listOf(200, 500, 800, 1536, 3072).forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            cacheSizeMb = preset
                            scope.launch {
                                cacheUsage = withContext(Dispatchers.IO) {
                                    // 改上限会释放并重建 SimpleCache，必须在 IO 上做。
                                    NeteaseStreamProxy.setSizeLimitMb(context, preset)
                                    NeteaseStreamProxy.usageBytes()
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatCacheSize(preset.toLong() * 1024 * 1024),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMain,
                        modifier = Modifier.weight(1f),
                    )
                    if (preset == cacheSizeMb) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Check,
                            contentDescription = stringResource(R.string.netease_cache_current),
                            size = 18.dp,
                            tint = colors.primary,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            cacheUsage = withContext(Dispatchers.IO) {
                                NeteaseStreamProxy.clearCache()
                                NeteaseStreamProxy.usageBytes()
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.netease_cache_clear),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMain,
                    modifier = Modifier.weight(1f),
                )
                MaterialSymbol(
                    symbol = MaterialSymbols.Delete,
                    contentDescription = null,
                    size = 18.dp,
                    tint = colors.textMuted,
                )
            }
        }
        Card {
            Text(
                text = stringResource(R.string.netease_cover_cache_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textMain,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
            Text(
                text = stringResource(
                    R.string.netease_cache_usage,
                    coverCacheUsage?.let(::formatCacheSize) ?: "…",
                    formatCacheSize(coverCacheSizeMb.toLong() * 1024 * 1024),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            listOf(200, 500, 800, 1536, 3072).forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            coverCacheSizeMb = preset
                            scope.launch {
                                coverCacheUsage = withContext(Dispatchers.IO) {
                                    // 改上限要重建 Coil 磁盘缓存并裁剪目录，全部在 IO 上做。
                                    NeteaseImageCache.setSizeLimitMb(context, preset)
                                    com.exodidio.tune.ui.components.TuneImages.applyDiskLimit(context)
                                    NeteaseImageCache.usageBytes(context)
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatCacheSize(preset.toLong() * 1024 * 1024),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMain,
                        modifier = Modifier.weight(1f),
                    )
                    if (preset == coverCacheSizeMb) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Check,
                            contentDescription = stringResource(R.string.netease_cache_current),
                            size = 18.dp,
                            tint = colors.primary,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            coverCacheUsage = withContext(Dispatchers.IO) {
                                NeteaseImageCache.clear(context)
                                com.exodidio.tune.ui.components.TuneImages.clearDisk()
                                NeteaseImageCache.usageBytes(context)
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.netease_cache_clear),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMain,
                    modifier = Modifier.weight(1f),
                )
                MaterialSymbol(
                    symbol = MaterialSymbols.Delete,
                    contentDescription = null,
                    size = 18.dp,
                    tint = colors.textMuted,
                )
            }
        }
        val visiblePlaylists = remember(playlists) {
            playlists.filter { it.dynamicKind == null && !(it.persistCache && !it.addedToPlaylists) }
        }
        if (visiblePlaylists.isEmpty()) {
            Text(
                text = stringResource(R.string.netease_playlists_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Card {
                visiblePlaylists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                NeteaseRepository.select(playlist.id)
                                onOpenPlaylist(playlist.id)
                            }
                            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.netease_playlist_entry_count, playlist.entries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    NeteaseRepository.refresh(context, playlist.id)
                                }
                            },
                        ) {
                            MaterialSymbol(
                                symbol = MaterialSymbols.Refresh,
                                contentDescription = stringResource(R.string.netease_refresh),
                                size = 20.dp,
                                tint = colors.textMuted,
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch { NeteaseRepository.delete(playlist.id) }
                            },
                        ) {
                            MaterialSymbol(
                                symbol = MaterialSymbols.Delete,
                                contentDescription = stringResource(R.string.netease_delete),
                                size = 20.dp,
                                tint = colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes.toDouble() / (1024L * 1024))
    else -> "%.0f KB".format(bytes.toDouble() / 1024)
}
