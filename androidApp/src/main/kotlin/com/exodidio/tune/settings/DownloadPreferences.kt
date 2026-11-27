package com.exodidio.tune.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import com.exodidio.tune.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 网易云下载音质档位（对齐 MeloX MusicQuality 的 7 个音质选项）。
 */
enum class DownloadQuality(
    val apiLevel: String,
    @StringRes val labelRes: Int,
) {
    Standard("standard", R.string.download_quality_standard),
    High("exhigh", R.string.download_quality_exhigh),
    Lossless("lossless", R.string.download_quality_lossless),
    HiResolution("hires", R.string.download_quality_hires),
    HighDefinitionSurround("jyeffect", R.string.download_quality_jyeffect),
    ImmersiveSurround("sky", R.string.download_quality_sky),
    UltraClearMaster("jymaster", R.string.download_quality_jymaster);

    val requiresImmersiveType: Boolean
        get() = this == ImmersiveSurround

    val downloadFallbacks: List<DownloadQuality>
        get() = when (this) {
            Standard -> listOf(Standard)
            High -> listOf(High, Standard)
            Lossless -> listOf(Lossless, High, Standard)
            HiResolution -> listOf(HiResolution, Lossless, High, Standard)
            HighDefinitionSurround -> listOf(HighDefinitionSurround, Lossless, High, Standard)
            ImmersiveSurround -> listOf(
                ImmersiveSurround,
                HighDefinitionSurround,
                Lossless,
                High,
                Standard,
            )
            UltraClearMaster -> listOf(UltraClearMaster, HiResolution, Lossless, High, Standard)
        }

    companion object {
        fun fromApiLevel(level: String?): DownloadQuality =
            entries.firstOrNull { it.apiLevel.equals(level, ignoreCase = true) } ?: Standard
    }
}

data class DownloadSettingsSnapshot(
    val quality: DownloadQuality = DownloadQuality.Standard,
    val usePrivateFolder: Boolean = true,
    val customFolderUri: String? = null,
)

/**
 * 下载音质与保存位置偏好：
 * - `usePrivateFolder` 默认为 true（保存在应用私有目录，开关关闭后可自选 SAF 文件夹）。
 * - 切换保存位置仅作用于后续新下载的歌曲；已下载曲目的 `audioPath` 保持原样不变，不会失效。
 */
internal object DownloadPreferences {
    private const val PREFS_NAME = "download_preferences"
    private const val KEY_QUALITY = "download_quality"
    private const val KEY_USE_PRIVATE = "download_use_private_folder"
    private const val KEY_CUSTOM_FOLDER_URI = "download_custom_folder_uri"

    private val _snapshot = MutableStateFlow(DownloadSettingsSnapshot())
    val snapshotFlow: StateFlow<DownloadSettingsSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var loadedFromDisk = false

    suspend fun refreshAsync(context: Context): DownloadSettingsSnapshot =
        withContext(Dispatchers.IO) { readSnapshotBlocking(context) }

    /** 仅应在 Dispatchers.IO / 工作线程中调用，避免主线程触发 SharedPreferences 磁盘加载。 */
    fun readSnapshotBlocking(context: Context): DownloadSettingsSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val snapshot = DownloadSettingsSnapshot(
            quality = DownloadQuality.fromApiLevel(prefs.getString(KEY_QUALITY, DownloadQuality.Standard.apiLevel)),
            usePrivateFolder = prefs.getBoolean(KEY_USE_PRIVATE, true),
            customFolderUri = prefs.getString(KEY_CUSTOM_FOLDER_URI, null)?.takeIf { it.isNotBlank() },
        )
        loadedFromDisk = true
        _snapshot.value = snapshot
        return snapshot
    }

    suspend fun setDownloadQuality(context: Context, quality: DownloadQuality) {
        val current = withContext(Dispatchers.IO) {
            if (!loadedFromDisk) readSnapshotBlocking(context) else _snapshot.value
        }
        val next = current.copy(quality = quality)
        _snapshot.value = next
        withContext(Dispatchers.IO) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUALITY, quality.apiLevel)
                .apply()
        }
    }

    suspend fun setUsePrivateFolder(context: Context, usePrivate: Boolean) {
        val current = withContext(Dispatchers.IO) {
            if (!loadedFromDisk) readSnapshotBlocking(context) else _snapshot.value
        }
        val next = current.copy(usePrivateFolder = usePrivate)
        _snapshot.value = next
        withContext(Dispatchers.IO) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_USE_PRIVATE, usePrivate)
                .apply()
        }
    }

    suspend fun setCustomFolderUri(context: Context, uriString: String?) {
        val normalized = uriString?.takeIf { it.isNotBlank() }
        val current = withContext(Dispatchers.IO) {
            if (!loadedFromDisk) readSnapshotBlocking(context) else _snapshot.value
        }
        val next = current.copy(customFolderUri = normalized)
        _snapshot.value = next
        withContext(Dispatchers.IO) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_FOLDER_URI, normalized)
                .apply()
        }
    }

    /** 解析 SAF 树 URI 的人类可读目录名；需在 IO 线程调用，避免主线程查询 ContentResolver。 */
    suspend fun resolveFolderDisplayName(context: Context, uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                DocumentFile.fromTreeUri(context.applicationContext, uri)?.name
                    ?: uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
                    ?: uriString
            }.getOrNull()
        }
    }
}
