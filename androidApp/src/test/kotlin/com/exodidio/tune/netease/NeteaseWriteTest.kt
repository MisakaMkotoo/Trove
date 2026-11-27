package com.exodidio.tune.netease

import com.exodidio.tune.player.PlaybackEndReason
import com.exodidio.tune.sync.LibraryTrack
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NeteaseWriteTest {

    @Test
    fun parseLikeListResponseExtractsLongIds() {
        val payload = buildJsonObject {
            put("code", 200)
            put(
                "ids",
                buildJsonArray {
                    add(JsonPrimitive(101L))
                    add(JsonPrimitive(202L))
                },
            )
        }
        assertEquals(setOf(101L, 202L), NeteaseWriteApi.parseLikeListResponse(payload))
    }

    @Test
    fun parseLikeTrackResponseHandlesLoginAndServerErrors() {
        val ok = buildJsonObject {
            put("code", 200)
            put("playlistId", 999L)
        }
        assertEquals(999L, NeteaseWriteApi.parseLikeTrackResponse(ok))

        val expired = buildJsonObject {
            put("code", 301)
            put("message", "Need login")
        }
        val loginError = runCatching { NeteaseWriteApi.parseLikeTrackResponse(expired) }.exceptionOrNull()
        assertTrue(loginError is NeteaseWriteException.LoginRequired)

        val serverErr = buildJsonObject {
            put("code", 400)
            put("msg", "Bad request")
        }
        val apiError = runCatching { NeteaseWriteApi.parseLikeTrackResponse(serverErr) }.exceptionOrNull()
        assertTrue(apiError is NeteaseWriteException.ServerError)
        assertEquals(400, (apiError as NeteaseWriteException.ServerError).code)
    }

    @Test
    fun parseCreatePlaylistResponseHandlesNestedAndFallbackPayload() {
        val nested = buildJsonObject {
            put("code", 200)
            put(
                "playlist",
                buildJsonObject {
                    put("id", 54321L)
                    put("name", "Night Drive")
                    put("coverImgUrl", "https://example.com/c.jpg")
                    put("privacy", 10)
                    put("trackCount", 0)
                    put("creator", buildJsonObject { put("userId", 777L) })
                },
            )
        }
        val created = NeteaseWriteApi.parseCreatePlaylistResponse(nested)
        assertEquals("54321", created.id)
        assertEquals("Night Drive", created.name)
        assertEquals("https://example.com/c.jpg", created.coverUrl)
        assertTrue(created.privacyPrivate)
        assertEquals("777", created.creatorId)

        val fallback = buildJsonObject {
            put("code", 200)
            put("id", 88888L)
        }
        val createdFallback = NeteaseWriteApi.parseCreatePlaylistResponse(fallback, fallbackName = "My List", fallbackPrivacyPrivate = false)
        assertEquals("88888", createdFallback.id)
        assertEquals("My List", createdFallback.name)
        assertFalse(createdFallback.privacyPrivate)
    }

    @Test
    fun parseManipulateTracksResponseAccepts502OnlyForAdd() {
        val duplicatePayload = buildJsonObject {
            put("code", 502)
            put("message", "歌曲已存在")
        }
        assertEquals(502, NeteaseWriteApi.parseManipulateTracksResponse(duplicatePayload, add = true))

        val delErr = runCatching {
            NeteaseWriteApi.parseManipulateTracksResponse(duplicatePayload, add = false)
        }.exceptionOrNull()
        assertTrue(delErr is NeteaseWriteException.ServerError)
        assertEquals(502, (delErr as NeteaseWriteException.ServerError).code)
    }

    @Test
    fun parseSimpleWriteResponseCoversDeleteAndSubscribe() {
        NeteaseWriteApi.parseSimpleWriteResponse(buildJsonObject { put("code", 200) })

        val err = runCatching {
            NeteaseWriteApi.parseSimpleWriteResponse(buildJsonObject { put("code", 403); put("msg", "Forbidden") })
        }.exceptionOrNull()
        assertTrue(err is NeteaseWriteException.ServerError)
        assertEquals(403, (err as NeteaseWriteException.ServerError).code)
    }

    @Test
    fun resolvePlaylistPermissionsCoversAllCategories() {
        assertEquals(
            NeteasePlaylistPermission.Liked,
            resolveNeteasePlaylistPermission(
                playlistId = NeteaseRepository.LIKED_ID,
                likedRemoteId = "999",
                isDynamicOrVirtual = true,
                isRadar = false,
                isOwnedCreated = false,
            ),
        )
        assertEquals(
            NeteasePlaylistPermission.Liked,
            resolveNeteasePlaylistPermission(
                playlistId = "999",
                likedRemoteId = "999",
                isDynamicOrVirtual = false,
                isRadar = false,
                isOwnedCreated = true,
            ),
        )
        assertEquals(
            NeteasePlaylistPermission.ReadOnlyDynamic,
            resolveNeteasePlaylistPermission(
                playlistId = "3136952023",
                likedRemoteId = "999",
                isDynamicOrVirtual = false,
                isRadar = true,
                isOwnedCreated = false,
            ),
        )
        assertEquals(
            NeteasePlaylistPermission.OwnedEditable,
            resolveNeteasePlaylistPermission(
                playlistId = "123456",
                likedRemoteId = "999",
                isDynamicOrVirtual = false,
                isRadar = false,
                isOwnedCreated = true,
            ),
        )
        assertEquals(
            NeteasePlaylistPermission.ForeignSubscribable,
            resolveNeteasePlaylistPermission(
                playlistId = "654321",
                likedRemoteId = "999",
                isDynamicOrVirtual = false,
                isRadar = false,
                isOwnedCreated = false,
            ),
        )
    }

    @Test
    fun optimisticLikeCommitsAndRollsBackOnlyFailedSong() = runTest {
        val gate = CompletableDeferred<Result<Long?>>()
        val persisted = mutableListOf<Set<Long>>()
        var expiredCalled = false

        val machine = NeteaseLikeStateMachine(
            scope = backgroundScope,
            hasSession = { true },
            resolveUid = { "123" },
            fetchLikeList = { Result.success(setOf(10L)) },
            apiLikeTrack = { songId, _ ->
                if (songId == 20L) gate.await() else Result.success(1L)
            },
            persistLikedIds = { persisted += it },
            onSessionExpired = { expiredCalled = true },
        )

        machine.refreshLikedIdsSuspend(force = true)
        assertEquals(setOf(10L), machine.likedSongIds.value)

        // Start failing like for song 20 and succeeding like for song 30 concurrently.
        val deferred20 = async { machine.setTrackLiked(20L, true, LibraryTrack(id = "netease:20")) }
        val deferred30 = async { machine.setTrackLiked(30L, true, LibraryTrack(id = "netease:30")) }
        advanceUntilIdle()

        assertTrue(20L in machine.likedSongIds.value)
        assertTrue(30L in machine.likedSongIds.value)

        gate.complete(Result.failure(NeteaseWriteException.LoginRequired("expired")))
        val result20 = deferred20.await()
        val result30 = deferred30.await()

        assertTrue(result20.isFailure)
        assertTrue(result30.isSuccess)
        assertTrue(expiredCalled)
        assertEquals(setOf(10L, 30L), machine.likedSongIds.value)
    }

    @Test
    fun concurrentLikeListRefreshDoesNotOverwriteNewerLocalLike() = runTest {
        val refreshGate = CompletableDeferred<Result<Set<Long>>>()
        val machineScope = CoroutineScope(coroutineContext + SupervisorJob())
        val machine = NeteaseLikeStateMachine(
            scope = machineScope,
            hasSession = { true },
            resolveUid = { "123" },
            fetchLikeList = { refreshGate.await() },
            apiLikeTrack = { _, _ -> Result.success(1L) },
        )

        val refreshJob = async { machine.refreshLikedIdsSuspend(force = true) }
        advanceUntilIdle()

        // While refresh is in-flight with stale snapshot {1L}, user likes 55L.
        assertTrue(machine.setTrackLiked(55L, true, null).isSuccess)
        assertEquals(setOf(55L), machine.likedSongIds.value)

        // Stale server response arrives saying {1L} (before 55L mutation).
        refreshGate.complete(Result.success(setOf(1L)))
        refreshJob.await()

        assertEquals(setOf(1L, 55L), machine.likedSongIds.value)
        machineScope.cancel()
    }

    @Test
    fun accountSwitchDiscardsInFlightLikeAndRefresh() = runTest {
        val gate = CompletableDeferred<Result<Long?>>()
        val machine = NeteaseLikeStateMachine(
            scope = backgroundScope,
            hasSession = { true },
            resolveUid = { "123" },
            fetchLikeList = { Result.success(setOf(1L)) },
            apiLikeTrack = { _, _ -> gate.await() },
        )
        machine.applySyncedLikedIds(setOf(1L), persist = false)

        val likeJob = async { machine.setTrackLiked(88L, true, null) }
        advanceUntilIdle()
        assertTrue(88L in machine.likedSongIds.value)

        // Switch / logout resets state and advances accountRevision.
        machine.onSessionChanged()
        assertEquals(emptySet<Long>(), machine.likedSongIds.value)

        gate.complete(Result.success(99L))
        val res = likeJob.await()
        assertTrue(res.isFailure)
        assertFalse(88L in machine.likedSongIds.value)
    }

    @Test
    fun cancellationTriggersBackgroundReconcile() = runTest {
        val apiStarted = CompletableDeferred<Unit>()
        val apiGate = CompletableDeferred<Result<Long?>>()
        var fetchCalls = 0
        val machineScope = CoroutineScope(coroutineContext + SupervisorJob())
        val machine = NeteaseLikeStateMachine(
            scope = machineScope,
            hasSession = { true },
            resolveUid = { "123" },
            fetchLikeList = {
                fetchCalls += 1
                Result.success(setOf(77L))
            },
            apiLikeTrack = { _, _ ->
                apiStarted.complete(Unit)
                apiGate.await()
            },
        )

        val job = async { machine.setTrackLiked(77L, true, null) }
        apiStarted.await()
        job.cancel(CancellationException("caller left"))
        advanceUntilIdle()

        assertEquals(1, fetchCalls)
        assertEquals(setOf(77L), machine.likedSongIds.value)
        machineScope.cancel()
    }

    @Test
    fun playlistStateMachineUpdatesOnlyChangedFieldsAndSerializesPerPlaylist() = runTest {
        val revision = AtomicLong(1L)
        var nameCalls = 0
        var descCalls = 0
        var updatedName = ""
        var updatedDesc = ""
        val activeForPlaylist = AtomicInteger(0)
        var maxConcurrent = 0
        val gate = CompletableDeferred<Unit>()
        val manipulatedCalls = mutableListOf<Triple<String, List<Long>, Boolean>>()

        val machine = NeteasePlaylistWriteStateMachine(
            hasSession = { true },
            currentRevision = { revision.get() },
            resolveLikedRemoteId = { "7777" },
            apiCreatePlaylist = { name, desc, priv ->
                Result.success(CreatedNeteasePlaylist(id = "100", name = name, description = desc, privacyPrivate = priv))
            },
            apiUpdatePlaylistName = { _, _ ->
                nameCalls += 1
                Result.success(Unit)
            },
            apiUpdatePlaylistDescription = { _, _ ->
                descCalls += 1
                Result.success(Unit)
            },
            apiDeletePlaylist = { Result.success(Unit) },
            apiManipulateTracks = { pid, ids, add ->
                val active = activeForPlaylist.incrementAndGet()
                if (active > maxConcurrent) maxConcurrent = active
                if (ids.contains(1L)) gate.await()
                manipulatedCalls += Triple(pid, ids, add)
                activeForPlaylist.decrementAndGet()
                Result.success(Unit)
            },
            apiSubscribePlaylist = { _, _ -> Result.success(Unit) },
            onPlaylistUpdated = { _, name, desc ->
                updatedName = name
                updatedDesc = desc
            },
        )

        // Only description changed -> name endpoint must NOT be called.
        assertTrue(
            machine.updatePlaylist(
                playlistId = "100",
                name = "Focus",
                description = "Deep work",
                currentName = "Focus",
                currentDescription = "",
            ).isSuccess,
        )
        assertEquals(0, nameCalls)
        assertEquals(1, descCalls)
        assertEquals("Focus", updatedName)
        assertEquals("Deep work", updatedDesc)

        // Concurrent manipulations on the same playlist (via LIKED_ID and remote 7777) must serialize.
        val job1 = async { machine.manipulateTracks(NeteaseRepository.LIKED_ID, listOf(1L), add = true, knownLikedRemoteId = "7777") }
        val job2 = async { machine.manipulateTracks("7777", listOf(2L), add = false, knownLikedRemoteId = "7777") }
        advanceUntilIdle()
        gate.complete(Unit)
        assertTrue(job1.await().isSuccess)
        assertTrue(job2.await().isSuccess)
        assertEquals(1, maxConcurrent)
        assertEquals(
            listOf(Triple("7777", listOf(1L), true), Triple("7777", listOf(2L), false)),
            manipulatedCalls,
        )
    }

    @Test
    fun playlistStateMachineDiscardsResultAfterAccountSwitch() = runTest {
        val revision = AtomicLong(1L)
        val createGate = CompletableDeferred<Result<CreatedNeteasePlaylist>>()
        var callbackInvoked = false
        val machine = NeteasePlaylistWriteStateMachine(
            hasSession = { true },
            currentRevision = { revision.get() },
            resolveLikedRemoteId = { "7777" },
            apiCreatePlaylist = { _, _, _ -> createGate.await() },
            apiUpdatePlaylistName = { _, _ -> Result.success(Unit) },
            apiUpdatePlaylistDescription = { _, _ -> Result.success(Unit) },
            apiDeletePlaylist = { Result.success(Unit) },
            apiManipulateTracks = { _, _, _ -> Result.success(Unit) },
            apiSubscribePlaylist = { _, _ -> Result.success(Unit) },
            onPlaylistCreated = { callbackInvoked = true },
        )

        val job = async { machine.createPlaylist("New", "", false) }
        advanceUntilIdle()
        revision.incrementAndGet()
        createGate.complete(Result.success(CreatedNeteasePlaylist(id = "200", name = "New")))

        val result = job.await()
        assertTrue(result.isFailure)
        assertFalse(callbackInvoked)
    }

    @Test
    fun eapiFormEncodingKeepsJsonStringParametersAsPrimitives() {
        val payload = NeteaseWireTransport.formToEapiJsonObject(
            mapOf(
                "c" to """[{"id":186016},{"id":186017}]""",
                "ids" to "[186016]",
                "trackIds" to "[101,202]",
                "n" to "100000",
            ),
        )
        assertTrue(payload["c"] is JsonPrimitive)
        assertTrue((payload["c"] as JsonPrimitive).isString)
        assertEquals("""[{"id":186016},{"id":186017}]""", (payload["c"] as JsonPrimitive).content)
        assertTrue(payload["ids"] is JsonPrimitive)
        assertTrue(payload["trackIds"] is JsonPrimitive)
    }

    @Test
    fun interfaceCookieHeaderIncludesBaselineAndPreservesUserTokens() {
        val cookieHeader = NeteaseWireTransport.buildInterfaceCookieHeader("MUSIC_U=user_token; __csrf=user_csrf")
        assertTrue(cookieHeader.contains("MUSIC_U=user_token"))
        assertTrue(cookieHeader.contains("__csrf=user_csrf"))
        assertTrue(cookieHeader.contains("URS_APPID="))
        assertTrue(cookieHeader.contains("NMDI="))
        assertTrue(cookieHeader.contains("_ntes_nuid="))
        assertTrue(cookieHeader.contains("WNMCID="))
    }

    @Test
    fun likeTrackFallsBackFromRiskControlToWeapiAndLikedPlaylist() = runTest {
        var weapiCalls = 0
        var playlistCalls = 0

        // Primary succeeds -> no fallbacks invoked.
        val direct = NeteaseWriteApi.executeLikeTrackWithFallback(
            songId = 1L,
            like = true,
            fallbackPlaylistId = "999",
            primaryLike = { 999L },
            weapiLike = { weapiCalls++; 999L },
            playlistFallback = { playlistCalls++; 999L },
        )
        assertEquals(999L, direct.getOrNull())
        assertEquals(0, weapiCalls)
        assertEquals(0, playlistCalls)

        // Primary -460, weapi succeeds -> playlist fallback not called.
        val viaWeapi = NeteaseWriteApi.executeLikeTrackWithFallback(
            songId = 2L,
            like = true,
            fallbackPlaylistId = "999",
            primaryLike = { throw NeteaseWriteException.ServerError(-460, "risk") },
            weapiLike = { weapiCalls++; 999L },
            playlistFallback = { playlistCalls++; 999L },
        )
        assertEquals(999L, viaWeapi.getOrNull())
        assertEquals(1, weapiCalls)
        assertEquals(0, playlistCalls)

        // Primary & weapi both -460 -> falls back to liked playlist manipulate tracks.
        val viaPlaylist = NeteaseWriteApi.executeLikeTrackWithFallback(
            songId = 3L,
            like = false,
            fallbackPlaylistId = "999",
            primaryLike = { throw NeteaseWriteException.ServerError(-460, "risk") },
            weapiLike = { weapiCalls++; throw NeteaseWriteException.ServerError(-460, "risk") },
            playlistFallback = { pid -> playlistCalls++; pid.toLong() },
        )
        assertEquals(999L, viaPlaylist.getOrNull())
        assertEquals(2, weapiCalls)
        assertEquals(1, playlistCalls)

        // LoginRequired (301/302) must NOT fall back.
        val loginErr = NeteaseWriteApi.executeLikeTrackWithFallback(
            songId = 4L,
            like = true,
            fallbackPlaylistId = "999",
            primaryLike = { throw NeteaseWriteException.LoginRequired("expired") },
            weapiLike = { weapiCalls++; 999L },
            playlistFallback = { playlistCalls++; 999L },
        )
        assertTrue(loginErr.exceptionOrNull() is NeteaseWriteException.LoginRequired)
        assertEquals(2, weapiCalls)
        assertEquals(1, playlistCalls)

        // No fallback playlist ID -> surfaces -460 failure.
        val noFallback = NeteaseWriteApi.executeLikeTrackWithFallback(
            songId = 5L,
            like = true,
            fallbackPlaylistId = null,
            primaryLike = { throw NeteaseWriteException.ServerError(-460, "risk") },
            weapiLike = { throw NeteaseWriteException.ServerError(-460, "risk") },
            playlistFallback = { playlistCalls++; 999L },
        )
        assertEquals(-460, (noFallback.exceptionOrNull() as? NeteaseWriteException.ServerError)?.code)
        assertEquals(1, playlistCalls)

        // Primary 405 (rate limit) -> falls back directly to liked playlist without calling weapi.
        val weapiBefore405 = weapiCalls
        val via405Direct = NeteaseWriteApi.executeLikeTrackWithFallback(
            songId = 6L,
            like = false,
            fallbackPlaylistId = "999",
            primaryLike = { throw NeteaseWriteException.ServerError(405, "操作频繁") },
            weapiLike = { weapiCalls++; 999L },
            playlistFallback = { pid -> playlistCalls++; pid.toLong() },
        )
        assertEquals(999L, via405Direct.getOrNull())
        assertEquals(weapiBefore405, weapiCalls)
        assertEquals(2, playlistCalls)

        // Primary -460 then weapi 405 -> falls back to liked playlist.
        val viaWeapi405 = NeteaseWriteApi.executeLikeTrackWithFallback(
            songId = 7L,
            like = true,
            fallbackPlaylistId = "999",
            primaryLike = { throw NeteaseWriteException.ServerError(-460, "risk") },
            weapiLike = { weapiCalls++; throw NeteaseWriteException.ServerError(405, "操作频繁") },
            playlistFallback = { pid -> playlistCalls++; pid.toLong() },
        )
        assertEquals(999L, viaWeapi405.getOrNull())
        assertEquals(weapiBefore405 + 1, weapiCalls)
        assertEquals(3, playlistCalls)

        // Primary 405 without fallback playlist ID -> surfaces 405 failure.
        val noFallback405 = NeteaseWriteApi.executeLikeTrackWithFallback(
            songId = 8L,
            like = true,
            fallbackPlaylistId = null,
            primaryLike = { throw NeteaseWriteException.ServerError(405, "操作频繁") },
            weapiLike = { weapiCalls++; 999L },
            playlistFallback = { playlistCalls++; 999L },
        )
        assertEquals(405, (noFallback405.exceptionOrNull() as? NeteaseWriteException.ServerError)?.code)
        assertEquals(3, playlistCalls)
    }

    @Test
    fun needsPlaylistHydrationDetectsTruncatedLegacyCachesAndSkipsSnapshots() {
        val sampleEntry = NeteaseEntryRecord(songId = 1L, title = "Song", artists = "Artist", album = "Album")
        val legacyLiked = NeteasePlaylistRecord(
            id = NeteaseRepository.LIKED_ID,
            name = "我喜欢的音乐",
            remoteTrackCount = 1000,
            fullyHydrated = false,
            entries = listOf(sampleEntry),
        )
        assertTrue(NeteaseRepository.needsPlaylistHydration(legacyLiked))

        val hydratedLiked = legacyLiked.copy(remoteTrackCount = 2042, fullyHydrated = true)
        assertFalse(NeteaseRepository.needsPlaylistHydration(hydratedLiked))

        val legacyNumeric = NeteasePlaylistRecord(
            id = "8066647508",
            name = "Big Playlist",
            remoteTrackCount = 0,
            fullyHydrated = false,
            entries = listOf(sampleEntry),
        )
        assertTrue(NeteaseRepository.needsPlaylistHydration(legacyNumeric))
        assertFalse(NeteaseRepository.needsPlaylistHydration(legacyNumeric.copy(fullyHydrated = true)))

        val snapshot = NeteasePlaylistRecord(
            id = "snapshot:daily:123",
            name = "每日推荐",
            fullyHydrated = false,
            entries = listOf(sampleEntry),
        )
        assertFalse(NeteaseRepository.needsPlaylistHydration(snapshot))
    }

    @Test
    fun parseArtistDetailAndPagesWorkCleanly() {
        val detailRoot = buildJsonObject {
            put("code", 200)
            put(
                "data",
                buildJsonObject {
                    put(
                        "artist",
                        buildJsonObject {
                            put("id", 6452L)
                            put("name", "周杰伦")
                            put("avatar", "https://example.com/jay.jpg")
                            put("briefDesc", "流行歌手")
                            put("musicSize", 500)
                            put("albumSize", 20)
                            put("mvSize", 100)
                            put("alias", buildJsonArray { add(JsonPrimitive("Jay Chou")) })
                        },
                    )
                },
            )
        }
        val detail = NeteaseClient.parseArtistDetail(detailRoot, "6452", followedOverride = true)
        assertEquals("6452", detail.id)
        assertEquals("周杰伦", detail.name)
        assertEquals("https://example.com/jay.jpg", detail.coverUrl)
        assertEquals(500, detail.musicCount)
        assertEquals(20, detail.albumCount)
        assertEquals(listOf("Jay Chou"), detail.alias)
        assertEquals(true, detail.followed)

        val songsRoot = buildJsonObject {
            put("code", 200)
            put("total", 120)
            put("more", true)
            put(
                "songs",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 186016L)
                            put("name", "晴天")
                            put("dt", 269000L)
                            put("ar", buildJsonArray { add(buildJsonObject { put("id", 6452L); put("name", "周杰伦") }) })
                            put("al", buildJsonObject { put("id", 18905L); put("name", "叶惠美"); put("picUrl", "https://example.com/al.jpg") })
                        },
                    )
                },
            )
        }
        val songsPage = NeteaseClient.parseArtistSongsPage(songsRoot)
        assertTrue(songsPage.hasMore)
        assertEquals(120, songsPage.total)
        assertEquals(1, songsPage.songs.size)
        assertEquals("晴天", songsPage.songs.first().title)

        val albumsRoot = buildJsonObject {
            put("code", 200)
            put("more", true)
            put(
                "hotAlbums",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 18905L)
                            put("name", "叶惠美")
                            put("size", 11)
                            put("publishTime", 1059667200000L)
                            put("picUrl", "https://example.com/yhm.jpg")
                            put("artist", buildJsonObject { put("name", "周杰伦") })
                        },
                    )
                },
            )
        }
        val albumsPage = NeteaseClient.parseArtistAlbumsPage(albumsRoot)
        assertTrue(albumsPage.hasMore)
        assertEquals(1, albumsPage.albums.size)
        assertEquals("叶惠美", albumsPage.albums.first().name)
        assertEquals(11, albumsPage.albums.first().trackCount)
    }

    @Test
    fun parseSubscribedAlbumsAndStatusWorkCleanly() {
        val sublistRoot = buildJsonObject {
            put("code", 200)
            put("hasMore", false)
            put("count", 1)
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 18905L)
                            put("name", "叶惠美")
                            put("size", 11)
                            put("subTime", 1700000000000L)
                            put("picUrl", "https://example.com/yhm.jpg")
                            put("artists", buildJsonArray { add(buildJsonObject { put("name", "周杰伦") }) })
                        },
                    )
                },
            )
        }
        val page = NeteaseClient.parseSubscribedAlbumsPage(sublistRoot)
        assertFalse(page.hasMore)
        assertEquals(1, page.total)
        assertEquals("18905", page.albums.first().id)
        assertEquals("周杰伦", page.albums.first().artistName)

        assertTrue(NeteaseClient.parseAlbumSubscribed(buildJsonObject { put("code", 200); put("isSub", true) }))
        assertFalse(NeteaseClient.parseAlbumSubscribed(buildJsonObject { put("code", 200); put("isSub", false) }))
    }

    @Test
    fun parseRecentPlayedAndUserPlayRecordsWorkCleanly() {
        val recentRoot = buildJsonObject {
            put("code", 200)
            put(
                "data",
                buildJsonObject {
                    put(
                        "list",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("playTime", 1000L)
                                    put(
                                        "data",
                                        buildJsonObject {
                                            put("id", 10L)
                                            put("name", "Song A")
                                            put("ar", buildJsonArray { add(buildJsonObject { put("name", "Artist A") }) })
                                            put("al", buildJsonObject { put("name", "Album A") })
                                        },
                                    )
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("playTime", 2000L)
                                    put(
                                        "data",
                                        buildJsonObject {
                                            put("id", 10L)
                                            put("name", "Song A")
                                            put("ar", buildJsonArray { add(buildJsonObject { put("name", "Artist A") }) })
                                            put("al", buildJsonObject { put("name", "Album A") })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val recent = NeteaseClient.parseRecentPlayedSongs(recentRoot)
        assertEquals(1, recent.size)
        assertEquals(2000L, recent.first().playedAtMs)

        val rankRoot = buildJsonObject {
            put("code", 200)
            put(
                "weekData",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("playCount", 15)
                            put("score", 100)
                            put(
                                "song",
                                buildJsonObject {
                                    put("id", 20L)
                                    put("name", "Song B")
                                    put("ar", buildJsonArray { add(buildJsonObject { put("name", "Artist B") }) })
                                    put("al", buildJsonObject { put("name", "Album B") })
                                },
                            )
                        },
                    )
                },
            )
        }
        val week = NeteaseClient.parseUserPlayRecords(rankRoot, allTime = false)
        assertEquals(1, week.size)
        assertEquals(15, week.first().playCount)
        assertEquals(100, week.first().score)

        val privateRoot = buildJsonObject { put("code", -2) }
        val err = runCatching { NeteaseClient.parseUserPlayRecords(privateRoot, allTime = true) }.exceptionOrNull()
        assertTrue(err is NeteaseWriteException.ServerError)
        assertEquals(-2, (err as NeteaseWriteException.ServerError).code)
    }

    @Test
    fun buildPlaybackWeblogLogsJsonFormatsStartAndEndCorrectly() {
        val startJson = NeteaseWriteApi.buildPlaybackWeblogLogsJson(
            songId = 186016L,
            start = true,
            startLogTimeMs = 1700000000000L,
            logTimeMs = 1700000000000L,
            sourceId = "999",
            source = "playlist",
        )
        assertTrue(startJson.contains("\"action\":\"startplay\""))
        assertTrue(startJson.contains("\"id\":186016"))
        assertTrue(startJson.contains("\"sourceId\":\"999\""))
        assertFalse(startJson.contains("\"end\""))

        val endJson = NeteaseWriteApi.buildPlaybackWeblogLogsJson(
            songId = 186016L,
            start = false,
            startLogTimeMs = 1700000000000L,
            logTimeMs = 1700000120000L,
            playedSeconds = 120L,
            endReason = "playend",
            sourceId = "",
            source = "list",
        )
        assertTrue(endJson.contains("\"action\":\"play\""))
        assertTrue(endJson.contains("\"end\":\"playend\""))
        assertTrue(endJson.contains("\"time\":120"))
        assertTrue(endJson.contains("\"content\":\"id=186016\""))
    }

    @Test
    fun entitySubscriptionStateMachineSerializesAndDiscardsStaleAccount() = runTest {
        val revision = AtomicLong(1L)
        val active = AtomicInteger(0)
        var maxConcurrent = 0
        val gate = CompletableDeferred<Unit>()
        val committed = mutableListOf<Pair<String, Boolean>>()

        val machine = NeteaseEntitySubscriptionStateMachine(
            hasSession = { true },
            currentRevision = { revision.get() },
            apiSubscribe = { _, sub ->
                val cur = active.incrementAndGet()
                if (cur > maxConcurrent) maxConcurrent = cur
                if (sub) gate.await()
                active.decrementAndGet()
                Result.success(Unit)
            },
            onCommitted = { id, sub -> committed += id to sub },
        )

        val j1 = async { machine.setSubscribed("6452", true) }
        val j2 = async { machine.setSubscribed("6452", false) }
        advanceUntilIdle()
        gate.complete(Unit)
        assertTrue(j1.await().isSuccess)
        assertTrue(j2.await().isSuccess)
        assertEquals(1, maxConcurrent)
        assertEquals(listOf("6452" to true, "6452" to false), committed)

        // Account switch while in flight discards callback.
        val gate2 = CompletableDeferred<Unit>()
        val machine2 = NeteaseEntitySubscriptionStateMachine(
            hasSession = { true },
            currentRevision = { revision.get() },
            apiSubscribe = { _, _ -> gate2.await(); Result.success(Unit) },
            onCommitted = { id, sub -> committed += id to sub },
        )
        val stale = async { machine2.setSubscribed("999", true) }
        advanceUntilIdle()
        revision.incrementAndGet()
        gate2.complete(Unit)
        assertTrue(stale.await().isFailure)
        assertEquals(2, committed.size)
    }

    @Test
    fun resolveWeblogSourceAndEndReasonMapping() {
        assertEquals("daily" to "", resolveWeblogSource(NeteaseRepository.DAILY_ID, "123"))
        assertEquals("list" to "987654", resolveWeblogSource(NeteaseRepository.LIKED_ID, "987654"))
        assertEquals("album" to "555", resolveWeblogSource("album:555", null))
        assertEquals("artist" to "6452", resolveWeblogSource("artist:6452", null))
        assertEquals("list" to "3136952023", resolveWeblogSource("3136952023", null))
        assertEquals("list" to "", resolveWeblogSource(null, null))

        assertEquals("playend", mapPlaybackEndReasonToWeblog(PlaybackEndReason.COMPLETED))
        assertEquals("ui", mapPlaybackEndReasonToWeblog(PlaybackEndReason.SKIPPED))
        assertEquals("ui", mapPlaybackEndReasonToWeblog(PlaybackEndReason.STOPPED))
        assertEquals("ui", mapPlaybackEndReasonToWeblog(null))
    }
}
