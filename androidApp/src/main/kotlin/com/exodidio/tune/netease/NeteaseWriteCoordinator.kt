package com.exodidio.tune.netease

import android.content.Context
import android.util.Log
import com.exodidio.tune.sync.LibrarySyncProtocol
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.sync.writeTextAtomically
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 网易云喜欢/红心写操作状态机（可独立单测）：
 * - 按账号修订号隔离会话（登出/切号后丢弃在途结果）。
 * - 按 songId 加非阻塞协程互斥锁串行化同一首歌的喜欢切换。
 * - 乐观更新 + 失败精确回滚 + 取消后后台对账，避免并发刷新覆盖新状态。
 */
internal class NeteaseLikeStateMachine(
    private val scope: CoroutineScope,
    private val hasSession: () -> Boolean,
    private val resolveUid: suspend () -> String?,
    private val fetchLikeList: suspend (uid: String) -> Result<Set<Long>>,
    private val apiLikeTrack: suspend (songId: Long, liked: Boolean) -> Result<Long?>,
    private val persistLikedIds: suspend (Set<Long>) -> Unit = {},
    private val onOptimisticPlaylistDelta: (songId: Long, liked: Boolean, track: LibraryTrack?) -> Unit = { _, _, _ -> },
    private val onCommittedPlaylistDelta: suspend (songId: Long, liked: Boolean, track: LibraryTrack?) -> Unit = { _, _, _ -> },
    private val onRollbackPlaylistDelta: (songId: Long, wasLiked: Boolean, track: LibraryTrack?) -> Unit = { _, _, _ -> },
    private val onSessionExpired: () -> Unit = {},
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = NeteaseRepository.DISCOVER_TTL_MS,
) {
    private val _likedSongIds = MutableStateFlow<Set<Long>>(emptySet())
    val likedSongIds: StateFlow<Set<Long>> = _likedSongIds.asStateFlow()

    private val _likeListLoaded = MutableStateFlow(false)
    val likeListLoaded: StateFlow<Boolean> = _likeListLoaded.asStateFlow()

    private val accountRevision = AtomicLong(0L)
    private val mutationVersion = AtomicLong(0L)
    private val songLocks = ConcurrentHashMap<Long, Mutex>()
    private val inFlightTargets = ConcurrentHashMap<Long, Boolean>()
    // 记录每次变更时的版本与目标态，防止并发的 likelist 刷新用旧服务端快照覆盖刚完成的本地变更。
    private val latestSongMutations = ConcurrentHashMap<Long, Pair<Long, Boolean>>()
    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null
    @Volatile
    private var loadedAtMs = 0L

    fun currentRevision(): Long = accountRevision.get()

    fun isLiked(songId: Long): Boolean = songId in _likedSongIds.value

    fun onSessionChanged() {
        accountRevision.incrementAndGet()
        mutationVersion.incrementAndGet()
        inFlightTargets.clear()
        latestSongMutations.clear()
        refreshJob?.cancel()
        refreshJob = null
        loadedAtMs = 0L
        _likeListLoaded.value = false
        _likedSongIds.value = emptySet()
    }

    fun applyCachedIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        if (!_likeListLoaded.value && _likedSongIds.value.isEmpty()) {
            _likedSongIds.value = ids
        }
    }

    suspend fun applySyncedLikedIds(
        ids: Set<Long>,
        expectedRevision: Long = accountRevision.get(),
        persist: Boolean = true,
    ) {
        if (accountRevision.get() != expectedRevision) return
        val merged = mergeWithInFlight(ids)
        _likedSongIds.value = merged
        _likeListLoaded.value = true
        loadedAtMs = clockMs()
        if (persist) persistLikedIds(merged)
    }

    suspend fun applyBatchLikedDelta(
        songIds: List<Long>,
        liked: Boolean,
        expectedRevision: Long = accountRevision.get(),
        persist: Boolean = true,
    ) {
        if (songIds.isEmpty() || accountRevision.get() != expectedRevision) return
        val version = mutationVersion.incrementAndGet()
        songIds.forEach { id -> latestSongMutations[id] = version to liked }
        _likedSongIds.update { current ->
            if (liked) current + songIds else current - songIds.toSet()
        }
        if (persist) persistLikedIds(_likedSongIds.value)
    }

    fun refreshLikedIds(force: Boolean = false): Job? {
        if (!hasSession()) return null
        val existing = refreshJob
        if (!force) {
            if (existing?.isActive == true) return existing
            if (_likeListLoaded.value && clockMs() - loadedAtMs < ttlMs) return null
        }
        return scope.launch {
            refreshLikedIdsSuspend(force)
        }
    }

    suspend fun refreshLikedIdsSuspend(force: Boolean = false): Result<Set<Long>> {
        if (!hasSession()) {
            return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        }
        val jobToAwait = refreshMutex.withLock {
            val active = refreshJob
            if (!force) {
                if (active?.isActive == true) return@withLock active
                if (_likeListLoaded.value && clockMs() - loadedAtMs < ttlMs) {
                    return Result.success(_likedSongIds.value)
                }
            } else {
                active?.cancel()
            }
            val expectedRevision = accountRevision.get()
            val launched = scope.launch {
                val uid = resolveUid()?.takeIf(String::isNotBlank) ?: return@launch
                val versionBefore = mutationVersion.get()
                val fetched = fetchLikeList(uid).getOrElse { err ->
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    Log.w("NeteaseWrite", "likelist failed: ${err.message}")
                    return@launch
                }
                if (accountRevision.get() != expectedRevision) return@launch
                val merged = mergeWithInFlight(fetched, sinceVersion = versionBefore)
                _likedSongIds.value = merged
                _likeListLoaded.value = true
                loadedAtMs = clockMs()
                persistLikedIds(merged)
            }
            refreshJob = launched
            launched
        }
        jobToAwait.join()
        return Result.success(_likedSongIds.value)
    }

    suspend fun setTrackLiked(
        songId: Long,
        liked: Boolean,
        track: LibraryTrack?,
    ): Result<Unit> {
        if (!hasSession()) {
            return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        }
        val lock = songLocks.computeIfAbsent(songId) { Mutex() }
        return lock.withLock {
            if (!hasSession()) {
                return@withLock Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
            }
            val expectedRevision = accountRevision.get()
            val wasLiked = songId in _likedSongIds.value
            val optimisticVersion = mutationVersion.incrementAndGet()
            inFlightTargets[songId] = liked
            latestSongMutations[songId] = optimisticVersion to liked
            _likedSongIds.update { current -> if (liked) current + songId else current - songId }
            onOptimisticPlaylistDelta(songId, liked, track)

            val result = try {
                apiLikeTrack(songId, liked)
            } catch (cancelled: CancellationException) {
                inFlightTargets.remove(songId)
                // 请求可能已在服务端生效：后台发起强制对账，再向上传播取消。
                scope.launch {
                    withContext(NonCancellable) {
                        if (accountRevision.get() == expectedRevision && hasSession()) {
                            runCatching { refreshLikedIdsSuspend(force = true) }
                        }
                    }
                }
                throw cancelled
            }
            inFlightTargets.remove(songId)

            if (accountRevision.get() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }

            result.fold(
                onSuccess = {
                    val committedVersion = mutationVersion.incrementAndGet()
                    latestSongMutations[songId] = committedVersion to liked
                    val snapshot = _likedSongIds.value
                    withContext(NonCancellable) {
                        persistLikedIds(snapshot)
                        onCommittedPlaylistDelta(songId, liked, track)
                    }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (accountRevision.get() == expectedRevision) {
                        val rollbackVersion = mutationVersion.incrementAndGet()
                        latestSongMutations[songId] = rollbackVersion to wasLiked
                        _likedSongIds.update { current -> if (wasLiked) current + songId else current - songId }
                        onRollbackPlaylistDelta(songId, wasLiked, track)
                    }
                    if (err is NeteaseWriteException.LoginRequired) {
                        onSessionExpired()
                    }
                    Result.failure(err)
                },
            )
        }
    }

    private fun mergeWithInFlight(serverIds: Set<Long>, sinceVersion: Long = 0L): Set<Long> {
        if (inFlightTargets.isEmpty() && latestSongMutations.isEmpty()) return serverIds
        val merged = LinkedHashSet(serverIds)
        latestSongMutations.forEach { (id, record) ->
            val (ver, target) = record
            if (ver > sinceVersion) {
                if (target) merged.add(id) else merged.remove(id)
            }
        }
        inFlightTargets.forEach { (id, target) ->
            if (target) merged.add(id) else merged.remove(id)
        }
        return merged
    }
}

