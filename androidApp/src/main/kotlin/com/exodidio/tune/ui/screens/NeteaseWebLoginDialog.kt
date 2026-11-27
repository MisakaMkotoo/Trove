package com.exodidio.tune.ui.screens

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneGlassIconButton
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 网页登录的两种页面形态：手机版（仅手机号）与电脑版（支持扫码）。 */
internal enum class NeteaseWebLoginMode { Mobile, Desktop }

private const val DesktopUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** 桌面环境伪装：UA 之外 WebView 仍会暴露手机信号（Client Hints/触摸/屏幕尺寸）。 */
private const val DesktopSpoofScript = """
(function () {
  try {
    var fake = {
      mobile: false,
      platform: 'macOS',
      brands: [{brand: 'Chromium', version: '124'}, {brand: 'Google Chrome', version: '124'}],
      getHighEntropyValues: function () {
        return Promise.resolve({
          platform: 'macOS', platformVersion: '14.0.0', architecture: 'x86',
          bitness: '64', model: '', uaFullVersion: '124.0.0.0', fullVersionList: []
        });
      }
    };
    fake.toJSON = function () { return this; };
    Object.defineProperty(navigator, 'userAgentData', { get: function () { return fake; } });
  } catch (e) {}
  try { Object.defineProperty(navigator, 'platform', { get: function () { return 'MacIntel'; } }); } catch (e) {}
  try { Object.defineProperty(navigator, 'maxTouchPoints', { get: function () { return 0; } }); } catch (e) {}
  try { Object.defineProperty(screen, 'width', { get: function () { return 1280; } }); } catch (e) {}
  try { Object.defineProperty(screen, 'height', { get: function () { return 800; } }); } catch (e) {}
  try { Object.defineProperty(window, 'devicePixelRatio', { get: function () { return 1; } }); } catch (e) {}
})()
"""

/** 一次性诊断：确认页面看到的 UA/平台/触摸点/屏幕宽度与最终 URL。 */
private const val DesktopDiagScript = """
(function () {
  try {
    return JSON.stringify({
      ua: navigator.userAgent.slice(0, 90),
      mobile: navigator.userAgentData ? navigator.userAgentData.mobile : 'n/a',
      platform: navigator.platform,
      touch: navigator.maxTouchPoints,
      sw: screen.width,
      dpr: window.devicePixelRatio,
      href: location.href
    });
  } catch (e) { return 'err:' + e; }
})()
"""

/**
 * 电脑端页面注入脚本：任意页面触发顶栏「登录」打开弹窗，再把弹窗精简成只留二维码。
 * 登录弹窗在顶层文档（.mrc-modal-container），canvas 是 152×152；允许 iframe 作为兜底。
 * React 重渲染会重置样式，所以页面打开期间周期执行。
 */
