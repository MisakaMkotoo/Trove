package com.exodidio.tune.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R

/** Full-width text filter displayed at the leading edge of a library collection. */
@Composable
fun LibraryTextFilter(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    showPlaceholderAndLeadingSymbol: Boolean = true,
    /** 非空时在搜索框右侧显示在线搜索开关。 */
    onlineMode: Boolean? = null,
    onOnlineModeChange: (Boolean) -> Unit = {},
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var inputValue by rememberSaveable { mutableStateOf(value) }
    // 外部改写关键词（点歌手/专辑回填、清空）时同步输入框。
    androidx.compose.runtime.LaunchedEffect(value) {
        if (value != inputValue) inputValue = value
    }
    val onlineLabel = stringResource(R.string.library_search_online_toggle)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneTextField(
            value = inputValue,
            onValueChange = { newValue ->
                inputValue = newValue
                onValueChange(newValue)
            },
            placeholder = placeholder,
            size = TuneTextFieldSize.Medium,
            leadingSymbol = MaterialSymbols.Search,
            showPlaceholderAndLeadingSymbol = showPlaceholderAndLeadingSymbol,
            onDone = onSubmit,
            modifier = Modifier.weight(1f),
        )
        if (onlineMode != null) {
            Spacer(Modifier.width(8.dp))
            TuneSwitch(
                checked = onlineMode,
                onCheckedChange = onOnlineModeChange,
                modifier = Modifier.semantics { contentDescription = onlineLabel },
            )
        }
    }
}
