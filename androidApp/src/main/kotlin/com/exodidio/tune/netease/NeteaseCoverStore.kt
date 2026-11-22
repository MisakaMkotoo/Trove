package com.exodidio.tune.netease

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 网易云封面统一缓存。
 * 所有歌曲物化（歌单导入、动态歌单、心动模式、在线搜索）都从这里取封面：
 * 磁盘位置固定、并发请求去重、失败负缓存，避免每个功能各写一套。
 */
internal object NeteaseCoverStore {
    private const val NEGATIVE_TTL_MS = 45_000L
    private val negative = ConcurrentHashMap<Long, Pair<Int, Long>>()
    private val inFlight = ConcurrentHashMap<Long, Deferred<File?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 整份歌单补图时限制同时下载数，避免把网络和磁盘打满、也减少封面失败。
    private val downloadGate = Semaphore(3)

    fun cached(context: Context, songId: Long): File? =
        File(dir(context), coverName(songId)).takeIf { it.isFile && it.length() > 0 }?.also {
            NeteaseImageCache.touch(it)
        }

    fun coverName(songId: Long): String = "$songId.jpg"

    private fun dir(context: Context): File =
        File(context.filesDir, "netease-covers").apply { mkdirs() }

    fun ensure(
        context: Context,
        songId: Long,
        coverUrl: String,
        cookie: String?,
    ): Deferred<File?> {
        if (coverUrl.isBlank()) return CompletableDeferred(null)
        cached(context, songId)?.let { return CompletableDeferred(it) }
        val urlKey = coverUrl.hashCode()
        val failed = negative[songId]
        if (failed != null && failed.first == urlKey &&
            System.currentTimeMillis() - failed.second < NEGATIVE_TTL_MS
        ) {
            return CompletableDeferred(null)
        }
        val app = context.applicationContext
        return inFlight.computeIfAbsent(songId) {
            scope.async {
                downloadGate.withPermit {
                    try {
                        val target = File(dir(app), coverName(songId))
                        // 播放器/列表不需要原图：取 600 见方的版本，字节数少一个量级。
                        val sizedUrl = if (coverUrl.contains("param=")) {
                            coverUrl
                        } else {
                            coverUrl + (if (coverUrl.contains('?')) '&' else '?') + "param=600y600"
                        }
                        var result = NeteaseClient.download(sizedUrl, cookie, target)
                        if (result.isFailure) {
                            delay(250)
                            result = NeteaseClient.download(sizedUrl, cookie, target)
                        }
                        val file = target.takeIf { result.isSuccess && it.isFile && it.length() > 0 }
                        if (file == null) {
                            negative[songId] = urlKey to System.currentTimeMillis()
                            System.err.println("COVER download failed song=$songId url=$sizedUrl")
                        } else {
                            NeteaseImageCache.trim(app)
                        }
                        file
                    } finally {
                        inFlight.remove(songId)
                    }
                }
            }
        }
    }
}
