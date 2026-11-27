package com.exodidio.tune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.lyrics.LyricsSettings
import com.exodidio.tune.lyrics.LyricsSourceId
import com.exodidio.tune.lyrics.SystemLyricMode
import com.exodidio.tune.lyrics.orderedSources
import com.exodidio.tune.netease.NeteaseWireConfig
import com.exodidio.tune.netease.NeteaseWireMode
import com.exodidio.tune.sync.RemoteMusicLibrary
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDivider
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.Selection
import com.exodidio.tune.ui.components.SelectionOption
import com.exodidio.tune.ui.components.TunePillButton
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.components.TuneTextField
import com.exodidio.tune.ui.theme.LocalTuneColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun IntegrationContent(
    onServiceStatusSelected: () -> Unit,
    onNeteasePlaylistsSelected: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val context = LocalContext.current
    var wireMode by remember { mutableStateOf(NeteaseWireConfig.mode) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabeledCard(label = stringResource(R.string.integration_section_netease)) {
            Selection(
                labelRes = R.string.netease_wire_title,
                options = listOf(
                    SelectionOption(NeteaseWireMode.EapiAndroid, R.string.netease_wire_eapi),
                    SelectionOption(NeteaseWireMode.Web, R.string.netease_wire_web),
                ),
                selectedValue = wireMode,
                onValueSelected = { value ->
                    wireMode = value
                    NeteaseWireConfig.setMode(context, value)
                },
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.settings_netease_playlists,
                        leadingSymbol = MaterialSymbols.MusicNote,
                        onClick = onNeteasePlaylistsSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
        LabeledCard(label = stringResource(R.string.integration_section_remote)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.settings_service_status,
                        leadingSymbol = MaterialSymbols.Sensors,
                        onClick = onServiceStatusSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
    }
}

/** 服务状态 + 电脑音乐库：状态总览与配对/同步放在同一页，不拆开。 */
@Composable
internal fun ServiceStatusContent(
    lyricsSettings: LyricsSettings,
    modifier: Modifier = Modifier,
) {
    val remote by RemoteMusicLibrary.status.collectAsState()
    val context = LocalContext.current
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            clock = System.currentTimeMillis()
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabeledCard(label = stringResource(R.string.settings_service_status)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoLine(
                    stringResource(R.string.integration_computer_library),
                    when {
                        !remote.configured -> stringResource(R.string.integration_remote_unconfigured)
                        remote.busy -> stringResource(R.string.integration_remote_syncing, remoteKindLabel(context, remote.kind))
                        remote.online -> stringResource(
                            R.string.integration_remote_online,
                            remoteKindLabel(context, remote.kind),
                            if (remote.secure) " · HTTPS" else "",
                        )
                        else -> stringResource(R.string.integration_remote_offline, remote.base)
                    },
                )
                if (remote.configured) {
                    InfoLine(
                        stringResource(R.string.integration_last_sync),
                        if (remote.lastSyncAt <= 0) stringResource(R.string.integration_never_synced)
                        else stringResource(R.string.integration_last_sync_value, relativeTime(remote.lastSyncAt, clock), remote.tracks),
                    )
                }
                InfoLine(
                    stringResource(R.string.integration_lyrics_sources),
                    LyricsSourceId.entries
                        .filter { it in lyricsSettings.enabledSources }
                        .map { stringResource(lyricsSourceLabel(it)) }
                        .joinToString(" · "),
                )
            }
        }
        RemoteLibraryCard()
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
            color = LocalTuneColors.current.textMain,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun remoteKindLabel(context: android.content.Context, kind: RemoteMusicLibrary.RemoteEndpointKind): String =
    context.getString(
        when (kind) {
            RemoteMusicLibrary.RemoteEndpointKind.Lan -> R.string.integration_kind_lan
            RemoteMusicLibrary.RemoteEndpointKind.Public -> R.string.integration_kind_public
            RemoteMusicLibrary.RemoteEndpointKind.Mesh -> R.string.integration_kind_mesh
            RemoteMusicLibrary.RemoteEndpointKind.Unknown -> R.string.common_unknown
        },
    )

@Composable
private fun relativeTime(timestamp: Long, now: Long): String {
    if (timestamp <= 0) return stringResource(R.string.integration_time_never)
    val seconds = ((now - timestamp) / 1000).coerceAtLeast(0)
    return when {
        seconds < 10 -> stringResource(R.string.integration_time_just_now)
        seconds < 60 -> stringResource(R.string.integration_time_seconds, seconds)
        seconds < 3600 -> stringResource(R.string.integration_time_minutes, seconds / 60)
        seconds < 86_400 -> stringResource(R.string.integration_time_hours, seconds / 3600)
        else -> stringResource(R.string.integration_time_days, seconds / 86_400)
    }
}

@Composable
internal fun LyricsContent(
    settings: LyricsSettings,
    onSourcesChanged: (List<LyricsSourceId>, Set<LyricsSourceId>) -> Unit,
    onWordFallbackChanged: (LyricsSourceId?) -> Unit,
    onSystemLyricModeChanged: (SystemLyricMode) -> Unit,
    onStandaloneLyricNotificationChanged: (Boolean) -> Unit,
    onHyperosSuperIslandChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onAutoAlignChanged: (Boolean) -> Unit = {},
    onPerSongLyricsConfigChanged: (Boolean) -> Unit = {},
    onAutoSyncModelsSelected: () -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val order = remember {
        mutableStateListOf<LyricsSourceId>().apply { addAll(orderedSources(settings.sourceOrder)) }
    }
    var enabled by remember { mutableStateOf(settings.enabledSources) }
    LaunchedEffect(settings.sourceOrder, settings.enabledSources) {
        val merged = orderedSources(settings.sourceOrder)
        order.clear()
        order.addAll(merged)
        enabled = settings.enabledSources
    }

    fun commit() = onSourcesChanged(order.toList(), enabled)

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target in order.indices) {
            val item = order.removeAt(index)
            order.add(target, item)
            commit()
        }
    }

    fun toggle(source: LyricsSourceId, checked: Boolean) {
        enabled = if (checked) enabled + source else enabled - source
        commit()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LabeledCard(label = stringResource(R.string.lyrics_sources_order_description)) {
            ActionList(
                items = order.withIndex().map { (index, source) ->
                    ActionListItem(
                        labelRes = lyricsSourceLabel(source),
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                TuneSwitch(
                                    checked = source in enabled,
                                    onCheckedChange = { toggle(source, it) },
                                )
                                IconButton(onClick = { move(index, -1) }, enabled = index > 0) {
                                    MaterialSymbol(
                                        symbol = MaterialSymbols.ArrowUpward,
                                        size = 20.dp,
                                        tint = colors.textMuted,
                                    )
                                }
                                IconButton(onClick = { move(index, +1) }, enabled = index < order.lastIndex) {
                                    MaterialSymbol(
                                        symbol = MaterialSymbols.ArrowDownward,
                                        size = 20.dp,
                                        tint = colors.textMuted,
                                    )
                                }
                            }
                        },
                        onClick = { toggle(source, source !in enabled) },
                    )
                },
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.lyrics_word_fallback_title,
                options = listOf(
                    SelectionOption<LyricsSourceId?>(null, R.string.lyrics_word_fallback_none),
                ) + LyricsSourceId.entries.map { source ->
                    SelectionOption<LyricsSourceId?>(source, lyricsSourceLabel(source))
                },
                selectedValue = settings.wordFallbackSource,
                onValueSelected = onWordFallbackChanged,
                hazeState = hazeState,
            )
        }
        LabeledCard(label = stringResource(R.string.lyrics_system_section)) {
            Selection(
                labelRes = R.string.lyrics_notification_lyrics,
                options = SystemLyricMode.entries.map { mode ->
                    SelectionOption(mode, systemLyricModeLabel(mode))
                },
                selectedValue = settings.systemLyricMode,
                onValueSelected = onSystemLyricModeChanged,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.lyrics_standalone_notification,
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.standaloneLyricNotification,
                                onCheckedChange = onStandaloneLyricNotificationChanged,
                            )
                        },
                        onClick = { onStandaloneLyricNotificationChanged(!settings.standaloneLyricNotification) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_hyperos_super_island,
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.hyperosSuperIsland,
                                onCheckedChange = onHyperosSuperIslandChanged,
                            )
                        },
                        onClick = { onHyperosSuperIslandChanged(!settings.hyperosSuperIsland) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }
        LabeledCard(label = stringResource(R.string.lyrics_auto_align_section)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.lyrics_auto_align,
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.autoAlignWordTiming,
                                onCheckedChange = onAutoAlignChanged,
                            )
                        },
                        onClick = { onAutoAlignChanged(!settings.autoAlignWordTiming) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_per_song_config,
                        subtitle = stringResource(R.string.lyrics_per_song_config_desc),
                        trailingContent = {
                            TuneSwitch(
                                checked = settings.perSongLyricsConfigEnabled,
                                onCheckedChange = onPerSongLyricsConfigChanged,
                            )
                        },
                        onClick = { onPerSongLyricsConfigChanged(!settings.perSongLyricsConfigEnabled) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_auto_align_models,
                        onClick = onAutoSyncModelsSelected,
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }
        Text(
            text = stringResource(R.string.lyrics_auto_align_notice),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

private fun systemLyricModeLabel(mode: SystemLyricMode): Int = when (mode) {
    SystemLyricMode.Off -> R.string.lyrics_notification_lyrics_off
    SystemLyricMode.LyricFirst -> R.string.lyrics_notification_lyrics_lyric_first
    SystemLyricMode.SongFirst -> R.string.lyrics_notification_lyrics_song_first
}

private fun lyricsSourceLabel(source: LyricsSourceId): Int = when (source) {
    LyricsSourceId.Netease -> R.string.lyrics_netease
    LyricsSourceId.Amll -> R.string.lyrics_amll
    LyricsSourceId.Lrclib -> R.string.lyrics_lrclib
    LyricsSourceId.Kugou -> R.string.lyrics_kugou
}

/** 电脑音乐库：地址 + 配对码；连上后显示链路、地址、同步时间和证书指纹。 */
@Composable
private fun RemoteLibraryCard() {
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val remote by RemoteMusicLibrary.status.collectAsState()
    var base by remember { mutableStateOf(RemoteMusicLibrary.config(context)?.base.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connectingText = stringResource(R.string.integration_connecting)
    val connectedSyncingText = stringResource(R.string.integration_connected_syncing)
    val syncingText = stringResource(R.string.integration_syncing)
    val disconnectedText = stringResource(R.string.integration_disconnected)
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tick = System.currentTimeMillis()
        }
    }
    val colors = LocalTuneColors.current
    val configured = remote.configured || RemoteMusicLibrary.isConfigured(context)
    val host = remote.host.ifBlank { RemoteMusicLibrary.config(context)?.host.orEmpty() }

    LabeledCard(label = stringResource(R.string.integration_computer_library)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!configured) {
                TuneTextField(
                    value = base,
                    onValueChange = { base = it },
                    placeholder = stringResource(R.string.integration_host_hint),
                    keyboardType = KeyboardType.Uri,
                )
                TuneTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                    placeholder = stringResource(R.string.integration_pair_code),
                    keyboardType = KeyboardType.Number,
                )
                TunePillButton(
                    label = stringResource(R.string.integration_connect),
                    variant = TunePillButtonVariant.Primary,
                    enabled = !busy && base.isNotBlank() && code.length == 6,
                    onClick = {
                        busy = true
                        status = connectingText
                        scope.launch {
                            val paired = RemoteMusicLibrary.pair(context, base, code)
                            if (paired.isSuccess) {
                                status = connectedSyncingText
                                val synced = RemoteMusicLibrary.syncWithRetry(context, attempts = 2)
                                status = synced.fold(
                                    onSuccess = { resources.getString(R.string.integration_synced_count, it) },
                                    onFailure = { resources.getString(R.string.integration_connected_sync_failed, it.message.orEmpty()) },
                                )
                            } else {
                                status = resources.getString(
                                    R.string.integration_connect_failed,
                                    paired.exceptionOrNull()?.message.orEmpty(),
                                )
                            }
                            busy = false
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.integration_address_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = buildString {
                            append(stringResource(R.string.integration_connected_prefix))
                            append(" ")
                            append(host.ifBlank { remote.base })
                            if (remote.kind != RemoteMusicLibrary.RemoteEndpointKind.Unknown) {
                                append(" · ")
                                append(remoteKindLabel(context, remote.kind))
                            }
                            if (remote.secure) append(" · HTTPS")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.textMain,
                    )
                    Text(
                        text = when {
                            remote.busy -> stringResource(R.string.integration_status_syncing)
                            remote.online -> stringResource(R.string.integration_status_online)
                            else -> stringResource(R.string.integration_status_offline)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remote.online) colors.textMuted else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.integration_current_address, remote.base.ifBlank { base }),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    Text(
                        text = stringResource(R.string.integration_last_sync_prefix) + " " + if (remote.lastSyncAt <= 0) {
                            stringResource(R.string.integration_never_synced)
                        } else {
                            stringResource(R.string.integration_last_sync_count, relativeTime(remote.lastSyncAt, tick), remote.tracks)
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
                            text = stringResource(R.string.integration_cert_pinned, short),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    } else if (remote.secure) {
                        Text(
                            text = stringResource(R.string.integration_cert_unpinned),
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
                            text = stringResource(R.string.integration_saved_addresses),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                        addresses.forEach { address ->
                            val selected = address == remote.base
                            val kind = RemoteMusicLibrary.kindOf(address)
                            Text(
                                text = "· ${remoteKindLabel(context, kind)} · $address",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colors.primary else colors.textMuted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !selected && !busy) {
                                        busy = true
                                        RemoteMusicLibrary.useAddress(context, address)
                                        scope.launch {
                                            status = resources.getString(R.string.integration_switching_to, remoteKindLabel(context, kind))
                                            val result = RemoteMusicLibrary.syncWithRetry(context, attempts = 2)
                                            status = result.fold(
                                                onSuccess = { resources.getString(R.string.integration_switched_synced, it) },
                                                onFailure = { resources.getString(R.string.integration_switch_failed, it.message.orEmpty()) },
                                            )
                                            busy = false
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }
                }
                TunePillButton(
                    label = stringResource(R.string.integration_resync),
                    variant = TunePillButtonVariant.Primary,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        status = syncingText
                        scope.launch {
                            val synced = RemoteMusicLibrary.syncWithRetry(context, attempts = 3)
                            status = synced.fold(
                                onSuccess = { resources.getString(R.string.integration_synced_count, it) },
                                onFailure = { resources.getString(R.string.integration_sync_failed, it.message.orEmpty()) },
                            )
                            busy = false
                        }
                    },
                )
                TunePillButton(
                    label = stringResource(R.string.integration_disconnect),
                    variant = TunePillButtonVariant.Secondary,
                    enabled = !busy,
                    onClick = {
                        RemoteMusicLibrary.disconnect(context)
                        base = ""
                        status = disconnectedText
                    },
                )
            }
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}
