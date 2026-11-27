package com.exodidio.tune.ui.components

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.exodidio.tune.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal val TuneSfSubsetOnlyCodePoints = setOf(0x101411, 0x10163F, 0x10173E, 0x101EEF)

private sealed interface AppleGlyphSpec {
    data class DrawableRes(val resId: Int, val visualScale: Float = 0.75f) : AppleGlyphSpec
    data class VectorKind(val kind: VectorSymbolKind) : AppleGlyphSpec
    data class SfCodePoint(val codePoint: Int) : AppleGlyphSpec
}

private enum class VectorSymbolKind {
    Star,
    Shuffle,
    Repeat,
    RepeatOne,
    MoreDots,
    Folder,
    Library,
    Heart,
    HeartPlus,
    HeartMinus,
}

private val AppleSymbolMappings: Map<String, AppleGlyphSpec> = mapOf(
    MaterialSymbols.Home to AppleGlyphSpec.DrawableRes(R.drawable.ic_apple_home_tab, visualScale = 0.74f),
    MaterialSymbols.Settings to AppleGlyphSpec.DrawableRes(R.drawable.ic_apple_brand_gear, visualScale = 0.75f),
    MaterialSymbols.Mic to AppleGlyphSpec.DrawableRes(R.drawable.ic_apple_mic, visualScale = 0.75f),
    MaterialSymbols.Add to AppleGlyphSpec.DrawableRes(R.drawable.ic_apple_plus, visualScale = 0.84f),
    MaterialSymbols.Star to AppleGlyphSpec.VectorKind(VectorSymbolKind.Star),
    MaterialSymbols.Shuffle to AppleGlyphSpec.VectorKind(VectorSymbolKind.Shuffle),
    MaterialSymbols.Repeat to AppleGlyphSpec.VectorKind(VectorSymbolKind.Repeat),
    MaterialSymbols.RepeatOne to AppleGlyphSpec.VectorKind(VectorSymbolKind.RepeatOne),
    MaterialSymbols.MoreVert to AppleGlyphSpec.VectorKind(VectorSymbolKind.MoreDots),
    MaterialSymbols.Folder to AppleGlyphSpec.VectorKind(VectorSymbolKind.Folder),
    MaterialSymbols.Library to AppleGlyphSpec.VectorKind(VectorSymbolKind.Library),
    MaterialSymbols.GraphicEq to AppleGlyphSpec.SfCodePoint(0x10066B),
    MaterialSymbols.BarChart to AppleGlyphSpec.SfCodePoint(0x100319),
    MaterialSymbols.LegendToggle to AppleGlyphSpec.SfCodePoint(0x10066B),
    MaterialSymbols.Search to AppleGlyphSpec.SfCodePoint(0x1002AB),
    MaterialSymbols.Schedule to AppleGlyphSpec.SfCodePoint(0x10042B),
    MaterialSymbols.PlayCircle to AppleGlyphSpec.SfCodePoint(0x100284),
    MaterialSymbols.QueueMusic to AppleGlyphSpec.SfCodePoint(0x10163F),
    MaterialSymbols.Sensors to AppleGlyphSpec.SfCodePoint(0x100319),
    MaterialSymbols.PlaylistAdd to AppleGlyphSpec.SfCodePoint(0x1002F8),
    MaterialSymbols.PlaylistRemove to AppleGlyphSpec.SfCodePoint(0x100211),
    MaterialSymbols.QueuePlayNext to AppleGlyphSpec.SfCodePoint(0x10163F),
    MaterialSymbols.AddToQueue to AppleGlyphSpec.SfCodePoint(0x1002F8),
    MaterialSymbols.RemoveFromQueue to AppleGlyphSpec.SfCodePoint(0x100211),
    MaterialSymbols.Tune to AppleGlyphSpec.SfCodePoint(0x10070A),
    MaterialSymbols.MaskedTransitions to AppleGlyphSpec.SfCodePoint(0x1001BF),
    MaterialSymbols.Gradient to AppleGlyphSpec.SfCodePoint(0x1001BF),
    MaterialSymbols.Subwoofer to AppleGlyphSpec.SfCodePoint(0x1002A7),
    MaterialSymbols.ChevronRight to AppleGlyphSpec.SfCodePoint(0x10018A),
    MaterialSymbols.ChevronLeft to AppleGlyphSpec.SfCodePoint(0x100189),
    MaterialSymbols.UnfoldMore to AppleGlyphSpec.SfCodePoint(0x10018F),
    MaterialSymbols.Check to AppleGlyphSpec.SfCodePoint(0x100185),
    MaterialSymbols.Close to AppleGlyphSpec.SfCodePoint(0x100184),
    MaterialSymbols.MusicNote to AppleGlyphSpec.SfCodePoint(0x10046A),
    MaterialSymbols.Bolt to AppleGlyphSpec.SfCodePoint(0x10066D),
    MaterialSymbols.Crown to AppleGlyphSpec.SfCodePoint(0x1001BF),
    MaterialSymbols.Menu to AppleGlyphSpec.SfCodePoint(0x1002F2),
    MaterialSymbols.ArrowUpward to AppleGlyphSpec.SfCodePoint(0x100077),
    MaterialSymbols.ArrowDownward to AppleGlyphSpec.SfCodePoint(0x100078),
    MaterialSymbols.SkipPrevious to AppleGlyphSpec.SfCodePoint(0x10028A),
    MaterialSymbols.PlayArrow to AppleGlyphSpec.SfCodePoint(0x100284),
    MaterialSymbols.Pause to AppleGlyphSpec.SfCodePoint(0x100286),
    MaterialSymbols.SkipNext to AppleGlyphSpec.SfCodePoint(0x10028C),
    MaterialSymbols.Reorder to AppleGlyphSpec.SfCodePoint(0x1002F2),
    MaterialSymbols.Chat to AppleGlyphSpec.SfCodePoint(0x100324),
    MaterialSymbols.Translate to AppleGlyphSpec.SfCodePoint(0x10173E),
    MaterialSymbols.GTranslate to AppleGlyphSpec.SfCodePoint(0x10173E),
    MaterialSymbols.Lyrics to AppleGlyphSpec.SfCodePoint(0x10032E),
    MaterialSymbols.Computer to AppleGlyphSpec.SfCodePoint(0x1008B9),
    MaterialSymbols.Cast to AppleGlyphSpec.SfCodePoint(0x1008B9),
    MaterialSymbols.Airplay to AppleGlyphSpec.SfCodePoint(0x1008B9),
    MaterialSymbols.VolumeDown to AppleGlyphSpec.SfCodePoint(0x1002A7),
    MaterialSymbols.VolumeUp to AppleGlyphSpec.SfCodePoint(0x1002A7),
    MaterialSymbols.People to AppleGlyphSpec.SfCodePoint(0x10026D),
    MaterialSymbols.Person to AppleGlyphSpec.SfCodePoint(0x10026D),
    MaterialSymbols.Album to AppleGlyphSpec.SfCodePoint(0x1007BF),
    MaterialSymbols.Label to AppleGlyphSpec.SfCodePoint(0x10173E),
    MaterialSymbols.Palette to AppleGlyphSpec.SfCodePoint(0x1001BF),
    MaterialSymbols.Refresh to AppleGlyphSpec.SfCodePoint(0x100148),
    MaterialSymbols.Info to AppleGlyphSpec.SfCodePoint(0x100174),
    MaterialSymbols.Power to AppleGlyphSpec.SfCodePoint(0x100184),
    MaterialSymbols.Sync to AppleGlyphSpec.SfCodePoint(0x100148),
    MaterialSymbols.Image to AppleGlyphSpec.SfCodePoint(0x1007BF),
    MaterialSymbols.Favorite to AppleGlyphSpec.VectorKind(VectorSymbolKind.Heart),
    MaterialSymbols.HeartPlus to AppleGlyphSpec.VectorKind(VectorSymbolKind.HeartPlus),
    MaterialSymbols.HeartMinus to AppleGlyphSpec.VectorKind(VectorSymbolKind.HeartMinus),
    MaterialSymbols.FilterList to AppleGlyphSpec.SfCodePoint(0x1002F2),
    MaterialSymbols.GridView to AppleGlyphSpec.SfCodePoint(0x1007BF),
    MaterialSymbols.ViewList to AppleGlyphSpec.SfCodePoint(0x1002F2),
    MaterialSymbols.StylusFountainPen to AppleGlyphSpec.SfCodePoint(0x10173E),
    MaterialSymbols.Edit to AppleGlyphSpec.SfCodePoint(0x10173E),
    MaterialSymbols.Delete to AppleGlyphSpec.SfCodePoint(0x100211),
    MaterialSymbols.FileExport to AppleGlyphSpec.SfCodePoint(0x100202),
)

