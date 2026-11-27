package com.exodidio.tune.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exodidio.tune.R
import com.exodidio.tune.netease.MineAlbum
import com.exodidio.tune.netease.NeteaseRepository
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LibraryTextFilterSlot
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TuneRemoteImage
import com.exodidio.tune.ui.components.rememberPullDownFilterState
import com.exodidio.tune.ui.theme.LocalTuneColors

internal fun filterMineSavedAlbums(albums: List<MineAlbum>, query: String): List<MineAlbum> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return albums
    return albums.filter { album ->
        album.name.contains(trimmed, ignoreCase = true) ||
            album.artistName.contains(trimmed, ignoreCase = true)
    }
}

/** “收藏的专辑”全部二级页（支持下拉搜索、分页加载与长按卡片菜单取消收藏）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MineSavedAlbumsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    onOpenAlbum: (MineAlbum) -> Unit = {},
) {
    val colors = LocalTuneColors.current
    val mine by NeteaseRepository.mine.collectAsStateWithLifecycle()
    var contextAlbumId by remember { mutableStateOf<String?>(null) }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    val trimmedQuery = filterQuery.trim()
    val visibleAlbums = remember(mine.albums, trimmedQuery) {
        filterMineSavedAlbums(mine.albums, trimmedQuery)
    }
    val pullDownFilterState = rememberPullDownFilterState(
        listState = listState,
        filterKey = "mine_saved_albums",
        filterActive = trimmedQuery.isNotEmpty(),
    )

    LaunchedEffect(Unit) { NeteaseRepository.loadMine() }

    val nearEnd by remember(listState, mine.albums.size) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 5
        }
    }
    LaunchedEffect(nearEnd, mine.albumsHasMore, mine.loadingMoreAlbums, trimmedQuery) {
        if (trimmedQuery.isEmpty() && nearEnd && mine.albumsHasMore && !mine.loadingMoreAlbums) {
            NeteaseRepository.loadMoreSavedAlbums()
        }
    }

    Box(modifier = modifier.fillMaxSize().nestedScroll(pullDownFilterState.scrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            item(key = "mine-saved-albums-text-filter", contentType = "mine-saved-albums-text-filter") {
                LibraryTextFilterSlot(state = pullDownFilterState) { showPlaceholder ->
                    LibraryTextFilter(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        placeholder = stringResource(R.string.filter_placeholder_search),
                        showPlaceholderAndLeadingSymbol = showPlaceholder,
                    )
                }
            }
            if (mine.albums.isEmpty()) {
                item(key = "mine_albums_empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.mine_albums_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                    }
                }
            } else if (visibleAlbums.isEmpty() && trimmedQuery.isNotEmpty()) {
                item(key = "mine_albums_filter_empty") {
                    HeroCard(
                        symbol = MaterialSymbols.Search,
                        title = stringResource(R.string.albums_no_match_title),
                        description = stringResource(R.string.filter_no_match_description),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            } else {
                itemsIndexed(items = visibleAlbums, key = { _, album -> album.id }) { index, album ->
                    val trackCountText = if (album.trackCount > 0) {
                        stringResource(R.string.netease_track_count, album.trackCount)
                    } else {
                        null
                    }
                    val subtitle = listOfNotNull(
                        album.artistName.takeIf { it.isNotBlank() },
                        trackCountText,
                    ).joinToString(" · ")
                    MineAlbumCardContextMenu(
                        album = album,
                        expanded = contextAlbumId == album.id,
                        onDismiss = { if (contextAlbumId == album.id) contextAlbumId = null },
                        onOpen = { onOpenAlbum(album) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenAlbum(album) },
                                    onLongClick = { contextAlbumId = album.id },
                                )
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TuneRemoteImage(
                                url = album.coverUrl,
                                sizePx = 300,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.glassElevated)
                                    .border(0.5.dp, colors.borderGlass, RoundedCornerShape(6.dp)),
                                fallback = {
                                    MaterialSymbol(
                                        symbol = MaterialSymbols.Album,
                                        contentDescription = null,
                                        size = 24.dp,
                                        tint = colors.textMuted,
                                    )
                                },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = album.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                    color = colors.textMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (index < visibleAlbums.lastIndex) {
                        HorizontalDivider(
                            color = colors.borderGlass,
                            modifier = Modifier.padding(start = 82.dp, end = 16.dp),
                        )
                    }
                }
                if (trimmedQuery.isEmpty()) {
                    item(key = "mine_albums_footer") {
                        PagingFooter(
                            loading = mine.loadingMoreAlbums,
                            hasMore = mine.albumsHasMore,
                        )
                    }
                }
            }
        }
    }
}