/**
 * 网易云歌单写操作状态机（可独立单测）：
 * - 按歌单 ID 串行化编辑、删歌单、增删歌曲、收藏/取消收藏。
 * - 不在持锁期间操作 UI；网络返回后校验 accountRevision，切号/登出即丢弃。
 */
internal class NeteasePlaylistWriteStateMachine(
    private val hasSession: () -> Boolean,
    private val currentRevision: () -> Long,
    private val resolveLikedRemoteId: suspend () -> String?,
    private val apiCreatePlaylist: suspend (name: String, description: String, privacyPrivate: Boolean) -> Result<CreatedNeteasePlaylist>,
    private val apiUpdatePlaylistName: suspend (playlistId: String, name: String) -> Result<Unit>,
    private val apiUpdatePlaylistDescription: suspend (playlistId: String, description: String) -> Result<Unit>,
    private val apiDeletePlaylist: suspend (playlistId: String) -> Result<Unit>,
    private val apiManipulateTracks: suspend (playlistId: String, trackIds: List<Long>, add: Boolean) -> Result<Unit>,
    private val apiSubscribePlaylist: suspend (playlistId: String, subscribe: Boolean) -> Result<Unit>,
    private val apiReorderTracks: suspend (playlistId: String, orderedTrackIds: List<Long>) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    private val apiUpdatePrivacy: suspend (playlistId: String, privacyPrivate: Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    private val onPlaylistCreated: suspend (CreatedNeteasePlaylist) -> Unit = {},
    private val onPlaylistUpdated: suspend (playlistId: String, name: String, description: String) -> Unit = { _, _, _ -> },
    private val onPlaylistDeleted: suspend (playlistId: String) -> Unit = {},
    private val onTracksManipulated: suspend (targetId: String, remotePid: String, songIds: List<Long>, add: Boolean, isLikedTarget: Boolean) -> Unit = { _, _, _, _, _ -> },
    private val onPlaylistSubscribed: suspend (playlistId: String, subscribe: Boolean) -> Unit = { _, _ -> },
    private val onTracksReordered: suspend (playlistId: String, orderedTrackIds: List<Long>) -> Unit = { _, _ -> },
    private val onPrivacyUpdated: suspend (playlistId: String, privacyPrivate: Boolean) -> Unit = { _, _ -> },
    private val onSessionExpired: () -> Unit = {},
) {
    private val playlistLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(playlistId: String): Mutex =
        playlistLocks.computeIfAbsent(playlistId) { Mutex() }

    suspend fun createPlaylist(
        name: String,
        description: String,
        privacyPrivate: Boolean,
    ): Result<CreatedNeteasePlaylist> {
        if (!hasSession()) return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return Result.failure(IllegalArgumentException("歌单名称不能为空"))
        val trimmedDesc = description.trim()
        val expectedRevision = currentRevision()
        val res = apiCreatePlaylist(trimmedName, trimmedDesc, privacyPrivate)
        if (currentRevision() != expectedRevision) {
            return Result.failure(IllegalStateException("账号会话已切换"))
        }
        return res.fold(
            onSuccess = { created ->
                lockFor(created.id).withLock {
                    if (currentRevision() != expectedRevision) {
                        return@withLock Result.failure(IllegalStateException("账号会话已切换"))
                    }
                    withContext(NonCancellable) { onPlaylistCreated(created) }
                    Result.success(created)
                }
            },
            onFailure = { err ->
                if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                Result.failure(err)
            },
        )
    }

    suspend fun updatePlaylist(
        playlistId: String,
        name: String,
        description: String,
        currentName: String?,
        currentDescription: String?,
    ): Result<Unit> {
        if (!hasSession()) return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return Result.failure(IllegalArgumentException("歌单名称不能为空"))
        val trimmedDesc = description.trim()
        return lockFor(playlistId).withLock {
            if (!hasSession()) return@withLock Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
            val expectedRevision = currentRevision()
            val needNameUpdate = currentName == null || trimmedName != currentName
            val needDescUpdate = if (currentDescription != null) {
                trimmedDesc != currentDescription
            } else {
                trimmedDesc.isNotEmpty()
            }
            if (needNameUpdate) {
                val nameRes = apiUpdatePlaylistName(playlistId, trimmedName)
                if (nameRes.isFailure) {
                    val err = nameRes.exceptionOrNull()!!
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    return@withLock Result.failure(err)
                }
            }
            if (currentRevision() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }
            if (needDescUpdate) {
                val descRes = apiUpdatePlaylistDescription(playlistId, trimmedDesc)
                if (descRes.isFailure) {
                    val err = descRes.exceptionOrNull()!!
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    return@withLock Result.failure(err)
                }
            }
            if (currentRevision() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }
            if (needNameUpdate || needDescUpdate) {
                withContext(NonCancellable) {
                    onPlaylistUpdated(playlistId, trimmedName, trimmedDesc)
                }
            }
            Result.success(Unit)
        }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        if (!hasSession()) return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        return lockFor(playlistId).withLock {
            if (!hasSession()) return@withLock Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
            val expectedRevision = currentRevision()
            val res = apiDeletePlaylist(playlistId)
            if (currentRevision() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }
            res.fold(
                onSuccess = {
                    withContext(NonCancellable) { onPlaylistDeleted(playlistId) }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    Result.failure(err)
                },
            )
        }
    }

    suspend fun manipulateTracks(
        playlistId: String,
        songIds: List<Long>,
        add: Boolean,
        knownLikedRemoteId: String?,
    ): Result<Unit> {
        if (!hasSession()) return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        val distinctIds = songIds.distinct()
        if (distinctIds.isEmpty()) return Result.success(Unit)
        val isLikedTarget = playlistId == NeteaseRepository.LIKED_ID ||
            (knownLikedRemoteId != null && playlistId == knownLikedRemoteId)
        val remotePid = if (playlistId == NeteaseRepository.LIKED_ID) {
            resolveLikedRemoteId()?.takeIf(String::isNotBlank)
                ?: return Result.failure(IllegalStateException("无法获取“我喜欢的音乐”歌单 ID"))
        } else {
            playlistId
        }
        return lockFor(remotePid).withLock {
            if (!hasSession()) return@withLock Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
            val expectedRevision = currentRevision()
            val res = apiManipulateTracks(remotePid, distinctIds, add)
            if (currentRevision() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }
            res.fold(
                onSuccess = {
                    withContext(NonCancellable) {
                        onTracksManipulated(playlistId, remotePid, distinctIds, add, isLikedTarget)
                    }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    Result.failure(err)
                },
            )
        }
    }

    suspend fun setSubscribed(playlistId: String, subscribe: Boolean): Result<Unit> {
        if (!hasSession()) return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        return lockFor(playlistId).withLock {
            if (!hasSession()) return@withLock Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
            val expectedRevision = currentRevision()
            val res = apiSubscribePlaylist(playlistId, subscribe)
            if (currentRevision() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }
            res.fold(
                onSuccess = {
                    withContext(NonCancellable) { onPlaylistSubscribed(playlistId, subscribe) }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    Result.failure(err)
                },
            )
        }
    }

    suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<Long>): Result<Unit> {
        if (!hasSession()) return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        val distinct = orderedTrackIds.distinct()
        if (distinct.isEmpty()) return Result.success(Unit)
        return lockFor(playlistId).withLock {
            if (!hasSession()) return@withLock Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
            val expectedRevision = currentRevision()
            val res = apiReorderTracks(playlistId, distinct)
            if (currentRevision() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }
            res.fold(
                onSuccess = {
                    withContext(NonCancellable) { onTracksReordered(playlistId, distinct) }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    Result.failure(err)
                },
            )
        }
    }

    suspend fun updatePrivacy(playlistId: String, privacyPrivate: Boolean): Result<Unit> {
        if (!hasSession()) return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        return lockFor(playlistId).withLock {
            if (!hasSession()) return@withLock Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
            val expectedRevision = currentRevision()
            val res = apiUpdatePrivacy(playlistId, privacyPrivate)
            if (currentRevision() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }
            res.fold(
                onSuccess = {
                    withContext(NonCancellable) { onPrivacyUpdated(playlistId, privacyPrivate) }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    Result.failure(err)
                },
            )
        }
    }
}

