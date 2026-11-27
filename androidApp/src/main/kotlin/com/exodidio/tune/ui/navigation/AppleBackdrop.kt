package com.exodidio.tune.ui.navigation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 苹果式正在播放背景：仿 kawarp 的管线——
 * 过饱和封面的重度模糊纹理 + 低频 simplex 噪声域扭曲（中心权重）+ 暗角/saturation/dither。
 * 着色器需要 RuntimeShader（API 33+），低版本回退为纯色。
 */
internal val AppleBackdropMinSdk: Int = Build.VERSION_CODES.TIRAMISU

private data class AppleTextureKey(
    val identityHash: Int,
    val generationId: Int,
    val width: Int,
    val height: Int,
    val blurred: Boolean,
    val blurPasses: Int,
)

private val appleTextureCache = LruCache<AppleTextureKey, Bitmap>(6)

@Composable
internal fun ApplePlayerBackdrop(
    artwork: FullScreenArtwork?,
    fallback: Color,
    modifier: Modifier = Modifier,
    blurred: Boolean = false,
    settings: com.exodidio.tune.player.AppleBackdropSettings = com.exodidio.tune.player.AppleBackdropSettings.Default,
    animated: Boolean = true,
) {
    val source = artwork?.sourceBitmap?.takeIf { !it.isRecycled }
    val clampedPasses = if (blurred) settings.blurPasses.coerceIn(0, 20) else 0
    val cacheKey = source?.let {
        AppleTextureKey(
            identityHash = System.identityHashCode(it),
            generationId = it.generationId,
            width = it.width,
            height = it.height,
            blurred = blurred,
            blurPasses = clampedPasses,
        )
    }
    val cachedBitmap = remember(cacheKey) {
        cacheKey?.let { appleTextureCache.get(it)?.takeIf { bmp -> !bmp.isRecycled } }
    }
    var asyncBitmap by remember(source, blurred) { mutableStateOf<Bitmap?>(cachedBitmap) }
    if (cachedBitmap != null && asyncBitmap !== cachedBitmap) {
        asyncBitmap = cachedBitmap
    }
    LaunchedEffect(cacheKey) {
        val src = source ?: run {
            asyncBitmap = null
            return@LaunchedEffect
        }
        val key = cacheKey ?: return@LaunchedEffect
        val existing = appleTextureCache.get(key)?.takeIf { !it.isRecycled }
        if (existing != null) {
            asyncBitmap = existing
            return@LaunchedEffect
        }
        val prepared = withContext(Dispatchers.Default) {
            if (src.isRecycled) null
            else runCatching { prepareAppleTexture(src, blurred, clampedPasses) }.getOrNull()
        }
        if (isActive && prepared != null && !prepared.isRecycled) {
            appleTextureCache.put(key, prepared)
            asyncBitmap = prepared
        }
    }
    val bitmap = (cachedBitmap ?: asyncBitmap)?.takeIf { !it.isRecycled }
    if (Build.VERSION.SDK_INT < AppleBackdropMinSdk || bitmap == null) {
        Box(modifier.background(fallback))
        return
    }

    val bitmapShader = remember(bitmap, blurred) {
        // 模糊版的 128px 纹理已经做过无缝化，允许 REPEAT 环绕采样。
        val tile = if (blurred) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
        BitmapShader(bitmap, tile, tile).apply {
            // 低分辨率纹理必须双线性放大；最近邻会把纹素放大成一个个色块。
            filterMode = if (blurred) BitmapShader.FILTER_MODE_LINEAR else BitmapShader.FILTER_MODE_NEAREST
        }
    }
    val runtimeShader = remember(blurred) {
        runCatching { RuntimeShader(if (blurred) AppleWarpShader else PixelWarpShader) }.getOrNull()
    }
    if (runtimeShader == null) {
        Box(modifier.background(fallback))
        return
    }
    val paint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
    }
    val matrix = remember { android.graphics.Matrix() }
    LaunchedEffect(runtimeShader, bitmapShader, blurred, settings) {
        runtimeShader.setInputShader("content", bitmapShader)
        if (blurred) {
            runtimeShader.setFloatUniform("twistAmount", settings.twist)
            runtimeShader.setFloatUniform("flowAmount", settings.flow)
            runtimeShader.setFloatUniform("saturation", settings.saturation)
            runtimeShader.setFloatUniform("brightness", settings.brightness)
            runtimeShader.setFloatUniform("speed", settings.speed)
            runtimeShader.setFloatUniform("vignetteStrength", settings.vignette)
            runtimeShader.setFloatUniform("detailMix", settings.detail)
            val harmony = artwork?.dominant
            if (harmony != null) {
                runtimeShader.setFloatUniform("harmonyColor", harmony.red, harmony.green, harmony.blue)
                runtimeShader.setFloatUniform("harmonyAmount", settings.harmony)
            }
        } else {
            runtimeShader.setFloatUniform("noiseScale", 2.4f)
            runtimeShader.setFloatUniform("warpAmount", 0.5f)
            runtimeShader.setFloatUniform("layerMix", 0.0f)
        }
    }

    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            time = (now - start) / 1_000_000_000f
        }
    }

    Box(
        modifier.drawWithContent {
            if (size.width <= 0f || size.height <= 0f) return@drawWithContent
            runtimeShader.setFloatUniform("resolution", size.width, size.height)
            runtimeShader.setFloatUniform("time", time)
            if (blurred) {
                // Kawarp 的 128px 模糊纹理是拉伸铺满整个画布的。
                matrix.setScale(size.width / bitmap.width, size.height / bitmap.height)
            } else {
                // 像素版保持“铺满”比例。
                val scale = max(size.width / bitmap.width, size.height / bitmap.height)
                matrix.setScale(scale, scale)
                matrix.postTranslate(
                    (size.width - bitmap.width * scale) / 2f,
                    (size.height - bitmap.height * scale) / 2f,
                )
            }
            bitmapShader.setLocalMatrix(matrix)
            paint.shader = runtimeShader
            drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
        },
    )
}

