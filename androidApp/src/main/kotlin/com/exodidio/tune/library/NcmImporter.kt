package com.exodidio.tune.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.albumIdFor
import com.exodidio.tune.sync.artistIdFor
import com.exodidio.tune.sync.writeTextAtomically
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class NcmFile(
    val uri: Uri,
    val name: String,
    val size: Long,
)

internal data class NcmImportSummary(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
)

internal class NcmImportPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ncm_import", Context.MODE_PRIVATE)

    var folderUri: String?
        get() = prefs.getString("folder", null)
        private set(value) {
            prefs.edit().apply { if (value.isNullOrBlank()) remove("folder") else putString("folder", value) }.apply()
        }

    fun setFolder(value: String?) {
        folderUri = value
    }
}

/**
 * 扫描用户选中的 SAF 文件夹并导入 .ncm。解码与落盘都在 IO 线程，
 * 解码结果按源 URI 哈希缓存，重复导入直接复用。
 */
internal class NcmImporter(private val context: Context) {

    @Serializable
    private data class CachedNcm(
        val audioName: String,
        val coverName: String? = null,
        val title: String = "",
        val artists: List<String> = emptyList(),
        val album: String = "",
        val format: String = "",
        val durationMs: Long = 0L,
    )

    suspend fun scan(treeUri: Uri): List<NcmFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val found = mutableListOf<NcmFile>()
        walk(root, found)
        found.sortedBy { it.name.lowercase() }
    }

    private fun walk(directory: DocumentFile, out: MutableList<NcmFile>) {
        val children = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (child.isDirectory) {
                walk(child, out)
            } else if (NcmDecoder.isNcm(child.name)) {
                out += NcmFile(uri = child.uri, name = child.name.orEmpty(), size = child.length())
            }
        }
    }

    suspend fun import(
        files: List<NcmFile>,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): NcmImportSummary = withContext(Dispatchers.IO) {
        val store = AndroidSyncRuntime.syncStore
        val outputDir = File(context.filesDir, "imported-ncm").apply { mkdirs() }
        val merged = store.importedTracksSnapshot().associateBy { it.id }.toMutableMap()
        var imported = 0
        var skipped = 0
        var failed = 0
        files.forEachIndexed { index, entry ->
            onProgress(index, files.size, entry.name)
            val base = "ncm-" + sha1(entry.uri.toString())
            val id = "ncm:$base"
            try {
                val cached = readCache(outputDir, base)?.takeIf { File(outputDir, it.audioName).isFile }
                if (cached != null) {
                    merged[id] = buildTrack(id, File(outputDir, cached.audioName), cached.coverName?.let { File(outputDir, it) }, cached, entry.name)
                    skipped++
                } else {
                    val decoded = NcmDecoder.decode(context, entry.uri, outputDir, base)
                    val durationMs = decoded.metadata.durationMs.takeIf { it > 0 } ?: probeDurationMs(decoded.audio)
                    val cache = CachedNcm(
                        audioName = decoded.audio.name,
                        coverName = decoded.cover?.name,
                        title = decoded.metadata.title,
                        artists = decoded.metadata.artists,
                        album = decoded.metadata.album,
                        format = decoded.metadata.format.ifBlank { decoded.audio.extension },
                        durationMs = durationMs,
                    )
                    writeCache(outputDir, base, cache)
                    merged[id] = buildTrack(id, decoded.audio, decoded.cover, cache, entry.name)
                    imported++
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed++
            }
        }
        onProgress(files.size, files.size, "")
        store.setImportedTracks(merged.values.toList())
        NcmImportSummary(imported = imported, skipped = skipped, failed = failed)
    }

    private fun buildTrack(id: String, audio: File, cover: File?, meta: CachedNcm, fallbackName: String): LibraryTrack {
        val title = meta.title.ifBlank { fallbackName.substringBeforeLast('.', fallbackName) }
        val artistNames = meta.artists.filter { it.isNotBlank() }.ifEmpty { listOf("未知歌手") }
        val albumTitle = meta.album.ifBlank { "未知专辑" }
        val albumId = albumIdFor(albumTitle)
        val extension = audio.extension.lowercase()
        val lossless = extension in setOf("flac", "wav", "aiff", "alac")
        val durationSeconds = meta.durationMs.coerceAtLeast(0L) / 1000L
        val metadataJson = buildJsonObject {
            put("title", title)
            if (durationSeconds > 0) put("duration", durationSeconds)
            put("format", extension)
            put("codec", extension)
            put("file_size", audio.length())
            put("lossless", lossless)
            put(
                "artists",
                JsonArray(
                    artistNames.map { name ->
                        buildJsonObject {
                            put("id", artistIdFor(name))
                            put("name", name)
                        }
                    },
                ),
            )
            put(
                "album",
                buildJsonObject {
                    put("id", albumId)
                    put("title", albumTitle)
                },
            )
        }.toString()
        val createdAt = Instant.now().toString()
        return LibraryTrack(
            id = id,
            title = title,
            artists = artistNames.joinToString(", "),
            album = albumTitle,
            albumId = albumId,
            metadataJson = metadataJson,
            artworkPath = cover?.takeIf { it.isFile }?.absolutePath,
            audioPath = audio.absolutePath,
            createdAt = createdAt,
            updatedAt = createdAt,
            sortTitle = title,
            sortArtists = artistNames.first(),
        )
    }

    private fun probeDurationMs(file: File): Long = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    private fun readCache(dir: File, base: String): CachedNcm? {
        val file = File(dir, "$base.json")
        if (!file.isFile) return null
        return runCatching {
            LibrarySyncProtocol.json.decodeFromString<CachedNcm>(file.readText())
        }.getOrNull()
    }

    private fun writeCache(dir: File, base: String, cache: CachedNcm) {
        runCatching { File(dir, "$base.json").writeTextAtomically(LibrarySyncProtocol.json.encodeToString(cache)) }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
}
