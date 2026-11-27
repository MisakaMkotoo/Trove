package com.exodidio.tune.player

/** 动态背景渲染样式：网格渐变 / Titanium 流沙。 */
internal enum class DynamicBackdropStyle(val storageValue: String) {
    Mesh("mesh"),
    Titanium("titanium"),
    ;

    companion object {
        fun fromStorage(value: String?): DynamicBackdropStyle =
            entries.firstOrNull { it.storageValue == value } ?: Mesh
    }
}

/**
 * 动态背景参数。默认值取 AMLL：
 * flowSpeed=1、fps=60（换歌淡入在 30fps 下会显得一卡一卡）、renderScale=0.75；
 * mesh 6 个固定预设（-1 = 随机 6x6）；
 * titanium grain 0.7 / sparkle 0.75 / sheen 0.5 / metalness 0.35 / crest 2000ms。
 */
internal data class DynamicBackdropSettings(
    val style: DynamicBackdropStyle = DynamicBackdropStyle.Mesh,
    val flowSpeed: Float = 1.5f,
    val fps: Int = 60,
    val renderScale: Float = 0.75f,
    val staticMode: Boolean = false,
    /** 播放器暂无 FFT，低频强度用固定滑杆模拟。 */
    val volume: Float = 0.7f,
    /**
     * 网格形变候选池：0..5 = Apple A-F，6 = Plain 3x3，7 = 随机 6x6。
     * 每次换封面用歌曲名哈希从勾选项里选取。
     */
    val meshPresetPool: Set<Int> = (0..7).toSet(),
    val titaniumGrain: Float = 0.7f,
    val titaniumSparkle: Float = 0.75f,
    val titaniumSheen: Float = 0.5f,
    val titaniumMetalness: Float = 0.35f,
    val titaniumCrestShiftMs: Int = 2000,
    val titaniumDithering: Boolean = true,
    val titaniumLyricScrim: Boolean = false,
) {
    fun encode(): String = listOf(
        "style=${style.storageValue}",
        "flow=$flowSpeed",
        "fps=$fps",
        "scale=$renderScale",
        "static=$staticMode",
        "volume=$volume",
        "presets=${meshPresetPool.sorted().joinToString(",")}",
        "grain=$titaniumGrain",
        "sparkle=$titaniumSparkle",
        "sheen=$titaniumSheen",
        "metal=$titaniumMetalness",
        "crest=$titaniumCrestShiftMs",
        "dither=$titaniumDithering",
        "scrim=$titaniumLyricScrim",
    ).joinToString(";")

    companion object {
        val Default = DynamicBackdropSettings()

        fun decode(raw: String?): DynamicBackdropSettings {
            if (raw.isNullOrBlank()) return Default
            val values = raw.split(';').mapNotNull { entry ->
                val index = entry.indexOf('=')
                if (index <= 0) null else entry.substring(0, index) to entry.substring(index + 1)
            }.toMap()
            val d = Default
            return DynamicBackdropSettings(
                style = DynamicBackdropStyle.fromStorage(values["style"]),
                flowSpeed = (values["flow"]?.toFloatOrNull() ?: d.flowSpeed).coerceIn(0f, 3f),
                fps = (values["fps"]?.toIntOrNull() ?: d.fps).coerceIn(15, 120),
                renderScale = (values["scale"]?.toFloatOrNull() ?: d.renderScale).coerceIn(0.25f, 1f),
                staticMode = values["static"]?.toBooleanStrictOrNull() ?: d.staticMode,
                volume = (values["volume"]?.toFloatOrNull() ?: d.volume).coerceIn(0f, 1f),
                meshPresetPool = values["presets"]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.filter { it in 0..7 }
                    ?.toSet()
                    ?: d.meshPresetPool,
                titaniumGrain = (values["grain"]?.toFloatOrNull() ?: d.titaniumGrain).coerceIn(0f, 1f),
                titaniumSparkle = (values["sparkle"]?.toFloatOrNull() ?: d.titaniumSparkle).coerceIn(0f, 1f),
                titaniumSheen = (values["sheen"]?.toFloatOrNull() ?: d.titaniumSheen).coerceIn(0f, 1f),
                titaniumMetalness = (values["metal"]?.toFloatOrNull() ?: d.titaniumMetalness).coerceIn(0f, 1f),
                titaniumCrestShiftMs = (values["crest"]?.toIntOrNull() ?: d.titaniumCrestShiftMs).coerceIn(300, 6000),
                titaniumDithering = values["dither"]?.toBooleanStrictOrNull() ?: d.titaniumDithering,
                titaniumLyricScrim = values["scrim"]?.toBooleanStrictOrNull() ?: d.titaniumLyricScrim,
            )
        }
    }
}
