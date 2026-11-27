package com.exodidio.tune.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

import com.exodidio.tune.AppDestination

internal val Context.themeDataStore by preferencesDataStore(name = "appearance")
private val ThemeModeKey = stringPreferencesKey("theme_mode")
private val ReduceTransparencyKey = booleanPreferencesKey("reduce_transparency")
private val PlayerThemeKey = stringPreferencesKey("player_theme")
private val ShowVolumeSliderKey = booleanPreferencesKey("show_volume_slider")
private val FullscreenArtworkKey = booleanPreferencesKey("fullscreen_artwork")
private val FullscreenLyricsKey = booleanPreferencesKey("fullscreen_lyrics")
private val VisibleTabsKey = stringSetPreferencesKey("visible_tabs")
private val VisibleTabsOrderKey = stringPreferencesKey("visible_tabs_order")
private val KeepScreenOnKey = booleanPreferencesKey("keep_screen_on")
/** “我的”标签一次性补齐：老用户也默认显示，之后仍可在“显示的标签”里关闭。 */
private val MineTabMigratedKey = booleanPreferencesKey("mine_tab_migrated")
/** 歌词字号档位：0 小、1 标准（默认）、2 大。 */
private val LyricsFontLevelKey = intPreferencesKey("lyrics_font_level")
/** 媒体库是否显示“流派 / 作曲家”入口，默认隐藏。 */
private val ShowGenresKey = booleanPreferencesKey("show_genres")
private val ShowComposersKey = booleanPreferencesKey("show_composers")
/** 点击底栏时是否回到该标签上次所在的页面；关闭后直接回一级页。 */
private val RememberTabPageKey = booleanPreferencesKey("remember_tab_page")
/** 滑动时把底栏和迷你播放条合并成一条；默认开启。 */
private val MergeBottomBarsKey = booleanPreferencesKey("merge_bottom_bars")
private val IconStyleKey = stringPreferencesKey("icon_style")
private val ControlStyleKey = stringPreferencesKey("control_style")
private val LiquidGlassBlurDpKey = floatPreferencesKey("liquid_glass_blur_dp")
private val AccentColorKey = stringPreferencesKey("accent_color")
private val MergeMineAndLibraryKey = booleanPreferencesKey("merge_mine_and_library")

internal const val DefaultLiquidGlassBlurDp = 14f
internal const val MinLiquidGlassBlurDp = 0f
internal const val MaxLiquidGlassBlurDp = 28f

enum class ThemeAccentColor(
    val storageValue: String,
    val labelRes: Int,
    val primaryLight: Long,
    val primaryDark: Long,
) {
    Red("red", com.exodidio.tune.R.string.theme_accent_red, 0xFFE11D48, 0xFFE11D48),
    Blue("blue", com.exodidio.tune.R.string.theme_accent_blue, 0xFF2563EB, 0xFF3B82F6),
    Green("green", com.exodidio.tune.R.string.theme_accent_green, 0xFF16A34A, 0xFF22C55E),
    Purple("purple", com.exodidio.tune.R.string.theme_accent_purple, 0xFF7C3AED, 0xFFA855F7),
    Orange("orange", com.exodidio.tune.R.string.theme_accent_orange, 0xFFEA580C, 0xFFF97316),
    ;

    companion object {
        fun fromStorage(value: String?): ThemeAccentColor =
            entries.firstOrNull { it.storageValue == value } ?: Red
    }
}

enum class ThemeMode(val storageValue: String, val labelRes: Int) {
    System("system", com.exodidio.tune.R.string.theme_system),
    Light("light", com.exodidio.tune.R.string.theme_light),
    Dark("dark", com.exodidio.tune.R.string.theme_dark),
    Amoled("amoled", com.exodidio.tune.R.string.theme_amoled),
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.storageValue == value } ?: System
    }
}

enum class PlayerTheme(val storageValue: String) {
    Standard("standard"),
    Adaptive("adaptive"),

    /** 苹果式：过饱和封面域扭曲 + 重模糊的动态背景。 */
    Apple("apple"),

    /** 只保留域扭曲的像素风背景，不叠厚模糊。 */
    Distorted("distorted"),

    /** GL 动态背景：网格 / Titanium 流沙 / Apple 逆向管线，参数在三级页配置。 */
    Dynamic("dynamic"),
    ;

    companion object {
        /** 默认使用动态背景（网格渐变）。 */
        fun fromStorage(value: String?): PlayerTheme = entries.firstOrNull { it.storageValue == value } ?: Dynamic
    }
}

enum class IconStyle(val storageValue: String, val labelRes: Int) {
    Material("material", com.exodidio.tune.R.string.appearance_icon_style_material),
    Apple("apple", com.exodidio.tune.R.string.appearance_icon_style_apple),
    ;

    companion object {
        fun fromStorage(value: String?): IconStyle =
            entries.firstOrNull { it.storageValue == value } ?: Material
    }
}

enum class ControlStyle(val storageValue: String, val labelRes: Int) {
    Frosted("frosted", com.exodidio.tune.R.string.appearance_control_style_frosted),
    LiquidGlass("liquid_glass", com.exodidio.tune.R.string.appearance_control_style_liquid_glass),
    ;

