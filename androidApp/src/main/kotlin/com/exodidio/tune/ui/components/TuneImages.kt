package com.exodidio.tune.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.exodidio.tune.netease.NeteaseImageCache
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Path.Companion.toOkioPath

/**
 * 图片加载底座：全局一个 ImageLoader（内存 LRU + 磁盘 LRU + 请求去重 + 按尺寸解码）。
 * 磁盘上限跟“封面缓存”设置一致；失败由网络层重试一次，其余交给下次可见时再请求。
 */
object TuneImages {
    @Volatile private var loader: ImageLoader? = null
    @Volatile private var diskLimitBytes: Long = -1L
    private var appContext: Context? = null
    // 磁盘缓存构建会建目录、开 journal，放到进程级 IO，不占启动主线程。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        applyDiskLimit(context.applicationContext, force = true)
    }

    /** 封面缓存上限变化时重建 loader（磁盘缓存大小只能在构建时指定）。 */
    fun applyDiskLimit(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        appContext = app
        scope.launch { runCatching { rebuild(app, force) } }
    }

    @Synchronized
    private fun rebuild(app: Context, force: Boolean) {
        val limit = NeteaseImageCache.sizeLimitMb(app) * 1024L * 1024L
        if (!force && loader != null && limit == diskLimitBytes) return
        loader?.shutdown()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(RetryOnceInterceptor())
            .build()
        val newLoader = ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(Runtime.getRuntime().maxMemory() / 6)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(app.cacheDir, "tune-images").toOkioPath())
                    .maxSizeBytes(limit)
                    .build()
            }
            .crossfade(true)
            .build()
        loader = newLoader
        diskLimitBytes = limit
        SingletonImageLoader.setSafe { newLoader }
    }

    /** 清空图片磁盘缓存（内存缓存随下一次访问自然重建）；删除走 IO。 */
    fun clearDisk() {
        val current = loader
        scope.launch { runCatching { current?.diskCache?.clear() } }
    }
}

/** 网络抖动/5xx 时重试一次，避免一张图一次失败就被当成永久失败。 */
private class RetryOnceInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            val response = chain.proceed(request)
            if (response.code >= 500) {
                response.close()
                chain.proceed(request)
            } else {
                response
            }
        } catch (error: IOException) {
            chain.proceed(request)
        }
    }
}

/** 网易云 CDN 支持按尺寸取图；非 http 源（本地文件）原样返回。 */
fun tuneSizedUrl(url: String, sizePx: Int): String {
    if (!url.startsWith("http", ignoreCase = true)) return url
    if (url.contains("param=")) return url
    val separator = if (url.contains('?')) '&' else '?'
    return "$url${separator}param=${sizePx}y$sizePx"
}

/**
 * 远端图片：加载中/失败时显示 fallback，成功后覆盖；进入组合才发请求，离开即取消。
 */
@Composable
fun TuneRemoteImage(
    url: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: (@Composable BoxScope.() -> Unit)? = null,
) {
    Box(modifier) {
        fallback?.invoke(this)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = tuneSizedUrl(url, sizePx),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
