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
 * 播放页高清封面缓存：只服务全屏播放器，1600²、独立 100MB LRU。
 * 列表和卡片仍走 NeteaseCoverStore 的 600²，避免整库封面都按高清下载。
 */
internal object NeteaseHiResCoverStore {
    const val SizePx = 1600
    private const val LimitBytes = 100L * 1024 * 1024
    private const val DirName = "netease-covers-hires"
    private const val NegativeTtlMs = 45_000L
    private const val PartMaxAgeMs = 60L * 60 * 1000

    @Volatile private var lastTrimAt = 0L
    private val negative = ConcurrentHashMap<Long, Pair<Int, Long>>()
    private val inFlight = ConcurrentHashMap<Long, Deferred<File?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadGate = Semaphore(2)

    fun cached(context: Context, songId: Long): File? =
        File(dir(context), coverName(songId)).takeIf { it.isFile && it.length() > 0 }?.also(::touch)

    fun coverName(songId: Long): String = "${songId}_${SizePx}.jpg"

    private fun dir(context: Context): File =
        File(context.filesDir, DirName).apply { mkdirs() }

    /**
     * 在调用方的协程里下载：切歌/关闭播放器会直接取消，不再让上一首的下载继续抢带宽。
     * 命中缓存或刚失败过（45 秒内）立即返回。
     */
    suspend fun ensure(
        context: Context,
        songId: Long,
        coverUrl: String,
        cookie: String?,
    ): File? {
        if (songId <= 0L || coverUrl.isBlank()) return null
        cached(context, songId)?.let { return it }
        val urlKey = coverUrl.hashCode()
        val failed = negative[songId]
        if (failed != null && failed.first == urlKey &&
            System.currentTimeMillis() - failed.second < NegativeTtlMs
        ) {
            return null
        }
        val app = context.applicationContext
        // 原图通常 2048²，param 只降到原图为止；小图不会被放大。
        val sizedUrl = if (coverUrl.contains("param=")) {
            coverUrl
        } else {
            coverUrl + (if (coverUrl.contains('?')) '&' else '?') + "param=${SizePx}y${SizePx}"
        }
        return downloadGate.withPermit {
            cached(app, songId)?.let { return@withPermit it }
            val target = File(dir(app), coverName(songId))
            var result = NeteaseClient.download(sizedUrl, cookie, target)
            if (result.isFailure) {
                delay(250)
                result = NeteaseClient.download(sizedUrl, cookie, target)
            }
            val file = target.takeIf { result.isSuccess && it.isFile && it.length() > 0 }
            if (file == null) {
                negative[songId] = urlKey to System.currentTimeMillis()
                System.err.println("HIRES fail song=$songId url=$sizedUrl err=${result.exceptionOrNull()?.message}")
            } else {
                System.err.println("HIRES ok song=$songId bytes=${file.length()}")
                trim(app, force = true)
            }
            file
        }
    }

    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    fun usageBytes(context: Context): Long = files(context).sumOf { it.length() }

    /** 超出 100MB 按最后访问时间淘汰；时间戳先取快照再排序，避免并发 touch 破坏比较器。 */
    @Synchronized
    fun trim(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTrimAt < 10_000L) return
        lastTrimAt = now
        val all = dir(context).listFiles()?.filter { it.isFile }.orEmpty()
        all.filter { it.name.endsWith(".part") && now - it.lastModified() > PartMaxAgeMs }
            .forEach { runCatching { it.delete() } }
        val entries = all.filterNot { it.name.endsWith(".part") }
            .map { file -> file to file.lastModified() }
        var total = entries.sumOf { (file, _) -> file.length() }
        if (total <= LimitBytes) return
        entries.sortedBy { (_, modified) -> modified }.forEach { (file, _) ->
            if (total <= LimitBytes) return@forEach
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= length
        }
    }

    private fun files(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
}
