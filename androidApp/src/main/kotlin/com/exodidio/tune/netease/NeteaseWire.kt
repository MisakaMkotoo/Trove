package com.exodidio.tune.netease

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import java.security.SecureRandom

/** 网易云请求线格式：网页（现状 api/weapi）或官方 Android eapi 指纹。 */
internal enum class NeteaseWireMode(val storageValue: String) {
    Web("web"),
    EapiAndroid("eapi_android"),
    ;

    companion object {
        fun fromStorage(value: String?): NeteaseWireMode =
            entries.firstOrNull { it.storageValue == value } ?: EapiAndroid
    }
}

/**
 * 对齐 MeiloX 的客户端身份：官方 Android UA + 合成 deviceId（形状同官方、
 * 首次生成后持久化）。本地不引入网易官方安全 SDK。
 */
internal object NeteaseWireConfig {
    private const val PREFS = "netease_wire"
    private const val KEY_MODE = "mode"
    private const val KEY_DEVICE_ID = "device_id"

    const val OS = "android"
    const val APP_VERSION = "9.5.70"
    const val VERSION_CODE = 9_005_070L
    const val BUILD_VERSION = "260818213343"
    const val CHANNEL = "xiaomi"
    const val USER_AGENT = "NeteaseMusic/$APP_VERSION.$BUILD_VERSION($VERSION_CODE);Dalvik/2.1.0"

    @Volatile
    var mode: NeteaseWireMode = NeteaseWireMode.EapiAndroid
        private set

    @Volatile
    var deviceId: String = ""
        private set

    @Volatile
    var osver: String = "14"
        private set

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = NeteaseWireMode.fromStorage(prefs.getString(KEY_MODE, null))
        deviceId = prefs.getString(KEY_DEVICE_ID, null)?.takeIf(String::isNotBlank)
            ?: generateDeviceId().also { value ->
                prefs.edit().putString(KEY_DEVICE_ID, value).apply()
            }
        osver = Build.VERSION.RELEASE?.takeIf(String::isNotBlank) ?: "14"
        Log.d("NeteaseWire", "mode=${mode.storageValue}")
    }

    fun setMode(context: Context, value: NeteaseWireMode) {
        mode = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, value.storageValue)
            .apply()
    }

    /** `Base64("null\t<随机MAC>\t<16位hex>\t<16位hex>")`，与官方 deviceId 形状一致。 */
    private fun generateDeviceId(): String {
        val random = SecureRandom()
        val mac = (0 until 6).joinToString(":") { "%02x".format(random.nextInt(256)) }
        fun hex(length: Int): String =
            (0 until length).joinToString("") { "%x".format(random.nextInt(16)) }
        val raw = "null\t$mac\t${hex(16)}\t${hex(16)}"
        return Base64.encodeToString(
            raw.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}
