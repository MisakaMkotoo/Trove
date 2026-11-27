package com.exodidio.tune.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.settings.ControlStyle
import com.exodidio.tune.ui.theme.LocalTuneColors

/** Full-width glass playback actions for a library collection. */
@Composable
fun LibraryPlaybackActions(
    playLabel: String,
    shuffleLabel: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LibraryPlaybackAction(
            symbol = MaterialSymbols.PlayArrow,
            label = playLabel,
            onClick = onPlay,
            hazeState = hazeState,
            modifier = Modifier.weight(1f),
        )
        LibraryPlaybackAction(
            symbol = MaterialSymbols.Shuffle,
            label = shuffleLabel,
            onClick = onShuffle,
            hazeState = hazeState,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LibraryPlaybackAction(
    symbol: String,
    label: String,
    onClick: () -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(26.dp)
    val useOpticalGlass = LocalControlStyle.current == ControlStyle.LiquidGlass &&
        !LocalReduceMotion.current &&
        tuneGlassBackdrop() != null
    Box(
        modifier = modifier
            .pressScale(interactionSource, pressedScale = 0.965f)
            .defaultMinSize(minHeight = 48.dp)
            .then(
                if (!useOpticalGlass) {
                    Modifier
                        .clip(shape)
                        .border(0.5.dp, colors.borderGlass, shape)
                } else {
                    Modifier
                },
            )
            .tuneLiquidGlassBackground(
                hazeState = hazeState,
                colors = colors,
                shape = shape,
                glassBlurRadius = LocalLiquidGlassBlurDp.current.dp,
                enableLens = true,
                lensRadius = 16.dp,
                refractionHeight = 10.dp,
                chromaticAberration = useOpticalGlass,
            )
            .semantics { contentDescription = label }
            .clickable(
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MaterialSymbol(symbol, null, size = 20.dp, tint = colors.primary, filled = true)
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}
