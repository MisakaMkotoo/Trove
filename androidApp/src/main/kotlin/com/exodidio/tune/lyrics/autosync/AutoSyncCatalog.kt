package com.exodidio.tune.lyrics.autosync

import com.exodidio.tune.net.TuneHttp
import com.exodidio.tune.net.executeCancellable
import com.exodidio.tune.net.readBodyBytesLimited
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Pinned AutoSync model catalog (MeloX-AutoSync android-models-v0.1.0-beta.4).
 * Models are Apache-2.0; the index itself is SHA-256 pinned so a moved tag
 * cannot swap the payload silently.
 */
internal data class AutoSyncAsset(
    val name: String,
    val role: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
) {
    val required: Boolean get() = role != "audit-only"
}

internal data class AutoSyncPack(
    val language: String,
    val engine: String,
    val license: String,
    val sourceModel: String,
    val sampleRate: Int,
    val frameHopSeconds: Double,
    val inputNames: List<String>,
    val outputNames: List<String>,
    val blankId: Int,
    val wordDelimiterId: Int?,
    val normalization: String,
    val tokensFile: String?,
    val files: List<AutoSyncAsset>,
    val accuracyNotice: String,
) {
    val requiredAssets: List<AutoSyncAsset> get() = files.filter { it.required }
}

internal data class AutoSyncIndex(
    val releaseTag: String,
    val notice: String,
    val packs: Map<String, AutoSyncPack>,
)

internal object AutoSyncCatalog {
    const val RELEASE_TAG = "android-models-v0.1.0-beta.4"
    const val RELEASE_PREFIX =
        "https://github.com/lladlam/MeloX-AutoSync/releases/download/$RELEASE_TAG/"
    const val INDEX_URL = RELEASE_PREFIX + "android-models.json"
    const val INDEX_SHA256 = "4b7b54a6f436b4f74ac6028f3876477a2d7dffe13fba557f9649577354a0f0ed"
    private const val MAX_INDEX_BYTES = 1L shl 20
    private const val USER_AGENT = "Tune-Android"

    /** Optional proxies for unreliable direct GitHub access, tried in order. */
    private val MIRROR_PREFIXES = listOf(
        "",
        "https://ghfast.top/",
        "https://ghproxy.net/",
    )

    @Volatile
    private var cached: AutoSyncIndex? = null

    suspend fun index(force: Boolean = false): AutoSyncIndex = withContext(Dispatchers.IO) {
        cached?.takeIf { !force }?.let { return@withContext it }
        var lastError: Throwable? = null
        for (prefix in MIRROR_PREFIXES) {
            val bytes = runCatching { fetch(prefix + INDEX_URL, MAX_INDEX_BYTES) }
                .onFailure { lastError = it }
                .getOrNull() ?: continue
            val actual = sha256(bytes)
            if (!actual.equals(INDEX_SHA256, ignoreCase = true)) {
                lastError = IOException("model index signature mismatch")
                continue
            }
            val parsed = parse(bytes.toString(Charsets.UTF_8))
            cached = parsed
            return@withContext parsed
        }
        throw IOException("对齐模型索引不可用", lastError)
    }

    fun mirrorPrefixFor(url: String): List<String> {
        // Absolute asset URLs already point at GitHub; try mirrors first only
        // when the direct call fails.
        return listOf(url) + MIRROR_PREFIXES.filter { it.isNotEmpty() }.map { it + url }
    }

    private fun parse(text: String): AutoSyncIndex {
        val root = JSONObject(text)
        val notice = root.optString("accuracy_notice")
        val languages = root.optJSONObject("languages") ?: JSONObject()
        val packs = LinkedHashMap<String, AutoSyncPack>()
        languages.keys().forEach { language ->
            val pack = languages.optJSONObject(language) ?: return@forEach
            val inputNames = buildList {
                pack.optJSONObject("input")?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                val aux = pack.optJSONArray("aux_inputs")
                if (aux != null) {
                    for (i in 0 until aux.length()) {
                        aux.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
            val outputNames = buildList {
                val outputs = pack.optJSONArray("outputs") ?: return@buildList
                for (i in 0 until outputs.length()) {
                    outputs.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
            val textMeta = pack.optJSONObject("text")
            val files = buildList {
                val array = pack.optJSONArray("files") ?: return@buildList
                for (i in 0 until array.length()) {
                    val file = array.optJSONObject(i) ?: continue
                    val name = file.optString("name").takeIf(String::isNotBlank) ?: continue
                    val url = file.optString("download_url").takeIf(String::isNotBlank) ?: continue
                    add(
                        AutoSyncAsset(
                            name = name,
                            role = file.optString("role", "model"),
                            url = url,
                            bytes = file.optLong("bytes", -1L),
                            sha256 = file.optString("sha256").lowercase(),
                        ),
                    )
                }
            }
            packs[language] = AutoSyncPack(
                language = language,
                engine = pack.optString("engine"),
                license = pack.optString("license"),
                sourceModel = pack.optString("source_model"),
                sampleRate = pack.optInt("sample_rate", 16_000),
                frameHopSeconds = pack.optDouble("frame_hop_seconds", 0.02)
                    .takeIf { it.isFinite() && it > 0.0 } ?: 0.02,
                inputNames = inputNames,
                outputNames = outputNames,
                blankId = pack.optInt("blank_id", 0),
                wordDelimiterId = textMeta?.optInt("word_delimiter_id", -1)
                    ?.takeIf { it >= 0 },
                normalization = textMeta?.optString("normalization").orEmpty(),
                tokensFile = textMeta?.optString("tokens_file")?.takeIf(String::isNotBlank),
                files = files,
                accuracyNotice = notice,
            )
        }
        return AutoSyncIndex(
            releaseTag = root.optString("release_tag", RELEASE_TAG),
            notice = notice,
            packs = packs,
        )
    }

    suspend fun fetch(url: String, maxBytes: Long): ByteArray {
        var lastError: Throwable? = null
        for (candidate in mirrorPrefixFor(url)) {
            try {
                return fetchOnce(candidate, maxBytes)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastError = error
            }
        }
        throw IOException("下载失败", lastError)
    }

    private suspend fun fetchOnce(url: String, maxBytes: Long): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return TuneHttp.client(connectTimeoutMs = 8_000L, readTimeoutMs = 20_000L)
            .executeCancellable(request) { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty response body")
                readBodyBytesLimited(body, maxBytes)
            }
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
