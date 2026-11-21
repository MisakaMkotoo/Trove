package com.exodidio.tune.ui.navigation

import com.exodidio.tune.lyrics.RomanizationUiState

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt
import com.exodidio.tune.R
import com.exodidio.tune.player.ArtworkCrossfadeTransition
import com.exodidio.tune.player.PlaybackItem
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackState
import com.exodidio.tune.player.showsPauseGlyph
import com.exodidio.tune.player.RepeatMode
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.ui.theme.LocalTuneColors
private val FullScreenPlayerCompactArtworkSize = 80.dp
private val FullScreenPlayerCompactGap = 24.dp
private const val LyricsControlsIdleHideDelayMs = 5_000L
private const val LyricsControlsFadeMs = 450
/** 进度条触摸区比可见轨道高出的部分：实心遮挡层从这里开始，歌词一直露到进度条。 */
private val SliderTrackTopOverhang = 20.dp
// Reserve the player chrome, metadata, and a usable controls area on short screens.
private val FullScreenPlayerArtworkVerticalReserve = 448.dp
@Composable
internal fun FullScreenPlayer(
    visible: Boolean,
    dragProgress: Float,
    isDragging: Boolean,
    openingFromMiniPlayerSwipe: Boolean,
    playbackState: PlaybackState,
    queue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    queueTracks: List<LibraryTrack> = emptyList(),
    lyrics: String? = null,
    lyricsLoading: Boolean = false,
    romanization: RomanizationUiState = RomanizationUiState(),
    romanizationAllowed: Boolean = false,
    onRomanizationInput: (List<String>, Boolean) -> Unit = { _, _ -> },
    onRomanizationToggle: () -> Unit = {},
    artworkCrossfade: ArtworkCrossfadeTransition? = null,
    blendArtworkDuringCrossfade: Boolean = true,
    showQualityBadge: Boolean = false,
    appleBackdrop: com.exodidio.tune.player.AppleBackdropSettings = com.exodidio.tune.player.AppleBackdropSettings.Default,
    adaptiveTextColor: Boolean = false,
    playerTheme: PlayerTheme = PlayerTheme.Standard,
    showVolumeSlider: Boolean = false,
    fullscreenArtwork: Boolean = false,
    fullscreenLyrics: Boolean = false,
    lyricsFontLevel: Int = 1,
    volume: Float,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onQueueTrackSelected: (String) -> Unit = {},
    onQueueReordered: (List<String>) -> Unit = {},
    onQueueTrackRemoved: (String) -> Unit = {},
    onShuffleChange: (Boolean) -> Unit = {},
    onRepeatModeChange: (RepeatMode) -> Unit = {},
    isFavorite: Boolean = false,
    onFavoriteToggle: (String, Boolean) -> Unit = { _, _ -> },
    onTrackPlayNext: (String) -> Unit = {},
    onTrackAddToQueue: (String) -> Unit = {},
    moodRadioEligibleTrackIds: Set<String> = emptySet(),
    onStartMoodRadio: (String) -> Unit = {},
    moodRadioActive: Boolean = false,
    onTrackGoToAlbum: (String) -> Unit = {},
    onTrackGoToArtist: (String) -> Unit = {},
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onCloseFullscreenThen: ((() -> Unit) -> Unit) = { action -> action() },
    onOpenMediaOutputSwitcher: () -> Unit,
    onDismiss: () -> Unit,
    onDismissAnimationFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val item = playbackState.fullScreenItemOrNull() ?: return
    val colors = LocalTuneColors.current
    // The fullscreen panel moves independently from the persistent chrome. Its
    // blur source must therefore be isolated: sharing the shell source lets its
    // dark backdrop be sampled by the mini player while this panel is closing.
    val fullScreenHazeState = rememberHazeState()
    val glassHazeState = fullScreenHazeState.takeIf { hazeState != null }
    val coroutineScope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val expansionProgress = remember { Animatable(0f) }
    val closeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
    val playerDescription = stringResource(R.string.full_screen_player)
    var hasBeenVisible by remember { mutableStateOf(visible) }

    LaunchedEffect(isDragging, dragProgress, visible, openingFromMiniPlayerSwipe) {
        if (isDragging) {
            expansionProgress.snapTo(dragProgress)
        } else {
            if (visible) {
                hasBeenVisible = true
                dragOffset.snapTo(0f)
            }
            expansionProgress.animateTo(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (visible && openingFromMiniPlayerSwipe) 760 else if (visible) 520 else 400,
                    easing = FastOutSlowInEasing,
                ),
            )
            if (!visible) {
                dragOffset.snapTo(0f)
                if (hasBeenVisible) {
                    hasBeenVisible = false
                    onDismissAnimationFinished()
                }
            }
        }
    }

    if (expansionProgress.value <= 0f) return
    val artwork = rememberFullscreenArtwork(item.artworkPath)
    val activeArtworkCrossfade = artworkCrossfade.takeIf { blendArtworkDuringCrossfade }
    val incomingArtwork = rememberFullscreenArtwork(activeArtworkCrossfade?.toArtworkPath, keepPrevious = false)
    val crossfadeProgress = rememberArtworkCrossfadeProgress(artworkCrossfade)
    // The service announces the visual transition before it promotes the
    // playback item. Freeze the currently visible cover then, so decoding the
    // incoming cover never replaces it with a blank layer or a full-opacity
    // new cover before the blend can render.
    val outgoingArtwork = remember(activeArtworkCrossfade?.id) {
        activeArtworkCrossfade?.let { artwork }
    }
    val isArtworkCrossfading = activeArtworkCrossfade != null &&
        outgoingArtwork != null && incomingArtwork != null
    val contextTrack = queueTracks.firstOrNull { it.id == item.trackId }
        ?: com.exodidio.tune.sync.AndroidSyncRuntime.syncStore.trackForPlayback(item.trackId)
    val contextMetadata = remember(contextTrack?.metadataJson) { contextTrack?.metadataObject() }
    val metadataDurationMs = (contextMetadata?.get("duration") as? JsonPrimitive)
        ?.contentOrNull
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.times(1_000L)
    val currentPositionMs = playbackState.positionMsOrZero()
    val durationMs = playbackState.durationMsOrZero()
    val displayedDurationMs = durationMs.takeIf { it > 0L } ?: metadataDurationMs
    val isPreparing = playbackState is PlaybackState.Preparing
    val isPlaying = playbackState is PlaybackState.Playing
    val showsPause = playbackState.showsPauseGlyph()
    val canNavigatePrevious = queue.canNavigatePrevious()
    val canNavigateNext = queue.canNavigateNext()
    var isTrackContextMenuExpanded by remember(item.trackId) { mutableStateOf(false) }
    var selectedPanel by remember { mutableStateOf<FullScreenPlayerPanel?>(null) }
    val isPanelOpen = selectedPanel != null
    var isQueueReordering by remember { mutableStateOf(false) }
    // The reorderable library reports the end of a normal drag, but a panel
    // switch or fullscreen dismissal can interrupt that gesture first.
    LaunchedEffect(selectedPanel, visible) {
        if (!visible || selectedPanel != FullScreenPlayerPanel.Queue) {
            isQueueReordering = false
        }
    }
    val artworkScale by animateFloatAsState(
        targetValue = if (playbackState is PlaybackState.Paused && !isPanelOpen) 0.75f else 1f,
        animationSpec = tween(if (isPanelOpen) 320 else 500, easing = FastOutSlowInEasing),
        label = "full-screen-artwork-scale",
    )
    val artworkTransformOrigin by animateFloatAsState(
        targetValue = if (isPanelOpen) 0f else 0.5f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "full-screen-artwork-transform-origin",
    )
    var lyricsSeekPositionMs by remember(item.trackId) { mutableStateOf<Long?>(null) }
    var lyricsSeekRequestId by remember(item.trackId) { mutableLongStateOf(0L) }
    val horizontalSwipeState = remember { FullScreenPlayerSwipeState() }
    val displayedHorizontalSwipeOffset by animateFloatAsState(
        targetValue = if (horizontalSwipeState.isDragging) horizontalSwipeState.dragOffset else 0f,
        animationSpec = spring(),
        label = "full-screen-player-swipe",
    )

    // 歌词视图：歌词容器始终铺到屏幕底部，底部控件是一块与背景同色的实心遮挡层。
    // 显隐只改遮挡层 alpha，容器尺寸不变，歌词与当前行都不重排。
    val lyricsView = selectedPanel == FullScreenPlayerPanel.Lyrics
    val lyricsAvailable = !lyrics.isNullOrBlank()
    var lyricsOverlayVisible by remember(lyricsView) { mutableStateOf(true) }
    var playerInteractionTick by remember { mutableLongStateOf(0L) }
    var lyricsOverlayHeightPx by remember { mutableIntStateOf(0) }
    val lyricsOverlayAlpha by animateFloatAsState(
        targetValue = if (lyricsOverlayVisible) 1f else 0f,
        animationSpec = tween(LyricsControlsFadeMs),
        label = "lyrics-overlay-alpha",
    )
    val showLyricsControls: () -> Unit = {
        playerInteractionTick += 1
        lyricsOverlayVisible = true
    }
    // 点歌词文字只跳转：重新计时，但不改变控件当前显隐状态。
    val noteLyricsInteraction: () -> Unit = {
        playerInteractionTick += 1
    }
    LaunchedEffect(visible, lyricsView, isPlaying, lyricsAvailable, lyricsOverlayVisible, playerInteractionTick) {
        if (!visible || !lyricsView || !isPlaying || !lyricsAvailable) {
            if (!lyricsOverlayVisible) lyricsOverlayVisible = true
            return@LaunchedEffect
        }
        if (!lyricsOverlayVisible) return@LaunchedEffect
        delay(LyricsControlsIdleHideDelayMs)
        lyricsOverlayVisible = false
    }
    LaunchedEffect(visible) {
        if (!visible) lyricsOverlayVisible = true
    }

    val adaptiveEnabled = playerTheme == PlayerTheme.Adaptive
    // Fullscreen cover art = album art blends with the now-playing background.
    // Off (default) = normal card-shaped artwork. Independent of Adaptive theme.
    val fullscreenArtworkEnabled = fullscreenArtwork
    val adaptiveColors = rememberAdaptivePlayerColors(
        bitmap = artwork?.sourceBitmap,
        enabled = adaptiveEnabled || adaptiveTextColor,
    )
    val basePlayerColors = LocalTuneColors.current
    val effectivePlayerColors = when {
        // 自适应主题：背景和字体都取自封面。
        adaptiveEnabled -> basePlayerColors.copy(
            playerBackdrop = adaptiveColors.background,
            onPrimary = adaptiveColors.accent,
            textMain = adaptiveColors.accent,
            textMuted = adaptiveColors.dim,
            foregroundSubtle = adaptiveColors.faint,
        )
        // 独立开关：只把字体颜色换成封面取色，背景仍由所选主题决定。
        adaptiveTextColor -> basePlayerColors.copy(
            onPrimary = adaptiveColors.accent,
            textMain = adaptiveColors.accent,
            textMuted = adaptiveColors.dim,
            foregroundSubtle = adaptiveColors.faint,
        )
        else -> basePlayerColors
    }
    // 遮挡层要与背景完全同色：按 FullScreenPlayerBackground 的合成方式算出实心等价色。
    val overlayDominant by animateColorAsState(
        targetValue = artwork?.dominant ?: effectivePlayerColors.playerBackdrop,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "lyrics-overlay-dominant",
    )
    val lyricsOverlayColor = remember(overlayDominant, fullscreenArtworkEnabled, effectivePlayerColors.playerBackdrop) {
        val base = if (fullscreenArtworkEnabled) overlayDominant else effectivePlayerColors.playerBackdrop
        val tinted = overlayDominant.copy(alpha = 0.66f).compositeOver(base)
        if (fullscreenArtworkEnabled) tinted
        else effectivePlayerColors.playerBackdrop.copy(alpha = 0.24f).compositeOver(tinted)
    }

    @Composable
    fun PlayerControls(callModifier: Modifier) {
        FullScreenPlayerControls(
            trackId = item.trackId,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            displayedDurationMs = displayedDurationMs,
            isPreparing = isPreparing,
            isPlaying = showsPause,
            canNavigatePrevious = canNavigatePrevious,
            canNavigateNext = canNavigateNext,
            volume = volume,
            queue = queue,
            contextTrack = contextTrack,
            showQualityBadge = showQualityBadge,
            selectedPanel = selectedPanel,
            isQueueReordering = isQueueReordering,
            onPanelSelected = { selectedPanel = it },
            onSeekRequested = { positionMs ->
                lyricsSeekPositionMs = positionMs
                lyricsSeekRequestId += 1
                onSeek(positionMs)
            },
            onSeekConfirmed = { lyricsSeekPositionMs = null },
            onVolumeChange = onVolumeChange,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onOpenMediaOutputSwitcher = onOpenMediaOutputSwitcher,
            showVolumeSlider = showVolumeSlider,
            // Lyrics is compulsory: button always visible. fullscreenLyrics only controls presentation.
            showLyricsButton = true,
            modifier = callModifier,
        )
    }

    @Composable
    fun CompactMetadata(callModifier: Modifier = Modifier) {
        Box(callModifier) {
            FullScreenPlayerMetadataTransition(
                item = item,
                crossfade = artworkCrossfade,
                displayedHorizontalSwipeOffset = displayedHorizontalSwipeOffset,
                hazeState = glassHazeState,
                compact = true,
                isFavorite = isFavorite,
                onFavoriteToggle = onFavoriteToggle,
                contextTrack = contextTrack,
                contextMenuExpanded = isTrackContextMenuExpanded,
                onContextMenuOpen = { isTrackContextMenuExpanded = true },
                onContextMenuDismiss = { isTrackContextMenuExpanded = false },
                playbackQueue = queue,
                onTrackPlayNext = onTrackPlayNext,
                onTrackAddToQueue = onTrackAddToQueue,
                moodRadioEligibleTrackIds = moodRadioEligibleTrackIds,
                onStartMoodRadio = onStartMoodRadio,
                onTrackGoToAlbum = onTrackGoToAlbum,
                onTrackGoToArtist = onTrackGoToArtist,
                onTrackContextBottomSheet = onTrackContextBottomSheet,
                onCloseFullscreenThen = onCloseFullscreenThen,
            )
        }
    }
    CompositionLocalProvider(
        LocalTuneColors provides effectivePlayerColors,
        LocalPlayerSurfaceColor provides lyricsOverlayColor,
    ) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The player column has 20dp padding on each side. On short screens,
        // cap the square cover by height so the controls retain their space.
        val expandedArtworkSize = minOf(
            maxWidth - 40.dp,
            (maxHeight - FullScreenPlayerArtworkVerticalReserve).coerceAtLeast(0.dp),
        )
        val compactMetadataWidth = maxWidth - 20.dp
        val queuePanelWidth = maxWidth
        val artworkSize by animateDpAsState(
            targetValue = if (isPanelOpen || lyricsView) FullScreenPlayerCompactArtworkSize else expandedArtworkSize,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "full-screen-artwork-size",
        )
        val artworkHorizontalOffset by animateDpAsState(
            targetValue = if (isPanelOpen || lyricsView) 0.dp else ((maxWidth - 40.dp) - expandedArtworkSize) / 2,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "full-screen-artwork-horizontal-offset",
        )
        // 头部高度：普通播放器为大封面，面板打开后收成 80dp 紧凑头。
        // 队列重排时临时撑满，让列表用满整屏；歌词视图沿用紧凑头，歌词在下方铺满。
        val topBlockHeight by animateDpAsState(
            targetValue = when {
                lyricsView -> FullScreenPlayerCompactArtworkSize
                isQueueReordering -> maxHeight
                else -> expandedArtworkSize + 96.dp
            },
            animationSpec = tween(
                if (isQueueReordering) QueueReorderTransitionDurationMs else 320,
                easing = FastOutSlowInEasing,
            ),
            label = "full-screen-header-height",
        )
        val panelHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
        val panelOffsetPx = panelHeightPx * (1f - expansionProgress.value)
        val sliderOverhangPx = with(androidx.compose.ui.platform.LocalDensity.current) { SliderTrackTopOverhang.toPx() }
        // 遮挡层的实心部分从进度条开始，歌词模糊截止线同步上移这段触摸区。
        val lyricsBottomInsetPx =
            ((lyricsOverlayHeightPx - sliderOverhangPx).coerceAtLeast(0f)) * lyricsOverlayAlpha
        Box(
            modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, (panelOffsetPx + dragOffset.value).roundToInt()) }
            .alpha((expansionProgress.value * 8f).coerceIn(0f, 1f))
            .semantics { contentDescription = playerDescription }
            .pointerInput(closeThresholdPx) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount > 0f || dragOffset.value > 0f) {
                            coroutineScope.launch {
                                dragOffset.snapTo((dragOffset.value + dragAmount).coerceAtLeast(0f))
                            }
                        }
                    },
                    onDragCancel = { coroutineScope.launch { dragOffset.animateTo(0f, spring()) } },
                    onDragEnd = {
                        coroutineScope.launch {
                            if (dragOffset.value >= closeThresholdPx) onDismiss()
                            else dragOffset.animateTo(0f, spring())
                        }
                    },
                )
            },
    ) {
            FullScreenPlayerBackground(
                artwork = artwork,
                outgoingArtwork = outgoingArtwork,
                incomingArtwork = incomingArtwork,
                crossfadeProgress = crossfadeProgress,
                isArtworkCrossfading = isArtworkCrossfading,
                adaptiveBackground = fullscreenArtworkEnabled,
                playerTheme = playerTheme,
                appleBackdrop = appleBackdrop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (glassHazeState == null) Modifier else Modifier.hazeSource(glassHazeState)),
            )
            Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            ) {
            Spacer(Modifier.height(8.dp))
            FullScreenPlayerDragHandle()
            Spacer(Modifier.height(20.dp))
            FullScreenPlayerSwipeTarget(
                modifier = Modifier.padding(horizontal = 20.dp),
                testTag = FullScreenPlayerArtworkSwipeTestTag,
                swipeState = horizontalSwipeState,
                displayedOffset = displayedHorizontalSwipeOffset,
                onPrevious = onPrevious,
                onNext = onNext,
                canNavigatePrevious = canNavigatePrevious,
                canNavigateNext = canNavigateNext,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topBlockHeight),
                ) {
                    FullScreenPlayerArtwork(
                        artwork = artwork,
                        outgoingArtwork = outgoingArtwork,
                        incomingArtwork = incomingArtwork,
                        crossfadeProgress = crossfadeProgress,
                        isArtworkCrossfading = isArtworkCrossfading,
                        fullscreen = fullscreenArtworkEnabled,
                        modifier = Modifier
                            .size(artworkSize)
                            .offset(x = artworkHorizontalOffset)
                            .semantics { testTag = FullScreenPlayerArtworkTestTag }
                            .then(
                                if (isPanelOpen) {
                                    Modifier.clickable(
                                        onClick = { selectedPanel = null },
                                        role = Role.Button,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .graphicsLayer {
                                scaleX = artworkScale
                                scaleY = artworkScale
                                transformOrigin = TransformOrigin(artworkTransformOrigin, artworkTransformOrigin)
                            },
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isPanelOpen,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = expandedArtworkSize + 36.dp),
                        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { 10 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -10 },
                    ) {
                        FullScreenPlayerMetadataTransition(
                            item = item,
                            crossfade = artworkCrossfade,
                            displayedHorizontalSwipeOffset = displayedHorizontalSwipeOffset,
                            hazeState = glassHazeState,
                            compact = false,
                            isFavorite = isFavorite,
                            onFavoriteToggle = onFavoriteToggle,
                            contextTrack = contextTrack,
                            contextMenuExpanded = isTrackContextMenuExpanded,
                            onContextMenuOpen = { isTrackContextMenuExpanded = true },
                            onContextMenuDismiss = { isTrackContextMenuExpanded = false },
                            playbackQueue = queue,
                            onTrackPlayNext = onTrackPlayNext,
                            onTrackAddToQueue = onTrackAddToQueue,
                            moodRadioEligibleTrackIds = moodRadioEligibleTrackIds,
                            onStartMoodRadio = onStartMoodRadio,
                            onTrackGoToAlbum = onTrackGoToAlbum,
                            onTrackGoToArtist = onTrackGoToArtist,
                            onTrackContextBottomSheet = onTrackContextBottomSheet,
                            onCloseFullscreenThen = onCloseFullscreenThen,
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isPanelOpen,
                        modifier = Modifier
                            // Extend only the compact metadata into the outer
                            // column's right inset, leaving it flush to screen edge.
                            .requiredWidth(compactMetadataWidth)
                            .padding(start = FullScreenPlayerCompactArtworkSize + FullScreenPlayerCompactGap)
                            .height(FullScreenPlayerCompactArtworkSize),
                        enter = fadeIn(tween(200, delayMillis = 120)) + slideInVertically(tween(200, delayMillis = 120)) { 12 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -10 },
                    ) {
                        CompactMetadata(Modifier.fillMaxSize())
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = selectedPanel == FullScreenPlayerPanel.Queue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FullScreenPlayerCompactArtworkSize + 8.dp),
                        enter = fadeIn(tween(200, delayMillis = 120)) + slideInVertically(tween(200, delayMillis = 120)) { 12 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -10 },
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = selectedPanel,
                            transitionSpec = {
                                (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                    slideInVertically(tween(200, easing = FastOutSlowInEasing)) { 12 }) togetherWith
                                    (fadeOut(tween(120, easing = FastOutSlowInEasing)) +
                                        slideOutVertically(tween(120, easing = FastOutSlowInEasing)) { -10 })
                            },
                            label = "full-screen-player-panel-content",
                        ) { panel ->
                            if (panel == FullScreenPlayerPanel.Queue) {
                                FullScreenQueuePanel(
                                    queue = queue,
                                    moodRadioActive = moodRadioActive,
                                    tracks = queueTracks,
                                    currentTrackId = item.trackId,
                                    isPlaying = isPlaying,
                                    onTrackSelected = onQueueTrackSelected,
                                    onTrackRemoved = onQueueTrackRemoved,
                                    onTrackPlayNext = onTrackPlayNext,
                                    onReorder = onQueueReordered,
                                    onReorderDragStateChange = { isQueueReordering = it },
                                    onShuffleChange = onShuffleChange,
                                    onRepeatModeChange = onRepeatModeChange,
                                    onFavoriteChange = onFavoriteToggle,
                                    onTrackGoToAlbum = onTrackGoToAlbum,
                                    onTrackGoToArtist = onTrackGoToArtist,
                                    onTrackContextBottomSheet = onTrackContextBottomSheet,
                                    onCloseFullscreenThen = onCloseFullscreenThen,
                                    hazeState = glassHazeState,
                                    // Queue owns its row insets so its list and header can
                                    // reach both screen edges instead of inheriting the player
                                    // column's horizontal padding.
                                    modifier = Modifier
                                        .requiredWidth(queuePanelWidth)
                                        .fillMaxSize(),
                                )
                            } else {
                                // 歌词视图走独立的整屏布局，不会出现在这个顶部面板里。
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 歌词从紧凑头下方一直铺到屏幕底；进入时与头部收拢同步淡入。
                // 退出不做过场：头部展开期间歌词若还在淡出，会露在普通播放器上面。
                androidx.compose.animation.AnimatedVisibility(
                    visible = lyricsView,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(240, delayMillis = 60)) { 12 },
                    exit = androidx.compose.animation.ExitTransition.None,
                ) {
                    FullScreenPlayerLyricsPanel(
                        trackId = item.trackId,
                        lyrics = lyrics,
                        loading = lyricsLoading,
                        visible = visible,
                        romanization = romanization,
                        romanizationAllowed = romanizationAllowed,
                        onRomanizationInput = onRomanizationInput,
                        onRomanizationToggle = onRomanizationToggle,
                        currentPositionMs = currentPositionMs,
                        pendingSeekPositionMs = lyricsSeekPositionMs,
                        seekRequestId = lyricsSeekRequestId,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxSize(),
                        fullscreen = true,
                        bottomInsetPx = lyricsBottomInsetPx,
                        onShowControls = showLyricsControls,
                        onSeekInteraction = noteLyricsInteraction,
                        fontLevel = lyricsFontLevel,
                    )
                }
            }
            }
            // 底部控件始终作为遮挡层存在：歌词视图里是实心同色面板，其它状态透明。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (lyricsView) lyricsOverlayAlpha else 1f }
                    .onSizeChanged { lyricsOverlayHeightPx = it.height }
                    .then(
                        if (lyricsView && lyricsOverlayAlpha > 0.01f) {
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    // 只吞掉事件，遮住下面的歌词；控件自己先收到触摸。
                                    awaitFirstDown(requireUnconsumed = false).consume()
                                    noteLyricsInteraction()
                                }
                            }
                        } else {
                            // 完全淡出后不能留下任何触摸节点，否则会挡住下面歌词的点击与滚动。
                            Modifier
                        },
                    ),
            ) {
                // 实心背景从进度条的可见轨道开始：上方触摸区保持透明，歌词露到进度条为止。
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(top = SliderTrackTopOverhang)
                        .background(
                            when {
                                !lyricsView -> Color.Transparent
                                // 苹果式背景直接透出来；歌词由边缘截止渐隐挡住，不需要实色面板。
                                playerTheme == PlayerTheme.Apple -> Color.Transparent
                                else -> lyricsOverlayColor
                            },
                        ),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp),
                ) {
                    PlayerControls(Modifier.fillMaxWidth())
                }
            }
        }
        }
    }
}

private fun PlaybackState.fullScreenItemOrNull(): PlaybackItem? = when (this) {
    PlaybackState.Idle, is PlaybackState.Failed -> null
    is PlaybackState.Preparing -> item
    is PlaybackState.Playing -> item
    is PlaybackState.Paused -> item
}

private fun PlaybackState.positionMsOrZero(): Long = when (this) {
    is PlaybackState.Playing -> positionMs
    is PlaybackState.Paused -> positionMs
    else -> 0L
}

private fun PlaybackState.durationMsOrZero(): Long = when (this) {
    is PlaybackState.Playing -> durationMs
    is PlaybackState.Paused -> durationMs
    else -> 0L
}
