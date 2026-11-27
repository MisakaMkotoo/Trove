package com.exodidio.tune.ui.navigation

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 控件显隐的方向判定：事件死区 + 位置极值回滞 + 快滑自适应回撤门槛 + 触发限流。
 * 只决定控件显隐，歌词滚动本身不经过这里。
 */
internal class LyricsDirectionGate(
    private val triggerStepPx: Float,
    private val retracePx: Float,
    private val fastRetracePx: Float,
    private val fastSpeedPxPerSec: Float,
    private val deadZonePx: Float,
    private val minTriggerIntervalMs: Long,
) {
    private var direction = 0
    private var extremumY = 0f
    private var runPx = 0f
    private var lastTriggerSign = 0
    private var lastTriggerAtMs: Long? = null
    private var speedPxPerSec = 0f
    private var lastEventAtMs = 0L
    private var hasEventTime = false

    fun reset() {
        direction = 0
        runPx = 0f
        lastTriggerSign = 0
        speedPxPerSec = 0f
        hasEventTime = false
    }

    /** @return +1 下拉（唤出控件），-1 上滑（隐藏控件），0 不动作。 */
    fun onDrag(y: Float, rawDeltaY: Float, timeMs: Long): Int = evaluateDrag(y, rawDeltaY, timeMs)

    private fun evaluateDrag(y: Float, rawDeltaY: Float, timeMs: Long): Int {
        val dy = if (abs(rawDeltaY) < deadZonePx) 0f else rawDeltaY
        if (hasEventTime && timeMs > lastEventAtMs) {
            val instant = abs(dy) * 1_000f / (timeMs - lastEventAtMs).toFloat()
            speedPxPerSec += (instant - speedPxPerSec) * 0.3f
        }
        lastEventAtMs = timeMs
        hasEventTime = true
        val sign = when {
            dy > 0f -> 1
            dy < 0f -> -1
            else -> 0
        }
        if (sign == 0) return 0
        var swapped = false
        when {
            direction == 0 -> {
                direction = sign
                extremumY = y
                runPx = abs(dy)
            }
            sign == direction -> {
                runPx += abs(dy)
                extremumY = if (sign > 0) maxOf(extremumY, y) else minOf(extremumY, y)
            }
            else -> {
                val retraced = if (direction > 0) extremumY - y else y - extremumY
                val required = if (speedPxPerSec >= fastSpeedPxPerSec) fastRetracePx else retracePx
                if (retraced < required) return 0
                direction = sign
                extremumY = y
                runPx = 0f
                swapped = true
            }
        }
        if (!swapped && runPx < triggerStepPx) return 0
        if (lastTriggerSign == sign) return 0
        val lastAt = lastTriggerAtMs
        if (lastAt != null && timeMs - lastAt < minTriggerIntervalMs) return 0
        lastTriggerSign = sign
        lastTriggerAtMs = timeMs
        runPx = 0f
        return sign
    }
}

/** 显隐手势统一参数：死区 0.5dp、触发 12dp、回撤 16dp、快滑回撤 22dp（>1200dp/s）、限流 140ms。 */
internal fun Density.lyricsDirectionGate(): LyricsDirectionGate = LyricsDirectionGate(
    triggerStepPx = 12.dp.toPx(),
    retracePx = 16.dp.toPx(),
    fastRetracePx = 22.dp.toPx(),
    fastSpeedPxPerSec = 1_200.dp.toPx(),
    deadZonePx = 0.5.dp.toPx(),
    minTriggerIntervalMs = 140L,
)

/** 纯文本歌词没有自己的拖动处理，用不消费事件的观察器实现同样的方向手势。 */
internal fun Modifier.lyricsOverlayDirections(
    gate: LyricsDirectionGate,
    onPullDown: () -> Unit,
    onPullUp: () -> Unit,
): Modifier = pointerInput(gate) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gate.reset()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dy = change.position.y - change.previousPosition.y
                when (gate.onDrag(change.position.y, dy, change.uptimeMillis)) {
                    1 -> onPullDown()
                    -1 -> onPullUp()
                }
                if (!change.pressed) break
            }
        }
    }
}
