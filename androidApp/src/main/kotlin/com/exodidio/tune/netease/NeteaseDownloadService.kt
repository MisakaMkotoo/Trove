package com.exodidio.tune.netease

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.exodidio.tune.MainActivity
import com.exodidio.tune.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 前台下载服务：展示歌曲下载队列进度并支持一键取消。 */
class NeteaseDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null
    private lateinit var notifications: NotificationManager
    private var lastNotifyAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.downloads_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notifications.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                NeteaseDownloadManager.cancelAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        completed = 0,
                        total = 1,
                        subtitle = getString(R.string.downloads_status_queued),
                        progressPercent = 0,
                        indeterminate = true,
                    ),
                )
                ensureObserving()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureObserving() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            combine(
                NeteaseDownloadManager.tasks,
                NeteaseDownloadManager.sessionCompleted,
            ) { tasks, completed -> tasks to completed }
                .collect { (tasks, completed) ->
                    val active = tasks.values.filter {
                        it.status == DownloadTaskStatus.Queued || it.status == DownloadTaskStatus.Downloading
                    }
                    if (active.isEmpty()) {
                        // 等待一拍防止任务入队前瞬间抖动，确认全部结束后移除前台通知。
                        delay(450L)
                        val latestActive = NeteaseDownloadManager.tasks.value.values.any {
                            it.status == DownloadTaskStatus.Queued || it.status == DownloadTaskStatus.Downloading
                        }
                        if (!latestActive) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        return@collect
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifyAtMs < 350L) return@collect
                    lastNotifyAtMs = now
                    val total = (completed + active.size).coerceAtLeast(1)
                    val currentIndex = (completed + 1).coerceAtMost(total)
                    val downloading = active.firstOrNull { it.status == DownloadTaskStatus.Downloading } ?: active.first()
                    val fraction = downloading.progress
                    val percent = if (fraction != null) {
                        ((completed + fraction) / total.toFloat() * 100f).toInt().coerceIn(0, 100)
                    } else {
                        (completed * 100 / total).coerceIn(0, 100)
                    }
                    notifications.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            completed = currentIndex,
                            total = total,
                            subtitle = downloading.entry.title,
                            progressPercent = percent,
                            indeterminate = fraction == null && active.size == 1,
                        ),
                    )
                }
        }
    }

    private fun buildNotification(
        completed: Int,
        total: Int,
        subtitle: String,
        progressPercent: Int,
        indeterminate: Boolean,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelAll = PendingIntent.getService(
            this,
            1,
            Intent(this, NeteaseDownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (total > 0 && completed > 0) {
            getString(R.string.downloads_notif_progress, completed, total)
        } else {
            getString(R.string.downloads_channel_name)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_app)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setProgress(100, progressPercent, indeterminate)
            .addAction(0, getString(R.string.downloads_cancel), cancelAll)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.exodidio.tune.netease.DOWNLOAD_START"
        const val ACTION_CANCEL_ALL = "com.exodidio.tune.netease.DOWNLOAD_CANCEL_ALL"
        private const val CHANNEL_ID = "netease_downloads"
        private const val NOTIFICATION_ID = 4320
    }
}
