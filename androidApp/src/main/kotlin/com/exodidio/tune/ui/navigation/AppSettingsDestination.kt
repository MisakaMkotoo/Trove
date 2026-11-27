package com.exodidio.tune.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import com.exodidio.tune.AppDestination
import com.exodidio.tune.AppDestinationModels
import com.exodidio.tune.AppIntent
import com.exodidio.tune.AppStackPage
import com.exodidio.tune.StackPageEntry
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.settings.IconStyle
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.settings.ThemeAccentColor
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.screens.AboutContent
import com.exodidio.tune.ui.screens.AppearanceContent
import com.exodidio.tune.ui.screens.AppleBackdropSettingsContent
import com.exodidio.tune.ui.screens.AutoSyncModelsContent
import com.exodidio.tune.ui.screens.DownloadSettingsContent
import com.exodidio.tune.ui.screens.DynamicBackdropSettingsContent
import com.exodidio.tune.ui.screens.EqualizerContent
import com.exodidio.tune.ui.screens.FunFeaturesSettingsContent
import com.exodidio.tune.ui.screens.HiddenTracksContent
import com.exodidio.tune.ui.screens.InsightContent
import com.exodidio.tune.ui.screens.IntegrationContent
import com.exodidio.tune.ui.screens.LibraryDuplicatesContent
import com.exodidio.tune.ui.screens.LyricsContent
import com.exodidio.tune.ui.screens.LyricsPlaybackSettingsContent
import com.exodidio.tune.ui.screens.MeshPresetSelectionContent
import com.exodidio.tune.ui.screens.MusicSyncContent
import com.exodidio.tune.ui.screens.NcmImportContent
import com.exodidio.tune.ui.screens.NeteasePlaylistsContent
import com.exodidio.tune.ui.screens.PlaybackSettingsContent
import com.exodidio.tune.ui.screens.PlayerAppearanceContent
import com.exodidio.tune.ui.screens.ServiceStatusContent
import com.exodidio.tune.ui.screens.SettingsContent
import com.exodidio.tune.ui.screens.SongTransitionContent
import com.exodidio.tune.ui.screens.StorageManagementContent
import com.exodidio.tune.ui.screens.TabLayoutContent
import com.exodidio.tune.ui.screens.VolumeNormalizationContent
import dev.chrisbanes.haze.HazeState

