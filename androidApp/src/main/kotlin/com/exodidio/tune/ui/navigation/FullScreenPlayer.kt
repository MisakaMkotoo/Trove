package com.exodidio.tune.ui.navigation

import android.os.Build
import android.util.Log
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt
import com.exodidio.tune.R
import com.exodidio.tune.player.AbLoopState
import com.exodidio.tune.player.ArtworkCrossfadeTransition
import com.exodidio.tune.player.PlaybackItem
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackState
import com.exodidio.tune.player.showsPauseGlyph
import com.exodidio.tune.player.RepeatMode
import com.exodidio.tune.player.SleepTimerState
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.ui.components.LocalControlStyle
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.LocalTuneBackdrop
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.rememberArtworkThumbnail
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
private val FullScreenPlayerCompactArtworkSize = 60.dp
/** 紧凑头再往右缩进：连同 20dp 内容边距对齐 Apple 实测的约 32dp。 */
// 父列自带 20dp 水平内边距：这里 15dp 偏移 => 封面主体绝对左边距 35dp。
private val FullScreenPlayerCompactLeftInset = 15.dp
/** 紧凑封面圆角：Apple 实测约 3.5dp（60dp 封面）。 */
private val FullScreenPlayerCompactCornerRadius = 3.5.dp
/** 苹果播放页封面：盒子宽 = 屏宽 - 48dp（左右各 24dp），暂停缩到 0.85。 */
private val FullScreenArtworkHorizontalInset = 48.dp
/** 状态栏 + 拖拽把手之后再留 35dp，封面顶部落在约 93dp。 */
private val FullScreenArtworkTopGap = 35.dp
/** 封面盒子到标题块的距离（苹果实测 59dp）。 */
private val FullScreenArtworkMetadataGap = 59.dp
/** 标题 + 艺人两行的块高，用于撑开头部容器。 */
private val FullScreenArtworkMetadataHeight = 56.dp
private val FullScreenPlayerCompactGap = 9.dp
/** 紧凑文字块比父级多出的宽度（伸到屏幕右缘）；requiredWidth 会居中，需要右移一半抵消。 */
private val FullScreenPlayerCompactRightOverflow = 20.dp
private const val LyricsControlsIdleHideDelayMs = 3_000L
private const val LyricsControlsFadeMs = 180
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
    translationVisible: Boolean = true,
    onTranslationToggle: () -> Unit = {},
    lyricSpringStrength: Float = 90f,
    lyricAutoFullscreen: Boolean = true,
    lyricFocusReturnDelayMs: Long = BrowseIdleReturnMs,
    lyricAutoFullscreenDelayMs: Long = LyricsControlsIdleHideDelayMs,
    artworkCrossfade: ArtworkCrossfadeTransition? = null,
    blendArtworkDuringCrossfade: Boolean = true,
    showQualityBadge: Boolean = false,
    appleBackdrop: com.exodidio.tune.player.AppleBackdropSettings = com.exodidio.tune.player.AppleBackdropSettings.Default,
    dynamicBackdrop: com.exodidio.tune.player.DynamicBackdropSettings = com.exodidio.tune.player.DynamicBackdropSettings.Default,
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
    isNeteaseTrack: Boolean? = null,
    isNeteaseLiked: Boolean = false,
    onNeteaseLikeToggle: (String, Boolean) -> Unit = { _, _ -> },
    onTrackPlayNext: (String) -> Unit = {},
    onTrackAddToQueue: (String) -> Unit = {},
    moodRadioEligibleTrackIds: Set<String> = emptySet(),
    onStartMoodRadio: (String) -> Unit = {},
    moodRadioActive: Boolean = false,
    onTrackGoToAlbum: (String) -> Unit = {},
    onTrackGoToArtist: (String) -> Unit = {},
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onCloseFullscreenThen: ((() -> Unit) -> Unit) = { action -> action() },
    playbackSpeed: Float = 1f,
    abLoopState: AbLoopState = AbLoopState(),
    sleepTimerState: SleepTimerState = SleepTimerState(),
    onPlaybackSpeedSelected: (Float) -> Unit = {},
    onSetAbLoopPointA: () -> Unit = {},
    onSetAbLoopPointB: () -> Unit = {},
    onFineTuneAbLoop: (isPointA: Boolean, deltaMs: Long) -> Unit = { _, _ -> },
    onClearAbLoop: () -> Unit = {},
    onDeduplicateQueue: () -> Int = { 0 },
    onSleepTimerMinutes: (Int) -> Unit = {},
    onSleepTimerAfterTrack: () -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onExportLrc: (title: String, artist: String, rawLyrics: String?, offsetMs: Long) -> Unit = { _, _, _, _ -> },
    funFeatures: com.exodidio.tune.player.FunFeaturesSettings = com.exodidio.tune.player.FunFeaturesSettings(),
    currentTrackPlayCount: Int = 0,
    onCassetteScrubChange: (Boolean, Float) -> Unit = { _, _ -> },
    onOpenMediaOutputSwitcher: () -> Unit,
    onDismiss: () -> Unit,
    onDismissAnimationFinished: () -> Unit = {},
    miniPlayerBounds: MiniPlayerTransitionBounds = MiniPlayerTransitionBounds(),
    onExpansionProgressChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val item = playbackState.fullScreenItemOrNull() ?: return
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    // The fullscreen panel moves independently from the persistent chrome. Its
    // blur source must therefore be isolated: sharing the shell source lets its
    // dark backdrop be sampled by the mini player while this panel is closing.
    val fullScreenHazeState = rememberHazeState()
    val glassHazeState = fullScreenHazeState.takeIf { hazeState != null }
    val coroutineScope = rememberCoroutineScope()
    var isDismissDragging by remember { mutableStateOf(false) }
    var dismissDragDistancePx by remember { mutableFloatStateOf(0f) }
    var dismissDragStartProgress by remember { mutableFloatStateOf(1f) }
    val expansionProgress = remember { Animatable(0f) }
    val closeThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val playerDescription = stringResource(R.string.full_screen_player)
    var hasBeenVisible by remember { mutableStateOf(visible) }
    var artworkTargetRectInRoot by remember { mutableStateOf<Rect?>(null) }
    var compactArtworkTargetRectInRoot by remember { mutableStateOf<Rect?>(null) }
    var panelOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    val hasMorphBounds = miniPlayerBounds.pillRectInRoot != null && !reduceMotion
    val latestOnExpansionProgressChanged by rememberUpdatedState(onExpansionProgressChanged)
    val shellSettledOpen by remember(visible, isDragging) {
        derivedStateOf {
            visible &&
                !isDragging &&
                !isDismissDragging &&
                !expansionProgress.isRunning &&
                expansionProgress.value >= 0.999f
        }
    }
    val flattenAnim = remember { Animatable(if (shellSettledOpen) 1f else 0f) }
    val isBackdropActive by remember(visible, isDragging) {
        derivedStateOf {
            visible || isDragging || isDismissDragging || expansionProgress.value > 0f
        }
    }

    LaunchedEffect(expansionProgress.value) {
        latestOnExpansionProgressChanged(expansionProgress.value)
    }

    LaunchedEffect(shellSettledOpen) {
        if (shellSettledOpen) {
            flattenAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = PlayerCornerFlattenMillis,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            flattenAnim.snapTo(0f)
        }
    }

    LaunchedEffect(
        isDragging,
        isDismissDragging,
        dragProgress,
        visible,
        openingFromMiniPlayerSwipe,
        hasMorphBounds,
        reduceMotion,
    ) {
        if (isDragging) {
            isDismissDragging = false
            dismissDragDistancePx = 0f
            expansionProgress.snapTo(dragProgress)
        } else if (!isDismissDragging) {
            if (visible) {
                hasBeenVisible = true
            }
            val spec = when {
                reduceMotion -> snap()
                hasMorphBounds -> spring(
                    dampingRatio = 1f,
                    stiffness = if (visible) 360f else 420f,
                    visibilityThreshold = 0.001f,
                )
                else -> tween(
                    durationMillis = if (visible && openingFromMiniPlayerSwipe) 760 else if (visible) 520 else 400,
                    easing = FastOutSlowInEasing,
                )
            }
            expansionProgress.animateTo(
                targetValue = if (visible) 1f else 0f,
                animationSpec = spec,
            )
            if (!visible) {
                dismissDragDistancePx = 0f
                if (hasBeenVisible) {
                    hasBeenVisible = false
                    onDismissAnimationFinished()
                }
            }
        }
    }

    if (expansionProgress.value <= 0f && !isDismissDragging) return
    // 首帧立即从 MiniPlayer 已缓存缩略图与主色种子起步，避免首次展开时闪黑屏。
    val seedThumbnail = rememberArtworkThumbnail(item.artworkPath)
    val seedArtwork = remember(item.artworkPath, seedThumbnail) {
        seedThumbnail?.let { img ->
            val identity = artworkIdentity(item.artworkPath) ?: item.artworkPath
            val bmp = runCatching { img.asAndroidBitmap() }.getOrNull()
            val dom = FullScreenDominantCache.getOrSampleQuick(identity, bmp) ?: Color.Transparent
            FullScreenArtwork(img, dom, bmp, identity)
        }
    }
    // 播放页封面走 1600² 高清：命中缓存立即用，没有就后台补；补好之前继续显示 600² 图。
    // 播放页封面直接走 Coil（与列表共用缓存）：先用列表尺寸兜底，再升 600，最后才换 1600。
    val coverInfo by produceState<com.exodidio.tune.netease.NeteaseRepository.PlaybackCoverInfo?>(
        initialValue = null,
        item.trackId,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { com.exodidio.tune.netease.NeteaseRepository.playbackCoverInfo(item.trackId) }
                .getOrNull()
        }
    }
    val coverUrl = coverInfo?.url
    val coverKey = coverInfo?.key
    val thumbArtwork = rememberCoilArtwork(coverUrl, 200, coverKey)
    val previewArtwork = rememberCoilArtwork(coverUrl, 600, coverKey)
    var hiResPath by remember(item.trackId) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.trackId, previewArtwork != null) {
        // 600 真正显示出来之后才拉 1600，避免和切歌抢带宽。
        if (previewArtwork == null) return@LaunchedEffect
        // 连点切歌时这一首可能只停留几十毫秒：稍等再拉高清封面，
        // 中间曲目被切走时协程自动取消，不会每首向网易云要一张 1600 图。
        delay(500)
        hiResPath = runCatching {
            com.exodidio.tune.netease.NeteaseRepository.ensureHiResCover(item.trackId)
        }.getOrNull()?.also { path ->
            Log.d("HIRES", "use track=${item.trackId} file=${path.substringAfterLast('/')}")
        }
    }
    val hiResArtwork = rememberFullscreenArtwork(hiResPath)
    // Coil 没取到（本地曲目/离线）时退回文件封面，最后退回已缓存缩略图种子。
    val fallbackArtwork = rememberFullscreenArtwork(
        if (thumbArtwork == null && previewArtwork == null) item.artworkPath else null,
    )
    val coverArtwork = hiResArtwork?.takeIf { it.identity != null && it.identity == coverKey }
        ?: previewArtwork
        ?: thumbArtwork
        ?: fallbackArtwork
        ?: seedArtwork
    // 背景与取色固定用 600：新 600 到达前保持上一张，避免一次切歌里背景换两次。
    var stableBackground by remember { mutableStateOf<FullScreenArtwork?>(null) }
    LaunchedEffect(previewArtwork) {
        if (previewArtwork != null) stableBackground = previewArtwork
    }
    val backgroundArtwork = stableBackground?.takeIf { it.identity == coverKey }
        ?: previewArtwork
        ?: coverArtwork
        ?: seedArtwork
    val artworkSwap = rememberArtworkSwap(coverArtwork)
    val activeArtworkCrossfade = artworkCrossfade.takeIf { blendArtworkDuringCrossfade }
    val incomingArtwork = rememberFullscreenArtwork(activeArtworkCrossfade?.toArtworkPath, keepPrevious = false)
    val crossfadeProgress = rememberArtworkCrossfadeProgress(artworkCrossfade)
    // The service announces the visual transition before it promotes the
    // playback item. Freeze the currently visible cover then, so decoding the
    // incoming cover never replaces it with a blank layer or a full-opacity
    // new cover before the blend can render.
    val outgoingArtwork = remember(activeArtworkCrossfade?.id) {
        activeArtworkCrossfade?.let { coverArtwork }
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
    // 播放状态短暂滑过 Idle / Preparing 时位置不应归零，否则进度条与歌词会闪回开头。
    // 初值直接取当前播放状态：避免首帧以 0 渲染造成一次闪跳。
    var lastKnownPositionMs by remember {
        mutableLongStateOf(
            when (playbackState) {
                is PlaybackState.Playing -> playbackState.positionMs
                is PlaybackState.Paused -> playbackState.positionMs
                else -> 0L
            },
        )
    }
    var lastKnownDurationMs by remember {
        mutableLongStateOf(
            when (playbackState) {
                is PlaybackState.Playing -> playbackState.durationMs
                is PlaybackState.Paused -> playbackState.durationMs
                else -> 0L
            },
        )
    }
    var lastKnownPositionTrackId by remember {
        mutableStateOf(playbackState.fullScreenItemOrNull()?.trackId)
    }
    LaunchedEffect(playbackState) {
        val trackId = playbackState.fullScreenItemOrNull()?.trackId
        if (trackId != null && trackId != lastKnownPositionTrackId) {
            lastKnownPositionTrackId = trackId
            lastKnownPositionMs = 0L
            lastKnownDurationMs = 0L
        }
        val position = when (playbackState) {
            is PlaybackState.Playing -> playbackState.positionMs
            is PlaybackState.Paused -> playbackState.positionMs
            else -> null
        }
        val duration = when (playbackState) {
            is PlaybackState.Playing -> playbackState.durationMs
            is PlaybackState.Paused -> playbackState.durationMs
            else -> null
        }
        if (position != null) {
            val sameTrack = trackId == lastKnownPositionTrackId
            val transientZero = sameTrack && position == 0L && lastKnownPositionMs > 1_500L
            if (!transientZero) lastKnownPositionMs = position
        }
        if (duration != null && duration > 0L) lastKnownDurationMs = duration
    }
    val currentPositionMs = lastKnownPositionMs
    val durationMs = lastKnownDurationMs
    val displayedDurationMs = durationMs.takeIf { it > 0L } ?: metadataDurationMs
    val isPreparing = playbackState is PlaybackState.Preparing
    val isBuffering = isPreparing || (playbackState as? PlaybackState.Playing)?.isBuffering == true
    val isPlaying = playbackState is PlaybackState.Playing && !playbackState.isBuffering
    val showsPause = playbackState.showsPauseGlyph()
    val canNavigatePrevious = queue.canNavigatePrevious()
    val canNavigateNext = queue.canNavigateNext()
    var isTrackContextMenuExpanded by remember(item.trackId) { mutableStateOf(false) }
    var isMoreActionsSheetOpen by remember { mutableStateOf(false) }
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
        targetValue = if (playbackState is PlaybackState.Paused && !isPanelOpen) 0.85f else 1f,
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
    var lyricsControlsHeightPx by remember { mutableIntStateOf(0) }
    val lyricsOverlayAlpha by animateFloatAsState(
        targetValue = if (lyricsOverlayVisible) 1f else 0f,
        animationSpec = tween(LyricsControlsFadeMs),
        label = "lyrics-overlay-alpha",
    )
    // 完全淡出的控件仍可命中：必须停止组合交互内容，否则底部起手的歌词滑动会被空壳控件吞掉。
    val lyricsControlsInteractive = !lyricsView || lyricsOverlayAlpha > 0.01f
    val showLyricsControls: () -> Unit = {
        playerInteractionTick += 1
        lyricsOverlayVisible = true
    }
    val hideLyricsControls: () -> Unit = {
        lyricsOverlayVisible = false
    }
    // 点歌词文字只跳转：重新计时，但不改变控件当前显隐状态。
    val noteLyricsInteraction: () -> Unit = {
        playerInteractionTick += 1
    }
    LaunchedEffect(
        visible,
        lyricsView,
        isPlaying,
        lyricsAvailable,
        lyricsOverlayVisible,
        playerInteractionTick,
        lyricAutoFullscreen,
        lyricAutoFullscreenDelayMs,
    ) {
        // 关闭“自动进入全屏歌词”后不再自动显隐；手势仍可手动进出。
        if (!lyricAutoFullscreen) return@LaunchedEffect
        if (!visible || !lyricsView || !lyricsAvailable) {
            if (!lyricsOverlayVisible) lyricsOverlayVisible = true
            return@LaunchedEffect
        }
        // 暂停时不自动显隐：全屏进出只由手势决定。播放时停留设定时长自动进入全屏。
        if (!isPlaying) return@LaunchedEffect
        if (!lyricsOverlayVisible) return@LaunchedEffect
        delay(lyricAutoFullscreenDelayMs.coerceAtLeast(500L))
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
        bitmap = backgroundArtwork?.sourceBitmap,
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
        targetValue = backgroundArtwork?.dominant ?: effectivePlayerColors.playerBackdrop,
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
            isBuffering = isBuffering,
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
            abLoopState = abLoopState,
            onFineTuneAbLoop = onFineTuneAbLoop,
            onClearAbLoop = onClearAbLoop,
            knobDetentAndInertia = funFeatures.gyroSheenAndKnobDetent,
            onScrubVelocityChange = if (funFeatures.cassetteMode) onCassetteScrubChange else null,
            modifier = callModifier,
        )
    }

    @Composable
    fun CompactMetadata(callModifier: Modifier = Modifier) {
        // 两行文本作为一个块，与紧凑封面垂直居中对齐。
        Box(callModifier, contentAlignment = Alignment.CenterStart) {
            FullScreenPlayerMetadataTransition(
                item = item,
                crossfade = artworkCrossfade,
                displayedHorizontalSwipeOffset = displayedHorizontalSwipeOffset,
                hazeState = glassHazeState,
                compact = true,
                isFavorite = isFavorite,
                onFavoriteToggle = onFavoriteToggle,
                isNeteaseTrack = isNeteaseTrack ?: (com.exodidio.tune.netease.NeteaseRepository.songIdForTrack(item.trackId) != null),
                isNeteaseLiked = isNeteaseLiked,
                onNeteaseLikeToggle = onNeteaseLikeToggle,
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
                onMoreClick = { isMoreActionsSheetOpen = true },
            )
        }
    }
    val playerBackdrop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) rememberLayerBackdrop() else null
    val activePlayerBackdrop = if (
        LocalControlStyle.current == ControlStyle.LiquidGlass &&
        !LocalReduceMotion.current &&
        hazeState != null
    ) {
        playerBackdrop
    } else {
        null
    }
    CompositionLocalProvider(
        LocalTuneColors provides effectivePlayerColors,
        LocalPlayerSurfaceColor provides lyricsOverlayColor,
        LocalTuneBackdrop provides activePlayerBackdrop,
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { panelOriginInRoot = it.positionInRoot() },
        ) {
        // The player column has 20dp padding on each side. On short screens,
        // cap the square cover by height so the controls retain their space.
        val expandedArtworkSize = minOf(
            maxWidth - FullScreenArtworkHorizontalInset,
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
            targetValue = if (isPanelOpen || lyricsView) FullScreenPlayerCompactLeftInset else ((maxWidth - 40.dp) - expandedArtworkSize) / 2,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "full-screen-artwork-horizontal-offset",
        )
        // 大封面 9dp；紧凑头按 Apple 实测调成 3.5dp，开合时同步过渡。
        val artworkCornerRadius by animateDpAsState(
            targetValue = if (isPanelOpen || lyricsView) FullScreenPlayerCompactCornerRadius else FullScreenArtworkCornerRadius,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "full-screen-artwork-corner-radius",
        )
        // 头部高度：普通播放器为大封面，面板打开后收成 80dp 紧凑头。
        // 队列重排时临时撑满，让列表用满整屏；歌词视图沿用紧凑头，歌词在下方铺满。
        val topBlockHeight by animateDpAsState(
            targetValue = when {
                lyricsView -> FullScreenPlayerCompactArtworkSize
                isQueueReordering -> maxHeight
                else -> expandedArtworkSize + FullScreenArtworkMetadataGap + FullScreenArtworkMetadataHeight
            },
            animationSpec = tween(
                if (isQueueReordering) QueueReorderTransitionDurationMs else 320,
                easing = FastOutSlowInEasing,
            ),
            label = "full-screen-header-height",
        )
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val displayCornerPx = rememberDisplayCornerPx()
        val displayCornerDp = with(density) { displayCornerPx.toDp().value }
        val p = expansionProgress.value.coerceIn(0f, 1f)
        val pillRect = miniPlayerBounds.pillRectInRoot?.translate(-panelOriginInRoot.x, -panelOriginInRoot.y)
        val useMorph = pillRect != null && !reduceMotion
        val panelWidthPx = with(density) { maxWidth.toPx() }
        val panelHeightPx = with(density) { maxHeight.toPx() }
        val panelOffsetPx = panelHeightPx * (1f - p)
        val dismissTravelPx = if (useMorph) {
            pillRect.top.coerceAtLeast(panelHeightPx * 0.65f).coerceAtLeast(closeThresholdPx * 2.5f)
        } else {
            panelHeightPx.coerceAtLeast(closeThresholdPx * 2f)
        }
        val contentRevealAlpha = if (useMorph) ((p - 0.30f) / 0.70f).coerceIn(0f, 1f) else 1f
        val sliderOverhangPx = with(density) { SliderTrackTopOverhang.toPx() }
        // 遮挡层的实心部分从进度条开始，歌词模糊截止线同步上移这段触摸区。
        val lyricsBottomInsetPx =
            ((lyricsOverlayHeightPx - sliderOverhangPx).coerceAtLeast(0f)) * lyricsOverlayAlpha
        // 翻译按钮固定在播放条上方：用不随淡出变化的高度。
        val lyricsControlsInsetPx = (lyricsOverlayHeightPx - sliderOverhangPx).coerceAtLeast(0f)
        // 触摸吞噬层与歌词裁切共用同一高度：只吞看不见的歌词区域，裁切线上方的可见歌词保持可拖。
        val lyricsBottomInsetDp = with(density) { lyricsBottomInsetPx.toDp() }
        var doubleTapSeekFeedback by remember(item.trackId) { mutableStateOf<Pair<Boolean, Long>?>(null) }
        LaunchedEffect(doubleTapSeekFeedback) {
            if (doubleTapSeekFeedback != null) {
                delay(700L)
                doubleTapSeekFeedback = null
            }
        }
        val latestCurrentPositionMs by rememberUpdatedState(currentPositionMs)
        val latestDurationMs by rememberUpdatedState(durationMs)
        val latestOnSeek by rememberUpdatedState(onSeek)
        val latestOnDismiss by rememberUpdatedState(onDismiss)
        Box(
            modifier = Modifier
            .fillMaxSize()
            .then(
                if (useMorph) {
                    Modifier.graphicsLayer {
                        val liveP = expansionProgress.value.coerceIn(0f, 1f)
                        val flatten = if (liveP >= 0.999f && !isDismissDragging && visible) flattenAnim.value else 0f
                        val morphLeft = lerpFloat(pillRect.left, 0f, liveP)
                        val morphTop = lerpFloat(pillRect.top, 0f, liveP)
                        val morphRight = lerpFloat(pillRect.right, panelWidthPx, liveP)
                        val morphBottom = lerpFloat(pillRect.bottom, panelHeightPx, liveP)
                        val rectHalfHeightDp = (morphBottom - morphTop).coerceAtLeast(0f).toDp().value * 0.5f
                        val cornerDp = playerShellCornerRadiusDp(
                            progress = liveP,
                            peakDp = displayCornerDp,
                            rectHalfHeightDp = rectHalfHeightDp,
                        ) * (1f - flatten)
                        clip = liveP < 0.999f || flatten < 0.999f
                        shape = MorphSmoothCornerShape(
                            left = morphLeft,
                            top = morphTop,
                            right = morphRight,
                            bottom = morphBottom,
                            cornerRadiusPx = cornerDp.dp.toPx(),
                        )
                        alpha = (liveP / MiniPlayerMorphCrossfadeThreshold).coerceIn(0f, 1f)
                    }
                } else {
                    Modifier
                        .offset { IntOffset(0, panelOffsetPx.roundToInt()) }
                        .alpha((p * 8f).coerceIn(0f, 1f))
                },
            )
            .semantics { contentDescription = playerDescription }
            .pointerInput(closeThresholdPx, dismissTravelPx) {
                detectVerticalDragGestures(
                    onDragStart = {
                        isDismissDragging = true
                        dismissDragDistancePx = 0f
                        dismissDragStartProgress = expansionProgress.value.coerceIn(0f, 1f)
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount > 0f || dismissDragDistancePx > 0f) {
                            dismissDragDistancePx = (dismissDragDistancePx + dragAmount)
                                .coerceIn(0f, dismissTravelPx)
                            val targetProgress = dismissDragExpansionProgress(
                                dragDistancePx = dismissDragDistancePx,
                                dismissTravelPx = dismissTravelPx,
                                startProgress = dismissDragStartProgress,
                            )
                            coroutineScope.launch {
                                expansionProgress.snapTo(targetProgress)
                            }
                        }
                    },
                    onDragCancel = {
                        isDismissDragging = false
                        dismissDragDistancePx = 0f
                    },
                    onDragEnd = {
                        val shouldDismiss = dismissDragDistancePx >= closeThresholdPx
                        isDismissDragging = false
                        dismissDragDistancePx = 0f
                        if (shouldDismiss) {
                            latestOnDismiss()
                        }
                    },
                )
            },
    ) {
            FullScreenPlayerBackground(
                artwork = backgroundArtwork,
                outgoingArtwork = outgoingArtwork,
                incomingArtwork = incomingArtwork,
                crossfadeProgress = crossfadeProgress,
                isArtworkCrossfading = isArtworkCrossfading,
                adaptiveBackground = fullscreenArtworkEnabled,
                playerTheme = playerTheme,
                appleBackdrop = appleBackdrop,
                dynamicBackdrop = dynamicBackdrop,
                backdropActive = isBackdropActive,
                lyricsVisible = lyricsView,
                presetSeed = item.title,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (activePlayerBackdrop == null) Modifier else Modifier.layerBackdrop(activePlayerBackdrop))
                    .then(if (glassHazeState == null) Modifier else Modifier.hazeSource(glassHazeState)),
            )
            Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            ) {
            Spacer(Modifier.height(8.dp))
            FullScreenPlayerDragHandle()
            Spacer(Modifier.height(FullScreenArtworkTopGap))
            FullScreenPlayerSwipeTarget(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .zIndex(1f),
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
                    val miniArtRect = miniPlayerBounds.artworkRectInRoot
                    val targetArtRect = if (isPanelOpen || lyricsView) {
                        compactArtworkTargetRectInRoot ?: artworkTargetRectInRoot
                    } else {
                        artworkTargetRectInRoot
                    }
                    val shouldFlyArtwork = useMorph &&
                        p < 0.999f &&
                        miniArtRect != null &&
                        targetArtRect != null &&
                        targetArtRect.width > 0f &&
                        targetArtRect.height > 0f
                    val flightScale = if (shouldFlyArtwork) {
                        val minScale = (miniArtRect!!.width / targetArtRect!!.width).coerceIn(0.05f, 1f)
                        lerpFloat(minScale, 1f, p)
                    } else {
                        1f
                    }
                    val flightTx = if (shouldFlyArtwork) {
                        lerpFloat(miniArtRect!!.center.x - targetArtRect!!.center.x, 0f, p)
                    } else {
                        0f
                    }
                    val flightTy = if (shouldFlyArtwork) {
                        lerpFloat(miniArtRect!!.center.y - targetArtRect!!.center.y, 0f, p)
                    } else {
                        0f
                    }
                    val effectiveArtworkScale = lerpFloat(1f, artworkScale, p)
                    val effectiveTransformOrigin = if (shouldFlyArtwork) {
                        lerpFloat(0.5f, artworkTransformOrigin, p)
                    } else {
                        artworkTransformOrigin
                    }
                    val displayedArtworkCornerRadius = if (useMorph && p < 0.999f) {
                        lerp(8.dp, artworkCornerRadius, p)
                    } else {
                        artworkCornerRadius
                    }
                    Box(
                        modifier = Modifier
                            .size(artworkSize)
                            .offset(x = artworkHorizontalOffset)
                            .onGloballyPositioned { coords ->
                                if (useMorph) {
                                    val rect = coords.boundsInRoot()
                                    if (rect.width > 0f && rect.height > 0f) {
                                        if (isPanelOpen || lyricsView) {
                                            if (kotlin.math.abs(artworkSize.value - FullScreenPlayerCompactArtworkSize.value) < 0.5f) {
                                                compactArtworkTargetRectInRoot = rect
                                            }
                                        } else {
                                            if (kotlin.math.abs(artworkSize.value - expandedArtworkSize.value) < 0.5f) {
                                                artworkTargetRectInRoot = rect
                                            }
                                        }
                                    }
                                }
                            },
                    ) {
                        FullScreenPlayerArtwork(
                            artwork = coverArtwork,
                            outgoingArtwork = outgoingArtwork,
                            incomingArtwork = incomingArtwork,
                            crossfadeProgress = crossfadeProgress,
                            isArtworkCrossfading = isArtworkCrossfading,
                            fullscreen = fullscreenArtworkEnabled,
                            swap = artworkSwap,
                            cornerRadius = displayedArtworkCornerRadius,
                            vinylRingWearEnabled = funFeatures.vinylRingWear,
                            vinylWearProgress = com.exodidio.tune.player.computeVinylWearProgress(currentTrackPlayCount),
                            gyroSheenEnabled = funFeatures.gyroSheenAndKnobDetent,
                            isCompactPanelOpen = isPanelOpen || lyricsView,
                            modifier = Modifier
                                .fillMaxSize()
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
                                        Modifier.pointerInput(item.trackId, isPreparing) {
                                            if (isPreparing) return@pointerInput
                                            detectTapGestures(
                                                onDoubleTap = { tapOffset ->
                                                    val dur = latestDurationMs
                                                    if (dur <= 0L) return@detectTapGestures
                                                    val isForward = tapOffset.x >= size.width / 2f
                                                    val targetMs = (latestCurrentPositionMs + if (isForward) 10_000L else -10_000L)
                                                        .coerceIn(0L, dur)
                                                    lyricsSeekPositionMs = targetMs
                                                    lyricsSeekRequestId += 1
                                                    latestOnSeek(targetMs)
                                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                                    doubleTapSeekFeedback = isForward to System.nanoTime()
                                                },
                                            )
                                        }
                                    },
                                )
                                .graphicsLayer {
                                    translationX = flightTx
                                    translationY = flightTy
                                    scaleX = effectiveArtworkScale * flightScale
                                    scaleY = effectiveArtworkScale * flightScale
                                    transformOrigin = TransformOrigin(effectiveTransformOrigin, effectiveTransformOrigin)
                                },
                        )
                        if (!isPanelOpen) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = doubleTapSeekFeedback != null,
                                enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.85f, animationSpec = tween(160)),
                                exit = fadeOut(tween(220)),
                                modifier = Modifier.align(
                                    if (doubleTapSeekFeedback?.first == true) Alignment.CenterEnd else Alignment.CenterStart,
                                ).padding(horizontal = 20.dp),
                            ) {
                                val isForward = doubleTapSeekFeedback?.first == true
                                val pillShape = RoundedCornerShape(16.dp)
                                Row(
                                    modifier = Modifier
                                        .clip(pillShape)
                                        .background(Color.Black.copy(alpha = 0.62f))
                                        .border(0.5.dp, Color.White.copy(alpha = 0.24f), pillShape)
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (!isForward) {
                                        MaterialSymbol(
                                            symbol = MaterialSymbols.ChevronLeft,
                                            contentDescription = null,
                                            tint = Color.White,
                                            size = 16.dp,
                                        )
                                    }
                                    Text(
                                        text = stringResource(
                                            if (isForward) R.string.player_seek_forward_10s else R.string.player_seek_backward_10s,
                                        ),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                    if (isForward) {
                                        MaterialSymbol(
                                            symbol = MaterialSymbols.ChevronRight,
                                            contentDescription = null,
                                            tint = Color.White,
                                            size = 16.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isPanelOpen,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 父级已有 20dp 内边距，这里再补 15dp，让标题和进度条都从 35dp 开始。
                            .padding(start = 15.dp)
                            .offset(y = expandedArtworkSize + FullScreenArtworkMetadataGap)
                            .alpha(contentRevealAlpha),
                        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { 10 },
                        exit = fadeOut(tween(280)) + slideOutVertically(tween(280)) { -10 },
                    ) {
                        FullScreenPlayerMetadataTransition(
                            item = item,
                            crossfade = artworkCrossfade,
                            displayedHorizontalSwipeOffset = displayedHorizontalSwipeOffset,
                            hazeState = glassHazeState,
                            compact = false,
                            isFavorite = isFavorite,
                            onFavoriteToggle = onFavoriteToggle,
                            isNeteaseTrack = isNeteaseTrack ?: (com.exodidio.tune.netease.NeteaseRepository.songIdForTrack(item.trackId) != null),
                            isNeteaseLiked = isNeteaseLiked,
                            onNeteaseLikeToggle = onNeteaseLikeToggle,
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
                            onMoreClick = { isMoreActionsSheetOpen = true },
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isPanelOpen,
                        modifier = Modifier
                            // Extend only the compact metadata into the outer
                            // column's right inset, leaving it flush to screen edge.
                            .offset(x = FullScreenPlayerCompactRightOverflow / 2)
                            .requiredWidth(compactMetadataWidth)
                            .height(FullScreenPlayerCompactArtworkSize),
                        enter = fadeIn(tween(200, delayMillis = 120)) + slideInVertically(tween(200, delayMillis = 120)) { 12 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -10 },
                    ) {
                        // 用 Row + Spacer 显式留出「缩进 + 封面 + 间距」，不依赖 Modifier.padding，
                        // 保证文字起点稳定在封面右侧 9dp。
                        Row(Modifier.fillMaxSize()) {
                            Spacer(
                                Modifier.width(
                                    FullScreenPlayerCompactLeftInset +
                                        FullScreenPlayerCompactArtworkSize +
                                        FullScreenPlayerCompactGap,
                                ),
                            )
                            CompactMetadata(Modifier.weight(1f).fillMaxHeight())
                        }
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
                                    onDeduplicateQueue = onDeduplicateQueue,
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
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
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
                        isPlaying = isPlaying,
                        translationVisible = translationVisible,
                        onTranslationToggle = onTranslationToggle,
                        springStrength = lyricSpringStrength,
                        browseIdleReturnMs = lyricFocusReturnDelayMs,
                        currentPositionMs = currentPositionMs,
                        pendingSeekPositionMs = lyricsSeekPositionMs,
                        seekRequestId = lyricsSeekRequestId,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxSize(),
                        fullscreen = true,
                        bottomInsetPx = lyricsBottomInsetPx,
                        controlsInsetPx = lyricsControlsInsetPx,
                        translationButtonVisible = lyricsOverlayVisible,
                        onShowControls = showLyricsControls,
                        onHideControls = hideLyricsControls,
                        onInteraction = noteLyricsInteraction,
                        onSeekInteraction = noteLyricsInteraction,
                        fontLevel = lyricsFontLevel,
                        lyricsGravityEnabled = funFeatures.lyricsGravity,
                    )
                }
            }
            }
            // 底部控件始终作为遮挡层存在：歌词视图里是实心同色面板，其它状态透明。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = (if (lyricsView) lyricsOverlayAlpha else 1f) * contentRevealAlpha }
                    .onSizeChanged { lyricsOverlayHeightPx = it.height },
            ) {
                // 触摸吞噬层：高度与歌词硬裁切一致，只拦被控件遮住的歌词，可见歌词仍可拖动/点按。
                // 完全淡出后不留任何触摸节点，否则会挡住下面歌词的点击与滚动。
                if (lyricsView && lyricsBottomInsetPx > 0.01f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(lyricsBottomInsetDp)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false).consume()
                                    noteLyricsInteraction()
                                }
                            },
                    )
                }
                // 实心背景从进度条的可见轨道开始：上方触摸区保持透明，歌词露到进度条为止。
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(top = SliderTrackTopOverhang)
                        .background(
                            when {
                                !lyricsView -> Color.Transparent
                                // 苹果式 / 动态背景直接透出来；歌词由边缘截止渐隐挡住，不需要实色面板。
                                playerTheme == PlayerTheme.Apple ||
                                    playerTheme == PlayerTheme.Dynamic -> Color.Transparent
                                else -> lyricsOverlayColor
                            },
                        ),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 35.dp, end = 35.dp),
                ) {
                    if (lyricsControlsInteractive) {
                        Box(Modifier.onSizeChanged { lyricsControlsHeightPx = it.height }) {
                            PlayerControls(Modifier.fillMaxWidth())
                        }
                    } else {
                        // 用同高占位保持遮挡层尺寸，不让歌词裁切线在显隐时跳动。
                        Spacer(
                            Modifier.height(
                                with(LocalDensity.current) {
                                    lyricsControlsHeightPx.toDp()
                                },
                            ),
                        )
                    }
                }
            }
        }
        if (isMoreActionsSheetOpen) {
            PlayerMoreActionsBottomSheet(
                item = item,
                contextTrack = contextTrack,
                lyrics = lyrics,
                playbackSpeed = playbackSpeed,
                abLoopState = abLoopState,
                sleepTimerState = sleepTimerState,
                moodRadioEligibleTrackIds = moodRadioEligibleTrackIds,
                onSpeedSelected = onPlaybackSpeedSelected,
                onSetAbLoopPointA = onSetAbLoopPointA,
                onSetAbLoopPointB = onSetAbLoopPointB,
                onFineTuneAbLoop = onFineTuneAbLoop,
                onClearAbLoop = onClearAbLoop,
                onSleepTimerMinutes = onSleepTimerMinutes,
                onSleepTimerAfterTrack = onSleepTimerAfterTrack,
                onCancelSleepTimer = onCancelSleepTimer,
                onExportLrc = onExportLrc,
                onStartMoodRadio = onStartMoodRadio,
                onTrackGoToAlbum = onTrackGoToAlbum,
                onTrackGoToArtist = onTrackGoToArtist,
                onTrackContextBottomSheet = onTrackContextBottomSheet,
                onCloseFullscreenThen = onCloseFullscreenThen,
                onDismiss = { isMoreActionsSheetOpen = false },
            )
        }
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

internal fun dismissDragExpansionProgress(
    dragDistancePx: Float,
    dismissTravelPx: Float,
    startProgress: Float = 1f,
): Float {
    if (dismissTravelPx <= 0f) return startProgress.coerceIn(0f, 1f)
    // 跟手收起允许直接拖到 0f，使全屏卡片在到达 MiniPlayer 位置前不会卡住并完成交叉淡出。
    val clampedStart = startProgress.coerceIn(0f, 1f)
    return (clampedStart - (dragDistancePx.coerceAtLeast(0f) / dismissTravelPx)).coerceIn(0f, 1f)
}

private fun PlaybackState.fullScreenItemOrNull(): PlaybackItem? = when (this) {
    PlaybackState.Idle, is PlaybackState.Failed -> null
    is PlaybackState.Preparing -> item
    is PlaybackState.Playing -> item
    is PlaybackState.Paused -> item
}
