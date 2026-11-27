package com.exodidio.tune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.exodidio.tune.ui.components.LabeledCard
import com.exodidio.tune.ui.components.LocalReduceMotion
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneBottomSheet
import com.exodidio.tune.ui.components.TuneDialog
import com.exodidio.tune.ui.components.TunePillButton
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.TuneRemoteImage
import com.exodidio.tune.ui.components.TuneSegmentedTabBar
import com.exodidio.tune.ui.components.TuneSwitch
import com.exodidio.tune.ui.components.TuneTextField
import com.exodidio.tune.ui.components.pressScale
import com.exodidio.tune.ui.theme.LocalTuneColors
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 独立账户页：负责登录入口、账号信息展示与账号同步偏好。 */
@Composable
internal fun AccountContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val loggedIn by NeteaseRepository.loggedIn.collectAsStateWithLifecycle()
    val info by NeteaseRepository.accountInfo.collectAsStateWithLifecycle()
    var webLoginMode by remember { mutableStateOf<NeteaseWebLoginMode?>(null) }
    var nativeLoginVisible by remember { mutableStateOf(false) }
    var confirmLogoutVisible by remember { mutableStateOf(false) }

    LaunchedEffect(loggedIn) {
        if (loggedIn) NeteaseRepository.loadAccountInfo()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (loggedIn) {
            val historyReportEnabled by NeteaseRepository.historyReportEnabled.collectAsStateWithLifecycle()
            AccountProfileCard(info = info)
            LabeledCard(label = stringResource(R.string.account_section_info)) {
                ActionList(
                    items = listOf(
                        ActionListItem(
                            labelRes = R.string.account_uid,
                            trailingContent = {
                                AccountTrailingValue(info?.uid.orEmpty().ifBlank { "—" })
                            },
                        ),
                        ActionListItem(
                            labelRes = R.string.account_login_name,
                            trailingContent = {
                                AccountTrailingValue(info?.loginName.orEmpty().ifBlank { "—" })
                            },
                        ),
                        ActionListItem(
                            labelRes = R.string.account_joined,
                            trailingContent = {
                                AccountTrailingValue(formatJoinedAt(info?.createdAtMillis ?: 0L))
                            },
                        ),
                    ),
                    containerStyle = ActionListContainerStyle.Plain,
                    dividerStyle = ActionListDividerStyle.FullWidth,
                )
            }
            LabeledCard(label = stringResource(R.string.account_section_preferences)) {
                ActionList(
                    items = listOf(
                        ActionListItem(
                            labelRes = R.string.netease_history_report_title,
                            subtitle = stringResource(R.string.netease_history_report_subtitle),
                            trailingContent = {
                                TuneSwitch(
                                    checked = historyReportEnabled,
                                    onCheckedChange = { NeteaseRepository.setHistoryReportEnabled(context, it) },
                                )
                            },
                            onClick = { NeteaseRepository.setHistoryReportEnabled(context, !historyReportEnabled) },
                        ),
                    ),
                    containerStyle = ActionListContainerStyle.Plain,
                )
            }
            TunePillButton(
                label = stringResource(R.string.netease_logout),
                variant = TunePillButtonVariant.Secondary,
                onClick = { confirmLogoutVisible = true },
            )
        } else {
            HeroCard(
                symbol = MaterialSymbols.Person,
                title = stringResource(R.string.account_login_title),
                description = stringResource(R.string.account_login_desc),
            )
            LabeledCard(label = stringResource(R.string.account_login_methods_title)) {
                ActionList(
                    items = listOf(
                        ActionListItem(
                            labelRes = R.string.netease_login_phone,
                            leadingSymbol = MaterialSymbols.Person,
                            leadingIconTint = colors.primary,
                            subtitle = stringResource(R.string.netease_login_phone_desc),
                            onClick = { nativeLoginVisible = true },
                        ),
                        ActionListItem(
                            labelRes = R.string.netease_login_qr,
                            leadingIconRes = R.drawable.ic_account_qr,
                            leadingIconTint = colors.textMain,
                            subtitle = stringResource(R.string.netease_login_qr_desc),
                            // 网易对第三方扫码接口做了风控；改用电脑端网页自带的扫码登录。
                            onClick = { webLoginMode = NeteaseWebLoginMode.Desktop },
                        ),
                        ActionListItem(
                            labelRes = R.string.netease_login_web,
                            leadingIconRes = R.drawable.ic_account_web,
                            leadingIconTint = colors.textMuted,
                            subtitle = stringResource(R.string.netease_login_web_desc),
                            onClick = { webLoginMode = NeteaseWebLoginMode.Mobile },
                        ),
                    ),
                    containerStyle = ActionListContainerStyle.Plain,
                    dividerStyle = ActionListDividerStyle.InsetForLeadingIcon,
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
    if (nativeLoginVisible) {
        NeteaseNativeLoginSheet(onDismiss = { nativeLoginVisible = false })
    }
    if (confirmLogoutVisible) {
        TuneDialog(
            title = stringResource(R.string.netease_logout_confirm_title),
            description = stringResource(R.string.netease_logout_confirm_desc),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { confirmLogoutVisible = false },
            confirmLabel = stringResource(R.string.netease_logout),
            confirmVariant = TunePillButtonVariant.Destructive,
            onConfirm = {
                confirmLogoutVisible = false
                NeteaseRepository.clearLogin(context)
            },
        )
    }
}

@Composable
private fun AccountProfileCard(info: NeteaseClient.AccountInfo?) {
    val colors = LocalTuneColors.current
    val accountType = accountTypeText(info)
    Card(contentPadding = PaddingValues(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TuneRemoteImage(
                url = info?.avatarUrl,
                sizePx = 240,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.glassElevated)
                    .border(0.5.dp, colors.borderGlass, CircleShape),
                fallback = {
                    MaterialSymbol(
                        symbol = MaterialSymbols.Person,
                        contentDescription = null,
                        size = 32.dp,
                        tint = colors.textMuted,
                    )
                },
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = info?.nickname.orEmpty().ifBlank { "—" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (accountType != "—") {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.buttonSecondary)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = accountType,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
                val signature = info?.signature.orEmpty()
                if (signature.isNotBlank()) {
                    Text(
                        text = signature,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountTrailingValue(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalTuneColors.current.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 原生手机号密码 / 验证码登录；统一采用胶囊分段、TuneTextField 与 TunePillButton。 */
@Composable
private fun NeteaseNativeLoginSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    var smsMode by remember { mutableStateOf(false) }
    var countryCode by remember { mutableStateOf("86") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var captcha by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }
    var sendingCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val failedLabel = stringResource(R.string.netease_login_failed)
    val smsFailedLabel = stringResource(R.string.netease_login_sms_failed)

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown -= 1
        }
    }

    val normalizedPhone = phone.trim()
    val normalizedCountry = countryCode.trim().ifBlank { "86" }
    val phoneValid = normalizedPhone.length >= 6 && normalizedCountry.all(Char::isDigit)
    val canSubmit = phoneValid &&
        (if (smsMode) captcha.trim().length >= 4 else password.isNotBlank()) &&
        !busy

    fun submitLogin() {
        if (!canSubmit) return
        busy = true
        status = ""
        scope.launch {
            val result = if (smsMode) {
                NeteaseRepository.loginBySmsCode(context, normalizedPhone, normalizedCountry, captcha.trim())
            } else {
                NeteaseRepository.loginByCellphone(context, normalizedPhone, password)
            }
            busy = false
            result.fold(
                onSuccess = { onDismiss() },
                onFailure = { error ->
                    status = error.message?.takeIf(String::isNotBlank) ?: failedLabel
                },
            )
        }
    }

    TuneBottomSheet(
        title = {
            Text(
                text = stringResource(R.string.netease_login_phone),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LoginMethodSegmentedTabs(
                smsMode = smsMode,
                onSelectMode = { nextSms ->
                    smsMode = nextSms
                    status = ""
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TuneTextField(
                    value = countryCode,
                    onValueChange = { input -> countryCode = input.filter(Char::isDigit).take(4) },
                    placeholder = stringResource(R.string.netease_login_country_code),
                    modifier = Modifier.width(84.dp),
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                    showClearButton = false,
                )
                TuneTextField(
                    value = phone,
                    onValueChange = { input -> phone = input.filter(Char::isDigit).take(15) },
                    placeholder = stringResource(R.string.netease_login_phone_hint),
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                )
            }
            if (smsMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TuneTextField(
                        value = captcha,
                        onValueChange = { input -> captcha = input.filter(Char::isDigit).take(8) },
                        placeholder = stringResource(R.string.netease_login_code_hint),
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                        onDone = ::submitLogin,
                    )
                    val codeEnabled = phoneValid && countdown == 0 && !sendingCode && !busy
                    val codeInteraction = remember { MutableInteractionSource() }
                    val codeBg by animateColorAsState(
                        targetValue = if (codeEnabled) colors.primary.copy(alpha = 0.14f) else colors.buttonSecondary,
                        animationSpec = if (reduceMotion) snap() else tween(180, easing = FastOutSlowInEasing),
                        label = "send-code-bg",
                    )
                    val codeText by animateColorAsState(
                        targetValue = if (codeEnabled) colors.primary else colors.textMuted,
                        animationSpec = if (reduceMotion) snap() else tween(180, easing = FastOutSlowInEasing),
                        label = "send-code-text",
                    )
                    Box(
                        modifier = Modifier
                            .then(if (codeEnabled) Modifier.pressScale(codeInteraction, pressedScale = 0.96f) else Modifier)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(codeBg)
                            .border(
                                0.5.dp,
                                if (codeEnabled) colors.primary.copy(alpha = 0.35f) else colors.borderGlass,
                                RoundedCornerShape(24.dp),
                            )
                            .clickable(
                                enabled = codeEnabled,
                                role = Role.Button,
                                interactionSource = codeInteraction,
                                indication = null,
                            ) {
                                if (sendingCode || countdown > 0) return@clickable
                                sendingCode = true
                                status = ""
                                scope.launch {
                                    val result = NeteaseRepository.sendSmsCode(normalizedPhone, normalizedCountry)
                                    sendingCode = false
                                    result.fold(
                                        onSuccess = { countdown = 60 },
                                        onFailure = { error ->
                                            status = error.message?.takeIf(String::isNotBlank) ?: smsFailedLabel
                                        },
                                    )
                                }
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (countdown > 0) {
                                stringResource(R.string.netease_login_resend, countdown)
                            } else {
                                stringResource(R.string.netease_login_send_code)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = codeText,
                        )
                    }
                }
            } else {
                TuneTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.netease_login_password_hint),
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    onDone = ::submitLogin,
                    trailingContent = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                painter = painterResource(
                                    if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                ),
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.netease_login_hide_password else R.string.netease_login_show_password,
                                ),
                                modifier = Modifier.size(20.dp),
                                tint = colors.textMuted,
                            )
                        }
                    },
                )
            }
            AnimatedVisibility(
                visible = status.isNotBlank(),
                enter = if (reduceMotion) fadeIn(snap()) else fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = if (reduceMotion) fadeOut(snap()) else fadeOut(tween(140)) + shrinkVertically(tween(180)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TunePillButton(
                label = stringResource(
                    if (busy) R.string.netease_login_working else R.string.netease_login_action,
                ),
                variant = TunePillButtonVariant.Primary,
                enabled = canSubmit,
                onClick = ::submitLogin,
            )
        }
    }
}

@Composable
private fun LoginMethodSegmentedTabs(
    smsMode: Boolean,
    onSelectMode: (Boolean) -> Unit,
) {
    TuneSegmentedTabBar(
        tabs = listOf(
            stringResource(R.string.netease_login_method_password),
            stringResource(R.string.netease_login_method_sms),
        ),
        selectedIndex = if (smsMode) 1 else 0,
        onSelect = { index -> onSelectMode(index == 1) },
    )
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
