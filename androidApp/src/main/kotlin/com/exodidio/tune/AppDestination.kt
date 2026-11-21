package com.exodidio.tune

enum class AppDestination {
    Home,
    Discover,
    Insight,
    Library,
    Search,
    Settings;

    val storageValue: String
        get() = name.lowercase()

    companion object {
        /** 可在底栏显示的标签；“统计/数据分析”和“设置”不再占底栏。 */
        val dockable: List<AppDestination> = listOf(Home, Discover, Library, Search)

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
    SettingsAppearance,
    SettingsPlayback,
    SettingsSongTransition,
    SettingsVolumeNormalization,
    SettingsEqualizer,
    SettingsAppleBackdrop,
    SettingsIntegration,
    SettingsMusicSync,
    SettingsNcmImport,
    SettingsLastFm,
    SettingsLyrics,
    SettingsAbout,
    SettingsTabLayout,
    SettingsHiddenTracks,
    SettingsNeteasePlaylists,
    SettingsDuplicates,
    SettingsInsight,
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
        AppStackPage.SettingsAppearance,
        AppStackPage.SettingsPlayback,
        AppStackPage.SettingsSongTransition,
        AppStackPage.SettingsVolumeNormalization,
        AppStackPage.SettingsEqualizer,
        AppStackPage.SettingsAppleBackdrop,
        AppStackPage.SettingsIntegration,
        AppStackPage.SettingsMusicSync,
        AppStackPage.SettingsNcmImport,
        AppStackPage.SettingsLastFm,
        AppStackPage.SettingsLyrics,
        AppStackPage.SettingsAbout,
        AppStackPage.SettingsTabLayout,
        AppStackPage.SettingsHiddenTracks,
        AppStackPage.SettingsNeteasePlaylists,
        AppStackPage.SettingsDuplicates,
        AppStackPage.SettingsInsight,
        -> AppDestination.Settings
    }

fun rootDestinationStacks(): Map<AppDestination, List<AppStackPage>> =
    AppDestination.entries.associateWith { listOf(AppStackPage.Root) }

fun List<AppStackPage>.currentStackPage(destination: AppDestination): StackPageEntry =
    StackPageEntry(destination = destination, page = last(), index = lastIndex)
