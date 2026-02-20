package eu.kanade.tachiyomi.animeextension.all.aiostreams

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * AniDB API Client
 * Provides episode metadata from AniDB database
 * 
 * Note: AniDB has strict rate limiting - 1 request per 2 seconds
 * API endpoint: https://api.anidb.net:9001/httpapi
 */
object AniDbApi {
    private const val ANIDB_API_BASE = "https://api.anidb.net:9001/httpapi"
    private const val ANIDB_CLIENT = "aiostreams"
    private const val ANIDB_CLIENT_VER = "1"
    
    // Cache for anime data to reduce API calls
    private val animeCache = mutableMapOf<Long, AniDbAnime>()
    private var lastRequestTime = 0L
    private const val MIN_REQUEST_INTERVAL = 2500L // 2.5 seconds to be safe
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Get anime data from AniDB by ID
     * Uses caching to minimize API calls
     */
    suspend fun getAnimeById(client: OkHttpClient, anidbId: Long): AniDbAnime? {
        // Check cache first
        animeCache[anidbId]?.let { return it }
        
        // Rate limiting
        enforceRateLimit()
        
        return try {
            val request = GET(
                "$ANIDB_API_BASE?" +
                "client=$ANIDB_CLIENT&" +
                "clientver=$ANIDB_CLIENT_VER&" +
                "protover=1&" +
                "request=anime&" +
                "aid=$anidbId"
            )
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                parseAnimeResponse(response)?.also { anime ->
                    animeCache[anidbId] = anime
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get episode titles from AniDB
     * Returns a map of episode number -> title (preferring English, falling back to romaji/native)
     */
    suspend fun getEpisodeTitles(client: OkHttpClient, anidbId: Long): Map<String, String> {
        val anime = getAnimeById(client, anidbId) ?: return emptyMap()
        
        return anime.episodes?.associate { ep ->
            val epNum = ep.epno ?: "1"
            val title = getBestTitle(ep.title)
            epNum to title
        } ?: emptyMap()
    }

    /**
     * Get the best available title from a list of titles
     * Priority: x-jat (romaji) > en > ja > main
     */
    private fun getBestTitle(titles: List<AniDbTitle>?): String {
        if (titles.isNullOrEmpty()) return ""
        
        // Priority order for title selection
        val priorityOrder = listOf("x-jat", "en", "ja", "main", "x-unk")
        
        for (lang in priorityOrder) {
            titles.find { it.lang == lang && !it.title.isNullOrBlank() }?.title?.let {
                return it
            }
        }
        
        // Fallback to first available title
        return titles.firstOrNull { !it.title.isNullOrBlank() }?.title ?: ""
    }

    /**
     * Parse AniDB XML response to AniDbAnime object
     * Note: AniDB returns XML, we'll parse it simply
     */
    private fun parseAnimeResponse(response: Response): AniDbAnime? {
        return try {
            val xml = response.body.string()
            parseAniDbXml(xml)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Simple XML parser for AniDB response
     */
    private fun parseAniDbXml(xml: String): AniDbAnime? {
        try {
            // Extract basic anime info
            val idMatch = Regex("""<anime id="(\d+)"""").find(xml)
            val typeMatch = Regex("""<type>([^<]+)</type>""").find(xml)
            val episodeCountMatch = Regex("""<episodecount>(\d+)</episodecount>""").find(xml)
            val startDateMatch = Regex("""<startdate>([^<]+)</startdate>""").find(xml)
            val endDateMatch = Regex("""<enddate>([^<]+)</enddate>""").find(xml)
            
            // Parse titles
            val titles = mutableListOf<AniDbTitle>()
            Regex("""<title[^>]*(?:type="([^"]*)")?[^>]*>([^<]+)</title>""").findAll(xml).forEach { match ->
                titles.add(
                    AniDbTitle(
                        type = match.groupValues[1].ifEmpty { "main" },
                        lang = match.groupValues[1].ifEmpty { "main" },
                        title = match.groupValues[2]
                    )
                )
            }
            
            // Parse episodes
            val episodes = mutableListOf<AniDbEpisode>()
            Regex("""<episode id="(\d+)">([\s\S]*?)</episode>""").findAll(xml).forEach { epMatch ->
                val epXml = epMatch.groupValues[2]
                val epId = epMatch.groupValues[1].toLongOrNull()
                
                val epnoMatch = Regex("""<epno>([^<]+)</epno>""").find(epXml)
                val lengthMatch = Regex("""<length>(\d+)</length>""").find(epXml)
                val airdateMatch = Regex("""<airdate>([^<]+)</airdate>""").find(epXml)
                val ratingMatch = Regex("""<rating[^>]*>([^<]+)</rating>""").find(epXml)
                val summaryMatch = Regex("""<summary>([^<]*)</summary>""").find(epXml)
                
                // Parse episode titles
                val epTitles = mutableListOf<AniDbTitle>()
                Regex("""<title[^>]*(?:xml:lang="([^"]*)")?[^>]*>([^<]+)</title>""").findAll(epXml).forEach { titleMatch ->
                    epTitles.add(
                        AniDbTitle(
                            lang = titleMatch.groupValues[1].ifEmpty { "en" },
                            title = titleMatch.groupValues[2]
                        )
                    )
                }
                
                episodes.add(
                    AniDbEpisode(
                        id = epId,
                        epno = epnoMatch?.groupValues?.get(1),
                        length = lengthMatch?.groupValues?.get(1)?.toIntOrNull(),
                        airdate = airdateMatch?.groupValues?.get(1),
                        rating = ratingMatch?.groupValues?.get(1),
                        title = epTitles.ifEmpty { null },
                        summary = summaryMatch?.groupValues?.get(1)
                    )
                )
            }
            
            return AniDbAnime(
                id = idMatch?.groupValues?.get(1)?.toLongOrNull(),
                type = typeMatch?.groupValues?.get(1),
                episodecount = episodeCountMatch?.groupValues?.get(1)?.toIntOrNull(),
                startDate = startDateMatch?.groupValues?.get(1),
                endDate = endDateMatch?.groupValues?.get(1),
                titles = titles.ifEmpty { null },
                episodes = episodes.ifEmpty { null }
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Enforce rate limiting between API calls
     */
    private suspend fun enforceRateLimit() {
        val now = System.currentTimeMillis()
        val timeSinceLastRequest = now - lastRequestTime
        
        if (timeSinceLastRequest < MIN_REQUEST_INTERVAL) {
            Thread.sleep(MIN_REQUEST_INTERVAL - timeSinceLastRequest)
        }
        
        lastRequestTime = System.currentTimeMillis()
    }

    /**
     * Clear the cache (call periodically to refresh data)
     */
    fun clearCache() {
        animeCache.clear()
    }
}