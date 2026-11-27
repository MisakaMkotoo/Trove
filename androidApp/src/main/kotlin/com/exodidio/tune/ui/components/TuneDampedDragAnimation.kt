package com.exodidio.tune.ui.components

/*
 * Adapted from DampedDragAnimation & DragGestureInspector in Kyant0/AndroidLiquidGlass (Apache-2.0):
 * https://github.com/Kyant0/AndroidLiquidGlass
 */

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

internal class TuneDampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedRange<Float>,
    visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: TuneDampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: TuneDampedDragAnimation.() -> Unit,
    val onDragCanceled: TuneDampedDragAnimation.() -> Unit = onDragStopped,
    val onDrag: TuneDampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    val shouldConsumeDrag: TuneDampedDragAnimation.(totalDelta: Offset) -> Boolean = { true },
) {
    // Glasmio / UIKit SwitchThumb springs + Kyant value/velocity springs
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressSpringSpec = spring(0.625f, 541.54f, 0.001f)
    private val unliftSpringSpec = spring(0.7f, 157.91f, 0.001f)
    private val minLiftDurationMillis = 220L

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
    private var pressStartedAtMillis = 0L
    private var liftJob: Job? = null

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(this) {
        inspectTuneDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragCanceled()
                release()
            },
            shouldConsumeDrag = { totalDelta -> shouldConsumeDrag(totalDelta) },
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        pressStartedAtMillis = SystemClock.uptimeMillis()
        liftJob?.cancel()
        liftJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressSpringSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, pressSpringSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, pressSpringSpec) }
        }
    }

    fun release() {
        liftJob?.cancel()
        liftJob = animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.05f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            val elapsed = SystemClock.uptimeMillis() - pressStartedAtMillis
            val remaining = minLiftDurationMillis - elapsed
            if (remaining > 0L) {
                delay(remaining.milliseconds)
            }
            launch { pressProgressAnimation.animateTo(0f, unliftSpringSpec) }
            launch { scaleXAnimation.animateTo(initialScale, unliftSpringSpec) }
            launch { scaleYAnimation.animateTo(initialScale, unliftSpringSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                animationScope.launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    animationScope.launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            SystemClock.uptimeMillis(),
            Offset(value, 0f),
        )
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
        val targetVelocity = velocityTracker.calculateVelocity().x / span
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

private suspend fun PointerInputScope.inspectTuneDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit,
    onDragEnd: (change: PointerInputChange) -> Unit,
    onDragCancel: () -> Unit,
    shouldConsumeDrag: (totalDelta: Offset) -> Boolean,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
        down.consume()
        var totalDelta = Offset.Zero
        onDragStart(down)
        onDrag(down, Offset.Zero)
        val upEvent = dragTunePointer(down.id) { change ->
            val delta = change.positionChange()
            totalDelta += delta
            if (shouldConsumeDrag(totalDelta)) {
                change.consume()
            }
            onDrag(change, delta)
        }
        if (upEvent == null) {
            onDragCancel()
        } else {
            upEvent.consume()
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.dragTunePointer(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null
    var pointer = pointerId
    while (true) {
        val change = awaitTuneDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitTuneDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) return dragEvent
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}
