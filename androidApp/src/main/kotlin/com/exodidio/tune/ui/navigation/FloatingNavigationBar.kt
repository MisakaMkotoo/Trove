package com.exodidio.tune.ui.navigation

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.exodidio.tune.AppDestination
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.components.LocalControlStyle
import com.exodidio.tune.ui.components.LocalLiquidGlassBlurDp
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.TuneNavigationTabMotionDefaults
import com.exodidio.tune.ui.components.computePlatterDragTranslationX
import com.exodidio.tune.ui.components.computePlatterPuffSpec
import com.exodidio.tune.ui.components.dampedLiquidDisplacement
import com.exodidio.tune.ui.components.finiteOrZero
import com.exodidio.tune.ui.components.rememberAnimatedGlassTint
import com.exodidio.tune.ui.components.rememberTuneInteractiveHighlight
import com.exodidio.tune.ui.components.rememberTuneNavigationTabMotion
import com.exodidio.tune.ui.components.safeGlassProgress
import com.exodidio.tune.ui.components.supportsKyantLens
import com.exodidio.tune.ui.components.tuneGlassAvailable
import com.exodidio.tune.ui.components.tuneLiquidGlassBackground
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

internal val FloatingNavigationHeight = 62.dp
internal val CompactNavigationHeight = 46.dp
internal val FloatingNavigationBottomMargin = 6.dp
internal val FloatingNavigationMinBottomClearance = 16.dp
internal val FloatingNavigationContentGap = 16.dp

internal fun navigationChromeBottomPadding(navBarBottom: Dp): Dp =
    maxOf(navBarBottom + FloatingNavigationBottomMargin, FloatingNavigationMinBottomClearance)

internal val OuterPillRadius = 31.dp
private val InnerPillRadius = 27.dp
private val PillGap = 4.dp

/** 底栏上的动作入口（不属于 tab）：搜索等直达功能。 */
internal data class NavigationAction(
    val symbol: String,
    val label: String,
    val onClick: () -> Unit,
)

internal data class DockDragFeedback(
    val offsetPx: Offset,
    val normalizedOverDragX: Float,
)

internal fun computeDockDragFeedback(
    rawDeltaFromDown: Offset,
    rawPillTargetPx: Float,
    maxIndicatorOffsetPx: Float,
    maxFollowXPx: Float,
    maxOverDragXPx: Float,
    maxFollowYPx: Float,
): DockDragFeedback {
    val followX = dampedLiquidDisplacement(rawDeltaFromDown.x, maxFollowXPx)
    val overDragRaw = when {
        rawPillTargetPx < 0f -> rawPillTargetPx
        rawPillTargetPx > maxIndicatorOffsetPx -> rawPillTargetPx - maxIndicatorOffsetPx
        else -> 0f
    }
    val overDragX = dampedLiquidDisplacement(overDragRaw, maxOverDragXPx)
    val followY = dampedLiquidDisplacement(rawDeltaFromDown.y, maxFollowYPx)
    val normOver = if (maxOverDragXPx > 0f) (overDragX / maxOverDragXPx).coerceIn(-1f, 1f) else 0f
    return DockDragFeedback(
        offsetPx = Offset(x = followX + overDragX, y = followY),
        normalizedOverDragX = normOver,
    )
}