/**
 * 网易云实体收藏/关注状态机（歌手关注、专辑收藏共用；可独立单测）：
 * - 按实体 ID 加非阻塞协程互斥锁，避免连续点击并发乱序。
 * - API 请求与修订号校验在锁内完成，持久化回调在释放锁后于 NonCancellable 中执行。
 */
internal class NeteaseEntitySubscriptionStateMachine(
    private val hasSession: () -> Boolean,
    private val currentRevision: () -> Long,
    private val apiSubscribe: suspend (entityId: String, subscribe: Boolean) -> Result<Unit>,
    private val onCommitted: suspend (entityId: String, subscribe: Boolean) -> Unit = { _, _ -> },
    private val onSessionExpired: () -> Unit = {},
) {
    private val entityLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun setSubscribed(entityId: String, subscribe: Boolean): Result<Unit> {
        val cleanId = entityId.trim()
        if (cleanId.isEmpty()) return Result.failure(IllegalArgumentException("无效 ID"))
        if (!hasSession()) return Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
        val lock = entityLocks.computeIfAbsent(cleanId) { Mutex() }
        return lock.withLock {
            if (!hasSession()) {
                return@withLock Result.failure(NeteaseWriteException.LoginRequired("请先登录网易云账号"))
            }
            val expectedRevision = currentRevision()
            val res = apiSubscribe(cleanId, subscribe)
            if (currentRevision() != expectedRevision) {
                return@withLock Result.failure(IllegalStateException("账号会话已切换"))
            }
            res.fold(
                onSuccess = {
                    withContext(NonCancellable) { onCommitted(cleanId, subscribe) }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (err is NeteaseWriteException.LoginRequired) onSessionExpired()
                    Result.failure(err)
                },
            )
        }
    }
}

