package com.exodidio.tune.lyrics.autosync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.exodidio.tune.MainActivity
import com.exodidio.tune.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 前台下载服务：对齐模型与日文字典，显示进度、可取消。 */
class AutoSyncDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.autosync_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notifications.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                finish(notify = false)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (job?.isActive == true) {
                    // 重复启动（按钮连点/系统重投递）不能取消并重头下载。
                    AutoSyncTrace.log(this, "download already running, ignoring duplicate start")
                    return START_NOT_STICKY
                }
                val kind = intent.getStringExtra(EXTRA_KIND) ?: AutoSyncDownloads.KIND_MODEL
                val language = intent.getStringExtra(EXTRA_LANGUAGE)
                AutoSyncTrace.log(this, "download start kind=$kind language=$language")
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        kind,
                        language,
                        0f,
                        getString(R.string.autosync_download_preparing),
                        null,
                    ),
                )
                job = scope.launch { run(kind, language) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(kind: String, language: String?) {
        try {
            if (kind == AutoSyncDownloads.KIND_DICTIONARY) {
                AutoSyncDictionary.install(this) { progress, message ->
                    publish(AutoSyncDownloads.State(kind, null, progress, message), null)
                }
            } else {
                val target = language ?: throw IllegalArgumentException("缺少语言参数")
                AutoSyncInstaller.install(this, target) { progress, message ->
                    publish(AutoSyncDownloads.State(kind, target, progress, message), null)
                }
            }
            val final = AutoSyncDownloads.State(
                kind,
                language,
                progress = 1f,
                message = getString(R.string.autosync_download_done),
                running = false,
            )
            AutoSyncDownloads.state.value = final
            publishNotification(final, null)
            delay(900)
            finish(notify = false)
        } catch (cancelled: CancellationException) {
            val state = AutoSyncDownloads.State(
                kind,
                language,
                progress = 0f,
                message = getString(R.string.autosync_download_cancelled),
                running = false,
            )
            AutoSyncDownloads.state.value = state
            finish(notify = false)
            throw cancelled
        } catch (error: Throwable) {
            AutoSyncTrace.log(this, "download failed kind=$kind language=$language", error)
            val state = AutoSyncDownloads.State(
                kind,
                language,
                progress = 0f,
                message = getString(R.string.autosync_download_failed, error.message.orEmpty()),
                error = error.message ?: error.javaClass.simpleName,
                running = false,
            )
            AutoSyncDownloads.state.value = state
            publishNotification(state, state.error)
            delay(2_500)
            finish(notify = false)
        }
    }

    private fun publish(state: AutoSyncDownloads.State, error: String?) {
        AutoSyncDownloads.state.value = state
        publishNotification(state, error)
    }

    private fun publishNotification(state: AutoSyncDownloads.State, error: String?) {
        val text = when {
            error != null -> getString(R.string.autosync_download_failed, error)
            else -> state.message
        }
        notifications.notify(NOTIFICATION_ID, buildNotification(state.kind, state.language, state.progress, text, error))
    }

    private fun buildNotification(
        kind: String,
        language: String?,
        progress: Float,
        text: String,
        error: String?,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, AutoSyncDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (kind) {
            AutoSyncDownloads.KIND_DICTIONARY -> getString(R.string.autosync_download_dict_title)
            else -> getString(R.string.autosync_download_model_title_language, language ?: "")
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(error == null)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), error != null)
            .addAction(0, getString(R.string.autosync_download_cancel), cancel)
            .build()
    }

    private fun finish(notify: Boolean) {
        if (notify) notifications.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.exodidio.tune.autosync.START"
        const val ACTION_CANCEL = "com.exodidio.tune.autosync.CANCEL"
        const val EXTRA_KIND = "kind"
        const val EXTRA_LANGUAGE = "language"
        private const val CHANNEL_ID = "autosync_downloads"
        private const val NOTIFICATION_ID = 4310
    }
}
