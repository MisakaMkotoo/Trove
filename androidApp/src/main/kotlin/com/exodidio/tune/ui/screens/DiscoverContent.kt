package com.exodidio.tune.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.MaterialSymbols

/** 新发现占位页：底栏入口先加上，功能后续再补。 */
@Composable
internal fun DiscoverContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp),
    ) {
        HeroCard(
            symbol = MaterialSymbols.Sensors,
            title = stringResource(R.string.discover_placeholder_title),
            description = stringResource(R.string.discover_placeholder_description),
        )
    }
}
