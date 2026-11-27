package com.exodidio.tune.ui.navigation

import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exodidio.tune.BuildConfig
import com.exodidio.tune.PlaybackModel
import com.exodidio.tune.player.PlaybackItem
import com.exodidio.tune.player.PlaybackState
import com.exodidio.tune.player.RepeatMode
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import java.util.Locale
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt

private val TerminalBlack = Color(0xFF000000)
private val TerminalSurface = Color(0xFF070D08)
private val TerminalBorder = Color(0xFF1F3825)
private val TerminalGreen = Color(0xFF00E676)
private val TerminalGreenBright = Color(0xFF8CFFBE)
private val TerminalGreenDim = Color(0xFF4CA86B)
private val TerminalMuted = Color(0xFF2E613E)
private val TerminalAmber = Color(0xFFFFC107)
private val TerminalRed = Color(0xFFFF5252)
private val TerminalCyan = Color(0xFF4DD0E1)

private const val TerminalMinDb = -60f
private const val TerminalPeakHoldNs = 600_000_000L
private const val TerminalMaxLogEntries = 140
private const val SparklineWidth = 24
private val SparklinePalette = charArrayOf(' ', '.', ':', '-', '=', '+', '*', '#', '%', '@')

private enum class TerminalLogTone { Normal, Bright, Dim, Warn, Error, Cyan }

private data class TerminalLogEntry(
    val id: Long,
    val text: String,
    val tone: TerminalLogTone = TerminalLogTone.Normal,
    val trackId: String? = null,
    val seekMs: Long? = null,
)

internal sealed interface TerminalCommand {
    data object Empty : TerminalCommand
    data object Help : TerminalCommand
    data object PlayPause : TerminalCommand
    data object Next : TerminalCommand
    data object Previous : TerminalCommand
    data class Seek(val arg: String) : TerminalCommand
    data class Volume(val percent: Int) : TerminalCommand
    data class Speed(val speed: Float) : TerminalCommand
    data class Shuffle(val enable: Boolean?) : TerminalCommand
    data class Repeat(val mode: RepeatMode?) : TerminalCommand
    data object Queue : TerminalCommand
    data class ListTracks(val query: String) : TerminalCommand
    data class PlayIndex(val oneBasedIndex: Int) : TerminalCommand
    data object Lyrics : TerminalCommand
    data object Status : TerminalCommand
    data object Clear : TerminalCommand
    data object Exit : TerminalCommand
    data class Unknown(val raw: String) : TerminalCommand
}

internal fun formatTerminalDuration(ms: Long): String {
    val safeSeconds = (ms.coerceAtLeast(0L) / 1_000L)
    val hours = safeSeconds / 3_600L
    val minutes = (safeSeconds % 3_600L) / 60L
    val seconds = safeSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

internal fun formatAsciiProgressBar(positionMs: Long, durationMs: Long, barChars: Int): String {
    val width = barChars.coerceAtLeast(4)
    if (durationMs <= 0L) return "[" + "-".repeat(width) + "]"
    val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    if (fraction >= 0.999f) return "[" + "=".repeat(width) + "]"
    val cursor = (fraction * width).toInt().coerceIn(0, width - 1)
    return buildString(width + 2) {
        append('[')
        repeat(cursor) { append('=') }
        append('>')
        repeat(width - 1 - cursor) { append('-') }
        append(']')
    }
}

internal fun linearAmplitudeToDb(linear: Float, minDb: Float = TerminalMinDb): Float {
    if (!linear.isFinite() || linear <= 1e-6f) return minDb
    return (20f * log10(linear)).coerceIn(minDb, 0f)
}

internal fun dbToNormalizedMeter(db: Float, minDb: Float = TerminalMinDb): Float {
    if (!db.isFinite() || minDb >= 0f) return 0f
    return ((db - minDb) / -minDb).coerceIn(0f, 1f)
}

internal fun formatAsciiLevelMeter(rmsNorm: Float, peakNorm: Float, barChars: Int): String {
    val width = barChars.coerceAtLeast(4)
    val clampedRms = rmsNorm.coerceIn(0f, 1f)
    val clampedPeak = peakNorm.coerceIn(0f, 1f)
    val fill = (clampedRms * width).roundToInt().coerceIn(0, width)
    val peakIdx = if (clampedPeak > 0.01f) {
        (clampedPeak * (width - 1)).roundToInt().coerceIn(0, width - 1)
    } else {
        -1
    }
    return buildString(width + 2) {
        append('[')
        for (i in 0 until width) {
            when {
                i == peakIdx && peakIdx >= fill -> append('|')
                i < fill -> append('#')
                else -> append('-')
            }
        }
        append(']')
    }
}

internal fun sparklineChar(norm: Float): Char {
    val idx = (norm.coerceIn(0f, 1f) * (SparklinePalette.size - 1)).roundToInt()
        .coerceIn(0, SparklinePalette.lastIndex)
    return SparklinePalette[idx]
}

internal fun parseTerminalSeekTargetMs(arg: String, currentMs: Long, durationMs: Long): Long? {
    val trimmed = arg.trim()
    if (trimmed.isEmpty()) return null
    val clampTarget: (Long) -> Long = { raw ->
        if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
    }
    if (trimmed.startsWith("+") || trimmed.startsWith("-")) {
        val sign = if (trimmed.startsWith("-")) -1L else 1L
        val body = trimmed.drop(1).removeSuffix("s").removeSuffix("S").trim()
        val deltaSec = body.toLongOrNull() ?: return null
        return clampTarget(currentMs + sign * deltaSec * 1_000L)
    }
    if (trimmed.contains(':')) {
        val parts = trimmed.split(':')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.toLongOrNull() ?: return null }
        val totalSeconds = when (nums.size) {
            2 -> nums[0] * 60L + nums[1]
            3 -> nums[0] * 3_600L + nums[1] * 60L + nums[2]
            else -> return null
        }
        return clampTarget(totalSeconds * 1_000L)
    }
    val seconds = trimmed.removeSuffix("s").removeSuffix("S").toLongOrNull() ?: return null
    return clampTarget(seconds * 1_000L)
}

