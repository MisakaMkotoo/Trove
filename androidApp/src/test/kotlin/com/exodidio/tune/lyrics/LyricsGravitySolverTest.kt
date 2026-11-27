package com.exodidio.tune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricsGravitySolverTest {
    @Test
    fun blocksFallAndStackWithoutOverlappingFloor() {
        val solver = LyricsGravitySolver(maxBlocks = 24)
        solver.spawnBlock(
            lineIndex = 0,
            text = "First line",
            startX = 40f,
            startY = 80f,
            width = 240f,
            height = 48f,
            viewportWidth = 800f,
        )
        solver.spawnBlock(
            lineIndex = 1,
            text = "Second line",
            startX = 45f,
            startY = 60f,
            width = 230f,
            height = 48f,
            viewportWidth = 800f,
        )

        repeat(200) {
            solver.step(dtSec = 0.016f, width = 800f, height = 600f)
        }

        val first = solver.blocks[0]
        val second = solver.blocks[1]
        assertTrue(first.y + first.height <= 600.5f)
        assertTrue(second.y + second.height <= 600.5f)
        val stackedCleanly = second.y + second.height <= first.y + 2f ||
            first.y + first.height <= second.y + 2f
        val separatedHorizontally = abs(second.x - first.x) >= 180f
        assertTrue(stackedCleanly || separatedHorizontally)
    }

    @Test
    fun blocksFollowTiltedGravityDirectionFromStart() {
        val solver = LyricsGravitySolver(maxBlocks = 24)
        // Phone tilted strongly to the left: gravity pulls left and down.
        solver.setGravity(axPxPerSec2 = -900f, ayPxPerSec2 = 900f)
        solver.spawnBlock(
            lineIndex = 0,
            text = "Tilted fall",
            startX = 400f,
            startY = 50f,
            width = 160f,
            height = 40f,
            viewportWidth = 800f,
        )

        repeat(90) {
            solver.step(dtSec = 0.016f, width = 800f, height = 600f)
        }

        val block = solver.blocks.first()
        assertTrue("Expected block to drift toward left wall under left gravity, x=${block.x}", block.x < 120f)
        assertTrue("Expected block to fall toward bottom, y=${block.y}", block.y > 400f)
    }

    @Test
    fun floorStaticFrictionHoldsGentleTiltButSteepTiltSlides() {
        val solver = LyricsGravitySolver(maxBlocks = 24)
        solver.setGravity(0f, 1000f)
        solver.spawnBlock(
            lineIndex = 0,
            text = "Friction block",
            startX = 320f,
            startY = 540f,
            width = 160f,
            height = 40f,
            viewportWidth = 800f,
        )
        repeat(80) { solver.step(0.016f, 800f, 600f) }
        assertTrue(solver.blocks.first().settled)
        val settledX = solver.blocks.first().x

        // Gentle tilt within static friction cone (|gx| / gy = 0.15 < muStaticWall = 0.34).
        solver.setGravity(axPxPerSec2 = 150f, ayPxPerSec2 = 1000f)
        repeat(60) { solver.step(0.016f, 800f, 600f) }
        assertTrue(
            "Expected static friction to prevent significant sliding, deltaX=${solver.blocks.first().x - settledX}",
            abs(solver.blocks.first().x - settledX) < 12f,
        )

        // Steep tilt overcoming static friction (|gx| / gy = 0.85 > muStaticWall = 0.34).
        solver.setGravity(axPxPerSec2 = 850f, ayPxPerSec2 = 1000f)
        repeat(80) { solver.step(0.016f, 800f, 600f) }
        assertTrue(
            "Expected steep tilt to slide block rightward, x=${solver.blocks.first().x}",
            solver.blocks.first().x > settledX + 120f,
        )
    }

    @Test
    fun elasticBounceReboundsUpwardOnFastFloorImpact() {
        val solver = LyricsGravitySolver(maxBlocks = 24)
        solver.setGravity(0f, 1200f)
        solver.spawnBlock(
            lineIndex = 0,
            text = "Bouncy line",
            startX = 300f,
            startY = 100f,
            width = 180f,
            height = 40f,
            viewportWidth = 800f,
        )

        var sawUpwardRebound = false
        var wasFallingFast = false
        repeat(120) {
            solver.step(0.016f, 800f, 600f)
            val vy = solver.blocks.first().vy
            if (vy > 300f) wasFallingFast = true
            if (wasFallingFast && vy < -40f) {
                sawUpwardRebound = true
            }
        }
        assertTrue("Expected elastic rebound (vy < 0) after high-speed floor impact", sawUpwardRebound)
    }

    @Test
    fun offCenterStackingProducesNaturalRotationAndTipping() {
        val solver = LyricsGravitySolver(maxBlocks = 24)
        solver.setGravity(0f, 1000f)
        // Short lower line at left edge.
        solver.spawnBlock(
            lineIndex = 0,
            text = "Short",
            startX = 40f,
            startY = 520f,
            width = 100f,
            height = 40f,
            viewportWidth = 800f,
        )
        repeat(80) { solver.step(0.016f, 800f, 600f) }

        // Much wider upper line left-aligned: center of mass overhangs past the short block's right edge.
        solver.spawnBlock(
            lineIndex = 1,
            text = "Much longer second line overhanging right",
            startX = 40f,
            startY = 380f,
            width = 360f,
            height = 40f,
            viewportWidth = 800f,
        )
        repeat(140) { solver.step(0.016f, 800f, 600f) }

        val upper = solver.blocks[1]
        assertTrue(
            "Expected overhanging upper block to rotate or slide past short lower block, angle=${upper.angleDeg}, x=${upper.x}",
            abs(upper.angleDeg) > 1.5f || upper.x > 60f,
        )
    }

    @Test
    fun linearImpulseAndScatterWakeSettledBlocks() {
        val solver = LyricsGravitySolver(maxBlocks = 24)
        solver.spawnBlock(
            lineIndex = 0,
            text = "Line A",
            startX = 100f,
            startY = 520f,
            width = 200f,
            height = 48f,
            viewportWidth = 800f,
        )
        repeat(120) { solver.step(0.016f, 800f, 600f) }
        assertTrue(solver.blocks.first().settled)

        solver.applyLinearImpulse(dvxPxPerSec = 260f, dvyPxPerSec = -320f, angularNoiseFactor = 2f)
        assertFalse(solver.blocks.first().settled)
        assertTrue(solver.blocks.first().vy < -200f)

        solver.scatter(impulseX = 120f, impulseY = 900f)
        assertFalse(solver.blocks.first().settled)
        assertTrue(solver.blocks.first().vy < -400f)
    }

    @Test
    fun movingFloorDropsAndLiftsSettledPileCleanly() {
        val solver = LyricsGravitySolver(maxBlocks = 24)
        solver.setGravity(0f, 1000f)
        solver.spawnBlock(0, "Bottom block", 200f, 300f, 180f, 40f, 800f)
        solver.spawnBlock(1, "Top block", 210f, 220f, 160f, 40f, 800f)

        // Settle onto raised UI floor at height = 420f.
        repeat(150) { solver.step(0.016f, 800f, 420f) }
        assertTrue(solver.blocks.all { it.settled })
        val raisedBottomY = solver.blocks[0].y
        assertTrue("Expected bottom block on 420f floor, y=$raisedBottomY", raisedBottomY in 370f..380f)

        // UI controls slide down smoothly: floor drops from 420f -> 600f.
        assertTrue(solver.onFloorBoundsChanging(600f))
        for (stepIdx in 1..12) {
            val h = 420f + (180f * stepIdx / 12f)
            solver.step(0.016f, 800f, h)
        }
        repeat(120) { solver.step(0.016f, 800f, 600f) }
        assertTrue(
            "Expected blocks to fall with descending floor to ~556f, y=${solver.blocks[0].y}",
            solver.blocks[0].y > 545f && solver.blocks[0].y + solver.blocks[0].height <= 600f,
        )
        assertTrue(solver.blocks[1].y < solver.blocks[0].y)

        // UI controls slide back up: floor rises from 600f -> 420f.
        assertTrue(solver.onFloorBoundsChanging(420f))
        for (stepIdx in 1..12) {
            val h = 600f - (180f * stepIdx / 12f)
            solver.step(0.016f, 800f, h)
        }
        repeat(100) { solver.step(0.016f, 800f, 420f) }
        assertTrue(
            "Expected bottom block to ride up with rising floor <= 420f, bottom=${solver.blocks[0].y + solver.blocks[0].height}",
            solver.blocks[0].y + solver.blocks[0].height <= 420.5f && solver.blocks[0].y >= 360f,
        )
        assertTrue(
            "Expected top block to stay stacked above bottom block, topBottom=${solver.blocks[1].y + solver.blocks[1].height}, bottomY=${solver.blocks[0].y}",
            solver.blocks[1].y + solver.blocks[1].height <= solver.blocks[0].y + 2f,
        )
    }

    @Test
    fun capsMaximumBlockCount() {
        val solver = LyricsGravitySolver(maxBlocks = 4)
        for (i in 0 until 8) {
            solver.spawnBlock(i, "Line $i", 20f, 40f, 160f, 40f, 800f)
        }
        assertEquals(4, solver.blocks.size)
        assertEquals(4, solver.blocks.first().lineIndex)
    }
}
