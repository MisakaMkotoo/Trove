package com.exodidio.tune.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneRadii

@Composable
fun DiscCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    artworkPath: String? = null,
    artworkUrl: String? = null,
    fallbackSymbol: String = MaterialSymbols.MusicNote,
    artworkShape: Shape = RoundedCornerShape(TuneRadii.LargeArtwork),
    titleStyle: androidx.compose.ui.text.TextStyle? = null,
    subtitleStyle: androidx.compose.ui.text.TextStyle? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalTuneColors.current
    val bitmap = rememberArtworkThumbnail(artworkPath, targetPx = 600)
    val bitmapAlpha = rememberArtworkFadeAlpha(artworkPath, bitmap != null)
    val interactive = onClick != null || onLongClick != null
    val interactionSource = remember { MutableInteractionSource() }
    val pressModifier = if (interactive) Modifier.pressScale(interactionSource, pressedScale = 0.965f) else Modifier
    val clickModifier = remember(onClick, onLongClick, interactionSource) {
        if (interactive) {
            Modifier.combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
            )
        } else {
            Modifier
        }
    }

    Column(
        modifier = modifier
            .then(pressModifier)
            .clip(RoundedCornerShape(TuneRadii.LargeArtwork))
            .then(clickModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(artworkShape)
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, artworkShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(bitmapAlpha),
                )
                !artworkUrl.isNullOrBlank() -> TuneRemoteImage(
                    url = artworkUrl,
                    sizePx = 600,
                    modifier = Modifier.matchParentSize(),
                    fallback = {
                        MaterialSymbol(
                            symbol = fallbackSymbol,
                            contentDescription = null,
                            size = 36.dp,
                            tint = colors.textMuted,
                        )
                    },
                )
                else -> MaterialSymbol(
                    symbol = fallbackSymbol,
                    contentDescription = null,
                    size = 36.dp,
                    tint = colors.textMuted,
                )
            }
        }

        Text(
            text = title,
            style = titleStyle ?: MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )

        Text(
            text = subtitle,
            style = subtitleStyle ?: MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
        )
    }
}
