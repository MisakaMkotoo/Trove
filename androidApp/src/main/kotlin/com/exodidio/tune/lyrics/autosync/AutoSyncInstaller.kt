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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Downloads and installs one AutoSync language pack: model + tokenizer files,
 * SHA-256 verified, staged and atomically published. Nothing is bundled.
 */
internal object AutoSyncInstaller {

    suspend fun install(
        context: Context,
        language: String,
        onProgress: (Float, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val index = AutoSyncCatalog.index()
        val pack = index.packs[language] ?: throw IOException("没有 $language 的模型包")
        val root = AutoSyncModelStore.root(context)
        val live = File(root, language)
        val staging = File(root, "$language.staging")
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory) { "无法创建下载目录" }

        val assets = pack.requiredAssets
        val totalBytes = assets.sumOf { it.bytes.coerceAtLeast(0L) }.coerceAtLeast(1L)
        var doneBytes = 0L
        onProgress(0f, "准备下载")

        // 半成品放在 staging 之外：取消/失败后手动重试可以续传。
        val parts = File(root, "downloads").apply { mkdirs() }
        val downloaded = LinkedHashMap<String, File>()
        for (asset in assets) {
            currentCoroutineContext().ensureActive()
            val target = File(staging, asset.name)
            val part = File(parts, "$language-${asset.name}.part")
            val base = doneBytes
            downloadAsset(context, asset, part, target) { written ->
                val current = (base + written).coerceAtMost(totalBytes)
                onProgress(current.toFloat() / totalBytes, "下载 ${asset.name}")
            }
            doneBytes += asset.bytes.coerceAtLeast(target.length())
            downloaded[asset.name] = target
        }
        assets.forEach { asset -> File(parts, "$language-${asset.name}.part").delete() }

        val tokensFile = pack.tokensFile?.let(downloaded::get)
            ?: downloaded.values.firstOrNull { it.name.contains("vocab") || it.name.contains("tokens") }
        val tokens = readTokens(tokensFile)
        check(tokens.isNotEmpty()) { "模型缺少词表" }

        val manifest = localManifest(pack, index.releaseTag, tokens)
        File(staging, "manifest.json").writeText(manifest.toString())

        // Keep the model file name the manifest points at.
        val modelName = manifest.optJSONObject("files")
            ?.optJSONObject("int8")?.optString("name").orEmpty()
        check(modelName.isNotBlank() && File(staging, modelName).isFile) { "模型文件缺失" }

        publish(root, language, live, staging)
        AutoSyncTimelineStore.clearLanguage(context, language)
        OnnxCtcEngine.releaseAll()
        ZhAlignmentEngine.releaseAll()
        AutoSyncTrace.log(context, "model installed lang=$language assets=${downloaded.size}")
        onProgress(1f, "已安装")
    }

    fun remove(context: Context, language: String) {
        AutoSyncModelStore.delete(context, language)
    }

    private fun publish(root: File, language: String, live: File, staging: File) {
        val previous = File(root, "$language.old")
        previous.deleteRecursively()
        if (live.exists() && !live.renameTo(previous)) error("无法替换旧模型")
        if (!staging.renameTo(live)) {
            previous.renameTo(live)
            error("无法发布模型")
        }
        previous.deleteRecursively()
    }

    /** 只请求一次：失败就停，半成品留在 parts 目录，下次手动重试续传。 */
    private suspend fun downloadAsset(
        context: Context,
        asset: AutoSyncAsset,
        part: File,
        target: File,
        onWritten: (Long) -> Unit,
    ) {
        var resumeAt = part.takeIf { it.isFile }?.length() ?: 0L
        if (asset.bytes > 0 && resumeAt >= asset.bytes) {
            part.delete()
            resumeAt = 0L
        }
        AutoSyncTrace.log(context, "asset download ${asset.name} resumeFrom=$resumeAt")
        val request = Request.Builder()
            .url(asset.url)
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
                                    if (asset.bytes > 0 && written > asset.bytes) {
                                        throw IOException("文件超出声明大小")
                                    }
                                    onWritten(written)
                                }
                            }
                    }
                    if (asset.bytes > 0 && written != asset.bytes) {
                        throw IOException("文件大小不符：$written != ${asset.bytes}")
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(asset.sha256, ignoreCase = true)) {
                        part.delete()
                        throw IOException("SHA-256 校验失败")
                    }
                    if (!part.renameTo(target)) {
                        throw IOException("无法写入 ${target.name}")
                    }
                    AutoSyncTrace.log(context, "asset download complete ${asset.name} bytes=$written")
                }
        } catch (error: Throwable) {
            AutoSyncTrace.log(context, "asset download failed ${asset.name}, partial kept at $part", error)
            throw error
        }
    }

    /** `en-vocab.json`/`ja-vocab.json` are token→id; `zh-tokens.json` is id→token. */
    private fun readTokens(file: File?): Map<String, String> {
        if (file == null || !file.isFile) return emptyMap()
        val text = file.readText().trim()
        val out = LinkedHashMap<String, String>()
        when {
            text.startsWith("{") -> {
                val root = JSONObject(text)
                root.keys().forEach { key -> out[key] = root.optString(key) }
            }
            text.startsWith("[") -> {
                val array = JSONArray(text)
                for (index in 0 until array.length()) {
                    val token = array.optString(index)
                    if (token.isNotEmpty()) out[token] = index.toString()
                }
            }
        }
        return out
    }

    /** Local manifest understood by [AutoSyncManifest.parse]. */
    private fun localManifest(
        pack: AutoSyncPack,
        releaseTag: String,
        tokens: Map<String, String>,
    ): JSONObject {
        val tokenArray = JSONArray()
        tokens.entries.sortedBy { it.value.toIntOrNull() ?: Int.MAX_VALUE }
            .forEach { tokenArray.put(it.key) }
        val inputs = JSONArray()
        pack.inputNames.forEach { name -> inputs.put(JSONObject().put("name", name)) }
        val outputs = JSONArray()
        pack.outputNames.forEach { name -> outputs.put(JSONObject().put("name", name)) }
        val model = pack.files.firstOrNull { it.role == "model" }
        return JSONObject()
            .put("schema", "melox.autosync.onnx.1")
            .put("language", pack.language)
            .put("engine", pack.engine)
            .put("release_tag", releaseTag)
            .put("source_model", pack.sourceModel)
            .put("license", pack.license)
            .put("sample_rate", pack.sampleRate)
            .put("frame_hop_seconds", pack.frameHopSeconds)
            .put("inputs", inputs)
            .put("outputs", outputs)
            .put("blank_id", pack.blankId)
            .apply {
                pack.wordDelimiterId?.let { put("word_delimiter_id", it) }
                if (pack.normalization.isNotBlank()) put("normalization", pack.normalization)
                put("g2p_available", pack.language != "ja")
                val files = JSONObject()
                model?.let {
                    files.put(
                        "int8",
                        JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256),
                    )
                }
                files.put("variant", "int8")
                val required = JSONArray()
                model?.let {
                    required.put(
                        JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256),
                    )
                }
                pack.requiredAssets.filter { it.role != "model" }.forEach {
                    required.put(
                        JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256),
                    )
                }
                files.put("required", required)
                put("files", files)
                put("tokens", tokenArray)
            }
    }
}
