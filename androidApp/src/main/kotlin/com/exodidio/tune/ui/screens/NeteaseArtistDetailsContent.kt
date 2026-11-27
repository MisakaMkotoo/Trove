package com.exodidio.tune.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.netease.NeteaseArtistAlbumItem
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.ArtworkHeroBackdrop
import com.exodidio.tune.ui.components.DetailHero
import com.exodidio.tune.ui.components.DetailHeroArtworkShape
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LibraryTextFilterSlot
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.components.TuneRemoteImage
import com.exodidio.tune.ui.components.rememberPullDownFilterState
import com.exodidio.tune.ui.theme.LocalTuneColors
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

internal fun filterNeteaseArtistTracks(tracks: List<LibraryTrack>, query: String): List<LibraryTrack> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return tracks
    return tracks.filter { track ->
        track.title.contains(trimmed, ignoreCase = true) ||
            track.artists.contains(trimmed, ignoreCase = true) ||
            track.album.contains(trimmed, ignoreCase = true)
    }
}

internal fun filterNeteaseArtistAlbums(
    albums: List<NeteaseArtistAlbumItem>,
    query: String,
): List<NeteaseArtistAlbumItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return albums
    return albums.filter { album ->
        album.name.contains(trimmed, ignoreCase = true)
    }
}

