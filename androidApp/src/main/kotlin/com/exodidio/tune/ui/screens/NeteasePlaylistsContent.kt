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
import com.exodidio.tune.netease.NeteaseRepository
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

