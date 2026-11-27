package com.exodidio.tune.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.exodidio.tune.library.scanFolderDataStore
import com.exodidio.tune.lyrics.LyricOffsetStore
import com.exodidio.tune.lyrics.PerSongLyricsConfigStore
import com.exodidio.tune.lyrics.lyricsDataStore
import com.exodidio.tune.player.playbackPreferencesDataStore
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.ui.screens.libraryAlbumsPreferencesDataStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class BackupManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appPackage: String = "",
    val createdAt: String = "",
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
internal data class BackupPreferenceEntry(
    val type: String,
    val value: String = "",
    val listValue: List<String> = emptyList(),
)

@Serializable
internal data class BackupPreferencesSnapshot(
    val stores: Map<String, Map<String, BackupPreferenceEntry>> = emptyMap(),
)

internal data class BackupSummary(
    val playlistCount: Int,
    val favoriteCount: Int,
)

/**
 * 全量配置与用户数据一键备份/恢复（ZIP）：
 * - 备份包含：外观/播放/歌词/扫描/布局 DataStore、本地与智能歌单、收藏、听歌统计、歌词偏移、单曲配置、自定义歌单/歌手封面。
 * - 严格排除：网易云 Cookie/账号文件、电脑库配对凭证/TLS 证书、音频与图片缓存。
 * - 全部压缩/解压与文件读写在 Dispatchers.IO 执行，恢复时严格校验 Zip-Slip 与大小上限。
 */
internal object AppBackupManager {
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val PREFERENCES_ENTRY = "preferences.json"
    private const val LIBRARY_STATE_ENTRY = "local-library-state.json"
    private const val LISTENING_STATS_ENTRY = "listening-stats.json"
    private const val LYRIC_OFFSETS_ENTRY = "lyric_offsets.json"
    private const val PER_SONG_LYRICS_ENTRY = "per_song_lyrics_config.json"
    private const val ARTWORK_PREFIX = "artwork/"

    private const val MAX_ZIP_ENTRIES = 2_000
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun exportBackup(
        context: Context,
        uri: Uri,
        syncStore: AndroidLibrarySyncStore,
    ): Result<BackupSummary> = runCatching {
        val appContext = context.applicationContext
        val libraryPayloads = syncStore.exportBackupPayloads()
        val preferencesSnapshot = exportAllPreferences(appContext)
        val lyricOffsetsJson = LyricOffsetStore.exportJson()
        val perSongLyricsJson = PerSongLyricsConfigStore.exportJson(appContext)
        val manifestJson = json.encodeToString(
            BackupManifest(
                schemaVersion = BackupManifest.CURRENT_SCHEMA_VERSION,
                appPackage = appContext.packageName,
                createdAt = Instant.now().toString(),
            ),
        )
        val prefsJson = json.encodeToString(preferencesSnapshot)

        withContext(Dispatchers.IO) {
            val stream = appContext.contentResolver.openOutputStream(uri)
                ?: error("无法写入所选文件")
            stream.use { rawOut ->
                ZipOutputStream(BufferedOutputStream(rawOut)).use { zip ->
                    writeTextEntry(zip, MANIFEST_ENTRY, manifestJson)
                    writeTextEntry(zip, PREFERENCES_ENTRY, prefsJson)
                    writeTextEntry(zip, LIBRARY_STATE_ENTRY, libraryPayloads.stateJson)
                    writeTextEntry(zip, LISTENING_STATS_ENTRY, libraryPayloads.listeningJson)
                    writeTextEntry(zip, LYRIC_OFFSETS_ENTRY, lyricOffsetsJson)
                    writeTextEntry(zip, PER_SONG_LYRICS_ENTRY, perSongLyricsJson)

                    writeDirectoryToZip(
                        zip = zip,
                        rootDir = File(appContext.filesDir, "playlist_artwork"),
                        entryPrefix = "${ARTWORK_PREFIX}playlist_artwork/",
                    )
                    writeDirectoryToZip(
                        zip = zip,
                        rootDir = File(appContext.filesDir, "artist_artwork"),
                        entryPrefix = "${ARTWORK_PREFIX}artist_artwork/",
                    )
                }
            }
        }
        BackupSummary(
            playlistCount = syncStore.playlists.value.size,
            favoriteCount = syncStore.favoriteIds.value.size,
        )
    }