private const val DesktopLoginInjectScript = """
(function () {
  try {
    var canvas = document.querySelector('canvas');
    var usedFrame = false;
    if (!canvas) {
      var f = document.querySelector('#g_iframe');
      var fd = f && f.contentDocument;
      var framed = fd && fd.querySelector('canvas');
      if (framed) { canvas = framed; usedFrame = true; }
    }
    if (!canvas) {
      // 弹窗没开：点顶栏「登录」（任意页面都会弹出同一个弹窗）。
      var loginBtn = null;
      Array.prototype.forEach.call(document.querySelectorAll('a'), function (a) {
        if (loginBtn) return;
        if ((a.textContent || '').trim() === '登录' && a.getBoundingClientRect().top < 160) loginBtn = a;
      });
      if (loginBtn) { loginBtn.click(); return 'clicked'; }
      return 'no-qr';
    }
    var doc = canvas.ownerDocument;
    var qrBox = canvas.parentElement;
    var row = qrBox && qrBox.parentElement;
    var modal = qrBox && qrBox.closest
      ? qrBox.closest('.mrc-modal-container, [class*="mrc-modal-container"]')
      : null;
    if (row) {
      Array.prototype.forEach.call(row.children, function (ch) {
        ch.style.display = ch.contains(canvas) ? '' : 'none';
      });
      row.style.width = 'auto';
      row.style.margin = '0 auto';
      row.style.background = 'transparent';
      row.style.boxShadow = 'none';
      row.style.border = '0';
    }
    Array.prototype.forEach.call(doc.querySelectorAll('a, div, span'), function (e) {
      var t = (e.textContent || '').trim();
      if (t === '选择其他登录模式' || t === '下载客户端，VIP歌曲免费听' || t === '登录获取更懂你的好音乐') {
        e.style.display = 'none';
      }
    });
    if (modal) {
      // 把弹窗从页面里“抠”出来：隐藏它之外的所有内容，去掉遮罩，固定居中。
      Array.prototype.forEach.call(document.body.children, function (el) {
        if (el === modal || el.contains(modal)) return;
        el.style.visibility = 'hidden';
      });
      var node = modal;
      while (node && node !== document.body) {
        var parent = node.parentElement;
        if (parent) {
          Array.prototype.forEach.call(parent.children, function (el) {
            if (el !== node) el.style.visibility = 'hidden';
          });
          var pcs = getComputedStyle(parent);
          if (pcs.backgroundColor !== 'rgba(0, 0, 0, 0)') parent.style.background = 'transparent';
        }
        node = parent;
      }
      modal.style.position = 'fixed';
      modal.style.left = '50%';
      modal.style.top = '50%';
      modal.style.transform = 'translate(-50%, -50%)';
      modal.style.margin = '0';
      modal.style.width = 'auto';
      modal.style.minWidth = '0';
      modal.style.background = 'transparent';
      modal.style.boxShadow = 'none';
      modal.style.zIndex = '99999';
      var head = modal.firstElementChild;
      if (head && !head.contains(canvas)) head.style.display = 'none';
    }
    var size = Math.round(Math.max(240, Math.min(860, window.innerWidth * 0.82)));
    if (qrBox) {
      qrBox.style.width = size + 'px';
      qrBox.style.height = size + 'px';
      qrBox.style.boxSizing = 'content-box';
    }
    canvas.style.width = (size - 16) + 'px';
    canvas.style.height = (size - 16) + 'px';
    return (usedFrame ? 'ok-frame:' : 'ok-top:') + size;
  } catch (e) { return 'err:' + e; }
})()
"""

