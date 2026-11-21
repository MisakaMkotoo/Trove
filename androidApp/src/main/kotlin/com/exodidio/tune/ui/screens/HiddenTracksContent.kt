package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.trackDisplayArtists
import com.exodidio.tune.sync.trackDisplayTitle
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.launch

/** 设置里的“已隐藏歌曲”：唯一能看到隐藏曲目的地方，可在此恢复。 */
@Composable
internal fun HiddenTracksContent(
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val store = AndroidSyncRuntime.syncStore
    val tracks by store.hiddenTracks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(
            symbol = MaterialSymbols.FilterList,
            title = stringResource(R.string.hidden_tracks_title),
            description = stringResource(R.string.hidden_tracks_desc),
        )
        if (tracks.isEmpty()) {
            Text(
                text = stringResource(R.string.hidden_tracks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Card {
                tracks.forEach { track ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = trackDisplayTitle(track),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = trackDisplayArtists(track),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { scope.launch { store.setHidden(track.id, false) } },
                        ) {
                            MaterialSymbol(
                                symbol = MaterialSymbols.Add,
                                contentDescription = stringResource(R.string.hidden_tracks_unhide),
                                size = 20.dp,
                                tint = colors.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
