package com.exodidio.tune.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTextFilterSlotTest {
    @Test
    fun closedFilterHasNoRetainedHeightAfterDismissal() {
        assertEquals(
            0.dp,
            libraryTextFilterSlotTargetHeight(
                visible = false,
                dismissalProgress = 48.dp,
                previewHeight = 0.dp,
            ),
        )
    }

    @Test
    fun finalCloseFrameAnimatesInsteadOfSnapping() {
        assertEquals(
            false,
            libraryTextFilterSlotUsesDragSnap(
                isUserDragging = true,
                visible = false,
                dismissalProgressPx = 48f,
                previewHeightPx = 0f,
            ),
        )
    }

    @Test
    fun appleSfGlyphTextSizeScalesByIconSizeNotContainerSize() {
        val iconSizePx = 75f // 25.dp at 3x
        val baseTextSizePx = iconSizePx * 0.75f
        // Wide glyph exceeds 0.78 * iconSizePx -> scaled proportionally to iconSizePx
        val scaledWide = computeAppleSfGlyphTextSizePx(
            baseTextSizePx = baseTextSizePx,
            boundsWidth = 78f,
            boundsHeight = 50f,
            iconSizePx = iconSizePx,
        )
        assertEquals(baseTextSizePx * ((iconSizePx * 0.78f) / 78f), scaledWide, 0.001f)

        // Small glyph fits inside 0.78 * iconSizePx -> keeps 0.75 * iconSizePx
        val unscaledSmall = computeAppleSfGlyphTextSizePx(
            baseTextSizePx = baseTextSizePx,
            boundsWidth = 48f,
            boundsHeight = 48f,
            iconSizePx = iconSizePx,
        )
        assertEquals(baseTextSizePx, unscaledSmall, 0.001f)
    }
}
