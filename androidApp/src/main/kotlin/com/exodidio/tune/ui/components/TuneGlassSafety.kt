package com.exodidio.tune.ui.components

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import com.exodidio.tune.settings.ControlStyle
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule

val LocalControlStyle = staticCompositionLocalOf { ControlStyle.Frosted }
val LocalLiquidGlassBlurDp = staticCompositionLocalOf { com.exodidio.tune.settings.DefaultLiquidGlassBlurDp }
val LocalTuneBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
internal fun tuneGlassAvailable(): Boolean {
    if (LocalControlStyle.current != ControlStyle.LiquidGlass) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (!LocalView.current.isHardwareAccelerated) return false
    return LocalTuneBackdrop.current != null
}

@Composable
internal fun tuneGlassBackdrop(): Backdrop? =
    if (tuneGlassAvailable()) LocalTuneBackdrop.current else null

internal fun safeGlassProgress(value: Float): Float =
    if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(0f, 1f)

internal fun Float.finiteOrZero(): Float =
    if (isNaN() || isInfinite()) 0f else this

internal fun Shape.supportsKyantLens(): Boolean =
    this is CornerBasedShape || this is Capsule