private const val KawarpBlurSize = 128

/** 模糊版严格照 Kawarp 管线；像素版保留 192px + 过饱和细节。 */
private fun prepareAppleTexture(source: Bitmap, blurred: Boolean, blurPasses: Int): Bitmap {
    if (blurred) return prepareKawarpTexture(source, blurPasses.coerceIn(0, 20))
    val maxSide = 192
    val scale = maxSide.toFloat() / max(source.width, source.height)
    val width = max(1, (source.width * scale).toInt())
    val height = max(1, (source.height * scale).toInt())
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
    }
    canvas.drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    return output
}

private fun prepareKawarpTexture(source: Bitmap, blurPasses: Int): Bitmap {
    val size = KawarpBlurSize
    val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size)
    try {
        Canvas(scaled).drawBitmap(
            source,
            null,
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    } finally {
        if (!scaled.isRecycled) scaled.recycle()
    }

    tintDarkAreas(pixels)

    var input = pixels
    var output = IntArray(pixels.size)
    for (pass in 0 until blurPasses) {
        val offset = pass + 0.5f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val a = sampleBilinear(input, size, x - offset, y - offset)
                val b = sampleBilinear(input, size, x + offset, y - offset)
                val c = sampleBilinear(input, size, x - offset, y + offset)
                val d = sampleBilinear(input, size, x + offset, y + offset)
                output[y * size + x] = averageFour(a, b, c, d)
            }
        }
        val swap = input
        input = output
        output = swap
    }

    val tileable = makeTileable(input, size)
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    result.setPixels(tileable, 0, size, 0, 0, size, size)
    return result
}

/**
 * 把模糊纹理做成可平铺：与自身平移半幅的副本按“离中心的距离”交叉淡化，
 * 使左右/上下边缘颜色一致，从而可以 REPEAT 采样实现真正的环绕流动。
 */
private fun makeTileable(pixels: IntArray, size: Int): IntArray {
    val half = size / 2f
    val xPass = IntArray(pixels.size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val distance = kotlin.math.abs(x + 0.5f - size / 2f) / (size / 2f)
            val weight = smoothstepRange(0.35f, 1f, distance)
            val original = pixels[y * size + x]
            val shifted = sampleWrapped(pixels, size, x + half, y.toFloat())
            xPass[y * size + x] = blendColor(original, shifted, weight)
        }
    }
    val yPass = IntArray(pixels.size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val distance = kotlin.math.abs(y + 0.5f - size / 2f) / (size / 2f)
            val weight = smoothstepRange(0.35f, 1f, distance)
            val original = xPass[y * size + x]
            val shifted = sampleWrapped(xPass, size, x.toFloat(), y + half)
            yPass[y * size + x] = blendColor(original, shifted, weight)
        }
    }
    return yPass
}

