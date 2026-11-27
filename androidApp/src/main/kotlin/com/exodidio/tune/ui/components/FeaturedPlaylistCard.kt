package com.exodidio.tune.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apple「专属精选推荐」卡片：上方正方形封面，下方信息区使用封面**四边平均色**。
 * 信息区会按明暗自动压暗/提亮，保证黑白文字对比度。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeaturedPlaylistCard(
    title: String,
    subtitle: String,
    artworkPath: String?,
    fallbackSymbol: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val bitmap = rememberArtworkThumbnail(artworkPath, targetPx = 400)
    val bitmapAlpha = rememberArtworkFadeAlpha(artworkPath, bitmap != null)
    var infoColor by remember(artworkPath) { mutableStateOf<Color?>(null) }
    LaunchedEffect(bitmap) {
        infoColor = bitmap?.let { image -> withContext(Dispatchers.Default) { artworkInfoBandColor(image) } }
    }
    val targetOnInfo = infoColor?.let { if (it.luminance() < 0.5f) Color.White else Color.Black } ?: colors.textMain
    val onInfo by animateColorAsState(
        targetValue = targetOnInfo,
        animationSpec = if (reduceMotion) snap() else tween(220, easing = FastOutSlowInEasing),
        label = "featured-card-on-info",
    )
    val interactive = onClick != null || onLongClick != null
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(TuneRadii.LargeArtwork)
    Box(
        modifier = modifier
            .then(if (interactive) Modifier.pressScale(interactionSource, pressedScale = 0.975f) else Modifier)
            .clip(shape)
            .artworkBorder(colors, shape)
            .combinedClickable(
                enabled = interactive,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            ),
    ) {
        if (bitmap != null) {
            Box(Modifier.matchParentSize().alpha(bitmapAlpha)) {
                // 封面和拉伸区画在同一张画布里，先保证颜色与纹理连续。
                Canvas(modifier = Modifier.matchParentSize()) { drawCardArt(bitmap) }
                // 再对整张画布按高度做递进模糊；文字在模糊层之上，不会被模糊。
                BlurredArtOverlay(bitmap, Modifier.matchParentSize())
            }
        } else {
            Box(Modifier.matchParentSize().background(colors.card))
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap == null) {
                    MaterialSymbol(
                        symbol = fallbackSymbol,
                        contentDescription = null,
                        size = 44.dp,
                        tint = colors.textMuted,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            ) {
                // 类型在上、介绍在下，两行都是小字；介绍最多两行后截断。
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = if (bitmap != null) onInfo else colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = if (bitmap != null) onInfo.copy(alpha = 0.72f) else colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 在整张卡片画布上绘制封面 + 底部拉伸区；两者使用同一张画布，交界处没有独立裁剪。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCardArt(bitmap: ImageBitmap) {
    val width = size.width
    val cover = width
    val side = kotlin.math.min(bitmap.width, bitmap.height)
    val srcX = (bitmap.width - side) / 2
    val srcY = (bitmap.height - side) / 2
    drawImage(
        image = bitmap,
        srcOffset = androidx.compose.ui.unit.IntOffset(srcX, srcY),
        srcSize = androidx.compose.ui.unit.IntSize(side, side),
        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
        dstSize = androidx.compose.ui.unit.IntSize(width.toInt(), cover.toInt()),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
    )
    val infoHeight = (size.height - cover).toInt().coerceAtLeast(1)
    drawImage(
        image = bitmap,
        srcOffset = androidx.compose.ui.unit.IntOffset(srcX, srcY + side - 1),
        srcSize = androidx.compose.ui.unit.IntSize(side, 1),
        dstOffset = androidx.compose.ui.unit.IntOffset(0, cover.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(width.toInt(), infoHeight),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
    )
}

/** 整张画布的递进模糊：越往下越糊，跨过封面/信息区边界连续变化。 */
@Composable
private fun BlurredArtOverlay(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    // 数值是“封面高度”的比例：封面底部 15% 开始起糊，往下（含整个信息区）保持模糊。
    val layers = listOf(
        8.dp to 0.85f,
        20.dp to 0.88f,
        36.dp to 0.91f,
        56.dp to 0.94f,
    )
    layers.forEach { (radius, startOfCover) ->
        Canvas(
            modifier = modifier
                .blur(radius)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawCardArt(bitmap)
            val cover = size.width
            val start = (cover * startOfCover) / size.height
            val end = cover / size.height
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    start.coerceIn(0.001f, 0.997f) to Color.Transparent,
                    end.coerceIn(0.002f, 1f) to Color.Black,
                    1f to Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** 取居中裁切正方形的最底一行：信息区就是由这一行拉伸出来的，用它决定文字黑/白。 */
private fun artworkInfoBandColor(bitmap: ImageBitmap): Color? {
    val source = bitmap.asAndroidBitmap()
    if (source.isRecycled) return null
    val side = kotlin.math.min(source.width, source.height)
    if (side <= 0) return null
    val left = (source.width - side) / 2
    val top = (source.height - side) / 2
    val size = 48
    val sample = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size)
    try {
        android.graphics.Canvas(sample).drawBitmap(
            source,
            android.graphics.Rect(left, top, left + side, top + side),
            android.graphics.Rect(0, 0, size, size),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        sample.getPixels(pixels, 0, size, 0, 0, size, size)
    } finally {
        if (!sample.isRecycled) sample.recycle()
    }
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    fun add(index: Int) {
        val color = pixels[index]
        if (android.graphics.Color.alpha(color) <= 32) return
        r += android.graphics.Color.red(color)
        g += android.graphics.Color.green(color)
        b += android.graphics.Color.blue(color)
        count++
    }
    val lastRow = size - 1
    for (x in 0 until size) add(lastRow * size + x)
    if (count == 0L) return null
    return Color((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
}
