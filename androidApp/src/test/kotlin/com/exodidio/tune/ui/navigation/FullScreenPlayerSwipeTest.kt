package com.exodidio.tune.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenPlayerSwipeTest {
    @Test
    fun rejectsVerticalGesturesEvenWhenTheyContainHorizontalJitter() {
        assertFalse(
            shouldDispatchFullScreenSwipe(
                horizontalDistancePx = 80f,
                verticalDistancePx = 140f,
                thresholdPx = 52f,
                velocityPxPerMs = 2f,
                velocityThresholdPxPerMs = 1.2f,
                velocityMinimumPx = 28f,
            ),
        )
    }

    @Test
    fun requiresMoreThanAShortHorizontalFlick() {
        assertFalse(
            shouldDispatchFullScreenSwipe(
                horizontalDistancePx = 18f,
                verticalDistancePx = 2f,
                thresholdPx = 52f,
                velocityPxPerMs = 2f,
                velocityThresholdPxPerMs = 1.2f,
                velocityMinimumPx = 28f,
            ),
        )
    }

    @Test
    fun acceptsDeliberateHorizontalSwipe() {
        assertTrue(
            shouldDispatchFullScreenSwipe(
                horizontalDistancePx = -64f,
                verticalDistancePx = 8f,
                thresholdPx = 52f,
                velocityPxPerMs = -0.4f,
                velocityThresholdPxPerMs = 1.2f,
                velocityMinimumPx = 28f,
            ),
        )
    }

    @Test
    fun dismissDragExpansionProgressTracksVerticalPullWithinLiveBounds() {
        assertEquals(1f, dismissDragExpansionProgress(dragDistancePx = 0f, dismissTravelPx = 400f), 0.001f)
        assertEquals(1f, dismissDragExpansionProgress(dragDistancePx = -30f, dismissTravelPx = 400f), 0.001f)
        assertEquals(0.75f, dismissDragExpansionProgress(dragDistancePx = 100f, dismissTravelPx = 400f), 0.001f)
        assertEquals(0f, dismissDragExpansionProgress(dragDistancePx = 500f, dismissTravelPx = 400f), 0.001f)
        assertEquals(1f, dismissDragExpansionProgress(dragDistancePx = 100f, dismissTravelPx = 0f), 0.001f)
        assertEquals(0.50f, dismissDragExpansionProgress(dragDistancePx = 100f, dismissTravelPx = 400f, startProgress = 0.75f), 0.001f)
    }

    @Test
    fun miniPlayerStaysHiddenUntilMorphReachesRestingPillThreshold() {
        assertEquals(0f, miniPlayerMorphAlpha(1f), 0.001f)
        assertEquals(0f, miniPlayerMorphAlpha(0.05f), 0.001f)
        assertEquals(0f, miniPlayerMorphAlpha(MiniPlayerMorphCrossfadeThreshold), 0.001f)
        assertEquals(0.5f, miniPlayerMorphAlpha(MiniPlayerMorphCrossfadeThreshold / 2f), 0.001f)
        assertEquals(1f, miniPlayerMorphAlpha(0f), 0.001f)
    }

    @Test
    fun shellCornerRadiusStaysAtMiniPillThenSmoothlyMatchesDisplayCorner() {
        val peak = 42f
        assertEquals(PlayerRestCornerDp, playerShellCornerRadiusDp(0f, peak), 0.001f)
        assertEquals(PlayerRestCornerDp, playerShellCornerRadiusDp(PlayerCornerRiseStart, peak), 0.001f)
        val mid = playerShellCornerRadiusDp((PlayerCornerRiseStart + PlayerCornerRiseEnd) * 0.5f, peak)
        assertEquals((PlayerRestCornerDp + peak) * 0.5f, mid, 0.001f)
        assertEquals(peak, playerShellCornerRadiusDp(PlayerCornerRiseEnd, peak), 0.001f)
        assertEquals(peak, playerShellCornerRadiusDp(1f, peak), 0.001f)

        // Small half-height clamps the corner radius so compact frames remain capsule-shaped.
        assertEquals(18f, playerShellCornerRadiusDp(0.9f, peak, rectHalfHeightDp = 18f), 0.001f)
        // Smaller tablet/screen corner transitions smoothly down from 24dp to peakDp.
        assertEquals(18f, playerShellCornerRadiusDp(1f, peakDp = 18f), 0.001f)
        // Invalid inputs fall back safely.
        assertEquals(PlayerRestCornerDp, playerShellCornerRadiusDp(Float.NaN, Float.NaN), 0.001f)
    }

    @Test
    fun morphSmoothCornerShapeValueEqualityReflectsGeometryParameters() {
        val a = MorphSmoothCornerShape(10f, 20f, 110f, 120f, 24f)
        val b = MorphSmoothCornerShape(10f, 20f, 110f, 120f, 24f)
        val c = MorphSmoothCornerShape(10f, 20f, 110f, 120f, 32f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }

    @Test
    fun navigationBarRetreatsProportionallyWithPlayerExpansion() {
        assertEquals(0f, navigationBarRetreatOffsetPx(0f, 62f, 24f, 16f), 0.001f)
        assertEquals(51f, navigationBarRetreatOffsetPx(0.5f, 62f, 24f, 16f), 0.001f)
        assertEquals(102f, navigationBarRetreatOffsetPx(1f, 62f, 24f, 16f), 0.001f)
        assertEquals(0f, navigationBarRetreatOffsetPx(-0.2f, 62f, 24f, 16f), 0.001f)
        assertEquals(102f, navigationBarRetreatOffsetPx(1.5f, 62f, 24f, 16f), 0.001f)
        assertEquals(0f, navigationBarRetreatOffsetPx(Float.NaN, 62f, 24f, 16f), 0.001f)
    }
}
