package com.exodidio.tune.lyrics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlin.math.abs

internal val Context.lyricsDataStore by preferencesDataStore("lyrics")
private val LrclibKey = booleanPreferencesKey("enable_lrclib")
private val KugouKey = booleanPreferencesKey("enable_kugou")
private val NeteaseKey = booleanPreferencesKey("enable_netease")
private val AmllKey = booleanPreferencesKey("enable_amll")
private val SourceOrderKey = stringPreferencesKey("source_order")
private val SourcesEnabledKey = stringSetPreferencesKey("sources_enabled")
private val WordFallbackKey = stringPreferencesKey("word_fallback_source")
private val TranslationVisibleKey = booleanPreferencesKey("translation_visible")
private val LyricSpringStrengthKey = floatPreferencesKey("lyric_spring_strength")
private val LyricAutoFullscreenKey = booleanPreferencesKey("lyric_auto_fullscreen")
private val LyricFocusReturnDelayMsKey = longPreferencesKey("lyric_focus_return_delay_ms")
private val LyricAutoFullscreenDelayMsKey = longPreferencesKey("lyric_auto_fullscreen_delay_ms")
private val SystemLyricModeKey = stringPreferencesKey("system_lyric_mode")
private val StandaloneLyricNotificationKey = booleanPreferencesKey("standalone_lyric_notification")
private val HyperosSuperIslandKey = booleanPreferencesKey("hyperos_super_island")
private val AutoAlignKey = booleanPreferencesKey("auto_align_word_timing")
private val PerSongLyricsConfigEnabledKey = booleanPreferencesKey("per_song_lyrics_config_enabled")

internal const val DefaultLyricFocusReturnDelayMs = 3_000L
internal const val DefaultLyricAutoFullscreenDelayMs = 3_000L
internal val LyricTimingDelayPresetsMs = listOf(2_000L, 3_000L, 5_000L, 8_000L, 10_000L)

internal fun normalizeLyricDelayPresetMs(value: Long?): Long {
    if (value == null || value <= 0L) return DefaultLyricFocusReturnDelayMs
    return LyricTimingDelayPresetsMs.minByOrNull { abs(it - value) }
        ?: DefaultLyricFocusReturnDelayMs
}

/** 系统媒体通知里的歌词位置：关闭 / 歌词优先（标题） / 歌曲优先（标题保持歌名）。 */
internal enum class SystemLyricMode(val storageValue: String) {
    Off("off"),
    LyricFirst("lyric_first"),
    SongFirst("song_first"),
    ;

    companion object {
        fun fromStorage(value: String?): SystemLyricMode =
            entries.firstOrNull { it.storageValue == value } ?: Off
    }
}

/** 歌词来源标识，顺序即抓取优先级。 */
internal enum class LyricsSourceId(val storageValue: String) {
    Netease("netease"),
    Amll("amll"),
    Lrclib("lrclib"),
    Kugou("kugou"),
    ;

    companion object {
        fun fromStorage(value: String?): LyricsSourceId? =
            entries.firstOrNull { it.storageValue == value }
    }
}

internal val DefaultLyricsSourceOrder = listOf(
    LyricsSourceId.Netease,
    LyricsSourceId.Amll,
    LyricsSourceId.Lrclib,
    LyricsSourceId.Kugou,
)

internal data class LyricsSettings(
    /** 抓取优先级；网易云默认第一（最稳定），AMLL 第二。 */
    val sourceOrder: List<LyricsSourceId> = DefaultLyricsSourceOrder,
    val enabledSources: Set<LyricsSourceId> = LyricsSourceId.entries.toSet(),
    /** 已有行级歌词但没有逐字时，优先去哪个源找逐字；null = 无（直接用行级，默认）。 */
    val wordFallbackSource: LyricsSourceId? = null,
    val translationVisible: Boolean = true,
    val lyricSpringStrength: Float = 170f,
    val lyricAutoFullscreen: Boolean = true,
    val lyricFocusReturnDelayMs: Long = DefaultLyricFocusReturnDelayMs,
    val lyricAutoFullscreenDelayMs: Long = DefaultLyricAutoFullscreenDelayMs,
    val systemLyricMode: SystemLyricMode = SystemLyricMode.Off,
    val standaloneLyricNotification: Boolean = false,
    /** 没有逐字歌词时，用本地 ONNX 模型做音频强制对齐。 */
    val autoAlignWordTiming: Boolean = false,
    val hyperosSuperIsland: Boolean = false,
    val perSongLyricsConfigEnabled: Boolean = false,
)

internal fun preferredLyrics(provider: String?): String? = provider

