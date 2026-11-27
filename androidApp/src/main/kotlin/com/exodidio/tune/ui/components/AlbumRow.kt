package com.exodidio.tune.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneRadii

@Composable
fun AlbumRow(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    artworkPath: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val bitmap = rememberArtworkThumbnail(artworkPath)
    val bitmapAlpha = rememberArtworkFadeAlpha(artworkPath, bitmap != null)
    val interactive = onClick != null || onLongClick != null
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressTint by animateColorAsState(
        targetValue = if (pressed && interactive && !reduceMotion) colors.textMain.copy(alpha = 0.05f) else Color.Transparent,
        animationSpec = if (reduceMotion) snap() else tween(140, easing = FastOutSlowInEasing),
        label = "album-row-press-tint",
    )
    val clickModifier = remember(onClick, onLongClick, interactionSource) {
        if (interactive) Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            indication = null,
        ) else Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(pressTint)
            .then(clickModifier)
            .padding(top = 6.dp, end = 8.dp, bottom = 6.dp, start = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(TuneRadii.ListArtwork))
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(TuneRadii.ListArtwork)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(bitmapAlpha),
                )
            } else {
                MaterialSymbol(
                    symbol = MaterialSymbols.Album,
                    contentDescription = null,
                    size = 22.dp,
                    tint = colors.textMuted,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        IconButton(
            onClick = { onClick?.invoke() },
            modifier = Modifier.size(48.dp),
        ) {
            MaterialSymbol(
                symbol = MaterialSymbols.ChevronRight,
                contentDescription = stringResource(R.string.album_row_open),
                size = 20.dp,
                tint = colors.textMuted,
            )
        }
    }
}
