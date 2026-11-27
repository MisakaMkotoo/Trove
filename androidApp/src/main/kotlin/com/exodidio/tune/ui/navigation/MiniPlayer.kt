package com.exodidio.tune.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.TuneMarqueeText
import com.exodidio.tune.ui.components.AnimatedPlayPauseSymbol
import com.exodidio.tune.ui.components.AnimatedSkipSymbol
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.player.PlaybackItem
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackState
import com.exodidio.tune.player.showsPauseGlyph
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.components.LocalControlStyle
import com.exodidio.tune.ui.components.LocalLiquidGlassBlurDp
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.tuneGlassBackdrop
import com.exodidio.tune.ui.components.tuneLiquidGlassBackground
import com.exodidio.tune.ui.components.rememberAnimatedGlassTint
import com.exodidio.tune.ui.components.rememberArtworkFadeAlpha
import com.exodidio.tune.ui.components.rememberArtworkThumbnail
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlin.math.absoluteValue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val MiniPlayerHeight = 48.dp
internal val MiniPlayerNavigationGap = 8.dp
internal val MiniPlayerPillRadius = 24.dp
internal const val MiniPlayerMorphCrossfadeThreshold = 0.04f
private val MetadataSwipeMaximum = 40.dp
private val MetadataSwipeThreshold = 32.dp
private const val MetadataSwipeVelocityPxPerMs = 1.2f

internal fun miniPlayerMorphAlpha(fullScreenExpansionProgress: Float): Float =
    1f - (fullScreenExpansionProgress / MiniPlayerMorphCrossfadeThreshold).coerceIn(0f, 1f)

internal data class MiniPlayerTransitionBounds(
    val pillRectInRoot: Rect? = null,
    val artworkRectInRoot: Rect? = null,
)