/** 在线歌手详情二级页：头像信息 + 下拉搜索 + 歌曲（分页/排序）与专辑（分页）双 Tab。 */
@Composable
internal fun NeteaseArtistDetailsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reduceTransparency: Boolean = false,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onOpenAlbum: (albumId: Long, name: String, coverUrl: String) -> Unit = { _, _, _ -> },
    onOpenArtist: (artistId: Long, name: String) -> Unit = { _, _ -> },
    onTrackPlayNext: (String) -> Unit = {},
    onTrackAddToQueue: (String) -> Unit = {},
    onTrackFavoriteToggle: (String, Boolean) -> Unit = { _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onHeroColorChanged: (Color) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detail by NeteaseRepository.artistDetail.collectAsStateWithLifecycle()
    val selectedArtistId by NeteaseRepository.selectedArtistId.collectAsStateWithLifecycle()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    val libraryTracks by AndroidSyncRuntime.syncStore.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playbackOnlyTracks by AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val localPlaylists by AndroidSyncRuntime.syncStore.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val playableById = remember(libraryTracks, playbackOnlyTracks) {
        (playbackOnlyTracks + libraryTracks).associateBy { it.id }
    }

    var selectedTab by rememberSaveable(selectedArtistId) { mutableIntStateOf(0) }
    var filterQuery by rememberSaveable(selectedArtistId) { mutableStateOf("") }
    var contextTrackId by remember { mutableStateOf<String?>(null) }
    var heroMenu by remember { mutableStateOf(false) }
    var showBioDialog by remember { mutableStateOf(false) }

    val followedToast = stringResource(R.string.netease_artist_followed)
    val unfollowedToast = stringResource(R.string.netease_artist_unfollowed)
    val failedTemplate = stringResource(R.string.netease_operation_failed, "{err}")

    LaunchedEffect(selectedArtistId) {
        val id = selectedArtistId ?: return@LaunchedEffect
        if (detail.artistId != id) {
            NeteaseRepository.loadArtistDetail(id)
        }
    }
    LaunchedEffect(loggedIn) {
        if (loggedIn) NeteaseRepository.loadMine()
    }

    if (detail.artistId.isBlank()) {
        Box(
            modifier = modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.netease_artist_songs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        return
    }

    val isFollowed = remember(detail.artistId, detail.followed, mine.artists) {
        mine.artists.any { it.id == detail.artistId } || detail.followed
    }
    val heroCoverPath = rememberNeteaseImagePath(detail.coverUrl, sizePx = 800)
    val listState = rememberLazyListState()
    val trimmedQuery = filterQuery.trim()
    val visibleSongs = remember(detail.songs, trimmedQuery) {
        filterNeteaseArtistTracks(detail.songs, trimmedQuery)
    }
    val visibleAlbums = remember(detail.albums, trimmedQuery) {
        filterNeteaseArtistAlbums(detail.albums, trimmedQuery)
    }
    val pullDownFilterState = rememberPullDownFilterState(
        listState = listState,
        filterKey = "netease-artist-details:${detail.artistId}",
        filterActive = trimmedQuery.isNotEmpty(),
    )

    val nearEnd by remember(listState, selectedTab, detail.songs.size, detail.albums.size) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 6
        }
    }
    LaunchedEffect(
        nearEnd,
        selectedTab,
        detail.artistId,
        detail.songsHasMore,
        detail.albumsHasMore,
        detail.loading,
        detail.loadingMoreSongs,
        detail.loadingMoreAlbums,
        trimmedQuery,
    ) {
        if (trimmedQuery.isNotEmpty() || !nearEnd || detail.loading) return@LaunchedEffect
        if (selectedTab == 0 && detail.songsHasMore && !detail.loadingMoreSongs) {
            NeteaseRepository.loadMoreArtistSongs()
        } else if (selectedTab == 1 && detail.albumsHasMore && !detail.loadingMoreAlbums) {
            NeteaseRepository.loadMoreArtistAlbums()
        }
    }

    fun playAll(startIndex: Int = 0, shuffle: Boolean = false) {
        val ids = detail.songs.map { it.id }.distinct()
        if (ids.isEmpty()) return
        val request = PlaybackRequest(ids, startIndex.coerceIn(0, ids.lastIndex))
        if (shuffle) AndroidPlaybackRuntime.controller().shuffle(request)
        else AndroidPlaybackRuntime.controller().play(request)
    }

    val appleTitleStyle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    )
    val appleArtistStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp)
    val appleRowPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 0.dp, bottom = 4.dp)

    Box(modifier = modifier.fillMaxSize().nestedScroll(pullDownFilterState.scrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item(key = "artist-hero") {
                ArtworkHeroBackdrop(
                    heroCoverPath,
                    Modifier.fillMaxWidth(),
                    onDominantColorChanged = onHeroColorChanged,
                    reduceTransparency = reduceTransparency,
                ) {
                    DetailHero(
                        title = detail.name,
                        subtitle = detail.alias.takeIf { it.isNotEmpty() }?.joinToString(" / "),
                        metadata = stringResource(
                            R.string.netease_artist_counts,
                            maxOf(detail.musicCount, detail.songsTotal, detail.songs.size),
                            maxOf(detail.albumCount, detail.albums.size),
                        ),
                        playLabel = stringResource(R.string.player_play),
                        shuffleLabel = stringResource(R.string.player_shuffle),
                        moreLabel = stringResource(R.string.artist_row_more_options),
                        artworkPath = heroCoverPath,
                        artworkShape = DetailHeroArtworkShape.Circle,
                        artworkSize = 144.dp,
                        fallbackSymbol = MaterialSymbols.Person,
                        reduceTransparency = reduceTransparency,
                        modifier = Modifier.fillMaxWidth().padding(
                            start = 24.dp,
                            top = contentPadding.calculateTopPadding(),
                            end = 24.dp,
                            bottom = 20.dp,
                        ),
                        onPlayClick = { playAll(0, shuffle = false) },
                        onShuffleClick = { playAll(0, shuffle = true) },
                        onMoreClick = { heroMenu = true },
                    )
                }
            }

            item(key = "netease-artist-text-filter", contentType = "netease-artist-text-filter") {
                LibraryTextFilterSlot(state = pullDownFilterState) { showPlaceholder ->
                    LibraryTextFilter(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        placeholder = stringResource(R.string.filter_placeholder_search),
                        showPlaceholderAndLeadingSymbol = showPlaceholder,
                    )
                }
            }

            item(key = "artist-tabs") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ArtistTabChip(
                        label = stringResource(R.string.netease_artist_tab_songs),
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.weight(1f),
                    )
                    ArtistTabChip(
                        label = stringResource(R.string.netease_artist_tab_albums),
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (detail.loading && detail.songs.isEmpty() && detail.albums.isEmpty()) {
                item(key = "artist-loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
            } else if (selectedTab == 0) {
                if (detail.songs.isEmpty()) {
                    item(key = "artist-songs-empty") {
                        Text(
                            text = detail.error ?: stringResource(R.string.netease_artist_songs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        )
                    }
                } else if (visibleSongs.isEmpty() && trimmedQuery.isNotEmpty()) {
                    item(key = "artist-songs-filter-empty") {
                        HeroCard(
                            symbol = MaterialSymbols.Search,
                            title = stringResource(R.string.tracks_no_match_title),
                            description = stringResource(R.string.filter_no_match_description),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                } else {
                    itemsIndexed(visibleSongs, key = { _, song -> song.id }) { index, baseTrack ->
                        val track = playableById[baseTrack.id] ?: baseTrack
                        val subtitle = remember(track.artists, track.album) {
                            val albumName = track.album.takeIf { it.isNotBlank() && it != "未知专辑" }
                            listOfNotNull(
                                track.artists.takeIf { it.isNotBlank() },
                                albumName,
                            ).joinToString(" · ").ifBlank { detail.name }
                        }
                        TrackContextMenu(
                            track = track,
                            expanded = contextTrackId == track.id,
                            onDismiss = { if (contextTrackId == track.id) contextTrackId = null },
                            actions = TrackContextMenuActions(
                                removeFromQueue = false,
                                removeFromPlaylist = false,
                                neteaseLike = true,
                                allowNeteasePlaylists = true,
                                hide = false,
                            ),
                            playbackQueue = playbackQueue,
                            onPlayNext = { onTrackPlayNext(it.id) },
                            onAddToQueue = { onTrackAddToQueue(it.id) },
                            onFavoriteChange = { item, favorite -> onTrackFavoriteToggle(item.id, favorite) },
                            onBottomSheetRequested = onTrackContextBottomSheet,
                            onGoToAlbum = { item ->
                                val aid = NeteaseRepository.neteaseAlbumIdForTrack(item)
                                if (aid != null && aid > 0L) {
                                    onOpenAlbum(aid, item.album, NeteaseRepository.coverUrlForTrack(item).orEmpty())
                                }
                            },
                            onGoToArtist = { artist ->
                                val aid = artist.id.toLongOrNull()
                                if (aid != null && aid > 0L) onOpenArtist(aid, artist.name)
                            },
                            playlists = localPlaylists.filter { it.id != FavoritesPlaylistId },
                            anchor = {
                                TrackRow(
                                    title = track.title,
                                    artist = subtitle,
                                    artworkPath = track.artworkPath,
                                    artworkUrl = NeteaseRepository.coverUrlForTrack(track),
                                    contentPadding = appleRowPadding,
                                    artworkCornerRadius = 4.dp,
                                    titleStyle = appleTitleStyle,
                                    artistStyle = appleArtistStyle,
                                    moreButtonWidth = 38.dp,
                                    onClick = {
                                        val fullIndex = detail.songs.indexOfFirst { it.id == baseTrack.id }.coerceAtLeast(0)
                                        playAll(fullIndex, shuffle = false)
                                    },
                                    onMoreClick = { contextTrackId = track.id },
                                    onLongClick = { contextTrackId = track.id },
                                    trailingContent = {
                                        IconButton(
                                            onClick = { contextTrackId = track.id },
                                            modifier = Modifier.width(38.dp).height(48.dp),
                                        ) {
                                            MaterialSymbol(
                                                symbol = MaterialSymbols.MoreVert,
                                                contentDescription = stringResource(R.string.album_row_more_options),
                                                size = 20.dp,
                                                tint = colors.textMuted,
                                            )
                                        }
                                    },
                                )
                            },
                        )
                        if (index < visibleSongs.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                                color = colors.borderGlass,
                            )
                        }
                    }
                    if (trimmedQuery.isEmpty()) {
                        item(key = "artist-songs-footer") {
                            PagingFooter(
                                loading = detail.loadingMoreSongs,
                                hasMore = detail.songsHasMore,
                            )
                        }
                    }
                }
            } else {
                if (detail.albums.isEmpty()) {
                    item(key = "artist-albums-empty") {
                        Text(
                            text = detail.error ?: stringResource(R.string.netease_artist_albums_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        )
                    }
                } else if (visibleAlbums.isEmpty() && trimmedQuery.isNotEmpty()) {
                    item(key = "artist-albums-filter-empty") {
                        HeroCard(
                            symbol = MaterialSymbols.Search,
                            title = stringResource(R.string.albums_no_match_title),
                            description = stringResource(R.string.filter_no_match_description),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                } else {
                    itemsIndexed(visibleAlbums, key = { _, album -> album.id }) { index, album ->
                        ArtistAlbumRow(
                            album = album,
                            onClick = {
                                val idLong = album.id.toLongOrNull()
                                if (idLong != null && idLong > 0L) {
                                    onOpenAlbum(idLong, album.name, album.coverUrl)
                                }
                            },
                        )
                        if (index < visibleAlbums.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 82.dp, end = 16.dp),
                                color = colors.borderGlass,
                            )
                        }
                    }
                    if (trimmedQuery.isEmpty()) {
                        item(key = "artist-albums-footer") {
                            PagingFooter(
                                loading = detail.loadingMoreAlbums,
                                hasMore = detail.albumsHasMore,
                            )
                        }
                    }
                }
            }
        }
    }

    if (heroMenu) {
        TuneBottomSheet(
            title = { Text(detail.name, color = colors.textMain) },
            onDismiss = { heroMenu = false },
        ) {
            ActionList(
                items = buildList {
                    if (loggedIn) {
                        add(
                            ActionListItem(
                                labelRes = if (isFollowed) {
                                    R.string.netease_artist_unfollow
                                } else {
                                    R.string.netease_artist_follow
                                },
                                leadingSymbol = if (isFollowed) MaterialSymbols.HeartMinus else MaterialSymbols.HeartPlus,
                                onClick = {
                                    val nextFollowed = !isFollowed
                                    heroMenu = false
                                    scope.launch {
                                        val artistCard = MineArtist(
                                            id = detail.artistId,
                                            name = detail.name,
                                            coverUrl = detail.coverUrl,
                                        )
                                        NeteaseRepository.setArtistFollowed(
                                            context = context,
                                            artist = artistCard,
                                            artistId = detail.artistId,
                                            follow = nextFollowed,
                                        ).onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (nextFollowed) followedToast else unfollowedToast,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }.onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                failedTemplate.replace("{err}", err.message.orEmpty()),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                            ),
                        )
                    }
                    add(
                        ActionListItem(
                            labelRes = R.string.netease_artist_sort_hot,
                            leadingSymbol = MaterialSymbols.BarChart,
                            trailingContent = if (detail.order == "hot") {
                                {
                                    MaterialSymbol(
                                        symbol = MaterialSymbols.Check,
                                        size = 18.dp,
                                        tint = colors.primary,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                heroMenu = false
                                selectedTab = 0
                                NeteaseRepository.switchArtistSongsOrder("hot")
                            },
                        ),
                    )
                    add(
                        ActionListItem(
                            labelRes = R.string.netease_artist_sort_time,
                            leadingSymbol = MaterialSymbols.Schedule,
                            trailingContent = if (detail.order == "time") {
                                {
                                    MaterialSymbol(
                                        symbol = MaterialSymbols.Check,
                                        size = 18.dp,
                                        tint = colors.primary,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                heroMenu = false
                                selectedTab = 0
                                NeteaseRepository.switchArtistSongsOrder("time")
                            },
                        ),
                    )
                    add(
                        ActionListItem(
                            labelRes = R.string.netease_artist_bio,
                            leadingSymbol = MaterialSymbols.Info,
                            onClick = {
                                heroMenu = false
                                showBioDialog = true
                            },
                        ),
                    )
                    add(
                        ActionListItem(
                            labelRes = R.string.netease_refresh,
                            leadingSymbol = MaterialSymbols.Refresh,
                            onClick = {
                                heroMenu = false
                                NeteaseRepository.loadArtistDetail(
                                    artistId = detail.artistId,
                                    initialName = detail.name,
                                    initialCoverUrl = detail.coverUrl,
                                    force = true,
                                )
                            },
                        ),
                    )
                },
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
    }

    if (showBioDialog) {
        TuneBottomSheet(
            title = { Text(detail.name, color = colors.textMain) },
            onDismiss = { showBioDialog = false },
        ) {
            Text(
                text = detail.description.ifBlank { stringResource(R.string.netease_artist_bio_empty) },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ArtistTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else colors.glassElevated)
            .border(0.5.dp, if (selected) colors.primary.copy(alpha = 0.45f) else colors.borderGlass, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.textMain,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ArtistAlbumRow(
    album: NeteaseArtistAlbumItem,
    onClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val year = remember(album.publishTimeMs) {
        if (album.publishTimeMs > 0L) {
            runCatching {
                Instant.ofEpochMilli(album.publishTimeMs)
                    .atZone(ZoneId.systemDefault())
                    .year
                    .toString()
            }.getOrNull()
        } else {
            null
        }
    }
    val trackCountText = if (album.trackCount > 0) {
        stringResource(R.string.netease_track_count, album.trackCount)
    } else {
        null
    }
    val subtitle = listOfNotNull(year, trackCountText).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneRemoteImage(
            url = album.coverUrl,
            sizePx = 200,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(6.dp)),
            fallback = {
                MaterialSymbol(
                    symbol = MaterialSymbols.Album,
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
                text = album.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun PagingFooter(
    loading: Boolean,
    hasMore: Boolean,
) {
    val colors = LocalTuneColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.netease_loading_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            !hasMore -> Text(
                text = stringResource(R.string.netease_no_more_items),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}
