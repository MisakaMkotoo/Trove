package com.exodidio.tune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.kyant.backdrop.Backdrop
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

@Stable
internal class PopupMenuHoverState {
    var pointerRootPosition by mutableStateOf<Offset?>(null)
    var isGliding by mutableStateOf(false)
    private val rowActions = mutableMapOf<Any, Pair<Rect, () -> Unit>>()

    fun registerAction(key: Any, boundsInRoot: Rect, onTrigger: () -> Unit) {
        rowActions[key] = boundsInRoot to onTrigger
    }

    fun unregisterAction(key: Any) {
        rowActions.remove(key)
    }

    fun triggerAt(rootPosition: Offset): Boolean {
        val match = rowActions.values.firstOrNull { (bounds, _) -> bounds.contains(rootPosition) } ?: return false
        match.second.invoke()
        return true
    }

    fun clear() {
        pointerRootPosition = null
        isGliding = false
    }
}

internal val LocalPopupMenuHoverState = staticCompositionLocalOf<PopupMenuHoverState?> { null }

private data class AnchoredPopupMenuRequest(
    val id: Any,
    val anchorRightPx: Float,
    val anchorTopPx: Float,
    val anchorBottomPx: Float,
    val offset: DpOffset,
    val width: Dp,
    val shape: Shape,
    val hazeState: HazeState?,
    val backdrop: Backdrop?,
    val onDismissRequest: () -> Unit,
    val menu: @Composable () -> Unit,
)

private class AnchoredPopupMenuHostState {
    var request by mutableStateOf<AnchoredPopupMenuRequest?>(null)

    fun show(request: AnchoredPopupMenuRequest) {
        val current = this.request
        if (
            current == null ||
            current.id !== request.id ||
            current.anchorRightPx != request.anchorRightPx ||
            current.anchorTopPx != request.anchorTopPx ||
            current.anchorBottomPx != request.anchorBottomPx ||
            current.offset != request.offset ||
            current.width != request.width ||
            current.shape != request.shape ||
            current.hazeState !== request.hazeState ||
            current.backdrop !== request.backdrop
        ) {
            this.request = request
        }
    }

    fun dismiss(id: Any) {
        if (request?.id === id) request = null
    }

    fun dismissAll() {
        request = null
    }
}

private val LocalAnchoredPopupMenuHost = staticCompositionLocalOf<AnchoredPopupMenuHostState?> { null }

/**
 * App-level host for [AnchoredPopupMenu]. It keeps popup content in the same
 * Compose tree as the Haze source, unlike Android's separate-window [Popup].
 */
@Composable
fun AnchoredPopupMenuHost(
    hazeState: HazeState?,
    dismissKey: Any? = Unit,
    content: @Composable () -> Unit,
) {
    val host = remember { AnchoredPopupMenuHostState() }
    LaunchedEffect(dismissKey) {
        host.dismissAll()
    }
    CompositionLocalProvider(LocalAnchoredPopupMenuHost provides host) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            AnchoredPopupMenuOverlay(host.request, hazeState)
        }
    }
}

