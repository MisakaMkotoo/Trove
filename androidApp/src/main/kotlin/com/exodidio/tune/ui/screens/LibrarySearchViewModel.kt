package com.exodidio.tune.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import com.exodidio.tune.sync.AndroidLibrarySyncStore
import com.exodidio.tune.sync.LibraryAlbum
import com.exodidio.tune.sync.LibraryArtist
import com.exodidio.tune.sync.LibraryComposer
import com.exodidio.tune.sync.LibraryPlaylist
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.metadataObject
import com.exodidio.tune.ui.libraryAlphabeticalComparator

data class LibrarySearchUiState(
    val isLoaded: Boolean = true,
    val query: String = "",
    val tracks: List<LibraryTrack> = emptyList(),
    internal val allTracks: List<LibraryTrack> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(),
    val artists: List<LibraryArtist> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val composers: List<LibraryComposer> = emptyList(),
    /** 搜索标签页：true = 网易云（默认），false = 本地资料库。 */
    val onlineMode: Boolean = true,
    val onlineQuery: String = "",
    val onlineTracks: List<LibraryTrack> = emptyList(),
    val onlinePlaylists: List<com.exodidio.tune.netease.OnlinePlaylistInfo> = emptyList(),
    val onlineArtists: List<com.exodidio.tune.netease.OnlineArtistInfo> = emptyList(),
    val onlineAlbums: List<com.exodidio.tune.netease.OnlineAlbumInfo> = emptyList(),
    val onlineLoading: Boolean = false,
    val onlineError: String? = null,
    val onlinePlaylistError: String? = null,
    val onlineArtistError: String? = null,
    val onlineAlbumError: String? = null,
    val onlineSuggestions: List<String> = emptyList(),
    val onlineCategory: OnlineSearchCategory = OnlineSearchCategory.All,
)

enum class OnlineSearchCategory {
    All,
    Songs,
    Playlists,
    Artists,
    Albums,
}

private data class OnlineSearchState(
    val query: String = "",
    val tracks: List<LibraryTrack> = emptyList(),
    val playlists: List<com.exodidio.tune.netease.OnlinePlaylistInfo> = emptyList(),
    val artists: List<com.exodidio.tune.netease.OnlineArtistInfo> = emptyList(),
    val albums: List<com.exodidio.tune.netease.OnlineAlbumInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val playlistError: String? = null,
    val artistError: String? = null,
    val albumError: String? = null,
)

