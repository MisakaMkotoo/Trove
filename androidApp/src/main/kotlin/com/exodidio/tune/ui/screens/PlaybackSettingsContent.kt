package com.exodidio.tune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDivider
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.components.TuneTrackSlider
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.player.EqualizerFrequenciesHz
import com.exodidio.tune.player.EqualizerSettings
import com.exodidio.tune.player.normalizeEqGain

@Composable
internal fun PlaybackSettingsContent(
    showFullscreenQualityBadge: Boolean,
    onShowFullscreenQualityBadgeChanged: (Boolean) -> Unit,
    onSongTransitionSelected: () -> Unit,
    onVolumeNormalizationSelected: () -> Unit,
    onEqualizerSelected: () -> Unit,
    modifier: Modifier = Modifier,
    audioQuality: com.exodidio.tune.player.AudioQualityMode =
        com.exodidio.tune.player.AudioQualityMode.Auto,
    onAudioQualityChanged: (com.exodidio.tune.player.AudioQualityMode) -> Unit = {},
    onPlaybackLyricsSelected: () -> Unit = {},
    onFunFeaturesSelected: () -> Unit = {},
    hazeState: HazeState? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LabeledCard(label = stringResource(R.string.playback_section_audio)) {
            Selection(
                labelRes = R.string.settings_audio_quality,
                options = listOf(
                    SelectionOption(
                        com.exodidio.tune.player.AudioQualityMode.Auto,
                        R.string.audio_quality_auto,
                        shortLabelRes = R.string.audio_quality_auto_short,
                    ),
                    SelectionOption(
                        com.exodidio.tune.player.AudioQualityMode.Mp3Only,
                        R.string.audio_quality_mp3,
                        shortLabelRes = R.string.audio_quality_mp3_short,
                    ),
                    SelectionOption(
                        com.exodidio.tune.player.AudioQualityMode.LosslessOnly,
                        R.string.audio_quality_lossless,
                        shortLabelRes = R.string.audio_quality_lossless_short,
                    ),
                ),
                selectedValue = audioQuality,
                onValueSelected = onAudioQualityChanged,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.playback_volume_normalization,
                        onClick = onVolumeNormalizationSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.equalizer_title,
                        onClick = onEqualizerSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }
        LabeledCard(label = stringResource(R.string.playback_section_experience)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.song_transition_title,
                        onClick = onSongTransitionSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_lyrics_title,
                        onClick = onPlaybackLyricsSelected,
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_show_quality_badge,
                        trailingContent = {
                            TuneSwitch(
                                checked = showFullscreenQualityBadge,
                                onCheckedChange = onShowFullscreenQualityBadgeChanged,
                            )
                        },
                        onClick = { onShowFullscreenQualityBadgeChanged(!showFullscreenQualityBadge) },
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_fun_features_title,
                        onClick = onFunFeaturesSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }
    }
}

@Composable
internal fun EqualizerContent(
    settings: EqualizerSettings,
    onEnabledChanged: (Boolean) -> Unit,
    onPresetSelected: (String) -> Unit,
    onBandChanged: (Int, Float) -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val enabledLabel = stringResource(R.string.equalizer_enable)
    val colors = LocalTuneColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.exodidio.tune.ui.components.Card {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.equalizer_enable,
                        trailingContent = { TuneSwitch(checked = settings.enabled, onCheckedChange = onEnabledChanged) },
                        onClick = { onEnabledChanged(!settings.enabled) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.equalizer_preset,
                options = settings.profiles.map { SelectionOption(it.key, label = it.name) },
                selectedValue = settings.presetKey,
                onValueSelected = onPresetSelected,
                hazeState = hazeState,
            )
        }
        LabeledCard(label = stringResource(R.string.equalizer_bands)) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                    settings.gainsDb.forEachIndexed { index, gain ->
                        val frequency = EqualizerFrequenciesHz[index]
                        val frequencyLabel = if (frequency < 1_000) {
                            stringResource(R.string.equalizer_frequency_hz, frequency)
                        } else {
                            stringResource(R.string.equalizer_frequency_khz, frequency / 1_000)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = frequencyLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textMuted,
                            )
                            Text(
                                text = stringResource(R.string.equalizer_gain_value, gain),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textMuted,
                            )
                        }
                        TuneTrackSlider(
                            value = gain,
                            valueRange = -12f..12f,
                            onValueChange = { onBandChanged(index, normalizeEqGain(it)) },
                            modifier = Modifier.semantics { contentDescription = "$enabledLabel $frequencyLabel" },
                            trackHeight = 6.dp,
                            activeTrackColor = colors.primary,
                            inactiveTrackColor = colors.buttonSecondary,
                            trackAlignment = Alignment.TopCenter,
                        )
                    }
                }
        }
    }
}