@Composable
private fun AnchoredPopupMenuOverlay(
    request: AnchoredPopupMenuRequest?,
    defaultHazeState: HazeState?,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val isLiquidGlass = LocalControlStyle.current == ControlStyle.LiquidGlass && !reduceMotion
    val liquidGlassBlurDp = LocalLiquidGlassBlurDp.current.dp
    var retainedRequest by remember { mutableStateOf<AnchoredPopupMenuRequest?>(null) }
    var hazeEnabled by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val openSpring = remember { spring<Float>(dampingRatio = 0.74f, stiffness = 460f, visibilityThreshold = 0.001f) }
    val closeSpring = remember { spring<Float>(dampingRatio = 0.94f, stiffness = 580f, visibilityThreshold = 0.001f) }

    LaunchedEffect(request?.id) {
        if (request == null) {
            if (reduceMotion) {
                progress.snapTo(0f)
            } else {
                progress.animateTo(0f, animationSpec = closeSpring)
            }
            retainedRequest = null
        } else {
            retainedRequest = request
            hazeEnabled = true
            progress.snapTo(0f)
            if (reduceMotion) {
                progress.snapTo(1f)
            } else {
                progress.animateTo(1f, animationSpec = openSpring)
            }
        }
    }
    val activeRequest = retainedRequest ?: return

    val hoverState = remember(activeRequest.id) { PopupMenuHoverState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val menuDragOffset = remember(activeRequest.id) { Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold) }
    val menuPressProgress = remember(activeRequest.id) { Animatable(0f) }
    val menuSpringBack = remember { spring(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = Offset.VisibilityThreshold) }
    val pressSpring = remember { spring<Float>(dampingRatio = 0.625f, stiffness = loupeSpringStiffness(0.314f), visibilityThreshold = 0.001f) }
    val releaseSpring = remember { spring<Float>(dampingRatio = 0.60f, stiffness = loupeSpringStiffness(0.360f), visibilityThreshold = 0.001f) }
    var menuBoundsInRoot by remember(activeRequest.id) { mutableStateOf<Rect?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                onClick = activeRequest.onDismissRequest,
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ),
    ) {
        val edgeInsetPx = with(density) { 16.dp.roundToPx() }
        val hostWidthPx = with(density) { maxWidth.roundToPx() }
        val hostHeightPx = with(density) { maxHeight.roundToPx() }
        val menuWidthPx = with(density) { activeRequest.width.roundToPx() }
        val offsetX = with(density) { activeRequest.offset.x.roundToPx() }
        val offsetY = with(density) { activeRequest.offset.y.roundToPx() }
        var menuHeightPx by remember(activeRequest.id) { mutableStateOf(0) }
        val preferredX = (activeRequest.anchorRightPx - menuWidthPx + offsetX).roundToInt()
        val maxX = (hostWidthPx - menuWidthPx - edgeInsetPx).coerceAtLeast(edgeInsetPx)
        val constrainedX = preferredX.coerceIn(edgeInsetPx, maxX)
        val belowY = (activeRequest.anchorBottomPx + offsetY).roundToInt()
        val aboveY = (activeRequest.anchorTopPx - offsetY - menuHeightPx).roundToInt()
        val renderAbove = menuHeightPx > 0 && belowY + menuHeightPx > hostHeightPx - edgeInsetPx
        val preferredY = if (renderAbove) aboveY else belowY
        val maxY = (hostHeightPx - menuHeightPx - edgeInsetPx).coerceAtLeast(edgeInsetPx)
        val constrainedY = preferredY.coerceIn(edgeInsetPx, maxY)
        val emergeOffsetPx = with(density) { (if (renderAbove) 10.dp else (-10).dp).toPx() }
        val animatedGlassTint = if (isLiquidGlass) null else rememberAnimatedGlassTint(colors, animate = !reduceMotion)

        val menuLayerBlock: GraphicsLayerScope.() -> Unit = {
            val p = progress.value
            val clampedAlpha = (p * 1.4f).coerceIn(0f, 1f)
            val openScaleX = 0.78f + (0.22f * p)
            val openScaleY = 0.62f + (0.38f * p)
            val rawOffset = menuDragOffset.value
            val minDim = minOf(size.width, size.height).coerceAtLeast(1f)
            val maxMenuTranslation = minDim * 0.25f
            val stretch = computeGlassDragScale(
                rawOffsetPx = rawOffset,
                widthPx = size.width,
                heightPx = size.height,
                pressProgress = menuPressProgress.value,
                pressedScale = if (isLiquidGlass) 1.018f else 1.008f,
                maxStretch = 0.048f,
            )
            alpha = clampedAlpha
            scaleX = openScaleX * stretch.scaleX
            scaleY = openScaleY * stretch.scaleY
            translationX = applyGlassDragDisplacement(rawOffset.x, maxMenuTranslation)
            translationY = emergeOffsetPx * (1f - p.coerceIn(0f, 1.15f)) +
                applyGlassDragDisplacement(rawOffset.y, maxMenuTranslation)
            transformOrigin = TransformOrigin(1f, if (renderAbove) 1f else 0f)
        }

        val useOpticalGlass = isLiquidGlass && hazeEnabled &&
            (activeRequest.backdrop != null || tuneGlassBackdrop() != null)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(constrainedX, constrainedY)
                }
                .width(activeRequest.width)
                .onSizeChanged { size -> if (menuHeightPx != size.height) menuHeightPx = size.height }
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) menuBoundsInRoot = coords.boundsInRoot()
                }
                .pointerInput(activeRequest.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val rootTopLeft = menuBoundsInRoot?.topLeft ?: Offset.Zero
                        hoverState.pointerRootPosition = rootTopLeft + down.position
                        hoverState.isGliding = false
                        if (!reduceMotion) {
                            scope.launch { menuPressProgress.animateTo(1f, pressSpring) }
                        }
                        var activeId = down.id
                        var gliding = false
                        var dragJob: Job? = null
                        val touchSlop = viewConfiguration.touchSlop
                        try {
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == activeId }
                                    ?: event.changes.firstOrNull { it.pressed }
                                if (change == null) break
                                val currentRootTopLeft = menuBoundsInRoot?.topLeft ?: rootTopLeft
                                val rootPos = currentRootTopLeft + change.position
                                hoverState.pointerRootPosition = rootPos
                                if (change.changedToUpIgnoreConsumed()) {
                                    if (gliding && !change.isConsumed) {
                                        if (hoverState.triggerAt(rootPos)) {
                                            change.consume()
                                        }
                                    }
                                    break
                                }
                                activeId = change.id
                                val delta = change.position - down.position
                                if (!gliding && hypot(delta.x, delta.y) > touchSlop) {
                                    gliding = true
                                    hoverState.isGliding = true
                                }
                                if (!reduceMotion) {
                                    val safeDelta = Offset(delta.x.finiteOrZero(), delta.y.finiteOrZero())
                                    dragJob?.cancel()
                                    dragJob = scope.launch { menuDragOffset.snapTo(safeDelta) }
                                }
                            }
                        } finally {
                            dragJob?.cancel()
                            hoverState.clear()
                            if (!reduceMotion) {
                                scope.launch {
                                    launch { menuPressProgress.animateTo(0f, releaseSpring) }
                                    launch { menuDragOffset.animateTo(Offset.Zero, menuSpringBack) }
                                }
                            }
                        }
                    }
                }
                .tuneLiquidGlassBackground(
                    hazeState = if (hazeEnabled) activeRequest.hazeState ?: defaultHazeState else null,
                    colors = colors,
                    shape = activeRequest.shape,
                    glassBlurRadius = liquidGlassBlurDp,
                    glassTint = animatedGlassTint,
                    enableLens = true,
                    lensRadius = 26.dp,
                    refractionHeight = 14.dp,
                    chromaticAberration = isLiquidGlass,
                    pressProgress = if (isLiquidGlass) menuPressProgress.value * 0.4f else 0f,
                    layerBlock = menuLayerBlock,
                    backdropOverride = if (hazeEnabled) activeRequest.backdrop else null,
                )
                .then(
                    if (!useOpticalGlass) {
                        Modifier.border(0.5.dp, colors.borderGlass, activeRequest.shape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            CompositionLocalProvider(LocalPopupMenuHoverState provides hoverState) {
                activeRequest.menu()
            }
        }
    }
}

