package com.nuvio.tv.data.mediaserver.nexflix

import kotlinx.serialization.Serializable

// Wire models for the Nexflix API (https://github.com/abdulaleem1/nexflix). Nexflix keeps portal
// credentials, cookies and stream commands server-side; every media URL it returns is a relative
// `/api/assets/...` or `/api/stream/...` proxy path on the Nexflix server itself.

@Serializable
internal data class NexflixHealth(val status: String? = null)

@Serializable
internal data class NexflixPortal(
    val id: String,
    val name: String = "",
    val state: String = "disconnected"
) {
    val isConnected: Boolean
        get() = state.equals("connected", ignoreCase = true)
}

@Serializable
internal data class NexflixCategory(val id: String, val name: String = "")

@Serializable
internal data class NexflixVersion(
    val id: String,
    val language: String? = null,
    val label: String = ""
)

/** A movie, series or search result row (`MovieResponse`, `SeriesResponse`, `SearchResultResponse`). */
@Serializable
internal data class NexflixTitle(
    val id: String,
    val kind: String? = null,
    val title: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val maturity: String? = null,
    val language: String? = null,
    val versions: List<NexflixVersion> = emptyList()
)

@Serializable
internal data class NexflixMovieDetails(
    val id: String,
    val title: String = "",
    val description: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val maturity: String? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val imdbId: String? = null,
    val language: String? = null,
    val versions: List<NexflixVersion> = emptyList()
)

@Serializable
internal data class NexflixEpisode(
    val id: String,
    val number: Int,
    val title: String = "",
    val description: String? = null,
    val runtime: String? = null,
    val thumbnailUrl: String? = null
)

@Serializable
internal data class NexflixSeason(
    val number: Int,
    val episodes: List<NexflixEpisode> = emptyList()
)

@Serializable
internal data class NexflixSeriesDetails(
    val id: String,
    val title: String = "",
    val description: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val maturity: String? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val seasons: List<NexflixSeason> = emptyList(),
    val imdbId: String? = null,
    val language: String? = null,
    val versions: List<NexflixVersion> = emptyList()
)

@Serializable
internal data class NexflixPlaybackSource(
    val url: String,
    val protocol: String = "unknown",
    val browserPlayable: Boolean = false
)

/** Nexflix `ContentSnapshot`: the durable title context stored with My List and watch progress. */
@Serializable
internal data class NexflixSnapshot(
    val id: String,
    val contentType: String,
    val title: String,
    val year: Int? = null,
    val runtime: String? = null,
    val maturity: String? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val artUrl: String? = null,
    val episodeLabel: String? = null,
    val accent: String? = null,
    val portal: Boolean = true,
    val detailsId: String? = null
)

@Serializable
internal data class NexflixProgress(
    val content: NexflixSnapshot,
    val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val lastWatchedAt: String? = null
)

@Serializable
internal data class NexflixProgressRequest(
    val content: NexflixSnapshot,
    val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val positionSeconds: Double,
    val durationSeconds: Double
)
