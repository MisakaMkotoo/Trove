package com.exodidio.tune

import com.exodidio.tune.settings.PlayerTheme
import com.exodidio.tune.settings.ThemeMode

/** User-initiated inputs accepted by the app shell. */
sealed interface AppIntent {
    data class SelectDestination(val destination: AppDestination) : AppIntent

    data class OpenPage(val page: AppStackPage) : AppIntent

    /** 压入当前 tab 的栈，不切到页面固有目的地（首页打开歌单后返回仍回首页）。 */
    data class OpenPageHere(val page: AppStackPage) : AppIntent

    data class OpenAlbumDetails(val albumId: String) : AppIntent

    data class OpenPlaylistDetails(val playlistId: String) : AppIntent

    data class OpenArtistDetails(val artistId: String) : AppIntent

    data class OpenGenreDetails(val genreId: String) : AppIntent

    data class OpenComposerDetails(val composerId: String) : AppIntent

    data class OpenSimilarSongs(val trackId: String) : AppIntent

    /** 在线实体页：复用播放列表界面，压在来源标签栈里。 */
    data class OpenNeteaseArtist(val artistId: Long, val name: String) : AppIntent

    data class OpenNeteaseAlbum(val albumId: Long, val name: String, val coverUrl: String) : AppIntent

    data object NavigateBack : AppIntent

    /** 从一级界面右上角进入设置；返回时回到进入前的 tab。 */
    data object OpenSettings : AppIntent

    /** 独立账户页：和设置一样切换 destination，返回时回到进入前的 tab。 */
    data object OpenAccount : AppIntent

    data class SetThemeMode(val themeMode: ThemeMode) : AppIntent

    data class SetReduceTransparency(val enabled: Boolean) : AppIntent

    data class SetPlayerTheme(val theme: PlayerTheme) : AppIntent

    data class SetShowVolumeSlider(val enabled: Boolean) : AppIntent

    data class SetFullscreenArtwork(val enabled: Boolean) : AppIntent

    data class SetFullscreenLyrics(val enabled: Boolean) : AppIntent

    data class SetVisibleTabs(val visibleTabs: List<AppDestination>) : AppIntent

    data class SetKeepScreenOn(val enabled: Boolean) : AppIntent

    data class SetLyricsFontLevel(val level: Int) : AppIntent

    data class SetShowGenres(val enabled: Boolean) : AppIntent

    data class SetShowComposers(val enabled: Boolean) : AppIntent

    /** 点击底栏是否回到该标签上次页面（关闭后直接回一级页）。 */
    data class SetRememberTabPage(val enabled: Boolean) : AppIntent

    /** 滑动时是否合并底栏与迷你播放条；关闭后两者始终分开，内容底部留白随之增加。 */
    data class SetMergeBottomBars(val enabled: Boolean) : AppIntent

    /** User picked the "Import M3U playlist" action. */
    data object ImportM3u : AppIntent

    /** User picked the "Export M3U playlist" action for a playlist. */
    data class ExportM3u(val playlistId: String) : AppIntent

    data class OpenExternalUrl(val url: String) : AppIntent
}

/** One-time work delegated by the app shell to the Android host. */
sealed interface AppEffect {
    data class OpenExternalUrl(val url: String) : AppEffect

    data class ResetPoppedPage(val page: AppStackPage) : AppEffect

    /** Launch the system file picker to import an M3U playlist. */
    data object PickM3uImport : AppEffect

    /** Launch the system file picker to export an M3U playlist. */
    data class PickM3uExport(val playlistId: String) : AppEffect
}
