package com.exodidio.tune.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import com.exodidio.tune.AppDestination
import com.exodidio.tune.AppDestinationModels
import com.exodidio.tune.AppIntent
import com.exodidio.tune.AppStackPage
import com.exodidio.tune.StackPageEntry
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.settings.IconStyle
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.settings.ThemeAccentColor
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.ui.components.LocalTuneBackdrop
import com.exodidio.tune.ui.components.StackPageLayout
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.exodidio.tune.ui.screens.AccountContent
import com.exodidio.tune.ui.screens.AlbumDetailsUiState
import com.exodidio.tune.ui.screens.ArtistDetailsUiState
import com.exodidio.tune.ui.screens.ComposerDetailsUiState
import com.exodidio.tune.ui.screens.DiscoverContent
import com.exodidio.tune.ui.screens.DiscoverSquareContent
import com.exodidio.tune.ui.screens.FolderDetailsUiState
import com.exodidio.tune.ui.screens.GenreDetailsUiState
import com.exodidio.tune.ui.screens.InsightContent
import com.exodidio.tune.ui.screens.LibrarySearchContent
import com.exodidio.tune.ui.screens.MineArtistsContent
import com.exodidio.tune.ui.screens.MineContent
import com.exodidio.tune.ui.screens.MineDownloadsContent
import com.exodidio.tune.ui.screens.MinePlaylistsContent
import com.exodidio.tune.ui.screens.MineSavedAlbumsContent
import com.exodidio.tune.ui.screens.NeteaseCloudDiskContent
import com.exodidio.tune.ui.screens.NeteaseHomeContent
import com.exodidio.tune.ui.screens.NeteasePlayRecordsContent
import com.exodidio.tune.ui.screens.NeteasePlaylistDetailsContent
import com.exodidio.tune.ui.screens.NeteaseRecommendedPlaylistsContent
import com.exodidio.tune.ui.screens.NeteaseRowStates
import com.exodidio.tune.ui.screens.PlaylistDetailsUiState
import com.exodidio.tune.ui.screens.albumDetailsUiStateFor
import com.exodidio.tune.ui.screens.artistDetailsUiStateFor
import com.exodidio.tune.ui.screens.composerDetailsUiStateFor
import com.exodidio.tune.ui.screens.folderDetailsUiStateFor
import com.exodidio.tune.ui.screens.genreDetailsUiStateFor
import com.exodidio.tune.ui.screens.playlistDetailsUiStateFor
import com.exodidio.tune.ui.screens.visiblePlaybackQueueAfterRemoval
import com.exodidio.tune.ui.screens.visibleTracksAfterRemoval
import com.exodidio.tune.ui.theme.LocalTuneColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

internal typealias PageKey = StackPageEntry

internal enum class ContentScrollDirection {
    Up,
    Down,
}

internal data class ContentScrollDelta(
    val direction: ContentScrollDirection,
    val distancePx: Float,
)

internal fun contentScrollDelta(consumedY: Float): ContentScrollDelta? = when {
    consumedY < 0f -> ContentScrollDelta(ContentScrollDirection.Up, -consumedY)
    consumedY > 0f -> ContentScrollDelta(ContentScrollDirection.Down, consumedY)
    else -> null
}

/**
 * 快速甩滑（fling）仅按垂直方向触发底栏收起/展开，不做滑动时长门槛限制。
 */
internal fun flingScrollDelta(
    velocityY: Float,
    minVelocityPxPerSec: Float = 150f,
): ContentScrollDelta? = when {
    velocityY < -minVelocityPxPerSec -> ContentScrollDelta(ContentScrollDirection.Up, 10_000f)
    velocityY > minVelocityPxPerSec -> ContentScrollDelta(ContentScrollDirection.Down, 10_000f)
    else -> null
}