internal fun appleSymbolSupports(symbol: String): Boolean = symbol in AppleSymbolMappings

internal fun appleSfCodePointFor(symbol: String): Int? =
    (AppleSymbolMappings[symbol] as? AppleGlyphSpec.SfCodePoint)?.codePoint

internal object TuneSfTypefaceCache {
    private val lock = Any()
    private val cache = HashMap<Int, Typeface>()

    fun obtain(
        context: Context,
        weight: Int,
        subsetOnly: Boolean = false,
    ): Typeface? {
        val key = weight or if (subsetOnly) 0x10000 else 0
        synchronized(lock) { cache[key]?.let { return it } }
        val font = ResourcesCompat.getFont(
            context,
            if (subsetOnly) R.font.sf_pro_subset else R.font.sf_pro_vf,
        ) ?: return null
        val typeface = Typeface.create(font, weight)
        synchronized(lock) { cache.putIfAbsent(key, typeface) }
        return typeface
    }
}

@Composable
internal fun AppleSymbol(
    symbol: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color,
    filled: Boolean = false,
) {
    val semanticsModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }
    when (val spec = AppleSymbolMappings[symbol]) {
        is AppleGlyphSpec.DrawableRes -> {
            Box(modifier = semanticsModifier.size(size), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(spec.resId),
                    contentDescription = null,
                    modifier = Modifier
                        .requiredSize(size * spec.visualScale)
                        .alpha(tint.alpha),
                    tint = tint.copy(alpha = 1f),
                )
            }
        }
        is AppleGlyphSpec.VectorKind -> when (spec.kind) {
            VectorSymbolKind.Star -> AppleStarVectorIcon(
                modifier = semanticsModifier,
                size = size,
                color = tint,
                filled = filled,
            )
            VectorSymbolKind.Shuffle,
            VectorSymbolKind.Repeat,
            VectorSymbolKind.RepeatOne -> ApplePlaybackModeVectorIcon(
                kind = spec.kind,
                modifier = semanticsModifier,
                size = size,
                color = tint,
            )
            VectorSymbolKind.MoreDots -> AppleMoreDotsVectorIcon(
                modifier = semanticsModifier,
                size = size,
                color = tint,
            )
            VectorSymbolKind.Folder -> AppleFolderVectorIcon(
                modifier = semanticsModifier,
                size = size,
                color = tint,
                filled = filled,
            )
            VectorSymbolKind.Library -> AppleLibraryVectorIcon(
                modifier = semanticsModifier,
                size = size,
                color = tint,
                filled = filled,
            )
            VectorSymbolKind.Heart -> AppleHeartVectorIcon(
                modifier = semanticsModifier,
                size = size,
                color = tint,
                filled = filled,
            )
            VectorSymbolKind.HeartPlus -> AppleHeartBadgeVectorIcon(
                modifier = semanticsModifier,
                size = size,
                color = tint,
                isPlus = true,
            )
            VectorSymbolKind.HeartMinus -> AppleHeartBadgeVectorIcon(
                modifier = semanticsModifier,
                size = size,
                color = tint,
                isPlus = false,
            )
        }
        is AppleGlyphSpec.SfCodePoint -> AppleSfGlyphIcon(
            codePoint = spec.codePoint,
            modifier = semanticsModifier,
            size = size,
            color = tint,
            weight = if (filled) FontWeight.Medium else FontWeight.Normal,
        )
        null -> AppleSfGlyphIcon(
            codePoint = 0x10005C,
            modifier = semanticsModifier,
            size = size,
            color = tint,
            weight = if (filled) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

internal data class PositionedAppleSfGlyph(
    val glyph: String,
    val x: Float,
    val baseline: Float,
)

internal fun computeAppleSfGlyphTextSizePx(
    baseTextSizePx: Float,
    boundsWidth: Float,
    boundsHeight: Float,
    iconSizePx: Float,
): Float {
    val maxExtent = iconSizePx * 0.78f
    return if (boundsWidth > 0f && boundsHeight > 0f && (boundsWidth > maxExtent || boundsHeight > maxExtent)) {
        baseTextSizePx * min(maxExtent / boundsWidth, maxExtent / boundsHeight)
    } else {
        baseTextSizePx
    }
}

internal fun prepareAppleSfGlyph(
    paint: Paint,
    codePoint: Int,
    iconSizePx: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): PositionedAppleSfGlyph {
    val baseTextSizePx = iconSizePx * 0.75f
    paint.textSize = baseTextSizePx
    paint.textAlign = Paint.Align.LEFT
    val glyph = String(Character.toChars(codePoint))
    val bounds = Rect()
    paint.getTextBounds(glyph, 0, glyph.length, bounds)
    val adjustedSizePx = computeAppleSfGlyphTextSizePx(
        baseTextSizePx = baseTextSizePx,
        boundsWidth = bounds.width().toFloat(),
        boundsHeight = bounds.height().toFloat(),
        iconSizePx = iconSizePx,
    )
    if (adjustedSizePx != baseTextSizePx) {
        paint.textSize = adjustedSizePx
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
    }
    val x = canvasWidth / 2f - (bounds.left + bounds.right) / 2f
    val baseline = canvasHeight / 2f - (bounds.top + bounds.bottom) / 2f
    return PositionedAppleSfGlyph(glyph = glyph, x = x, baseline = baseline)
}

@Composable
private fun AppleSfGlyphIcon(
    codePoint: Int,
    modifier: Modifier,
    size: Dp,
    color: Color,
    weight: FontWeight,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val subsetOnly = codePoint in TuneSfSubsetOnlyCodePoints
    val typeface = remember(context, weight, subsetOnly) {
        TuneSfTypefaceCache.obtain(context, weight.weight, subsetOnly)
    }
    if (typeface == null) return
    val iconSizePx = with(density) { size.toPx() }
    Canvas(modifier = modifier.size(size)) {
        drawIntoCanvas { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                this.color = color.toArgb()
                this.typeface = typeface
            }
            val positioned = prepareAppleSfGlyph(
                paint = paint,
                codePoint = codePoint,
                iconSizePx = iconSizePx,
                canvasWidth = this.size.width,
                canvasHeight = this.size.height,
            )
            canvas.nativeCanvas.drawText(positioned.glyph, positioned.x, positioned.baseline, paint)
        }
    }
}

