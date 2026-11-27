package com.exodidio.tune.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.exodidio.tune.R
import com.xzakota.hyper.notification.focus.FocusNotification
import com.xzakota.hyper.notification.island.model.BigIslandArea
import com.xzakota.hyper.notification.island.model.TextInfo

/**
 * HyperOS 超级岛歌词（Focus V3）。
 * 只在 Focus 协议版本为 3（HyperOS 3+）时启用；payload 通过独立的
 * special-use 前台服务发布，否则 HyperOS 会把 Focus 当作普通通知处理。
 * 参数照搬参考实现（MeloX，Apache-2.0 的 Focus 封装）。
 */
internal object SuperIslandLyrics {
    private const val FocusProtocolSetting = "notification_focus_protocol"
    private const val IslandProtocolVersion = 3
    private const val ChannelId = "super_island_lyrics"

    @Volatile
    private var lastPublishedKey: String? = null

    @Volatile
    private var unavailableForSession = false

    fun isSupported(context: Context): Boolean = protocolVersion(context) == IslandProtocolVersion

    fun publish(
        context: Context,
        lyric: String,
        nextLyric: String,
        songTitle: String,
        artist: String,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        if (unavailableForSession || !isSupported(context)) return
        val text = lyric.trim()
        if (text.isBlank()) {
            stop(context)
            return
        }
        runCatching {
            publishInternal(context, text, nextLyric.trim(), songTitle, artist, positionMs, durationMs, isPlaying)
        }.onFailure {
            unavailableForSession = true
        }
    }

    fun stop(context: Context) {
        lastPublishedKey = null
        runCatching { SuperIslandLyricService.stop(context.applicationContext) }
    }

    private fun protocolVersion(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, FocusProtocolSetting, 0)
    }.getOrDefault(0)

    private fun publishInternal(
        context: Context,
        text: String,
        nextLyric: String,
        songTitle: String,
        artist: String,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        val progress = if (durationMs > 0L) {
            ((positionMs.coerceIn(0L, durationMs) * 100L) / durationMs).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val key = listOf(songTitle, artist, text, nextLyric, progress / 2, isPlaying).joinToString("|")
        if (key == lastPublishedKey) return
        lastPublishedKey = key

        val appContext = context.applicationContext
        ensureChannel(appContext)
        val songLabel = listOf(songTitle.trim(), artist.trim())
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .ifBlank { appContext.getString(R.string.app_name) }

        val focusExtras = FocusNotification.buildV3 {
            business = "lyric_display"
            // 岛数据照常渲染，但不再额外生成一条通知卡片：媒体通知保持唯一可见通知。
            isShowNotification = false
            enableFloat = false
            updatable = true
            islandFirstFloat = false
            aodTitle = text.take(20)
            ticker = text

            chatInfo {
                title = text
                content = songLabel
                appIconPkg = appContext.packageName
            }

            progressInfo {
                this.progress = progress
                colorProgress = "#FF757575"
                colorProgressEnd = "#FF757575"
            }

            island {
                islandProperty = 1
                bigIslandArea {
                    applyLyric(text)
                }
                smallIslandArea {
                    combinePicInfo {
                        progressInfo {
                            this.progress = progress
                            colorReach = "#FF757575"
                            colorUnReach = "#333333"
                        }
                    }
                }
            }
        }

        val notification = Notification.Builder(appContext, ChannelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(text)
            .setContentText(songLabel)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setOngoing(isPlaying)
            .setTimeoutAfter(if (isPlaying) 15_000L else 5_000L)
            .addExtras(focusExtras)
            .build()

        SuperIslandLyricService.publish(appContext, notification)
    }

    /** 大岛左右两区被中间挖孔隔开：同一句歌词按中间断成两半，左半放左区、右半放右区。 */
    private fun BigIslandArea.applyLyric(lyric: String) {
        val (leftText, rightText) = splitIslandHalves(lyric)
        imageTextInfoLeft {
            type = 1
            textInfo {
                title = leftText.take(42)
                showHighlightColor = false
                narrowFont = false
            }
        }
        textInfo = TextInfo().apply {
            title = rightText.take(42)
            showHighlightColor = false
            narrowFont = false
        }
    }

    /** 在中点附近优先按标点/空格断开，避免把词拆得太碎；找不到就近硬切。 */
    private fun splitIslandHalves(text: String): Pair<String, String> {
        if (text.length < 2) return text to ""
        val mid = text.length / 2
        var cut = mid
        var bestDistance = Int.MAX_VALUE
        text.forEachIndexed { index, char ->
            if (char !in IslandSplitChars) return@forEachIndexed
            val distance = kotlin.math.abs(index - mid)
            if (distance <= 4 && distance < bestDistance) {
                bestDistance = distance
                cut = index + 1
            }
        }
        val left = text.substring(0, cut).trim()
        val right = text.substring(cut).trim()
        if (left.isEmpty() || right.isEmpty()) return text to ""
        return left to right
    }

    private val IslandSplitChars = charArrayOf(
        ' ', '，', ',', '、', '。', '.', '！', '!', '？', '?', '；', ';', '：', ':', '·', '—', '~',
    )

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ChannelId) != null) return
        // LOW 通道无法升级为 Focus 通道：必须单独建一个静音的高重要性通道。
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                context.getString(R.string.lyrics_super_island_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.lyrics_super_island_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }
}
