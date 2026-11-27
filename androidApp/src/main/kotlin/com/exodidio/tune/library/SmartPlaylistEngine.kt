package com.exodidio.tune.library

import com.exodidio.tune.player.DailyTrackListeningStat
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.ui.screens.durationSecondsOf
import com.exodidio.tune.ui.screens.isFavoriteOf
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
internal enum class SmartPlaylistMatchMode {
    All,
    Any,
}

@Serializable
internal enum class SmartPlaylistField {
    Favorite,
    Artist,
    Album,
    Folder,
    Title,
    Format,
    PlayCount,
    DurationSec,
    DateAddedDays,
}

@Serializable
internal enum class SmartPlaylistOperator {
    Contains,
    Equals,
    GreaterThan,
    LessThan,
    IsTrue,
    IsFalse,
}

@Serializable
internal enum class SmartPlaylistSortBy {
    DateAddedDesc,
    PlayCountDesc,
    TitleAsc,
    ArtistAsc,
    DurationDesc,
    Random,
}

@Serializable
internal data class SmartPlaylistRule(
    val field: SmartPlaylistField,
    val operator: SmartPlaylistOperator,
    val value: String = "",
)

@Serializable
internal data class SmartPlaylistSpec(
    val matchMode: SmartPlaylistMatchMode = SmartPlaylistMatchMode.All,
    val rules: List<SmartPlaylistRule> = emptyList(),
    val sortBy: SmartPlaylistSortBy = SmartPlaylistSortBy.DateAddedDesc,
    val limit: Int? = null,
)

internal data class SmartPlaylistPreset(
    val titleRes: Int,
    val spec: SmartPlaylistSpec,
)

