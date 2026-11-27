package com.exodidio.tune.lyrics.autosync

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Append-only trace for model/dictionary installs. Release builds have no
 * logcat access, so failures are diagnosed from this file
 * (`Android/data/<pkg>/files/autosync-trace.log`).
 */
internal object AutoSyncTrace {
    private const val TAG = "TuneAutoSync"
    private const val FILE_NAME = "autosync-trace.log"
    private const val MAX_BYTES = 512L * 1024L
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
            .withZone(ZoneId.systemDefault())
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeQueue = Channel<Pair<Context, String>>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        writerScope.launch {
            for ((app, text) in writeQueue) {
                runCatching {
                    val file = File(app.getExternalFilesDir(null) ?: app.filesDir, FILE_NAME)
                    if (file.isFile && file.length() > MAX_BYTES) file.delete()
                    file.appendText(text)
                }
            }
        }
    }

    fun log(context: Context, message: String) {
        Log.i(TAG, message)
        val stamp = formatter.format(Instant.now())
        writeQueue.trySend(context.applicationContext to "$stamp $message\n")
    }

    fun log(context: Context, message: String, error: Throwable) {
        val header = "$message: ${error.javaClass.simpleName}: ${error.message}"
        Log.i(TAG, header)
        val stamp = formatter.format(Instant.now())
        val frames = error.stackTraceToString().lineSequence()
            .filter { it.trimStart().startsWith("at ") }
            .take(12)
            .toList()
        frames.forEach { frame -> Log.i(TAG, "  $frame") }
        val payload = buildString {
            append(stamp).append(' ').append(header).append('\n')
            frames.forEach { frame ->
                append(stamp).append("   ").append(frame).append('\n')
            }
        }
        writeQueue.trySend(context.applicationContext to payload)
    }
}
