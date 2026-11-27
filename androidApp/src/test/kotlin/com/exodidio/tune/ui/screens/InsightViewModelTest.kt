package com.exodidio.tune.ui.screens

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import com.exodidio.tune.player.DailyPlaybackAttemptStat
import com.exodidio.tune.player.DailyTrackListeningStat
import com.exodidio.tune.player.PlaybackEndReason
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.LibraryAlbum
import com.exodidio.tune.sync.LibraryArtist
import com.exodidio.tune.sync.LibraryPlaylist
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.TrackAudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightViewModelTest {
    private val tracks = listOf(
        track("one", "Alpha", "2026-01-02T00:00:00Z", "flac", 24, "artist-a", "Artist A", listOf("Rock", "Pop", "Soul", "Jazz", "Folk", "Metal"), 1_000),
        track("two", "Beta", "2026-01-09T00:00:00Z", "mp3", 0, "artist-b", "Artist B", listOf("Jazz"), 2_000),
        track("three", "Gamma", "2025-06-01T00:00:00Z", "unknown", 0, "artist-c", "Artist C", listOf("Ambient"), 3_000),
    )
    private val bundle = LibraryBundle(
        tracks,
        listOf(LibraryArtist("artist-a", "Artist A"), LibraryArtist("artist-b", "Artist B"), LibraryArtist("artist-c", "Artist C")),
        listOf(LibraryAlbum("album-a", "Album A"), LibraryAlbum("album-b", "Album B")),
        listOf(LibraryPlaylist(id = "favorites", name = "Favorites", metadataJson = "{}"), LibraryPlaylist(id = "mix", name = "Mix", metadataJson = "{}")),
    )
    private val raw = InsightRawData(
        library = bundle,
        dailyTracks = listOf(
            DailyTrackListeningStat("phone", "2026-01-10", "one", 600, 3),
            DailyTrackListeningStat("desktop", "2026-01-09", "two", 300, 2),
            DailyTrackListeningStat("other", "2026-01-08", "three", 60, 1),
            DailyTrackListeningStat("phone", "2026-01-03", "one", 300, 1),
        ),
        dailyAttempts = listOf(
            DailyPlaybackAttemptStat("phone", "2026-01-10", 2, 1, 1, 0, 500),
            DailyPlaybackAttemptStat("desktop", "2026-01-09", 1, 0, 0, 1, 200),
        ),
    )

    @Test
    fun mirrorsDesktopListeningCalculationsAndRankings() {
        val state = buildInsightUiState(raw, InsightPeriod.SevenDays, InsightPeriod.SevenDays, LocalDate.parse("2026-01-10"))

        assertEquals(960, state.listening.listenedSeconds)
        assertEquals(6, state.listening.plays)
        assertEquals(3, state.listening.streakDays)
        assertEquals(233, state.listening.averageSessionSeconds)
        assertEquals(220.0, state.listening.changePercent!!, 0.01)
        assertEquals(listOf("one", "two", "three"), state.listening.topTracks.map { it.track.id })
        assertEquals("Artist A", state.listening.topArtists.first().name)
        assertTrue(state.listening.genres.last().isOther)
        assertEquals(7, state.listening.activity.size)
    }

    @Test
    fun libraryProjectionUsesMirroredSnapshot() {
        val state = buildInsightUiState(raw, InsightPeriod.SevenDays, InsightPeriod.SevenDays, LocalDate.parse("2026-01-10"))

        assertEquals(3, state.library.tracks)
        assertEquals(2, state.library.albums)
        assertEquals(1, state.library.playlists)
        assertEquals(6_000, state.library.bytes)
        assertEquals(3, state.library.growth.last().value)
        assertEquals(1, state.library.quality.first { it.quality == TrackAudioQuality.HiRes }.count)
        assertEquals(1, state.library.quality.first { it.quality == TrackAudioQuality.Lossy }.count)
    }

    @Test
    fun longerListeningRangesKeepTheActivityData() {
        listOf(InsightPeriod.ThirtyDays, InsightPeriod.All).forEach { period ->
            val state = buildInsightUiState(raw, InsightPeriod.SevenDays, period, LocalDate.parse("2026-01-10"))

            assertTrue(state.listening.listenedSeconds > 0)
            assertTrue(state.listening.activity.any { it.value > 0 })
        }
    }

    @Test
    fun buildsLocalHeatmapAnnualReportAndChronologicalFootprints() {
        val ts2026Morning = LocalDateTime.of(2026, 1, 10, 9, 15).toInstant(ZoneOffset.UTC).toEpochMilli()
        val ts2026Night = LocalDateTime.of(2026, 1, 10, 21, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val ts2025Evening = LocalDateTime.of(2025, 11, 20, 20, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        val rawWithFootprints = raw.copy(
            footprints = listOf(
                AndroidLibrarySyncStore.ListeningFootprintRecord(
                    id = "fp-1",
                    trackId = "one",
                    title = "Alpha",
                    artists = "Artist A",
                    startedAt = ts2026Morning,
                    endedAt = ts2026Morning + 180_000,
                    listenedSeconds = 180,
                    endReason = PlaybackEndReason.COMPLETED,
                ),
                AndroidLibrarySyncStore.ListeningFootprintRecord(
                    id = "fp-2",
                    trackId = "one",
                    title = "Alpha",
                    artists = "Artist A",
                    startedAt = ts2026Night,
                    endedAt = ts2026Night + 420_000,
                    listenedSeconds = 420,
                    endReason = PlaybackEndReason.COMPLETED,
                ),
                AndroidLibrarySyncStore.ListeningFootprintRecord(
                    id = "fp-3",
                    trackId = "three",
                    title = "Gamma",
                    artists = "Artist C",
                    startedAt = ts2025Evening,
                    endedAt = ts2025Evening + 240_000,
                    listenedSeconds = 240,
                    endReason = PlaybackEndReason.SKIPPED,
                ),
            ),
        )

        val state2026 = buildInsightUiState(
            data = rawWithFootprints,
            libraryPeriod = InsightPeriod.SevenDays,
            listeningPeriod = InsightPeriod.SevenDays,
            today = LocalDate.parse("2026-01-10"),
            reportYear = 2026,
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(26 * 7, state2026.heatmap.cells.size)
        assertEquals(5, state2026.heatmap.activeDays)
        assertEquals(600, state2026.heatmap.maxDaySeconds)
        val peakCell = state2026.heatmap.cells.first { it.dateText == "2026-01-10" }
        assertEquals(4, peakCell.level)
        assertEquals(3, peakCell.plays)
        val futureCell = state2026.heatmap.cells.firstOrNull { it.dateText == "2026-01-11" }
        assertEquals(true, futureCell?.inFuture)
        assertEquals(0, futureCell?.level)

        assertEquals(listOf(2026, 2025), state2026.annualReport.availableYears)
        assertEquals(2026, state2026.annualReport.year)
        assertEquals(1260, state2026.annualReport.listenedSeconds)
        assertEquals(4, state2026.annualReport.activeDays)
        assertEquals(7, state2026.annualReport.totalPlays)
        assertEquals(3, state2026.annualReport.uniqueTracks)
        assertEquals("one", state2026.annualReport.topTrack?.track?.id)
        assertEquals("Artist A", state2026.annualReport.topArtist?.name)
        assertEquals(ListeningTimeSlot.Evening, state2026.annualReport.peakTimeSlot)
        assertEquals(21, state2026.annualReport.peakHour)

        assertEquals(3, state2026.totalFootprintsCount)
        assertEquals(2, state2026.footprintGroups.size)
        assertEquals("2026-01-10", state2026.footprintGroups.first().date)
        assertEquals(2, state2026.footprintGroups.first().entries.size)
        assertEquals("fp-2", state2026.footprintGroups.first().entries.first().id)
        assertEquals("21:37", state2026.footprintGroups.first().entries.first().timeLabel)
        assertEquals(4, state2026.footprintGroups.first().entries.first().trackPlayCount)

        val state2025 = buildInsightUiState(
            data = rawWithFootprints,
            libraryPeriod = InsightPeriod.SevenDays,
            listeningPeriod = InsightPeriod.SevenDays,
            today = LocalDate.parse("2026-01-10"),
            reportYear = 2025,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(2025, state2025.annualReport.year)
        assertEquals("three", state2025.annualReport.topTrack?.track?.id)
        assertEquals(ListeningTimeSlot.Evening, state2025.annualReport.peakTimeSlot)
        assertEquals(20, state2025.annualReport.peakHour)
    }

    private fun track(
        id: String,
        title: String,
        createdAt: String,
        format: String,
        bitDepth: Int,
        artistId: String,
        artist: String,
        genres: List<String>,
        fileSize: Long,
    ) = LibraryTrack(
        id = id,
        title = title,
        artists = artist,
        createdAt = createdAt,
        metadataJson = """{"format":"$format","bit_depth":$bitDepth,"file_size":$fileSize,"artists":[{"id":"$artistId","name":"$artist"}],"genres":[${genres.joinToString { "{\"id\":\"$it\",\"name\":\"$it\"}" }}]}""",
    )
}