internal object SmartPlaylistEngine {
    private val losslessExtensions = setOf(
        "flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff", "dsd", "pcm",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun isSmartPlaylist(metadataJson: String): Boolean = parseSpec(metadataJson) != null

    fun parseSpec(metadataJson: String): SmartPlaylistSpec? {
        if (metadataJson.isBlank() || metadataJson == "{}") return null
        val root = runCatching {
            LibrarySyncProtocol.json.parseToJsonElement(metadataJson) as? JsonObject
        }.getOrNull() ?: return null
        val container = root["playlist"] as? JsonObject ?: root
        val isSmart = (container["is_smart"] as? JsonPrimitive)?.contentOrNull == "true"
        val specElement = container["smart_spec"] ?: return null
        if (!isSmart) return null
        return runCatching {
            json.decodeFromJsonElement<SmartPlaylistSpec>(specElement)
        }.getOrNull()
    }

    fun encodeMetadataJson(spec: SmartPlaylistSpec, existingMetadataJson: String = "{}"): String {
        val existing = runCatching {
            LibrarySyncProtocol.json.parseToJsonElement(existingMetadataJson) as? JsonObject
        }.getOrNull()
        val artworkKey = ((existing?.get("playlist") as? JsonObject ?: existing)?.get("artwork_key") as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotBlank() }
        val specElement = json.encodeToJsonElement(spec)
        return buildJsonObject {
            put("is_smart", true)
            put("smart_spec", specElement)
            if (artworkKey != null) {
                put("artwork_key", artworkKey)
            }
        }.toString()
    }

    fun aggregatePlayCounts(stats: List<DailyTrackListeningStat>): Map<String, Int> {
        if (stats.isEmpty()) return emptyMap()
        val counts = HashMap<String, Int>(stats.size)
        for (stat in stats) {
            if (stat.trackId.isNotBlank() && stat.playCount > 0) {
                counts[stat.trackId] = (counts[stat.trackId] ?: 0) + stat.playCount
            }
        }
        return counts
    }

    fun evaluate(
        spec: SmartPlaylistSpec,
        tracks: List<LibraryTrack>,
        playCountsByTrackId: Map<String, Int>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<LibraryTrack> {
        val matched = if (spec.rules.isEmpty()) {
            tracks
        } else {
            tracks.filter { track ->
                val metadata = track.metadataObject()
                when (spec.matchMode) {
                    SmartPlaylistMatchMode.All -> spec.rules.all { rule ->
                        matchesRule(track, metadata, rule, playCountsByTrackId, nowMs)
                    }
                    SmartPlaylistMatchMode.Any -> spec.rules.any { rule ->
                        matchesRule(track, metadata, rule, playCountsByTrackId, nowMs)
                    }
                }
            }
        }
        val sorted = when (spec.sortBy) {
            SmartPlaylistSortBy.DateAddedDesc -> matched.sortedWith(
                compareByDescending<LibraryTrack> { createdAtEpochMs(it.createdAt) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )
            SmartPlaylistSortBy.PlayCountDesc -> matched.sortedWith(
                compareByDescending<LibraryTrack> { playCountsByTrackId[it.id] ?: 0 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )
            SmartPlaylistSortBy.TitleAsc -> matched.sortedBy { it.sortTitle.ifBlank { it.title }.lowercase() }
            SmartPlaylistSortBy.ArtistAsc -> matched.sortedWith(
                compareBy<LibraryTrack> { it.sortArtists.ifBlank { it.artists }.lowercase() }
                    .thenBy { it.sortTitle.ifBlank { it.title }.lowercase() },
            )
            SmartPlaylistSortBy.DurationDesc -> matched.sortedWith(
                compareByDescending<LibraryTrack> { durationSecondsOf(it.metadataObject()) ?: 0L }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )
            SmartPlaylistSortBy.Random -> matched.shuffled()
        }
        val limit = spec.limit?.takeIf { it > 0 }
        return if (limit != null && sorted.size > limit) sorted.take(limit) else sorted
    }

    private fun matchesRule(
        track: LibraryTrack,
        metadata: JsonObject?,
        rule: SmartPlaylistRule,
        playCountsByTrackId: Map<String, Int>,
        nowMs: Long,
    ): Boolean {
        val needle = rule.value.trim()
        return when (rule.field) {
            SmartPlaylistField.Favorite -> {
                val fav = isFavoriteOf(metadata)
                when (rule.operator) {
                    SmartPlaylistOperator.IsFalse -> !fav
                    SmartPlaylistOperator.Equals -> fav == needle.equals("true", ignoreCase = true)
                    else -> fav
                }
            }
            SmartPlaylistField.Artist -> matchText(track.artists, rule.operator, needle)
            SmartPlaylistField.Album -> matchText(track.album, rule.operator, needle)
            SmartPlaylistField.Title -> matchText(track.title, rule.operator, needle)
            SmartPlaylistField.Folder -> {
                val folder = resolveFolderPathForTrack(track).orEmpty()
                matchText(folder, rule.operator, needle)
            }
            SmartPlaylistField.Format -> matchFormat(track, metadata, rule.operator, needle)
            SmartPlaylistField.PlayCount -> {
                val count = (playCountsByTrackId[track.id] ?: 0).toDouble()
                matchNumber(count, rule.operator, needle)
            }
            SmartPlaylistField.DurationSec -> {
                val seconds = (durationSecondsOf(metadata) ?: 0L).toDouble()
                matchNumber(seconds, rule.operator, needle)
            }
            SmartPlaylistField.DateAddedDays -> {
                val created = createdAtEpochMs(track.createdAt)
                if (created <= 0L) return false
                val daysAgo = ((nowMs - created).coerceAtLeast(0L)).toDouble() / 86_400_000.0
                matchNumber(daysAgo, rule.operator, needle)
            }
        }
    }

    private fun matchText(haystack: String, operator: SmartPlaylistOperator, needle: String): Boolean {
        if (needle.isEmpty()) return true
        return when (operator) {
            SmartPlaylistOperator.Equals -> haystack.equals(needle, ignoreCase = true)
            else -> haystack.contains(needle, ignoreCase = true)
        }
    }

    private fun matchFormat(
        track: LibraryTrack,
        metadata: JsonObject?,
        operator: SmartPlaylistOperator,
        needle: String,
    ): Boolean {
        if (needle.isEmpty()) return true
        val ext = track.audioPath
            ?.substringBefore('?')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()
        val codec = (metadata?.get("audio_codec") as? JsonPrimitive)?.contentOrNull?.lowercase()
            ?: (metadata?.get("codec") as? JsonPrimitive)?.contentOrNull?.lowercase()
            ?: (metadata?.get("format") as? JsonPrimitive)?.contentOrNull?.lowercase()
            ?: ""
        if (needle.equals("lossless", ignoreCase = true)) {
            return ext in losslessExtensions || codec in losslessExtensions || codec.contains("lossless")
        }
        return when (operator) {
            SmartPlaylistOperator.Equals -> ext.equals(needle, ignoreCase = true) || codec.equals(needle, ignoreCase = true)
            else -> ext.contains(needle, ignoreCase = true) || codec.contains(needle, ignoreCase = true)
        }
    }

    private fun matchNumber(value: Double, operator: SmartPlaylistOperator, needle: String): Boolean {
        val target = needle.toDoubleOrNull() ?: return true
        return when (operator) {
            SmartPlaylistOperator.Equals -> value.toLong() == target.toLong()
            SmartPlaylistOperator.GreaterThan -> value > target
            SmartPlaylistOperator.LessThan -> value < target
            else -> value >= target
        }
    }

    internal fun createdAtEpochMs(createdAt: String): Long {
        val trimmed = createdAt.trim()
        if (trimmed.isEmpty()) return 0L
        trimmed.toLongOrNull()?.let { num ->
            return if (num < 10_000_000_000L) num * 1000L else num
        }
        return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrDefault(0L)
    }

    fun defaultOperatorsFor(field: SmartPlaylistField): List<SmartPlaylistOperator> = when (field) {
        SmartPlaylistField.Favorite -> listOf(SmartPlaylistOperator.IsTrue, SmartPlaylistOperator.IsFalse)
        SmartPlaylistField.PlayCount,
        SmartPlaylistField.DurationSec,
        SmartPlaylistField.DateAddedDays,
        -> listOf(
            SmartPlaylistOperator.GreaterThan,
            SmartPlaylistOperator.LessThan,
            SmartPlaylistOperator.Equals,
        )
        SmartPlaylistField.Artist,
        SmartPlaylistField.Album,
        SmartPlaylistField.Folder,
        SmartPlaylistField.Title,
        SmartPlaylistField.Format,
        -> listOf(SmartPlaylistOperator.Contains, SmartPlaylistOperator.Equals)
    }

    val presets: List<SmartPlaylistPreset> = listOf(
        SmartPlaylistPreset(
            titleRes = com.exodidio.tune.R.string.smart_playlist_preset_top_played,
            spec = SmartPlaylistSpec(
                matchMode = SmartPlaylistMatchMode.All,
                rules = listOf(SmartPlaylistRule(SmartPlaylistField.PlayCount, SmartPlaylistOperator.GreaterThan, "0")),
                sortBy = SmartPlaylistSortBy.PlayCountDesc,
                limit = 25,
            ),
        ),
        SmartPlaylistPreset(
            titleRes = com.exodidio.tune.R.string.smart_playlist_preset_recent,
            spec = SmartPlaylistSpec(
                matchMode = SmartPlaylistMatchMode.All,
                rules = listOf(SmartPlaylistRule(SmartPlaylistField.DateAddedDays, SmartPlaylistOperator.LessThan, "30")),
                sortBy = SmartPlaylistSortBy.DateAddedDesc,
                limit = 50,
            ),
        ),
        SmartPlaylistPreset(
            titleRes = com.exodidio.tune.R.string.smart_playlist_preset_lossless,
            spec = SmartPlaylistSpec(
                matchMode = SmartPlaylistMatchMode.All,
                rules = listOf(SmartPlaylistRule(SmartPlaylistField.Format, SmartPlaylistOperator.Equals, "lossless")),
                sortBy = SmartPlaylistSortBy.TitleAsc,
            ),
        ),
        SmartPlaylistPreset(
            titleRes = com.exodidio.tune.R.string.smart_playlist_preset_long,
            spec = SmartPlaylistSpec(
                matchMode = SmartPlaylistMatchMode.All,
                rules = listOf(SmartPlaylistRule(SmartPlaylistField.DurationSec, SmartPlaylistOperator.GreaterThan, "240")),
                sortBy = SmartPlaylistSortBy.DurationDesc,
            ),
        ),
        SmartPlaylistPreset(
            titleRes = com.exodidio.tune.R.string.smart_playlist_preset_fav_hot,
            spec = SmartPlaylistSpec(
                matchMode = SmartPlaylistMatchMode.All,
                rules = listOf(
                    SmartPlaylistRule(SmartPlaylistField.Favorite, SmartPlaylistOperator.IsTrue, "true"),
                    SmartPlaylistRule(SmartPlaylistField.PlayCount, SmartPlaylistOperator.GreaterThan, "0"),
                ),
                sortBy = SmartPlaylistSortBy.PlayCountDesc,
            ),
        ),
    )
}
