package com.exodidio.tune.netease

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 网易云歌单/歌手/专辑封面缓存（按图片 URL 去重）。
 * 搜索结果与各类列表都从这里取远程封面，和歌曲封面一样“列表即带图”。
 */
internal object NeteaseImageStore {
    private const val NEGATIVE_TTL_MS = 10 * 60 * 1000L
    private val negative = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Deferred<File?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cached(context: Context, url: String): File? {
        if (url.isBlank()) return null
        return File(dir(context), key(url)).takeIf { it.isFile && it.length() > 0 }
    }

    fun ensure(context: Context, url: String, cookie: String?): Deferred<File?> {
        if (url.isBlank()) return CompletableDeferred(null)
        cached(context, url)?.let { return CompletableDeferred(it) }
        if ((negative[url] ?: 0L) > System.currentTimeMillis() - NEGATIVE_TTL_MS) {
            return CompletableDeferred(null)
        }
        val app = context.applicationContext
        return inFlight.computeIfAbsent(url) {
            scope.async {
                try {
                    val target = File(dir(app), key(url))
                    val result = NeteaseClient.download(url, cookie, target)
                    val file = target.takeIf { result.isSuccess && it.isFile && it.length() > 0 }
                    if (file == null) {
                        negative[url] = System.currentTimeMillis()
                        System.err.println("IMAGE download failed url=$url")
                    }
                    file
                } finally {
                    inFlight.remove(url)
                }
            }
        }
    }

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "netease-images").apply { mkdirs() }
}
