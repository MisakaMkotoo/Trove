package com.exodidio.tune.lyrics.autosync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 16 kHz mono float PCM 抽取（FFmpeg，本地解码，不上传）。 */
internal external fun nativeDecodePcm16k(source: String): FloatArray?

internal object AutoSyncPcm {
    const val SAMPLE_RATE = 16_000
    private const val TAG = "TuneAutoSync"

    /** Decodes a whole audio source (local path or URL) to 16 kHz mono floats. */
    suspend fun decode(source: String?): FloatArray? = withContext(Dispatchers.IO) {
        if (source.isNullOrBlank()) return@withContext null
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val pcm = runCatching { nativeDecodePcm16k(source) }
            .onFailure { Log.w(TAG, "PCM decode failed for $source", it) }
            .getOrNull()
        if (pcm != null) {
            Log.i(TAG, "decoded ${pcm.size} samples in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        }
        pcm
    }
}
