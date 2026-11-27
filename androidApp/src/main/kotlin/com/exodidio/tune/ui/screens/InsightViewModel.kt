package com.exodidio.tune.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.exodidio.tune.player.DailyPlaybackAttemptStat
import com.exodidio.tune.player.DailyTrackListeningStat
import com.exodidio.tune.player.PlaybackController
import com.exodidio.tune.player.PlaybackEndReason
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.LibraryAlbum
import com.exodidio.tune.sync.LibraryArtist
import com.exodidio.tune.sync.LibraryPlaylist
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.ui.components.TrackAudioQuality
import com.exodidio.tune.ui.components.trackAudioQuality

internal enum class InsightPeriod(val days: Long?) { SevenDays(7), ThirtyDays(30), All(null) }
internal enum class ListeningTimeSlot { Morning, Afternoon, Evening, LateNight }
internal data class InsightPoint(val date: String, val value: Int)
internal data class InsightQuality(val quality: TrackAudioQuality, val count: Int)
internal data class InsightBreakdown(val name: String, val listenedSeconds: Int, val isOther: Boolean = false)
internal data class InsightTopArtist(val id: String, val name: String, val artworkPath: String?, val listenedSeconds: Int)
internal data class InsightTopTrack(val track: LibraryTrack, val playCount: Int, val listenedSeconds: Int)

internal data class InsightHeatmapCell(
    val dateText: String,
    val listenedSeconds: Int,
    val plays: Int,
    val level: Int,
    val inFuture: Boolean = false,
)

internal data class InsightHeatmapState(
    val weeks: Int = 26,
    val cells: List<InsightHeatmapCell> = emptyList(),
    val activeDays: Int = 0,
    val maxDaySeconds: Int = 0,
    val totalPlays: Int = 0,
)

internal data class InsightAnnualReportState(
    val year: Int = 0,
    val availableYears: List<Int> = emptyList(),
    val listenedSeconds: Int = 0,
    val activeDays: Int = 0,
    val totalPlays: Int = 0,
    val uniqueTracks: Int = 0,
    val topTrack: InsightTopTrack? = null,
    val topArtist: InsightTopArtist? = null,
    val peakTimeSlot: ListeningTimeSlot? = null,
    val peakHour: Int? = null,
)

internal data class InsightFootprintEntry(
    val id: String,
    val trackId: String,
    val title: String,
    val artists: String,
    val album: String,
    val artworkPath: String?,
    val coverUrl: String,
    val startedAt: Long,
    val endedAt: Long,
    val listenedSeconds: Int,
    val endReason: PlaybackEndReason,
    val trackPlayCount: Int,
    val timeLabel: String,
)

internal data class InsightFootprintDayGroup(
    val date: String,
    val totalListenedSeconds: Int,
    val playCount: Int,
    val entries: List<InsightFootprintEntry>,
)

internal data class LibraryInsightState(
    val tracks: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val playlists: Int = 0,
    val bytes: Long = 0,
    val growth: List<InsightPoint> = emptyList(),
    val quality: List<InsightQuality> = emptyList(),
)

internal data class ListeningInsightState(
    val listenedSeconds: Int = 0,
    val plays: Int = 0,
    val attempts: Int = 0,
    val completed: Int = 0,
    val skipped: Int = 0,
    val stopped: Int = 0,
    val averageSessionSeconds: Int = 0,
    val streakDays: Int = 0,
    val changePercent: Double? = null,
    val activity: List<InsightPoint> = emptyList(),
    val genres: List<InsightBreakdown> = emptyList(),
    val topArtists: List<InsightTopArtist> = emptyList(),
    val topTracks: List<InsightTopTrack> = emptyList(),
)

internal data class InsightUiState(
    val libraryPeriod: InsightPeriod = InsightPeriod.SevenDays,
    val listeningPeriod: InsightPeriod = InsightPeriod.SevenDays,
    val library: LibraryInsightState = LibraryInsightState(),
    val listening: ListeningInsightState = ListeningInsightState(),
    val heatmap: InsightHeatmapState = InsightHeatmapState(),
    val annualReport: InsightAnnualReportState = InsightAnnualReportState(),
    val footprintGroups: List<InsightFootprintDayGroup> = emptyList(),
    val totalFootprintsCount: Int = 0,
)

internal data class LibraryBundle(
    val tracks: List<LibraryTrack>,
    val artists: List<LibraryArtist>,
    val albums: List<LibraryAlbum>,
    val playlists: List<LibraryPlaylist>,
)

