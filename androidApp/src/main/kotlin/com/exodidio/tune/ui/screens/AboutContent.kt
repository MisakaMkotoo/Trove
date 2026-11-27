package com.exodidio.tune.ui.screens

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.BuildConfig
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.ActionList
import com.exodidio.tune.ui.components.ActionListContainerStyle
import com.exodidio.tune.ui.components.ActionListDividerStyle
import com.exodidio.tune.ui.components.ActionListItem
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.theme.LocalTuneColors

private const val TuneGithubUrl = "https://github.com/MisakaMkotoo/Trove"
private const val TuneLicenseUrl = "https://github.com/MisakaMkotoo/Trove/blob/master/LICENSE"
private const val TerminalEasterEggTapCount = 5
private const val TerminalEasterEggWindowMs = 2_500L

@Composable
internal fun AboutContent(
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEnterTerminalMode: () -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val countdownOneText = stringResource(R.string.about_terminal_countdown, 1)
    val countdownTwoText = stringResource(R.string.about_terminal_countdown, 2)
    val unlockedText = stringResource(R.string.about_terminal_unlocked)
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var activeToast by remember { mutableStateOf<Toast?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(
            title = stringResource(R.string.app_name),
            description = stringResource(R.string.about_description),
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_app),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        }
        Text(
            text = stringResource(R.string.about_section_app),
            style = MaterialTheme.typography.titleSmall,
            color = colors.textMain,
            modifier = Modifier.padding(top = 4.dp),
        )
        ActionList(
            items = listOf(
                ActionListItem(
                    labelRes = R.string.about_version,
                    trailingContent = {
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textMuted,
                        )
                    },
                    onClick = {
                        val now = SystemClock.elapsedRealtime()
                        tapCount = if (now - lastTapMs > TerminalEasterEggWindowMs) 1 else tapCount + 1
                        lastTapMs = now
                        when {
                            tapCount == 3 -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                activeToast?.cancel()
                                activeToast = Toast.makeText(
                                    context,
                                    countdownTwoText,
                                    Toast.LENGTH_SHORT,
                                ).also { it.show() }
                            }
                            tapCount == 4 -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                activeToast?.cancel()
                                activeToast = Toast.makeText(
                                    context,
                                    countdownOneText,
                                    Toast.LENGTH_SHORT,
                                ).also { it.show() }
                            }
                            tapCount >= TerminalEasterEggTapCount -> {
                                tapCount = 0
                                lastTapMs = 0L
                                activeToast?.cancel()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                activeToast = Toast.makeText(
                                    context,
                                    unlockedText,
                                    Toast.LENGTH_SHORT,
                                ).also { it.show() }
                                onEnterTerminalMode()
                            }
                        }
                    },
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
            dividerStyle = ActionListDividerStyle.FullWidth,
        )
        Text(
            text = stringResource(R.string.about_section_project),
            style = MaterialTheme.typography.titleSmall,
            color = colors.textMain,
            modifier = Modifier.padding(top = 4.dp),
        )
        ActionList(
            items = listOf(
                ActionListItem(
                    labelRes = R.string.about_github,
                    onClick = { onOpenExternalUrl(TuneGithubUrl) },
                ),
                ActionListItem(
                    labelRes = R.string.about_star,
                    leadingSymbol = MaterialSymbols.Star,
                    onClick = { onOpenExternalUrl(TuneGithubUrl) },
                ),
                ActionListItem(
                    labelRes = R.string.about_license,
                    onClick = { onOpenExternalUrl(TuneLicenseUrl) },
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
            dividerStyle = ActionListDividerStyle.FullWidth,
        )
    }
}
