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
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
        val json = JSONObject(response.body.string())
        val mediaList = json.getJSONObject("data")
            .getJSONObject("Page")
            .getJSONArray("media")

        val animeList = (0 until mediaList.length()).map { i ->
            val media = mediaList.getJSONObject(i)
            val title = media.getJSONObject("title")

            SAnime.create().apply {
                this.title = title.optString("english").ifEmpty {
                    title.getString("romaji")
                }
                thumbnail_url = media.getJSONObject("coverImage")
                    .optString("extraLarge")
                    .ifEmpty { media.getJSONObject("coverImage").getString("large") }
                url = media.getInt("id").toString()
                description = media.optString("description", "")
                    .replace(Regex("<[^>]*>"), "")
                genre = media.optJSONArray("genres")?.let { genres ->
                    (0 until genres.length()).joinToString(", ") { j ->
                        genres.getString(j)
                    }
                }
                status = when (media.optString("status")) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    "NOT_YET_RELEASED" -> SAnime.LICENSED
                    else -> SAnime.UNKNOWN
                }
            }
        }

        return AnimesPage(animeList, mediaList.length() >= 20)
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
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch anime details: ${response.code} - ${response.message}")
        }

        val responseBody = response.body.string()
        val json = JSONObject(responseBody)

        if (json.has("errors")) {
            val errors = json.getJSONArray("errors")
            val errorMsg = if (errors.length() > 0) {
                errors.getJSONObject(0).optString("message", "Unknown error")
            } else {
                "Unknown AniList error"
            }
            throw Exception("AniList API error: $errorMsg")
        }

        val media = json.getJSONObject("data").getJSONObject("Media")
        val title = media.getJSONObject("title")

        return SAnime.create().apply {
            this.title = title.optString("english").ifEmpty { title.getString("romaji") }
            thumbnail_url = media.getJSONObject("coverImage").getString("extraLarge")
            url = media.getInt("id").toString()
            description = buildString {
                media.optString("description", "")
                    .replace(Regex("<[^>]*>"), "")
                    .let { if (it.isNotBlank()) append("$it\n\n") }

                media.optInt("averageScore", 0).let {
                    if (it > 0) append("★ Score: $it/100\n")
                }

                media.optJSONObject("studios")?.getJSONArray("nodes")?.let { studios ->
                    if (studios.length() > 0) {
                        append("Studio: ${studios.getJSONObject(0).getString("name")}\n")
                    }
                }
            }
            genre = media.optJSONArray("genres")?.let { genres ->
                (0 until genres.length()).joinToString(", ") { i ->
                    genres.getString(i)
                }
            }
            status = when (media.optString("status")) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "NOT_YET_RELEASED" -> SAnime.LICENSED
                else -> SAnime.UNKNOWN
            }
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
                    format
                    genres
                    averageScore
                    studios { nodes { name } }
                }
            }
        """.trimIndent()

        val variables = """{"id": ${anime.url}}"""
        val payload = """{"query": ${JSONObject.quote(query)}, "variables": $variables}"""

        return POST(
            baseUrl,
            body = payload.toRequestBody("application/json".toMediaType()),
            headers = Headers.headersOf("Content-Type", "application/json"),
        )
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("https://api.ani.zip/mappings?anilist_id=${anime.url}")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch episodes: ${response.code}")
        }

        val json = JSONObject(response.body.string())
        val mappings = json.getJSONObject("mappings")
        val type = mappings.optString("type", "")

        val anilistId = mappings.getInt("anilist_id")
        val malId = mappings.optInt("mal_id", -1).takeIf { it != -1 }
        val kitsuId = mappings.optInt("kitsu_id", -1).takeIf { it != -1 }
        val imdbId = mappings.optString("imdb_id", "").takeIf { it.isNotEmpty() }
        val tmdbId = mappings.optString("themoviedb_id", "").takeIf { it.isNotEmpty() }

        return when (type) {
            "TV", "ONA", "OVA" -> {
                val episodes = json.optJSONObject("episodes") ?: return emptyList()
                val episodeList = mutableListOf<SEpisode>()
                val keys = episodes.keys()

                while (keys.hasNext()) {
                    val episodeNumKey = keys.next()
                    val episodeNumber = episodeNumKey.toFloatOrNull() ?: continue
                    val episodeData = episodes.getJSONObject(episodeNumKey)
                    val seasonNumber = episodeData.optInt("seasonNumber", 1)
                    val episodeInSeason = episodeData.optInt("episodeNumber", episodeNumKey.toIntOrNull() ?: 1)

                    episodeList.add(
                        SEpisode.create().apply {
                            episode_number = episodeNumber
                            val titleObj = episodeData.optJSONObject("title")
                            val epTitle = titleObj?.optString("en", "") ?: ""
                            name = if (epTitle.isNotBlank() && epTitle != "null") "Episode $episodeNumKey: $epTitle" else "Episode $episodeNumKey"
                            date_upload = parseDate(episodeData.optString("airdate", ""))
                            
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
                val dateUpload = json.optJSONObject("episodes")?.optJSONObject("1")?.optString("airdate", "") ?: ""
                listOf(
                    SEpisode.create().apply {
                        episode_number = 1.0F
                        name = "Movie"
                        date_upload = parseDate(dateUpload)
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

    // ============================ Video Links =============================

    private var currentAnilistId: Int = 0
    private var cachedConfig: AIOStreamsConfig? = null

    override fun videoListRequest(episode: SEpisode): Request {
        val manifestUrl = preferences.getString(PREF_MANIFEST_URL, null)
        if (manifestUrl.isNullOrBlank()) throw Exception("Please configure AIOStreams manifest URL")

   
        cachedConfig = AIOStreamsConfig.fromManifestUrl(manifestUrl)
            ?: throw Exception("Invalid manifest URL format")

        val parts = episode.url.split("|").associate {
            val split = it.split(":", limit = 2)
            split[0] to split[1]
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

    private fun selectIdForApi(parts: Map<String, String>, priority: String, isMovie: Boolean, episodeNum: String): Pair<String, String> {
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
        if (parts.containsKey("imdb")) return selectIdForApi(parts, "imdb", isMovie, episodeNum)
        throw Exception("No valid ID found")
    }

    override fun videoListParse(response: Response): List<Video> {
        val json = JSONObject(response.body.string())
        val data = json.optJSONObject("data") ?: throw Exception("API returned no data")
        val results = data.optJSONArray("results")
        
        if (results == null || results.length() == 0) throw Exception("No streams found")

        val bestHashes = if (preferences.getBoolean(PREF_SEADEX_HIGHLIGHT, PREF_SEADEX_HIGHLIGHT_DEFAULT) && currentAnilistId > 0) {
            try { SeaDexApi.getBestInfoHashesForAnime(client, currentAnilistId) } 
            catch (e: Exception) { emptySet() }
        } else { emptySet() }
        
        val showP2P = preferences.getBoolean(PREF_SHOW_P2P, PREF_SHOW_P2P_DEFAULT)
        val videoList = mutableListOf<Pair<Video, Int>>()

     
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

            videoList.add(Video(finalUrl, displayInfo, finalUrl, headers = videoHeaders) to priority)
        }

        return if (preferences.getBoolean(PREF_SEADEX_SORT, PREF_SEADEX_SORT_DEFAULT)) {
            videoList.sortedBy { it.second }.map { it.first }
        } else {
            videoList.map { it.first }
        }
    }

    private fun getDefaultAnimeTrackers(): List<String> = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "http://nyaa.tracker.wf:7777/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce"
    )

    private fun parseDate(dateStr: String): Long {
        return try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L } catch (e: Exception) { 0L }
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
        private const val PREF_ID_PRIORITY = "id_priority"
        private const val PREF_ID_PRIORITY_DEFAULT = "kitsu,imdb,mal,anilist"
        private const val PREF_SHOW_P2P = "show_p2p_streams"
        private const val PREF_SHOW_P2P_DEFAULT = false
        private const val PREF_SEADEX_HIGHLIGHT = "seadex_highlight"
        private const val PREF_SEADEX_HIGHLIGHT_DEFAULT = true
        private const val PREF_SEADEX_SORT = "seadex_sort_best"
        private const val PREF_SEADEX_SORT_DEFAULT = true
    }
}