internal data class InsightRawData(
    val library: LibraryBundle,
    val dailyTracks: List<DailyTrackListeningStat>,
    val dailyAttempts: List<DailyPlaybackAttemptStat>,
    val footprints: List<AndroidLibrarySyncStore.ListeningFootprintRecord> = emptyList(),
)

internal class InsightViewModel(
    private val store: AndroidLibrarySyncStore,
    private val playbackController: PlaybackController,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    class Factory(
        private val store: AndroidLibrarySyncStore,
        private val playbackController: PlaybackController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = InsightViewModel(
            store,
            playbackController,
        ) as T
    }

    private val libraryPeriod = MutableStateFlow(InsightPeriod.SevenDays)
    private val listeningPeriod = MutableStateFlow(InsightPeriod.SevenDays)
    private val selectedReportYear = MutableStateFlow(today().year)

    private val library = combine(store.tracks, store.artists, store.albums, store.playlists) { tracks, artists, albums, playlists ->
        LibraryBundle(tracks, artists, albums, playlists)
    }
    private val raw = combine(
        library,
        store.dailyTrackListeningStats,
        store.dailyPlaybackAttemptStats,
        store.listeningFootprints,
    ) { library, tracks, attempts, footprints ->
        InsightRawData(library, tracks, attempts, footprints)
    }

    val uiState = combine(raw, libraryPeriod, listeningPeriod, selectedReportYear) { data, libraryRange, listeningRange, reportYear ->
        buildInsightUiState(data, libraryRange, listeningRange, today(), reportYear)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightUiState())

    fun setLibraryPeriod(value: InsightPeriod) { libraryPeriod.value = value }
    fun setListeningPeriod(value: InsightPeriod) { listeningPeriod.value = value }
    fun setReportYear(year: Int) { selectedReportYear.value = year }

    fun playTopTrack(trackId: String) {
        val tracks = uiState.value.listening.topTracks.map { it.track }
        val index = tracks.indexOfFirst { it.id == trackId }
        if (index >= 0) playbackController.play(PlaybackRequest(tracks.map { it.id }, index))
    }

    fun playFootprintTrack(trackId: String) {
        if (trackId.isBlank()) return
        val entries = uiState.value.footprintGroups.flatMap { it.entries }
        val matched = entries.firstOrNull { it.trackId == trackId }
        if (store.trackForPlayback(trackId) == null && matched != null && trackId.startsWith("netease_")) {
            val coverJson = if (matched.coverUrl.isNotBlank()) {
                val escaped = matched.coverUrl.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"coverUrl":"$escaped"}"""
            } else "{}"
            store.putPlaybackOnlyTrack(
                LibraryTrack(
                    id = matched.trackId,
                    title = matched.title.ifBlank { trackId },
                    artists = matched.artists,
                    album = matched.album,
                    artworkPath = matched.artworkPath,
                    metadataJson = coverJson,
                ),
            )
        }
        val playableIds = entries
            .map { it.trackId }
            .distinct()
            .filter { id -> id == trackId || store.trackForPlayback(id) != null }
        if (playableIds.isEmpty()) return
        val index = playableIds.indexOf(trackId).coerceAtLeast(0)
        playbackController.play(PlaybackRequest(playableIds, index))
    }
}

internal fun buildInsightUiState(
    data: InsightRawData,
    libraryPeriod: InsightPeriod,
    listeningPeriod: InsightPeriod,
    today: LocalDate,
    reportYear: Int = today.year,
    zoneId: ZoneId = ZoneId.systemDefault(),
): InsightUiState {
    val footprints = buildFootprintGroups(data, zoneId)
    return InsightUiState(
        libraryPeriod = libraryPeriod,
        listeningPeriod = listeningPeriod,
        library = libraryInsights(data.library, libraryPeriod, today),
        listening = listeningInsights(data, listeningPeriod, today),
        heatmap = buildHeatmapState(data, today, zoneId),
        annualReport = buildAnnualReportState(data, today, reportYear, zoneId),
        footprintGroups = footprints,
        totalFootprintsCount = data.footprints.size,
    )
}

private fun libraryInsights(bundle: LibraryBundle, period: InsightPeriod, today: LocalDate): LibraryInsightState {
    val datedTracks = bundle.tracks.mapNotNull { track -> track.createdAt.toLocalDateOrNull()?.let { it to track } }
    val growth = if (period == InsightPeriod.All) {
        var total = 0
        datedTracks.groupingBy { it.first.year }.eachCount().toSortedMap().map { (year, count) ->
            total += count
            InsightPoint(year.toString(), total)
        }
    } else {
        val start = today.minusDays(period.days!! - 1)
        var total = datedTracks.count { it.first < start }
        (0 until period.days).map { offset ->
            val date = start.plusDays(offset)
            total += datedTracks.count { it.first == date }
            InsightPoint(date.toString(), total)
        }
    }
    val quality = bundle.tracks.groupingBy(::trackAudioQuality).eachCount().entries
        .sortedByDescending { it.value }
        .map { InsightQuality(it.key, it.value) }
    return LibraryInsightState(
        tracks = bundle.tracks.size,
        albums = bundle.albums.size,
        artists = bundle.artists.size,
        playlists = bundle.playlists.count { it.id != "favorites" },
        bytes = bundle.tracks.sumOf { it.metadataObject().long("file_size") ?: 0L },
        growth = growth,
        quality = quality,
    )
}

private fun listeningInsights(data: InsightRawData, period: InsightPeriod, today: LocalDate): ListeningInsightState {
    val start = period.days?.let { today.minusDays(it - 1) }
    val tracks = data.dailyTracks.filter { start == null || it.localDate >= start.toString() }
    val attempts = data.dailyAttempts.filter { start == null || it.localDate >= start.toString() }
    val listenedSeconds = tracks.sumOf { it.listenedSeconds }
    val plays = tracks.sumOf { it.playCount }
    val endedAttempts = attempts.sumOf { it.completed + it.skipped + it.stopped }
    val previous = start?.let { currentStart ->
        val previousStart = currentStart.minusDays(period.days)
        data.dailyTracks.filter {
            it.localDate >= previousStart.toString() && it.localDate < currentStart.toString()
        }.sumOf { it.listenedSeconds }
    }
    val byDate = tracks.groupBy { it.localDate }.mapValues { (_, rows) -> rows.sumOf { it.listenedSeconds } }
    val activity = if (start == null) {
        byDate.entries.groupBy { it.key.take(7) }.mapValues { (_, rows) -> rows.sumOf { it.value } }
            .toSortedMap().map { InsightPoint(it.key, it.value) }
    } else {
        (0 until period.days).map { offset -> start.plusDays(offset).toString() }.map { InsightPoint(it, byDate[it] ?: 0) }
    }
    val trackStats = tracks.groupBy { it.trackId }.mapValues { (_, rows) ->
        rows.sumOf { it.playCount } to rows.sumOf { it.listenedSeconds }
    }
    val tracksById = data.library.tracks.associateBy { it.id }
    val topTracks = trackStats.mapNotNull { (id, values) -> tracksById[id]?.let { InsightTopTrack(it, values.first, values.second) } }
        .filter { it.playCount > 0 || it.listenedSeconds > 0 }
        .sortedWith(compareByDescending<InsightTopTrack> { it.playCount }.thenByDescending { it.listenedSeconds }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.track.title })
        .take(50)
    val artistArtwork = data.library.artists.associateBy { it.id }
    val artistSeconds = mutableMapOf<Pair<String, String>, Int>()
    val genreSeconds = mutableMapOf<String, Int>()
    trackStats.forEach { (trackId, values) ->
        val metadata = tracksById[trackId]?.metadataObject() ?: return@forEach
        metadata.objects("artists").forEach { artist ->
            val id = artist.text("id") ?: return@forEach
            val name = artist.text("name") ?: return@forEach
            artistSeconds[id to name] = (artistSeconds[id to name] ?: 0) + values.second
        }
        metadata.objects("genres").forEach { genre ->
            val name = genre.text("name") ?: genre.text("title") ?: return@forEach
            genreSeconds[name] = (genreSeconds[name] ?: 0) + values.second
        }
    }
    val topArtists = artistSeconds.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key.second })
        .take(50).map { (key, seconds) -> InsightTopArtist(key.first, key.second, artistArtwork[key.first]?.artworkPath, seconds) }
    val sortedGenres = genreSeconds.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
    val genres = sortedGenres.take(5).map { InsightBreakdown(it.key, it.value) } +
        sortedGenres.drop(5).sumOf { it.value }.takeIf { it > 0 }?.let { listOf(InsightBreakdown("", it, true)) }.orEmpty()
    val activeDates = data.dailyTracks.filter { it.listenedSeconds > 0 && it.localDate <= today.toString() }.mapTo(mutableSetOf()) { it.localDate }
    var cursor = if (today.toString() in activeDates) today else today.minusDays(1)
    var streak = 0
    while (cursor.toString() in activeDates) { streak++; cursor = cursor.minusDays(1) }
    return ListeningInsightState(
        listenedSeconds = listenedSeconds,
        plays = plays,
        attempts = attempts.sumOf { it.attempts },
        completed = attempts.sumOf { it.completed },
        skipped = attempts.sumOf { it.skipped },
        stopped = attempts.sumOf { it.stopped },
        averageSessionSeconds = if (endedAttempts == 0) 0 else attempts.sumOf { it.listenedSeconds } / endedAttempts,
        streakDays = streak,
        changePercent = previous?.takeIf { it > 0 }?.let { (listenedSeconds - it) * 100.0 / it },
        activity = activity,
        genres = genres,
        topArtists = topArtists,
        topTracks = topTracks,
    )
}

private fun buildHeatmapState(
    data: InsightRawData,
    today: LocalDate,
    zoneId: ZoneId,
    weeks: Int = 26,
): InsightHeatmapState {
    val dailySeconds = mutableMapOf<String, Int>()
    val dailyPlays = mutableMapOf<String, Int>()
    data.dailyTracks.forEach { row ->
        dailySeconds[row.localDate] = (dailySeconds[row.localDate] ?: 0) + row.listenedSeconds.coerceAtLeast(0)
        dailyPlays[row.localDate] = (dailyPlays[row.localDate] ?: 0) + row.playCount.coerceAtLeast(0)
    }
    val fpSeconds = mutableMapOf<String, Int>()
    val fpPlays = mutableMapOf<String, Int>()
    data.footprints.forEach { fp ->
        val ts = fp.endedAt.takeIf { it > 0 } ?: fp.startedAt
        if (ts <= 0) return@forEach
        val dateText = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate().toString()
        fpSeconds[dateText] = (fpSeconds[dateText] ?: 0) + fp.listenedSeconds.coerceAtLeast(0)
        fpPlays[dateText] = (fpPlays[dateText] ?: 0) + 1
    }
    val endSunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val totalCells = weeks * 7
    val startMonday = endSunday.minusDays((totalCells - 1).toLong())
    val rawDays = (0 until totalCells).map { offset ->
        val date = startMonday.plusDays(offset.toLong())
        val inFuture = date.isAfter(today)
        val dateText = date.toString()
        val sec = if (inFuture) 0 else maxOf(dailySeconds[dateText] ?: 0, fpSeconds[dateText] ?: 0)
        val plays = if (inFuture) 0 else maxOf(dailyPlays[dateText] ?: 0, fpPlays[dateText] ?: 0)
        Triple(dateText, sec, plays) to inFuture
    }
    val maxDaySeconds = rawDays.maxOfOrNull { it.first.second } ?: 0
    var activeDays = 0
    var totalPlays = 0
    val cells = rawDays.map { (triple, inFuture) ->
        val (dateText, sec, plays) = triple
        val level = when {
            inFuture || (sec <= 0 && plays <= 0) -> 0
            maxDaySeconds <= 0 -> 1
            else -> {
                val ratio = sec.toFloat() / maxDaySeconds.toFloat()
                when {
                    ratio > 0.75f -> 4
                    ratio > 0.48f -> 3
                    ratio > 0.20f -> 2
                    else -> 1
                }
            }
        }
        if (!inFuture && (sec > 0 || plays > 0)) activeDays++
        if (!inFuture) totalPlays += plays
        InsightHeatmapCell(
            dateText = dateText,
            listenedSeconds = sec,
            plays = plays,
            level = level,
            inFuture = inFuture,
        )
    }
    return InsightHeatmapState(
        weeks = weeks,
        cells = cells,
        activeDays = activeDays,
        maxDaySeconds = maxDaySeconds,
        totalPlays = totalPlays,
    )
}

private fun buildAnnualReportState(
    data: InsightRawData,
    today: LocalDate,
    requestedYear: Int,
    zoneId: ZoneId,
): InsightAnnualReportState {
    val years = sortedSetOf<Int>(Comparator.reverseOrder())
    years += today.year
    data.dailyTracks.forEach { row ->
        row.localDate.take(4).toIntOrNull()?.let { years += it }
    }
    data.footprints.forEach { fp ->
        val ts = fp.endedAt.takeIf { it > 0 } ?: fp.startedAt
        if (ts > 0) years += Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate().year
    }
    val availableYears = years.toList()
    val year = if (requestedYear in availableYears) requestedYear else availableYears.firstOrNull() ?: today.year
    val yearPrefix = "$year-"
    val yearDailyTracks = data.dailyTracks.filter { it.localDate.startsWith(yearPrefix) }
    val yearFootprints = data.footprints.filter { fp ->
        val ts = fp.endedAt.takeIf { it > 0 } ?: fp.startedAt
        ts > 0 && Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate().year == year
    }
    val dailySeconds = yearDailyTracks.sumOf { it.listenedSeconds.coerceAtLeast(0) }
    val footprintSeconds = yearFootprints.sumOf { it.listenedSeconds.coerceAtLeast(0) }
    val listenedSeconds = maxOf(dailySeconds, footprintSeconds)

    val activeDateSet = mutableSetOf<String>()
    yearDailyTracks.filter { it.listenedSeconds > 0 || it.playCount > 0 }.forEach { activeDateSet += it.localDate }
    yearFootprints.forEach { fp ->
        val ts = fp.endedAt.takeIf { it > 0 } ?: fp.startedAt
        if (ts > 0) activeDateSet += Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate().toString()
    }

    val tracksById = data.library.tracks.associateBy { it.id }
    val dailyPlaysByTrack = mutableMapOf<String, Int>()
    val dailySecondsByTrack = mutableMapOf<String, Int>()
    yearDailyTracks.forEach { row ->
        dailyPlaysByTrack[row.trackId] = (dailyPlaysByTrack[row.trackId] ?: 0) + row.playCount.coerceAtLeast(0)
        dailySecondsByTrack[row.trackId] = (dailySecondsByTrack[row.trackId] ?: 0) + row.listenedSeconds.coerceAtLeast(0)
    }
    val fpPlaysByTrack = mutableMapOf<String, Int>()
    val fpSecondsByTrack = mutableMapOf<String, Int>()
    val fpMetaByTrack = mutableMapOf<String, AndroidLibrarySyncStore.ListeningFootprintRecord>()
    yearFootprints.forEach { fp ->
        if (fp.trackId.isNotBlank()) {
            fpPlaysByTrack[fp.trackId] = (fpPlaysByTrack[fp.trackId] ?: 0) + 1
            fpSecondsByTrack[fp.trackId] = (fpSecondsByTrack[fp.trackId] ?: 0) + fp.listenedSeconds.coerceAtLeast(0)
            fpMetaByTrack.putIfAbsent(fp.trackId, fp)
        }
    }
    val allTrackIds = (dailyPlaysByTrack.keys + fpPlaysByTrack.keys).distinct()
    val combinedTopTracks = allTrackIds.mapNotNull { id ->
        val plays = maxOf(dailyPlaysByTrack[id] ?: 0, fpPlaysByTrack[id] ?: 0)
        val sec = maxOf(dailySecondsByTrack[id] ?: 0, fpSecondsByTrack[id] ?: 0)
        if (plays <= 0 && sec <= 0) return@mapNotNull null
        val track = tracksById[id] ?: fpMetaByTrack[id]?.let { fp ->
            LibraryTrack(
                id = fp.trackId,
                title = fp.title.ifBlank { fp.trackId },
                artists = fp.artists,
                album = fp.album,
                artworkPath = fp.artworkPath,
            )
        } ?: return@mapNotNull null
        InsightTopTrack(track, plays, sec)
    }.sortedWith(
        compareByDescending<InsightTopTrack> { it.playCount }
            .thenByDescending { it.listenedSeconds }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.track.title },
    )

    val artistArtwork = data.library.artists.associateBy { it.id }
    val artistSeconds = mutableMapOf<Pair<String, String>, Int>()
    combinedTopTracks.forEach { item ->
        val metadata = tracksById[item.track.id]?.metadataObject()
        val metadataArtists = metadata?.objects("artists").orEmpty()
        if (metadataArtists.isNotEmpty()) {
            metadataArtists.forEach { artist ->
                val id = artist.text("id") ?: return@forEach
                val name = artist.text("name") ?: return@forEach
                artistSeconds[id to name] = (artistSeconds[id to name] ?: 0) + item.listenedSeconds
            }
        } else if (item.track.artists.isNotBlank()) {
            val primary = item.track.artists.substringBefore("/").substringBefore(",").trim()
            if (primary.isNotBlank()) {
                artistSeconds["" to primary] = (artistSeconds["" to primary] ?: 0) + item.listenedSeconds
            }
        }
    }
    val topArtist = artistSeconds.entries
        .sortedWith(
            compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key.second },
        )
        .firstOrNull()
        ?.let { (key, seconds) ->
            InsightTopArtist(key.first, key.second, artistArtwork[key.first]?.artworkPath, seconds)
        }

    val hourWeights = IntArray(24)
    yearFootprints.forEach { fp ->
        val ts = fp.startedAt.takeIf { it > 0 } ?: fp.endedAt
        if (ts > 0) {
            val hour = Instant.ofEpochMilli(ts).atZone(zoneId).hour
            hourWeights[hour] += fp.listenedSeconds.coerceAtLeast(1)
        }
    }
    val peakHour = hourWeights.indices.maxByOrNull { hourWeights[it] }?.takeIf { hourWeights[it] > 0 }
    val peakSlot = peakHour?.let { hour ->
        when (hour) {
            in 6..11 -> ListeningTimeSlot.Morning
            in 12..17 -> ListeningTimeSlot.Afternoon
            in 18..23 -> ListeningTimeSlot.Evening
            else -> ListeningTimeSlot.LateNight
        }
    }

    val totalPlays = maxOf(
        yearDailyTracks.sumOf { it.playCount.coerceAtLeast(0) },
        yearFootprints.size,
    )
    return InsightAnnualReportState(
        year = year,
        availableYears = availableYears,
        listenedSeconds = listenedSeconds,
        activeDays = activeDateSet.size,
        totalPlays = totalPlays,
        uniqueTracks = combinedTopTracks.size,
        topTrack = combinedTopTracks.firstOrNull(),
        topArtist = topArtist,
        peakTimeSlot = peakSlot,
        peakHour = peakHour,
    )
}

private fun buildFootprintGroups(
    data: InsightRawData,
    zoneId: ZoneId,
): List<InsightFootprintDayGroup> {
    if (data.footprints.isEmpty()) return emptyList()
    val dailyPlaysByTrack = mutableMapOf<String, Int>()
    data.dailyTracks.forEach { row ->
        dailyPlaysByTrack[row.trackId] = (dailyPlaysByTrack[row.trackId] ?: 0) + row.playCount.coerceAtLeast(0)
    }
    val footprintCountByTrack = data.footprints.groupingBy { it.trackId }.eachCount()
    val tracksById = data.library.tracks.associateBy { it.id }
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    val sorted = data.footprints
        .sortedByDescending { it.endedAt.coerceAtLeast(it.startedAt) }
        .take(200)

    val byDate = linkedMapOf<String, MutableList<InsightFootprintEntry>>()
    sorted.forEach { fp ->
        val ts = fp.endedAt.takeIf { it > 0 } ?: fp.startedAt
        val zoned = Instant.ofEpochMilli(ts.coerceAtLeast(0L)).atZone(zoneId)
        val dateText = zoned.toLocalDate().toString()
        val liveTrack = tracksById[fp.trackId]
        val coverUrl = fp.coverUrl.ifBlank {
            liveTrack?.metadataObject()?.text("coverUrl").orEmpty()
        }
        val playCount = maxOf(dailyPlaysByTrack[fp.trackId] ?: 0, footprintCountByTrack[fp.trackId] ?: 1)
        val entry = InsightFootprintEntry(
            id = fp.id.ifBlank { "${fp.trackId}:$ts" },
            trackId = fp.trackId,
            title = fp.title.ifBlank { liveTrack?.title.orEmpty().ifBlank { fp.trackId } },
            artists = fp.artists.ifBlank { liveTrack?.artists.orEmpty() },
            album = fp.album.ifBlank { liveTrack?.album.orEmpty() },
            artworkPath = fp.artworkPath ?: liveTrack?.artworkPath,
            coverUrl = coverUrl,
            startedAt = fp.startedAt,
            endedAt = fp.endedAt,
            listenedSeconds = fp.listenedSeconds,
            endReason = fp.endReason,
            trackPlayCount = playCount,
            timeLabel = zoned.format(timeFormatter),
        )
        byDate.getOrPut(dateText) { mutableListOf() }.add(entry)
    }

    return byDate.entries.map { (date, entries) ->
        InsightFootprintDayGroup(
            date = date,
            totalListenedSeconds = entries.sumOf { it.listenedSeconds.coerceAtLeast(0) },
            playCount = entries.size,
            entries = entries,
        )
    }
}

private fun String.toLocalDateOrNull(): LocalDate? = runCatching {
    Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDate()
}.recoverCatching { LocalDate.parse(take(10)) }.getOrNull()

private fun JsonObject.objects(name: String): List<JsonObject> = (this[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject?.long(name: String): Long? = (this?.get(name) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

