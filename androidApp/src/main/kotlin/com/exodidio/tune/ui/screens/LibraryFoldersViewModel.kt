package com.exodidio.tune.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exodidio.tune.R
import com.exodidio.tune.library.LibraryFolder
import com.exodidio.tune.library.folderPathFromMetadata
import com.exodidio.tune.library.groupTracksByFolder
import com.exodidio.tune.library.queryMediaStoreFolders
import com.exodidio.tune.player.PlaybackController
import com.exodidio.tune.player.PlaybackRequest
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.libraryAlphabeticalComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

enum class FolderSortOption {
    Name,
    TrackCount,
    DateAdded,
}

data class LibraryFoldersUiState(
    val isLoaded: Boolean = false,
    val folders: List<LibraryFolder> = emptyList(),
    val filterQuery: String = "",
    val sortOption: FolderSortOption = FolderSortOption.Name,
    val sortOrder: SortOrder = SortOrder.Ascending,
)

data class FolderDetailsUiState(
    val folder: LibraryFolder? = null,
    val tracks: List<LibraryTrack> = emptyList(),
)

internal class LibraryFoldersViewModel(
    private val appContext: Context,
    syncStore: AndroidLibrarySyncStore,
    private val playbackController: PlaybackController,
) : ViewModel() {
    class Factory(
        private val context: Context,
        private val syncStore: AndroidLibrarySyncStore,
        private val playbackController: PlaybackController,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryFoldersViewModel(context.applicationContext, syncStore, playbackController) as T
    }

    private val internalStorageLabel = appContext.getString(R.string.folder_internal_storage)
    private val sortOptionFlow = MutableStateFlow(FolderSortOption.Name)
    private val sortOrderFlow = MutableStateFlow(SortOrder.Ascending)
    private val filterQueryFlow = MutableStateFlow("")
    private val appliedFilterQuery = filterQueryFlow.debouncedLibrarySearchQuery()
    private val mediaStoreFoldersFlow = MutableStateFlow<Map<Long, String>>(emptyMap())

    init {
        syncStore.tracks
            .onEach { tracks ->
                val needsLookup = tracks.any { track ->
                    track.folderPathFromMetadata() == null &&
                        track.audioPath?.startsWith("content://", ignoreCase = true) == true
                }
                if (needsLookup) {
                    val resolved = withContext(Dispatchers.IO) {
                        queryMediaStoreFolders(appContext)
                    }
                    if (resolved.isNotEmpty() || mediaStoreFoldersFlow.value.isNotEmpty()) {
                        mediaStoreFoldersFlow.value = resolved
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    val allFolders: StateFlow<List<LibraryFolder>> = combine(
        syncStore.tracks,
        mediaStoreFoldersFlow,
    ) { tracks, mediaStoreFolders ->
        groupTracksByFolder(
            tracks = tracks,
            mediaStoreFolderById = mediaStoreFolders,
            internalStorageLabel = internalStorageLabel,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val uiState: StateFlow<LibraryFoldersUiState> = combine(
        allFolders,
        sortOptionFlow,
        sortOrderFlow,
        filterQueryFlow,
        appliedFilterQuery,
    ) { folders, option, order, query, appliedQuery ->
        val filtered = if (appliedQuery.isBlank()) {
            folders
        } else {
            folders.filter { folder ->
                matchesLibraryTextFilter(appliedQuery, folder.name, folder.displayPath)
            }
        }
        LibraryFoldersUiState(
            isLoaded = true,
            folders = sortFolders(filtered, option, order),
            filterQuery = query,
            sortOption = option,
            sortOrder = order,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryFoldersUiState(isLoaded = false),
    )

    fun setSortOption(option: FolderSortOption) {
        sortOptionFlow.value = option
    }

    fun setFilterQuery(query: String) {
        filterQueryFlow.value = query
    }

    fun toggleSortOrder() {
        sortOrderFlow.value = if (sortOrderFlow.value == SortOrder.Ascending) {
            SortOrder.Descending
        } else {
            SortOrder.Ascending
        }
    }

    fun orderedTrackIds(folderId: String): List<String> =
        allFolders.value.firstOrNull { it.id == folderId }?.trackIds.orEmpty()

    fun play(folderId: String, shuffle: Boolean) {
        val ids = orderedTrackIds(folderId)
        if (ids.isEmpty()) return
        val request = PlaybackRequest(ids, 0)
        if (shuffle) playbackController.shuffle(request) else playbackController.play(request)
    }

    fun playTrack(folderId: String, trackId: String) {
        val ids = orderedTrackIds(folderId)
        val index = ids.indexOf(trackId).takeIf { it >= 0 } ?: 0
        if (ids.isEmpty()) return
        playbackController.play(PlaybackRequest(ids, index))
    }
}

internal fun folderDetailsUiStateFor(
    folders: List<LibraryFolder>,
    tracks: List<LibraryTrack>,
    folderId: String,
): FolderDetailsUiState {
    val folder = folders.firstOrNull { it.id == folderId } ?: return FolderDetailsUiState()
    val trackById = tracks.associateBy { it.id }
    val resolvedTracks = folder.trackIds.mapNotNull { trackById[it] }
    return FolderDetailsUiState(
        folder = folder.copy(trackIds = resolvedTracks.map { it.id }, trackCount = resolvedTracks.size),
        tracks = resolvedTracks,
    )
}

internal fun sortFolders(
    folders: List<LibraryFolder>,
    option: FolderSortOption,
    order: SortOrder,
): List<LibraryFolder> {
    val comparator = when (option) {
        FolderSortOption.Name -> compareBy<LibraryFolder, String>(libraryAlphabeticalComparator) { it.sortName }
            .thenBy { it.displayPath.lowercase() }
            .thenBy { it.id }
        FolderSortOption.TrackCount -> compareByDescending<LibraryFolder> { it.trackCount }
            .thenBy(libraryAlphabeticalComparator) { it.sortName }
            .thenBy { it.id }
        FolderSortOption.DateAdded -> compareBy<LibraryFolder> { it.createdAt }
            .thenBy(libraryAlphabeticalComparator) { it.sortName }
            .thenBy { it.id }
    }
    val sorted = folders.sortedWith(comparator)
    return if (order == SortOrder.Descending) sorted.reversed() else sorted
}