internal fun parseTerminalCommand(raw: String): TerminalCommand {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return TerminalCommand.Empty
    val splitIdx = trimmed.indexOfFirst { it.isWhitespace() }
    val verb = (if (splitIdx < 0) trimmed else trimmed.substring(0, splitIdx)).lowercase(Locale.US)
    val arg = if (splitIdx < 0) "" else trimmed.substring(splitIdx + 1).trim()
    return when (verb) {
        "help", "?", "h" -> TerminalCommand.Help
        "pp", "pause", "toggle" -> TerminalCommand.PlayPause
        "play" -> {
            val idx = arg.toIntOrNull()
            if (arg.isEmpty()) TerminalCommand.PlayPause
            else if (idx != null && idx > 0) TerminalCommand.PlayIndex(idx)
            else TerminalCommand.Unknown(trimmed)
        }
        "next", "n" -> TerminalCommand.Next
        "prev", "previous", "p" -> TerminalCommand.Previous
        "seek", "s" -> if (arg.isNotEmpty()) TerminalCommand.Seek(arg) else TerminalCommand.Unknown(trimmed)
        "vol", "volume" -> {
            val pct = arg.removeSuffix("%").toIntOrNull()
            if (pct != null) TerminalCommand.Volume(pct.coerceIn(0, 100)) else TerminalCommand.Unknown(trimmed)
        }
        "speed", "spd", "rate" -> {
            val spd = arg.removeSuffix("x").removeSuffix("X").toFloatOrNull()
            if (spd != null && spd.isFinite()) {
                TerminalCommand.Speed(spd.coerceIn(0.5f, 2.0f))
            } else {
                TerminalCommand.Unknown(trimmed)
            }
        }
        "shuffle", "shf" -> when (arg.lowercase(Locale.US)) {
            "" -> TerminalCommand.Shuffle(null)
            "on", "1", "true" -> TerminalCommand.Shuffle(true)
            "off", "0", "false" -> TerminalCommand.Shuffle(false)
            else -> TerminalCommand.Unknown(trimmed)
        }
        "repeat", "rep" -> when (arg.lowercase(Locale.US)) {
            "" -> TerminalCommand.Repeat(null)
            "off", "none", "0" -> TerminalCommand.Repeat(RepeatMode.Off)
            "all", "queue" -> TerminalCommand.Repeat(RepeatMode.All)
            "one", "1", "single" -> TerminalCommand.Repeat(RepeatMode.One)
            else -> TerminalCommand.Unknown(trimmed)
        }
        "queue", "q" -> TerminalCommand.Queue
        "ls", "find", "grep" -> TerminalCommand.ListTracks(arg)
        "lyr", "lyrics" -> TerminalCommand.Lyrics
        "status", "stat", "info" -> TerminalCommand.Status
        "clear", "cls" -> TerminalCommand.Clear
        "exit", "quit", "gui" -> TerminalCommand.Exit
        else -> TerminalCommand.Unknown(trimmed)
    }
}

