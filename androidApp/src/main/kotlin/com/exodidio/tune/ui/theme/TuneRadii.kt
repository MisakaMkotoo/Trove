package com.exodidio.tune.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一圆角：遵循 Apple 的同心圆角规则（内圆角 = 外圆角 − 间距，圆心重合）。
 * 胶囊类控件的半径由自身高度决定。
 */
object TuneRadii {
    /** 设置页等卡片。 */
    val Card = 28.dp
    /** 底部弹层。 */
    val Sheet = 30.dp
    /** 大封面/大图（卡片内的封面按同心规则与卡片一致）。 */
    val LargeArtwork = 12.dp
    /** 列表行封面（实测 Apple Music 列表为 4dp）。 */
    val ListArtwork = 4.dp

    /** 胶囊半径：始终等于高度的一半。 */
    fun capsule(height: Dp): Dp = height / 2

    /** 同心内圆角：父圆角减去内外间距，最小为 0。 */
    fun inner(outer: Dp, padding: Dp): Dp = (outer - padding).coerceAtLeast(0.dp)
}
