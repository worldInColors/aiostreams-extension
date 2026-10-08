package eu.kanade.tachiyomi.animeextension.all.aiostreams

import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * AniList GraphQL client — the airing clock. For currently-airing shows,
 * nextAiringEpisode/airingSchedule is the most reliable source of "which
 * episodes have actually aired" (Kitsu lists titleless/dateless stubs and
 * MAL lags on brand-new episodes). streamingEpisodes additionally carries
 * simulcast-sourced English titles and thumbnails.
 */
object AniListApi {

    private const val API_URL = "https://graphql.anilist.co"

    data class AiringInfo(
        /** Episode number of the next episode to air (null when done/unknown) */
        val nextEpisode: Int?,
        /** Episode number -> air time in epoch millis */
        val schedule: Map<Int, Long>,
        /** Episode number -> simulcast English title */
        val streamingTitles: Map<Int, String>,
        /** Episode number -> simulcast thumbnail */
        val streamingThumbnails: Map<Int, String>,
    )

    private val QUERY = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                episodes
                nextAiringEpisode { episode airingAt }
                airingSchedule(perPage: 25) { nodes { episode airingAt } }
                streamingEpisodes { title thumbnail }
            }
        }
    """.trimIndent()

    /**
     * Fetch airing info for an AniList anime id, or null on any failure.
     */
    fun fetchAiringInfo(client: OkHttpClient, anilistId: Long): AiringInfo? {
        return try {
            val payload = JSONObject()
                .put("query", QUERY)
                .put("variables", JSONObject().put("id", anilistId))
                .toString()

            val request = POST(
                API_URL,
                body = payload.toRequestBody("application/json".toMediaType()),
                headers = Headers.headersOf("Content-Type", "application/json"),
            )
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val media = JSONObject(response.body.string())
                    .optJSONObject("data")
                    ?.optJSONObject("Media")
                    ?: return null

                val nextEpisode = media.optJSONObject("nextAiringEpisode")
                    ?.takeIf { it.has("episode") }
                    ?.optInt("episode")

                val schedule = mutableMapOf<Int, Long>()
                media.optJSONObject("airingSchedule")
                    ?.optJSONArray("nodes")
                    ?.let { nodes ->
                        for (i in 0 until nodes.length()) {
                            val node = nodes.optJSONObject(i) ?: continue
                            val episode = node.optInt("episode", -1)
                            val airingAt = node.optLong("airingAt", -1L)
                            if (episode > 0 && airingAt > 0) {
                                schedule[episode] = airingAt * 1000L
                            }
                        }
                    }

                val streamingTitles = mutableMapOf<Int, String>()
                val streamingThumbnails = mutableMapOf<Int, String>()
                media.optJSONArray("streamingEpisodes")?.let { streams ->
                    for (i in 0 until streams.length()) {
                        val entry = streams.optJSONObject(i) ?: continue
                        val rawTitle = entry.optString("title").orEmpty()
                        val thumbnail = entry.optString("thumbnail").orEmpty()
                        // Titles look like "Episode 3 - Foo"; fall back to position
                        val numberMatch = Regex("^Episode\\s*(\\d+)\\b").find(rawTitle)
                        val number = numberMatch?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                        val cleanTitle = rawTitle.removePrefix(numberMatch?.value.orEmpty())
                            .trimStart(' ', '-', '–')
                            .takeIf { it.isNotBlank() } ?: continue
                        streamingTitles[number] = cleanTitle
                        if (thumbnail.isNotBlank()) streamingThumbnails[number] = thumbnail
                    }
                }

                AiringInfo(nextEpisode, schedule, streamingTitles, streamingThumbnails)
            }
        } catch (e: Exception) {
            null
        }
    }
}
