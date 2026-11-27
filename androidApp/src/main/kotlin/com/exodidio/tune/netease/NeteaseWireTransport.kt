package com.exodidio.tune.netease

import android.util.Log
import com.exodidio.tune.net.TuneHttp
import com.exodidio.tune.net.executeCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 网易云 HTTP / WEAPI / EAPI 加密传输与 Cookie / JSON 辅助。 */
internal object NeteaseWireTransport {
    const val BASE = "https://music.163.com"
    /** 新版桌面登录接口：旧 music.163.com 的扫码接口已被风控（8821）。 */
    const val QR_BASE = "https://interface.music.163.com"
    const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val EAPI_BASE = "https://interface.music.163.com"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val WEApiKey = "0CoJUm6Qyw8W8jud"
    private const val WEApiIv = "0102030405060708"
    private const val RSAModulusHex =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val Base62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val DESKTOP_URS_APPID =
        "F2219AE9D7828A7D73E2006D000C61031D196A37DB497E3885B8298504867886B6F0E44087D61EFC06BE92279CD6EEC6"
    private const val DESKTOP_NMDI =
        "Q1NKTQkBDAAMIEF4coQMHcb6TLA7AAAAciOiJ%2F%2FOO4VQ7m%2FLvLJ1pD9CIsJP5mfzI4SusB%2BaNScGLpThEYBcPxGzj0pL5hLdZ7LqB2UVULdYgc0%3D"
    private const val DESKTOP_CSRF = "40ab38f0a305fc4c7ff68e636bcf34aa"
    private val wnmcidAnchorMs: Long by lazy { System.currentTimeMillis() }
    val json = Json { ignoreUnknownKeys = true }

    private data class WireIdentity(
        val nuid: String,
        val nmtid: String,
        val wnmcid: String,
        val ip: String,
    )

    private fun deriveWireIdentity(deviceId: String): WireIdentity {
        val seed = md5Hex("tune:wire:${deviceId.ifBlank { "default" }}")
        val nuid = seed.substring(0, 12)
        val nmtid = seed.substring(12, 28)
        val prefix = seed.substring(0, 6).map { ch ->
            ('a'.code + (ch.digitToIntOrNull(16) ?: 0)).toChar()
        }.joinToString("")
        val b1 = 1 + (seed.substring(0, 2).toInt(16) % 253)
        val b2 = 1 + (seed.substring(2, 4).toInt(16) % 253)
        return WireIdentity(
            nuid = nuid,
            nmtid = nmtid,
            wnmcid = "$prefix.$wnmcidAnchorMs.01.0",
            ip = "116.25.$b1.$b2",
        )
    }

    internal fun buildInterfaceCookieHeader(cookie: String?): String {
        val deviceId = NeteaseWireConfig.deviceId
        val identity = deriveWireIdentity(deviceId)
        val values = LinkedHashMap<String, String>()
        values["os"] = NeteaseWireConfig.OS
        values["appver"] = NeteaseWireConfig.APP_VERSION
        values["osver"] = NeteaseWireConfig.osver
        values["channel"] = NeteaseWireConfig.CHANNEL
        values["versioncode"] = NeteaseWireConfig.VERSION_CODE.toString()
        values["buildver"] = NeteaseWireConfig.BUILD_VERSION
        values["resolution"] = "2268x1080"
        if (deviceId.isNotBlank()) values["deviceId"] = deviceId
        values["packageType"] = "release"
        values["ntes_kaola_ad"] = "1"
        values["_ntes_nuid"] = identity.nuid
        values["WNMCID"] = identity.wnmcid
        values["WEVNSM"] = "1.0.0"
        values["URS_APPID"] = DESKTOP_URS_APPID
        values["NMDI"] = DESKTOP_NMDI
        values["NMTID"] = identity.nmtid
        values["__csrf"] = DESKTOP_CSRF
        cookie?.split(';')?.forEach { segment ->
            val trimmed = segment.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) values[key] = value
            }
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 根据当前传输模式走 interface 明文 api（对齐 MeiloX）或旧网页端请求。 */
    suspend fun requestApi(
        path: String,
        form: Map<String, String>,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = true,
    ): JsonObject =
        if (NeteaseWireConfig.mode == NeteaseWireMode.EapiAndroid) {
            requestInterfaceApi(path, form, cookie, connectTimeoutMs, readTimeoutMs, retry)
        } else {
            request(path, form, cookie, connectTimeoutMs, readTimeoutMs, retry)
        }

