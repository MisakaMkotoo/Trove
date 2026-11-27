package com.exodidio.tune.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodidio.tune.R
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.navigation.FloatingNavigationHeight
import com.exodidio.tune.ui.navigation.OuterPillRadius
import com.exodidio.tune.ui.navigation.navigationChromeBottomPadding
import com.exodidio.tune.ui.theme.LocalTuneColors
import dev.chrisbanes.haze.HazeState

internal data class BatchChromeRequest(
    val id: Any,
    val topContent: (@Composable () -> Unit)? = null,
    val bottomContent: (@Composable () -> Unit)? = null,
)

internal class BatchChromeHostState {
    var request by mutableStateOf<BatchChromeRequest?>(null)
        private set

    val isActive: Boolean
        get() = request != null

    fun show(
        id: Any,
        topContent: (@Composable () -> Unit)? = null,
        bottomContent: (@Composable () -> Unit)? = null,
    ) {
        if (request?.id !== id) {
            request = BatchChromeRequest(
                id = id,
                topContent = topContent,
                bottomContent = bottomContent,
            )
        }
    }

    fun dismiss(id: Any) {
        if (request?.id === id) {
            request = null
        }
    }

    fun dismissAll() {
        request = null
    }
}

internal val LocalBatchChromeHost = staticCompositionLocalOf<BatchChromeHostState?> { null }
internal val LocalBatchHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
internal fun BatchChromeOverlay(
    host: BatchChromeHostState,
    hazeState: HazeState?,
    reduceMotion: Boolean = false,
) {
    val request = host.request
    val visibleState = remember { MutableTransitionState(false) }
    var retainedRequest by remember { mutableStateOf<BatchChromeRequest?>(null) }
    if (request != null) {
        retainedRequest = request
    }
    visibleState.targetState = request != null
    LaunchedEffect(visibleState.currentState, visibleState.targetState, visibleState.isIdle) {
        if (visibleState.isIdle && !visibleState.targetState) {
            retainedRequest = null
        }
    }
    val activeRequest = retainedRequest ?: return

    CompositionLocalProvider(LocalBatchHazeState provides hazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            activeRequest.topContent?.let { topBar ->
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = if (reduceMotion) {
                        EnterTransition.None
                    } else {
                        fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                            slideInVertically(tween(280, easing = FastOutSlowInEasing)) { -it / 2 }
                    },
                    exit = if (reduceMotion) {
                        ExitTransition.None
                    } else {
                        fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                            slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 2 }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(start = 16.dp, top = 74.dp, end = 16.dp),
                ) {
                    topBar()
                }
            }
            activeRequest.bottomContent?.let { bottomBar ->
                val chromeBottomPadding = navigationChromeBottomPadding(
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = if (reduceMotion) {
                        EnterTransition.None
                    } else {
                        fadeIn(tween(260, delayMillis = 50, easing = FastOutSlowInEasing)) +
                            slideInVertically(tween(300, delayMillis = 30, easing = FastOutSlowInEasing)) { it / 2 }
                    },
                    exit = if (reduceMotion) {
                        ExitTransition.None
                    } else {
                        fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                            slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp, bottom = chromeBottomPadding),
                ) {
                    bottomBar()
                }
            }
        }
    }
}

@Composable
internal fun MultiSelectSelectionHeader(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onExit: () -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val effectiveHazeState = hazeState ?: LocalBatchHazeState.current
    val isLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass
    val liquidGlassBlurDp = LocalLiquidGlassBlurDp.current.dp
    val animatedGlassTint = rememberAnimatedGlassTint(colors, animate = !LocalReduceMotion.current)
    val useOpticalGlass = isLiquidGlass && !LocalReduceMotion.current && tuneGlassBackdrop() != null
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
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
            modifier = Modifier
                .matchParentSize()
                .tuneLiquidGlassBackground(
                    hazeState = effectiveHazeState,
                    colors = colors,
                    shape = shape,
                    hazeBlurRadius = 30.dp,
                    glassBlurRadius = liquidGlassBlurDp,
                    glassTint = if (isLiquidGlass) null else animatedGlassTint,
                    enableLens = true,
                    lensRadius = 16.dp,
                    refractionHeight = 10.dp,
                    chromaticAberration = isLiquidGlass && !LocalReduceMotion.current,
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.batch_select_count, selectedCount),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(if (allSelected) R.string.batch_select_none else R.string.batch_select_all),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleSelectAll)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.batch_select_exit),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onExit)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun MultiSelectCheckBadge(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val colorSpec = if (reduceMotion) snap<androidx.compose.ui.graphics.Color>() else tween(180, easing = FastOutSlowInEasing)
    val fillColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.glassElevated,
        animationSpec = colorSpec,
        label = "multi-select-badge-fill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.borderGlass,
        animationSpec = colorSpec,
        label = "multi-select-badge-border",
    )
    val badgeScale = remember { Animatable(1f) }
    var previousSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected, reduceMotion) {
        val newlySelected = selected && !previousSelected
        previousSelected = selected
        if (newlySelected && !reduceMotion) {
            badgeScale.snapTo(1f)
            badgeScale.animateTo(1.16f, tween(110, easing = FastOutSlowInEasing))
            badgeScale.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
        } else {
            badgeScale.snapTo(1f)
        }
    }
    Box(
        modifier = modifier
            .size(22.dp)
            .graphicsLayer {
                scaleX = badgeScale.value
                scaleY = badgeScale.value
            }
            .clip(CircleShape)
            .background(fillColor)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = borderColor,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                fadeIn(tween(140, easing = FastOutSlowInEasing)) +
                    scaleIn(initialScale = 0.55f, animationSpec = tween(170, easing = FastOutSlowInEasing))
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                fadeOut(tween(110, easing = FastOutSlowInEasing)) +
                    scaleOut(targetScale = 0.55f, animationSpec = tween(130, easing = FastOutSlowInEasing))
            },
        ) {
            MaterialSymbol(
                symbol = MaterialSymbols.Check,
                contentDescription = null,
                size = 14.dp,
                tint = colors.onPrimary,
            )
        }
    }
}

