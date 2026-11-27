package com.exodidio.tune.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.dp
import com.exodidio.tune.ui.theme.TuneColors
import com.kyant.backdrop.highlight.HighlightStyle
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeEffectNode
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalHazeApi::class)
class LiquidGlassTest {
    @Test
    fun backdropKeepsFullInputResolution() {
        val colors = TuneColors(
            background = Color.Unspecified,
            playerBackdrop = Color.Unspecified,
            card = Color.Unspecified,
            glass = Color.White.copy(alpha = 0.4f),
            glassOpaque = Color.White,
            glassElevated = Color.Unspecified,
            sliderInactive = Color.Unspecified,
            buttonSecondary = Color.Unspecified,
            textFieldClear = Color.Unspecified,
            borderGlass = Color.Unspecified,
            textMain = Color.Unspecified,
            textMuted = Color.Unspecified,
            primary = Color.Unspecified,
            onPrimary = Color.Unspecified,
            foregroundSubtle = Color.Unspecified,
            success = Color.Unspecified,
            navigationActive = Color.Unspecified,
        )
        val nodes = Modifier.liquidGlassBackground(HazeState(), colors)
            .foldIn(emptyList<HazeEffectNode>()) { nodes, element ->
                val node = (element as? ModifierNodeElement<*>)?.create()
                if (node is HazeEffectNode) nodes + node else nodes
            }
        val effect = nodes.single()
        // Apply the modifier's real configuration without attaching an Android renderer.
        effect.block!!.invoke(effect)
        assertEquals(HazeInputScale.None, effect.inputScale)
    }

    @Test
    fun lightModeLiquidGlassShadowsAndAmbientRimRemainVisibleOnWhite() {
        val restDrop = liquidGlassDropShadow(isDark = false, isCircular = true, pressProgress = 0f)
        val pressedDrop = liquidGlassDropShadow(isDark = false, isCircular = true, pressProgress = 1f)
        assertEquals(1f, restDrop.alpha, 1e-4f)
        assertTrue(restDrop.color.alpha >= 0.08f)
        assertTrue(pressedDrop.color.alpha > restDrop.color.alpha)
        assertEquals(12.dp, restDrop.radius)

        val restInner = liquidGlassInnerShadow(isDark = false, pressProgress = 0f)
        val pressedInner = liquidGlassInnerShadow(isDark = false, pressProgress = 1f)
        assertEquals(1f, restInner.alpha, 1e-4f)
        assertTrue(restInner.color.alpha >= 0.06f)
        assertTrue(pressedInner.color.alpha > restInner.color.alpha)

        val lightHighlight = liquidGlassHighlight(isDark = false, pressProgress = 0f)
        assertEquals(HighlightStyle.Ambient, lightHighlight.style)
        assertTrue(lightHighlight.alpha >= 0.20f)
        assertEquals(0.75.dp, lightHighlight.width)

        val darkHighlight = liquidGlassHighlight(isDark = true, pressProgress = 0f)
        assertEquals(HighlightStyle.Default, darkHighlight.style)
        assertTrue(darkHighlight.alpha >= 0.30f)

        assertTrue(resolveGlassEdgeShadowAlpha(isDark = false) >= 0.28f)
        assertTrue(resolveGlassEdgeShadowAlpha(isDark = true) >= 0.20f)
    }
}