@Composable
private fun AppleStarVectorIcon(
    modifier: Modifier,
    size: Dp,
    color: Color,
    filled: Boolean,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .requiredSize(size * 0.76f)
                .alpha(color.alpha),
        ) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val outer = min(this.size.width, this.size.height) / 2f * 0.92f
            val innerRatio = 0.40f
            val strokeW = outer * 0.16f
            val drawOuter = if (filled) outer else outer - strokeW / 2f
            val inner = drawOuter * innerRatio
            val path = Path().apply {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) drawOuter else inner
                    val angle = (-Math.PI / 2.0 + i * Math.PI / 5.0).toFloat()
                    val x = cx + r * cos(angle)
                    val y = cy + r * sin(angle)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(
                path = path,
                color = color.copy(alpha = 1f),
                style = if (filled) {
                    Fill
                } else {
                    Stroke(
                        width = strokeW,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                },
            )
        }
    }
}

@Composable
private fun AppleMoreDotsVectorIcon(
    modifier: Modifier,
    size: Dp,
    color: Color,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .alpha(color.alpha),
    ) {
        val m = min(this.size.width, this.size.height)
        val dotR = m * 0.085f
        val gap = m * 0.25f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.copy(alpha = 1f).toArgb()
        }
        drawIntoCanvas { canvas ->
            for (dx in listOf(-gap, 0f, gap)) {
                canvas.nativeCanvas.drawCircle(cx + dx, cy, dotR, paint)
            }
        }
    }
}

