package com.exodidio.tune.ui.navigation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.toBitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.exodidio.tune.ui.components.tuneSizedUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.exodidio.tune.player.ArtworkCrossfadeTransition
import com.exodidio.tune.ui.components.ArtworkThumbnailCache
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.theme.LocalTuneColors
import java.io.File

internal val FullScreenArtworkCornerRadius = 9.dp

internal data class FullScreenArtwork(
    val image: androidx.compose.ui.graphics.ImageBitmap,
    val dominant: Color,
    val sourceBitmap: Bitmap? = null,
    /** 同一首歌的 600 和 1600 图共享同一个 identity，用于判断能否直接沿用旧图/旧配色。 */
    val identity: String? = null,
)

/** 封面/背景换图时的交叉淡化状态。 */
internal data class ArtworkSwap(
    val outgoing: FullScreenArtwork? = null,
    val incoming: FullScreenArtwork? = null,
    val progress: Float = 1f,
)

/**
 * 曲目切换时保留上一张图，并在新图解码完成时做 220ms 交叉淡化；
 * 同一首歌 600→1600 只换清晰度，不做淡化。
 */
@Composable
internal fun rememberArtworkSwap(current: FullScreenArtwork?): ArtworkSwap {
    var outgoing by remember { mutableStateOf<FullScreenArtwork?>(null) }
    var incoming by remember { mutableStateOf(current) }
    val progress = remember { Animatable(1f) }
    val identity = current?.identity
    LaunchedEffect(identity) {
        val shown = incoming
        when {
            current == null -> {
                incoming = null
                progress.snapTo(1f)
            }
            shown == null -> {
                incoming = current
                progress.snapTo(1f)
            }
            identity != null && identity == shown.identity -> incoming = current
            else -> {
                outgoing = shown
                incoming = current
                progress.snapTo(0f)
                progress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                outgoing = null
            }
        }
    }
    return ArtworkSwap(outgoing, incoming, progress.value)
}

@Composable
internal fun FullScreenPlayerBackground(
    artwork: FullScreenArtwork?,
    outgoingArtwork: FullScreenArtwork?,
    incomingArtwork: FullScreenArtwork?,
    crossfadeProgress: Float,
    isArtworkCrossfading: Boolean,
    modifier: Modifier,
    adaptiveBackground: Boolean = false,
    playerTheme: com.exodidio.tune.settings.PlayerTheme = com.exodidio.tune.settings.PlayerTheme.Standard,
    appleBackdrop: com.exodidio.tune.player.AppleBackdropSettings = com.exodidio.tune.player.AppleBackdropSettings.Default,
) {
    val colors = LocalTuneColors.current
    val dominantColor by animateColorAsState(
        targetValue = artwork?.dominant ?: colors.playerBackdrop,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "full-screen-background-colour",
    )
    if (playerTheme == com.exodidio.tune.settings.PlayerTheme.Apple ||
        playerTheme == com.exodidio.tune.settings.PlayerTheme.Distorted
    ) {
        val blurred = playerTheme == com.exodidio.tune.settings.PlayerTheme.Apple
        Box(modifier.background(dominantColor)) {
            if (isArtworkCrossfading) {
                AppleBackdropLayer(outgoingArtwork ?: artwork, blurred, equalPowerOutgoing(crossfadeProgress), appleBackdrop)
                AppleBackdropLayer(incomingArtwork ?: artwork, blurred, equalPowerIncoming(crossfadeProgress), appleBackdrop)
            } else {
                AppleBackdropLayer(artwork, blurred, 1f, appleBackdrop)
            }
        }
        return
    }
    if (adaptiveBackground) {
        Box(modifier.background(dominantColor)) {
            if (isArtworkCrossfading) {
                PlayerBackgroundGradient(outgoingArtwork?.dominant ?: dominantColor, equalPowerOutgoing(crossfadeProgress))
                PlayerBackgroundGradient(incomingArtwork?.dominant ?: dominantColor, equalPowerIncoming(crossfadeProgress))
            } else {
                PlayerBackgroundGradient(dominantColor, 1f)
            }
        }
    } else {
        Box(modifier.background(colors.playerBackdrop)) {
            if (isArtworkCrossfading) {
                PlayerBackgroundGradient(outgoingArtwork?.dominant ?: colors.playerBackdrop, equalPowerOutgoing(crossfadeProgress))
                PlayerBackgroundGradient(incomingArtwork?.dominant ?: colors.playerBackdrop, equalPowerIncoming(crossfadeProgress))
            } else {
                PlayerBackgroundGradient(dominantColor, 1f)
            }
            Box(Modifier.fillMaxSize().background(colors.playerBackdrop.copy(alpha = 0.24f)))
        }
    }
}

@Composable
private fun AppleBackdropLayer(
    artwork: FullScreenArtwork?,
    blurred: Boolean,
    alpha: Float,
    settings: com.exodidio.tune.player.AppleBackdropSettings,
) {
    Box(Modifier.fillMaxSize().alpha(alpha.coerceIn(0f, 1f))) {
        ApplePlayerBackdrop(artwork, Color.Black, Modifier.fillMaxSize(), blurred = blurred, settings = settings)
    }
}