@Composable
internal fun AppDestinationContent(
    stackPage: StackPageEntry,
    themeMode: ThemeMode,
    reduceTransparency: Boolean,
    playerTheme: PlayerTheme = PlayerTheme.Standard,
    showVolumeSlider: Boolean = false,
    fullscreenArtwork: Boolean = false,
    fullscreenLyrics: Boolean = false,
    keepScreenOn: Boolean = false,
    onKeepScreenOnChanged: (Boolean) -> Unit = {},
    lyricsFontLevel: Int = 1,
    visibleTabs: List<AppDestination> = AppDestination.entries.toList(),
    onVisibleTabsChanged: (List<AppDestination>) -> Unit = {},
    hazeState: HazeState?,
    navigationBottomPadding: Dp,
    homeListState: LazyListState,
    discoverListState: LazyListState = rememberLazyListState(),
    mineListState: LazyListState = rememberLazyListState(),
    insightListState: LazyListState = rememberLazyListState(),
    libraryListState: LazyListState = rememberLazyListState(),
    tracksListState: LazyListState = rememberLazyListState(),
    artistsListState: LazyListState = rememberLazyListState(),
    albumsListState: LazyListState = rememberLazyListState(),
    artistDetailsListState: LazyListState = rememberLazyListState(),
    folderDetailsListState: LazyListState = rememberLazyListState(),
    genreDetailsListState: LazyListState = rememberLazyListState(),
    composerDetailsListState: LazyListState = rememberLazyListState(),
    foldersListState: LazyListState = rememberLazyListState(),
    genresListState: LazyListState = rememberLazyListState(),
    composersListState: LazyListState = rememberLazyListState(),
    playlistsListState: LazyListState = rememberLazyListState(),
    duplicatesListState: LazyListState = rememberLazyListState(),
    selectedAlbumId: String? = null,
    selectedPlaylistId: String? = null,
    selectedSimilarSongId: String? = null,
    selectedArtistId: String? = null,
    selectedFolderId: String? = null,
    selectedGenreId: String? = null,
    selectedComposerId: String? = null,
    onIntent: (AppIntent) -> Unit,
    destinations: AppDestinationModels,
    playbackQueue: PlaybackQueueSnapshot,
    onTrackPlayNext: (String) -> Unit,
    onTrackAddToQueue: (String) -> Unit,
    onTrackFavoriteToggle: (String, Boolean) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    removedTrackIds: Set<String> = emptySet(),
    showGenres: Boolean = false,
    showComposers: Boolean = false,
    rememberTabPage: Boolean = true,
    mergeBottomBars: Boolean = true,
    iconStyle: IconStyle = IconStyle.Material,
    controlStyle: ControlStyle = ControlStyle.Frosted,
    liquidGlassBlurDp: Float = com.exodidio.tune.settings.DefaultLiquidGlassBlurDp,
    accentColor: ThemeAccentColor = ThemeAccentColor.Red,
    mergeMineAndLibrary: Boolean = false,
    mergedLibraryRootTab: Int = 0,
    onMergedLibraryRootTabSelected: (Int) -> Unit = {},
    mergedPlaylistsTab: Int = 0,
    onMergedPlaylistsTabSelected: (Int) -> Unit = {},
    mergedArtistsTab: Int = 0,
    onMergedArtistsTabSelected: (Int) -> Unit = {},
    onTracksRemovedFromLibrary: (List<String>) -> Unit = {},
    onAlbumHeroColorChanged: (Color) -> Unit = {},
    onSettingsContentScrolled: (Boolean) -> Unit = {},
    onContentScroll: (ContentScrollDelta) -> Unit = {},
    onEnterTerminalMode: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    // 横向滚动状态挂在 AppDestinationContent 上：进详情页返回不丢，进程退出才重置。
    val neteaseRowStates = remember { NeteaseRowStates() }
    // 我的二级页也保留滚动位置：只在进程退出时重置。
    val mineCreatedListState = remember { LazyListState() }
    val mineSavedListState = remember { LazyListState() }
    val mineSavedAlbumsListState = remember { LazyListState() }
    val mineArtistsListState = remember { LazyListState() }
    val mineDownloadsListState = remember { LazyListState() }
    val neteasePlayRecordsListState = remember { LazyListState() }
    val recommendedPlaylistsListState = remember { LazyListState() }
    val discoverSquareListState = remember { LazyListState() }
    val cloudDiskListState = remember { LazyListState() }
    // “我的”里点歌单/歌手：先占位再进详情，详情页自己后台加载。
    val openNeteasePlaylist: (DiscoverPlaylist) -> Unit = { playlist ->
        if (NeteaseRepository.playlistById(playlist.id) == null) {
            NeteaseRepository.stagePlaylist(
                playlistId = playlist.id,
                name = playlist.name,
                coverUrl = playlist.coverUrl,
                coverPath = playlist.coverPath,
                trackCount = playlist.trackCount,
                cacheToDisk = true,
            )
        }
        NeteaseRepository.select(playlist.id)
        onIntent(AppIntent.OpenPageHere(AppStackPage.NeteasePlaylistDetails))
    }
    val openNeteaseArtist: (MineArtist) -> Unit = { artist ->
        val id = artist.id.toLongOrNull()
        if (id != null) {
            NeteaseRepository.stageArtistPage(id, artist.name, artist.coverUrl)
            onIntent(AppIntent.OpenPageHere(AppStackPage.NeteaseArtistDetails))
        }
    }
    val insightUiState = destinations.insight.state
    val library = destinations.library
    val tracksUiState = library.tracks.state
    // F1 round 2: REAL library removal, centralized choke point.
    val visibleTracks = visibleTracksAfterRemoval(tracksUiState.tracks, removedTrackIds)
    val visibleTracksUiState = if (removedTrackIds.isEmpty()) tracksUiState else tracksUiState.copy(
        tracks = visibleTracks,
        recentTracks = visibleTracksAfterRemoval(tracksUiState.recentTracks, removedTrackIds),
    )
    val visiblePlaybackQueue = visiblePlaybackQueueAfterRemoval(playbackQueue, removedTrackIds)
    val albumsUiState = if (removedTrackIds.isEmpty()) library.albums.state else library.albums.state.copy(
        tracks = visibleTracksAfterRemoval(library.albums.state.tracks, removedTrackIds),
    )
    val searchUiState = if (removedTrackIds.isEmpty()) library.search.state else library.search.state.copy(
        tracks = visibleTracksAfterRemoval(library.search.state.tracks, removedTrackIds),
        allTracks = visibleTracksAfterRemoval(library.search.state.allTracks, removedTrackIds),
    )
    val albumDetailsUiState = if (removedTrackIds.isEmpty()) library.details.albums else library.details.albums.copy(
        tracks = visibleTracksAfterRemoval(library.details.albums.tracks, removedTrackIds),
    )
    val playlistDetailsUiState = if (removedTrackIds.isEmpty()) library.details.playlists else library.details.playlists.copy(
        tracks = visibleTracksAfterRemoval(library.details.playlists.tracks, removedTrackIds),
        allTracks = visibleTracksAfterRemoval(library.details.playlists.allTracks, removedTrackIds),
    )
    val artistDetailsUiState = if (removedTrackIds.isEmpty()) library.details.artists else library.details.artists.copy(
        tracks = visibleTracksAfterRemoval(library.details.artists.tracks, removedTrackIds),
    )
    val genreDetailsUiState = if (removedTrackIds.isEmpty()) library.details.genres else library.details.genres.copy(
        tracks = visibleTracksAfterRemoval(library.details.genres.tracks, removedTrackIds),
    )
    val composerDetailsUiState = if (removedTrackIds.isEmpty()) library.details.composers else library.details.composers.copy(
        tracks = visibleTracksAfterRemoval(library.details.composers.tracks, removedTrackIds),
    )
    val selectedAlbumDetails = remember(albumDetailsUiState, selectedAlbumId) {
        selectedAlbumId?.let { albumDetailsUiStateFor(albumDetailsUiState, it) } ?: AlbumDetailsUiState()
    }
    val selectedPlaylistDetails = remember(playlistDetailsUiState, selectedPlaylistId) {
        selectedPlaylistId?.let { playlistDetailsUiStateFor(playlistDetailsUiState, it) } ?: PlaylistDetailsUiState()
    }
    val selectedArtistDetails = remember(artistDetailsUiState, selectedArtistId) {
        selectedArtistId?.let { artistDetailsUiStateFor(artistDetailsUiState, it) } ?: ArtistDetailsUiState()
    }
    val selectedFolderDetails = remember(library.folders.allFolders, visibleTracks, selectedFolderId) {
        selectedFolderId?.let { folderDetailsUiStateFor(library.folders.allFolders, visibleTracks, it) }
            ?: FolderDetailsUiState()
    }
    val selectedGenreDetails = remember(genreDetailsUiState, selectedGenreId) {
        selectedGenreId?.let { genreDetailsUiStateFor(genreDetailsUiState, it) } ?: GenreDetailsUiState()
    }
    val selectedComposerDetails = remember(composerDetailsUiState, selectedComposerId) {
        selectedComposerId?.let { composerDetailsUiStateFor(composerDetailsUiState, it) } ?: ComposerDetailsUiState()
    }
    val colors = LocalTuneColors.current
    val destination = stackPage.destination
    val page = stackPage.page
    val currentOnContentScroll = rememberUpdatedState(onContentScroll)
    val currentOnSettingsContentScrolled = rememberUpdatedState(onSettingsContentScrolled)
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput || source == NestedScrollSource.SideEffect) {
                    contentScrollDelta(consumed.y)?.let(currentOnContentScroll.value)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                flingScrollDelta(available.y)?.let(currentOnContentScroll.value)
                return Velocity.Zero
            }
        }
    }
    val layerBackdrop = LocalTuneBackdrop.current as? LayerBackdrop
    Surface(
        color = colors.background,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollConnection)
            .then(if (layerBackdrop == null) Modifier else Modifier.layerBackdrop(layerBackdrop))
            .then(if (hazeState == null) Modifier else Modifier.hazeSource(hazeState)),
    ) {
        // 子树本身就是被 layerBackdrop 捕获的源，屏蔽 LocalTuneBackdrop 防止内部组件自引用递归崩溃。
        CompositionLocalProvider(LocalTuneBackdrop provides null) {
            StackPageLayout(
            title = stringResource(page.titleRes(destination)),
            hazeState = hazeState,
            contentBottomPadding = navigationBottomPadding,
            isContentScrolled = false,
            onBackClick = if (page != AppStackPage.Root) {
                { onIntent(AppIntent.NavigateBack) }
            } else {
                null
            },
            showHeader = false,
            reserveHeaderSpace = !(page == AppStackPage.Root && (destination == AppDestination.Library || destination == AppDestination.Home || destination == AppDestination.Discover || destination == AppDestination.Mine)),
        ) { modifier, contentPadding ->
            AnimatedContent(
                targetState = stackPage,
                modifier = modifier,
                transitionSpec = {
                    if (targetState.destination != initialState.destination) {
                        fadeIn(animationSpec = tween(durationMillis = 200)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 200))
                    } else if (isForwardTransition(targetState, initialState)) {
                        (slideInHorizontally { it } togetherWith
                            slideOutHorizontally { -it / 4 }).apply {
                            targetContentZIndex = 1f
                        }
                    } else {
                        (slideInHorizontally { -it / 4 } togetherWith
                            slideOutHorizontally { it }).apply {
                            targetContentZIndex = 0f
                        }
                    }
                },
                label = "stack-page-content",
            ) { currentPage ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .pointerInput(focusManager) {
                            detectTapGestures { focusManager.clearFocus() }
                        },
                ) {
                    // 详情页在任何标签内都可打开：压在来源标签的栈上，返回时原路返回。
                    if (isSharedEntityDetailsPage(currentPage.page)) {
                        SharedEntityDetailsPage(
                            page = currentPage.page,
                            contentPadding = contentPadding,
                            hazeState = hazeState,
                            reduceTransparency = reduceTransparency,
                            library = library,
                            selectedAlbumId = selectedAlbumId,
                            selectedPlaylistId = selectedPlaylistId,
                            selectedSimilarSongId = selectedSimilarSongId,
                            selectedArtistId = selectedArtistId,
                            selectedFolderId = selectedFolderId,
                            selectedGenreId = selectedGenreId,
                            selectedComposerId = selectedComposerId,
                            selectedAlbumDetails = selectedAlbumDetails,
                            selectedPlaylistDetails = selectedPlaylistDetails,
                            selectedArtistDetails = selectedArtistDetails,
                            selectedFolderDetails = selectedFolderDetails,
                            selectedGenreDetails = selectedGenreDetails,
                            selectedComposerDetails = selectedComposerDetails,
                            artistDetailsListState = artistDetailsListState,
                            folderDetailsListState = folderDetailsListState,
                            genreDetailsListState = genreDetailsListState,
                            composerDetailsListState = composerDetailsListState,
                            visiblePlaybackQueue = visiblePlaybackQueue,
                            onIntent = onIntent,
                            onAlbumHeroColorChanged = onAlbumHeroColorChanged,
                            onTrackPlayNext = onTrackPlayNext,
                            onTrackAddToQueue = onTrackAddToQueue,
                            onTrackFavoriteToggle = onTrackFavoriteToggle,
                            onTrackContextBottomSheet = onTrackContextBottomSheet,
                        )
                    } else {
                        when (currentPage.destination) {
                            AppDestination.Account -> AccountContent(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = contentPadding,
                            )
                            AppDestination.Search -> when (currentPage.page) {
                                AppStackPage.Root -> LibrarySearchContent(
                                    uiState = searchUiState,
                                    contentPadding = contentPadding,
                                    onQueryChange = library.search.onQueryChange,
                                    onTrackClick = library.search.onTrackClick,
                                    onOnlineModeChange = library.search.onOnlineModeChange,
                                    onOnlineCategoryChange = library.search.onOnlineCategoryChange,
                                    onOnlineSubmit = library.search.onOnlineSubmit,
                                    onOnlineTrackPlay = library.search.onOnlineTrackPlay,
                                    onOnlinePlaylistClick = library.search.onOnlinePlaylistClick,
                                    onAlbumClick = { onIntent(AppIntent.OpenAlbumDetails(it)) },
                                    onArtistClick = { onIntent(AppIntent.OpenArtistDetails(it)) },
                                    onPlaylistClick = { onIntent(AppIntent.OpenPlaylistDetails(it)) },
                                    onComposerClick = { onIntent(AppIntent.OpenComposerDetails(it)) },
                                    playbackQueue = visiblePlaybackQueue,
                                    onTrackPlayNext = { onTrackPlayNext(it.id) },
                                    onTrackAddToQueue = { onTrackAddToQueue(it.id) },
                                    onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                    onAlbumPlayNext = library.albums.onPlayNext,
                                    onAlbumAddToQueue = library.albums.onAddToQueue,
                                    onAlbumAddToFavorites = library.albums.onAddToFavorites,
                                )
                                AppStackPage.NeteasePlaylistDetails -> NeteasePlaylistDetailsContent(
                                    contentPadding = contentPadding,
                                    modifier = Modifier.fillMaxSize(),
                                    hazeState = hazeState,
                                    reduceTransparency = reduceTransparency,
                                    onOpenAlbum = { albumId -> onIntent(AppIntent.OpenAlbumDetails(albumId)) },
                                    onOpenArtist = { artistId -> onIntent(AppIntent.OpenArtistDetails(artistId)) },
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                    onNavigateBack = { onIntent(AppIntent.NavigateBack) },
                                    onHeroColorChanged = onAlbumHeroColorChanged,
                                )
                                else -> Unit
                            }
                            AppDestination.Discover -> DiscoverDestinationContent {
                                val discover = destinations.discover
                                when (currentPage.page) {
                                    AppStackPage.Root -> DiscoverContent(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = contentPadding,
                                        listState = discoverListState,
                                        rowStates = neteaseRowStates,
                                        playbackQueue = visiblePlaybackQueue,
                                        onPlayTracks = discover.onPlayTracks,
                                        onOpenPlaylist = discover.onOpenPlaylist,
                                        onTrackPlayNext = { onTrackPlayNext(it.id) },
                                        onTrackAddToQueue = { onTrackAddToQueue(it.id) },
                                        onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                                        onOpenAccount = { onIntent(AppIntent.OpenAccount) },
                                        onOpenSquare = { tab, category ->
                                            NeteaseRepository.openDiscoverSquare(tab, category)
                                            onIntent(AppIntent.OpenPageHere(AppStackPage.DiscoverSquare))
                                        },
                                    )
                                    AppStackPage.DiscoverSquare -> DiscoverSquareContent(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = contentPadding,
                                        listState = discoverSquareListState,
                                        onOpenPlaylist = discover.onOpenPlaylist,
                                        onOpenAlbum = { albumId, name, coverUrl ->
                                            onIntent(AppIntent.OpenNeteaseAlbum(albumId, name, coverUrl))
                                        },
                                        onOpenArtist = { artistId, name, _ ->
                                            onIntent(AppIntent.OpenNeteaseArtist(artistId, name))
                                        },
                                    )
                                    AppStackPage.NeteasePlaylistDetails -> NeteasePlaylistDetailsContent(
                                        contentPadding = contentPadding,
                                        modifier = Modifier.fillMaxSize(),
                                        hazeState = hazeState,
                                        reduceTransparency = reduceTransparency,
                                        onOpenAlbum = { albumId -> onIntent(AppIntent.OpenAlbumDetails(albumId)) },
                                        onOpenArtist = { artistId -> onIntent(AppIntent.OpenArtistDetails(artistId)) },
                                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                                        onNavigateBack = { onIntent(AppIntent.NavigateBack) },
                                        onHeroColorChanged = onAlbumHeroColorChanged,
                                    )
                                    else -> Unit
                                }
                            }
                            AppDestination.Home -> HomeDestinationContent {
                                when (currentPage.page) {
                                    AppStackPage.Root -> NeteaseHomeContent(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = contentPadding,
                                        listState = homeListState,
                                        rowStates = neteaseRowStates,
                                        playbackQueue = visiblePlaybackQueue,
                                        onPlayTracks = destinations.home.onPlayTracks,
                                        onTrackPlayNext = { onTrackPlayNext(it.id) },
                                        onTrackAddToQueue = { onTrackAddToQueue(it.id) },
                                        onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                                        onOpenPlaylist = { onIntent(AppIntent.OpenPageHere(AppStackPage.NeteasePlaylistDetails)) },
                                        onOpenRecommendedPlaylists = { onIntent(AppIntent.OpenPageHere(AppStackPage.NeteaseRecommendedPlaylists)) },
                                        onOpenAccount = { onIntent(AppIntent.OpenAccount) },
                                    )
                                    AppStackPage.NeteaseRecommendedPlaylists -> NeteaseRecommendedPlaylistsContent(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = contentPadding,
                                        listState = recommendedPlaylistsListState,
                                        onOpenPlaylist = openNeteasePlaylist,
                                    )
                                    AppStackPage.NeteasePlaylistDetails -> NeteasePlaylistDetailsContent(
                                        contentPadding = contentPadding,
                                        modifier = Modifier.fillMaxSize(),
                                        hazeState = hazeState,
                                        reduceTransparency = reduceTransparency,
                                        onOpenAlbum = { albumId -> onIntent(AppIntent.OpenAlbumDetails(albumId)) },
                                        onOpenArtist = { artistId -> onIntent(AppIntent.OpenArtistDetails(artistId)) },
                                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                                        onNavigateBack = { onIntent(AppIntent.NavigateBack) },
                                        onHeroColorChanged = onAlbumHeroColorChanged,
                                    )
                                    else -> Unit
                                }
                            }
                            AppDestination.Mine -> when (currentPage.page) {
                                AppStackPage.Root -> MineContent(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    listState = mineListState,
                                    rowStates = neteaseRowStates,
                                    playbackQueue = visiblePlaybackQueue,
                                    onPlayTracks = destinations.mine.onPlayTracks,
                                    onTrackPlayNext = { onTrackPlayNext(it.id) },
                                    onTrackAddToQueue = { onTrackAddToQueue(it.id) },
                                    onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                    onOpenPlaylist = openNeteasePlaylist,
                                    onOpenCreatedPlaylists = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineCreatedPlaylists)) },
                                    onOpenSavedPlaylists = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineSavedPlaylists)) },
                                    onOpenSavedAlbums = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineSavedAlbums)) },
                                    onOpenAlbum = { album ->
                                        val idLong = album.id.toLongOrNull()
                                        if (idLong != null && idLong > 0L) {
                                            onIntent(AppIntent.OpenNeteaseAlbum(idLong, album.name, album.coverUrl))
                                        }
                                    },
                                    onOpenArtists = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineArtists)) },
                                    onOpenArtist = openNeteaseArtist,
                                    onOpenPlayRecords = { onIntent(AppIntent.OpenPageHere(AppStackPage.NeteasePlayRecords)) },
                                    onOpenCloudDisk = { onIntent(AppIntent.OpenPageHere(AppStackPage.NeteaseCloudDisk)) },
                                    onOpenDownloads = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineDownloads)) },
                                    onOpenAccount = { onIntent(AppIntent.OpenAccount) },
                                )
                                AppStackPage.MineCreatedPlaylists -> MinePlaylistsContent(
                                    saved = false,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    listState = mineCreatedListState,
                                    onOpenPlaylist = openNeteasePlaylist,
                                )
                                AppStackPage.MineSavedPlaylists -> MinePlaylistsContent(
                                    saved = true,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    listState = mineSavedListState,
                                    onOpenPlaylist = openNeteasePlaylist,
                                )
                                AppStackPage.MineSavedAlbums -> MineSavedAlbumsContent(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    listState = mineSavedAlbumsListState,
                                    onOpenAlbum = { album ->
                                        val idLong = album.id.toLongOrNull()
                                        if (idLong != null && idLong > 0L) {
                                            onIntent(AppIntent.OpenNeteaseAlbum(idLong, album.name, album.coverUrl))
                                        }
                                    },
                                )
                                AppStackPage.MineArtists -> MineArtistsContent(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    listState = mineArtistsListState,
                                    onOpenArtist = openNeteaseArtist,
                                )
                                AppStackPage.MineDownloads -> MineDownloadsContent(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    listState = mineDownloadsListState,
                                    playbackQueue = visiblePlaybackQueue,
                                    onPlayTracks = destinations.mine.onPlayTracks,
                                    onTrackPlayNext = { onTrackPlayNext(it.id) },
                                    onTrackAddToQueue = { onTrackAddToQueue(it.id) },
                                    onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                )
                                AppStackPage.NeteasePlayRecords -> NeteasePlayRecordsContent(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    listState = neteasePlayRecordsListState,
                                    playbackQueue = visiblePlaybackQueue,
                                    onPlayTracks = destinations.mine.onPlayTracks,
                                    onTrackPlayNext = { onTrackPlayNext(it.id) },
                                    onTrackAddToQueue = { onTrackAddToQueue(it.id) },
                                    onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                )
                                AppStackPage.NeteaseCloudDisk -> NeteaseCloudDiskContent(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    hazeState = hazeState,
                                    listState = cloudDiskListState,
                                    playbackQueue = visiblePlaybackQueue,
                                    onPlayTracks = destinations.mine.onPlayTracks,
                                    onTrackPlayNext = { onTrackPlayNext(it.id) },
                                    onTrackAddToQueue = { onTrackAddToQueue(it.id) },
                                    onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                )
                                AppStackPage.NeteasePlaylistDetails -> NeteasePlaylistDetailsContent(
                                    contentPadding = contentPadding,
                                    modifier = Modifier.fillMaxSize(),
                                    hazeState = hazeState,
                                    reduceTransparency = reduceTransparency,
                                    onOpenAlbum = { albumId -> onIntent(AppIntent.OpenAlbumDetails(albumId)) },
                                    onOpenArtist = { artistId -> onIntent(AppIntent.OpenArtistDetails(artistId)) },
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                    onNavigateBack = { onIntent(AppIntent.NavigateBack) },
                                    onHeroColorChanged = onAlbumHeroColorChanged,
                                )
                                else -> Unit
                            }
                            AppDestination.Insight -> InsightDestinationContent {
                                InsightContent(
                                    state = insightUiState,
                                    listState = insightListState,
                                    contentPadding = contentPadding,
                                    onLibraryPeriodSelected = destinations.insight.onLibraryPeriodSelected,
                                    onListeningPeriodSelected = destinations.insight.onListeningPeriodSelected,
                                    onArtistClick = { onIntent(AppIntent.OpenArtistDetails(it)) },
                                    onTrackClick = destinations.insight.onTrackClick,
                                    onReportYearSelected = destinations.insight.onReportYearSelected,
                                    onFootprintClick = destinations.insight.onFootprintClick,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            AppDestination.Settings -> SettingsDestinationContent {
                                SettingsDestinationPages(
                                    currentPage = currentPage,
                                    stackPage = stackPage,
                                    contentPadding = contentPadding,
                                    hazeState = hazeState,
                                    themeMode = themeMode,
                                    reduceTransparency = reduceTransparency,
                                    playerTheme = playerTheme,
                                    showVolumeSlider = showVolumeSlider,
                                    fullscreenArtwork = fullscreenArtwork,
                                    keepScreenOn = keepScreenOn,
                                    lyricsFontLevel = lyricsFontLevel,
                                    visibleTabs = visibleTabs,
                                    onVisibleTabsChanged = onVisibleTabsChanged,
                                    showGenres = showGenres,
                                    showComposers = showComposers,
                                    rememberTabPage = rememberTabPage,
                                    mergeBottomBars = mergeBottomBars,
                                    iconStyle = iconStyle,
                                    controlStyle = controlStyle,
                                    liquidGlassBlurDp = liquidGlassBlurDp,
                                    accentColor = accentColor,
                                    mergeMineAndLibrary = mergeMineAndLibrary,
                                    destinations = destinations,
                                    visibleTracks = visibleTracks,
                                    visiblePlaybackQueue = visiblePlaybackQueue,
                                    insightListState = insightListState,
                                    duplicatesListState = duplicatesListState,
                                    onIntent = onIntent,
                                    onTrackPlayNext = onTrackPlayNext,
                                    onTrackAddToQueue = onTrackAddToQueue,
                                    onTrackFavoriteToggle = onTrackFavoriteToggle,
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                    onTracksRemovedFromLibrary = onTracksRemovedFromLibrary,
                                    onSettingsContentScrolled = currentOnSettingsContentScrolled.value,
                                    onEnterTerminalMode = onEnterTerminalMode,
                                )
                            }
                            AppDestination.Library -> LibraryDestinationContent {
                                LibraryDestinationPages(
                                    page = currentPage.page,
                                    contentPadding = contentPadding,
                                    hazeState = hazeState,
                                    reduceTransparency = reduceTransparency,
                                    library = library,
                                    visibleTracks = visibleTracks,
                                    visibleTracksUiState = visibleTracksUiState,
                                    albumsUiState = albumsUiState,
                                    searchUiState = searchUiState,
                                    visiblePlaybackQueue = visiblePlaybackQueue,
                                    libraryListState = libraryListState,
                                    tracksListState = tracksListState,
                                    artistsListState = artistsListState,
                                    albumsListState = albumsListState,
                                    foldersListState = foldersListState,
                                    genresListState = genresListState,
                                    composersListState = composersListState,
                                    playlistsListState = playlistsListState,
                                    duplicatesListState = duplicatesListState,
                                    showGenres = showGenres,
                                    showComposers = showComposers,
                                    mergeMineAndLibrary = mergeMineAndLibrary,
                                    mergedLibraryRootTab = mergedLibraryRootTab,
                                    onMergedLibraryRootTabSelected = onMergedLibraryRootTabSelected,
                                    mergedPlaylistsTab = mergedPlaylistsTab,
                                    onMergedPlaylistsTabSelected = onMergedPlaylistsTabSelected,
                                    mergedArtistsTab = mergedArtistsTab,
                                    onMergedArtistsTabSelected = onMergedArtistsTabSelected,
                                    mineCreatedListState = mineCreatedListState,
                                    mineSavedListState = mineSavedListState,
                                    mineSavedAlbumsListState = mineSavedAlbumsListState,
                                    mineArtistsListState = mineArtistsListState,
                                    mineDownloadsListState = mineDownloadsListState,
                                    neteasePlayRecordsListState = neteasePlayRecordsListState,
                                    cloudDiskListState = cloudDiskListState,
                                    neteaseRowStates = neteaseRowStates,
                                    onMinePlayTracks = destinations.mine.onPlayTracks,
                                    onOpenNeteasePlaylist = openNeteasePlaylist,
                                    onOpenNeteaseArtist = openNeteaseArtist,
                                    onAlbumHeroColorChanged = onAlbumHeroColorChanged,
                                    onIntent = onIntent,
                                    onTrackPlayNext = onTrackPlayNext,
                                    onTrackAddToQueue = onTrackAddToQueue,
                                    onTrackFavoriteToggle = onTrackFavoriteToggle,
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                    onTracksRemovedFromLibrary = onTracksRemovedFromLibrary,
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun HomeDestinationContent(content: @Composable () -> Unit) = content()

@Composable
internal fun DiscoverDestinationContent(content: @Composable () -> Unit) = content()

@Composable
internal fun InsightDestinationContent(content: @Composable () -> Unit) = content()

@Composable
internal fun LibraryDestinationContent(content: @Composable () -> Unit) = content()

@Composable
internal fun SettingsDestinationContent(content: @Composable () -> Unit) = content()

internal fun isForwardTransition(target: PageKey, initial: PageKey): Boolean =
    target.index > initial.index
