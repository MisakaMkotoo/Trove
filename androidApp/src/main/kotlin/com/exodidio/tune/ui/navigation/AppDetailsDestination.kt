package com.exodidio.tune.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.exodidio.tune.AppIntent
import com.exodidio.tune.AppStackPage
import com.exodidio.tune.LibraryDestinationModel
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.screens.AlbumDetailsContent
import com.exodidio.tune.ui.screens.AlbumDetailsUiState
import com.exodidio.tune.ui.screens.ArtistDetailsContent
import com.exodidio.tune.ui.screens.ArtistDetailsUiState
import com.exodidio.tune.ui.screens.ComposerDetailsContent
import com.exodidio.tune.ui.screens.ComposerDetailsUiState
import com.exodidio.tune.ui.screens.FolderDetailsContent
import com.exodidio.tune.ui.screens.FolderDetailsUiState
import com.exodidio.tune.ui.screens.GenreDetailsContent
import com.exodidio.tune.ui.screens.GenreDetailsUiState
import com.exodidio.tune.ui.screens.NeteaseArtistDetailsContent
import com.exodidio.tune.ui.screens.NeteasePlaylistDetailsContent
import com.exodidio.tune.ui.screens.PlaylistDetailsContent
import com.exodidio.tune.ui.screens.PlaylistDetailsUiState
import com.exodidio.tune.ui.screens.SimilarSongsContent
import dev.chrisbanes.haze.HazeState

internal fun isSharedEntityDetailsPage(page: AppStackPage): Boolean = page == AppStackPage.AlbumDetails ||
    page == AppStackPage.ArtistDetails ||
    page == AppStackPage.LibraryFolderDetails ||
    page == AppStackPage.GenreDetails ||
    page == AppStackPage.ComposerDetails ||
    page == AppStackPage.PlaylistDetails ||
    page == AppStackPage.SimilarSongs ||
    page == AppStackPage.NeteasePlaylistDetails ||
    page == AppStackPage.NeteaseArtistDetails

