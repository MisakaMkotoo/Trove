package com.exodidio.tune.ui.navigation

import com.exodidio.tune.AppDestination
import com.exodidio.tune.AppStackPage
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.MaterialSymbols
import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationMetadataTest {
    @Test
    fun libraryDestinationUsesMusicVideoSymbol() {
        assertEquals(MaterialSymbols.Library, AppDestination.Library.symbol)
        assertEquals("music_video", AppDestination.Library.symbol)
    }

    @Test
    fun mapsDestinationsToCorrectSymbols() {
        assertEquals(
            listOf(
                AppDestination.Home,
                AppDestination.Discover,
                AppDestination.Mine,
                AppDestination.Insight,
                AppDestination.Library,
                AppDestination.Search,
                AppDestination.Settings,
                AppDestination.Account,
            ),
            AppDestination.entries,
        )
        assertEquals(MaterialSymbols.Home, AppDestination.Home.symbol)
        assertEquals(MaterialSymbols.Sensors, AppDestination.Discover.symbol)
        assertEquals(MaterialSymbols.Person, AppDestination.Mine.symbol)
        assertEquals(MaterialSymbols.BarChart, AppDestination.Insight.symbol)
        assertEquals(MaterialSymbols.Library, AppDestination.Library.symbol)
        assertEquals(MaterialSymbols.Search, AppDestination.Search.symbol)
        assertEquals(MaterialSymbols.Settings, AppDestination.Settings.symbol)
        assertEquals(MaterialSymbols.Person, AppDestination.Account.symbol)
    }

    @Test
    fun mapsNewStackPagesToTitleRes() {
        assertEquals(R.string.discover_square_title, AppStackPage.DiscoverSquare.titleRes(AppDestination.Discover))
        assertEquals(R.string.mine_cloud_disk_title, AppStackPage.NeteaseCloudDisk.titleRes(AppDestination.Mine))
    }

    @Test
    fun mapsSettingsAndLyricsPagesToDistinctTitles() {
        assertEquals(R.string.playback_lyrics_title, AppStackPage.SettingsPlaybackLyrics.titleRes(AppDestination.Settings))
        assertEquals(R.string.lyrics_service_title, AppStackPage.SettingsLyrics.titleRes(AppDestination.Settings))
        assertEquals(R.string.settings_storage, AppStackPage.SettingsStorage.titleRes(AppDestination.Settings))
        assertEquals(R.string.integration_title, AppStackPage.SettingsIntegration.titleRes(AppDestination.Settings))
        assertEquals(R.string.music_sync_title, AppStackPage.SettingsMusicSync.titleRes(AppDestination.Settings))
        assertEquals(R.string.appearance_player_settings, AppStackPage.SettingsPlayerAppearance.titleRes(AppDestination.Settings))
        assertEquals(R.string.playback_fun_features_title, AppStackPage.SettingsFunFeatures.titleRes(AppDestination.Settings))
    }
}