internal fun resolveNeteasePlaylistPermission(
    playlistId: String,
    likedRemoteId: String?,
    isDynamicOrVirtual: Boolean,
    isRadar: Boolean,
    isOwnedCreated: Boolean,
): NeteasePlaylistPermission = when {
    playlistId == NeteaseRepository.LIKED_ID || (likedRemoteId != null && playlistId == likedRemoteId) ->
        NeteasePlaylistPermission.Liked
    isDynamicOrVirtual || isRadar ->
        NeteasePlaylistPermission.ReadOnlyDynamic
    isOwnedCreated ->
        NeteasePlaylistPermission.OwnedEditable
    playlistId.all(Char::isDigit) ->
        NeteasePlaylistPermission.ForeignSubscribable
    else ->
        NeteasePlaylistPermission.ReadOnlyDynamic
}

/** 网易云写操作单例协调器，绑定到 [NeteaseRepository] 与磁盘缓存。 */
internal object NeteaseWriteCoordinator {
    private const val LIKED_IDS_FILE = "netease-liked-ids.json"

    @Volatile
    private var likedPlaylistRemoteId: String? = null

    private val pendingTrackManipulations = ConcurrentHashMap<String, List<Pair<Long, LibraryTrack?>>>()

    private val likeMachine = NeteaseLikeStateMachine(
        scope = NeteaseRepository.ioScope,
        hasSession = { !NeteaseRepository.cookie().isNullOrBlank() },
        resolveUid = {
            val cachedUid = NeteaseRepository.accountInfo.value?.uid?.takeIf(String::isNotBlank)
            if (cachedUid != null) {
                cachedUid
            } else {
                val cookie = NeteaseRepository.cookie()
                NeteaseClient.fetchAccountInfo(cookie).getOrNull()?.also { info ->
                    NeteaseRepository.setAccountInfo(info)
                }?.uid
            }
        },
        fetchLikeList = { uid -> NeteaseWriteApi.fetchLikeList(uid, NeteaseRepository.cookie()) },
        apiLikeTrack = { songId, liked ->
            val fallbackPid = resolveLikedPlaylistRemoteId()
            NeteaseWriteApi.likeTrack(
                songId = songId,
                like = liked,
                cookie = NeteaseRepository.cookie(),
                fallbackPlaylistId = fallbackPid,
            ).onSuccess { pid ->
                pid?.toString()?.let(::updateLikedPlaylistRemoteId)
            }
        },
        persistLikedIds = { ids ->
            NeteaseRepository.appContext?.let { ctx -> writeLikedIds(ctx, ids) }
        },
        onOptimisticPlaylistDelta = { songId, liked, track ->
            NeteaseRepository.applyLikedPlaylistDelta(songId, liked, track, persist = false)
        },
        onCommittedPlaylistDelta = { songId, liked, track ->
            NeteaseRepository.applyLikedPlaylistDelta(songId, liked, track, persist = true)
            NeteaseRepository.appContext?.let { ctx ->
                val count = NeteaseRepository.playlistById(NeteaseRepository.LIKED_ID)?.let { rec ->
                    rec.entries.size.takeIf { it > 0 } ?: rec.remoteTrackCount
                } ?: likedSongIds.value.size
                NeteaseFeedLoader.onPlaylistTrackCountChanged(ctx, NeteaseRepository.LIKED_ID, likedPlaylistRemoteId, count)
            }
        },
        onRollbackPlaylistDelta = { songId, wasLiked, track ->
            NeteaseRepository.applyLikedPlaylistDelta(songId, wasLiked, track, persist = false)
        },
        onSessionExpired = { NeteaseRepository.markSessionExpired() },
    )

