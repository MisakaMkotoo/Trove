package com.exodidio.tune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.settings.ThemeModeStore

data class PageStateKey(
    val destination: AppDestination,
    val page: AppStackPage,
)

data class AppUiState(
    val selectedDestination: AppDestination = AppDestination.Home,
    val themeMode: ThemeMode = ThemeMode.System,
    val reduceTransparency: Boolean = false,
    val playerTheme: PlayerTheme = PlayerTheme.Standard,
    val showVolumeSlider: Boolean = false,
    val fullscreenArtwork: Boolean = false,
    val fullscreenLyrics: Boolean = false,
    val visibleTabs: List<AppDestination> = AppDestination.entries.toList(),
    val keepScreenOn: Boolean = false,
    val lyricsFontLevel: Int = 1,
    val showGenres: Boolean = false,
    val showComposers: Boolean = false,
    val rememberTabPage: Boolean = true,
    val mergeBottomBars: Boolean = true,
    val selectedAlbumId: String? = null,
    val selectedArtistId: String? = null,
    val selectedGenreId: String? = null,
    val selectedComposerId: String? = null,
    val selectedPlaylistId: String? = null,
    val selectedSimilarSongId: String? = null,
    val settingsReturnDestination: AppDestination? = null,
    val accountReturnDestination: AppDestination? = null,
    val destinationStacks: Map<AppDestination, List<AppStackPage>> = rootDestinationStacks(),
    val pageStateGenerations: Map<PageStateKey, Int> = emptyMap(),
) {
    fun stackFor(destination: AppDestination): List<AppStackPage> =
        destinationStacks[destination].orEmpty().ifEmpty { listOf(AppStackPage.Root) }

    val currentPage: AppStackPage
        get() = stackFor(selectedDestination).last()

    fun pageStateGenerationFor(destination: AppDestination, page: AppStackPage): Int =
        pageStateGenerations[PageStateKey(destination, page)] ?: 0
}