@Composable
internal fun MiniPlayer(
    playbackState: PlaybackState,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    hazeState: HazeState?,
    compact: Boolean = false,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFullScreenPlayer: () -> Unit,
    onFullScreenPlayerDrag: (Float) -> Unit,
    onFullScreenPlayerDragEnd: (Boolean) -> Unit,
    stableGlassWidth: Dp? = null,
    fullScreenExpansionProgress: Float = 0f,
    onBoundsChanged: (MiniPlayerTransitionBounds) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val item = playbackState.itemOrNull() ?: return
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val isLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass
    val liquidGlassBlurDp = LocalLiquidGlassBlurDp.current.dp
    val animatedGlassTint = rememberAnimatedGlassTint(colors, animate = !reduceMotion)
    val shape = RoundedCornerShape(MiniPlayerPillRadius)
    val isPreparing = playbackState is PlaybackState.Preparing
    val isBuffering = isPreparing || (playbackState as? PlaybackState.Playing)?.isBuffering == true
    // 在线歌曲点播放后正在重连/缓冲：按钮先显示“暂停”语义，点一下可退回暂停态。
    val showsPause = playbackState.showsPauseGlyph()
    val canNavigatePrevious = playbackQueue.canNavigatePrevious()
    val canNavigateNext = playbackQueue.canNavigateNext()
    val latestCanNavigatePrevious by rememberUpdatedState(canNavigatePrevious)
    val latestCanNavigateNext by rememberUpdatedState(canNavigateNext)
    val latestOnPreviousClick by rememberUpdatedState(onPreviousClick)
    val latestOnNextClick by rememberUpdatedState(onNextClick)
    val localArtwork = rememberArtworkThumbnail(item.artworkPath)
    val coverInfo by androidx.compose.runtime.produceState<com.exodidio.tune.netease.NeteaseRepository.PlaybackCoverInfo?>(
        initialValue = null,
        item.trackId,
        localArtwork != null,
    ) {
        value = if (localArtwork != null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { com.exodidio.tune.netease.NeteaseRepository.playbackCoverInfo(item.trackId) }
                    .getOrNull()
            }
        }
    }
    val coilArtwork = rememberCoilThumbnail(coverInfo?.url, 200)
    val artwork = localArtwork ?: coilArtwork
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val dragOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val dismissThresholdPx = with(density) { 36.dp.toPx() }
    val fullScreenTransitionDistancePx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val fullScreenCatchUpTravelPx = with(density) { 120.dp.toPx() }
    val maximumOpenPullPx = with(density) { MiniPlayerHeight.toPx() }
    val dismissTargetPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val metadataSwipeMaximumPx = with(density) { MetadataSwipeMaximum.toPx() }
    val metadataSwipeThresholdPx = with(density) { MetadataSwipeThreshold.toPx() }
    var metadataDragOffset by remember { mutableStateOf(0f) }
    var isMetadataDragging by remember { mutableStateOf(false) }
    var metadataDragStartedAtMs by remember { mutableStateOf(0L) }
    val displayedMetadataDragOffset by animateFloatAsState(
        targetValue = if (isMetadataDragging) metadataDragOffset else 0f,
        animationSpec = spring(),
        label = "mini-player-metadata-swipe",
    )
    val displayedDragOffset = dragOffset.value
    val upwardPullProgress = (-displayedDragOffset / maximumOpenPullPx).coerceIn(0f, 1f)
    val morphHiddenAlpha = miniPlayerMorphAlpha(fullScreenExpansionProgress)
    val miniPlayerAlpha = (1f - upwardPullProgress) * morphHiddenAlpha
    var fullScreenPullPx by remember { mutableStateOf(0f) }
    var miniPlayerTopPx by remember { mutableStateOf(0f) }
    val latestMiniPlayerTopPx by rememberUpdatedState(miniPlayerTopPx)
    var restingPillRect by remember { mutableStateOf<Rect?>(null) }
    var restingArtworkRect by remember { mutableStateOf<Rect?>(null) }
    val latestOnBoundsChanged by rememberUpdatedState(onBoundsChanged)
    androidx.compose.runtime.LaunchedEffect(restingPillRect, restingArtworkRect) {
        latestOnBoundsChanged(MiniPlayerTransitionBounds(restingPillRect, restingArtworkRect))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .onGloballyPositioned { miniPlayerTopPx = it.positionInRoot().y }
            .pointerInput(
                dismissThresholdPx,
                fullScreenTransitionDistancePx,
                fullScreenCatchUpTravelPx,
                maximumOpenPullPx,
                dismissTargetPx,
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A cancelled gesture may skip its terminal branch. A new pointer
                    // must always start from the mini-player resting state.
                    fullScreenPullPx = 0f
                    var currentDragOffset = 0f
                    coroutineScope.launch { dragOffset.snapTo(0f) }
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var isVerticalDrag = false
                    var isHorizontalDrag = false
                    var isOpeningFullscreen = false
                    var fullScreenCatchUpPx = 0f
                    var wasCancelled = false
                    var change = down

                    while (change.pressed) {
                        val event = awaitPointerEvent()
                        change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) {
                            wasCancelled = true
                            break
                        }

                        val delta = change.positionChange()
                        totalDragX += delta.x
                        totalDragY += delta.y
                        if (!isVerticalDrag && !isHorizontalDrag) {
                            when {
                                abs(totalDragY) > viewConfiguration.touchSlop &&
                                    abs(totalDragY) > abs(totalDragX) -> {
                                    isVerticalDrag = true
                                    isOpeningFullscreen = totalDragY < 0f
                                    if (isOpeningFullscreen) {
                                        val fingerStartY = latestMiniPlayerTopPx + down.position.y
                                        fullScreenCatchUpPx = (fullScreenTransitionDistancePx - fingerStartY)
                                            .coerceAtLeast(0f)
                                    }
                                    currentDragOffset = 0f
                                    coroutineScope.launch { dragOffset.snapTo(0f) }
                                }
                                abs(totalDragX) > viewConfiguration.touchSlop -> {
                                    isHorizontalDrag = true
                                }
                            }
                        }

                        if (isVerticalDrag) {
                            change.consume()
                            if (isOpeningFullscreen) {
                                fullScreenPullPx = (fullScreenPullPx - delta.y)
                                    .coerceIn(0f, fullScreenTransitionDistancePx)
                                currentDragOffset = -fullScreenPullPx.coerceAtMost(maximumOpenPullPx)
                                val targetDragOffset = currentDragOffset
                                coroutineScope.launch { dragOffset.snapTo(targetDragOffset) }
                                val catchUpProgress = (
                                    fullScreenPullPx / fullScreenCatchUpTravelPx
                                ).coerceIn(0f, 1f)
                                val easedCatchUpProgress = 1f -
                                    (1f - catchUpProgress) * (1f - catchUpProgress)
                                val panelDistancePx = fullScreenPullPx +
                                    fullScreenCatchUpPx * easedCatchUpProgress
                                onFullScreenPlayerDrag(
                                    (panelDistancePx / fullScreenTransitionDistancePx)
                                        .coerceIn(0f, 1f),
                                )
                            } else {
                                // A downward dismissal pull must remain reversible.  Keeping
                                // the gesture's initial direction locked avoids turning a
                                // reversal into a fullscreen open, while allowing the user to
                                // pull the pill back to its resting position before release.
                                currentDragOffset = (currentDragOffset + delta.y)
                                    .coerceIn(0f, dismissTargetPx)
                                val targetDragOffset = currentDragOffset
                                coroutineScope.launch { dragOffset.snapTo(targetDragOffset) }
                            }
                        }
                    }

                    when {
                        isVerticalDrag && wasCancelled -> {
                            onFullScreenPlayerDragEnd(false)
                            fullScreenPullPx = 0f
                            coroutineScope.launch {
                                dragOffset.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                            }
                        }
                        isVerticalDrag -> {
                            when {
                                // An upward gesture can be reversed before release. Do not
                                // complete the fullscreen transition when only a sub-slop
                                // upward remainder is left after the reversal.
                                fullScreenPullPx > viewConfiguration.touchSlop -> {
                                    onFullScreenPlayerDragEnd(true)
                                    fullScreenPullPx = 0f
                                    coroutineScope.launch { dragOffset.snapTo(0f) }
                                }
                                currentDragOffset >= dismissThresholdPx -> {
                                    coroutineScope.launch {
                                        dragOffset.animateTo(
                                            dismissTargetPx,
                                            tween(180, easing = FastOutSlowInEasing),
                                        )
                                        onDismiss()
                                    }
                                }
                                else -> {
                                    onFullScreenPlayerDragEnd(false)
                                    fullScreenPullPx = 0f
                                    coroutineScope.launch {
                                        dragOffset.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                                    }
                                }
                            }
                        }
                        !wasCancelled && !isHorizontalDrag && !change.pressed &&
                            abs(totalDragX) <= viewConfiguration.touchSlop &&
                            abs(totalDragY) <= viewConfiguration.touchSlop -> onOpenFullScreenPlayer()
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                onClick { onOpenFullScreenPlayer(); true }
            },
    ) {
        val useOpticalGlass = stableGlassWidth == null &&
            isLiquidGlass &&
            !reduceMotion &&
            tuneGlassBackdrop() != null
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, displayedDragOffset.roundToInt()) }
                .onGloballyPositioned { coords ->
                    // 即使全屏页已展开，底层 MiniPlayer 完成进场/合并过渡后也要继续同步静止坐标，
                    // 避免未合并模式下按住全屏页下滑收起时目标矩形停留在半路。
                    if (abs(displayedDragOffset) < 0.5f) {
                        val rect = coords.boundsInRoot()
                        if (rect.width > 0f && rect.height > 0f) {
                            restingPillRect = rect
                        }
                    }
                }
                .alpha(miniPlayerAlpha)
                .then(
                    if (!useOpticalGlass) {
                        Modifier
                            .clip(shape)
                            .border(0.5.dp, colors.borderGlass, shape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(
            modifier = if (stableGlassWidth == null) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .align(Alignment.CenterStart)
                    .requiredWidth(stableGlassWidth)
                    .fillMaxHeight()
            }
                .tuneLiquidGlassBackground(
                    hazeState = hazeState,
                    colors = colors,
                    shape = shape,
                    hazeBlurRadius = 30.dp,
                    glassBlurRadius = liquidGlassBlurDp,
                    glassTint = if (isLiquidGlass) null else animatedGlassTint,
                    enableLens = true,
                    lensRadius = 16.dp,
                    refractionHeight = 9.dp,
                    chromaticAberration = isLiquidGlass && !reduceMotion,
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, top = 2.dp, end = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .onGloballyPositioned { coords ->
                    if (abs(displayedDragOffset) < 0.5f) {
                        val rect = coords.boundsInRoot()
                        if (rect.width > 0f && rect.height > 0f) {
                            restingArtworkRect = rect
                        }
                    }
                }
                .clip(RoundedCornerShape(8.dp))
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = item.trackId,
                transitionSpec = {
                    if (reduceMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                            scaleIn(initialScale = 0.92f, animationSpec = tween(220, easing = FastOutSlowInEasing))) togetherWith
                            fadeOut(tween(150, easing = FastOutSlowInEasing))
                    }
                },
                label = "mini-player-artwork-transition",
            ) { targetTrackId ->
                var slotArtwork by remember(targetTrackId) { mutableStateOf(artwork) }
                if (targetTrackId == item.trackId) {
                    slotArtwork = artwork
                }
                val artworkAlpha = rememberArtworkFadeAlpha(targetTrackId, slotArtwork != null)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val activeBitmap = slotArtwork
                    if (activeBitmap != null) {
                        Image(
                            bitmap = activeBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().alpha(artworkAlpha),
                        )
                    } else {
                        MaterialSymbol(
                            symbol = MaterialSymbols.MusicNote,
                            contentDescription = null,
                            tint = colors.textMuted,
                            size = 22.dp,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .pointerInput(metadataSwipeMaximumPx, metadataSwipeThresholdPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            // Never carry an in-flight spring animation into the next
                            // gesture. A cancelled/rapidly repeated swipe must start at
                            // the resting position, otherwise its direction and distance
                            // can be calculated from stale offset.
                            metadataDragOffset = 0f
                            metadataDragStartedAtMs = android.os.SystemClock.uptimeMillis()
                            isMetadataDragging = true
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            metadataDragOffset = (metadataDragOffset + dragAmount)
                                .coerceIn(-metadataSwipeMaximumPx, metadataSwipeMaximumPx)
                        },
                        onDragCancel = {
                            isMetadataDragging = false
                            metadataDragOffset = 0f
                        },
                        onDragEnd = {
                            val durationMs = (android.os.SystemClock.uptimeMillis() - metadataDragStartedAtMs)
                                .coerceAtLeast(1L)
                            val completedDragOffset = metadataDragOffset
                            val velocityPxPerMs = completedDragOffset / durationMs
                            val shouldChangeTrack = completedDragOffset.absoluteValue >= metadataSwipeThresholdPx ||
                                velocityPxPerMs.absoluteValue >= MetadataSwipeVelocityPxPerMs
                            val swipeDirection = completedDragOffset.compareTo(0f)
                            isMetadataDragging = false
                            metadataDragOffset = 0f

                            if (
                                shouldChangeTrack &&
                                canDispatchQueueSwipe(
                                    swipeDirection,
                                    latestCanNavigatePrevious,
                                    latestCanNavigateNext,
                                )
                            ) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                if (swipeDirection < 0) latestOnNextClick() else latestOnPreviousClick()
                            }
                        },
                    )
                },
            ) {
                AnimatedContent(
                    targetState = item,
                    contentKey = { "${it.trackId}:${it.title}:${it.artist}" },
                    modifier = Modifier.offset { IntOffset(displayedMetadataDragOffset.roundToInt(), 0) },
                    transitionSpec = {
                        if (reduceMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 4 }) togetherWith
                                (fadeOut(tween(150, easing = FastOutSlowInEasing)) +
                                    slideOutVertically(tween(150, easing = FastOutSlowInEasing)) { -it / 4 })
                        }
                    },
                    label = "mini-player-metadata-transition",
                ) { activeItem ->
                    Column {
                        TuneMarqueeText(
                            activeItem.title,
                            colors.textMain,
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        TuneMarqueeText(activeItem.artist, colors.textMuted, MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !compact,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            MiniPlayerControl(
                symbol = MaterialSymbols.SkipPrevious,
                label = stringResource(R.string.player_previous),
                onClick = onPreviousClick,
                horizontalOffset = 16.dp,
                skipForward = false,
                enabled = canNavigatePrevious,
            )
        }
        MiniPlayerControl(
            label = stringResource(if (showsPause) R.string.player_pause else R.string.player_play),
            onClick = onPlayPauseClick,
            enabled = !isPreparing || showsPause,
            horizontalOffset = 8.dp,
            isPlaying = showsPause,
            isBuffering = isBuffering,
        )
        MiniPlayerControl(
            symbol = MaterialSymbols.SkipNext,
            label = stringResource(R.string.player_next),
            onClick = onNextClick,
            skipForward = true,
            enabled = canNavigateNext,
        )
        }
        }
    }
}