    private val playlistMachine = NeteasePlaylistWriteStateMachine(
        hasSession = { !NeteaseRepository.cookie().isNullOrBlank() },
        currentRevision = { likeMachine.currentRevision() },
        resolveLikedRemoteId = { resolveLikedPlaylistRemoteId() },
        apiCreatePlaylist = { name, desc, isPrivate ->
            NeteaseWriteApi.createPlaylist(name, desc, isPrivate, NeteaseRepository.cookie())
        },
        apiUpdatePlaylistName = { id, name ->
            NeteaseWriteApi.updatePlaylistName(id, name, NeteaseRepository.cookie())
        },
        apiUpdatePlaylistDescription = { id, desc ->
            NeteaseWriteApi.updatePlaylistDescription(id, desc, NeteaseRepository.cookie())
        },
        apiDeletePlaylist = { id ->
            NeteaseWriteApi.deletePlaylist(id, NeteaseRepository.cookie())
        },
        apiManipulateTracks = { pid, ids, add ->
            NeteaseWriteApi.manipulatePlaylistTracks(pid, ids, add, NeteaseRepository.cookie())
        },
        apiSubscribePlaylist = { id, subscribe ->
            NeteaseWriteApi.subscribePlaylist(id, subscribe, NeteaseRepository.cookie())
        },
        apiReorderTracks = { pid, ids ->
            NeteaseWriteApi.updatePlaylistTrackOrder(pid, ids, NeteaseRepository.cookie())
        },
        apiUpdatePrivacy = { pid, isPrivate ->
            NeteaseWriteApi.updatePlaylistPrivacy(pid, isPrivate, NeteaseRepository.cookie())
        },
        onPlaylistCreated = { created ->
            val ctx = NeteaseRepository.appContext
            val record = NeteasePlaylistRecord(
                id = created.id,
                name = created.name,
                coverUrl = created.coverUrl,
                importedAt = Instant.now().toString(),
                addedToPlaylists = false,
                sourceUrl = "https://music.163.com/playlist?id=${created.id}",
                remoteTrackCount = created.trackCount,
                description = created.description,
                persistCache = true,
                privacy = if (created.privacyPrivate) 10 else 0,
                entries = emptyList(),
            )
            NeteaseRepository.updatePlaylists { list -> list.filterNot { it.id == created.id } + record }
            if (ctx != null) {
                NeteaseRepository.persistNow(ctx)
                NeteaseFeedLoader.onPlaylistCreated(
                    context = ctx,
                    playlist = DiscoverPlaylist(
                        id = created.id,
                        name = created.name,
                        subtitle = created.description,
                        coverUrl = created.coverUrl,
                        trackCount = created.trackCount,
                    ),
                    likedRemoteId = likedPlaylistRemoteId,
                )
            }
        },
        onPlaylistUpdated = { playlistId, name, description ->
            val ctx = NeteaseRepository.appContext
            var updatedRecord: NeteasePlaylistRecord? = null
            NeteaseRepository.updatePlaylists { list ->
                list.map { record ->
                    if (record.id == playlistId) {
                        record.copy(name = name, description = description).also { updatedRecord = it }
                    } else {
                        record
                    }
                }
            }
            if (ctx != null) {
                if (updatedRecord != null) NeteaseRepository.persistNow(ctx)
                NeteaseFeedLoader.onPlaylistUpdated(ctx, playlistId, name, description)
            }
        },
        onPlaylistDeleted = { playlistId ->
            val ctx = NeteaseRepository.appContext
            if (ctx != null) NeteaseFeedLoader.onPlaylistDeleted(ctx, playlistId)
            NeteaseRepository.delete(playlistId)
        },
        onTracksManipulated = { targetId, remotePid, songIds, add, isLikedTarget ->
            val ctx = NeteaseRepository.appContext
            val tracksById = pendingTrackManipulations.remove(manipulationKey(targetId, songIds))
                .orEmpty()
                .associate { it.first to it.second }
            if (isLikedTarget) {
                likeMachine.applyBatchLikedDelta(
                    songIds = songIds,
                    liked = add,
                    expectedRevision = likeMachine.currentRevision(),
                    persist = true,
                )
                NeteaseRepository.applyLikedPlaylistBatchDelta(songIds, add, tracksById, persist = true)
            }
            val nextCount = NeteaseRepository.applyPlaylistTracksDelta(
                playlistId = if (isLikedTarget) remotePid else targetId,
                songIds = songIds,
                add = add,
                tracksById = tracksById,
                persist = true,
            )
            val likedCount = if (isLikedTarget) {
                NeteaseRepository.playlistById(NeteaseRepository.LIKED_ID)?.let { rec ->
                    rec.entries.size.takeIf { it > 0 } ?: rec.remoteTrackCount
                } ?: likeMachine.likedSongIds.value.size
            } else {
                nextCount
            }
            if (ctx != null && likedCount != null) {
                NeteaseFeedLoader.onPlaylistTrackCountChanged(
                    context = ctx,
                    playlistId = targetId,
                    likedRemoteId = likedPlaylistRemoteId,
                    nextCount = likedCount,
                )
            }
        },
        onPlaylistSubscribed = { playlistId, subscribe ->
            val ctx = NeteaseRepository.appContext ?: return@NeteasePlaylistWriteStateMachine
            val card = pendingSubscribeCards.remove(playlistId)
                ?: NeteaseRepository.playlistById(playlistId)?.let { rec ->
                    DiscoverPlaylist(
                        id = rec.id,
                        name = rec.name,
                        subtitle = rec.description,
                        coverUrl = rec.coverUrl,
                        coverPath = rec.coverPath,
                        trackCount = rec.entries.size.takeIf { it > 0 } ?: rec.remoteTrackCount,
                    )
                }
                ?: DiscoverPlaylist(id = playlistId, name = playlistId)
            NeteaseFeedLoader.onPlaylistSubscribed(ctx, card, subscribe)
        },
        onTracksReordered = { playlistId, orderedIds ->
            NeteaseRepository.applyPlaylistOrderDelta(playlistId, orderedIds, persist = true)
        },
        onPrivacyUpdated = { playlistId, privacyPrivate ->
            NeteaseRepository.applyPlaylistPrivacyDelta(playlistId, privacyPrivate, persist = true)
        },
        onSessionExpired = { NeteaseRepository.markSessionExpired() },
    )

