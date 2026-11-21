package com.exodidio.tune.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.playbackPreferencesDataStore by preferencesDataStore(name = "playback_preferences")
private val CrossfadeSecondsKey = intPreferencesKey("crossfade_seconds")
private val LastEnabledCrossfadeSecondsKey = intPreferencesKey("last_enabled_crossfade_seconds")
private val BlendArtworkDuringCrossfadeKey = booleanPreferencesKey("blend_artwork_during_crossfade")
private val ShowFullscreenQualityBadgeKey = booleanPreferencesKey("show_fullscreen_quality_badge")
private val HideShortAudioKey = booleanPreferencesKey("hide_short_audio")
private val AdaptiveTextColorKey = booleanPreferencesKey("adaptive_text_color")
private val AppleTwistKey = floatPreferencesKey("apple_backdrop_twist")
private val AppleSaturationKey = floatPreferencesKey("apple_backdrop_saturation")
private val AppleBrightnessKey = floatPreferencesKey("apple_backdrop_brightness")
private val AppleHarmonyKey = floatPreferencesKey("apple_backdrop_harmony")
private val AppleBlurPassesKey = intPreferencesKey("apple_backdrop_blur_passes")
private val AppleSpeedKey = floatPreferencesKey("apple_backdrop_speed")
private val AppleVignetteKey = floatPreferencesKey("apple_backdrop_vignette")
private val AppleDetailKey = floatPreferencesKey("apple_backdrop_detail")
private val AppleFlowKey = floatPreferencesKey("apple_backdrop_flow")

/** 苹果式正在播放背景的可调参数；默认值即当前定稿的观感。 */
internal data class AppleBackdropSettings(
    val twist: Float = 0.6f,
    val saturation: Float = 1.05f,
    val brightness: Float = 0.92f,
    val harmony: Float = 0.15f,
    val blurPasses: Int = 10,
    val speed: Float = 0.5f,
    val vignette: Float = 0.35f,
    val detail: Float = 0.35f,
    val flow: Float = 0.22f,
) {
    companion object {
        val Default = AppleBackdropSettings()
    }
}

internal data class CrossfadeSettings(
    val seconds: Int,
    val lastEnabledSeconds: Int,
    val blendArtworkDuringCrossfade: Boolean,
)

/** Persistent playback options, deliberately separate from the resumable queue session. */
internal class PlaybackPreferences(private val context: Context) {
    val settings: Flow<CrossfadeSettings> = context.playbackPreferencesDataStore.data.map { preferences ->
        val seconds = clampCrossfadeSeconds(preferences[CrossfadeSecondsKey] ?: CrossfadeDisabledSeconds)
        CrossfadeSettings(
            seconds = seconds,
            lastEnabledSeconds = clampEnabledCrossfadeSeconds(
                preferences[LastEnabledCrossfadeSecondsKey] ?: CrossfadeDefaultSeconds,
            ),
            blendArtworkDuringCrossfade = preferences[BlendArtworkDuringCrossfadeKey] ?: true,
        )
    }

    val crossfadeSeconds: Flow<Int> = settings.map { it.seconds }
    val showFullscreenQualityBadge: Flow<Boolean> = context.playbackPreferencesDataStore.data.map {
        it[ShowFullscreenQualityBadgeKey] ?: false
    }

    val hideShortAudio: Flow<Boolean> = context.playbackPreferencesDataStore.data.map {
        it[HideShortAudioKey] ?: false
    }

    val adaptiveTextColor: Flow<Boolean> = context.playbackPreferencesDataStore.data.map {
        it[AdaptiveTextColorKey] ?: false
    }

    val appleBackdrop: Flow<AppleBackdropSettings> = context.playbackPreferencesDataStore.data.map { preferences ->
        val defaults = AppleBackdropSettings.Default
        AppleBackdropSettings(
            twist = preferences[AppleTwistKey] ?: defaults.twist,
            saturation = preferences[AppleSaturationKey] ?: defaults.saturation,
            brightness = preferences[AppleBrightnessKey] ?: defaults.brightness,
            harmony = preferences[AppleHarmonyKey] ?: defaults.harmony,
            blurPasses = (preferences[AppleBlurPassesKey] ?: defaults.blurPasses).coerceIn(0, 20),
            speed = preferences[AppleSpeedKey] ?: defaults.speed,
            vignette = preferences[AppleVignetteKey] ?: defaults.vignette,
            detail = preferences[AppleDetailKey] ?: defaults.detail,
            flow = preferences[AppleFlowKey] ?: defaults.flow,
        )
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        context.playbackPreferencesDataStore.edit { preferences ->
            val clamped = clampCrossfadeSeconds(seconds)
            preferences[CrossfadeSecondsKey] = clamped
            if (clamped > CrossfadeDisabledSeconds) {
                preferences[LastEnabledCrossfadeSecondsKey] = clamped
            }
        }
    }

    suspend fun setBlendArtworkDuringCrossfade(enabled: Boolean) {
        context.playbackPreferencesDataStore.edit { preferences ->
            preferences[BlendArtworkDuringCrossfadeKey] = enabled
        }
    }

    suspend fun setShowFullscreenQualityBadge(enabled: Boolean) {
        context.playbackPreferencesDataStore.edit { it[ShowFullscreenQualityBadgeKey] = enabled }
    }

    suspend fun setHideShortAudio(enabled: Boolean) {
        context.playbackPreferencesDataStore.edit { it[HideShortAudioKey] = enabled }
    }

    suspend fun setAdaptiveTextColor(enabled: Boolean) {
        context.playbackPreferencesDataStore.edit { it[AdaptiveTextColorKey] = enabled }
    }

    suspend fun setAppleBackdrop(settings: AppleBackdropSettings) {
        context.playbackPreferencesDataStore.edit {
            it[AppleTwistKey] = settings.twist
            it[AppleSaturationKey] = settings.saturation
            it[AppleBrightnessKey] = settings.brightness
            it[AppleHarmonyKey] = settings.harmony
            it[AppleBlurPassesKey] = settings.blurPasses
            it[AppleSpeedKey] = settings.speed
            it[AppleVignetteKey] = settings.vignette
            it[AppleDetailKey] = settings.detail
            it[AppleFlowKey] = settings.flow
        }
    }
}

internal const val CrossfadeDisabledSeconds = 0
internal const val CrossfadeDefaultSeconds = 4
internal const val CrossfadeMaxSeconds = 12

internal fun clampCrossfadeSeconds(seconds: Int): Int = seconds.coerceIn(CrossfadeDisabledSeconds, CrossfadeMaxSeconds)
internal fun clampEnabledCrossfadeSeconds(seconds: Int): Int = seconds.coerceIn(1, CrossfadeMaxSeconds)
