package com.exodidio.tune.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneRemoteImage
import com.exodidio.tune.ui.theme.LocalTuneColors

/** “更多推荐歌单”二级页：合并每日推荐歌单与精选推荐歌单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NeteaseRecommendedPlaylistsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    onOpenPlaylist: (DiscoverPlaylist) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val state by NeteaseRepository.recommendedPlaylists.collectAsStateWithLifecycle()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    var contextPlaylistId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(loggedIn) {
        NeteaseRepository.loadRecommendedPlaylistsPage()
        if (loggedIn) NeteaseRepository.loadMine()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item(key = "rec_playlists_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.netease_recommended_playlists_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textMain,
                )
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { NeteaseRepository.loadRecommendedPlaylistsPage(force = true) }) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Refresh,
                            contentDescription = stringResource(R.string.netease_refresh),
                            size = 20.dp,
                            tint = colors.textMuted,
                        )
                    }
                }
            }
        }
        if (state.loading && state.items.isEmpty()) {
            item(key = "rec_playlists_loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            }
        } else if (state.items.isEmpty()) {
            item(key = "rec_playlists_empty") {
                Text(
                    text = state.error?.let { stringResource(R.string.discover_error, it) }
                        ?: stringResource(R.string.netease_recommended_playlists_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        } else {
            items(items = state.items, key = { it.id }) { playlist ->
                NeteasePlaylistCardContextMenu(
                    playlist = playlist,
                    expanded = contextPlaylistId == playlist.id,
                    onDismiss = { if (contextPlaylistId == playlist.id) contextPlaylistId = null },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpenPlaylist(playlist) },
                                onLongClick = { contextPlaylistId = playlist.id },
                            )
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TuneRemoteImage(
                            url = playlist.coverUrl,
                            sizePx = 300,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.glassElevated)
                                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(6.dp)),
                            fallback = {
                                MaterialSymbol(
                                    symbol = MaterialSymbols.QueueMusic,
                                    contentDescription = null,
                                    size = 24.dp,
                                    tint = colors.textMuted,
                                )
                            },
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 14.dp),
                        ) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                color = colors.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sub = playlist.subtitle.ifBlank {
                                if (playlist.trackCount > 0) {
                                    stringResource(R.string.netease_playlist_entry_count, playlist.trackCount)
                                } else {
                                    stringResource(R.string.discover_recommended_subtitle)
                                }
                            }
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(
                    color = colors.borderGlass,
                    modifier = Modifier.padding(start = 82.dp, end = 16.dp),
                )
            }
        }
    }
}
