package eu.kanade.tachiyomi.animeextension.all.aiostreams

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Jikan (unofficial MyAnimeList API) client — episode titles, airdates and
 * filler/recap flags, 100 episodes per page.
 *
 * Uses the jikanfortheweebs mirror with the official api.jikan.moe as
 * fallback. Rate limits (per IP): 60/min sustained, 15 burst — pages are
 * fetched with pacing and one retry.
 */
object JikanApi {

    private const val MIRROR_URL = "https://jikanfortheweebs.midnightignite.me/v4"
    private const val OFFICIAL_URL = "https://api.jikan.moe/v4"
    private const val PAGE_SIZE = 100

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    data class EpisodesResponse(
        val data: List<JikanEpisode>? = null,
        val pagination: Pagination? = null,
    )

    @Serializable
    data class Pagination(
        @SerialName("last_visible_page") val lastVisiblePage: Int? = null,
        @SerialName("has_next_page") val hasNextPage: Boolean? = null,
    )

    @Serializable
    data class JikanEpisode(
        @SerialName("mal_id") val malId: Int? = null,
        val title: String? = null,
        @SerialName("title_romanji") val titleRomanji: String? = null,
        val aired: String? = null,
        val filler: Boolean? = null,
        val recap: Boolean? = null,
    )

    // ---- browse listings (this-season calendar, random) ----

    @Serializable
    data class SeasonListResponse(
        val data: List<JikanAnimeSearch>? = null,
        val pagination: Pagination? = null,
    )

    @Serializable
    data class RandomResponse(
        val data: JikanAnimeSearch? = null,
    )

    @Serializable
    data class JikanAnimeSearch(
        @SerialName("mal_id") val malId: Int? = null,
        val title: String? = null,
        @SerialName("title_english") val titleEnglish: String? = null,
        @SerialName("title_japanese") val titleJapanese: String? = null,
        val synopsis: String? = null,
        val type: String? = null,
        val status: String? = null,
        val airing: Boolean? = null,
        val images: JikanImages? = null,
    )

    @Serializable
    data class JikanImages(
        val jpg: JikanImage? = null,
        val webp: JikanImage? = null,
    )

    @Serializable
    data class JikanImage(
        @SerialName("image_url") val imageUrl: String? = null,
        @SerialName("large_image_url") val largeImageUrl: String? = null,
    )

    data class JikanListPage(
        val items: List<JikanAnimeSearch>,
        val hasNextPage: Boolean,
    )

    /** Currently-airing season, paged — the simulcast calendar listing. */
    fun seasonsNowUrl(page: Int): String =
        "$MIRROR_URL/seasons/now".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("sfw", "true")
            .addQueryParameter("limit", "24")
            .build().toString()

    fun randomUrl(): String =
        "$MIRROR_URL/random/anime".toHttpUrl().newBuilder()
            .addQueryParameter("sfw", "true")
            .build().toString()

    fun parseSeasonList(body: String): JikanListPage? {
        val parsed = try {
            json.decodeFromString<SeasonListResponse>(body)
        } catch (e: Exception) {
            return null
        }
        val items = parsed.data.orEmpty().filterNotNull().filter { !it.title.isNullOrBlank() }
        return JikanListPage(items, hasNextPage = parsed.pagination?.hasNextPage == true)
    }

    fun parseRandom(body: String): JikanAnimeSearch? = try {
        json.decodeFromString<RandomResponse>(body).data
    } catch (e: Exception) {
        null
    }

    /**
     * All episodes for a MAL anime, keyed by episode number. Paged at 100 per
     * request; mirror first with the official API as fallback, brief pacing
     * between pages to stay under the burst limit.
     */
    fun fetchEpisodes(client: OkHttpClient, malId: Long): Map<Int, JikanEpisode> {
        val byNumber = mutableMapOf<Int, JikanEpisode>()

        fetchPaged(client, MIRROR_URL, malId, byNumber)
        if (byNumber.isEmpty()) {
            fetchPaged(client, OFFICIAL_URL, malId, byNumber)
        }
        return byNumber
    }

    private fun fetchPaged(client: OkHttpClient, baseUrl: String, malId: Long, out: MutableMap<Int, JikanEpisode>) {
        var page = 1
        while (page <= 30) { // hard cap: 30 pages = 3000 episodes
            val url = "$baseUrl/anime/$malId/episodes".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .build().toString()

            val body = fetchBodyWithRetry(client, url) ?: return
            val parsed = try {
                json.decodeFromString<EpisodesResponse>(body)
            } catch (e: Exception) {
                return
            }
            val episodes = parsed.data.orEmpty()
            if (episodes.isEmpty()) return

            episodes.forEach { ep ->
                val num = ep.malId ?: return@forEach
                out[num] = ep
            }

            if (parsed.pagination?.hasNextPage != true) return
            page++
            try {
                Thread.sleep(100) // keep paged fetches under the burst limit
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun fetchBodyWithRetry(client: OkHttpClient, url: String): String? {
        var body = fetchBody(client, url)
        if (body == null) {
            try {
                Thread.sleep(1000) // one retry after backing off (429/5xx)
            } catch (e: InterruptedException) {
                return null
            }
            body = fetchBody(client, url)
        }
        return body
    }

    private fun fetchBody(client: OkHttpClient, url: String): String? = try {
        client.newCall(GET(url)).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    } catch (e: Exception) {
        null
    }
}
