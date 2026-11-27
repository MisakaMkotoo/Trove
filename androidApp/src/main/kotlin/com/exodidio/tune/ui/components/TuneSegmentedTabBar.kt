package com.exodidio.tune.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * 统一的胶囊分段标签栏：在液态玻璃模式下使用 Kyant 光学透镜 + Glasmio loupeFlex 物理引擎，
 * 按住或拖动指示胶囊即可呈现物理膨胀、加速度形变与前缘漂移动效。
 */
@Composable
internal fun TuneSegmentedTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = LocalReduceMotion.current,
    tabTestTagPrefix: String? = null,
) {
    if (tabs.isEmpty()) return
    val colors = LocalTuneColors.current
    val dark = colors.background.luminance() < 0.5f
    val isLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass && !reduceMotion
    val canUseKyantLens = isLiquidGlass &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        LocalView.current.isHardwareAccelerated
    val outerCorner = if (isLiquidGlass) 19.dp else 14.dp
    val pillCorner = if (isLiquidGlass) 16.dp else 11.dp
    val barHeight = if (isLiquidGlass) 40.dp else 38.dp
    val outerShape = RoundedCornerShape(outerCorner)
    val pillShape = RoundedCornerShape(pillCorner)
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)

    if (!isLiquidGlass) {
        FrostedSegmentedTabBar(
            tabs = tabs,
            safeIndex = safeIndex,
            onSelect = onSelect,
            modifier = modifier,
            reduceMotion = reduceMotion,
            tabTestTagPrefix = tabTestTagPrefix,
            outerShape = outerShape,
            pillShape = pillShape,
            barHeight = barHeight,
        )
        return
    }

    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val panelBackdrop = if (canUseKyantLens) rememberLayerBackdrop() else null
    val outerBackdrop = tuneGlassBackdrop()
    val liquidGlassBlurDp = LocalLiquidGlassBlurDp.current.dp

    var barWidthPx by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    val platterPuffSpec = remember(barWidthPx, barHeightPx, density.density) {
        computePlatterPuffSpec(
            widthPx = if (barWidthPx > 0f) barWidthPx else with(density) { 320.dp.toPx() },
            heightPx = if (barHeightPx > 0f) barHeightPx else with(density) { barHeight.toPx() },
            density = density.density,
        )
    }
    val platterPuffAnim = remember { Animatable(0f) }
    val platterDragOffsetXAnim = remember { Animatable(0f) }
    val platterDragSettleSpring = remember {
        spring<Float>(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.5f)
    }
    val maxPlatterOffsetPx = with(density) { 4.dp.toPx() }

    val barLayerBlock: GraphicsLayerScope.() -> Unit = {
        val scale = (1f + platterPuffSpec.scaleDeltaPerUnitProgress * platterPuffAnim.value.finiteOrZero())
            .coerceIn(0.95f, 1.12f)
        scaleX = scale
        scaleY = scale
        translationX = platterDragOffsetXAnim.value.finiteOrZero()
    }
    val trackFill = if (dark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.055f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .onSizeChanged { size ->
                barWidthPx = size.width.toFloat()
                barHeightPx = size.height.toFloat()
            },
    ) {
        // Outer translucent track (exported into panelBackdrop for internal magnifying lens)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (outerBackdrop != null) {
                        Modifier.tuneLiquidGlassBackground(
                            hazeState = null,
                            colors = colors,
                            shape = outerShape,
                            glassBlurRadius = liquidGlassBlurDp,
                            glassTint = null,
                            enableLens = true,
                            lensRadius = 12.dp,
                            refractionHeight = 7.dp,
                            chromaticAberration = true,
                            pressProgress = platterPuffAnim.value * 0.45f,
                            layerBlock = barLayerBlock,
                            exportedBackdrop = panelBackdrop,
                        )
                    } else {
                        Modifier
                            .graphicsLayer(barLayerBlock)
                            .clip(outerShape)
                            .background(trackFill, outerShape)
                            .then(if (panelBackdrop != null) Modifier.layerBackdrop(panelBackdrop) else Modifier)
                            .border(0.5.dp, colors.borderGlass, outerShape)
                    },
                ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(barLayerBlock)
                .padding(3.dp),
        ) {
            val trackWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val trackHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val trackHeightDp = with(density) { trackHeightPx.toDp() }
            val segmentWidth = maxWidth / tabs.size
            val segmentWidthPx = (trackWidthPx / tabs.size.toFloat()).coerceAtLeast(1f)
            val minCenterPx = segmentWidthPx / 2f
            val maxCenterPx = (trackWidthPx - segmentWidthPx / 2f).coerceAtLeast(minCenterPx)
            val targetCenterPx = ((safeIndex + 0.5f) * segmentWidthPx).coerceIn(minCenterPx, maxCenterPx)

            val currentSafeIndex by rememberUpdatedState(safeIndex)
            val currentOnSelect by rememberUpdatedState(onSelect)
            val currentTabCount by rememberUpdatedState(tabs.size)
            var isInteracting by remember { mutableStateOf(false) }
            var lastCommittedIndex by remember { mutableStateOf<Int?>(null) }

            val tabMotion = rememberTuneNavigationTabMotion(
                isTopBar = true,
                reduceMotion = reduceMotion,
            )

            LaunchedEffect(safeIndex, segmentWidthPx, maxCenterPx, trackHeightPx, isInteracting) {
                tabMotion.updateTrackHeight(trackHeightPx)
                if (!isInteracting) {
                    val previousIndex = lastCommittedIndex
                    if (previousIndex == null) {
                        tabMotion.snapTo(targetCenterPx, segmentWidthPx, trackHeightPx)
                        lastCommittedIndex = safeIndex
                    } else if (previousIndex != safeIndex) {
                        lastCommittedIndex = safeIndex
                        if (abs(tabMotion.targetCenterPx - targetCenterPx) > 1f) {
                            tabMotion.press()
                            tabMotion.moveTo(targetCenterPx, segmentWidthPx, trackHeightPx)
                            tabMotion.release()
                        } else {
                            tabMotion.moveTo(targetCenterPx, segmentWidthPx, trackHeightPx)
                        }
                    } else if (
                        abs(tabMotion.targetCenterPx - targetCenterPx) > 0.5f ||
                        abs(tabMotion.targetWidthPx - segmentWidthPx) > 0.5f
                    ) {
                        tabMotion.snapTo(targetCenterPx, segmentWidthPx, trackHeightPx)
                    }
                }
            }

            val activeCenterPx = if (lastCommittedIndex == null) targetCenterPx else tabMotion.centerPx
            val activeWidthPx = if (lastCommittedIndex == null) segmentWidthPx else tabMotion.widthPx
            val pressProgress = safeGlassProgress(tabMotion.pressProgress)
            val liftGrowthDp = TuneNavigationTabMotionDefaults.LiftGrowthDp.dp * pressProgress
            val liftGrowthPx = with(density) { liftGrowthDp.toPx() }
            val liftedWidthPx = activeWidthPx + liftGrowthPx
            val liftedWidthDp = with(density) { liftedWidthPx.toDp() }
            val liftedHeightDp = trackHeightDp + liftGrowthDp
            val liftedShape = RoundedCornerShape(pillCorner + liftGrowthDp * 0.5f)
            val useSingleBlueGlassPill = panelBackdrop != null && liftedShape.supportsKyantLens()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(segmentWidthPx, minCenterPx, maxCenterPx, trackHeightPx, tabs.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var dragActive = false
                            var longPressEngaged = false
                            var abortedForScroll = false
                            var activePointerId = down.id
                            var platterDragJob: Job? = null
                            animationScope.launch {
                                platterPuffAnim.animateTo(1f, platterPuffSpec.pressSpring)
                            }
                            val holdJob = animationScope.launch {
                                delay(85.milliseconds)
                                if (!abortedForScroll) {
                                    longPressEngaged = true
                                    isInteracting = true
                                    tabMotion.press()
                                    val touchCenter = down.position.x.coerceIn(minCenterPx, maxCenterPx)
                                    tabMotion.moveTo(touchCenter, segmentWidthPx, trackHeightPx)
                                }
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
                                            val slot = (finalCenter / segmentWidthPx).toInt()
                                                .coerceIn(0, currentTabCount - 1)
                                            val slotCenter = ((slot + 0.5f) * segmentWidthPx)
                                                .coerceIn(minCenterPx, maxCenterPx)
                                            tabMotion.moveTo(slotCenter, segmentWidthPx, trackHeightPx)
                                            if (slot != currentSafeIndex) {
                                                currentOnSelect(slot)
                                            }
                                        }
                                        break
                                    }
                                    activePointerId = change.id
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (!dragActive && !longPressEngaged && abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.15f) {
                                        abortedForScroll = true
                                        holdJob.cancel()
                                        tabMotion.cancelPress()
                                        break
                                    }
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
                                        tabMotion.moveTo(targetCenter, segmentWidthPx, trackHeightPx)
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
                            } finally {
                                holdJob.cancel()
                                platterDragJob?.cancel()
                                if (longPressEngaged || dragActive) {
                                    tabMotion.release()
                                }
                                animationScope.launch {
                                    launch { platterPuffAnim.animateTo(0f, platterPuffSpec.releaseSpring) }
                                    launch { platterDragOffsetXAnim.animateTo(0f, platterDragSettleSpring) }
                                }
                                isInteracting = false
                            }
                        }
                    },
            ) {
                // Single unified blue-tinted Kyant liquid glass capsule (+16.dp lift expansion + loupeFlex + drift)
                if (useSingleBlueGlassPill) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset {
                                IntOffset((activeCenterPx - liftedWidthPx / 2f).roundToInt(), 0)
                            }
                            .requiredWidth(liftedWidthDp.coerceAtLeast(segmentWidth))
                            .requiredHeight(liftedHeightDp.coerceAtLeast(trackHeightDp))
                            .drawBackdrop(
                                backdrop = panelBackdrop,
                                shape = { liftedShape },
                                effects = {
                                    val p = safeGlassProgress(tabMotion.pressProgress)
                                    lens(
                                        refractionHeight = (5.dp.toPx() + 5.dp.toPx() * p).coerceAtLeast(1f),
                                        refractionAmount = (12.dp.toPx() + 8.dp.toPx() * p).coerceAtLeast(1f),
                                        depthEffect = true,
                                        chromaticAberration = true,
                                    )
                                },
                                highlight = {
                                    val p = safeGlassProgress(tabMotion.pressProgress)
                                    Highlight.Default.copy(alpha = (0.48f + 0.34f * p).coerceAtMost(1f))
                                },
                                shadow = {
                                    val p = safeGlassProgress(tabMotion.pressProgress)
                                    Shadow(
                                        radius = 8.dp + 4.dp * p,
                                        color = Color.Black.copy(
                                            alpha = (if (dark) 0.22f + 0.10f * p else 0.08f + 0.08f * p).coerceIn(0f, 1f),
                                        ),
                                        alpha = 1f,
                                    )
                                },
                                innerShadow = {
                                    val p = safeGlassProgress(tabMotion.pressProgress)
                                    InnerShadow(
                                        radius = 3.dp + 2.dp * p,
                                        color = Color.Black.copy(
                                            alpha = (if (dark) 0.16f + 0.08f * p else 0.06f + 0.06f * p).coerceIn(0f, 1f),
                                        ),
                                        alpha = 1f,
                                    )
                                },
                                layerBlock = {
                                    alpha = 1f
                                    scaleX = tabMotion.scaleX
                                    scaleY = tabMotion.scaleY
                                    translationX = tabMotion.flexOffsetPx
                                },
                                onDrawSurface = {
                                    val p = safeGlassProgress(tabMotion.pressProgress)
                                    val baseBlueAlpha = if (dark) 0.28f else 0.18f
                                    val blueAlpha = (baseBlueAlpha - 0.04f * p).coerceIn(0.12f, 0.34f)
                                    drawRect(colors.primary.copy(alpha = blueAlpha))
                                    drawRect(
                                        Color.White.copy(alpha = (if (dark) 0.055f else 0.12f) + 0.08f * p),
                                        blendMode = BlendMode.Screen,
                                    )
                                },
                            ),
                    )
                } else {
                    val fallbackPillColor = if (dark) {
                        colors.primary.copy(alpha = 0.24f)
                    } else {
                        colors.primary.copy(alpha = 0.16f)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset {
                                IntOffset((activeCenterPx - liftedWidthPx / 2f).roundToInt(), 0)
                            }
                            .requiredWidth(liftedWidthDp.coerceAtLeast(segmentWidth))
                            .requiredHeight(liftedHeightDp.coerceAtLeast(trackHeightDp))
                            .graphicsLayer {
                                scaleX = tabMotion.scaleX
                                scaleY = tabMotion.scaleY
                                translationX = tabMotion.flexOffsetPx
                            }
                            .clip(liftedShape)
                            .background(fallbackPillColor)
                            .border(0.5.dp, colors.primary.copy(alpha = 0.35f), liftedShape),
                    )
                }

                // Crisp dual-masked labels synced to the exact lifted & flexing geometry of the glass capsule
                Box(modifier = Modifier.fillMaxSize()) {
                    SegmentedTabLabelsRow(
                        tabs = tabs,
                        foreground = colors.textMuted,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .fillMaxSize()
                            .segmentedTabMask(
                                centerPx = activeCenterPx + tabMotion.flexOffsetPx,
                                pillWidthPx = liftedWidthPx,
                                liftGrowthPx = liftGrowthPx,
                                baseCornerRadius = pillCorner,
                                pillScaleX = tabMotion.scaleX,
                                pillScaleY = tabMotion.scaleY,
                                clipOp = ClipOp.Difference,
                            ),
                    )
                    SegmentedTabLabelsRow(
                        tabs = tabs,
                        foreground = colors.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxSize()
                            .segmentedTabMask(
                                centerPx = activeCenterPx + tabMotion.flexOffsetPx,
                                pillWidthPx = liftedWidthPx,
                                liftGrowthPx = liftGrowthPx,
                                baseCornerRadius = pillCorner,
                                pillScaleX = tabMotion.scaleX,
                                pillScaleY = tabMotion.scaleY,
                                clipOp = ClipOp.Intersect,
                            ),
                    )
                }

                // Layer 3: Accessibility & quick-tap hit targets
                Row(modifier = Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { index, _ ->
                        val isSelected = index == safeIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(pillShape)
                                .semantics { this.selected = isSelected }
                                .then(
                                    if (tabTestTagPrefix != null) Modifier.testTag("$tabTestTagPrefix-$index") else Modifier,
                                )
                                .selectable(
                                    selected = isSelected,
                                    onClick = { onSelect(index) },
                                    role = Role.Tab,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedTabLabelsRow(
    tabs: List<String>,
    foreground: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    val compactLabels = tabs.size >= 4
    val baseStyle = MaterialTheme.typography.labelLarge
    val textStyle = if (compactLabels) {
        baseStyle.copy(fontSize = 12.sp, fontWeight = fontWeight)
    } else {
        baseStyle.copy(fontWeight = fontWeight)
    }
    Row(
        modifier = modifier.clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { label ->
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = textStyle,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = if (compactLabels) 3.dp else 6.dp),
                )
            }
        }
    }
}

