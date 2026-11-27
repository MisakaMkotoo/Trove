package com.exodidio.tune.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.toBitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.exodidio.tune.ui.components.LocalReduceMotion
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
    val image: ImageBitmap,
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
    dynamicBackdrop: com.exodidio.tune.player.DynamicBackdropSettings = com.exodidio.tune.player.DynamicBackdropSettings.Default,
    backdropActive: Boolean = true,
    lyricsVisible: Boolean = false,
    presetSeed: String? = null,
) {
    val colors = LocalTuneColors.current
    val dominantColor by animateColorAsState(
        targetValue = artwork?.dominant ?: colors.playerBackdrop,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "full-screen-background-colour",
    )
    if (playerTheme == com.exodidio.tune.settings.PlayerTheme.Dynamic) {
        val shown = incomingArtwork ?: artwork
        Box(modifier.background(dominantColor)) {
            com.exodidio.tune.backdrop.DynamicBackdrop(
                settings = dynamicBackdrop,
                artwork = shown?.sourceBitmap,
                artworkIdentity = shown?.identity,
                lyricsVisible = lyricsVisible,
                modifier = Modifier.fillMaxSize(),
                active = backdropActive,
                presetSeed = presetSeed,
            )
        }
        return
    }
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
        ApplePlayerBackdrop(artwork, Color.Transparent, Modifier.fillMaxSize(), blurred = blurred, settings = settings)
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
    vinylRingWearEnabled: Boolean = false,
    vinylWearProgress: Float = 0f,
    gyroSheenEnabled: Boolean = false,
    isCompactPanelOpen: Boolean = false,
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
            ArtworkNoveltyOverlays(
                vinylRingWearEnabled = vinylRingWearEnabled,
                vinylWearProgress = vinylWearProgress,
                gyroSheenEnabled = gyroSheenEnabled,
                isCompactPanelOpen = isCompactPanelOpen,
            )
        }
    } else {
        val density = LocalDensity.current
        val primaryShadowPaint = remember(density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = 0.15f).toArgb()
                val blurPx = with(density) { 12.dp.toPx() }.coerceAtLeast(0.5f)
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
        val contactShadowPaint = remember(density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = 0.05f).toArgb()
                val blurPx = with(density) { 2.dp.toPx() }.coerceAtLeast(0.5f)
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
        Box(
            modifier
                // 苹果封面是下偏的软投影：主阴影大模糊、向下偏移，另叠一层贴边接触阴影。
                // 使用 BlurMaskFilter 直接绘制，避免 Compose dropShadow 在动态圆角/尺寸动画时
                // 向无界 AndroidShadowContext 缓存海量 Alpha8 Bitmap 导致 OOM 与系统卡死。
                .drawBehind {
                    if (size.width > 0f && size.height > 0f) {
                        val cornerPx = cornerRadius.toPx().coerceIn(0f, minOf(size.width, size.height) / 2f)
                        val primaryOffsetY = 4.dp.toPx()
                        val contactOffsetY = 1.5.dp.toPx()
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        nativeCanvas.drawRoundRect(
                            0f,
                            primaryOffsetY,
                            size.width,
                            size.height + primaryOffsetY,
                            cornerPx,
                            cornerPx,
                            primaryShadowPaint,
                        )
                        nativeCanvas.drawRoundRect(
                            0f,
                            contactOffsetY,
                            size.width,
                            size.height + contactOffsetY,
                            cornerPx,
                            cornerPx,
                            contactShadowPaint,
                        )
                    }
                }
                .clip(shape).background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, shape),
            contentAlignment = Alignment.Center,
        ) {
            ArtworkLayers(artwork, outgoingArtwork, incomingArtwork, crossfadeProgress, isArtworkCrossfading, swap)
            ArtworkNoveltyOverlays(
                vinylRingWearEnabled = vinylRingWearEnabled,
                vinylWearProgress = vinylWearProgress,
                gyroSheenEnabled = gyroSheenEnabled,
                isCompactPanelOpen = isCompactPanelOpen,
            )
        }
    }
}

