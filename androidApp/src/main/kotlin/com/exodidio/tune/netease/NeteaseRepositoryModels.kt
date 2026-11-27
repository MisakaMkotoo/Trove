package com.exodidio.tune.netease

import androidx.annotation.StringRes
import com.exodidio.tune.sync.LibraryTrack
import kotlinx.serialization.Serializable

@Serializable
internal data class NeteaseEntryRecord(
    val songId: Long,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val coverPath: String? = null,
    val matchedTrackId: String? = null,
    val downloadedFile: String? = null,
    val downloadFormat: String? = null,
    /** 网易云专辑/歌手 ID：用于“前往专辑/歌手”打开在线实体页。 */
    val albumId: Long = 0L,
    val artistIds: List<Long> = emptyList(),
)

@Serializable
internal data class NeteasePlaylistRecord(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val importedAt: String = "",
    val coverPath: String? = null,
    val addedToPlaylists: Boolean = false,
    /** 动态歌单来源（daily/radar/rec）；普通导入为空。 */
    val dynamicKind: String? = null,
    /** 导入时保存的原始链接；刷新按它重新拉取并与远端完全同步。 */
    val sourceUrl: String = "",
    /** 远端歌单的歌曲数量；条目未加载时首页用它显示数量。 */
    val remoteTrackCount: Int = 0,
    /** 歌单介绍，首页卡片副标题使用。 */
    val description: String = "",
    /** 从“我的”点开过的歌单：条目写盘缓存，重启后仍在，但不进播放列表。 */
    val persistCache: Boolean = false,
    /** 是否已通过分块详情补齐完整曲目列表（用于自动修复旧版截断缓存）。 */
    val fullyHydrated: Boolean = false,
    /** 网易云隐私状态：0=公开，10=私密。 */
    val privacy: Int = 0,
    val entries: List<NeteaseEntryRecord> = emptyList(),
)

/** 在线搜索返回的歌单/歌手/专辑元数据（封面可为已缓存的本地路径）。 */
data class OnlinePlaylistInfo(
    val id: Long,
    val name: String,
    val creator: String,
    val trackCount: Int,
    val coverUrl: String,
    val artworkPath: String? = null,
)

data class OnlineArtistInfo(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val artworkPath: String? = null,
)

data class OnlineAlbumInfo(
    val id: Long,
    val name: String,
    val artist: String,
    val coverUrl: String,
    val artworkPath: String? = null,
)

/** 一键下载结果：total=开始时待下载数，succeeded=实际下载成功数。 */
data class DownloadAllResult(val total: Int, val succeeded: Int)

internal data class OnlineSearchResult(
    val songs: List<LibraryTrack> = emptyList(),
    val playlists: List<OnlinePlaylistInfo> = emptyList(),
    val artists: List<OnlineArtistInfo> = emptyList(),
    val albums: List<OnlineAlbumInfo> = emptyList(),
    /** 分区级失败：非空表示该分区请求失败，界面必须区别于“真的没有结果”。 */
    val playlistError: String? = null,
    val artistError: String? = null,
    val albumError: String? = null,
)

/** 心动/漫游卡片预览：启动时先取第一首（或一批），只展示封面，不播放。 */
data class NeteaseModePreview(
    val trackIds: List<String> = emptyList(),
    val artworkPath: String? = null,
    val seed: Long? = null,
)

/** 新发现页的歌单卡片数据（榜单/甄选/推荐/我的歌单共用）。 */
@Serializable
data class DiscoverPlaylist(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val coverUrl: String = "",
    val coverPath: String? = null,
    val trackCount: Int = 0,
)

/** 新发现页数据：歌单分区 + 新歌；封面缺失的随后在后台补齐。 */
data class DiscoverData(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val rankings: List<DiscoverPlaylist> = emptyList(),
    val curated: List<DiscoverPlaylist> = emptyList(),
    val newSongs: List<LibraryTrack> = emptyList(),
    val rankingsError: String? = null,
    val curatedError: String? = null,
    val newSongsError: String? = null,
)

