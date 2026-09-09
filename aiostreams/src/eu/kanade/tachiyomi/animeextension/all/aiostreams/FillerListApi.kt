package eu.kanade.tachiyomi.animeextension.all.aiostreams

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Fetches filler episode data from chaiwala-anime API
 * Falls back to scraping animefillerlist.com if API fails
 */
object FillerListApi {

    private const val API_URL = "https://filler-list.chaiwala-anime.workers.dev"
    private const val SCRAPE_URL = "https://www.animefillerlist.com/shows"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    data class FillerApiResponse(
        val animeName: String? = null,
        val fillerEpisodes: List<Int>? = null,
        val cannonEpisodes: List<Int>? = null,
        val animecanonsEp: List<Int>? = null
    )

    /**
     * Fetches filler episodes for an anime using the new API
     * @param client OkHttp client to use
     * @param animeName The anime name as slug (e.g., "naruto", "one-piece")
     * @return Set of episode numbers that are filler, or empty set if not found
     */
    fun getFillerEpisodes(client: OkHttpClient, animeName: String): Set<Int> {
        // Try new API first
        return try {
            val response = client.newCall(GET("$API_URL/$animeName")).execute()
            if (response.isSuccessful) {
                val apiResponse = json.decodeFromString<FillerApiResponse>(response.body.string())
                val fillers = apiResponse.fillerEpisodes?.toSet() ?: emptySet()
                if (fillers.isNotEmpty()) {
                    return fillers
                }
            }
            // Fallback to scraping if API returns empty
            getFillerEpisodesScrape(client, animeName)
        } catch (e: Exception) {
            // Fallback to scraping on error
            getFillerEpisodesScrape(client, animeName)
        }
    }

    /**
     * Fallback: Scrapes animefillerlist.com to get filler episode data
     */
    private fun getFillerEpisodesScrape(client: OkHttpClient, animeName: String): Set<Int> {
        return try {
            val response = client.newCall(GET("$SCRAPE_URL/$animeName")).execute()
            if (response.isSuccessful) {
                parseFillerEpisodes(response)
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Fetches both filler and mixed canon/filler episodes
     * @return Pair of (filler episodes, mixed canon/filler episodes)
     */
    fun getFillerAndMixedEpisodes(client: OkHttpClient, animeName: String): Pair<Set<Int>, Set<Int>> {
        return try {
            val response = client.newCall(GET("$API_URL/$animeName")).execute()
            if (response.isSuccessful) {
                val apiResponse = json.decodeFromString<FillerApiResponse>(response.body.string())
                val fillers = apiResponse.fillerEpisodes?.toSet() ?: emptySet()
                // Use animecanonsEp as mixed if available
                val mixed = apiResponse.animecanonsEp?.toSet() ?: emptySet()
                fillers to mixed
            } else {
                getFillerAndMixedEpisodesScrape(client, animeName)
            }
        } catch (e: Exception) {
            getFillerAndMixedEpisodesScrape(client, animeName)
        }
    }

    private fun getFillerAndMixedEpisodesScrape(client: OkHttpClient, animeName: String): Pair<Set<Int>, Set<Int>> {
        return try {
            val response = client.newCall(GET("$SCRAPE_URL/$animeName")).execute()
            if (response.isSuccessful) {
                parseFillerAndMixedEpisodes(response)
            } else {
                emptySet<Int>() to emptySet()
            }
        } catch (e: Exception) {
            emptySet<Int>() to emptySet()
        }
    }

    private fun parseFillerEpisodes(response: Response): Set<Int> {
        val html = response.body.string()
        val doc = org.jsoup.Jsoup.parse(html)
        val fillerEpisodes = mutableSetOf<Int>()

        // Parse filler episodes - look for span.Episodes after the Label
        doc.select("div.filler span.Label").forEach { element ->
            if (element.text().trim() == "Filler Episodes:") {
                val episodesSpan = element.nextElementSibling()
                if (episodesSpan != null && episodesSpan.tagName() == "span") {
                    episodesSpan.select("a").forEach { link ->
                        val epNum = link.text().trim().toIntOrNull()
                        if (epNum != null) {
                            fillerEpisodes.add(epNum)
                        }
                    }
                }
            }
        }

        if (fillerEpisodes.isEmpty()) {
            doc.select("div.filler span.Episodes").forEach { element ->
                val episodeText = element.text().trim()
                fillerEpisodes.addAll(parseEpisodeRanges(episodeText))
            }
        }

        return fillerEpisodes
    }

    private fun parseFillerAndMixedEpisodes(response: Response): Pair<Set<Int>, Set<Int>> {
        val html = response.body.string()
        val doc = org.jsoup.Jsoup.parse(html)
        val fillerEpisodes = mutableSetOf<Int>()
        val mixedEpisodes = mutableSetOf<Int>()

        doc.select("div.filler span.Label").forEach { element ->
            if (element.text().trim() == "Filler Episodes:") {
                val episodesSpan = element.nextElementSibling()
                if (episodesSpan != null) {
                    episodesSpan.select("a").forEach { link ->
                        val epNum = link.text().trim().toIntOrNull()
                        if (epNum != null) {
                            fillerEpisodes.add(epNum)
                        }
                    }
                }
            }
        }

        doc.select("div.mixed_canon\\/filler span.Label").forEach { element ->
            if (element.text().trim() == "Mixed Canon/Filler Episodes:") {
                val episodesSpan = element.nextElementSibling()
                if (episodesSpan != null) {
                    episodesSpan.select("a").forEach { link ->
                        val epNum = link.text().trim().toIntOrNull()
                        if (epNum != null) {
                            mixedEpisodes.add(epNum)
                        }
                    }
                }
            }
        }

        return fillerEpisodes to mixedEpisodes
    }

    private fun parseEpisodeRanges(rangeText: String): Set<Int> {
        val episodes = mutableSetOf<Int>()
        if (rangeText.isBlank()) return episodes

        rangeText.split(",").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                val rangeParts = trimmed.split("-")
                if (rangeParts.size == 2) {
                    val start = rangeParts[0].trim().toIntOrNull()
                    val end = rangeParts[1].trim().toIntOrNull()
                    if (start != null && end != null) {
                        for (i in start..end) {
                            episodes.add(i)
                        }
                    }
                }
            } else {
                trimmed.toIntOrNull()?.let { episodes.add(it) }
            }
        }

        return episodes
    }

    /**
     * Converts an anime title to a likely URL slug
     * e.g., "Naruto Shippuden" -> "naruto-shippuden"
     */
    fun titleToSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }
}
