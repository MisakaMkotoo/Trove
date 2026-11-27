package com.exodidio.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exodidio.tune.R
import com.exodidio.tune.library.LibraryFolder
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.ui.components.GenreContextMenu
import com.exodidio.tune.ui.components.HeroCard
import com.exodidio.tune.ui.components.LibraryTextFilter
import com.exodidio.tune.ui.components.LibraryVirtualList
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneRadii
import dev.chrisbanes.haze.HazeState

@Composable
internal fun LibraryFoldersContent(
    uiState: LibraryFoldersUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState = remember(uiState.sortOption, uiState.sortOrder) { LazyListState() },
    contentPadding: PaddingValues = PaddingValues(),
    onFolderClick: (LibraryFolder) -> Unit = {},
    onFilterQueryChange: (String) -> Unit = {},
    orderedTrackIdsForFolder: (String) -> List<String> = { emptyList() },
    onFolderPlayNext: (List<String>) -> Unit = {},
    onFolderAddToQueue: (List<String>) -> Unit = {},
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
    hazeState: HazeState? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
) {
    var contextFolderId by remember { mutableStateOf<String?>(null) }
    val listPadding = remember(contentPadding) {
        PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
            start = 0.dp,
            end = 0.dp,
        )
    }
    LibraryVirtualList(
        items = uiState.folders,
        isLoaded = uiState.isLoaded,
        key = { folder -> folder.id },
        contentType = "folder_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        dividerTestTag = "folder-row-divider",
        filterKey = "folders",
        filterActive = uiState.filterQuery.isNotBlank(),
        filterContent = {
            LibraryTextFilter(
                value = uiState.filterQuery,
                onValueChange = onFilterQueryChange,
                placeholder = stringResource(R.string.filter_placeholder_search),
            )
        },
        alphabeticalIndexKey = if (uiState.sortOption == FolderSortOption.Name) ({ folder -> folder.name.ifBlank { folder.id } }) else null,
        emptyContent = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroCard(
                    title = stringResource(if (uiState.filterQuery.isBlank()) R.string.folders_empty_title else R.string.folders_no_match_title),
                    description = stringResource(if (uiState.filterQuery.isBlank()) R.string.folders_empty_description else R.string.filter_no_match_description),
                    symbol = MaterialSymbols.Folder,
                )
            }
        },
    ) { folder ->
        val trackIds = if (contextFolderId == folder.id) {
            orderedTrackIdsForFolder(folder.id)
        } else {
            emptyList()
        }
        GenreContextMenu(
            trackIds = trackIds,
            expanded = contextFolderId == folder.id,
            onDismiss = { if (contextFolderId == folder.id) contextFolderId = null },
            onPlayNext = onFolderPlayNext,
            onAddToQueue = onFolderAddToQueue,
            onBottomSheetRequested = onTrackContextBottomSheet,
            addToPlaylistOnly = true,
            hazeState = hazeState,
            playbackQueue = playbackQueue,
        ) {
            FolderRow(
                folder = folder,
                onClick = { onFolderClick(folder) },
                onLongClick = { contextFolderId = folder.id },
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: LibraryFolder,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val countLabel = pluralStringResource(
        R.plurals.playlist_details_track_count,
        folder.trackCount,
        folder.trackCount,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(TuneRadii.ListArtwork))
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(TuneRadii.ListArtwork)),
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(
                symbol = MaterialSymbols.Folder,
                contentDescription = null,
                size = 24.dp,
                tint = colors.primary,
                filled = true,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$countLabel · ${folder.displayPath}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(44.dp),
        ) {
            MaterialSymbol(
                symbol = MaterialSymbols.ChevronRight,
                contentDescription = stringResource(R.string.folder_row_open),
                size = 20.dp,
                tint = colors.textMuted,
            )
        }
    }
}
