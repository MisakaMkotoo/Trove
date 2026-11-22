package com.exodidio.tune.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.exodidio.tune.R
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.RepeatMode
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.TuneTrackSlider
import com.exodidio.tune.ui.components.AnimatedPlayPauseSymbol
import com.exodidio.tune.ui.components.AnimatedSkipSymbol
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.MaterialSymbolsFilledFontFamily
import com.exodidio.tune.ui.components.MaterialSymbolsOutlinedFontFamily
import com.exodidio.tune.ui.components.TrackAudioQuality
import com.exodidio.tune.ui.components.sliderFilledTrackColor
import com.exodidio.tune.ui.components.trackAudioQuality
import com.exodidio.tune.ui.components.trackInfoValues
import com.exodidio.tune.ui.theme.TuneColors
import com.exodidio.tune.ui.theme.LocalTuneColors

internal const val FullScreenPlayerControlsTestTag = "full_screen_player_controls"
/** 播放器实际背景的实心等价色；选中图标的阴文镂空用它才能和背景融为一体。 */
internal val LocalPlayerSurfaceColor = androidx.compose.runtime.staticCompositionLocalOf { Color.Unspecified }
internal const val QueueReorderTransitionDurationMs = 360
internal const val QueueReorderControlsFadeDurationMs = 300
internal const val QueueButtonSelectionTransitionDurationMs = 220
internal const val QueueStatusBadgeRevealDelayMs = QueueButtonSelectionTransitionDurationMs + 16
internal const val FullScreenQueueStatusBadgeTestTag = "full_screen_queue_status_badge"
internal const val FullScreenPlayerQualityBadgeTestTag = "full_screen_player_quality_badge"
internal const val FullScreenPlayerElapsedTimeTestTag = "full_screen_player_elapsed_time"
internal const val FullScreenPlayerDurationTestTag = "full_screen_player_duration"
private const val SeekConfirmationToleranceMs = 250L

internal enum class FullScreenPlayerPanel { Lyrics, Queue }

/** Controls are hidden only for an active Queue reorder, never for a normal Queue view. */
internal fun areFullScreenPlayerControlsVisible(isQueueReordering: Boolean): Boolean = !isQueueReordering

internal fun hasConfirmedSeekPosition(
    seekFraction: Float,
    playbackPositionMs: Long,
    durationMs: Long,
): Boolean {
    if (durationMs <= 0L) return false
    val targetPositionMs = (durationMs * seekFraction.coerceIn(0f, 1f)).toLong()
    return kotlin.math.abs(playbackPositionMs - targetPositionMs) <= SeekConfirmationToleranceMs
}

