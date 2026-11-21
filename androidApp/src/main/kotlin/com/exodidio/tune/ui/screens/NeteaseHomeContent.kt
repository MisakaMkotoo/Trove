package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteasePlaylistRecord
import com.exodidio.tune.netease.NeteaseRadarSummary
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackRequest
import kotlinx.coroutines.launch
import com.exodidio.tune.ui.components.DiscCard
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.theme.LocalTuneColors

/**
 * 首页：沿用原首页的“分区标题 + 横向卡片”布局，只把歌曲卡片换成网易云歌单卡片。
 * 数据在 App 启动时由 NeteaseRepository.loadHome 自动拉取。
 */
@Composable
internal fun NeteaseHomeContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenPlaylist: (String) -> Unit = {},
) {
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val playlists by NeteaseRepository.activePlaylists.collectAsStateWithLifecycle()
    val homeStatus by NeteaseRepository.homeStatus.collectAsStateWithLifecycle()
    val daily = playlists.firstOrNull { it.id == "dynamic:daily" }
    val radar = playlists.firstOrNull { it.id == "dynamic:radar" }
    val liked = playlists.firstOrNull { it.id == "dynamic:liked" }
    val heartbeatOn by NeteaseRepository.heartbeatEnabled.collectAsStateWithLifecycle()
    val roamOn by NeteaseRepository.roamEnabled.collectAsStateWithLifecycle()
    val heartbeatPreview by NeteaseRepository.heartbeatPreview.collectAsStateWithLifecycle()
    val roamPreview by NeteaseRepository.roamPreview.collectAsStateWithLifecycle()
    val radars by NeteaseRepository.radars.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf("") }
    var loadingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val contextLocal = LocalContext.current
    val cards = remember(daily, radar, liked) {
        listOf(
            daily ?: NeteasePlaylistRecord(id = "dynamic:daily", name = "每日推荐"),
            radar ?: NeteasePlaylistRecord(id = "dynamic:radar", name = "私人雷达"),
            liked ?: NeteasePlaylistRecord(id = "dynamic:liked", name = "我喜欢的音乐"),
            NeteasePlaylistRecord(id = "roam", name = "私人漫游"),
            NeteasePlaylistRecord(id = "heartbeat", name = "心动模式"),
        )
    }

    fun openPlaylist(id: String) {
        if (loadingId != null) return
        // 先进入详情页，条目由页面后台加载（已有条目则直接就绪）。
        NeteaseRepository.select(id)
        onOpenPlaylist(id)
    }

    fun openRadar(radar: NeteaseRadarSummary) {
        if (loadingId != null) return
        NeteaseRepository.stagePlaylist(
            playlistId = radar.id,
            name = radar.name,
            coverUrl = radar.coverUrl,
            coverPath = radar.coverPath,
            trackCount = radar.trackCount,
            dynamicKind = "radar",
        )
        NeteaseRepository.select(radar.id)
        onOpenPlaylist(radar.id)
    }

    fun startHeartbeat() {
        if (loadingId == "heartbeat") return
        loadingId = "heartbeat"
        status = ""
        scope.launch {
            // 当前歌曲（播放/暂停/准备中）能解析出网易云 ID 就用它当种子；
            // 本地歌或没有当前歌曲时交给仓库，用“我喜欢的音乐”最新添加的一首。
            val playbackState = AndroidPlaybackRuntime.controller().state.value
            val currentTrackId = when (playbackState) {
                is com.exodidio.tune.player.PlaybackState.Playing -> playbackState.item.trackId
                is com.exodidio.tune.player.PlaybackState.Paused -> playbackState.item.trackId
                is com.exodidio.tune.player.PlaybackState.Preparing -> playbackState.item.trackId
                else -> null
            }
            val seed = currentTrackId?.let { NeteaseRepository.songIdForTrack(it) }
            val result = NeteaseRepository.startHeartbeat(contextLocal, seed)
            result.fold(
                onSuccess = { trackId ->
                    AndroidPlaybackRuntime.controller().playHeartbeat(PlaybackRequest(listOf(trackId), 0))
                },
                onFailure = { status = it.message ?: "心动模式启动失败" },
            )
            loadingId = null
        }
    }

    fun startRoam() {
        if (loadingId == "roam") return
        loadingId = "roam"
        status = ""
        scope.launch {
            val result = NeteaseRepository.startRoam(contextLocal)
            result.fold(
                onSuccess = { ids ->
                    if (ids.isNotEmpty()) {
                        AndroidPlaybackRuntime.controller().playRoam(PlaybackRequest(ids, 0))
                    }
                },
                onFailure = { status = it.message ?: "漫游启动失败" },
            )
            loadingId = null
        }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        if (!loggedIn) {
            item {
                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.MusicNote,
                        title = stringResource(R.string.netease_playlists_title),
                        description = stringResource(R.string.netease_home_login_hint),
                    )
                }
            }
            return@LazyColumn
        }
        if (homeStatus.isNotBlank()) {
            item {
                Text(
                    text = homeStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalTuneColors.current.textMuted,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        if (status.isNotBlank()) {
            item {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalTuneColors.current.textMuted,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        item {
            HomePlaylistSection(
                title = stringResource(R.string.netease_home_section),
                playlists = cards,
                heartbeatOn = heartbeatOn,
                roamOn = roamOn,
                heartbeatArtwork = heartbeatPreview.artworkPath,
                roamArtwork = roamPreview.artworkPath,
                modeLoadingId = loadingId,
                onClick = { playlist ->
                    when (playlist.id) {
                        "heartbeat" -> startHeartbeat()
                        "roam" -> startRoam()
                        else -> openPlaylist(playlist.id)
                    }
                },
                onLongClick = { playlist ->
                    // 心动/漫游长按关闭；每日推荐/私人雷达统一走详情页菜单。
                    when (playlist.id) {
                        "heartbeat" -> NeteaseRepository.setHeartbeatEnabled(false)
                        "roam" -> NeteaseRepository.setRoamEnabled(false)
                    }
                },
                titleHorizontalPadding = 24.dp,
            )
        }
        item {
            RadarSection(
                title = stringResource(R.string.netease_radar_section),
                radars = radars,
                onOpen = ::openRadar,
                titleHorizontalPadding = 24.dp,
            )
        }
    }
}

@Composable
private fun HomePlaylistSection(
    title: String,
    playlists: List<NeteasePlaylistRecord>,
    heartbeatOn: Boolean,
    roamOn: Boolean,
    heartbeatArtwork: String?,
    roamArtwork: String?,
    modeLoadingId: String?,
    onClick: (NeteasePlaylistRecord) -> Unit,
    onLongClick: (NeteasePlaylistRecord) -> Unit,
    titleHorizontalPadding: Dp,
) {
    val colors = LocalTuneColors.current
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
            modifier = Modifier.padding(horizontal = titleHorizontalPadding, vertical = 4.dp),
        )
        // 单行横向滚动：新增入口只往后排，不换行。
        LazyHorizontalGrid(
            rows = GridCells.Fixed(1),
            modifier = Modifier.fillMaxWidth().height(182.dp),
            contentPadding = PaddingValues(horizontal = titleHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            gridItems(items = playlists, key = { it.id }, contentType = { "home_playlist" }) { playlist ->
                val isHeartbeat = playlist.id == "heartbeat"
                val isRoam = playlist.id == "roam"
                DiscCard(
                    title = when {
                        isHeartbeat -> stringResource(R.string.netease_heartbeat)
                        isRoam -> stringResource(R.string.netease_roam)
                        else -> playlist.name
                    },
                    subtitle = when {
                        isHeartbeat && modeLoadingId == "heartbeat" -> stringResource(R.string.netease_home_loading)
                        isHeartbeat && heartbeatOn -> stringResource(R.string.netease_heartbeat_on)
                        isHeartbeat -> stringResource(R.string.netease_heartbeat_hint)
                        isRoam && modeLoadingId == "roam" -> stringResource(R.string.netease_home_loading)
                        isRoam && roamOn -> stringResource(R.string.netease_roam_on)
                        isRoam -> stringResource(R.string.netease_roam_hint)
                        else -> stringResource(
                            R.string.netease_playlist_entry_count,
                            if (playlist.entries.isNotEmpty()) playlist.entries.size else playlist.remoteTrackCount,
                        )
                    },
                    artworkPath = when {
                        isHeartbeat -> heartbeatArtwork ?: playlist.coverPath
                        isRoam -> roamArtwork ?: playlist.coverPath
                        else -> playlist.coverPath
                    },
                    fallbackSymbol = when {
                        isHeartbeat -> MaterialSymbols.Bolt
                        isRoam -> MaterialSymbols.Sensors
                        else -> MaterialSymbols.QueueMusic
                    },
                    modifier = Modifier.width(128.dp),
                    onClick = { onClick(playlist) },
                    onLongClick = { onLongClick(playlist) },
                )
            }
        }
    }
}

/** 雷达歌单：第二个横向容器，固定单行，可横向滚动。 */
@Composable
private fun RadarSection(
    title: String,
    radars: List<NeteaseRadarSummary>,
    onOpen: (NeteaseRadarSummary) -> Unit,
    titleHorizontalPadding: Dp,
) {
    if (radars.isEmpty()) return
    val colors = LocalTuneColors.current
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
            modifier = Modifier.padding(horizontal = titleHorizontalPadding, vertical = 4.dp),
        )
        LazyHorizontalGrid(
            rows = GridCells.Fixed(1),
            modifier = Modifier.fillMaxWidth().height(182.dp),
            contentPadding = PaddingValues(horizontal = titleHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            gridItems(items = radars, key = { it.id }, contentType = { "radar_playlist" }) { radar ->
                DiscCard(
                    title = radar.name,
                    subtitle = stringResource(R.string.netease_playlist_entry_count, radar.trackCount),
                    artworkPath = radar.coverPath,
                    fallbackSymbol = MaterialSymbols.Sensors,
                    modifier = Modifier.width(128.dp),
                    onClick = { onOpen(radar) },
                )
            }
        }
    }
}
