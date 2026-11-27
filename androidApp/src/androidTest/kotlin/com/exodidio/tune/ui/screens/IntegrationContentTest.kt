package com.exodidio.tune.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.exodidio.tune.lyrics.LyricsSettings
import com.exodidio.tune.lyrics.LyricsSourceId
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.ui.theme.TuneTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class IntegrationContentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun lyricsSourceToggleReportsDisabledSource() {
        var enabled = LyricsSourceId.entries.toSet()
        composeTestRule.setContent {
            TuneTheme(themeMode = ThemeMode.Dark) {
                LyricsContent(
                    settings = LyricsSettings(),
                    onSourcesChanged = { _, sources -> enabled = sources },
                    onWordFallbackChanged = {},
                )
            }
        }

        composeTestRule.onNodeWithText("NetEase").performClick()
        assertFalse(LyricsSourceId.Netease in enabled)
    }
}