    /**
     * 对齐 MeiloX `api` 模式：直连 `https://interface.music.163.com/api/...`，
     * 携带客户端防风控 Cookie 基线与 `X-Real-IP`/`X-Forwarded-For`。
     */
    suspend fun requestInterfaceApi(
        path: String,
        form: Map<String, String>,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = true,
    ): JsonObject {
        val identity = deriveWireIdentity(NeteaseWireConfig.deviceId)
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(EAPI_BASE + path)
            .header("User-Agent", NeteaseWireConfig.USER_AGENT)
            .header("Accept", "*/*")
            .header("X-Real-IP", identity.ip)
            .header("X-Forwarded-For", identity.ip)
            .header("Cookie", buildInterfaceCookieHeader(cookie))
            .post(body)
            .build()
        val client = TuneHttp.client(
            connectTimeoutMs = connectTimeoutMs.toLong(),
            readTimeoutMs = readTimeoutMs.toLong(),
        )
        suspend fun executeOnce(): JsonObject = client.executeCancellable(request) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throwHttpError("api", path, response.code, text)
            json.parseToJsonElement(text) as? JsonObject ?: error("响应格式异常")
        }
        return if (!retry) {
            executeOnce()
        } else {
            try {
                executeOnce()
            } catch (first: Throwable) {
                if (first is CancellationException) throw first
                Log.w("NeteaseWire", "api failed $path: ${first.message}")
                try {
                    executeOnce()
                } catch (second: Throwable) {
                    if (second is CancellationException) throw second
                    Log.w("NeteaseWire", "api failed again $path: ${second.message}")
                    throw first
                }
            }
        }
    }

    suspend fun request(
        path: String,
        form: Map<String, String>,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = true,
    ): JsonObject {
        val (root, _) = requestWithCookies(path, form, cookie, connectTimeoutMs, readTimeoutMs, retry)
        return root
    }

    suspend fun requestJson(
        path: String,
        data: JsonObject,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = false,
    ): JsonObject {
        if (NeteaseWireConfig.mode == NeteaseWireMode.EapiAndroid) {
            return requestEapiJson(path, data, cookie, connectTimeoutMs, readTimeoutMs, retry).first
        }
        return requestWeapiJson(path, data, cookie, retry)
    }

    suspend fun requestWithCookies(
        path: String,
        form: Map<String, String>,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = true,
        base: String = BASE,
    ): Pair<JsonObject, List<String>> {
        // 激进模式：业务请求统一走官方 Android eapi；扫码登录仍保留原网页链路。
        if (NeteaseWireConfig.mode == NeteaseWireMode.EapiAndroid &&
            base == BASE &&
            !path.startsWith("/api/login/qrcode/")
        ) {
            return requestEapi(path, form, cookie, connectTimeoutMs, readTimeoutMs, retry)
        }
        Log.d("NeteaseWire", "web $path")
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(base + path)
            .neteaseHeaders(cookie, UA)
            .post(body)
            .build()
        val client = TuneHttp.client(
            connectTimeoutMs = connectTimeoutMs.toLong(),
            readTimeoutMs = readTimeoutMs.toLong(),
        )
        suspend fun executeOnce(): Pair<JsonObject, List<String>> = client.executeCancellable(request) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throwHttpError("web", path, response.code, text)
            val root = json.parseToJsonElement(text) as? JsonObject ?: error("响应格式异常")
            val cookies = response.headers("Set-Cookie")
            root to cookies
        }
        return if (!retry) {
            executeOnce()
        } else {
            try {
                executeOnce()
            } catch (first: Throwable) {
                if (first is CancellationException) throw first
                try {
                    executeOnce()
                } catch (second: Throwable) {
                    if (second is CancellationException) throw second
                    throw first
                }
            }
        }
    }

    suspend fun requestWeapi(
        path: String,
        data: Map<String, String>,
        cookie: String?,
        retry: Boolean = true,
        forceWeapi: Boolean = false,
    ): JsonObject {
        if (NeteaseWireConfig.mode == NeteaseWireMode.EapiAndroid && !forceWeapi) {
            return requestEapi(path, data, cookie, retry = retry).first
        }
        val jsonData = buildJsonObject {
            data.forEach { (key, value) -> put(key, value) }
        }
        return requestWeapiJson(path, jsonData, cookie, retry)
    }

    private suspend fun requestWeapiJson(
        path: String,
        data: JsonObject,
        cookie: String?,
        retry: Boolean = true,
    ): JsonObject {
        val csrf = cookie?.let { Regex("__csrf=([^;]+)").find(it)?.groupValues?.getOrNull(1) }
            ?: (0 until 32).joinToString("") { Base62.random().toString() }
        val payload = buildJsonObject {
            put("csrf_token", csrf)
            data.forEach { (key, value) -> put(key, value) }
        }.toString()
        val secret = (0 until 16).map { Base62.random() }.joinToString("")
        val params = aesCbcBase64(aesCbcBase64(payload, WEApiKey), secret)
        val encSecKey = rsaEncrypt(secret.reversed())
        val fullCookie = buildString {
            if (!cookie.isNullOrBlank()) append(cookie)
            if (cookie?.contains("__csrf=") != true) {
                if (isNotEmpty()) append("; ")
                append("__csrf=").append(csrf)
            }
        }
        return postForm(
            BASE + "/weapi/" + path.removePrefix("/api/"),
            mapOf("params" to params, "encSecKey" to encSecKey),
            fullCookie,
            retry = retry,
        )
    }

    private suspend fun postForm(
        url: String,
        form: Map<String, String>,
        cookie: String?,
        retry: Boolean = true,
    ): JsonObject {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .neteaseHeaders(cookie)
            .post(body)
            .build()
        val client = TuneHttp.client(connectTimeoutMs = 10_000L, readTimeoutMs = 15_000L)
        suspend fun executeOnce(): JsonObject = client.executeCancellable(request) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throwHttpError("weapi", url, response.code, text)
            json.parseToJsonElement(text) as? JsonObject ?: error("响应格式异常")
        }
        return if (!retry) {
            executeOnce()
        } else {
            try {
                executeOnce()
            } catch (first: Throwable) {
                if (first is CancellationException) throw first
                try {
                    executeOnce()
                } catch (second: Throwable) {
                    if (second is CancellationException) throw second
                    throw first
                }
            }
        }
    }

    internal fun formToEapiJsonObject(form: Map<String, String>): JsonObject = buildJsonObject {
        form.forEach { (key, value) ->
            // 保留原始字符串（如 /api/v3/song/detail 的 c、/api/playlist/manipulate/tracks 的 trackIds），
            // 与 MeiloX wirePayload / requestWeapiJson 保持一致，避免被强转为 JsonArray 触发 code=400。
            put(key, JsonPrimitive(value))
        }
    }

    /**
     * 官方 Android eapi：AES-ECB 签名体 + 客户端身份头，主机 interface.music.163.com。
     * 响应正常是明文 JSON；若被加密则按同一密钥解密并按 gzip 处理。
     */
    private suspend fun requestEapi(
        path: String,
        form: Map<String, String>,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = true,
    ): Pair<JsonObject, List<String>> =
        requestEapiJson(
            path,
            formToEapiJsonObject(form),
            cookie,
            connectTimeoutMs,
            readTimeoutMs,
            retry,
        )

    private suspend fun requestEapiJson(
        path: String,
        data: JsonObject,
        cookie: String?,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        retry: Boolean = true,
    ): Pair<JsonObject, List<String>> {
        val payload = buildEapiPayload(data, cookie)
        val params = encryptEapiParams(path, payload)
        Log.d("NeteaseWire", "eapi $path")
        val body = FormBody.Builder().add("params", params).build()
        val url = if (path.startsWith("/api/")) {
            EAPI_BASE + "/eapi/" + path.removePrefix("/api/")
        } else {
            EAPI_BASE + path
        }
        val request = Request.Builder()
            .url(url)
            .neteaseHeaders(cookie, NeteaseWireConfig.USER_AGENT)
            .post(body)
            .build()
        val client = TuneHttp.client(
            connectTimeoutMs = connectTimeoutMs.toLong(),
            readTimeoutMs = readTimeoutMs.toLong(),
        )
        suspend fun executeOnce(): Pair<JsonObject, List<String>> = client.executeCancellable(request) { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val decoded = decodeEapiResponse(bytes)
            if (!response.isSuccessful) throwHttpError("eapi", path, response.code, decoded)
            val root = json.parseToJsonElement(decoded) as? JsonObject
                ?: error("响应格式异常")
            val cookies = response.headers("Set-Cookie")
            root to cookies
        }
        return if (!retry) {
            executeOnce()
        } else {
            try {
                executeOnce()
            } catch (first: Throwable) {
                if (first is CancellationException) throw first
                Log.w("NeteaseWire", "eapi failed $path: ${first.message}")
                try {
                    executeOnce()
                } catch (second: Throwable) {
                    if (second is CancellationException) throw second
                    Log.w("NeteaseWire", "eapi failed again $path: ${second.message}")
                    throw first
                }
            }
        }
    }

    private fun aesCbcBase64(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(WEApiIv.toByteArray(Charsets.UTF_8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private fun rsaEncrypt(text: String): String {
        val modulus = BigInteger(1, hexBytes(RSAModulusHex))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)),
        )
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val size = (modulus.bitLength() + 7) / 8
        val input = text.toByteArray(Charsets.UTF_8)
        val block = ByteArray(size)
        input.copyInto(block, size - input.size)
        return cipher.doFinal(block).joinToString("") { "%02x".format(it) }
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun buildEapiPayload(
        data: JsonObject,
        cookie: String?,
    ): String {
        val csrf = cookie?.let { Regex("__csrf=([^;]+)").find(it)?.groupValues?.getOrNull(1) }.orEmpty()
        val musicU = cookie?.let { Regex("MUSIC_U=([^;]+)").find(it)?.groupValues?.getOrNull(1) }
        val header = buildJsonObject {
            put("os", NeteaseWireConfig.OS)
            put("appver", NeteaseWireConfig.APP_VERSION)
            put("versioncode", NeteaseWireConfig.VERSION_CODE)
            put("buildver", (System.currentTimeMillis() / 1_000L).toString())
            put("osver", NeteaseWireConfig.osver)
            put("channel", NeteaseWireConfig.CHANNEL)
            put("deviceId", NeteaseWireConfig.deviceId)
            put("requestId", "${System.currentTimeMillis()}_${(0..999).random().toString().padStart(4, '0')}")
            put("__csrf", csrf)
            musicU?.takeIf(String::isNotBlank)?.let { put("MUSIC_U", it) }
        }
        return buildJsonObject {
            data.forEach { (key, value) -> put(key, value) }
            put("header", header)
            put("e_r", false)
        }.toString()
    }

    private fun throwHttpError(tag: String, path: String, httpCode: Int, bodyText: String): Nothing {
        val snippet = bodyText.trim().take(300)
        Log.w("NeteaseWire", "$tag $path http=$httpCode body=$snippet")
        val parsed = runCatching { json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull()
        val parsedCode = parsed?.stringOrNull("code")?.toIntOrNull()
        val rawMsg = parsed?.stringOrNull("message")?.takeIf(String::isNotBlank)
            ?: parsed?.stringOrNull("msg")?.takeIf(String::isNotBlank)
        if (parsedCode != null && parsedCode != 200) {
            val message = rawMsg ?: "HTTP $httpCode (code=$parsedCode)"
            if (parsedCode == 301 || parsedCode == 302) {
                throw NeteaseWriteException.LoginRequired(message)
            }
            throw NeteaseWriteException.ServerError(parsedCode, message)
        }
        throw NeteaseWriteException.ServerError(httpCode, rawMsg ?: "HTTP $httpCode")
    }

    private fun encryptEapiParams(path: String, payload: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest("nobody${path}use${payload}md5forencrypt".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val text = "$path-36cd479b6b5-$payload-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }

    private fun decodeEapiResponse(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return text
        val decrypted = runCatching {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"))
            cipher.doFinal(bytes)
        }.getOrNull() ?: return text
        val data = if (decrypted.size >= 2 && decrypted[0] == 0x1f.toByte() && decrypted[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(decrypted)).use { it.readBytes() }
        } else {
            decrypted
        }
        return data.toString(Charsets.UTF_8)
    }

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun joinCookies(headers: List<String>): String? = headers
        .mapNotNull { header -> header.substringBefore(';').trim().takeIf { it.contains('=') } }
        .filter { segment -> segment.substringAfter('=').isNotBlank() }
        .distinct()
        .joinToString("; ")
        .takeIf { it.isNotBlank() }

    /** 合并 Cookie 头（按名字去重，后者覆盖前者）。 */
    fun mergeCookies(vararg headers: String?): String? {
        val byName = LinkedHashMap<String, String>()
        headers.filterNotNull().forEach { header ->
            header.split(';').map(String::trim).filter { it.contains('=') }.forEach { segment ->
                byName[segment.substringBefore('=').trim()] = segment
            }
        }
        return byName.values.joinToString("; ").takeIf { it.isNotBlank() }
    }

    fun mergeCookieHeader(
        base: String?,
        setCookies: List<String>,
        bodyCookie: String?,
    ): String? {
        val values = LinkedHashMap<String, String>()
        fun absorb(header: String?) {
            header?.split(';')?.forEach { segment ->
                val trimmed = segment.trim()
                if (!trimmed.contains('=')) return@forEach
                val key = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim()
                if (key.isNotEmpty() && value.isNotEmpty()) values[key] = value
            }
        }
        absorb(base)
        setCookies.forEach(::absorb)
        absorb(bodyCookie)
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }.takeIf { it.isNotBlank() }
    }
}

internal fun Request.Builder.neteaseHeaders(
    cookie: String?,
    userAgent: String = NeteaseWireTransport.UA,
): Request.Builder {
    header("User-Agent", userAgent)
    header("Referer", "https://music.163.com/")
    header("Accept", "*/*")
    if (!cookie.isNullOrBlank()) header("Cookie", cookie)
    return this
}

internal fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** 首页块的标题字段有对象和字符串两种形态（{"title": "..."} / "..."）。 */
internal fun JsonObject.titleText(key: String): String =
    when (val element = this[key]) {
        is JsonObject -> element.string("title").ifBlank { element.string("text") }
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> ""
    }

internal fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