@Composable
private fun ArtworkNoveltyOverlays(
    vinylRingWearEnabled: Boolean,
    vinylWearProgress: Float,
    gyroSheenEnabled: Boolean,
    isCompactPanelOpen: Boolean,
) {
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    val showWear = vinylRingWearEnabled && vinylWearProgress > 0.01f && !isCompactPanelOpen
    if (showWear) {
        val w = vinylWearProgress.coerceIn(0f, 1f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = minOf(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f)
            // Outer cardboard sleeve ring impression from repeated record handling.
            drawCircle(
                color = Color(0xFFF3EBDD).copy(alpha = 0.045f + 0.135f * w),
                radius = minDim * 0.456f,
                center = center,
                style = Stroke(width = (8.dp.toPx() + 8.dp.toPx() * w)),
            )
            // Secondary subtle inner paper scuff ring.
            drawCircle(
                color = Color(0xFFE7DAC4).copy(alpha = 0.025f + 0.085f * w),
                radius = minDim * 0.428f,
                center = Offset(center.x + 1.5.dp.toPx(), center.y - 1.dp.toPx()),
                style = Stroke(width = 3.5.dp.toPx()),
            )
            // Faint top & bottom edge cardboard rub.
            val edgeBand = 12.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFEDE2CE).copy(alpha = 0.09f * w), Color.Transparent),
                    startY = 0f,
                    endY = edgeBand,
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xFFEDE2CE).copy(alpha = 0.08f * w)),
                    startY = (size.height - edgeBand).coerceAtLeast(0f),
                    endY = size.height,
                ),
            )
            // Gentle darkened edge vignette on aged sleeves.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        Color(0xFF1A140F).copy(alpha = 0.14f * w),
                    ),
                    center = center,
                    radius = minDim * 0.72f,
                ),
            )
        }
    }

    val activeGyro = gyroSheenEnabled && !isCompactPanelOpen && !reduceMotion
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    DisposableEffect(context, activeGyro) {
        if (!activeGyro) {
            tiltX = 0f
            tiltY = 0f
            return@DisposableEffect onDispose {}
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            return@DisposableEffect onDispose {}
        }
        var baselineX: Float? = null
        var baselineY: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                val bx = baselineX ?: gx.also { baselineX = it }
                val by = baselineY ?: gy.also { baselineY = it }
                // Slowly re-center baseline so natural posture shifts stay comfortable
                // while deliberate wrist tilts sweep the glass reflection immediately.
                baselineX = bx * 0.992f + gx * 0.008f
                baselineY = by * 0.992f + gy * 0.008f
                val relX = -(gx - bx) / 3.6f
                val relY = (gy - by) / 3.6f
                val absX = -gx / 5.2f
                val absY = (gy - 4.5f) / 5.2f
                val targetX = (relX * 0.72f + absX * 0.28f).coerceIn(-1f, 1f)
                val targetY = (relY * 0.72f + absY * 0.28f).coerceIn(-1f, 1f)
                tiltX = tiltX * 0.68f + targetX * 0.32f
                tiltY = tiltY * 0.68f + targetY * 0.32f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            manager.unregisterListener(listener)
        }
    }
    if (activeGyro) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sweepShift = tiltX * size.width * 0.48f + tiltY * size.height * 0.26f
            val centerX = size.width * 0.5f + sweepShift
            val centerY = size.height * 0.5f + tiltY * size.height * 0.28f
            val halfSpan = size.minDimension * 0.36f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.04f),
                        Color.White.copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    start = Offset(centerX - halfSpan, centerY - halfSpan),
                    end = Offset(centerX + halfSpan, centerY + halfSpan),
                ),
                blendMode = BlendMode.Screen,
            )
            val secondaryOffset = size.minDimension * 0.26f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                    start = Offset(centerX - secondaryOffset - halfSpan * 0.5f, centerY - secondaryOffset - halfSpan * 0.5f),
                    end = Offset(centerX - secondaryOffset + halfSpan * 0.5f, centerY - secondaryOffset + halfSpan * 0.5f),
                ),
                blendMode = BlendMode.Screen,
            )
            val tiltMagnitude = kotlin.math.hypot(tiltX, tiltY).coerceIn(0f, 1f)
            val rimAlpha = 0.05f + 0.14f * tiltMagnitude
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = rimAlpha),
                        Color.Transparent,
                        Color.White.copy(alpha = rimAlpha * 0.6f),
                    ),
                    start = Offset(if (tiltX >= 0f) 0f else size.width, if (tiltY >= 0f) 0f else size.height),
                    end = Offset(if (tiltX >= 0f) size.width else 0f, if (tiltY >= 0f) size.height else 0f),
                ),
                blendMode = BlendMode.Screen,
            )
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
                Log.d("ARTDIM", "${file.name} ${bounds.outWidth}x${bounds.outHeight} sample=$sample")
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
        val cachedDominant = identity?.let { FullScreenDominantCache.get(it) }
        // 先出图再取色：Palette 不应该拖住封面出现。
        val fastDominant = cachedDominant ?: withContext(Dispatchers.Default) {
            bitmap?.let { fallbackDominantColor(it) } ?: Color.Transparent
        }
        if (identity != null && fastDominant != Color.Transparent) {
            FullScreenDominantCache.put(identity, fastDominant)
        }
        artwork = FullScreenArtwork(image, fastDominant, bitmap, identity)
        if (bitmap != null && cachedDominant == null) {
            val refined = withContext(Dispatchers.Default) { adaptiveDominantColor(bitmap) }
            if (identity != null && refined != Color.Transparent) {
                FullScreenDominantCache.put(identity, refined)
            }
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
        val cachedDominant = identity?.let { FullScreenDominantCache.get(it) }
        val fast = cachedDominant ?: withContext(Dispatchers.Default) { fallbackDominantColor(bitmap) }
        if (identity != null && fast != Color.Transparent) {
            FullScreenDominantCache.put(identity, fast)
        }
        artwork = FullScreenArtwork(image, fast, bitmap, identity)
        if (cachedDominant == null) {
            val refined = withContext(Dispatchers.Default) { adaptiveDominantColor(bitmap) }
            if (identity != null && refined != Color.Transparent) {
                FullScreenDominantCache.put(identity, refined)
            }
            val current = artwork
            if (current != null && current.image === image) artwork = current.copy(dominant = refined)
        }
    }
    return artwork
}