    suspend fun restoreBackup(
        context: Context,
        uri: Uri,
        syncStore: AndroidLibrarySyncStore,
    ): Result<BackupSummary> = runCatching {
        val appContext = context.applicationContext
        val extracted = withContext(Dispatchers.IO) {
            extractBackupArchive(appContext, uri)
        }
        val manifestText = extracted.textEntries[MANIFEST_ENTRY]
            ?: error("备份包缺少 manifest.json")
        val manifest = json.decodeFromString<BackupManifest>(manifestText)
        if (manifest.schemaVersion !in 1..BackupManifest.CURRENT_SCHEMA_VERSION) {
            error("不支持的备份格式版本：${manifest.schemaVersion}")
        }

        // 1. 恢复自定义封面文件到 filesDir
        withContext(Dispatchers.IO) {
            if (extracted.artworkDir.isDirectory) {
                copyDirectoryContents(extracted.artworkDir, appContext.filesDir)
            }
            extracted.stagingDir.deleteRecursively()
        }

        // 2. 恢复所有 DataStore（内存状态立即生效）
        extracted.textEntries[PREFERENCES_ENTRY]?.let { prefsText ->
            val snapshot = json.decodeFromString<BackupPreferencesSnapshot>(prefsText)
            restoreAllPreferences(appContext, snapshot)
        }

        // 3. 恢复歌词偏移与单曲独立配置
        extracted.textEntries[LYRIC_OFFSETS_ENTRY]?.let { offsetsText ->
            LyricOffsetStore.restoreFromJson(appContext, offsetsText)
        }
        extracted.textEntries[PER_SONG_LYRICS_ENTRY]?.let { perSongText ->
            PerSongLyricsConfigStore.restoreFromJson(appContext, perSongText)
        }

        // 4. 恢复曲库收藏、隐藏、歌单/智能歌单、听歌统计
        val (playlistsCount, favoritesCount) = syncStore.restoreFromBackup(
            stateJson = extracted.textEntries[LIBRARY_STATE_ENTRY],
            listeningJson = extracted.textEntries[LISTENING_STATS_ENTRY],
        )
        BackupSummary(
            playlistCount = playlistsCount,
            favoriteCount = favoritesCount,
        )
    }

    private data class ExtractedBackup(
        val stagingDir: File,
        val artworkDir: File,
        val textEntries: Map<String, String>,
    )