@Composable
internal fun VolumeNormalizationContent(
    normalizationAvailable: Boolean,
    normalization: com.exodidio.tune.player.NormalizationSettings,
    onNormalizationChanged: (com.exodidio.tune.player.NormalizationSettings) -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.exodidio.tune.ui.components.Card {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.playback_volume_normalization,
                        trailingContent = { TuneSwitch(checked = normalization.enabled && normalizationAvailable, enabled = normalizationAvailable, onCheckedChange = { onNormalizationChanged(normalization.copy(enabled = it)) }) },
                        onClick = { if (normalizationAvailable) onNormalizationChanged(normalization.copy(enabled = !normalization.enabled)) },
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_normalization_prevent_clip,
                        trailingContent = { TuneSwitch(checked = normalization.preventClip, enabled = normalizationAvailable && normalization.enabled, onCheckedChange = { onNormalizationChanged(normalization.copy(preventClip = it)) }) },
                        onClick = { if (normalizationAvailable && normalization.enabled) onNormalizationChanged(normalization.copy(preventClip = !normalization.preventClip)) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
            if (normalization.enabled && normalizationAvailable) {
                ActionListDivider(style = ActionListDividerStyle.FullWidth)
                Selection(
                    labelRes = R.string.playback_normalization_mode,
                    options = listOf(
                        SelectionOption(com.exodidio.tune.player.NormalizationMode.Track, R.string.playback_normalization_mode_track),
                        SelectionOption(com.exodidio.tune.player.NormalizationMode.Album, R.string.playback_normalization_mode_album),
                    ),
                    selectedValue = normalization.mode,
                    onValueSelected = { onNormalizationChanged(normalization.copy(mode = it)) },
                    hazeState = hazeState,
                )
            }
        }
        AnimatedVisibility(
            visible = normalization.enabled && normalizationAvailable,
            enter = fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = fadeOut(animationSpec = tween(durationMillis = 150)),
        ) {
            LabeledCard(label = stringResource(R.string.playback_normalization_target)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(text = stringResource(R.string.playback_normalization_target_value, normalization.targetLufs.toInt()), style = MaterialTheme.typography.labelMedium, color = com.exodidio.tune.ui.theme.LocalTuneColors.current.textMuted)
                    LufsTargetSlider(
                        targetLufs = normalization.targetLufs,
                        enabled = true,
                        onTargetChanged = { onNormalizationChanged(normalization.copy(targetLufs = it)) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SongTransitionContent(
    crossfadeSeconds: Int,
    lastEnabledCrossfadeSeconds: Int,
    onCrossfadeSecondsChanged: (Int) -> Unit,
    blendArtworkDuringCrossfade: Boolean,
    onBlendArtworkDuringCrossfadeChanged: (Boolean) -> Unit,
    gaplessPlayback: Boolean = true,
    onGaplessPlaybackChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val enabled = crossfadeSeconds > 0
    val displayedSeconds = if (enabled) crossfadeSeconds else lastEnabledCrossfadeSeconds
    val crossfadeLabel = stringResource(R.string.playback_crossfade)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.exodidio.tune.ui.components.Card {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.playback_gapless,
                        subtitle = stringResource(R.string.playback_gapless_desc),
                        trailingContent = {
                            TuneSwitch(
                                checked = gaplessPlayback,
                                onCheckedChange = onGaplessPlaybackChanged,
                            )
                        },
                        onClick = { onGaplessPlaybackChanged(!gaplessPlayback) },
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_crossfade,
                        trailingContent = {
                            TuneSwitch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    onCrossfadeSecondsChanged(if (checked) lastEnabledCrossfadeSeconds else 0)
                                },
                            )
                        },
                        onClick = {
                            onCrossfadeSecondsChanged(if (enabled) 0 else lastEnabledCrossfadeSeconds)
                        },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }
        AnimatedVisibility(
            visible = enabled,
            enter = fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = fadeOut(animationSpec = tween(durationMillis = 150)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledCard(label = stringResource(R.string.playback_crossfade_duration)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.playback_crossfade_duration_value, displayedSeconds),
                            style = MaterialTheme.typography.labelMedium,
                            color = com.exodidio.tune.ui.theme.LocalTuneColors.current.textMuted,
                        )
                        CrossfadeDurationSlider(
                            seconds = displayedSeconds,
                            onSecondsChanged = onCrossfadeSecondsChanged,
                            enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = crossfadeLabel },
                        )
                    }
                }
                com.exodidio.tune.ui.components.Card {
                    ActionList(
                        items = listOf(
                            ActionListItem(
                                labelRes = R.string.playback_blend_artwork_during_crossfade,
                                trailingContent = {
                                    TuneSwitch(
                                        checked = blendArtworkDuringCrossfade,
                                        onCheckedChange = onBlendArtworkDuringCrossfadeChanged,
                                    )
                                },
                                onClick = {
                                    onBlendArtworkDuringCrossfadeChanged(!blendArtworkDuringCrossfade)
                                },
                            ),
                        ),
                        containerStyle = ActionListContainerStyle.Plain,
                    )
                }
            }
        }
    }
}

