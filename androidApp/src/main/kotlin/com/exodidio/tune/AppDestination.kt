package com.exodidio.tune

enum class AppDestination {
    Home,
    Discover,
    Mine,
    Insight,
    Library,
    Search,
    Settings,
    Account;

    val storageValue: String
        get() = name.lowercase()

    companion object {
        /** 可在底栏显示的标签；“统计/数据分析”和“设置”不再占底栏。 */
        val dockable: List<AppDestination> = listOf(Home, Discover, Mine, Library, Search)

        fun fromStorage(value: String?): AppDestination? =
            entries.firstOrNull { it.storageValue == value }
    }
}

/** Pages that can be placed on a destination's independent navigation stack. */
enum class AppStackPage {
    Root,
    LibrarySearch,
    LibraryArtists,
    ArtistDetails,
    LibraryAlbums,
    AlbumDetails,
    LibraryTracks,
    LibraryFolders,
    LibraryFolderDetails,
    LibraryGenres,
    GenreDetails,
    LibraryComposers,
    ComposerDetails,
    LibraryPlaylists,
    LibraryDuplicates,
    PlaylistDetails,
    SimilarSongs,
    MineCreatedPlaylists,
    MineSavedPlaylists,
    MineSavedAlbums,
    MineArtists,
    MineDownloads,
    NeteasePlayRecords,
    SettingsAppearance,
    SettingsPlayerAppearance,
    SettingsPlayback,
    SettingsFunFeatures,
    SettingsDownload,
    SettingsSongTransition,
    SettingsPlaybackLyrics,
    SettingsVolumeNormalization,
    SettingsEqualizer,
    SettingsAppleBackdrop,
    SettingsStorage,
    SettingsDynamicBackdrop,
    SettingsDynamicBackdropMesh,
    SettingsIntegration,
    SettingsMusicSync,
    SettingsNcmImport,
    SettingsLyrics,
    SettingsAutoSyncModels,
    SettingsAbout,
    SettingsTabLayout,
    SettingsHiddenTracks,
    SettingsNeteasePlaylists,
    SettingsDuplicates,
    SettingsInsight,
    SettingsServiceStatus,
    NeteasePlaylistDetails,
    NeteaseArtistDetails,
    NeteaseRecommendedPlaylists,
    DiscoverSquare,
    NeteaseCloudDisk,
}

/** Identifies the visible page and its actual position in a destination stack. */
data class StackPageEntry(
    val destination: AppDestination,
    val page: AppStackPage,
    val index: Int,
)

val AppStackPage.destination: AppDestination
    get() = when (this) {
        AppStackPage.Root,
        AppStackPage.NeteaseRecommendedPlaylists,
        -> AppDestination.Home
        AppStackPage.DiscoverSquare,
        -> AppDestination.Discover
        AppStackPage.LibrarySearch,
        AppStackPage.LibraryArtists,
        AppStackPage.ArtistDetails,
        AppStackPage.LibraryAlbums,
        AppStackPage.AlbumDetails,
        AppStackPage.LibraryTracks,
        AppStackPage.LibraryFolders,
        AppStackPage.LibraryFolderDetails,
        AppStackPage.LibraryGenres,
        AppStackPage.GenreDetails,
        AppStackPage.LibraryComposers,
        AppStackPage.ComposerDetails,
        AppStackPage.LibraryPlaylists,
        AppStackPage.LibraryDuplicates,
        AppStackPage.PlaylistDetails,
        AppStackPage.NeteasePlaylistDetails,
        AppStackPage.NeteaseArtistDetails,
        AppStackPage.SimilarSongs,
        -> AppDestination.Library
        AppStackPage.MineCreatedPlaylists,
        AppStackPage.MineSavedPlaylists,
        AppStackPage.MineSavedAlbums,
        AppStackPage.MineArtists,
        AppStackPage.MineDownloads,
        AppStackPage.NeteasePlayRecords,
        AppStackPage.NeteaseCloudDisk,
        -> AppDestination.Mine
        AppStackPage.SettingsAppearance,
        AppStackPage.SettingsPlayerAppearance,
        AppStackPage.SettingsPlayback,
        AppStackPage.SettingsFunFeatures,
        AppStackPage.SettingsDownload,
        AppStackPage.SettingsSongTransition,
        AppStackPage.SettingsPlaybackLyrics,
        AppStackPage.SettingsVolumeNormalization,
        AppStackPage.SettingsEqualizer,
        AppStackPage.SettingsAppleBackdrop,
        AppStackPage.SettingsStorage,
        AppStackPage.SettingsDynamicBackdrop,
        AppStackPage.SettingsDynamicBackdropMesh,
        AppStackPage.SettingsIntegration,
        AppStackPage.SettingsMusicSync,
        AppStackPage.SettingsNcmImport,
        AppStackPage.SettingsLyrics,
        AppStackPage.SettingsAutoSyncModels,
        AppStackPage.SettingsAbout,
        AppStackPage.SettingsTabLayout,
        AppStackPage.SettingsHiddenTracks,
        AppStackPage.SettingsNeteasePlaylists,
        AppStackPage.SettingsDuplicates,
        AppStackPage.SettingsInsight,
        AppStackPage.SettingsServiceStatus,
        -> AppDestination.Settings
    }

fun rootDestinationStacks(): Map<AppDestination, List<AppStackPage>> =
    AppDestination.entries.associateWith { listOf(AppStackPage.Root) }

fun List<AppStackPage>.currentStackPage(destination: AppDestination): StackPageEntry =
    StackPageEntry(destination = destination, page = last(), index = lastIndex)
