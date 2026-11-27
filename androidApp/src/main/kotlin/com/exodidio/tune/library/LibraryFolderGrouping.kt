package com.exodidio.tune.library

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.sync.stableId
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class LibraryFolder(
    val id: String,
    val name: String,
    val displayPath: String,
    val fullPath: String,
    val trackIds: List<String>,
    val trackCount: Int,
    val artworkPath: String?,
    val createdAt: String,
    val sortName: String,
)

internal fun folderIdFor(canonicalPath: String): String = stableId("local-folder-", canonicalPath)

internal fun LibraryTrack.folderPathFromMetadata(): String? =
    metadataObject()
        ?.get("folder_path")
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::canonicalizeScanPath)

internal fun canonicalFolderForPath(rawPath: String?): String? {
    val raw = rawPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (
        raw.startsWith("netease://", ignoreCase = true) ||
        raw.startsWith("http://", ignoreCase = true) ||
        raw.startsWith("https://", ignoreCase = true) ||
        raw.startsWith("content://", ignoreCase = true)
    ) {
        return null
    }
    val stripped = if (raw.startsWith("file://", ignoreCase = true)) {
        raw.substring("file://".length).takeIf { it.isNotBlank() } ?: return null
    } else {
        raw
    }
    val normalized = canonicalizeScanPath(stripped)
    val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "").trim()
    return when {
        parent.isNotBlank() -> canonicalizeScanPath(parent)
        normalized.startsWith("/") -> "/"
        else -> null
    }
}

internal fun folderDisplayNameAndPath(
    canonicalFolder: String,
    internalStorageLabel: String = "Internal Storage",
): Pair<String, String> {
    val primaryPrefix = "/storage/emulated/0"
    return when {
        canonicalFolder.equals(primaryPrefix, ignoreCase = true) || canonicalFolder == "/" ->
            internalStorageLabel to "/"
        canonicalFolder.startsWith("$primaryPrefix/", ignoreCase = true) -> {
            val rel = canonicalFolder.substring(primaryPrefix.length + 1)
            val name = rel.substringAfterLast('/').ifBlank { internalStorageLabel }
            name to rel
        }
        canonicalFolder.startsWith("/storage/", ignoreCase = true) -> {
            val rel = canonicalFolder.substring("/storage/".length)
            val name = rel.substringAfterLast('/').ifBlank { rel }
            name to rel
        }
        else -> {
            val name = canonicalFolder.substringAfterLast('/').ifBlank { canonicalFolder }
            name to canonicalFolder
        }
    }
}

internal fun resolveFolderPathForTrack(
    track: LibraryTrack,
    mediaStoreFolderById: Map<Long, String> = emptyMap(),
): String? {
    track.folderPathFromMetadata()?.let { return it }
    val audioPath = track.audioPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (audioPath.startsWith("content://", ignoreCase = true)) {
        val mediaId = audioPath.substringAfterLast('/').toLongOrNull() ?: return null
        return mediaStoreFolderById[mediaId]
    }
    return canonicalFolderForPath(audioPath)
}

internal fun groupTracksByFolder(
    tracks: List<LibraryTrack>,
    mediaStoreFolderById: Map<Long, String> = emptyMap(),
    internalStorageLabel: String = "Internal Storage",
): List<LibraryFolder> {
    if (tracks.isEmpty()) return emptyList()
    val grouped = linkedMapOf<String, MutableList<LibraryTrack>>()
    tracks.forEach { track ->
        val folderPath = resolveFolderPathForTrack(track, mediaStoreFolderById) ?: return@forEach
        grouped.getOrPut(folderPath) { mutableListOf() }.add(track)
    }
    return grouped.entries.map { (folderPath, folderTracks) ->
        val (name, displayPath) = folderDisplayNameAndPath(folderPath, internalStorageLabel)
        val sortedTracks = folderTracks.sortedWith(folderTrackComparator)
        val artworkPath = sortedTracks.firstNotNullOfOrNull { it.artworkPath?.takeIf(String::isNotBlank) }
        val latestCreatedAt = sortedTracks.maxOfOrNull { it.createdAt }.orEmpty()
        LibraryFolder(
            id = folderIdFor(folderPath),
            name = name,
            displayPath = displayPath,
            fullPath = folderPath,
            trackIds = sortedTracks.map { it.id },
            trackCount = sortedTracks.size,
            artworkPath = artworkPath,
            createdAt = latestCreatedAt,
            sortName = name.lowercase(),
        )
    }
}

internal val folderTrackComparator = compareBy<LibraryTrack> {
    if (it.discNumber > 0) it.discNumber else Int.MAX_VALUE
}.thenBy {
    if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE
}.thenBy {
    it.sortTitle.ifBlank { it.title.lowercase() }
}.thenBy { it.id }

/**
 * Queries MediaStore directory metadata for existing local tracks stored only with `content://` URIs.
 * Must be called from a blocking I/O dispatcher.
 */
internal fun queryMediaStoreFolders(context: Context): Map<Long, String> {
    return runCatching {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Audio.Media.RELATIVE_PATH)
        }.toTypedArray()
        val result = HashMap<Long, String>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            if (idIdx < 0) return@use
            val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val relIdx = if (Build.VERSION.SDK_INT >= 29) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val displayIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val data = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                val folderFromData = canonicalFolderForPath(data)
                if (folderFromData != null) {
                    result[id] = folderFromData
                    continue
                }
                val rel = if (relIdx >= 0) cursor.getString(relIdx) else null
                val display = if (displayIdx >= 0) cursor.getString(displayIdx) else null
                val fallback = fallbackScanPathFor(rel, display)?.let(::canonicalFolderForPath)
                    ?: rel?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
                        ?.let { canonicalizeScanPath("/storage/emulated/0/$it") }
                if (fallback != null) {
                    result[id] = fallback
                }
            }
        }
        result
    }.getOrDefault(emptyMap())
}
