package com.exodidio.tune.settings

import com.exodidio.tune.AppDestination
import com.exodidio.tune.ui.screens.mergeTabOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class TabLayoutOrderTest {
    @Test
    fun `parseVisibleTabs preserves comma-separated order and ignores invalid items`() {
        val parsed = parseVisibleTabs(
            orderedRaw = "search,unknown,mine,settings,home,search",
            legacySet = setOf("home", "discover"),
        )
        assertEquals(
            listOf(AppDestination.Search, AppDestination.Mine, AppDestination.Home),
            parsed,
        )
    }

    @Test
    fun `parseVisibleTabs falls back to dockable order for legacy set and defaults`() {
        assertEquals(
            listOf(AppDestination.Home, AppDestination.Search),
            parseVisibleTabs(orderedRaw = null, legacySet = setOf("search", "home")),
        )
        assertEquals(
            AppDestination.dockable,
            parseVisibleTabs(orderedRaw = null, legacySet = null),
        )
    }

    @Test
    fun `mergeTabOrder fills visible slots with preferred order and keeps hidden tabs anchored`() {
        val current = listOf(
            AppDestination.Home,
            AppDestination.Discover,
            AppDestination.Mine,
            AppDestination.Library,
            AppDestination.Search,
        )
        val merged = mergeTabOrder(
            preferred = listOf(
                AppDestination.Search,
                AppDestination.Home,
                AppDestination.Mine,
                AppDestination.Library,
            ),
            current = current,
        )
        assertEquals(
            listOf(
                AppDestination.Search,
                AppDestination.Discover,
                AppDestination.Home,
                AppDestination.Mine,
                AppDestination.Library,
            ),
            merged,
        )
    }
}
