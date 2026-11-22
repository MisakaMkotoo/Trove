package com.exodidio.tune.lyrics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map

private val Context.lyricsDataStore by preferencesDataStore("lyrics")
private val LrclibKey = booleanPreferencesKey("enable_lrclib")
private val KugouKey = booleanPreferencesKey("enable_kugou")
private val NeteaseKey = booleanPreferencesKey("enable_netease")
private val TranslationVisibleKey = booleanPreferencesKey("translation_visible")
private val LyricSpringStrengthKey = floatPreferencesKey("lyric_spring_strength")
private val LyricAutoFullscreenKey = booleanPreferencesKey("lyric_auto_fullscreen")
private val PreferredSourceKey = stringPreferencesKey("preferred_source")

internal enum class LyricsSource(val storageValue: String) {
    AutoFetch("auto_fetch"),
    ;

    companion object {
        fun fromStorage(value: String?) = entries.firstOrNull { it.storageValue == value } ?: AutoFetch
    }
}

internal data class LyricsSettings(
    val preferredSource: LyricsSource = LyricsSource.AutoFetch,
    val lrclib: Boolean = true,
    val kugou: Boolean = true,
    val netease: Boolean = true,
    val translationVisible: Boolean = true,
    val lyricSpringStrength: Float = 170f,
    val lyricAutoFullscreen: Boolean = true,
)

internal fun preferredLyrics(provider: String?): String? = provider

internal class LyricsPreferences(private val context: Context) {
    val settings = context.lyricsDataStore.data.map {
        LyricsSettings(
            preferredSource = LyricsSource.fromStorage(it[PreferredSourceKey]),
            lrclib = it[LrclibKey] ?: true,
            kugou = it[KugouKey] ?: true,
            netease = it[NeteaseKey] ?: true,
            translationVisible = it[TranslationVisibleKey] ?: true,
            lyricSpringStrength = it[LyricSpringStrengthKey] ?: 170f,
            lyricAutoFullscreen = it[LyricAutoFullscreenKey] ?: true,
        )
    }
    suspend fun setPreferredSource(source: LyricsSource) = context.lyricsDataStore.edit { it[PreferredSourceKey] = source.storageValue }
    suspend fun setLrclib(enabled: Boolean) = context.lyricsDataStore.edit { it[LrclibKey] = enabled }
    suspend fun setKugou(enabled: Boolean) = context.lyricsDataStore.edit { it[KugouKey] = enabled }
    suspend fun setNetease(enabled: Boolean) = context.lyricsDataStore.edit { it[NeteaseKey] = enabled }
    suspend fun setTranslationVisible(visible: Boolean) = context.lyricsDataStore.edit { it[TranslationVisibleKey] = visible }
    suspend fun setLyricSpringStrength(strength: Float) = context.lyricsDataStore.edit { it[LyricSpringStrengthKey] = strength }
    suspend fun setLyricAutoFullscreen(enabled: Boolean) = context.lyricsDataStore.edit { it[LyricAutoFullscreenKey] = enabled }
}