private fun formatAnnotatedMeterBar(
    rmsNorm: Float,
    peakNorm: Float,
    barChars: Int,
): AnnotatedString {
    val raw = formatAsciiLevelMeter(rmsNorm, peakNorm, barChars)
    val width = raw.length - 2
    val amberStart = (width * 0.70f).toInt()
    val redStart = (width * 0.88f).toInt()
    return buildAnnotatedString {
        append('[')
        addStyle(SpanStyle(color = TerminalMuted), 0, 1)
        for (i in 0 until width) {
            val ch = raw[i + 1]
            val start = length
            append(ch)
            val color = when {
                ch == '-' -> TerminalMuted
                ch == '|' -> if (i >= redStart) TerminalRed else TerminalGreenBright
                i >= redStart -> TerminalRed
                i >= amberStart -> TerminalAmber
                else -> TerminalGreen
            }
            addStyle(SpanStyle(color = color), start, length)
        }
        val endIdx = length
        append(']')
        addStyle(SpanStyle(color = TerminalMuted), endIdx, length)
    }
}

@Composable
internal fun TerminalPlayerScreen(
    playback: PlaybackModel,
    onExitTerminalMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackState = playback.state
    val queue = playback.queue
    val currentOnReadAudioLevels = rememberUpdatedState(playback.onReadAudioLevels)
    val currentOnSeek = rememberUpdatedState(playback.onSeek)

    val trackById = remember(playback.queueTracks) {
        playback.queueTracks.associateBy { it.id }
    }
    val uniqueLibraryTracks = remember(playback.queueTracks) {
        playback.queueTracks.distinctBy { it.id }
    }

    val activeItem: PlaybackItem? = when (playbackState) {
        is PlaybackState.Preparing -> playbackState.item
        is PlaybackState.Playing -> playbackState.item
        is PlaybackState.Paused -> playbackState.item
        else -> null
    }
    val activeTrack: LibraryTrack? = remember(activeItem?.trackId, trackById) {
        activeItem?.trackId?.let { id ->
            trackById[id] ?: AndroidSyncRuntime.syncStore.trackForPlayback(id)
        }
    }

    val statePositionMs = when (playbackState) {
        is PlaybackState.Playing -> playbackState.positionMs
        is PlaybackState.Paused -> playbackState.positionMs
        else -> 0L
    }
    val durationMs = when (playbackState) {
        is PlaybackState.Playing -> playbackState.durationMs
        is PlaybackState.Paused -> playbackState.durationMs
        else -> 0L
    }
    val isPlaying = playbackState is PlaybackState.Playing
    val isActivelyAdvancing = playbackState is PlaybackState.Playing && !playbackState.isBuffering

    var positionCapturedAtMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var livePositionMs by remember { mutableLongStateOf(statePositionMs) }
    LaunchedEffect(statePositionMs, playbackState) {
        positionCapturedAtMs = SystemClock.elapsedRealtime()
        livePositionMs = statePositionMs
    }

    var previewSeekFraction by remember { mutableStateOf<Float?>(null) }
    val displayedPositionMs = previewSeekFraction?.let { frac ->
        if (durationMs > 0L) (frac.coerceIn(0f, 1f) * durationMs).toLong() else 0L
    } ?: livePositionMs

    // Real-time audio level meter state (L, R, LOW, MID, HI) + rolling sparkline
    val rawLevels = remember { FloatArray(7) }
    var rmsL by remember { mutableFloatStateOf(0f) }
    var rmsR by remember { mutableFloatStateOf(0f) }
    var peakHoldL by remember { mutableFloatStateOf(0f) }
    var peakHoldR by remember { mutableFloatStateOf(0f) }
    var bandLow by remember { mutableFloatStateOf(0f) }
    var bandMid by remember { mutableFloatStateOf(0f) }
    var bandHigh by remember { mutableFloatStateOf(0f) }
    var peakHoldTimeL by remember { mutableLongStateOf(0L) }
    var peakHoldTimeR by remember { mutableLongStateOf(0L) }
    val sparklineBuffer = remember { CharArray(SparklineWidth) { ' ' } }
    var sparklineText by remember { mutableStateOf(" ".repeat(SparklineWidth)) }
    var sparklineAccumulatorNs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPlaying, isActivelyAdvancing, playbackState) {
        var lastFrameNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                val dtSec = if (lastFrameNs == 0L) 0.016f else {
                    ((frameNs - lastFrameNs).coerceIn(1_000_000L, 100_000_000L)) / 1_000_000_000f
                }
                val dtNs = if (lastFrameNs == 0L) 16_000_000L else (frameNs - lastFrameNs).coerceAtLeast(0L)
                lastFrameNs = frameNs

                if (isActivelyAdvancing && previewSeekFraction == null) {
                    val elapsed = (SystemClock.elapsedRealtime() - positionCapturedAtMs).coerceAtLeast(0L)
                    val interpolated = statePositionMs + (elapsed * playback.playbackSpeed).toLong()
                    livePositionMs = if (durationMs > 0L) interpolated.coerceIn(0L, durationMs) else interpolated
                }

                val hasTelemetry = isPlaying && currentOnReadAudioLevels.value(rawLevels)
                val targetL = if (hasTelemetry) dbToNormalizedMeter(linearAmplitudeToDb(rawLevels[0])) else 0f
                val targetR = if (hasTelemetry) dbToNormalizedMeter(linearAmplitudeToDb(rawLevels[1])) else 0f
                val instantPeakL = if (hasTelemetry) dbToNormalizedMeter(linearAmplitudeToDb(rawLevels[2])) else 0f
                val instantPeakR = if (hasTelemetry) dbToNormalizedMeter(linearAmplitudeToDb(rawLevels[3])) else 0f
                val targetLow = if (hasTelemetry) dbToNormalizedMeter(linearAmplitudeToDb(rawLevels[4])) else 0f
                val targetMid = if (hasTelemetry) dbToNormalizedMeter(linearAmplitudeToDb(rawLevels[5])) else 0f
                val targetHigh = if (hasTelemetry) dbToNormalizedMeter(linearAmplitudeToDb(rawLevels[6])) else 0f

                val attackAlpha = 0.65f
                val releaseDecay = exp(-dtSec * 7.5f)
                fun smooth(prev: Float, target: Float): Float {
                    val next = if (target > prev) {
                        prev + (target - prev) * attackAlpha
                    } else {
                        prev * releaseDecay + target * (1f - releaseDecay)
                    }
                    return if (next < 0.002f && target == 0f) 0f else next
                }

                rmsL = smooth(rmsL, targetL)
                rmsR = smooth(rmsR, targetR)
                bandLow = smooth(bandLow, targetLow)
                bandMid = smooth(bandMid, targetMid)
                bandHigh = smooth(bandHigh, targetHigh)

                if (instantPeakL >= peakHoldL) {
                    peakHoldL = instantPeakL
                    peakHoldTimeL = frameNs
                } else if (frameNs - peakHoldTimeL > TerminalPeakHoldNs) {
                    peakHoldL = smooth(peakHoldL, instantPeakL)
                }
                if (instantPeakR >= peakHoldR) {
                    peakHoldR = instantPeakR
                    peakHoldTimeR = frameNs
                } else if (frameNs - peakHoldTimeR > TerminalPeakHoldNs) {
                    peakHoldR = smooth(peakHoldR, instantPeakR)
                }

                sparklineAccumulatorNs += dtNs
                if (sparklineAccumulatorNs >= 65_000_000L) {
                    sparklineAccumulatorNs = 0L
                    for (i in 0 until SparklineWidth - 1) {
                        sparklineBuffer[i] = sparklineBuffer[i + 1]
                    }
                    sparklineBuffer[SparklineWidth - 1] = sparklineChar(0.5f * (rmsL + rmsR))
                    sparklineText = String(sparklineBuffer)
                }
            }
            val settled = !isPlaying &&
                rmsL == 0f && rmsR == 0f &&
                peakHoldL == 0f && peakHoldR == 0f &&
                bandLow == 0f && bandMid == 0f && bandHigh == 0f
            if (settled) break
        }
    }

    val parsedLyrics = remember(playback.lyrics) {
        parsePlayerLyricsDocument(playback.lyrics)
    }
    val activeLyricLine = remember(parsedLyrics, displayedPositionMs) {
        parsedLyrics.lines.lastOrNull { line ->
            val ts = line.timestampSeconds ?: return@lastOrNull false
            !line.isCountdown && (ts * 1_000f).toLong() <= displayedPositionMs
        }
    }

    val logListState = rememberLazyListState()
    var nextLogId by remember { mutableLongStateOf(1L) }
    val logs = remember {
        mutableStateListOf(
            TerminalLogEntry(
                id = 0L,
                text = "TROVE AUDIO TERMINAL // RAW PCM TELEMETRY ACTIVE",
                tone = TerminalLogTone.Bright,
            ),
            TerminalLogEntry(
                id = -1L,
                text = "Type 'help' or tap a chip below. 'exit' returns to GUI.",
                tone = TerminalLogTone.Dim,
            ),
        )
    }
    var lastListedTrackIds by remember(queue.activeTrackIds) {
        mutableStateOf(queue.activeTrackIds)
    }

    fun appendLog(
        text: String,
        tone: TerminalLogTone = TerminalLogTone.Normal,
        trackId: String? = null,
        seekMs: Long? = null,
    ) {
        val entry = TerminalLogEntry(
            id = nextLogId++,
            text = text,
            tone = tone,
            trackId = trackId,
            seekMs = seekMs,
        )
        logs.add(entry)
        if (logs.size > TerminalMaxLogEntries) {
            logs.removeRange(0, logs.size - TerminalMaxLogEntries)
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.scrollToItem(logs.lastIndex)
        }
    }

    fun executeCommand(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        appendLog("trove$ $trimmed", TerminalLogTone.Bright)
        when (val cmd = parseTerminalCommand(trimmed)) {
            TerminalCommand.Empty -> Unit
            TerminalCommand.Help -> {
                appendLog("COMMANDS:", TerminalLogTone.Cyan)
                appendLog("  pp | play | pause      toggle playback")
                appendLog("  next (n) | prev (p)    skip track")
                appendLog("  seek <mm:ss|sec|+10>   jump to position")
                appendLog("  vol <0..100>           set volume %")
                appendLog("  speed <0.5..2.0>       set playback rate")
                appendLog("  shuffle [on|off]       toggle or set shuffle")
                appendLog("  repeat [off|all|one]   cycle or set repeat")
                appendLog("  queue (q)              list active queue (tap row to play)")
                appendLog("  ls [query]             search loaded tracks (tap row to play)")
                appendLog("  play <#>               play index # from last listing")
                appendLog("  lyr | status | cls     lyrics / player state / clear")
                appendLog("  exit | quit | gui      restore GUI player")
            }
            TerminalCommand.PlayPause -> {
                playback.onPlayPause()
                appendLog(if (isPlaying) "-> PAUSE" else "-> PLAY", TerminalLogTone.Dim)
            }
            TerminalCommand.Next -> {
                playback.onNext()
                appendLog("-> NEXT TRACK", TerminalLogTone.Dim)
            }
            TerminalCommand.Previous -> {
                playback.onPrevious()
                appendLog("-> PREVIOUS TRACK", TerminalLogTone.Dim)
            }
            is TerminalCommand.Seek -> {
                val target = parseTerminalSeekTargetMs(cmd.arg, displayedPositionMs, durationMs)
                if (target == null) {
                    appendLog("ERR invalid seek '${cmd.arg}' (use mm:ss, sec, or +10/-10)", TerminalLogTone.Error)
                } else {
                    playback.onSeek(target)
                    appendLog("-> SEEK ${formatTerminalDuration(target)}", TerminalLogTone.Dim)
                }
            }
            is TerminalCommand.Volume -> {
                playback.onSystemVolumeChange(cmd.percent / 100f)
                appendLog("-> VOL ${cmd.percent}%", TerminalLogTone.Dim)
            }
            is TerminalCommand.Speed -> {
                playback.onPlaybackSpeedSelected(cmd.speed)
                appendLog(String.format(Locale.US, "-> SPEED %.2fx", cmd.speed), TerminalLogTone.Dim)
            }
            is TerminalCommand.Shuffle -> {
                val target = cmd.enable ?: !queue.shuffle
                playback.onShuffleChange(target)
                appendLog("-> SHUFFLE ${if (target) "ON" else "OFF"}", TerminalLogTone.Dim)
            }
            is TerminalCommand.Repeat -> {
                val nextMode = cmd.mode ?: when (queue.repeatMode) {
                    RepeatMode.Off -> RepeatMode.All
                    RepeatMode.All -> RepeatMode.One
                    RepeatMode.One -> RepeatMode.Off
                }
                playback.onRepeatModeChange(nextMode)
                appendLog("-> REPEAT ${nextMode.name.uppercase(Locale.US)}", TerminalLogTone.Dim)
            }
            TerminalCommand.Queue -> {
                val ids = queue.activeTrackIds
                if (ids.isEmpty()) {
                    appendLog("(queue empty)", TerminalLogTone.Dim)
                } else {
                    val start = (queue.currentIndex - 8).coerceAtLeast(0)
                    val end = (start + 30).coerceAtMost(ids.size)
                    lastListedTrackIds = ids
                    appendLog("QUEUE [${ids.size} tracks, showing ${start + 1}..$end]:", TerminalLogTone.Cyan)
                    for (idx in start until end) {
                        val id = ids[idx]
                        val tr = trackById[id] ?: AndroidSyncRuntime.syncStore.trackForPlayback(id)
                        val mark = if (idx == queue.currentIndex) "*" else " "
                        val title = tr?.title?.ifBlank { id } ?: id
                        val artist = tr?.artists?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                        appendLog(
                            text = String.format(Locale.US, "%s%3d. %s%s", mark, idx + 1, title, artist),
                            tone = if (idx == queue.currentIndex) TerminalLogTone.Bright else TerminalLogTone.Normal,
                            trackId = id,
                        )
                    }
                }
            }
            is TerminalCommand.ListTracks -> {
                val q = cmd.query.lowercase(Locale.US)
                val matches = if (q.isEmpty()) {
                    uniqueLibraryTracks
                } else {
                    uniqueLibraryTracks.filter {
                        it.title.lowercase(Locale.US).contains(q) ||
                            it.artists.lowercase(Locale.US).contains(q) ||
                            it.album.lowercase(Locale.US).contains(q)
                    }
                }
                val shown = matches.take(30)
                lastListedTrackIds = shown.map { it.id }
                if (shown.isEmpty()) {
                    appendLog("NO MATCHES FOR '${cmd.query}'", TerminalLogTone.Warn)
                } else {
                    appendLog("TRACKS [${matches.size} matched, showing ${shown.size}]:", TerminalLogTone.Cyan)
                    shown.forEachIndexed { idx, tr ->
                        val artist = tr.artists.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                        appendLog(
                            text = String.format(Locale.US, " %3d. %s%s", idx + 1, tr.title, artist),
                            tone = if (tr.id == activeItem?.trackId) TerminalLogTone.Bright else TerminalLogTone.Normal,
                            trackId = tr.id,
                        )
                    }
                }
            }
            is TerminalCommand.PlayIndex -> {
                val targetId = lastListedTrackIds.getOrNull(cmd.oneBasedIndex - 1)
                if (targetId == null) {
                    appendLog("ERR index #${cmd.oneBasedIndex} out of range (1..${lastListedTrackIds.size})", TerminalLogTone.Error)
                } else {
                    playback.onQueueTrackSelected(targetId)
                    val tr = trackById[targetId]
                    appendLog("-> PLAY #${cmd.oneBasedIndex}: ${tr?.title ?: targetId}", TerminalLogTone.Dim)
                }
            }
            TerminalCommand.Lyrics -> {
                if (parsedLyrics.lines.isEmpty()) {
                    appendLog("(no lyrics loaded)", TerminalLogTone.Dim)
                } else {
                    appendLog("LYRICS [${parsedLyrics.lines.size} lines, tap to seek]:", TerminalLogTone.Cyan)
                    parsedLyrics.lines.filter { !it.isCountdown }.take(60).forEach { line ->
                        val tsMs = line.timestampSeconds?.let { (it * 1_000f).toLong() }
                        val prefix = tsMs?.let { "[${formatTerminalDuration(it)}] " } ?: "        "
                        val isActive = line == activeLyricLine
                        appendLog(
                            text = "$prefix${line.primary}",
                            tone = if (isActive) TerminalLogTone.Bright else TerminalLogTone.Normal,
                            seekMs = tsMs,
                        )
                    }
                }
            }
            TerminalCommand.Status -> {
                val title = activeItem?.title ?: "(none)"
                val artist = activeItem?.artist ?: "-"
                val volPct = (playback.systemVolume.coerceIn(0f, 1f) * 100f).roundToInt()
                appendLog("STATUS:", TerminalLogTone.Cyan)
                appendLog("  TRACK : $title / $artist")
                appendLog("  POS   : ${formatTerminalDuration(displayedPositionMs)} / ${formatTerminalDuration(durationMs)}")
                appendLog(
                    String.format(
                        Locale.US,
                        "  CONF  : VOL=%d%%  SPD=%.2fx  SHF=%s  REP=%s",
                        volPct,
                        playback.playbackSpeed,
                        if (queue.shuffle) "ON" else "OFF",
                        queue.repeatMode.name.uppercase(Locale.US),
                    ),
                )
            }
            TerminalCommand.Clear -> logs.clear()
            TerminalCommand.Exit -> onExitTerminalMode()
            is TerminalCommand.Unknown -> appendLog("ERR unknown command '${cmd.raw}'. Try 'help'.", TerminalLogTone.Error)
        }
    }

    val monoStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    )
    val smallMonoStyle = monoStyle.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val textMeasurer = rememberTextMeasurer()
    val charWidthPx = remember(monoStyle) {
        textMeasurer.measure("MMMMMMMMMM", monoStyle).size.width / 10f
    }.coerceAtLeast(1f)
    val smallCharWidthPx = remember(smallMonoStyle) {
        textMeasurer.measure("MMMMMMMMMM", smallMonoStyle).size.width / 10f
    }.coerceAtLeast(1f)

    var commandInput by remember { mutableStateOf("") }
    val pid = remember { Process.myPid() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val density = LocalDensity.current
        val availableWidthPx = with(density) { maxWidth.toPx() }
        val dividerCols = (availableWidthPx / smallCharWidthPx).toInt().coerceIn(24, 96)
        val dividerLine = remember(dividerCols) { "-".repeat(dividerCols) }

        val progressBarCols = ((availableWidthPx / charWidthPx).toInt() - 25).coerceIn(10, 44)
        val meterBarCols = ((availableWidthPx / smallCharWidthPx).toInt() - 16).coerceIn(10, 40)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 1. Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TROVE-CLI v${BuildConfig.VERSION_NAME} [PID:$pid]",
                    style = smallMonoStyle.copy(fontWeight = FontWeight.Bold),
                    color = TerminalGreenBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "[EXIT]",
                    style = smallMonoStyle.copy(fontWeight = FontWeight.Bold),
                    color = TerminalRed,
                    modifier = Modifier
                        .border(1.dp, TerminalBorder)
                        .background(TerminalSurface)
                        .clickable { onExitTerminalMode() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Text(
                text = dividerLine,
                style = smallMonoStyle,
                color = TerminalMuted,
                maxLines = 1,
            )

            // 2. Track Info + State + Active Lyric
            val stateLabel = when (playbackState) {
                is PlaybackState.Playing -> if (playbackState.isBuffering) "BUFFERING" else "PLAYING"
                is PlaybackState.Paused -> "PAUSED"
                is PlaybackState.Preparing -> "PREPARING"
                is PlaybackState.Failed -> "ERROR"
                PlaybackState.Idle -> "IDLE"
            }
            val queuePosLabel = if (queue.activeTrackIds.isNotEmpty() && queue.currentIndex >= 0) {
                " (${queue.currentIndex + 1}/${queue.activeTrackIds.size})"
            } else {
                ""
            }
            val titleText = activeItem?.title?.ifBlank { "(untitled)" } ?: "(no track loaded)"
            val artistText = activeItem?.artist?.ifBlank { "-" } ?: "-"
            val albumText = activeTrack?.album?.takeIf { it.isNotBlank() } ?: activeItem?.album.orEmpty()
            val volPct = (playback.systemVolume.coerceIn(0f, 1f) * 100f).roundToInt()

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "TRACK : $titleText$queuePosLabel",
                    style = monoStyle.copy(fontWeight = FontWeight.Bold),
                    color = TerminalGreenBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (albumText.isNotBlank()) "ARTIST: $artistText  |  ALBUM: $albumText" else "ARTIST: $artistText",
                    style = smallMonoStyle,
                    color = TerminalGreenDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "STATE : [%-9s] VOL:%3d%% SPD:%.2fx SHF:%-3s REP:%s",
                        stateLabel,
                        volPct,
                        playback.playbackSpeed,
                        if (queue.shuffle) "ON" else "OFF",
                        queue.repeatMode.name.uppercase(Locale.US),
                    ),
                    style = smallMonoStyle,
                    color = if (playbackState is PlaybackState.Failed) TerminalRed else TerminalGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (activeLyricLine != null) {
                    Text(
                        text = "LYRIC > ${activeLyricLine.primary}",
                        style = smallMonoStyle,
                        color = TerminalCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 3. Interactive ASCII Progress Bar
            val pct = if (durationMs > 0L) {
                ((displayedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * 100f).roundToInt()
            } else {
                0
            }
            val barAscii = formatAsciiProgressBar(displayedPositionMs, durationMs, progressBarCols)
            val progressLine = String.format(
                Locale.US,
                "POS %s %s/%s %3d%%",
                barAscii,
                formatTerminalDuration(displayedPositionMs),
                formatTerminalDuration(durationMs),
                pct,
            )
            val currentDurationMs = rememberUpdatedState(durationMs)
            Text(
                text = progressLine,
                style = monoStyle,
                color = if (previewSeekFraction != null) TerminalAmber else TerminalGreen,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalSurface)
                    .border(1.dp, TerminalBorder)
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val dur = currentDurationMs.value
                            if (dur > 0L) {
                                val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                currentOnSeek.value((frac * dur).toLong())
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                if (currentDurationMs.value > 0L) {
                                    previewSeekFraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                }
                            },
                            onHorizontalDrag = { change, _ ->
                                if (currentDurationMs.value > 0L) {
                                    change.consume()
                                    previewSeekFraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                }
                            },
                            onDragEnd = {
                                val frac = previewSeekFraction
                                previewSeekFraction = null
                                val dur = currentDurationMs.value
                                if (frac != null && dur > 0L) {
                                    currentOnSeek.value((frac * dur).toLong())
                                }
                            },
                            onDragCancel = {
                                previewSeekFraction = null
                            },
                        )
                    },
            )

            // 4. Real-Time ASCII Audio Level Meters
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalSurface)
                    .border(1.dp, TerminalBorder)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val dbL = TerminalMinDb + rmsL * -TerminalMinDb
                val dbR = TerminalMinDb + rmsR * -TerminalMinDb
                val dbLow = TerminalMinDb + bandLow * -TerminalMinDb
                val dbMid = TerminalMinDb + bandMid * -TerminalMinDb
                val dbHigh = TerminalMinDb + bandHigh * -TerminalMinDb

                TerminalMeterRow("L  ", rmsL, peakHoldL, dbL, meterBarCols, smallMonoStyle)
                TerminalMeterRow("R  ", rmsR, peakHoldR, dbR, meterBarCols, smallMonoStyle)
                TerminalMeterRow("LOW", bandLow, 0f, dbLow, meterBarCols, smallMonoStyle)
                TerminalMeterRow("MID", bandMid, 0f, dbMid, meterBarCols, smallMonoStyle)
                TerminalMeterRow("HI ", bandHigh, 0f, dbHigh, meterBarCols, smallMonoStyle)

                Text(
                    text = "WAV [$sparklineText]  ${if (isPlaying) "LIVE-PCM" else "SILENCE "}",
                    style = smallMonoStyle,
                    color = if (isPlaying) TerminalGreenDim else TerminalMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }

            // 5. Scrollable CLI Console Output
            LazyColumn(
                state = logListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TerminalBlack)
                    .border(1.dp, TerminalBorder)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(logs, key = { it.id }) { entry ->
                    val color = when (entry.tone) {
                        TerminalLogTone.Normal -> TerminalGreen
                        TerminalLogTone.Bright -> TerminalGreenBright
                        TerminalLogTone.Dim -> TerminalGreenDim
                        TerminalLogTone.Warn -> TerminalAmber
                        TerminalLogTone.Error -> TerminalRed
                        TerminalLogTone.Cyan -> TerminalCyan
                    }
                    val clickableModifier = when {
                        entry.trackId != null -> Modifier.clickable {
                            playback.onQueueTrackSelected(entry.trackId)
                            appendLog("-> PLAY ${entry.text.trim()}", TerminalLogTone.Dim)
                        }
                        entry.seekMs != null -> Modifier.clickable {
                            playback.onSeek(entry.seekMs)
                            appendLog("-> SEEK ${formatTerminalDuration(entry.seekMs)}", TerminalLogTone.Dim)
                        }
                        else -> Modifier
                    }
                    Text(
                        text = entry.text,
                        style = smallMonoStyle,
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(clickableModifier),
                    )
                }
            }

            // 6. Quick ASCII Action Strip
            val chipActions = remember(isPlaying) {
                listOf(
                    "[prev]" to "prev",
                    (if (isPlaying) "[pause]" else "[play]") to "pp",
                    "[next]" to "next",
                    "[-10s]" to "seek -10",
                    "[+10s]" to "seek +10",
                    "[queue]" to "queue",
                    "[ls]" to "ls",
                    "[lyr]" to "lyr",
                    "[shf]" to "shuffle",
                    "[rep]" to "repeat",
                    "[status]" to "status",
                    "[cls]" to "cls",
                    "[help]" to "help",
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chipActions.forEach { (label, cmd) ->
                    Text(
                        text = label,
                        style = smallMonoStyle.copy(fontWeight = FontWeight.Bold),
                        color = TerminalGreen,
                        modifier = Modifier
                            .border(1.dp, TerminalBorder)
                            .background(TerminalSurface)
                            .clickable { executeCommand(cmd) }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
            }

            // 7. Interactive Prompt Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TerminalBorder)
                    .background(TerminalSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "trove$ ",
                    style = monoStyle.copy(fontWeight = FontWeight.Bold),
                    color = TerminalGreenBright,
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (commandInput.isEmpty()) {
                        Text(
                            text = "help | seek +15 | ls | queue | exit",
                            style = monoStyle,
                            color = TerminalMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        singleLine = true,
                        textStyle = monoStyle.copy(color = TerminalGreenBright),
                        cursorBrush = SolidColor(TerminalGreen),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val submitted = commandInput
                                commandInput = ""
                                executeCommand(submitted)
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[RUN]",
                    style = smallMonoStyle.copy(fontWeight = FontWeight.Bold),
                    color = TerminalGreenBright,
                    modifier = Modifier
                        .border(1.dp, TerminalBorder)
                        .background(TerminalBlack)
                        .clickable {
                            val submitted = commandInput
                            commandInput = ""
                            executeCommand(submitted)
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun TerminalMeterRow(
    label: String,
    rmsNorm: Float,
    peakNorm: Float,
    dbValue: Float,
    barChars: Int,
    style: TextStyle,
) {
    val barText = formatAnnotatedMeterBar(rmsNorm, peakNorm, barChars)
    val dbText = String.format(Locale.US, "%5.1f dB", dbValue)
    val dbColor = when {
        dbValue >= -3f -> TerminalRed
        dbValue >= -12f -> TerminalAmber
        rmsNorm > 0.01f -> TerminalGreenDim
        else -> TerminalMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label ",
            style = style.copy(fontWeight = FontWeight.Bold),
            color = TerminalGreenDim,
            maxLines = 1,
        )
        Text(
            text = barText,
            style = style,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = dbText,
            style = style,
            color = dbColor,
            maxLines = 1,
        )
    }
}
