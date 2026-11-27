package com.exodidio.tune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.settings.IconStyle
import com.exodidio.tune.settings.ThemeAccentColor
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.settings.ThemeModeStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selecting a new destination retains other destination stacks`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenPage(AppStackPage.SettingsAppearance))
        viewModel.dispatch(AppIntent.SelectDestination(AppDestination.Library))
        advanceUntilIdle()

        assertEquals(AppDestination.Library, viewModel.uiState.value.selectedDestination)
        assertEquals(
            listOf(AppStackPage.Root, AppStackPage.SettingsAppearance),
            viewModel.uiState.value.stackFor(AppDestination.Settings),
        )
    }

    @Test
    fun `reselecting the destination restores its root stack`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenPage(AppStackPage.SettingsAppearance))
        viewModel.dispatch(AppIntent.SelectDestination(AppDestination.Settings))
        advanceUntilIdle()

        assertEquals(listOf(AppStackPage.Root), viewModel.uiState.value.stackFor(AppDestination.Settings))
    }

    @Test
    fun `opening a settings page selects settings and back pops it`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenPage(AppStackPage.SettingsAppearance))
        advanceUntilIdle()
        assertEquals(AppDestination.Settings, viewModel.uiState.value.selectedDestination)
        assertEquals(AppStackPage.SettingsAppearance, viewModel.uiState.value.currentPage)

        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()
        assertEquals(AppStackPage.Root, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `opening genre details selects the genre and pushes the library page`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenGenreDetails("electronic"))
        advanceUntilIdle()

        assertEquals("electronic", viewModel.uiState.value.selectedGenreId)
        assertEquals(AppStackPage.GenreDetails, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `opening composer details selects the composer and pushes the library page`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenComposerDetails("glass"))
        advanceUntilIdle()

        assertEquals("glass", viewModel.uiState.value.selectedComposerId)
        assertEquals(AppStackPage.ComposerDetails, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `opening playlist details selects the playlist and pushes the library page`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenPlaylistDetails("favorites"))
        advanceUntilIdle()

        assertEquals("favorites", viewModel.uiState.value.selectedPlaylistId)
        assertEquals(AppStackPage.PlaylistDetails, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `popping a detail page clears its selected page state`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenAlbumDetails("album-1"))
        advanceUntilIdle()
        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()

        assertEquals(AppStackPage.Root, viewModel.uiState.value.currentPage)
        assertEquals(null, viewModel.uiState.value.selectedAlbumId)
    }

    @Test
    fun `popping a nested detail only clears the page that was popped`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenArtistDetails("artist-1"))
        viewModel.dispatch(AppIntent.OpenAlbumDetails("album-1"))
        advanceUntilIdle()
        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()

        assertEquals(AppStackPage.ArtistDetails, viewModel.uiState.value.currentPage)
        assertEquals("artist-1", viewModel.uiState.value.selectedArtistId)
        assertEquals(null, viewModel.uiState.value.selectedAlbumId)
    }

    @Test
    fun `popping a list page invalidates only that page's cached ui state`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)

        viewModel.dispatch(AppIntent.OpenPage(AppStackPage.LibraryAlbums))
        advanceUntilIdle()
        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()

        assertEquals(
            1,
            viewModel.uiState.value.pageStateGenerationFor(AppDestination.Library, AppStackPage.LibraryAlbums),
        )
        assertEquals(
            0,
            viewModel.uiState.value.pageStateGenerationFor(AppDestination.Library, AppStackPage.Root),
        )
    }

    @Test
    fun `setting the theme persists it and updates ui state`() = runTest {
        val store = FakeThemeModeStore()
        val viewModel = MainViewModel(store)
        activateState(viewModel)

        viewModel.dispatch(AppIntent.SetThemeMode(ThemeMode.Dark))
        advanceUntilIdle()

        assertEquals(listOf(ThemeMode.Dark), store.savedModes)
        assertEquals(ThemeMode.Dark, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `setting reduce transparency persists it and updates ui state`() = runTest {
        val store = FakeThemeModeStore()
        val viewModel = MainViewModel(store)
        activateState(viewModel)

        viewModel.dispatch(AppIntent.SetReduceTransparency(true))
        advanceUntilIdle()

        assertEquals(listOf(true), store.savedReduceTransparencyValues)
        assertEquals(true, viewModel.uiState.value.reduceTransparency)
    }

    @Test
    fun `opening an external url emits one host effect`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        val expected = AppEffect.OpenExternalUrl("https://example.com")
        val effect = async { viewModel.effects.first() }

        viewModel.dispatch(AppIntent.OpenExternalUrl(expected.url))

        assertEquals(expected, effect.await())
    }

    @Test
    fun `popping a page emits its reset effect`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)
        viewModel.dispatch(AppIntent.OpenPage(AppStackPage.LibraryTracks))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.dispatch(AppIntent.NavigateBack)

        assertEquals(AppEffect.ResetPoppedPage(AppStackPage.LibraryTracks), effect.await())
    }

    @Test
    fun `opening similar songs from player emits RestoreFullScreenPlayer on back`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)
        val emitted = mutableListOf<AppEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { emitted += it }
        }

        viewModel.dispatch(AppIntent.OpenSimilarSongs("song-1", returnToPlayer = true))
        advanceUntilIdle()
        assertEquals(AppStackPage.SimilarSongs, viewModel.uiState.value.currentPage)

        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()

        assertEquals(AppStackPage.Root, viewModel.uiState.value.currentPage)
        assertEquals(
            listOf(AppEffect.ResetPoppedPage(AppStackPage.SimilarSongs), AppEffect.RestoreFullScreenPlayer),
            emitted,
        )
    }

    @Test
    fun `nested navigation from player waits until returning past anchor before restoring player`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)
        val emitted = mutableListOf<AppEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { emitted += it }
        }

        viewModel.dispatch(AppIntent.OpenArtistDetails("artist-1", returnToPlayer = true))
        viewModel.dispatch(AppIntent.OpenAlbumDetails("album-1"))
        advanceUntilIdle()
        assertEquals(AppStackPage.AlbumDetails, viewModel.uiState.value.currentPage)

        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()
        assertEquals(AppStackPage.ArtistDetails, viewModel.uiState.value.currentPage)
        assertEquals(listOf(AppEffect.ResetPoppedPage(AppStackPage.AlbumDetails)), emitted)

        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()
        assertEquals(AppStackPage.Root, viewModel.uiState.value.currentPage)
        assertEquals(
            listOf(
                AppEffect.ResetPoppedPage(AppStackPage.AlbumDetails),
                AppEffect.ResetPoppedPage(AppStackPage.ArtistDetails),
                AppEffect.RestoreFullScreenPlayer,
            ),
            emitted,
        )
    }

    @Test
    fun `switching tabs clears pending return-to-player anchor`() = runTest {
        val viewModel = MainViewModel(FakeThemeModeStore())
        activateState(viewModel)
        val emitted = mutableListOf<AppEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { emitted += it }
        }

        viewModel.dispatch(AppIntent.OpenAlbumDetails("album-1", returnToPlayer = true))
        viewModel.dispatch(AppIntent.SelectDestination(AppDestination.Library))
        advanceUntilIdle()
        viewModel.dispatch(AppIntent.OpenAlbumDetails("album-2"))
        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()

        assertEquals(listOf(AppEffect.ResetPoppedPage(AppStackPage.AlbumDetails)), emitted)
    }

    @Test
    fun `dispatching SetAccentColor persists and updates ui state`() = runTest {
        val store = FakeThemeModeStore()
        val viewModel = MainViewModel(store)
        activateState(viewModel)
        advanceUntilIdle()
        assertEquals(ThemeAccentColor.Red, viewModel.uiState.value.accentColor)

        viewModel.dispatch(AppIntent.SetAccentColor(ThemeAccentColor.Blue))
        advanceUntilIdle()

        assertEquals(listOf(ThemeAccentColor.Blue), store.savedAccentColors)
        assertEquals(ThemeAccentColor.Blue, viewModel.uiState.value.accentColor)
    }

    @Test
    fun `enabling mergeMineAndLibrary persists flag and moves Mine destination to Library`() = runTest {
        val store = FakeThemeModeStore()
        val viewModel = MainViewModel(store)
        activateState(viewModel)

        viewModel.dispatch(AppIntent.SelectDestination(AppDestination.Mine))
        advanceUntilIdle()
        assertEquals(AppDestination.Mine, viewModel.uiState.value.selectedDestination)

        viewModel.dispatch(AppIntent.SetMergeMineAndLibrary(true))
        advanceUntilIdle()

        assertEquals(listOf(true), store.savedMergeMineAndLibrary)
        assertEquals(true, viewModel.uiState.value.mergeMineAndLibrary)
        assertEquals(AppDestination.Library, viewModel.uiState.value.selectedDestination)

        viewModel.dispatch(AppIntent.SelectDestination(AppDestination.Mine))
        advanceUntilIdle()
        assertEquals(AppDestination.Library, viewModel.uiState.value.selectedDestination)
    }

    @Test
    fun `enabling mergeMineAndLibrary updates settings return destination from Mine to Library`() = runTest {
        val store = FakeThemeModeStore()
        val viewModel = MainViewModel(store)
        activateState(viewModel)

        viewModel.dispatch(AppIntent.SelectDestination(AppDestination.Mine))
        viewModel.dispatch(AppIntent.OpenSettings)
        viewModel.dispatch(AppIntent.SetMergeMineAndLibrary(true))
        advanceUntilIdle()

        viewModel.dispatch(AppIntent.NavigateBack)
        advanceUntilIdle()

        assertEquals(AppDestination.Library, viewModel.uiState.value.selectedDestination)
    }

    @Test
    fun `setting liquidGlassBlurDp updates uiState within bounds`() = runTest {
        val store = FakeThemeModeStore()
        val viewModel = MainViewModel(store)
        activateState(viewModel)

        viewModel.dispatch(AppIntent.SetLiquidGlassBlurDp(4f))
        advanceUntilIdle()
        assertEquals(4f, viewModel.uiState.value.liquidGlassBlurDp, 0.01f)

        viewModel.dispatch(AppIntent.SetLiquidGlassBlurDp(99f))
        advanceUntilIdle()
        assertEquals(com.exodidio.tune.settings.MaxLiquidGlassBlurDp, viewModel.uiState.value.liquidGlassBlurDp, 0.01f)
    }

    private fun TestScope.activateState(viewModel: MainViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
    }
}

