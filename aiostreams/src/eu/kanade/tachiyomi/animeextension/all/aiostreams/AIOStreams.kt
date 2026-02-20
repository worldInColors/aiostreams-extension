package eu.kanade.tachiyomi.animeextension.all.aiostreams

import android.app.Application
import android.content.SharedPreferences
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
            this.title = title?.english?.takeIf { it.isNotBlank() } ?: title?.romaji.orEmpty()
            thumbnail_url = media.coverImage?.extraLarge?.takeIf { it.isNotBlank() }
                ?: media.coverImage?.large.orEmpty()
            url = media.id.toString()
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

    override fun episodeListRequest(anime: SAnime): Request {
        // Extract base ID if it contains season info
        val baseId = anime.url.split("|").first()
        return GET("https://api.ani.zip/mappings?anilist_id=$baseId")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch episodes: ${response.code}")
        }

        val responseString = response.body.string()
        val aniZipResponse = json.decodeFromString<AniZipResponse>(responseString)
        val mappings = aniZipResponse.mappings
        val type = mappings?.type ?: ""

        val anilistId = mappings?.aniListId?.toInt() ?: 0
        val malId = mappings?.myAnimeListId
        val kitsuId = mappings?.kitsuId
        val imdbId = mappings?.imdbId
        val tmdbId = mappings?.theMovieDbId
        val aniDbId = mappings?.aniDbId

        return when (type) {
            "TV", "ONA", "OVA" -> {
                val episodes = aniZipResponse.episodes ?: return emptyList()
                val episodeList = mutableListOf<SEpisode>()
                
                // AniDB titles disabled for now - causes blocking issues
                // TODO: Implement proper async fetching
                val aniDbTitles = emptyMap<String, String>()

                val now = System.currentTimeMillis()
                
                episodes.forEach { (episodeNumKey, episodeData) ->
                    val episodeNumber = episodeNumKey.toFloatOrNull() ?: return@forEach
                    val seasonNumber = episodeData?.seasonNumber ?: 1
                    val episodeInSeason = episodeData?.episodeNumber ?: episodeNumKey.toIntOrNull() ?: 1

                    // Skip future episodes (those that haven't aired yet)
                    val airDate = parseDate(episodeData?.airDate ?: "")
                    if (airDate > 0 && airDate > now) return@forEach

                    // Get the best available title
                    val epTitle = getBestEpisodeTitle(
                        episodeData?.title,
                        aniDbTitles[episodeNumKey]
                    )

                    episodeList.add(
                        SEpisode.create().apply {
                            episode_number = episodeNumber
                            name = if (epTitle.isNotBlank()) {
                                "Episode $episodeNumKey: $epTitle"
                            } else {
                                "Episode $episodeNumKey"
                            }
                            date_upload = parseDate(episodeData?.airDate ?: "")
                            
                            // Rich episode metadata
                            summary = episodeData?.overview?.takeIf { it.isNotBlank() }
                            preview_url = episodeData?.image?.takeIf { it.isNotBlank() }
                            
                            url = buildString {
                                imdbId?.let { append("imdb:$it|season:$seasonNumber|") }
                                tmdbId?.let { append("tmdb:$it|season:$seasonNumber|") }
                                malId?.let { append("mal:$it|") }
                                kitsuId?.let { append("kitsu:$it|") }
                                append("anilist:$anilistId|ep:$episodeNumKey|epInSeason:$episodeInSeason")
                            }
                        }
                    )
                }
                episodeList.sortedBy { it.episode_number }.reversed()
            }
            "MOVIE" -> {
                val dateUpload = aniZipResponse.episodes?.get("1")?.airDate?.let { parseDate(it) } ?: 0L
                listOf(
                    SEpisode.create().apply {
                        episode_number = 1.0F
                        name = "Movie"
                        date_upload = dateUpload
                        url = buildString {
                            imdbId?.let { append("imdb:$it|") }
                            tmdbId?.let { append("tmdb:$it|") }
                            malId?.let { append("mal:$it|") }
                            kitsuId?.let { append("kitsu:$it|") }
                            append("anilist:$anilistId|ep:movie")
                        }
                    }
                )
            }
            else -> emptyList()
        }
    }

    /**
     * Get the best available episode title
     * Priority: ani.zip en > ani.zip romaji > ani.zip native > AniDB title
     */
    private fun getBestEpisodeTitle(
        aniZipTitle: Map<String, String?>?,
        aniDbTitle: String?
    ): String {
        if (aniZipTitle.isNullOrEmpty()) {
            return aniDbTitle?.takeIf { it.isNotBlank() } ?: ""
        }
        
        // Check for valid English title (not null string)
        val enTitle = aniZipTitle["en"]?.takeIf { 
            it.isNotBlank() && it != "null" 
        }
        if (!enTitle.isNullOrBlank()) return enTitle
        
        // Fallback to romaji
        val romajiTitle = aniZipTitle["romaji"]?.takeIf { 
            it.isNotBlank() && it != "null" 
        }
        if (!romajiTitle.isNullOrBlank()) return romajiTitle
        
        // Fallback to native
        val nativeTitle = aniZipTitle["native"]?.takeIf { 
            it.isNotBlank() && it != "null" 
        }
        if (!nativeTitle.isNullOrBlank()) return nativeTitle
        
        // Fallback to x-jat (transliterated Japanese)
        val xjatTitle = aniZipTitle["x-jat"]?.takeIf { 
            it.isNotBlank() && it != "null" 
        }
        if (!xjatTitle.isNullOrBlank()) return xjatTitle
        
        // Final fallback to AniDB
        return aniDbTitle?.takeIf { it.isNotBlank() } ?: ""
    }

    // ============================ Video Links =============================

    private var currentAnilistId: Int = 0
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

        return if (preferences.getBoolean(PREF_SEADEX_SORT, PREF_SEADEX_SORT_DEFAULT)) {
            hosterList.sortedBy { it.second }.map { it.first }
        } else {
            hosterList.map { it.first }
        }
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
    }
}