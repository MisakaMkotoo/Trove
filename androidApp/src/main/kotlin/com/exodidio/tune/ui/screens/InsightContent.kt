package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.DiscCard
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackRow
import com.exodidio.tune.ui.components.formatFileSize
import com.exodidio.tune.ui.theme.LocalTuneColors

@Composable
internal fun InsightContent(
    state: InsightUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onLibraryPeriodSelected: (InsightPeriod) -> Unit,
    onListeningPeriodSelected: (InsightPeriod) -> Unit,
    onArtistClick: (String) -> Unit,
    onTrackClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReportYearSelected: (Int) -> Unit = {},
    onFootprintClick: (String) -> Unit = onTrackClick,
) {
    var expandedTracks by rememberSaveable { mutableStateOf(false) }
    var expandedFootprints by rememberSaveable { mutableStateOf(false) }
    val visibleTracks = if (expandedTracks) state.listening.topTracks else state.listening.topTracks.take(5)
    LazyColumn(
        modifier = modifier.testTag("insight-page"),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(R.string.insight_library_section, Modifier.weight(1f))
                InsightPeriodSelector(state.libraryPeriod, onLibraryPeriodSelected, Modifier.testTag("insight-library-period"))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    R.string.insight_library_size,
                    formatFileSize(state.library.bytes),
                    Modifier.fillMaxWidth().testTag("insight-library-size"),
                )
                MetricPair(
                    R.string.insight_total_tracks to number(state.library.tracks),
                    R.string.insight_total_albums to number(state.library.albums),
                )
                MetricPair(
                    R.string.insight_total_artists to number(state.library.artists),
                    R.string.insight_total_playlists to number(state.library.playlists),
                    secondModifier = Modifier.testTag("insight-playlists"),
                )
            }
        }
        item {
            ResponsivePair(
                first = {
                    ChartCard(R.string.insight_library_growth, MaterialSymbols.LegendToggle) {
                        if (state.library.tracks > 0) {
                            InsightLineChart(state.library.growth, stringResource(R.string.insight_library_growth_description))
                        } else EmptyInsightText(R.string.insight_no_library_data)
                    }
                },
                second = { QualityCard(state.library.quality) },
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(R.string.insight_listening_section, Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InsightPeriodSelector(state.listeningPeriod, onListeningPeriodSelected, Modifier.testTag("insight-listening-period"))
                }
            }
        }
        item { ListeningHero(state.listening) }
        item {
            MetricPair(
                R.string.insight_streak to stringResource(R.string.insight_days_value, state.listening.streakDays),
                R.string.insight_average_session to formatDuration(state.listening.averageSessionSeconds),
            )
        }
        item {
            ResponsivePair(
                first = { GenreCard(state.listening.genres) },
                second = { OutcomeCard(state.listening) },
            )
        }
        item {
            ChartCard(
                R.string.insight_top_artists,
                MaterialSymbols.People,
                contentPadding = PaddingValues(vertical = 20.dp),
                headerPadding = PaddingValues(horizontal = 20.dp),
            ) {
                if (state.listening.topArtists.isEmpty()) EmptyInsightText(R.string.insight_no_top_artists, Modifier.padding(horizontal = 20.dp))
                else LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.listening.topArtists, key = { it.id }, contentType = { "insight_artist" }) { artist ->
                        DiscCard(
                            title = artist.name,
                            subtitle = formatDuration(artist.listenedSeconds),
                            artworkPath = artist.artworkPath,
                            artworkShape = CircleShape,
                            modifier = Modifier.size(112.dp, 168.dp),
                            fallbackSymbol = MaterialSymbols.People,
                            onClick = { onArtistClick(artist.id) },
                        )
                    }
                }
            }
        }
        item {
            ChartCard(R.string.insight_top_tracks, MaterialSymbols.MusicNote) {
                if (visibleTracks.isEmpty()) EmptyInsightText(R.string.insight_no_top_tracks)
                else {
                    visibleTracks.forEachIndexed { index, item ->
                        TrackRow(
                            title = item.track.title,
                            artist = item.track.artists,
                            artworkPath = item.track.artworkPath,
                            contentPadding = PaddingValues(vertical = 6.dp),
                            onClick = { onTrackClick(item.track.id) },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(formatDuration(item.listenedSeconds), style = MaterialTheme.typography.labelMedium, color = LocalTuneColors.current.textMuted)
                                    Text(stringResource(R.string.insight_plays_value, item.playCount), style = MaterialTheme.typography.labelSmall, color = LocalTuneColors.current.textMuted)
                                }
                            },
                        )
                        if (index < visibleTracks.lastIndex) {
                            HorizontalDivider(
                                color = LocalTuneColors.current.borderGlass,
                                thickness = 1.dp,
                                modifier = Modifier.testTag("insight-track-divider"),
                            )
                        }
                    }
                    if (state.listening.topTracks.size > 5) {
                        Text(
                            text = stringResource(if (expandedTracks) R.string.insight_show_less else R.string.insight_show_more),
                            color = LocalTuneColors.current.textMuted,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .align(Alignment.End)
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { expandedTracks = !expandedTracks }
                                .padding(horizontal = 12.dp, vertical = 15.dp)
                                .testTag("insight-top-tracks-toggle"),
                        )
                    }
                }
            }
        }
        item {
            AnnualReportCard(
                report = state.annualReport,
                onYearSelected = onReportYearSelected,
                onTrackClick = onFootprintClick,
            )
        }
        item {
            HeatmapCard(heatmap = state.heatmap)
        }
        item {
            FootprintsCard(
                groups = state.footprintGroups,
                totalCount = state.totalFootprintsCount,
                expanded = expandedFootprints,
                onToggleExpanded = { expandedFootprints = !expandedFootprints },
                onFootprintClick = onFootprintClick,
            )
        }
    }
}

