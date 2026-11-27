package com.exodidio.tune.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.PlaybackState as AndroidMediaPlaybackState
import android.os.Build
import android.util.Log
import com.exodidio.tune.MainActivity
import com.exodidio.tune.R
import com.exodidio.tune.lyrics.SystemLyricMode
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.ui.navigation.PlayerLyricLine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

internal fun PlaybackService.showForeground(item: PlaybackItem, fromCacheOnly: Boolean = false) {
    createChannel()
    startForeground(
        PlaybackService.NotificationId,
        notification(item, fromCacheOnly = fromCacheOnly),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
    )
    isForegroundStarted = true
}

internal fun PlaybackService.updateNotification() {
    val item = when (val current = PlaybackService.state.value) {
        is PlaybackState.Playing -> current.item
        is PlaybackState.Paused -> current.item
        is PlaybackState.Preparing -> current.item
        else -> return
    }
    getSystemService(NotificationManager::class.java).notify(PlaybackService.NotificationId, notification(item))
}

internal fun PlaybackService.registerNoisyAudioReceiver() {
    val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(noisyAudioReceiver, filter, Service.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        registerReceiver(noisyAudioReceiver, filter)
    }
}

/** Publishes metadata and transport state to Android System Now Playing surfaces. */
internal fun PlaybackService.publishNowPlaying(item: PlaybackItem, state: Int, positionMs: Long, durationMs: Long) {
    mediaSession.isActive = true
    val metadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, item.trackId)
        .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
    // 小米系统媒体控件（通知中心/锁屏/背屏）读取的“原生歌词”字段：标准 LRC 文本，由系统解析并跟随播放进度。
    if (trackedLyricTrackId == item.trackId) {
        trackedNativeLyric?.takeIf { it.isNotBlank() }?.let { lyrics ->
            metadata.putString(PlaybackService.NativeLyricMetadataKey, lyrics)
        }
    }
    val artworkSize = if (mediaNotificationStyle == MediaNotificationStyle.LargeArtwork) {
        PlaybackService.LargeNowPlayingArtworkSizePx
    } else {
        PlaybackService.NowPlayingArtworkSizePx
    }
    loadNowPlayingArtwork(item.artworkPath, artworkSize)?.let { artwork ->
        metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
        metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
        metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
    }
    mediaSession.setMetadata(metadata.build())
    mediaSession.setPlaybackState(androidPlaybackState(state, positionMs))
    Log.d(PlaybackLogTag, "Published Android Now Playing id=${item.trackId} state=$state")
}

/** 歌词异步到达后重发一次元数据，让系统媒体控件拿到完整 LRC。 */
internal fun PlaybackService.republishCurrentMetadata() {
    when (val snapshot = PlaybackService.state.value) {
        is PlaybackState.Playing -> publishNowPlaying(
            snapshot.item,
            if (snapshot.isBuffering) AndroidMediaPlaybackState.STATE_BUFFERING else AndroidMediaPlaybackState.STATE_PLAYING,
            snapshot.positionMs,
            snapshot.durationMs,
        )
        is PlaybackState.Paused -> publishNowPlaying(
            snapshot.item,
            AndroidMediaPlaybackState.STATE_PAUSED,
            snapshot.positionMs,
            snapshot.durationMs,
        )
        is PlaybackState.Preparing -> publishNowPlaying(
            snapshot.item,
            AndroidMediaPlaybackState.STATE_BUFFERING,
            0L,
            0L,
        )
        else -> Unit
    }
}

/** 行级 LRC（[mm:ss.xx]原文），供系统原生歌词解析；没有歌词时返回 null。 */
internal fun buildNativeLyric(lines: List<PlayerLyricLine>, offsetMs: Long = 0L): String? {
    if (lines.isEmpty()) return null
    val builder = StringBuilder()
    for (line in lines.sortedBy { it.timestampSeconds ?: 0f }) {
        if (line.isCountdown) continue
        val timestamp = line.timestampSeconds ?: continue
        val text = line.primary.trim()
        if (text.isEmpty()) continue
        val totalMs = ((timestamp * 1_000f).roundToInt().toLong() + offsetMs).coerceAtLeast(0L).toInt()
        val totalSeconds = totalMs / 1_000
        builder.append('[')
            .append(
                String.format(
                    Locale.US,
                    "%02d:%02d.%02d",
                    totalSeconds / 60,
                    totalSeconds % 60,
                    (totalMs % 1_000) / 10,
                ),
            )
            .append(']')
            .append(text)
            .append('\n')
    }
    return builder.toString().takeIf { it.isNotBlank() }
}

