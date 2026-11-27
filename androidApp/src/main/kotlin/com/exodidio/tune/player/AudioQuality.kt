package com.exodidio.tune.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 流媒体音质：自动（Wi-Fi 无损、非 Wi-Fi MP3）/ 仅 MP3 / 仅无损。 */
internal enum class AudioQualityMode(val storageValue: String) {
    Auto("auto"),
    Mp3Only("mp3"),
    LosslessOnly("lossless"),
    ;

    companion object {
        fun fromStorage(value: String?): AudioQualityMode =
            entries.firstOrNull { it.storageValue == value } ?: Auto
    }
}

/** 由 MainActivity 写入当前设置；解析播流地址时据此决定请求档位。 */
internal object AudioQualityRuntime {
    @Volatile
    var mode: AudioQualityMode = AudioQualityMode.Auto

    /** 是否优先请求无损；取不到时会回退 MP3，避免无法播放。 */
    fun preferLossless(context: Context): Boolean = when (mode) {
        AudioQualityMode.Mp3Only -> false
        AudioQualityMode.LosslessOnly -> true
        AudioQualityMode.Auto -> isWifi(context)
    }

    private fun isWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
