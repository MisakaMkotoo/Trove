package com.exodidio.tune.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import com.exodidio.tune.settings.ThemeAccentColor
import com.exodidio.tune.settings.ThemeMode

data class TuneColors(
    val background: Color,
    val playerBackdrop: Color,
    val card: Color,
    val glass: Color,
    val glassOpaque: Color,
    val glassElevated: Color,
    val sliderInactive: Color,
    val buttonSecondary: Color,
    val textFieldClear: Color,
    val borderGlass: Color,
    val textMain: Color,
    val textMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val foregroundSubtle: Color,
    val success: Color,
    val navigationActive: Color,
    // Audio quality badge accents mirror the desktop quality palette.
    val qualityHiRes: Color = Color(0xFFF59E0B),
    val qualityDsd: Color = Color(0xFFE879F9),
    val qualityDsdSurface: Color = Color(0xFFD946EF),
)

private val LightColors = TuneColors(
    background = Color.White,
    playerBackdrop = Color(0xFF18181B),
    card = Color.White,
    glass = Color.White.copy(alpha = 0.4f),
    glassOpaque = Color(0xFFF2F2F3),
    glassElevated = Color.White.copy(alpha = 0.90f),
    // The fullscreen player sits over artwork, so this remains translucent to
    // let that backdrop show through instead of reading as a solid gray bar.
    sliderInactive = Color.White.copy(alpha = 0.10f),
    buttonSecondary = Color(0xFFE4E4E7),
    textFieldClear = Color(0xFFD4D4D8),
    borderGlass = Color.Black.copy(alpha = 0.05f),
    textMain = Color(0xFF0A0A0A),
    textMuted = Color(0xFF52525B),
    primary = Color(0xFFE11D48),
    onPrimary = Color.White,
    foregroundSubtle = Color.White.copy(alpha = 0.46f),
    success = Color(0xFF16A34A),
    navigationActive = Color.Black.copy(alpha = 0.08f),
    qualityHiRes = Color(0xFFF59E0B),
    qualityDsd = Color(0xFFE879F9),
    qualityDsdSurface = Color(0xFFD946EF),
)

private val DarkColors = TuneColors(
    background = Color(0xFF18181B),
    playerBackdrop = Color(0xFF0A0A0A),
    card = Color(0xFF27272A),
    glass = Color(0xFF232326).copy(alpha = 0.4f),
    glassOpaque = Color(0xFF2C2C2E),
    glassElevated = Color(0xFF37373C).copy(alpha = 0.40f),
    sliderInactive = Color.White.copy(alpha = 0.10f),
    buttonSecondary = Color(0xFF52525B),
    textFieldClear = Color(0xFF3F3F46),
    borderGlass = Color.White.copy(alpha = 0.05f),
    textMain = Color.White,
    textMuted = Color(0xFFA1A1AA),
    primary = Color(0xFFE11D48),
    onPrimary = Color.White,
    foregroundSubtle = Color.White.copy(alpha = 0.46f),
    success = Color(0xFF4ADE80),
    navigationActive = Color.Black.copy(alpha = 0.40f),
    qualityHiRes = Color(0xFFF59E0B),
    qualityDsd = Color(0xFFE879F9),
    qualityDsdSurface = Color(0xFFD946EF),
)

private val AmoledColors = TuneColors(
    background = Color(0xFF000000),
    playerBackdrop = Color(0xFF000000),
    card = Color(0xFF000000),
    glass = Color(0xFF000000).copy(alpha = 0.4f),
    glassOpaque = Color(0xFF000000),
    glassElevated = Color(0xFF000000).copy(alpha = 0.40f),
    sliderInactive = Color.White.copy(alpha = 0.10f),
    buttonSecondary = Color(0xFF52525B),
    textFieldClear = Color(0xFF3F3F46),
    borderGlass = Color.White.copy(alpha = 0.05f),
    textMain = Color.White,
    textMuted = Color(0xFFA1A1AA),
    primary = Color(0xFFE11D48),
    onPrimary = Color.White,
    foregroundSubtle = Color.White.copy(alpha = 0.46f),
    success = Color(0xFF4ADE80),
    navigationActive = Color.Black.copy(alpha = 0.40f),
    qualityHiRes = Color(0xFFF59E0B),
    qualityDsd = Color(0xFFE879F9),
    qualityDsdSurface = Color(0xFFD946EF),
)