@Composable
internal fun SettingsDestinationPages(
    currentPage: StackPageEntry,
    stackPage: StackPageEntry,
    contentPadding: PaddingValues,
    hazeState: HazeState?,
    themeMode: ThemeMode,
    reduceTransparency: Boolean,
    playerTheme: PlayerTheme,
    showVolumeSlider: Boolean,
    fullscreenArtwork: Boolean,
    keepScreenOn: Boolean,
    lyricsFontLevel: Int,
    visibleTabs: List<AppDestination>,
    onVisibleTabsChanged: (List<AppDestination>) -> Unit,
    showGenres: Boolean,
    showComposers: Boolean,
    rememberTabPage: Boolean,
    mergeBottomBars: Boolean,
    iconStyle: IconStyle = IconStyle.Material,
    controlStyle: ControlStyle = ControlStyle.Frosted,
    liquidGlassBlurDp: Float = com.exodidio.tune.settings.DefaultLiquidGlassBlurDp,
    accentColor: ThemeAccentColor = ThemeAccentColor.Red,
    mergeMineAndLibrary: Boolean = false,
    destinations: AppDestinationModels,
    visibleTracks: List<LibraryTrack>,
    visiblePlaybackQueue: PlaybackQueueSnapshot,
    insightListState: LazyListState,
    duplicatesListState: LazyListState,
    onIntent: (AppIntent) -> Unit,
    onTrackPlayNext: (String) -> Unit,
    onTrackAddToQueue: (String) -> Unit,
    onTrackFavoriteToggle: (String, Boolean) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit,
    onTracksRemovedFromLibrary: (List<String>) -> Unit,
    onSettingsContentScrolled: (Boolean) -> Unit,
    onEnterTerminalMode: () -> Unit = {},
) {
    val settings = destinations.settings
    val insightUiState = destinations.insight.state
    val onTrackClick = destinations.library.tracks.onTrackClick
    val onInsightLibraryPeriodSelected = destinations.insight.onLibraryPeriodSelected
    val onInsightListeningPeriodSelected = destinations.insight.onListeningPeriodSelected
    val onInsightTrackClick = destinations.insight.onTrackClick
    val onInsightReportYearSelected = destinations.insight.onReportYearSelected
    val onInsightFootprintClick = destinations.insight.onFootprintClick
    key(currentPage.page) {
        val settingsScrollState = rememberScrollState()
        LaunchedEffect(currentPage, stackPage, settingsScrollState.value) {
            if (currentPage == stackPage) {
                onSettingsContentScrolled(settingsScrollState.value > 0)
            }
        }
        val layoutDirection = LocalLayoutDirection.current
        val settingsPageModifier = Modifier
            .fillMaxSize()
            .verticalScroll(settingsScrollState)
            .padding(
                start = contentPadding.calculateStartPadding(layoutDirection),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding(),
            )
            .testTag("settings-page-scroll")
        when (currentPage.page) {
            AppStackPage.SettingsAppearance -> AppearanceContent(
                modifier = settingsPageModifier,
                themeMode = themeMode,
                accentColor = accentColor,
                onAccentColorSelected = { selected ->
                    onIntent(AppIntent.SetAccentColor(selected))
                },
                reduceTransparency = reduceTransparency,
                onThemeModeSelected = { selected ->
                    onIntent(AppIntent.SetThemeMode(selected))
                },
                onReduceTransparencyChanged = { enabled ->
                    onIntent(AppIntent.SetReduceTransparency(enabled))
                },
                playerTheme = playerTheme,
                onPlayerThemeSelected = { theme ->
                    onIntent(AppIntent.SetPlayerTheme(theme))
                },
                showVolumeSlider = showVolumeSlider,
                onShowVolumeSliderChanged = { enabled ->
                    onIntent(AppIntent.SetShowVolumeSlider(enabled))
                },
                fullscreenArtwork = fullscreenArtwork,
                onFullscreenArtworkChanged = { enabled ->
                    onIntent(AppIntent.SetFullscreenArtwork(enabled))
                },
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChanged = { enabled ->
                    onIntent(AppIntent.SetKeepScreenOn(enabled))
                },
                lyricsFontLevel = lyricsFontLevel,
                onLyricsFontLevelChanged = { level ->
                    onIntent(AppIntent.SetLyricsFontLevel(level))
                },
                adaptiveTextColor = settings.adaptiveTextColor,
                onAdaptiveTextColorChanged = settings.onAdaptiveTextColorChanged,
                onAppleBackdropSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsAppleBackdrop))
                },
                onDynamicBackdropSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsDynamicBackdrop))
                },
                onPlayerAppearanceSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsPlayerAppearance))
                },
                onTabLayoutSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsTabLayout))
                },
                rememberTabPage = rememberTabPage,
                onRememberTabPageChanged = { enabled ->
                    onIntent(AppIntent.SetRememberTabPage(enabled))
                },
                mergeBottomBars = mergeBottomBars,
                onMergeBottomBarsChanged = { enabled ->
                    onIntent(AppIntent.SetMergeBottomBars(enabled))
                },
                iconStyle = iconStyle,
                onIconStyleSelected = { style ->
                    onIntent(AppIntent.SetIconStyle(style))
                },
                controlStyle = controlStyle,
                onControlStyleSelected = { style ->
                    onIntent(AppIntent.SetControlStyle(style))
                },
                liquidGlassBlurDp = liquidGlassBlurDp,
                onLiquidGlassBlurDpChanged = { blurDp ->
                    onIntent(AppIntent.SetLiquidGlassBlurDp(blurDp))
                },
                hazeState = hazeState,
            )
            AppStackPage.SettingsPlayerAppearance -> PlayerAppearanceContent(
                modifier = settingsPageModifier,
                playerTheme = playerTheme,
                onPlayerThemeSelected = { theme ->
                    onIntent(AppIntent.SetPlayerTheme(theme))
                },
                adaptiveTextColor = settings.adaptiveTextColor,
                onAdaptiveTextColorChanged = settings.onAdaptiveTextColorChanged,
                onAppleBackdropSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsAppleBackdrop))
                },
                onDynamicBackdropSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsDynamicBackdrop))
                },
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChanged = { enabled ->
                    onIntent(AppIntent.SetKeepScreenOn(enabled))
                },
                lyricsFontLevel = lyricsFontLevel,
                onLyricsFontLevelChanged = { level ->
                    onIntent(AppIntent.SetLyricsFontLevel(level))
                },
                mediaNotificationStyle = settings.mediaNotificationStyle,
                onMediaNotificationStyleSelected = settings.onMediaNotificationStyleChanged,
                hazeState = hazeState,
            )
            AppStackPage.SettingsAppleBackdrop -> AppleBackdropSettingsContent(
                settings = settings.appleBackdrop,
                onSettingsChanged = settings.onAppleBackdropChanged,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsDynamicBackdrop -> DynamicBackdropSettingsContent(
                settings = settings.dynamicBackdrop,
                onSettingsChanged = settings.onDynamicBackdropChanged,
                modifier = settingsPageModifier,
                onMeshPresetsSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsDynamicBackdropMesh))
                },
            )
            AppStackPage.SettingsDynamicBackdropMesh -> MeshPresetSelectionContent(
                settings = settings.dynamicBackdrop,
                onSettingsChanged = settings.onDynamicBackdropChanged,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsPlayback -> PlaybackSettingsContent(
                audioQuality = settings.audioQuality,
                onAudioQualityChanged = settings.onAudioQualityChanged,
                showFullscreenQualityBadge = settings.showFullscreenQualityBadge,
                onShowFullscreenQualityBadgeChanged = settings.onShowFullscreenQualityBadgeChanged,
                onSongTransitionSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsSongTransition))
                },
                onPlaybackLyricsSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsPlaybackLyrics))
                },
                onVolumeNormalizationSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsVolumeNormalization))
                },
                onEqualizerSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsEqualizer))
                },
                onFunFeaturesSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsFunFeatures))
                },
                hazeState = hazeState,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsFunFeatures -> FunFeaturesSettingsContent(
                settings = settings.funFeatures,
                onSettingsChanged = settings.onFunFeaturesChanged,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsDownload -> DownloadSettingsContent(
                modifier = settingsPageModifier,
                hazeState = hazeState,
            )
            AppStackPage.SettingsSongTransition -> SongTransitionContent(
                crossfadeSeconds = settings.crossfadeSeconds,
                lastEnabledCrossfadeSeconds = settings.lastEnabledCrossfadeSeconds,
                onCrossfadeSecondsChanged = settings.onCrossfadeSecondsChanged,
                blendArtworkDuringCrossfade = settings.blendArtworkDuringCrossfade,
                onBlendArtworkDuringCrossfadeChanged = settings.onBlendArtworkDuringCrossfadeChanged,
                gaplessPlayback = settings.gaplessPlayback,
                onGaplessPlaybackChanged = settings.onGaplessPlaybackChanged,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsPlaybackLyrics -> LyricsPlaybackSettingsContent(
                springStrength = settings.lyricsSettings.lyricSpringStrength,
                onSpringStrengthChanged = settings.onLyricSpringStrengthChanged,
                focusReturnDelayMs = settings.lyricsSettings.lyricFocusReturnDelayMs,
                onFocusReturnDelayChanged = settings.onLyricFocusReturnDelayChanged,
                autoFullscreen = settings.lyricsSettings.lyricAutoFullscreen,
                onAutoFullscreenChanged = settings.onLyricAutoFullscreenChanged,
                autoFullscreenDelayMs = settings.lyricsSettings.lyricAutoFullscreenDelayMs,
                onAutoFullscreenDelayChanged = settings.onLyricAutoFullscreenDelayChanged,
                hazeState = hazeState,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsVolumeNormalization -> VolumeNormalizationContent(
                normalizationAvailable = settings.normalizationAvailable,
                normalization = settings.normalization,
                onNormalizationChanged = settings.onNormalizationChanged,
                hazeState = hazeState,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsEqualizer -> EqualizerContent(
                settings = settings.equalizer,
                onEnabledChanged = settings.onEqualizerEnabledChanged,
                onPresetSelected = settings.onEqualizerPresetSelected,
                onBandChanged = settings.onEqualizerBandChanged,
                hazeState = hazeState,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsIntegration -> IntegrationContent(
                onServiceStatusSelected = { onIntent(AppIntent.OpenPage(AppStackPage.SettingsServiceStatus)) },
                onNeteasePlaylistsSelected = { onIntent(AppIntent.OpenPage(AppStackPage.SettingsNeteasePlaylists)) },
                modifier = settingsPageModifier,
                hazeState = hazeState,
            )
            AppStackPage.SettingsServiceStatus -> ServiceStatusContent(
                lyricsSettings = settings.lyricsSettings,
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsStorage -> StorageManagementContent(
                modifier = settingsPageModifier,
                hazeState = hazeState,
                onExportBackup = settings.onExportBackup,
                onRestoreBackup = settings.onRestoreBackup,
            )
            AppStackPage.SettingsMusicSync -> MusicSyncContent(
                hideShortAudio = settings.hideShortAudio,
                onHideShortAudioChanged = settings.onHideShortAudioChanged,
                showGenres = showGenres,
                onShowGenresChanged = { onIntent(AppIntent.SetShowGenres(it)) },
                showComposers = showComposers,
                onShowComposersChanged = { onIntent(AppIntent.SetShowComposers(it)) },
                onDuplicatesSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsDuplicates))
                },
                onHiddenTracksSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsHiddenTracks))
                },
                onDataAnalysisSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsInsight))
                },
                onNcmImportSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsNcmImport))
                },
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsNcmImport -> NcmImportContent(
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsHiddenTracks -> HiddenTracksContent(
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsNeteasePlaylists -> NeteasePlaylistsContent(
                modifier = settingsPageModifier,
                onOpenPlaylist = { onIntent(AppIntent.OpenPage(AppStackPage.NeteasePlaylistDetails)) },
            )
            AppStackPage.SettingsDuplicates -> LibraryDuplicatesContent(
                tracks = visibleTracks,
                listState = duplicatesListState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
                playbackQueue = visiblePlaybackQueue,
                onTrackClick = { track -> onTrackClick(track.id) },
                onTrackPlayNext = { track -> onTrackPlayNext(track.id) },
                onTrackAddToQueue = { track -> onTrackAddToQueue(track.id) },
                onTrackFavoriteToggle = { track, favorite -> onTrackFavoriteToggle(track.id, favorite) },
                onTrackAlbumClick = { track -> onIntent(AppIntent.OpenAlbumDetails(track.albumId)) },
                onTrackArtistClick = { artist -> onIntent(AppIntent.OpenArtistDetails(artist.id)) },
                onTrackContextBottomSheet = onTrackContextBottomSheet,
                onTracksRemovedFromLibrary = onTracksRemovedFromLibrary,
            )
            AppStackPage.SettingsInsight -> InsightContent(
                state = insightUiState,
                listState = insightListState,
                contentPadding = contentPadding,
                onLibraryPeriodSelected = onInsightLibraryPeriodSelected,
                onListeningPeriodSelected = onInsightListeningPeriodSelected,
                onArtistClick = { onIntent(AppIntent.OpenArtistDetails(it)) },
                onTrackClick = onInsightTrackClick,
                onReportYearSelected = onInsightReportYearSelected,
                onFootprintClick = onInsightFootprintClick,
                modifier = Modifier.fillMaxSize(),
            )
            AppStackPage.SettingsLyrics -> LyricsContent(
                settings = settings.lyricsSettings,
                onSourcesChanged = settings.onLyricsSourcesChanged,
                onWordFallbackChanged = settings.onLyricsWordFallbackChanged,
                onSystemLyricModeChanged = settings.onSystemLyricModeChanged,
                onStandaloneLyricNotificationChanged = settings.onStandaloneLyricNotificationChanged,
                onHyperosSuperIslandChanged = settings.onHyperosSuperIslandChanged,
                modifier = settingsPageModifier,
                hazeState = hazeState,
                onAutoAlignChanged = settings.onLyricsAutoAlignChanged,
                onPerSongLyricsConfigChanged = settings.onPerSongLyricsConfigChanged,
                onAutoSyncModelsSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsAutoSyncModels))
                },
            )
            AppStackPage.SettingsAutoSyncModels -> AutoSyncModelsContent(
                modifier = settingsPageModifier,
            )
            AppStackPage.SettingsAbout -> AboutContent(
                modifier = settingsPageModifier,
                onOpenExternalUrl = { url ->
                    onIntent(AppIntent.OpenExternalUrl(url))
                },
                onEnterTerminalMode = onEnterTerminalMode,
            )
            AppStackPage.SettingsTabLayout -> TabLayoutContent(
                visibleTabs = visibleTabs,
                onVisibleTabsChanged = onVisibleTabsChanged,
                modifier = settingsPageModifier,
                mergeMineAndLibrary = mergeMineAndLibrary,
                onMergeMineAndLibraryChanged = { enabled ->
                    onIntent(AppIntent.SetMergeMineAndLibrary(enabled))
                },
            )
            else -> SettingsContent(
                modifier = settingsPageModifier,
                onAppearanceSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsAppearance))
                },
                onPlaybackSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsPlayback))
                },
                onDownloadSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsDownload))
                },
                onLyricsSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsLyrics))
                },
                onIntegrationSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsIntegration))
                },
                onMusicSyncSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsMusicSync))
                },
                onStorageSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsStorage))
                },
                onAboutSelected = {
                    onIntent(AppIntent.OpenPage(AppStackPage.SettingsAbout))
                },
            )
        }
    }
}
