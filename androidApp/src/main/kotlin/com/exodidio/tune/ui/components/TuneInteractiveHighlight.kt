package com.exodidio.tune.ui.components

/*
 * Based on the InteractiveHighlight demo shipped with Kyant0/AndroidLiquidGlass (Apache-2.0).
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val FOCUS_GLOW_STEPS = 11

private fun focusGlowWeight(u: Float, plateau: Float): Float {
    if (u <= plateau) return 1f
    val t = ((u - plateau) / (1f - plateau)).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

private fun focusGlowWeights(plateau: Float): List<Float> =
    List(FOCUS_GLOW_STEPS) { i ->
        focusGlowWeight(i / (FOCUS_GLOW_STEPS - 1).toFloat(), plateau)
    }

class TuneInteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val panelGlowAlpha: Float = 0.08f,
    private val focusGlowAlpha: Float = 0.15f,
    private val focusGlowRadiusRatio: Float = 1.5f,
    private val focusGlowPlateau: Float = 0.5f,
) {
    private val pressSpec = spring<Float>(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero

    val pressProgress: Float get() = pressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition
    var isPressed: Boolean = false
        private set

    private val shader = if (isRuntimeShaderSupported()) {
        RuntimeShader(
            """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float plateau;
uniform float2 position;
half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * plateau, dist);
    return color * intensity;
}
""".trimIndent(),
        )
    } else {
        null
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = safeGlassProgress(pressAnimation.value)
        if (progress > 0f) {
            drawRect(Color.White.copy(alpha = panelGlowAlpha * progress), blendMode = BlendMode.Plus)
            val focus = positionAnimation.value
            val radius = size.minDimension * focusGlowRadiusRatio
            val peak = focusGlowAlpha * progress
            if (radius > 0f && peak > 0f) {
                val cx = focus.x.fastCoerceIn(0f, size.width)
                val cy = focus.y.fastCoerceIn(0f, size.height)
                if (shader != null) {
                    shader.apply {
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", Color.White.copy(alpha = peak))
                        setFloatUniform("radius", radius)
                        setFloatUniform("plateau", focusGlowPlateau)
                        setFloatUniform("position", cx, cy)
                    }
                    drawRect(ShaderBrush(shader.asComposeShader()), blendMode = BlendMode.Plus)
                } else {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = focusGlowWeights(focusGlowPlateau).map { Color.White.copy(alpha = peak * it) },
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        tuneInspectDragGestures(
            onDragStart = { down ->
                isPressed = true
                startPosition = down
                animationScope.launch {
                    launch { pressAnimation.animateTo(1f, pressSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                isPressed = false
                animationScope.launch {
                    launch { pressAnimation.animateTo(0f, pressSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionSpec) }
                }
            },
            onDragCancel = {
                isPressed = false
                animationScope.launch {
                    launch { pressAnimation.animateTo(0f, pressSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionSpec) }
                }
            },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }
}

@Composable
internal fun rememberTuneInteractiveHighlight(
    panelGlowAlpha: Float = 0.08f,
    focusGlowAlpha: Float = 0.15f,
    focusGlowRadiusRatio: Float = 1.5f,
    focusGlowPlateau: Float = 0.5f,
): TuneInteractiveHighlight {
    val scope = rememberCoroutineScope()
    return remember(scope, panelGlowAlpha, focusGlowAlpha, focusGlowRadiusRatio, focusGlowPlateau) {
        TuneInteractiveHighlight(
            animationScope = scope,
            panelGlowAlpha = panelGlowAlpha,
            focusGlowAlpha = focusGlowAlpha,
            focusGlowRadiusRatio = focusGlowRadiusRatio,
            focusGlowPlateau = focusGlowPlateau,
        )
    }
}

private suspend fun PointerInputScope.tuneInspectDragGestures(
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (PointerInputChange, Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        onDragStart(down.position)
        onDrag(initialDown, Offset.Zero)
        val upEvent = dragPointer(initialDown.id) { change ->
            onDrag(change, change.positionChange())
        }
        if (upEvent == null) onDragCancel() else onDragEnd()
    }
}

private suspend inline fun AwaitPointerEventScope.dragPointer(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUpPointer(pointer) ?: return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUpPointer(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) return change
            pointer = otherDown.id
        } else if (change.previousPosition != change.position) {
            return change
        }
    }
}
