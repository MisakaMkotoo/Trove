package com.exodidio.tune.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.exodidio.tune.R
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneRadii
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PlaylistRow(
    playlistId: String,
    name: String,
    artworkPaths: List<String>,
    modifier: Modifier = Modifier,
    artworkUrl: String? = null,
    syncFailed: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val colors = LocalTuneColors.current
    Row(
        modifier = modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick).padding(start = 24.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistArtwork(playlistId, artworkPaths, artworkUrl = artworkUrl)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (syncFailed) {
                Text(
                    text = stringResource(R.string.playlist_sync_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            MaterialSymbol(
                symbol = MaterialSymbols.ChevronRight,
                contentDescription = stringResource(R.string.playlist_row_open),
                size = 20.dp,
                tint = colors.textMuted,
            )
        }
    }
}

@Composable
internal fun PlaylistArtwork(
    playlistId: String,
    artworkPaths: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 110.dp,
    artworkUrl: String? = null,
) {
    val colors = LocalTuneColors.current
    // 本地文件可能已被清理/迁移：先按传入路径乐观渲染，再在 IO 线程校验一次，避免组合期打磁盘。
    var validPaths by remember(artworkPaths) {
        mutableStateOf(artworkPaths.filter { it.isNotBlank() })
    }
    LaunchedEffect(artworkPaths) {
        validPaths = withContext(Dispatchers.IO) {
            artworkPaths.filter { it.isNotBlank() && File(it).isFile }
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(TuneRadii.LargeArtwork))
            .background(colors.glassElevated)
            .border(0.5.dp, colors.borderGlass, RoundedCornerShape(TuneRadii.LargeArtwork))
            .then(if (validPaths.size >= 4) Modifier.testTag("playlist-artwork-mosaic") else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val targetPx = if (size > 110.dp) 480 else 128
        when {
            validPaths.size >= 4 -> {
                Column(Modifier.fillMaxSize()) {
                    PlaylistArtworkPair(validPaths.take(2), targetPx)
                    PlaylistArtworkPair(validPaths.drop(2).take(2), targetPx)
                }
            }
            validPaths.isNotEmpty() -> PlaylistArtworkImage(validPaths.first(), Modifier.fillMaxSize(), targetPx)
            !artworkUrl.isNullOrBlank() -> TuneRemoteImage(
                url = artworkUrl,
                sizePx = if (size > 110.dp) 800 else 400,
                modifier = Modifier.fillMaxSize(),
                fallback = {
                    MaterialSymbol(
                        symbol = if (playlistId == "favorites") MaterialSymbols.Favorite else MaterialSymbols.QueueMusic,
                        contentDescription = null,
                        size = 30.dp,
                        tint = colors.textMuted,
                        filled = playlistId == "favorites",
                    )
                },
            )
            else -> MaterialSymbol(
                symbol = if (playlistId == "favorites") MaterialSymbols.Favorite else MaterialSymbols.QueueMusic,
                contentDescription = null,
                size = 30.dp,
                tint = colors.textMuted,
                filled = playlistId == "favorites",
            )
        }
    }
}

@Composable
private fun ColumnScope.PlaylistArtworkPair(paths: List<String>, targetPx: Int) {
    Row(Modifier.fillMaxWidth().weight(1f)) {
        paths.forEach { path -> PlaylistArtworkImage(path, Modifier.weight(1f).fillMaxSize(), targetPx) }
    }
}

@Composable
private fun PlaylistArtworkImage(path: String, modifier: Modifier, targetPx: Int) {
    val bitmap = rememberArtworkThumbnail(path, targetPx = targetPx)
    if (bitmap != null) Image(bitmap, null, modifier = modifier, contentScale = ContentScale.Crop)
}