@Composable
internal fun SharedEntityDetailsPage(
    page: AppStackPage,
    contentPadding: PaddingValues,
    hazeState: HazeState?,
    reduceTransparency: Boolean,
    library: LibraryDestinationModel,
    selectedAlbumId: String?,
    selectedPlaylistId: String?,
    selectedSimilarSongId: String?,
    selectedArtistId: String?,
    selectedFolderId: String?,
    selectedGenreId: String?,
    selectedComposerId: String?,
    selectedAlbumDetails: AlbumDetailsUiState,
    selectedPlaylistDetails: PlaylistDetailsUiState,
    selectedArtistDetails: ArtistDetailsUiState,
    selectedFolderDetails: FolderDetailsUiState,
    selectedGenreDetails: GenreDetailsUiState,
    selectedComposerDetails: ComposerDetailsUiState,
    artistDetailsListState: LazyListState,
    folderDetailsListState: LazyListState,
    genreDetailsListState: LazyListState,
    composerDetailsListState: LazyListState,
    visiblePlaybackQueue: PlaybackQueueSnapshot,
    onIntent: (AppIntent) -> Unit,
    onAlbumHeroColorChanged: (Color) -> Unit,
    onTrackPlayNext: (String) -> Unit,
    onTrackAddToQueue: (String) -> Unit,
    onTrackFavoriteToggle: (String, Boolean) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit,
) {
    when (page) {
        AppStackPage.AlbumDetails -> AlbumDetailsContent(
            uiState = selectedAlbumDetails,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            hazeState = hazeState,
            reduceTransparency = reduceTransparency,
            onHeroColorChanged = onAlbumHeroColorChanged,
            onPlay = { selectedAlbumId?.let { library.albums.onPlay(it, false) } },
            onShuffle = { selectedAlbumId?.let { library.albums.onPlay(it, true) } },
            onTrackClick = { trackId -> selectedAlbumId?.let { library.albums.onTrackPlay(it, trackId) } },
            playbackQueue = visiblePlaybackQueue,
            onTrackPlayNext = onTrackPlayNext,
            onTrackAddToQueue = onTrackAddToQueue,
            onTrackFavoriteToggle = onTrackFavoriteToggle,
            onTrackArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
            onAlbumPlayNext = library.albums.onPlayNext,
            onAlbumAddToQueue = library.albums.onAddToQueue,
            onAlbumAddToFavorites = library.albums.onAddToFavorites,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
        )
        AppStackPage.ArtistDetails -> ArtistDetailsContent(
            uiState = selectedArtistDetails,
            listState = artistDetailsListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            hazeState = hazeState,
            reduceTransparency = reduceTransparency,
            onHeroColorChanged = onAlbumHeroColorChanged,
            onPlay = { selectedArtistId?.let { library.artists.onPlay(it, false) } },
            onShuffle = { selectedArtistId?.let { library.artists.onPlay(it, true) } },
            onPlayNext = library.artists.onPlayNext,
            onAddToQueue = library.artists.onAddToQueue,
            onArtistUpdate = library.artists.onArtistUpdate,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onAlbumClick = { album -> onIntent(AppIntent.OpenAlbumDetails(album.id)) },
            playbackQueue = visiblePlaybackQueue,
        )
        AppStackPage.LibraryFolderDetails -> FolderDetailsContent(
            uiState = selectedFolderDetails,
            listState = folderDetailsListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            hazeState = hazeState,
            reduceTransparency = reduceTransparency,
            onHeroColorChanged = onAlbumHeroColorChanged,
            onPlay = { selectedFolderId?.let { library.folders.onPlay(it, false) } },
            onShuffle = { selectedFolderId?.let { library.folders.onPlay(it, true) } },
            onTrackClick = { trackId -> selectedFolderId?.let { library.folders.onTrackPlay(it, trackId) } },
            playbackQueue = visiblePlaybackQueue,
            onTrackPlayNext = onTrackPlayNext,
            onTrackAddToQueue = onTrackAddToQueue,
            onTrackFavoriteToggle = onTrackFavoriteToggle,
            onTrackAlbumClick = { albumId -> onIntent(AppIntent.OpenAlbumDetails(albumId)) },
            onTrackArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
            onFolderPlayNext = library.folders.onPlayNext,
            onFolderAddToQueue = library.folders.onAddToQueue,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
        )
        AppStackPage.GenreDetails -> GenreDetailsContent(
            uiState = selectedGenreDetails,
            listState = genreDetailsListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            hazeState = hazeState,
            reduceTransparency = reduceTransparency,
            onHeroColorChanged = onAlbumHeroColorChanged,
            onPlay = { selectedGenreId?.let { library.genres.onPlay(it, false) } },
            onShuffle = { selectedGenreId?.let { library.genres.onPlay(it, true) } },
            onPlayNext = library.genres.onPlayNext,
            onAddToQueue = library.genres.onAddToQueue,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onAlbumClick = { album -> onIntent(AppIntent.OpenAlbumDetails(album.id)) },
            playbackQueue = visiblePlaybackQueue,
        )
        AppStackPage.ComposerDetails -> ComposerDetailsContent(
            uiState = selectedComposerDetails,
            listState = composerDetailsListState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            hazeState = hazeState,
            reduceTransparency = reduceTransparency,
            onHeroColorChanged = onAlbumHeroColorChanged,
            onPlay = { selectedComposerId?.let { library.composers.onPlay(it, false) } },
            onShuffle = { selectedComposerId?.let { library.composers.onPlay(it, true) } },
            onPlayNext = library.composers.onPlayNext,
            onAddToQueue = library.composers.onAddToQueue,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onAlbumClick = { album -> onIntent(AppIntent.OpenAlbumDetails(album.id)) },
            playbackQueue = visiblePlaybackQueue,
        )
        AppStackPage.PlaylistDetails -> PlaylistDetailsContent(
            uiState = selectedPlaylistDetails,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            hazeState = hazeState,
            reduceTransparency = reduceTransparency,
            onHeroColorChanged = onAlbumHeroColorChanged,
            onPlay = { selectedPlaylistId?.let { library.playlists.onPlay(it, false) } },
            onShuffle = { selectedPlaylistId?.let { library.playlists.onPlay(it, true) } },
            onTrackClick = { trackId -> selectedPlaylistId?.let { library.playlists.onTrackPlay(it, trackId) } },
            playbackQueue = visiblePlaybackQueue,
            onTrackPlayNext = onTrackPlayNext,
            onTrackAddToQueue = onTrackAddToQueue,
            onTrackFavoriteToggle = onTrackFavoriteToggle,
            onTrackArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
            onTrackRemoveFromPlaylist = { trackId -> selectedPlaylistId?.let { library.playlists.onTrackRemove(it, trackId) } },
            onTrackMove = { trackId, previousTrackId, nextTrackId ->
                selectedPlaylistId?.let { library.playlists.onTrackMove(it, trackId, previousTrackId, nextTrackId) }
            },
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onPlaylistPlayNext = library.playlists.onPlayNext,
            onPlaylistAddToQueue = library.playlists.onAddToQueue,
            onPlaylistUpdate = library.playlists.onUpdate,
            onSmartPlaylistUpdate = library.playlists.onUpdateSmart,
            onExportM3u = { selectedPlaylistId?.let { onIntent(AppIntent.ExportM3u(it)) } },
            onPlaylistDeduplicate = library.playlists.onDeduplicate,
            onPlaylistDelete = { playlistId ->
                library.playlists.onDelete(playlistId)
                onIntent(AppIntent.NavigateBack)
            },
        )
        AppStackPage.SimilarSongs -> SimilarSongsContent(
            songId = selectedSimilarSongId,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            playbackQueue = visiblePlaybackQueue,
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
        AppStackPage.NeteaseArtistDetails -> NeteaseArtistDetailsContent(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            reduceTransparency = reduceTransparency,
            playbackQueue = visiblePlaybackQueue,
            onOpenAlbum = { albumId, name, coverUrl ->
                onIntent(AppIntent.OpenNeteaseAlbum(albumId, name, coverUrl))
            },
            onOpenArtist = { artistId, name ->
                onIntent(AppIntent.OpenNeteaseArtist(artistId, name))
            },
            onTrackPlayNext = onTrackPlayNext,
            onTrackAddToQueue = onTrackAddToQueue,
            onTrackFavoriteToggle = onTrackFavoriteToggle,
            onTrackContextBottomSheet = onTrackContextBottomSheet,
            onHeroColorChanged = onAlbumHeroColorChanged,
        )
        else -> Unit
    }
}
