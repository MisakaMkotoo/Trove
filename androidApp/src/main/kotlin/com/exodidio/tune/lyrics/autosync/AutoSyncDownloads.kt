package com.exodidio.tune.lyrics.autosync

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/** 对齐模型/字典下载的前台任务状态；UI 直接订阅这里。 */
internal object AutoSyncDownloads {
    const val KIND_MODEL = "model"
    const val KIND_DICTIONARY = "dictionary"

    data class State(
        val kind: String,
        val language: String?,
        val progress: Float,
        val message: String,
        val error: String? = null,
        val running: Boolean = true,
    )

    val state = MutableStateFlow<State?>(null)

    fun start(context: Context, kind: String, language: String? = null) {
        if (state.value?.running == true) return
        // 先落状态：按钮点下去立即出现进度条，不用等服务线程先发第一条进度。
        state.value = State(
            kind = kind,
            language = language,
            progress = 0f,
            message = context.getString(com.exodidio.tune.R.string.autosync_download_preparing),
        )
        val intent = Intent(context, AutoSyncDownloadService::class.java)
            .setAction(AutoSyncDownloadService.ACTION_START)
            .putExtra(AutoSyncDownloadService.EXTRA_KIND, kind)
            .putExtra(AutoSyncDownloadService.EXTRA_LANGUAGE, language)
        context.startForegroundService(intent)
    }

    fun cancel(context: Context) {
        val intent = Intent(context, AutoSyncDownloadService::class.java)
            .setAction(AutoSyncDownloadService.ACTION_CANCEL)
        context.startService(intent)
    }

    fun clearFinished() {
        if (state.value?.running != true) state.value = null
    }
}
