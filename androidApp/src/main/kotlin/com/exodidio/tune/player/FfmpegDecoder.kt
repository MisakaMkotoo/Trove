package com.exodidio.tune.player

import android.os.ParcelFileDescriptor
import com.exodidio.tune.netease.NeteaseStreamByteSource
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * Thin JNI boundary. FFmpeg owns both demuxing and decoding; Android only
 * receives already-decoded float PCM through AAudio.
 */
internal class FfmpegDecoder : Closeable {
    private var handle: Long = nativeCreate()

    fun prepare(file: File, normalizationGainDb: Float = 0f, hasStaticAnalysis: Boolean = false) {
        check(handle != 0L) { "Decoder is closed" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            nativePrepare(handle, descriptor.detachFd(), normalizationGainDb, hasStaticAnalysis)
        }
    }

    /** Opens and decodes the next item into the engine's idle source slot. */
    fun preload(file: File, normalizationGainDb: Float = 0f, hasStaticAnalysis: Boolean = false): Boolean {
        check(handle != 0L) { "Decoder is closed" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            return nativePreload(handle, descriptor.detachFd(), normalizationGainDb, hasStaticAnalysis)
        }
    }

    /** 通过进程内 AVIOContext + DirectByteBuffer 打开网易云流式数据源，不走本地 HTTP 代理。 */
    fun prepareStream(
        source: NeteaseStreamByteSource,
        normalizationGainDb: Float = 0f,
        hasStaticAnalysis: Boolean = false,
    ) {
        check(handle != 0L) { "Decoder is closed" }
        runCatching {
            nativePrepareStream(handle, source, source.directBuffer, normalizationGainDb, hasStaticAnalysis)
        }.onFailure {
            source.close()
        }.getOrThrow()
    }

    /** 在空闲 slot 预加载网易云流式数据源，支持在线曲目的无停顿衔接与淡入淡出。 */
    fun preloadStream(
        source: NeteaseStreamByteSource,
        normalizationGainDb: Float = 0f,
        hasStaticAnalysis: Boolean = false,
    ): Boolean {
        check(handle != 0L) { "Decoder is closed" }
        return runCatching {
            nativePreloadStream(handle, source, source.directBuffer, normalizationGainDb, hasStaticAnalysis)
        }.onFailure {
            source.close()
        }.getOrThrow()
    }

