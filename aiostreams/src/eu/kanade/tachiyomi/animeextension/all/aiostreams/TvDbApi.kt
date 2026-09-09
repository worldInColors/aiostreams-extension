package eu.kanade.tachiyomi.animeextension.all.aiostreams

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * TVDB API v4 client — optional enrichment for episode metadata.
 *
 * The TVDB series id is resolved without a key (from Kitsu mappings / AniZip),
 * but reading data from TVDB itself requires an API key from thetvdb.com.
 * Responses are English by default.
 */
object TvDbApi {

    private const val API_URL = "https://api4.thetvdb.com/v4"

    private var authToken: String? = null
    private var tokenExpiry: Long = 0

    // Shared Json instance - isLenient needed because TVDB returns some numbers as strings
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    data class LoginResponse(
        val status: String? = null,
        val data: TokenData? = null,
    )

    @Serializable
    data class TokenData(
        val token: String? = null,
    )

    @Serializable
    data class SeriesExtendedResponse(
        val status: String? = null,
        val data: SeriesExtendedData? = null,
    )

    @Serializable
    data class SeriesExtendedData(
        val id: Long? = null,
        val name: String? = null,
        val overview: String? = null,
        val image: String? = null,
        @SerialName("episodes") val episodes: List<EpisodeData>? = null,
        @SerialName("remoteIds") val remoteIds: List<RemoteId>? = null,
    )

    /** /series/{id}/episodes/default returns data as an object wrapping the episode list. */
    @Serializable
    data class EpisodesDefaultResponse(
        val status: String? = null,
        val data: EpisodesDefaultData? = null,
        val links: LinksData? = null,
    )

    @Serializable
    data class EpisodesDefaultData(
        @SerialName("episodes") val episodes: List<EpisodeData>? = null,
        val seasons: List<SeasonData>? = null,
    )

    @Serializable
    data class SeasonData(
        val id: Long? = null,
        val name: String? = null,
        val number: Int? = null,
    )

    @Serializable
    data class EpisodeData(
        val id: Long? = null,
        @SerialName("seasonNumber") val seasonNumber: Int? = null,
        @SerialName("number") val episodeNumber: Int? = null,
        @SerialName("absoluteNumber") val absoluteNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("aired") val airDate: String? = null,
        @SerialName("runtime") val runtime: Int? = null,
        @SerialName("image") val imageUrl: String? = null,
        val isMovie: Int? = null,
    )

    @Serializable
    data class LinksData(
        val first: Int? = null,
        val prev: Int? = null,
        val next: Int? = null,
        val last: Int? = null,
        @SerialName("total_items") val totalItems: Long? = null,
    )

    @Serializable
    data class SearchResponse(
        val status: String? = null,
        val data: List<SearchResult>? = null,
    )

    @Serializable
    data class SearchResult(
        val id: String? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("primary_type") val primaryType: String? = null,
        @SerialName("tvdb_id") val tvdbId: String? = null,
        @SerialName("imdb_id") val imdbId: String? = null,
        val type: String? = null,
    )

    @Serializable
    data class RemoteId(
        val id: String? = null,
        val type: Int? = null, // 2=IMDB, 4=TMDB, ...
        val sourceName: String? = null,
    )

    /**
     * Login to TVDB API and get a bearer token.
     */
    fun login(client: OkHttpClient, apiKey: String): Boolean {
        return try {
            val url = "$API_URL/login"
            val body = """{"apikey": "$apiKey"}"""
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val loginResponse = json.decodeFromString<LoginResponse>(response.body.string())
                    authToken = loginResponse.data?.token
                    // Token itself is valid ~30 days; refresh weekly
                    tokenExpiry = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
                    authToken != null
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True when a token is cached and not close to expiring.
     */
    fun isAuthenticated(): Boolean =
        authToken != null && System.currentTimeMillis() < tokenExpiry

    private fun getHeaders(): Headers {
        return Headers.Builder()
            .add("Authorization", "Bearer ${authToken ?: ""}")
            .add("Accept", "application/json")
            .build()
    }

    private fun ensureLoggedIn(client: OkHttpClient, apiKey: String) {
        if (!isAuthenticated()) {
            login(client, apiKey)
        }
    }

    /**
     * Get a series with all of its episodes in one call.
     */
    fun getSeriesExtended(client: OkHttpClient, apiKey: String, tvdbId: Long): SeriesExtendedData? {
        ensureLoggedIn(client, apiKey)
        if (authToken == null) return null

        return try {
            val url = "$API_URL/series/$tvdbId/extended?meta=episodes"
            client.newCall(GET(url, getHeaders())).execute().use { response ->
                if (response.isSuccessful) {
                    json.decodeFromString<SeriesExtendedResponse>(response.body.string()).data
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get all episodes for a series, trying the extended endpoint first and
     * falling back to the paginated default episode order.
     */
    fun getAllEpisodes(client: OkHttpClient, apiKey: String, tvdbId: Long): List<EpisodeData> {
        val extended = getSeriesExtended(client, apiKey, tvdbId)
        if (!extended?.episodes.isNullOrEmpty()) {
            return extended!!.episodes!!
        }

        val allEpisodes = mutableListOf<EpisodeData>()
        var page = 1
        try {
            while (page in 1..100) {
                val url = "$API_URL/series/$tvdbId/episodes/default?page=$page"
                val body = client.newCall(GET(url, getHeaders())).execute().use { response ->
                    if (response.isSuccessful) response.body.string() else return@use null
                } ?: break

                val parsed = json.decodeFromString<EpisodesDefaultResponse>(body)
                val episodes = parsed.data?.episodes ?: break
                allEpisodes.addAll(episodes)

                if (parsed.links?.next == null) break
                page = parsed.links.next
            }
        } catch (e: Exception) {
            // Return what we have
        }
        return allEpisodes
    }

    /**
     * Find a TVDB series id from an external id (e.g. an IMDB id).
     */
    fun findTvDbId(client: OkHttpClient, apiKey: String, imdbId: String): Long? {
        ensureLoggedIn(client, apiKey)
        if (authToken == null) return null

        return try {
            val url = "$API_URL/search/remoteid?id=$imdbId"
            client.newCall(GET(url, getHeaders())).execute().use { response ->
                if (response.isSuccessful) {
                    json.decodeFromString<SearchResponse>(response.body.string())
                        .data?.firstOrNull()?.tvdbId?.toLongOrNull()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Index episodes by absolute number (fallback: season 1 episode number,
     * then "S{season}E{episode}") so callers can look up by episode number.
     */
    fun episodesToMap(episodes: List<EpisodeData>, useAbsoluteNumbering: Boolean = true): Map<String, EpisodeData> {
        val map = mutableMapOf<String, EpisodeData>()

        episodes.forEach { ep ->
            when {
                useAbsoluteNumbering && ep.absoluteNumber != null ->
                    map[ep.absoluteNumber.toString()] = ep
                ep.seasonNumber == 1 && ep.episodeNumber != null ->
                    map[ep.episodeNumber.toString()] = ep
                ep.seasonNumber != null && ep.episodeNumber != null ->
                    map["S${ep.seasonNumber}E${ep.episodeNumber}"] = ep
            }
        }

        return map
    }
}
