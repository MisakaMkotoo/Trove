package com.exodidio.tune.ui.screens

import android.util.Log
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.homeGreetingTitleRes
import com.exodidio.tune.netease.DiscoverPlaylist
import com.exodidio.tune.netease.NeteasePlaylistRecord
import com.exodidio.tune.netease.NeteaseRadarSummary
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.player.AndroidPlaybackRuntime
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.player.PlaybackState
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import kotlinx.coroutines.launch
import com.exodidio.tune.ui.components.AnchoredPopupMenu
import com.exodidio.tune.ui.components.ContextActionMenu
import com.exodidio.tune.ui.components.ContextActionMenuEntry
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
    onOpenRecommendedPlaylists: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
) {
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val playlists by NeteaseRepository.activePlaylists.collectAsStateWithLifecycle()
    val homeStatus by NeteaseRepository.homeStatus.collectAsStateWithLifecycle()
    val daily = playlists.firstOrNull { it.id == "dynamic:daily" }
    val radar = playlists.firstOrNull { it.id == "dynamic:radar" }
    val heartbeatOn by NeteaseRepository.heartbeatEnabled.collectAsStateWithLifecycle()
    val roamOn by NeteaseRepository.roamEnabled.collectAsStateWithLifecycle()
    val similarOn by NeteaseRepository.similarEnabled.collectAsStateWithLifecycle()
    val heartbeatPreview by NeteaseRepository.heartbeatPreview.collectAsStateWithLifecycle()
    val roamPreview by NeteaseRepository.roamPreview.collectAsStateWithLifecycle()
    val similarPreview by NeteaseRepository.similarPreview.collectAsStateWithLifecycle()
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
    var similarError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val contextLocal = LocalContext.current
    val dailyRefreshedToast = stringResource(R.string.netease_daily_refreshed)
    val playbackState by AndroidPlaybackRuntime.controller().state.collectAsStateWithLifecycle()
    val currentPlaybackTrackId = when (val state = playbackState) {
        is PlaybackState.Playing -> state.item.trackId
        is PlaybackState.Paused -> state.item.trackId
        is PlaybackState.Preparing -> state.item.trackId
        else -> null
    }
    val currentHeartbeatSeed = remember(currentPlaybackTrackId) {
        currentPlaybackTrackId?.let(NeteaseRepository::songIdForTrack)
    }
    LaunchedEffect(loggedIn) { if (loggedIn) NeteaseRepository.loadMine() }
    LaunchedEffect(loggedIn, heartbeatOn, currentHeartbeatSeed) {
        if (loggedIn && !heartbeatOn) {
            NeteaseRepository.prefetchHeartbeatPreview(contextLocal, currentHeartbeatSeed)
        }
    }
    LaunchedEffect(loggedIn, similarOn, currentHeartbeatSeed) {
        if (loggedIn && !similarOn) {
            NeteaseRepository.prefetchSimilarPreview(contextLocal, currentHeartbeatSeed)
        }
    }
    val cards = remember(daily, radar, contextLocal) {
        listOf(
            daily ?: NeteasePlaylistRecord(id = "dynamic:daily", name = contextLocal.getString(R.string.netease_daily_recommend)),
            radar ?: NeteasePlaylistRecord(id = "dynamic:radar", name = contextLocal.getString(R.string.netease_radar)),
            NeteasePlaylistRecord(id = "roam", name = contextLocal.getString(R.string.netease_roam)),
            NeteasePlaylistRecord(id = "heartbeat", name = contextLocal.getString(R.string.netease_heartbeat)),
            NeteasePlaylistRecord(id = "similar", name = contextLocal.getString(R.string.netease_similar_mode)),
        )
    }
    val featuredIds = remember(cards) { cards.mapTo(mutableSetOf()) { it.id } }
    val radarIds = remember(radars) { radars.mapTo(mutableSetOf()) { it.id } }
    val recommendedIds = remember(feed.recommended) { feed.recommended.mapTo(mutableSetOf()) { it.id } }
    val exclusiveIds = remember(feed.exclusivePlaylists) { feed.exclusivePlaylists.mapTo(mutableSetOf()) { it.id } }
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
            val seed = currentHeartbeatSeed
            val stateBefore = playbackState
            val result = NeteaseRepository.startHeartbeat(contextLocal, seed)
            result.fold(
                onSuccess = { trackId ->
                    Log.d("NeteaseMode", "heartbeat before=${stateBefore::class.simpleName} id=$trackId")
                    AndroidPlaybackRuntime.controller().playHeartbeat(PlaybackRequest(listOf(trackId), 0))
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
                        Log.d("NeteaseMode", "roam before=${stateBefore::class.simpleName} ids=${ids.size} first=${ids.first()}")
                        AndroidPlaybackRuntime.controller().playRoam(PlaybackRequest(ids, 0))
                    }
                },
                onFailure = { roamError = it.message ?: contextLocal.getString(R.string.netease_roam_start_failed) },
            )
            loadingId = null
        }
    }

    fun startSimilar() {
        if (loadingId == "similar") return
        loadingId = "similar"
        similarError = null
        actionStatus = null
        scope.launch {
            val seed = currentHeartbeatSeed
            val stateBefore = AndroidPlaybackRuntime.controller().state.value
            val result = NeteaseRepository.startSimilar(contextLocal, seed)
            result.fold(
                onSuccess = { ids ->
                    if (ids.isNotEmpty()) {
                        Log.d("NeteaseMode", "similar before=${stateBefore::class.simpleName} ids=${ids.size} first=${ids.first()}")
                        AndroidPlaybackRuntime.controller().playSimilar(PlaybackRequest(ids, 0))
                    }
                },
                onFailure = { similarError = it.message ?: contextLocal.getString(R.string.netease_similar_start_failed) },
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
                similarOn = similarOn,
                heartbeatArtwork = heartbeatPreview.artworkPath,
                roamArtwork = roamPreview.artworkPath,
                similarArtwork = similarPreview.artworkPath,
                heartbeatError = heartbeatError,
                roamError = roamError,
                similarError = similarError,
                modeLoadingId = loadingId,
                errorText = listOfNotNull(homeStatus.ifBlank { null }, actionError(featuredIds))
                    .joinToString("\n"),
                onClick = { playlist ->
                    when (playlist.id) {
                        "heartbeat" -> startHeartbeat()
                        "roam" -> startRoam()
                        "similar" -> startSimilar()
                        else -> openPlaylist(playlist.id)
                    }
                },
                onDailyRefresh = {
                    scope.launch {
                        if (NeteaseRepository.playlistById(NeteaseRepository.DAILY_ID) == null) {
                            NeteaseRepository.stagePlaylist(
                                playlistId = NeteaseRepository.DAILY_ID,
                                name = contextLocal.getString(R.string.netease_daily_recommend),
                                coverUrl = "",
                                coverPath = null,
                                trackCount = 0,
                                cacheToDisk = true,
                            )
                        }
                        val res = NeteaseRepository.refreshDailyRecommend(contextLocal)
                        actionStatus = res.exceptionOrNull()?.message?.let { NeteaseRepository.DAILY_ID to it }
                        if (res.isSuccess) {
                            Toast.makeText(contextLocal, dailyRefreshedToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onStartHeartbeat = ::startHeartbeat,
                onStopHeartbeat = { NeteaseRepository.setHeartbeatEnabled(false) },
                onStartRoam = ::startRoam,
                onStopRoam = { NeteaseRepository.setRoamEnabled(false) },
                onStartSimilar = ::startSimilar,
                onStopSimilar = { NeteaseRepository.setSimilarEnabled(false) },
                onActionError = { id, err -> actionStatus = err?.let { id to it } },
                titleHorizontalPadding = 20.dp,
            )
        }
        item {
            RadarSection(
                title = stringResource(R.string.netease_radar_section),
                radars = radars,
                rowState = rowStates.state("home_radar"),
                onOpen = ::openRadar,
                onActionError = { id, err -> actionStatus = err?.let { id to it } },
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
                        onActionError = { id, err -> actionStatus = err?.let { id to it } },
                        horizontalPadding = 20.dp,
                        titleTopPadding = 0.dp,
                        horizontalState = rowStates.state("home_feed_recommended"),
                        onTitleClick = onOpenRecommendedPlaylists,
                        errorText = actionError(recommendedIds),
                    )
                }
            }
            if (feed.exclusivePlaylists.isNotEmpty()) {
                item("home_feed_exclusive_playlists") {
                    NeteasePlaylistCardRow(
                        title = feed.exclusiveTitle.ifBlank { stringResource(R.string.home_section_exclusive_playlists) },
                        playlists = feed.exclusivePlaylists,
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
                        onActionError = { id, err -> actionStatus = err?.let { id to it } },
                        horizontalPadding = 20.dp,
                        titleTopPadding = 0.dp,
                        horizontalState = rowStates.state("home_feed_exclusive_playlists"),
                        onTitleClick = onOpenRecommendedPlaylists,
                        errorText = actionError(exclusiveIds),
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
    similarOn: Boolean,
    heartbeatArtwork: String?,
    roamArtwork: String?,
    similarArtwork: String?,
    heartbeatError: String?,
    roamError: String?,
    similarError: String?,
    modeLoadingId: String?,
    errorText: String?,
    onClick: (NeteasePlaylistRecord) -> Unit,
    onDailyRefresh: () -> Unit,
    onStartHeartbeat: () -> Unit,
    onStopHeartbeat: () -> Unit,
    onStartRoam: () -> Unit,
    onStopRoam: () -> Unit,
    onStartSimilar: () -> Unit,
    onStopSimilar: () -> Unit,
    onActionError: (String, String?) -> Unit,
    titleHorizontalPadding: Dp,
) {
    val colors = LocalTuneColors.current
    val dailyIntro = stringResource(R.string.netease_daily_intro)
    val radarIntro = stringResource(R.string.netease_radar_intro)
    var contextFeaturedId by remember { mutableStateOf<String?>(null) }
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
                val isSimilar = playlist.id == "similar"
                val cardContent: @Composable () -> Unit = {
                    FeaturedPlaylistCard(
                        title = when {
                            isHeartbeat -> stringResource(R.string.netease_heartbeat)
                            isRoam -> stringResource(R.string.netease_roam)
                            isSimilar -> stringResource(R.string.netease_similar_mode)
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
                            isSimilar && modeLoadingId == "similar" -> stringResource(R.string.netease_home_loading)
                            isSimilar && similarError != null -> similarError
                            isSimilar && similarOn -> stringResource(R.string.netease_similar_on)
                            isSimilar -> stringResource(R.string.netease_similar_hint)
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
                            isSimilar -> similarArtwork ?: playlist.coverPath
                            else -> playlist.coverPath
                        },
                        fallbackSymbol = when {
                            isHeartbeat -> MaterialSymbols.Bolt
                            isRoam -> MaterialSymbols.Sensors
                            isSimilar -> MaterialSymbols.MusicNote
                            else -> MaterialSymbols.QueueMusic
                        },
                        // 固定卡片尺寸，与内容长度无关。
                        modifier = Modifier
                            .width(227.dp)
                            .height(320.dp),
                        onClick = { onClick(playlist) },
                        onLongClick = { contextFeaturedId = playlist.id },
                    )
                }
                if (isHeartbeat || isRoam || isSimilar) {
                    val modeLabel = stringResource(
                        when {
                            isHeartbeat && heartbeatOn -> R.string.netease_heartbeat_stop
                            isHeartbeat -> R.string.netease_heartbeat_start
                            isRoam && roamOn -> R.string.netease_roam_stop
                            isRoam -> R.string.netease_roam_start
                            similarOn -> R.string.netease_similar_stop
                            else -> R.string.netease_similar_start
                        },
                    )
                    val isStopAction = (isHeartbeat && heartbeatOn) || (isRoam && roamOn) || (isSimilar && similarOn)
                    val modeSymbol = when {
                        isStopAction -> MaterialSymbols.Close
                        isHeartbeat -> MaterialSymbols.Bolt
                        isRoam -> MaterialSymbols.Sensors
                        else -> MaterialSymbols.MusicNote
                    }
                    AnchoredPopupMenu(
                        expanded = contextFeaturedId == playlist.id,
                        onDismissRequest = { if (contextFeaturedId == playlist.id) contextFeaturedId = null },
                        width = 272.dp,
                        offset = DpOffset(x = (-8).dp, y = 8.dp),
                        anchor = cardContent,
                    ) {
                        ContextActionMenu(
                            listOf(
                                ContextActionMenuEntry.Action(
                                    label = modeLabel,
                                    symbol = modeSymbol,
                                    destructive = isStopAction,
                                ) {
                                    contextFeaturedId = null
                                    when {
                                        isHeartbeat && heartbeatOn -> onStopHeartbeat()
                                        isHeartbeat -> onStartHeartbeat()
                                        isRoam && roamOn -> onStopRoam()
                                        isRoam -> onStartRoam()
                                        isSimilar && similarOn -> onStopSimilar()
                                        else -> onStartSimilar()
                                    }
                                },
                            ),
                        )
                    }
                } else {
                    val isDaily = playlist.id == NeteaseRepository.DAILY_ID
                    NeteasePlaylistCardContextMenu(
                        playlist = DiscoverPlaylist(
                            id = playlist.id,
                            name = playlist.name,
                            subtitle = playlist.description,
                            coverUrl = playlist.coverUrl,
                            coverPath = playlist.coverPath,
                            trackCount = playlist.entries.size.takeIf { it > 0 } ?: playlist.remoteTrackCount,
                        ),
                        expanded = contextFeaturedId == playlist.id,
                        onDismiss = { if (contextFeaturedId == playlist.id) contextFeaturedId = null },
                        refreshLabelRes = if (isDaily) R.string.netease_daily_refresh else R.string.netease_refresh,
                        onRefreshOverride = if (isDaily) onDailyRefresh else null,
                        onActionError = { err -> onActionError(playlist.id, err) },
                        anchor = cardContent,
                    )
                }
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
    onActionError: (String, String?) -> Unit,
    titleHorizontalPadding: Dp,
    errorText: String? = null,
) {
    if (radars.isEmpty()) return
    val colors = LocalTuneColors.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // Apple 首页标准网格：卡片约为屏宽的 40%，封面圆角约 7dp。
    val cardWidth = (screenWidthDp * 0.40f).coerceIn(140f, 180f).dp
    var contextRadarId by remember { mutableStateOf<String?>(null) }
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
                NeteasePlaylistCardContextMenu(
                    playlist = DiscoverPlaylist(
                        id = radar.id,
                        name = radar.name,
                        subtitle = radar.description,
                        coverUrl = radar.coverUrl,
                        coverPath = radar.coverPath,
                        trackCount = radar.trackCount,
                    ),
                    expanded = contextRadarId == radar.id,
                    onDismiss = { if (contextRadarId == radar.id) contextRadarId = null },
                    onActionError = { err -> onActionError(radar.id, err) },
                ) {
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
                        onLongClick = { contextRadarId = radar.id },
                    )
                }
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
