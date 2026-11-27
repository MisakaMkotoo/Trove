package com.exodidio.tune.ui.navigation

import androidx.annotation.StringRes
import com.exodidio.tune.AppDestination
import com.exodidio.tune.AppStackPage
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.MaterialSymbols

@StringRes
internal fun AppStackPage.titleRes(destination: AppDestination): Int = when (this) {
    AppStackPage.LibrarySearch -> R.string.library_search
    AppStackPage.LibraryArtists -> R.string.library_artists
    AppStackPage.ArtistDetails -> R.string.artist_details_title
    AppStackPage.LibraryAlbums -> R.string.library_albums
    AppStackPage.AlbumDetails -> R.string.album_details_title
    AppStackPage.LibraryTracks -> R.string.library_tracks
    AppStackPage.LibraryFolders -> R.string.library_folders
    AppStackPage.LibraryFolderDetails -> R.string.folder_details_title
    AppStackPage.LibraryGenres -> R.string.library_genres
    AppStackPage.GenreDetails -> R.string.genre_details_title
    AppStackPage.LibraryComposers -> R.string.library_composers
    AppStackPage.ComposerDetails -> R.string.composer_details_title
    AppStackPage.LibraryPlaylists -> R.string.library_playlists
    AppStackPage.LibraryDuplicates -> R.string.library_duplicates
    AppStackPage.PlaylistDetails -> R.string.playlist_details_title
    AppStackPage.SettingsAppearance -> R.string.appearance_title
    AppStackPage.SettingsPlayerAppearance -> R.string.appearance_player_settings
    AppStackPage.SettingsPlayback -> R.string.playback_settings_title
    AppStackPage.SettingsFunFeatures -> R.string.playback_fun_features_title
    AppStackPage.SettingsDownload -> R.string.settings_download
    AppStackPage.SettingsSongTransition -> R.string.song_transition_title
    AppStackPage.SettingsPlaybackLyrics -> R.string.playback_lyrics_title
    AppStackPage.SettingsVolumeNormalization -> R.string.playback_volume_normalization
    AppStackPage.SettingsEqualizer -> R.string.equalizer_title
    AppStackPage.SettingsAppleBackdrop -> R.string.appearance_apple_backdrop_settings
    AppStackPage.SettingsStorage -> R.string.settings_storage
    AppStackPage.SettingsDynamicBackdrop -> R.string.appearance_dynamic_backdrop_settings
    AppStackPage.SettingsDynamicBackdropMesh -> R.string.dynamic_backdrop_mesh_title
    AppStackPage.SettingsIntegration -> R.string.integration_title
    AppStackPage.SettingsMusicSync -> R.string.music_sync_title
    AppStackPage.SettingsNcmImport -> R.string.ncm_import_title
    AppStackPage.SettingsHiddenTracks -> R.string.hidden_tracks_title
    AppStackPage.SettingsNeteasePlaylists -> R.string.netease_playlists_title
    AppStackPage.SettingsDuplicates -> R.string.library_duplicates
    AppStackPage.SettingsInsight -> R.string.settings_data_analysis
    AppStackPage.SettingsServiceStatus -> R.string.settings_service_status
    AppStackPage.NeteasePlaylistDetails -> R.string.netease_playlists_title
    AppStackPage.NeteaseArtistDetails -> R.string.netease_artist_detail_title
    AppStackPage.NeteaseRecommendedPlaylists -> R.string.netease_recommended_playlists_title
    AppStackPage.DiscoverSquare -> R.string.discover_square_title
    AppStackPage.SimilarSongs -> R.string.similar_songs_title
    AppStackPage.MineCreatedPlaylists -> R.string.mine_created_title
    AppStackPage.MineSavedPlaylists -> R.string.mine_saved_title
    AppStackPage.MineSavedAlbums -> R.string.mine_albums_title
    AppStackPage.MineArtists -> R.string.mine_artists_title
    AppStackPage.MineDownloads -> R.string.mine_downloads_title
    AppStackPage.NeteasePlayRecords -> R.string.netease_play_records_title
    AppStackPage.NeteaseCloudDisk -> R.string.mine_cloud_disk_title
    AppStackPage.SettingsLyrics -> R.string.lyrics_service_title
    AppStackPage.SettingsAutoSyncModels -> R.string.lyrics_auto_align_models
    AppStackPage.SettingsAbout -> R.string.about_title
    AppStackPage.SettingsTabLayout -> R.string.tab_layout_title
    AppStackPage.Root -> destination.titleRes
}

internal val AppDestination.titleRes: Int
    @StringRes get() = when (this) {
        AppDestination.Home -> R.string.destination_home
        AppDestination.Discover -> R.string.destination_discover
        AppDestination.Mine -> R.string.destination_mine
        AppDestination.Insight -> R.string.destination_insight
        AppDestination.Library -> R.string.destination_library
        AppDestination.Search -> R.string.library_search
        AppDestination.Settings -> R.string.destination_settings
        AppDestination.Account -> R.string.account_title
    }

internal val AppDestination.placeholderRes: Int
    @StringRes get() = when (this) {
        AppDestination.Home -> R.string.placeholder_home
        AppDestination.Discover -> R.string.discover_placeholder_title
        AppDestination.Mine -> R.string.mine_placeholder_description
        AppDestination.Insight -> R.string.placeholder_insight
        AppDestination.Library -> R.string.placeholder_library
        AppDestination.Search -> R.string.library_search
        AppDestination.Settings -> R.string.placeholder_settings
        AppDestination.Account -> R.string.account_title
    }

internal val AppDestination.symbol: String
    get() = when (this) {
        AppDestination.Home -> MaterialSymbols.Home
        AppDestination.Discover -> MaterialSymbols.Sensors
        AppDestination.Mine -> MaterialSymbols.Person
        AppDestination.Insight -> MaterialSymbols.BarChart
        AppDestination.Library -> MaterialSymbols.Library
        AppDestination.Search -> MaterialSymbols.Search
        AppDestination.Settings -> MaterialSymbols.Settings
        AppDestination.Account -> MaterialSymbols.Person
    }