/** 设置-播放-歌词：弹簧强度、回到焦点延迟、自动进入全屏及档位延迟。 */
@Composable
internal fun LyricsPlaybackSettingsContent(
    springStrength: Float,
    onSpringStrengthChanged: (Float) -> Unit,
    autoFullscreen: Boolean,
    onAutoFullscreenChanged: (Boolean) -> Unit,
    focusReturnDelayMs: Long = com.exodidio.tune.lyrics.DefaultLyricFocusReturnDelayMs,
    onFocusReturnDelayChanged: (Long) -> Unit = {},
    autoFullscreenDelayMs: Long = com.exodidio.tune.lyrics.DefaultLyricAutoFullscreenDelayMs,
    onAutoFullscreenDelayChanged: (Long) -> Unit = {},
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val delayOptions = com.exodidio.tune.lyrics.LyricTimingDelayPresetsMs.map { delayMs ->
        val seconds = (delayMs / 1_000L).toInt()
        SelectionOption(
            value = delayMs,
            label = stringResource(R.string.playback_lyrics_delay_seconds, seconds),
        )
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledCard(label = stringResource(R.string.playback_lyrics_spring_strength)) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = springStrength.roundToInt().toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMuted,
                    )
                }
                TuneTrackSlider(
                    value = springStrength,
                    valueRange = 40f..200f,
                    onValueChange = onSpringStrengthChanged,
                    onValueChangeFinished = {},
                    modifier = Modifier.fillMaxWidth(),
                    trackHeight = 6.dp,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.buttonSecondary,
                    trackAlignment = Alignment.TopCenter,
                )
            }
        }
        com.exodidio.tune.ui.components.Card {
            Selection(
                labelRes = R.string.playback_lyrics_focus_return_delay,
                options = delayOptions,
                selectedValue = com.exodidio.tune.lyrics.normalizeLyricDelayPresetMs(focusReturnDelayMs),
                onValueSelected = onFocusReturnDelayChanged,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.playback_lyrics_auto_fullscreen,
                        trailingContent = {
                            TuneSwitch(
                                checked = autoFullscreen,
                                onCheckedChange = onAutoFullscreenChanged,
                            )
                        },
                        onClick = { onAutoFullscreenChanged(!autoFullscreen) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
            AnimatedVisibility(
                visible = autoFullscreen,
                enter = fadeIn(animationSpec = tween(durationMillis = 200)),
                exit = fadeOut(animationSpec = tween(durationMillis = 150)),
            ) {
                Column {
                    ActionListDivider(style = ActionListDividerStyle.FullWidth)
                    Selection(
                        labelRes = R.string.playback_lyrics_auto_fullscreen_delay,
                        options = delayOptions,
                        selectedValue = com.exodidio.tune.lyrics.normalizeLyricDelayPresetMs(autoFullscreenDelayMs),
                        onValueSelected = onAutoFullscreenDelayChanged,
                        hazeState = hazeState,
                    )
                }
            }
        }
    }
}
