package com.exodidio.tune.lyrics

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal const val MinLyricOffsetMs = -15_000L
internal const val MaxLyricOffsetMs = 15_000L

internal fun clampLyricOffsetMs(offsetMs: Long): Long =
    offsetMs.coerceIn(MinLyricOffsetMs, MaxLyricOffsetMs)

internal fun serializeLyricOffsets(offsets: Map<String, Long>): String =
    Json.encodeToString(offsets.filterValues { it != 0L })

internal fun parseLyricOffsets(raw: String): Map<String, Long> =
    runCatching {
        if (raw.isBlank()) emptyMap()
        else Json.decodeFromString<Map<String, Long>>(raw)
            .mapValues { (_, value) -> clampLyricOffsetMs(value) }
            .filterValues { it != 0L }
    }.getOrElse { emptyMap() }

/**
 * 单曲歌词时间轴微调持久化存储。
 * 正值表示歌词延后（例如 +500ms 时，原 [00:10.00] 的歌词在 10.50s 才触发）；
 * 负值表示歌词提前（例如 -500ms 时，原 [00:10.00] 的歌词在 9.50s 触发）。
 * 磁盘读写全部在 Dispatchers.IO 的单消费者协程中异步执行，不阻塞 UI 线程且不持锁执行 I/O。
 */
internal object LyricOffsetStore {
    private const val FileName = "lyric_offsets.json"
    private const val LogTag = "LyricOffsetStore"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)
    private val writeRequests = Channel<Pair<File, Map<String, Long>>>(Channel.CONFLATED)

    private val _offsets = MutableStateFlow<Map<String, Long>>(emptyMap())
    val offsets: StateFlow<Map<String, Long>> = _offsets.asStateFlow()

    init {
        ioScope.launch {
            for ((targetFile, snapshot) in writeRequests) {
                writeSnapshotToFile(targetFile, snapshot)
            }
        }
    }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        ioScope.launch {
            val file = File(appContext.filesDir, FileName)
            val diskOffsets = runCatching {
                if (file.exists()) parseLyricOffsets(file.readText(Charsets.UTF_8)) else emptyMap()
            }.onFailure { error ->
                Log.w(LogTag, "Failed to read lyric offsets", error)
            }.getOrDefault(emptyMap())
            if (diskOffsets.isNotEmpty()) {
                _offsets.update { current ->
                    // 用户在初始化完成前刚修改过的值优先保留。
                    diskOffsets + current
                }
            }
        }
    }

    fun offsetFor(trackId: String?): Long =
        if (trackId.isNullOrBlank()) 0L else _offsets.value[trackId] ?: 0L

    fun setOffset(context: Context, trackId: String, offsetMs: Long) {
        if (trackId.isBlank()) return
        val clamped = clampLyricOffsetMs(offsetMs)
        var latestSnapshot: Map<String, Long> = emptyMap()
        _offsets.update { current ->
            val next = if (clamped == 0L) current - trackId else current + (trackId to clamped)
            latestSnapshot = next
            next
        }
        val targetFile = File(context.applicationContext.filesDir, FileName)
        writeRequests.trySend(targetFile to latestSnapshot)
    }

    fun nudgeOffset(context: Context, trackId: String, deltaMs: Long) {
        if (trackId.isBlank()) return
        val nextValue = clampLyricOffsetMs(offsetFor(trackId) + deltaMs)
        setOffset(context, trackId, nextValue)
    }

    fun resetOffset(context: Context, trackId: String) {
        setOffset(context, trackId, 0L)
    }

    fun exportJson(): String = serializeLyricOffsets(_offsets.value)

    suspend fun restoreFromJson(context: Context, rawJson: String) {
        val parsed = parseLyricOffsets(rawJson)
        _offsets.value = parsed
        val targetFile = File(context.applicationContext.filesDir, FileName)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            writeSnapshotToFile(targetFile, parsed)
        }
    }

    private fun writeSnapshotToFile(targetFile: File, snapshot: Map<String, Long>) {
        runCatching {
            val parent = targetFile.parentFile ?: return
            if (!parent.exists()) parent.mkdirs()
            val temp = File(parent, "$FileName.tmp")
            temp.writeText(serializeLyricOffsets(snapshot), Charsets.UTF_8)
            if (!temp.renameTo(targetFile)) {
                targetFile.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
        }.onFailure { error ->
            Log.w(LogTag, "Failed to persist lyric offsets", error)
        }
    }
}
