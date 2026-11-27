package com.exodidio.tune.player

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 用独立的 special-use 前台服务发布 HyperOS Focus 通知。
 * 普通 notify() 附带的 Focus payload 会被 HyperOS 降级成普通通知；
 * payload 需要图片等进程内资源，所以走静态持有的最新通知而不是跨 Binder。
 */
class SuperIslandLyricService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            pendingNotification = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = pendingNotification
        if (notification == null) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching { startWithFocus(notification) }
            .onFailure { Log.w(Tag, "Unable to start Super Island lyric service", it) }
            .isSuccess
        running = started
        if (!started) stopSelf()
        return START_NOT_STICKY
    }

    private fun startWithFocus(notification: Notification) {
        startForeground(
            NotificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val Tag = "SuperIslandLyrics"
        private const val NotificationId = 1703
        private const val ActionPublish = "com.exodidio.tune.player.SUPER_ISLAND_PUBLISH"
        private const val ActionStop = "com.exodidio.tune.player.SUPER_ISLAND_STOP"

        @Volatile
        private var pendingNotification: Notification? = null

        @Volatile
        private var running = false

        fun publish(context: Context, notification: Notification) {
            pendingNotification = notification
            val appContext = context.applicationContext
            val intent = Intent(appContext, SuperIslandLyricService::class.java).setAction(ActionPublish)
            val updated = if (running) {
                runCatching { appContext.startService(intent) }.isSuccess
            } else {
                false
            }
            if (updated) return
            val foreground = runCatching { appContext.startForegroundService(intent) }
                .onFailure { Log.w(Tag, "Unable to start Super Island lyric service", it) }
                .isSuccess
            if (!foreground) running = false
        }

        fun stop(context: Context) {
            pendingNotification = null
            running = false
            val appContext = context.applicationContext
            runCatching { appContext.stopService(Intent(appContext, SuperIslandLyricService::class.java)) }
            runCatching {
                appContext.getSystemService(android.app.NotificationManager::class.java)?.cancel(NotificationId)
            }
        }
    }
}
