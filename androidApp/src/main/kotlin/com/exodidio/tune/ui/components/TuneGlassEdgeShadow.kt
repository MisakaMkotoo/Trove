package com.exodidio.tune.ui.components

/*
 * Adapted from NEORUAA/Glasmio (Apache-2.0):
 * https://github.com/NEORUAA/Glasmio
 */

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

internal const val TuneGlassEdgeShadowLightAlpha = 0.30f
internal const val TuneGlassEdgeShadowDarkAlpha = 0.24f

internal fun resolveGlassEdgeShadowAlpha(isDark: Boolean): Float =
    if (isDark) TuneGlassEdgeShadowDarkAlpha else TuneGlassEdgeShadowLightAlpha

/**
 * Draws the crisp exterior liquid-glass edge shadow and hairline contour outside the backdrop
 * clip, synchronized with [layerBlock] so press/drag deformation moves the shadow seamlessly
 * without leaving an inset fringe inside the glass lens.
 */
internal fun Modifier.tuneGlassBoxShadow(
    shape: () -> Shape,
    alpha: Float = TuneGlassEdgeShadowLightAlpha,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    outlineEnabled: Boolean = true,
): Modifier = then(TuneGlassBoxShadowElement(shape, alpha, layerBlock, outlineEnabled))

private class TuneGlassBoxShadowElement(
    private val shape: () -> Shape,
    private val alpha: Float,
    private val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    private val outlineEnabled: Boolean,
) : ModifierNodeElement<TuneGlassBoxShadowNode>() {

    override fun create(): TuneGlassBoxShadowNode =
        TuneGlassBoxShadowNode(shape, alpha, layerBlock, outlineEnabled)

    override fun update(node: TuneGlassBoxShadowNode) {
        node.shape = shape
        node.alpha = alpha
        node.layerBlock = layerBlock
        node.outlineEnabled = outlineEnabled
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tuneGlassBoxShadow"
        properties["shape"] = shape
        properties["alpha"] = alpha
        properties["layerBlock"] = layerBlock
        properties["outlineEnabled"] = outlineEnabled
    }

    override fun equals(other: Any?): Boolean =
        other is TuneGlassBoxShadowElement &&
            shape == other.shape &&
            alpha == other.alpha &&
            layerBlock == other.layerBlock &&
            outlineEnabled == other.outlineEnabled

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + outlineEnabled.hashCode()
        return result
    }
}