internal fun PlaybackService.updateNowPlayingTransportState() {
    when (val current = PlaybackService.state.value) {
        is PlaybackState.Playing -> mediaSession.setPlaybackState(
            androidPlaybackState(
                if (current.isBuffering) AndroidMediaPlaybackState.STATE_BUFFERING else AndroidMediaPlaybackState.STATE_PLAYING,
                decoder?.positionMs() ?: current.positionMs,
            ),
        )
        is PlaybackState.Paused -> mediaSession.setPlaybackState(
            androidPlaybackState(AndroidMediaPlaybackState.STATE_PAUSED, decoder?.positionMs() ?: current.positionMs),
        )
        else -> Unit
    }
}

internal fun PlaybackService.androidPlaybackState(state: Int, positionMs: Long): AndroidMediaPlaybackState {
    val snapshot = queue.snapshot()
    val builder = AndroidMediaPlaybackState.Builder()
        .setActions(
            AndroidMediaPlaybackState.ACTION_PLAY or
                AndroidMediaPlaybackState.ACTION_PAUSE or
                AndroidMediaPlaybackState.ACTION_PLAY_PAUSE or
                AndroidMediaPlaybackState.ACTION_SKIP_TO_NEXT or
                AndroidMediaPlaybackState.ACTION_SKIP_TO_PREVIOUS or
                AndroidMediaPlaybackState.ACTION_SEEK_TO or
                AndroidMediaPlaybackState.ACTION_STOP,
        )
        .setActiveQueueItemId(activeQueueItemId(snapshot))
        .setState(state, positionMs, if (state == AndroidMediaPlaybackState.STATE_PLAYING) PlaybackService.playbackSpeed.value else 0f)
    if (mediaNotificationStyle == MediaNotificationStyle.ExtendedControls) {
        val currentTrackId = currentPlaybackTrackId()
        val liked = isTrackCurrentlyLiked(currentTrackId)
        builder.addCustomAction(
            AndroidMediaPlaybackState.CustomAction.Builder(
                PlaybackService.ActionToggleFavorite,
                getString(R.string.notification_action_favorite),
                if (liked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart_outline,
            ).build(),
        )
        builder.addCustomAction(
            AndroidMediaPlaybackState.CustomAction.Builder(
                PlaybackService.ActionCycleRepeat,
                getString(repeatLabelRes(snapshot.repeatMode)),
                repeatIconRes(snapshot.repeatMode),
            ).build(),
        )
    }
    return builder.build()
}

internal fun loadNowPlayingArtwork(
    path: String?,
    sizePx: Int = PlaybackService.NowPlayingArtworkSizePx,
    fromCacheOnly: Boolean = false,
): Bitmap? {
    if (path.isNullOrBlank()) return null
    val cacheKey = "$path@$sizePx"
    PlaybackService.nowPlayingArtworkCache.get(cacheKey)?.let { return it }
    if (fromCacheOnly) return null
    val artwork = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= sizePx &&
            bounds.outHeight / (sampleSize * 2) >= sizePx
        ) sampleSize *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()
    if (artwork != null) PlaybackService.nowPlayingArtworkCache.put(cacheKey, artwork)
    else Log.w(PlaybackLogTag, "Unable to decode Now Playing artwork path=$path")
    return artwork
}

