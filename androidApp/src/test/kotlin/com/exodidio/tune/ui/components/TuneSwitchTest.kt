package com.exodidio.tune.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneSwitchTest {
    @Test
    fun tapReleaseAlwaysFlipsCurrentState() {
        assertTrue(resolveLiquidToggleReleaseChecked(didDrag = false, targetFraction = 0.05f, currentChecked = false))
        assertFalse(resolveLiquidToggleReleaseChecked(didDrag = false, targetFraction = 0.95f, currentChecked = true))
    }

    @Test
    fun dragReleaseCommitsStateBasedOnHalfwayThreshold() {
        assertTrue(resolveLiquidToggleReleaseChecked(didDrag = true, targetFraction = 0.5f, currentChecked = false))
        assertFalse(resolveLiquidToggleReleaseChecked(didDrag = true, targetFraction = 0.49f, currentChecked = true))
        assertFalse(resolveLiquidToggleReleaseChecked(didDrag = true, targetFraction = 0.2f, currentChecked = false))
        assertTrue(resolveLiquidToggleReleaseChecked(didDrag = true, targetFraction = 0.8f, currentChecked = true))
    }

    @Test
    fun dragFractionTracksDirectionAndClampsToUnitRange() {
        assertEquals(0.5f, applyLiquidToggleDragFraction(0f, dragDeltaPx = 10f, dragWidthPx = 20f, isLtr = true), 0.001f)
        assertEquals(1f, applyLiquidToggleDragFraction(0.8f, dragDeltaPx = 10f, dragWidthPx = 20f, isLtr = true), 0.001f)
        assertEquals(0f, applyLiquidToggleDragFraction(0.1f, dragDeltaPx = -10f, dragWidthPx = 20f, isLtr = true), 0.001f)
        assertEquals(0.5f, applyLiquidToggleDragFraction(0f, dragDeltaPx = -10f, dragWidthPx = 20f, isLtr = false), 0.001f)
    }
}