private fun sampleWrapped(pixels: IntArray, size: Int, fx: Float, fy: Float): Int {
    val sizeF = size.toFloat()
    val x = ((fx % sizeF) + sizeF) % sizeF
    val y = ((fy % sizeF) + sizeF) % sizeF
    val x0 = x.toInt()
    val y0 = y.toInt()
    val x1 = (x0 + 1) % size
    val y1 = (y0 + 1) % size
    val tx = x - x0
    val ty = y - y0
    val c00 = pixels[y0 * size + x0]
    val c10 = pixels[y0 * size + x1]
    val c01 = pixels[y1 * size + x0]
    val c11 = pixels[y1 * size + x1]
    fun channel(shift: Int): Int {
        val top = (c00 shr shift and 0xFF) * (1f - tx) + (c10 shr shift and 0xFF) * tx
        val bottom = (c01 shr shift and 0xFF) * (1f - tx) + (c11 shr shift and 0xFF) * tx
        return (top * (1f - ty) + bottom * ty).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun blendColor(a: Int, b: Int, weight: Float): Int {
    val w = weight.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val ca = (a shr shift and 0xFF)
        val cb = (b shr shift and 0xFF)
        return (ca * (1f - w) + cb * w).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun smoothstepRange(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun tintDarkAreas(pixels: IntArray) {
    val tintR = 0.157f
    val tintG = 0.157f
    val tintB = 0.235f
    val intensity = 0.15f
    for (index in pixels.indices) {
        val color = pixels[index]
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        val dark = 1f - smoothstep01(luma / 0.5f)
        val mix = dark * intensity
        pixels[index] = (0xFF shl 24) or
            (toByteValue(r + (tintR - r) * mix) shl 16) or
            (toByteValue(g + (tintG - g) * mix) shl 8) or
            toByteValue(b + (tintB - b) * mix)
    }
}

private fun sampleBilinear(pixels: IntArray, size: Int, fx: Float, fy: Float): Int {
    val x = fx.coerceIn(0f, (size - 1).toFloat())
    val y = fy.coerceIn(0f, (size - 1).toFloat())
    val x0 = x.toInt().coerceAtMost(size - 1)
    val y0 = y.toInt().coerceAtMost(size - 1)
    val x1 = (x0 + 1).coerceAtMost(size - 1)
    val y1 = (y0 + 1).coerceAtMost(size - 1)
    val tx = x - x0
    val ty = y - y0
    val c00 = pixels[y0 * size + x0]
    val c10 = pixels[y0 * size + x1]
    val c01 = pixels[y1 * size + x0]
    val c11 = pixels[y1 * size + x1]
    fun channel(shift: Int): Int {
        val top = (c00 shr shift and 0xFF) * (1f - tx) + (c10 shr shift and 0xFF) * tx
        val bottom = (c01 shr shift and 0xFF) * (1f - tx) + (c11 shr shift and 0xFF) * tx
        return (top * (1f - ty) + bottom * ty).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun averageFour(a: Int, b: Int, c: Int, d: Int): Int {
    fun avg(shift: Int): Int =
        (((a shr shift and 0xFF) + (b shr shift and 0xFF) + (c shr shift and 0xFF) + (d shr shift and 0xFF)) shr 2)
    return (0xFF shl 24) or (avg(16) shl 16) or (avg(8) shl 8) or avg(0)
}

private fun smoothstep01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun toByteValue(v: Float): Int = (v * 255f).toInt().coerceIn(0, 255)

/**
 * 苹果式背景：低频 simplex 噪声平移让颜色整体“流动”，再叠一点轻旋涡做弯曲；
 * 位移幅度控制在不会折叠的范围内，色块保持平滑、外凸。
 */
private const val AppleWarpShader = """
uniform shader content;
uniform float2 resolution;
uniform float time;
uniform float twistAmount;
uniform float flowAmount;
uniform float saturation;
uniform float brightness;
uniform float3 harmonyColor;
uniform float harmonyAmount;
uniform float speed;
uniform float vignetteStrength;
uniform float detailMix;

vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec2 mod289(vec2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec3 permute(vec3 x) { return mod289(((x * 34.0) + 1.0) * x); }

float snoise(vec2 v) {
    const vec4 C = vec4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    vec2 i = floor(v + dot(v, C.yy));
    vec2 x0 = v - i + dot(i, C.xx);
    vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    vec4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289(i);
    vec3 p = permute(permute(i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
    vec3 m = max(0.5 - vec3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    vec3 x = 2.0 * fract(p * C.www) - 1.0;
    vec3 h = abs(x) - 0.5;
    vec3 ox = floor(x + 0.5);
    vec3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    vec3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float2 twist(float2 p, float2 center, float radius, float angle) {
    float2 coord = p - center;
    float ratio = length(coord) / radius;
    float falloff = 1.0 - smoothstep(0.55, 1.0, ratio);
    if (falloff <= 0.0) return p;
    float a = angle * ratio * ratio * falloff;
    float ca = cos(a);
    float sa = sin(a);
    float2 rotated = float2(coord.x * ca - coord.y * sa, coord.x * sa + coord.y * ca);
    return mix(coord, rotated, falloff) + center;
}

half4 main(float2 fragCoord) {
    float2 res = resolution;
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float t = time * speed;

    float2 sp = float2(uv.x * aspect, uv.y);

    // 流动主体：低频噪声场随时间平移，颜色会从一边漂到另一边（不是原地转）。
    float n1 = snoise(sp * 0.8 + float2(t * 0.5, t * 0.3));
    float n2 = snoise(sp * 0.8 + float2(5.2, 1.3) + float2(-t * 0.35, t * 0.45));
    float2 flow = float2(n1, n2) * flowAmount;
    // 纹理已无缝化并允许 REPEAT：颜色可以从一边流出、另一边流回。
    float2 p = sp + flow;

    // 轻旋涡：保留弯曲感，幅度很小，不产生原地旋转的观感。
    float2 c1 = float2(
        aspect * 0.42 + 0.05 * sin(t * 0.23),
        0.38 + 0.06 * cos(t * 0.19)
    );
    p = twist(p, c1, 0.42, twistAmount * (0.5 + 0.5 * sin(t * 0.31)));

    // 极慢整体旋转 + 呼吸缩放。
    float2 centered = p - float2(aspect * 0.5, 0.5);
    float ang = 0.03 * sin(t * 0.17);
    float ca = cos(ang);
    float sa = sin(ang);
    float zoom = 1.0 - 0.03 * (0.5 + 0.5 * sin(t * 0.21));
    float2 rotated = float2(centered.x * ca - centered.y * sa, centered.x * sa + centered.y * ca) * zoom;

    float2 finalUv = float2(rotated.x / aspect, rotated.y) + 0.5;

    half4 color = content.eval(finalUv * res);
    float3 rgb = float3(color.rgb);

    // 第二份“放大 + 旋转”的副本：放大能强化眼睛之类的小元素，旋转让原本的竖条纹出现其它方向。
    float2 detailCoord = finalUv - 0.5;
    float detailAngle = 2.39;
    float dca = cos(detailAngle);
    float dsa = sin(detailAngle);
    float2 detailUv = float2(
        detailCoord.x * dca - detailCoord.y * dsa,
        detailCoord.x * dsa + detailCoord.y * dca
    ) * 0.6 + 0.5;
    rgb = mix(rgb, float3(content.eval(detailUv * res).rgb), detailMix);

    // 向主色收拢，避免红蓝此类对比色在背景里各占一大片。
    rgb = mix(rgb, harmonyColor, harmonyAmount);

    float gray = dot(rgb, float3(0.299, 0.587, 0.114));
    rgb = mix(float3(gray), rgb, saturation);

    float2 vc = uv - 0.5;
    float vignette = 1.0 - dot(vc, vc) * vignetteStrength;
    rgb *= vignette * brightness;

    float dither = (hash21(fragCoord) - 0.5) * 0.008;
    rgb += dither;

    return half4(half3(rgb), 1.0);
}
"""

/** “扭曲像素”主题：高频 value-noise 扭曲、单层，保留像素感。 */
private const val PixelWarpShader = """
uniform shader content;
uniform float2 resolution;
uniform float time;
uniform float noiseScale;
uniform float warpAmount;
uniform float layerMix;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 2; i++) {
        v += amp * valueNoise(p);
        p = p * 2.03 + 17.1;
        amp *= 0.5;
    }
    return v;
}

float2 rotateScale(float2 uv, float angle, float zoom) {
    float2 c = uv - 0.5;
    float ca = cos(angle);
    float sa = sin(angle);
    return float2(c.x * ca - c.y * sa, c.x * sa + c.y * ca) * zoom + 0.5;
}

half4 main(float2 fragCoord) {
    float2 res = resolution;
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float2 np = float2(uv.x * aspect, uv.y) * noiseScale;
    float n1 = fbm(np + float2(time * 0.045, time * 0.018));
    float n2 = fbm(np + float2(5.2, 1.3) - float2(time * 0.025, time * 0.04));
    float2 warped = uv + (float2(n1, n2) - 0.5) * warpAmount;

    float2 uvA = rotateScale(warped, 0.10 * sin(time * 0.09), 1.0 - 0.06 * (0.5 + 0.5 * sin(time * 0.13)));
    half4 color = content.eval(uvA * res);

    if (layerMix > 0.0) {
        float2 uvB = rotateScale(warped, -0.16 * sin(time * 0.06 + 1.7), 1.24 - 0.08 * (0.5 + 0.5 * sin(time * 0.09 + 2.3)));
        half4 second = content.eval(uvB * res);
        color = mix(color, second, half(layerMix));
    }

    float dist = distance(uvA, float2(0.5, 0.45));
    float vignette = 1.0 - smoothstep(0.18, 0.95, dist);
    color.rgb *= half(0.45 + 0.55 * vignette);
    float dither = (hash21(fragCoord) - 0.5) / 255.0;
    color.rgb += half(dither);
    color.a = 1.0;
    return color;
}
"""