private fun PlaybackService.notification(item: PlaybackItem, fromCacheOnly: Boolean = false): Notification {
    val mode = lyricSettings.systemLyricMode
    val lyric = currentLyricText.trim()
    val showLyric = mode != SystemLyricMode.Off && lyric.isNotBlank()
    val title = if (showLyric && mode == SystemLyricMode.LyricFirst) lyric else item.title
    val text = when {
        !showLyric -> item.artist
        mode == SystemLyricMode.LyricFirst -> listOf(item.title, item.artist)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        else -> lyric
    }
    val stylePreset = mediaNotificationStyle
    val artworkSize = if (stylePreset == MediaNotificationStyle.LargeArtwork) {
        PlaybackService.LargeNowPlayingArtworkSizePx
    } else {
        PlaybackService.NowPlayingArtworkSizePx
    }
    val artwork = loadNowPlayingArtwork(item.artworkPath, artworkSize, fromCacheOnly = fromCacheOnly)
    val isPlaying = PlaybackService.state.value is PlaybackState.Playing
    val playPauseAction = notificationAction(
        requestCode = 202,
        iconRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        title = getString(if (isPlaying) R.string.player_pause else R.string.player_play),
        serviceAction = PlaybackService.ActionToggle,
    )
    val prevAction = notificationAction(
        requestCode = 201,
        iconRes = R.drawable.ic_widget_prev,
        title = getString(R.string.player_previous),
        serviceAction = PlaybackService.ActionPrevious,
    )
    val nextAction = notificationAction(
        requestCode = 203,
        iconRes = R.drawable.ic_widget_next,
        title = getString(R.string.player_next),
        serviceAction = PlaybackService.ActionNext,
    )
    val builder = Notification.Builder(this, PlaybackService.ChannelId)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(nowPlayingContentIntent())
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
    if (artwork != null) {
        builder.setLargeIcon(artwork)
    }
    val mediaStyle = Notification.MediaStyle().setMediaSession(mediaSession.sessionToken)
    when (stylePreset) {
        MediaNotificationStyle.LargeArtwork -> {
            builder.addAction(playPauseAction)
            builder.addAction(nextAction)
            mediaStyle.setShowActionsInCompactView(0, 1)
        }
        MediaNotificationStyle.Default -> {
            builder.addAction(prevAction)
            builder.addAction(playPauseAction)
            builder.addAction(nextAction)
            mediaStyle.setShowActionsInCompactView(0, 1, 2)
        }
        MediaNotificationStyle.ExtendedControls -> {
            val liked = isTrackCurrentlyLiked(item.trackId)
            val repeatMode = queue.snapshot().repeatMode
            val favoriteAction = notificationAction(
                requestCode = 204,
                iconRes = if (liked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart_outline,
                title = getString(R.string.notification_action_favorite),
                serviceAction = PlaybackService.ActionToggleFavorite,
            )
            val repeatAction = notificationAction(
                requestCode = 205,
                iconRes = repeatIconRes(repeatMode),
                title = getString(repeatLabelRes(repeatMode)),
                serviceAction = PlaybackService.ActionCycleRepeat,
            )
            builder.addAction(prevAction)
            builder.addAction(playPauseAction)
            builder.addAction(nextAction)
            builder.addAction(favoriteAction)
            builder.addAction(repeatAction)
            mediaStyle.setShowActionsInCompactView(0, 1, 2)
        }
    }
    return builder
        .setStyle(mediaStyle)
        .build()
}

private fun PlaybackService.notificationAction(
    requestCode: Int,
    iconRes: Int,
    title: String,
    serviceAction: String,
): Notification.Action {
    val intent = PendingIntent.getService(
        this,
        requestCode,
        PlaybackService.intent(this, serviceAction),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Action.Builder(Icon.createWithResource(this, iconRes), title, intent).build()
}

private fun currentPlaybackTrackId(): String? = when (val current = PlaybackService.state.value) {
    is PlaybackState.Playing -> current.item.trackId
    is PlaybackState.Paused -> current.item.trackId
    is PlaybackState.Preparing -> current.item.trackId
    else -> null
}

private fun PlaybackService.isTrackCurrentlyLiked(trackId: String?): Boolean {
    if (trackId.isNullOrBlank()) return false
    val songId = NeteaseRepository.songIdForTrack(trackId)
    val neteaseLiked = songId != null && songId in NeteaseRepository.likedSongIds.value
    val store = AndroidSyncRuntime.syncStore
    val localLiked = store.favoriteOverrides.value[trackId] ?: (trackId in store.favoriteIds.value)
    return localLiked || neteaseLiked
}

internal fun PlaybackService.toggleCurrentFavoriteAsync() {
    val trackId = currentPlaybackTrackId() ?: return
    scope.launch {
        val store = AndroidSyncRuntime.syncStore
        val track = store.tracks.value.firstOrNull { it.id == trackId }
            ?: store.playbackOnlyTracksFlow.value.firstOrNull { it.id == trackId }
            ?: store.restoredQueueTracksFlow.value[trackId]
        val localLiked = store.favoriteOverrides.value[trackId] ?: store.isFavorite(trackId)
        val songId = NeteaseRepository.songIdForTrack(trackId)
        val neteaseLiked = songId != null && songId in NeteaseRepository.likedSongIds.value
        val nextLiked = !(localLiked || neteaseLiked)
        store.setFavorite(trackId, nextLiked)
        if (songId != null && NeteaseRepository.loggedIn.value) {
            NeteaseRepository.setTrackLiked(applicationContext, track, songId, nextLiked)
        }
        updateNowPlayingTransportState()
        notificationRequests.trySend(Unit)
    }
}

private fun repeatIconRes(mode: RepeatMode): Int = when (mode) {
    RepeatMode.Off -> R.drawable.ic_media_repeat_off
    RepeatMode.All -> R.drawable.ic_media_repeat_all
    RepeatMode.One -> R.drawable.ic_media_repeat_one
}

private fun repeatLabelRes(mode: RepeatMode): Int = when (mode) {
    RepeatMode.Off -> R.string.player_repeat_off
    RepeatMode.All -> R.string.player_repeat_all
    RepeatMode.One -> R.string.player_repeat_one
}

/**
 * 系统歌词分发：只在歌词行变化时更新通知/独立通知/超级岛，
 * 位置 tick 只负责算行号，不产生额外的通知刷新。
 */
internal fun PlaybackService.updateLyricSurfaces() {
    val settings = lyricSettings
    val surfacesEnabled = settings.systemLyricMode != SystemLyricMode.Off ||
        settings.standaloneLyricNotification || settings.hyperosSuperIsland
    if (!surfacesEnabled) return
    val snapshot = PlaybackService.state.value
    val item = when (snapshot) {
        is PlaybackState.Playing -> snapshot.item
        is PlaybackState.Paused -> snapshot.item
        else -> null
    }
    if (item == null) {
        if (lastLyricLineIndex != -1 || currentLyricText.isNotEmpty()) {
            lastLyricLineIndex = -1
            lastLyricPlaying = false
            currentLyricText = ""
            currentNextLyricText = ""
            cancelStandaloneLyricNotification()
            SuperIslandLyrics.stop(this)
            notificationRequests.trySend(Unit)
        }
        return
    }
    val lines = trackedLyricLines
    val positionMs = when (snapshot) {
        is PlaybackState.Playing -> snapshot.positionMs
        is PlaybackState.Paused -> snapshot.positionMs
        else -> 0L
    }
    val offsetMs = if (trackedLyricTrackId == item.trackId) currentLyricOffsetMs else 0L
    val effectivePositionMs = (positionMs - offsetMs).coerceAtLeast(0L)
    val index = if (lines.isEmpty()) {
        -1
    } else {
        lines.indexOfLast { (it.timestampSeconds ?: Float.MAX_VALUE) <= effectivePositionMs / 1_000f }
    }
    val playing = snapshot is PlaybackState.Playing && !snapshot.isBuffering
    if (index == lastLyricLineIndex && item.trackId == lastLyricSurfaceTrackId &&
        playing == lastLyricPlaying
    ) {
        return
    }
    lastLyricLineIndex = index
    lastLyricSurfaceTrackId = item.trackId
    lastLyricPlaying = playing
    currentLyricText = lines.getOrNull(index)?.primary.orEmpty()
    currentNextLyricText = lines.getOrNull(index + 1)?.primary.orEmpty()
    if (settings.systemLyricMode != SystemLyricMode.Off) {
        notificationRequests.trySend(Unit)
    }
    if (settings.standaloneLyricNotification) {
        postStandaloneLyricNotification(item, playing)
    }
    if (settings.hyperosSuperIsland) {
        val lyric = currentLyricText.trim()
        if (lyric.isBlank()) SuperIslandLyrics.stop(this)
        else SuperIslandLyrics.publish(
            this,
            lyric = lyric,
            nextLyric = currentNextLyricText,
            songTitle = item.title,
            artist = item.artist,
            positionMs = effectivePositionMs,
            durationMs = when (snapshot) {
                is PlaybackState.Playing -> snapshot.durationMs
                is PlaybackState.Paused -> snapshot.durationMs
                else -> 0L
            },
            isPlaying = playing,
        )
    }
}

/** 向 Lyricon 中心推送播放器状态、整歌歌词和展示配置（中心未安装时是空操作）。 */
internal fun PlaybackService.syncLyricon() {
    val publisher = lyriconPublisher ?: return
    val snapshot = PlaybackService.state.value
    val item = when (snapshot) {
        is PlaybackState.Playing -> snapshot.item
        is PlaybackState.Paused -> snapshot.item
        is PlaybackState.Preparing -> snapshot.item
        else -> null
    }
    if (item == null) {
        publisher.stop()
        return
    }
    val positionMs: Long
    val durationMs: Long
    val playing: Boolean
    when (snapshot) {
        is PlaybackState.Playing -> {
            positionMs = snapshot.positionMs
            durationMs = snapshot.durationMs
            playing = !snapshot.isBuffering
        }
        is PlaybackState.Paused -> {
            positionMs = snapshot.positionMs
            durationMs = snapshot.durationMs
            playing = false
        }
        else -> {
            positionMs = 0L
            durationMs = 0L
            playing = false
        }
    }
    val offsetMs = if (trackedLyricTrackId == item.trackId) currentLyricOffsetMs else 0L
    publisher.sync(
        trackId = item.trackId,
        title = item.title,
        artist = item.artist,
        durationMs = durationMs,
        lyrics = if (trackedLyricTrackId == item.trackId) trackedLyricLines else emptyList(),
        positionMs = (positionMs - offsetMs).coerceAtLeast(0L),
        playing = playing,
        displayTranslation = lyricSettings.translationVisible,
    )
}

internal fun PlaybackService.clearLyricSurfaces() {
    currentLyricText = ""
    currentNextLyricText = ""
    trackedNativeLyric = null
    lastLyricLineIndex = -1
    lastLyricSurfaceTrackId = null
    lastLyricPlaying = false
    cancelStandaloneLyricNotification()
    SuperIslandLyrics.stop(this)
    notificationRequests.trySend(Unit)
}

private fun PlaybackService.postStandaloneLyricNotification(item: PlaybackItem, playing: Boolean) {
    val lyric = currentLyricText.trim()
    if (lyric.isBlank()) {
        cancelStandaloneLyricNotification()
        return
    }
    ensureLyricsChannel()
    val next = currentNextLyricText.trim()
    val songLabel = listOf(item.title, item.artist).filter(String::isNotBlank).joinToString(" · ")
    val notification = Notification.Builder(this, PlaybackService.LyricsChannelId)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(lyric)
        .setContentText(next.ifBlank { songLabel })
        .setStyle(Notification.BigTextStyle().bigText(if (next.isBlank()) lyric else "$lyric\n$next"))
        .setContentIntent(nowPlayingContentIntent())
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setOngoing(playing)
        .setSound(null)
        .build()
    getSystemService(NotificationManager::class.java).notify(PlaybackService.LyricsNotificationId, notification)
}

internal fun PlaybackService.cancelStandaloneLyricNotification() {
    getSystemService(NotificationManager::class.java).cancel(PlaybackService.LyricsNotificationId)
}

private fun PlaybackService.nowPlayingContentIntent(): PendingIntent = PendingIntent.getActivity(
    this,
    0,
    Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    },
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private fun PlaybackService.createChannel() {
    getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(
            PlaybackService.ChannelId,
            getString(R.string.playback_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
}

private fun PlaybackService.ensureLyricsChannel() {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(PlaybackService.LyricsChannelId) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            PlaybackService.LyricsChannelId,
            getString(R.string.lyrics_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        },
    )
}