val LocalTuneColors = staticCompositionLocalOf { LightColors }

@Composable
fun rememberAnimatedTuneColors(
    target: TuneColors,
    animate: Boolean = true,
    durationMillis: Int = 380,
): TuneColors {
    if (!animate) return target
    val spec = remember(durationMillis) { tween<Color>(durationMillis, easing = FastOutSlowInEasing) }
    val background by animateColorAsState(target.background, spec, label = "tune-background")
    val playerBackdrop by animateColorAsState(target.playerBackdrop, spec, label = "tune-player-backdrop")
    val card by animateColorAsState(target.card, spec, label = "tune-card")
    val glass by animateColorAsState(target.glass, spec, label = "tune-glass")
    val glassOpaque by animateColorAsState(target.glassOpaque, spec, label = "tune-glass-opaque")
    val glassElevated by animateColorAsState(target.glassElevated, spec, label = "tune-glass-elevated")
    val sliderInactive by animateColorAsState(target.sliderInactive, spec, label = "tune-slider-inactive")
    val buttonSecondary by animateColorAsState(target.buttonSecondary, spec, label = "tune-button-secondary")
    val textFieldClear by animateColorAsState(target.textFieldClear, spec, label = "tune-textfield-clear")
    val borderGlass by animateColorAsState(target.borderGlass, spec, label = "tune-border-glass")
    val textMain by animateColorAsState(target.textMain, spec, label = "tune-text-main")
    val textMuted by animateColorAsState(target.textMuted, spec, label = "tune-text-muted")
    val primary by animateColorAsState(target.primary, spec, label = "tune-primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "tune-on-primary")
    val foregroundSubtle by animateColorAsState(target.foregroundSubtle, spec, label = "tune-foreground-subtle")
    val success by animateColorAsState(target.success, spec, label = "tune-success")
    val navigationActive by animateColorAsState(target.navigationActive, spec, label = "tune-navigation-active")
    return TuneColors(
        background = background,
        playerBackdrop = playerBackdrop,
        card = card,
        glass = glass,
        glassOpaque = glassOpaque,
        glassElevated = glassElevated,
        sliderInactive = sliderInactive,
        buttonSecondary = buttonSecondary,
        textFieldClear = textFieldClear,
        borderGlass = borderGlass,
        textMain = textMain,
        textMuted = textMuted,
        primary = primary,
        onPrimary = onPrimary,
        foregroundSubtle = foregroundSubtle,
        success = success,
        navigationActive = navigationActive,
        qualityHiRes = target.qualityHiRes,
        qualityDsd = target.qualityDsd,
        qualityDsdSurface = target.qualityDsdSurface,
    )
}

@Composable
fun TuneTheme(
    themeMode: ThemeMode,
    accentColor: ThemeAccentColor = ThemeAccentColor.Red,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.Amoled -> true
    }
    val primary = Color(if (darkTheme) accentColor.primaryDark else accentColor.primaryLight)
    val baseColors = when (themeMode) {
        ThemeMode.Amoled -> AmoledColors
        else -> if (darkTheme) DarkColors else LightColors
    }
    val targetColors = baseColors.copy(primary = primary)
    val colors = rememberAnimatedTuneColors(targetColors)
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.background,
            surface = colors.glass,
            onBackground = colors.textMain,
            onSurface = colors.textMain,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.background,
            surface = colors.glass,
            onBackground = colors.textMain,
            onSurface = colors.textMain,
        )
    }

    CompositionLocalProvider(LocalTuneColors provides colors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
