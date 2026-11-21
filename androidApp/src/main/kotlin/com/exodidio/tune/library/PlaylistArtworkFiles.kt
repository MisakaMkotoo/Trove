package com.exodidio.tune.library

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import com.exodidio.tune.sync.StagedPlaylistArtwork

/** Converts picker content into a hashed JPEG under filesDir (local-first, no sync). */
internal fun stagePlaylistArtwork(contentResolver: ContentResolver, filesDir: File, uri: Uri): StagedPlaylistArtwork {
    val bitmap = decodeArtwork(contentResolver, uri, maxDimension = 2048)
        ?: error("Unable to decode playlist artwork")
    val bytes = java.io.ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Unable to encode playlist artwork" }
        output.toByteArray()
    }
    bitmap.recycle()
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val relativePath = "playlist-artwork/$hash.jpg"
    val target = File(filesDir, relativePath)
    if (!target.isFile) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "$hash.tmp")
        temporary.outputStream().use { it.write(bytes) }
        check(temporary.renameTo(target)) { "Unable to save playlist artwork" }
    }
    return StagedPlaylistArtwork(hash, "image/jpeg", bytes.size.toLong(), relativePath)
}

/** 先用 ImageDecoder（能读 HEIC/HEIF 等小米相册常见格式），失败再退回 BitmapFactory。 */
private fun decodeArtwork(contentResolver: ContentResolver, uri: Uri, maxDimension: Int): Bitmap? {
    val decoded = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val width = info.size.width.coerceAtLeast(1)
            val height = info.size.height.coerceAtLeast(1)
            var sample = 1
            while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()
    if (decoded != null) return decoded
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
    return contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