    private val pendingSubscribeCards = ConcurrentHashMap<String, DiscoverPlaylist>()
    private val pendingArtistCards = ConcurrentHashMap<String, MineArtist>()
    private val pendingAlbumCards = ConcurrentHashMap<String, MineAlbum>()

    private val artistMachine = NeteaseEntitySubscriptionStateMachine(
        hasSession = { !NeteaseRepository.cookie().isNullOrBlank() },
        currentRevision = { likeMachine.currentRevision() },
        apiSubscribe = { artistId, follow ->
            NeteaseWriteApi.subscribeArtist(artistId, follow, NeteaseRepository.cookie())
        },
        onCommitted = { artistId, follow ->
            val ctx = NeteaseRepository.appContext ?: return@NeteaseEntitySubscriptionStateMachine
            val card = pendingArtistCards.remove(artistId)
                ?: NeteaseFeedLoader.artistCardFallback(artistId)
            NeteaseFeedLoader.onArtistFollowed(ctx, card, follow)
        },
        onSessionExpired = { NeteaseRepository.markSessionExpired() },
    )

    private val albumMachine = NeteaseEntitySubscriptionStateMachine(
        hasSession = { !NeteaseRepository.cookie().isNullOrBlank() },
        currentRevision = { likeMachine.currentRevision() },
        apiSubscribe = { albumId, subscribe ->
            NeteaseWriteApi.subscribeAlbum(albumId, subscribe, NeteaseRepository.cookie())
        },
        onCommitted = { albumId, subscribe ->
            val ctx = NeteaseRepository.appContext ?: return@NeteaseEntitySubscriptionStateMachine
            val card = pendingAlbumCards.remove(albumId)
                ?: NeteaseFeedLoader.albumCardFallback(albumId)
            NeteaseFeedLoader.onAlbumSubscribed(ctx, card, subscribe)
        },
        onSessionExpired = { NeteaseRepository.markSessionExpired() },
    )

