package com.nuvio.tv.data.mediaserver.nexflix

import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerGenre
import com.nuvio.tv.data.mediaserver.ServerItemDetails
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPage
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerResumeEntry
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.mediabrowser.normalizeServerAddress
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient

/**
 * Nuvio server provider for a Nexflix API (Stalker/Ministra VOD proxied by a local-first server).
 *
 * Nexflix has no per-user login. The connection stores the Nexflix portal profile ID, and the provider
 * reconnects that saved profile when the API has restarted. Portal credentials, cookies, tokens and
 * stream commands never leave the Nexflix server: catalogs, artwork and streams all come from its
 * `/api` proxy routes.
 */
internal class NexflixProvider(
    http: OkHttpClient
) : ServerProvider {
    override val id: String = "nexflix"
    override val displayName: String = "Nexflix"
    override val minimumVersion: String = "1.0"
    override val requiresCredentials: Boolean = false
    override val addressHint: String = "http://192.168.1.10:53317"
    override val capabilities: Set<ServerCapability> = setOf(
        ServerCapability.SEARCH,
        ServerCapability.USER_STATE_READ,
        ServerCapability.CONTINUE_WATCHING
    )

    private val client = NexflixClient(http)
    private val lock = Any()
    private val verified = mutableSetOf<String>()
    private val catalogs = mutableMapOf<String, Cached<List<NexflixTitle>>>()
    private val movies = mutableMapOf<String, Cached<NexflixMovieDetails>>()
    private val series = mutableMapOf<String, Cached<NexflixSeriesDetails>>()
    private val histories = mutableMapOf<String, Cached<List<NexflixProgress>>>()
    private val reports = mutableMapOf<String, ReportContext>()

    override suspend fun signIn(address: String, username: String, password: String): ServerSignIn {
        val baseUrl = normalizeServerAddress(address) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val health = client.get(baseUrl, "/api/health", NexflixHealth.serializer())
        if (health.status != "ok") throw ServerException(ServerFailure.UNSUPPORTED)
        val portal = connectPortal(baseUrl, preferredId = null)
        return ServerSignIn(
            address = baseUrl,
            serverName = displayName,
            serverId = portal.id,
            userId = portal.id,
            userName = portal.name.ifBlank { displayName },
            token = portal.id
        )
    }

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> = portal(session) { baseUrl ->
        coroutineScope {
            val movieRequest = async { categories(baseUrl, "/api/movie-categories") }
            val seriesRequest = async { categories(baseUrl, "/api/series-categories") }
            val movieCategories = movieRequest.await()
            val seriesCategories = seriesRequest.await()
            // Like Jellyfin view names, these are server library names rather than app strings.
            val moviesName = "Movies"
            val seriesName = "Series"
            // Whole-catalog rows are on by default and offer every portal category as a Discover genre.
            // Each category is also an optional library for its own Home row.
            listOf(
                ServerLibrary(
                    libraryId(ServerMediaKind.MOVIE, null),
                    moviesName,
                    ServerMediaKind.MOVIE,
                    genres = movieCategories.map { ServerGenre(it.id, it.name) }
                ),
                ServerLibrary(
                    libraryId(ServerMediaKind.SERIES, null),
                    seriesName,
                    ServerMediaKind.SERIES,
                    genres = seriesCategories.map { ServerGenre(it.id, it.name) }
                )
            ) + movieCategories.map {
                ServerLibrary(libraryId(ServerMediaKind.MOVIE, it.id), "$moviesName · ${it.name}", ServerMediaKind.MOVIE, selected = false)
            } + seriesCategories.map {
                ServerLibrary(libraryId(ServerMediaKind.SERIES, it.id), "$seriesName · ${it.name}", ServerMediaKind.SERIES, selected = false)
            }
        }
    }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        // Nexflix returns a whole (bounded) category, so pages are sliced from a short-lived copy.
        val items = catalog(session, library.kind, categoryId(library.id))
        val mapper = mapper(session)
        return ServerPage(items.drop(start).take(limit).map { mapper.title(it, library.kind) }, items.size)
    }

    override suspend fun genrePage(
        session: ServerSession,
        library: ServerLibrary,
        genreId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        val items = catalog(session, library.kind, genreId)
        val mapper = mapper(session)
        return ServerPage(items.drop(start).take(limit).map { mapper.title(it, library.kind) }, items.size)
    }

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        val term = query.trim()
        if (term.length < MIN_SEARCH_LENGTH) return emptyList()
        val results = portal(session) { baseUrl ->
            client.get(baseUrl, "/api/search", ListSerializer(NexflixTitle.serializer()), mapOf("query" to term))
        }
        val mapper = mapper(session)
        return results
            .filter { ServerMediaKind.fromContentType(it.kind) == kind }
            .take(limit)
            .map { mapper.title(it, kind) }
    }

    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> {
        val progress = client.get(
            session.connection.address,
            "/api/library/progress",
            ListSerializer(NexflixProgress.serializer())
        )
        val mapper = mapper(session)
        return progress
            .sortedByDescending { it.lastWatchedAt.epochMs() ?: 0L }
            .mapNotNull(mapper::resumeTitle)
            .distinctBy { it.preview.id }
            .take(limit)
    }

    override suspend fun continueWatching(session: ServerSession, limit: Int): List<ServerResumeEntry> {
        val progress = client.get(
            session.connection.address,
            "/api/library/progress",
            ListSerializer(NexflixProgress.serializer())
        )
        val mapper = mapper(session)
        return progress
            .sortedByDescending { it.lastWatchedAt.epochMs() ?: 0L }
            .mapNotNull(mapper::resumeEntry)
            .distinctBy { it.parent.id }
            .take(limit)
    }

    override suspend fun removeProgress(session: ServerSession, itemId: String) {
        val item = NexflixItem.parse(itemId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        client.execute(
            method = "DELETE",
            baseUrl = session.connection.address,
            path = "/api/library/progress/${pathSegment(item.contentId)}"
        )
        synchronized(lock) { histories.remove(session.connection.address) }
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val mapper = mapper(session)
        return when (val item = NexflixItem.parse(itemId) ?: throw ServerException(ServerFailure.NOT_FOUND)) {
            is NexflixItem.Movie -> {
                val details = movie(session, item)
                val history = history(session)
                // Prefer the ID Nexflix resolved from the title and year over a stale portal ID.
                val progress = history.firstOrNull { it.content.id == details.id }
                    ?: history.firstOrNull { it.content.id == item.contentId }
                val states = listOfNotNull(progress?.let { mapper.userState(item, it) })
                ServerItemDetails(mapper.movieDetails(item, details), mapper.externalIds(details.imdbId), states)
            }

            is NexflixItem.Series -> {
                val details = series(session, item.contentId)
                val progress = history(session).associateBy { it.content.id }
                val states = details.seasons.flatMap { season ->
                    season.episodes.mapNotNull { episode ->
                        progress[episode.id]?.let { mapper.userState(mapper.episode(details.id, season.number, episode), it) }
                    }
                }
                ServerItemDetails(mapper.seriesDetails(details), mapper.externalIds(details.imdbId), states)
            }

            is NexflixItem.Episode -> {
                val details = series(session, item.seriesId)
                val progress = history(session).firstOrNull { it.content.id == item.contentId }
                ServerItemDetails(
                    meta = mapper.seriesDetails(details),
                    externalIds = mapper.externalIds(details.imdbId),
                    userStates = listOfNotNull(progress?.let { mapper.userState(item, it) })
                )
            }
        }
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> {
        val ref = ServerItemRef(session.connection.id, itemId)
        return when (val item = NexflixItem.parse(itemId) ?: throw ServerException(ServerFailure.NOT_FOUND)) {
            is NexflixItem.Movie -> {
                val details = movie(session, item)
                val versions = details.versions
                    .ifEmpty { listOf(NexflixVersion(details.id, details.language, details.language.orEmpty())) }
                    .sortedByDescending { it.id == details.id }
                versions.map { version ->
                    ServerCandidate(
                        target = ServerPlaybackTarget(ref, mediaSourceId = version.id),
                        title = version.label.ifBlank { version.language ?: displayName },
                        description = listOfNotNull(details.title, details.year?.toString()).joinToString(" · "),
                        filename = null,
                        sizeBytes = null
                    )
                }
            }

            is NexflixItem.Episode -> {
                val episode = episode(session, item)
                listOf(
                    ServerCandidate(
                        target = ServerPlaybackTarget(ref, mediaSourceId = item.contentId),
                        title = "S${item.season} E${item.episode}",
                        description = episode.title.ifBlank { null },
                        filename = null,
                        sizeBytes = null
                    )
                )
            }

            is NexflixItem.Series -> emptyList()
        }
    }

    override suspend fun preparePlayback(
        session: ServerSession,
        request: ServerPlaybackRequest
    ): ServerPlaybackSession {
        val item = NexflixItem.parse(request.target.item.itemId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val contentId = request.target.mediaSourceId ?: item.contentId
        val context = reportContext(session, item, contentId)
        // Title and year let Nexflix recover a stale saved movie ID. A version chosen from fresh
        // details is already exact and must not be re-resolved to another language by title.
        val query = if (item is NexflixItem.Movie && contentId == item.contentId) {
            mapOf("title" to item.title, "year" to item.year?.toString())
        } else {
            emptyMap()
        }
        val source = portal(session) { baseUrl ->
            client.post(baseUrl, "/api/playback/${pathSegment(contentId)}", NexflixPlaybackSource.serializer(), query)
        }
        if (!source.url.startsWith(STREAM_PATH) || source.protocol.lowercase() in UNPLAYABLE_PROTOCOLS) {
            throw ServerException(ServerFailure.UNSUPPORTED, "Unsupported Nexflix stream")
        }
        val url = session.connection.address.trimEnd('/') + source.url
        synchronized(lock) { reports[url] = context }
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = contentId,
            url = url,
            headers = emptyMap(),
            subtitles = emptyList(),
            playSessionId = null,
            // A retry after a failed first attempt is still a proxied stream; reporting it as
            // direct play would make the player retry it again.
            playMethod = if (request.capabilities.allowDirectPlay) ServerPlayMethod.DIRECT_PLAY else ServerPlayMethod.DIRECT_STREAM
        )
    }

    override suspend fun report(
        session: ServerSession,
        playback: ServerPlaybackSession,
        event: ServerPlaybackEvent
    ) {
        val context = synchronized(lock) {
            if (event.type == ServerPlaybackEventType.STOP) reports.remove(playback.url) else reports[playback.url]
        } ?: return
        // START is sent before Nuvio applies a resume seek; an early position must never replace
        // progress that Nexflix already saved from another device.
        if (event.type == ServerPlaybackEventType.START || event.positionMs < MIN_REPORT_POSITION_MS) return
        val duration = event.durationMs.takeIf { it > 0 }?.let { it / 1000.0 }
            ?: context.runtimeSeconds?.toDouble()
            ?: 0.0
        val position = event.positionMs / 1000.0
        val body = NexflixProgressRequest(
            content = context.snapshot,
            seriesId = context.seriesId,
            season = context.season,
            episode = context.episode,
            positionSeconds = if (duration > 0) minOf(position, duration) else position,
            durationSeconds = duration
        )
        client.execute(
            method = "PUT",
            baseUrl = session.connection.address,
            path = "/api/library/progress/${pathSegment(context.snapshot.id)}",
            body = client.json.encodeToString(NexflixProgressRequest.serializer(), body)
        )
        synchronized(lock) { histories.remove(session.connection.address) }
    }

    private suspend fun reportContext(session: ServerSession, item: NexflixItem, contentId: String): ReportContext =
        when (item) {
            is NexflixItem.Movie -> {
                val details = movie(session, item)
                ReportContext(
                    snapshot = NexflixSnapshot(
                        id = contentId,
                        contentType = ServerMediaKind.MOVIE.contentType,
                        title = details.title.ifBlank { item.title.orEmpty() },
                        year = details.year ?: item.year,
                        runtime = details.runtime,
                        maturity = details.maturity,
                        genres = details.genres,
                        description = details.description,
                        artUrl = details.posterUrl ?: details.backdropUrl
                    ),
                    seriesId = null,
                    season = null,
                    episode = null,
                    runtimeSeconds = runtimeSeconds(details.runtime)
                )
            }

            is NexflixItem.Episode -> {
                val details = series(session, item.seriesId)
                val episode = episode(session, item)
                ReportContext(
                    snapshot = NexflixSnapshot(
                        id = item.contentId,
                        contentType = ServerMediaKind.SERIES.contentType,
                        title = details.title,
                        year = details.year,
                        runtime = episode.runtime,
                        maturity = details.maturity,
                        genres = details.genres,
                        description = episode.description ?: details.description,
                        artUrl = episode.thumbnailUrl ?: details.backdropUrl ?: details.posterUrl,
                        episodeLabel = "S${item.season} E${item.episode} · ${episode.title}",
                        detailsId = item.seriesId
                    ),
                    seriesId = item.seriesId,
                    season = item.season,
                    episode = item.episode,
                    runtimeSeconds = runtimeSeconds(episode.runtime)
                )
            }

            is NexflixItem.Series -> throw ServerException(ServerFailure.UNSUPPORTED)
        }

    /** Confirms the exact episode still exists in the series rather than guessing a neighbouring ID. */
    private suspend fun episode(session: ServerSession, item: NexflixItem.Episode): NexflixEpisode =
        series(session, item.seriesId).seasons
            .firstOrNull { it.number == item.season }
            ?.episodes
            ?.firstOrNull { it.id == item.contentId && it.number == item.episode }
            ?: throw ServerException(ServerFailure.NOT_FOUND, "Episode is no longer in the portal catalog")

    private suspend fun categories(baseUrl: String, path: String): List<NexflixCategory> =
        client.get(baseUrl, path, ListSerializer(NexflixCategory.serializer()))
            .filter { it.id.isNotBlank() && it.id != ALL_CATEGORIES && it.name.isNotBlank() }

    private suspend fun catalog(session: ServerSession, kind: ServerMediaKind, categoryId: String?): List<NexflixTitle> {
        val path = when (kind) {
            ServerMediaKind.MOVIE -> "/api/movies"
            ServerMediaKind.SERIES -> "/api/series"
            else -> throw ServerException(ServerFailure.UNSUPPORTED)
        }
        return cached(catalogs, "${session.connection.address}|${libraryId(kind, categoryId)}", CATALOG_TTL_MS) {
            portal(session) { baseUrl ->
                client.get(baseUrl, path, ListSerializer(NexflixTitle.serializer()), mapOf("categoryId" to categoryId))
            }
        }
    }

    private suspend fun movie(session: ServerSession, item: NexflixItem.Movie): NexflixMovieDetails =
        cached(movies, "${session.connection.address}|${item.contentId}", DETAILS_TTL_MS) {
            portal(session) { baseUrl ->
                client.get(
                    baseUrl,
                    "/api/movies/${pathSegment(item.contentId)}",
                    NexflixMovieDetails.serializer(),
                    mapOf("title" to item.title, "year" to item.year?.toString())
                )
            }
        }

    private suspend fun series(session: ServerSession, seriesId: String): NexflixSeriesDetails =
        cached(series, "${session.connection.address}|$seriesId", DETAILS_TTL_MS) {
            portal(session) { baseUrl ->
                client.get(baseUrl, "/api/series/${pathSegment(seriesId)}", NexflixSeriesDetails.serializer())
            }
        }

    private suspend fun history(session: ServerSession): List<NexflixProgress> =
        cached(histories, session.connection.address, HISTORY_TTL_MS) {
            client.get(session.connection.address, "/api/library/history", ListSerializer(NexflixProgress.serializer()))
        }

    /**
     * Runs a catalog or playback request against the saved portal profile. Nexflix keeps the portal
     * session in memory, so after an API restart the profile is reconnected once and the request retried.
     */
    private suspend fun <T> portal(session: ServerSession, block: suspend (String) -> T): T {
        val baseUrl = session.connection.address
        if (synchronized(lock) { baseUrl !in verified }) connectPortal(baseUrl, session.token)
        return try {
            block(baseUrl)
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.UNREACHABLE) throw error
            synchronized(lock) { verified -= baseUrl }
            connectPortal(baseUrl, session.token)
            block(baseUrl)
        }
    }

    private suspend fun connectPortal(baseUrl: String, preferredId: String?): NexflixPortal {
        val portals = client.get(baseUrl, "/api/portals", ListSerializer(NexflixPortal.serializer()))
        val portal = portals.firstOrNull { it.id == preferredId }
            ?: portals.firstOrNull { it.isConnected }
            ?: portals.firstOrNull()
            ?: throw ServerException(ServerFailure.INCOMPLETE, "Add a portal profile in Nexflix first")
        if (!portal.isConnected) {
            client.execute("POST", baseUrl, "/api/portals/${pathSegment(portal.id)}/connect")
        }
        synchronized(lock) { verified += baseUrl }
        return portal
    }

    private suspend fun <T> cached(
        store: MutableMap<String, Cached<T>>,
        key: String,
        ttlMs: Long,
        load: suspend () -> T
    ): T {
        synchronized(lock) { store[key]?.takeIf { it.expiresAtMs > nowMs() } }?.let { return it.value }
        val value = load()
        synchronized(lock) { store[key] = Cached(value, nowMs() + ttlMs) }
        return value
    }

    private fun mapper(session: ServerSession) = NexflixMapper(session.connection.address, session.connection.id)

    private class Cached<T>(val value: T, val expiresAtMs: Long)

    private class ReportContext(
        val snapshot: NexflixSnapshot,
        val seriesId: String?,
        val season: Int?,
        val episode: Int?,
        val runtimeSeconds: Long?
    )

    private companion object {
        const val CATALOG_TTL_MS = 90_000L
        const val DETAILS_TTL_MS = 60_000L
        const val HISTORY_TTL_MS = 5_000L
        const val MIN_REPORT_POSITION_MS = 10_000L
        const val MIN_SEARCH_LENGTH = 2
        const val ALL_CATEGORIES = "*"
        const val STREAM_PATH = "/api/stream/"
        val UNPLAYABLE_PROTOCOLS = setOf("rtsp", "rtmp", "unknown")
    }
}

internal fun libraryId(kind: ServerMediaKind, categoryId: String?): String =
    "${kind.contentType}:${categoryId ?: "*"}"

internal fun categoryId(libraryId: String): String? =
    libraryId.substringAfter(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() && it != "*" }

private fun nowMs(): Long = System.currentTimeMillis()