@Composable
internal fun MultiSelectActionBar(
    selectedCount: Int,
    onPlayNext: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onNeteaseLike: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val effectiveHazeState = hazeState ?: LocalBatchHazeState.current
    val isLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass
    val liquidGlassBlurDp = LocalLiquidGlassBlurDp.current.dp
    val animatedGlassTint = rememberAnimatedGlassTint(colors, animate = !LocalReduceMotion.current)
    val enabled = selectedCount > 0
    val useOpticalGlass = isLiquidGlass && !LocalReduceMotion.current && tuneGlassBackdrop() != null
    val shape = RoundedCornerShape(OuterPillRadius)
    Box(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .defaultMinSize(minHeight = FloatingNavigationHeight)
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
            modifier = Modifier
                .matchParentSize()
                .tuneLiquidGlassBackground(
                    hazeState = effectiveHazeState,
                    colors = colors,
                    shape = shape,
                    hazeBlurRadius = 30.dp,
                    glassBlurRadius = liquidGlassBlurDp,
                    glassTint = if (isLiquidGlass) null else animatedGlassTint,
                    enableLens = true,
                    lensRadius = 20.dp,
                    refractionHeight = 12.dp,
                    chromaticAberration = isLiquidGlass && !LocalReduceMotion.current,
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FloatingNavigationHeight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatchActionItem(
                symbol = MaterialSymbols.QueuePlayNext,
                label = stringResource(R.string.batch_action_play_next),
                enabled = enabled,
                onClick = onPlayNext,
                modifier = Modifier.weight(1f),
            )
            if (onAddToPlaylist != null) {
                BatchActionItem(
                    symbol = MaterialSymbols.PlaylistAdd,
                    label = stringResource(R.string.batch_action_add_playlist),
                    enabled = enabled,
                    onClick = onAddToPlaylist,
                    modifier = Modifier.weight(1f),
                )
            }
            if (onNeteaseLike != null) {
                BatchActionItem(
                    symbol = MaterialSymbols.Favorite,
                    label = stringResource(R.string.batch_action_like),
                    enabled = enabled,
                    onClick = onNeteaseLike,
                    modifier = Modifier.weight(1f),
                )
            }
            if (onDownload != null) {
                BatchActionItem(
                    symbol = MaterialSymbols.ArrowDownward,
                    label = stringResource(R.string.batch_action_download),
                    enabled = enabled,
                    onClick = onDownload,
                    modifier = Modifier.weight(1f),
                )
            }
            if (onRemove != null) {
                BatchActionItem(
                    symbol = MaterialSymbols.Delete,
                    label = stringResource(R.string.batch_action_remove),
                    enabled = enabled,
                    destructive = true,
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BatchActionItem(
    symbol: String,
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val targetTint = when {
        !enabled -> colors.textMuted.copy(alpha = 0.45f)
        destructive -> colors.primary
        else -> colors.textMain
    }
    val tint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = if (reduceMotion) snap() else tween(160, easing = FastOutSlowInEasing),
        label = "batch-action-tint",
    )
    Column(
        modifier = modifier
            .then(if (enabled) Modifier.pressScale(interactionSource, pressedScale = 0.94f) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MaterialSymbol(symbol = symbol, contentDescription = label, size = 20.dp, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