private object AppleGlyphPaths {
    private val SHUFFLE_PATHS = listOf(
        "M77.5 13.3Q77.5 12.0 78.2 11.3Q78.9 10.6 80.2 10.6Q80.9 10.6 81.4 10.8Q82.0 11.0 82.5 11.4L99.0 25.0Q100.0 25.9 100.0 27.1Q100.0 28.3 99.0 29.2L82.5 42.8Q82.0 43.2 81.4 43.4Q80.9 43.6 80.2 43.6Q78.9 43.6 78.2 42.9Q77.5 42.2 77.5 40.9Z",
        "M0.0 72.3Q0.0 70.7 1.1 69.7Q2.2 68.7 4.0 68.7L14.1 68.7Q17.4 68.7 20.0 67.3Q22.5 66.0 25.4 62.7L51.7 31.9Q55.6 27.3 59.3 25.5Q62.9 23.8 68.4 23.8L83.9 23.8Q85.4 23.8 86.4 24.8Q87.5 25.9 87.5 27.4Q87.5 28.9 86.4 30.0Q85.4 31.0 83.9 31.0L68.6 31.0Q66.2 31.0 64.3 31.6Q62.3 32.2 60.6 33.5Q58.8 34.8 56.9 37.0L30.5 67.8Q26.6 72.3 23.0 74.1Q19.3 75.9 13.9 75.9L4.0 75.9Q2.2 75.9 1.1 74.9Q0.0 73.9 0.0 72.3Z",
        "M77.5 86.7L77.5 59.1Q77.5 57.8 78.2 57.1Q78.9 56.4 80.2 56.4Q80.9 56.4 81.4 56.6Q82.0 56.8 82.5 57.2L99.0 70.8Q100.0 71.7 100.0 72.9Q100.0 74.1 99.0 75.0L82.5 88.6Q82.0 89.0 81.4 89.2Q80.9 89.4 80.2 89.4Q78.9 89.4 78.2 88.7Q77.5 88.0 77.5 86.7Z",
        "M0.0 27.7Q0.0 26.1 1.1 25.1Q2.2 24.1 4.0 24.1L13.9 24.1Q19.3 24.1 23.0 25.9Q26.6 27.7 30.5 32.2L56.9 63.0Q58.8 65.2 60.6 66.5Q62.3 67.8 64.3 68.4Q66.2 69.0 68.6 69.0L83.9 69.0Q85.4 69.0 86.4 70.0Q87.5 71.1 87.5 72.6Q87.5 74.1 86.4 75.2Q85.4 76.2 83.9 76.2L68.4 76.2Q62.9 76.2 59.3 74.5Q55.6 72.7 51.7 68.1L25.4 37.3Q22.5 34.0 20.0 32.7Q17.4 31.3 14.1 31.3L4.0 31.3Q2.2 31.3 1.1 30.3Q0.0 29.3 0.0 27.7Z",
    )

