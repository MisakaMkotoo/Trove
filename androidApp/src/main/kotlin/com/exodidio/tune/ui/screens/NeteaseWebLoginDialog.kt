package com.exodidio.tune.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import kotlinx.coroutines.delay

/** 网页登录：走网易云网页登录态，成功后从 CookieManager 读取 MUSIC_U。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NeteaseWebLoginDialog(
    onDismiss: () -> Unit,
    onLoggedIn: (String) -> Unit,
) {
    var status by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun checkLogin() {
        val cookie = readMusicCookie()
        if (cookie != null) {
            status = "已获取登录凭证"
            onLoggedIn(cookie)
        } else {
            status = "还没有检测到登录状态"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Close,
                            contentDescription = stringResource(R.string.cancel),
                            size = 22.dp,
                        )
                    }
                    Text(
                        text = stringResource(R.string.netease_login_web),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = ::checkLogin) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Check,
                            contentDescription = stringResource(R.string.netease_login_done),
                            size = 22.dp,
                        )
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    if (readMusicCookie() != null) checkLogin()
                                }
                            }
                            loadUrl("https://music.163.com/#/login")
                            webView = this
                        }
                    },
                )
            }
        }
    }

    // 登录页是单页应用，页面不一定再次触发 onPageFinished，定时轮询 Cookie。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            val cookie = readMusicCookie()
            if (cookie != null) {
                status = "已获取登录凭证"
                onLoggedIn(cookie)
                return@LaunchedEffect
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
}

/** 读取网易云域下的登录 Cookie；只有包含 MUSIC_U 才算登录成功。 */
internal fun readMusicCookie(): String? {
    val manager = CookieManager.getInstance()
    val raw = manager.getCookie("https://music.163.com").orEmpty()
    if (raw.isBlank()) return null
    if (!Regex("""MUSIC_U=[^;]+""").containsMatchIn(raw)) return null
    return raw
}
