package com.exodidio.tune

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.media.MediaRouter2
import android.media.session.MediaSession
import android.database.ContentObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.provider.Settings
import android.view.WindowInsetsController
import android.view.KeyEvent
import com.exodidio.tune.library.matchTracks
import com.exodidio.tune.library.m3uContent
import com.exodidio.tune.library.parseM3u
import com.exodidio.tune.library.readTextFromUri
import com.exodidio.tune.library.writeTextToUri
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.settings.ThemePreferences
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.library.LocalLibraryScanner
import com.exodidio.tune.library.ScanFolderPreferences
import com.exodidio.tune.library.audioPermission
import com.exodidio.tune.sync.LibraryPlaylist
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.AndroidPlaybackSession
import com.exodidio.tune.player.PlaybackService
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.player.PlaybackState
import com.exodidio.tune.lyrics.AndroidLyricsService
import com.exodidio.tune.lyrics.LyricsPreferences
import kotlin.math.roundToInt
import com.exodidio.tune.ui.screens.LibraryTracksViewModel
import com.exodidio.tune.ui.screens.LibraryArtistsViewModel
import com.exodidio.tune.ui.screens.LibraryAlbumsViewModel
import com.exodidio.tune.ui.screens.LibraryAlbumsLayoutPreferences
import com.exodidio.tune.ui.screens.LibraryGenresViewModel
import com.exodidio.tune.ui.screens.LibraryComposersViewModel
import com.exodidio.tune.ui.screens.LibraryPlaylistsViewModel
import com.exodidio.tune.ui.screens.LibrarySearchViewModel
import com.exodidio.tune.ui.screens.playbackRequestFor
import com.exodidio.tune.ui.screens.PlaylistDetailsViewModel
import com.exodidio.tune.ui.screens.AlbumDetailsViewModel
import com.exodidio.tune.ui.screens.ArtistDetailsViewModel
import com.exodidio.tune.ui.screens.GenreDetailsViewModel
import com.exodidio.tune.ui.screens.ComposerDetailsViewModel
import com.exodidio.tune.ui.screens.InsightViewModel

private data class ManualLyricsOverride(val trackId: String, val content: String)