/** 首页动态分区（曲风精选/歌曲推荐/艺人新动向）：标题和内容都由服务端下发。 */
data class HomeSongSection(
    val title: String,
    /** 服务端没给标题时使用的本地文案（如“根据你喜爱的歌曲推荐”）。 */
    @StringRes val titleRes: Int? = null,
    val songs: List<LibraryTrack> = emptyList(),
)

data class HomeFeedData(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val sections: List<HomeSongSection> = emptyList(),
    val artistNew: HomeSongSection? = null,
    val recommended: List<DiscoverPlaylist> = emptyList(),
    val exclusiveTitle: String = "",
    val exclusivePlaylists: List<DiscoverPlaylist> = emptyList(),
    val error: String? = null,
)

/** “我的”页关注的歌手。 */
@Serializable
data class MineArtist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
)

/** “我的”页收藏的专辑。 */
@Serializable
data class MineAlbum(
    val id: String,
    val name: String,
    val artistName: String = "",
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val subTimeMs: Long = 0L,
)

/** 歌手详情页展示数据。 */
data class NeteaseArtistDetailState(
    val artistId: String = "",
    val name: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val alias: List<String> = emptyList(),
    val musicCount: Int = 0,
    val albumCount: Int = 0,
    val mvCount: Int = 0,
    val followed: Boolean = false,
    val order: String = "hot",
    val loading: Boolean = false,
    val loadingMoreSongs: Boolean = false,
    val loadingMoreAlbums: Boolean = false,
    val songs: List<LibraryTrack> = emptyList(),
    val songsTotal: Int = 0,
    val songsHasMore: Boolean = false,
    val albums: List<NeteaseArtistAlbumItem> = emptyList(),
    val albumsHasMore: Boolean = false,
    val error: String? = null,
)

data class NeteaseArtistAlbumItem(
    val id: String,
    val name: String,
    val artistName: String = "",
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val publishTimeMs: Long = 0L,
)

/** 云端最近播放与听歌排行条目。 */
data class NeteasePlayRecordEntry(
    val track: LibraryTrack,
    val playCount: Int = 0,
    val score: Int = 0,
    val playedAtMs: Long = 0L,
)

data class NeteasePlayRecordsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val recent: List<NeteasePlayRecordEntry> = emptyList(),
    val weekRank: List<NeteasePlayRecordEntry> = emptyList(),
    val allRank: List<NeteasePlayRecordEntry> = emptyList(),
    val recentError: String? = null,
    val weekRankError: String? = null,
    val allRankError: String? = null,
)

/** 更多推荐歌单页状态（每日推荐歌单 + 精选推荐歌单）。 */
data class RecommendedPlaylistsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<DiscoverPlaylist> = emptyList(),
    val error: String? = null,
)

/** “我的”页数据：云端自建/收藏歌单 + 收藏专辑 + 关注歌手（最近播放走本地）。 */
data class MineData(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val created: List<DiscoverPlaylist> = emptyList(),
    val saved: List<DiscoverPlaylist> = emptyList(),
    val albums: List<MineAlbum> = emptyList(),
    val artists: List<MineArtist> = emptyList(),
    val albumsHasMore: Boolean = false,
    val albumsTotal: Int = 0,
    val loadingMoreAlbums: Boolean = false,
    val error: String? = null,
)

/** “我的”页磁盘缓存：资料头、歌单列表、收藏专辑、关注歌手启动时先用缓存渲染。 */
@Serializable
internal data class MineCache(
    val created: List<DiscoverPlaylist> = emptyList(),
    val saved: List<DiscoverPlaylist> = emptyList(),
    val albums: List<MineAlbum> = emptyList(),
    val artists: List<MineArtist> = emptyList(),
)

/** 首页“雷达歌单”卡片元数据（名称/封面/数量，不含歌曲）。 */
@Serializable
data class NeteaseRadarSummary(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val coverPath: String? = null,
    val trackCount: Int = 0,
    val description: String = "",
)

/** 官方雷达歌单目录；内容按账号在服务端个性化。 */
internal const val RADAR_DISPLAY_NAME = "私人雷达"

