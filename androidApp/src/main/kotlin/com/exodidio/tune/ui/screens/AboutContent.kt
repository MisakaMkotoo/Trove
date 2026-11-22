package com.exodidio.tune.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

@Composable
internal fun AboutContent(
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current

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
