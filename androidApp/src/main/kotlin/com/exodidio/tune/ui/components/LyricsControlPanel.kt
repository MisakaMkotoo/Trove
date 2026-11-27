package com.exodidio.tune.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.lyrics.LyricsSettings
import com.exodidio.tune.lyrics.LyricsSourceId
import com.exodidio.tune.lyrics.PerSongLyricsConfig
import com.exodidio.tune.lyrics.autosync.AutoSyncCoordinator
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal data class LyricsMenuActions(
    val lyricsSettings: LyricsSettings = LyricsSettings(),
    val perSongConfigs: Map<String, PerSongLyricsConfig> = emptyMap(),
    val hasAutoSyncModels: Boolean = false,
    val sourceFlowFor: (String) -> Flow<String?> = { flowOf(null) },
    val baseSourceFor: (String) -> String? = { null },
    val lyricsContentFlowFor: (String) -> Flow<String?> = { flowOf(null) },
    val onSelectSource: suspend (trackId: String, source: LyricsSourceId?) -> Boolean = { _, _ -> false },
    val onToggleAutoAlign: (trackId: String, enabled: Boolean) -> Unit = { _, _ -> },
    val onResetPerSong: (trackId: String) -> Unit = {},
)

internal val LocalLyricsMenuActions = staticCompositionLocalOf { LyricsMenuActions() }

@Composable
internal fun LyricsControlSection(
    track: LibraryTrack,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = LocalLyricsMenuActions.current
    val settings = actions.lyricsSettings
    val perSongEnabled = settings.perSongLyricsConfigEnabled
    val perSongConfig = if (perSongEnabled) actions.perSongConfigs[track.id] else null

    val sourceFlow = remember(track.id, actions) { actions.sourceFlowFor(track.id) }
    val rawSource by sourceFlow.collectAsStateWithLifecycle(initialValue = null)
    val contentFlow = remember(track.id, actions) { actions.lyricsContentFlowFor(track.id) }
    val lyricsContent by contentFlow.collectAsStateWithLifecycle(initialValue = null)

    val baseSource = remember(rawSource, track.id, actions) {
        if (rawSource == "autosync") actions.baseSourceFor(track.id) else rawSource
    }
    val hasWordTiming = remember(lyricsContent) {
        !lyricsContent.isNullOrBlank() && AutoSyncCoordinator.hasWordTiming(lyricsContent)
    }
    val effectiveAutoAlign = perSongConfig?.autoAlignOverride ?: settings.autoAlignWordTiming
    val activeSourceId = remember(baseSource) { sourceIdFromRaw(baseSource) }
    val selectedChipSource: LyricsSourceId? = perSongConfig?.preferredSourceId ?: activeSourceId

    var switchingSource by remember(track.id) { mutableStateOf(false) }

    val activeSourceLabel = formatActiveSourceLabel(rawSource, baseSource)
    val alignStatusText = when {
        rawSource == "autosync" -> stringResource(R.string.lyrics_align_status_active)
        hasWordTiming -> stringResource(R.string.lyrics_align_status_word_native)
        !effectiveAutoAlign -> stringResource(R.string.lyrics_align_status_off)
        !actions.hasAutoSyncModels -> stringResource(R.string.lyrics_align_status_no_models)
        else -> stringResource(R.string.lyrics_align_status_waiting)
    }
    val alignStatusActive = rawSource == "autosync" || hasWordTiming

    val isNeteaseTrack = track.id.startsWith("netease:")
    val availableSources = remember(isNeteaseTrack) {
        LyricsSourceId.entries.filter { it != LyricsSourceId.Amll || isNeteaseTrack }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 20.dp else 16.dp, vertical = if (compact) 0.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.lyrics_control_title),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (perSongEnabled) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.lyrics_per_song_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                        )
                    }
                }
                if (perSongConfig != null && !perSongConfig.isEmpty()) {
                    Text(
                        text = stringResource(R.string.lyrics_per_song_reset),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { actions.onResetPerSong(track.id) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // 当前歌词源状态
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.lyrics_source_current, activeSourceLabel),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (switchingSource) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.8.dp)
                    Text(
                        text = stringResource(R.string.lyrics_source_switching),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        }

        // 歌词源快速切换栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val notFoundTemplate = stringResource(R.string.lyrics_source_not_found, "{src}")
            LyricsSourceChip(
                label = stringResource(R.string.lyrics_source_auto),
                selected = perSongConfig?.preferredSourceId == null && activeSourceId == null,
                enabled = !switchingSource,
                onClick = {
                    if (switchingSource) return@LyricsSourceChip
                    switchingSource = true
                    scope.launch {
                        try {
                            actions.onSelectSource(track.id, null)
                        } finally {
                            switchingSource = false
                        }
                    }
                },
            )
            availableSources.forEach { source ->
                val label = stringResource(sourceLabelRes(source))
                val selected = selectedChipSource == source
                LyricsSourceChip(
                    label = label,
                    selected = selected,
                    enabled = !switchingSource,
                    onClick = {
                        if (switchingSource) return@LyricsSourceChip
                        switchingSource = true
                        scope.launch {
                            try {
                                val ok = actions.onSelectSource(track.id, source)
                                if (!ok) {
                                    Toast.makeText(
                                        context,
                                        notFoundTemplate.replace("{src}", label),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } finally {
                                switchingSource = false
                            }
                        }
                    },
                )
            }
        }

        // 自动对齐状态与开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(12.dp))
                .clickable { actions.onToggleAutoAlign(track.id, !effectiveAutoAlign) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.lyrics_align_status_label),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMain,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = alignStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (alignStatusActive) colors.primary else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TuneSwitch(
                checked = effectiveAutoAlign,
                onCheckedChange = { actions.onToggleAutoAlign(track.id, it) },
            )
        }
    }
}

