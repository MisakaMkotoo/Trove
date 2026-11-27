package com.exodidio.tune.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import com.exodidio.tune.MainActivity
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.sync.AndroidSyncRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class WidgetDisplayMetadata(
    val trackId: String?,
    val title: String,
    val artist: String,
    val artworkPath: String?,
    val isPlaying: Boolean,
    val hasTrack: Boolean,
)

internal fun formatWidgetMetadata(
    state: PlaybackState,
    defaultTitle: String,
    defaultSubtitle: String,
    unknownArtistLabel: String,
): WidgetDisplayMetadata = when (state) {
    is PlaybackState.Playing -> WidgetDisplayMetadata(
        trackId = state.item.trackId,
        title = state.item.title.ifBlank { defaultTitle },
        artist = state.item.artist.ifBlank { unknownArtistLabel },
        artworkPath = state.item.artworkPath,
        isPlaying = true,
        hasTrack = true,
    )
    is PlaybackState.Paused -> WidgetDisplayMetadata(
        trackId = state.item.trackId,
        title = state.item.title.ifBlank { defaultTitle },
        artist = state.item.artist.ifBlank { unknownArtistLabel },
        artworkPath = state.item.artworkPath,
        isPlaying = false,
        hasTrack = true,
    )
    is PlaybackState.Preparing -> WidgetDisplayMetadata(
        trackId = state.item.trackId,
        title = state.item.title.ifBlank { defaultTitle },
        artist = state.item.artist.ifBlank { unknownArtistLabel },
        artworkPath = state.item.artworkPath,
        isPlaying = true,
        hasTrack = true,
    )
    else -> WidgetDisplayMetadata(
        trackId = null,
        title = defaultTitle,
        artist = defaultSubtitle,
        artworkPath = null,
        isPlaying = false,
        hasTrack = false,
    )
}

class TuneAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        AndroidSyncRuntime.backgroundScope.launch {
            try {
                pushUpdate(context.applicationContext, appWidgetIds)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_LIKE) {
            val pendingResult = goAsync()
            val app = context.applicationContext
            AndroidSyncRuntime.backgroundScope.launch {
                try {
                    withTimeoutOrNull(10_000L) {
                        toggleCurrentTrackFavorite(app)
                    }
                    pushUpdate(app)
                } finally {
                    runCatching { pendingResult.finish() }
                }
            }
        }
    }

    private suspend fun toggleCurrentTrackFavorite(app: Context) {
        AndroidSyncRuntime.initialize(app)
        NeteaseRepository.initialize(app)
        runCatching { AndroidSyncRuntime.syncStore.awaitLoaded() }
        val item = when (val current = PlaybackService.state.value) {
            is PlaybackState.Playing -> current.item
            is PlaybackState.Paused -> current.item
            is PlaybackState.Preparing -> current.item
            else -> null
        } ?: return
        val store = AndroidSyncRuntime.syncStore
        val track = store.tracks.value.firstOrNull { it.id == item.trackId }
            ?: store.playbackOnlyTracksFlow.value.firstOrNull { it.id == item.trackId }
            ?: store.restoredQueueTracksFlow.value[item.trackId]
        val localLiked = store.favoriteOverrides.value[item.trackId]
            ?: store.isFavorite(item.trackId)
        val songId = NeteaseRepository.songIdForTrack(item.trackId)
        val neteaseLiked = songId != null && songId in NeteaseRepository.likedSongIds.value
        val nextLiked = !(localLiked || neteaseLiked)
        store.setFavorite(item.trackId, nextLiked)
        if (songId != null && NeteaseRepository.loggedIn.value) {
            NeteaseRepository.setTrackLiked(app, track, songId, nextLiked)
        }
    }

    companion object {
        internal const val ACTION_TOGGLE_LIKE = "com.exodidio.tune.widget.TOGGLE_LIKE"
        private const val ARTWORK_SIZE_PX = 144
        private val roundedArtworkCache = LruCache<String, Bitmap>(6)

        suspend fun pushUpdate(context: Context, specificIds: IntArray? = null) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = specificIds ?: manager.getAppWidgetIds(
                ComponentName(app, TuneAppWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return

            val snapshot = PlaybackService.state.value
            val meta = formatWidgetMetadata(
                state = snapshot,
                defaultTitle = app.getString(R.string.app_name),
                defaultSubtitle = app.getString(R.string.widget_idle_subtitle),
                unknownArtistLabel = app.getString(R.string.common_unknown_artist),
            )
            val isLiked = resolveCurrentLiked(app, meta.trackId)
            val artworkBitmap = withContext(Dispatchers.IO) {
                loadRoundedArtwork(meta.artworkPath)
            }

            val views = buildRemoteViews(app, meta, isLiked, artworkBitmap)
            manager.updateAppWidget(ids, views)
        }

        private fun resolveCurrentLiked(app: Context, trackId: String?): Boolean {
            if (trackId.isNullOrBlank()) return false
            val songId = NeteaseRepository.songIdForTrack(trackId)
            val neteaseLiked = songId != null && songId in NeteaseRepository.likedSongIds.value
            val localLiked = runCatching {
                AndroidSyncRuntime.initialize(app)
                val store = AndroidSyncRuntime.syncStore
                store.favoriteOverrides.value[trackId] ?: (trackId in store.favoriteIds.value)
            }.getOrDefault(false)
            return localLiked || neteaseLiked
        }

        private fun buildRemoteViews(
            app: Context,
            meta: WidgetDisplayMetadata,
            isLiked: Boolean,
            artworkBitmap: Bitmap?,
        ): RemoteViews {
            val views = RemoteViews(app.packageName, R.layout.widget_playback)
            views.setTextViewText(R.id.widget_title, meta.title)
            views.setTextViewText(R.id.widget_artist, meta.artist)

            if (artworkBitmap != null) {
                views.setImageViewBitmap(R.id.widget_artwork, artworkBitmap)
                views.setViewPadding(R.id.widget_artwork, 0, 0, 0, 0)
            } else {
                views.setImageViewResource(R.id.widget_artwork, R.drawable.ic_widget_note)
                val pad = (12 * app.resources.displayMetrics.density).toInt()
                views.setViewPadding(R.id.widget_artwork, pad, pad, pad, pad)
            }

            views.setImageViewResource(
                R.id.widget_btn_play,
                if (meta.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setImageViewResource(
                R.id.widget_btn_like,
                if (isLiked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart_outline,
            )
            views.setViewVisibility(
                R.id.widget_btn_like,
                if (meta.hasTrack) View.VISIBLE else View.INVISIBLE,
            )

            val openAppIntent = PendingIntent.getActivity(
                app,
                100,
                Intent(app, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent)

            if (meta.hasTrack) {
                views.setOnClickPendingIntent(
                    R.id.widget_btn_prev,
                    serviceActionIntent(app, 101, PlaybackService.ActionPrevious),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_btn_play,
                    serviceActionIntent(app, 102, PlaybackService.ActionToggle),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_btn_next,
                    serviceActionIntent(app, 103, PlaybackService.ActionNext),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_btn_like,
                    PendingIntent.getBroadcast(
                        app,
                        104,
                        Intent(app, TuneAppWidgetProvider::class.java).setAction(ACTION_TOGGLE_LIKE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            } else {
                views.setOnClickPendingIntent(R.id.widget_btn_prev, openAppIntent)
                views.setOnClickPendingIntent(R.id.widget_btn_play, openAppIntent)
                views.setOnClickPendingIntent(R.id.widget_btn_next, openAppIntent)
                views.setOnClickPendingIntent(R.id.widget_btn_like, openAppIntent)
            }
            return views
        }

        private fun serviceActionIntent(context: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                requestCode,
                PlaybackService.intent(context, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun loadRoundedArtwork(path: String?): Bitmap? {
            if (path.isNullOrBlank()) return null
            roundedArtworkCache.get(path)?.let { return it }
            val decoded = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sampleSize = 1
                while (bounds.outWidth / (sampleSize * 2) >= ARTWORK_SIZE_PX &&
                    bounds.outHeight / (sampleSize * 2) >= ARTWORK_SIZE_PX
                ) {
                    sampleSize *= 2
                }
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            }.getOrNull() ?: return null

            val rounded = runCatching {
                val output = Bitmap.createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val rect = Rect(0, 0, ARTWORK_SIZE_PX, ARTWORK_SIZE_PX)
                val rectF = RectF(rect)
                canvas.drawARGB(0, 0, 0, 0)
                canvas.drawRoundRect(rectF, 20f, 20f, paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                val side = minOf(decoded.width, decoded.height)
                val left = (decoded.width - side) / 2
                val top = (decoded.height - side) / 2
                val src = Rect(left, top, left + side, top + side)
                canvas.drawBitmap(decoded, src, rect, paint)
                output
            }.getOrNull()
            decoded.recycle()
            if (rounded != null) roundedArtworkCache.put(path, rounded)
            return rounded
        }
    }
}