@Composable
private fun PlayerBackgroundGradient(dominant: Color, alpha: Float) {
    Box(Modifier.fillMaxSize().alpha(alpha).background(dominant.copy(alpha = 0.66f)))
}

@Composable
internal fun FullScreenPlayerArtwork(
    artwork: FullScreenArtwork?,
    outgoingArtwork: FullScreenArtwork?,
    incomingArtwork: FullScreenArtwork?,
    crossfadeProgress: Float,
    isArtworkCrossfading: Boolean,
    modifier: Modifier,
    fullscreen: Boolean = false,
    swap: ArtworkSwap? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = FullScreenArtworkCornerRadius,
) {
    val colors = LocalTuneColors.current
    val shape = RoundedCornerShape(cornerRadius)
    // Off (default) = normal card-shaped artwork. On = artwork blends with the
    // now-playing screen (full-bleed, no card chrome); the background gradient
    // behind it is already tinted from the same artwork.
    if (fullscreen) {
        Box(
            modifier.background(colors.glassElevated),
            contentAlignment = Alignment.Center,
        ) {
        ArtworkLayers(artwork, outgoingArtwork, incomingArtwork, crossfadeProgress, isArtworkCrossfading, swap)
        }
    } else {
        Box(
            modifier
                // 苹果封面是下偏的软投影：主阴影大模糊、向下偏移，另叠一层贴边接触阴影。
                // Modifier.shadow 只能按 elevation 出均匀投影，做不出这种分布。
                .dropShadow(shape = shape) {
                    radius = 12.dp.toPx()
                    spread = 0f
                    color = Color.Black
                    alpha = 0.15f
                    offset = Offset(0f, 4.dp.toPx())
                }
                .dropShadow(shape = shape) {
                    radius = 2.dp.toPx()
                    spread = 0f
                    color = Color.Black
                    alpha = 0.05f
                    offset = Offset(0f, 1.5.dp.toPx())
                }
                .clip(shape).background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, shape),
            contentAlignment = Alignment.Center,
        ) {
            ArtworkLayers(artwork, outgoingArtwork, incomingArtwork, crossfadeProgress, isArtworkCrossfading, swap)
        }
    }
}

@Composable
private fun ArtworkLayers(
    artwork: FullScreenArtwork?,
    outgoingArtwork: FullScreenArtwork?,
    incomingArtwork: FullScreenArtwork?,
    crossfadeProgress: Float,
    isArtworkCrossfading: Boolean,
    swap: ArtworkSwap?,
) {
    when {
        // 换图交叉淡化优先于音频交叉淡化：旧图全不透明打底，新图淡入，避免中点透底。
        swap?.outgoing != null -> {
            ArtworkLayer(swap.outgoing, 1f)
            ArtworkLayer(swap.incoming, swap.progress)
        }
        isArtworkCrossfading -> {
            ArtworkLayer(outgoingArtwork, equalPowerOutgoing(crossfadeProgress))
            ArtworkLayer(incomingArtwork, equalPowerIncoming(crossfadeProgress))
        }
        else -> ArtworkLayer(artwork, 1f)
    }
}

@Composable
private fun ArtworkLayer(artwork: FullScreenArtwork?, alpha: Float) {
    val colors = LocalTuneColors.current
    if (artwork != null) Image(artwork.image, null, Modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop)
    else Box(Modifier.fillMaxSize().alpha(alpha), contentAlignment = Alignment.Center) {
        MaterialSymbol(MaterialSymbols.MusicNote, null, tint = colors.textMuted, size = 64.dp)
    }
}

@Composable
internal fun rememberArtworkCrossfadeProgress(crossfade: ArtworkCrossfadeTransition?): Float {
    var progress by remember(crossfade?.id) { mutableFloatStateOf(if (crossfade == null) 1f else 0f) }
    LaunchedEffect(crossfade?.id) {
        if (crossfade == null) return@LaunchedEffect
        val durationNanos = crossfade.durationMs.coerceAtLeast(1L) * 1_000_000L
        var startedAtNanos = 0L
        while (progress < 1f) withFrameNanos { frameNanos ->
            if (startedAtNanos == 0L) startedAtNanos = frameNanos
            progress = ((frameNanos - startedAtNanos).toFloat() / durationNanos).coerceIn(0f, 1f)
        }
    }
    return progress
}

internal fun equalPowerOutgoing(progress: Float): Float = kotlin.math.cos(progress.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)
internal fun equalPowerIncoming(progress: Float): Float = kotlin.math.sin(progress.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)

