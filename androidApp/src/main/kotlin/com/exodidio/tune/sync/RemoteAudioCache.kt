package com.exodidio.tune.sync

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 电脑音乐库音频缓存：独立目录、独立配额（默认 1GB），只服务资源库/remote 曲目，
 * 与网易云的流缓存、封面缓存完全分离。
 */
internal object RemoteAudioCache {
    private const val PREFS = "remote_audio_cache"
    private const val KEY_SIZE_MB = "size_mb"
    private const val DEFAULT_SIZE_MB = 1024
    private const val DIR_NAME = "remote-audio-cache"
    private const val LEGACY_DIR_NAME = "remote-audio"
    private const val PART_MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private val lastTrimAt = AtomicLong(0L)
    private val trimRunning = AtomicBoolean(false)

    fun sizeLimitMb(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SIZE_MB, DEFAULT_SIZE_MB).coerceIn(100, 16384)

    fun setSizeLimitMb(context: Context, sizeMb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SIZE_MB, sizeMb.coerceIn(100, 16384)).apply()
        trim(context, force = true)
    }

    fun usageBytes(context: Context): Long = files(context).sumOf { it.length() }

    fun clear(context: Context) {
        files(context).forEach { file -> runCatching { file.delete() } }
        lastTrimAt.set(System.currentTimeMillis())
    }

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 缓存文件：key 只取文件身份（URL 的 p 参数），不受主机和 s 签名变化影响。 */
    fun target(context: Context, url: String): File {
        val identity = identity(url)
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }
        return File(dir(context), "$key.${extension(url)}")
    }

    /** 命中缓存；旧 key（含签名）存在时迁移到新 key 后返回。 */
    fun cached(context: Context, url: String): File? {
        val target = target(context, url)
        if (target.isFile && target.length() > 0) {
            touch(target)
            return target
        }
        val legacy = legacyTarget(context, url)
        if (legacy.isFile && legacy.length() > 0) {
            legacy.renameTo(target)
            if (target.isFile && target.length() > 0) {
                touch(target)
                return target
            }
        }
        return null
    }

    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /** 超配额时按最后访问时间淘汰；10 秒内最多检查一次。 */
    fun trim(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTrimAt.get() < 10_000L) return
        if (!trimRunning.compareAndSet(false, true)) return
        try {
            lastTrimAt.set(now)
            val limit = sizeLimitMb(context) * 1024L * 1024L
            val entries = files(context).filterNot { it.name.endsWith(".part") }
                .map { file -> file to file.lastModified() }
            var total = entries.sumOf { (file, _) -> file.length() }
            if (total <= limit) return
            entries.sortedBy { (_, modified) -> modified }.forEach { (file, _) ->
                if (total <= limit) return@forEach
                val length = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) total -= length
            }
        } finally {
            trimRunning.set(false)
        }
    }

    /** 启动清理：超过 7 天的半成品文件。 */
    fun cleanupPartials(context: Context) {
        val cutoff = System.currentTimeMillis() - PART_MAX_AGE_MS
        listOf(dir(context), File(context.filesDir, LEGACY_DIR_NAME)).forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.name.endsWith(".part") && file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    private fun files(context: Context): List<File> =
        listOf(dir(context), File(context.filesDir, LEGACY_DIR_NAME))
            .flatMap { directory -> directory.listFiles()?.filter { it.isFile }.orEmpty() }

    /** URL 的文件身份：优先 p 参数（不含 s 签名）；否则用去主机后的路径。 */
    private fun identity(url: String): String {
        val query = runCatching { URL(url).query }.getOrNull().orEmpty()
        query.split('&').firstOrNull { it.startsWith("p=") }
            ?.substringAfter("p=")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return url.substringAfter("://", url)
            .substringAfter('/', "")
            .substringBefore('?')
            .ifBlank { url }
    }

    private fun extension(url: String): String {
        val decoded = runCatching {
            String(Base64.decode(identity(url), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        }.getOrNull().orEmpty()
        val ext = decoded.substringAfterLast('.', "")
            .ifBlank { url.substringBefore('?').substringAfterLast('.', "") }
            .lowercase()
            .take(5)
        return ext.ifBlank { "bin" }
    }

    /** 旧版本 key：主机以下完整路径（含 s 签名），用于一次性迁移。 */
    private fun legacyTarget(context: Context, url: String): File {
        val path = if (url.startsWith("http", ignoreCase = true)) {
            val rest = url.substringAfter("://", url)
            val slash = rest.indexOf('/')
            if (slash >= 0) rest.substring(slash) else rest
        } else {
            url
        }
        val key = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, LEGACY_DIR_NAME), "$key.bin")
    }
}
