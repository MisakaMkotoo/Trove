package com.exodidio.tune.lyrics

import android.content.Context
import com.exodidio.tune.sync.writeTextAtomically
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PerSongLyricsConfig(
    val preferredSource: String? = null,
    val autoAlignOverride: Boolean? = null,
) {
    val preferredSourceId: LyricsSourceId?
        get() = LyricsSourceId.fromStorage(preferredSource)

    fun isEmpty(): Boolean = preferredSource == null && autoAlignOverride == null
}

internal fun applyPerSongLyricsConfig(
    settings: LyricsSettings,
    config: PerSongLyricsConfig?,
): LyricsSettings {
    if (config == null) return settings
    val preferred = config.preferredSourceId
    val nextOrder = if (preferred != null) {
        listOf(preferred) + settings.sourceOrder.filterNot { it == preferred }
    } else {
        settings.sourceOrder
    }
    val nextEnabled = if (preferred != null) {
        settings.enabledSources + preferred
    } else {
        settings.enabledSources
    }
    val nextAutoAlign = config.autoAlignOverride ?: settings.autoAlignWordTiming
    return settings.copy(
        sourceOrder = nextOrder,
        enabledSources = nextEnabled,
        autoAlignWordTiming = nextAutoAlign,
    )
}

/**
 * 每首歌独立歌词配置持久化存储（JSON 原子写入，全部磁盘 I/O 在 Dispatchers.IO 执行）。
 */
internal object PerSongLyricsConfigStore {
    private const val FILE_NAME = "per_song_lyrics_config.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val loaded = AtomicBoolean(false)
    private val loadMutex = Mutex()
    private val writeMutex = Mutex()
    private val map = ConcurrentHashMap<String, PerSongLyricsConfig>()
    private val _configs = MutableStateFlow<Map<String, PerSongLyricsConfig>>(emptyMap())
    val configs: StateFlow<Map<String, PerSongLyricsConfig>> = _configs.asStateFlow()

    suspend fun load(context: Context) {
        if (loaded.get()) return
        loadMutex.withLock {
            if (loaded.get()) return
            val appContext = context.applicationContext
            val parsed = withContext(Dispatchers.IO) {
                val file = File(appContext.filesDir, FILE_NAME)
                if (!file.isFile) return@withContext emptyMap<String, PerSongLyricsConfig>()
                runCatching {
                    json.decodeFromString<Map<String, PerSongLyricsConfig>>(file.readText(Charsets.UTF_8))
                }.getOrDefault(emptyMap())
            }
            map.clear()
            parsed.forEach { (trackId, config) ->
                if (trackId.isNotBlank() && !config.isEmpty()) {
                    map[trackId] = config
                }
            }
            _configs.value = map.toMap()
            loaded.set(true)
        }
    }

    fun configFor(trackId: String): PerSongLyricsConfig? = map[trackId]

    suspend fun setPreferredSource(context: Context, trackId: String, source: LyricsSourceId?) {
        if (trackId.isBlank()) return
        load(context)
        val current = map[trackId] ?: PerSongLyricsConfig()
        val updated = current.copy(preferredSource = source?.storageValue)
        updateEntry(context, trackId, updated)
    }

    suspend fun setAutoAlignOverride(context: Context, trackId: String, enabled: Boolean?) {
        if (trackId.isBlank()) return
        load(context)
        val current = map[trackId] ?: PerSongLyricsConfig()
        val updated = current.copy(autoAlignOverride = enabled)
        updateEntry(context, trackId, updated)
    }

    suspend fun clearTrack(context: Context, trackId: String) {
        if (trackId.isBlank()) return
        load(context)
        if (map.remove(trackId) != null) {
            val snapshot = map.toMap()
            _configs.value = snapshot
            persist(context, snapshot)
        }
    }

    suspend fun clearAll(context: Context) {
        load(context)
        map.clear()
        _configs.value = emptyMap()
        persist(context, emptyMap())
    }

    fun exportJson(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (map.isNotEmpty()) {
            return json.encodeToString(map.toMap())
        }
        return if (file.isFile) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("{}")
        } else {
            "{}"
        }
    }

    suspend fun restoreFromJson(context: Context, rawJson: String) {
        val parsed = runCatching {
            json.decodeFromString<Map<String, PerSongLyricsConfig>>(rawJson)
        }.getOrDefault(emptyMap())
        map.clear()
        parsed.forEach { (trackId, config) ->
            if (trackId.isNotBlank() && !config.isEmpty()) {
                map[trackId] = config
            }
        }
        val snapshot = map.toMap()
        _configs.value = snapshot
        loaded.set(true)
        persist(context, snapshot)
    }

    private suspend fun updateEntry(context: Context, trackId: String, updated: PerSongLyricsConfig) {
        if (updated.isEmpty()) {
            map.remove(trackId)
        } else {
            map[trackId] = updated
        }
        val snapshot = map.toMap()
        _configs.value = snapshot
        persist(context, snapshot)
    }

    private suspend fun persist(context: Context, snapshot: Map<String, PerSongLyricsConfig>) {
        val appContext = context.applicationContext
        val encoded = json.encodeToString(snapshot)
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                runCatching {
                    File(appContext.filesDir, FILE_NAME).writeTextAtomically(encoded)
                }
            }
        }
    }
}
