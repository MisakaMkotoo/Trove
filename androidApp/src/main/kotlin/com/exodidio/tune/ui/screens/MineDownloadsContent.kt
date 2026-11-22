package com.exodidio.tune.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack

/** 用户明确点过下载的网易云歌曲（导入曲库、有本地文件，不含流缓存）。 */
@Composable
internal fun rememberNeteaseDownloadedTracks(): List<LibraryTrack> {
    val tracks by AndroidSyncRuntime.syncStore.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(tracks) {
        tracks.filter(NeteaseRepository::isExplicitlyDownloaded)
            .sortedByDescending { it.updatedAt }
    }
}