    private val REPEAT_PATHS = listOf(
        "M58.5 11.3Q58.5 9.8 59.3 9.1Q60.1 8.3 61.5 8.3Q62.1 8.3 62.8 8.5Q63.4 8.7 63.9 9.2L81.6 23.9Q82.8 24.9 82.8 26.1Q82.8 27.4 81.6 28.3L63.9 43.0Q63.4 43.4 62.8 43.7Q62.1 43.9 61.5 43.9Q60.1 43.9 59.3 43.1Q58.5 42.3 58.5 40.9Z",
        "M3.9 51.7Q2.3 51.7 1.1 50.5Q0.0 49.4 0.0 47.7L0.0 42.7Q0.0 36.3 2.8 31.7Q5.5 27.2 10.6 24.6Q15.7 22.1 22.7 22.1L65.4 22.1Q67.0 22.1 68.2 23.3Q69.3 24.4 69.3 26.0Q69.3 27.6 68.2 28.8Q67.0 29.9 65.4 29.9L21.9 29.9Q15.6 29.9 11.7 33.6Q7.9 37.2 7.9 43.2L7.9 47.7Q7.9 49.4 6.8 50.5Q5.6 51.7 3.9 51.7Z",
        "M41.5 88.7Q41.5 90.1 40.7 90.9Q40.0 91.7 38.5 91.7Q37.9 91.7 37.2 91.4Q36.6 91.2 36.1 90.8L18.4 76.1Q17.2 75.1 17.2 73.9Q17.2 72.6 18.4 71.6L36.1 57.0Q36.6 56.5 37.2 56.3Q37.9 56.1 38.5 56.1Q40.0 56.1 40.7 56.9Q41.5 57.6 41.5 59.1Z",
        "M98.9 49.5Q97.8 48.3 100.0 52.3Q100.0 50.6 100.0 57.3Q100.0 57.3 97.2 68.2Q100.0 63.6 89.4 75.4Q94.5 72.8 77.3 77.9Q84.3 77.9 34.6 77.9Q34.6 77.9 31.8 76.7Q33.0 77.9 30.7 73.9Q30.7 75.6 31.8 71.2Q30.7 72.3 34.6 70.0Q33.0 70.0 78.1 70.0Q78.1 70.0 88.3 66.4Q84.4 70.0 92.1 56.8Q92.1 62.8 92.1 52.3Q92.1 52.3 93.3 49.5Q92.1 50.6 96.1 48.3Q94.4 48.3 96.1 48.3Z",
    )

