package com.exodidio.tune.netease

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.exodidio.tune.sync.AndroidSyncRuntime
import com.exodidio.tune.sync.LibraryTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class WifiAutoCacheStatus(
    val active: Boolean = false,
    val onUnmeteredWifi: Boolean = false,
    val currentPlaylistId: String? = null,
    val currentPlaylistName: String = "",
    val completed: Int = 0,
    val total: Int = 0,
)

internal fun isUnmeteredWifiCapabilities(capabilities: NetworkCapabilities?): Boolean {
    if (capabilities == null) return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

internal fun encodeWifiAutoCacheIds(ids: Set<String>): String =
    ids.asSequence().map(String::trim).filter(String::isNotBlank).sorted().joinToString(",")

internal fun decodeWifiAutoCacheIds(raw: String?): Set<String> =
    raw.orEmpty()
        .split(',')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

internal fun selectPendingAutoCacheEntries(
    entries: List<NeteaseEntryRecord>,
    libraryById: Map<String, LibraryTrack>,
    fileExists: (String) -> Boolean,
): List<NeteaseEntryRecord> = entries.filter { entry ->
    val entryFile = entry.downloadedFile?.takeIf(fileExists)
    val importedFile = libraryById["netease:${entry.songId}"]
        ?.audioPath
        ?.takeIf { !it.startsWith("netease://") }
        ?.takeIf(fileExists)
    entryFile == null && importedFile == null
}

/**
 * 指定歌单在免费 Wi-Fi 下自动离线缓存。
 * 全部网络与磁盘操作都在 Dispatchers.IO；断网或切换为计费网络时由 collectLatest 立即取消。
 */
internal object NeteaseWifiAutoCacheManager {
    private const val TAG = "WifiAutoCache"
    private const val HYDRATE_TIMEOUT_MS = 20_000L
    private const val DOWNLOAD_TIMEOUT_MS = 120_000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _onUnmeteredWifi = MutableStateFlow(false)
    private val _status = MutableStateFlow(WifiAutoCacheStatus())
    val status: StateFlow<WifiAutoCacheStatus> = _status.asStateFlow()

    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (initialized) {
            refreshNetworkState(app)
            return
        }
        synchronized(this) {
            if (initialized) {
                refreshNetworkState(app)
                return
            }
            initialized = true
        }
        registerNetworkCallbacks(app)
        refreshNetworkState(app)
        ioScope.launch {
            combine(
                _onUnmeteredWifi,
                NeteaseRepository.wifiAutoCacheIds,
            ) { wifi, ids -> wifi to ids }
                .distinctUntilChanged()
                .collectLatest { (wifi, ids) ->
                    if (!wifi || ids.isEmpty()) {
                        _status.value = WifiAutoCacheStatus(onUnmeteredWifi = wifi)
                        return@collectLatest
                    }
                    delay(1_200L)
                    runAutoCachePass(app, ids)
                }
        }
    }

    fun refreshNow(context: Context) {
        val app = context.applicationContext
        initialize(app)
        refreshNetworkState(app)
    }

    private fun registerNetworkCallbacks(app: Context) {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshNetworkState(app)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                refreshNetworkState(app)
            override fun onLost(network: Network) = refreshNetworkState(app)
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { defaultCallback = callback }

        val wifiReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val wifiCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshNetworkState(app)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                refreshNetworkState(app)
            override fun onLost(network: Network) = refreshNetworkState(app)
        }
        runCatching { manager.registerNetworkCallback(wifiReq, wifiCb) }
            .onSuccess { wifiCallback = wifiCb }
    }

    private fun refreshNetworkState(app: Context) {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        val caps = active?.let { manager.getNetworkCapabilities(it) }
        val unmeteredWifi = isUnmeteredWifiCapabilities(caps)
        _onUnmeteredWifi.value = unmeteredWifi
        if (!unmeteredWifi) {
            _status.value = _status.value.copy(active = false, onUnmeteredWifi = false)
        } else {
            _status.value = _status.value.copy(onUnmeteredWifi = true)
        }
    }

    private suspend fun runAutoCachePass(app: Context, ids: Set<String>) {
        try {
            runCatching { NeteaseRepository.awaitDiskLoaded() }
            runCatching { AndroidSyncRuntime.syncStore.awaitLoaded() }
            for (playlistId in ids) {
                currentCoroutineContext().ensureActive()
                if (!_onUnmeteredWifi.value || playlistId !in NeteaseRepository.wifiAutoCacheIds.value) break

                withTimeoutOrNull(HYDRATE_TIMEOUT_MS) {
                    NeteaseRepository.ensureDynamicLoaded(app, playlistId)
                }
                val record = NeteaseRepository.playlistById(playlistId) ?: continue
                val libraryById = AndroidSyncRuntime.syncStore.tracks.value.associateBy { it.id }
                val pending = selectPendingAutoCacheEntries(
                    entries = record.entries,
                    libraryById = libraryById,
                    fileExists = { path -> NeteaseMediaHelper.isAudioPathAvailable(app, path) },
                )
                if (pending.isEmpty()) continue

                Log.d(TAG, "Auto caching playlist=${record.name} pending=${pending.size}")
                var completed = 0
                _status.value = WifiAutoCacheStatus(
                    active = true,
                    onUnmeteredWifi = _onUnmeteredWifi.value,
                    currentPlaylistId = playlistId,
                    currentPlaylistName = record.name,
                    completed = 0,
                    total = pending.size,
                )
                for (entry in pending) {
                    currentCoroutineContext().ensureActive()
                    if (!_onUnmeteredWifi.value || playlistId !in NeteaseRepository.wifiAutoCacheIds.value) break
                    withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                        NeteaseRepository.ensureDownloaded(app, playlistId, entry.songId)
                    }
                    completed += 1
                    _status.value = WifiAutoCacheStatus(
                        active = true,
                        onUnmeteredWifi = _onUnmeteredWifi.value,
                        currentPlaylistId = playlistId,
                        currentPlaylistName = record.name,
                        completed = completed,
                        total = pending.size,
                    )
                }
            }
        } finally {
            _status.value = WifiAutoCacheStatus(
                active = false,
                onUnmeteredWifi = _onUnmeteredWifi.value,
            )
        }
    }
}