internal class LyricsPreferences(private val context: Context) {
    val settings = context.lyricsDataStore.data.map {
        val enabled = it[SourcesEnabledKey]
            ?.mapNotNull(LyricsSourceId::fromStorage)
            ?.toSet()
            ?: buildSet {
                // 旧存档没有新键：按旧的四个开关回填
                if (it[NeteaseKey] ?: true) add(LyricsSourceId.Netease)
                if (it[AmllKey] ?: true) add(LyricsSourceId.Amll)
                if (it[LrclibKey] ?: true) add(LyricsSourceId.Lrclib)
                if (it[KugouKey] ?: true) add(LyricsSourceId.Kugou)
            }
        val order = it[SourceOrderKey]
            ?.split(',')
            ?.mapNotNull(LyricsSourceId::fromStorage)
            ?.let { stored -> orderedSources(stored) }
            ?: DefaultLyricsSourceOrder
        // 默认不追查逐字（AMLL 命中也有网络延迟）；只有用户显式选过才按存档值。
        val fallback = if (it.contains(WordFallbackKey)) {
            LyricsSourceId.fromStorage(it[WordFallbackKey])
        } else {
            null
        }
        LyricsSettings(
            sourceOrder = order,
            enabledSources = enabled,
            wordFallbackSource = fallback,
            translationVisible = it[TranslationVisibleKey] ?: true,
            lyricSpringStrength = it[LyricSpringStrengthKey] ?: 170f,
            lyricAutoFullscreen = it[LyricAutoFullscreenKey] ?: true,
            lyricFocusReturnDelayMs = normalizeLyricDelayPresetMs(it[LyricFocusReturnDelayMsKey]),
            lyricAutoFullscreenDelayMs = normalizeLyricDelayPresetMs(it[LyricAutoFullscreenDelayMsKey]),
            systemLyricMode = SystemLyricMode.fromStorage(it[SystemLyricModeKey]),
            standaloneLyricNotification = it[StandaloneLyricNotificationKey] ?: false,
            autoAlignWordTiming = it[AutoAlignKey] ?: false,
            hyperosSuperIsland = it[HyperosSuperIslandKey] ?: false,
            perSongLyricsConfigEnabled = it[PerSongLyricsConfigEnabledKey] ?: false,
        )
    }

    suspend fun setSources(order: List<LyricsSourceId>, enabled: Set<LyricsSourceId>) {
        context.lyricsDataStore.edit {
            it[SourceOrderKey] = orderedSources(order).joinToString(",") { source -> source.storageValue }
            it[SourcesEnabledKey] = enabled.map(LyricsSourceId::storageValue).toSet()
        }
    }

    suspend fun setWordFallbackSource(source: LyricsSourceId?) {
        context.lyricsDataStore.edit { it[WordFallbackKey] = source?.storageValue.orEmpty() }
    }

    suspend fun setLrclib(enabled: Boolean) = updateEnabled(LyricsSourceId.Lrclib, enabled)
    suspend fun setKugou(enabled: Boolean) = updateEnabled(LyricsSourceId.Kugou, enabled)
    suspend fun setNetease(enabled: Boolean) = updateEnabled(LyricsSourceId.Netease, enabled)
    suspend fun setAmll(enabled: Boolean) = updateEnabled(LyricsSourceId.Amll, enabled)

    private suspend fun updateEnabled(source: LyricsSourceId, enabled: Boolean) {
        context.lyricsDataStore.edit {
            val current = it[SourcesEnabledKey]?.toMutableSet() ?: mutableSetOf()
            if (enabled) current += source.storageValue else current -= source.storageValue
            it[SourcesEnabledKey] = current
        }
    }

    suspend fun setTranslationVisible(visible: Boolean) = context.lyricsDataStore.edit { it[TranslationVisibleKey] = visible }
    suspend fun toggleTranslationVisible() {
        context.lyricsDataStore.edit {
            val current = it[TranslationVisibleKey] ?: true
            it[TranslationVisibleKey] = !current
        }
    }
    suspend fun setLyricSpringStrength(strength: Float) = context.lyricsDataStore.edit { it[LyricSpringStrengthKey] = strength }
    suspend fun setLyricAutoFullscreen(enabled: Boolean) = context.lyricsDataStore.edit { it[LyricAutoFullscreenKey] = enabled }
    suspend fun setLyricFocusReturnDelayMs(delayMs: Long) =
        context.lyricsDataStore.edit { it[LyricFocusReturnDelayMsKey] = normalizeLyricDelayPresetMs(delayMs) }
    suspend fun setLyricAutoFullscreenDelayMs(delayMs: Long) =
        context.lyricsDataStore.edit { it[LyricAutoFullscreenDelayMsKey] = normalizeLyricDelayPresetMs(delayMs) }
    suspend fun setSystemLyricMode(mode: SystemLyricMode) = context.lyricsDataStore.edit { it[SystemLyricModeKey] = mode.storageValue }
    suspend fun setAutoAlignWordTiming(enabled: Boolean) =
        context.lyricsDataStore.edit { it[AutoAlignKey] = enabled }

    suspend fun setStandaloneLyricNotification(enabled: Boolean) =
        context.lyricsDataStore.edit { it[StandaloneLyricNotificationKey] = enabled }
    suspend fun setHyperosSuperIsland(enabled: Boolean) = context.lyricsDataStore.edit { it[HyperosSuperIslandKey] = enabled }
    suspend fun setPerSongLyricsConfigEnabled(enabled: Boolean) =
        context.lyricsDataStore.edit { it[PerSongLyricsConfigEnabledKey] = enabled }
}

/** 补全缺失的来源并去重：外部偏好里的顺序 + 其余来源。 */
internal fun orderedSources(order: List<LyricsSourceId>): List<LyricsSourceId> {
    val result = order.distinct().toMutableList()
    LyricsSourceId.entries.forEach { if (it !in result) result += it }
    return result
}
