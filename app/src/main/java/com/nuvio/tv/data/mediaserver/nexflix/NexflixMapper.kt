package com.nuvio.tv.data.mediaserver.nexflix

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerResumeEntry
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerUserState
import com.nuvio.tv.data.mediaserver.domainType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import java.time.OffsetDateTime
import kotlin.math.roundToLong
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class NexflixMapper(
    private val baseUrl: String,
    private val connectionId: String
) {
    fun ref(item: NexflixItem): String = ServerItemRef(connectionId, item.encode()).encode()

    /**
     * Resolves a Nexflix media path against the server address. Like the Nexflix apps, this only
     * accepts the server's own `/api/` proxy paths so a portal URL never reaches the player or image loader.
     */
    fun media(value: String?): String? {
        val path = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ("://" in path) {
            val url = path.toHttpUrlOrNull() ?: return null
            val base = baseUrl.toHttpUrlOrNull() ?: return null
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return null
            return path.takeIf { url.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/api/") }
        }
        if (!path.startsWith("/api/")) return null
        return baseUrl.trimEnd('/') + path
    }

    fun movie(item: NexflixTitle): NexflixItem.Movie = NexflixItem.Movie(item.id, item.title, item.year)

    fun title(item: NexflixTitle, kind: ServerMediaKind): ServerTitle {
        val target = if (kind == ServerMediaKind.SERIES) NexflixItem.Series(item.id) else movie(item)
        val poster = media(item.posterUrl) ?: media(item.backdropUrl)
        return ServerTitle(
            preview = MetaPreview(
                id = ref(target),
                type = kind.domainType(),
                rawType = kind.contentType,
                name = item.title,
                poster = poster,
                posterShape = PosterShape.POSTER,
                background = media(item.backdropUrl) ?: poster,
                logo = null,
                description = item.description?.takeIf { it.isNotBlank() },
                releaseInfo = item.year?.toString(),
                imdbRating = item.rating.rating(),
                genres = item.genres,
                ageRating = item.maturity?.takeIf { it.isNotBlank() },
                language = item.language
            )
        )
    }

    fun movieDetails(target: NexflixItem.Movie, item: NexflixMovieDetails): Meta {
        val cast = item.cast.filter { it.isNotBlank() }
        return Meta(
            id = ref(target),
            type = ServerMediaKind.MOVIE.domainType(),
            rawType = ServerMediaKind.MOVIE.contentType,
            name = item.title,
            poster = media(item.posterUrl) ?: media(item.backdropUrl),
            posterShape = PosterShape.POSTER,
            background = media(item.backdropUrl) ?: media(item.posterUrl),
            logo = null,
            description = item.description?.takeIf { it.isNotBlank() },
            releaseInfo = item.year?.toString(),
            imdbRating = item.rating.rating(),
            genres = item.genres,
            runtime = runtimeMinutes(item.runtime)?.let { "$it min" },
            director = listOfNotNull(item.director?.takeIf { it.isNotBlank() }),
            cast = cast,
            castMembers = cast.map { MetaCastMember(name = it) },
            videos = emptyList(),
            ageRating = item.maturity?.takeIf { it.isNotBlank() },
            country = null,
            awards = null,
            language = item.language,
            links = emptyList(),
            imdbId = item.imdbId.imdb()
        )
    }

    fun seriesDetails(item: NexflixSeriesDetails): Meta {
        val background = media(item.backdropUrl) ?: media(item.posterUrl)
        val cast = item.cast.filter { it.isNotBlank() }
        return Meta(
            id = ref(NexflixItem.Series(item.id)),
            type = ServerMediaKind.SERIES.domainType(),
            rawType = ServerMediaKind.SERIES.contentType,
            name = item.title,
            poster = media(item.posterUrl) ?: media(item.backdropUrl),
            posterShape = PosterShape.POSTER,
            background = background,
            logo = null,
            description = item.description?.takeIf { it.isNotBlank() },
            releaseInfo = item.year?.toString(),
            imdbRating = item.rating.rating(),
            genres = item.genres,
            runtime = null,
            director = listOfNotNull(item.director?.takeIf { it.isNotBlank() }),
            cast = cast,
            castMembers = cast.map { MetaCastMember(name = it) },
            videos = item.seasons.flatMap { season ->
                season.episodes.map { episode ->
                    Video(
                        id = ref(episode(item.id, season.number, episode)),
                        title = episode.title,
                        released = null,
                        thumbnail = media(episode.thumbnailUrl) ?: background,
                        season = season.number,
                        episode = episode.number,
                        overview = episode.description?.takeIf { it.isNotBlank() },
                        runtime = runtimeMinutes(episode.runtime),
                        available = true
                    )
                }
            },
            ageRating = item.maturity?.takeIf { it.isNotBlank() },
            country = null,
            awards = null,
            language = item.language,
            links = emptyList(),
            imdbId = item.imdbId.imdb()
        )
    }

    fun episode(seriesId: String, season: Int, episode: NexflixEpisode): NexflixItem.Episode =
        NexflixItem.Episode(contentId = episode.id, seriesId = seriesId, season = season, episode = episode.number)

    fun externalIds(imdbId: String?): TrackingExternalIds = TrackingExternalIds(imdb = imdbId.imdb())

    fun userState(item: NexflixItem, progress: NexflixProgress): ServerUserState = ServerUserState(
        videoId = ref(item),
        positionMs = progress.positionSeconds.toMillis(),
        durationMs = progress.durationSeconds.toMillis(),
        played = isCompleted(progress.positionSeconds, progress.durationSeconds),
        lastPlayedEpochMs = progress.lastWatchedAt.epochMs(),
        season = progress.season,
        episode = progress.episode,
        title = if (item is NexflixItem.Episode) episodeTitle(progress.content.episodeLabel) else progress.content.title
    )

    /** Server Continue Watching row entry: the movie, or the series for an in-progress episode. */
    fun resumeTitle(progress: NexflixProgress): ServerTitle? {
        val entry = resumeEntry(progress) ?: return null
        return ServerTitle(
            preview = entry.parent.copy(
                description = progress.content.episodeLabel ?: progress.content.description
            )
        )
    }

    /** A Nexflix progress record with the movie or series and episode context Continue Watching shows. */
    fun resumeEntry(progress: NexflixProgress): ServerResumeEntry? {
        val content = progress.content
        val art = media(content.artUrl)
        return when (content.contentType) {
            ServerMediaKind.MOVIE.contentType -> {
                val item = NexflixItem.Movie(content.id, content.title, content.year)
                ServerResumeEntry(
                    parent = preview(ref(item), ServerMediaKind.MOVIE, content, art),
                    state = userState(item, progress)
                )
            }

            ServerMediaKind.SERIES.contentType -> {
                // Episode records name their series and position; without them the episode is unknown.
                val seriesId = progress.seriesId ?: content.detailsId ?: return null
                val item = NexflixItem.Episode(
                    contentId = content.id,
                    seriesId = seriesId,
                    season = progress.season ?: return null,
                    episode = progress.episode ?: return null
                )
                ServerResumeEntry(
                    parent = preview(ref(NexflixItem.Series(seriesId)), ServerMediaKind.SERIES, content, art),
                    state = userState(item, progress),
                    episodeThumbnail = art
                )
            }

            else -> null
        }
    }

    private fun preview(id: String, kind: ServerMediaKind, content: NexflixSnapshot, art: String?) = MetaPreview(
        id = id,
        type = kind.domainType(),
        rawType = kind.contentType,
        name = content.title,
        poster = art,
        posterShape = PosterShape.POSTER,
        background = art,
        logo = null,
        description = content.description,
        releaseInfo = content.year?.toString(),
        imdbRating = null,
        genres = content.genres
    )
}

/** Nexflix clients label episodes as `S1 E2 · Title`. */
internal fun episodeTitle(label: String?): String? =
    label?.substringAfter(" · ", missingDelimiterValue = "")?.trim()?.takeIf { it.isNotEmpty() }

/** Matches the Nexflix server rule for removing a title from Continue Watching. */
internal fun isCompleted(positionSeconds: Double, durationSeconds: Double): Boolean =
    durationSeconds > 0 && positionSeconds >= durationSeconds - COMPLETION_MARGIN_SECONDS

/** Parses a .NET `TimeSpan` such as `01:52:00` or `1.02:00:00`. */
internal fun runtimeSeconds(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = text.split(':')
    if (parts.size != 3) return null
    val days = if ('.' in parts[0]) parts[0].substringBefore('.').toLongOrNull() ?: return null else 0L
    val hours = parts[0].substringAfter('.').toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].substringBefore('.').toLongOrNull() ?: return null
    return ((days * 24 + hours) * 60 + minutes) * 60 + seconds
}

internal fun runtimeMinutes(value: String?): Int? =
    runtimeSeconds(value)?.takeIf { it > 0 }?.let { ((it + 30) / 60).toInt() }

/** Nexflix serializes `DateTimeOffset`, which carries an offset such as `+00:00`. */
internal fun String?.epochMs(): Long? =
    this?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

private fun Double.toMillis(): Long = if (isFinite() && this > 0) (this * 1000).roundToLong() else 0L

private fun String?.rating(): Float? = this?.trim()?.toFloatOrNull()?.takeIf { it > 0f }

private fun String?.imdb(): String? = this?.trim()?.takeIf { it.startsWith("tt") }

private const val COMPLETION_MARGIN_SECONDS = 30.0
