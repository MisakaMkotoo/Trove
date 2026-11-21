package com.exodidio.tune.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.lastfm.LastFmStatus
import com.exodidio.tune.lyrics.LyricsSettings
import com.exodidio.tune.lyrics.LyricsSource
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption
import com.exodidio.tune.ui.components.TunePillButton
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.rememberArtworkThumbnail
import com.exodidio.tune.sync.RemoteMusicLibrary
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.launch

@Composable
internal fun IntegrationContent(
    lastFmStatus: LastFmStatus,
    lyricsSettings: LyricsSettings,
    onLastFmSelected: () -> Unit,
    onLyricsSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remote by RemoteMusicLibrary.status.collectAsState()
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            clock = System.currentTimeMillis()
        }
    }
    Column(modifier = modifier) {
        LabeledCard(label = "服务状态") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InfoLine(
                    "电脑音乐库",
                    when {
                        !remote.configured -> "未配置"
                        remote.busy -> "同步中…（${remote.kind.ifBlank { "未知" }}）"
                        remote.online -> "${remote.kind}${if (remote.secure) " · HTTPS" else ""} · 在线"
                        else -> "离线 · 上次地址 ${remote.base}"
                    },
                )
                if (remote.configured) {
                    InfoLine(
                        "上次同步",
                        if (remote.lastSyncAt <= 0) "还没有成功同步过"
                        else "${relativeTime(remote.lastSyncAt, clock)} · ${remote.tracks} 首电脑曲目",
                    )
                }
                InfoLine(
                    "Last.fm",
                    when {
                        lastFmStatus.working -> "连接中…"
                        lastFmStatus.connected -> "已连接：${lastFmStatus.username}"
                        lastFmStatus.failed -> "连接失败"
                        else -> "未连接"
                    },
                )
                InfoLine(
                    "歌词源",
                    buildList {
                        add(if (lyricsSettings.lrclib) "LRCLIB 开" else "LRCLIB 关")
                        add(if (lyricsSettings.kugou) "酷狗 开" else "酷狗 关")
                        add(if (lyricsSettings.romanizationEnabled) "罗马音 开" else "罗马音 关")
                    }.joinToString(" · "),
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        RemoteLibraryCard()
        Spacer(modifier = Modifier.height(16.dp))
        ActionList(
            items = listOf(
                ActionListItem(R.string.lastfm_title, onClick = onLastFmSelected),
                ActionListItem(R.string.lyrics_title, onClick = onLyricsSelected),
            ),
            containerStyle = ActionListContainerStyle.Card,
            dividerStyle = ActionListDividerStyle.FullWidth,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalTuneColors.current.textMuted,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun relativeTime(timestamp: Long, now: Long): String {
    if (timestamp <= 0) return "从未"
    val seconds = ((now - timestamp) / 1000).coerceAtLeast(0)
    return when {
        seconds < 10 -> "刚刚"
        seconds < 60 -> "$seconds 秒前"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86_400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86_400} 天前"
    }
}

@Composable
internal fun LastFmContent(
    status: LastFmStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val avatar = rememberArtworkThumbnail(status.avatarPath)
    Column(modifier = modifier) {
        HeroCard(
            title = stringResource(R.string.lastfm_title),
            description = if (status.connected) {
                stringResource(R.string.lastfm_connected_as, status.username)
            } else {
                stringResource(R.string.lastfm_description)
            },
            bottomContent = {
                TunePillButton(
                    label = stringResource(
                        when {
                            status.working -> R.string.lastfm_connecting
                            status.connected -> R.string.lastfm_disconnect
                            else -> R.string.lastfm_connect
                        },
                    ),
                    onClick = if (status.connected) onDisconnect else onConnect,
                    enabled = status.configured && !status.working,
                    variant = if (status.connected) TunePillButtonVariant.Secondary else TunePillButtonVariant.Primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
                )
            },
        ) {
            if (status.connected && avatar != null) {
                Image(
                    bitmap = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).testTag("lastfm-avatar"),
                )
            } else {
                MaterialSymbol(
                    symbol = MaterialSymbols.GraphicEq,
                    contentDescription = null,
                    size = 40.dp,
                    tint = colors.textMuted,
                    modifier = Modifier.testTag("lastfm-icon"),
                )
            }
        }
        if (!status.configured || status.failed) {
            Text(
                text = stringResource(if (status.configured) R.string.lastfm_error else R.string.lastfm_not_configured),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
internal fun LyricsContent(
    settings: LyricsSettings,
    onSourceChanged: (LyricsSource) -> Unit,
    onLrclibChanged: (Boolean) -> Unit,
    onKugouChanged: (Boolean) -> Unit,
    onNeteaseChanged: (Boolean) -> Unit,
    onRomanizationEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LabeledCard(label = stringResource(R.string.lyrics_data_sources), modifier = modifier) {
        Selection(
            labelRes = R.string.lyrics_preferred_source,
            options = listOf(
                SelectionOption(LyricsSource.AutoFetch, R.string.lyrics_source_auto_fetch),
            ),
            selectedValue = settings.preferredSource,
            onValueSelected = onSourceChanged,
        )
        com.exodidio.tune.ui.components.ActionListDivider(style = ActionListDividerStyle.FullWidth)
        ActionList(
            items = listOf(
                ActionListItem(R.string.lyrics_lrclib, trailingContent = { Switch(checked = settings.lrclib, onCheckedChange = onLrclibChanged) }, onClick = { onLrclibChanged(!settings.lrclib) }),
                ActionListItem(R.string.lyrics_kugou, trailingContent = { Switch(checked = settings.kugou, onCheckedChange = onKugouChanged) }, onClick = { onKugouChanged(!settings.kugou) }),
                ActionListItem(R.string.lyrics_netease, trailingContent = { Switch(checked = settings.netease, onCheckedChange = onNeteaseChanged) }, onClick = { onNeteaseChanged(!settings.netease) }),
                ActionListItem(R.string.lyrics_romanization, trailingContent = { Switch(checked = settings.romanizationEnabled, onCheckedChange = onRomanizationEnabledChanged) }, onClick = { onRomanizationEnabledChanged(!settings.romanizationEnabled) }),
            ),
            containerStyle = ActionListContainerStyle.Plain,
            dividerStyle = ActionListDividerStyle.FullWidth,
        )
    }
}


/** 电脑音乐库：地址 + 配对码；连上后显示链路、地址、同步时间和证书指纹。 */
@Composable
private fun RemoteLibraryCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote by RemoteMusicLibrary.status.collectAsState()
    var base by remember { mutableStateOf(RemoteMusicLibrary.config(context)?.base.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tick = System.currentTimeMillis()
        }
    }
    val colors = LocalTuneColors.current
    val configured = remote.configured || RemoteMusicLibrary.isConfigured(context)
    val host = remote.host.ifBlank { RemoteMusicLibrary.config(context)?.host.orEmpty() }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = "电脑音乐库", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        if (!configured) {
            OutlinedTextField(
                value = base,
                onValueChange = { base = it },
                label = { Text("主机地址（http://IP:27185 或 https://IP:27186）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                label = { Text("6 位配对码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                enabled = !busy && base.isNotBlank() && code.length == 6,
                onClick = {
                    busy = true
                    status = "正在连接…"
                    scope.launch {
                        val paired = RemoteMusicLibrary.pair(context, base, code)
                        if (paired.isSuccess) {
                            status = "已连接，正在同步曲库…"
                            val synced = RemoteMusicLibrary.syncWithRetry(context, attempts = 2)
                            status = synced.fold(
                                onSuccess = { "已同步 $it 首电脑曲目" },
                                onFailure = { "已连接，但同步失败：${it.message}" },
                            )
                        } else {
                            status = "连接失败：${paired.exceptionOrNull()?.message}"
                        }
                        busy = false
                    }
                },
            ) { Text("连接") }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "家里填局域网 HTTP 地址；外网填公网 IPv6 的 HTTPS 地址。配对后电脑会把两套地址都发过来，手机自动保存并在换网时切换。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = buildString {
                        append("已连接：")
                        append(host.ifBlank { remote.base })
                        if (remote.kind.isNotBlank()) append(" · ${remote.kind}")
                        if (remote.secure) append(" · HTTPS")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = when {
                        remote.busy -> "状态：同步中…"
                        remote.online -> "状态：在线"
                        else -> "状态：离线"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remote.online) colors.textMuted else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "当前地址：${remote.base.ifBlank { base }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                Text(
                    text = "上次同步：" + if (remote.lastSyncAt <= 0) {
                        "还没有成功同步过"
                    } else {
                        "${relativeTime(remote.lastSyncAt, tick)} · ${remote.tracks} 首"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                if (remote.fingerprint.isNotBlank()) {
                    val parts = remote.fingerprint.split(':')
                    val short = if (parts.size > 4) {
                        parts.take(3).joinToString(":") + "…" + parts.last()
                    } else {
                        remote.fingerprint
                    }
                    Text(
                        text = "证书指纹：$short（已固定）",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                } else if (remote.secure) {
                    Text(
                        text = "证书指纹：未固定",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                val addresses = remote.addresses.ifEmpty {
                    RemoteMusicLibrary.config(context)?.addresses.orEmpty()
                }
                if (addresses.size > 1) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "已保存地址（点一下切换）",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    addresses.forEach { address ->
                        val selected = address == remote.base
                        Text(
                            text = "· ${RemoteMusicLibrary.kindOf(address)} · $address",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else colors.textMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !selected && !busy) {
                                    busy = true
                                    RemoteMusicLibrary.useAddress(context, address)
                                    scope.launch {
                                        status = "正在切换到${RemoteMusicLibrary.kindOf(address)}地址…"
                                        val result = RemoteMusicLibrary.syncWithRetry(context, attempts = 2)
                                        status = result.fold(
                                            onSuccess = { "已切换并同步 $it 首" },
                                            onFailure = { "切换失败：${it.message}" },
                                        )
                                        busy = false
                                    }
                                }
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "正在同步…"
                    scope.launch {
                        val synced = RemoteMusicLibrary.syncWithRetry(context, attempts = 3)
                        status = synced.fold(
                            onSuccess = { "已同步 $it 首电脑曲目" },
                            onFailure = { "同步失败：${it.message}" },
                        )
                        busy = false
                    }
                },
            ) { Text("重新同步") }
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                enabled = !busy,
                onClick = {
                    RemoteMusicLibrary.disconnect(context)
                    base = ""
                    status = "已断开电脑音乐库"
                },
            ) { Text("断开") }
        }
        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