class MainActivity : ComponentActivity() {
    private var playbackServiceStarted = false
    private var systemMusicVolumeState by mutableFloatStateOf(0f)
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(ThemePreferences(applicationContext))
    }
    private val tracksViewModel: LibraryTracksViewModel by viewModels {
        LibraryTracksViewModel.Factory(
            AndroidSyncRuntime.syncStore,
            AndroidPlaybackRuntime.controller(),
            com.exodidio.tune.player.PlaybackPreferences(applicationContext).hideShortAudio,
        )
    }
    private val insightViewModel: InsightViewModel by viewModels {
        InsightViewModel.Factory(
            AndroidSyncRuntime.syncStore,
            AndroidPlaybackRuntime.controller(),
        )
    }
    private val artistsViewModel: LibraryArtistsViewModel by viewModels {
        LibraryArtistsViewModel.Factory(applicationContext, AndroidSyncRuntime.syncStore)
    }
    private val albumsViewModel: LibraryAlbumsViewModel by viewModels {
        LibraryAlbumsViewModel.Factory(
            AndroidSyncRuntime.syncStore,
            AndroidPlaybackRuntime.controller(),
            LibraryAlbumsLayoutPreferences(applicationContext),
        )
    }
    private val genresViewModel: LibraryGenresViewModel by viewModels {
        LibraryGenresViewModel.Factory(AndroidSyncRuntime.syncStore)
    }
    private val composersViewModel: LibraryComposersViewModel by viewModels {
        LibraryComposersViewModel.Factory(AndroidSyncRuntime.syncStore)
    }
    private val playlistsViewModel: LibraryPlaylistsViewModel by viewModels {
        LibraryPlaylistsViewModel.Factory(applicationContext, AndroidSyncRuntime.syncStore)
    }

    // M3U playlist import/export via the Storage Access Framework.
    private var exportTargetPlaylistId: String? = null
    private var pendingLrcExportContent: String? = null
    private val importM3uLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleM3uImport(it) }
    }
    private val exportM3uLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        uri?.let { savedUri ->
            exportTargetPlaylistId?.let { playlistId ->
                handleM3uExport(savedUri, playlistId)
            }
        }
        exportTargetPlaylistId = null
    }
    private val exportLrcLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content = pendingLrcExportContent
        pendingLrcExportContent = null
        if (uri != null && content != null) {
            handleLrcExport(uri, content)
        }
    }
    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(::handleBackupExport)
    }
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::handleBackupRestore)
    }
    private val searchViewModel: LibrarySearchViewModel by viewModels {
        LibrarySearchViewModel.Factory(
            AndroidSyncRuntime.syncStore,
            com.exodidio.tune.player.PlaybackPreferences(applicationContext).hideShortAudio,
            applicationContext,
        )
    }
    private val albumDetailsViewModel: AlbumDetailsViewModel by viewModels {
        AlbumDetailsViewModel.Factory(AndroidSyncRuntime.syncStore, AndroidPlaybackRuntime.controller())
    }
    private val playlistDetailsViewModel: PlaylistDetailsViewModel by viewModels {
        PlaylistDetailsViewModel.Factory(AndroidSyncRuntime.syncStore, AndroidPlaybackRuntime.controller())
    }
    private val artistDetailsViewModel: ArtistDetailsViewModel by viewModels {
        ArtistDetailsViewModel.Factory(AndroidSyncRuntime.syncStore, AndroidPlaybackRuntime.controller())
    }
    private val genreDetailsViewModel: GenreDetailsViewModel by viewModels {
        GenreDetailsViewModel.Factory(AndroidSyncRuntime.syncStore, AndroidPlaybackRuntime.controller())
    }
    private val foldersViewModel: com.exodidio.tune.ui.screens.LibraryFoldersViewModel by viewModels {
        com.exodidio.tune.ui.screens.LibraryFoldersViewModel.Factory(
            applicationContext,
            AndroidSyncRuntime.syncStore,
            AndroidPlaybackRuntime.controller(),
        )
    }
    private val composerDetailsViewModel: ComposerDetailsViewModel by viewModels {
        ComposerDetailsViewModel.Factory(AndroidSyncRuntime.syncStore, AndroidPlaybackRuntime.controller())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        AndroidSyncRuntime.initialize(applicationContext)
        com.exodidio.tune.netease.NeteaseRepository.initialize(applicationContext)
        com.exodidio.tune.netease.NeteaseWifiAutoCacheManager.initialize(applicationContext)
        com.exodidio.tune.ui.components.TuneImages.initialize(applicationContext)
        AndroidPlaybackRuntime.initialize(applicationContext, AndroidSyncRuntime.syncStore)
        com.exodidio.tune.lyrics.LyricOffsetStore.initialize(applicationContext)
        // 先把上次同步的电脑曲库垫进去，界面不用等网络；随后再后台刷新。
        // 恢复也放到 IO：整库元数据解析不能占着 onCreate 的主线程。
        lifecycleScope.launch(Dispatchers.IO) {
            // 启动读盘全部在后台：先等本地曲库与网易云缓存就绪，再做首页匹配/恢复，
            // 避免拿空库比对导致“未匹配”被写盘。
            runCatching { AndroidSyncRuntime.syncStore.awaitLoaded() }
            runCatching { com.exodidio.tune.netease.NeteaseRepository.awaitDiskLoaded() }
            runCatching { com.exodidio.tune.netease.NeteaseRepository.loadHome(applicationContext) }
            runCatching { com.exodidio.tune.sync.RemoteMusicLibrary.restoreCached(applicationContext) }
            // 配过电脑音乐库的话，启动时安静同步一次（失败自动退避重试，换网由网络回调再触发）。
            com.exodidio.tune.sync.RemoteMusicLibrary.initialize(applicationContext)
            com.exodidio.tune.sync.RemoteMusicLibrary.syncWithRetry(applicationContext)
        }
        systemMusicVolumeState = currentSystemMusicVolume()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NotificationPermissionRequest)
        }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val activePage = uiState.currentPage
            val activeDestination = uiState.selectedDestination
            val allTracks by AndroidSyncRuntime.syncStore.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
            // 播放器的曲目视图必须包含在线流式曲目，否则收藏/封面等元数据会缺失。
            val playbackOnlyTracks by AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val restoredQueueTracks by AndroidSyncRuntime.syncStore.restoredQueueTracksFlow
                .collectAsStateWithLifecycle(initialValue = emptyMap())
            val playbackQueueTracks = remember(allTracks, playbackOnlyTracks, restoredQueueTracks) {
                allTracks + playbackOnlyTracks + restoredQueueTracks.values
            }
            val moodRadioEligibleTrackIds by AndroidSyncRuntime.syncStore.moodRadioEligibleTrackIds.collectAsStateWithLifecycle(initialValue = emptySet())
            val allPlaylists by AndroidSyncRuntime.syncStore.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
            val homeUiState by if (activeDestination == AppDestination.Home && activePage == AppStackPage.Root) {
                tracksViewModel.homeUiState.collectAsStateWithLifecycle()
            } else {
                // AnimatedContent keeps the old page composed until its slide-out completes.
                // Preserve its last state without keeping every inactive flow subscribed.
                remember { mutableStateOf(tracksViewModel.homeUiState.value) }
            }
            val tracksUiState by if (
                activePage == AppStackPage.SettingsDuplicates ||
                (
                    activeDestination == AppDestination.Library &&
                        (activePage == AppStackPage.Root || activePage == AppStackPage.LibraryTracks || activePage == AppStackPage.LibraryDuplicates)
                    )
            ) {
                tracksViewModel.uiState.collectAsStateWithLifecycle()
            } else {
                remember { mutableStateOf(tracksViewModel.uiState.value) }
            }
            // “数据分析”在设置里打开时也必须保持订阅，否则拿到的是未初始化的空状态。
            val insightUiState by if (
                activePage == AppStackPage.SettingsInsight ||
                (activeDestination == AppDestination.Insight && activePage == AppStackPage.Root)
            ) insightViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(insightViewModel.uiState.value) }
            val artistsUiState by if (activePage == AppStackPage.LibraryArtists) artistsViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(artistsViewModel.uiState.value) }
            val albumsUiState by if (activePage == AppStackPage.LibraryAlbums) albumsViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(albumsViewModel.uiState.value) }
            val foldersUiState by if (activePage == AppStackPage.LibraryFolders) foldersViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(foldersViewModel.uiState.value) }
            val allFolders by if (activePage == AppStackPage.LibraryFolders || activePage == AppStackPage.LibraryFolderDetails) foldersViewModel.allFolders.collectAsStateWithLifecycle() else remember { mutableStateOf(foldersViewModel.allFolders.value) }
            val genresUiState by if (activePage == AppStackPage.LibraryGenres) genresViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(genresViewModel.uiState.value) }
            val composersUiState by if (activePage == AppStackPage.LibraryComposers) composersViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(composersViewModel.uiState.value) }
            val playlistsUiState by if (activePage == AppStackPage.LibraryPlaylists) playlistsViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(playlistsViewModel.uiState.value) }
            val searchUiState by searchViewModel.uiState.collectAsStateWithLifecycle()
            val albumDetailsUiState by if (activePage == AppStackPage.AlbumDetails) albumDetailsViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(albumDetailsViewModel.uiState.value) }
            val playlistDetailsUiState by if (activePage == AppStackPage.PlaylistDetails) playlistDetailsViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(playlistDetailsViewModel.uiState.value) }
            val artistDetailsUiState by if (activePage == AppStackPage.ArtistDetails) artistDetailsViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(artistDetailsViewModel.uiState.value) }
            val genreDetailsUiState by if (activePage == AppStackPage.GenreDetails) genreDetailsViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(genreDetailsViewModel.uiState.value) }
            val composerDetailsUiState by if (activePage == AppStackPage.ComposerDetails) composerDetailsViewModel.uiState.collectAsStateWithLifecycle() else remember { mutableStateOf(composerDetailsViewModel.uiState.value) }
            val playbackController = AndroidPlaybackRuntime.controller()
            val playbackPreferences = remember { com.exodidio.tune.player.PlaybackPreferences(applicationContext) }
            val normalizationPreferences = remember { com.exodidio.tune.player.NormalizationPreferences(applicationContext) }
            val equalizerPreferences = remember { com.exodidio.tune.player.EqualizerPreferences(applicationContext) }
            val lyricsPreferences = remember { LyricsPreferences(applicationContext) }
            val lyricsService = remember(applicationContext) { AndroidLyricsService(AndroidSyncRuntime.syncStore, appContext = applicationContext) }
            val preferenceScope = rememberCoroutineScope()
            // Auto-scan on app launch (#18): when a scan folder is configured,
            // audio permission is granted, and the library is empty, populate the
            // library automatically without requiring the user to tap "Scan".
            val scanner = remember { LocalLibraryScanner(applicationContext, AndroidSyncRuntime.syncStore) }
            val scanFolders = remember { ScanFolderPreferences(applicationContext) }
            val folder by scanFolders.folder.collectAsStateWithLifecycle(initialValue = null)
            var autoScanPending by remember { mutableStateOf(false) }

            val autoScanPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    val activity = this as Activity
                    val rationale = activity.shouldShowRequestPermissionRationale(audioPermission()) == true
                    preferenceScope.launch {
                        scanner.refreshPermission(granted, rationale)
                        if (granted && autoScanPending) {
                            autoScanPending = false
                            val singleFolder = folder ?: ""
                            // 曲库在后台读盘，直接判空会把“还没读完”当成“空库”，每次冷启动都全量重扫。
                            runCatching { AndroidSyncRuntime.syncStore.awaitLoaded() }
                            if (singleFolder.isNotBlank() && AndroidSyncRuntime.syncStore.tracks.value.isEmpty()) {
                                scanner.scan(setOf(singleFolder))
                            }
                        }
                    }
                }

            LaunchedEffect(Unit) {
                val activity = this@MainActivity
                if (activity.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED) {
                    preferenceScope.launch {
                        val singleFolder = folder ?: ""
                        runCatching { AndroidSyncRuntime.syncStore.awaitLoaded() }
                        if (singleFolder.isNotBlank() && AndroidSyncRuntime.syncStore.tracks.value.isEmpty()) {
                            scanner.refreshPermission(true, false)
                            scanner.scan(setOf(singleFolder))
                        }
                    }
                } else {
                    autoScanPending = true
                    autoScanPermissionLauncher.launch(audioPermission())
                }
            }

            val crossfadeSettings by playbackPreferences.settings.collectAsStateWithLifecycle(
                initialValue = com.exodidio.tune.player.CrossfadeSettings(0, 4, true, true),
            )
            val showFullscreenQualityBadge by playbackPreferences.showFullscreenQualityBadge.collectAsStateWithLifecycle(initialValue = false)
            val hideShortAudio by playbackPreferences.hideShortAudio.collectAsStateWithLifecycle(initialValue = false)
            val appleBackdropSettings by playbackPreferences.appleBackdrop.collectAsStateWithLifecycle(
                initialValue = com.exodidio.tune.player.AppleBackdropSettings.Default,
            )
            val dynamicBackdropSettings by playbackPreferences.dynamicBackdrop.collectAsStateWithLifecycle(
                initialValue = com.exodidio.tune.player.DynamicBackdropSettings.Default,
            )
            val audioQualityMode by playbackPreferences.audioQuality.collectAsStateWithLifecycle(
                initialValue = com.exodidio.tune.player.AudioQualityMode.Auto,
            )
            val mediaNotificationStyle by playbackPreferences.mediaNotificationStyle.collectAsStateWithLifecycle(
                initialValue = com.exodidio.tune.player.MediaNotificationStyle.Default,
            )
            val funFeaturesSettings by playbackPreferences.funFeatures.collectAsStateWithLifecycle(
                initialValue = com.exodidio.tune.player.FunFeaturesSettings(),
            )
            val dailyListeningStats by AndroidSyncRuntime.syncStore.dailyTrackListeningStats
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val trackPlayCounts = remember(dailyListeningStats) {
                com.exodidio.tune.library.SmartPlaylistEngine.aggregatePlayCounts(dailyListeningStats)
            }
            LaunchedEffect(audioQualityMode) {
                com.exodidio.tune.player.AudioQualityRuntime.mode = audioQualityMode
            }
            val adaptiveTextColor by playbackPreferences.adaptiveTextColor.collectAsStateWithLifecycle(initialValue = false)
            val normalizationSettings by normalizationPreferences.settings.collectAsStateWithLifecycle(
                initialValue = com.exodidio.tune.player.NormalizationSettings(),
            )
            val equalizerSettings by equalizerPreferences.settings.collectAsStateWithLifecycle(
                initialValue = com.exodidio.tune.player.EqualizerSettings(),
            )
            val lyricsSettings by lyricsPreferences.settings.collectAsStateWithLifecycle(initialValue = com.exodidio.tune.lyrics.LyricsSettings())
            val perSongLyricsConfigs by com.exodidio.tune.lyrics.PerSongLyricsConfigStore.configs.collectAsStateWithLifecycle()
            var hasAutoSyncModels by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                com.exodidio.tune.lyrics.PerSongLyricsConfigStore.load(applicationContext)
                hasAutoSyncModels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.exodidio.tune.lyrics.autosync.AutoSyncModelStore.installedLanguages(applicationContext).isNotEmpty()
                }
            }
            // Normalization is always available: cached BS.1770 analysis + real-time adaptive LUFS follower.
            val normalizationAvailable by AndroidSyncRuntime.syncStore.analysisAvailable.collectAsStateWithLifecycle(initialValue = true)
            val playbackState by playbackController.state.collectAsStateWithLifecycle()
            val playbackQueue by playbackController.queue.collectAsStateWithLifecycle()
            val moodRadioActive by playbackController.moodRadioActive.collectAsStateWithLifecycle()
            val artworkCrossfade by playbackController.artworkCrossfade.collectAsStateWithLifecycle()
            val playbackSpeed by playbackController.playbackSpeed.collectAsStateWithLifecycle()
            val abLoopState by playbackController.abLoopState.collectAsStateWithLifecycle()
            val sleepTimerState by playbackController.sleepTimerState.collectAsStateWithLifecycle()
            val lyricsTrackId = when (val state = playbackState) {
                is PlaybackState.Preparing -> state.item.trackId
                is PlaybackState.Playing -> state.item.trackId
                is PlaybackState.Paused -> state.item.trackId
                else -> null
            }
            val providerLyricsFlow = remember(lyricsTrackId) {
                lyricsTrackId?.let(AndroidSyncRuntime.syncStore::providerLyrics) ?: flowOf(null)
            }
            val providerLyrics by providerLyricsFlow.collectAsStateWithLifecycle(initialValue = null)
            var manualLyricsOverride by remember { mutableStateOf<ManualLyricsOverride?>(null) }
            LaunchedEffect(lyricsTrackId) {
                if (manualLyricsOverride?.trackId != lyricsTrackId) manualLyricsOverride = null
            }
            val lyrics = manualLyricsOverride?.takeIf { it.trackId == lyricsTrackId }?.content
                ?: com.exodidio.tune.lyrics.preferredLyrics(providerLyrics)
            var lyricsLoadingTrackId by remember { mutableStateOf<String?>(null) }
            val lyricsFetchKey = Triple(
                lyricsSettings.sourceOrder,
                lyricsSettings.enabledSources,
                lyricsSettings.wordFallbackSource,
            )
            var previousLyricsFetchKey by remember {
                mutableStateOf<Triple<List<com.exodidio.tune.lyrics.LyricsSourceId>, Set<com.exodidio.tune.lyrics.LyricsSourceId>, com.exodidio.tune.lyrics.LyricsSourceId?>?>(null)
            }
            LaunchedEffect(lyricsFetchKey) {
                val previous = previousLyricsFetchKey
                previousLyricsFetchKey = lyricsFetchKey
                if (previous != null && previous != lyricsFetchKey) {
                    lyricsService.cancelAutoAlign()
                    lyricsService.invalidateSearchCache()
                    AndroidSyncRuntime.syncStore.clearNonManualProviderLyrics()
                }
            }
            var previousAutoAlignEnabled by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(lyricsSettings.autoAlignWordTiming) {
                val enabled = lyricsSettings.autoAlignWordTiming
                val wasEnabled = previousAutoAlignEnabled
                previousAutoAlignEnabled = enabled
                if (wasEnabled == true && !enabled) {
                    lyricsService.cancelAutoAlign()
                    AndroidSyncRuntime.syncStore.restoreAllAlignedLyrics()
                }
            }
            LaunchedEffect(lyricsTrackId, providerLyrics, lyricsSettings, perSongLyricsConfigs, manualLyricsOverride) {
                if (lyricsTrackId == null || manualLyricsOverride?.trackId == lyricsTrackId || !providerLyrics.isNullOrBlank()) return@LaunchedEffect
                lyricsLoadingTrackId = lyricsTrackId
                try {
                    lyricsService.fetch(lyricsTrackId, lyricsSettings)
                } finally {
                    if (lyricsLoadingTrackId == lyricsTrackId) lyricsLoadingTrackId = null
                }
            }
            // 已缓存的歌词不会再 fetch：开关打开后补一次本地对齐（去重在 coordinator）。
            LaunchedEffect(lyricsTrackId, providerLyrics, lyricsSettings, perSongLyricsConfigs) {
                val id = lyricsTrackId ?: return@LaunchedEffect
                if (manualLyricsOverride?.trackId == id) return@LaunchedEffect
                val content = providerLyrics?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
                hasAutoSyncModels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.exodidio.tune.lyrics.autosync.AutoSyncModelStore.installedLanguages(applicationContext).isNotEmpty()
                }
                lyricsService.alignCachedLyrics(id, content, lyricsSettings)
            }
            DisposableEffect(Unit) {
                val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        systemMusicVolumeState = currentSystemMusicVolume()
                    }
                }
                contentResolver.registerContentObserver(
                    Settings.System.CONTENT_URI,
                    true,
                    volumeObserver,
                )
                onDispose { contentResolver.unregisterContentObserver(volumeObserver) }
            }
            var isFullScreenPlayerVisible by remember { mutableStateOf(false) }
            var isTerminalMode by remember { mutableStateOf(false) }
            var restoreFullScreenPlayerNonce by remember { mutableIntStateOf(0) }
            LaunchedEffect(viewModel) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is AppEffect.OpenExternalUrl -> {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)))
                        }
                        is AppEffect.ResetPoppedPage -> when (effect.page) {
                            AppStackPage.LibrarySearch -> searchViewModel.clear()
                            AppStackPage.LibraryTracks -> tracksViewModel.setFilterQuery("")
                            AppStackPage.LibraryArtists -> artistsViewModel.setFilterQuery("")
                            AppStackPage.LibraryAlbums -> albumsViewModel.setFilterQuery("")
                            AppStackPage.LibraryFolders -> foldersViewModel.setFilterQuery("")
                            AppStackPage.LibraryGenres -> genresViewModel.setFilterQuery("")
                            AppStackPage.LibraryComposers -> composersViewModel.setFilterQuery("")
                            else -> Unit
                        }
                        AppEffect.PickM3uImport -> {
                            importM3uLauncher.launch(arrayOf("*/*"))
                        }
                        is AppEffect.PickM3uExport -> {
                            exportTargetPlaylistId = effect.playlistId
                            val suggestedName = m3uSuggestedFileName(effect.playlistId)
                            exportM3uLauncher.launch(suggestedName)
                        }
                        AppEffect.RestoreFullScreenPlayer -> {
                            restoreFullScreenPlayerNonce++
                        }
                    }
                }
            }
            LaunchedEffect(playlistsViewModel) {
                playlistsViewModel.createdPlaylistIds.collect { playlistId ->
                    viewModel.dispatch(AppIntent.OpenPlaylistDetails(playlistId))
                }
            }
            val darkTheme = isDarkTheme(uiState.themeMode)
            SideEffect {
                updateSystemBarAppearance(darkTheme || isTerminalMode, isFullScreenPlayerVisible)
            }
            // Keep screen awake when the keep-screen-on setting is enabled (#11).
            SideEffect {
                val window = window
                if (uiState.keepScreenOn) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            val destinations = AppDestinationModels(
                home = HomeDestinationModel(
                    state = homeUiState,
                    onTrackClick = tracksViewModel::playHomeTrack,
                    onPlayTracks = { ids, index ->
                        if (index in ids.indices) playbackController.play(PlaybackRequest(ids, index))
                    },
                ),
                discover = DiscoverDestinationModel(
                    onPlayTracks = { ids, index ->
                        if (index in ids.indices) playbackController.play(PlaybackRequest(ids, index))
                    },
                    onOpenPlaylist = { playlist ->
                        com.exodidio.tune.netease.NeteaseRepository.stagePlaylist(
                            playlistId = playlist.id,
                            name = playlist.name,
                            coverUrl = playlist.coverUrl,
                            coverPath = playlist.coverPath,
                            trackCount = 0,
                        )
                        com.exodidio.tune.netease.NeteaseRepository.select(playlist.id)
                        viewModel.dispatch(AppIntent.OpenPageHere(AppStackPage.NeteasePlaylistDetails))
                    },
                ),
                mine = MineDestinationModel(
                    onPlayTracks = { ids, index ->
                        if (index in ids.indices) playbackController.play(PlaybackRequest(ids, index))
                    },
                ),
                insight = InsightDestinationModel(
                    state = insightUiState,
                    onLibraryPeriodSelected = insightViewModel::setLibraryPeriod,
                    onListeningPeriodSelected = insightViewModel::setListeningPeriod,
                    onTrackClick = insightViewModel::playTopTrack,
                    onReportYearSelected = insightViewModel::setReportYear,
                    onFootprintClick = insightViewModel::playFootprintTrack,
                ),
                library = LibraryDestinationModel(
                    tracks = LibraryTracksModel(
                        state = tracksUiState,
                        onSortOptionSelected = tracksViewModel::setSortOption,
                        onToggleSortOrder = tracksViewModel::toggleSortOrder,
                        onTrackClick = tracksViewModel::playTrack,
                        onPlayAll = tracksViewModel::playAll,
                        onFilterQueryChange = tracksViewModel::setFilterQuery,
                        onRecentTrackClick = tracksViewModel::playRecentTrack,
                    ),
                    artists = LibraryArtistsModel(
                        state = artistsUiState,
                        onSortOptionSelected = artistsViewModel::setSortOption,
                        onToggleSortOrder = artistsViewModel::toggleSortOrder,
                        onFilterQueryChange = artistsViewModel::setFilterQuery,
                        onPlay = artistDetailsViewModel::play,
                        onPlayNext = playbackController::playNext,
                        onAddToQueue = playbackController::append,
                        orderedTrackIds = artistDetailsViewModel::orderedTrackIds,
                        onArtistUpdate = artistsViewModel::updateArtistArtwork,
                    ),
                    albums = LibraryAlbumsModel(
                        state = albumsUiState,
                        onSortOptionSelected = albumsViewModel::setSortOption,
                        onToggleSortOrder = albumsViewModel::toggleSortOrder,
                        onLayoutModeSelected = albumsViewModel::setLayoutMode,
                        onPlay = albumDetailsViewModel::play,
                        onPlayAll = albumsViewModel::playAll,
                        onFilterQueryChange = albumsViewModel::setFilterQuery,
                        onTrackPlay = albumDetailsViewModel::playTrack,
                        onPlayNext = playbackController::playNext,
                        onAddToQueue = playbackController::append,
                        onAddToFavorites = { trackIds ->
                            preferenceScope.launch {
                                trackIds.forEach { trackId -> AndroidSyncRuntime.syncStore.setFavorite(trackId, true) }
                            }
                        },
                    ),
                    genres = LibraryGenresModel(
                        state = genresUiState,
                        onSortOptionSelected = genresViewModel::setSortOption,
                        onToggleSortOrder = genresViewModel::toggleSortOrder,
                        onFilterQueryChange = genresViewModel::setFilterQuery,
                        onPlay = genreDetailsViewModel::play,
                        onPlayNext = playbackController::playNext,
                        onAddToQueue = playbackController::append,
                        orderedTrackIds = genreDetailsViewModel::orderedTrackIds,
                    ),
                    folders = LibraryFoldersModel(
                        state = foldersUiState,
                        allFolders = allFolders,
                        onSortOptionSelected = foldersViewModel::setSortOption,
                        onToggleSortOrder = foldersViewModel::toggleSortOrder,
                        onFilterQueryChange = foldersViewModel::setFilterQuery,
                        onPlay = foldersViewModel::play,
                        onTrackPlay = foldersViewModel::playTrack,
                        onPlayNext = playbackController::playNext,
                        onAddToQueue = playbackController::append,
                        orderedTrackIds = foldersViewModel::orderedTrackIds,
                    ),
                    composers = LibraryComposersModel(
                        state = composersUiState,
                        onSortOptionSelected = composersViewModel::setSortOption,
                        onToggleSortOrder = composersViewModel::toggleSortOrder,
                        onFilterQueryChange = composersViewModel::setFilterQuery,
                        onPlay = composerDetailsViewModel::play,
                        onPlayNext = playbackController::playNext,
                        onAddToQueue = playbackController::append,
                        orderedTrackIds = composerDetailsViewModel::orderedTrackIds,
                    ),
                    playlists = LibraryPlaylistsModel(
                        state = playlistsUiState,
                        availablePlaylists = allPlaylists,
                        onPlay = playlistDetailsViewModel::play,
                        onTrackPlay = playlistDetailsViewModel::playTrack,
                        onTrackRemove = playlistDetailsViewModel::removeTrack,
                        onTrackMove = playlistDetailsViewModel::moveTrack,
                        onPlayNext = playbackController::playNext,
                        onAddToQueue = playbackController::append,
                        onUpdate = playlistsViewModel::updatePlaylist,
                        onDelete = playlistsViewModel::deletePlaylist,
                        onCreate = playlistsViewModel::createPlaylist,
                        onCreateWithTracks = { name, artwork, trackIds -> playlistsViewModel.createPlaylist(name, artwork, trackIds) },
                        onCreateSmart = playlistsViewModel::createSmartPlaylist,
                        onUpdateSmart = playlistsViewModel::updateSmartPlaylist,
                        onMembershipChange = playlistsViewModel::changePlaylistMembership,
                        onDeduplicate = playlistsViewModel::deduplicatePlaylist,
                    ),
                    search = LibrarySearchModel(
                        state = searchUiState,
                        onQueryChange = searchViewModel::setQuery,
                        onTrackClick = { trackId ->
                            playbackRequestFor(searchUiState.tracks, trackId)?.let(playbackController::play)
                        },
                        onOnlineModeChange = searchViewModel::setOnlineMode,
                        onOnlineCategoryChange = searchViewModel::setOnlineCategory,
                        onOnlineSubmit = searchViewModel::submitOnlineSearch,
                        onOnlineTrackPlay = { index ->
                            val ids = searchUiState.onlineTracks.map { it.id }
                            if (index in ids.indices) {
                                playbackController.play(PlaybackRequest(ids, index))
                            }
                        },
                        onOnlinePlaylistClick = { playlistId ->
                            // 搜索结果只提供元数据：先占位进详情页，条目由页面后台加载。
                            val info = searchUiState.onlinePlaylists.firstOrNull { it.id == playlistId }
                            com.exodidio.tune.netease.NeteaseRepository.stagePlaylist(
                                playlistId = playlistId.toString(),
                                name = info?.name.orEmpty(),
                                coverUrl = info?.coverUrl.orEmpty(),
                                coverPath = info?.artworkPath,
                                trackCount = info?.trackCount ?: 0,
                            )
                            com.exodidio.tune.netease.NeteaseRepository.select(playlistId.toString())
                            viewModel.dispatch(AppIntent.OpenPageHere(AppStackPage.NeteasePlaylistDetails))
                        },
                    ),
                    details = LibraryDetailActions(
                        albums = albumDetailsUiState,
                        playlists = playlistDetailsUiState,
                        artists = artistDetailsUiState,
                        genres = genreDetailsUiState,
                        composers = composerDetailsUiState,
                    ),
                ),
                settings = SettingsDestinationModel(
                    lyricsSettings = lyricsSettings,
                    onLyricsSourcesChanged = { order, enabled ->
                        preferenceScope.launch { lyricsPreferences.setSources(order, enabled) }
                    },
                    onLyricsWordFallbackChanged = { source ->
                        preferenceScope.launch { lyricsPreferences.setWordFallbackSource(source) }
                    },
                    onLyricSpringStrengthChanged = { strength -> preferenceScope.launch { lyricsPreferences.setLyricSpringStrength(strength) } },
                    onLyricAutoFullscreenChanged = { enabled -> preferenceScope.launch { lyricsPreferences.setLyricAutoFullscreen(enabled) } },
                    onLyricFocusReturnDelayChanged = { delayMs -> preferenceScope.launch { lyricsPreferences.setLyricFocusReturnDelayMs(delayMs) } },
                    onLyricAutoFullscreenDelayChanged = { delayMs -> preferenceScope.launch { lyricsPreferences.setLyricAutoFullscreenDelayMs(delayMs) } },
                    onSystemLyricModeChanged = { mode -> preferenceScope.launch { lyricsPreferences.setSystemLyricMode(mode) } },
                    onStandaloneLyricNotificationChanged = { enabled -> preferenceScope.launch { lyricsPreferences.setStandaloneLyricNotification(enabled) } },
                    onLyricsAutoAlignChanged = { enabled -> preferenceScope.launch { lyricsPreferences.setAutoAlignWordTiming(enabled) } },
                    onPerSongLyricsConfigChanged = { enabled -> preferenceScope.launch { lyricsPreferences.setPerSongLyricsConfigEnabled(enabled) } },
                    onHyperosSuperIslandChanged = { enabled -> preferenceScope.launch { lyricsPreferences.setHyperosSuperIsland(enabled) } },
                    crossfadeSeconds = crossfadeSettings.seconds,
                    lastEnabledCrossfadeSeconds = crossfadeSettings.lastEnabledSeconds,
                    onCrossfadeSecondsChanged = playbackController::setCrossfadeSeconds,
                    blendArtworkDuringCrossfade = crossfadeSettings.blendArtworkDuringCrossfade,
                    onBlendArtworkDuringCrossfadeChanged = playbackController::setBlendArtworkDuringCrossfade,
                    gaplessPlayback = crossfadeSettings.gaplessPlayback,
                    onGaplessPlaybackChanged = playbackController::setGaplessPlayback,
                    showFullscreenQualityBadge = showFullscreenQualityBadge,
                    onShowFullscreenQualityBadgeChanged = { enabled -> preferenceScope.launch { playbackPreferences.setShowFullscreenQualityBadge(enabled) } },
                    hideShortAudio = hideShortAudio,
                    onHideShortAudioChanged = { enabled -> preferenceScope.launch { playbackPreferences.setHideShortAudio(enabled) } },
                    audioQuality = audioQualityMode,
                    onAudioQualityChanged = { mode -> preferenceScope.launch { playbackPreferences.setAudioQuality(mode) } },
                    appleBackdrop = appleBackdropSettings,
                    onAppleBackdropChanged = { settings -> preferenceScope.launch { playbackPreferences.setAppleBackdrop(settings) } },
                    dynamicBackdrop = dynamicBackdropSettings,
                    onDynamicBackdropChanged = { settings -> preferenceScope.launch { playbackPreferences.setDynamicBackdrop(settings) } },
                    adaptiveTextColor = adaptiveTextColor,
                    onAdaptiveTextColorChanged = { enabled -> preferenceScope.launch { playbackPreferences.setAdaptiveTextColor(enabled) } },
                    mediaNotificationStyle = mediaNotificationStyle,
                    onMediaNotificationStyleChanged = { style ->
                        preferenceScope.launch { playbackPreferences.setMediaNotificationStyle(style) }
                    },
                    normalizationAvailable = normalizationAvailable,
                    normalization = normalizationSettings,
                    onNormalizationChanged = { settings -> preferenceScope.launch { normalizationPreferences.update { settings } } },
                    equalizer = equalizerSettings,
                    onEqualizerEnabledChanged = { enabled -> preferenceScope.launch { equalizerPreferences.setEnabled(enabled) } },
                    onEqualizerPresetSelected = { key -> preferenceScope.launch { equalizerPreferences.selectPreset(key) } },
                    onEqualizerBandChanged = { index, gain -> preferenceScope.launch { equalizerPreferences.setBand(equalizerSettings, index, gain) } },
                    onEqualizerProfileCreate = { name -> preferenceScope.launch { equalizerPreferences.createProfile(name) } },
                    onEqualizerProfileReset = { key -> preferenceScope.launch { equalizerPreferences.resetDefault(key) } },
                    onEqualizerProfileDelete = { key -> preferenceScope.launch { equalizerPreferences.deleteProfile(key) } },
                    funFeatures = funFeaturesSettings,
                    onFunFeaturesChanged = { updated ->
                        preferenceScope.launch { playbackPreferences.setFunFeatures(updated) }
                    },
                    onExportBackup = {
                        exportBackupLauncher.launch("tune-backup-${java.time.LocalDate.now()}.zip")
                    },
                    onRestoreBackup = {
                        importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                ),
            )
            val playback = PlaybackModel(
                state = playbackState,
                queue = playbackQueue,
                queueTracks = playbackQueueTracks,
                lyrics = lyrics,
                lyricsLoading = lyricsLoadingTrackId == lyricsTrackId,
                translationVisible = lyricsSettings.translationVisible,
                onTranslationToggle = {
                    preferenceScope.launch { lyricsPreferences.toggleTranslationVisible() }
                },
                lyricSpringStrength = lyricsSettings.lyricSpringStrength,
                lyricAutoFullscreen = lyricsSettings.lyricAutoFullscreen,
                lyricFocusReturnDelayMs = lyricsSettings.lyricFocusReturnDelayMs,
                lyricAutoFullscreenDelayMs = lyricsSettings.lyricAutoFullscreenDelayMs,
                onSearchLyrics = { track, title, artist -> lyricsService.search(track.id, title, artist, lyricsSettings) },
                onLyricsSelected = { trackId, lyric ->
                    lyricsService.cancelAutoAlign(trackId)
                    AndroidSyncRuntime.syncStore.saveProviderLyrics(trackId, lyric.content, lyric.source, manual = true)
                    if (trackId == lyricsTrackId) manualLyricsOverride = ManualLyricsOverride(trackId, lyric.content)
                },
                lyricsMenu = com.exodidio.tune.ui.components.LyricsMenuActions(
                    lyricsSettings = lyricsSettings,
                    perSongConfigs = perSongLyricsConfigs,
                    hasAutoSyncModels = hasAutoSyncModels,
                    sourceFlowFor = AndroidSyncRuntime.syncStore::providerLyricsSource,
                    baseSourceFor = AndroidSyncRuntime.syncStore::currentProviderLyricsBaseSource,
                    lyricsContentFlowFor = AndroidSyncRuntime.syncStore::providerLyrics,
                    onSelectSource = { trackId, source ->
                        if (trackId == lyricsTrackId) manualLyricsOverride = null
                        if (lyricsSettings.perSongLyricsConfigEnabled) {
                            com.exodidio.tune.lyrics.PerSongLyricsConfigStore.setPreferredSource(
                                applicationContext,
                                trackId,
                                source,
                            )
                            lyricsService.fetchFromSource(trackId, source, lyricsSettings)
                        } else if (source == null) {
                            lyricsService.fetchFromSource(trackId, null, lyricsSettings)
                        } else {
                            val nextOrder = listOf(source) + lyricsSettings.sourceOrder.filterNot { it == source }
                            val nextEnabled = lyricsSettings.enabledSources + source
                            if (nextOrder != lyricsSettings.sourceOrder || nextEnabled != lyricsSettings.enabledSources) {
                                lyricsPreferences.setSources(nextOrder, nextEnabled)
                                true
                            } else {
                                lyricsService.fetchFromSource(
                                    trackId,
                                    source,
                                    lyricsSettings.copy(sourceOrder = nextOrder, enabledSources = nextEnabled),
                                )
                            }
                        }
                    },
                    onToggleAutoAlign = { trackId, enabled ->
                        preferenceScope.launch {
                            if (lyricsSettings.perSongLyricsConfigEnabled) {
                                com.exodidio.tune.lyrics.PerSongLyricsConfigStore.setAutoAlignOverride(
                                    applicationContext,
                                    trackId,
                                    enabled,
                                )
                                if (!enabled) {
                                    lyricsService.cancelAutoAlign(trackId)
                                    AndroidSyncRuntime.syncStore.restoreAlignedLyrics(trackId)
                                } else {
                                    val content = AndroidSyncRuntime.syncStore.currentProviderLyrics(trackId)
                                    if (!content.isNullOrBlank()) {
                                        lyricsService.alignCachedLyrics(trackId, content, lyricsSettings)
                                    }
                                }
                            } else {
                                lyricsPreferences.setAutoAlignWordTiming(enabled)
                            }
                        }
                    },
                    onResetPerSong = { trackId ->
                        preferenceScope.launch {
                            com.exodidio.tune.lyrics.PerSongLyricsConfigStore.clearTrack(applicationContext, trackId)
                            lyricsService.cancelAutoAlign(trackId)
                            AndroidSyncRuntime.syncStore.clearProviderLyrics(trackId)
                            lyricsService.fetch(trackId, lyricsSettings)
                        }
                    },
                ),
                artworkCrossfade = artworkCrossfade,
                blendArtworkDuringCrossfade = crossfadeSettings.blendArtworkDuringCrossfade,
                showFullscreenQualityBadge = showFullscreenQualityBadge,
                appleBackdrop = appleBackdropSettings,
                dynamicBackdrop = dynamicBackdropSettings,
                adaptiveTextColor = adaptiveTextColor,
                systemVolume = systemMusicVolumeState,
                onPrevious = playbackController::previous,
                onPlayPause = playbackController::togglePlayPause,
                onNext = playbackController::next,
                onSeek = playbackController::seekTo,
                onQueueTrackSelected = playbackController::selectQueueTrack,
                onQueueReordered = playbackController::reorderQueue,
                onQueueTrackRemoved = playbackController::removeFromQueue,
                onShuffleChange = playbackController::setShuffle,
                onRepeatModeChange = playbackController::setRepeatMode,
                onFavoriteToggle = { trackId, favorite ->
                    preferenceScope.launch {
                        AndroidSyncRuntime.syncStore.setFavorite(trackId, favorite)
                    }
                },
                onNeteaseLikeToggle = { trackId, liked ->
                    val songId = com.exodidio.tune.netease.NeteaseRepository.songIdForTrack(trackId)
                    if (songId != null) {
                        if (com.exodidio.tune.netease.NeteaseRepository.cookie(this@MainActivity).isNullOrBlank()) {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                R.string.netease_login_required_action,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val track = playbackQueueTracks.firstOrNull { it.id == trackId }
                                ?: AndroidSyncRuntime.syncStore.trackForPlayback(trackId)
                            preferenceScope.launch {
                                com.exodidio.tune.netease.NeteaseRepository
                                    .setTrackLiked(this@MainActivity, track, songId, liked)
                                    .onFailure { err ->
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.netease_like_failed, err.message.orEmpty()),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                            }
                        }
                    }
                },
                onTrackPlayNext = playbackController::playNext,
                onTrackAddToQueue = { trackId -> playbackController.append(listOf(trackId)) },
                moodRadioEligibleTrackIds = moodRadioEligibleTrackIds,
                onStartMoodRadio = playbackController::startMoodRadio,
                moodRadioActive = moodRadioActive,
                onSystemVolumeChange = { volume ->
                    setSystemMusicVolume(volume.coerceIn(0f, 1f))
                    systemMusicVolumeState = currentSystemMusicVolume()
                },
                onMiniPlayerDismiss = playbackController::clearQueue,
                onOpenMediaOutputSwitcher = ::openMediaOutputSwitcher,
                playbackSpeed = playbackSpeed,
                abLoopState = abLoopState,
                sleepTimerState = sleepTimerState,
                onPlaybackSpeedSelected = playbackController::setPlaybackSpeed,
                onSetAbLoopPointA = playbackController::setAbLoopPointA,
                onSetAbLoopPointB = playbackController::setAbLoopPointB,
                onFineTuneAbLoop = playbackController::fineTuneAbLoop,
                onClearAbLoop = playbackController::clearAbLoop,
                onDeduplicateQueue = playbackController::deduplicateQueue,
                onSleepTimerMinutes = playbackController::setSleepTimerMinutes,
                onSleepTimerAfterTrack = playbackController::setSleepTimerAfterCurrentTrack,
                onCancelSleepTimer = playbackController::cancelSleepTimer,
                onExportLrc = { title, artist, rawLyrics, offsetMs ->
                    val content = com.exodidio.tune.lyrics.LrcExporter.buildExportableLrc(
                        title = title,
                        artist = artist,
                        rawLyrics = rawLyrics,
                        offsetMs = offsetMs,
                    )
                    pendingLrcExportContent = content
                    exportLrcLauncher.launch(
                        com.exodidio.tune.lyrics.LrcExporter.suggestedFileName(title, artist),
                    )
                },
                funFeatures = funFeaturesSettings,
                currentTrackPlayCount = if (funFeaturesSettings.vinylRingWear && lyricsTrackId != null) {
                    trackPlayCounts[lyricsTrackId] ?: 0
                } else {
                    0
                },
                onCassetteScrubChange = playbackController::setCassetteScrub,
                onReadAudioLevels = playbackController::readAudioLevels,
            )
            App(
                uiState = uiState,
                destinations = destinations,
                playback = playback,
                onIntent = viewModel::dispatch,
                restoreFullScreenPlayerNonce = restoreFullScreenPlayerNonce,
                onFullScreenPlayerVisibilityChanged = { visible ->
                    isFullScreenPlayerVisible = visible
                    updateSystemBarAppearance(darkTheme || isTerminalMode, visible)
                },
                onTerminalModeChanged = { enabled ->
                    isTerminalMode = enabled
                    updateSystemBarAppearance(darkTheme || enabled, isFullScreenPlayerVisible)
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun updateSystemBarAppearance(darkTheme: Boolean, fullScreenPlayerVisible: Boolean) {
        val lightSystemBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val statusBarAppearance = if (darkTheme || fullScreenPlayerVisible) {
            0
        } else {
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        }
        val navigationBarAppearance = if (darkTheme) {
            0
        } else {
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        }
        window.insetsController?.setSystemBarsAppearance(
            statusBarAppearance or navigationBarAppearance,
            lightSystemBars,
        )
    }

    private companion object {
        const val NotificationPermissionRequest = 51
    }

    private fun currentSystemMusicVolume(): Float {
        val manager = getSystemService(AudioManager::class.java)
        return normalizeSystemMusicVolume(
            current = manager.getStreamVolume(AudioManager.STREAM_MUSIC),
            maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
    }

    private fun setSystemMusicVolume(volume: Float) {
        val manager = getSystemService(AudioManager::class.java)
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, (maximum * volume).roundToInt(), 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> predictSystemMusicVolumeChange(-1)
            KeyEvent.KEYCODE_VOLUME_UP -> predictSystemMusicVolumeChange(1)
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun predictSystemMusicVolumeChange(direction: Int) {
        val manager = getSystemService(AudioManager::class.java)
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        systemMusicVolumeState = adjustSystemMusicVolume(systemMusicVolumeState, maximum, direction)
    }

    private fun openMediaOutputSwitcher() {
        if (canShowSystemMediaOutputSwitcher(Build.VERSION.SDK_INT)) {
            showSystemOutputSwitcher(
                router = MediaRouter2.getInstance(this),
                sessionToken = AndroidPlaybackSession.tokenOrNull(),
            )
        }
    }

    private fun isDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
        ThemeMode.System -> {
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.Amoled -> true
    }

    // ---- M3U playlist import/export (Storage Access Framework) ----

    private fun handleM3uImport(uri: Uri) {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { readTextFromUri(contentResolver, uri) } ?: return@launch
            val paths = parseM3u(content)
            val store = AndroidSyncRuntime.syncStore
            val matched = matchTracks(paths, store.tracks.value)
            if (matched.isNotEmpty()) {
                val displayName = uri.lastPathSegment?.let { it.substringBeforeLast('.') } ?: "Imported playlist"
                val name = if (displayName.isNotBlank()) displayName else "Imported playlist"
                playlistsViewModel.createPlaylist(name, null, matched)
            }
        }
    }

    private fun handleM3uExport(uri: Uri, playlistId: String) {
        lifecycleScope.launch {
            val store = AndroidSyncRuntime.syncStore
            val allTracks = store.tracks.value + store.onlineFavorites.value + store.playbackOnlyTracksFlow.value
            val trackById = allTracks.associateBy { it.id }
            val selectedTracks = if (playlistId == com.exodidio.tune.ui.screens.FavoritesPlaylistId) {
                allTracks.filter { com.exodidio.tune.ui.screens.isFavoriteOf(it.metadataObject()) }
            } else {
                val playlist = store.playlists.value.firstOrNull { it.id == playlistId }
                val smartSpec = playlist?.let { com.exodidio.tune.library.SmartPlaylistEngine.parseSpec(it.metadataJson) }
                if (smartSpec != null) {
                    val playCounts = com.exodidio.tune.library.SmartPlaylistEngine.aggregatePlayCounts(
                        store.dailyTrackListeningStats.value,
                    )
                    com.exodidio.tune.library.SmartPlaylistEngine.evaluate(smartSpec, allTracks, playCounts)
                } else {
                    playlist?.trackIds?.mapNotNull(trackById::get).orEmpty()
                }
            }
            val entries = selectedTracks.mapNotNull { track ->
                track.audioPath?.takeIf { it.isNotBlank() }?.let { path -> track.title to path }
            }
            if (entries.isEmpty()) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    R.string.playlist_export_empty,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val content = m3uContent(entries)
                    writeTextToUri(contentResolver, uri, content)
                }.getOrDefault(false)
            }
            val message = if (saved) {
                getString(R.string.playlist_export_success, entries.size)
            } else {
                getString(R.string.playlist_export_failed)
            }
            android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun m3uSuggestedFileName(playlistId: String): String {
        val name = if (playlistId == com.exodidio.tune.ui.screens.FavoritesPlaylistId) {
            getString(R.string.library_favorites)
        } else {
            AndroidSyncRuntime.syncStore.playlists.value.firstOrNull { it.id == playlistId }?.name
        }
        val base = name?.takeIf { it.isNotBlank() } ?: "playlist"
        return "$base.m3u8"
    }

    private fun handleLrcExport(uri: Uri, content: String) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { writeTextToUri(contentResolver, uri, content) }.getOrDefault(false)
            }
            android.widget.Toast.makeText(
                this@MainActivity,
                if (saved) R.string.player_export_lrc_success else R.string.player_export_lrc_failed,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun handleBackupExport(uri: Uri) {
        lifecycleScope.launch {
            val result = com.exodidio.tune.settings.AppBackupManager.exportBackup(
                context = applicationContext,
                uri = uri,
                syncStore = AndroidSyncRuntime.syncStore,
            )
            val message = result.fold(
                onSuccess = { summary ->
                    getString(R.string.backup_export_success, summary.playlistCount, summary.favoriteCount)
                },
                onFailure = { error ->
                    getString(R.string.backup_export_failed, error.message.orEmpty())
                },
            )
            android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBackupRestore(uri: Uri) {
        lifecycleScope.launch {
            val result = com.exodidio.tune.settings.AppBackupManager.restoreBackup(
                context = applicationContext,
                uri = uri,
                syncStore = AndroidSyncRuntime.syncStore,
            )
            val message = result.fold(
                onSuccess = { summary ->
                    getString(R.string.backup_restore_success, summary.playlistCount, summary.favoriteCount)
                },
                onFailure = { error ->
                    getString(R.string.backup_restore_failed, error.message.orEmpty())
                },
            )
            android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
        ensurePlaybackServiceStarted()
    }

    /**
     * 在 onCreate 里直接 startService 会被系统按“后台启动服务”拒绝（BackgroundServiceStartNotAllowedException）。
     * 放到 onResume：此时进程已是前台；万一仍被拒，下一次 onResume 会重试，并留下日志而不是静默吞掉。
     */
    private fun ensurePlaybackServiceStarted() {
        if (playbackServiceStarted) return
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
            .onSuccess { playbackServiceStarted = true }
            .onFailure { error -> Log.w("Tune", "PlaybackService start deferred: ${error.message}") }
    }

    /** 显式请求屏幕最高刷新率；仅在显示模式变化时更新窗口属性，避免系统旋转锁定状态被重置。 */
    private fun requestHighRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            val modes = display.supportedModes
            val best = modes.maxByOrNull { it.refreshRate } ?: return@runCatching
            if (best.refreshRate <= 60f) return@runCatching
            val current = window.attributes
            if (current.preferredDisplayModeId == best.modeId &&
                current.preferredRefreshRate == best.refreshRate
            ) {
                return@runCatching
            }
            window.attributes = current.apply {
                preferredRefreshRate = best.refreshRate
                preferredDisplayModeId = best.modeId
            }
        }
    }

}

internal fun canShowSystemMediaOutputSwitcher(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

internal fun normalizeSystemMusicVolume(current: Int, maximum: Int): Float =
    current.coerceIn(0, maximum.coerceAtLeast(1)).toFloat() / maximum.coerceAtLeast(1)

internal fun adjustSystemMusicVolume(current: Float, maximum: Int, direction: Int): Float =
    normalizeSystemMusicVolume(
        current = (current.coerceIn(0f, 1f) * maximum.coerceAtLeast(1)).roundToInt() + direction,
        maximum = maximum,
    )

/**
 * Android 16 QPR 1 added the session-bound overload. Reflection keeps the app
 * buildable with API 36 while using it on devices where the platform provides it.
 */
internal fun showSystemOutputSwitcher(router: MediaRouter2, sessionToken: MediaSession.Token?): Boolean {
    if (sessionToken != null) {
        val method = router.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "showSystemOutputSwitcher" &&
                candidate.parameterTypes.contentEquals(arrayOf(MediaSession.Token::class.java))
        }
        if (method != null) {
            runCatching { method.invoke(router, sessionToken) as Boolean }.getOrNull()?.let { result ->
                return result
            }
        }
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        router.showSystemOutputSwitcher()
    } else {
        false
    }
}