    private fun extractBackupArchive(context: Context, uri: Uri): ExtractedBackup {
        val stagingDir = File(context.cacheDir, "backup-restore-${System.currentTimeMillis()}").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val artworkDir = File(stagingDir, "artwork").apply { mkdirs() }
        val textEntries = mutableMapOf<String, String>()
        var entryCount = 0
        var totalBytes = 0L

        val input = context.contentResolver.openInputStream(uri)
            ?: error("无法读取所选备份文件")
        try {
            input.use { rawIn ->
                ZipInputStream(BufferedInputStream(rawIn)).use { zip ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entryCount += 1
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            error("备份文件条目数过多")
                        }
                        val name = entry.name
                        if (!isSafeBackupZipPath(name)) {
                            error("检测到非法路径条目：$name")
                        }
                        if (!entry.isDirectory) {
                            when {
                                name == MANIFEST_ENTRY ||
                                    name == PREFERENCES_ENTRY ||
                                    name == LIBRARY_STATE_ENTRY ||
                                    name == LISTENING_STATS_ENTRY ||
                                    name == LYRIC_OFFSETS_ENTRY ||
                                    name == PER_SONG_LYRICS_ENTRY -> {
                                    val out = ByteArrayOutputStream()
                                    while (true) {
                                        val read = zip.read(buffer)
                                        if (read <= 0) break
                                        totalBytes += read
                                        if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                            error("备份文件解压体积超出限制")
                                        }
                                        out.write(buffer, 0, read)
                                    }
                                    textEntries[name] = out.toString(Charsets.UTF_8.name())
                                }
                                name.startsWith("${ARTWORK_PREFIX}playlist_artwork/") ||
                                    name.startsWith("${ARTWORK_PREFIX}artist_artwork/") -> {
                                    val relative = name.removePrefix(ARTWORK_PREFIX)
                                    val target = validateBackupTargetFile(artworkDir, relative)
                                        ?: error("检测到越界封面路径：$name")
                                    target.parentFile?.mkdirs()
                                    target.outputStream().buffered().use { out ->
                                        while (true) {
                                            val read = zip.read(buffer)
                                            if (read <= 0) break
                                            totalBytes += read
                                            if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                                error("备份文件解压体积超出限制")
                                            }
                                            out.write(buffer, 0, read)
                                        }
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        } catch (error: Throwable) {
            stagingDir.deleteRecursively()
            throw error
        }
        return ExtractedBackup(
            stagingDir = stagingDir,
            artworkDir = artworkDir,
            textEntries = textEntries,
        )
    }

    private fun dataStores(context: Context): Map<String, DataStore<Preferences>> = mapOf(
        "appearance" to context.themeDataStore,
        "lyrics" to context.lyricsDataStore,
        "playback_preferences" to context.playbackPreferencesDataStore,
        "scan_folders" to context.scanFolderDataStore,
        "library_albums" to context.libraryAlbumsPreferencesDataStore,
    )

    private suspend fun exportAllPreferences(context: Context): BackupPreferencesSnapshot {
        val result = linkedMapOf<String, Map<String, BackupPreferenceEntry>>()
        for ((storeName, store) in dataStores(context)) {
            val prefs = store.data.first()
            val serialized = linkedMapOf<String, BackupPreferenceEntry>()
            prefs.asMap().forEach { (key, value) ->
                when (value) {
                    is Boolean -> serialized[key.name] = BackupPreferenceEntry("bool", value.toString())
                    is Int -> serialized[key.name] = BackupPreferenceEntry("int", value.toString())
                    is Long -> serialized[key.name] = BackupPreferenceEntry("long", value.toString())
                    is Float -> serialized[key.name] = BackupPreferenceEntry("float", value.toString())
                    is Double -> serialized[key.name] = BackupPreferenceEntry("double", value.toString())
                    is String -> serialized[key.name] = BackupPreferenceEntry("string", value)
                    is Set<*> -> {
                        val strings = value.mapNotNull { it as? String }
                        serialized[key.name] = BackupPreferenceEntry("string_set", listValue = strings)
                    }
                }
            }
            result[storeName] = serialized
        }
        return BackupPreferencesSnapshot(result)
    }

    private suspend fun restoreAllPreferences(context: Context, snapshot: BackupPreferencesSnapshot) {
        val stores = dataStores(context)
        for ((storeName, entries) in snapshot.stores) {
            val store = stores[storeName] ?: continue
            store.edit { mutablePrefs ->
                mutablePrefs.clear()
                entries.forEach { (keyName, entry) ->
                    when (entry.type) {
                        "bool" -> entry.value.toBooleanStrictOrNull()?.let {
                            mutablePrefs[booleanPreferencesKey(keyName)] = it
                        }
                        "int" -> entry.value.toIntOrNull()?.let {
                            mutablePrefs[intPreferencesKey(keyName)] = it
                        }
                        "long" -> entry.value.toLongOrNull()?.let {
                            mutablePrefs[longPreferencesKey(keyName)] = it
                        }
                        "float" -> entry.value.toFloatOrNull()?.let {
                            mutablePrefs[floatPreferencesKey(keyName)] = it
                        }
                        "double" -> entry.value.toDoubleOrNull()?.let {
                            mutablePrefs[doublePreferencesKey(keyName)] = it
                        }
                        "string" -> mutablePrefs[stringPreferencesKey(keyName)] = entry.value
                        "string_set" -> mutablePrefs[stringSetPreferencesKey(keyName)] = entry.listValue.toSet()
                    }
                }
            }
        }
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeDirectoryToZip(zip: ZipOutputStream, rootDir: File, entryPrefix: String) {
        if (!rootDir.isDirectory) return
        val files = rootDir.listFiles().orEmpty()
        for (file in files) {
            if (!file.isFile) continue
            zip.putNextEntry(ZipEntry("$entryPrefix${file.name}"))
            file.inputStream().buffered().use { input ->
                input.copyTo(zip)
            }
            zip.closeEntry()
        }
    }

    private fun copyDirectoryContents(srcDir: File, destRoot: File) {
        srcDir.listFiles().orEmpty().forEach { child ->
            val target = validateBackupTargetFile(destRoot, child.name) ?: return@forEach
            if (child.isDirectory) {
                target.mkdirs()
                copyDirectoryContents(child, target)
            } else if (child.isFile) {
                target.parentFile?.mkdirs()
                child.copyTo(target, overwrite = true)
            }
        }
    }
}

internal fun isSafeBackupZipPath(entryName: String): Boolean {
    if (entryName.isBlank()) return false
    if (entryName.startsWith("/") || entryName.startsWith("\\") || entryName.contains('\\')) return false
    val segments = entryName.split('/')
    return segments.none { it == ".." || it == "." && segments.size > 1 }
}

internal fun validateBackupTargetFile(rootDir: File, relativePath: String): File? {
    if (!isSafeBackupZipPath(relativePath)) return null
    val canonicalRoot = rootDir.canonicalFile
    val candidate = File(canonicalRoot, relativePath).canonicalFile
    val rootPrefix = canonicalRoot.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
    return candidate.takeIf { it.path.startsWith(rootPrefix) }
}
