package com.exodidio.tune.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.MineAlbum
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.netease.MineData
import com.exodidio.tune.netease.NeteaseClient
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.DiscCard
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LibraryTextFilterSlot
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.rememberPullDownFilterState
import com.exodidio.tune.ui.components.TuneDialog
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.TuneRemoteImage
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.launch

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
    onOpenSavedAlbums: () -> Unit = {},
    onOpenAlbum: (MineAlbum) -> Unit = {},
    onOpenArtists: () -> Unit = {},
    onOpenArtist: (MineArtist) -> Unit = {},
    onOpenPlayRecords: () -> Unit = {},
    onOpenCloudDisk: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val account by NeteaseRepository.accountInfo.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val playRecords by NeteaseRepository.playRecords.collectAsStateWithLifecycle()
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            NeteaseRepository.loadAccountInfo()
            NeteaseRepository.loadMine()
            NeteaseRepository.loadPlayRecords()
        }
    }

    val recentTracks by NeteaseRepository.recentTracks.collectAsStateWithLifecycle()
    val cloudRecordSongs = remember(playRecords.recent, playRecords.weekRank, playRecords.allRank) {
        val source = playRecords.recent.ifEmpty {
            playRecords.weekRank.ifEmpty { playRecords.allRank }
        }
        source.take(18).map { it.track }
    }
    val mergedHistorySongs = remember(recentTracks, cloudRecordSongs) {
        (recentTracks + cloudRecordSongs).distinctBy { it.id }.take(18)
    }
    val downloadedSongs = rememberNeteaseDownloadedTracks()
    var showCreateSheet by remember { mutableStateOf(false) }
    var editingPlaylist by remember { mutableStateOf<DiscoverPlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<DiscoverPlaylist?>(null) }

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
        mineNeteaseSections(
            loggedIn = loggedIn,
            account = account,
            mine = mine,
            mergedHistorySongs = mergedHistorySongs,
            downloadedSongs = downloadedSongs,
            includeProfileHeader = true,
            sectionTopPadding = 0.dp,
            rowStates = rowStates,
            playbackQueue = playbackQueue,
            onPlayTracks = onPlayTracks,
            onTrackPlayNext = onTrackPlayNext,
            onTrackAddToQueue = onTrackAddToQueue,
            onTrackFavoriteToggle = onTrackFavoriteToggle,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onOpenPlaylist = onOpenPlaylist,
            onOpenCreatedPlaylists = onOpenCreatedPlaylists,
            onOpenSavedPlaylists = onOpenSavedPlaylists,
            onOpenSavedAlbums = onOpenSavedAlbums,
            onOpenAlbum = onOpenAlbum,
            onOpenArtists = onOpenArtists,
            onOpenArtist = onOpenArtist,
            onOpenPlayRecords = onOpenPlayRecords,
            onOpenCloudDisk = onOpenCloudDisk,
            onOpenDownloads = onOpenDownloads,
            onOpenAccount = onOpenAccount,
            onShowCreatePlaylist = { showCreateSheet = true },
            onEditPlaylist = { editingPlaylist = it },
            onDeletePlaylist = { deletingPlaylist = it },
        )
    }

    MinePlaylistManageOverlays(
        showCreateSheet = showCreateSheet,
        onDismissCreate = { showCreateSheet = false },
        editingPlaylist = editingPlaylist,
        onDismissEdit = { editingPlaylist = null },
        deletingPlaylist = deletingPlaylist,
        onDismissDelete = { deletingPlaylist = null },
    )
}