private fun Modifier.segmentedTabMask(
    centerPx: Float,
    pillWidthPx: Float,
    liftGrowthPx: Float,
    baseCornerRadius: Dp,
    pillScaleX: Float = 1f,
    pillScaleY: Float = 1f,
    clipOp: ClipOp,
): Modifier = drawWithCache {
    val pillRadius = baseCornerRadius.toPx() + liftGrowthPx * 0.5f
    val centerY = size.height / 2f
    val halfW = (pillWidthPx * pillScaleX) / 2f
    val halfH = ((size.height + liftGrowthPx) * pillScaleY) / 2f
    val scaledRadius = pillRadius * minOf(pillScaleX, pillScaleY)
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
        val scope = this
        clipPath(pillPath, clipOp = clipOp) { scope.drawContent() }
    }
}

@Composable
private fun FrostedSegmentedTabBar(
    tabs: List<String>,
    safeIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    reduceMotion: Boolean,
    tabTestTagPrefix: String?,
    outerShape: RoundedCornerShape,
    pillShape: RoundedCornerShape,
    barHeight: Dp,
) {
    val colors = LocalTuneColors.current
    val compactLabels = tabs.size >= 4
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(outerShape)
            .background(colors.glassElevated)
            .border(0.5.dp, colors.borderGlass, outerShape)
            .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / tabs.size
        val indicatorOffset = if (reduceMotion) {
            segmentWidth * safeIndex
        } else {
            animateDpAsState(
                targetValue = segmentWidth * safeIndex,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "segmented-tab-indicator",
            ).value
        }
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(pillShape)
                .background(colors.primary.copy(alpha = 0.16f))
                .border(0.5.dp, colors.primary.copy(alpha = 0.35f), pillShape),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == safeIndex
                val textColor = if (reduceMotion) {
                    if (isSelected) colors.primary else colors.textMuted
                } else {
                    animateColorAsState(
                        targetValue = if (isSelected) colors.primary else colors.textMuted,
                        animationSpec = tween(durationMillis = 180),
                        label = "segmented-tab-color-$index",
                    ).value
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pillShape)
                        .semantics { this.selected = isSelected }
                        .then(
                            if (tabTestTagPrefix != null) Modifier.testTag("$tabTestTagPrefix-$index") else Modifier,
                        )
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(index) },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val baseStyle = MaterialTheme.typography.labelLarge
                    Text(
                        text = label,
                        style = (if (compactLabels) baseStyle.copy(fontSize = 12.sp) else baseStyle).copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = if (compactLabels) 3.dp else 6.dp),
                    )
                }
            }
        }
    }
}