    /** Opens a MediaStore content Uri through the same native fd path. Caller transfers fd ownership. */
    fun prepareFd(fd: Int, normalizationGainDb: Float = 0f, hasStaticAnalysis: Boolean = false) {
        if (handle == 0L) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            error("Decoder is closed")
        }
        nativePrepare(handle, fd, normalizationGainDb, hasStaticAnalysis)
    }

    fun preloadFd(fd: Int, normalizationGainDb: Float = 0f, hasStaticAnalysis: Boolean = false): Boolean {
        if (handle == 0L) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            error("Decoder is closed")
        }
        return nativePreload(handle, fd, normalizationGainDb, hasStaticAnalysis)
    }

    fun setNormalizationConfig(settings: NormalizationSettings) {
        nativeSetNormalizationConfig(requireHandle(), settings.enabled, settings.targetLufs, settings.preventClip)
    }

    fun setNormalizationGains(
        activeDb: Float,
        activeHasStatic: Boolean,
        preloadedDb: Float,
        preloadedHasStatic: Boolean,
    ) {
        nativeSetNormalizationGains(requireHandle(), activeDb, activeHasStatic, preloadedDb, preloadedHasStatic)
    }

    fun setFocusGain(gain: Float) = nativeSetFocusGain(requireHandle(), gain)

    fun clearPreloaded() = nativeClearPreloaded(requireHandle())
    fun hasPreloaded(): Boolean = nativeHasPreloaded(requireHandle())
    fun beginCrossfade(durationMs: Long) = nativeBeginCrossfade(requireHandle(), durationMs)
    fun finishCrossfade() = nativeFinishCrossfade(requireHandle())
    fun snapCrossfade() = nativeSnapCrossfade(requireHandle())
    fun isCrossfading(): Boolean = nativeIsCrossfading(requireHandle())
    fun consumeTransition(): NativeTransition? = when (nativeConsumeTransition(requireHandle())) {
        NativeTransition.GaplessPromoted.code -> NativeTransition.GaplessPromoted
        NativeTransition.CrossfadeStarted.code -> NativeTransition.CrossfadeStarted
        else -> null
    }
    fun setGlobalDspConfig(config: GlobalDspConfig) = nativeSetGlobalDspConfig(
        requireHandle(), config.preampGainDb, config.stereoWidth, config.eqBandGainsDb,
    )

    fun play() {
        val h = requireHandle()
        nativeCancelCassetteSpinDown(h)
        nativePlay(h)
    }
    fun pause() = nativePause(requireHandle())
    fun stop() = nativeStop(requireHandle())
    fun setSpeed(speed: Float) = nativeSetSpeed(requireHandle(), speed.coerceIn(0.5f, 2.0f))
    fun setCassetteEnabled(enabled: Boolean) {
        val current = handle
        if (current != 0L) nativeSetCassetteEnabled(current, enabled)
    }
    fun setCassetteScrub(active: Boolean, pitchFactor: Float) {
        val current = handle
        if (current != 0L) nativeSetCassetteScrub(current, active, pitchFactor)
    }
    fun triggerCassetteSpinDown(durationMs: Int = 220) {
        val current = handle
        if (current != 0L) nativeTriggerCassetteSpinDown(current, durationMs)
    }
    fun cancelCassetteSpinDown() {
        val current = handle
        if (current != 0L) nativeCancelCassetteSpinDown(current)
    }
    fun triggerRadioCrosstalk(durationMs: Int = 500) {
        val current = handle
        if (current != 0L) nativeTriggerRadioCrosstalk(current, durationMs)
    }
    fun seekTo(positionMs: Long) = nativeSeekTo(requireHandle(), positionMs)
    fun durationMs(): Long = nativeDurationMs(requireHandle())
    fun positionMs(): Long = nativePositionMs(requireHandle())
    fun preloadedDurationMs(): Long = nativePreloadedDurationMs(requireHandle())
    fun preloadedPositionMs(): Long = nativePreloadedPositionMs(requireHandle())
    fun isFinished(): Boolean = nativeIsFinished(requireHandle())
    fun isBuffering(): Boolean = nativeIsBuffering(requireHandle())
    fun bufferedAheadMs(): Long = nativeBufferedAheadMs(requireHandle())
    fun isOutputDisconnected(): Boolean = nativeIsOutputDisconnected(requireHandle())
    fun readAudioLevels(outLevels: FloatArray): Boolean {
        val current = handle
        if (current == 0L || outLevels.size < 7) return false
        return nativeReadAudioLevels(current, outLevels)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    private fun requireHandle(): Long = checkNotNull(handle.takeIf { it != 0L }) { "Decoder is closed" }

    companion object {
        init { System.loadLibrary("tune_player") }

        fun analyzeFile(file: File): TrackAnalysis? = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                nativeAnalyzeFd(descriptor.detachFd())?.let { result ->
                    if (result.size >= 2) TrackAnalysis(result[0], result[1]) else null
                }
            }
        }.getOrNull()

        fun analyzeFd(fd: Int): TrackAnalysis? = runCatching {
            nativeAnalyzeFd(fd)?.let { result ->
                if (result.size >= 2) TrackAnalysis(result[0], result[1]) else null
            }
        }.getOrNull()

        fun analyzeStream(source: NeteaseStreamByteSource): TrackAnalysis? = runCatching {
            nativeAnalyzeStream(source, source.directBuffer)?.let { result ->
                if (result.size >= 2) TrackAnalysis(result[0], result[1]) else null
            }
        }.also {
            runCatching { source.close() }
        }.getOrNull()

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativePrepare(
            handle: Long,
            fd: Int,
            normalizationGainDb: Float,
            hasStaticAnalysis: Boolean,
        )

        @JvmStatic private external fun nativePrepareStream(
            handle: Long,
            source: NeteaseStreamByteSource,
            directBuffer: ByteBuffer,
            normalizationGainDb: Float,
            hasStaticAnalysis: Boolean,
        )
        @JvmStatic private external fun nativePreload(
            handle: Long,
            fd: Int,
            normalizationGainDb: Float,
            hasStaticAnalysis: Boolean,
        ): Boolean
        @JvmStatic private external fun nativePreloadStream(
            handle: Long,
            source: NeteaseStreamByteSource,
            directBuffer: ByteBuffer,
            normalizationGainDb: Float,
            hasStaticAnalysis: Boolean,
        ): Boolean
        @JvmStatic private external fun nativeSetNormalizationConfig(
            handle: Long,
            enabled: Boolean,
            targetLufs: Float,
            preventClip: Boolean,
        )
        @JvmStatic private external fun nativeSetNormalizationGains(
            handle: Long,
            activeDb: Float,
            activeHasStatic: Boolean,
            preloadedDb: Float,
            preloadedHasStatic: Boolean,
        )
        @JvmStatic private external fun nativeAnalyzeFd(fd: Int): FloatArray?
        @JvmStatic private external fun nativeAnalyzeStream(
            source: NeteaseStreamByteSource,
            directBuffer: ByteBuffer,
        ): FloatArray?
        @JvmStatic private external fun nativeSetFocusGain(handle: Long, gain: Float)
        @JvmStatic private external fun nativeClearPreloaded(handle: Long)
        @JvmStatic private external fun nativeHasPreloaded(handle: Long): Boolean
        @JvmStatic private external fun nativeBeginCrossfade(handle: Long, durationMs: Long)
        @JvmStatic private external fun nativeFinishCrossfade(handle: Long)
        @JvmStatic private external fun nativeSnapCrossfade(handle: Long)
        @JvmStatic private external fun nativeIsCrossfading(handle: Long): Boolean
        @JvmStatic private external fun nativeConsumeTransition(handle: Long): Int
        @JvmStatic private external fun nativeSetGlobalDspConfig(handle: Long, preampGainDb: Float, stereoWidth: Float, eqBandGainsDb: FloatArray)
        @JvmStatic private external fun nativePlay(handle: Long)
        @JvmStatic private external fun nativePause(handle: Long)
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeSetSpeed(handle: Long, speed: Float)
        @JvmStatic private external fun nativeSeekTo(handle: Long, positionMs: Long)
        @JvmStatic private external fun nativeDurationMs(handle: Long): Long
        @JvmStatic private external fun nativePositionMs(handle: Long): Long
        @JvmStatic private external fun nativePreloadedDurationMs(handle: Long): Long
        @JvmStatic private external fun nativePreloadedPositionMs(handle: Long): Long
        @JvmStatic private external fun nativeIsFinished(handle: Long): Boolean
        @JvmStatic private external fun nativeIsBuffering(handle: Long): Boolean
        @JvmStatic private external fun nativeBufferedAheadMs(handle: Long): Long
        @JvmStatic private external fun nativeIsOutputDisconnected(handle: Long): Boolean
        @JvmStatic private external fun nativeSetCassetteEnabled(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeSetCassetteScrub(handle: Long, active: Boolean, pitchFactor: Float)
        @JvmStatic private external fun nativeTriggerCassetteSpinDown(handle: Long, durationMs: Int)
        @JvmStatic private external fun nativeCancelCassetteSpinDown(handle: Long)
        @JvmStatic private external fun nativeTriggerRadioCrosstalk(handle: Long, durationMs: Int)
        @JvmStatic private external fun nativeReadAudioLevels(handle: Long, outLevels: FloatArray): Boolean
    }
}

internal enum class NativeTransition(internal val code: Int) {
    GaplessPromoted(1),
    CrossfadeStarted(2),
}

/** Reserved global post-mix DSP snapshot. The native stage is neutral today. */
internal data class GlobalDspConfig(
    val preampGainDb: Float = 0f,
    val stereoWidth: Float = 1f,
    val eqBandGainsDb: FloatArray = FloatArray(10),
)