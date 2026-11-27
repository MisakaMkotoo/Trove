package com.exodidio.tune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.netease.NeteaseClient
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.theme.LocalTuneColors
import kotlinx.coroutines.launch

@Composable
internal fun SongCommentsSheetContent(
    track: LibraryTrack,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTuneColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember(track.id) { mutableIntStateOf(0) }
    var loading by remember(track.id) { mutableStateOf(true) }
    var loadingMore by remember(track.id) { mutableStateOf(false) }
    var errorMessage by remember(track.id) { mutableStateOf<String?>(null) }
    var hotComments by remember(track.id) { mutableStateOf<List<NeteaseClient.NeteaseSongComment>>(emptyList()) }
    var latestComments by remember(track.id) { mutableStateOf<List<NeteaseClient.NeteaseSongComment>>(emptyList()) }
    var totalCount by remember(track.id) { mutableIntStateOf(0) }
    var hasMore by remember(track.id) { mutableStateOf(false) }
    var resolvedSongId by remember(track.id) { mutableStateOf<Long?>(null) }
    var matchedTitle by remember(track.id) { mutableStateOf<String?>(null) }
    var matchedBySearch by remember(track.id) { mutableStateOf(false) }
    var retryNonce by remember(track.id) { mutableIntStateOf(0) }

    LaunchedEffect(track.id, retryNonce) {
        loading = true
        errorMessage = null
        NeteaseRepository.fetchTrackComments(
            context = context,
            track = track,
            limit = 20,
            offset = 0,
        ).onSuccess { result ->
            resolvedSongId = result.songId
            matchedTitle = result.matchedTitle
            matchedBySearch = result.matchedBySearch
            hotComments = result.page.hotComments
            latestComments = result.page.comments
            totalCount = result.page.totalCount
            hasMore = result.page.hasMore
            if (hotComments.isEmpty() && latestComments.isNotEmpty()) {
                selectedTab = 1
            }
            loading = false
        }.onFailure { err ->
            errorMessage = err.message ?: context.getString(R.string.account_network_error)
            loading = false
        }
    }

    val loadMore: () -> Unit = {
        val sid = resolvedSongId
        if (sid != null && !loadingMore && hasMore) {
            loadingMore = true
            val beforeTime = latestComments.lastOrNull()?.timeMs ?: 0L
            scope.launch {
                NeteaseRepository.fetchTrackComments(
                    context = context,
                    track = track,
                    limit = 20,
                    offset = latestComments.size,
                    beforeTime = beforeTime,
                    knownSongId = sid,
                    knownMatchedTitle = matchedTitle,
                ).onSuccess { result ->
                    val existing = latestComments.mapTo(HashSet()) { it.commentId }
                    latestComments = latestComments + result.page.comments.filter { it.commentId !in existing }
                    hasMore = result.page.hasMore
                }
                loadingMore = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = listOf(track.title, track.artists).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (matchedBySearch && !matchedTitle.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.comments_matched_song, matchedTitle!!),
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Tabs + total count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommentTabPill(
                    label = stringResource(R.string.comments_tab_hot),
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                )
                CommentTabPill(
                    label = stringResource(R.string.comments_tab_latest),
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                )
            }
            if (totalCount > 0) {
                Text(
                    text = stringResource(R.string.comments_total_count, totalCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.4.dp)
                }
            }
            errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                    TunePillButton(
                        label = stringResource(R.string.comments_retry),
                        onClick = { retryNonce += 1 },
                        variant = TunePillButtonVariant.Secondary,
                    )
                }
            }
            else -> {
                val activeList = if (selectedTab == 0) hotComments else latestComments
                if (activeList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (selectedTab == 0) R.string.comments_empty_hot else R.string.comments_empty_latest,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(activeList, key = { "${selectedTab}_${it.commentId}" }) { comment ->
                            CommentRowCard(comment = comment)
                        }
                        if (selectedTab == 1 && hasMore) {
                            item("comments_load_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (loadingMore) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        TunePillButton(
                                            label = stringResource(R.string.comments_load_more),
                                            onClick = loadMore,
                                            variant = TunePillButtonVariant.Secondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CommentTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) colors.primary else colors.glassElevated)
            .border(
                width = 0.5.dp,
                color = if (selected) colors.primary else colors.borderGlass,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (selected) colors.onPrimary else colors.textMain,
        )
    }
}

@Composable
private fun CommentRowCard(comment: NeteaseClient.NeteaseSongComment) {
    val colors = LocalTuneColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.glassElevated)
            .border(0.5.dp, colors.borderGlass, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TuneRemoteImage(
                url = comment.avatarUrl.takeIf { it.isNotBlank() },
                sizePx = 96,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.card),
                fallback = {
                    Box(
                        modifier = Modifier.size(34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.Person,
                            size = 18.dp,
                            tint = colors.textMuted,
                        )
                    }
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = comment.userName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOf(comment.timeStr, comment.ipLocation)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (comment.likedCount > 0) {
                    Text(
                        text = formatLikeCount(comment.likedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
                MaterialSymbol(
                    symbol = MaterialSymbols.FavoriteBorder,
                    size = 15.dp,
                    tint = colors.textMuted,
                )
            }
        }
        Text(
            text = comment.content,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMain,
        )
        if (!comment.repliedContent.isNullOrBlank()) {
            HorizontalDivider(color = colors.borderGlass.copy(alpha = 0.5f))
            Text(
                text = stringResource(
                    R.string.comments_reply_prefix,
                    comment.repliedUser.orEmpty().ifBlank { "用户" },
                    comment.repliedContent,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.card.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

private fun formatLikeCount(count: Int): String = when {
    count >= 100_000 -> "${count / 10_000}w"
    count >= 10_000 -> String.format("%.1fw", count / 10_000f)
    else -> count.toString()
}
