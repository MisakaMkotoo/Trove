package com.exodidio.tune.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.exodidio.tune.ui.theme.TuneColors
import org.junit.Assert.assertEquals
import org.junit.Test

class TuneTrackSliderTest {
    private val colors = TuneColors(
        background = Color.Unspecified,
        playerBackdrop = Color.Unspecified,
        card = Color.Unspecified,
        glass = Color.Unspecified,
        glassOpaque = Color.Unspecified,
        glassElevated = Color.Unspecified,
        sliderInactive = Color.Unspecified,
        buttonSecondary = Color.Unspecified,
        textFieldClear = Color.Unspecified,
        borderGlass = Color.Unspecified,
        textMain = Color.Unspecified,
        textMuted = Color.Unspecified,
        primary = Color.Unspecified,
        onPrimary = Color.White,
        foregroundSubtle = Color.White.copy(alpha = 0.46f),
        success = Color.Unspecified,
        navigationActive = Color.Unspecified,
    )

    @Test
    fun filledTrackColorMatchesItsRestingAndInteractingStates() {
        assertEquals(
            lerp(colors.foregroundSubtle, colors.onPrimary, 0.5f),
            sliderFilledTrackColor(colors, isInteracting = false),
        )
        assertEquals(colors.onPrimary, sliderFilledTrackColor(colors, isInteracting = true))
    }

    @Test
    fun knobInertiaIgnoresSmallOrReversedReleaseVelocity() {
        assertEquals(0.5f, computeKnobInertiaTarget(0.5f, 0.2f, 0.1f, 0f..1f), 1e-4f)
        assertEquals(0.5f, computeKnobInertiaTarget(0.5f, 0.2f, -1.8f, 0f..1f), 1e-4f)
    }

    @Test
    fun knobInertiaCoastsGentlyAndClampsToRange() {
        val target = computeKnobInertiaTarget(0.5f, 0.2f, 1.0f, 0f..1f)
        assertEquals(0.53f, target, 1e-4f)
        assertEquals(1.0f, computeKnobInertiaTarget(0.99f, 0.2f, 3.0f, 0f..1f), 1e-4f)
    }

    @Test
    fun bufferingBreathPulsePeaksSmoothlyAtMidCycle() {
        assertEquals(0f, bufferingBreathProgress(0f), 1e-4f)
        assertEquals(1f, bufferingBreathProgress(0.5f), 1e-4f)
        assertEquals(0f, bufferingBreathProgress(1f), 1e-4f)

        val dimFill = sliderBufferingFilledColor(colors.onPrimary, colors.onPrimary, breath = 0f)
        val brightFill = sliderBufferingFilledColor(colors.onPrimary, colors.onPrimary, breath = 1f)
        assertEquals(0.48f, dimFill.alpha, 1e-2f)
        assertEquals(1.0f, brightFill.alpha, 1e-2f)

        val dimTrack = sliderBufferingInactiveOverlayColor(colors.onPrimary, breath = 0f)
        val brightTrack = sliderBufferingInactiveOverlayColor(colors.onPrimary, breath = 1f)
        assertEquals(0.04f, dimTrack.alpha, 1e-2f)
        assertEquals(0.24f, brightTrack.alpha, 1e-2f)
    }
}
