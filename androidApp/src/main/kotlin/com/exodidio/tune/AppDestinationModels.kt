package com.exodidio.tune

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.exodidio.tune.lyrics.LyricsSettings
import com.exodidio.tune.lyrics.LyricsSourceId
import com.exodidio.tune.lyrics.LyricsSearchResult
import com.exodidio.tune.lyrics.SystemLyricMode
import com.exodidio.tune.player.AbLoopState
import com.exodidio.tune.player.ArtworkCrossfadeTransition
import com.exodidio.tune.player.AppleBackdropSettings
import com.exodidio.tune.player.DynamicBackdropSettings
import com.exodidio.tune.player.EqualizerSettings
import com.exodidio.tune.player.FunFeaturesSettings
import com.exodidio.tune.player.NormalizationSettings
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackState
import com.exodidio.tune.player.RepeatMode
import com.exodidio.tune.player.SleepTimerState
import com.exodidio.tune.sync.LibraryPlaylist
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.screens.AlbumDetailsUiState
import com.exodidio.tune.ui.screens.AlbumLayoutMode
import com.exodidio.tune.ui.screens.AlbumSortOption
import com.exodidio.tune.ui.screens.ArtistDetailsUiState
import com.exodidio.tune.ui.screens.ArtistSortOption
import com.exodidio.tune.ui.screens.ComposerDetailsUiState
import com.exodidio.tune.ui.screens.ComposerSortOption
import com.exodidio.tune.library.LibraryFolder
import com.exodidio.tune.library.SmartPlaylistSpec
import com.exodidio.tune.ui.screens.FolderDetailsUiState
import com.exodidio.tune.ui.screens.FolderSortOption
import com.exodidio.tune.ui.screens.GenreDetailsUiState
import com.exodidio.tune.ui.screens.GenreSortOption
import com.exodidio.tune.ui.screens.HomeUiState
import com.exodidio.tune.ui.screens.InsightPeriod
import com.exodidio.tune.ui.screens.InsightUiState
import com.exodidio.tune.ui.screens.LibraryAlbumsUiState
import com.exodidio.tune.ui.screens.LibraryArtistsUiState
import com.exodidio.tune.ui.screens.LibraryComposersUiState
import com.exodidio.tune.ui.screens.LibraryFoldersUiState
import com.exodidio.tune.ui.screens.LibraryGenresUiState
import com.exodidio.tune.ui.screens.LibraryPlaylistsUiState
import com.exodidio.tune.ui.screens.LibrarySearchUiState
import com.exodidio.tune.ui.screens.LibraryTracksUiState
import com.exodidio.tune.ui.screens.PlaylistDetailsUiState
import com.exodidio.tune.ui.screens.TrackSortOption

// Function-type fields (onTrackClick, orderedTrackIds, onHeroColorChanged, etc.) are treated as stable only under @Immutable; LibraryDetailActions holds a Color callback and stays @Stable. No constructor, default, or call-site change.
@Immutable internal data class HomeDestinationModel(
    val state: HomeUiState = HomeUiState(),
    val onTrackClick: (List<LibraryTrack>, String) -> Unit = { _, _ -> },
    val onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
)

@Immutable internal data class InsightDestinationModel(
    val state: InsightUiState = InsightUiState(),
    val onLibraryPeriodSelected: (InsightPeriod) -> Unit = {},
    val onListeningPeriodSelected: (InsightPeriod) -> Unit = {},
    val onTrackClick: (String) -> Unit = {},
    val onReportYearSelected: (Int) -> Unit = {},
    val onFootprintClick: (String) -> Unit = {},
)

@Immutable internal data class LibraryTracksModel(
    val state: LibraryTracksUiState = LibraryTracksUiState(),
    val onSortOptionSelected: (TrackSortOption) -> Unit = {},
    val onToggleSortOrder: () -> Unit = {},
    val onTrackClick: (String) -> Unit = {},
    val onPlayAll: (Boolean) -> Unit = {},
    val onFilterQueryChange: (String) -> Unit = {},
    val onRecentTrackClick: (String) -> Unit = {},
)

@Immutable internal data class LibraryArtistsModel(
    val state: LibraryArtistsUiState = LibraryArtistsUiState(),
    val onSortOptionSelected: (ArtistSortOption) -> Unit = {},
    val onToggleSortOrder: () -> Unit = {},
    val onFilterQueryChange: (String) -> Unit = {},
    val onPlay: (String, Boolean) -> Unit = { _, _ -> },
    val onPlayNext: (List<String>) -> Unit = {},
    val onAddToQueue: (List<String>) -> Unit = {},
    val orderedTrackIds: (String) -> List<String> = { emptyList() },
    val onArtistUpdate: (String, Uri?, Boolean) -> Unit = { _, _, _ -> },
)

