package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.theme.LocalTuneColors

/** 以一首歌为基准的相似歌曲列表：点击整单播放，长按沿用歌曲菜单。 */
@Composable
internal fun SimilarSongsContent(
    songId: String?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
) {
    val context = LocalContext.current
    val colors = LocalTuneColors.current
    var tracks by remember { mutableStateOf<List<LibraryTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var contextTrack by remember { mutableStateOf<LibraryTrack?>(null) }

    LaunchedEffect(songId) {
        val id = songId ?: return@LaunchedEffect
        loading = true
        error = null
        NeteaseRepository.similarSongs(context, id).fold(
            onSuccess = { result ->
                tracks = result
                if (result.isEmpty()) error = context.getString(R.string.similar_songs_empty)
            },
            onFailure = { failure ->
                tracks = emptyList()
                error = failure.message ?: context.getString(R.string.similar_songs_error)
            },
        )
        loading = false
    }

    if (loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (tracks.isEmpty()) {
        Text(
            text = error ?: stringResource(R.string.similar_songs_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            modifier = modifier.padding(horizontal = 24.dp),
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackContextMenu(
                track = track,
                expanded = contextTrack?.id == track.id,
                onDismiss = { if (contextTrack?.id == track.id) contextTrack = null },
                playbackQueue = playbackQueue,
                onPlayNext = { item -> AndroidPlaybackRuntime.controller().playNext(item.id) },
                onAddToQueue = { item -> AndroidPlaybackRuntime.controller().append(listOf(item.id)) },
                onFavoriteChange = onTrackFavoriteToggle,
                onBottomSheetRequested = onTrackContextBottomSheet,
                actions = TrackContextMenuActions(
                    favorite = true,
                    neteaseLike = true,
                    allowNeteasePlaylists = true,
                    addToPlaylist = true,
                    goToAlbum = true,
                    goToArtists = true,
                    hide = false,
                ),
            ) {
                Column {
                    TrackRow(
                        title = track.title,
                        artist = track.artists,
                        artworkPath = track.artworkPath,
                        artworkUrl = NeteaseRepository.coverUrlForTrack(track),
                        contentPadding = PaddingValues(start = 24.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                        onClick = {
                            AndroidPlaybackRuntime.controller().play(
                                PlaybackRequest(tracks.map { it.id }, index),
                            )
                        },
                        onMoreClick = { contextTrack = track },
                        onLongClick = { contextTrack = track },
                    )
                    if (index < tracks.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = colors.borderGlass,
                        )
                    }
                }
            }
        }
    }
}
