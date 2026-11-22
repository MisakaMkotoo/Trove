package com.exodidio.tune.netease

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 封面缓存（歌曲封面 + 歌单/歌手/专辑图片）的统一上限与淘汰。
 * 与音频流缓存完全独立：默认 800MB，超出后按文件最后访问时间淘汰最旧的文件。
 */
internal object NeteaseImageCache {
    private const val PREFS = "netease-image-cache"
    private const val KEY_SIZE_MB = "size_mb"
    private const val DEFAULT_SIZE_MB = 800
    private val dirNames = listOf("netease-covers", "netease-images")
    private val lastTrimAt = AtomicLong(0L)
    private val trimRunning = AtomicBoolean(false)

    fun sizeLimitMb(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SIZE_MB, DEFAULT_SIZE_MB).coerceIn(100, 8192)

    fun setSizeLimitMb(context: Context, sizeMb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SIZE_MB, sizeMb.coerceIn(100, 8192)).apply()
        trim(context, force = true)
    }

    fun usageBytes(context: Context): Long = files(context).sumOf { it.length() }

    fun clear(context: Context) {
        // 与 trim 并发删除同一个文件只会让 delete 返回 false，不需要互斥。
        files(context).forEach { file -> runCatching { file.delete() } }
        lastTrimAt.set(System.currentTimeMillis())
    }

    /** 访问即刷新时间戳，配合按 lastModified 的 LRU 淘汰。 */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    fun trim(context: Context, force: Boolean = false) {
        // 每次下载都全量扫目录会很贵：10 秒内只检查一次，改上限时强制检查。
        val now = System.currentTimeMillis()
        if (!force && now - lastTrimAt.get() < 10_000L) return
        // 同时只跑一个 trim：不拿对象监视器，UI 线程误调用也不会被删文件的 IO 卡住。
        if (!trimRunning.compareAndSet(false, true)) return
        try {
            lastTrimAt.set(now)
            trimLockFree(context)
        } finally {
            trimRunning.set(false)
        }
    }

    private fun trimLockFree(context: Context) {
        val limit = sizeLimitMb(context) * 1024L * 1024L
        // 先把时间戳取成快照再排序：其他线程的 touch 会在排序过程中改动 lastModified，
        // 直接把它当比较键会破坏比较器契约并让 TimSort 抛异常。
        val entries = files(context).map { file -> file to file.lastModified() }
        var total = entries.sumOf { (file, _) -> file.length() }
        if (total <= limit) return
        entries.sortedBy { (_, modified) -> modified }.forEach { (file, _) ->
            if (total <= limit) return@forEach
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= length
        }
    }

    private fun files(context: Context): List<File> =
        dirNames.flatMap { name ->
            File(context.filesDir, name).listFiles()?.filter { it.isFile }.orEmpty()
        }
}
