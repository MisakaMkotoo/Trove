package com.exodidio.tune.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.exodidio.tune.AppIntent
import com.exodidio.tune.AppStackPage
import com.exodidio.tune.LibraryDestinationModel
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.MineArtist
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.screens.LibraryAlbumsContent
import com.exodidio.tune.ui.screens.LibraryAlbumsUiState
import com.exodidio.tune.ui.screens.LibraryArtistsContent
import com.exodidio.tune.ui.screens.LibraryComposersContent
import com.exodidio.tune.ui.screens.LibraryContent
import com.exodidio.tune.ui.screens.LibraryDuplicatesContent
import com.exodidio.tune.ui.screens.LibraryFoldersContent
import com.exodidio.tune.ui.screens.LibraryGenresContent
import com.exodidio.tune.ui.screens.LibraryPlaylistsContent
import com.exodidio.tune.ui.screens.LibrarySearchContent
import com.exodidio.tune.ui.screens.LibrarySearchUiState
import com.exodidio.tune.ui.screens.LibraryTracksContent
import com.exodidio.tune.ui.screens.LibraryTracksUiState
import com.exodidio.tune.ui.screens.MergedLibraryArtistsContent
import com.exodidio.tune.ui.screens.MergedLibraryPlaylistsContent
import com.exodidio.tune.ui.screens.MineArtistsContent
import com.exodidio.tune.ui.screens.MineDownloadsContent
import com.exodidio.tune.ui.screens.MinePlaylistsContent
import com.exodidio.tune.ui.screens.MineSavedAlbumsContent
import com.exodidio.tune.ui.screens.NeteaseCloudDiskContent
import com.exodidio.tune.ui.screens.NeteasePlayRecordsContent
import com.exodidio.tune.ui.screens.NeteasePlaylistDetailsContent
import com.exodidio.tune.ui.screens.NeteaseRowStates
import dev.chrisbanes.haze.HazeState