    companion object {
        fun fromStorage(value: String?): ControlStyle =
            entries.firstOrNull { it.storageValue == value } ?: Frosted
    }
}

interface ThemeModeStore {
    val themeMode: Flow<ThemeMode>
    val reduceTransparency: Flow<Boolean>
    val playerTheme: Flow<PlayerTheme>
    val showVolumeSlider: Flow<Boolean>
    val fullscreenArtwork: Flow<Boolean>
    val fullscreenLyrics: Flow<Boolean>
    val visibleTabs: Flow<List<AppDestination>>
    val keepScreenOn: Flow<Boolean>
    val lyricsFontLevel: Flow<Int>
    val showGenres: Flow<Boolean>
    val showComposers: Flow<Boolean>
    val rememberTabPage: Flow<Boolean>
    val mergeBottomBars: Flow<Boolean>
    val iconStyle: Flow<IconStyle>
    val controlStyle: Flow<ControlStyle>
    val liquidGlassBlurDp: Flow<Float>
    val accentColor: Flow<ThemeAccentColor>
    val mergeMineAndLibrary: Flow<Boolean>

    suspend fun setThemeMode(themeMode: ThemeMode)
    suspend fun setReduceTransparency(enabled: Boolean)
    suspend fun setPlayerTheme(theme: PlayerTheme)
    suspend fun setShowVolumeSlider(enabled: Boolean)
    suspend fun setFullscreenArtwork(enabled: Boolean)
    suspend fun setVisibleTabs(visibleTabs: List<AppDestination>)
    suspend fun setFullscreenLyrics(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setLyricsFontLevel(level: Int)
    suspend fun setShowGenres(enabled: Boolean)
    suspend fun setShowComposers(enabled: Boolean)
    suspend fun setRememberTabPage(enabled: Boolean)
    suspend fun setMergeBottomBars(enabled: Boolean)
    suspend fun setIconStyle(style: IconStyle)
    suspend fun setControlStyle(style: ControlStyle)
    suspend fun setLiquidGlassBlurDp(blurDp: Float)
    suspend fun setAccentColor(accentColor: ThemeAccentColor)
    suspend fun setMergeMineAndLibrary(enabled: Boolean)
}

class ThemePreferences(
    private val context: Context,
) : ThemeModeStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            val current = context.themeDataStore.data.first()
            if (current[MineTabMigratedKey] == true) return@launch
            context.themeDataStore.edit { preferences ->
                val stored = preferences[VisibleTabsKey]
                if (stored != null) {
                    val destinations = stored.mapNotNull { AppDestination.fromStorage(it) }
                    if (AppDestination.Mine !in destinations) {
                        preferences[VisibleTabsKey] = (destinations + AppDestination.Mine)
                            .map { it.storageValue }
                            .toSet()
                    }
                }
                preferences[MineTabMigratedKey] = true
            }
        }
    }

    override val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { preferences: Preferences ->
        ThemeMode.fromStorage(preferences[ThemeModeKey])
    }
    override val reduceTransparency: Flow<Boolean> = context.themeDataStore.data.map { preferences: Preferences ->
        preferences[ReduceTransparencyKey] ?: false
    }
    override val playerTheme: Flow<PlayerTheme> = context.themeDataStore.data.map { preferences: Preferences ->
        PlayerTheme.fromStorage(preferences[PlayerThemeKey])
    }
    override val showVolumeSlider: Flow<Boolean> = context.themeDataStore.data.map { preferences: Preferences ->
        preferences[ShowVolumeSliderKey] ?: false
    }
    override val fullscreenArtwork: Flow<Boolean> = context.themeDataStore.data.map { preferences: Preferences ->
        preferences[FullscreenArtworkKey] ?: false
    }
    override val fullscreenLyrics: Flow<Boolean> = context.themeDataStore.data.map { preferences: Preferences ->
        preferences[FullscreenLyricsKey] ?: false
    }
    override val visibleTabs: Flow<List<AppDestination>> = context.themeDataStore.data.map { preferences: Preferences ->
        parseVisibleTabs(
            orderedRaw = preferences[VisibleTabsOrderKey],
            legacySet = preferences[VisibleTabsKey],
        )
    }
    override val lyricsFontLevel: Flow<Int> = context.themeDataStore.data.map { preferences: Preferences ->
        (preferences[LyricsFontLevelKey] ?: 1).coerceIn(0, 2)
    }
    override val showGenres: Flow<Boolean> = context.themeDataStore.data.map { preferences ->
        preferences[ShowGenresKey] ?: false
    }
    override val showComposers: Flow<Boolean> = context.themeDataStore.data.map { preferences ->
        preferences[ShowComposersKey] ?: false
    }

    override val rememberTabPage: Flow<Boolean> = context.themeDataStore.data.map { preferences ->
        preferences[RememberTabPageKey] ?: true
    }

    override val mergeBottomBars: Flow<Boolean> = context.themeDataStore.data.map { preferences ->
        preferences[MergeBottomBarsKey] ?: true
    }

    override val iconStyle: Flow<IconStyle> = context.themeDataStore.data.map { preferences ->
        IconStyle.fromStorage(preferences[IconStyleKey])
    }

    override val controlStyle: Flow<ControlStyle> = context.themeDataStore.data.map { preferences ->
        ControlStyle.fromStorage(preferences[ControlStyleKey])
    }

    override val liquidGlassBlurDp: Flow<Float> = context.themeDataStore.data.map { preferences ->
        (preferences[LiquidGlassBlurDpKey] ?: DefaultLiquidGlassBlurDp).coerceIn(MinLiquidGlassBlurDp, MaxLiquidGlassBlurDp)
    }

    override val accentColor: Flow<ThemeAccentColor> = context.themeDataStore.data.map { preferences ->
        ThemeAccentColor.fromStorage(preferences[AccentColorKey])
    }

    override val mergeMineAndLibrary: Flow<Boolean> = context.themeDataStore.data.map { preferences ->
        preferences[MergeMineAndLibraryKey] ?: false
    }

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        context.themeDataStore.edit { preferences ->
            preferences[ThemeModeKey] = themeMode.storageValue
        }
    }

    override suspend fun setReduceTransparency(enabled: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[ReduceTransparencyKey] = enabled
        }
    }

    override suspend fun setPlayerTheme(theme: PlayerTheme) {
        context.themeDataStore.edit { preferences ->
            preferences[PlayerThemeKey] = theme.storageValue
        }
    }

    override suspend fun setShowVolumeSlider(enabled: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[ShowVolumeSliderKey] = enabled
        }
    }

    override suspend fun setFullscreenArtwork(enabled: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[FullscreenArtworkKey] = enabled
        }
    }

    override suspend fun setFullscreenLyrics(enabled: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[FullscreenLyricsKey] = enabled
        }
    }

    override suspend fun setVisibleTabs(visibleTabs: List<AppDestination>) {
        val cleaned = visibleTabs.distinct().filter { it in AppDestination.dockable }
        context.themeDataStore.edit { preferences ->
            preferences[VisibleTabsOrderKey] = cleaned.joinToString(",") { it.storageValue }
            preferences[VisibleTabsKey] = cleaned.map { it.storageValue }.toSet()
        }
    }

    override val keepScreenOn: Flow<Boolean> = context.themeDataStore.data.map { preferences ->
        preferences[KeepScreenOnKey] ?: false
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[KeepScreenOnKey] = enabled
        }
    }

    override suspend fun setLyricsFontLevel(level: Int) {
        context.themeDataStore.edit { preferences ->
            preferences[LyricsFontLevelKey] = level.coerceIn(0, 2)
        }
    }

    override suspend fun setShowGenres(enabled: Boolean) {
        context.themeDataStore.edit { preferences -> preferences[ShowGenresKey] = enabled }
    }

    override suspend fun setShowComposers(enabled: Boolean) {
        context.themeDataStore.edit { preferences -> preferences[ShowComposersKey] = enabled }
    }

    override suspend fun setRememberTabPage(enabled: Boolean) {
        context.themeDataStore.edit { preferences -> preferences[RememberTabPageKey] = enabled }
    }

    override suspend fun setMergeBottomBars(enabled: Boolean) {
        context.themeDataStore.edit { preferences -> preferences[MergeBottomBarsKey] = enabled }
    }

    override suspend fun setIconStyle(style: IconStyle) {
        context.themeDataStore.edit { preferences -> preferences[IconStyleKey] = style.storageValue }
    }

    override suspend fun setControlStyle(style: ControlStyle) {
        context.themeDataStore.edit { preferences -> preferences[ControlStyleKey] = style.storageValue }
    }

    override suspend fun setLiquidGlassBlurDp(blurDp: Float) {
        context.themeDataStore.edit { preferences ->
            preferences[LiquidGlassBlurDpKey] = blurDp.coerceIn(MinLiquidGlassBlurDp, MaxLiquidGlassBlurDp)
        }
    }

    override suspend fun setAccentColor(accentColor: ThemeAccentColor) {
        context.themeDataStore.edit { preferences -> preferences[AccentColorKey] = accentColor.storageValue }
    }

    override suspend fun setMergeMineAndLibrary(enabled: Boolean) {
        context.themeDataStore.edit { preferences -> preferences[MergeMineAndLibraryKey] = enabled }
    }
}

internal fun parseVisibleTabs(
    orderedRaw: String?,
    legacySet: Set<String>?,
): List<AppDestination> {
    if (orderedRaw != null) {
        val ordered = orderedRaw.split(',')
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { AppDestination.fromStorage(it) }
            .filter { it in AppDestination.dockable }
            .distinct()
            .toList()
        if (ordered.isNotEmpty()) return ordered
    }
    val legacyValues = legacySet
        ?.mapNotNull { AppDestination.fromStorage(it) }
        ?.toSet()
    val legacy = legacyValues
        ?.let { set -> AppDestination.dockable.filter { it in set } }
        ?.takeIf { it.isNotEmpty() }
    return if (legacy == null || legacy.none { it == AppDestination.Discover || it == AppDestination.Search }) {
        AppDestination.dockable
    } else {
        legacy
    }
}
