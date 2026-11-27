package com.exodidio.tune.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
internal fun rememberPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val target = if (pressed && !reduceMotion) pressedScale else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = 0.72f,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "press-scale",
    )
    return scale
}

@Composable
internal fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val scale = rememberPressScale(interactionSource, pressedScale)
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 本地磁盘缩略图首次异步解码完成时做短促淡入；
 * 若组合首帧已命中内存缓存（loaded == true），则保持 1f 不闪烁。
 */
@Composable
internal fun rememberArtworkFadeAlpha(
    cacheKey: Any?,
    loaded: Boolean,
    durationMillis: Int = 160,
): Float {
    val startedWithoutArtwork = remember(cacheKey) { !loaded }
    val reduceMotion = LocalReduceMotion.current
    val target = if (loaded || !startedWithoutArtwork || reduceMotion) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion || !startedWithoutArtwork) {
            snap()
        } else {
            tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        },
        label = "artwork-fade-alpha",
    )
    return alpha
}
