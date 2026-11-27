package com.exodidio.tune.lyrics.autosync

import android.content.Context
import com.exodidio.tune.net.TuneHttp
import com.exodidio.tune.net.executeCancellable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * OpenJTalk NAIST dictionary for the Japanese G2P frontend.
 * ~24 MB compressed / ~102 MB installed; downloaded on demand, never bundled.
 * Dictionary licensing is separate from the app (NAIST / open_jtalk).
 */
internal object AutoSyncDictionary {
    private const val URL_BASE =
        "https://github.com/r9y9/open_jtalk/releases/download/v1.11.1/open_jtalk_dic_utf_8-1.11.tar.gz"
    private const val BYTES = 23_646_843L
    private const val SHA256 = "fe6ba0e43542cef98339abdffd903e062008ea170b04e7e2a35da805902f382a"
    private const val ARCHIVE_ROOT = "open_jtalk_dic_utf_8-1.11"

    fun directory(context: Context): File? {
        val candidate = File(File(AutoSyncModelStore.root(context), "g2p"), ARCHIVE_ROOT)
        val meta = File(candidate, "meta.json")
        val sysDic = File(candidate, "sys.dic")
        return candidate.takeIf { it.isDirectory && meta.isFile && sysDic.isFile }
    }

    fun isInstalled(context: Context): Boolean = directory(context) != null

    suspend fun install(context: Context, onProgress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            if (isInstalled(context)) {
                AutoSyncTrace.log(context, "dictionary already installed, skip download")
                onProgress(1f, "已安装")
                return@withContext
            }
            AutoSyncTrace.log(context, "dictionary install start")
            val root = File(AutoSyncModelStore.root(context), "g2p")
            root.mkdirs()
            val staging = File(root, "$ARCHIVE_ROOT.staging")
            staging.deleteRecursively()
            staging.mkdirs()
            // 压缩包留在 root 下：校验失败重装时直接复用，不再重下 24 MB。
            val tarball = File(root, "open_jtalk_dic.tar.gz")
            try {
                if (tarball.isFile && tarball.length() == BYTES) {
                    AutoSyncTrace.log(context, "reuse verified dictionary archive")
                    onProgress(0.7f, "使用已下载的字典包")
                } else {
                    download(context, tarball) { onProgress(it * 0.7f, "下载日文字典") }
                }
                onProgress(0.72f, "解压字典")
                AutoSyncTarExtractor.extract(tarball, staging)
                val dictionary = File(staging, ARCHIVE_ROOT)
                if (!dictionary.isDirectory) throw IOException("字典包缺少 $ARCHIVE_ROOT")
                // NAIST 词典本身就是 10 个文件（无 dicrc），按关键文件校验而不是项数。
                val required = listOf("sys.dic", "matrix.bin", "char.bin", "unk.dic")
                val missing = required.filter { !File(dictionary, it).isFile }
                if (missing.isNotEmpty()) {
                    throw IOException("字典包缺少 ${missing.joinToString("/")}")
                }
                val entryCount = dictionary.walkTopDown().count()
                AutoSyncTrace.log(context, "dictionary extracted entries=$entryCount")
                File(dictionary, "meta.json").writeText(
                    JSONObject()
                        .put("sha256", SHA256)
                        .put("bytes", BYTES)
                        .put("entries", entryCount)
                        .toString(),
                )
                publish(context, root, dictionary, staging)
                tarball.delete()
                onProgress(1f, "已安装")
            } catch (error: Throwable) {
                AutoSyncTrace.log(context, "dictionary install failed", error)
                throw error
            }
        }

    fun remove(context: Context) {
        val root = File(AutoSyncModelStore.root(context), "g2p")
        root.deleteRecursively()
        JaOpenJtalk.close()
    }

    /** 包里自带顶层目录，发布的是里层字典目录本身，避免多套一层。 */
    private fun publish(context: Context, root: File, dictionary: File, staging: File) {
        val live = File(root, ARCHIVE_ROOT)
        val previous = File(root, "$ARCHIVE_ROOT.old")
        previous.deleteRecursively()
        if (live.exists() && !live.renameTo(previous)) error("无法替换旧字典")
        if (!dictionary.renameTo(live)) {
            previous.renameTo(live)
            error("无法发布字典")
        }
        previous.deleteRecursively()
        staging.deleteRecursively()
        AutoSyncTrace.log(context, "dictionary published at ${live.absolutePath}")
    }

    /** 只请求一次：失败就停，已下载部分留在 `.part`，下次手动点下载再续传。 */
    private suspend fun download(context: Context, target: File, onProgress: (Float) -> Unit) {
        val part = File(target.absolutePath + ".part")
        var resumeAt = part.takeIf { it.isFile }?.length() ?: 0L
        if (resumeAt >= BYTES) {
            part.delete()
            resumeAt = 0L
        }
        AutoSyncTrace.log(context, "dictionary download resumeFrom=$resumeAt")
        val request = Request.Builder()
            .url(URL_BASE)
            .header("User-Agent", "Tune-Android")
            .apply { if (resumeAt > 0L) header("Range", "bytes=$resumeAt-") }
            .get()
            .build()
        try {
            TuneHttp.client(connectTimeoutMs = 8_000L, readTimeoutMs = 30_000L)
                .executeCancellable(request) { response ->
                    val code = response.code
                    if (!response.isSuccessful) throw IOException("HTTP $code")
                    val body = response.body ?: throw IOException("响应体为空")
                    val startAt = if (code == 206) resumeAt else 0L
                    val declared = body.contentLength()
                    if (declared > 0 && startAt + declared != BYTES) {
                        throw IOException("字典包大小异常：$declared (code=$code)")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    if (startAt > 0L) {
                        part.inputStream().use { existing ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = existing.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                            }
                        }
                    }
                    var written = startAt
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(part, startAt > 0L)
                            .buffered()
                            .use { output ->
                                val buffer = ByteArray(1 shl 16)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    written += read
                                    if (written > BYTES) throw IOException("字典包超出声明大小")
                                    onProgress((written.toFloat() / BYTES).coerceIn(0f, 1f))
                                }
                            }
                    }
                    if (written != BYTES) throw IOException("字典包大小不符：$written")
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(SHA256, ignoreCase = true)) {
                        part.delete()
                        throw IOException("字典 SHA-256 校验失败")
                    }
                    if (!part.renameTo(target)) throw IOException("无法写入 ${target.name}")
                    AutoSyncTrace.log(context, "dictionary download complete bytes=$written")
                }
        } catch (error: Throwable) {
            AutoSyncTrace.log(context, "dictionary download failed, partial kept at $part", error)
            throw error
        }
    }
}
