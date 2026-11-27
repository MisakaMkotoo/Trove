package com.exodidio.tune.lyrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

internal data class GravityLyricBlock(
    val id: Long,
    val lineIndex: Int,
    val text: String,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val width: Float,
    val height: Float,
    var angleDeg: Float,
    var angularVel: Float,
    var settled: Boolean = false,
) {
    internal var cx: Float = x + width * 0.5f
    internal var cy: Float = y + height * 0.5f
    internal val halfW: Float = width * 0.5f
    internal val halfH: Float = height * 0.5f
    internal var angleRad: Float = Math.toRadians(angleDeg.toDouble()).toFloat()
    internal var omegaRad: Float = Math.toRadians(angularVel.toDouble()).toFloat()
    internal val invMass: Float
    internal val invInertia: Float
    internal var sleepTimer: Float = 0f

    init {
        val mass = ((width * height) / 10_000f).coerceIn(0.45f, 6.0f)
        invMass = 1f / mass
        val inertia = (mass * (width * width + height * height) / 12f).coerceAtLeast(1f)
        invInertia = 1f / inertia
    }

    internal fun syncPublicFromInternal() {
        x = cx - halfW
        y = cy - halfH
        angleDeg = (angleRad * RadToDeg)
        angularVel = (omegaRad * RadToDeg)
    }
}

private const val RadToDeg = (180.0 / PI).toFloat()
private const val DegToRad = (PI / 180.0).toFloat()

/**
 * 2D rigid-body physics simulation for finished lyric pills inside the lyrics container.
 * Uses oriented bounding boxes (SAT + clipped contact manifolds), sequential impulses with
 * Coulomb static/dynamic friction, restitution, rotational torque, and continuous 2D gravity.
 */