    private val REPEAT_ONE_PATHS = listOf(
        "M48.3 12.0Q48.3 10.6 49.1 9.8Q49.9 9.1 51.3 9.1Q52.0 9.1 52.6 9.3Q53.2 9.5 53.8 9.9L71.4 24.6Q72.6 25.6 72.6 26.9Q72.5 28.1 71.4 29.1L53.8 43.7Q53.2 44.1 52.6 44.3Q52.0 44.6 51.3 44.6Q49.9 44.6 49.1 43.8Q48.3 43.0 48.3 41.6Z",
        "M3.9 52.3Q2.3 52.3 1.1 51.2Q0.0 50.0 0.0 48.4L0.0 43.4Q0.0 37.1 2.8 32.5Q5.5 27.9 10.6 25.4Q15.7 22.9 22.6 22.9L55.2 22.9Q56.8 22.9 58.0 24.0Q59.1 25.2 59.1 26.8Q59.1 28.4 58.0 29.5Q56.8 30.6 55.2 30.6L21.9 30.6Q15.5 30.6 11.7 34.3Q7.9 37.9 7.9 43.9L7.9 48.4Q7.9 50.0 6.8 51.2Q5.6 52.3 3.9 52.3Z",
        "M41.4 89.3Q41.4 90.7 40.6 91.5Q39.9 92.3 38.4 92.3Q37.8 92.3 37.1 92.0Q36.5 91.8 36.0 91.4L18.3 76.7Q17.2 75.8 17.2 74.5Q17.2 73.2 18.3 72.3L36.0 57.6Q36.5 57.2 37.1 57.0Q37.8 56.8 38.4 56.8Q39.9 56.8 40.6 57.5Q41.4 58.3 41.4 59.7Z",
        "M98.6 50.2Q97.5 49.0 99.8 53.0Q99.8 51.3 99.8 58.0Q99.8 58.0 97.0 68.9Q99.8 64.3 89.2 76.0Q94.2 73.5 77.1 78.5Q84.1 78.5 34.5 78.5Q34.5 78.5 31.8 77.3Q32.9 78.5 30.6 74.5Q30.6 76.2 31.8 71.8Q30.6 73.0 34.5 70.7Q32.9 70.7 77.9 70.7Q77.9 70.7 88.1 67.0Q84.2 70.7 91.9 57.4Q91.9 63.4 91.9 53.0Q91.9 53.0 93.0 50.2Q91.9 51.3 95.8 49.0Q94.2 49.0 95.8 49.0Z",
        "M95.9 38.9Q93.9 38.9 92.8 37.8Q91.7 36.7 91.7 34.7L91.7 15.0L91.2 15.0L86.2 19.0Q85.3 19.7 84.3 19.7Q83.1 19.7 82.3 19.0Q81.6 18.3 81.6 17.2Q81.6 16.5 81.8 15.9Q82.1 15.4 82.8 14.8L89.1 10.0Q90.6 8.9 91.8 8.3Q93.1 7.7 95.0 7.7Q97.2 7.7 98.6 9.1Q100.0 10.5 100.0 12.9L100.0 34.7Q100.0 36.7 98.9 37.8Q97.8 38.9 95.9 38.9Z",
    )

    fun paths(kind: VectorSymbolKind): List<String> = when (kind) {
        VectorSymbolKind.Shuffle -> SHUFFLE_PATHS
        VectorSymbolKind.RepeatOne -> REPEAT_ONE_PATHS
        else -> REPEAT_PATHS
    }
}

private fun buildNormalizedSfGlyphPath(data: String, width: Float, height: Float): Path {
    val sx = width / 100f
    val sy = height / 100f
    var i = 0
    var cmd = ' '
    val n = data.length
    val path = Path()

    fun skipSep() {
        while (i < n && (data[i] == ' ' || data[i] == ',')) i++
    }

    fun nextFloat(): Float {
        skipSep()
        val start = i
        if (i < n && (data[i] == '-' || data[i] == '+')) i++
        while (i < n && (data[i].isDigit() || data[i] == '.')) i++
        return data.substring(start, i).toFloat()
    }

    while (i < n) {
        skipSep()
        if (i >= n) break
        if (data[i].isLetter()) {
            cmd = data[i]
            i++
            if (cmd == 'Z') {
                path.close()
                continue
            }
        }
        when (cmd) {
            'M' -> {
                val x = nextFloat()
                val y = nextFloat()
                path.moveTo(x * sx, y * sy)
            }
            'L' -> {
                val x = nextFloat()
                val y = nextFloat()
                path.lineTo(x * sx, y * sy)
            }
            'Q' -> {
                val cx = nextFloat()
                val cy = nextFloat()
                val x = nextFloat()
                val y = nextFloat()
                path.quadraticTo(cx * sx, cy * sy, x * sx, y * sy)
            }
        }
    }
    return path
}

