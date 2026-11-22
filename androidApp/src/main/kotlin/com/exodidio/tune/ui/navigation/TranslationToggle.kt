package com.exodidio.tune.ui.navigation

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodidio.tune.R
import com.exodidio.tune.ui.theme.LocalTuneColors

/**
 * Apple 风格歌词翻译开关：
 * - 外层：无描边的半透明白色圆角容器；
 * - 开启（阴文）：整块白色填充，图标位置镂空，露出容器自身的半透明白；
 * - 关闭（阳文）：容器上绘制白色图标。
 */
@Composable
internal fun TranslationToggle(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        tween(120),
        label = "translation-button-scale",
    )
    val negativeProgress by animateFloatAsState(
        if (enabled) 1f else 0f,
        tween(200),
        label = "translation-button-negative",
    )
    val label = stringResource(if (enabled) R.string.player_translation_hide else R.string.player_translation_show)
    val baseColor = colors.onPrimary.copy(alpha = 0.14f)
    val glyphColor = colors.onPrimary.copy(alpha = 0.85f)
    val fillColor = colors.onPrimary.copy(alpha = 0.92f)
    val density = LocalDensity.current
    val context = LocalContext.current
    val glyphTypeface = remember(context) {
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.material_symbols_rounded)
    }
    val iconSizePx = with(density) { 28.5.sp.toPx() }
    val cornerPx = with(density) { 12.dp.toPx() }

    Box(
        modifier = modifier
            .size(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(baseColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics {
                selected = enabled
                stateDescription = label
            }
            .testTag("translation_toggle"),
        contentAlignment = Alignment.Center,
    ) {
        // 阳文层：图标直接画在半透明容器上。
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - negativeProgress }) {
            drawTranslationGlyph(glyphColor, iconSizePx, glyphTypeface, xfermode = null)
        }
        // 阴文层：整块白色填充 + 图标镂空；镂空只清掉这层白色，露出下面的半透明容器。
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = negativeProgress
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            drawRoundRect(
                color = fillColor,
                topLeft = androidx.compose.ui.geometry.Offset.Zero,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
            )
            val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            drawTranslationGlyph(glyphColor, iconSizePx, glyphTypeface, xfermode = clearMode)
        }
    }
}

private fun DrawScope.drawTranslationGlyph(
    glyphColor: Color,
    iconSizePx: Float,
    typeface: android.graphics.Typeface?,
    xfermode: android.graphics.Xfermode?,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glyphColor.toArgbCompat()
        textSize = iconSizePx
        textAlign = Paint.Align.CENTER
        this.typeface = typeface
        fontFeatureSettings = "\"liga\" 1"
        if (xfermode != null) this.xfermode = xfermode
    }
    val metrics = paint.fontMetrics
    val baseline = size.height / 2f - (metrics.ascent + metrics.descent) / 2f
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText("g_translate", size.width / 2f, baseline, paint)
    }
}

private fun Color.toArgbCompat(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