    private fun manipulationKey(playlistId: String, songIds: List<Long>): String =
        "$playlistId:${songIds.joinToString(",")}"

    val likedSongIds: StateFlow<Set<Long>> get() = likeMachine.likedSongIds

    fun isLikeListLoaded(): Boolean = likeMachine.likeListLoaded.value

    fun likedPlaylistRemoteId(): String? = likedPlaylistRemoteId

    fun updateLikedPlaylistRemoteId(id: String?) {
        val clean = id?.trim()?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return
        likedPlaylistRemoteId = clean
    }

    private suspend fun resolveLikedPlaylistRemoteId(): String? {
        likedPlaylistRemoteId?.let { return it }
        NeteaseRepository.playlistById(NeteaseRepository.LIKED_ID)?.sourceUrl
            ?.let { NeteaseClient.playlistIdFrom(it) }
            ?.let { found ->
                updateLikedPlaylistRemoteId(found)
                return found
            }
        val token = NeteaseRepository.cookie() ?: return null
        return NeteaseClient.fetchLikedPlaylistId(token).getOrNull()?.also { found ->
            updateLikedPlaylistRemoteId(found)
        }
    }

    fun isLiked(songId: Long): Boolean = likeMachine.isLiked(songId)

    fun loadFromDisk(context: Context, fallbackLikedPlaylist: NeteasePlaylistRecord?) {
        fallbackLikedPlaylist?.sourceUrl?.let { NeteaseClient.playlistIdFrom(it) }?.let(::updateLikedPlaylistRemoteId)
        val ids = runCatching {
            val file = File(context.filesDir, LIKED_IDS_FILE)
            if (!file.isFile) emptySet()
            else LibrarySyncProtocol.json.decodeFromString<List<Long>>(file.readText()).toSet()
        }.getOrDefault(emptySet())
        if (ids.isNotEmpty()) {
            likeMachine.applyCachedIds(ids)
        } else if (fallbackLikedPlaylist != null && fallbackLikedPlaylist.entries.isNotEmpty()) {
            likeMachine.applyCachedIds(fallbackLikedPlaylist.entries.mapTo(LinkedHashSet()) { it.songId })
        }
    }

    fun onLoginChanged() {
        likedPlaylistRemoteId = null
        pendingTrackManipulations.clear()
        pendingSubscribeCards.clear()
        pendingArtistCards.clear()
        pendingAlbumCards.clear()
        likeMachine.onSessionChanged()
    }

    fun resetOnLogout(context: Context) {
        likedPlaylistRemoteId = null
        pendingTrackManipulations.clear()
        pendingSubscribeCards.clear()
        pendingArtistCards.clear()
        pendingAlbumCards.clear()
        likeMachine.onSessionChanged()
        File(context.filesDir, LIKED_IDS_FILE).delete()
    }

