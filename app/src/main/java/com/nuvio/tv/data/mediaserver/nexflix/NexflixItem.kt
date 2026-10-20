package com.nuvio.tv.data.mediaserver.nexflix

/**
 * The Nexflix item a Nuvio server ref points at.
 *
 * Portal movie IDs can change while Nuvio keeps a title in Continue Watching, so movie refs carry the
 * title and year that Nexflix uses to re-resolve the exact portal ID. Episode refs carry the series
 * position because Nexflix records watch progress against `seriesId`, `season` and `episode`.
 */
internal sealed interface NexflixItem {
    val contentId: String

    fun encode(): String

    data class Movie(override val contentId: String, val title: String?, val year: Int?) : NexflixItem {
        override fun encode(): String = listOf(MOVIE, contentId, year?.toString().orEmpty(), title.orEmpty()).joinToString(SEPARATOR)
    }

    data class Series(override val contentId: String) : NexflixItem {
        override fun encode(): String = listOf(SERIES, contentId).joinToString(SEPARATOR)
    }

    data class Episode(
        override val contentId: String,
        val seriesId: String,
        val season: Int,
        val episode: Int
    ) : NexflixItem {
        override fun encode(): String =
            listOf(EPISODE, seriesId, season.toString(), episode.toString(), contentId).joinToString(SEPARATOR)
    }

    companion object {
        private const val SEPARATOR = "|"
        private const val MOVIE = "m"
        private const val SERIES = "s"
        private const val EPISODE = "e"

        fun parse(value: String): NexflixItem? {
            val kind = value.substringBefore(SEPARATOR, missingDelimiterValue = "")
            val body = value.substringAfter(SEPARATOR, missingDelimiterValue = "")
            return when (kind) {
                MOVIE -> {
                    // The title is last so that it may itself contain the separator.
                    val parts = body.split(SEPARATOR, limit = 3)
                    val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
                    Movie(id, parts.getOrNull(2)?.takeIf { it.isNotEmpty() }, parts.getOrNull(1)?.toIntOrNull())
                }
                SERIES -> body.takeIf { it.isNotEmpty() && SEPARATOR !in it }?.let(::Series)
                EPISODE -> {
                    val parts = body.split(SEPARATOR, limit = 4)
                    if (parts.size != 4 || parts[0].isEmpty() || parts[3].isEmpty()) return null
                    Episode(
                        contentId = parts[3],
                        seriesId = parts[0],
                        season = parts[1].toIntOrNull() ?: return null,
                        episode = parts[2].toIntOrNull() ?: return null
                    )
                }
                else -> null
            }
        }
    }
}