@Composable
private fun ListeningHero(state: ListeningInsightState) {
    ChartCard(R.string.insight_total_time, MaterialSymbols.MusicNote) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                Text(formatDuration(state.listenedSeconds), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = LocalTuneColors.current.textMain)
                state.changePercent?.let {
                    Text(stringResource(R.string.insight_change_value, it), style = MaterialTheme.typography.labelMedium, color = LocalTuneColors.current.textMuted)
                }
            }
            Text(stringResource(R.string.insight_plays_value, state.plays), style = MaterialTheme.typography.labelLarge, color = LocalTuneColors.current.textMuted)
        }
        if (state.listenedSeconds > 0) {
            InsightBarChart(state.activity, stringResource(R.string.insight_activity_description), Modifier.padding(top = 20.dp))
        } else EmptyInsightText(R.string.insight_no_listening_data)
    }
}

@Composable
private fun QualityCard(values: List<InsightQuality>) {
    ChartCard(R.string.insight_audio_quality, MaterialSymbols.GraphicEq) {
        if (values.isEmpty()) EmptyInsightText(R.string.insight_no_audio_quality)
        else {
            InsightDonut(values.map { it.count to qualityColor(it.quality) }, number(values.sumOf { it.count }), stringResource(R.string.insight_audio_quality_description))
            values.forEach { BreakdownRow(qualityLabel(it.quality), it.count, values.sumOf { value -> value.count }, qualityColor(it.quality)) }
        }
    }
}

@Composable
private fun GenreCard(values: List<InsightBreakdown>) {
    ChartCard(R.string.insight_genre_distribution, MaterialSymbols.Label) {
        if (values.isEmpty()) EmptyInsightText(R.string.insight_no_genres)
        else {
            val total = values.sumOf { it.listenedSeconds }
            InsightDonut(values.mapIndexed { index, value -> value.listenedSeconds to insightPalette(index) }, formatDuration(total), stringResource(R.string.insight_genre_description))
            values.forEachIndexed { index, value -> BreakdownRow(if (value.isOther) stringResource(R.string.insight_other) else value.name, value.listenedSeconds, total, insightPalette(index), true) }
        }
    }
}

@Composable
private fun OutcomeCard(state: ListeningInsightState) {
    val values = listOf(
        stringResource(R.string.insight_completed) to state.completed,
        stringResource(R.string.insight_skipped) to state.skipped,
        stringResource(R.string.insight_stopped) to state.stopped,
    ).sortedByDescending { it.second }
    val colors = LocalTuneColors.current
    val palette = listOf(colors.primary, colors.qualityHiRes, colors.textMuted)
    val total = values.sumOf { it.second }
    ChartCard(R.string.insight_playback_outcomes, MaterialSymbols.MusicNote) {
        if (total == 0) EmptyInsightText(R.string.insight_no_outcomes)
        else {
            InsightDonut(values.mapIndexed { index, value -> value.second to palette[index] }, stringResource(R.string.insight_attempts_value, total), stringResource(R.string.insight_outcomes_description))
            values.forEachIndexed { index, value -> BreakdownRow(value.first, value.second, total, palette[index]) }
        }
    }
}

