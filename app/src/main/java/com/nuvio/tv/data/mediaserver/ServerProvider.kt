package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds

enum class ServerCapability {
    SEARCH,
    EXTERNAL_ID_LOOKUP,
    USER_STATE_READ,
    USER_STATE_WRITE,
    TRANSCODING,

    /** In-progress items are imported into Nuvio's Continue Watching instead of a server row. */
    CONTINUE_WATCHING
}

interface ServerProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ServerCapability>
    val minimumVersion: String

    /** False for servers that are reached by address alone, so sign-in asks for no username or password. */
    val requiresCredentials: Boolean
        get() = true

    /** Example address shown in the sign-in field, or null for the default media-server hint. */
    val addressHint: String?
        get() = null

    suspend fun signIn(address: String, username: String, password: String): ServerSignIn = unsupported()

    suspend fun libraries(session: ServerSession): List<ServerLibrary>

    suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle>

    /** A page of [library] narrowed to one of its [ServerLibrary.genres]. */
    suspend fun genrePage(
        session: ServerSession,
        library: ServerLibrary,
        genreId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = unsupported()

    suspend fun details(session: ServerSession, itemId: String): ServerItemDetails

    suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate>

    suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession

    suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) = Unit

    suspend fun signOut(session: ServerSession) = Unit

    suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = unsupported()

    suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> = unsupported()

    suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> = unsupported()

    suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean): Unit = unsupported()

    /** Items for Nuvio's Continue Watching; requires [ServerCapability.CONTINUE_WATCHING]. */
    suspend fun continueWatching(session: ServerSession, limit: Int): List<ServerResumeEntry> = unsupported()

    /** Forgets the saved position of [itemId] after it is removed from Continue Watching. */
    suspend fun removeProgress(session: ServerSession, itemId: String): Unit = unsupported()

    suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> = unsupported()

    suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int
    ): ServerEpisode? = unsupported()
}

data class ServerIndexEntry(
    val itemId: String,
    val ids: TrackingExternalIds
)

data class ServerEpisode(
    val itemId: String,
    val premiereDate: String?
)

fun ServerProvider.supports(capability: ServerCapability): Boolean = capability in capabilities

private fun unsupported(): Nothing = throw ServerException(ServerFailure.UNSUPPORTED)
