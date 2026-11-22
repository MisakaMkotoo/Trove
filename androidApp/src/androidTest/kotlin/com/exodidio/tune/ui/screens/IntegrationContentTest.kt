package com.exodidio.tune.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.exodidio.tune.lyrics.LyricsSettings
import com.exodidio.tune.lyrics.LyricsSource
import com.exodidio.tune.settings.ThemeMode
import com.exodidio.tune.ui.theme.TuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IntegrationContentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun lyricsSourceSelectionReportsAutoFetch() {
        var source = LyricsSource.AutoFetch
        composeTestRule.setContent {
            TuneTheme(themeMode = ThemeMode.Dark) {
                LyricsContent(
                    settings = LyricsSettings(),
                    onSourceChanged = { source = it },
                    onLrclibChanged = {},
                    onKugouChanged = {},
                    onNeteaseChanged = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Auto fetch").performClick()
        assertEquals(LyricsSource.AutoFetch, source)
    }
}