private class TuneGlassBoxShadowNode(
    var shape: () -> Shape,
    var alpha: Float,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var outlineEnabled: Boolean,
) : DrawModifierNode, Modifier.Node() {

    private var sideLayer: GraphicsLayer? = null
    private var outlineLayer: GraphicsLayer? = null
    private val layerScope = TuneGraphicsLayerBlockScope()
    private var recordedSize = Size.Unspecified
    private var recordedShape: Shape? = null
    private var recordedAlpha = Float.NaN
    private var recordedDensity = Float.NaN
    private var recordedFontScale = Float.NaN
    private var recordedLayoutDirection: LayoutDirection? = null
    private var allocationSize = IntSize.Zero

    private val sidePaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val outlinePaint = Paint().apply {
        style = PaintingStyle.Stroke
        isAntiAlias = true
    }
    private val clearPaint = Paint().apply {
        style = PaintingStyle.Fill
        blendMode = BlendMode.Clear
        isAntiAlias = true
    }

    override fun ContentDrawScope.draw() {
        val sideLayer = sideLayer ?: return drawContent()
        val outlineLayer = outlineLayer ?: return drawContent()
        val shadowAlpha = alpha.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (shadowAlpha <= 0f || size.minDimension <= 0f || !size.width.isFinite() || !size.height.isFinite()) {
            return drawContent()
        }

        // Evaluate layerBlock before early-out on alpha == 0 so snapshot state remains observed.
        layerScope.reset(this, size)
        layerBlock?.invoke(layerScope)
        val liveAlpha = layerScope.alpha.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (liveAlpha <= 0f) return drawContent()

        val sideOffset = 0.7.dp.toPx()
        val outlineWidth = 0.65.dp.toPx()
        val padding = ceil(sideOffset + outlineWidth / 2f + 1f).toInt()
        val layerSize = IntSize(
            ceil(size.width).toInt() + padding * 2,
            ceil(size.height).toInt() + padding * 2,
        )
        allocationSize = IntSize(
            maxOf(allocationSize.width, (layerSize.width + 31) / 32 * 32),
            maxOf(allocationSize.height, (layerSize.height + 31) / 32 * 32),
        )
        val currentShape = shape()
        if (recordedSize != size ||
            recordedShape != currentShape ||
            recordedAlpha != shadowAlpha ||
            recordedDensity != density ||
            recordedFontScale != fontScale ||
            recordedLayoutDirection != layoutDirection
        ) {
            val outline = currentShape.createOutline(size, layoutDirection, this)
            sidePaint.color = Color(0xFF3F3F3F).copy(alpha = shadowAlpha)
            outlinePaint.color = Color(0xFF6E6E6E).copy(alpha = shadowAlpha)
            outlinePaint.strokeWidth = outlineWidth

            // Horizontally expanded silhouette minus the original interior yields symmetric curved
            // meniscus side shadows strictly along the left/right arcs (no flat top/bottom line).
            val bodyWidth = size.width.coerceAtLeast(1f)
            sideLayer.record(allocationSize) {
                translate(padding.toFloat(), padding.toFloat()) {
                    drawContext.canvas.save()
                    drawContext.canvas.translate(bodyWidth / 2f, 0f)
                    drawContext.canvas.scale((bodyWidth + sideOffset * 2f) / bodyWidth, 1f)
                    drawContext.canvas.translate(-bodyWidth / 2f, 0f)
                    drawContext.canvas.drawOutline(outline, sidePaint)
                    drawContext.canvas.restore()
                    drawContext.canvas.drawOutline(outline, clearPaint)
                }
            }

            // Outer-only hairline contour: stroke the outline then clear the interior.
            outlineLayer.record(allocationSize) {
                translate(padding.toFloat(), padding.toFloat()) {
                    drawContext.canvas.drawOutline(outline, outlinePaint)
                    drawContext.canvas.drawOutline(outline, clearPaint)
                }
            }

            recordedSize = size
            recordedShape = currentShape
            recordedAlpha = shadowAlpha
            recordedDensity = density
            recordedFontScale = fontScale
            recordedLayoutDirection = layoutDirection
        }

        val pivotOffset = Offset(
            padding + layerScope.transformOrigin.pivotFractionX * size.width,
            padding + layerScope.transformOrigin.pivotFractionY * size.height,
        )
        val safeScaleX = if (layerScope.scaleX.isFinite()) layerScope.scaleX else 1f
        val safeScaleY = if (layerScope.scaleY.isFinite()) layerScope.scaleY else 1f
        val safeTransX = layerScope.translationX.finiteOrZero()
        val safeTransY = layerScope.translationY.finiteOrZero()
        val safeRotX = layerScope.rotationX.finiteOrZero()
        val safeRotY = layerScope.rotationY.finiteOrZero()
        val safeRotZ = layerScope.rotationZ.finiteOrZero()
        val safeCamDist = if (layerScope.cameraDistance.isFinite() && layerScope.cameraDistance > 0f) {
            layerScope.cameraDistance
        } else {
            8f
        }

        sideLayer.alpha = liveAlpha
        sideLayer.scaleX = safeScaleX
        sideLayer.scaleY = safeScaleY
        sideLayer.translationX = safeTransX
        sideLayer.translationY = safeTransY
        sideLayer.rotationX = safeRotX
        sideLayer.rotationY = safeRotY
        sideLayer.rotationZ = safeRotZ
        sideLayer.cameraDistance = safeCamDist
        sideLayer.pivotOffset = pivotOffset

        outlineLayer.alpha = liveAlpha
        outlineLayer.scaleX = safeScaleX
        outlineLayer.scaleY = safeScaleY
        outlineLayer.translationX = safeTransX
        outlineLayer.translationY = safeTransY
        outlineLayer.rotationX = safeRotX
        outlineLayer.rotationY = safeRotY
        outlineLayer.rotationZ = safeRotZ
        outlineLayer.cameraDistance = safeCamDist
        outlineLayer.pivotOffset = pivotOffset

        translate(-padding.toFloat(), -padding.toFloat()) {
            drawLayer(sideLayer)
        }
        drawContent()
        if (outlineEnabled) {
            translate(-padding.toFloat(), -padding.toFloat()) {
                drawLayer(outlineLayer)
            }
        }
    }

    override fun onAttach() {
        recordedSize = Size.Unspecified
        recordedShape = null
        allocationSize = IntSize.Zero
        sideLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
        outlineLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    override fun onDetach() {
        sideLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        outlineLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        sideLayer = null
        outlineLayer = null
    }
}

private class TuneGraphicsLayerBlockScope : GraphicsLayerScope {
    private var densityValue: Density = Density(1f)

    override val density: Float get() = densityValue.density
    override val fontScale: Float get() = densityValue.fontScale

    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var alpha: Float = 1f
    override var translationX: Float = 0f
    override var translationY: Float = 0f
    override var shadowElevation: Float = 0f
    override var rotationX: Float = 0f
    override var rotationY: Float = 0f
    override var rotationZ: Float = 0f
    override var cameraDistance: Float = 8f
    override var transformOrigin: TransformOrigin = TransformOrigin.Center
    override var shape: Shape = RectangleShape
    override var clip: Boolean = false
    override var size: Size = Size.Zero

    fun reset(drawScope: Density, size: Size) {
        densityValue = drawScope
        this.size = size
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
        shadowElevation = 0f
        rotationX = 0f
        rotationY = 0f
        rotationZ = 0f
        cameraDistance = 8f
        transformOrigin = TransformOrigin.Center
        shape = RectangleShape
        clip = false
    }
}
