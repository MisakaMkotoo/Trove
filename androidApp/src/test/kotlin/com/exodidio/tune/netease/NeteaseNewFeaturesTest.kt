package com.exodidio.tune.netease

import com.exodidio.tune.settings.DownloadQuality
import com.exodidio.tune.settings.DownloadSettingsSnapshot
import com.exodidio.tune.sync.LibraryAlbum
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.tuneSizedUrl
import com.exodidio.tune.ui.screens.filterArtistDetailAlbums
import com.exodidio.tune.ui.screens.filterMineSavedAlbums
import com.exodidio.tune.ui.screens.filterNeteaseArtistAlbums
import com.exodidio.tune.ui.screens.filterNeteaseArtistTracks
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NeteaseNewFeaturesTest {

    @Test
    fun parseCloudDiskPageExtractsSongsAndQuota() {
        val root = buildJsonObject {
            put("code", 200)
            put("count", 42)
            put("size", 104857600L)
            put("maxSize", 107374182400L)
            put("hasMore", true)
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("fileName", "test_track.flac")
                            put("fileSize", 31457280L)
                            put("addTime", 1700000000000L)
                            put(
                                "simpleSong",
                                buildJsonObject {
                                    put("id", 987654L)
                                    put("name", "云盘无损")
                                    put("dt", 240000L)
                                    put("ar", buildJsonArray {
                                        add(buildJsonObject { put("id", 11L); put("name", "测试歌手") })
                                    })
                                    put("al", buildJsonObject {
                                        put("id", 22L)
                                        put("name", "测试专辑")
                                        put("picUrl", "https://example.com/cd.jpg")
                                    })
                                },
                            )
                        },
                    )
                    add(
                        buildJsonObject {
                            put("songId", 112233L)
                            put("fileName", "fallback.mp3")
                            put("artist", "独立音乐人")
                            put("album", "Demo")
                        },
                    )
                },
            )
        }

        val page = NeteaseClient.parseCloudDiskPage(root)
        assertEquals(42, page.count)
        assertEquals(104857600L, page.sizeBytes)
        assertEquals(107374182400L, page.maxSizeBytes)
        assertTrue(page.hasMore)
        assertEquals(2, page.tracks.size)
        assertEquals(987654L, page.tracks[0].song.songId)
        assertEquals("云盘无损", page.tracks[0].song.title)
        assertEquals("test_track.flac", page.tracks[0].fileName)
        assertEquals(112233L, page.tracks[1].song.songId)
        assertEquals("fallback", page.tracks[1].song.title)
    }

    @Test
    fun parsePlaylistCatlistAndPlaylistsPages() {
        val catRoot = buildJsonObject {
            put("code", 200)
            put("all", buildJsonObject { put("name", "全部歌单") })
            put(
                "sub",
                buildJsonArray {
                    add(buildJsonObject { put("name", "华语"); put("category", 0); put("hot", true) })
                    add(buildJsonObject { put("name", "轻音乐"); put("category", 2); put("hot", false) })
                },
            )
        }
        val catlist = NeteaseClient.parsePlaylistCatlist(catRoot)
        assertEquals("全部歌单", catlist.allCategory)
        assertEquals(2, catlist.categories.size)
        assertTrue(catlist.categories[0].hot)
        assertEquals("轻音乐", catlist.categories[1].name)

        val topRoot = buildJsonObject {
            put("code", 200)
            put("total", 500)
            put("more", true)
            put(
                "playlists",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 10001L)
                            put("name", "夜间编码")
                            put("coverImgUrl", "https://example.com/pl.jpg")
                            put("trackCount", 35)
                            put("creator", buildJsonObject { put("nickname", "CreatorA") })
                        },
                    )
                },
            )
        }
        val topPage = NeteaseClient.parseTopPlaylistsPage(topRoot)
        assertTrue(topPage.hasMore)
        assertEquals(500, topPage.total)
        assertEquals("10001", topPage.playlists[0].id)
        assertEquals("CreatorA", topPage.playlists[0].description)

        val hqRoot = buildJsonObject {
            put("code", 200)
            put("more", false)
            put("total", 1)
            put("lasttime", 1700001234000L)
            put(
                "playlists",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 20002L)
                            put("name", "精品爵士")
                            put("picUrl", "https://example.com/hq.jpg")
                            put("copywriter", "精选爵士录音")
                            put("trackCount", 20)
                        },
                    )
                },
            )
        }
        val hqPage = NeteaseClient.parseHighQualityPlaylistsPage(hqRoot)
        assertFalse(hqPage.hasMore)
        assertEquals(1700001234000L, hqPage.lastTime)
        assertEquals("精选爵士录音", hqPage.playlists[0].description)
    }

    @Test
    fun parseSearchHotAndSuggestAndHistory() {
        val hotRoot = buildJsonObject {
            put("code", 200)
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("searchWord", "稻香")
                            put("score", 998877L)
                            put("content", "经典曲目")
                            put("iconType", 1)
                        },
                    )
                },
            )
        }
        val hots = NeteaseClient.parseSearchHotDetail(hotRoot)
        assertEquals(1, hots.size)
        assertEquals("稻香", hots[0].keyword)
        assertEquals(998877L, hots[0].score)
        assertEquals(1, hots[0].iconType)

        val suggestRoot = buildJsonObject {
            put("code", 200)
            put(
                "result",
                buildJsonObject {
                    put(
                        "allMatch",
                        buildJsonArray {
                            add(buildJsonObject { put("keyword", "晴天") })
                            add(buildJsonObject { put("keyword", "晴天 周杰伦") })
                        },
                    )
                },
            )
        }
        val suggest = NeteaseClient.parseSearchSuggest(suggestRoot)
        assertEquals(listOf("晴天", "晴天 周杰伦"), suggest.keywords)

        val decoded = NeteaseFeedLoader.decodeSearchHistory("""["周杰伦", "  陈奕迅 ", "", "周杰伦"]""")
        assertEquals(listOf("周杰伦", "陈奕迅"), decoded)
        assertTrue(NeteaseFeedLoader.decodeSearchHistory("not-json").isEmpty())
    }

    @Test
    fun parseNewAlbumsAndTopArtistsPages() {
        val albumRoot = buildJsonObject {
            put("code", 200)
            put("total", 20)
            put(
                "albums",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 3001L)
                            put("name", "新专一")
                            put("picUrl", "https://example.com/a1.jpg")
                            put("size", 10)
                            put("publishTime", 1710000000000L)
                            put(
                                "artists",
                                buildJsonArray {
                                    add(buildJsonObject { put("name", "歌手甲") })
                                    add(buildJsonObject { put("name", "歌手乙") })
                                },
                            )
                        },
                    )
                },
            )
        }
        val albumsPage = NeteaseClient.parseNewAlbumsPage(albumRoot, offset = 0)
        assertTrue(albumsPage.hasMore)
        assertEquals(20, albumsPage.total)
        assertEquals("歌手甲 / 歌手乙", albumsPage.albums[0].artistName)

        val artistRoot = buildJsonObject {
            put("code", 200)
            put("more", true)
            put(
                "artists",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 5005L)
                            put("name", "林俊杰")
                            put("img1v1Url", "https://example.com/jj.jpg")
                            put("musicSize", 320)
                            put("albumSize", 18)
                        },
                    )
                },
            )
        }
        val artistsPage = NeteaseClient.parseTopArtistsPage(artistRoot)
        assertTrue(artistsPage.hasMore)
        assertEquals("5005", artistsPage.artists[0].id)
        assertEquals("林俊杰", artistsPage.artists[0].name)
        assertEquals("https://example.com/jj.jpg", artistsPage.artists[0].coverUrl)
        assertEquals(320, artistsPage.artists[0].musicSize)
    }

    @Test
    fun playlistStateMachineHandlesReorderAndPrivacyToggle() = runTest {
        val revision = AtomicLong(1L)
        var lastReordered: List<Long> = emptyList()
        var lastPrivacy: Boolean? = null

        val machine = NeteasePlaylistWriteStateMachine(
            hasSession = { true },
            currentRevision = { revision.get() },
            resolveLikedRemoteId = { "777" },
            apiCreatePlaylist = { name, desc, priv -> Result.success(CreatedNeteasePlaylist(id = "1", name = name, description = desc, privacyPrivate = priv)) },
            apiUpdatePlaylistName = { _, _ -> Result.success(Unit) },
            apiUpdatePlaylistDescription = { _, _ -> Result.success(Unit) },
            apiDeletePlaylist = { Result.success(Unit) },
            apiManipulateTracks = { _, _, _ -> Result.success(Unit) },
            apiSubscribePlaylist = { _, _ -> Result.success(Unit) },
            apiReorderTracks = { _, ids ->
                lastReordered = ids
                Result.success(Unit)
            },
            apiUpdatePrivacy = { _, priv ->
                lastPrivacy = priv
                Result.success(Unit)
            },
        )

        val reorderRes = machine.reorderTracks("100", listOf(30L, 10L, 20L, 10L))
        assertTrue(reorderRes.isSuccess)
        assertEquals(listOf(30L, 10L, 20L), lastReordered)

        val privacyRes = machine.updatePrivacy("100", privacyPrivate = false)
        assertTrue(privacyRes.isSuccess)
        assertEquals(false, lastPrivacy)

        // Subsequent call after revision bump still updates cleanly
        revision.incrementAndGet()
        val secondRes = machine.updatePrivacy("100", privacyPrivate = true)
        assertTrue(secondRes.isSuccess)
        assertEquals(true, lastPrivacy)
    }

    @Test
    fun buildSearchGuessKeywordsCombinesAndDeduplicates() {
        val recommend = listOf(
            NeteaseEntryPayload(songId = 1L, title = "七里香", artists = "周杰伦", album = "七里香", durationMs = 240_000L, coverUrl = ""),
            NeteaseEntryPayload(songId = 2L, title = "晴天", artists = "周杰伦", album = "叶惠美", durationMs = 240_000L, coverUrl = ""),
            NeteaseEntryPayload(songId = 3L, title = "红豆", artists = "王菲 / 梁翘柏", album = "唱游", durationMs = 240_000L, coverUrl = ""),
        )
        val newSongs = listOf(
            NeteaseEntryPayload(songId = 4L, title = "七里香", artists = "新人", album = "A", durationMs = 200_000L, coverUrl = ""),
            NeteaseEntryPayload(songId = 5L, title = "夜曲", artists = "周杰伦", album = "十一月的萧邦", durationMs = 200_000L, coverUrl = ""),
        )
        val hot = listOf(
            NeteaseSearchHotItemPayload(keyword = "稻香"),
            NeteaseSearchHotItemPayload(keyword = "七里香"),
        )

        val keywords = NeteaseFeedLoader.buildSearchGuessKeywords(
            recommendSongs = recommend,
            newSongs = newSongs,
            hotItems = hot,
            shuffle = false,
            limit = 12,
        )
        assertTrue(keywords.contains("七里香"))
        assertTrue(keywords.contains("周杰伦"))
        assertTrue(keywords.contains("晴天"))
        assertTrue(keywords.contains("王菲"))
        assertTrue(keywords.contains("稻香"))
        assertEquals(keywords.distinctBy { it.lowercase() }.size, keywords.size)
    }

    @Test
    fun dynamicSnapshotPlaylistDetectionCoversDynamicAndRadars() {
        assertTrue(NeteaseRepository.isDynamicSnapshotPlaylist(NeteaseRepository.DAILY_ID))
        assertTrue(NeteaseRepository.isDynamicSnapshotPlaylist("dynamic:radar"))
        assertTrue(NeteaseRepository.isDynamicSnapshotPlaylist(NeteaseRepository.LIKED_ID))
        assertTrue(NeteaseRepository.isDynamicSnapshotPlaylist(NeteaseClient.PrivateRadarPlaylistId))
        assertTrue(NeteaseRepository.isDynamicSnapshotPlaylist("3136952023"))
        assertTrue(NeteaseRepository.isDynamicSnapshotPlaylist("999", dynamicKind = "radar"))
        assertTrue(NeteaseRepository.isDynamicSnapshotPlaylist("999", name = RADAR_DISPLAY_NAME))
        assertTrue(
            NeteaseRepository.isDynamicSnapshotPlaylist(
                "snapshot:radar:1",
                sourceUrl = "https://music.163.com/playlist?id=3136952023",
            ),
        )
        assertFalse(NeteaseRepository.isDynamicSnapshotPlaylist("123456", name = "我的日常歌单"))
    }

    @Test
    fun tuneSizedUrlAppendsOrUpgradesParamSize() {
        assertEquals(
            "https://p1.music.126.net/abc.jpg?param=600y600",
            tuneSizedUrl("https://p1.music.126.net/abc.jpg", 600),
        )
        assertEquals(
            "https://p1.music.126.net/abc.jpg?param=600y600",
            tuneSizedUrl("https://p1.music.126.net/abc.jpg?param=140y140", 600),
        )
        assertEquals(
            "/data/user/0/cover.jpg",
            tuneSizedUrl("/data/user/0/cover.jpg", 600),
        )
    }

    @Test
    fun parseCloudDiskPageHandlesJsonNullFieldsAndBlankSimpleSongName() {
        val nullDataRoot = buildJsonObject {
            put("code", 200)
            put("data", JsonNull)
        }
        val emptyPage = NeteaseClient.parseCloudDiskPage(nullDataRoot)
        assertTrue(emptyPage.tracks.isEmpty())

        val root = buildJsonObject {
            put("code", 200)
            put("count", 2)
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("songId", 556677L)
                            put("fileName", "upload_null_ar.flac")
                            put("simpleSong", buildJsonObject {
                                put("id", 556677L)
                                put("name", "云盘缺省歌手")
                                put("ar", JsonNull)
                                put("al", JsonNull)
                                put("dt", 210000L)
                            })
                        },
                    )
                    add(
                        buildJsonObject {
                            put("songId", 778899L)
                            put("fileName", "private_demo.mp3")
                            put("songName", "未匹配原声")
                            put("artist", "本地作者")
                            put("simpleSong", buildJsonObject {
                                put("id", 778899L)
                                put("name", "")
                                put("ar", JsonNull)
                                put("dt", 180000L)
                            })
                        },
                    )
                },
            )
        }
        val page = NeteaseClient.parseCloudDiskPage(root)
        assertEquals(2, page.tracks.size)
        assertEquals("云盘缺省歌手", page.tracks[0].song.title)
        assertEquals("", page.tracks[0].song.artists)
        assertEquals("未匹配原声", page.tracks[1].song.title)
        assertEquals("本地作者", page.tracks[1].song.artists)
        assertEquals(180000L, page.tracks[1].song.durationMs)
    }

    @Test
    fun heartbeatStartUnifiesCoverWithPlayedTrack() = runTest {
        var latestPreview = NeteaseModePreview(
            trackIds = listOf("netease:101"),
            artworkPath = "/covers/101.jpg",
            seed = 10L,
        )
        val matchedId = NeteaseModeEngine.resolveHeartbeatStart(
            seedSongId = 10L,
            currentPreview = latestPreview,
            resolveLikedSeed = { 10L },
            resolveDailySeed = { 20L },
            fetchTrackForSeed = { error("Should reuse matching preview") },
            onPreviewUpdated = { latestPreview = it },
        )
        assertEquals("netease:101", matchedId)
        assertEquals("/covers/101.jpg", latestPreview.artworkPath)
        assertTrue(latestPreview.trackIds.isEmpty())

        // When seed changes and preview no longer matches, cover updates to the newly started track.
        val updatedId = NeteaseModeEngine.resolveHeartbeatStart(
            seedSongId = 99L,
            currentPreview = latestPreview,
            resolveLikedSeed = { 10L },
            resolveDailySeed = { 20L },
            fetchTrackForSeed = { seed ->
                LibraryTrack(
                    id = "netease:999",
                    title = "Seed $seed Track",
                    artists = "Artist",
                    artworkPath = "/covers/999.jpg",
                )
            },
            onPreviewUpdated = { latestPreview = it },
        )
        assertEquals("netease:999", updatedId)
        assertEquals("/covers/999.jpg", latestPreview.artworkPath)
        assertEquals(99L, latestPreview.seed)
    }

    @Test
    fun similarModeIsMutuallyExclusiveWithHeartbeatAndRoam() {
        NeteaseModeEngine.setHeartbeatEnabled(false)
        NeteaseModeEngine.setRoamEnabled(false)
        NeteaseModeEngine.setSimilarEnabled(false)

        NeteaseModeEngine.setHeartbeatEnabled(true)
        assertTrue(NeteaseModeEngine.heartbeatEnabled.value)
        assertFalse(NeteaseModeEngine.similarEnabled.value)

        NeteaseModeEngine.setSimilarEnabled(true)
        assertTrue(NeteaseModeEngine.similarEnabled.value)
        assertFalse(NeteaseModeEngine.heartbeatEnabled.value)
        assertFalse(NeteaseModeEngine.roamEnabled.value)

        NeteaseModeEngine.setRoamEnabled(true)
        assertTrue(NeteaseModeEngine.roamEnabled.value)
        assertFalse(NeteaseModeEngine.similarEnabled.value)

        NeteaseModeEngine.setRoamEnabled(false)
        assertFalse(NeteaseModeEngine.roamEnabled.value)
    }

    @Test
    fun parseHomeBlockUsesTailoredSubtitleAndExtractsExclusivePlaylists() {
        val styleBlock = buildJsonObject {
            put("blockCode", "HOMEPAGE_BLOCK_STYLE_RCMD")
            put("uiElement", buildJsonObject {
                put("mainTitle", buildJsonObject { put("title", "推荐歌曲") })
                put("subTitle", buildJsonObject { put("title", "因为你听过 晴天 · 为你定制") })
            })
            put("resources", buildJsonArray {
                add(buildJsonObject {
                    put("resourceType", "song")
                    put("resourceId", "186016")
                    put("uiElement", buildJsonObject {
                        put("mainTitle", buildJsonObject { put("title", "晴天") })
                    })
                    put("resourceExtInfo", buildJsonObject {
                        put("artists", buildJsonArray {
                            add(buildJsonObject { put("id", 6452L); put("name", "周杰伦") })
                        })
                    })
                })
            })
        }
        val parsedStyle = parseHomeBlock(styleBlock)
        assertEquals("因为你听过 晴天 · 为你定制", parsedStyle.title)
        assertEquals(1, parsedStyle.songs.size)
        assertEquals("晴天", parsedStyle.songs[0].title)

        val playlistBlock = buildJsonObject {
            put("blockCode", "HOMEPAGE_BLOCK_PLAYLIST_RCMD")
            put("uiElement", buildJsonObject {
                put("mainTitle", buildJsonObject { put("title", "你的专属推荐歌单") })
            })
            put("resources", buildJsonArray {
                add(buildJsonObject {
                    put("resourceType", "list")
                    put("resourceId", "778899")
                    put("uiElement", buildJsonObject {
                        put("mainTitle", buildJsonObject { put("title", "私人雷达") })
                        put("subTitle", buildJsonObject { put("title", "每日更新") })
                    })
                })
            })
        }
        val parsedPlaylists = parseHomeBlock(playlistBlock)
        assertEquals("你的专属推荐歌单", parsedPlaylists.title)
        assertEquals(1, parsedPlaylists.playlists.size)
        assertEquals("778899", parsedPlaylists.playlists[0].id)
        assertEquals("私人雷达", parsedPlaylists.playlists[0].name)
    }

    @Test
    fun downloadQualityAndPathSyntaxHandlePrivateAndSafUris() {
        assertEquals(DownloadQuality.Standard, DownloadQuality.fromApiLevel(null))
        assertEquals(DownloadQuality.Standard, DownloadQuality.fromApiLevel("invalid"))
        assertEquals(DownloadQuality.UltraClearMaster, DownloadQuality.fromApiLevel("JYMASTER"))
        assertEquals(
            listOf(DownloadQuality.Lossless, DownloadQuality.High, DownloadQuality.Standard),
            DownloadQuality.Lossless.downloadFallbacks,
        )
        assertTrue(DownloadSettingsSnapshot().usePrivateFolder)

        assertTrue(NeteaseMediaHelper.isDownloadedAudioPathSyntaxValid("/data/user/0/app/files/123.flac"))
        assertTrue(NeteaseMediaHelper.isDownloadedAudioPathSyntaxValid("content://com.android.externalstorage.documents/tree/primary/document/123.flac"))
        assertFalse(NeteaseMediaHelper.isDownloadedAudioPathSyntaxValid(null))
        assertFalse(NeteaseMediaHelper.isDownloadedAudioPathSyntaxValid(""))
        assertFalse(NeteaseMediaHelper.isDownloadedAudioPathSyntaxValid("netease://123"))
        assertFalse(NeteaseMediaHelper.isDownloadedAudioPathSyntaxValid("https://music.126.net/a.mp3"))

        assertTrue(
            NeteaseMediaHelper.isExplicitlyDownloaded(
                LibraryTrack("netease:123", "Song", "Artist", audioPath = "content://downloads/123.flac"),
            ),
        )
        assertFalse(
            NeteaseMediaHelper.isExplicitlyDownloaded(
                LibraryTrack("local:123", "Song", "Artist", audioPath = "/sdcard/a.flac"),
            ),
        )
        assertFalse(
            NeteaseMediaHelper.isExplicitlyDownloaded(
                LibraryTrack("netease:123", "Song", "Artist", audioPath = "netease://123"),
            ),
        )
    }

    @Test
    fun artistDetailsAndSavedAlbumsFiltersMatchCaseInsensitively() {
        val localAlbums = listOf(
            LibraryAlbum(id = "a1", title = "Fantasy", artist = "Jay Chou"),
            LibraryAlbum(id = "a2", title = "Dawn", artist = "Other"),
        )
        assertEquals(2, filterArtistDetailAlbums(localAlbums, " ").size)
        assertEquals(listOf("a1"), filterArtistDetailAlbums(localAlbums, "fantasy").map { it.id })
        assertEquals(listOf("a1"), filterArtistDetailAlbums(localAlbums, "CHOU").map { it.id })
        assertTrue(filterArtistDetailAlbums(localAlbums, "nonexistent").isEmpty())

        val artistTracks = listOf(
            LibraryTrack(id = "t1", title = "Sunny Day", artists = "Jay Chou", album = "Yeh Hui-Mei"),
            LibraryTrack(id = "t2", title = "Rice Field", artists = "Jay Chou", album = "Capricorn"),
        )
        assertEquals(2, filterNeteaseArtistTracks(artistTracks, "").size)
        assertEquals(listOf("t1"), filterNeteaseArtistTracks(artistTracks, "hui-mei").map { it.id })
        assertEquals(listOf("t2"), filterNeteaseArtistTracks(artistTracks, "RICE").map { it.id })

        val artistAlbums = listOf(
            NeteaseArtistAlbumItem(id = "na1", name = "Jay", coverUrl = ""),
            NeteaseArtistAlbumItem(id = "na2", name = "The Eight Dimensions", coverUrl = ""),
        )
        assertEquals(2, filterNeteaseArtistAlbums(artistAlbums, "").size)
        assertEquals(listOf("na2"), filterNeteaseArtistAlbums(artistAlbums, "dimensions").map { it.id })

        val savedAlbums = listOf(
            MineAlbum(id = "ma1", name = "Blue Train", artistName = "John Coltrane"),
            MineAlbum(id = "ma2", name = "Kind of Blue", artistName = "Miles Davis"),
        )
        assertEquals(2, filterMineSavedAlbums(savedAlbums, "").size)
        assertEquals(listOf("ma2"), filterMineSavedAlbums(savedAlbums, "davis").map { it.id })
        assertEquals(2, filterMineSavedAlbums(savedAlbums, "BLUE").size)
    }
}
