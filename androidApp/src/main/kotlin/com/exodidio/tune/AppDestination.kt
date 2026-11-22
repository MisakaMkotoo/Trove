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
    MineArtists,
    SettingsAppearance,
    SettingsPlayback,
    SettingsSongTransition,
    SettingsPlaybackLyrics,
    SettingsVolumeNormalization,
    SettingsEqualizer,
    SettingsAppleBackdrop,
    SettingsIntegration,
    SettingsMusicSync,
    SettingsNcmImport,
    SettingsLyrics,
    SettingsAbout,
    SettingsTabLayout,
    SettingsHiddenTracks,
    SettingsNeteasePlaylists,
    SettingsDuplicates,
    SettingsInsight,
    SettingsServiceStatus,
    NeteasePlaylistDetails,
}

/** Identifies the visible page and its actual position in a destination stack. */
data class StackPageEntry(
    val destination: AppDestination,
    val page: AppStackPage,
    val index: Int,
)

val AppStackPage.destination: AppDestination
    get() = when (this) {
        AppStackPage.Root -> AppDestination.Home
        AppStackPage.LibrarySearch,
        AppStackPage.LibraryArtists,
        AppStackPage.ArtistDetails,
        AppStackPage.LibraryAlbums,
        AppStackPage.AlbumDetails,
        AppStackPage.LibraryTracks,
        AppStackPage.LibraryGenres,
        AppStackPage.GenreDetails,
        AppStackPage.LibraryComposers,
        AppStackPage.ComposerDetails,
        AppStackPage.LibraryPlaylists,
        AppStackPage.LibraryDuplicates,
        AppStackPage.PlaylistDetails,
        AppStackPage.NeteasePlaylistDetails,
        AppStackPage.SimilarSongs,
        -> AppDestination.Library
        AppStackPage.MineCreatedPlaylists,
        AppStackPage.MineSavedPlaylists,
        AppStackPage.MineArtists,
        -> AppDestination.Mine
        AppStackPage.SettingsAppearance,
        AppStackPage.SettingsPlayback,
        AppStackPage.SettingsSongTransition,
        AppStackPage.SettingsPlaybackLyrics,
        AppStackPage.SettingsVolumeNormalization,
        AppStackPage.SettingsEqualizer,
        AppStackPage.SettingsAppleBackdrop,
        AppStackPage.SettingsIntegration,
        AppStackPage.SettingsMusicSync,
        AppStackPage.SettingsNcmImport,
        AppStackPage.SettingsLyrics,
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
