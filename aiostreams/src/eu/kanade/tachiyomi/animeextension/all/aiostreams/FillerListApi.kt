package eu.kanade.tachiyomi.animeextension.all.aiostreams

import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * Scrapes animefillerlist.com to get filler episode data
 */
object FillerListApi {

    private const val BASE_URL = "https://www.animefillerlist.com/shows"

    /**
     * Fetches filler episodes for an anime
     * @param client OkHttp client to use
     * @param animeName The anime name as used in animefillerlist.com URL (e.g., "naruto", "one-piece")
     * @return Set of episode numbers that are filler, or empty set if not found
     */
    fun getFillerEpisodes(client: OkHttpClient, animeName: String): Set<Int> {
        return try {
            val response = client.newCall(GET("$BASE_URL/$animeName")).execute()
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
            val response = client.newCall(GET("$BASE_URL/$animeName")).execute()
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
        val doc = Jsoup.parse(html)
        val fillerEpisodes = mutableSetOf<Int>()

        // Parse filler episodes - look for span.Episodes after the Label
        doc.select("div.filler span.Label").forEach { element ->
            if (element.text().trim() == "Filler Episodes:") {
                // The episodes are in the next sibling span.Episodes
                val episodesSpan = element.nextElementSibling()
                if (episodesSpan != null && episodesSpan.tagName() == "span") {
                    // Get all episode numbers from <a> tags
                    episodesSpan.select("a").forEach { link ->
                        val epNum = link.text().trim().toIntOrNull()
                        if (epNum != null) {
                            fillerEpisodes.add(epNum)
                        }
                    }
                }
            }
        }

        // Also try direct text parsing as fallback
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
        val doc = Jsoup.parse(html)
        val fillerEpisodes = mutableSetOf<Int>()
        val mixedEpisodes = mutableSetOf<Int>()

        // Parse filler episodes
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

        // Parse mixed canon/filler episodes
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

    /**
     * Parses episode ranges like "1-5, 7, 10-12" into individual episode numbers
     */
    private fun parseEpisodeRanges(rangeText: String): Set<Int> {
        val episodes = mutableSetOf<Int>()
        
        if (rangeText.isBlank()) return episodes

        rangeText.split(",").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                // Range like "1-5"
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
                // Single episode
                trimmed.toIntOrNull()?.let { episodes.add(it) }
            }
        }

        return episodes
    }

    /**
     * Converts an anime title to a likely animefillerlist.com URL slug
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