/** 网易云个人区块（供“我的”一级页与合并后的“资料库 -> 网易云”分页复用）。 */
internal fun LazyListScope.mineNeteaseSections(
    loggedIn: Boolean,
    account: NeteaseClient.AccountInfo?,
    mine: MineData,
    mergedHistorySongs: List<LibraryTrack>,
    downloadedSongs: List<LibraryTrack>,
    includeProfileHeader: Boolean,
    sectionTopPadding: Dp,
    rowStates: NeteaseRowStates,
    playbackQueue: PlaybackQueueSnapshot,
    onPlayTracks: (List<String>, Int) -> Unit,
    onTrackPlayNext: (LibraryTrack) -> Unit,
    onTrackAddToQueue: (LibraryTrack) -> Unit,
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit,
    onOpenPlaylist: (DiscoverPlaylist) -> Unit,
    onOpenCreatedPlaylists: () -> Unit,
    onOpenSavedPlaylists: () -> Unit,
    onOpenSavedAlbums: () -> Unit,
    onOpenAlbum: (MineAlbum) -> Unit,
    onOpenArtists: () -> Unit,
    onOpenArtist: (MineArtist) -> Unit,
    onOpenPlayRecords: () -> Unit,
    onOpenCloudDisk: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    onShowCreatePlaylist: () -> Unit,
    onEditPlaylist: (DiscoverPlaylist) -> Unit,
    onDeletePlaylist: (DiscoverPlaylist) -> Unit,
) {
    if (!loggedIn) {
        item("mine_login") {
            val colors = LocalTuneColors.current
            Box(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = sectionTopPadding)) {
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
        return
    }
    if (includeProfileHeader) {
        item("mine_profile") {
            MineProfileHeader(
                account = account,
                onOpenAccount = onOpenAccount,
            )
        }
    }
    if (!mine.loaded) {
        item("mine_skeleton_created") {
            Box(Modifier.padding(top = sectionTopPadding)) {
                NeteasePlaylistRowSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp)
            }
        }
        item("mine_skeleton_saved") {
            Box(Modifier.padding(top = sectionTopPadding)) {
                NeteasePlaylistRowSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp)
            }
        }
        item("mine_skeleton_recent") {
            Box(Modifier.padding(top = sectionTopPadding)) {
                NeteaseSongGridSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp)
            }
        }
    } else {
        if (mine.error != null && mine.created.isEmpty() && mine.saved.isEmpty() && mine.artists.isEmpty()) {
            item("mine_error") {
                Text(
                    text = stringResource(R.string.discover_error, mine.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalTuneColors.current.textMuted,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = sectionTopPadding),
                )
            }
        }
        item("mine_created") {
            val createDesc = stringResource(R.string.netease_create_playlist_action)
            Box(Modifier.padding(top = sectionTopPadding)) {
                if (mine.created.isEmpty()) {
                    NeteaseSectionTitle(
                        text = stringResource(R.string.mine_created_title),
                        horizontalPadding = 20.dp,
                        topPadding = 0.dp,
                        onClick = onOpenCreatedPlaylists,
                        actionSymbol = MaterialSymbols.Add,
                        actionContentDescription = createDesc,
                        onActionClick = onShowCreatePlaylist,
                    )
                } else {
                    NeteasePlaylistCardRow(
                        title = stringResource(R.string.mine_created_title),
                        playlists = mine.created.take(10),
                        fallbackSubtitle = stringResource(R.string.netease_playlists_title),
                        onOpen = onOpenPlaylist,
                        horizontalPadding = 20.dp,
                        titleTopPadding = 0.dp,
                        horizontalState = rowStates.state("mine_created"),
                        onTitleClick = onOpenCreatedPlaylists,
                        actionSymbol = MaterialSymbols.Add,
                        actionContentDescription = createDesc,
                        onActionClick = onShowCreatePlaylist,
                        onEditPlaylist = onEditPlaylist,
                        onDeletePlaylist = onDeletePlaylist,
                    )
                }
            }
        }
        item("mine_saved") {
            Box(Modifier.padding(top = sectionTopPadding)) {
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
        }
        if (mine.artists.isNotEmpty()) {
            item("mine_artists") {
                Box(Modifier.padding(top = sectionTopPadding)) {
                    MineArtistRow(
                        artists = mine.artists.take(10),
                        rowState = rowStates.state("mine_artists"),
                        onOpen = onOpenArtist,
                        onMore = onOpenArtists,
                    )
                }
            }
        }
        item("mine_albums") {
            Box(Modifier.padding(top = sectionTopPadding)) {
                if (mine.albums.isEmpty()) {
                    NeteaseSectionTitle(
                        text = stringResource(R.string.mine_albums_title),
                        horizontalPadding = 20.dp,
                        topPadding = 0.dp,
                        onClick = onOpenSavedAlbums,
                    )
                } else {
                    MineAlbumCardRow(
                        albums = mine.albums.take(10),
                        rowState = rowStates.state("mine_albums"),
                        onOpen = onOpenAlbum,
                        onTitleClick = onOpenSavedAlbums,
                    )
                }
            }
        }
        item("mine_play_history") {
            Box(Modifier.padding(top = sectionTopPadding)) {
                if (mergedHistorySongs.isEmpty()) {
                    NeteaseSectionTitle(
                        text = stringResource(R.string.netease_play_records_title),
                        horizontalPadding = 20.dp,
                        topPadding = 0.dp,
                        onClick = onOpenPlayRecords,
                    )
                } else {
                    NeteaseSongGridSection(
                        title = stringResource(R.string.netease_play_records_title),
                        songs = mergedHistorySongs,
                        playbackQueue = playbackQueue,
                        onPlayTracks = onPlayTracks,
                        onTrackPlayNext = onTrackPlayNext,
                        onTrackAddToQueue = onTrackAddToQueue,
                        onTrackFavoriteToggle = onTrackFavoriteToggle,
                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                        horizontalPadding = 20.dp,
                        titleTopPadding = 0.dp,
                        horizontalState = rowStates.gridState("mine_play_history"),
                        onTitleClick = onOpenPlayRecords,
                    )
                }
            }
        }
        item("mine_cloud_disk") {
            Box(Modifier.padding(top = sectionTopPadding)) {
                NeteaseSectionTitle(
                    text = stringResource(R.string.mine_cloud_disk_title),
                    horizontalPadding = 20.dp,
                    topPadding = 0.dp,
                    onClick = onOpenCloudDisk,
                )
            }
        }
    }
    if (downloadedSongs.isNotEmpty()) {
        item("mine_downloads") {
            Box(Modifier.padding(top = sectionTopPadding)) {
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
internal fun MinePlaylistManageOverlays(
    showCreateSheet: Boolean,
    onDismissCreate: () -> Unit,
    editingPlaylist: DiscoverPlaylist?,
    onDismissEdit: () -> Unit,
    deletingPlaylist: DiscoverPlaylist?,
    onDismissDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var createBusy by remember(showCreateSheet) { mutableStateOf(false) }
    var createError by remember(showCreateSheet) { mutableStateOf<String?>(null) }

    var editBusy by remember(editingPlaylist?.id) { mutableStateOf(false) }
    var editError by remember(editingPlaylist?.id) { mutableStateOf<String?>(null) }

    val createdToast = stringResource(R.string.netease_playlist_created)
    val updatedToast = stringResource(R.string.netease_playlist_updated)
    val deletedToast = stringResource(R.string.netease_playlist_deleted)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")

    if (showCreateSheet) {
        NeteasePlaylistEditorBottomSheet(
            isCreate = true,
            busy = createBusy,
            errorMessage = createError,
            onDismiss = onDismissCreate,
            onSubmit = { name, description, isPrivate ->
                if (createBusy) return@NeteasePlaylistEditorBottomSheet
                createBusy = true
                createError = null
                scope.launch {
                    NeteaseRepository.createNeteasePlaylist(
                        context = context,
                        name = name,
                        description = description,
                        privacyPrivate = isPrivate,
                    ).onSuccess {
                        createBusy = false
                        onDismissCreate()
                        Toast.makeText(context, createdToast, Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        createBusy = false
                        val message = err.message.orEmpty()
                        createError = message
                        Toast.makeText(context, failedTemplate.replace("{err}", message), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    editingPlaylist?.let { target ->
        val record = NeteaseRepository.playlistById(target.id)
        NeteasePlaylistEditorBottomSheet(
            isCreate = false,
            initialName = record?.name ?: target.name,
            initialDescription = record?.description ?: target.subtitle,
            busy = editBusy,
            errorMessage = editError,
            onDismiss = {
                if (!editBusy) {
                    onDismissEdit()
                    editError = null
                }
            },
            onSubmit = { name, description, _ ->
                if (editBusy) return@NeteasePlaylistEditorBottomSheet
                editBusy = true
                editError = null
                scope.launch {
                    NeteaseRepository.updateNeteasePlaylist(
                        context = context,
                        playlistId = target.id,
                        name = name,
                        description = description,
                    ).onSuccess {
                        editBusy = false
                        onDismissEdit()
                        Toast.makeText(context, updatedToast, Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        editBusy = false
                        val message = err.message.orEmpty()
                        editError = message
                        Toast.makeText(context, failedTemplate.replace("{err}", message), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    deletingPlaylist?.let { target ->
        TuneDialog(
            title = stringResource(R.string.netease_playlist_delete_confirm_title),
            description = stringResource(R.string.netease_playlist_delete_confirm_desc, target.name),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismissDelete,
            confirmLabel = stringResource(R.string.netease_playlist_delete),
            confirmVariant = TunePillButtonVariant.Destructive,
            onConfirm = {
                val id = target.id
                onDismissDelete()
                scope.launch {
                    NeteaseRepository.deleteNeteasePlaylist(context, id)
                        .onSuccess {
                            Toast.makeText(context, deletedToast, Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { err ->
                            Toast.makeText(
                                context,
                                failedTemplate.replace("{err}", err.message.orEmpty()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                }
            },
        )
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
private fun MineAlbumCardRow(
    albums: List<MineAlbum>,
    rowState: LazyListState,
    onOpen: (MineAlbum) -> Unit,
    onTitleClick: () -> Unit,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cardWidth = (screenWidthDp * 0.40f).coerceIn(140f, 180f).dp
    var contextAlbumId by remember { mutableStateOf<String?>(null) }
    Column {
        NeteaseSectionTitle(
            text = stringResource(R.string.mine_albums_title),
            horizontalPadding = 20.dp,
            topPadding = 0.dp,
            onClick = onTitleClick,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = rowState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = albums, key = { it.id }) { album ->
                val subtitle = album.artistName.ifBlank {
                    if (album.trackCount > 0) stringResource(R.string.netease_track_count, album.trackCount)
                    else stringResource(R.string.album_details_title)
                }
                MineAlbumCardContextMenu(
                    album = album,
                    expanded = contextAlbumId == album.id,
                    onDismiss = { if (contextAlbumId == album.id) contextAlbumId = null },
                    onOpen = { onOpen(album) },
                ) {
                    DiscCard(
                        title = album.name,
                        subtitle = subtitle,
                        artworkUrl = album.coverUrl,
                        fallbackSymbol = MaterialSymbols.Album,
                        artworkShape = RoundedCornerShape(7.dp),
                        titleStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                        subtitleStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        modifier = Modifier.width(cardWidth),
                        onClick = { onOpen(album) },
                        onLongClick = { contextAlbumId = album.id },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MineArtistRow(
    artists: List<MineArtist>,
    rowState: LazyListState,
    onOpen: (MineArtist) -> Unit,
    onMore: () -> Unit,
) {
    val colors = LocalTuneColors.current
    var contextArtistId by remember { mutableStateOf<String?>(null) }
    Column {
        NeteaseSectionTitle(
            text = stringResource(R.string.mine_artists_title),
            horizontalPadding = 20.dp,
            topPadding = 0.dp,
            onClick = onMore,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = rowState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items = artists, key = { it.id }) { artist ->
                MineArtistCardContextMenu(
                    artist = artist,
                    expanded = contextArtistId == artist.id,
                    onDismiss = { if (contextArtistId == artist.id) contextArtistId = null },
                    onOpen = { onOpen(artist) },
                ) {
                    Column(
                        modifier = Modifier
                            .width(88.dp)
                            .combinedClickable(
                                onClick = { onOpen(artist) },
                                onLongClick = { contextArtistId = artist.id },
                            ),
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
    var contextPlaylistId by remember { mutableStateOf<String?>(null) }
    var editingPlaylist by remember { mutableStateOf<DiscoverPlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<DiscoverPlaylist?>(null) }
    var filterQuery by rememberSaveable(saved) { mutableStateOf("") }
    val trimmedQuery = filterQuery.trim()
    val filteredPlaylists = remember(playlists, trimmedQuery) {
        if (trimmedQuery.isEmpty()) playlists else playlists.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
    }
    val pullDownFilterState = rememberPullDownFilterState(
        listState = listState,
        filterKey = if (saved) "mine_saved_playlists" else "mine_created_playlists",
        filterActive = trimmedQuery.isNotEmpty(),
    )
    LaunchedEffect(Unit) { NeteaseRepository.loadMine() }
    Box(modifier = modifier.fillMaxSize().nestedScroll(pullDownFilterState.scrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            item(key = "mine-playlists-text-filter", contentType = "mine-playlists-text-filter") {
                LibraryTextFilterSlot(state = pullDownFilterState) { showPlaceholder ->
                    LibraryTextFilter(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        placeholder = stringResource(R.string.filter_placeholder_search),
                        showPlaceholderAndLeadingSymbol = showPlaceholder,
                    )
                }
            }
            if (filteredPlaylists.isEmpty() && trimmedQuery.isNotEmpty()) {
                item(key = "mine-playlists-filter-empty") {
                    HeroCard(
                        symbol = MaterialSymbols.QueueMusic,
                        title = stringResource(R.string.playlists_no_match_title),
                        description = stringResource(R.string.filter_no_match_description),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            } else {
                items(items = filteredPlaylists, key = { it.id }) { playlist ->
                    NeteasePlaylistCardContextMenu(
                        playlist = playlist,
                        expanded = contextPlaylistId == playlist.id,
                        onDismiss = { if (contextPlaylistId == playlist.id) contextPlaylistId = null },
                        onEdit = { editingPlaylist = playlist },
                        onDelete = { deletingPlaylist = playlist },
                    ) {
                        MinePlaylistRow(
                            playlist = playlist,
                            onClick = { onOpenPlaylist(playlist) },
                            onLongClick = { contextPlaylistId = playlist.id },
                        )
                    }
                    HorizontalDivider(
                        color = colors.borderGlass,
                        modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                    )
                }
            }
        }
    }

    MinePlaylistManageOverlays(
        showCreateSheet = false,
        onDismissCreate = {},
        editingPlaylist = editingPlaylist,
        onDismissEdit = { editingPlaylist = null },
        deletingPlaylist = deletingPlaylist,
        onDismissDelete = { deletingPlaylist = null },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MinePlaylistRow(
    playlist: DiscoverPlaylist,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneRemoteImage(
            url = playlist.coverUrl,
            sizePx = 300,
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

/** “关注歌手”全部二级页（支持下拉搜索与统一卡片菜单）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MineArtistsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    onOpenArtist: (MineArtist) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    var contextArtistId by remember { mutableStateOf<String?>(null) }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    val trimmedQuery = filterQuery.trim()
    val filteredArtists = remember(mine.artists, trimmedQuery) {
        if (trimmedQuery.isEmpty()) mine.artists
        else mine.artists.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
    }
    val pullDownFilterState = rememberPullDownFilterState(
        listState = listState,
        filterKey = "mine_artists",
        filterActive = trimmedQuery.isNotEmpty(),
    )
    LaunchedEffect(Unit) { NeteaseRepository.loadMine() }
    Box(modifier = modifier.fillMaxSize().nestedScroll(pullDownFilterState.scrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            item(key = "mine-artists-text-filter", contentType = "mine-artists-text-filter") {
                LibraryTextFilterSlot(state = pullDownFilterState) { showPlaceholder ->
                    LibraryTextFilter(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        placeholder = stringResource(R.string.filter_placeholder_search),
                        showPlaceholderAndLeadingSymbol = showPlaceholder,
                    )
                }
            }
            if (filteredArtists.isEmpty() && trimmedQuery.isNotEmpty()) {
                item(key = "mine-artists-filter-empty") {
                    HeroCard(
                        symbol = MaterialSymbols.People,
                        title = stringResource(R.string.artists_no_match_title),
                        description = stringResource(R.string.filter_no_match_description),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            } else {
                items(items = filteredArtists, key = { it.id }) { artist ->
                    MineArtistCardContextMenu(
                        artist = artist,
                        expanded = contextArtistId == artist.id,
                        onDismiss = { if (contextArtistId == artist.id) contextArtistId = null },
                        onOpen = { onOpenArtist(artist) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenArtist(artist) },
                                    onLongClick = { contextArtistId = artist.id },
                                )
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TuneRemoteImage(
                                url = artist.coverUrl,
                                sizePx = 300,
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
                    }
                    HorizontalDivider(
                        color = colors.borderGlass,
                        modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                    )
                }
            }
        }
    }
}