private class FakeThemeModeStore(initialThemeMode: ThemeMode = ThemeMode.System) : ThemeModeStore {
    private val mutableThemeMode = MutableStateFlow(initialThemeMode)
    private val mutableReduceTransparency = MutableStateFlow(false)
    private val mutableAccentColor = MutableStateFlow(ThemeAccentColor.Red)
    private val mutableMergeMineAndLibrary = MutableStateFlow(false)
    override val themeMode: Flow<ThemeMode> = mutableThemeMode
    override val reduceTransparency: Flow<Boolean> = mutableReduceTransparency
    override val playerTheme: Flow<PlayerTheme> = kotlinx.coroutines.flow.MutableStateFlow(PlayerTheme.Standard)
    override val showVolumeSlider: Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val fullscreenArtwork: Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val fullscreenLyrics: Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val visibleTabs: Flow<List<AppDestination>> = kotlinx.coroutines.flow.MutableStateFlow(AppDestination.entries.toList())
    override val keepScreenOn: Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val lyricsFontLevel: Flow<Int> = kotlinx.coroutines.flow.MutableStateFlow(1)
    override val showGenres: Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val showComposers: Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val rememberTabPage: Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)
    override val mergeBottomBars: Flow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)
    override val iconStyle: Flow<IconStyle> = kotlinx.coroutines.flow.MutableStateFlow(IconStyle.Material)
    override val controlStyle: Flow<ControlStyle> = kotlinx.coroutines.flow.MutableStateFlow(ControlStyle.Frosted)
    private val mutableLiquidGlassBlurDp = MutableStateFlow(com.exodidio.tune.settings.DefaultLiquidGlassBlurDp)
    override val liquidGlassBlurDp: Flow<Float> = mutableLiquidGlassBlurDp
    override val accentColor: Flow<ThemeAccentColor> = mutableAccentColor
    override val mergeMineAndLibrary: Flow<Boolean> = mutableMergeMineAndLibrary
    val savedModes = mutableListOf<ThemeMode>()
    val savedReduceTransparencyValues = mutableListOf<Boolean>()
    val savedAccentColors = mutableListOf<ThemeAccentColor>()
    val savedMergeMineAndLibrary = mutableListOf<Boolean>()

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        savedModes += themeMode
        mutableThemeMode.value = themeMode
    }

    override suspend fun setReduceTransparency(enabled: Boolean) {
        savedReduceTransparencyValues += enabled
        mutableReduceTransparency.value = enabled
    }
    override suspend fun setPlayerTheme(theme: PlayerTheme) {}
    override suspend fun setShowVolumeSlider(enabled: Boolean) {}
    override suspend fun setFullscreenArtwork(enabled: Boolean) {}
    override suspend fun setFullscreenLyrics(enabled: Boolean) {}
    override suspend fun setVisibleTabs(visibleTabs: List<AppDestination>) {}
    override suspend fun setKeepScreenOn(enabled: Boolean) {}
    override suspend fun setShowGenres(enabled: Boolean) {}
    override suspend fun setShowComposers(enabled: Boolean) {}
    override suspend fun setRememberTabPage(enabled: Boolean) {}
    override suspend fun setMergeBottomBars(enabled: Boolean) {}
    override suspend fun setLyricsFontLevel(level: Int) {}
    override suspend fun setIconStyle(style: IconStyle) {}
    override suspend fun setControlStyle(style: ControlStyle) {}
    override suspend fun setLiquidGlassBlurDp(blurDp: Float) {
        mutableLiquidGlassBlurDp.value = blurDp.coerceIn(
            com.exodidio.tune.settings.MinLiquidGlassBlurDp,
            com.exodidio.tune.settings.MaxLiquidGlassBlurDp,
        )
    }
    override suspend fun setAccentColor(accentColor: ThemeAccentColor) {
        savedAccentColors += accentColor
        mutableAccentColor.value = accentColor
    }
    override suspend fun setMergeMineAndLibrary(enabled: Boolean) {
        savedMergeMineAndLibrary += enabled
        mutableMergeMineAndLibrary.value = enabled
    }
}
