package com.exodidio.tune.settings

import com.exodidio.tune.player.MediaNotificationStyle
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.appleSymbolSupports
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeIconControlStyleTest {
    @Test
    fun `icon control style accent and notification presets round trip with safe defaults`() {
        assertEquals(IconStyle.Material, IconStyle.fromStorage(null))
        assertEquals(IconStyle.Material, IconStyle.fromStorage("unknown"))
        IconStyle.entries.forEach { style ->
            assertEquals(style, IconStyle.fromStorage(style.storageValue))
        }

        assertEquals(ControlStyle.Frosted, ControlStyle.fromStorage(null))
        assertEquals(ControlStyle.Frosted, ControlStyle.fromStorage("unknown"))
        ControlStyle.entries.forEach { style ->
            assertEquals(style, ControlStyle.fromStorage(style.storageValue))
        }

        assertEquals(ThemeAccentColor.Red, ThemeAccentColor.fromStorage(null))
        assertEquals(ThemeAccentColor.Red, ThemeAccentColor.fromStorage("unknown"))
        assertEquals(5, ThemeAccentColor.entries.size)
        ThemeAccentColor.entries.forEach { accent ->
            assertEquals(accent, ThemeAccentColor.fromStorage(accent.storageValue))
        }

        assertEquals(MediaNotificationStyle.Default, MediaNotificationStyle.fromStorage(null))
        assertEquals(MediaNotificationStyle.Default, MediaNotificationStyle.fromStorage("unknown"))
        MediaNotificationStyle.entries.forEach { preset ->
            assertEquals(preset, MediaNotificationStyle.fromStorage(preset.storageValue))
        }
    }

    @Test
    fun `apple symbol mapper covers every MaterialSymbols constant`() {
        val symbolFields = MaterialSymbols::class.java.declaredFields.filter { field ->
            Modifier.isStatic(field.modifiers) && field.type == String::class.java
        }
        assertTrue("Expected MaterialSymbols constants", symbolFields.isNotEmpty())
        val unsupported = symbolFields.mapNotNull { field ->
            field.isAccessible = true
            val value = field.get(null) as String
            if (appleSymbolSupports(value)) null else "${field.name}=$value"
        }
        assertTrue(
            "MaterialSymbols without Apple glyph mapping: $unsupported",
            unsupported.isEmpty(),
        )
    }
}