@Composable
private fun ApplePlaybackModeVectorIcon(
    kind: VectorSymbolKind,
    modifier: Modifier,
    size: Dp,
    color: Color,
) {
    val inkEm = when (kind) {
        VectorSymbolKind.Shuffle -> 2298f / 2048f
        VectorSymbolKind.RepeatOne -> 2139f / 2048f
        else -> 2134f / 2048f
    }
    val renderSize = size * (0.76f * inkEm / (2298f / 2048f))
    val density = LocalDensity.current
    val glyphPath = remember(kind, renderSize, density.density) {
        val px = with(density) { renderSize.toPx() }
        buildNormalizedSfGlyphPath(AppleGlyphPaths.paths(kind).joinToString(separator = ""), px, px)
    }
    Box(
        modifier = modifier
            .size(size)
            .alpha(color.alpha),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.requiredSize(renderSize)) {
            drawPath(glyphPath, color.copy(alpha = 1f))
        }
    }
}

@Composable
private fun AppleFolderVectorIcon(
    modifier: Modifier,
    size: Dp,
    color: Color,
    filled: Boolean,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .requiredSize(size * 0.76f)
                .alpha(color.alpha),
        ) {
            val w = this.size.width
            val h = this.size.height
            val strokeW = min(w, h) * 0.085f
            val padX = w * 0.08f
            val topY = h * 0.19f
            val bottomY = h * 0.83f
            val rightX = w - padX
            val tabEndX = w * 0.44f
            val tabKneeX = w * 0.52f
            val shoulderY = h * 0.30f
            val corner = min(w, h) * 0.10f
            val path = Path().apply {
                moveTo(padX + corner, topY)
                lineTo(tabEndX, topY)
                lineTo(tabKneeX, shoulderY)
                lineTo(rightX - corner, shoulderY)
                quadraticTo(rightX, shoulderY, rightX, shoulderY + corner)
                lineTo(rightX, bottomY - corner)
                quadraticTo(rightX, bottomY, rightX - corner, bottomY)
                lineTo(padX + corner, bottomY)
                quadraticTo(padX, bottomY, padX, bottomY - corner)
                lineTo(padX, topY + corner)
                quadraticTo(padX, topY, padX + corner, topY)
                close()
            }
            drawPath(
                path = path,
                color = color.copy(alpha = 1f),
                style = if (filled) {
                    Fill
                } else {
                    Stroke(
                        width = strokeW,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                },
            )
        }
    }
}