internal class LibrarySearchViewModel(
    private val syncStore: AndroidLibrarySyncStore,
    private val hideShortAudio: Flow<Boolean> = flowOf(false),
    private val appContext: android.content.Context? = null,
) : ViewModel() {
    class Factory(
        private val syncStore: AndroidLibrarySyncStore,
        private val hideShortAudio: Flow<Boolean> = flowOf(false),
        private val appContext: android.content.Context? = null,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibrarySearchViewModel(syncStore, hideShortAudio, appContext) as T
    }

    private val query = MutableStateFlow("")
    private val onlineMode = MutableStateFlow(true)
    private val onlineCategory = MutableStateFlow(OnlineSearchCategory.All)
    private val onlineState = MutableStateFlow(OnlineSearchState())
    private val onlineSuggestions = MutableStateFlow<List<String>>(emptyList())
    private var onlineToken = 0

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(query, onlineMode) { text, online -> online to text.trim() }
                .distinctUntilChanged()
                .debounce { (online, keyword) -> if (!online || keyword.isEmpty()) 0L else 300L }
                .collectLatest { (online, keyword) ->
                    if (!online || keyword.isEmpty()) {
                        onlineSuggestions.value = emptyList()
                    } else {
                        onlineSuggestions.value = com.exodidio.tune.netease.NeteaseRepository
                            .fetchSearchSuggestions(keyword)
                    }
                }
        }
    }

    private val library = combine(
        syncStore.tracks, syncStore.albums, syncStore.artists, syncStore.playlists, syncStore.composers,
        syncStore.artistArtworkKeys, syncStore.artworkPaths,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        LibrarySnapshot(
            tracks = values[0] as List<LibraryTrack>,
            albums = values[1] as List<LibraryAlbum>,
            artists = values[2] as List<LibraryArtist>,
            playlists = values[3] as List<LibraryPlaylist>,
            composers = values[4] as List<LibraryComposer>,
            artworkKeys = values[5] as Map<String, String>,
            artworkPaths = values[6] as Map<String, String>,
        )
    }

    // 输入即搜：结果直接由当前关键词和曲库推导，不再经过额外的异步触发链路。
    val uiState: StateFlow<LibrarySearchUiState> = combine(
        query, library, hideShortAudio, onlineMode, onlineState,
    ) { text, library, hideShort, online, onlineState ->
        LibrarySearchUiState(
            isLoaded = true,
            query = text,
            tracks = filterShortAudioTracks(searchTracks(library.tracks, text), hideShort),
            allTracks = filterShortAudioTracks(library.tracks, hideShort),
            albums = searchLibrary(library.albums, text, { listOf(it.title, it.artist) }, { it.sortTitle }, { it.id }),
            artists = overlaySearchArtistArtwork(
                searchLibrary(library.artists, text, { listOf(it.name) }, { it.sortName }, { it.id }),
                library.artworkKeys,
                library.artworkPaths,
            ),
            playlists = searchLibrary(library.playlists, text, { listOf(it.name, playlistDescription(it)) }, { it.name }, { it.id }),
            composers = searchLibrary(library.composers, text, { listOf(it.name) }, { it.sortName }, { it.id }),
            onlineMode = online,
            onlineQuery = onlineState.query,
            onlineTracks = onlineState.tracks,
            onlinePlaylists = onlineState.playlists,
            onlineArtists = onlineState.artists,
            onlineAlbums = onlineState.albums,
            onlineLoading = onlineState.loading,
            onlineError = onlineState.error,
            onlinePlaylistError = onlineState.playlistError,
            onlineArtistError = onlineState.artistError,
            onlineAlbumError = onlineState.albumError,
        )
    }.combine(onlineSuggestions) { state, suggestions ->
        state.copy(onlineSuggestions = suggestions)
    }.combine(onlineCategory) { state, category ->
        state.copy(onlineCategory = category)
    }.combine(syncStore.playbackOnlyTracksFlow) { state, playbackOnly ->
        // 封面是异步补齐的：结果列表按 trackId 合并 store 里的最新封面，晚到也会自动出现。
        if (state.onlineTracks.isEmpty()) {
            state
        } else {
            val artworkById = playbackOnly.associate { it.id to it.artworkPath }
            state.copy(
                onlineTracks = state.onlineTracks.map { track ->
                    val artwork = artworkById[track.id]
                    if (artwork != null && artwork != track.artworkPath) track.copy(artworkPath = artwork) else track
                },
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LibrarySearchUiState(isLoaded = false),
    )

    fun setQuery(value: String) { query.value = value }
    fun clear() {
        query.value = ""
        onlineCategory.value = OnlineSearchCategory.All
        onlineState.value = OnlineSearchState()
        onlineSuggestions.value = emptyList()
        onlineToken++
    }

    /** 切换搜索标签页（网易云 / 资料库），保留已加载的在线搜索结果，切回无需重新搜。 */
    fun setOnlineMode(enabled: Boolean) {
        if (onlineMode.value == enabled) return
        onlineMode.value = enabled
    }

    fun setOnlineCategory(category: OnlineSearchCategory) {
        if (onlineCategory.value == category) return
        onlineCategory.value = category
    }

    /** 在线搜索只在回车时触发，避免每敲一个字就请求网易云。 */
    fun submitOnlineSearch() {
        if (!onlineMode.value) return
        val keyword = query.value.trim()
        if (keyword.isEmpty()) return
        appContext?.let { com.exodidio.tune.netease.NeteaseRepository.recordSearchHistory(it, keyword) }
        onlineSuggestions.value = emptyList()
        onlineCategory.value = OnlineSearchCategory.All
        val token = ++onlineToken
        onlineState.value = OnlineSearchState(query = keyword, loading = true)
        viewModelScope.launch {
            val result = com.exodidio.tune.netease.NeteaseRepository.searchOnline(keyword)
            if (token != onlineToken) return@launch
            result.fold(
                onSuccess = { found ->
                    onlineState.value = OnlineSearchState(
                        query = keyword,
                        tracks = found.songs,
                        playlists = found.playlists,
                        artists = found.artists,
                        albums = found.albums,
                        playlistError = found.playlistError,
                        artistError = found.artistError,
                        albumError = found.albumError,
                    )
                    // 歌单/歌手/专辑封面异步补齐，到达后按 id 回填。
                    found.playlists.forEach { item ->
                        if (item.artworkPath == null) ensureArtwork(item.coverUrl) { path ->
                            onlineState.update { state ->
                                state.copy(playlists = state.playlists.map {
                                    if (it.id == item.id) it.copy(artworkPath = path) else it
                                })
                            }
                        }
                    }
                    found.artists.forEach { item ->
                        if (item.artworkPath == null) ensureArtwork(item.coverUrl) { path ->
                            onlineState.update { state ->
                                state.copy(artists = state.artists.map {
                                    if (it.id == item.id) it.copy(artworkPath = path) else it
                                })
                            }
                        }
                    }
                    found.albums.forEach { item ->
                        if (item.artworkPath == null) ensureArtwork(item.coverUrl) { path ->
                            onlineState.update { state ->
                                state.copy(albums = state.albums.map {
                                    if (it.id == item.id) it.copy(artworkPath = path) else it
                                })
                            }
                        }
                    }
                },
                onFailure = {
                    onlineState.value = OnlineSearchState(
                        query = keyword,
                        error = it.message ?: appContext?.getString(com.exodidio.tune.R.string.netease_search_failed),
                    )
                },
            )
        }
    }

    private fun ensureArtwork(url: String, apply: (String) -> Unit) {
        if (url.isBlank()) return
        com.exodidio.tune.netease.NeteaseRepository.ensureOnlineArtwork(url, apply)
    }
}

private data class LibrarySnapshot(
    val tracks: List<LibraryTrack>,
    val albums: List<LibraryAlbum>,
    val artists: List<LibraryArtist>,
    val playlists: List<LibraryPlaylist>,
    val composers: List<LibraryComposer>,
    val artworkKeys: Map<String, String>,
    val artworkPaths: Map<String, String>,
)

/**
 * Overlays custom artist artwork onto search results (custom-wins),
 * mirroring the list/hero overlay in [artistDisplayArtworkPath].
 */
internal fun overlaySearchArtistArtwork(
    artists: List<LibraryArtist>,
    artistArtworkKeys: Map<String, String>,
    artworkPaths: Map<String, String>,
): List<LibraryArtist> = artists.map { artist ->
    artist.copy(artworkPath = artistDisplayArtworkPath(artist, artistArtworkKeys, artworkPaths))
}

internal const val LibrarySearchDebounceMs = 200L

@OptIn(FlowPreview::class)
internal fun Flow<String>.debouncedLibrarySearchQuery(): Flow<String> = debounce { query ->
    if (query.isBlank()) 0L else LibrarySearchDebounceMs
}

internal fun searchTracks(tracks: List<LibraryTrack>, query: String, candidateIds: Set<String>? = null): List<LibraryTrack> = searchLibrary(
    tracks, query,
    { track -> listOf(track.title, track.artists, track.album) + trackGenres(track) },
    { it.sortTitle }, { it.id }, candidateIds,
)

internal fun <T> searchLibrary(
    items: List<T>, query: String, fields: (T) -> List<String>, sortName: (T) -> String, id: (T) -> String, candidateIds: Set<String>? = null,
): List<T> {
    val phrase = normalizedLibrarySearchText(query).trim()
    if (phrase.isEmpty()) return emptyList()
    val terms = searchTokens(phrase)
    return items.asSequence().filter { candidateIds == null || id(it) in candidateIds }.mapNotNull { item ->
        val normalizedFields = fields(item).map(::normalizedLibrarySearchText)
        if (!terms.all { term -> normalizedFields.any { field -> searchTokens(field).any { it.startsWith(term) } } }) null
        else item to when {
            normalizedFields.any { it == phrase } -> 0
            normalizedFields.any { it.startsWith(phrase) } -> 1
            else -> 2
        }
    }.sortedWith(compareBy<Pair<T, Int>> { it.second }.thenBy(libraryAlphabeticalComparator) { sortName(it.first) }.thenBy { id(it.first) })
        .map(Pair<T, Int>::first)
        .toList()
}

private val SearchTokenPattern = Regex("[\\p{L}\\p{N}]+")

/** Mirrors Bleve's word-token matching for punctuation-delimited metadata. */
internal fun searchTokens(value: String): List<String> = SearchTokenPattern.findAll(normalizedLibrarySearchText(value)).map { it.value }.toList()

private fun trackGenres(track: LibraryTrack): List<String> = track.metadataObject().metadataValues(
    "genres", "genre", "raw_genre_names", "raw_genres", "genre_names",
)

private fun playlistDescription(playlist: LibraryPlaylist): String = runCatching {
    LibrarySyncProtocol.json.parseToJsonElement(playlist.metadataJson) as? JsonObject
}.getOrNull().metadataValues("description", "playlist").joinToString(" ")

private fun JsonObject?.metadataValues(vararg keys: String): List<String> = keys.flatMap { key ->
    when (val value = this?.get(key)) {
        is JsonArray -> value.flatMap { it.metadataValues() }
        else -> value.metadataValues()
    }
}

private fun JsonElement?.metadataValues(): List<String> = when (this) {
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    is JsonObject -> listOfNotNull(
        (this["name"] as? JsonPrimitive)?.contentOrNull,
        (this["title"] as? JsonPrimitive)?.contentOrNull,
        (this["description"] as? JsonPrimitive)?.contentOrNull,
    )
    is JsonArray -> flatMap { it.metadataValues() }
    else -> emptyList()
}