    fun refreshLikedIds(force: Boolean = false) {
        likeMachine.refreshLikedIds(force)
    }

    suspend fun syncFromLikedPlaylist(entries: List<NeteaseEntryRecord>, expectedRevision: Long? = null) {
        val rev = expectedRevision ?: likeMachine.currentRevision()
        val ids = entries.mapTo(LinkedHashSet()) { it.songId }
        likeMachine.applySyncedLikedIds(ids, expectedRevision = rev, persist = true)
    }

    fun currentRevision(): Long = likeMachine.currentRevision()

    suspend fun setTrackLiked(
        context: Context,
        track: LibraryTrack?,
        songId: Long,
        liked: Boolean,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        return likeMachine.setTrackLiked(songId, liked, track)
    }

    suspend fun createPlaylist(
        context: Context,
        name: String,
        description: String,
        privacyPrivate: Boolean,
        initialSongs: List<Pair<Long, LibraryTrack?>> = emptyList(),
    ): Result<String> {
        NeteaseRepository.appContext = context.applicationContext
        val created = playlistMachine.createPlaylist(name, description, privacyPrivate).getOrElse {
            return Result.failure(it)
        }
        if (initialSongs.isNotEmpty()) {
            val addRes = manipulatePlaylistTracks(context, created.id, initialSongs, add = true)
            if (addRes.isFailure) {
                return Result.failure(addRes.exceptionOrNull()!!)
            }
        }
        return Result.success(created.id)
    }

    suspend fun updatePlaylist(
        context: Context,
        playlistId: String,
        name: String,
        description: String,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        val record = NeteaseRepository.playlistById(playlistId)
        val mineCard = NeteaseRepository.mine.value.created.firstOrNull { it.id == playlistId }
        return playlistMachine.updatePlaylist(
            playlistId = playlistId,
            name = name,
            description = description,
            currentName = record?.name ?: mineCard?.name,
            currentDescription = record?.description,
        )
    }

    suspend fun deletePlaylist(
        context: Context,
        playlistId: String,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        return playlistMachine.deletePlaylist(playlistId)
    }

    suspend fun manipulatePlaylistTracks(
        context: Context,
        playlistId: String,
        tracks: List<Pair<Long, LibraryTrack?>>,
        add: Boolean,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        val distinctIds = tracks.map { it.first }.distinct()
        if (distinctIds.isEmpty()) return Result.success(Unit)
        val key = manipulationKey(playlistId, distinctIds)
        pendingTrackManipulations[key] = tracks
        val res = playlistMachine.manipulateTracks(
            playlistId = playlistId,
            songIds = distinctIds,
            add = add,
            knownLikedRemoteId = likedPlaylistRemoteId,
        )
        if (res.isFailure) pendingTrackManipulations.remove(key)
        return res
    }

    suspend fun setPlaylistSubscribed(
        context: Context,
        playlist: DiscoverPlaylist?,
        playlistId: String,
        subscribe: Boolean,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        if (playlist != null) {
            pendingSubscribeCards[playlistId] = playlist
        }
        val res = playlistMachine.setSubscribed(playlistId, subscribe)
        if (res.isFailure) pendingSubscribeCards.remove(playlistId)
        return res
    }

    suspend fun setArtistFollowed(
        context: Context,
        artist: MineArtist?,
        artistId: String,
        follow: Boolean,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        if (artist != null) {
            pendingArtistCards[artistId] = artist
        }
        val res = artistMachine.setSubscribed(artistId, follow)
        if (res.isFailure) pendingArtistCards.remove(artistId)
        return res
    }

    suspend fun setAlbumSubscribed(
        context: Context,
        album: MineAlbum?,
        albumId: String,
        subscribe: Boolean,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        if (album != null) {
            pendingAlbumCards[albumId] = album
        }
        val res = albumMachine.setSubscribed(albumId, subscribe)
        if (res.isFailure) pendingAlbumCards.remove(albumId)
        return res
    }

    suspend fun reorderPlaylistTracks(
        context: Context,
        playlistId: String,
        orderedSongIds: List<Long>,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        return playlistMachine.reorderTracks(playlistId, orderedSongIds)
    }

    suspend fun updatePlaylistPrivacy(
        context: Context,
        playlistId: String,
        privacyPrivate: Boolean,
    ): Result<Unit> {
        NeteaseRepository.appContext = context.applicationContext
        return playlistMachine.updatePrivacy(playlistId, privacyPrivate)
    }

    private fun writeLikedIds(context: Context, ids: Set<Long>) {
        runCatching {
            File(context.filesDir, LIKED_IDS_FILE).writeTextAtomically(
                LibrarySyncProtocol.json.encodeToString(ids.toList()),
            )
        }
    }
}
