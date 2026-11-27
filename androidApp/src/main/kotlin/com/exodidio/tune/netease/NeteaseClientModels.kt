package com.exodidio.tune.netease

internal data class NeteaseEntryPayload(
    val songId: Long,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
    val albumId: Long = 0L,
    val artistIds: List<Long> = emptyList(),
)

internal data class NeteasePlaylistPayload(
    val id: String,
    val name: String,
    val coverUrl: String,
    val entries: List<NeteaseEntryPayload>,
    val description: String = "",
    val remoteTrackCount: Int = entries.size,
    val privacy: Int = 0,
)

internal data class NeteaseQrStatus(
    val code: Int,
    val message: String,
    val cookie: String? = null,
    /** 风控 -462 时下发的验证页地址；可在 WebView 里完成人机验证。 */
    val verifyUrl: String? = null,
)

internal data class NeteaseLoginQr(
    val key: String,
    val url: String,
    val cookie: String? = null,
)

internal data class NeteaseArtistDetailPayload(
    val id: String,
    val name: String,
    val coverUrl: String,
    val description: String,
    val alias: List<String> = emptyList(),
    val musicCount: Int = 0,
    val albumCount: Int = 0,
    val mvCount: Int = 0,
    val followed: Boolean? = null,
)

internal data class NeteaseArtistSongsPagePayload(
    val songs: List<NeteaseEntryPayload>,
    val hasMore: Boolean,
    val total: Int,
)

internal data class NeteaseArtistAlbumPayload(
    val id: String,
    val name: String,
    val artistName: String,
    val coverUrl: String,
    val trackCount: Int,
    val publishTimeMs: Long,
)

internal data class NeteaseArtistAlbumsPagePayload(
    val albums: List<NeteaseArtistAlbumPayload>,
    val hasMore: Boolean,
)

internal data class NeteaseSavedAlbumPayload(
    val id: String,
    val name: String,
    val artistName: String,
    val coverUrl: String,
    val trackCount: Int,
    val subTimeMs: Long,
)

internal data class NeteaseSavedAlbumsPagePayload(
    val albums: List<NeteaseSavedAlbumPayload>,
    val hasMore: Boolean,
    val total: Int,
)

internal data class NeteasePlayRecordPayload(
    val song: NeteaseEntryPayload,
    val playCount: Int = 0,
    val score: Int = 0,
    val playedAtMs: Long = 0L,
)

internal data class NeteaseCloudDiskTrackPayload(
    val song: NeteaseEntryPayload,
    val fileName: String = "",
    val fileSizeBytes: Long = 0L,
    val addTimeMs: Long = 0L,
)

internal data class NeteaseCloudDiskPagePayload(
    val tracks: List<NeteaseCloudDiskTrackPayload>,
    val count: Int,
    val sizeBytes: Long,
    val maxSizeBytes: Long,
    val hasMore: Boolean,
)

internal data class NeteasePlaylistCategoryPayload(
    val name: String,
    val category: Int = 0,
    val hot: Boolean = false,
)

internal data class NeteasePlaylistCatlistPayload(
    val allCategory: String = "全部",
    val categories: List<NeteasePlaylistCategoryPayload> = emptyList(),
)

internal data class NeteasePlaylistsPagePayload(
    val playlists: List<NeteasePlaylistPayload>,
    val hasMore: Boolean,
    val total: Int = 0,
    val lastTime: Long = 0L,
)

internal data class NeteaseSearchHotItemPayload(
    val keyword: String,
    val score: Long = 0L,
    val content: String = "",
    val iconType: Int = 0,
)

internal data class NeteaseSearchSuggestPayload(
    val keywords: List<String> = emptyList(),
)

internal data class NeteaseNewAlbumsPagePayload(
    val albums: List<NeteaseArtistAlbumPayload>,
    val total: Int,
    val hasMore: Boolean,
)

internal data class NeteaseTopArtistPayload(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val musicSize: Int = 0,
    val albumSize: Int = 0,
)

internal data class NeteaseTopArtistsPagePayload(
    val artists: List<NeteaseTopArtistPayload>,
    val hasMore: Boolean,
)