/** Controls own their transient interaction state; the player shell only coordinates panels and lyrics seeking. */
@Composable
internal fun FullScreenPlayerControls(
    trackId: String,
    currentPositionMs: Long,
    durationMs: Long,
    displayedDurationMs: Long?,
    isPreparing: Boolean,
    isPlaying: Boolean,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    volume: Float,
    queue: PlaybackQueueSnapshot,
    contextTrack: LibraryTrack?,
    showQualityBadge: Boolean,
    selectedPanel: FullScreenPlayerPanel?,
    isQueueReordering: Boolean,
    onPanelSelected: (FullScreenPlayerPanel?) -> Unit,
    onSeekRequested: (Long) -> Unit,
    onSeekConfirmed: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenMediaOutputSwitcher: () -> Unit,
    showVolumeSlider: Boolean = true,
    showLyricsButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val seekLabel = stringResource(R.string.player_seek)
    val volumeLabel = stringResource(R.string.player_volume)
    var restingHeightPx by remember { mutableIntStateOf(0) }
    var pendingSeekFraction by remember(trackId) { mutableStateOf<Float?>(null) }
    var awaitingSeekConfirmation by remember(trackId) { mutableStateOf(false) }
    var seekInteracting by remember { mutableStateOf(false) }
    var volumeInteracting by remember { mutableStateOf(false) }
    var qualityDialogVisible by remember(trackId) { mutableStateOf(false) }
    var queueBadgeVisible by remember { mutableStateOf(true) }

    LaunchedEffect(currentPositionMs, durationMs, awaitingSeekConfirmation) {
        val fraction = pendingSeekFraction
        if (awaitingSeekConfirmation && fraction != null && hasConfirmedSeekPosition(fraction, currentPositionMs, durationMs)) {
            pendingSeekFraction = null
            awaitingSeekConfirmation = false
            onSeekConfirmed()
        }
    }
    LaunchedEffect(selectedPanel) {
        if (selectedPanel == FullScreenPlayerPanel.Queue) queueBadgeVisible = false
        else {
            delay(QueueStatusBadgeRevealDelayMs.toLong())
            queueBadgeVisible = true
        }
    }

    val seekOffset by animateDpAsState(if (seekInteracting) 4.dp else 0.dp, tween(220, easing = FastOutSlowInEasing), label = "full-screen-seek-supporting-offset")
    val seekLabelColor by animateColorAsState(if (seekInteracting) colors.onPrimary else colors.foregroundSubtle, tween(220, easing = FastOutSlowInEasing), label = "full-screen-seek-time-label-colour")
    val seekScaleX by animateFloatAsState(if (seekInteracting) 1.03f else 1f, tween(220, easing = FastOutSlowInEasing), label = "full-screen-seek-supporting-scale-x")
    val seekScaleY by animateFloatAsState(if (seekInteracting) 1.10f else 1f, tween(220, easing = FastOutSlowInEasing), label = "full-screen-seek-supporting-scale-y")
    val volumeOffset by animateDpAsState(if (volumeInteracting) 4.dp else 0.dp, tween(220, easing = FastOutSlowInEasing), label = "full-screen-volume-icon-offset")
    val volumeColor by animateColorAsState(sliderFilledTrackColor(colors, volumeInteracting), tween(220, easing = FastOutSlowInEasing), label = "full-screen-volume-icon-colour")
    val lyricsSelected = selectedPanel == FullScreenPlayerPanel.Lyrics
    val queueSelected = selectedPanel == FullScreenPlayerPanel.Queue
    val qualityBadge = contextTrack?.let(::trackAudioQuality)?.let {
        when (it) {
            TrackAudioQuality.Lossless -> R.string.track_info_quality_lossless to MaterialSymbols.GraphicEq
            TrackAudioQuality.HiRes -> R.string.track_info_quality_hi_res to MaterialSymbols.Bolt
            TrackAudioQuality.Dsd -> R.string.track_info_quality_dsd to MaterialSymbols.Crown
            else -> null
        }
    }
    val qualitySlot = qualityBadge ?: (R.string.track_info_quality_lossless to MaterialSymbols.GraphicEq)
    val qualityVisible = showQualityBadge && qualityBadge != null
    val qualityDetails = contextTrack?.let(::trackInfoValues).orEmpty().filter {
        it.labelRes == R.string.track_info_sample_rate || it.labelRes == R.string.track_info_bit_depth || it.labelRes == R.string.track_info_codec
    }
    val controlsModifier = if (isQueueReordering && restingHeightPx > 0) {
        Modifier.fillMaxWidth().requiredHeight(with(LocalDensity.current) { restingHeightPx.toDp() })
    } else modifier.fillMaxWidth()

    AnimatedVisibility(
        visible = areFullScreenPlayerControlsVisible(isQueueReordering),
        modifier = controlsModifier.onSizeChanged { if (!isQueueReordering) restingHeightPx = it.height }
            .semantics { testTag = FullScreenPlayerControlsTestTag },
        enter = fadeIn(tween(QueueReorderControlsFadeDurationMs, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(QueueReorderTransitionDurationMs, easing = FastOutSlowInEasing)) { it },
        exit = fadeOut(tween(QueueReorderControlsFadeDurationMs, easing = LinearOutSlowInEasing)) +
            slideOutVertically(tween(QueueReorderTransitionDurationMs, easing = FastOutSlowInEasing)) { it },
    ) {
        // 仿 Apple Music：整组控件贴底，不再在剩余空间里均分。
        Column(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            // 苹果同机实测：底部两图标 64dp、三键 157dp、进度条 260dp（距屏幕底），
            // 对应的两段间距都是 31dp。
            verticalArrangement = Arrangement.spacedBy(31.dp, Alignment.Bottom),
        ) {
            Column {
                TuneTrackSlider(
                    value = pendingSeekFraction ?: if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f,
                    onValueChange = { pendingSeekFraction = it },
                    onValueChangeFinished = {
                        pendingSeekFraction?.let { onSeekRequested((durationMs * it).toLong()) }
                        awaitingSeekConfirmation = pendingSeekFraction != null
                    },
                    enabled = durationMs > 0 && !isPreparing,
                    onInteractionChange = { seekInteracting = it },
                    trackHeight = 7.dp,
                    modifier = Modifier.semantics { contentDescription = seekLabel },
                )
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        text = formatPlaybackTime(pendingSeekFraction?.let { (durationMs * it).toLong() } ?: currentPositionMs),
                        color = seekLabelColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterStart).offset(x = -seekOffset, y = (-12).dp + seekOffset)
                            .semantics { testTag = FullScreenPlayerElapsedTimeTestTag },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.align(Alignment.Center).offset(y = (-12).dp + seekOffset).alpha(if (qualityVisible) 1f else 0f)
                            .graphicsLayer { scaleX = seekScaleX; scaleY = seekScaleY }
                            .then(if (qualityVisible) Modifier.semantics { testTag = FullScreenPlayerQualityBadgeTestTag } else Modifier.clearAndSetSemantics {})
                            .clip(RoundedCornerShape(6.dp)).background(fullScreenSecondaryControlBackground(colors))
                            .then(if (qualityVisible) Modifier.clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) { qualityDialogVisible = true } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        MaterialSymbol(qualitySlot.second, null, size = 12.dp, tint = colors.foregroundSubtle)
                        Text(stringResource(qualitySlot.first), color = colors.foregroundSubtle, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                    }
                    Text(
                        text = displayedDurationMs?.let(::formatPlaybackTime) ?: "--:--",
                        color = seekLabelColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterEnd).offset(x = seekOffset, y = (-12).dp + seekOffset)
                            .semantics { testTag = FullScreenPlayerDurationTestTag },
                    )
                }
                if (qualityDialogVisible && qualityBadge != null) FullScreenQualityDialog(qualityBadge.first, qualityBadge.second, qualityDetails) { qualityDialogVisible = false }
            }
            // 三键中心间距 107dp（苹果实测 80.8 / 187.8 / 294.9）。
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(43.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                // 苹果同机实测图形：上一/下一 44.3×25.2dp、播放 39.4×39.7dp。
                FullScreenTransportButton(MaterialSymbols.SkipPrevious, stringResource(R.string.player_previous), onPrevious, enabled = canNavigatePrevious, iconSize = 51.dp, skipForward = false)
                FullScreenTransportButton(label = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play), onClick = onPlayPause, enabled = !isPreparing || isPlaying, iconSize = 56.dp, isPlaying = isPlaying)
                FullScreenTransportButton(MaterialSymbols.SkipNext, stringResource(R.string.player_next), onNext, enabled = canNavigateNext, iconSize = 51.dp, skipForward = true)
            }
            Column {
                if (showVolumeSlider) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MaterialSymbol(MaterialSymbols.VolumeDown, null, tint = volumeColor, size = 20.dp, filled = true, modifier = Modifier.offset(x = -volumeOffset))
                        Spacer(Modifier.width(10.dp))
                        TuneTrackSlider(volume, onVolumeChange, onInteractionChange = { volumeInteracting = it }, trackHeight = 7.dp,
                            modifier = Modifier.weight(1f).semantics { contentDescription = volumeLabel })
                        Spacer(Modifier.width(10.dp))
                        MaterialSymbol(MaterialSymbols.VolumeUp, null, tint = volumeColor, size = 20.dp, filled = true, modifier = Modifier.offset(x = volumeOffset))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                // 苹果底部两图标中心距屏幕边约 84.6dp：控件列已有 32dp 内边距，这里再补 22.5dp。
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Lyrics is a compulsory player feature: always available. Fullscreen lyrics
                    // only controls presentation (see FullScreenPlayerLyricsPanel fullscreen flag).
                    FullScreenToggleIcon(
                        selected = lyricsSelected,
                        symbol = MaterialSymbols.Lyrics,
                        label = stringResource(R.string.player_lyrics),
                        shape = RoundedCornerShape(8.dp),
                        containerSize = 32.dp,
                        iconSize = 25.dp,
                        onClick = { onPanelSelected(if (lyricsSelected) null else FullScreenPlayerPanel.Lyrics) },
                    )
                    Box(Modifier.size(60.dp)) {
                        FullScreenToggleIcon(
                            selected = queueSelected,
                            symbol = MaterialSymbols.QueueMusic,
                            label = stringResource(R.string.player_queue),
                            shape = RoundedCornerShape(8.dp),
                            containerSize = 32.dp,
                            iconSize = 25.dp,
                            onClick = { onPanelSelected(if (queueSelected) null else FullScreenPlayerPanel.Queue) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                        if (!queueSelected && queueBadgeVisible) queueStatusBadgeSymbol(queue)?.let { QueueStatusBadge(it) }
                    }
                }
            }
        }
    }
}

