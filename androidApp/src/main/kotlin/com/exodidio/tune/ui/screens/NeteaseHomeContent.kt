package com.exodidio.tune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.homeGreetingTitleRes
import com.exodidio.tune.netease.NeteasePlaylistRecord
import com.exodidio.tune.netease.NeteaseRadarSummary
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.RADAR_DISPLAY_NAME
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.exodidio.tune.ui.components.DiscCard
import com.exodidio.tune.ui.components.FeaturedPlaylistCard
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.theme.LocalTuneColors
import java.time.LocalTime

/**
 * 首页：沿用原首页的“分区标题 + 横向卡片”布局，只把歌曲卡片换成网易云歌单卡片。
 * 数据在 App 启动时由 NeteaseRepository.loadHome 自动拉取。
 */
@Composable
internal fun NeteaseHomeContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    rowStates: NeteaseRowStates = remember { NeteaseRowStates() },
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onPlayTracks: (List<String>, Int) -> Unit = { _, _ -> },
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    onOpenAccount: () -> Unit = {},
) {
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val playlists by NeteaseRepository.activePlaylists.collectAsStateWithLifecycle()
    val homeStatus by NeteaseRepository.homeStatus.collectAsStateWithLifecycle()
    val daily = playlists.firstOrNull { it.id == "dynamic:daily" }
    val radar = playlists.firstOrNull { it.id == "dynamic:radar" }
    val heartbeatOn by NeteaseRepository.heartbeatEnabled.collectAsStateWithLifecycle()
    val roamOn by NeteaseRepository.roamEnabled.collectAsStateWithLifecycle()
    val heartbeatPreview by NeteaseRepository.heartbeatPreview.collectAsStateWithLifecycle()
    val roamPreview by NeteaseRepository.roamPreview.collectAsStateWithLifecycle()
    val radars by NeteaseRepository.radars.collectAsStateWithLifecycle()
    val feed by NeteaseRepository.homeFeed.collectAsStateWithLifecycle()
    val playbackOnlyTracks by AndroidSyncRuntime.syncStore.playbackOnlyTracksFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val feedSections = remember(feed.sections, playbackOnlyTracks) {
        val artworkById = playbackOnlyTracks.associate { it.id to it.artworkPath }
        feed.sections.map { section -> section.copy(songs = section.songs.withFeedArtwork(artworkById)) }
    }
    val feedArtistNew = remember(feed.artistNew, playbackOnlyTracks) {
        val artworkById = playbackOnlyTracks.associate { it.id to it.artworkPath }
        feed.artistNew?.let { section -> section.copy(songs = section.songs.withFeedArtwork(artworkById)) }
    }
    // 长按菜单的结果只贴在它所属的歌单分区下，不再放到页面顶部。
    var actionStatus by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loadingId by remember { mutableStateOf<String?>(null) }
    var heartbeatError by remember { mutableStateOf<String?>(null) }
    var roamError by remember { mutableStateOf<String?>(null) }
    var actionTarget by remember { mutableStateOf<PlaylistActionTarget?>(null) }
    val scope = rememberCoroutineScope()
    val contextLocal = LocalContext.current
    val cards = remember(daily, radar, contextLocal) {
        listOf(
            daily ?: NeteasePlaylistRecord(id = "dynamic:daily", name = contextLocal.getString(R.string.netease_daily_recommend)),
            radar ?: NeteasePlaylistRecord(id = "dynamic:radar", name = contextLocal.getString(R.string.netease_radar)),
            NeteasePlaylistRecord(id = "roam", name = contextLocal.getString(R.string.netease_roam)),
            NeteasePlaylistRecord(id = "heartbeat", name = contextLocal.getString(R.string.netease_heartbeat)),
        )
    }
    val featuredIds = remember(cards) { cards.mapTo(mutableSetOf()) { it.id } }
    val radarIds = remember(radars) { radars.mapTo(mutableSetOf()) { it.id } }
    val recommendedIds = remember(feed.recommended) { feed.recommended.mapTo(mutableSetOf()) { it.id } }
    fun actionError(ids: Set<String>): String? =
        actionStatus?.takeIf { it.first in ids }?.second

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
        heartbeatError = null
        actionStatus = null
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
            val stateBefore = AndroidPlaybackRuntime.controller().state.value
            val result = NeteaseRepository.startHeartbeat(contextLocal, seed)
            result.fold(
                onSuccess = { trackId ->
                    System.err.println("MODE heartbeat before=${stateBefore::class.simpleName} id=$trackId")
                    AndroidPlaybackRuntime.controller().playHeartbeat(PlaybackRequest(listOf(trackId), 0))
                    delay(900)
                    System.err.println("MODE heartbeat after=${AndroidPlaybackRuntime.controller().state.value::class.simpleName}")
                },
                onFailure = { heartbeatError = it.message ?: contextLocal.getString(R.string.netease_heartbeat_start_failed) },
            )
            loadingId = null
        }
    }

    fun startRoam() {
        if (loadingId == "roam") return
        loadingId = "roam"
        roamError = null
        actionStatus = null
        scope.launch {
            val stateBefore = AndroidPlaybackRuntime.controller().state.value
            val result = NeteaseRepository.startRoam(contextLocal)
            result.fold(
                onSuccess = { ids ->
                    if (ids.isNotEmpty()) {
                        System.err.println("MODE roam before=${stateBefore::class.simpleName} ids=${ids.size} first=${ids.first()}")
                        AndroidPlaybackRuntime.controller().playRoam(PlaybackRequest(ids, 0))
                        delay(900)
                        System.err.println("MODE roam after=${AndroidPlaybackRuntime.controller().state.value::class.simpleName}")
                    }
                },
                onFailure = { roamError = it.message ?: contextLocal.getString(R.string.netease_roam_start_failed) },
            )
            loadingId = null
        }
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item(key = "home-greeting") {
            Text(
                text = stringResource(homeGreetingTitleRes(LocalTime.now().hour)),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 34.sp,
                    lineHeight = 41.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = LocalTuneColors.current.textMain,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            )
        }
        if (!loggedIn) {
            item {
                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.MusicNote,
                        title = stringResource(R.string.netease_playlists_title),
                        description = stringResource(R.string.netease_home_login_hint),
                        modifier = Modifier.clickable(onClick = onOpenAccount),
                        bottomContent = {
                            Text(
                                text = stringResource(R.string.netease_login_go),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalTuneColors.current.primary,
                                // bottomContent 在卡片内边距之外，需自己留出圆角安全区。
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                            )
                        },
                    )
                }
            }
            return@LazyColumn
        }
        item {
            HomePlaylistSection(
                title = stringResource(R.string.netease_home_section),
                playlists = cards,
                rowState = rowStates.state("home_featured"),
                heartbeatOn = heartbeatOn,
                roamOn = roamOn,
                heartbeatArtwork = heartbeatPreview.artworkPath,
                roamArtwork = roamPreview.artworkPath,
                heartbeatError = heartbeatError,
                roamError = roamError,
                modeLoadingId = loadingId,
                errorText = listOfNotNull(homeStatus.ifBlank { null }, actionError(featuredIds))
                    .joinToString("\n"),
                onClick = { playlist ->
                    when (playlist.id) {
                        "heartbeat" -> startHeartbeat()
                        "roam" -> startRoam()
                        else -> openPlaylist(playlist.id)
                    }
                },
                onLongClick = { playlist ->
                    // 心动/漫游长按关闭；其余歌单弹出刷新/下载全部/保存菜单。
                    when (playlist.id) {
                        "heartbeat" -> NeteaseRepository.setHeartbeatEnabled(false)
                        "roam" -> NeteaseRepository.setRoamEnabled(false)
                        else -> actionTarget = PlaylistActionTarget(
                            id = playlist.id,
                            name = playlist.name,
                            coverUrl = playlist.coverUrl,
                            coverPath = playlist.coverPath,
                        )
                    }
                },
                titleHorizontalPadding = 20.dp,
            )
        }
        item {
            RadarSection(
                title = stringResource(R.string.netease_radar_section),
                radars = radars,
                rowState = rowStates.state("home_radar"),
                onOpen = ::openRadar,
                onLongPress = { radar ->
                    actionTarget = PlaylistActionTarget(radar.id, radar.name, radar.coverUrl, radar.coverPath)
                },
                titleHorizontalPadding = 20.dp,
                errorText = actionError(radarIds),
            )
        }
        if (!feed.loaded) {
            // 首页进来的首屏先占住分区容器，数据到了原位替换。
            if (feed.error == null) {
                item("home_feed_skeleton_songs") { NeteaseSongGridSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp) }
                item("home_feed_skeleton_playlists") { NeteasePlaylistRowSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp) }
                item("home_feed_skeleton_artist") { NeteaseSongGridSkeleton(horizontalPadding = 20.dp, titleTopPadding = 0.dp) }
            } else {
                item("home_feed_error") {
                    Text(
                        text = stringResource(R.string.discover_error, feed.error.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalTuneColors.current.textMuted,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        } else {
            feedSections.forEachIndexed { index, section ->
                item(key = "home_feed_section_$index") {
                    NeteaseSongGridSection(
                        title = section.titleRes?.let { stringResource(it) }
                            ?: section.title.ifBlank { stringResource(R.string.home_feed_fallback_title) },
                        songs = section.songs,
                        playbackQueue = playbackQueue,
                        onPlayTracks = onPlayTracks,
                        onTrackPlayNext = onTrackPlayNext,
                        onTrackAddToQueue = onTrackAddToQueue,
                        onTrackFavoriteToggle = onTrackFavoriteToggle,
                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                        horizontalPadding = 20.dp,
                        titleTopPadding = 0.dp,
                        horizontalState = rowStates.gridState("home_feed_section_$index"),
                    )
                }
            }
            if (feed.recommended.isNotEmpty()) {
                item("home_feed_recommended") {
                    NeteasePlaylistCardRow(
                        title = stringResource(R.string.discover_section_recommended),
                        playlists = feed.recommended,
                        fallbackSubtitle = stringResource(R.string.discover_recommended_subtitle),
                        onOpen = { playlist ->
                            NeteaseRepository.stagePlaylist(
                                playlistId = playlist.id,
                                name = playlist.name,
                                coverUrl = playlist.coverUrl,
                                coverPath = playlist.coverPath,
                                trackCount = 0,
                            )
                            openPlaylist(playlist.id)
                        },
                        onLongClick = { playlist ->
                            actionTarget = PlaylistActionTarget(playlist.id, playlist.name, playlist.coverUrl, playlist.coverPath)
                        },
                        horizontalPadding = 20.dp,
                        titleTopPadding = 0.dp,
                        horizontalState = rowStates.state("home_feed_recommended"),
                        errorText = actionError(recommendedIds),
                    )
                }
            }
            feedArtistNew?.let { section ->
                item("home_feed_artist_new") {
                    NeteaseSongGridSection(
                        title = section.title.ifBlank { stringResource(R.string.home_feed_fallback_title) },
                        songs = section.songs,
                        playbackQueue = playbackQueue,
                        onPlayTracks = onPlayTracks,
                        onTrackPlayNext = onTrackPlayNext,
                        onTrackAddToQueue = onTrackAddToQueue,
                        onTrackFavoriteToggle = onTrackFavoriteToggle,
                        onTrackContextBottomSheet = onTrackContextBottomSheet,
                        horizontalPadding = 20.dp,
                        titleTopPadding = 0.dp,
                        horizontalState = rowStates.gridState("home_feed_artist_new"),
                    )
                }
            }
            if (feed.error != null) {
                // 部分分区失败时保留已拿到的内容，但把失败原因挂在同一区域末尾。
                item("home_feed_partial_error") {
                    Text(
                        text = stringResource(R.string.discover_error, feed.error.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalTuneColors.current.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    actionTarget?.let { target ->
        NeteasePlaylistActionSheet(
            target = target,
            onDismiss = { actionTarget = null },
            onRefresh = {
                actionTarget = null
                scope.launch {
                    stageActionTarget(target)
                    actionStatus = NeteaseRepository.refresh(contextLocal, target.id)
                        .exceptionOrNull()?.message?.let { target.id to it }
                }
            },
            onDownloadAll = {
                actionTarget = null
                scope.launch {
                    stageActionTarget(target)
                    NeteaseRepository.ensureDynamicLoaded(contextLocal, target.id)
                    NeteaseRepository.downloadAll(contextLocal, target.id)
                }
            },
            onSave = {
                actionTarget = null
                scope.launch {
                    stageActionTarget(target)
                    val saved = NeteaseRepository.saveSnapshot(target.id)
                    actionStatus = if (saved == null) target.id to contextLocal.getString(R.string.netease_save_failed) else null
                }
            },
        )
    }
}

private fun stageActionTarget(target: PlaylistActionTarget) {
    if (NeteaseRepository.playlistById(target.id) == null) {
        NeteaseRepository.stagePlaylist(
            playlistId = target.id,
            name = target.name,
            coverUrl = target.coverUrl,
            coverPath = target.coverPath,
            trackCount = 0,
            dynamicKind = if (target.name.contains(RADAR_DISPLAY_NAME)) "radar" else null,
        )
    }
}

private fun List<LibraryTrack>.withFeedArtwork(artworkById: Map<String, String?>): List<LibraryTrack> =
    if (artworkById.isEmpty()) {
        this
    } else {
        map { track ->
            val artwork = artworkById[track.id]
            if (artwork != null && artwork != track.artworkPath) track.copy(artworkPath = artwork) else track
        }
    }

@Composable
private fun HomePlaylistSection(
    title: String,
    playlists: List<NeteasePlaylistRecord>,
    rowState: LazyListState,
    heartbeatOn: Boolean,
    roamOn: Boolean,
    heartbeatArtwork: String?,
    roamArtwork: String?,
    heartbeatError: String?,
    roamError: String?,
    modeLoadingId: String?,
    errorText: String?,
    onClick: (NeteasePlaylistRecord) -> Unit,
    onLongClick: (NeteasePlaylistRecord) -> Unit,
    titleHorizontalPadding: Dp,
) {
    val colors = LocalTuneColors.current
    val dailyIntro = stringResource(R.string.netease_daily_intro)
    val radarIntro = stringResource(R.string.netease_radar_intro)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            color = colors.textMain,
            modifier = Modifier.padding(horizontal = titleHorizontalPadding, vertical = 4.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = rowState,
            contentPadding = PaddingValues(horizontal = titleHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = playlists, key = { it.id }) { playlist ->
                val isHeartbeat = playlist.id == "heartbeat"
                val isRoam = playlist.id == "roam"
                FeaturedPlaylistCard(
                    title = when {
                        isHeartbeat -> stringResource(R.string.netease_heartbeat)
                        isRoam -> stringResource(R.string.netease_roam)
                        else -> playlist.name
                    },
                    subtitle = when {
                        isHeartbeat && modeLoadingId == "heartbeat" -> stringResource(R.string.netease_home_loading)
                        isHeartbeat && heartbeatError != null -> heartbeatError
                        isHeartbeat && heartbeatOn -> stringResource(R.string.netease_heartbeat_on)
                        isHeartbeat -> stringResource(R.string.netease_heartbeat_hint)
                        isRoam && modeLoadingId == "roam" -> stringResource(R.string.netease_home_loading)
                        isRoam && roamError != null -> roamError
                        isRoam && roamOn -> stringResource(R.string.netease_roam_on)
                        isRoam -> stringResource(R.string.netease_roam_hint)
                        else -> playlist.description.ifBlank {
                            when (playlist.id) {
                                "dynamic:daily" -> dailyIntro
                                "dynamic:radar" -> radarIntro
                                else -> playlist.name
                            }
                        }
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
                    // 固定卡片尺寸，与内容长度无关。
                    modifier = Modifier
                        .width(227.dp)
                        .height(320.dp),
                    onClick = { onClick(playlist) },
                    onLongClick = { onLongClick(playlist) },
                )
            }
        }
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(start = titleHorizontalPadding, end = titleHorizontalPadding, top = 8.dp),
            )
        }
    }
}

/** 雷达歌单：第二个横向容器，固定单行，可横向滚动。 */
@Composable
private fun RadarSection(
    title: String,
    radars: List<NeteaseRadarSummary>,
    rowState: LazyListState,
    onOpen: (NeteaseRadarSummary) -> Unit,
    onLongPress: (NeteaseRadarSummary) -> Unit,
    titleHorizontalPadding: Dp,
    errorText: String? = null,
) {
    if (radars.isEmpty()) return
    val colors = LocalTuneColors.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // Apple 首页标准网格：卡片约为屏宽的 40%，封面圆角约 7dp。
    val cardWidth = (screenWidthDp * 0.40f).coerceIn(140f, 180f).dp
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            color = colors.textMain,
            modifier = Modifier.padding(horizontal = titleHorizontalPadding, vertical = 4.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = rowState,
            contentPadding = PaddingValues(horizontal = titleHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = radars, key = { it.id }) { radar ->
                DiscCard(
                    title = radar.name,
                    subtitle = radar.description.ifBlank { stringResource(R.string.netease_radar_intro) },
                    artworkPath = radar.coverPath,
                    artworkUrl = radar.coverUrl,
                    fallbackSymbol = MaterialSymbols.Sensors,
                    artworkShape = RoundedCornerShape(7.dp),
                    titleStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    subtitleStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    modifier = Modifier.width(cardWidth),
                    onClick = { onOpen(radar) },
                    onLongClick = { onLongPress(radar) },
                )
            }
        }
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(start = titleHorizontalPadding, end = titleHorizontalPadding, top = 8.dp),
            )
        }
    }
}