internal val RadarCatalog: List<Pair<String, String>> = listOf(
    "3136952023" to RADAR_DISPLAY_NAME,
    "10106461201" to "怀旧雷达",
    "8402996200" to "会员雷达",
    "5300458264" to "新歌雷达",
    "8819359201" to "热点雷达",
    "5362359247" to "宝藏雷达",
    "5341776086" to "神秘雷达",
    "5320167908" to "时光雷达",
)

/** 网易云歌单的操作权限分级。 */
internal enum class NeteasePlaylistPermission {
    /** 我喜欢的音乐（仅允许增删喜欢歌曲，不允许编辑/删除歌单）。 */
    Liked,
    /** 当前账号自建普通歌单（可编辑名称/简介、删除歌单、增删歌曲）。 */
    OwnedEditable,
    /** 他人/公共普通歌单（可收藏/取消收藏到“我的”，不可直接删改歌单或曲目）。 */
    ForeignSubscribable,
    /** 动态歌单/雷达/快照/下载页/虚拟歌手专辑页（只读）。 */
    ReadOnlyDynamic,
}

/** 网易云音乐云盘曲目。 */
data class NeteaseCloudDiskItem(
    val songId: Long,
    val track: LibraryTrack,
    val fileName: String = "",
    val fileSizeBytes: Long = 0L,
    val addTimeMs: Long = 0L,
)

data class NeteaseCloudDiskState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val items: List<NeteaseCloudDiskItem> = emptyList(),
    val totalCount: Int = 0,
    val sizeBytes: Long = 0L,
    val maxSizeBytes: Long = 0L,
    val hasMore: Boolean = false,
    val error: String? = null,
)

/** 热搜榜条目。 */
data class NeteaseSearchHotItem(
    val keyword: String,
    val score: Long = 0L,
    val content: String = "",
    val iconType: Int = 0,
)

/** 歌单分类。 */
data class NeteasePlaylistCategory(
    val name: String,
    val category: Int = 0,
    val hot: Boolean = false,
)

/** 热门歌手榜条目。 */
data class NeteaseTopArtistItem(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val musicSize: Int = 0,
    val albumSize: Int = 0,
)

/** 发现广场页签。 */
enum class DiscoverSquareTab {
    Playlists,
    HighQuality,
    Rankings,
    NewAlbums,
    TopArtists,
}

/** 歌单广场 / 排行榜 / 新碟上架 / 热门歌手统一状态。 */
data class DiscoverSquareState(
    val activeTab: DiscoverSquareTab = DiscoverSquareTab.Playlists,
    val selectedCategory: String = "全部",
    val categories: List<NeteasePlaylistCategory> = emptyList(),
    val playlists: List<DiscoverPlaylist> = emptyList(),
    val playlistsLoading: Boolean = false,
    val playlistsLoadingMore: Boolean = false,
    val playlistsHasMore: Boolean = false,
    val playlistsError: String? = null,
    val highQuality: List<DiscoverPlaylist> = emptyList(),
    val highQualityLastTime: Long = 0L,
    val highQualityLoading: Boolean = false,
    val highQualityLoadingMore: Boolean = false,
    val highQualityHasMore: Boolean = false,
    val highQualityError: String? = null,
    val rankings: List<DiscoverPlaylist> = emptyList(),
    val rankingsLoading: Boolean = false,
    val rankingsError: String? = null,
    val newAlbumsArea: String = "ALL",
    val newAlbums: List<NeteaseArtistAlbumItem> = emptyList(),
    val newAlbumsLoading: Boolean = false,
    val newAlbumsLoadingMore: Boolean = false,
    val newAlbumsHasMore: Boolean = false,
    val newAlbumsError: String? = null,
    val topArtists: List<NeteaseTopArtistItem> = emptyList(),
    val topArtistsLoading: Boolean = false,
    val topArtistsLoadingMore: Boolean = false,
    val topArtistsHasMore: Boolean = false,
    val topArtistsError: String? = null,
)
