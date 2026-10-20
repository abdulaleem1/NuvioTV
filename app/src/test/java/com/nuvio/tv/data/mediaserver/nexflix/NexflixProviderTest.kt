package com.nuvio.tv.data.mediaserver.nexflix

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerGenre
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexflixProviderTest {
    @Test
    fun encodesItemsWithTitleContextAndEpisodePosition() {
        val movie = NexflixItem.Movie("1234", "Up | Away: Part 2", 2009)
        assertEquals(movie, NexflixItem.parse(movie.encode()))
        val untitled = NexflixItem.Movie("99", null, null)
        assertEquals(untitled, NexflixItem.parse(untitled.encode()))
        val episode = NexflixItem.Episode("episode:7:2:3:555", "7", 2, 3)
        assertEquals(episode, NexflixItem.parse(episode.encode()))
        assertEquals(NexflixItem.Series("7"), NexflixItem.parse("s|7"))
        assertNull(NexflixItem.parse("x|1"))
        assertNull(NexflixItem.parse("e|7|two|3|id"))

        val ref = ServerItemRef("c1", episode.encode())
        assertEquals(episode, ServerItemRef.parse(ref.encode())?.itemId?.let(NexflixItem::parse))
    }

    @Test
    fun parsesDotNetTimeSpans() {
        assertEquals(6720L, runtimeSeconds("01:52:00"))
        assertEquals(93600L, runtimeSeconds("1.02:00:00"))
        assertEquals(45L, runtimeSeconds("00:00:45.5000000"))
        assertNull(runtimeSeconds("112 min"))
        assertEquals(112, runtimeMinutes("01:52:00"))
    }

    @Test
    fun onlyExposesServerProxyMedia() {
        val mapper = NexflixMapper("http://nexflix.lan:53317", "c1")
        assertEquals("http://nexflix.lan:53317/api/assets/abc", mapper.media("/api/assets/abc"))
        assertEquals("http://nexflix.lan:53317/api/stream/k", mapper.media("http://nexflix.lan:53317/api/stream/k"))
        assertNull(mapper.media("http://portal.example/stalker_portal/poster.jpg"))
        assertNull(mapper.media("/stalker_portal/poster.jpg"))
        assertNull(mapper.media(""))
    }

    @Test
    fun signInConnectsTheSavedPortalProfile() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/health" -> ok("""{"status":"ok","phase":5}""")
                "/api/portals" -> ok("""[{"id":"p1","name":"Home portal","portalUrl":"hidden","state":"disconnected"}]""")
                "/api/portals/p1/connect" -> ok("""{"profileId":"p1","state":"connected"}""")
                else -> notFound()
            }
        }
        val signIn = NexflixProvider(http.client).signIn(" 192.168.2.58:53317/ ", "", "")
        assertEquals("http://192.168.2.58:53317", signIn.address)
        assertEquals("p1", signIn.serverId)
        assertEquals("p1", signIn.token)
        assertEquals("Home portal", signIn.userName)
        assertEquals("POST", http.requests.last().method)
    }

    @Test
    fun rejectsServersThatAreNotNexflix() = runTest {
        val http = NexflixHttp { notFound() }
        val error = assertFailsWith<ServerException> { NexflixProvider(http.client).signIn("http://host:8096", "", "") }
        assertEquals(ServerFailure.NOT_FOUND, error.failure)
    }

    @Test
    fun listsWholeCatalogsAndOptionalCategories() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/movie-categories" -> ok("""[{"id":"*","name":"All"},{"id":"10","name":"Hindi"}]""")
                "/api/series-categories" -> ok("""[{"id":"20","name":"Drama"}]""")
                else -> notFound()
            }
        }
        val libraries = NexflixProvider(http.client).libraries(session())
        assertEquals(listOf("movie:*", "series:*", "movie:10", "series:20"), libraries.map { it.id })
        assertEquals(listOf(true, true, false, false), libraries.map { it.selected })
        assertEquals(listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES, ServerMediaKind.MOVIE, ServerMediaKind.SERIES), libraries.map { it.kind })
        assertEquals(listOf(ServerGenre("10", "Hindi")), libraries[0].genres)
        assertEquals(listOf(ServerGenre("20", "Drama")), libraries[1].genres)
        assertTrue(libraries.drop(2).all { it.genres.isEmpty() })
        assertEquals("10", categoryId("movie:10"))
        assertNull(categoryId("movie:*"))
    }

    @Test
    fun slicesCatalogPagesFromOneRequest() = runTest {
        val movies = (1..5).joinToString(",", "[", "]") {
            """{"id":"m$it","title":"Movie $it","year":2020,"posterUrl":"/api/assets/p$it","genres":["Drama"],"versions":[]}"""
        }
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/movies" -> ok(movies)
                else -> notFound()
            }
        }
        val provider = NexflixProvider(http.client)
        val library = ServerLibrary("movie:10", "Hindi", ServerMediaKind.MOVIE)
        val first = provider.libraryPage(session(), library, start = 0, limit = 2)
        val last = provider.libraryPage(session(), library, start = 4, limit = 2)
        assertEquals(5, first.totalCount)
        assertEquals(listOf("Movie 1", "Movie 2"), first.items.map { it.preview.name })
        assertEquals(listOf("Movie 5"), last.items.map { it.preview.name })
        assertEquals("http://nexflix.lan:53317/api/assets/p1", first.items.first().preview.poster)
        assertEquals(NexflixItem.Movie("m1", "Movie 1", 2020), NexflixItem.parse(ServerItemRef.parse(first.items.first().preview.id)!!.itemId))
        assertEquals(1, http.requests.count { it.path == "/api/movies" })
        assertEquals("10", http.requests.first { it.path == "/api/movies" }.url.queryParameter("categoryId"))
    }

    @Test
    fun reconnectsThePortalAfterAnApiRestart() = runTest {
        var catalogCalls = 0
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(if (catalogCalls == 0) CONNECTED_PORTAL else DISCONNECTED_PORTAL)
                "/api/portals/p1/connect" -> ok("""{"profileId":"p1","state":"connected"}""")
                "/api/series" -> if (catalogCalls++ == 0) problem(502, "portal_unreachable") else ok("[]")
                else -> notFound()
            }
        }
        val page = NexflixProvider(http.client).libraryPage(session(), ServerLibrary("series:*", "Series", ServerMediaKind.SERIES), 0, 10)
        assertTrue(page.items.isEmpty())
        assertEquals(2, catalogCalls)
        assertTrue(http.requests.any { it.path == "/api/portals/p1/connect" })
    }

    @Test
    fun offersMovieLanguageVersionsAsCandidates() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/movies/m1" -> ok(MOVIE_DETAILS)
                else -> notFound()
            }
        }
        val itemId = NexflixItem.Movie("m1", "Arrival", 2016).encode()
        val candidates = NexflixProvider(http.client).candidates(session(), itemId)
        assertEquals(listOf("m1", "m2"), candidates.map { it.target.mediaSourceId })
        assertEquals(listOf("English", "Hindi"), candidates.map { it.title })
        assertEquals("Arrival", http.requests.first { it.path == "/api/movies/m1" }.url.queryParameter("title"))
    }

    @Test
    fun preparesProxiedPlaybackWithoutReResolvingAChosenVersion() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/movies/m1" -> ok(MOVIE_DETAILS)
                "/api/playback/m2" -> ok("""{"url":"/api/stream/key1","protocol":"hls","browserPlayable":true}""")
                else -> notFound()
            }
        }
        val provider = NexflixProvider(http.client)
        val target = ServerPlaybackTarget(ServerItemRef("c1", NexflixItem.Movie("m1", "Arrival", 2016).encode()), mediaSourceId = "m2")
        val playback = provider.preparePlayback(session(), ServerPlaybackRequest(target, ServerPlayerCapabilities()))
        assertEquals("http://nexflix.lan:53317/api/stream/key1", playback.url)
        assertEquals("m2", playback.mediaSourceId)
        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        val request = http.requests.first { it.path == "/api/playback/m2" }
        assertEquals("POST", request.method)
        assertNull(request.url.queryParameter("title"))
    }

    @Test
    fun rejectsStreamsOutsideTheNexflixProxy() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/movies/m1" -> ok(MOVIE_DETAILS)
                "/api/playback/m1" -> ok("""{"url":"http://portal.example/movie.mkv","protocol":"mp4"}""")
                else -> notFound()
            }
        }
        val target = ServerPlaybackTarget(ServerItemRef("c1", NexflixItem.Movie("m1", "Arrival", 2016).encode()), mediaSourceId = null)
        val error = assertFailsWith<ServerException> {
            NexflixProvider(http.client).preparePlayback(session(), ServerPlaybackRequest(target, ServerPlayerCapabilities()))
        }
        assertEquals(ServerFailure.UNSUPPORTED, error.failure)
        assertEquals("Arrival", http.requests.first { it.path == "/api/playback/m1" }.url.queryParameter("title"))
    }

    @Test
    fun reportsEpisodeProgressToNexflixAfterTheResumePoint() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/series/s7" -> ok(SERIES_DETAILS)
                "/api/playback/episode:s7:1:2:e2" -> ok("""{"url":"/api/stream/key2","protocol":"ts"}""")
                "/api/library/progress/episode:s7:1:2:e2" -> ok("")
                else -> notFound()
            }
        }
        val provider = NexflixProvider(http.client)
        val episode = NexflixItem.Episode("episode:s7:1:2:e2", "s7", 1, 2)
        val target = ServerPlaybackTarget(ServerItemRef("c1", episode.encode()), mediaSourceId = episode.contentId)
        val playback = provider.preparePlayback(session(), ServerPlaybackRequest(target, ServerPlayerCapabilities()))

        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.START, 0L, isPaused = false))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 4_000L, isPaused = false))
        assertFalse(http.requests.any { it.method == "PUT" })

        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, 600_000L, isPaused = true, durationMs = 1_500_000L))
        val put = http.requests.single { it.method == "PUT" }
        val body = Json.parseToJsonElement(put.text).jsonObject
        val content = body["content"]!!.jsonObject
        assertEquals("episode:s7:1:2:e2", content["id"]!!.jsonPrimitive.content)
        assertEquals("series", content["contentType"]!!.jsonPrimitive.content)
        assertEquals("Severance", content["title"]!!.jsonPrimitive.content)
        assertEquals("S1 E2 · Half Loop", content["episodeLabel"]!!.jsonPrimitive.content)
        assertEquals("s7", content["detailsId"]!!.jsonPrimitive.content)
        assertEquals("s7", body["seriesId"]!!.jsonPrimitive.content)
        assertEquals(600.0, body["positionSeconds"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1500.0, body["durationSeconds"]!!.jsonPrimitive.content.toDouble(), 0.0)

        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 700_000L, isPaused = false))
        assertEquals(1, http.requests.count { it.method == "PUT" })
    }

    @Test
    fun refusesAnEpisodeThatLeftTheCatalog() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/series/s7" -> ok(SERIES_DETAILS)
                else -> notFound()
            }
        }
        val episode = NexflixItem.Episode("episode:s7:1:3:gone", "s7", 1, 3)
        val error = assertFailsWith<ServerException> {
            NexflixProvider(http.client).candidates(session(), episode.encode())
        }
        assertEquals(ServerFailure.NOT_FOUND, error.failure)
    }

    @Test
    fun readsNexflixWatchProgressIntoDetailsAndResumeRows() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/series/s7" -> ok(SERIES_DETAILS)
                "/api/library/history", "/api/library/progress" -> ok(
                    """[{"content":{"id":"episode:s7:1:2:e2","contentType":"series","title":"Severance","artUrl":"/api/assets/t2","genres":[],"portal":true,"detailsId":"s7"},
                        "seriesId":"s7","season":1,"episode":2,"positionSeconds":600,"durationSeconds":1500,"lastWatchedAt":"2026-10-18T20:00:00+00:00"}]"""
                )
                else -> notFound()
            }
        }
        val provider = NexflixProvider(http.client)
        val details = provider.details(session(), NexflixItem.Series("s7").encode())
        assertEquals(2, details.meta.videos.size)
        assertEquals("tt11280740", details.externalIds.imdb)
        val state = details.userStates.single()
        assertEquals(details.meta.videos[1].id, state.videoId)
        assertEquals(600_000L, state.positionMs)
        assertFalse(state.played)

        val resume = provider.resumeItems(session(), limit = 10).single()
        assertEquals(ServerItemRef("c1", NexflixItem.Series("s7").encode()).encode(), resume.preview.id)
        assertEquals(ContentType.SERIES, resume.preview.type)
        assertEquals("http://nexflix.lan:53317/api/assets/t2", resume.preview.poster)
    }

    @Test
    fun pagesAGenreFromItsPortalCategory() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/portals" -> ok(CONNECTED_PORTAL)
                "/api/series" -> ok("""[{"id":"s7","title":"Severance","genres":[],"versions":[]}]""")
                else -> notFound()
            }
        }
        val library = ServerLibrary("series:*", "Series", ServerMediaKind.SERIES, genres = listOf(ServerGenre("20", "Drama")))
        val page = NexflixProvider(http.client).genrePage(session(), library, "20", start = 0, limit = 10)
        assertEquals(listOf("Severance"), page.items.map { it.preview.name })
        assertEquals("20", http.requests.single { it.path == "/api/series" }.url.queryParameter("categoryId"))
    }

    @Test
    fun importsEpisodesAndMoviesIntoContinueWatching() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/library/progress" -> ok(
                    """[{"content":{"id":"episode:s7:1:2:e2","contentType":"series","title":"Severance","artUrl":"/api/assets/t2",
                          "episodeLabel":"S1 E2 · Half Loop","genres":[],"portal":true,"detailsId":"s7"},
                        "seriesId":"s7","season":1,"episode":2,"positionSeconds":600,"durationSeconds":1500,"lastWatchedAt":"2026-10-18T20:00:00+00:00"},
                       {"content":{"id":"m1","contentType":"movie","title":"Arrival","year":2016,"artUrl":"/api/assets/a1","genres":[],"portal":true},
                        "positionSeconds":300,"durationSeconds":6960,"lastWatchedAt":"2026-10-17T20:00:00+00:00"},
                       {"content":{"id":"episode:x","contentType":"series","title":"No position","genres":[],"portal":true},
                        "positionSeconds":30,"durationSeconds":100,"lastWatchedAt":"2026-10-16T20:00:00+00:00"}]"""
                )
                else -> notFound()
            }
        }
        val entries = NexflixProvider(http.client).continueWatching(session(), limit = 10)
        assertEquals(2, entries.size)

        val episode = entries[0]
        assertEquals(ServerItemRef("c1", NexflixItem.Series("s7").encode()).encode(), episode.parent.id)
        assertEquals(ContentType.SERIES, episode.parent.type)
        assertEquals(ServerItemRef("c1", NexflixItem.Episode("episode:s7:1:2:e2", "s7", 1, 2).encode()).encode(), episode.state.videoId)
        assertEquals("Severance", episode.parent.name)
        assertEquals("Half Loop", episode.state.title)
        assertEquals(1, episode.state.season)
        assertEquals(2, episode.state.episode)
        assertEquals(600_000L, episode.state.positionMs)
        assertEquals(1_500_000L, episode.state.durationMs)
        assertEquals("http://nexflix.lan:53317/api/assets/t2", episode.episodeThumbnail)

        val movie = entries[1]
        assertEquals(movie.parent.id, movie.state.videoId)
        assertEquals(ContentType.MOVIE, movie.parent.type)
        assertEquals("Arrival", movie.state.title)
        assertEquals(300_000L, movie.state.positionMs)
    }

    @Test
    fun clearsServerProgressForARemovedItem() = runTest {
        val http = NexflixHttp { request ->
            when (request.path) {
                "/api/library/progress/episode:s7:1:2:e2" -> Reply(204, "")
                else -> notFound()
            }
        }
        NexflixProvider(http.client).removeProgress(session(), NexflixItem.Episode("episode:s7:1:2:e2", "s7", 1, 2).encode())
        assertEquals("DELETE", http.requests.single().method)
    }

    @Test
    fun readsEpisodeTitlesFromNexflixLabels() {
        assertEquals("Half Loop", episodeTitle("S1 E2 · Half Loop"))
        assertEquals("Part · Two", episodeTitle("S1 E3 · Part · Two"))
        assertNull(episodeTitle("Episode 4"))
        assertNull(episodeTitle(null))
    }

    private fun session() = ServerSession(
        connection = ServerConnection(
            id = "c1",
            providerId = "nexflix",
            name = "Nexflix",
            address = "http://nexflix.lan:53317",
            remoteServerId = "p1",
            remoteUserId = "p1",
            userName = "Home portal",
            credentialRef = "k1"
        ),
        token = "p1"
    )

    private companion object {
        const val CONNECTED_PORTAL = """[{"id":"p1","name":"Home portal","state":"connected"}]"""
        const val DISCONNECTED_PORTAL = """[{"id":"p1","name":"Home portal","state":"disconnected"}]"""
        const val MOVIE_DETAILS = """{"id":"m1","title":"Arrival","year":2016,"runtime":"01:56:00","genres":["Sci-Fi"],
            "cast":["Amy Adams"],"imdbId":"tt2543164","language":"English","posterUrl":"/api/assets/a1",
            "versions":[{"id":"m2","language":"HIN","label":"Hindi"},{"id":"m1","language":"ENG","label":"English"}]}"""
        const val SERIES_DETAILS = """{"id":"s7","title":"Severance","year":2022,"genres":["Drama"],"cast":[],"imdbId":"tt11280740",
            "seasons":[{"number":1,"episodes":[
              {"id":"episode:s7:1:1:e1","number":1,"title":"Good News About Hell","runtime":"00:57:00"},
              {"id":"episode:s7:1:2:e2","number":2,"title":"Half Loop","runtime":"00:53:00","thumbnailUrl":"/api/assets/t2"}]}],
            "versions":[]}"""
    }
}

private class Reply(val status: Int, val body: String)

private fun ok(body: String) = Reply(200, body)

private fun notFound() = Reply(404, "")

private fun problem(status: Int, code: String) =
    Reply(status, """{"title":"Portal unavailable","status":$status,"code":"$code"}""")

private val Request.path: String
    get() = url.pathSegments.joinToString("/", prefix = "/")

private val Request.text: String
    get() = Buffer().also { buffer -> body?.writeTo(buffer) }.readUtf8()

private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw error
    }
    throw AssertionError("Expected ${T::class.simpleName}")
}

private class NexflixHttp(private val responder: (Request) -> Reply) {
    private val recorded = mutableListOf<Request>()

    val requests: List<Request>
        get() = synchronized(recorded) { recorded.toList() }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            synchronized(recorded) { recorded += request }
            val reply = responder(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.status)
                .message("HTTP ${reply.status}")
                .body(reply.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()
}