@Composable
internal fun LibraryDestinationPages(
    page: AppStackPage,
    contentPadding: PaddingValues,
    hazeState: HazeState?,
    reduceTransparency: Boolean = false,
    library: LibraryDestinationModel,
    visibleTracks: List<LibraryTrack>,
    visibleTracksUiState: LibraryTracksUiState,
    albumsUiState: LibraryAlbumsUiState,
    searchUiState: LibrarySearchUiState,
    visiblePlaybackQueue: PlaybackQueueSnapshot,
    libraryListState: LazyListState,
    tracksListState: LazyListState,
    artistsListState: LazyListState,
    albumsListState: LazyListState,
    foldersListState: LazyListState,
    genresListState: LazyListState,
    composersListState: LazyListState,
    playlistsListState: LazyListState,
    duplicatesListState: LazyListState,
    showGenres: Boolean,
    showComposers: Boolean,
    mergeMineAndLibrary: Boolean = false,
    mergedLibraryRootTab: Int = 0,
    onMergedLibraryRootTabSelected: (Int) -> Unit = {},
    mergedPlaylistsTab: Int = 0,
    onMergedPlaylistsTabSelected: (Int) -> Unit = {},
    mergedArtistsTab: Int = 0,
    onMergedArtistsTabSelected: (Int) -> Unit = {},
    mineCreatedListState: LazyListState = rememberLazyListState(),
    mineSavedListState: LazyListState = rememberLazyListState(),
    mineSavedAlbumsListState: LazyListState = rememberLazyListState(),
    mineArtistsListState: LazyListState = rememberLazyListState(),
    mineDownloadsListState: LazyListState = rememberLazyListState(),
    neteasePlayRecordsListState: LazyListState = rememberLazyListState(),
    cloudDiskListState: LazyListState = rememberLazyListState(),
    neteaseRowStates: NeteaseRowStates = remember { NeteaseRowStates() },
    onMinePlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenNeteasePlaylist: (DiscoverPlaylist) -> Unit = {},
    onOpenNeteaseArtist: (MineArtist) -> Unit = {},
    onAlbumHeroColorChanged: (Color) -> Unit = {},
    onIntent: (AppIntent) -> Unit,
    onTrackPlayNext: (String) -> Unit,
    onTrackAddToQueue: (String) -> Unit,
    onTrackFavoriteToggle: (String, Boolean) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit,
    onTracksRemovedFromLibrary: (List<String>) -> Unit,
) {
    when (page) {
        AppStackPage.LibrarySearch -> LibrarySearchContent(
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
        AppStackPage.LibraryArtists -> if (mergeMineAndLibrary) {
            MergedLibraryArtistsContent(
                uiState = library.artists.state,
                selectedTab = mergedArtistsTab,
                onTabSelected = onMergedArtistsTabSelected,
                listState = artistsListState,
                mineArtistsListState = mineArtistsListState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
                onArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
                onFilterQueryChange = library.artists.onFilterQueryChange,
                orderedTrackIdsForArtist = library.artists.orderedTrackIds,
                onArtistPlayNext = library.artists.onPlayNext,
                onArtistAddToQueue = library.artists.onAddToQueue,
                onArtistUpdate = library.artists.onArtistUpdate,
                onTrackContextBottomSheet = onTrackContextBottomSheet,
                onOpenMineArtist = onOpenNeteaseArtist,
                hazeState = hazeState,
                playbackQueue = visiblePlaybackQueue,
            )
        } else {
            LibraryArtistsContent(
                uiState = library.artists.state,
                listState = artistsListState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
                onArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
                onFilterQueryChange = library.artists.onFilterQueryChange,
                orderedTrackIdsForArtist = library.artists.orderedTrackIds,
                onArtistPlayNext = library.artists.onPlayNext,
                onArtistAddToQueue = library.artists.onAddToQueue,
                onArtistUpdate = library.artists.onArtistUpdate,
                onTrackContextBottomSheet = onTrackContextBottomSheet,
                hazeState = hazeState,
                playbackQueue = visiblePlaybackQueue,
            )
        }
        AppStackPage.LibraryAlbums -> LibraryAlbumsContent(
            uiState = albumsUiState,
            listState = albumsListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            onAlbumClick = { album -> onIntent(AppIntent.OpenAlbumDetails(album.id)) },
            hazeState = hazeState,
            playbackQueue = visiblePlaybackQueue,
            onAlbumPlay = library.albums.onPlay,
            onPlayAll = library.albums.onPlayAll,
            onAlbumPlayNext = library.albums.onPlayNext,
            onAlbumAddToQueue = library.albums.onAddToQueue,
            onAlbumAddToFavorites = library.albums.onAddToFavorites,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onFilterQueryChange = library.albums.onFilterQueryChange,
        )
        AppStackPage.LibraryFolders -> LibraryFoldersContent(
            uiState = library.folders.state,
            listState = foldersListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            onFolderClick = { folder -> onIntent(AppIntent.OpenFolderDetails(folder.id)) },
            onFilterQueryChange = library.folders.onFilterQueryChange,
            orderedTrackIdsForFolder = library.folders.orderedTrackIds,
            onFolderPlayNext = library.folders.onPlayNext,
            onFolderAddToQueue = library.folders.onAddToQueue,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            hazeState = hazeState,
            playbackQueue = visiblePlaybackQueue,
        )
        AppStackPage.LibraryGenres -> LibraryGenresContent(
            uiState = library.genres.state,
            listState = genresListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            onGenreClick = { genre -> onIntent(AppIntent.OpenGenreDetails(genre.id)) },
            onFilterQueryChange = library.genres.onFilterQueryChange,
            orderedTrackIdsForGenre = library.genres.orderedTrackIds,
            onGenrePlayNext = library.genres.onPlayNext,
            onGenreAddToQueue = library.genres.onAddToQueue,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            hazeState = hazeState,
            playbackQueue = visiblePlaybackQueue,
        )
        AppStackPage.LibraryComposers -> LibraryComposersContent(
            uiState = library.composers.state,
            listState = composersListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            onComposerClick = { composer -> onIntent(AppIntent.OpenComposerDetails(composer.id)) },
            onFilterQueryChange = library.composers.onFilterQueryChange,
            orderedTrackIdsForComposer = library.composers.orderedTrackIds,
            onComposerPlayNext = library.composers.onPlayNext,
            onComposerAddToQueue = library.composers.onAddToQueue,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            hazeState = hazeState,
            playbackQueue = visiblePlaybackQueue,
        )
        AppStackPage.LibraryDuplicates -> LibraryDuplicatesContent(
            tracks = visibleTracks,
            listState = duplicatesListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            playbackQueue = visiblePlaybackQueue,
            onTrackClick = { track -> library.tracks.onTrackClick(track.id) },
            onTrackPlayNext = { track -> onTrackPlayNext(track.id) },
            onTrackAddToQueue = { track -> onTrackAddToQueue(track.id) },
            onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
            onTrackAlbumClick = { track -> onIntent(AppIntent.OpenAlbumDetails(track.albumId)) },
            onTrackArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onTracksRemovedFromLibrary = onTracksRemovedFromLibrary,
        )
        AppStackPage.LibraryPlaylists -> if (mergeMineAndLibrary) {
            MergedLibraryPlaylistsContent(
                uiState = library.playlists.state,
                selectedTab = mergedPlaylistsTab,
                onTabSelected = onMergedPlaylistsTabSelected,
                listState = playlistsListState,
                mineCreatedListState = mineCreatedListState,
                mineSavedListState = mineSavedListState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
                onPlaylistClick = { playlistId -> onIntent(AppIntent.OpenPlaylistDetails(playlistId)) },
                onPlaylistPlayNext = library.playlists.onPlayNext,
                onPlaylistAddToQueue = library.playlists.onAddToQueue,
                onPlaylistUpdate = library.playlists.onUpdate,
                onPlaylistDelete = library.playlists.onDelete,
                onSmartPlaylistCreate = library.playlists.onCreateSmart,
                onSmartPlaylistUpdate = library.playlists.onUpdateSmart,
                onExportM3u = { playlistId -> onIntent(AppIntent.ExportM3u(playlistId)) },
                onPlaylistDeduplicate = library.playlists.onDeduplicate,
                onNeteasePlaylistClick = { playlistId ->
                    NeteaseRepository.select(playlistId)
                    onIntent(AppIntent.OpenPageHere(AppStackPage.NeteasePlaylistDetails))
                },
                onOpenMinePlaylist = onOpenNeteasePlaylist,
            )
        } else {
            LibraryPlaylistsContent(
                uiState = library.playlists.state,
                listState = playlistsListState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
                onPlaylistClick = { playlistId -> onIntent(AppIntent.OpenPlaylistDetails(playlistId)) },
                onPlaylistPlayNext = library.playlists.onPlayNext,
                onPlaylistAddToQueue = library.playlists.onAddToQueue,
                onPlaylistUpdate = library.playlists.onUpdate,
                onPlaylistDelete = library.playlists.onDelete,
                onSmartPlaylistCreate = library.playlists.onCreateSmart,
                onSmartPlaylistUpdate = library.playlists.onUpdateSmart,
                onExportM3u = { playlistId -> onIntent(AppIntent.ExportM3u(playlistId)) },
                onPlaylistDeduplicate = library.playlists.onDeduplicate,
                onNeteasePlaylistClick = { playlistId ->
                    NeteaseRepository.select(playlistId)
                    onIntent(AppIntent.OpenPage(AppStackPage.NeteasePlaylistDetails))
                },
            )
        }
        AppStackPage.LibraryTracks -> LibraryTracksContent(
            uiState = visibleTracksUiState,
            onSortOptionSelected = library.tracks.onSortOptionSelected,
            onToggleSortOrder = library.tracks.onToggleSortOrder,
            listState = tracksListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            onTrackClick = { track -> library.tracks.onTrackClick(track.id) },
            playbackQueue = visiblePlaybackQueue,
            onTrackPlayNext = { track -> onTrackPlayNext(track.id) },
            onTrackAddToQueue = { track -> onTrackAddToQueue(track.id) },
            onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
            onTrackAlbumClick = { track -> onIntent(AppIntent.OpenAlbumDetails(track.albumId)) },
            onTrackArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
            hazeState = hazeState,
            onPlayAll = library.tracks.onPlayAll,
            onFilterQueryChange = library.tracks.onFilterQueryChange,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onTracksRemovedFromLibrary = onTracksRemovedFromLibrary,
        )
        AppStackPage.MineCreatedPlaylists -> MinePlaylistsContent(
            saved = false,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            listState = mineCreatedListState,
            onOpenPlaylist = onOpenNeteasePlaylist,
        )
        AppStackPage.MineSavedPlaylists -> MinePlaylistsContent(
            saved = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            listState = mineSavedListState,
            onOpenPlaylist = onOpenNeteasePlaylist,
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
            onOpenArtist = onOpenNeteaseArtist,
        )
        AppStackPage.MineDownloads -> MineDownloadsContent(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            listState = mineDownloadsListState,
            playbackQueue = visiblePlaybackQueue,
            onPlayTracks = onMinePlayTracks,
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
            onPlayTracks = onMinePlayTracks,
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
            onPlayTracks = onMinePlayTracks,
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
        else -> LibraryContent(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            recentTracks = visibleTracksUiState.recentTracks,
            listState = libraryListState,
            onTrackClick = library.tracks.onRecentTrackClick,
            playbackQueue = visiblePlaybackQueue,
            onTrackPlayNext = { track -> onTrackPlayNext(track.id) },
            onTrackAddToQueue = { track -> onTrackAddToQueue(track.id) },
            onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
            onTrackAlbumClick = { track -> onIntent(AppIntent.OpenAlbumDetails(track.albumId)) },
            onTrackArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onTracksSelected = {
                onIntent(AppIntent.OpenPage(AppStackPage.LibraryTracks))
            },
            onFoldersSelected = {
                onIntent(AppIntent.OpenPage(AppStackPage.LibraryFolders))
            },
            onArtistsSelected = {
                onIntent(AppIntent.OpenPage(AppStackPage.LibraryArtists))
            },
            onAlbumsSelected = {
                onIntent(AppIntent.OpenPage(AppStackPage.LibraryAlbums))
            },
            onGenresSelected = {
                onIntent(AppIntent.OpenPage(AppStackPage.LibraryGenres))
            },
            onComposersSelected = {
                onIntent(AppIntent.OpenPage(AppStackPage.LibraryComposers))
            },
            onPlaylistsSelected = {
                onIntent(AppIntent.OpenPage(AppStackPage.LibraryPlaylists))
            },
            showGenres = showGenres,
            showComposers = showComposers,
            mergedMode = mergeMineAndLibrary,
            selectedMergedTab = mergedLibraryRootTab,
            onMergedTabSelected = onMergedLibraryRootTabSelected,
            onCloudDiskSelected = {
                onIntent(AppIntent.OpenPageHere(AppStackPage.NeteaseCloudDisk))
            },
            onDownloadsSelected = {
                onIntent(AppIntent.OpenPageHere(AppStackPage.MineDownloads))
            },
            rowStates = neteaseRowStates,
            onPlayTracks = onMinePlayTracks,
            onOpenNeteasePlaylist = onOpenNeteasePlaylist,
            onOpenCreatedPlaylists = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineCreatedPlaylists)) },
            onOpenSavedPlaylists = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineSavedPlaylists)) },
            onOpenSavedAlbums = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineSavedAlbums)) },
            onOpenNeteaseAlbum = { album ->
                val idLong = album.id.toLongOrNull()
                if (idLong != null && idLong > 0L) {
                    onIntent(AppIntent.OpenNeteaseAlbum(idLong, album.name, album.coverUrl))
                }
            },
            onOpenArtists = { onIntent(AppIntent.OpenPageHere(AppStackPage.MineArtists)) },
            onOpenNeteaseArtist = onOpenNeteaseArtist,
            onOpenPlayRecords = { onIntent(AppIntent.OpenPageHere(AppStackPage.NeteasePlayRecords)) },
            onOpenAccount = { onIntent(AppIntent.OpenAccount) },
        )
    }
}