/**
 * Renders [menu] in the app-level [AnchoredPopupMenuHost], top-end aligned to
 * [anchor]. The host is required for Haze; without one the anchor still works
 * but no menu is rendered, which keeps isolated previews safe.
 */
@Composable
fun AnchoredPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    width: Dp,
    hazeState: HazeState? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    anchor: @Composable () -> Unit,
    menu: @Composable () -> Unit,
) {
    val host = LocalAnchoredPopupMenuHost.current
    val backdrop = LocalTuneBackdrop.current
    val id = remember { Any() }
    var anchorRightPx by remember { mutableStateOf<Float?>(null) }
    var anchorTopPx by remember { mutableStateOf<Float?>(null) }
    var anchorBottomPx by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(host, id) {
        onDispose { host?.dismiss(id) }
    }

    SideEffect {
        val right = anchorRightPx
        val top = anchorTopPx
        val bottom = anchorBottomPx
        if (expanded && host != null && right != null && top != null && bottom != null) {
            host.show(
                AnchoredPopupMenuRequest(
                    id = id,
                    anchorRightPx = right,
                    anchorTopPx = top,
                    anchorBottomPx = bottom,
                    offset = offset,
                    width = width,
                    shape = shape,
                    hazeState = hazeState,
                    backdrop = backdrop,
                    onDismissRequest = onDismissRequest,
                    menu = menu,
                ),
            )
        } else if (!expanded) {
            host?.dismiss(id)
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            coordinates.boundsInRoot().let { bounds ->
                anchorRightPx = bounds.right
                anchorTopPx = bounds.top
                anchorBottomPx = bounds.bottom
            }
        },
    ) {
        anchor()
    }
}
