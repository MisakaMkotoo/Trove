package com.exodidio.tune.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.netease.NeteaseClient
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.TuneRemoteImage
import com.exodidio.tune.ui.components.rememberArtworkThumbnail
import com.exodidio.tune.ui.theme.LocalTuneColors

/** “我的”一级页：资料头 + 自建/收藏歌单 + 关注歌手 + 最近播放（本地）。 */
@Composable
internal fun MineContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    rowStates: NeteaseRowStates = remember { NeteaseRowStates() },
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onOpenPlaylist: (DiscoverPlaylist) -> Unit = {},
    onOpenCreatedPlaylists: () -> Unit = {},
    onOpenSavedPlaylists: () -> Unit = {},
    onOpenArtists: () -> Unit = {},
    onOpenArtist: (MineArtist) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val account by NeteaseRepository.accountInfo.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            NeteaseRepository.loadAccountInfo()
            NeteaseRepository.loadMine()
        }
    }

    val recentTracks by NeteaseRepository.recentTracks.collectAsStateWithLifecycle()
    val downloadedSongs = rememberNeteaseDownloadedTracks()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item("mine_title") {
            Text(
                text = stringResource(R.string.destination_mine),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 34.sp,
                    lineHeight = 41.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = colors.textMain,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            )
        }
        if (!loggedIn) {
            item("mine_login") {
                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.Person,
                        title = stringResource(R.string.netease_playlists_title),
                        description = stringResource(R.string.netease_home_login_hint),
                        modifier = Modifier.clickable(onClick = onOpenAccount),
                        bottomContent = {
                            Text(
                                text = stringResource(R.string.netease_login_go),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.primary,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                            )
                        },
                    )
                }
            }
            return@LazyColumn
        }
        item("mine_profile") {
            MineProfileHeader(
                account = account,
                onOpenAccount = onOpenAccount,
            )
        }
        if (!mine.loaded) {
            item("mine_skeleton_created") { NeteasePlaylistRowSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp) }
            item("mine_skeleton_saved") { NeteasePlaylistRowSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp) }
            item("mine_skeleton_recent") { NeteaseSongGridSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp) }
        } else {
            if (mine.error != null && mine.created.isEmpty() && mine.saved.isEmpty() && mine.artists.isEmpty()) {
                item("mine_error") {
                    Text(
                        text = stringResource(R.string.discover_error, mine.error.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            item("mine_created") {
                NeteasePlaylistCardRow(
                    title = stringResource(R.string.mine_created_title),
                    playlists = mine.created.take(10),
                    fallbackSubtitle = stringResource(R.string.netease_playlists_title),
                    onOpen = onOpenPlaylist,
                    horizontalPadding = 20.dp,
                    titleTopPadding = 0.dp,
                    horizontalState = rowStates.state("mine_created"),
                    onTitleClick = onOpenCreatedPlaylists,
                )
            }
            item("mine_saved") {
                NeteasePlaylistCardRow(
                    title = stringResource(R.string.mine_saved_title),
                    playlists = mine.saved.take(10),
                    fallbackSubtitle = stringResource(R.string.netease_playlists_title),
                    onOpen = onOpenPlaylist,
                    horizontalPadding = 20.dp,
                    titleTopPadding = 0.dp,
                    horizontalState = rowStates.state("mine_saved"),
                    onTitleClick = onOpenSavedPlaylists,
                )
            }
            if (mine.artists.isNotEmpty()) {
                item("mine_artists") {
                    MineArtistRow(
                        artists = mine.artists.take(10),
                        rowState = rowStates.state("mine_artists"),
                        onOpen = onOpenArtist,
                        onMore = onOpenArtists,
                    )
                }
            }
            if (recentTracks.isNotEmpty()) {
                item("mine_recent") {
                    NeteaseSongGridSection(
                        title = stringResource(R.string.mine_recent_section),
                        songs = recentTracks.take(18),
                        playbackQueue = playbackQueue,
                        onPlayTracks = onPlayTracks,
                        onTrackPlayNext = onTrackPlayNext,
                        onTrackAddToQueue = onTrackAddToQueue,
                        onTrackFavoriteToggle = onTrackFavoriteToggle,
                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                        horizontalPadding = 20.dp,
                        titleTopPadding = 0.dp,
                        horizontalState = rowStates.gridState("mine_recent"),
                    )
                }
            }
        }
        if (downloadedSongs.isNotEmpty()) {
            item("mine_downloads") {
                NeteaseSongGridSection(
                    title = stringResource(R.string.mine_downloads_title),
                    songs = downloadedSongs.take(100),
                    playbackQueue = playbackQueue,
                    onPlayTracks = onPlayTracks,
                    onTrackPlayNext = onTrackPlayNext,
                    onTrackAddToQueue = onTrackAddToQueue,
                    onTrackFavoriteToggle = onTrackFavoriteToggle,
                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                    horizontalPadding = 20.dp,
                    titleTopPadding = 0.dp,
                    horizontalState = rowStates.gridState("mine_downloads"),
                    onTitleClick = onOpenDownloads,
                )
            }
        }
    }
}

@Composable
private fun MineProfileHeader(account: NeteaseClient.AccountInfo?, onOpenAccount: () -> Unit) {
    val colors = LocalTuneColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenAccount)
            .padding(start = 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneRemoteImage(
            url = account?.avatarUrl,
            sizePx = 300,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, CircleShape),
            fallback = {
                MaterialSymbol(symbol = MaterialSymbols.Person, contentDescription = null, size = 40.dp, tint = colors.textMuted)
            },
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.nickname.orEmpty().ifBlank { "—" },
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val signature = account?.signature.orEmpty()
            if (signature.isNotBlank()) {
                Text(
                    text = signature,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val gender = when (account?.gender) {
                1 -> stringResource(R.string.account_gender_male)
                2 -> stringResource(R.string.account_gender_female)
                else -> null
            }
            if (gender != null) {
                Text(
                    text = gender,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MineArtistRow(
    artists: List<MineArtist>,
    rowState: LazyListState,
    onOpen: (MineArtist) -> Unit,
    onMore: () -> Unit,
) {
    val colors = LocalTuneColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.mine_artists_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                color = colors.textMain,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.mine_more),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primary,
                modifier = Modifier.clickable(onClick = onMore).padding(start = 12.dp),
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = rowState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items = artists, key = { it.id }) { artist ->
                Column(
                    modifier = Modifier.width(88.dp).clickable { onOpen(artist) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TuneRemoteImage(
                        url = artist.coverUrl,
                        sizePx = 300,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(colors.glassElevated)
                            .border(0.5.dp, colors.borderGlass, CircleShape),
                        fallback = {
                            MaterialSymbol(symbol = MaterialSymbols.Person, contentDescription = null, size = 36.dp, tint = colors.textMuted)
                        },
                    )
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** “自建歌单 / 收藏歌单”全部二级页。 */
@Composable
internal fun MinePlaylistsContent(
    saved: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    onOpenPlaylist: (DiscoverPlaylist) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val playlists = if (saved) mine.saved else mine.created
    LaunchedEffect(Unit) { NeteaseRepository.loadMine() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        items(items = playlists, key = { it.id }) { playlist ->
            MinePlaylistRow(playlist = playlist, onClick = { onOpenPlaylist(playlist) })
            HorizontalDivider(
                color = colors.borderGlass,
                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun MinePlaylistRow(playlist: DiscoverPlaylist, onClick: () -> Unit) {
    val colors = LocalTuneColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneRemoteImage(
            url = playlist.coverUrl,
            sizePx = 200,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(4.dp)),
            fallback = {
                MaterialSymbol(symbol = MaterialSymbols.QueueMusic, contentDescription = null, size = 22.dp, tint = colors.textMuted)
            },
        )
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (playlist.trackCount > 0) {
                Text(
                    text = stringResource(R.string.netease_playlist_entry_count, playlist.trackCount),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = colors.textMuted,
                )
            }
        }
    }
}

/** “关注歌手”全部二级页。 */
@Composable
internal fun MineArtistsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    onOpenArtist: (MineArtist) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { NeteaseRepository.loadMine() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        items(items = mine.artists, key = { it.id }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenArtist(artist) }
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TuneRemoteImage(
                    url = artist.coverUrl,
                    sizePx = 200,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.glassElevated)
                        .border(0.5.dp, colors.borderGlass, CircleShape),
                    fallback = {
                        MaterialSymbol(symbol = MaterialSymbols.Person, contentDescription = null, size = 22.dp, tint = colors.textMuted)
                    },
                )
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
            }
            HorizontalDivider(
                color = colors.borderGlass,
                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
            )
        }
    }
}