@Composable
private fun LyricsSourceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) colors.primary else colors.glassElevated)
            .border(
                width = 0.5.dp,
                color = if (selected) colors.primary else colors.borderGlass,
                shape = shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = when {
                !enabled -> colors.textMuted.copy(alpha = 0.5f)
                selected -> colors.onPrimary
                else -> colors.textMain
            },
            maxLines = 1,
        )
    }
}

private fun sourceIdFromRaw(rawSource: String?): LyricsSourceId? {
    val s = rawSource?.lowercase().orEmpty()
    return when {
        s.startsWith("netease") -> LyricsSourceId.Netease
        s.startsWith("amll") -> LyricsSourceId.Amll
        s.startsWith("lrclib") -> LyricsSourceId.Lrclib
        s.startsWith("kugou") -> LyricsSourceId.Kugou
        else -> null
    }
}

private fun sourceLabelRes(source: LyricsSourceId): Int = when (source) {
    LyricsSourceId.Netease -> R.string.lyrics_netease
    LyricsSourceId.Amll -> R.string.lyrics_amll
    LyricsSourceId.Lrclib -> R.string.lyrics_lrclib
    LyricsSourceId.Kugou -> R.string.lyrics_kugou
}

@Composable
private fun formatActiveSourceLabel(rawSource: String?, baseSource: String?): String {
    val base = (baseSource ?: rawSource)?.lowercase().orEmpty()
    val name = when {
        base.isBlank() -> stringResource(R.string.lyrics_source_none)
        base.startsWith("netease") -> stringResource(R.string.lyrics_netease)
        base.startsWith("amll") -> stringResource(R.string.lyrics_amll)
        base.startsWith("lrclib") -> stringResource(R.string.lyrics_lrclib)
        base.startsWith("kugou") -> stringResource(R.string.lyrics_kugou)
        base == "local-lrc" -> stringResource(R.string.lyrics_source_local)
        base == "manual" -> stringResource(R.string.lyrics_source_manual)
        else -> base
    }
    return if (rawSource == "autosync" && baseSource != null) {
        "$name · ${stringResource(R.string.lyrics_align_status_active)}"
    } else {
        name
    }
}