internal class LyricsGravitySolver(
    private val maxBlocks: Int = 48,
    pixelsPerDp: Float = 1f,
) {
    private val mutableBlocks = mutableListOf<GravityLyricBlock>()
    private var nextId = 1L

    private val scale = pixelsPerDp.coerceAtLeast(0.5f)
    private var gravityX = 0f
    private var gravityY = 920f * scale
    private var lastSimHeight = 0f

    private val contacts = ArrayList<Contact>(64)

    val blocks: List<GravityLyricBlock>
        get() = mutableBlocks

    fun hasAwakeBlocks(): Boolean = mutableBlocks.any { !it.settled }

    fun onFloorBoundsChanging(height: Float): Boolean {
        if (mutableBlocks.isEmpty() || height <= 0f) return false
        if (lastSimHeight <= 0f || abs(height - lastSimHeight) > 0.25f) {
            wakeAll()
            return true
        }
        return false
    }

    fun setGravity(axPxPerSec2: Float, ayPxPerSec2: Float) {
        val maxG = 1_800f * scale
        val clampedX = axPxPerSec2.coerceIn(-maxG, maxG)
        val clampedY = ayPxPerSec2.coerceIn(-maxG, maxG)
        val delta = hypot(clampedX - gravityX, clampedY - gravityY)
        gravityX = clampedX
        gravityY = clampedY
        if (delta > 18f * scale) {
            wakeAll()
        }
    }

    /** Applies an instantaneous velocity impulse (e.g. from device linear acceleration / jolt). */
    fun applyLinearImpulse(dvxPxPerSec: Float, dvyPxPerSec: Float, angularNoiseFactor: Float = 0f) {
        if (mutableBlocks.isEmpty()) return
        val maxDv = 1_400f * scale
        val dx = dvxPxPerSec.coerceIn(-maxDv, maxDv)
        val dy = dvyPxPerSec.coerceIn(-maxDv, maxDv)
        if (hypot(dx, dy) < 1f) return
        for (i in mutableBlocks.indices) {
            val block = mutableBlocks[i]
            block.settled = false
            block.sleepTimer = 0f
            block.vx = (block.vx + dx).coerceIn(-1_800f * scale, 1_800f * scale)
            block.vy = (block.vy + dy).coerceIn(-1_800f * scale, 1_800f * scale)
            if (angularNoiseFactor > 0f) {
                val sign = if (((block.id + i) and 1L) == 0L) 1f else -1f
                block.omegaRad = (block.omegaRad + sign * angularNoiseFactor * (0.6f + (i % 5) * 0.15f))
                    .coerceIn(-12f, 12f)
            }
            block.syncPublicFromInternal()
        }
    }

    fun spawnBlock(
        lineIndex: Int,
        text: String,
        startX: Float,
        startY: Float,
        width: Float,
        height: Float,
        viewportWidth: Float,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || width <= 0f || height <= 0f) return
        if (mutableBlocks.any { it.lineIndex == lineIndex }) return
        if (mutableBlocks.size >= maxBlocks) {
            mutableBlocks.removeAt(0)
        }
        val wallPad = 4f * scale
        val safeMaxX = (viewportWidth - width - wallPad).coerceAtLeast(wallPad)
        val clampedX = startX.coerceIn(wallPad, safeMaxX)
        val block = GravityLyricBlock(
            id = nextId++,
            lineIndex = lineIndex,
            text = trimmed,
            x = clampedX,
            y = startY.coerceAtLeast(wallPad),
            vx = 0f,
            vy = 0f,
            width = width,
            height = height,
            angleDeg = 0f,
            angularVel = 0f,
            settled = false,
        )
        mutableBlocks.add(block)
        // Wake existing blocks near spawn height or underneath so fresh collisions react cleanly.
        wakeAll()
    }

    fun step(dtSec: Float, width: Float, height: Float) {
        val totalDt = dtSec.coerceIn(0f, 0.033f)
        if (totalDt <= 0f || width <= 0f || height <= 0f || mutableBlocks.isEmpty()) return

        val wallPad = 4f * scale
        val minY = wallPad
        val maxY = (height - wallPad).coerceAtLeast(minY + 16f)

        val prevHeight = lastSimHeight
        lastSimHeight = height
        if (prevHeight > 0f && abs(height - prevHeight) > 0.25f) {
            val deltaFloor = height - prevHeight
            wakeAll()
            if (deltaFloor < 0f) {
                liftSupportedPile(
                    prevFloorY = (prevHeight - wallPad).coerceAtLeast(minY + 16f),
                    newFloorY = maxY,
                    deltaY = deltaFloor,
                    minY = minY,
                    dtSec = totalDt,
                )
            }
        }

        if (mutableBlocks.none { !it.settled }) return

        val substeps = 2
        val dt = totalDt / substeps
        val minX = wallPad
        val maxX = (width - wallPad).coerceAtLeast(minX + 16f)

        val linearDamping = exp(-0.22f * dt)
        val angularDamping = exp(-1.15f * dt)
        val restitutionThreshold = 90f * scale
        val slop = 0.35f * scale
        val posCorrectionPercent = 0.42f

        repeat(substeps) {
            // 1. Integrate velocities with 2D gravity and gentle air drag.
            for (block in mutableBlocks) {
                if (block.settled) continue
                block.vx += gravityX * dt
                block.vy += gravityY * dt
                block.vx *= linearDamping
                block.vy *= linearDamping
                block.omegaRad *= angularDamping
            }

            // 2. Gather contacts (container walls + block-block OBB SAT manifolds).
            contacts.clear()
            collectWallContacts(minX, maxX, minY, maxY)
            collectBlockContacts()

            // Precompute effective masses and velocity restitution bias.
            for (c in contacts) {
                val a = c.a
                val b = c.b
                val invMassA = a?.invMass ?: 0f
                val invInertiaA = a?.invInertia ?: 0f
                val invMassB = b.invMass
                val invInertiaB = b.invInertia

                val rnA = if (a != null) c.rAx * c.ny - c.rAy * c.nx else 0f
                val rnB = c.rBx * c.ny - c.rBy * c.nx
                val kNormal = invMassA + invMassB + invInertiaA * rnA * rnA + invInertiaB * rnB * rnB
                c.massNormal = if (kNormal > 1e-6f) 1f / kNormal else 0f

                val tx = -c.ny
                val ty = c.nx
                val rtA = if (a != null) c.rAx * ty - c.rAy * tx else 0f
                val rtB = c.rBx * ty - c.rBy * tx
                val kTangent = invMassA + invMassB + invInertiaA * rtA * rtA + invInertiaB * rtB * rtB
                c.massTangent = if (kTangent > 1e-6f) 1f / kTangent else 0f

                val vAx = if (a != null) a.vx - a.omegaRad * c.rAy else 0f
                val vAy = if (a != null) a.vy + a.omegaRad * c.rAx else 0f
                val vBx = b.vx - b.omegaRad * c.rBy
                val vBy = b.vy + b.omegaRad * c.rBx
                val vn = (vBx - vAx) * c.nx + (vBy - vAy) * c.ny
                c.velocityBias = if (vn < -restitutionThreshold) -c.restitution * vn else 0f
            }

            // 3. Sequential Impulse solver iterations (normal + Coulomb friction).
            val iterations = 8
            repeat(iterations) {
                for (c in contacts) {
                    val a = c.a
                    val b = c.b
                    if ((a == null || a.settled) && b.settled) continue

                    val invMassA = a?.invMass ?: 0f
                    val invInertiaA = a?.invInertia ?: 0f
                    val invMassB = b.invMass
                    val invInertiaB = b.invInertia

                    val vAx0 = if (a != null) a.vx - a.omegaRad * c.rAy else 0f
                    val vAy0 = if (a != null) a.vy + a.omegaRad * c.rAx else 0f
                    val vBx0 = b.vx - b.omegaRad * c.rBy
                    val vBy0 = b.vy + b.omegaRad * c.rBx
                    val dvx0 = vBx0 - vAx0
                    val dvy0 = vBy0 - vAy0

                    // Normal impulse with accumulated clamping >= 0.
                    val vn = dvx0 * c.nx + dvy0 * c.ny
                    var dJn = c.massNormal * (-vn + c.velocityBias)
                    val oldJn = c.accJn
                    c.accJn = max(oldJn + dJn, 0f)
                    dJn = c.accJn - oldJn

                    val pNx = c.nx * dJn
                    val pNy = c.ny * dJn
                    if (a != null && !a.settled) {
                        a.vx -= pNx * invMassA
                        a.vy -= pNy * invMassA
                        a.omegaRad -= invInertiaA * (c.rAx * pNy - c.rAy * pNx)
                    }
                    if (!b.settled) {
                        b.vx += pNx * invMassB
                        b.vy += pNy * invMassB
                        b.omegaRad += invInertiaB * (c.rBx * pNy - c.rBy * pNx)
                    }

                    // Tangential friction impulse (Coulomb static + dynamic friction cone).
                    val tx = -c.ny
                    val ty = c.nx
                    val vAx1 = if (a != null) a.vx - a.omegaRad * c.rAy else 0f
                    val vAy1 = if (a != null) a.vy + a.omegaRad * c.rAx else 0f
                    val vBx1 = b.vx - b.omegaRad * c.rBy
                    val vBy1 = b.vy + b.omegaRad * c.rBx
                    val vt = (vBx1 - vAx1) * tx + (vBy1 - vAy1) * ty

                    val dJtRaw = c.massTangent * (-vt)
                    val oldJt = c.accJt
                    val candidateJt = oldJt + dJtRaw
                    val maxStatic = c.muStatic * c.accJn
                    val newJt = if (abs(candidateJt) <= maxStatic) {
                        candidateJt
                    } else {
                        val maxDynamic = c.muDynamic * c.accJn
                        candidateJt.coerceIn(-maxDynamic, maxDynamic)
                    }
                    val dJt = newJt - oldJt
                    c.accJt = newJt

                    val pTx = tx * dJt
                    val pTy = ty * dJt
                    if (a != null && !a.settled) {
                        a.vx -= pTx * invMassA
                        a.vy -= pTy * invMassA
                        a.omegaRad -= invInertiaA * (c.rAx * pTy - c.rAy * pTx)
                    }
                    if (!b.settled) {
                        b.vx += pTx * invMassB
                        b.vy += pTy * invMassB
                        b.omegaRad += invInertiaB * (c.rBx * pTy - c.rBy * pTx)
                    }
                }
            }

            // 4. Integrate positions & angles.
            for (block in mutableBlocks) {
                if (block.settled) continue
                block.cx += block.vx * dt
                block.cy += block.vy * dt
                block.angleRad += block.omegaRad * dt
            }

            // 5. Direct positional correction to resolve overlap without adding phantom bounce.
            for (c in contacts) {
                val excess = c.penetration - slop
                if (excess <= 0f) continue
                val a = c.a
                val b = c.b
                val invMassA = if (a != null && !a.settled) a.invMass else 0f
                val invMassB = if (!b.settled) b.invMass else 0f
                val totalInvMass = invMassA + invMassB
                if (totalInvMass <= 0f) continue
                val corr = (excess * posCorrectionPercent) / totalInvMass
                if (a != null && invMassA > 0f) {
                    a.cx -= c.nx * corr * invMassA
                    a.cy -= c.ny * corr * invMassA
                }
                if (invMassB > 0f) {
                    b.cx += c.nx * corr * invMassB
                    b.cy += c.ny * corr * invMassB
                }
            }

            // 6. Hard safety clamp inside container walls so blocks never escape viewport.
            clampBlocksInsideContainer(minX, maxX, minY, maxY)
        }

        // 7. Sleep detection & public coordinate sync.
        val sleepLinearThresh = 14f * scale
        val sleepOmegaThresh = 0.22f // rad/s (~12.6 deg/s)
        for (block in mutableBlocks) {
            if (!block.settled) {
                val speed = hypot(block.vx, block.vy)
                if (speed < sleepLinearThresh && abs(block.omegaRad) < sleepOmegaThresh) {
                    block.sleepTimer += totalDt
                    if (block.sleepTimer >= 0.42f) {
                        block.vx = 0f
                        block.vy = 0f
                        block.omegaRad = 0f
                        block.settled = true
                    }
                } else {
                    block.sleepTimer = 0f
                }
            }
            block.syncPublicFromInternal()
        }
    }

    fun scatter(impulseX: Float, impulseY: Float) {
        val baseUp = -abs(impulseY).coerceIn(520f * scale, 1_400f * scale)
        mutableBlocks.forEachIndexed { idx, block ->
            block.settled = false
            block.sleepTimer = 0f
            val spreadSign = if (idx % 2 == 0) 1f else -1f
            val spreadMag = (120f + ((idx * 53) % 220)) * scale
            block.vx += impulseX + spreadSign * spreadMag
            block.vy = baseUp - ((idx * 37) % 240) * scale
            block.omegaRad = (spreadSign * (90f + ((idx * 29) % 120))) * DegToRad
            block.syncPublicFromInternal()
        }
    }

    fun clear() {
        mutableBlocks.clear()
        contacts.clear()
        lastSimHeight = 0f
    }

    private fun wakeAll() {
        for (block in mutableBlocks) {
            block.settled = false
            block.sleepTimer = 0f
        }
    }

    private fun liftSupportedPile(
        prevFloorY: Float,
        newFloorY: Float,
        deltaY: Float,
        minY: Float,
        dtSec: Float,
    ) {
        val count = mutableBlocks.size
        if (count == 0) return
        val aabbMinX = FloatArray(count)
        val aabbMaxX = FloatArray(count)
        val aabbMinY = FloatArray(count)
        val aabbMaxY = FloatArray(count)
        val halfExtY = FloatArray(count)
        for (i in 0 until count) {
            val b = mutableBlocks[i]
            val c = cos(b.angleRad)
            val s = sin(b.angleRad)
            val ex = abs(c) * b.halfW + abs(s) * b.halfH
            val ey = abs(s) * b.halfW + abs(c) * b.halfH
            aabbMinX[i] = b.cx - ex
            aabbMaxX[i] = b.cx + ex
            aabbMinY[i] = b.cy - ey
            aabbMaxY[i] = b.cy + ey
            halfExtY[i] = ey
        }
        val order = (0 until count).sortedByDescending { aabbMaxY[it] }
        val supported = BooleanArray(count)
        val contactBand = 6f * scale
        val floorThreshold = minOf(prevFloorY, newFloorY) - contactBand
        for (pos in order.indices) {
            val idx = order[pos]
            if (aabbMaxY[idx] >= floorThreshold) {
                supported[idx] = true
                continue
            }
            for (belowPos in 0 until pos) {
                val belowIdx = order[belowPos]
                if (!supported[belowIdx]) continue
                val overlapX = minOf(aabbMaxX[idx], aabbMaxX[belowIdx]) - maxOf(aabbMinX[idx], aabbMinX[belowIdx])
                if (overlapX <= 4f * scale) continue
                val verticalGap = aabbMinY[belowIdx] - aabbMaxY[idx]
                if (verticalGap <= contactBand && aabbMaxY[idx] <= aabbMaxY[belowIdx]) {
                    supported[idx] = true
                    break
                }
            }
        }
        val gentleLiftVy = (deltaY / dtSec).coerceIn(-220f * scale, 0f) * 0.20f
        for (i in 0 until count) {
            if (!supported[i]) continue
            val block = mutableBlocks[i]
            val minCy = minY + halfExtY[i]
            block.cy = max(minCy, block.cy + deltaY)
            if (block.vy > gentleLiftVy) block.vy = gentleLiftVy
            block.syncPublicFromInternal()
        }
    }

    private fun collectWallContacts(minX: Float, maxX: Float, minY: Float, maxY: Float) {
        val muStaticWall = 0.34f
        val muDynamicWall = 0.22f
        val wallRestitution = 0.32f
        for (block in mutableBlocks) {
            if (block.settled) continue
            val c = cos(block.angleRad)
            val s = sin(block.angleRad)
            val uxX = c * block.halfW
            val uxY = s * block.halfW
            val uyX = -s * block.halfH
            val uyY = c * block.halfH

            for (cornerIdx in 0 until 4) {
                val sx = if ((cornerIdx and 1) == 0) -1f else 1f
                val sy = if ((cornerIdx and 2) == 0) -1f else 1f
                val rx = sx * uxX + sy * uyX
                val ry = sx * uxY + sy * uyY
                val px = block.cx + rx
                val py = block.cy + ry

                if (px < minX) {
                    contacts.add(
                        Contact(
                            a = null,
                            b = block,
                            nx = 1f,
                            ny = 0f,
                            penetration = minX - px,
                            rAx = 0f,
                            rAy = 0f,
                            rBx = rx,
                            rBy = ry,
                            muStatic = muStaticWall,
                            muDynamic = muDynamicWall,
                            restitution = wallRestitution,
                        ),
                    )
                } else if (px > maxX) {
                    contacts.add(
                        Contact(
                            a = null,
                            b = block,
                            nx = -1f,
                            ny = 0f,
                            penetration = px - maxX,
                            rAx = 0f,
                            rAy = 0f,
                            rBx = rx,
                            rBy = ry,
                            muStatic = muStaticWall,
                            muDynamic = muDynamicWall,
                            restitution = wallRestitution,
                        ),
                    )
                }
                if (py < minY) {
                    contacts.add(
                        Contact(
                            a = null,
                            b = block,
                            nx = 0f,
                            ny = 1f,
                            penetration = minY - py,
                            rAx = 0f,
                            rAy = 0f,
                            rBx = rx,
                            rBy = ry,
                            muStatic = muStaticWall,
                            muDynamic = muDynamicWall,
                            restitution = wallRestitution,
                        ),
                    )
                } else if (py > maxY) {
                    contacts.add(
                        Contact(
                            a = null,
                            b = block,
                            nx = 0f,
                            ny = -1f,
                            penetration = py - maxY,
                            rAx = 0f,
                            rAy = 0f,
                            rBx = rx,
                            rBy = ry,
                            muStatic = muStaticWall,
                            muDynamic = muDynamicWall,
                            restitution = wallRestitution,
                        ),
                    )
                }
            }
        }
    }

    private fun collectBlockContacts() {
        val muStaticBlock = 0.44f
        val muDynamicBlock = 0.28f
        val blockRestitution = 0.26f
        val count = mutableBlocks.size
        val wakeSpeedThresh = 24f * scale

        for (i in 0 until count) {
            val a = mutableBlocks[i]
            val cosA = cos(a.angleRad)
            val sinA = sin(a.angleRad)
            val uxAx = cosA
            val uxAy = sinA
            val uyAx = -sinA
            val uyAy = cosA
            val exA = abs(uxAx) * a.halfW + abs(uyAx) * a.halfH
            val eyA = abs(uxAy) * a.halfW + abs(uyAy) * a.halfH

            for (j in i + 1 until count) {
                val b = mutableBlocks[j]
                if (a.settled && b.settled) continue

                val cosB = cos(b.angleRad)
                val sinB = sin(b.angleRad)
                val uxBx = cosB
                val uxBy = sinB
                val uyBx = -sinB
                val uyBy = cosB
                val exB = abs(uxBx) * b.halfW + abs(uyBx) * b.halfH
                val eyB = abs(uxBy) * b.halfW + abs(uyBy) * b.halfH

                val dx = b.cx - a.cx
                val dy = b.cy - a.cy
                if (abs(dx) > exA + exB + 1f || abs(dy) > eyA + eyB + 1f) continue

                // SAT across 4 axes: A.ux, A.uy, B.ux, B.uy.
                val d0 = dx * uxAx + dy * uxAy
                val pen0 = a.halfW + (abs(uxBx * uxAx + uxBy * uxAy) * b.halfW + abs(uyBx * uxAx + uyBy * uxAy) * b.halfH) - abs(d0)
                if (pen0 <= 0f) continue

                val d1 = dx * uyAx + dy * uyAy
                val pen1 = a.halfH + (abs(uxBx * uyAx + uxBy * uyAy) * b.halfW + abs(uyBx * uyAx + uyBy * uyAy) * b.halfH) - abs(d1)
                if (pen1 <= 0f) continue

                val d2 = dx * uxBx + dy * uxBy
                val pen2 = b.halfW + (abs(uxAx * uxBx + uxAy * uxBy) * a.halfW + abs(uyAx * uxBx + uyAy * uxBy) * a.halfH) - abs(d2)
                if (pen2 <= 0f) continue

                val d3 = dx * uyBx + dy * uyBy
                val pen3 = b.halfH + (abs(uxAx * uyBx + uxAy * uyBy) * a.halfW + abs(uyAx * uyBx + uyAy * uyBy) * a.halfH) - abs(d3)
                if (pen3 <= 0f) continue

                // Select axis with smallest penetration (tiny bias keeps face selection stable).
                var bestAxis = 0
                var bestPen = pen0
                if (pen1 < bestPen * 0.99f) {
                    bestAxis = 1
                    bestPen = pen1
                }
                if (pen2 < bestPen * 0.96f) {
                    bestAxis = 2
                    bestPen = pen2
                }
                if (pen3 < bestPen * 0.96f) {
                    bestAxis = 3
                    bestPen = pen3
                }

                // Normal pointing from A -> B.
                val nx: Float
                val ny: Float
                when (bestAxis) {
                    0 -> if (d0 >= 0f) { nx = uxAx; ny = uxAy } else { nx = -uxAx; ny = -uxAy }
                    1 -> if (d1 >= 0f) { nx = uyAx; ny = uyAy } else { nx = -uyAx; ny = -uyAy }
                    2 -> if (d2 >= 0f) { nx = uxBx; ny = uxBy } else { nx = -uxBx; ny = -uxBy }
                    else -> if (d3 >= 0f) { nx = uyBx; ny = uyBy } else { nx = -uyBx; ny = -uyBy }
                }

                // Wake settled block on real impact or overlap.
                if (a.settled && (hypot(b.vx, b.vy) > wakeSpeedThresh || bestPen > 0.6f * scale)) {
                    a.settled = false
                    a.sleepTimer = 0f
                } else if (b.settled && (hypot(a.vx, a.vy) > wakeSpeedThresh || bestPen > 0.6f * scale)) {
                    b.settled = false
                    b.sleepTimer = 0f
                }

                // Reference vs Incident body setup.
                val refIsA = bestAxis <= 1
                val refCx = if (refIsA) a.cx else b.cx
                val refCy = if (refIsA) a.cy else b.cy
                val refUxX = if (refIsA) uxAx else uxBx
                val refUxY = if (refIsA) uxAy else uxBy
                val refUyX = if (refIsA) uyAx else uyBx
                val refUyY = if (refIsA) uyAy else uyBy
                val refHw = if (refIsA) a.halfW else b.halfW
                val refHh = if (refIsA) a.halfH else b.halfH

                val incCx = if (refIsA) b.cx else a.cx
                val incCy = if (refIsA) b.cy else a.cy
                val incUxX = if (refIsA) uxBx else uxAx
                val incUxY = if (refIsA) uxBy else uxAy
                val incUyX = if (refIsA) uyBx else uyAx
                val incUyY = if (refIsA) uyBy else uyAy
                val incHw = if (refIsA) b.halfW else a.halfW
                val incHh = if (refIsA) b.halfH else a.halfH

                // Reference face outward normal points toward incident body.
                val refNx = if (refIsA) nx else -nx
                val refNy = if (refIsA) ny else -ny
                val refAxisIsX = bestAxis == 0 || bestAxis == 2
                val refFaceDist = if (refAxisIsX) refHw else refHh
                val refFaceCx = refCx + refNx * refFaceDist
                val refFaceCy = refCy + refNy * refFaceDist
                val sideDirX = if (refAxisIsX) refUyX else refUxX
                val sideDirY = if (refAxisIsX) refUyY else refUxY
                val sideExtent = if (refAxisIsX) refHh else refHw

                // Incident edge: face of incident body most anti-parallel to refNormal.
                val dotIncX = incUxX * refNx + incUxY * refNy
                val dotIncY = incUyX * refNx + incUyY * refNy
                val incFaceCx: Float
                val incFaceCy: Float
                val incEdgeDirX: Float
                val incEdgeDirY: Float
                val incEdgeHalf: Float
                if (abs(dotIncX) > abs(dotIncY)) {
                    val sign = if (dotIncX > 0f) -1f else 1f
                    incFaceCx = incCx + sign * incUxX * incHw
                    incFaceCy = incCy + sign * incUxY * incHw
                    incEdgeDirX = incUyX
                    incEdgeDirY = incUyY
                    incEdgeHalf = incHh
                } else {
                    val sign = if (dotIncY > 0f) -1f else 1f
                    incFaceCx = incCx + sign * incUyX * incHh
                    incFaceCy = incCy + sign * incUyY * incHh
                    incEdgeDirX = incUxX
                    incEdgeDirY = incUxY
                    incEdgeHalf = incHw
                }

                // Incident edge endpoints in coordinates relative to refFaceCenter.
                var p0x = incFaceCx - incEdgeDirX * incEdgeHalf - refFaceCx
                var p0y = incFaceCy - incEdgeDirY * incEdgeHalf - refFaceCy
                var p1x = incFaceCx + incEdgeDirX * incEdgeHalf - refFaceCx
                var p1y = incFaceCy + incEdgeDirY * incEdgeHalf - refFaceCy

                // Clip against +sideDir <= sideExtent
                val dPos0 = p0x * sideDirX + p0y * sideDirY - sideExtent
                val dPos1 = p1x * sideDirX + p1y * sideDirY - sideExtent
                if (dPos0 > 0f && dPos1 > 0f) continue
                if (dPos0 > 0f && dPos1 <= 0f) {
                    val t = dPos0 / (dPos0 - dPos1)
                    p0x += (p1x - p0x) * t
                    p0y += (p1y - p0y) * t
                } else if (dPos1 > 0f && dPos0 <= 0f) {
                    val t = dPos1 / (dPos1 - dPos0)
                    p1x += (p0x - p1x) * t
                    p1y += (p0y - p1y) * t
                }

                // Clip against -sideDir <= sideExtent
                val dNeg0 = -(p0x * sideDirX + p0y * sideDirY) - sideExtent
                val dNeg1 = -(p1x * sideDirX + p1y * sideDirY) - sideExtent
                if (dNeg0 > 0f && dNeg1 > 0f) continue
                if (dNeg0 > 0f && dNeg1 <= 0f) {
                    val t = dNeg0 / (dNeg0 - dNeg1)
                    p0x += (p1x - p0x) * t
                    p0y += (p1y - p0y) * t
                } else if (dNeg1 > 0f && dNeg0 <= 0f) {
                    val t = dNeg1 / (dNeg1 - dNeg0)
                    p1x += (p0x - p1x) * t
                    p1y += (p0y - p1y) * t
                }

                val sep0 = p0x * refNx + p0y * refNy
                if (sep0 <= 0.5f * scale) {
                    val wx = p0x + refFaceCx
                    val wy = p0y + refFaceCy
                    contacts.add(
                        Contact(
                            a = a,
                            b = b,
                            nx = nx,
                            ny = ny,
                            penetration = (-sep0).coerceAtLeast(0f),
                            rAx = wx - a.cx,
                            rAy = wy - a.cy,
                            rBx = wx - b.cx,
                            rBy = wy - b.cy,
                            muStatic = muStaticBlock,
                            muDynamic = muDynamicBlock,
                            restitution = blockRestitution,
                        ),
                    )
                }

                val sep1 = p1x * refNx + p1y * refNy
                if (sep1 <= 0.5f * scale) {
                    val wx = p1x + refFaceCx
                    val wy = p1y + refFaceCy
                    contacts.add(
                        Contact(
                            a = a,
                            b = b,
                            nx = nx,
                            ny = ny,
                            penetration = (-sep1).coerceAtLeast(0f),
                            rAx = wx - a.cx,
                            rAy = wy - a.cy,
                            rBx = wx - b.cx,
                            rBy = wy - b.cy,
                            muStatic = muStaticBlock,
                            muDynamic = muDynamicBlock,
                            restitution = blockRestitution,
                        ),
                    )
                }
            }
        }
    }

    private fun clampBlocksInsideContainer(minX: Float, maxX: Float, minY: Float, maxY: Float) {
        val slack = 0.5f * scale
        for (block in mutableBlocks) {
            if (block.settled) continue
            val c = cos(block.angleRad)
            val s = sin(block.angleRad)
            val ex = abs(c) * block.halfW + abs(s) * block.halfH
            val ey = abs(s) * block.halfW + abs(c) * block.halfH

            val minCx = minX + ex - slack
            val maxCx = maxX - ex + slack
            if (minCx <= maxCx) {
                block.cx = block.cx.coerceIn(minCx, maxCx)
            } else {
                block.cx = (minX + maxX) * 0.5f
            }

            val minCy = minY + ey - slack
            val maxCy = maxY - ey + slack
            if (minCy <= maxCy) {
                block.cy = block.cy.coerceIn(minCy, maxCy)
            } else {
                block.cy = (minY + maxY) * 0.5f
            }
        }
    }

    private class Contact(
        val a: GravityLyricBlock?,
        val b: GravityLyricBlock,
        val nx: Float,
        val ny: Float,
        val penetration: Float,
        val rAx: Float,
        val rAy: Float,
        val rBx: Float,
        val rBy: Float,
        val muStatic: Float,
        val muDynamic: Float,
        val restitution: Float,
        var accJn: Float = 0f,
        var accJt: Float = 0f,
        var massNormal: Float = 0f,
        var massTangent: Float = 0f,
        var velocityBias: Float = 0f,
    )
}
