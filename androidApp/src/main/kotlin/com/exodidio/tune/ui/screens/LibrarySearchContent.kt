package com.exodidio.tune.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.itemsIndexed as columnItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.OnlinePlaylistInfo
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.AlbumContextMenu
import com.exodidio.tune.ui.components.AlbumRow
import com.exodidio.tune.ui.components.ArtistRow
import com.exodidio.tune.ui.components.DiscCard
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LocalNeteaseEntityMenuActions
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.TuneSegmentedTabBar
import com.exodidio.tune.ui.components.pressScale
import com.exodidio.tune.ui.components.rememberArtworkFadeAlpha
import com.exodidio.tune.ui.components.rememberArtworkThumbnail
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneRadii
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale

internal fun tracksByAlbumId(tracks: List<LibraryTrack>): Map<String, List<LibraryTrack>> =
    tracks.groupBy { it.albumId }

internal fun trackIndexById(tracks: List<LibraryTrack>): Map<String, Int> =
    tracks.mapIndexed { index, track -> track.id to index }.toMap()

@Composable
internal fun LibrarySearchContent(
    uiState: LibrarySearchUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onQueryChange: (String) -> Unit = {},
    onTrackClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onComposerClick: (String) -> Unit = {},
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onAlbumPlayNext: (List<String>) -> Unit = {},
    onAlbumAddToQueue: (List<String>) -> Unit = {},
    onAlbumAddToFavorites: (List<String>) -> Unit = {},
    onOnlineModeChange: (Boolean) -> Unit = {},
    onOnlineCategoryChange: (OnlineSearchCategory) -> Unit = {},
    onOnlineSubmit: () -> Unit = {},
    onOnlineTrackPlay: (index: Int) -> Unit = {},
    onOnlinePlaylistClick: (Long) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val neteaseEntityActions = LocalNeteaseEntityMenuActions.current
    val searchGuess by NeteaseRepository.searchGuess.collectAsStateWithLifecycle()
    val searchGuessLoading by NeteaseRepository.searchGuessLoading.collectAsStateWithLifecycle()
    val searchHistory by NeteaseRepository.searchHistory.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.onlineMode) {
        if (uiState.onlineMode) NeteaseRepository.loadSearchGuess()
    }
    var contextTrack by remember { mutableStateOf<LibraryTrack?>(null) }
    var contextAlbumId by remember { mutableStateOf<String?>(null) }
    val tracksByAlbum = remember(uiState.allTracks) { tracksByAlbumId(uiState.allTracks) }
    val playlistArtworkByTrackId = remember(uiState.allTracks) { uiState.allTracks.associateBy({ it.id }, { it.artworkPath }) }
    val trackIndexByIdMap = remember(uiState.tracks) { trackIndexById(uiState.tracks) }
    BoxWithConstraints(modifier.fillMaxSize()) {
    val trackDividerWidth = maxWidth * 0.8f
    Column(Modifier.fillMaxSize()) {
        LibraryTextFilter(
            value = uiState.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(
                if (uiState.onlineMode) R.string.search_netease_placeholder else R.string.library_search_placeholder,
            ),
            onlineMode = null,
            onOnlineModeChange = onOnlineModeChange,
            onSubmit = { if (uiState.onlineMode) onOnlineSubmit() },
            modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
        )
        val reduceMotion = LocalReduceMotion.current
        SearchModeTabBar(
            onlineMode = uiState.onlineMode,
            reduceMotion = reduceMotion,
            onOnlineModeChange = onOnlineModeChange,
        )
        val showSuggestions = uiState.onlineMode &&
            uiState.query.isNotBlank() &&
            !uiState.onlineLoading &&
            uiState.onlineQuery != uiState.query.trim() &&
            uiState.onlineSuggestions.isNotEmpty()
        val searchSurfaceKey = if (uiState.onlineMode) {
            when {
                showSuggestions -> "online_suggest"
                uiState.onlineLoading -> "online_loading"
                uiState.onlineError != null -> "online_error"
                uiState.onlineQuery.isBlank() || uiState.query.isBlank() -> "online_idle"
                uiState.onlineTracks.isEmpty() && uiState.onlinePlaylists.isEmpty() &&
                    uiState.onlineArtists.isEmpty() && uiState.onlineAlbums.isEmpty() &&
                    uiState.onlinePlaylistError == null && uiState.onlineArtistError == null &&
                    uiState.onlineAlbumError == null -> "online_empty"
                else -> "online_results"
            }
        } else {
            when {
                uiState.query.isBlank() -> "local_empty"
                uiState.isLoaded && uiState.tracks.isEmpty() && uiState.albums.isEmpty() &&
                    uiState.artists.isEmpty() && uiState.playlists.isEmpty() &&
                    uiState.composers.isEmpty() -> "local_no_results"
                else -> "local_results"
            }
        }
        if (uiState.onlineMode && searchSurfaceKey == "online_results") {
            OnlineSearchCategoryBar(
                selected = uiState.onlineCategory,
                songsCount = uiState.onlineTracks.size,
                playlistsCount = uiState.onlinePlaylists.size,
                artistsCount = uiState.onlineArtists.size,
                albumsCount = uiState.onlineAlbums.size,
                onSelect = onOnlineCategoryChange,
            )
        }
        AnimatedContent(
            targetState = uiState.onlineMode to searchSurfaceKey,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (initialState.first != targetState.first) {
                    // Tab 0 = NetEase (online=true), Tab 1 = Library (online=false)
                    val movingToLibrary = initialState.first && !targetState.first
                    val enterOffset: (Int) -> Int = { width -> if (movingToLibrary) width / 3 else -width / 3 }
                    val exitOffset: (Int) -> Int = { width -> if (movingToLibrary) -width / 3 else width / 3 }
                    (slideInHorizontally(tween(260, easing = FastOutSlowInEasing), enterOffset) +
                        fadeIn(tween(220, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutHorizontally(tween(220, easing = FastOutSlowInEasing), exitOffset) +
                            fadeOut(tween(180, easing = FastOutSlowInEasing)))
                } else {
                    fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(130, easing = FastOutSlowInEasing))
                }
            },
            label = "search-surface-transition",
        ) { (_, surfaceKey) ->
        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
            when (surfaceKey) {
                "online_suggest" -> {
                        item("online_suggest_header") {
                            Text(
                                text = stringResource(R.string.library_search_suggest_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textMuted,
                                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 6.dp),
                            )
                        }
                        columnItemsIndexed(
                            uiState.onlineSuggestions,
                            key = { idx, kw -> "suggest_${idx}_$kw" },
                        ) { idx, keyword ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onQueryChange(keyword)
                                        onOnlineSubmit()
                                    }
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MaterialSymbol(
                                    symbol = MaterialSymbols.Search,
                                    size = 18.dp,
                                    tint = colors.textMuted,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = keyword,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.textMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (idx < uiState.onlineSuggestions.lastIndex) {
                                HorizontalDivider(
                                    color = colors.borderGlass,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                            }
                        }
                    }
                    "online_loading" -> item("online_loading") {
                        SearchHint(stringResource(R.string.library_search_online_loading))
                    }
                    "online_error" -> item("online_error") {
                        SearchHint(stringResource(R.string.library_search_online_error, uiState.onlineError.orEmpty()))
                    }
                    "online_idle" -> {
                        if (searchHistory.isNotEmpty()) {
                            item("online_history") {
                                SearchHistorySection(
                                    items = searchHistory,
                                    onItemClick = { kw ->
                                        onQueryChange(kw)
                                        onOnlineSubmit()
                                    },
                                    onRemove = { kw -> NeteaseRepository.removeSearchHistory(context, kw) },
                                    onClearAll = { NeteaseRepository.clearSearchHistory(context) },
                                )
                            }
                        }
                        if (searchGuessLoading && searchGuess.isEmpty()) {
                            item("online_guess_loading") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                }
                            }
                        } else if (searchGuess.isNotEmpty()) {
                            item("online_guess_section") {
                                SearchGuessSection(
                                    items = searchGuess,
                                    loading = searchGuessLoading,
                                    onRefresh = { NeteaseRepository.loadSearchGuess(force = true) },
                                    onItemClick = { keyword ->
                                        onQueryChange(keyword)
                                        onOnlineSubmit()
                                    },
                                )
                            }
                        } else if (searchHistory.isEmpty()) {
                            item("online_prompt") {
                                SearchEmptyState(R.string.library_search_online_title, R.string.library_search_online_description)
                            }
                        }
                    }
                    "online_empty" -> item("online_empty") {
                        SearchEmptyState(R.string.library_search_no_results_title, R.string.library_search_online_empty_description)
                    }
                    "online_results" -> {
                        val onlineIds = uiState.onlineTracks.map { it.id }
                        when (uiState.onlineCategory) {
                            OnlineSearchCategory.All -> {
                                searchSection(
                                    title = R.string.library_tracks,
                                    values = uiState.onlineTracks,
                                    key = { it.id },
                                    rows = 3,
                                    topPadding = 16.dp,
                                    onHeaderClick = { onOnlineCategoryChange(OnlineSearchCategory.Songs) },
                                ) { track ->
                                    val onlineIndex = onlineIds.indexOf(track.id)
                                    TrackContextMenu(
                                        track = track,
                                        expanded = contextTrack?.id == track.id,
                                        onDismiss = { contextTrack = null },
                                        playbackQueue = playbackQueue,
                                        onPlayNext = onTrackPlayNext,
                                        onAddToQueue = onTrackAddToQueue,
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
                                                track.title,
                                                track.artists,
                                                artworkPath = track.artworkPath,
                                                artworkUrl = com.exodidio.tune.netease.NeteaseRepository.coverUrlForTrack(track),
                                                contentPadding = PaddingValues(vertical = 6.dp),
                                                onClick = { if (onlineIndex >= 0) onOnlineTrackPlay(onlineIndex) },
                                                onMoreClick = { contextTrack = track },
                                                onLongClick = { contextTrack = track },
                                            )
                                            if (trackHasDivider(onlineIndex, onlineIds.lastIndex)) {
                                                HorizontalDivider(
                                                    color = colors.borderGlass,
                                                    modifier = Modifier.width(trackDividerWidth).align(Alignment.CenterHorizontally),
                                                )
                                            }
                                        }
                                    }
                                }
                                uiState.onlinePlaylistError?.let { message ->
                                    item("online_playlists_error") {
                                        SectionErrorHint(R.string.library_playlists, message, topPadding = 24.dp)
                                    }
                                }
                                searchSection(
                                    title = R.string.library_playlists,
                                    values = uiState.onlinePlaylists,
                                    key = { it.id.toString() },
                                    topPadding = 24.dp,
                                    onHeaderClick = { onOnlineCategoryChange(OnlineSearchCategory.Playlists) },
                                ) { playlist ->
                                    DiscCard(
                                        title = playlist.name,
                                        subtitle = playlist.creator.ifBlank { stringResource(R.string.netease_track_count, playlist.trackCount) },
                                        artworkPath = playlist.artworkPath,
                                        fallbackSymbol = MaterialSymbols.QueueMusic,
                                        onClick = { onOnlinePlaylistClick(playlist.id) },
                                    )
                                }
                                uiState.onlineArtistError?.let { message ->
                                    item("online_artists_error") {
                                        SectionErrorHint(R.string.library_artists, message, topPadding = 16.dp)
                                    }
                                }
                                searchSection(
                                    title = R.string.library_artists,
                                    values = uiState.onlineArtists,
                                    key = { it.id.toString() },
                                    topPadding = 16.dp,
                                    onHeaderClick = { onOnlineCategoryChange(OnlineSearchCategory.Artists) },
                                ) { artist ->
                                    DiscCard(
                                        title = artist.name,
                                        subtitle = "",
                                        artworkPath = artist.artworkPath,
                                        fallbackSymbol = MaterialSymbols.Person,
                                        artworkShape = CircleShape,
                                        onClick = {
                                            if (artist.id > 0L) {
                                                neteaseEntityActions.onOpenArtist(artist.id, artist.name)
                                            } else {
                                                onQueryChange(artist.name)
                                                onOnlineSubmit()
                                            }
                                        },
                                    )
                                }
                                uiState.onlineAlbumError?.let { message ->
                                    item("online_albums_error") {
                                        SectionErrorHint(R.string.library_albums, message, topPadding = 16.dp)
                                    }
                                }
                                searchSection(
                                    title = R.string.library_albums,
                                    values = uiState.onlineAlbums,
                                    key = { it.id.toString() },
                                    topPadding = 16.dp,
                                    onHeaderClick = { onOnlineCategoryChange(OnlineSearchCategory.Albums) },
                                ) { album ->
                                    DiscCard(
                                        title = album.name,
                                        subtitle = album.artist,
                                        artworkPath = album.artworkPath,
                                        fallbackSymbol = MaterialSymbols.Album,
                                        onClick = {
                                            if (album.id > 0L) {
                                                neteaseEntityActions.onOpenAlbum(album.id, album.name, album.coverUrl)
                                            } else {
                                                onQueryChange(
                                                    listOf(album.artist, album.name).filter { it.isNotBlank() }.joinToString(" "),
                                                )
                                                onOnlineSubmit()
                                            }
                                        },
                                    )
                                }
                            }
                            OnlineSearchCategory.Songs -> {
                                if (uiState.onlineTracks.isEmpty()) {
                                    item("online_songs_empty") {
                                        SearchCategoryEmptyCard()
                                    }
                                } else {
                                    columnItemsIndexed(
                                        uiState.onlineTracks,
                                        key = { _, track -> "online_cat_song_${track.id}" },
                                    ) { index, track ->
                                        TrackContextMenu(
                                            track = track,
                                            expanded = contextTrack?.id == track.id,
                                            onDismiss = { contextTrack = null },
                                            playbackQueue = playbackQueue,
                                            onPlayNext = onTrackPlayNext,
                                            onAddToQueue = onTrackAddToQueue,
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
                                            TrackRow(
                                                track.title,
                                                track.artists,
                                                artworkPath = track.artworkPath,
                                                artworkUrl = com.exodidio.tune.netease.NeteaseRepository.coverUrlForTrack(track),
                                                onClick = { onOnlineTrackPlay(index) },
                                                onMoreClick = { contextTrack = track },
                                                onLongClick = { contextTrack = track },
                                            )
                                        }
                                    }
                                }
                            }
                            OnlineSearchCategory.Playlists -> {
                                uiState.onlinePlaylistError?.let { message ->
                                    item("online_playlists_cat_error") {
                                        SectionErrorHint(R.string.library_playlists, message, topPadding = 16.dp)
                                    }
                                }
                                if (uiState.onlinePlaylists.isEmpty()) {
                                    if (uiState.onlinePlaylistError == null) {
                                        item("online_playlists_empty") { SearchCategoryEmptyCard() }
                                    }
                                } else {
                                    columnItemsIndexed(
                                        uiState.onlinePlaylists,
                                        key = { _, playlist -> "online_cat_playlist_${playlist.id}" },
                                    ) { _, playlist ->
                                        OnlinePlaylistSearchRow(
                                            playlist = playlist,
                                            onClick = { onOnlinePlaylistClick(playlist.id) },
                                        )
                                    }
                                }
                            }
                            OnlineSearchCategory.Artists -> {
                                uiState.onlineArtistError?.let { message ->
                                    item("online_artists_cat_error") {
                                        SectionErrorHint(R.string.library_artists, message, topPadding = 16.dp)
                                    }
                                }
                                if (uiState.onlineArtists.isEmpty()) {
                                    if (uiState.onlineArtistError == null) {
                                        item("online_artists_empty") { SearchCategoryEmptyCard() }
                                    }
                                } else {
                                    columnItemsIndexed(
                                        uiState.onlineArtists,
                                        key = { _, artist -> "online_cat_artist_${artist.id}" },
                                    ) { _, artist ->
                                        ArtistRow(
                                            name = artist.name,
                                            artworkPath = artist.artworkPath,
                                            onClick = {
                                                if (artist.id > 0L) {
                                                    neteaseEntityActions.onOpenArtist(artist.id, artist.name)
                                                } else {
                                                    onQueryChange(artist.name)
                                                    onOnlineSubmit()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            OnlineSearchCategory.Albums -> {
                                uiState.onlineAlbumError?.let { message ->
                                    item("online_albums_cat_error") {
                                        SectionErrorHint(R.string.library_albums, message, topPadding = 16.dp)
                                    }
                                }
                                if (uiState.onlineAlbums.isEmpty()) {
                                    if (uiState.onlineAlbumError == null) {
                                        item("online_albums_empty") { SearchCategoryEmptyCard() }
                                    }
                                } else {
                                    columnItemsIndexed(
                                        uiState.onlineAlbums,
                                        key = { _, album -> "online_cat_album_${album.id}" },
                                    ) { _, album ->
                                        AlbumRow(
                                            title = album.name,
                                            artist = album.artist,
                                            artworkPath = album.artworkPath,
                                            onClick = {
                                                if (album.id > 0L) {
                                                    neteaseEntityActions.onOpenAlbum(album.id, album.name, album.coverUrl)
                                                } else {
                                                    onQueryChange(
                                                        listOf(album.artist, album.name).filter { it.isNotBlank() }.joinToString(" "),
                                                    )
                                                    onOnlineSubmit()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                "local_empty" -> item {
                    SearchEmptyState(R.string.library_search_empty_title, R.string.library_search_empty_description)
                }
                "local_no_results" -> item {
                    SearchEmptyState(R.string.library_search_no_results_title, R.string.library_search_no_results_description)
                }
                else -> {
                searchSection(R.string.library_tracks, uiState.tracks, { it.id }, rows = 3, topPadding = 20.dp) { track ->
                    TrackContextMenu(
                        track = track, expanded = contextTrack?.id == track.id,
                        onDismiss = { contextTrack = null }, playbackQueue = playbackQueue,
                        onPlayNext = onTrackPlayNext, onAddToQueue = onTrackAddToQueue,
                        onFavoriteChange = onTrackFavoriteToggle, onBottomSheetRequested = onTrackContextBottomSheet,
                    ) {
                        Column {
                            TrackRow(track.title, track.artists, artworkPath = track.artworkPath, contentPadding = PaddingValues(vertical = 6.dp), onClick = { onTrackClick(track.id) }, onMoreClick = { contextTrack = track }, onLongClick = { contextTrack = track })
                            val trackIndex = trackIndexByIdMap[track.id] ?: -1
                            if (trackHasDivider(trackIndex, uiState.tracks.lastIndex)) {
                                HorizontalDivider(
                                    color = colors.borderGlass,
                                    modifier = Modifier.width(trackDividerWidth).align(Alignment.CenterHorizontally),
                                )
                            }
                        }
                    }
                }
                searchSection(R.string.library_albums, uiState.albums, { it.id }, topPadding = 32.dp) { album ->
                    val tracks = tracksByAlbum[album.id].orEmpty()
                    AlbumContextMenu(
                        tracks = tracks, expanded = contextAlbumId == album.id, onDismiss = { contextAlbumId = null }, playbackQueue = playbackQueue,
                        onPlayNext = onAlbumPlayNext, onAddToQueue = onAlbumAddToQueue, onAddToFavorites = onAlbumAddToFavorites,
                        onBottomSheetRequested = onTrackContextBottomSheet,
                    ) {
                        DiscCard(
                            title = album.title,
                            subtitle = album.artist,
                            artworkPath = album.artworkPath,
                            fallbackSymbol = MaterialSymbols.Album,
                            onClick = { onAlbumClick(album.id) },
                            onLongClick = { contextAlbumId = album.id },
                        )
                    }
                }
                searchSection(R.string.library_artists, uiState.artists, { it.id }, topPadding = 16.dp) { artist ->
                    DiscCard(artist.name, "", artworkPath = artist.artworkPath, fallbackSymbol = MaterialSymbols.Person, artworkShape = CircleShape, onClick = { onArtistClick(artist.id) })
                }
                searchSection(R.string.library_playlists, uiState.playlists, { it.id }, topPadding = 16.dp) { playlist ->
                    DiscCard(
                        title = playlist.name,
                        subtitle = "",
                        artworkPath = playlist.trackIds.firstNotNullOfOrNull { playlistArtworkByTrackId[it] },
                        fallbackSymbol = MaterialSymbols.QueueMusic,
                        onClick = { onPlaylistClick(playlist.id) },
                    )
                }
                searchSection(R.string.library_composers, uiState.composers, { it.id }, topPadding = 16.dp) { composer ->
                    DiscCard(composer.name, "", artworkPath = composer.artworkPath, fallbackSymbol = MaterialSymbols.StylusFountainPen, artworkShape = CircleShape, onClick = { onComposerClick(composer.id) })
                }
            }
        }
        }
    }
    }
}
}

private fun <T> LazyListScope.searchSection(
    title: Int,
    values: List<T>,
    key: (T) -> String,
    rows: Int = 1,
    topPadding: androidx.compose.ui.unit.Dp = 16.dp,
    onHeaderClick: (() -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    if (values.isEmpty()) return
    item("section_$title") {
        val colors = LocalTuneColors.current
        Row(
            modifier = Modifier
                .then(
                    if (onHeaderClick != null) {
                        Modifier.clickable(onClick = onHeaderClick)
                    } else {
                        Modifier
                    },
                )
                .padding(start = 24.dp, end = 16.dp, top = topPadding, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textMuted,
            )
            if (onHeaderClick != null) {
                Spacer(Modifier.width(4.dp))
                MaterialSymbol(
                    symbol = MaterialSymbols.ChevronRight,
                    contentDescription = null,
                    size = 16.dp,
                    tint = colors.textMuted,
                )
            }
        }
    }
    item("section_content_$title") {
        if (rows == 3) {
            LazyHorizontalGrid(
                rows = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(183.dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                gridItems(values, key = { searchItemKey(title, key(it)) }, contentType = { "search_grid_$title" }) { item ->
                    Box(Modifier.widthIn(max = SearchTrackColumnMaxWidth)) { row(item) }
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems(values, key = { searchItemKey(title, key(it)) }, contentType = { "search_row_$title" }) { item ->
                    Box(Modifier.width(160.dp)) { row(item) }
                }
            }
        }
    }
}

internal fun searchItemKey(section: Int, id: String): String = "search_$section:$id"

private val SearchTrackColumnMaxWidth = 320.dp

internal fun trackHasDivider(index: Int, lastIndex: Int): Boolean = index >= 0 && index < lastIndex && index % 3 != 2

internal const val SearchEmptyStateTag = "search-empty-state"

@Composable private fun SearchEmptyState(title: Int, description: Int) = Box(
    Modifier.padding(24.dp).testTag(SearchEmptyStateTag),
) {
    HeroCard(
        title = stringResource(title),
        description = stringResource(description),
        symbol = MaterialSymbols.Search,
    )
}

/** 分区级失败提示：和“无结果”区分开，并说明是哪一个分区加载失败。 */
@Composable private fun SectionErrorHint(section: Int, message: String, topPadding: Dp) = Text(
    text = stringResource(R.string.library_search_section_failed, stringResource(section), message),
    style = MaterialTheme.typography.bodyMedium,
    color = LocalTuneColors.current.textMuted,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = topPadding, bottom = 4.dp),
)

@Composable private fun SearchHint(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = LocalTuneColors.current.textMuted,
    modifier = Modifier.padding(24.dp),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistorySection(
    items: List<String>,
    onItemClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val colors = LocalTuneColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.library_search_history_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            Text(
                text = stringResource(R.string.library_search_history_clear),
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClearAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { keyword ->
                val interactionSource = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(10.dp)
                Row(
                    modifier = Modifier
                        .pressScale(interactionSource, pressedScale = 0.95f)
                        .clip(shape)
                        .background(colors.glassElevated)
                        .border(0.5.dp, colors.borderGlass, shape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) { onItemClick(keyword) }
                        .padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MaterialSymbol(
                        symbol = MaterialSymbols.Schedule,
                        contentDescription = null,
                        size = 14.dp,
                        tint = colors.textMuted,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = keyword,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMain,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    MaterialSymbol(
                        symbol = MaterialSymbols.Close,
                        contentDescription = null,
                        size = 14.dp,
                        tint = colors.textMuted,
                        modifier = Modifier.clickable { onRemove(keyword) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchGuessSection(
    items: List<String>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onItemClick: (String) -> Unit,
) {
    val colors = LocalTuneColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.library_search_guess_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    MaterialSymbol(
                        symbol = MaterialSymbols.Refresh,
                        contentDescription = stringResource(R.string.netease_refresh),
                        size = 18.dp,
                        tint = colors.textMuted,
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { keyword ->
                val interactionSource = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .pressScale(interactionSource, pressedScale = 0.95f)
                        .clip(shape)
                        .background(colors.glassElevated)
                        .border(0.5.dp, colors.borderGlass, shape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) { onItemClick(keyword) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = keyword,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal const val SearchModeTabsTag = "search-mode-tabs"

@Composable
private fun SearchModeTabBar(
    onlineMode: Boolean,
    reduceMotion: Boolean,
    onOnlineModeChange: (Boolean) -> Unit,
) {
    TuneSegmentedTabBar(
        tabs = listOf(
            stringResource(R.string.search_tab_netease),
            stringResource(R.string.search_tab_library),
        ),
        selectedIndex = if (onlineMode) 0 else 1,
        onSelect = { index -> onOnlineModeChange(index == 0) },
        reduceMotion = reduceMotion,
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, bottom = 10.dp)
            .testTag(SearchModeTabsTag),
    )
}

@Composable
private fun OnlineSearchCategoryBar(
    selected: OnlineSearchCategory,
    songsCount: Int,
    playlistsCount: Int,
    artistsCount: Int,
    albumsCount: Int,
    onSelect: (OnlineSearchCategory) -> Unit,
) {
    val colors = LocalTuneColors.current
    val items = listOf(
        OnlineSearchCategory.All to stringResource(R.string.search_category_all),
        OnlineSearchCategory.Songs to countLabel(stringResource(R.string.search_category_songs), songsCount),
        OnlineSearchCategory.Playlists to countLabel(stringResource(R.string.search_category_playlists), playlistsCount),
        OnlineSearchCategory.Artists to countLabel(stringResource(R.string.search_category_artists), artistsCount),
        OnlineSearchCategory.Albums to countLabel(stringResource(R.string.search_category_albums), albumsCount),
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rowItems(items, key = { (cat, _) -> cat.name }) { (category, label) ->
            val active = selected == category
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) colors.primary.copy(alpha = 0.16f) else colors.glassElevated)
                    .border(
                        0.5.dp,
                        if (active) colors.primary.copy(alpha = 0.45f) else colors.borderGlass,
                        shape,
                    )
                    .clickable { onSelect(category) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (active) colors.primary else colors.textMain,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun countLabel(base: String, count: Int): String =
    if (count > 0) "$base ($count)" else base

@Composable
private fun SearchCategoryEmptyCard() = Box(Modifier.padding(24.dp)) {
    HeroCard(
        title = stringResource(R.string.library_search_no_results_title),
        description = stringResource(R.string.search_category_empty_category),
        symbol = MaterialSymbols.Search,
    )
}

@Composable
private fun OnlinePlaylistSearchRow(
    playlist: OnlinePlaylistInfo,
    onClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val bitmap = rememberArtworkThumbnail(playlist.artworkPath)
    val bitmapAlpha = rememberArtworkFadeAlpha(playlist.artworkPath, bitmap != null)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressTint by animateColorAsState(
        targetValue = if (pressed && !reduceMotion) colors.textMain.copy(alpha = 0.05f) else Color.Transparent,
        animationSpec = if (reduceMotion) tween(0) else tween(140, easing = FastOutSlowInEasing),
        label = "online-playlist-row-press-tint",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(pressTint)
            .combinedClickable(
                onClick = onClick,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(top = 6.dp, end = 8.dp, bottom = 6.dp, start = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(TuneRadii.ListArtwork))
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(TuneRadii.ListArtwork)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(bitmapAlpha),
                )
            } else {
                MaterialSymbol(
                    symbol = MaterialSymbols.QueueMusic,
                    contentDescription = null,
                    size = 22.dp,
                    tint = colors.textMuted,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = playlist.creator.ifBlank { stringResource(R.string.netease_track_count, playlist.trackCount) },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            MaterialSymbol(
                symbol = MaterialSymbols.ChevronRight,
                contentDescription = stringResource(R.string.playlist_row_open),
                size = 20.dp,
                tint = colors.textMuted,
            )
        }
    }
}
