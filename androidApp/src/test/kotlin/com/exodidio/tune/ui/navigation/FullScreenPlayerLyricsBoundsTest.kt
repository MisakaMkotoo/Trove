package com.exodidio.tune.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenPlayerLyricsBoundsTest {
    @Test
    fun `rows scrolled completely above viewport cannot intercept top ui or artwork taps`() {
        assertFalse(
            isLyricRowClickableWithinViewport(
                rowTopY = -120f,
                rowHeightPx = 80,
                viewportHeightPx = 900,
                bottomInsetPx = 220f,
            ),
        )
        assertFalse(
            isLyricRowClickableWithinViewport(
                rowTopY = -80f,
                rowHeightPx = 80,
                viewportHeightPx = 900,
                bottomInsetPx = 220f,
            ),
        )
    }

    @Test
    fun `rows intersecting visible cutoff range remain clickable`() {
        assertTrue(
            isLyricRowClickableWithinViewport(
                rowTopY = -20f,
                rowHeightPx = 80,
                viewportHeightPx = 900,
                bottomInsetPx = 220f,
            ),
        )
        assertTrue(
            isLyricRowClickableWithinViewport(
                rowTopY = 160f,
                rowHeightPx = 80,
                viewportHeightPx = 900,
                bottomInsetPx = 220f,
            ),
        )
    }

    @Test
    fun `rows below bottom controls cutoff cannot be clicked`() {
        assertFalse(
            isLyricRowClickableWithinViewport(
                rowTopY = 680f,
                rowHeightPx = 80,
                viewportHeightPx = 900,
                bottomInsetPx = 220f,
            ),
        )
    }
}