@Immutable internal data class LibraryAlbumsModel(
    val state: LibraryAlbumsUiState = LibraryAlbumsUiState(),
    val onSortOptionSelected: (AlbumSortOption) -> Unit = {},
    val onToggleSortOrder: () -> Unit = {},
    val onLayoutModeSelected: (AlbumLayoutMode) -> Unit = {},
    val onPlay: (String, Boolean) -> Unit = { _, _ -> },
    val onPlayAll: (Boolean) -> Unit = {},
    val onFilterQueryChange: (String) -> Unit = {},
    val onTrackPlay: (String, String) -> Unit = { _, _ -> },
    val onPlayNext: (List<String>) -> Unit = {},
    val onAddToQueue: (List<String>) -> Unit = {},
    val onAddToFavorites: (List<String>) -> Unit = {},
)

@Immutable internal data class LibraryGenresModel(
    val state: LibraryGenresUiState = LibraryGenresUiState(),
    val onSortOptionSelected: (GenreSortOption) -> Unit = {},
    val onToggleSortOrder: () -> Unit = {},
    val onFilterQueryChange: (String) -> Unit = {},
    val onPlay: (String, Boolean) -> Unit = { _, _ -> },
    val onPlayNext: (List<String>) -> Unit = {},
    val onAddToQueue: (List<String>) -> Unit = {},
    val orderedTrackIds: (String) -> List<String> = { emptyList() },
)

@Immutable internal data class LibraryFoldersModel(
    val state: LibraryFoldersUiState = LibraryFoldersUiState(),
    val allFolders: List<LibraryFolder> = emptyList(),
    val onSortOptionSelected: (FolderSortOption) -> Unit = {},
    val onToggleSortOrder: () -> Unit = {},
    val onFilterQueryChange: (String) -> Unit = {},
    val onPlay: (String, Boolean) -> Unit = { _, _ -> },
    val onTrackPlay: (String, String) -> Unit = { _, _ -> },
    val onPlayNext: (List<String>) -> Unit = {},
    val onAddToQueue: (List<String>) -> Unit = {},
    val orderedTrackIds: (String) -> List<String> = { emptyList() },
)

@Immutable internal data class LibraryComposersModel(
    val state: LibraryComposersUiState = LibraryComposersUiState(),
    val onSortOptionSelected: (ComposerSortOption) -> Unit = {},
    val onToggleSortOrder: () -> Unit = {},
    val onFilterQueryChange: (String) -> Unit = {},
    val onPlay: (String, Boolean) -> Unit = { _, _ -> },
    val onPlayNext: (List<String>) -> Unit = {},
    val onAddToQueue: (List<String>) -> Unit = {},
    val orderedTrackIds: (String) -> List<String> = { emptyList() },
)