@Composable
private fun AnnualReportCard(
    report: InsightAnnualReportState,
    onYearSelected: (Int) -> Unit,
    onTrackClick: (String) -> Unit,
) {
    val colors = LocalTuneColors.current
    ChartCard(R.string.insight_annual_report, MaterialSymbols.Crown) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(report.availableYears) { year ->
                    val selected = year == report.year
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) colors.primary.copy(alpha = 0.18f) else colors.glassElevated)
                            .border(
                                1.dp,
                                if (selected) colors.primary else colors.borderGlass,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable(role = Role.Button) { onYearSelected(year) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = year.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) colors.primary else colors.textMuted,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.glassElevated)
                    .border(0.5.dp, colors.borderGlass, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.insight_local_only_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        if (report.listenedSeconds == 0 && report.totalPlays == 0) {
            EmptyInsightText(R.string.insight_no_annual_data)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnnualMiniStat(
                        label = stringResource(R.string.insight_total_time),
                        value = formatDuration(report.listenedSeconds),
                        modifier = Modifier.weight(1f),
                    )
                    AnnualMiniStat(
                        label = stringResource(R.string.insight_streak),
                        value = stringResource(R.string.insight_days_value, report.activeDays),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnnualMiniStat(
                        label = stringResource(R.string.insight_listening_section),
                        value = stringResource(R.string.insight_annual_tracks_plays, report.uniqueTracks, report.totalPlays),
                        modifier = Modifier.weight(1f),
                    )
                    val peakSlotText = when (report.peakTimeSlot) {
                        ListeningTimeSlot.Morning -> stringResource(R.string.insight_slot_morning, report.peakHour ?: 9)
                        ListeningTimeSlot.Afternoon -> stringResource(R.string.insight_slot_afternoon, report.peakHour ?: 14)
                        ListeningTimeSlot.Evening -> stringResource(R.string.insight_slot_evening, report.peakHour ?: 20)
                        ListeningTimeSlot.LateNight -> stringResource(R.string.insight_slot_late_night, report.peakHour ?: 1)
                        null -> "--"
                    }
                    AnnualMiniStat(
                        label = stringResource(R.string.insight_annual_peak_slot),
                        value = peakSlotText,
                        modifier = Modifier.weight(1f),
                    )
                }
                report.topTrack?.let { topTrack ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.primary.copy(alpha = 0.08f))
                            .border(0.5.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onTrackClick(topTrack.track.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringResource(R.string.insight_annual_top_track),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                            )
                            Text(
                                text = "${topTrack.track.title} — ${topTrack.track.artists}".trimEnd(' ', '—'),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stringResource(R.string.insight_plays_value, topTrack.playCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                        )
                    }
                }
                report.topArtist?.let { topArtist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.glassElevated)
                            .border(0.5.dp, colors.borderGlass, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringResource(R.string.insight_annual_top_artist),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                            Text(
                                text = topArtist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = formatDuration(topArtist.listenedSeconds),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnualMiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.glassElevated)
            .border(0.5.dp, colors.borderGlass, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HeatmapCard(heatmap: InsightHeatmapState) {
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    ChartCard(R.string.insight_heatmap_title, MaterialSymbols.BarChart) {
        InsightHeatmapGrid(
            state = heatmap,
            selectedDate = selectedDate,
            onSelectDate = { tapped -> selectedDate = if (selectedDate == tapped) null else tapped },
            description = stringResource(R.string.insight_heatmap_description),
        )
    }
}

@Composable
private fun FootprintsCard(
    groups: List<InsightFootprintDayGroup>,
    totalCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onFootprintClick: (String) -> Unit,
) {
    val colors = LocalTuneColors.current
    val todayText = remember { LocalDate.now().toString() }
    val yesterdayText = remember { LocalDate.now().minusDays(1).toString() }
    val maxEntries = if (expanded) 50 else 8
    ChartCard(R.string.insight_footprints_title, MaterialSymbols.Schedule) {
        if (groups.isEmpty()) {
            EmptyInsightText(R.string.insight_no_footprints)
        } else {
            Text(
                text = stringResource(R.string.insight_footprints_count, totalCount),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            var rendered = 0
            val totalBuilt = groups.sumOf { it.entries.size }
            groups.forEach { group ->
                if (rendered >= maxEntries) return@forEach
                val remaining = maxEntries - rendered
                val visibleEntries = group.entries.take(remaining)
                if (visibleEntries.isEmpty()) return@forEach
                val dateLabel = when (group.date) {
                    todayText -> stringResource(R.string.insight_footprints_today)
                    yesterdayText -> stringResource(R.string.insight_footprints_yesterday)
                    else -> group.date
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textMain,
                    )
                    Text(
                        text = stringResource(
                            R.string.insight_footprints_day_summary,
                            group.playCount,
                            formatDuration(group.totalListenedSeconds),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
                visibleEntries.forEachIndexed { index, entry ->
                    TrackRow(
                        title = entry.title,
                        artist = entry.artists,
                        artworkPath = entry.artworkPath,
                        artworkUrl = entry.coverUrl.takeIf { it.isNotBlank() },
                        contentPadding = PaddingValues(vertical = 6.dp),
                        onClick = { onFootprintClick(entry.trackId) },
                        trailingContent = {
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.widthIn(min = 56.dp),
                            ) {
                                Text(
                                    text = "${entry.timeLabel} · ${formatDuration(entry.listenedSeconds)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textMain,
                                )
                                Text(
                                    text = stringResource(R.string.insight_footprints_play_count, entry.trackPlayCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textMuted,
                                )
                            }
                        },
                    )
                    if (index < visibleEntries.lastIndex) {
                        HorizontalDivider(
                            color = colors.borderGlass,
                            thickness = 1.dp,
                            modifier = Modifier.testTag("insight-footprint-divider"),
                        )
                    }
                    rendered++
                }
            }
            if (totalBuilt > 8) {
                Text(
                    text = stringResource(if (expanded) R.string.insight_footprints_show_less else R.string.insight_footprints_show_more),
                    color = colors.textMuted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onToggleExpanded() }
                        .padding(horizontal = 12.dp, vertical = 15.dp)
                        .testTag("insight-footprints-toggle"),
                )
            }
        }
    }
}