@Composable
internal fun rememberCoilThumbnail(url: String?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(url, sizePx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, sizePx) {
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
        image = bitmap.asImageBitmap()
    }
    return image
}

/** A crossfade layer is path-scoped; only the normal player cover may persist across paths. */
internal fun fullscreenArtworkMemoryKey(artworkPath: String?, keepPrevious: Boolean): Any? =
    if (keepPrevious) FullscreenArtworkRetainedKey else artworkPath

private object FullscreenArtworkRetainedKey

internal object FullScreenDominantCache {
    private val cache = android.util.LruCache<String, Color>(128)

    fun get(identity: String): Color? = synchronized(cache) { cache.get(identity) }

    fun put(identity: String, color: Color) {
        if (color == Color.Transparent) return
        synchronized(cache) { cache.put(identity, color) }
    }

    fun getOrSampleQuick(identity: String?, bitmap: Bitmap?): Color? {
        if (identity != null) {
            get(identity)?.let { return it }
        }
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val sampled = sampleStridedDominantColor(bitmap)
        if (identity != null && sampled != Color.Transparent) {
            put(identity, sampled)
        }
        return sampled.takeIf { it != Color.Transparent }
    }
}

private fun sampleStridedDominantColor(bitmap: Bitmap): Color {
    val w = bitmap.width
    val h = bitmap.height
    val cols = 6
    val rows = 6
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0L
    for (row in 0 until rows) {
        val y = ((row + 0.5f) * h / rows).toInt().coerceIn(0, h - 1)
        for (col in 0 until cols) {
            val x = ((col + 0.5f) * w / cols).toInt().coerceIn(0, w - 1)
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) > 32) {
                rSum += android.graphics.Color.red(pixel)
                gSum += android.graphics.Color.green(pixel)
                bSum += android.graphics.Color.blue(pixel)
                count++
            }
        }
    }
    if (count == 0L) return Color.Transparent
    return Color(
        red = (rSum / count).toInt(),
        green = (gSum / count).toInt(),
        blue = (bSum / count).toInt(),
    )
}

internal fun adaptiveDominantColor(bitmap: Bitmap): Color {
    return runCatching { bitmap.extractAdaptivePlayerColors().background }.getOrDefault(fallbackDominantColor(bitmap))
}

private fun fallbackDominantColor(bitmap: Bitmap): Color {
    if (bitmap.isRecycled) return Color.Transparent
    val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
    val pixels = IntArray(24 * 24)
    try {
        sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
    } finally {
        if (sample !== bitmap && !sample.isRecycled) sample.recycle()
    }
    val opaque = pixels.filter { android.graphics.Color.alpha(it) > 32 }
    if (opaque.isEmpty()) return Color.Transparent
    return Color(
        opaque.sumOf { android.graphics.Color.red(it) } / opaque.size,
        opaque.sumOf { android.graphics.Color.green(it) } / opaque.size,
        opaque.sumOf { android.graphics.Color.blue(it) } / opaque.size,
    )
}
