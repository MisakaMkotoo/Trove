package com.exodidio.tune.player

import androidx.media3.common.C
import com.exodidio.tune.netease.NeteaseStreamByteSource
import com.exodidio.tune.netease.StreamChunkSource
import com.exodidio.tune.netease.isPrematureStreamEnd
import com.exodidio.tune.netease.isSpanRangeFullyCovered
import com.exodidio.tune.netease.shouldClearLegacyStreamCache
import com.exodidio.tune.netease.shouldEvictCachedStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRecoveryPolicyTest {
    @Test
    fun `mid-song clean EOF triggers cache eviction and recovery instead of skipping`() {
        val action = classifyStreamCompletion(
            isPreview = false,
            positionMs = 95_000L,
            durationMs = 240_000L,
            elapsedSinceStartMs = 95_000L,
            hasUpstreamError = false,
            wasStreamIncomplete = false,
            alreadyRetriedCleanEofAtSamePos = false,
        )
        assertEquals(StreamCompletionAction.RecoverFromCleanPrematureEof, action)
    }

    @Test
    fun `repeated clean EOF at same position does not loop infinitely`() {
        val action = classifyStreamCompletion(
            isPreview = false,
            positionMs = 95_000L,
            durationMs = 240_000L,
            elapsedSinceStartMs = 5_000L,
            hasUpstreamError = false,
            wasStreamIncomplete = false,
            alreadyRetriedCleanEofAtSamePos = true,
        )
        assertEquals(StreamCompletionAction.NormalCompletion, action)
    }

    @Test
    fun `upstream error or incomplete proxy stream always triggers error recovery before end`() {
        val action = classifyStreamCompletion(
            isPreview = false,
            positionMs = 130_000L,
            durationMs = 240_000L,
            elapsedSinceStartMs = 130_000L,
            hasUpstreamError = true,
            wasStreamIncomplete = false,
            alreadyRetriedCleanEofAtSamePos = true,
        )
        assertEquals(StreamCompletionAction.RecoverFromError, action)

        val incompleteAction = classifyStreamCompletion(
            isPreview = false,
            positionMs = 235_000L,
            durationMs = 240_000L,
            elapsedSinceStartMs = 235_000L,
            hasUpstreamError = false,
            wasStreamIncomplete = true,
            alreadyRetriedCleanEofAtSamePos = false,
        )
        assertEquals(StreamCompletionAction.RecoverFromError, incompleteAction)
    }

    @Test
    fun `normal end of full track and preview track complete normally`() {
        assertEquals(
            StreamCompletionAction.NormalCompletion,
            classifyStreamCompletion(
                isPreview = false,
                positionMs = 239_200L,
                durationMs = 240_000L,
                elapsedSinceStartMs = 239_200L,
                hasUpstreamError = false,
                wasStreamIncomplete = false,
                alreadyRetriedCleanEofAtSamePos = false,
            ),
        )
        assertEquals(
            StreamCompletionAction.NormalCompletion,
            classifyStreamCompletion(
                isPreview = true,
                positionMs = 30_000L,
                durationMs = 240_000L,
                elapsedSinceStartMs = 30_000L,
                hasUpstreamError = false,
                wasStreamIncomplete = false,
                alreadyRetriedCleanEofAtSamePos = false,
            ),
        )
    }

    @Test
    fun `cache eviction triggers on size, unbounded spans, or variant mismatch`() {
        assertTrue(shouldEvictCachedStream(3_200_000L, 28_000_000L, "mp3-3200000", "flac-28000000"))
        assertTrue(shouldEvictCachedStream(-1L, 28_000_000L, "mp3-3200000", "flac-28000000"))
        assertTrue(shouldEvictCachedStream(-1L, 28_000_000L, null, "flac-28000000", hasCachedSpans = true))
        assertTrue(shouldEvictCachedStream(4_194_304L, 10_000_000L, "mp3-10000000", "mp3-10000000"))
        assertFalse(shouldEvictCachedStream(28_000_000L, 28_000_000L, "flac-28000000", "flac-28000000", hasCachedSpans = true))
    }

    @Test
    fun `legacy cache migration clears unversioned schemas once`() {
        assertTrue(shouldClearLegacyStreamCache(storedSchemaVersion = 1, targetSchemaVersion = 2))
        assertFalse(shouldClearLegacyStreamCache(storedSchemaVersion = 2, targetSchemaVersion = 2))
    }

    @Test
    fun `span coverage detects holes and incomplete caches`() {
        assertFalse(isSpanRangeFullyCovered(emptyList(), 1_000L))
        assertFalse(isSpanRangeFullyCovered(listOf(0L to 500L), 1_000L))
        assertFalse(isSpanRangeFullyCovered(listOf(0L to 400L, 600L to 400L), 1_000L))
        assertTrue(isSpanRangeFullyCovered(listOf(0L to 600L, 500L to 500L), 1_000L))
    }

    @Test
    fun `premature stream end detects read shorter than total or expected size`() {
        assertTrue(isPrematureStreamEnd(4_194_304L, 10_000_000L, -1L))
        assertTrue(isPrematureStreamEnd(4_194_304L, -1L, 10_000_000L))
        assertFalse(isPrematureStreamEnd(10_000_000L, 10_000_000L, 10_000_000L))
    }

    @Test
    fun `byte source reads into directBuffer, seeks across modes, and reports EOF`() {
        val payload = ByteArray(64) { (it + 1).toByte() }
        var completed = false
        val source = NeteaseStreamByteSource(
            songId = 101L,
            allowCache = true,
            initialUpstream = "https://cdn.example/101.mp3",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = null,
            chunkSourceFactory = { _, _ -> FakeChunkSource(payload) },
            onStreamComplete = { completed = true },
            retryDelayMs = 0L,
        )
        try {
            assertEquals(64L, source.seek(0L, NeteaseStreamByteSource.SEEK_MODE_SIZE))
            assertEquals(16, source.read(16))
            val first = ByteArray(16)
            source.directBuffer.position(0)
            source.directBuffer.get(first)
            assertArrayEquals(payload.copyOfRange(0, 16), first)

            assertEquals(20L, source.seek(4L, NeteaseStreamByteSource.SEEK_MODE_CUR))
            assertEquals(58L, source.seek(-6L, NeteaseStreamByteSource.SEEK_MODE_END))
            assertEquals(6, source.read(32))
            val tail = ByteArray(6)
            source.directBuffer.position(0)
            source.directBuffer.get(tail)
            assertArrayEquals(payload.copyOfRange(58, 64), tail)

            assertEquals(NeteaseStreamByteSource.RESULT_EOF, source.read(32))
            assertTrue(completed)
            assertEquals(-1L, source.seek(100L, NeteaseStreamByteSource.SEEK_MODE_SET))
        } finally {
            source.close()
        }
    }

    @Test
    fun `byte source refreshes upstream and resumes at current position on mid-stream error`() {
        val payload = ByteArray(48) { (it * 3).toByte() }
        var openCount = 0
        var refreshedCount = 0
        val source = NeteaseStreamByteSource(
            songId = 202L,
            allowCache = true,
            initialUpstream = "https://cdn.example/old.mp3",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = { _, _ ->
                refreshedCount += 1
                "https://cdn.example/fresh.mp3"
            },
            chunkSourceFactory = { _, _ ->
                openCount += 1
                val failAfter = if (openCount == 1) 12 else Int.MAX_VALUE
                FakeChunkSource(payload, failAfterBytes = failAfter)
            },
            retryDelayMs = 0L,
        )
        try {
            assertEquals(12, source.read(12))
            assertEquals(36, source.read(64))
            val tail = ByteArray(36)
            source.directBuffer.position(0)
            source.directBuffer.get(tail)
            assertArrayEquals(payload.copyOfRange(12, 48), tail)
            assertEquals(1, refreshedCount)
            assertEquals(2, openCount)
        } finally {
            source.close()
        }
    }

    @Test
    fun `byte source evicts truncated cache when opened size is smaller than expected`() {
        val payload = ByteArray(40) { it.toByte() }
        var evicted = 0
        var openAttempt = 0
        val source = NeteaseStreamByteSource(
            songId = 303L,
            allowCache = true,
            initialUpstream = "https://cdn.example/303.mp3",
            knownExpectedBytes = 40L,
            knownTotalBytes = null,
            upstreamRefresher = null,
            chunkSourceFactory = { _, _ ->
                openAttempt += 1
                if (openAttempt == 1) FakeChunkSource(payload.copyOfRange(0, 10))
                else FakeChunkSource(payload)
            },
            onEvictCache = { evicted += 1 },
            retryDelayMs = 0L,
        )
        try {
            assertEquals(40, source.read(64))
            assertEquals(1, evicted)
            assertEquals(2, openAttempt)
        } finally {
            source.close()
        }
    }

    @Test
    fun `byte source cancel aborts retry wait immediately`() {
        val enteringRetry = CountDownLatch(1)
        val source = NeteaseStreamByteSource(
            songId = 404L,
            allowCache = false,
            initialUpstream = "https://cdn.example/404.mp3",
            knownExpectedBytes = 100L,
            knownTotalBytes = 100L,
            upstreamRefresher = { _, _ ->
                enteringRetry.countDown()
                null
            },
            chunkSourceFactory = { _, _ ->
                object : StreamChunkSource {
                    override fun open(upstream: String, position: Long): Long {
                        throw IOException("transient failure")
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
                    override fun close() = Unit
                }
            },
            retryDelayMs = 5_000L,
            maxAttempts = 3,
        )
        val cancelThread = thread {
            assertTrue(enteringRetry.await(2, TimeUnit.SECONDS))
            Thread.sleep(30)
            source.cancel()
        }
        val startedNs = System.nanoTime()
        val result = source.read(16)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
        cancelThread.join(2_000)
        source.close()
        assertEquals(NeteaseStreamByteSource.RESULT_CANCELLED, result)
        assertTrue("expected fast cancel, took ${elapsedMs}ms", elapsedMs < 1_500L)
    }

    @Test
    fun `abortReadForSeek interrupts stalled retry immediately without failing subsequent seek read`() {
        val payload = ByteArray(64) { (it + 10).toByte() }
        val enteringRetry = CountDownLatch(1)
        var streamErrors = 0
        val openedPositions = mutableListOf<Long>()
        val source = NeteaseStreamByteSource(
            songId = 505L,
            allowCache = false,
            initialUpstream = "https://cdn.example/505.mp3",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = { _, _ ->
                enteringRetry.countDown()
                null
            },
            chunkSourceFactory = { _, _ ->
                object : StreamChunkSource {
                    private var cursor = 0
                    private var failThisOpen = false

                    override fun open(upstream: String, position: Long): Long {
                        openedPositions += position
                        if (position == 0L) {
                            failThisOpen = true
                            throw IOException("slow/failing old position")
                        }
                        failThisOpen = false
                        cursor = position.toInt().coerceIn(0, payload.size)
                        return (payload.size - cursor).toLong()
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (failThisOpen) throw IOException("old read")
                        if (cursor >= payload.size) return C.RESULT_END_OF_INPUT
                        val count = minOf(length, payload.size - cursor)
                        System.arraycopy(payload, cursor, buffer, offset, count)
                        cursor += count
                        return count
                    }

                    override fun close() = Unit
                }
            },
            onStreamError = { streamErrors += 1 },
            retryDelayMs = 5_000L,
            maxAttempts = 3,
        )
        try {
            val abortThread = thread {
                assertTrue(enteringRetry.await(2, TimeUnit.SECONDS))
                Thread.sleep(30)
                source.abortReadForSeek(1L)
            }
            val startedNs = System.nanoTime()
            val abortedResult = source.read(16)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
            abortThread.join(2_000)

            assertEquals(NeteaseStreamByteSource.RESULT_CANCELLED, abortedResult)
            assertTrue("expected fast seek abort, took ${elapsedMs}ms", elapsedMs < 1_500L)
            assertEquals(0, streamErrors)

            source.beginSeek(1L)
            assertEquals(32L, source.seek(32L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(16, source.read(16))
            val actual = ByteArray(16)
            source.directBuffer.position(0)
            source.directBuffer.get(actual)
            assertArrayEquals(payload.copyOfRange(32, 48), actual)
            assertTrue(openedPositions.contains(32L))
        } finally {
            source.close()
        }
    }

    @Test
    fun `abortReadForSeek does not cancel a read once beginSeek advances to that generation`() {
        val payload = ByteArray(32) { (it + 1).toByte() }
        val source = NeteaseStreamByteSource(
            songId = 606L,
            allowCache = false,
            initialUpstream = "https://cdn.example/606.mp3",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = null,
            chunkSourceFactory = { _, _ -> FakeChunkSource(payload) },
            retryDelayMs = 0L,
        )
        try {
            source.beginSeek(1L)
            source.abortReadForSeek(1L)
            assertEquals(16L, source.seek(16L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(16, source.read(16))
        } finally {
            source.close()
        }
    }

    @Test
    fun `resolveSettledStreamFailurePosition preserves forward seek position and clamps to duration`() {
        assertEquals(150_000L, resolveSettledStreamFailurePosition(positionMs = 150_000L, durationMs = 240_000L))
        assertEquals(240_000L, resolveSettledStreamFailurePosition(positionMs = 260_000L, durationMs = 240_000L))
        assertEquals(150_000L, resolveSettledStreamFailurePosition(positionMs = 150_000L, durationMs = 0L))
        assertEquals(0L, resolveSettledStreamFailurePosition(positionMs = -500L, durationMs = 240_000L))
    }

    @Test
    fun `failure immediately after seek near end triggers recovery instead of skipping`() {
        val action = classifyStreamCompletion(
            isPreview = false,
            positionMs = 235_000L,
            durationMs = 240_000L,
            elapsedSinceStartMs = 400L,
            hasUpstreamError = true,
            wasStreamIncomplete = true,
            alreadyRetriedCleanEofAtSamePos = false,
        )
        assertEquals(StreamCompletionAction.RecoverFromError, action)
    }

    @Test
    fun `seek probe page serves nearby backward and forward seeks from RAM without reopening`() {
        val payload = ByteArray(512 * 1024) { ((it * 31) xor (it ushr 8)).toByte() }
        val openCalls = mutableListOf<OpenRecord>()
        val source = NeteaseStreamByteSource(
            songId = 707L,
            allowCache = true,
            initialUpstream = "https://cdn.example/707.flac",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = null,
            chunkSourceFactory = { _, _ -> FakeRangeChunkSource(payload, openCalls) },
            retryDelayMs = 0L,
        )
        try {
            val pageSize = NeteaseStreamByteSource.SEEK_PAGE_SIZE.toLong()
            assertEquals(pageSize + 40_000L, source.seek(pageSize + 40_000L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(1024, source.read(1024))
            assertEquals(1, openCalls.size)
            assertEquals(OpenRecord(position = pageSize, length = pageSize, writeCache = true), openCalls[0])

            val firstSlice = ByteArray(1024)
            source.directBuffer.position(0)
            source.directBuffer.get(firstSlice)
            assertArrayEquals(
                payload.copyOfRange((pageSize + 40_000).toInt(), (pageSize + 41_024).toInt()),
                firstSlice,
            )

            // Backward seek inside the same 128KB probe page: 0 new opens.
            assertEquals(pageSize + 4_096L, source.seek(pageSize + 4_096L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(512, source.read(512))
            assertEquals(1, openCalls.size)

            val backSlice = ByteArray(512)
            source.directBuffer.position(0)
            source.directBuffer.get(backSlice)
            assertArrayEquals(
                payload.copyOfRange((pageSize + 4_096).toInt(), (pageSize + 4_608).toInt()),
                backSlice,
            )

            // Forward seek inside the same 128KB probe page: 0 new opens.
            assertEquals(pageSize + 90_000L, source.seek(pageSize + 90_000L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(256, source.read(256))
            assertEquals(1, openCalls.size)
        } finally {
            source.close()
        }
    }

    @Test
    fun `bounded seek probe preserves unknown total size and hands off sequential read to writeCache`() {
        val payload = ByteArray(512 * 1024) { (it and 0xFF).toByte() }
        val openCalls = mutableListOf<OpenRecord>()
        val source = NeteaseStreamByteSource(
            songId = 808L,
            allowCache = true,
            initialUpstream = "https://cdn.example/808.flac",
            knownExpectedBytes = null,
            knownTotalBytes = null,
            upstreamRefresher = null,
            chunkSourceFactory = { _, _ -> FakeRangeChunkSource(payload, openCalls) },
            retryDelayMs = 0L,
        )
        try {
            val pageSize = NeteaseStreamByteSource.SEEK_PAGE_SIZE.toLong()
            // Seek to 150KB with unknown total -> bounded probe fetches [128KB, 256KB).
            assertEquals(pageSize + 22_000L, source.seek(pageSize + 22_000L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(64, source.read(64))
            assertEquals(OpenRecord(position = pageSize, length = pageSize, writeCache = true), openCalls.single())

            // Seeking beyond 256KB must NOT be rejected (total was NOT poisoned to 256KB).
            val targetPos = pageSize * 3L - 64L
            assertEquals(targetPos, source.seek(targetPos, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(64, source.read(64))
            assertEquals(2, openCalls.size)
            assertEquals(OpenRecord(position = pageSize * 2L, length = pageSize, writeCache = true), openCalls[1])

            // Sequential continuation past 384KB transitions to unbounded writeCache = true stream.
            assertEquals(128, source.read(128))
            assertEquals(3, openCalls.size)
            assertEquals(
                OpenRecord(position = pageSize * 3L, length = C.LENGTH_UNSET.toLong(), writeCache = true),
                openCalls[2],
            )
            val seq = ByteArray(128)
            source.directBuffer.position(0)
            source.directBuffer.get(seq)
            assertArrayEquals(
                payload.copyOfRange((pageSize * 3).toInt(), (pageSize * 3 + 128).toInt()),
                seq,
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun `small forward seek on open stream skips in place without reopening`() {
        val payload = ByteArray(256 * 1024) { ((it * 7) + 3).toByte() }
        val openCalls = mutableListOf<OpenRecord>()
        val source = NeteaseStreamByteSource(
            songId = 909L,
            allowCache = true,
            initialUpstream = "https://cdn.example/909.flac",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = null,
            chunkSourceFactory = { _, _ -> FakeRangeChunkSource(payload, openCalls) },
            retryDelayMs = 0L,
        )
        try {
            assertEquals(1024, source.read(1024))
            assertEquals(1, openCalls.size)
            assertEquals(65_536L, source.seek(65_536L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(512, source.read(512))
            assertEquals(1, openCalls.size)

            val actual = ByteArray(512)
            source.directBuffer.position(0)
            source.directBuffer.get(actual)
            assertArrayEquals(payload.copyOfRange(65_536, 65_536 + 512), actual)
        } finally {
            source.close()
        }
    }

    @Test
    fun `aborted seek probe does not cache partial page and new seek reads cleanly`() {
        val payload = ByteArray(512 * 1024) { ((it * 13) + 5).toByte() }
        val openCalls = mutableListOf<OpenRecord>()
        val pageSize = NeteaseStreamByteSource.SEEK_PAGE_SIZE.toLong()
        val firstProbeBlocked = CountDownLatch(1)
        val source = NeteaseStreamByteSource(
            songId = 1010L,
            allowCache = true,
            initialUpstream = "https://cdn.example/1010.flac",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = null,
            chunkSourceFactory = { _, isCancelled ->
                object : StreamChunkSource {
                    private var cursor = 0
                    private var remaining = Long.MAX_VALUE
                    private var failMidProbe = false

                    override fun open(upstream: String, position: Long): Long =
                        openRange(upstream, position, C.LENGTH_UNSET.toLong(), writeCache = true)

                    override fun openRange(upstream: String, position: Long, length: Long, writeCache: Boolean): Long {
                        openCalls += OpenRecord(position, length, writeCache)
                        cursor = position.toInt()
                        remaining = if (length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else length
                        failMidProbe = (openCalls.size == 1 && position == pageSize)
                        return if (length == C.LENGTH_UNSET.toLong()) (payload.size - cursor).toLong() else length
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (failMidProbe && cursor >= pageSize + 4096) {
                            firstProbeBlocked.countDown()
                            var spins = 0
                            while (!isCancelled() && spins < 500) {
                                Thread.sleep(10)
                                spins += 1
                            }
                            throw IOException("aborted probe")
                        }
                        if (remaining <= 0L || cursor >= payload.size) return C.RESULT_END_OF_INPUT
                        val count = minOf(length.toLong(), remaining, (payload.size - cursor).toLong(), 4096L).toInt()
                        System.arraycopy(payload, cursor, buffer, offset, count)
                        cursor += count
                        remaining -= count
                        return count
                    }

                    override fun close() = Unit
                }
            },
            retryDelayMs = 5_000L,
        )
        try {
            source.beginSeek(1L)
            assertEquals(pageSize + 1024L, source.seek(pageSize + 1024L, NeteaseStreamByteSource.SEEK_MODE_SET))
            val abortThread = thread {
                assertTrue(firstProbeBlocked.await(2, TimeUnit.SECONDS))
                source.abortReadForSeek(2L)
            }
            val aborted = source.read(1024)
            abortThread.join(2_000)
            assertEquals(NeteaseStreamByteSource.RESULT_CANCELLED, aborted)

            // Subsequent seek to second page succeeds and original partial page was not cached.
            source.beginSeek(2L)
            val secondPagePos = pageSize * 2L + 2048L
            assertEquals(secondPagePos, source.seek(secondPagePos, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(512, source.read(512))
            val actual = ByteArray(512)
            source.directBuffer.position(0)
            source.directBuffer.get(actual)
            assertArrayEquals(
                payload.copyOfRange(secondPagePos.toInt(), secondPagePos.toInt() + 512),
                actual,
            )
            assertEquals(2, openCalls.size)

            // Seeking back into the first page must reopen (partial aborted page was NOT cached).
            assertEquals(pageSize + 1024L, source.seek(pageSize + 1024L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(256, source.read(256))
            assertEquals(3, openCalls.size)
        } finally {
            source.close()
        }
    }

    @Test
    fun `beginSeek keeps boundary crossing reads in bounded probe mode until endSeek`() {
        val payload = ByteArray(512 * 1024) { ((it * 19) + 7).toByte() }
        val openCalls = mutableListOf<OpenRecord>()
        val source = NeteaseStreamByteSource(
            songId = 1111L,
            allowCache = true,
            initialUpstream = "https://cdn.example/1111.flac",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = null,
            chunkSourceFactory = { _, _ -> FakeRangeChunkSource(payload, openCalls) },
            retryDelayMs = 0L,
        )
        try {
            val pageSize = NeteaseStreamByteSource.SEEK_PAGE_SIZE.toLong()
            source.beginSeek(1L)
            assertEquals(pageSize * 2L - 512L, source.seek(pageSize * 2L - 512L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(512, source.read(512))
            assertEquals(OpenRecord(position = pageSize, length = pageSize, writeCache = true), openCalls.single())

            // Crosses page boundary while still seeking -> must fetch bounded page [2*pageSize, 3*pageSize), NOT C.LENGTH_UNSET.
            assertEquals(1024, source.read(1024))
            assertEquals(2, openCalls.size)
            assertEquals(OpenRecord(position = pageSize * 2L, length = pageSize, writeCache = true), openCalls[1])

            // After endSeek, reading past page 2 hands off to continuous sequential stream (C.LENGTH_UNSET).
            source.endSeek(1L)
            assertEquals(pageSize * 3L - 128L, source.seek(pageSize * 3L - 128L, NeteaseStreamByteSource.SEEK_MODE_SET))
            assertEquals(128, source.read(128))
            assertEquals(2, openCalls.size)
            assertEquals(256, source.read(256))
            assertEquals(3, openCalls.size)
            assertEquals(
                OpenRecord(position = pageSize * 3L, length = C.LENGTH_UNSET.toLong(), writeCache = true),
                openCalls[2],
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun `abortReadForSeek causes seek calls to return RESULT_CANCELLED immediately`() {
        val payload = ByteArray(1024) { it.toByte() }
        val source = NeteaseStreamByteSource(
            songId = 1212L,
            allowCache = false,
            initialUpstream = "https://cdn.example/1212.flac",
            knownExpectedBytes = payload.size.toLong(),
            knownTotalBytes = payload.size.toLong(),
            upstreamRefresher = null,
            chunkSourceFactory = { _, _ -> FakeChunkSource(payload) },
            retryDelayMs = 0L,
        )
        try {
            assertEquals(-1L, source.seek(2048L, NeteaseStreamByteSource.SEEK_MODE_SET))
            source.abortReadForSeek(1L)
            assertEquals(
                NeteaseStreamByteSource.RESULT_CANCELLED.toLong(),
                source.seek(128L, NeteaseStreamByteSource.SEEK_MODE_SET),
            )
            assertEquals(
                NeteaseStreamByteSource.RESULT_CANCELLED.toLong(),
                source.seek(64L, NeteaseStreamByteSource.SEEK_MODE_CUR),
            )
            assertEquals(
                NeteaseStreamByteSource.RESULT_CANCELLED.toLong(),
                source.seek(-64L, NeteaseStreamByteSource.SEEK_MODE_END),
            )
            source.beginSeek(1L)
            assertEquals(128L, source.seek(128L, NeteaseStreamByteSource.SEEK_MODE_SET))
            source.endSeek(1L)
        } finally {
            source.close()
        }
    }

    @Test
    fun `abortReadForSeek interrupts in-flight upstreamRefresher immediately`() {
        val refreshStarted = CountDownLatch(1)
        val refreshCancelled = CountDownLatch(1)
        val source = NeteaseStreamByteSource(
            songId = 1313L,
            allowCache = false,
            initialUpstream = "https://cdn.example/1313.flac",
            knownExpectedBytes = 1024L,
            knownTotalBytes = 1024L,
            upstreamRefresher = { _, registerCancel ->
                val done = CountDownLatch(1)
                registerCancel {
                    refreshCancelled.countDown()
                    done.countDown()
                }
                refreshStarted.countDown()
                done.await(8, TimeUnit.SECONDS)
                null
            },
            chunkSourceFactory = { _, _ ->
                object : StreamChunkSource {
                    override fun open(upstream: String, position: Long): Long {
                        throw IOException("connection reset")
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
                    override fun close() = Unit
                }
            },
            retryDelayMs = 5_000L,
        )
        try {
            val abortThread = thread {
                assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))
                Thread.sleep(30)
                source.abortReadForSeek(1L)
            }
            val startedNs = System.nanoTime()
            val result = source.read(64)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
            abortThread.join(2_000)

            assertEquals(NeteaseStreamByteSource.RESULT_CANCELLED, result)
            assertTrue("expected refresh cancel callback", refreshCancelled.await(1, TimeUnit.SECONDS))
            assertTrue("expected fast refresh abort, took ${elapsedMs}ms", elapsedMs < 1_500L)
        } finally {
            source.close()
        }
    }

    @Test
    fun `effectiveStreamBufferingState hides transient buffering inside seek grace window`() {
        assertFalse(effectiveStreamBufferingState(nativeBuffering = false, nowElapsedMs = 5_000L, seekGraceUntilElapsedMs = 1_000L))
        assertFalse(effectiveStreamBufferingState(nativeBuffering = true, nowElapsedMs = 1_050L, seekGraceUntilElapsedMs = 1_200L))
        assertTrue(effectiveStreamBufferingState(nativeBuffering = true, nowElapsedMs = 1_200L, seekGraceUntilElapsedMs = 1_200L))
        assertTrue(effectiveStreamBufferingState(nativeBuffering = true, nowElapsedMs = 2_000L, seekGraceUntilElapsedMs = 0L))
    }

    @Test
    fun `buffering stall anchor resets on buffer growth and recovers when stalled`() {
        val t0 = 10_000L
        val a0 = nextBufferingStallAnchor(null, "track-1", 30_000L, 0L, t0)
        // Small noise < 100ms does not reset anchor.
        val a1 = nextBufferingStallAnchor(a0, "track-1", 30_000L, 40L, t0 + 5_000L)
        assertEquals(t0, a1.sinceElapsedMs)
        assertFalse(shouldRecoverStalledBuffering(a1, t0 + 5_000L, hasUpstreamError = false, wasIncomplete = false))

        // Upstream error with nearly empty buffer recovers after short threshold.
        assertTrue(shouldRecoverStalledBuffering(a1, t0 + BufferingErrorStallTimeoutMs, hasUpstreamError = true, wasIncomplete = false))

        // Pure stall without error recovers after full timeout.
        assertTrue(shouldRecoverStalledBuffering(a1, t0 + BufferingStallTimeoutMs, hasUpstreamError = false, wasIncomplete = false))

        // Meaningful buffer growth (>= 100ms) resets the stall timer.
        val a2 = nextBufferingStallAnchor(a1, "track-1", 30_000L, 250L, t0 + 10_000L)
        assertEquals(t0 + 10_000L, a2.sinceElapsedMs)
        assertFalse(shouldRecoverStalledBuffering(a2, t0 + 14_000L, hasUpstreamError = false, wasIncomplete = false))
    }
}

private data class OpenRecord(
    val position: Long,
    val length: Long,
    val writeCache: Boolean,
)

private class FakeRangeChunkSource(
    private val data: ByteArray,
    private val openCalls: MutableList<OpenRecord>,
) : StreamChunkSource {
    private var cursor = 0
    private var remaining = Long.MAX_VALUE

    override fun open(upstream: String, position: Long): Long =
        openRange(upstream, position, C.LENGTH_UNSET.toLong(), writeCache = true)

    override fun openRange(upstream: String, position: Long, length: Long, writeCache: Boolean): Long {
        openCalls += OpenRecord(position, length, writeCache)
        cursor = position.toInt().coerceIn(0, data.size)
        val avail = (data.size - cursor).toLong()
        return if (length == C.LENGTH_UNSET.toLong()) {
            remaining = avail
            avail
        } else {
            val bounded = minOf(length, avail)
            remaining = bounded
            bounded
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L || cursor >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length.toLong(), remaining, (data.size - cursor).toLong()).toInt()
        System.arraycopy(data, cursor, buffer, offset, count)
        cursor += count
        remaining -= count
        return count
    }

    override fun close() = Unit
}

private class FakeChunkSource(
    private val data: ByteArray,
    private val failAfterBytes: Int = Int.MAX_VALUE,
) : StreamChunkSource {
    private var cursor = 0
    private var readSoFar = 0

    override fun open(upstream: String, position: Long): Long {
        cursor = position.toInt().coerceIn(0, data.size)
        return (data.size - cursor).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readSoFar >= failAfterBytes) throw IOException("simulated mid-stream disconnect")
        if (cursor >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - cursor, failAfterBytes - readSoFar)
        System.arraycopy(data, cursor, buffer, offset, count)
        cursor += count
        readSoFar += count
        return count
    }

    override fun close() = Unit
}