@Composable
internal fun FloatingNavigationBar(
    selectedDestination: AppDestination,
    hazeState: HazeState?,
    onDestinationSelected: (AppDestination) -> Unit,
    visibleDestinations: List<AppDestination> = AppDestination.entries,
    actions: List<NavigationAction> = emptyList(),
    activeActionIndex: Int? = null,
    fullNavigationContentAlpha: Float = 1f,
    onCompactClick: () -> Unit = {},
    height: Dp = FloatingNavigationHeight,
    stableGlassWidth: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val isLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass && !reduceMotion
    val liquidGlassBlurDp = LocalLiquidGlassBlurDp.current.dp
    val canUseKyantLens = isLiquidGlass &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        LocalView.current.isHardwareAccelerated &&
        tuneGlassAvailable()
    val animatedGlassTint = rememberAnimatedGlassTint(colors, animate = !reduceMotion)
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val outerPillShape = RoundedCornerShape(OuterPillRadius)
    val panelBackdrop = if (canUseKyantLens) rememberLayerBackdrop() else null
    val tabsBackdrop = if (canUseKyantLens) rememberLayerBackdrop() else null
    val highlight = if (isLiquidGlass) {
        rememberTuneInteractiveHighlight(panelGlowAlpha = 0.06f, focusGlowAlpha = 0.16f)
    } else {
        null
    }
    val isExpanded = fullNavigationContentAlpha >= 0.99f && height >= FloatingNavigationHeight - 1.dp

    var barWidthPx by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    val platterPuffSpec = remember(barWidthPx, barHeightPx, density.density) {
        computePlatterPuffSpec(
            widthPx = if (barWidthPx > 0f) barWidthPx else with(density) { 360.dp.toPx() },
            heightPx = if (barHeightPx > 0f) barHeightPx else with(density) { FloatingNavigationHeight.toPx() },
            density = density.density,
        )
    }
    val platterPuffAnim = remember { Animatable(0f) }
    val platterDragOffsetXAnim = remember { Animatable(0f) }
    val platterDragSettleSpring = remember {
        spring<Float>(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.5f)
    }
    val maxPlatterOffsetPx = with(density) { 4.dp.toPx() }

    val dockLayerBlock: (GraphicsLayerScope.() -> Unit)? = if (isExpanded && !reduceMotion) {
        {
            val scale = (1f + platterPuffSpec.scaleDeltaPerUnitProgress * platterPuffAnim.value.finiteOrZero())
                .coerceIn(0.95f, 1.12f)
            scaleX = scale
            scaleY = scale
            translationX = platterDragOffsetXAnim.value.finiteOrZero()
        }
    } else {
        null
    }

    Box(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { size ->
                barWidthPx = size.width.toFloat()
                barHeightPx = size.height.toFloat()
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!isExpanded) {
                        Modifier
                            .clip(outerPillShape)
                            .border(0.5.dp, colors.borderGlass, outerPillShape)
                    } else {
                        Modifier
                    },
                )
                .then(highlight?.gestureModifier ?: Modifier),
        ) {
            Box(
                modifier = if (stableGlassWidth == null || isExpanded) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.CenterStart)
                        .requiredWidth(stableGlassWidth)
                        .requiredHeight(FloatingNavigationHeight)
                }
                    .tuneLiquidGlassBackground(
                        hazeState = hazeState,
                        colors = colors,
                        shape = outerPillShape,
                        hazeBlurRadius = 30.dp,
                        glassBlurRadius = liquidGlassBlurDp,
                        glassTint = if (isLiquidGlass) null else animatedGlassTint,
                        enableLens = true,
                        lensRadius = if (height <= 50.dp) 10.dp else 16.dp,
                        refractionHeight = 9.dp,
                        chromaticAberration = isLiquidGlass,
                        pressProgress = if (isExpanded) maxOf(highlight?.pressProgress ?: 0f, platterPuffAnim.value * 0.55f) else highlight?.pressProgress ?: 0f,
                        layerBlock = dockLayerBlock,
                        exportedBackdrop = panelBackdrop,
                    )
                    .then(
                        if (isExpanded && !canUseKyantLens) {
                            Modifier.border(0.5.dp, colors.borderGlass, outerPillShape)
                        } else {
                            Modifier
                        },
                    )
                    .then(highlight?.modifier ?: Modifier),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (dockLayerBlock != null) Modifier.graphicsLayer(dockLayerBlock) else Modifier)
                .padding(PillGap),
        ) {
            if (fullNavigationContentAlpha < 1f) {
                CompactNavigationTarget(
                    selectedDestination = selectedDestination,
                    onClick = onCompactClick,
                    modifier = Modifier.graphicsLayer(alpha = 1f - fullNavigationContentAlpha),
                )
            }
            if (fullNavigationContentAlpha > 0f) {
                val contentTranslationY = with(density) { (1f - fullNavigationContentAlpha) * 8.dp.toPx() }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            alpha = fullNavigationContentAlpha,
                            scaleX = 0.96f + (0.04f * fullNavigationContentAlpha),
                            scaleY = 0.96f + (0.04f * fullNavigationContentAlpha),
                            translationY = contentTranslationY,
                        ),
                ) {
                    val totalSlots = (visibleDestinations.size + actions.size).coerceAtLeast(1)
                    val trackWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val trackHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                    val trackHeightDp = with(density) { trackHeightPx.toDp() }
                    val itemWidth = maxWidth / totalSlots
                    val itemWidthPx = (trackWidthPx / totalSlots.toFloat()).coerceAtLeast(1f)
                    val minCenterPx = itemWidthPx / 2f
                    val maxCenterPx = (trackWidthPx - itemWidthPx / 2f).coerceAtLeast(minCenterPx)

                    val actionSlot = activeActionIndex?.takeIf { it in actions.indices }?.let { visibleDestinations.size + it }
                    val destinationSlot = visibleDestinations.indexOf(selectedDestination).takeIf { it >= 0 }
                    val hasSelectedSlot = actionSlot != null || destinationSlot != null
                    val selectedSlot = (actionSlot ?: destinationSlot ?: 0).coerceIn(0, totalSlots - 1)
                    val targetCenterPx = ((selectedSlot + 0.5f) * itemWidthPx).coerceIn(minCenterPx, maxCenterPx)

                    val currentSelectedDestination by rememberUpdatedState(selectedDestination)
                    val currentVisibleDestinations by rememberUpdatedState(visibleDestinations)
                    val currentActions by rememberUpdatedState(actions)
                    val currentOnDestinationSelected by rememberUpdatedState(onDestinationSelected)
                    val currentSelectedSlot by rememberUpdatedState(selectedSlot)
                    var isInteracting by remember { mutableStateOf(false) }
                    var lastCommittedSlot by remember { mutableStateOf<Int?>(null) }

                    val tabMotion = rememberTuneNavigationTabMotion(
                        isTopBar = false,
                        reduceMotion = reduceMotion,
                    )

                    LaunchedEffect(selectedSlot, itemWidthPx, maxCenterPx, trackHeightPx, isInteracting) {
                        tabMotion.updateTrackHeight(trackHeightPx)
                        if (!isInteracting) {
                            val previousSlot = lastCommittedSlot
                            if (previousSlot == null) {
                                tabMotion.snapTo(targetCenterPx, itemWidthPx, trackHeightPx)
                                lastCommittedSlot = selectedSlot
                            } else if (previousSlot != selectedSlot) {
                                lastCommittedSlot = selectedSlot
                                if (!reduceMotion && abs(tabMotion.targetCenterPx - targetCenterPx) > 1f) {
                                    tabMotion.press()
                                    tabMotion.moveTo(targetCenterPx, itemWidthPx, trackHeightPx)
                                    tabMotion.release()
                                } else {
                                    tabMotion.moveTo(targetCenterPx, itemWidthPx, trackHeightPx)
                                }
                            } else if (
                                abs(tabMotion.targetCenterPx - targetCenterPx) > 0.5f ||
                                abs(tabMotion.targetWidthPx - itemWidthPx) > 0.5f
                            ) {
                                tabMotion.snapTo(targetCenterPx, itemWidthPx, trackHeightPx)
                            }
                        }
                    }

                    val activeCenterPx = if (lastCommittedSlot == null) targetCenterPx else tabMotion.centerPx
                    val activeWidthPx = if (lastCommittedSlot == null) itemWidthPx else tabMotion.widthPx
                    val lensPressProgress = if (canUseKyantLens) safeGlassProgress(tabMotion.pressProgress) else 0f
                    val liftGrowthDp = TuneNavigationTabMotionDefaults.LiftGrowthDp.dp * lensPressProgress
                    val liftGrowthPx = with(density) { liftGrowthDp.toPx() }
                    val restingPillLeftPx = activeCenterPx - activeWidthPx / 2f + tabMotion.flexOffsetPx
                    val restingPillWidthDp = with(density) { activeWidthPx.toDp() }
                    val liftedWidthPx = activeWidthPx + liftGrowthPx
                    val liftedWidthDp = with(density) { liftedWidthPx.toDp() }
                    val liftedHeightDp = trackHeightDp + liftGrowthDp
                    val liftedShape = RoundedCornerShape(InnerPillRadius + liftGrowthDp * 0.5f)
                    val innerPillShape = RoundedCornerShape(InnerPillRadius)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(itemWidthPx, minCenterPx, maxCenterPx, trackHeightPx, totalSlots) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    var dragActive = false
                                    var longPressEngaged = false
                                    var activePointerId = down.id
                                    var platterDragJob: Job? = null
                                    if (!reduceMotion) {
                                        animationScope.launch {
                                            platterPuffAnim.animateTo(1f, platterPuffSpec.pressSpring)
                                        }
                                    }
                                    val holdJob = animationScope.launch {
                                        delay(85.milliseconds)
                                        longPressEngaged = true
                                        isInteracting = true
                                        tabMotion.press()
                                        val touchCenter = down.position.x.coerceIn(minCenterPx, maxCenterPx)
                                        tabMotion.moveTo(touchCenter, itemWidthPx, trackHeightPx)
                                    }
                                    try {
                                        val touchSlop = viewConfiguration.touchSlop
                                        while (true) {
                                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == activePointerId }
                                                ?: event.changes.firstOrNull { it.pressed }
                                            if (change == null || change.changedToUpIgnoreConsumed()) {
                                                if (dragActive || longPressEngaged) {
                                                    change?.consume()
                                                    val finalCenter = tabMotion.targetCenterPx
                                                    val slot = (finalCenter / itemWidthPx).toInt()
                                                        .coerceIn(0, totalSlots - 1)
                                                    val slotCenter = ((slot + 0.5f) * itemWidthPx)
                                                        .coerceIn(minCenterPx, maxCenterPx)
                                                    tabMotion.moveTo(slotCenter, itemWidthPx, trackHeightPx)
                                                    val destinations = currentVisibleDestinations
                                                    if (slot <= destinations.lastIndex) {
                                                        val destination = destinations[slot]
                                                        if (destination != currentSelectedDestination) {
                                                            currentOnDestinationSelected(destination)
                                                        }
                                                    } else {
                                                        val action = currentActions.getOrNull(slot - destinations.size)
                                                        action?.onClick?.invoke()
                                                        val restoreCenter = ((currentSelectedSlot + 0.5f) * itemWidthPx)
                                                            .coerceIn(minCenterPx, maxCenterPx)
                                                        tabMotion.moveTo(restoreCenter, itemWidthPx, trackHeightPx)
                                                    }
                                                }
                                                break
                                            }
                                            activePointerId = change.id
                                            val dx = change.position.x - down.position.x
                                            val dy = change.position.y - down.position.y
                                            if (!dragActive && (abs(dx) > touchSlop || longPressEngaged)) {
                                                if (!longPressEngaged && abs(dx) > touchSlop && abs(dx) >= abs(dy) * 0.85f) {
                                                    holdJob.cancel()
                                                    dragActive = true
                                                    isInteracting = true
                                                    tabMotion.press()
                                                } else if (longPressEngaged && (abs(dx) > 2f || abs(dy) > 2f)) {
                                                    dragActive = true
                                                }
                                            }
                                            if (dragActive) {
                                                change.consume()
                                                val targetCenter = change.position.x.coerceIn(minCenterPx, maxCenterPx)
                                                tabMotion.moveTo(targetCenter, itemWidthPx, trackHeightPx)
                                                if (!reduceMotion) {
                                                    val platterX = computePlatterDragTranslationX(
                                                        rawDeltaXPx = dx,
                                                        barWidthPx = barWidthPx.coerceAtLeast(trackWidthPx),
                                                        maxOffsetPx = maxPlatterOffsetPx,
                                                    )
                                                    platterDragJob?.cancel()
                                                    platterDragJob = animationScope.launch {
                                                        platterDragOffsetXAnim.snapTo(platterX)
                                                    }
                                                }
                                            }
                                        }
                                    } finally {
                                        holdJob.cancel()
                                        platterDragJob?.cancel()
                                        if (longPressEngaged || dragActive) {
                                            tabMotion.release()
                                        }
                                        if (!reduceMotion) {
                                            animationScope.launch {
                                                launch { platterPuffAnim.animateTo(0f, platterPuffSpec.releaseSpring) }
                                                launch { platterDragOffsetXAnim.animateTo(0f, platterDragSettleSpring) }
                                            }
                                        }
                                        isInteracting = false
                                    }
                                }
                            },
                    ) {
                        // Layer 1: Base resting indicator + tab icons/labels captured into tabsBackdrop
                        // so the floating liquid lens can magnify and refract them during press/drag.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (tabsBackdrop != null) Modifier.layerBackdrop(tabsBackdrop) else Modifier),
                        ) {
                            Box(
                                modifier = if (hasSelectedSlot) {
                                    Modifier
                                        .offset { IntOffset(restingPillLeftPx.roundToInt(), 0) }
                                        .width(restingPillWidthDp.coerceAtLeast(itemWidth))
                                        .fillMaxSize()
                                        .clip(innerPillShape)
                                        .background(
                                            colors.navigationActive.copy(
                                                alpha = colors.navigationActive.alpha * if (canUseKyantLens) {
                                                    (1f - 4f * lensPressProgress).coerceIn(0f, 1f)
                                                } else {
                                                    1f
                                                },
                                            ),
                                        )
                                } else {
                                    Modifier.size(0.dp)
                                },
                            )
                            FloatingNavigationVisuals(
                                foreground = colors.textMain,
                                filled = true,
                                visibleDestinations = visibleDestinations,
                                actions = actions,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (hasSelectedSlot) {
                                                Modifier.navigationForegroundMask(
                                                    centerPx = activeCenterPx + tabMotion.flexOffsetPx,
                                                    pillWidthPx = liftedWidthPx,
                                                    liftGrowthPx = liftGrowthPx,
                                                    pillScaleX = tabMotion.scaleX,
                                                    pillScaleY = tabMotion.scaleY,
                                                    clipOp = ClipOp.Difference,
                                                )
                                            } else {
                                                Modifier
                                            },
                                        ),
                            )
                            if (hasSelectedSlot) {
                                FloatingNavigationVisuals(
                                    foreground = colors.primary,
                                    filled = true,
                                    visibleDestinations = visibleDestinations,
                                    actions = actions,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .navigationForegroundMask(
                                            centerPx = activeCenterPx + tabMotion.flexOffsetPx,
                                            pillWidthPx = liftedWidthPx,
                                            liftGrowthPx = liftGrowthPx,
                                            pillScaleX = tabMotion.scaleX,
                                            pillScaleY = tabMotion.scaleY,
                                            clipOp = ClipOp.Intersect,
                                        ),
                                )
                            }
                        }

                        // Layer 2: Floating Kyant Liquid Glass Magnifier Lens pill (+16.dp lift expansion + loupeFlex)
                        if (canUseKyantLens && hasSelectedSlot && panelBackdrop != null && tabsBackdrop != null &&
                            liftedShape.supportsKyantLens()
                        ) {
                            val lensBackdrop = rememberCombinedBackdrop(panelBackdrop, tabsBackdrop)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset {
                                        IntOffset((activeCenterPx - liftedWidthPx / 2f).roundToInt(), 0)
                                    }
                                    .requiredWidth(liftedWidthDp.coerceAtLeast(itemWidth))
                                    .requiredHeight(liftedHeightDp.coerceAtLeast(trackHeightDp))
                                    .drawBackdrop(
                                        backdrop = lensBackdrop,
                                        shape = { liftedShape },
                                        effects = {
                                            val p = safeGlassProgress(tabMotion.pressProgress)
                                            if (p > 0.01f) {
                                                lens(
                                                    refractionHeight = (10.dp.toPx() * p).coerceAtLeast(1f),
                                                    refractionAmount = (20.dp.toPx() * p).coerceAtLeast(1f),
                                                    depthEffect = true,
                                                    chromaticAberration = true,
                                                )
                                            }
                                        },
                                        highlight = {
                                            val p = safeGlassProgress(tabMotion.pressProgress)
                                            Highlight.Default.copy(alpha = (0.76f * p).coerceAtMost(1f))
                                        },
                                        shadow = {
                                            val p = safeGlassProgress(tabMotion.pressProgress)
                                            Shadow(
                                                radius = 12.dp,
                                                color = Color.Black.copy(alpha = (0.14f * p).coerceIn(0f, 1f)),
                                                alpha = 1f,
                                            )
                                        },
                                        innerShadow = {
                                            val p = safeGlassProgress(tabMotion.pressProgress)
                                            InnerShadow(
                                                radius = 4.dp * p,
                                                color = Color.Black.copy(alpha = (0.10f * p).coerceIn(0f, 1f)),
                                                alpha = 1f,
                                            )
                                        },
                                        layerBlock = {
                                            val p = safeGlassProgress(tabMotion.pressProgress)
                                            alpha = (p * 4f).coerceIn(0f, 1f)
                                            scaleX = tabMotion.scaleX
                                            scaleY = tabMotion.scaleY
                                            translationX = tabMotion.flexOffsetPx
                                        },
                                        onDrawSurface = {
                                            val p = safeGlassProgress(tabMotion.pressProgress)
                                            drawRect(Color.White.copy(alpha = 0.10f * p), blendMode = BlendMode.Screen)
                                        },
                                    ),
                            )
                        }

                        // Layer 3: Accessibility & quick-tap targets
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            visibleDestinations.forEach { destination ->
                                FloatingNavigationTarget(
                                    destination = destination,
                                    selected = destination == selectedDestination,
                                    onClick = { onDestinationSelected(destination) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            actions.forEach { action ->
                                NavigationActionTarget(
                                    action = action,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactNavigationTarget(
    selectedDestination: AppDestination,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val destinationLabel = stringResource(selectedDestination.titleRes)
    IconButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(InnerPillRadius))
            .semantics { contentDescription = destinationLabel },
    ) {
        MaterialSymbol(
            symbol = selectedDestination.symbol,
            tint = colors.primary,
            filled = true,
        )
    }
}

internal fun navPillRectPx(offsetPx: Float, widthPx: Float, heightPx: Float, radiusPx: Float): RoundRect =
    RoundRect(
        left = offsetPx,
        top = 0f,
        right = offsetPx + widthPx,
        bottom = heightPx,
        radiusX = radiusPx,
        radiusY = radiusPx,
    )

// Path allocated once per size/offset change, one cached layer.
private fun Modifier.navigationForegroundMask(
    centerPx: Float,
    pillWidthPx: Float,
    liftGrowthPx: Float,
    pillScaleX: Float = 1f,
    pillScaleY: Float = 1f,
    clipOp: ClipOp,
): Modifier = drawWithCache {
    val baseRadius = InnerPillRadius.toPx() + liftGrowthPx * 0.5f
    val halfW = (pillWidthPx * pillScaleX) / 2f
    val halfH = ((size.height + liftGrowthPx) * pillScaleY) / 2f
    val scaledRadius = baseRadius * minOf(pillScaleX, pillScaleY)
    val centerY = size.height / 2f
    val pillPath = Path().apply {
        addRoundRect(
            RoundRect(
                left = centerPx - halfW,
                top = centerY - halfH,
                right = centerPx + halfW,
                bottom = centerY + halfH,
                radiusX = scaledRadius,
                radiusY = scaledRadius,
            ),
        )
    }
    onDrawWithContent {
        val contentScope = this
        clipPath(pillPath, clipOp = clipOp) { contentScope.drawContent() }
    }
}

@Composable
private fun FloatingNavigationVisuals(
    foreground: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
    visibleDestinations: List<AppDestination> = AppDestination.entries,
    actions: List<NavigationAction> = emptyList(),
) {
    Row(
        modifier = modifier.clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleDestinations.forEach { destination ->
            FloatingNavigationVisual(
                symbol = destination.symbol,
                label = stringResource(destination.titleRes),
                foreground = foreground,
                filled = filled,
                modifier = Modifier.weight(1f),
            )
        }
        actions.forEach { action ->
            FloatingNavigationVisual(
                symbol = action.symbol,
                label = action.label,
                foreground = foreground,
                filled = filled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FloatingNavigationVisual(
    symbol: String,
    label: String,
    foreground: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MaterialSymbol(
            symbol = symbol,
            tint = foreground,
            size = 30.dp,
            filled = filled,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.offset(y = (-4).dp),
        )
    }
}

@Composable
private fun NavigationActionTarget(
    action: NavigationAction,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(InnerPillRadius))
            .semantics { contentDescription = action.label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = action.onClick,
            ),
    )
}

@Composable
private fun FloatingNavigationTarget(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinationLabel = stringResource(destination.titleRes)
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(InnerPillRadius))
            .semantics { contentDescription = destinationLabel }
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
    )
}