internal fun queueStatusBadgeSymbol(queue: PlaybackQueueSnapshot): String? = when {
    queue.shuffle -> MaterialSymbols.Shuffle
    queue.repeatMode == RepeatMode.One -> MaterialSymbols.RepeatOne
    queue.repeatMode == RepeatMode.All -> MaterialSymbols.Repeat
    else -> null
}

internal fun fullScreenSecondaryControlBackground(colors: TuneColors): Color = colors.sliderInactive.copy(alpha = 0.06f)

@Composable
internal fun BoxScope.QueueStatusBadge(symbol: String) {
    val colors = LocalTuneColors.current
    Box(
        Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)
            .semantics { testTag = FullScreenQueueStatusBadgeTestTag }
            .background(fullScreenSecondaryControlBackground(colors), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(symbol, null, tint = colors.onPrimary, size = 13.dp)
    }
}

/**
 * 苹果式面板开关：未选中是线描图标；选中时套一个小容器（白色底、图标用背景色挖空）。
 */
@Composable
internal fun FullScreenToggleIcon(
    selected: Boolean,
    symbol: String,
    label: String,
    shape: androidx.compose.ui.graphics.Shape,
    containerSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "full-screen-toggle-selection",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(120),
        label = "full-screen-toggle-press",
    )
    // 真镂空：白色容器画在离屏图层里，再用 DstOut 把图标形状从图层擦掉，
    // 让图标位置透出真实背景像素，而不是涂一个固定的“假背景色”。
    val measurer = rememberTextMeasurer()
    val glyph = remember(symbol, iconSize, selected, measurer) {
        measurer.measure(
            text = symbol,
            style = TextStyle(
                fontFamily = if (selected) MaterialSymbolsFilledFontFamily else MaterialSymbolsOutlinedFontFamily,
                fontSize = iconSize.value.sp,
            ),
        )
    }
    Box(
        modifier = modifier
            .size(60.dp)
            .semantics { contentDescription = label }
            .clickable(
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(containerSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .clip(shape)
                .background(colors.onPrimary.copy(alpha = selectionProgress))
                .drawWithContent {
                    drawContent()
                    if (selectionProgress > 0.01f) {
                        drawText(
                            textLayoutResult = glyph,
                            color = Color.Black,
                            topLeft = Offset(
                                (size.width - glyph.size.width) / 2f,
                                (size.height - glyph.size.height) / 2f,
                            ),
                            alpha = selectionProgress,
                            blendMode = BlendMode.DstOut,
                        )
                    }
                },
        )
        // 过渡期间保留线描图标，随选中进度淡出；选中后不再有实色图标，只剩真实镂空。
        MaterialSymbol(
            symbol,
            null,
            tint = colors.foregroundSubtle,
            size = iconSize,
            filled = selected,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .alpha(1f - selectionProgress),
        )
    }
}

@Composable
internal fun FullScreenTransportButton(
    symbol: String? = null,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    iconSize: androidx.compose.ui.unit.Dp = 32.dp,
    tint: Color? = null,
    containerColor: Color? = null,
    filled: Boolean = true,
    isPlaying: Boolean? = null,
    skipForward: Boolean? = null,
) {
    val colors = LocalTuneColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        Modifier.size(64.dp).then(if (containerColor == null) Modifier else Modifier.padding(8.dp).background(containerColor, CircleShape))
            .semantics { contentDescription = label }
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val iconTint = tint ?: if (enabled || isPlaying != null) colors.onPrimary else colors.foregroundSubtle
        when {
            isPlaying != null -> AnimatedPlayPauseSymbol(isPlaying, !enabled && !isPlaying, isPressed, iconTint, iconSize, 64.dp)
            skipForward != null -> AnimatedSkipSymbol(skipForward, isPressed, iconTint, iconSize, 64.dp)
            else -> MaterialSymbol(requireNotNull(symbol), null, tint = iconTint, size = iconSize, filled = filled)
        }
    }
}

private fun formatPlaybackTime(timeMs: Long): String {
    val seconds = (timeMs.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
