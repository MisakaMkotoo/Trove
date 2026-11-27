package com.exodidio.tune.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.MineAlbum
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextArtist
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TuneSegmentedTabBar
import com.exodidio.tune.ui.components.artworkBorder
import com.exodidio.tune.ui.components.rememberArtworkThumbnail
import com.exodidio.tune.ui.theme.LocalTuneColors

private val LibraryHorizontalPadding = 20.dp
private val CategoryRowHeight = 44.dp
private val RecentTileRadius = 6.dp
private val RecentColumnsGap = 12.dp
private val RecentMinTileWidth = 160.dp
private const val RecentGridGap = 12

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    recentTracks: List<LibraryTrack> = emptyList(),
    listState: LazyListState = rememberLazyListState(),
    onTrackClick: ((String) -> Unit)? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackAlbumClick: (LibraryTrack) -> Unit = {},
    onTrackArtistClick: (TrackContextArtist) -> Unit = {},
    onArtistsSelected: (() -> Unit)? = null,
    onAlbumsSelected: (() -> Unit)? = null,
    onTracksSelected: (() -> Unit)? = null,
    onFoldersSelected: (() -> Unit)? = null,
    onGenresSelected: (() -> Unit)? = null,
    onComposersSelected: (() -> Unit)? = null,
    onPlaylistsSelected: (() -> Unit)? = null,
    showGenres: Boolean = false,
    showComposers: Boolean = false,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    mergedMode: Boolean = false,
    selectedMergedTab: Int = 0,
    onMergedTabSelected: (Int) -> Unit = {},
    onCloudDiskSelected: (() -> Unit)? = null,
    onDownloadsSelected: (() -> Unit)? = null,
    rowStates: NeteaseRowStates = remember { NeteaseRowStates() },
    onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenNeteasePlaylist: (DiscoverPlaylist) -> Unit = {},
    onOpenCreatedPlaylists: () -> Unit = {},
    onOpenSavedPlaylists: () -> Unit = {},
    onOpenSavedAlbums: () -> Unit = {},
    onOpenNeteaseAlbum: (MineAlbum) -> Unit = {},
    onOpenArtists: () -> Unit = {},
    onOpenNeteaseArtist: (MineArtist) -> Unit = {},
    onOpenPlayRecords: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
) {
    val colors = LocalTuneColors.current
    var contextTrack by remember { mutableStateOf<LibraryTrack?>(null) }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val recentColumns = remember(screenWidthDp) {
        val available = screenWidthDp - (LibraryHorizontalPadding.value * 2)
        ((available + RecentGridGap) / (RecentMinTileWidth.value + RecentGridGap))
            .toInt()
            .coerceAtLeast(2)
    }

    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val account by NeteaseRepository.accountInfo.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val playRecords by NeteaseRepository.playRecords.collectAsStateWithLifecycle()
    LaunchedEffect(mergedMode, loggedIn) {
        if (mergedMode && loggedIn) {
            NeteaseRepository.loadAccountInfo()
            NeteaseRepository.loadMine()
            NeteaseRepository.loadPlayRecords()
        }
    }
    val neteaseRecentTracks by NeteaseRepository.recentTracks.collectAsStateWithLifecycle()
    val cloudRecordSongs = remember(playRecords.recent, playRecords.weekRank, playRecords.allRank) {
        val source = playRecords.recent.ifEmpty {
            playRecords.weekRank.ifEmpty { playRecords.allRank }
        }
        source.take(18).map { it.track }
    }
    val mergedHistorySongs = remember(neteaseRecentTracks, cloudRecordSongs) {
        (neteaseRecentTracks + cloudRecordSongs).distinctBy { it.id }.take(18)
    }
    val downloadedSongs = if (mergedMode) rememberNeteaseDownloadedTracks() else emptyList()
    var showCreateNeteasePlaylistSheet by remember { mutableStateOf(false) }
    var editingNeteasePlaylist by remember { mutableStateOf<DiscoverPlaylist?>(null) }
    var deletingNeteasePlaylist by remember { mutableStateOf<DiscoverPlaylist?>(null) }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item(key = "library-title") {
            Text(
                text = stringResource(R.string.destination_library),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 34.sp,
                    lineHeight = 41.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = colors.textMain,
                modifier = Modifier.padding(
                    start = LibraryHorizontalPadding,
                    end = LibraryHorizontalPadding,
                    top = 8.dp,
                    bottom = 12.dp,
                ),
            )
        }
        item(key = "library-categories") {
            Column(modifier = Modifier.padding(horizontal = LibraryHorizontalPadding)) {
                CategoryRow(R.string.library_playlists, MaterialSymbols.QueueMusic, onPlaylistsSelected)
                CategoryDivider()
                CategoryRow(R.string.library_artists, MaterialSymbols.People, onArtistsSelected)
                CategoryDivider()
                CategoryRow(R.string.library_albums, MaterialSymbols.Album, onAlbumsSelected)
                CategoryDivider()
                CategoryRow(R.string.library_tracks, MaterialSymbols.MusicNote, onTracksSelected)
                CategoryDivider()
                CategoryRow(R.string.library_folders, MaterialSymbols.Folder, onFoldersSelected)
                if (showGenres) {
                    CategoryDivider()
                    CategoryRow(R.string.library_genres, MaterialSymbols.Label, onGenresSelected)
                }
                if (showComposers) {
                    CategoryDivider()
                    CategoryRow(R.string.library_composers, MaterialSymbols.StylusFountainPen, onComposersSelected)
                }
                if (mergedMode) {
                    CategoryDivider()
                    CategoryRow(R.string.mine_cloud_disk_title, MaterialSymbols.Sync, onCloudDiskSelected)
                    CategoryDivider()
                    CategoryRow(R.string.mine_downloads_title, MaterialSymbols.ArrowDownward, onDownloadsSelected)
                }
                CategoryDivider()
            }
        }
        if (mergedMode) {
            item(key = "library-merged-tabs") {
                TuneSegmentedTabBar(
                    tabs = listOf(
                        stringResource(R.string.search_tab_netease),
                        stringResource(R.string.library_tab_local),
                    ),
                    selectedIndex = selectedMergedTab,
                    onSelect = onMergedTabSelected,
                    modifier = Modifier.padding(
                        start = LibraryHorizontalPadding,
                        end = LibraryHorizontalPadding,
                        top = 20.dp,
                    ),
                    tabTestTagPrefix = "library-merged-root-tab",
                )
            }
        }
        if (mergedMode && selectedMergedTab == 0) {
            mineNeteaseSections(
                loggedIn = loggedIn,
                account = account,
                mine = mine,
                mergedHistorySongs = mergedHistorySongs,
                downloadedSongs = downloadedSongs,
                includeProfileHeader = false,
                sectionTopPadding = 28.dp,
                rowStates = rowStates,
                playbackQueue = playbackQueue,
                onPlayTracks = onPlayTracks,
                onTrackPlayNext = onTrackPlayNext,
                onTrackAddToQueue = onTrackAddToQueue,
                onTrackFavoriteToggle = onTrackFavoriteToggle,
                onTrackContextBottomSheet = onTrackContextBottomSheet,
                onOpenPlaylist = onOpenNeteasePlaylist,
                onOpenCreatedPlaylists = onOpenCreatedPlaylists,
                onOpenSavedPlaylists = onOpenSavedPlaylists,
                onOpenSavedAlbums = onOpenSavedAlbums,
                onOpenAlbum = onOpenNeteaseAlbum,
                onOpenArtists = onOpenArtists,
                onOpenArtist = onOpenNeteaseArtist,
                onOpenPlayRecords = onOpenPlayRecords,
                onOpenCloudDisk = { onCloudDiskSelected?.invoke() },
                onOpenDownloads = { onDownloadsSelected?.invoke() },
                onOpenAccount = onOpenAccount,
                onShowCreatePlaylist = { showCreateNeteasePlaylistSheet = true },
                onEditPlaylist = { editingNeteasePlaylist = it },
                onDeletePlaylist = { deletingNeteasePlaylist = it },
            )
        } else {
            if (recentTracks.isNotEmpty()) {
                item(key = "recently-added-title") {
                    Text(
                        text = stringResource(R.string.library_recently_added),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.textMain,
                        modifier = Modifier.padding(
                            start = LibraryHorizontalPadding,
                            end = LibraryHorizontalPadding,
                            top = 28.dp,
                            bottom = 12.dp,
                        ),
                    )
                }
                recentTracks.chunked(recentColumns).forEach { rowTracks ->
                    item(key = "recent-row-${rowTracks.first().id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = LibraryHorizontalPadding, end = LibraryHorizontalPadding, bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(RecentColumnsGap),
                        ) {
                            rowTracks.forEach { track ->
                                TrackContextMenu(
                                    track = track,
                                    expanded = contextTrack?.id == track.id,
                                    onDismiss = { if (contextTrack?.id == track.id) contextTrack = null },
                                    modifier = Modifier.weight(1f),
                                    playbackQueue = playbackQueue,
                                    onPlayNext = onTrackPlayNext,
                                    onAddToQueue = onTrackAddToQueue,
                                    onFavoriteChange = onTrackFavoriteToggle,
                                    onGoToAlbum = onTrackAlbumClick,
                                    onGoToArtist = onTrackArtistClick,
                                    onBottomSheetRequested = onTrackContextBottomSheet,
                                ) {
                                    RecentTrackTile(
                                        track = track,
                                        onClick = { onTrackClick?.invoke(track.id) },
                                        onLongClick = { contextTrack = track },
                                    )
                                }
                            }
                            repeat(recentColumns - rowTracks.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else if (mergedMode) {
                item(key = "library-recent-empty") {
                    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                        HeroCard(
                            symbol = MaterialSymbols.MusicNote,
                            title = stringResource(R.string.library_recent_empty_title),
                            description = stringResource(R.string.library_recent_empty_description),
                        )
                    }
                }
            }
        }
    }

    if (mergedMode) {
        MinePlaylistManageOverlays(
            showCreateSheet = showCreateNeteasePlaylistSheet,
            onDismissCreate = { showCreateNeteasePlaylistSheet = false },
            editingPlaylist = editingNeteasePlaylist,
            onDismissEdit = { editingNeteasePlaylist = null },
            deletingPlaylist = deletingNeteasePlaylist,
            onDismissDelete = { deletingNeteasePlaylist = null },
        )
    }
}

@Composable
private fun CategoryRow(labelRes: Int, symbol: String, onClick: (() -> Unit)?) {
    val colors = LocalTuneColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CategoryRowHeight)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick, role = Role.Button)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialSymbol(symbol = symbol, contentDescription = null, size = 24.dp, tint = colors.textMain)
        Spacer(modifier = Modifier.width(21.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
            color = colors.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryDivider() {
    val colors = LocalTuneColors.current
    HorizontalDivider(
        modifier = Modifier.padding(start = 45.dp),
        thickness = 0.5.dp,
        color = colors.borderGlass,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentTrackTile(
    track: LibraryTrack,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val bitmap = rememberArtworkThumbnail(track.artworkPath, targetPx = 320)
    Column(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            role = Role.Button,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(RecentTileRadius))
                .background(colors.glassElevated)
                .artworkBorder(colors, RoundedCornerShape(RecentTileRadius)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                MaterialSymbol(
                    symbol = MaterialSymbols.MusicNote,
                    contentDescription = null,
                    size = 32.dp,
                    tint = colors.textMuted,
                )
            }
        }
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
            color = colors.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            text = track.artists,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
