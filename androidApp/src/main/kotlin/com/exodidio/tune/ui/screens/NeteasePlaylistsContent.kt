package com.exodidio.tune.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.netease.NeteaseStreamProxy
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.components.TuneTextField
import com.exodidio.tune.ui.components.TuneTextFieldSize
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun NeteasePlaylistsContent(
    modifier: Modifier = Modifier,
    onOpenPlaylist: (String) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by NeteaseRepository.playlists.collectAsStateWithLifecycle()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var qrLogin by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var qrStatus by remember { mutableStateOf("") }
    var showWebLogin by remember { mutableStateOf(false) }
    var cacheSizeMb by remember { mutableStateOf(NeteaseStreamProxy.sizeLimitMb(context)) }
    var cacheUsage by remember { mutableStateOf(NeteaseStreamProxy.usageBytes()) }

    fun importPlaylist() {
        if (busy || link.isBlank()) return
        busy = true
        status = ""
        scope.launch {
            val result = NeteaseRepository.import(context, link)
            status = result.fold(
                onSuccess = { "已导入歌单" },
                onFailure = { it.message ?: "导入失败" },
            )
            if (result.isSuccess) link = ""
            busy = false
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(
            symbol = MaterialSymbols.MusicNote,
            title = stringResource(R.string.netease_playlists_title),
            description = stringResource(R.string.netease_playlists_desc),
        )
        Card {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TuneTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = stringResource(R.string.netease_playlist_link_hint),
                    size = TuneTextFieldSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(horizontal = 12.dp).size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = ::importPlaylist, enabled = link.isNotBlank()) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Add,
                            contentDescription = stringResource(R.string.netease_import_action),
                            size = 22.dp,
                            tint = colors.primary,
                        )
                    }
                }
            }
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            ActionList(
                items = buildList {
                    if (loggedIn) {
                        add(
                            ActionListItem(
                                labelRes = R.string.netease_logout,
                                leadingSymbol = MaterialSymbols.Person,
                                onClick = { NeteaseRepository.clearLogin(context) },
                            ),
                        )
                    } else {
                        add(
                            ActionListItem(
                                labelRes = R.string.netease_login_qr,
                                leadingSymbol = MaterialSymbols.Person,
                                onClick = {
                                    scope.launch {
                                        NeteaseRepository.createQrLogin().fold(
                                            onSuccess = { qr -> qrStatus = ""; qrLogin = Triple(qr.key, qr.url, qr.cookie) },
                                            onFailure = { qrStatus = it.message ?: "无法获取二维码" },
                                        )
                                    }
                                },
                            ),
                        )
                        add(
                            ActionListItem(
                                labelRes = R.string.netease_login_web,
                                leadingSymbol = MaterialSymbols.Translate,
                                onClick = { showWebLogin = true },
                            ),
                        )
                    }
                },
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
        Card {
            Text(
                text = stringResource(R.string.netease_cache_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.textMain,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
            Text(
                text = stringResource(
                    R.string.netease_cache_usage,
                    formatCacheSize(cacheUsage),
                    formatCacheSize(cacheSizeMb.toLong() * 1024 * 1024),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            listOf(200, 500, 800, 1536, 3072).forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            NeteaseStreamProxy.setSizeLimitMb(context, preset)
                            cacheSizeMb = preset
                            cacheUsage = NeteaseStreamProxy.usageBytes()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatCacheSize(preset.toLong() * 1024 * 1024),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMain,
                        modifier = Modifier.weight(1f),
                    )
                    if (preset == cacheSizeMb) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Check,
                            contentDescription = stringResource(R.string.netease_cache_current),
                            size = 18.dp,
                            tint = colors.primary,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        NeteaseStreamProxy.clearCache()
                        cacheUsage = NeteaseStreamProxy.usageBytes()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.netease_cache_clear),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMain,
                    modifier = Modifier.weight(1f),
                )
                MaterialSymbol(
                    symbol = MaterialSymbols.Delete,
                    contentDescription = null,
                    size = 18.dp,
                    tint = colors.textMuted,
                )
            }
        }
        val visiblePlaylists = remember(playlists) { playlists.filter { it.dynamicKind == null } }
        if (visiblePlaylists.isEmpty()) {
            Text(
                text = stringResource(R.string.netease_playlists_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Card {
                visiblePlaylists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                NeteaseRepository.select(playlist.id)
                                onOpenPlaylist(playlist.id)
                            }
                            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.netease_playlist_entry_count, playlist.entries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    NeteaseRepository.refresh(context, playlist.id)
                                }
                            },
                        ) {
                            MaterialSymbol(
                                symbol = MaterialSymbols.Refresh,
                                contentDescription = stringResource(R.string.netease_refresh),
                                size = 20.dp,
                                tint = colors.textMuted,
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch { NeteaseRepository.delete(playlist.id) }
                            },
                        ) {
                            MaterialSymbol(
                                symbol = MaterialSymbols.Delete,
                                contentDescription = stringResource(R.string.netease_delete),
                                size = 20.dp,
                                tint = colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }

    qrLogin?.let { (key, url, cookie) ->
        QrLoginSheet(
            key = key,
            url = url,
            cookie = cookie,
            context = context,
            initialStatus = qrStatus,
            onDone = { success ->
                qrLogin = null
                if (success) status = "登录成功"
            },
        )
    }
    if (showWebLogin) {
        NeteaseWebLoginDialog(
            onDismiss = { showWebLogin = false },
            onLoggedIn = { cookie ->
                NeteaseRepository.saveLogin(context, cookie)
                showWebLogin = false
                status = "登录成功"
            },
        )
    }
}

@Composable
private fun QrLoginSheet(
    key: String,
    url: String,
    cookie: String?,
    context: Context,
    initialStatus: String,
    onDone: (Boolean) -> Unit,
) {
    val colors = LocalTuneColors.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(initialStatus) }
    val bitmap = remember(url) { runCatching { qrBitmap(url, 512) }.getOrNull() }
    LaunchedEffect(key) {
        while (true) {
            delay(1_500)
            val result = NeteaseRepository.pollQrLogin(key, cookie)
            val polled = result.getOrElse { error ->
                status = "查询状态失败：${error.message ?: "网络错误"}"
                continue
            }
            when (polled.code) {
                800 -> status = "二维码已过期（code=800）"
                801 -> status = "等待扫码（code=801）"
                802 -> status = "已扫码，请在手机上确认（code=802）"
                803 -> {
                    val session = polled.cookie?.takeIf { it.contains("MUSIC_U=") }
                    if (session != null) {
                        NeteaseRepository.saveLogin(context, session)
                        status = "登录成功（code=803）"
                        delay(800)
                        onDone(true)
                    } else {
                        status = "code=803 但没有拿到 MUSIC_U"
                        onDone(false)
                    }
                    return@LaunchedEffect
                }
                else -> status = "等待扫码（状态 ${polled.code}${polled.message.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}）"
            }
        }
    }
    TuneBottomSheet(
        title = { Text(stringResource(R.string.netease_login_qr), color = colors.textMain) },
        onDismiss = { onDone(false) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(240.dp)) }
            Text(
                text = status.ifBlank { "请使用网易云音乐 App 扫码" },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

private fun qrBitmap(content: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx) { index ->
        val x = index % sizePx
        val y = index / sizePx
        if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes.toDouble() / (1024L * 1024))
    else -> "%.0f KB".format(bytes.toDouble() / 1024)
}