internal fun fullscreenSampleSize(outWidth: Int, outHeight: Int, targetPx: Int = 1080): Int {
    var sample = 1
    while (outWidth / (sample * 2) >= targetPx && outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    return sample
}

/** 网易云封面文件名 <songId>.jpg / <songId>_1600.jpg 视为同一张图；本地封面按路径区分。 */
internal fun artworkIdentity(path: String?): String? {
    val name = path?.takeIf { it.isNotBlank() }?.substringAfterLast('/') ?: return null
    val digits = name.substringBefore('_').substringBefore('.')
    return if (digits.isNotEmpty() && digits.all(Char::isDigit)) "netease:$digits" else path
}

@Composable
internal fun rememberFullscreenArtwork(
    artworkPath: String?,
    keepPrevious: Boolean = true,
): FullScreenArtwork? {
    val context = LocalContext.current
    var artwork by remember(fullscreenArtworkMemoryKey(artworkPath, keepPrevious)) { mutableStateOf<FullScreenArtwork?>(null) }
    LaunchedEffect(artworkPath) {
        // 路径为空/文件缺失时保留上一张：背景不清空，封面槽由 identity 判断是否显示占位。
        if (artworkPath.isNullOrBlank()) return@LaunchedEffect
        val file = File(if (File(artworkPath).isAbsolute) artworkPath else File(context.filesDir, artworkPath).path)
        if (!file.isFile) return@LaunchedEffect
        val identity = artworkIdentity(artworkPath)
        val sameArtwork = artwork?.identity != null && artwork?.identity == identity
        val key = ArtworkThumbnailCache.cacheKey(file.absolutePath, 1080)
        var decodedBitmap: Bitmap? = null
        val cachedImage = ArtworkThumbnailCache.get(key)
        val image = cachedImage ?: withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val sample = fullscreenSampleSize(bounds.outWidth, bounds.outHeight, 1080)
                System.err.println("ARTDIM ${file.name} ${bounds.outWidth}x${bounds.outHeight} sample=$sample")
                val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }) ?: return@runCatching null
                decodedBitmap = bitmap
                val decoded = bitmap.asImageBitmap()
                ArtworkThumbnailCache.put(key, decoded)
                decoded
            }.getOrNull()
        } ?: return@LaunchedEffect
        val bitmap = decodedBitmap ?: runCatching { cachedImage?.asAndroidBitmap() }.getOrNull()
        val existingDominant = artwork?.takeIf { sameArtwork }?.dominant
        if (existingDominant != null) {
            // 同一首歌的高清图：直接换图，沿用 600 图的主色，不再重复取色。
            artwork = FullScreenArtwork(image, existingDominant, bitmap, identity)
            return@LaunchedEffect
        }
        // 先出图再取色：Palette 不应该拖住封面出现。
        val fastDominant = withContext(Dispatchers.Default) {
            bitmap?.let { fallbackDominantColor(it) } ?: Color.Transparent
        }
        artwork = FullScreenArtwork(image, fastDominant, bitmap, identity)
        if (bitmap != null) {
            val refined = withContext(Dispatchers.Default) { adaptiveDominantColor(bitmap) }
            val current = artwork
            if (current != null && current.image === image) {
                artwork = current.copy(dominant = refined)
            }
        }
    }
    return artwork
}

/**
 * 播放页直接走 Coil 取图：和列表共用同一个 ImageLoader 缓存。
 * 先请求列表用过的尺寸（200/600）通常直接命中，不需要再挤网易云封面下载队列。
 */
@Composable
internal fun rememberCoilArtwork(url: String?, sizePx: Int, identity: String?): FullScreenArtwork? {
    val context = LocalContext.current
    var artwork by remember(url, sizePx, identity) { mutableStateOf<FullScreenArtwork?>(null) }
    LaunchedEffect(url, sizePx, identity) {
        val target = url?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val loader = SingletonImageLoader.get(context)
                val request = ImageRequest.Builder(context)
                    .data(tuneSizedUrl(target, sizePx))
                    .allowHardware(false)
                    .build()
                loader.execute(request).image?.toBitmap()
            }.getOrNull()
        } ?: return@LaunchedEffect
        val image = bitmap.asImageBitmap()
        val fast = withContext(Dispatchers.Default) { fallbackDominantColor(bitmap) }
        artwork = FullScreenArtwork(image, fast, bitmap, identity)
        val refined = withContext(Dispatchers.Default) { adaptiveDominantColor(bitmap) }
        val current = artwork
        if (current != null && current.image === image) artwork = current.copy(dominant = refined)
    }
    return artwork
}

/** A crossfade layer is path-scoped; only the normal player cover may persist across paths. */
internal fun fullscreenArtworkMemoryKey(artworkPath: String?, keepPrevious: Boolean): Any? =
    if (keepPrevious) FullscreenArtworkRetainedKey else artworkPath

private object FullscreenArtworkRetainedKey

internal fun adaptiveDominantColor(bitmap: Bitmap): Color {
    return runCatching { bitmap.extractAdaptivePlayerColors().background }.getOrDefault(fallbackDominantColor(bitmap))
}

private fun fallbackDominantColor(bitmap: Bitmap): Color {
    val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
    val pixels = IntArray(24 * 24)
    sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
    val opaque = pixels.filter { android.graphics.Color.alpha(it) > 32 }
    if (opaque.isEmpty()) return Color.Transparent
    return Color(
        opaque.sumOf { android.graphics.Color.red(it) } / opaque.size,
        opaque.sumOf { android.graphics.Color.green(it) } / opaque.size,
        opaque.sumOf { android.graphics.Color.blue(it) } / opaque.size,
    )
}