@Immutable internal data class LibraryPlaylistsModel(
    val state: LibraryPlaylistsUiState = LibraryPlaylistsUiState(),
    val availablePlaylists: List<LibraryPlaylist> = emptyList(),
    val onPlay: (String, Boolean) -> Unit = { _, _ -> },
    val onTrackPlay: (String, String) -> Unit = { _, _ -> },
    val onTrackRemove: (String, String) -> Unit = { _, _ -> },
    val onTrackMove: (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
    val onPlayNext: (List<String>) -> Unit = {},
    val onAddToQueue: (List<String>) -> Unit = {},
    val onUpdate: (String, String, Uri?, Boolean) -> Unit = { _, _, _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onCreate: (String, Uri?) -> Unit = { _, _ -> },
    val onCreateWithTracks: (String, Uri?, List<String>) -> Unit = { _, _, _ -> },
    val onCreateSmart: (String, SmartPlaylistSpec) -> Unit = { _, _ -> },
    val onUpdateSmart: (String, String, SmartPlaylistSpec) -> Unit = { _, _, _ -> },
    val onMembershipChange: (String, List<String>, Boolean) -> Unit = { _, _, _ -> },
    val onDeduplicate: (String) -> Int = { 0 },
)

@Immutable internal data class LibrarySearchModel(
    val state: LibrarySearchUiState = LibrarySearchUiState(),
    val onQueryChange: (String) -> Unit = {},
    val onTrackClick: (String) -> Unit = {},
    val onOnlineModeChange: (Boolean) -> Unit = {},
    val onOnlineCategoryChange: (com.exodidio.tune.ui.screens.OnlineSearchCategory) -> Unit = {},
    val onOnlineSubmit: () -> Unit = {},
    val onOnlineTrackPlay: (Int) -> Unit = {},
    val onOnlinePlaylistClick: (Long) -> Unit = {},
)

@Immutable internal data class DiscoverDestinationModel(
    val onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
    val onOpenPlaylist: (com.exodidio.tune.netease.DiscoverPlaylist) -> Unit = {},
)

@Immutable internal data class MineDestinationModel(
    val onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
)

@Stable internal data class LibraryDetailActions(
    val albums: AlbumDetailsUiState = AlbumDetailsUiState(),
    val playlists: PlaylistDetailsUiState = PlaylistDetailsUiState(),
    val artists: ArtistDetailsUiState = ArtistDetailsUiState(),
    val genres: GenreDetailsUiState = GenreDetailsUiState(),
    val composers: ComposerDetailsUiState = ComposerDetailsUiState(),
    val onHeroColorChanged: (Color) -> Unit = {},
)

@Immutable internal data class LibraryDestinationModel(
    val tracks: LibraryTracksModel = LibraryTracksModel(),
    val artists: LibraryArtistsModel = LibraryArtistsModel(),
    val albums: LibraryAlbumsModel = LibraryAlbumsModel(),
    val folders: LibraryFoldersModel = LibraryFoldersModel(),
    val genres: LibraryGenresModel = LibraryGenresModel(),
    val composers: LibraryComposersModel = LibraryComposersModel(),
    val playlists: LibraryPlaylistsModel = LibraryPlaylistsModel(),
    val search: LibrarySearchModel = LibrarySearchModel(),
    val details: LibraryDetailActions = LibraryDetailActions(),
)

@Immutable internal data class SettingsDestinationModel(
    val lyricsSettings: LyricsSettings = LyricsSettings(),
    val onLyricsSourcesChanged: (List<LyricsSourceId>, Set<LyricsSourceId>) -> Unit = { _, _ -> },
    val onLyricsWordFallbackChanged: (LyricsSourceId?) -> Unit = {},
    val onLyricSpringStrengthChanged: (Float) -> Unit = {},
    val onLyricAutoFullscreenChanged: (Boolean) -> Unit = {},
    val onLyricFocusReturnDelayChanged: (Long) -> Unit = {},
    val onLyricAutoFullscreenDelayChanged: (Long) -> Unit = {},
    val onSystemLyricModeChanged: (SystemLyricMode) -> Unit = {},
    val onStandaloneLyricNotificationChanged: (Boolean) -> Unit = {},
    val onLyricsAutoAlignChanged: (Boolean) -> Unit = {},
    val onPerSongLyricsConfigChanged: (Boolean) -> Unit = {},
    val onHyperosSuperIslandChanged: (Boolean) -> Unit = {},
    val crossfadeSeconds: Int = 0,
    val lastEnabledCrossfadeSeconds: Int = 4,
    val onCrossfadeSecondsChanged: (Int) -> Unit = {},
    val blendArtworkDuringCrossfade: Boolean = true,
    val onBlendArtworkDuringCrossfadeChanged: (Boolean) -> Unit = {},
    val gaplessPlayback: Boolean = true,
    val onGaplessPlaybackChanged: (Boolean) -> Unit = {},
    val showFullscreenQualityBadge: Boolean = false,
    val onShowFullscreenQualityBadgeChanged: (Boolean) -> Unit = {},
    val hideShortAudio: Boolean = false,
    val onHideShortAudioChanged: (Boolean) -> Unit = {},
    val audioQuality: com.exodidio.tune.player.AudioQualityMode =
        com.exodidio.tune.player.AudioQualityMode.Auto,
    val onAudioQualityChanged: (com.exodidio.tune.player.AudioQualityMode) -> Unit = {},
    val appleBackdrop: AppleBackdropSettings = AppleBackdropSettings.Default,
    val onAppleBackdropChanged: (AppleBackdropSettings) -> Unit = {},
    val dynamicBackdrop: DynamicBackdropSettings = DynamicBackdropSettings.Default,
    val onDynamicBackdropChanged: (DynamicBackdropSettings) -> Unit = {},
    val adaptiveTextColor: Boolean = false,
    val onAdaptiveTextColorChanged: (Boolean) -> Unit = {},
    val mediaNotificationStyle: com.exodidio.tune.player.MediaNotificationStyle =
        com.exodidio.tune.player.MediaNotificationStyle.Default,
    val onMediaNotificationStyleChanged: (com.exodidio.tune.player.MediaNotificationStyle) -> Unit = {},
    val normalizationAvailable: Boolean = false,
    val normalization: NormalizationSettings = NormalizationSettings(),
    val onNormalizationChanged: (NormalizationSettings) -> Unit = {},
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val onEqualizerEnabledChanged: (Boolean) -> Unit = {},
    val onEqualizerPresetSelected: (String) -> Unit = {},
    val onEqualizerBandChanged: (Int, Float) -> Unit = { _, _ -> },
    val onEqualizerProfileCreate: (String) -> Unit = {},
    val onEqualizerProfileReset: (String) -> Unit = {},
    val onEqualizerProfileDelete: (String) -> Unit = {},
    val funFeatures: FunFeaturesSettings = FunFeaturesSettings(),
    val onFunFeaturesChanged: (FunFeaturesSettings) -> Unit = {},
    val onExportBackup: () -> Unit = {},
    val onRestoreBackup: () -> Unit = {},
)

@Immutable internal data class AppDestinationModels(
    val home: HomeDestinationModel = HomeDestinationModel(),
    val discover: DiscoverDestinationModel = DiscoverDestinationModel(),
    val mine: MineDestinationModel = MineDestinationModel(),
    val insight: InsightDestinationModel = InsightDestinationModel(),
    val library: LibraryDestinationModel = LibraryDestinationModel(),
    val settings: SettingsDestinationModel = SettingsDestinationModel(),
)

@Immutable internal data class PlaybackModel(
    val state: PlaybackState = PlaybackState.Idle,
    val queue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    val queueTracks: List<LibraryTrack> = emptyList(),
    val lyrics: String? = null,
    val lyricsLoading: Boolean = false,
    val translationVisible: Boolean = true,
    val onTranslationToggle: () -> Unit = {},
    val lyricSpringStrength: Float = 90f,
    val lyricAutoFullscreen: Boolean = true,
    val lyricFocusReturnDelayMs: Long = com.exodidio.tune.lyrics.DefaultLyricFocusReturnDelayMs,
    val lyricAutoFullscreenDelayMs: Long = com.exodidio.tune.lyrics.DefaultLyricAutoFullscreenDelayMs,
    val onSearchLyrics: suspend (LibraryTrack, String, String) -> List<LyricsSearchResult> = { _, _, _ -> emptyList() },
    val onLyricsSelected: suspend (String, LyricsSearchResult) -> Unit = { _, _ -> },
    val lyricsMenu: com.exodidio.tune.ui.components.LyricsMenuActions =
        com.exodidio.tune.ui.components.LyricsMenuActions(),
    val artworkCrossfade: ArtworkCrossfadeTransition? = null,
    val blendArtworkDuringCrossfade: Boolean = true,
    val showFullscreenQualityBadge: Boolean = false,
    val appleBackdrop: AppleBackdropSettings = AppleBackdropSettings.Default,
    val dynamicBackdrop: DynamicBackdropSettings = DynamicBackdropSettings.Default,
    val adaptiveTextColor: Boolean = false,
    val systemVolume: Float = 0f,
    val onPrevious: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onQueueTrackSelected: (String) -> Unit = {},
    val onQueueReordered: (List<String>) -> Unit = {},
    val onQueueTrackRemoved: (String) -> Unit = {},
    val onShuffleChange: (Boolean) -> Unit = {},
    val onRepeatModeChange: (RepeatMode) -> Unit = {},
    val onSystemVolumeChange: (Float) -> Unit = {},
    val onMiniPlayerDismiss: () -> Unit = {},
    val onOpenMediaOutputSwitcher: () -> Unit = {},
    val onFavoriteToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onNeteaseLikeToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onTrackPlayNext: (String) -> Unit = {},
    val onTrackAddToQueue: (String) -> Unit = {},
    val moodRadioEligibleTrackIds: Set<String> = emptySet(),
    val onStartMoodRadio: (String) -> Unit = {},
    val moodRadioActive: Boolean = false,
    val playbackSpeed: Float = 1f,
    val abLoopState: AbLoopState = AbLoopState(),
    val sleepTimerState: SleepTimerState = SleepTimerState(),
    val onPlaybackSpeedSelected: (Float) -> Unit = {},
    val onSetAbLoopPointA: () -> Unit = {},
    val onSetAbLoopPointB: () -> Unit = {},
    val onFineTuneAbLoop: (Boolean, Long) -> Unit = { _, _ -> },
    val onClearAbLoop: () -> Unit = {},
    val onDeduplicateQueue: () -> Int = { 0 },
    val onSleepTimerMinutes: (Int) -> Unit = {},
    val onSleepTimerAfterTrack: () -> Unit = {},
    val onCancelSleepTimer: () -> Unit = {},
    val onExportLrc: (title: String, artist: String, rawLyrics: String?, offsetMs: Long) -> Unit = { _, _, _, _ -> },
    val funFeatures: FunFeaturesSettings = FunFeaturesSettings(),
    val currentTrackPlayCount: Int = 0,
    val onCassetteScrubChange: (Boolean, Float) -> Unit = { _, _ -> },
    val onReadAudioLevels: (FloatArray) -> Boolean = { false },
)
