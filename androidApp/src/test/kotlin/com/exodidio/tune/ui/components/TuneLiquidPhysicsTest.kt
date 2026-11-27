package com.exodidio.tune.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.exodidio.tune.ui.navigation.computeDockDragFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneLiquidPhysicsTest {

    @Test
    fun `applyGlassDragDisplacement matches 0_05 compliance and saturates smoothly`() {
        val minDim = 120f
        assertEquals(0f, applyGlassDragDisplacement(0f, minDim), 1e-4f)
        assertEquals(0f, applyGlassDragDisplacement(Float.NaN, minDim), 1e-4f)
        assertEquals(0f, applyGlassDragDisplacement(Float.POSITIVE_INFINITY, minDim), 1e-4f)

        // Small drag uses ~0.05 initial slope (20:1 sticky anchor resistance)
        val small = applyGlassDragDisplacement(40f, minDim)
        assertEquals(2.0f, small, 0.05f)

        val large = applyGlassDragDisplacement(20_000f, minDim)
        assertTrue(large <= minDim && large > minDim * 0.98f)
        assertEquals(-large, applyGlassDragDisplacement(-20_000f, minDim), 1e-3f)
    }

    @Test
    fun `dampedLiquidDisplacement saturates symmetrically within max bounds`() {
        val maxDisp = 18f
        assertEquals(0f, dampedLiquidDisplacement(0f, maxDisp), 1e-4f)
        assertEquals(0f, dampedLiquidDisplacement(Float.NaN, maxDisp), 1e-4f)

        val small = dampedLiquidDisplacement(6f, maxDisp)
        val large = dampedLiquidDisplacement(1800f, maxDisp)
        assertTrue(small > 0f && small < 6f * 0.06f)
        assertTrue(small < large)
        assertTrue(large <= maxDisp && large > maxDisp * 0.95f)

        val negLarge = dampedLiquidDisplacement(-1800f, maxDisp)
        assertEquals(-large, negLarge, 1e-3f)
    }

    @Test
    fun `computeGlassDragScale stretches along drag angle and preserves orthogonal squeeze`() {
        val resting = computeGlassDragScale(
            rawOffsetPx = Offset.Zero,
            widthPx = 120f,
            heightPx = 120f,
            pressProgress = 0f,
            pressedScale = 1.15f,
        )
        assertEquals(1f, resting.scaleX, 1e-4f)
        assertEquals(1f, resting.scaleY, 1e-4f)

        val horizontal = computeGlassDragScale(
            rawOffsetPx = Offset(180f, 0f),
            widthPx = 120f,
            heightPx = 120f,
            pressProgress = 1f,
            pressedScale = 1.15f,
        )
        assertTrue(horizontal.scaleX > 1.15f)
        assertTrue(horizontal.scaleY < 1.15f)

        val vertical = computeGlassDragScale(
            rawOffsetPx = Offset(0f, 180f),
            widthPx = 120f,
            heightPx = 120f,
            pressProgress = 1f,
            pressedScale = 1.15f,
        )
        assertTrue(vertical.scaleY > 1.15f)
        assertTrue(vertical.scaleX < 1.15f)
    }

    @Test
    fun `liquidSquashStretch expands on press and stretches along drag axis`() {
        val resting = liquidSquashStretch(
            dampedOffsetPx = Offset.Zero,
            maxDisplacementPx = 20f,
            pressProgress = 0f,
            pressedScale = 1.15f,
        )
        assertEquals(1f, resting.scaleX, 1e-4f)
        assertEquals(1f, resting.scaleY, 1e-4f)

        val pressedHorizontal = liquidSquashStretch(
            dampedOffsetPx = Offset(18f, 0f),
            maxDisplacementPx = 20f,
            pressProgress = 1f,
            pressedScale = 1.15f,
        )
        assertTrue(pressedHorizontal.scaleX > 1.15f)
        assertTrue(pressedHorizontal.scaleY < 1.15f)
    }

    @Test
    fun `loupeSoftClamp is identity inside bounds and C1 smooth outside`() {
        assertEquals(1.02f, loupeSoftClamp(1.02f, 0.90f, 1.10f), 1e-5f)
        assertEquals(1.10f, loupeSoftClamp(1.10f, 0.90f, 1.10f), 1e-5f)
        assertEquals(0.90f, loupeSoftClamp(0.90f, 0.90f, 1.10f), 1e-5f)

        val above = loupeSoftClamp(1.50f, 0.90f, 1.10f)
        val margin = (1.10f - 0.90f) / 3f
        assertTrue(above > 1.10f && above < 1.10f + margin)

        val below = loupeSoftClamp(0.50f, 0.90f, 1.10f)
        assertTrue(below < 0.90f && below > 0.90f - margin)
    }

    @Test
    fun `computeLoupeFlex stretches on acceleration and compresses on deceleration with leading-edge drift`() {
        val accelRight = computeLoupeFlex(
            accelerationX = 1200f,
            velocityX = 240f,
            liveWidthDp = 96f,
            liveHeightDp = 70f,
        )
        assertTrue(accelRight.scaleX > 1f)
        assertTrue(accelRight.scaleY < 1f)
        assertTrue(accelRight.driftXDp < 0f)

        val decelRight = computeLoupeFlex(
            accelerationX = -1200f,
            velocityX = 240f,
            liveWidthDp = 96f,
            liveHeightDp = 70f,
        )
        assertTrue(decelRight.scaleX < 1f)
        assertTrue(decelRight.scaleY > 1f)
        assertTrue(decelRight.driftXDp > 0f)

        val idle = computeLoupeFlex(
            accelerationX = 0f,
            velocityX = 0f,
            liveWidthDp = 96f,
            liveHeightDp = 70f,
        )
        assertEquals(1f, idle.scaleX, 1e-4f)
        assertEquals(1f, idle.scaleY, 1e-4f)
        assertEquals(0f, idle.driftXDp, 1e-4f)
    }

    @Test
    fun `TuneGlassSpring converges analytically across underdamped critical and overdamped specs`() {
        listOf(
            TuneGlassSpringSpec(dampingRatio = 0.56f, response = 0.30f),
            TuneGlassSpringSpec(dampingRatio = 1.00f, response = 0.25f),
            TuneGlassSpringSpec(dampingRatio = 1.35f, response = 0.35f),
        ).forEach { spec ->
            val spring = TuneGlassSpring(0f, valueTolerance = 0.01f, velocityTolerance = 0.1f)
            spring.target(100f, spec)
            var active = true
            repeat(180) {
                if (active) active = spring.advance(1f / 60f)
            }
            assertFalse("Spring $spec failed to settle", active)
            assertEquals(100f, spring.value, 0.05f)
        }
    }

    @Test
    fun `TuneNavigationTabMotion enforces 220ms minimum lift hold before unlifting`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val motion = TuneNavigationTabMotion(
            scope = scope,
            density = Density(3f),
            isTopBar = false,
            reduceMotion = false,
        )
        motion.snapTo(centerPx = 150f, widthPx = 240f, trackHeightPx = 162f)
        motion.press()
        motion.moveTo(centerPx = 450f, widthPx = 240f, trackHeightPx = 162f)
        motion.release()

        // Advance 100ms (< 220ms minimum lift): lift must stay elevated even near/at target
        repeat(6) { motion.advance(1f / 60f) }
        assertTrue("Expected lift to stay elevated before 220ms: ${motion.pressProgress}", motion.pressProgress > 0.5f)

        // Advance 900ms more: reaches target and unlifts cleanly
        repeat(60) { motion.advance(1f / 60f) }
        assertEquals(450f, motion.centerPx, 1f)
        assertEquals(0f, motion.pressProgress, 0.02f)
        assertEquals(1f, motion.scaleX, 0.02f)
        assertEquals(1f, motion.scaleY, 0.02f)
    }

    @Test
    fun `platter helpers compute UIKit puff and 4dp EaseOut drag bounds`() {
        val puff = computePlatterPuffSpec(widthPx = 1080f, heightPx = 186f, density = 3f)
        assertTrue(puff.scaleDeltaPerUnitProgress in 0.03f..0.06f)

        val maxOffset = 12f
        assertEquals(0f, computePlatterDragTranslationX(0f, 1080f, maxOffset), 1e-4f)
        val half = computePlatterDragTranslationX(540f, 1080f, maxOffset)
        val full = computePlatterDragTranslationX(2000f, 1080f, maxOffset)
        assertTrue(half > 6f && half < maxOffset)
        assertEquals(maxOffset, full, 1e-4f)
        assertEquals(-maxOffset, computePlatterDragTranslationX(-2000f, 1080f, maxOffset), 1e-4f)
    }

    @Test
    fun `computeDockDragFeedback detects left and right edge overdrag`() {
        val leftOver = computeDockDragFeedback(
            rawDeltaFromDown = Offset(-240f, 5f),
            rawPillTargetPx = -160f,
            maxIndicatorOffsetPx = 300f,
            maxFollowXPx = 6f,
            maxOverDragXPx = 18f,
            maxFollowYPx = 10f,
        )
        assertTrue(leftOver.normalizedOverDragX < -0.3f)
        assertTrue(leftOver.offsetPx.x < -6f)
        assertTrue(leftOver.offsetPx.y > 0f)

        val inside = computeDockDragFeedback(
            rawDeltaFromDown = Offset(40f, 0f),
            rawPillTargetPx = 120f,
            maxIndicatorOffsetPx = 300f,
            maxFollowXPx = 6f,
            maxOverDragXPx = 18f,
            maxFollowYPx = 10f,
        )
        assertEquals(0f, inside.normalizedOverDragX, 1e-4f)
        assertTrue(inside.offsetPx.x > 0f && inside.offsetPx.x < 6f)

        val rightOver = computeDockDragFeedback(
            rawDeltaFromDown = Offset(320f, -10f),
            rawPillTargetPx = 460f,
            maxIndicatorOffsetPx = 300f,
            maxFollowXPx = 6f,
            maxOverDragXPx = 18f,
            maxFollowYPx = 10f,
        )
        assertTrue(rightOver.normalizedOverDragX > 0.3f)
        assertTrue(rightOver.offsetPx.x > 6f)
        assertTrue(rightOver.offsetPx.y < 0f)
    }
}
