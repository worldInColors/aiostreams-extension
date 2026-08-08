package eu.kanade.tachiyomi.animeextension.all.aiostreams

import android.app.Application
import android.content.SharedPreferences
import android.os.Environment
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.io.File
import java.util.Locale

class AIOStreams : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AIOStreams"

    override val baseUrl = "https://graphql.anilist.co"

    override val lang = "all"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val query = """
            query (${"$"}page: Int, ${"$"}perPage: Int) {
                Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                    media(type: ANIME, sort: POPULARITY_DESC) {
                        id
                        title { romaji english native }
                        coverImage { large extraLarge }
                        description
                        episodes
                        status
                        seasonYear
                        format
                        genres
                        relations {
                            edges {
                                relationType
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val variables = """{"page": $page, "perPage": 20}"""
        val payload = """{"query": ${JSONObject.quote(query)}, "variables": $variables}"""

        return POST(
            baseUrl,
            body = payload.toRequestBody("application/json".toMediaType()),
            headers = Headers.headersOf("Content-Type", "application/json"),
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonStr = response.body.string()
        val parsed = json.decodeFromString<AniListSearchResponse>(jsonStr)
        val mediaList = parsed.data?.Page?.media.orEmpty()

        val useSeasons = preferences.getBoolean(PREF_USE_SEASONS, PREF_USE_SEASONS_DEFAULT)

        val animeList = mediaList.filterNotNull().map { media ->
            SAnime.create().apply {
                this.title = media.title?.english?.takeIf { it.isNotBlank() }
                    ?: media.title?.romaji.orEmpty()
                thumbnail_url = media.coverImage?.extraLarge?.takeIf { it.isNotBlank() }
                    ?: media.coverImage?.large.orEmpty()
                url = media.id.toString()
                description = media.description?.replace(Regex("<[^>]*>"), "").orEmpty()
                genre = media.genres?.filterNotNull()?.joinToString(", ").orEmpty()
                status = parseAniListStatus(media.status)
                // Only set Seasons mode if enabled AND anime has related entries
                if (useSeasons && hasRelatedSeasonsSimple(media.relations?.edges)) {
                    fetch_type = FetchType.Seasons
                }
            }
        }

        val hasNextPage = parsed.data?.Page?.pageInfo?.hasNextPage ?: false
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val graphQLQuery = """
            query (${"$"}page: Int, ${"$"}search: String) {
                Page(page: ${"$"}page, perPage: 20) {
                    media(type: ANIME, search: ${"$"}search) {
                        id
                        title { romaji english native }
                        coverImage { large extraLarge }
                        description
                        episodes
                        status
                        genres
                        relations {
                            edges {
                                relationType
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val variables = """{"page": $page, "search": ${JSONObject.quote(query)}}"""
        val payload = """{"query": ${JSONObject.quote(graphQLQuery)}, "variables": $variables}"""

        return POST(
            baseUrl,
            body = payload.toRequestBody("application/json".toMediaType()),
            headers = Headers.headersOf("Content-Type", "application/json"),
        )
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        
        // Check for errors
        if (responseBody.contains("\"errors\"")) {
            val errorJson = JSONObject(responseBody)
            val errors = errorJson.optJSONArray("errors")
            val errorMsg = errors?.optJSONObject(0)?.optString("message") ?: "Unknown AniList error"
            throw Exception("AniList API error: $errorMsg")
        }

        val parsed = json.decodeFromString<AniListMediaResponse>(responseBody)
        val media = parsed.data?.Media ?: throw Exception("Failed to parse anime details")
        val title = media.title

        val useSeasons = preferences.getBoolean(PREF_USE_SEASONS, PREF_USE_SEASONS_DEFAULT)

        return SAnime.create().apply {
            val animeTitle = title?.english?.takeIf { it.isNotBlank() } ?: title?.romaji.orEmpty()
            this.title = animeTitle
            thumbnail_url = media.coverImage?.extraLarge?.takeIf { it.isNotBlank() }
                ?: media.coverImage?.large.orEmpty()
            // Include title in URL for filler lookup
            url = "${media.id}|title:${animeTitle.replace("|", " ")}"
            description = buildString {
                media.description?.replace(Regex("<[^>]*>"), "")?.let { 
                    if (it.isNotBlank()) append("$it\n\n") 
                }

                media.averageScore?.let { if (it > 0) append("★ Score: $it/100\n") }

                media.studios?.nodes?.firstOrNull()?.name?.let {
                    append("Studio: $it\n")
                }

                media.format?.let { append("Format: $it\n") }
                media.episodes?.let { append("Episodes: $it\n") }
                "${media.season ?: ""} ${media.seasonYear ?: ""}".trim().takeIf { it.isNotBlank() }?.let {
                    append("Release: $it\n")
                }
            }.trim()

            genre = media.genres?.filterNotNull()?.joinToString(", ").orEmpty()
            status = parseAniListStatus(media.status)
            
            // Set fetch_type based on seasons setting and relations
            if (useSeasons && hasRelatedSeasons(media.relations?.edges)) {
                fetch_type = FetchType.Seasons
            }
        }
    }

    private fun hasRelatedSeasons(edges: List<AniListRelationEdge?>?): Boolean {
        if (edges.isNullOrEmpty()) return false
        return edges.filterNotNull().any { 
            it.relationType in listOf("SEQUEL", "PREQUEL", "SIDE_STORY", "PARENT", "ALTERNATIVE") &&
                it.node != null
        }
    }

    // Simplified check for browse/search (no node data needed)
    private fun hasRelatedSeasonsSimple(edges: List<AniListRelationEdge?>?): Boolean {
        if (edges.isNullOrEmpty()) return false
        return edges.filterNotNull().any { 
            it.relationType in listOf("SEQUEL", "PREQUEL", "SIDE_STORY", "PARENT", "ALTERNATIVE")
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val query = """
            query (${"$"}id: Int) {
                Media(id: ${"$"}id, type: ANIME) {
                    id
                    title { romaji english native }
                    coverImage { extraLarge large }
                    description
                    episodes
                    status
                    seasonYear
                    season
                    format
                    genres
                    averageScore
                    studios { nodes { name } }
                    relations {
                        edges {
                            relationType
                            node {
                                id
                                title { romaji english native }
                                coverImage { extraLarge large }
                                episodes
                                status
                                format
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val baseId = anime.url.split("|").first()
        val variables = """{"id": $baseId}"""
        val payload = """{"query": ${JSONObject.quote(query)}, "variables": $variables}"""

        return POST(
            baseUrl,
            body = payload.toRequestBody("application/json".toMediaType()),
            headers = Headers.headersOf("Content-Type", "application/json"),
        )
    }

    // ============================== Seasons ===============================

    override fun seasonListRequest(anime: SAnime): Request {
        // Reuse anime details request for season data
        return animeDetailsRequest(anime)
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        val responseBody = response.body.string()
        
        if (responseBody.contains("\"errors\"")) {
            return emptyList()
        }

        val parsed = json.decodeFromString<AniListMediaResponse>(responseBody)
        val media = parsed.data?.Media ?: return emptyList()

        if (currentAnimeTitle.isBlank()) {
            currentAnimeTitle = media.title?.english?.takeIf { it.isNotBlank() }
                ?: media.title?.romaji.orEmpty()
        }
        val relations = media.relations?.edges?.filterNotNull().orEmpty()

        val seasonList = mutableListOf<SAnime>()
        
        // Add the main anime as "Season 1"
        seasonList.add(SAnime.create().apply {
            title = media.title?.english?.takeIf { it.isNotBlank() } ?: media.title?.romaji.orEmpty()
            thumbnail_url = media.coverImage?.extraLarge?.takeIf { it.isNotBlank() }
                ?: media.coverImage?.large.orEmpty()
            url = "${media.id}|season:1"
            description = media.description?.replace(Regex("<[^>]*>"), "").orEmpty()
            genre = media.genres?.filterNotNull()?.joinToString(", ").orEmpty()
            status = parseAniListStatus(media.status)
            fetch_type = FetchType.Episodes
            season_number = 1.0
        })

        // Add related anime as seasons
        val seenIds = mutableSetOf(media.id)
        var seasonNum = 2
        relations.filter {
            it.relationType in listOf("SEQUEL", "PREQUEL", "SIDE_STORY", "PARENT", "ALTERNATIVE")
        }.sortedWith(compareBy<AniListRelationEdge> { edge ->
            // Group by relation type: PREQUEL/PARENT first, then SEQUEL, then others
            when (edge.relationType) {
                "PREQUEL" -> 0
                "PARENT" -> 0
                "SEQUEL" -> 1
                "SIDE_STORY" -> 2
                "ALTERNATIVE" -> 3
                else -> 4
            }
        }.thenBy { edge ->
            // Within each group, sort by AniList ID (lower = older = earlier season)
            edge.node?.id ?: Int.MAX_VALUE
        }).forEach { edge ->
            val node = edge.node ?: return@forEach
            if (!seenIds.add(node.id)) return@forEach
            val relTitle = node.title?.english?.takeIf { it.isNotBlank() } 
                ?: node.title?.romaji.orEmpty()
            
            if (relTitle.isNotBlank()) {
                seasonList.add(SAnime.create().apply {
                    title = relTitle
                    thumbnail_url = node.coverImage?.extraLarge?.takeIf { it.isNotBlank() }
                        ?: node.coverImage?.large.orEmpty()
                    url = "${node.id}|season:$seasonNum"
                    description = "Related as: ${edge.relationType}"
                    genre = media.genres?.filterNotNull()?.joinToString(", ").orEmpty()
                    status = parseAniListStatus(node.status)
                    fetch_type = FetchType.Episodes
                    season_number = seasonNum.toDouble()
                })
                seasonNum++
            }
        }

        return seasonList.sortedBy { it.season_number }
    }

    // ============================== Episodes ==============================

    // Store anime title for filler lookup (passed via URL encoding)
    private var currentAnimeTitle: String = ""
    // Store ID mappings from AniZip for streaming
    private var currentIdMappings: AniZipMappings? = null
    // Store AniZip episodes for metadata (English titles, images, descriptions)
    private var currentAniZipEpisodes: Map<String, AniZipEpisode?> = emptyMap()

    override fun episodeListRequest(anime: SAnime): Request {
        // Extract base ID and title if encoded in URL
        val parts = anime.url.split("|")
        val baseId = parts.first().toIntOrNull() ?: 0
        currentAnilistId = baseId
        
        // Extract title if present (format: id|title:Anime Title|...)
        parts.forEach { part ->
            if (part.startsWith("title:")) {
                currentAnimeTitle = part.removePrefix("title:")
            }
        }
        
        // Fetch AniZip for both ID mappings AND episode metadata (has English titles!)
        fetchAniZipData(baseId)
        
        // Use AniList GraphQL to get basic info (episodes, format)
        val query = """
            query (${"$"}id: Int) {
                Media(id: ${"$"}id, type: ANIME) {
                    id
                    title { romaji english }
                    episodes
                    format
                }
            }
        """.trimIndent()

        val variables = """{"id": $baseId}"""
        val payload = """{"query": ${JSONObject.quote(query)}, "variables": $variables}"""

        return POST(
            baseUrl,
            body = payload.toRequestBody("application/json".toMediaType()),
            headers = Headers.headersOf("Content-Type", "application/json"),
        )
    }
    
    /**
     * Fetch AniZip data for both ID mappings AND episode metadata
     * AniZip provides English titles, images, and descriptions
     */
    private fun fetchAniZipData(anilistId: Int) {
        try {
            val url = "https://api.ani.zip/mappings?anilist_id=$anilistId"
            val response = client.newCall(GET(url)).execute()
            if (response.isSuccessful) {
                val aniZipResponse = json.decodeFromString<AniZipResponse>(response.body.string())
                currentIdMappings = aniZipResponse.mappings
                currentAniZipEpisodes = aniZipResponse.episodes ?: emptyMap()
            }
        } catch (e: Exception) {
            // Keep defaults
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseBody = response.body.string()
        return try {
            val parsed = json.decodeFromString<AniListMediaResponse>(responseBody)
            val media = parsed.data?.Media ?: return buildFallbackEpisodeList()

            buildEpisodeList(media)
        } catch (e: Exception) {
            buildFallbackEpisodeList()
        }
    }

    private fun buildEpisodeList(media: AniListMedia): List<SEpisode> {
        val totalEpisodes = media.episodes ?: 0
        val format = media.format ?: "TV"

        val anizipEpisodes = currentAniZipEpisodes
        val metadataStatus = if (anizipEpisodes.isNotEmpty()) {
            "AniZip: ${anizipEpisodes.size} eps"
        } else {
            "AniZip: No data"
        }

        val fillerEpisodes = if (preferences.getBoolean(PREF_MARK_FILLERS, PREF_MARK_FILLERS_DEFAULT) && currentAnimeTitle.isNotBlank()) {
            try {
                val slug = FillerListApi.titleToSlug(currentAnimeTitle)
                FillerListApi.getFillerEpisodes(client, slug)
            } catch (e: Exception) {
                emptySet()
            }
        } else {
            emptySet()
        }

        val episodeList = mutableListOf<SEpisode>()
        val now = System.currentTimeMillis()

        val mappings = currentIdMappings
        val imdbPart = mappings?.imdbId?.let { "imdb:$it" } ?: ""
        val tmdbPart = mappings?.theMovieDbId?.let { "tmdb:$it" } ?: ""
        val kitsuPart = mappings?.kitsuId?.let { "kitsu:$it" } ?: ""
        val malPart = mappings?.myAnimeListId?.let { "mal:$it" } ?: ""

        when (format) {
            "MOVIE" -> {
                val anizipEp = anizipEpisodes["1"]
                val epTitle = anizipEp?.title?.get("en")?.takeIf { it.isNotBlank() }
                    ?: anizipEp?.title?.get("ja")?.takeIf { it.isNotBlank() }
                    ?: "Movie"
                val epSummary = anizipEp?.overview?.takeIf { it.isNotBlank() }
                val epImage = anizipEp?.image?.takeIf { it.isNotBlank() }

                episodeList.add(
                    SEpisode.create().apply {
                        episode_number = 1.0F
                        name = epTitle
                        date_upload = parseDate(anizipEp?.airDate ?: "")
                        summary = epSummary
                        preview_url = epImage
                        scanlator = metadataStatus
                        url = buildEpisodeUrl("movie", 0, 0, imdbPart, tmdbPart, kitsuPart, malPart)
                    }
                )
            }
            else -> {
                val maxEpisodes = if (anizipEpisodes.isNotEmpty()) {
                    anizipEpisodes.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: totalEpisodes
                } else {
                    if (totalEpisodes > 0) totalEpisodes else 0
                }

                for (epNum in 1..maxEpisodes) {
                    val anizipEp = anizipEpisodes[epNum.toString()]
                    val airDate = parseDate(anizipEp?.airDate ?: "")

                    if (airDate > 0 && airDate > now) continue

                    val epTitle = anizipEp?.title?.get("en")?.takeIf { it.isNotBlank() }
                        ?: anizipEp?.title?.get("ja")?.takeIf { it.isNotBlank() }
                        ?: ""
                    val epSummary = anizipEp?.overview?.takeIf { it.isNotBlank() }
                    val epImage = anizipEp?.image?.takeIf { it.isNotBlank() }

                    val isFiller = fillerEpisodes.contains(epNum)

                    if (anizipEp == null && totalEpisodes > 0 && epNum > totalEpisodes) break

                    val seasonNum = anizipEp?.seasonNumber ?: 1
                    val epInSeason = anizipEp?.episodeNumber ?: epNum

                    episodeList.add(
                        SEpisode.create().apply {
                            episode_number = epNum.toFloat()
                            name = when {
                                epTitle.isNotBlank() && isFiller -> "🦊 Episode $epNum: $epTitle"
                                epTitle.isNotBlank() -> "Episode $epNum: $epTitle"
                                isFiller -> "🦊 Episode $epNum (Filler)"
                                else -> "Episode $epNum"
                            }
                            date_upload = airDate
                            summary = epSummary
                            preview_url = epImage
                            fillermark = isFiller
                            if (epNum == 1 && metadataStatus.isNotBlank()) {
                                scanlator = metadataStatus
                            }
                            url = buildEpisodeUrl(epNum.toString(), seasonNum, epInSeason, imdbPart, tmdbPart, kitsuPart, malPart)
                        }
                    )
                }
            }
        }

        return episodeList.sortedByDescending { it.episode_number }
    }

    private fun buildFallbackEpisodeList(): List<SEpisode> {
        val anizipEpisodes = currentAniZipEpisodes
        if (anizipEpisodes.isEmpty()) return emptyList()

        val episodeCount = anizipEpisodes.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: return emptyList()
        val metadataStatus = "AniZip: ${anizipEpisodes.size} eps"
        val now = System.currentTimeMillis()

        val mappings = currentIdMappings
        val imdbPart = mappings?.imdbId?.let { "imdb:$it" } ?: ""
        val tmdbPart = mappings?.theMovieDbId?.let { "tmdb:$it" } ?: ""
        val kitsuPart = mappings?.kitsuId?.let { "kitsu:$it" } ?: ""
        val malPart = mappings?.myAnimeListId?.let { "mal:$it" } ?: ""

        return buildList {
            for (epNum in 1..episodeCount) {
                val anizipEp = anizipEpisodes[epNum.toString()]
                val airDate = parseDate(anizipEp?.airDate ?: "")
                if (airDate > 0 && airDate > now) continue

                val epTitle = anizipEp?.title?.get("en")?.takeIf { it.isNotBlank() }
                    ?: anizipEp?.title?.get("ja")?.takeIf { it.isNotBlank() }
                    ?: ""

                add(
                    SEpisode.create().apply {
                        episode_number = epNum.toFloat()
                        name = if (epTitle.isNotBlank()) "Episode $epNum: $epTitle" else "Episode $epNum"
                        date_upload = airDate
                        summary = anizipEp?.overview?.takeIf { it.isNotBlank() }
                        preview_url = anizipEp?.image?.takeIf { it.isNotBlank() }
                        scanlator = if (epNum == 1) metadataStatus else null
                        url = buildEpisodeUrl(epNum.toString(), anizipEp?.seasonNumber ?: 1, anizipEp?.episodeNumber ?: epNum, imdbPart, tmdbPart, kitsuPart, malPart)
                    }
                )
            }
        }.sortedByDescending { it.episode_number }
    }
    
    /**
     * Build full TVDB image URL from relative path
     */
    private fun buildTvdbImageUrl(path: String): String {
        return if (path.startsWith("http")) path else "https://artworks.thetvdb.com$path"
    }
    
    /**
     * Build episode URL with all ID mappings for streaming
     */
    private fun buildEpisodeUrl(
        epNum: String,
        seasonNum: Int,
        epInSeason: Int,
        imdbPart: String,
        tmdbPart: String,
        kitsuPart: String,
        malPart: String
    ): String {
        val parts = mutableListOf<String>()
        parts.add("anilist:$currentAnilistId")
        parts.add("ep:$epNum")
        parts.add("season:$seasonNum")
        parts.add("epInSeason:$epInSeason")
        if (imdbPart.isNotBlank()) parts.add(imdbPart)
        if (tmdbPart.isNotBlank()) parts.add(tmdbPart)
        if (kitsuPart.isNotBlank()) parts.add(kitsuPart)
        if (malPart.isNotBlank()) parts.add(malPart)
        return parts.joinToString("|")
    }

    // ============================ Video Links =============================

    private var currentAnilistId: Int = 0
    private var currentEpisodeNumber: Int? = null
    private var currentSeasonNumber: Int? = null
    private var currentIsMovie: Boolean = false
    private var cachedConfig: AIOStreamsConfig? = null

    override fun hosterListRequest(episode: SEpisode): Request {
        val manifestUrl = preferences.getString(PREF_MANIFEST_URL, null)
        if (manifestUrl.isNullOrBlank()) throw Exception("Please configure AIOStreams manifest URL")

        cachedConfig = AIOStreamsConfig.fromManifestUrl(manifestUrl)
            ?: throw Exception("Invalid manifest URL format")

        val parts = episode.url.split("|").associate {
            val split = it.split(":", limit = 2)
            if (split.size == 2) split[0] to split[1] else split[0] to ""
        }

        val episodeNum = parts["ep"] ?: "1"
        val isMovie = episodeNum == "movie" || episodeNum == "0"
        currentAnilistId = parts["anilist"]?.toIntOrNull() ?: 0
        currentIsMovie = isMovie
        currentEpisodeNumber = episodeNum.toIntOrNull() ?: parts["epInSeason"]?.toIntOrNull()
        currentSeasonNumber = parts["season"]?.toIntOrNull()

        val idPriority = preferences.getString(PREF_ID_PRIORITY, PREF_ID_PRIORITY_DEFAULT)!!
        val (searchId, type) = selectIdForApi(parts, idPriority, isMovie, episodeNum)

        val apiUrl = "${cachedConfig!!.baseUrl}/api/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("id", searchId)
            .addQueryParameter("format", "true")
            .addQueryParameter("requiredFields", "infoHash")
            .build()

        val credential = Credentials.basic(cachedConfig!!.uuid, cachedConfig!!.encryptedBlob)

        return GET(
            apiUrl.toString(),
            headers = Headers.headersOf("Authorization", credential)
        )
    }

    private fun selectIdForApi(
        parts: Map<String, String>, 
        priority: String, 
        isMovie: Boolean, 
        episodeNum: String
    ): Pair<String, String> {
        val priorityOrder = priority.split(",").map { it.trim() }
        val type = if (isMovie) "movie" else "series"

        for (idType in priorityOrder) {
            when (idType) {
                "imdb" -> if (parts.containsKey("imdb")) {
                    val id = parts["imdb"]!!
                    val finalId = if (isMovie) id else "$id:${parts["season"]}:${parts["epInSeason"]}"
                    return finalId to type
                }
                "tmdb" -> if (parts.containsKey("tmdb")) {
                    val id = "tmdb:${parts["tmdb"]}"
                    val finalId = if (isMovie) id else "$id:${parts["season"]}:${parts["epInSeason"]}"
                    return finalId to type
                }
                "kitsu" -> if (parts.containsKey("kitsu")) {
                    val id = "kitsu:${parts["kitsu"]}"
                    val finalId = if (isMovie) id else "$id:${parts["epInSeason"]}"
                    return finalId to type
                }
                "anilist" -> if (parts.containsKey("anilist")) {
                    val id = "anilist:${parts["anilist"]}"
                    val finalId = if (isMovie) id else "$id:${parts["season"]}:${parts["epInSeason"]}"
                    return finalId to type
                }
                "mal" -> if (parts.containsKey("mal")) {
                    val id = "mal:${parts["mal"]}"
                    val finalId = if (isMovie) id else "$id:$episodeNum"
                    return finalId to type
                }
            }
        }
        
        // Fallback to first available ID
        if (parts.containsKey("imdb")) return selectIdForApi(parts, "imdb", isMovie, episodeNum)
        throw Exception("No valid ID found")
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val debugEnabled = preferences.getBoolean(PREF_LOCAL_DEBUG, PREF_LOCAL_DEBUG_DEFAULT)
        val debugInfo = if (debugEnabled) buildLocalDebugInfo() else null

        val localOverrideEnabled = preferences.getBoolean(PREF_LOCAL_OVERRIDE, PREF_LOCAL_OVERRIDE_DEFAULT)
        if (localOverrideEnabled) {
            val localVideo = findLocalVideoOrNull()
            if (localVideo != null) {
                val videoList = if (debugInfo != null) {
                    listOf(
                        localVideo,
                        Video(
                            videoUrl = localVideo.videoUrl,
                            videoTitle = "DEBUG: $debugInfo",
                            headers = localVideo.headers,
                            preferred = false,
                        )
                    )
                } else {
                    listOf(localVideo)
                }
                return listOf(
                    Hoster(
                        hosterUrl = localVideo.videoUrl,
                        hosterName = localVideo.videoTitle,
                        videoList = videoList,
                    )
                )
            }
        }

        val jsonStr = response.body.string()
        val jsonObj = JSONObject(jsonStr)
        val data = jsonObj.optJSONObject("data") ?: throw Exception("API returned no data")
        val results = data.optJSONArray("results")

        if (results == null || results.length() == 0) throw Exception("No streams found")

        val bestHashes = if (preferences.getBoolean(PREF_SEADEX_HIGHLIGHT, PREF_SEADEX_HIGHLIGHT_DEFAULT) && currentAnilistId > 0) {
            try {
                SeaDexApi.getBestInfoHashesForAnime(client, currentAnilistId)
            } catch (e: Exception) {
                emptySet()
            }
        } else {
            emptySet()
        }

        val showP2P = preferences.getBoolean(PREF_SHOW_P2P, PREF_SHOW_P2P_DEFAULT)
        val hosterList = mutableListOf<Pair<Hoster, Int>>()

        val playbackHeaders = if (cachedConfig != null) {
            Headers.headersOf("Authorization", Credentials.basic(cachedConfig!!.uuid, cachedConfig!!.encryptedBlob))
        } else null

        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)

            val infoHash = result.optString("infoHash", "").lowercase()
            if (infoHash.isEmpty() || infoHash == "<redacted>") continue

            val name = result.optString("name", "Stream")
            val description = result.optString("description", "")
            val streamUrl = result.optString("url", "")

            val isMagnet = streamUrl.startsWith("magnet:")
            if (isMagnet && !showP2P) continue

            val isBest = bestHashes.contains(infoHash)
            val priority = if (isBest) 0 else 1

            val displayName = if (isBest) "⭐ $name" else name
            val displayInfo = if (description.isNotEmpty()) "$displayName\n$description" else displayName

            val finalUrl = if (!isMagnet && streamUrl.isNotEmpty()) {
                streamUrl
            } else {
                val trackers = getDefaultAnimeTrackers().joinToString("&tr=")
                "magnet:?xt=urn:btih:$infoHash&dn=$infoHash&tr=$trackers"
            }

            val videoHeaders = if (!isMagnet) playbackHeaders else null

            val video = Video(
                videoUrl = finalUrl,
                videoTitle = displayInfo,
                headers = videoHeaders,
                preferred = isBest,
            )
            val hoster = Hoster(
                hosterUrl = finalUrl,
                hosterName = displayName,
                videoList = listOf(video),
            )

            hosterList.add(hoster to priority)
        }

        val result = if (preferences.getBoolean(PREF_SEADEX_SORT, PREF_SEADEX_SORT_DEFAULT)) {
            hosterList.sortedBy { it.second }.map { it.first }
        } else {
            hosterList.map { it.first }
        }

        return if (debugInfo != null) applyDebugInfoToHosters(result, debugInfo) else result
    }

    override fun videoListRequest(hoster: Hoster): Request {
        return GET(hoster.hosterUrl.ifBlank { baseUrl }, headers)
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        return hoster.videoList.orEmpty()
    }

    private fun getDefaultAnimeTrackers(): List<String> = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "http://nyaa.tracker.wf:7777/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce"
    )

    private fun findLocalVideoOrNull(): Video? {
        val baseDir = resolveLocalAnimeBaseDir() ?: return null
        if (!baseDir.exists() || !baseDir.isDirectory) return null
        val animeTitle = currentAnimeTitle.ifBlank { return null }
        val episodeNumber = currentEpisodeNumber

        val animeDir = if (isAnimeVideoDir(baseDir)) {
            baseDir
        } else {
            findBestMatchingAnimeDir(baseDir, animeTitle) ?: return null
        }
        val candidateFiles = listVideoFiles(animeDir)
        val matchedFile = when {
            currentIsMovie -> candidateFiles.firstOrNull { isMovieFile(it.name) }
            episodeNumber != null -> candidateFiles.firstOrNull { file ->
                val parsed = extractEpisodeNumber(file.nameWithoutExtension)
                parsed == episodeNumber
            }
            else -> null
        } ?: return null

        val videoUrl = matchedFile.toURI().toString()
        val displayName = "Local - ${matchedFile.name}"

        return Video(
            videoUrl = videoUrl,
            videoTitle = displayName,
            headers = null,
            preferred = true,
        )
    }

    private fun resolveLocalAnimeBaseDir(): File? {
        val configured = preferences.getString(PREF_LOCAL_ANIME_DIR, PREF_LOCAL_ANIME_DIR_DEFAULT)
            ?.trim()
            .orEmpty()
        if (configured.isBlank()) return null

        return when {
            configured.startsWith("/") -> File(configured)
            configured.startsWith("content://") -> null
            else -> File(Environment.getExternalStorageDirectory(), configured)
        }
    }

    private fun findBestMatchingAnimeDir(baseDir: File, animeTitle: String): File? {
        val normalizedTarget = normalizeTitleForMatch(animeTitle)
        var bestMatch: File? = null
        var bestScore = 0

        baseDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name.startsWith(".")) return@forEach
            val normalizedDir = normalizeTitleForMatch(dir.name)
            val score = when {
                normalizedDir == normalizedTarget -> 3
                normalizedDir.contains(normalizedTarget) || normalizedTarget.contains(normalizedDir) -> 2
                normalizedDir.replace("the", "") == normalizedTarget.replace("the", "") -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = dir
            }
        }

        return bestMatch
    }

    private fun listVideoFiles(animeDir: File): List<File> {
        val supportedExtensions = setOf("avi", "flv", "mkv", "mov", "mp4", "webm", "wmv")
        val result = mutableListOf<File>()

        animeDir.listFiles()?.forEach { file ->
            when {
                file.isFile && file.extension.lowercase() in supportedExtensions -> result.add(file)
                file.isDirectory && !file.name.startsWith(".") -> {
                    file.listFiles()?.forEach { child ->
                        if (child.isFile && child.extension.lowercase() in supportedExtensions) {
                            result.add(child)
                        }
                    }
                }
            }
        }

        return result
    }

    private fun isAnimeVideoDir(dir: File): Boolean {
        val supportedExtensions = setOf("avi", "flv", "mkv", "mov", "mp4", "webm", "wmv")
        return dir.listFiles()?.any { file ->
            file.isFile && file.extension.lowercase() in supportedExtensions
        } ?: false
    }

    private fun normalizeTitleForMatch(title: String): String {
        return title.lowercase()
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), "")
            .trim()
    }

    private fun extractEpisodeNumber(name: String): Int? {
        val patterns = listOf(
            Regex("(?i)S\\d{1,2}[ ._-]*E(\\d{1,3})"),
            Regex("(?i)\\bEP(?:ISODE)?[ ._-]?(\\d{1,3})\\b"),
            Regex("(?i)\\bE(\\d{1,3})\\b"),
        )

        for (pattern in patterns) {
            val match = pattern.find(name)
            if (match != null) return match.groupValues[1].toIntOrNull()
        }

        val cleaned = name.replace(Regex("(?i)\\b(1080|720|480|2160)p\\b"), " ")
        val loose = Regex("\\b(\\d{1,3})\\b").find(cleaned)
        return loose?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun isMovieFile(name: String): Boolean {
        return Regex("(?i)\\b(movie|film)\\b").containsMatchIn(name)
    }

    private fun buildLocalDebugInfo(): String {
        val configured = preferences.getString(PREF_LOCAL_ANIME_DIR, PREF_LOCAL_ANIME_DIR_DEFAULT)
            ?.trim()
            .orEmpty()
        val baseDir = resolveLocalAnimeBaseDir()
        if (baseDir == null) return "path='$configured', baseDir=null"

        val animeTitle = currentAnimeTitle.ifBlank { "<blank>" }
        val isAnimeDir = isAnimeVideoDir(baseDir)
        val animeDir = if (isAnimeDir) baseDir else findBestMatchingAnimeDir(baseDir, animeTitle)
        val files = animeDir?.let { listVideoFiles(it) }.orEmpty()
        val sampleNames = files.take(5).joinToString("|") { it.name }

        return "path='$configured', baseExists=${baseDir.exists()}, isDir=${baseDir.isDirectory}, isAnimeDir=$isAnimeDir, animeTitle='$animeTitle', episode=$currentEpisodeNumber, season=$currentSeasonNumber, animeDir='${animeDir?.name ?: "<none>"}', files=${files.size}, sample='$sampleNames'"
    }

    private fun applyDebugInfoToHosters(hosters: List<Hoster>, debugInfo: String): List<Hoster> {
        if (hosters.isEmpty()) return hosters
        val first = hosters.first()
        val debugLine = "DEBUG: $debugInfo"
        val updatedVideos = first.videoList?.map { video ->
            Video(
                videoUrl = video.videoUrl,
                videoTitle = "${video.videoTitle}\n$debugLine",
                headers = video.headers,
                preferred = video.preferred,
            )
        }

        val updatedFirst = Hoster(
            hosterUrl = first.hosterUrl,
            hosterName = "${first.hosterName}\n$debugLine",
            videoList = updatedVideos,
        )

        return listOf(updatedFirst) + hosters.drop(1)
    }

    // ============================== Helpers ===============================

    private fun parseAniListStatus(status: String?): Int = when (status) {
        "FINISHED" -> SAnime.COMPLETED
        "RELEASING" -> SAnime.ONGOING
        "NOT_YET_RELEASED" -> SAnime.LICENSED
        else -> SAnime.UNKNOWN
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ============================= Config Class ===========================

    data class AIOStreamsConfig(
        val baseUrl: String,
        val uuid: String,
        val encryptedBlob: String,
    ) {
        companion object {
            fun fromManifestUrl(url: String): AIOStreamsConfig? {
                val regex = Regex("(https?://[^/]+)/stremio/([^/]+)/([^/]+)/manifest\\.json")
                val match = regex.find(url) ?: return null
                return AIOStreamsConfig(
                    baseUrl = match.groupValues[1],
                    uuid = match.groupValues[2],
                    encryptedBlob = match.groupValues[3]
                )
            }
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_MANIFEST_URL
            title = "AIOStreams Manifest URL"
            summary = "Get from https://aiostreamsfortheweak.nhyira.dev/stremio/configure or any other public fork"
            setOnPreferenceChangeListener { _, newValue ->
                AIOStreamsConfig.fromManifestUrl(newValue as String) != null
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_SEASONS
            title = "Enable Seasons Mode"
            summary = "Group related anime (sequels, prequels, etc.) as seasons. Disable if you prefer flat episode lists."
            setDefaultValue(PREF_USE_SEASONS_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_ID_PRIORITY
            title = "ID Priority"
            summary = "Choose which ID type to prioritize."
            entries = arrayOf(
                "Kitsu → IMDB → MAL → AniList",
                "MAL → Kitsu → IMDB → AniList",
                "Kitsu → MAL → IMDB → AniList",
                "MAL → IMDB → Kitsu → AniList",
                "IMDB → MAL → Kitsu → AniList",
                "IMDB → Kitsu → MAL → AniList",
                "IMDB → AniList → MAL → Kitsu",
                "AniList → Kitsu → MAL → IMDB",
                "AniList → MAL → Kitsu → IMDB"
            )
            entryValues = arrayOf(
                "kitsu,imdb,mal,anilist",
                "mal,kitsu,imdb,anilist",
                "kitsu,mal,imdb,anilist",
                "mal,imdb,kitsu,anilist",
                "imdb,mal,kitsu,anilist",
                "imdb,kitsu,mal,anilist",
                "imdb,anilist,mal,kitsu",
                "anilist,kitsu,mal,imdb",
                "anilist,mal,kitsu,imdb"
            )
            setDefaultValue(PREF_ID_PRIORITY_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_ANIDB
            title = "Use AniDB for Episode Titles"
            summary = "Fetch additional episode metadata from AniDB when available. May slow down episode loading."
            setDefaultValue(PREF_USE_ANIDB_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_P2P
            title = "Show P2P/Torrent Streams"
            summary = "Enable only if using Anikku. Disable for Debrid only."
            setDefaultValue(PREF_SHOW_P2P_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEADEX_HIGHLIGHT
            title = "Highlight SeaDex Best Releases"
            setDefaultValue(PREF_SEADEX_HIGHLIGHT_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEADEX_SORT
            title = "Move SeaDex Best to Top"
            setDefaultValue(PREF_SEADEX_SORT_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MARK_FILLERS
            title = "Mark Filler Episodes"
            summary = "Fetch filler data from animefillerlist.com and mark filler episodes with 🦊 icon."
            setDefaultValue(PREF_MARK_FILLERS_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LOCAL_OVERRIDE
            title = "Prefer LocalAnime Files"
            summary = "Play a local file from AniMiru/localanime when it matches the episode."
            setDefaultValue(PREF_LOCAL_OVERRIDE_DEFAULT)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_LOCAL_ANIME_DIR
            title = "LocalAnime Directory"
            summary = "Relative to storage root, e.g. Aniyomi/localanime"
            setDefaultValue(PREF_LOCAL_ANIME_DIR_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LOCAL_DEBUG
            title = "Local Debug Info"
            summary = "Append local scan details to the first hoster title."
            setDefaultValue(PREF_LOCAL_DEBUG_DEFAULT)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TVDB_KEY
            title = "TVDB Key"
            summary = "Enter your TVDB API key for episode metadata (titles, thumbnails, descriptions). Get one free at thetvdb.com"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_MANIFEST_URL = "manifest_url"
        private const val PREF_USE_SEASONS = "use_seasons_mode"
        private const val PREF_USE_SEASONS_DEFAULT = true
        private const val PREF_ID_PRIORITY = "id_priority"
        private const val PREF_ID_PRIORITY_DEFAULT = "kitsu,imdb,mal,anilist"
        private const val PREF_USE_ANIDB = "use_anidb_titles"
        private const val PREF_USE_ANIDB_DEFAULT = false
        private const val PREF_SHOW_P2P = "show_p2p_streams"
        private const val PREF_SHOW_P2P_DEFAULT = false
        private const val PREF_SEADEX_HIGHLIGHT = "seadex_highlight"
        private const val PREF_SEADEX_HIGHLIGHT_DEFAULT = true
        private const val PREF_SEADEX_SORT = "seadex_sort_best"
        private const val PREF_SEADEX_SORT_DEFAULT = true
        private const val PREF_MARK_FILLERS = "mark_filler_episodes"
        private const val PREF_MARK_FILLERS_DEFAULT = false
        private const val PREF_TVDB_API_KEY = "tvdb_api_key"
        private const val PREF_LOCAL_OVERRIDE = "local_override"
        private const val PREF_LOCAL_OVERRIDE_DEFAULT = true
        private const val PREF_LOCAL_ANIME_DIR = "local_anime_dir"
        private const val PREF_LOCAL_ANIME_DIR_DEFAULT = "AniMiru/localanime"
        private const val PREF_LOCAL_DEBUG = "local_debug"
        private const val PREF_LOCAL_DEBUG_DEFAULT = false
        private const val PREF_TVDB_KEY = PREF_TVDB_API_KEY
    }
}