internal const val MiniPlayerBufferingSpinnerTestTag = "mini_player_buffering_spinner"

@Composable
private fun MiniPlayerControl(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    horizontalOffset: Dp = 0.dp,
    isPlaying: Boolean? = null,
    isBuffering: Boolean = false,
    skipForward: Boolean? = null,
    symbol: String? = null,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = tween(durationMillis = if (isPressed) 120 else 200, easing = FastOutSlowInEasing),
        label = "mini-player-control-press-scale",
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .offset(x = horizontalOffset)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                interactionSource = interactionSource,
                indication = null,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Preparing disables interaction but must not dim the retained Pause
        // glyph during a track hand-off, which reads as a visual flash.
        val tint = if (enabled || isPlaying != null) colors.textMain else colors.textMuted
        if (isPlaying != null) {
            if (isBuffering) {
                if (reduceMotion) {
                    CircularProgressIndicator(
                        progress = { 0.72f },
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            }
                            .semantics { testTag = MiniPlayerBufferingSpinnerTestTag },
                        color = tint,
                        trackColor = tint.copy(alpha = 0.22f),
                        strokeWidth = 2.2.dp,
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            }
                            .semantics { testTag = MiniPlayerBufferingSpinnerTestTag },
                        color = tint,
                        trackColor = tint.copy(alpha = 0.22f),
                        strokeWidth = 2.2.dp,
                        strokeCap = StrokeCap.Round,
                    )
                }
            } else {
                AnimatedPlayPauseSymbol(
                    isPlaying = isPlaying,
                    // 播放中但被临时禁用（重连中）时要直接显示“暂停”，不要停留在上一次的形态。
                    isPreparing = !enabled && !isPlaying,
                    isPressed = isPressed,
                    tint = tint,
                    size = 22.dp,
                    touchTargetSize = 40.dp,
                )
            }
        } else if (skipForward != null) {
            AnimatedSkipSymbol(
                forward = skipForward,
                isPressed = isPressed,
                tint = tint,
                size = 22.dp,
                touchTargetSize = 48.dp,
            )
        } else {
            MaterialSymbol(
                symbol = requireNotNull(symbol),
                contentDescription = label,
                tint = tint,
                size = 22.dp,
                filled = true,
            )
        }
    }
}

private fun PlaybackState.itemOrNull(): PlaybackItem? = when (this) {
    PlaybackState.Idle, is PlaybackState.Failed -> null
    is PlaybackState.Preparing -> item
    is PlaybackState.Playing -> item
    is PlaybackState.Paused -> item
}
