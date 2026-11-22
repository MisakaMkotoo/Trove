package com.exodidio.tune.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.exodidio.tune.netease.NeteaseClient
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.Card
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 独立账户页：只负责登录状态与账号信息，歌单管理仍在设置里。 */
@Composable
internal fun AccountContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val info by NeteaseRepository.accountInfo.collectAsStateWithLifecycle()
    var webLoginMode by remember { mutableStateOf<NeteaseWebLoginMode?>(null) }

    LaunchedEffect(loggedIn) {
        if (loggedIn) NeteaseRepository.loadAccountInfo()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loggedIn) {
            Card {
                AccountInfoRow(R.string.account_nickname, info?.nickname.orEmpty().ifBlank { "—" })
                AccountInfoRow(R.string.account_uid, info?.uid.orEmpty().ifBlank { "—" })
                AccountInfoRow(R.string.account_login_name, info?.loginName.orEmpty().ifBlank { "—" })
                AccountInfoRow(R.string.account_type, accountTypeText(info))
                AccountInfoRow(R.string.account_joined, formatJoinedAt(info?.createdAtMillis ?: 0L))
            }
            Card {
                ActionList(
                    items = listOf(
                        ActionListItem(
                            labelRes = R.string.netease_logout,
                            leadingSymbol = MaterialSymbols.Power,
                            onClick = { NeteaseRepository.clearLogin(context) },
                        ),
                    ),
                    containerStyle = ActionListContainerStyle.Card,
                    dividerStyle = ActionListDividerStyle.FullWidth,
                )
            }
        } else {
            HeroCard(
                symbol = MaterialSymbols.Person,
                title = stringResource(R.string.account_login_title),
                description = stringResource(R.string.account_login_desc),
            )
            Card {
                ActionList(
                    items = listOf(
                        ActionListItem(
                            labelRes = R.string.netease_login_qr,
                            leadingSymbol = MaterialSymbols.Person,
                            // 网易对第三方扫码接口做了风控；改用电脑端网页自带的扫码登录。
                            onClick = { webLoginMode = NeteaseWebLoginMode.Desktop },
                        ),
                        ActionListItem(
                            labelRes = R.string.netease_login_web,
                            leadingSymbol = MaterialSymbols.Translate,
                            onClick = { webLoginMode = NeteaseWebLoginMode.Mobile },
                        ),
                    ),
                    containerStyle = ActionListContainerStyle.Plain,
                )
            }
        }
    }

    webLoginMode?.let { mode ->
        NeteaseWebLoginDialog(
            mode = mode,
            onDismiss = { webLoginMode = null },
            onLoggedIn = { cookie ->
                NeteaseRepository.saveLogin(context, cookie)
                webLoginMode = null
            },
        )
    }
}

@Composable
private fun AccountInfoRow(label: Int, value: String) {
    val colors = LocalTuneColors.current
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMain,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun accountTypeText(info: NeteaseClient.AccountInfo?): String {
    val loginName = info?.loginName.orEmpty()
    return when {
        loginName.isBlank() -> "—"
        loginName.contains("@") -> stringResource(R.string.account_type_email)
        else -> stringResource(R.string.account_type_phone)
    }
}

private fun formatJoinedAt(millis: Long): String =
    if (millis <= 0L) "—" else {
        runCatching {
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        }.getOrDefault("—")
    }

@Composable
private fun QrLoginSheet(
    key: String,
    url: String,
    cookie: String?,
    context: Context,
    initialStatus: String,
    onDone: (Boolean) -> Unit,
    onRequestNewQr: () -> Unit,
) {
    val colors = LocalTuneColors.current
    var status by remember { mutableStateOf(initialStatus) }
    var verifyUrl by remember { mutableStateOf<String?>(null) }
    var showVerify by remember { mutableStateOf(false) }
    val bitmap = remember(url) { runCatching { qrBitmap(url, 512) }.getOrNull() }
    LaunchedEffect(key) {
        while (true) {
            delay(1_500)
            val result = NeteaseRepository.pollQrLogin(key, cookie)
            val polled = result.getOrElse { error ->
                status = context.getString(
                    R.string.account_status_query_failed,
                    error.message ?: context.getString(R.string.account_network_error),
                )
                continue
            }
            when (polled.code) {
                -462, -460, 8810, 8821 -> {
                    // 网易对第三方登录接口的风控：有验证页就弹出来，验证后再重新出码。
                    status = context.getString(
                        R.string.account_qr_risk,
                        polled.message.ifBlank { polled.code.toString() },
                    )
                    verifyUrl = polled.verifyUrl
                    return@LaunchedEffect
                }
                800 -> {
                    status = context.getString(R.string.account_qr_expired)
                    return@LaunchedEffect
                }
                801 -> status = context.getString(R.string.account_qr_waiting)
                802 -> status = context.getString(R.string.account_qr_scanned)
                803 -> {
                    val session = polled.cookie?.takeIf { it.contains("MUSIC_U=") }
                    if (session != null) {
                        NeteaseRepository.saveLogin(context, session)
                        status = context.getString(R.string.account_login_success)
                        delay(800)
                        onDone(true)
                    } else {
                        status = context.getString(R.string.account_qr_missing_cookie)
                        onDone(false)
                    }
                    return@LaunchedEffect
                }
                else -> status = context.getString(
                    R.string.account_qr_waiting_code,
                    polled.code,
                    polled.message.takeIf { it.isNotBlank() }
                        ?.let { context.getString(R.string.account_qr_status_suffix, it) }
                        .orEmpty(),
                )
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
                text = status.ifBlank { stringResource(R.string.account_qr_scan_hint) },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            if (verifyUrl != null) {
                Button(onClick = { showVerify = true }) {
                    Text(stringResource(R.string.account_qr_verify))
                }
            }
        }
    }
    verifyUrl?.let { target ->
        if (showVerify) {
            NeteaseVerifyDialog(
                url = target,
                onDismiss = { showVerify = false },
                onCompleted = {
                    showVerify = false
                    onRequestNewQr()
                },
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
