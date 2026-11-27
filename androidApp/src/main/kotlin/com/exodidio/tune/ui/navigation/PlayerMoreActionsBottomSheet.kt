package com.exodidio.tune.ui.navigation

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.exodidio.tune.lyrics.LyricOffsetStore
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AbLoopState
import com.exodidio.tune.player.PlaybackItem
import com.exodidio.tune.player.SleepTimerMode
import com.exodidio.tune.player.SleepTimerState
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.ContextActionMenu
import com.exodidio.tune.ui.components.ContextActionMenuEntry
import com.exodidio.tune.ui.components.LocalNeteaseEntityMenuActions
import com.exodidio.tune.ui.components.LocalSimilarSongsMenuActions
import com.exodidio.tune.ui.components.LyricsControlSection
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.components.trackContextArtists
import com.exodidio.tune.ui.theme.LocalTuneColors
import java.util.Locale

@Composable
internal fun PlayerMoreActionsBottomSheet(
    item: PlaybackItem,
    contextTrack: LibraryTrack?,
    lyrics: String?,
    playbackSpeed: Float,
    abLoopState: AbLoopState,
    sleepTimerState: SleepTimerState,
    moodRadioEligibleTrackIds: Set<String>,
    onSpeedSelected: (Float) -> Unit,
    onSetAbLoopPointA: () -> Unit,
    onSetAbLoopPointB: () -> Unit,
    onFineTuneAbLoop: (isPointA: Boolean, deltaMs: Long) -> Unit = { _, _ -> },
    onClearAbLoop: () -> Unit,
    onSleepTimerMinutes: (Int) -> Unit,
    onSleepTimerAfterTrack: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onExportLrc: (title: String, artist: String, rawLyrics: String?, offsetMs: Long) -> Unit,
    onStartMoodRadio: (String) -> Unit,
    onTrackGoToAlbum: (String) -> Unit,
    onTrackGoToArtist: (String) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit,
    onCloseFullscreenThen: ((() -> Unit) -> Unit),
    onDismiss: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val offsets by LyricOffsetStore.offsets.collectAsStateWithLifecycle()
    val currentOffsetMs = offsets[item.trackId] ?: 0L
    var showCustomSleep by remember { mutableStateOf(false) }
    var customSleepMinutes by remember { mutableIntStateOf(20) }

    val similarMenu = LocalSimilarSongsMenuActions.current
    val neteaseEntityActions = LocalNeteaseEntityMenuActions.current

    TuneBottomSheet(
        title = {
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.artist.isNotBlank()) {
                    Text(
                        text = item.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. 睡眠定时关闭
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val timerStatusText = when (sleepTimerState.mode) {
                    SleepTimerMode.Off -> stringResource(R.string.sleep_timer_off)
                    SleepTimerMode.AfterCurrentTrack -> stringResource(R.string.sleep_timer_after_track)
                    is SleepTimerMode.Countdown -> formatClockMs(sleepTimerState.remainingMs)
                }
                SectionHeaderRow(
                    title = stringResource(R.string.player_sleep_timer),
                    status = timerStatusText,
                    active = sleepTimerState.isActive,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolChip(
                        label = stringResource(R.string.sleep_timer_off),
                        selected = !sleepTimerState.isActive,
                        onClick = {
                            showCustomSleep = false
                            onCancelSleepTimer()
                        },
                    )
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        val mode = sleepTimerState.mode
                        val selected = mode is SleepTimerMode.Countdown &&
                            mode.totalDurationMs == minutes * 60_000L
                        ToolChip(
                            label = stringResource(R.string.sleep_timer_minutes, minutes),
                            selected = selected,
                            onClick = {
                                showCustomSleep = false
                                onSleepTimerMinutes(minutes)
                            },
                        )
                    }
                    ToolChip(
                        label = stringResource(R.string.sleep_timer_after_track),
                        selected = sleepTimerState.afterCurrentTrack,
                        onClick = {
                            showCustomSleep = false
                            onSleepTimerAfterTrack()
                        },
                    )
                    ToolChip(
                        label = stringResource(R.string.sleep_timer_custom),
                        selected = showCustomSleep,
                        onClick = { showCustomSleep = !showCustomSleep },
                    )
                }
                if (showCustomSleep) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolChip(
                            label = "-5m",
                            selected = false,
                            onClick = { customSleepMinutes = (customSleepMinutes - 5).coerceIn(5, 180) },
                        )
                        Text(
                            text = stringResource(R.string.sleep_timer_minutes, customSleepMinutes),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        ToolChip(
                            label = "+5m",
                            selected = false,
                            onClick = { customSleepMinutes = (customSleepMinutes + 5).coerceIn(5, 180) },
                        )
                        Spacer(Modifier.weight(1f))
                        ToolChip(
                            label = stringResource(R.string.ok),
                            selected = true,
                            onClick = {
                                showCustomSleep = false
                                onSleepTimerMinutes(customSleepMinutes)
                            },
                        )
                    }
                }
            }

            // 2. 播放倍速
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionHeaderRow(
                    title = stringResource(R.string.player_speed_title),
                    status = String.format(Locale.US, "%.2gx", playbackSpeed),
                    active = kotlin.math.abs(playbackSpeed - 1f) >= 0.01f,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolChip(
                        label = "-0.1x",
                        selected = false,
                        onClick = { onSpeedSelected(((playbackSpeed * 10f).toInt() - 1) / 10f) },
                    )
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        val selected = kotlin.math.abs(playbackSpeed - speed) < 0.02f
                        ToolChip(
                            label = String.format(Locale.US, "%.2gx", speed),
                            selected = selected,
                            onClick = { onSpeedSelected(speed) },
                        )
                    }
                    ToolChip(
                        label = "+0.1x",
                        selected = false,
                        onClick = { onSpeedSelected(((playbackSpeed * 10f).toInt() + 1) / 10f) },
                    )
                }
            }

            // 3. A-B 区间复读
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val abForThisTrack = abLoopState.trackId == item.trackId
                val abStatus = when {
                    abForThisTrack && abLoopState.isActive ->
                        "A ${formatClockMs(abLoopState.pointA!!)} → B ${formatClockMs(abLoopState.pointB!!)}"
                    abForThisTrack && abLoopState.pointA != null ->
                        "A ${formatClockMs(abLoopState.pointA)} → B --:--"
                    else -> stringResource(R.string.player_ab_loop_idle)
                }
                SectionHeaderRow(
                    title = stringResource(R.string.player_ab_loop_title),
                    status = abStatus,
                    active = abForThisTrack && abLoopState.isActive,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip(
                        label = stringResource(R.string.player_ab_set_a),
                        selected = abForThisTrack && abLoopState.pointA != null,
                        onClick = onSetAbLoopPointA,
                    )
                    ToolChip(
                        label = stringResource(R.string.player_ab_set_b),
                        selected = abForThisTrack && abLoopState.pointB != null,
                        onClick = onSetAbLoopPointB,
                    )
                    ToolChip(
                        label = stringResource(R.string.player_ab_clear),
                        selected = false,
                        enabled = abForThisTrack && (abLoopState.hasPointA || abLoopState.hasPointB),
                        onClick = onClearAbLoop,
                    )
                }
                if (abForThisTrack && (abLoopState.hasPointA || abLoopState.hasPointB)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (abLoopState.hasPointA) {
                            ToolChip(
                                label = stringResource(R.string.player_ab_fine_a_minus),
                                selected = false,
                                onClick = { onFineTuneAbLoop(true, -500L) },
                            )
                            ToolChip(
                                label = stringResource(R.string.player_ab_fine_a_plus),
                                selected = false,
                                onClick = { onFineTuneAbLoop(true, 500L) },
                            )
                        }
                        if (abLoopState.hasPointB) {
                            ToolChip(
                                label = stringResource(R.string.player_ab_fine_b_minus),
                                selected = false,
                                onClick = { onFineTuneAbLoop(false, -500L) },
                            )
                            ToolChip(
                                label = stringResource(R.string.player_ab_fine_b_plus),
                                selected = false,
                                onClick = { onFineTuneAbLoop(false, 500L) },
                            )
                        }
                    }
                }
            }

            // 4. 歌词时间轴微调 & 导出 LRC
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val offsetLabel = String.format(Locale.US, "%+.1fs", currentOffsetMs / 1_000f)
                SectionHeaderRow(
                    title = stringResource(R.string.player_lyric_offset_title),
                    status = if (currentOffsetMs == 0L) "0.0s" else offsetLabel,
                    active = currentOffsetMs != 0L,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolChip("-0.5s", selected = false) {
                        LyricOffsetStore.nudgeOffset(context, item.trackId, -500L)
                    }
                    ToolChip("-0.2s", selected = false) {
                        LyricOffsetStore.nudgeOffset(context, item.trackId, -200L)
                    }
                    ToolChip(
                        label = stringResource(R.string.player_lyric_offset_reset),
                        selected = false,
                        enabled = currentOffsetMs != 0L,
                    ) {
                        LyricOffsetStore.resetOffset(context, item.trackId)
                    }
                    ToolChip("+0.2s", selected = false) {
                        LyricOffsetStore.nudgeOffset(context, item.trackId, 200L)
                    }
                    ToolChip("+0.5s", selected = false) {
                        LyricOffsetStore.nudgeOffset(context, item.trackId, 500L)
                    }
                    ToolChip(
                        label = stringResource(R.string.player_export_lrc),
                        selected = false,
                        enabled = !lyrics.isNullOrBlank(),
                    ) {
                        onDismiss()
                        onExportLrc(item.title, item.artist, lyrics, currentOffsetMs)
                    }
                }
            }

            val controlTrack = remember(item, contextTrack) {
                contextTrack ?: LibraryTrack(
                    id = item.trackId,
                    title = item.title,
                    artists = item.artist,
                    album = item.album,
                    albumId = "",
                    metadataJson = "{}",
                )
            }

            // 5. 歌词源切换 & 自动对齐状态/开关
            LyricsControlSection(
                track = controlTrack,
                compact = true,
            )

            HorizontalDivider(
                color = colors.borderGlass,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // 6. 曲目菜单动作
            val entries = buildList {
                add(
                    ContextActionMenuEntry.Action(
                        label = stringResource(R.string.track_context_comments),
                        symbol = MaterialSymbols.Chat,
                    ) {
                        onDismiss()
                        onTrackContextBottomSheet(TrackContextBottomSheetRequest.Comments(controlTrack))
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        label = stringResource(R.string.track_context_add_to_playlist),
                        symbol = MaterialSymbols.PlaylistAdd,
                    ) {
                        onDismiss()
                        onTrackContextBottomSheet(
                            TrackContextBottomSheetRequest.Playlist(
                                trackIds = listOf(item.trackId),
                                allowNeteasePlaylists = true,
                            ),
                        )
                    },
                )
                if (contextTrack != null) {
                    add(
                        ContextActionMenuEntry.Action(
                            label = stringResource(R.string.track_context_find_lyrics),
                            symbol = MaterialSymbols.Search,
                        ) {
                            onDismiss()
                            onTrackContextBottomSheet(TrackContextBottomSheetRequest.FindLyrics(contextTrack))
                        },
                    )
                }
                if (similarMenu.available && similarMenu.isAvailable(item.trackId)) {
                    add(
                        ContextActionMenuEntry.Action(
                            label = stringResource(R.string.track_context_similar_songs),
                            symbol = MaterialSymbols.Sensors,
                        ) {
                            onDismiss()
                            onCloseFullscreenThen { similarMenu.onOpen(item.trackId) }
                        },
                    )
                }
                if (item.trackId in moodRadioEligibleTrackIds) {
                    add(
                        ContextActionMenuEntry.Action(
                            label = stringResource(R.string.track_context_start_mood_radio),
                            symbol = MaterialSymbols.Sensors,
                        ) {
                            onDismiss()
                            onStartMoodRadio(item.trackId)
                        },
                    )
                }
                if (contextTrack != null) {
                    val neteaseAlbumId = NeteaseRepository.neteaseAlbumIdForTrack(contextTrack)
                    val neteaseArtistId = NeteaseRepository.neteaseArtistIdForTrack(contextTrack)
                    val artists = trackContextArtists(contextTrack)
                    if (contextTrack.albumId.isNotBlank() && contextTrack.album.isNotBlank()) {
                        add(
                            ContextActionMenuEntry.Action(
                                label = stringResource(R.string.track_context_go_to_album),
                                symbol = MaterialSymbols.Album,
                            ) {
                                onDismiss()
                                onCloseFullscreenThen {
                                    if (neteaseAlbumId != null) {
                                        neteaseEntityActions.onOpenAlbum(
                                            neteaseAlbumId,
                                            contextTrack.album,
                                            NeteaseRepository.coverUrlForTrack(contextTrack),
                                        )
                                    } else {
                                        onTrackGoToAlbum(contextTrack.albumId)
                                    }
                                }
                            },
                        )
                    }
                    if (neteaseArtistId != null) {
                        val artistName = artists.firstOrNull()?.name ?: contextTrack.artists
                        add(
                            ContextActionMenuEntry.Action(
                                label = stringResource(R.string.track_context_go_to_artist, artistName),
                                symbol = MaterialSymbols.Person,
                            ) {
                                onDismiss()
                                onCloseFullscreenThen {
                                    neteaseEntityActions.onOpenArtist(neteaseArtistId, artistName)
                                }
                            },
                        )
                    } else if (artists.size == 1) {
                        add(
                            ContextActionMenuEntry.Action(
                                label = stringResource(R.string.track_context_go_to_artist, artists.single().name),
                                symbol = MaterialSymbols.Person,
                            ) {
                                onDismiss()
                                onCloseFullscreenThen { onTrackGoToArtist(artists.single().id) }
                            },
                        )
                    } else if (artists.size > 1) {
                        add(
                            ContextActionMenuEntry.Action(
                                label = stringResource(R.string.track_context_go_to_artists),
                                symbol = MaterialSymbols.People,
                            ) {
                                onDismiss()
                                onTrackContextBottomSheet(TrackContextBottomSheetRequest.Artists(artists))
                            },
                        )
                    }
                    add(
                        ContextActionMenuEntry.Action(
                            label = stringResource(R.string.track_context_track_info),
                            symbol = MaterialSymbols.Info,
                        ) {
                            onDismiss()
                            onTrackContextBottomSheet(TrackContextBottomSheetRequest.Info(contextTrack))
                        },
                    )
                }
            }
            ContextActionMenu(entries)
        }
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    status: String,
    active: Boolean,
) {
    val colors = LocalTuneColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) colors.primary else colors.textMuted,
        )
    }
}

@Composable
private fun ToolChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
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
                !enabled -> colors.textMuted.copy(alpha = 0.45f)
                selected -> colors.onPrimary
                else -> colors.textMain
            },
            maxLines = 1,
        )
    }
}

private fun formatClockMs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
