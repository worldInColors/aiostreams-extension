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
 * TVDB API v4 client for episode metadata
 * Requires an API key to be configured
 */
object TvDbApi {

    private const val API_URL = "https://api4.thetvdb.com/v4"

    private var authToken: String? = null
    private var tokenExpiry: Long = 0

    @Serializable
    data class LoginResponse(
        val status: String? = null,
        val data: TokenData? = null
    )

    @Serializable
    data class TokenData(
        val token: String? = null
    )

    @Serializable
    data class SeriesResponse(
        val status: String? = null,
        val data: SeriesData? = null
    )

    @Serializable
    data class SeriesData(
        val id: Long? = null,
        val name: String? = null,
        @SerialName("episodes") val episodes: List<EpisodeData>? = null
    )

    @Serializable
    data class EpisodesResponse(
        val status: String? = null,
        val data: List<EpisodeData>? = null,
        val links: LinksData? = null
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
        @SerialName("image") val imageUrl: String? = null,
        @SerialName("runtime") val runtime: Int? = null
    )

    @Serializable
    data class LinksData(
        val prev: Int? = null,
        val next: Int? = null,
        @SerialName("total_items") val totalItems: Int? = null
    )

    @Serializable
    data class SearchResponse(
        val status: String? = null,
        val data: List<SearchResult>? = null
    )

    @Serializable
    data class SearchResult(
        val id: Long? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("primary_type") val primaryType: String? = null,
        @SerialName("tvdb_id") val tvdbId: Long? = null,
        @SerialName("imdb_id") val imdbId: String? = null,
        val type: String? = null
    )

    @Serializable
    data class SeriesExtendedResponse(
        val status: String? = null,
        val data: SeriesExtendedData? = null
    )

    @Serializable
    data class SeriesExtendedData(
        val id: Long? = null,
        val name: String? = null,
        @SerialName("episodes") val episodes: List<EpisodeData>? = null,
        @SerialName("remote_ids") val remoteIds: List<RemoteId>? = null
    )

    @Serializable
    data class RemoteId(
        val id: String? = null,
        val type: Int? = null,  // 1=IMDB, 2=TMDB, 3=TvDB, etc.
        val sourceName: String? = null
    )

    /**
     * Login to TVDB API and get auth token
     */
    fun login(client: OkHttpClient, apiKey: String): Boolean {
        return try {
            val url = "$API_URL/login"
            val body = """{"apikey": "$apiKey"}"""
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val loginResponse = Json { ignoreUnknownKeys = true }.decodeFromString<LoginResponse>(response.body.string())
                authToken = loginResponse.data?.token
                // Token expires in 30 days, but refresh weekly
                tokenExpiry = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get auth headers
     */
    private fun getHeaders(): Headers {
        return Headers.Builder()
            .add("Authorization", "Bearer ${authToken ?: ""}")
            .add("Accept", "application/json")
            .build()
    }

    /**
     * Search for a series by name
     */
    fun searchSeries(client: OkHttpClient, apiKey: String, query: String): List<SearchResult> {
        ensureLoggedIn(client, apiKey)
        
        return try {
            val url = "$API_URL/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}&type=series"
            val response = client.newCall(GET(url, getHeaders())).execute()
            
            if (response.isSuccessful) {
                val searchResponse = Json { ignoreUnknownKeys = true }.decodeFromString<SearchResponse>(response.body.string())
                searchResponse.data ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get series info by TVDB ID with all episodes
     */
    fun getSeriesExtended(client: OkHttpClient, apiKey: String, tvdbId: Long): SeriesExtendedData? {
        ensureLoggedIn(client, apiKey)
        
        return try {
            val url = "$API_URL/series/$tvdbId/extended?meta=episodes"
            val response = client.newCall(GET(url, getHeaders())).execute()
            
            if (response.isSuccessful) {
                val seriesResponse = Json { ignoreUnknownKeys = true }.decodeFromString<SeriesExtendedResponse>(response.body.string())
                seriesResponse.data
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get all episodes for a series (handles pagination)
     */
    fun getAllEpisodes(client: OkHttpClient, apiKey: String, tvdbId: Long): List<EpisodeData> {
        ensureLoggedIn(client, apiKey)
        
        val allEpisodes = mutableListOf<EpisodeData>()
        var page = 1
        
        try {
            // First try extended endpoint which includes all episodes
            val extended = getSeriesExtended(client, apiKey, tvdbId)
            if (!extended?.episodes.isNullOrEmpty()) {
                return extended!!.episodes!!
            }
            
            // Fallback to paginated endpoint
            while (true) {
                val url = "$API_URL/series/$tvdbId/episodes/default?page=$page"
                val response = client.newCall(GET(url, getHeaders())).execute()
                
                if (!response.isSuccessful) break
                
                val episodesResponse = Json { ignoreUnknownKeys = true }.decodeFromString<EpisodesResponse>(response.body.string())
                val episodes = episodesResponse.data ?: break
                
                allEpisodes.addAll(episodes)
                
                if (episodesResponse.links?.next == null) break
                page = episodesResponse.links.next
            }
        } catch (e: Exception) {
            // Return what we have
        }
        
        return allEpisodes
    }

    /**
     * Find TVDB ID from IMDB or other external IDs
     */
    fun findTvDbId(client: OkHttpClient, apiKey: String, imdbId: String): Long? {
        ensureLoggedIn(client, apiKey)
        
        return try {
            val url = "$API_URL/search/remoteid?id=$imdbId"
            val response = client.newCall(GET(url, getHeaders())).execute()
            
            if (response.isSuccessful) {
                val searchResponse = Json { ignoreUnknownKeys = true }.decodeFromString<SearchResponse>(response.body.string())
                searchResponse.data?.firstOrNull()?.tvdbId
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ensure we have a valid auth token
     */
    private fun ensureLoggedIn(client: OkHttpClient, apiKey: String) {
        if (authToken == null || System.currentTimeMillis() > tokenExpiry) {
            login(client, apiKey)
        }
    }

    /**
     * Convert episode list to a map keyed by episode number
     */
    fun episodesToMap(episodes: List<EpisodeData>, useAbsoluteNumbering: Boolean = true): Map<String, EpisodeData> {
        val map = mutableMapOf<String, EpisodeData>()
        
        episodes.forEach { ep ->
            if (useAbsoluteNumbering && ep.absoluteNumber != null) {
                map[ep.absoluteNumber.toString()] = ep
            } else if (ep.episodeNumber != null && ep.seasonNumber != null) {
                // Use season/episode format as key
                if (ep.seasonNumber == 1) {
                    map[ep.episodeNumber.toString()] = ep
                } else {
                    map["S${ep.seasonNumber}E${ep.episodeNumber}"] = ep
                }
            }
        }
        
        return map
    }
}