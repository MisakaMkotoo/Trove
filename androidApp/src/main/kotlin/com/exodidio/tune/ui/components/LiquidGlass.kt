package com.exodidio.tune.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import com.exodidio.tune.ui.theme.TuneColors

/** 玻璃/控件描边宽度：从 1dp 降到约 1 物理像素级别。 */
internal val GlassBorderWidth = 0.5.dp

/**
 * Apple 标准材质：一层亮度介于黑白之间的半透明灰度填充 + 模糊。
 * 浅色外观用浅灰（白底略暗、深底提亮），深色外观用深灰（黑底提亮、白底压暗）。
 * 填充不能太实，否则模糊会被盖住、看起来像纯色块。
 */
private val LightGlassMaterial = Color(0xFFF2F2F3).copy(alpha = 0.45f)
private val DarkGlassMaterial = Color(0xFF2C2C2E).copy(alpha = 0.45f)

/** The shared backdrop treatment for the persistent navigation and page header. */
fun Modifier.liquidGlassBackground(
    hazeState: HazeState?,
    colors: TuneColors,
    hazeBlurRadius: Dp = 24.dp,
    glassTint: Color? = null,
): Modifier {
    val material = if (colors.background.luminance() < 0.5f) DarkGlassMaterial else LightGlassMaterial
    val fill = glassTint ?: material
    return if (hazeState == null) {
        background(fill)
    } else {
        hazeEffect(hazeState) {
            // Downsampling makes small glass surfaces look pixelated after upscaling.
            inputScale = HazeInputScale.None
            blurEffect {
                blurRadius = hazeBlurRadius
                colorEffects = listOf(HazeColorEffect.tint(fill))
            }
        }.background(fill)
    }
}