@Composable
private fun AppleLibraryVectorIcon(
    modifier: Modifier,
    size: Dp,
    color: Color,
    filled: Boolean,
) {
    val solidColor = color.copy(alpha = 1f)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .requiredSize(size * 0.78f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .alpha(color.alpha),
        ) {
            val w = this.size.width
            val h = this.size.height
            val strokeW = min(w, h) * 0.085f
            // 顶部叠层短线
            drawLine(
                color = solidColor,
                start = Offset(w * 0.25f, h * 0.12f),
                end = Offset(w * 0.75f, h * 0.12f),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
            // 主卡片圆角矩形
            val cardLeft = w * 0.14f
            val cardTop = h * 0.24f
            val cardWidth = w * 0.72f
            val cardHeight = h * 0.66f
            val corner = min(w, h) * 0.16f
            drawRoundRect(
                color = solidColor,
                topLeft = Offset(cardLeft, cardTop),
                size = Size(cardWidth, cardHeight),
                cornerRadius = CornerRadius(corner, corner),
                style = if (filled) Fill else Stroke(width = strokeW),
            )
            // 音符图形：未加入为实心音符+线框卡片；已加入为实心卡片+镂空音符，对比一目了然。
            val noteColor = if (filled) Color.Black else solidColor
            val noteBlend = if (filled) BlendMode.DstOut else BlendMode.SrcOver
            val stemW = w * 0.068f
            val leftNoteCenter = Offset(w * 0.37f, h * 0.71f)
            val rightNoteCenter = Offset(w * 0.62f, h * 0.66f)
            val noteHeadW = w * 0.14f
            val noteHeadH = h * 0.105f
            drawOval(
                color = noteColor,
                topLeft = Offset(leftNoteCenter.x - noteHeadW / 2f, leftNoteCenter.y - noteHeadH / 2f),
                size = Size(noteHeadW, noteHeadH),
                blendMode = noteBlend,
            )
            drawOval(
                color = noteColor,
                topLeft = Offset(rightNoteCenter.x - noteHeadW / 2f, rightNoteCenter.y - noteHeadH / 2f),
                size = Size(noteHeadW, noteHeadH),
                blendMode = noteBlend,
            )
            val leftStemX = leftNoteCenter.x + noteHeadW * 0.32f
            val rightStemX = rightNoteCenter.x + noteHeadW * 0.32f
            val leftTopY = h * 0.415f
            val rightTopY = h * 0.375f
            drawLine(
                color = noteColor,
                start = Offset(leftStemX, leftNoteCenter.y),
                end = Offset(leftStemX, leftTopY),
                strokeWidth = stemW,
                cap = StrokeCap.Round,
                blendMode = noteBlend,
            )
            drawLine(
                color = noteColor,
                start = Offset(rightStemX, rightNoteCenter.y),
                end = Offset(rightStemX, rightTopY),
                strokeWidth = stemW,
                cap = StrokeCap.Round,
                blendMode = noteBlend,
            )
            drawLine(
                color = noteColor,
                start = Offset(leftStemX, leftTopY),
                end = Offset(rightStemX, rightTopY),
                strokeWidth = h * 0.085f,
                cap = StrokeCap.Round,
                blendMode = noteBlend,
            )
        }
    }
}

private fun buildAppleHeartPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.50f, h * 0.27f)
    cubicTo(w * 0.42f, h * 0.13f, w * 0.19f, h * 0.12f, w * 0.11f, h * 0.27f)
    cubicTo(w * 0.03f, h * 0.42f, w * 0.14f, h * 0.63f, w * 0.50f, h * 0.88f)
    cubicTo(w * 0.86f, h * 0.63f, w * 0.97f, h * 0.42f, w * 0.89f, h * 0.27f)
    cubicTo(w * 0.81f, h * 0.12f, w * 0.58f, h * 0.13f, w * 0.50f, h * 0.27f)
    close()
}

@Composable
private fun AppleHeartVectorIcon(
    modifier: Modifier,
    size: Dp,
    color: Color,
    filled: Boolean,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .requiredSize(size * 0.76f)
                .alpha(color.alpha),
        ) {
            val w = this.size.width
            val h = this.size.height
            val strokeW = min(w, h) * 0.095f
            drawPath(
                path = buildAppleHeartPath(w, h),
                color = color.copy(alpha = 1f),
                style = if (filled) {
                    Fill
                } else {
                    Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                },
            )
        }
    }
}

@Composable
private fun AppleHeartBadgeVectorIcon(
    modifier: Modifier,
    size: Dp,
    color: Color,
    isPlus: Boolean,
) {
    val solidColor = color.copy(alpha = 1f)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .requiredSize(size * 0.76f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .alpha(color.alpha),
        ) {
            val w = this.size.width
            val h = this.size.height
            val strokeW = min(w, h) * 0.095f
            drawPath(
                path = buildAppleHeartPath(w, h),
                color = solidColor,
                style = if (isPlus) {
                    Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                } else {
                    Fill
                },
            )
            val badgeCenter = Offset(w * 0.77f, h * 0.74f)
            val badgeRadius = min(w, h) * 0.22f
            drawCircle(
                color = Color.Black,
                radius = badgeRadius,
                center = badgeCenter,
                blendMode = BlendMode.DstOut,
            )
            val arm = badgeRadius * 0.62f
            val badgeStroke = min(w, h) * 0.085f
            drawLine(
                color = solidColor,
                start = Offset(badgeCenter.x - arm, badgeCenter.y),
                end = Offset(badgeCenter.x + arm, badgeCenter.y),
                strokeWidth = badgeStroke,
                cap = StrokeCap.Round,
            )
            if (isPlus) {
                drawLine(
                    color = solidColor,
                    start = Offset(badgeCenter.x, badgeCenter.y - arm),
                    end = Offset(badgeCenter.x, badgeCenter.y + arm),
                    strokeWidth = badgeStroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
