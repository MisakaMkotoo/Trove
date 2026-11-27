package com.exodidio.tune.player

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.exodidio.tune.MainActivity
import com.exodidio.tune.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlaybackQuickTileService : TileService() {

    private var listenerScope: CoroutineScope? = null
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenerScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listenerScope = scope
        stateJob = scope.launch {
            PlaybackService.state.collect { snapshot ->
                updateTileForState(snapshot)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        listenerScope?.cancel()
        listenerScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val snapshot = PlaybackService.state.value
        val hasTrack = snapshot is PlaybackState.Playing ||
            snapshot is PlaybackState.Paused ||
            snapshot is PlaybackState.Preparing
        if (hasTrack) {
            startForegroundService(PlaybackService.intent(this, PlaybackService.ActionToggle))
            return
        }
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                201,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            launchTileActivityLegacy(launchIntent)
        }
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchTileActivityLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun updateTileForState(snapshot: PlaybackState) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_monochrome)
        when (snapshot) {
            is PlaybackState.Playing -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = snapshot.item.title.ifBlank { getString(R.string.tile_playback_label) }
                tile.subtitle = snapshot.item.artist.ifBlank { getString(R.string.common_unknown_artist) }
            }
            is PlaybackState.Preparing -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = snapshot.item.title.ifBlank { getString(R.string.tile_playback_label) }
                tile.subtitle = snapshot.item.artist.ifBlank { getString(R.string.netease_loading) }
            }
            is PlaybackState.Paused -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = snapshot.item.title.ifBlank { getString(R.string.tile_playback_label) }
                tile.subtitle = getString(
                    R.string.tile_playback_paused,
                    snapshot.item.artist.ifBlank { getString(R.string.common_unknown_artist) },
                )
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_playback_label)
                tile.subtitle = getString(R.string.tile_tap_to_open)
            }
        }
        tile.updateTile()
    }
}
