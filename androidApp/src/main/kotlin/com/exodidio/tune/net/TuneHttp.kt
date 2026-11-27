package com.exodidio.tune.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import kotlin.coroutines.resumeWithException

/**
 * 全局共享 OkHttp 传输底座：复用同一个 [ConnectionPool] 与 [Dispatcher]，
 * 统一连接/读写超时与协程取消语义，避免各模块重复造 HttpURLConnection 轮子。
 */
internal object TuneHttp {
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 12
    }
    private val sharedPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedPool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()

    private val trustAllPair: Pair<SSLContext, X509TrustManager> by lazy {
        @Suppress("CustomX509TrustManager")
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("不接受客户端证书")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        context to trustAll
    }

    private val trustAllBaseClient: OkHttpClient by lazy {
        val (context, manager) = trustAllPair
        baseClient.newBuilder()
            .sslSocketFactory(context.socketFactory, manager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun client(
        connectTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 15_000L,
        writeTimeoutMs: Long = 15_000L,
        followRedirects: Boolean = true,
    ): OkHttpClient {
        if (connectTimeoutMs == 10_000L &&
            readTimeoutMs == 15_000L &&
            writeTimeoutMs == 15_000L &&
            followRedirects
        ) {
            return baseClient
        }
        return baseClient.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .build()
    }

    /** 仅用于首次配对前向自签桌面端拉取公开 TLS 指纹。 */
    fun trustAllTlsClient(
        connectTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 5_000L,
    ): OkHttpClient = trustAllBaseClient.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 按证书 SHA-256 指纹校验的共享 OkHttpClient（复用同一个连接池与调度器）。
     * 指纹为空时直接退回标准 [client]。
     */
    fun pinnedClient(
        fingerprint: String,
        connectTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 15_000L,
    ): OkHttpClient {
        if (fingerprint.isBlank()) {
            return client(connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs)
        }
        val key = fingerprint.lowercase()
        val pinnedBase = pinnedClients.getOrPut(key) {
            val (context, manager) = buildPinnedTls(fingerprint)
            val fallback = HttpsURLConnection.getDefaultHostnameVerifier()
            baseClient.newBuilder()
                .sslSocketFactory(context.socketFactory, manager)
                .hostnameVerifier { host, session ->
                    runCatching {
                        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                        leaf != null && fingerprintOf(leaf).equals(fingerprint, ignoreCase = true)
                    }.getOrDefault(false) || fallback.verify(host, session)
                }
                .build()
        }
        if (connectTimeoutMs == 10_000L && readTimeoutMs == 15_000L) return pinnedBase
        return pinnedBase.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun fingerprintOf(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

    private fun buildPinnedTls(fingerprint: String): Pair<SSLContext, X509TrustManager> {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>()
            .first()
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("不接受客户端证书")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certs = chain ?: emptyArray()
                val leaf = certs.firstOrNull() ?: throw CertificateException("证书链为空")
                if (fingerprintOf(leaf).equals(fingerprint, ignoreCase = true)) return
                system.checkServerTrusted(certs, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(manager), SecureRandom())
        }
        return context to manager
    }
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) {
                    response.close()
                    return
                }
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

internal suspend inline fun <T> OkHttpClient.executeCancellable(
    request: Request,
    crossinline block: suspend (Response) -> T,
): T = withContext(Dispatchers.IO) {
    val call = newCall(request)
    val response = call.awaitResponse()
    val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
    try {
        response.use { block(it) }
    } finally {
        cancelHandle?.dispose()
    }
}

internal suspend fun readBodyBytesLimited(body: ResponseBody, maxBytes: Long): ByteArray {
    val declared = body.contentLength()
    if (declared > maxBytes) throw IOException("response too large")
    val output = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("response too large")
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}