class MainViewModel(
    private val themeModeStore: ThemeModeStore,
) : ViewModel() {
    private val selectedDestination = MutableStateFlow(AppDestination.Home)
    private val destinationStacks = MutableStateFlow(rootDestinationStacks())
    private val selectedAlbumId = MutableStateFlow<String?>(null)
    private val selectedArtistId = MutableStateFlow<String?>(null)
    private val selectedGenreId = MutableStateFlow<String?>(null)
    private val selectedComposerId = MutableStateFlow<String?>(null)
    private val selectedPlaylistId = MutableStateFlow<String?>(null)
    private val selectedSimilarSongId = MutableStateFlow<String?>(null)
    private val settingsReturnDestination = MutableStateFlow<AppDestination?>(null)
    private val accountReturnDestination = MutableStateFlow<AppDestination?>(null)
    private val pageStateGenerations = MutableStateFlow<Map<PageStateKey, Int>>(emptyMap())
    private val _effects = Channel<AppEffect>(Channel.BUFFERED)
    private val rememberTabPage = MutableStateFlow(true)
    init {
        viewModelScope.launch {
            themeModeStore.rememberTabPage.collect { rememberTabPage.value = it }
        }
    }

    val effects = _effects.receiveAsFlow()

    private data class AppearancePrefs(
        val themeMode: ThemeMode = ThemeMode.System,
        val reduceTransparency: Boolean = false,
        val playerTheme: PlayerTheme = PlayerTheme.Standard,
        val showVolumeSlider: Boolean = false,
        val fullscreenArtwork: Boolean = false,
        val fullscreenLyrics: Boolean = false,
        val visibleTabs: List<AppDestination> = AppDestination.dockable,
        val keepScreenOn: Boolean = false,
        val lyricsFontLevel: Int = 1,
        val showGenres: Boolean = false,
        val showComposers: Boolean = false,
        val rememberTabPage: Boolean = true,
        val mergeBottomBars: Boolean = true,
    )

    private val appearancePrefs = themeModeStore.themeMode
        .combine(themeModeStore.reduceTransparency) { themeMode, reduceTransparency ->
            AppearancePrefs(themeMode = themeMode, reduceTransparency = reduceTransparency)
        }
        .combine(themeModeStore.playerTheme) { prefs, playerTheme ->
            prefs.copy(playerTheme = playerTheme)
        }
        .combine(themeModeStore.showVolumeSlider) { prefs, showVolumeSlider ->
            prefs.copy(showVolumeSlider = showVolumeSlider)
        }
        .combine(themeModeStore.fullscreenArtwork) { prefs, fullscreenArtwork ->
            prefs.copy(fullscreenArtwork = fullscreenArtwork)
        }
        .combine(themeModeStore.fullscreenLyrics) { prefs, fullscreenLyrics ->
            prefs.copy(fullscreenLyrics = fullscreenLyrics)
        }
        .combine(themeModeStore.visibleTabs) { prefs, visibleTabs ->
            prefs.copy(visibleTabs = visibleTabs)
        }
        .combine(themeModeStore.keepScreenOn) { prefs, keepScreenOn ->
            prefs.copy(keepScreenOn = keepScreenOn)
        }
        .combine(themeModeStore.lyricsFontLevel) { prefs, lyricsFontLevel ->
            prefs.copy(lyricsFontLevel = lyricsFontLevel)
        }
        .combine(themeModeStore.showGenres) { prefs, showGenres ->
            prefs.copy(showGenres = showGenres)
        }
        .combine(themeModeStore.showComposers) { prefs, showComposers ->
            prefs.copy(showComposers = showComposers)
        }
        .combine(themeModeStore.rememberTabPage) { prefs, rememberTabPage ->
            prefs.copy(rememberTabPage = rememberTabPage)
        }
        .combine(themeModeStore.mergeBottomBars) { prefs, mergeBottomBars ->
            prefs.copy(mergeBottomBars = mergeBottomBars)
        }

    val uiState: StateFlow<AppUiState> = combine(
        appearancePrefs,
        selectedDestination,
        destinationStacks,
        selectedAlbumId,
    ) { appearance: AppearancePrefs,
        destination: AppDestination,
        pages: Map<AppDestination, List<AppStackPage>>,
        albumId: String? ->
        AppUiState(
            selectedDestination = destination,
            themeMode = appearance.themeMode,
            reduceTransparency = appearance.reduceTransparency,
            playerTheme = appearance.playerTheme,
            showVolumeSlider = appearance.showVolumeSlider,
            fullscreenArtwork = appearance.fullscreenArtwork,
            fullscreenLyrics = appearance.fullscreenLyrics,
            visibleTabs = appearance.visibleTabs,
            keepScreenOn = appearance.keepScreenOn,
            lyricsFontLevel = appearance.lyricsFontLevel,
            showGenres = appearance.showGenres,
            showComposers = appearance.showComposers,
            rememberTabPage = appearance.rememberTabPage,
            mergeBottomBars = appearance.mergeBottomBars,
            selectedAlbumId = albumId,
            destinationStacks = pages,
        )
    }.combine(selectedArtistId) { state, artistId ->
        state.copy(selectedArtistId = artistId)
    }.combine(selectedGenreId) { state, genreId ->
        state.copy(selectedGenreId = genreId)
    }.combine(selectedComposerId) { state, composerId ->
        state.copy(selectedComposerId = composerId)
    }.combine(selectedPlaylistId) { state, playlistId ->
        state.copy(selectedPlaylistId = playlistId)
    }.combine(selectedSimilarSongId) { state, similarSongId ->
        state.copy(selectedSimilarSongId = similarSongId)
    }.combine(settingsReturnDestination) { state, returnDestination ->
        state.copy(settingsReturnDestination = returnDestination)
    }.combine(accountReturnDestination) { state, returnDestination ->
        state.copy(accountReturnDestination = returnDestination)
    }.combine(pageStateGenerations) { state, generations ->
        state.copy(pageStateGenerations = generations)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = AppUiState(),
    )

    fun dispatch(intent: AppIntent) {
        when (intent) {
            is AppIntent.SelectDestination -> selectDestination(intent.destination)
            is AppIntent.OpenPage -> openPage(intent.page)
            is AppIntent.OpenPageHere -> openPageHere(intent.page)
            is AppIntent.OpenAlbumDetails -> openAlbumDetails(intent.albumId)
            is AppIntent.OpenPlaylistDetails -> openPlaylistDetails(intent.playlistId)
            is AppIntent.OpenArtistDetails -> openArtistDetails(intent.artistId)
            is AppIntent.OpenGenreDetails -> openGenreDetails(intent.genreId)
            is AppIntent.OpenComposerDetails -> openComposerDetails(intent.composerId)
            is AppIntent.OpenSimilarSongs -> openSimilarSongs(intent.trackId)
            is AppIntent.OpenNeteaseArtist -> openNeteaseArtist(intent.artistId, intent.name)
            is AppIntent.OpenNeteaseAlbum -> openNeteaseAlbum(intent.albumId, intent.name, intent.coverUrl)
            AppIntent.NavigateBack -> navigateBack()
            AppIntent.OpenSettings -> openSettings()
            AppIntent.OpenAccount -> openAccount()
            is AppIntent.SetThemeMode -> setThemeMode(intent.themeMode)
            is AppIntent.SetReduceTransparency -> setReduceTransparency(intent.enabled)
            is AppIntent.SetPlayerTheme -> setPlayerTheme(intent.theme)
            is AppIntent.SetShowVolumeSlider -> setShowVolumeSlider(intent.enabled)
            is AppIntent.SetFullscreenArtwork -> setFullscreenArtwork(intent.enabled)
            is AppIntent.SetFullscreenLyrics -> setFullscreenLyrics(intent.enabled)
            is AppIntent.SetVisibleTabs -> setVisibleTabs(intent.visibleTabs)
            is AppIntent.SetKeepScreenOn -> setKeepScreenOn(intent.enabled)
            is AppIntent.SetLyricsFontLevel -> setLyricsFontLevel(intent.level)
            is AppIntent.SetShowGenres -> setShowGenres(intent.enabled)
            is AppIntent.SetShowComposers -> setShowComposers(intent.enabled)
            is AppIntent.SetRememberTabPage -> setRememberTabPage(intent.enabled)
            is AppIntent.SetMergeBottomBars -> setMergeBottomBars(intent.enabled)
            is AppIntent.ImportM3u -> _effects.trySend(AppEffect.PickM3uImport)
            is AppIntent.ExportM3u -> _effects.trySend(AppEffect.PickM3uExport(intent.playlistId))
            is AppIntent.OpenExternalUrl -> _effects.trySend(AppEffect.OpenExternalUrl(intent.url))
        }
    }

    private fun selectDestination(destination: AppDestination) {
        if (destination != AppDestination.Settings) settingsReturnDestination.value = null
        if (destination != AppDestination.Account) accountReturnDestination.value = null
        val remember = rememberTabPage.value
        if (destination != selectedDestination.value) {
            selectedDestination.value = destination
        }
        // 记住上次页面（默认）：切标签保留原页面；关闭后点任意标签都直接回一级页。
        if (!remember) resetToRoot(destination)
    }

    private fun resetToRoot(destination: AppDestination) {
        val discardedPages = destinationStacks.value.getValue(destination).drop(1)
        if (discardedPages.isEmpty()) return
        destinationStacks.update { stacks ->
            stacks + (destination to listOf(AppStackPage.Root))
        }
        discardedPages.forEach { page -> invalidatePoppedPageState(destination, page) }
    }

    private fun openPage(page: AppStackPage) {
        val destination = page.destination
        selectedDestination.value = destination
        destinationStacks.update { stacks ->
            val stack = stacks.getValue(destination)
            if (stack.lastOrNull() == page) {
                stacks
            } else {
                stacks + (destination to stack + page)
            }
        }
    }

    /** 在用户当前所在的 tab 栈里打开页面，返回时回到该 tab 的原页面。 */
    private fun openPageHere(page: AppStackPage) {
        val destination = selectedDestination.value
        destinationStacks.update { stacks ->
            val stack = stacks.getValue(destination)
            if (stack.lastOrNull() == page) {
                stacks
            } else {
                stacks + (destination to stack + page)
            }
        }
    }

    private fun openAlbumDetails(albumId: String) {
        selectedAlbumId.value = albumId
        openPageHere(AppStackPage.AlbumDetails)
    }

    private fun openPlaylistDetails(playlistId: String) {
        selectedPlaylistId.value = playlistId
        openPageHere(AppStackPage.PlaylistDetails)
    }

    private fun openArtistDetails(artistId: String) {
        selectedArtistId.value = artistId
        openPageHere(AppStackPage.ArtistDetails)
    }

    private fun openGenreDetails(genreId: String) {
        selectedGenreId.value = genreId
        openPageHere(AppStackPage.GenreDetails)
    }

    private fun openComposerDetails(composerId: String) {
        selectedComposerId.value = composerId
        openPageHere(AppStackPage.ComposerDetails)
    }

    private fun openSimilarSongs(trackId: String) {
        selectedSimilarSongId.value = trackId
        openPageHere(AppStackPage.SimilarSongs)
    }

    private fun openNeteaseArtist(artistId: Long, name: String) {
        if (artistId <= 0) return
        com.exodidio.tune.netease.NeteaseRepository.stageArtistPage(artistId, name)
        openPageHere(AppStackPage.NeteasePlaylistDetails)
    }

    private fun openNeteaseAlbum(albumId: Long, name: String, coverUrl: String) {
        if (albumId <= 0) return
        com.exodidio.tune.netease.NeteaseRepository.stageAlbumPage(albumId, name, coverUrl)
        openPageHere(AppStackPage.NeteasePlaylistDetails)
    }

    /** 设置改为一级界面的右上角入口：记住进入前的 tab，返回时恢复。 */
    private fun openSettings() {
        val current = selectedDestination.value
        if (current == AppDestination.Settings) return
        settingsReturnDestination.value = current
        selectedDestination.value = AppDestination.Settings
        destinationStacks.update { stacks ->
            stacks + (AppDestination.Settings to listOf(AppStackPage.Root))
        }
    }

    /** 账户走独立 destination：与设置一致的淡入淡出，不进任何 tab 的浏览栈。 */
    private fun openAccount() {
        val current = selectedDestination.value
        if (current == AppDestination.Account) return
        accountReturnDestination.value = current
        selectedDestination.value = AppDestination.Account
        destinationStacks.update { stacks ->
            stacks + (AppDestination.Account to listOf(AppStackPage.Root))
        }
    }

    private fun navigateBack() {
        val destination = selectedDestination.value
        // 设置/账户根页：返回到进入前所在的 tab。
        if (destination == AppDestination.Settings &&
            destinationStacks.value.getValue(destination).size <= 1
        ) {
            val returnDestination = settingsReturnDestination.value
            if (returnDestination != null) {
                settingsReturnDestination.value = null
                selectedDestination.value = returnDestination
                return
            }
        }
        if (destination == AppDestination.Account &&
            destinationStacks.value.getValue(destination).size <= 1
        ) {
            val returnDestination = accountReturnDestination.value
            if (returnDestination != null) {
                accountReturnDestination.value = null
                selectedDestination.value = returnDestination
                return
            }
        }
        val poppedPage = destinationStacks.value.getValue(destination).takeIf { it.size > 1 }?.last()
        destinationStacks.update { stacks ->
            val stack = stacks.getValue(destination)
            if (stack.size > 1) stacks + (destination to stack.dropLast(1)) else stacks
        }
        poppedPage?.let { page -> invalidatePoppedPageState(destination, page) }
    }

    private fun invalidatePoppedPageState(destination: AppDestination, page: AppStackPage) {
        val key = PageStateKey(destination, page)
        pageStateGenerations.update { generations ->
            generations + (key to (generations[key] ?: 0) + 1)
        }
        clearPoppedPageState(page)
        _effects.trySend(AppEffect.ResetPoppedPage(page))
    }

    /**
     * Detail destinations are parameterized by a selected ID that lives in the
     * activity-scoped app shell. A stack pop must also discard that parameter;
     * otherwise reopening the same destination can render its previous page
     * instance before a new selection is provided.
     */
    private fun clearPoppedPageState(page: AppStackPage) {
        when (page) {
            AppStackPage.AlbumDetails -> selectedAlbumId.value = null
            AppStackPage.PlaylistDetails -> selectedPlaylistId.value = null
            AppStackPage.ArtistDetails -> selectedArtistId.value = null
            AppStackPage.GenreDetails -> selectedGenreId.value = null
            AppStackPage.ComposerDetails -> selectedComposerId.value = null
            AppStackPage.SimilarSongs -> selectedSimilarSongId.value = null
            else -> Unit
        }
    }

    private fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            themeModeStore.setThemeMode(themeMode)
        }
    }

    private fun setReduceTransparency(enabled: Boolean) {
        viewModelScope.launch {
            themeModeStore.setReduceTransparency(enabled)
        }
    }

    private fun setPlayerTheme(theme: PlayerTheme) {
        viewModelScope.launch {
            themeModeStore.setPlayerTheme(theme)
        }
    }

    private fun setShowVolumeSlider(enabled: Boolean) {
        viewModelScope.launch {
            themeModeStore.setShowVolumeSlider(enabled)
        }
    }

    private fun setFullscreenArtwork(enabled: Boolean) {
        viewModelScope.launch {
            themeModeStore.setFullscreenArtwork(enabled)
        }
    }

    private fun setFullscreenLyrics(enabled: Boolean) {
        viewModelScope.launch {
            themeModeStore.setFullscreenLyrics(enabled)
        }
    }

    private fun setVisibleTabs(visibleTabs: List<AppDestination>) {
        viewModelScope.launch {
            themeModeStore.setVisibleTabs(visibleTabs)
        }
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            themeModeStore.setKeepScreenOn(enabled)
        }
    }

    private fun setLyricsFontLevel(level: Int) {
        viewModelScope.launch {
            themeModeStore.setLyricsFontLevel(level)
        }
    }

    private fun setShowGenres(enabled: Boolean) {
        viewModelScope.launch { themeModeStore.setShowGenres(enabled) }
    }

    private fun setShowComposers(enabled: Boolean) {
        viewModelScope.launch { themeModeStore.setShowComposers(enabled) }
    }

    private fun setRememberTabPage(enabled: Boolean) {
        viewModelScope.launch { themeModeStore.setRememberTabPage(enabled) }
    }

    private fun setMergeBottomBars(enabled: Boolean) {
        viewModelScope.launch { themeModeStore.setMergeBottomBars(enabled) }
    }

    override fun onCleared() {
        _effects.close()
    }

    class Factory(
        private val themeModeStore: ThemeModeStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(MainViewModel::class.java))
            return MainViewModel(themeModeStore) as T
        }
    }
}