/** 网页登录：走网易云网页登录态，成功后从 CookieManager 读取 MUSIC_U。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NeteaseWebLoginDialog(
    mode: NeteaseWebLoginMode = NeteaseWebLoginMode.Mobile,
    onDismiss: () -> Unit,
    onLoggedIn: (String) -> Unit,
) {
    val colors = LocalTuneColors.current
    val desktop = mode == NeteaseWebLoginMode.Desktop
    val credentialObtainedText = stringResource(R.string.netease_login_credential_obtained)
    val notDetectedText = stringResource(R.string.netease_login_not_detected)
    var status by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var qrReady by remember { mutableStateOf(false) }
    var mobilePageLoaded by remember { mutableStateOf(false) }

    fun checkLogin() {
        val cookie = readMusicCookie()
        if (cookie != null) {
            status = credentialObtainedText
            onLoggedIn(cookie)
        } else {
            status = notDetectedText
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            TuneWebDialogTopBar(
                title = stringResource(
                    if (desktop) R.string.netease_login_qr else R.string.netease_login_web,
                ),
                confirmLabel = stringResource(R.string.netease_login_done),
                onDismiss = onDismiss,
                onConfirm = ::checkLogin,
            )
            AnimatedVisibility(visible = pageProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.primary,
                    trackColor = Color.Transparent,
                )
            }
            AnimatedVisibility(visible = status.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.card)
                        .border(0.5.dp, colors.borderGlass, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMain,
                    )
                }
            }
            if (desktop) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.netease_web_qr_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            textAlign = TextAlign.Center,
                        )
                        val qrCardShape = RoundedCornerShape(28.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 320.dp)
                                .aspectRatio(1f)
                                .clip(qrCardShape)
                                .background(Color.White)
                                .border(0.5.dp, colors.borderGlass, qrCardShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { viewContext ->
                                    WebView(viewContext).apply {
                                        setBackgroundColor(android.graphics.Color.WHITE)
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString = DesktopUserAgent
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.builtInZoomControls = false
                                        settings.displayZoomControls = false
                                        CookieManager.getInstance().setAcceptCookie(true)
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                            runCatching {
                                                WebViewCompat.addDocumentStartJavaScript(this, DesktopSpoofScript, setOf("*"))
                                            }.onFailure {
                                                Log.w("NeteaseLogin", "docstart failed: ${it.message}")
                                            }
                                        }
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                pageProgress = newProgress
                                            }
                                        }
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                                Log.d("NeteaseLogin", "page start url=${url.take(160)}")
                                                super.onPageStarted(view, url, favicon)
                                            }

                                            override fun onPageFinished(view: WebView, url: String) {
                                                Log.d("NeteaseLogin", "page finish url=${url.take(160)}")
                                                view.evaluateJavascript(DesktopSpoofScript, null)
                                                view.evaluateJavascript(DesktopLoginInjectScript) { result ->
                                                    Log.d("NeteaseLogin", "inject result=$result")
                                                    if (result?.contains("ok-") == true) qrReady = true
                                                }
                                                view.evaluateJavascript(DesktopDiagScript) { diag ->
                                                    Log.d("NeteaseLogin", "diag=$diag")
                                                }
                                                if (readMusicCookie() != null) checkLogin()
                                            }
                                        }
                                        loadUrl("https://music.163.com/")
                                        webView = this
                                    }
                                },
                            )
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !qrReady,
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(220)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(colors.card),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 2.5.dp,
                                            color = colors.primary,
                                        )
                                        Text(
                                            text = stringResource(R.string.netease_web_loading_qr),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(sheetShape)
                        .background(colors.card)
                        .border(0.5.dp, colors.borderGlass, sheetShape),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            WebView(viewContext).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        pageProgress = newProgress
                                        if (newProgress >= 85) mobilePageLoaded = true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        mobilePageLoaded = true
                                        if (readMusicCookie() != null) checkLogin()
                                    }
                                }
                                loadUrl("https://music.163.com/#/login")
                                webView = this
                            }
                        },
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !mobilePageLoaded,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(200)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(colors.card),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 2.5.dp,
                                    color = colors.primary,
                                )
                                Text(
                                    text = stringResource(R.string.netease_web_loading_page),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 电脑端：React 重渲染会覆盖内联样式，登录页打开期间周期重放注入脚本。
    // 两段脚本分开执行，避免拼接后语法上连成一次函数调用导致整段中止。
    LaunchedEffect(desktop, webView) {
        if (!desktop) return@LaunchedEffect
        while (true) {
            webView?.evaluateJavascript(DesktopSpoofScript, null)
            webView?.evaluateJavascript(DesktopLoginInjectScript) { result ->
                if (result?.contains("ok-") == true) qrReady = true
            }
            delay(800)
        }
    }

    // 登录页是单页应用，页面不一定再次触发 onPageFinished，定时在 IO 线程轮询 Cookie。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            val cookie = withContext(Dispatchers.IO) { readMusicCookie() }
            if (cookie != null) {
                status = credentialObtainedText
                onLoggedIn(cookie)
                return@LaunchedEffect
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
}

/** 风控人机验证页：在 WebView 里完成网易下发的验证步骤。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NeteaseVerifyDialog(
    url: String,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
) {
    val colors = LocalTuneColors.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            TuneWebDialogTopBar(
                title = stringResource(R.string.account_qr_verify_title),
                confirmLabel = stringResource(R.string.account_qr_verify_done),
                onDismiss = onDismiss,
                onConfirm = onCompleted,
            )
            AnimatedVisibility(visible = pageProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.primary,
                    trackColor = Color.Transparent,
                )
            }
            val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(sheetShape)
                    .background(colors.card)
                    .border(0.5.dp, colors.borderGlass, sheetShape),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    pageProgress = newProgress
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
}

@Composable
private fun TuneWebDialogTopBar(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalTuneColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneGlassIconButton(
            hazeState = null,
            symbol = MaterialSymbols.Close,
            label = stringResource(R.string.cancel),
            onClick = onDismiss,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.weight(1f))
        TuneGlassIconButton(
            hazeState = null,
            symbol = MaterialSymbols.Check,
            label = confirmLabel,
            onClick = onConfirm,
        )
    }
}

/** 读取 WebView 在网易域下的 Cookie（含验证页域名），用于验证后重新出码。 */
internal fun readNeteaseCookieHeader(): String? {
    val manager = CookieManager.getInstance()
    val byName = LinkedHashMap<String, String>()
    listOf("https://music.163.com", "https://st.music.163.com").forEach { domain ->
        manager.getCookie(domain).orEmpty().split(';').map(String::trim)
            .filter { it.contains('=') }
            .forEach { segment -> byName[segment.substringBefore('=').trim()] = segment }
    }
    return byName.values.joinToString("; ").takeIf { it.isNotBlank() }
}

/** 读取网易云域下的登录 Cookie；只有包含 MUSIC_U 才算登录成功。 */
internal fun readMusicCookie(): String? {
    val manager = CookieManager.getInstance()
    val raw = manager.getCookie("https://music.163.com").orEmpty()
    if (raw.isBlank()) return null
    if (!Regex("""MUSIC_U=[^;]+""").containsMatchIn(raw)) return null
    return raw
}
