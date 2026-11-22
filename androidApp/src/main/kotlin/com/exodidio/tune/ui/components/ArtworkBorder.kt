package com.exodidio.tune.ui.components

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.exodidio.tune.ui.theme.TuneColors

/** 封面统一描边：与底栏一致的 0.5dp、5% 对比色，避免白底封面与背景糊在一起。 */
internal fun Modifier.artworkBorder(colors: TuneColors, shape: Shape): Modifier =
    border(0.5.dp, colors.borderGlass, shape)
