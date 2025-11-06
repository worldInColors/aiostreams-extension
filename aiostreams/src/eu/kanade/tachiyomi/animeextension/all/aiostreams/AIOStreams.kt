package eu.kanade.tachiyomi.animeextension.all.aiostreams

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
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
import okhttp3.Headers
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
            throw Exception("Failed to fetch episodes from ani.zip: ${response.code} - This anime may not be in the database")
        }

        val json = JSONObject(response.body.string())
        val mappings = json.getJSONObject("mappings")
        val type = mappings.optString("type", "")

        val anilistId = mappings.getInt("anilist_id")
        val malId = mappings.optInt("mal_id", -1).takeIf { it != -1 }
        val kitsuId = mappings.optInt("kitsu_id", -1).takeIf { it != -1 }
        val imdbId = mappings.optString("imdb_id", "").takeIf { it.isNotEmpty() }

        return when (type) {
            "TV", "ONA", "OVA" -> {
                val episodes = json.optJSONObject("episodes") ?: return emptyList()
                val episodeList = mutableListOf<SEpisode>()
                val keys = episodes.keys()

                while (keys.hasNext()) {
                    val episodeNum = keys.next()

                    // Skip specials/OVAs (e.g., "S1", "S2", "O1"): only process numeric episodes
                    val episodeNumber = episodeNum.toFloatOrNull() ?: continue

                    val episodeData = episodes.getJSONObject(episodeNum)
                    val seasonNumber = episodeData.optInt("seasonNumber", 1)
                    val episodeInSeason = episodeData.optInt("episodeNumber", episodeNum.toIntOrNull() ?: 1)

                    episodeList.add(
                        SEpisode.create().apply {
                            episode_number = episodeNumber

                            val episodeTitle = episodeData.optJSONObject("title")?.let { titleObj ->
                                titleObj.optString("en", "").takeIf { it.isNotEmpty() && it != "null" }
                                    ?: titleObj.optString("ja", "").takeIf { it.isNotEmpty() && it != "null" }
                                    ?: titleObj.optString("x-jat", "").takeIf { it.isNotEmpty() && it != "null" }
                            }

                            name = if (!episodeTitle.isNullOrEmpty()) {
                                "Episode $episodeNum: $episodeTitle"
                            } else {
                                "Episode $episodeNum"
                            }

                            date_upload = episodeData.optString("airdate", "")
                                .let { if (it.isNotBlank()) parseDate(it) else 0L }

                            url = buildString {
                                imdbId?.let { append("imdb:$it|season:$seasonNumber|") }
                                malId?.let { append("mal:$it|") }
                                kitsuId?.let { append("kitsu:$it|") }
                                append("anilist:$anilistId|ep:$episodeNum|epInSeason:$episodeInSeason")
                            }
                        },
                    )
                }

                episodeList.sortedBy { it.episode_number }.reversed()
            }

            "MOVIE" -> {
                val episodes = json.optJSONObject("episodes")
                val dateUpload = episodes?.optJSONObject("1")?.optString("airdate", "")
                    ?.let { if (it.isNotBlank()) parseDate(it) else 0L } ?: 0L

                listOf(
                    SEpisode.create().apply {
                        episode_number = 1.0F
                        name = "Movie"
                        date_upload = dateUpload

                        url = buildString {
                            imdbId?.let { append("imdb:$it|") }
                            malId?.let { append("mal:$it|") }
                            kitsuId?.let { append("kitsu:$it|") }
                            append("anilist:$anilistId|ep:movie")
                        }
                    },
                )
            }

            else -> emptyList()
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val manifestUrl = preferences.getString(PREF_MANIFEST_URL, null)
        if (manifestUrl.isNullOrBlank()) {
            throw Exception("Please configure AIOStreams manifest URL in settings")
        }

        val config = AIOStreamsConfig.fromManifestUrl(manifestUrl)
            ?: throw Exception("Invalid manifest URL. Please check settings.")

        val parts = episode.url.split("|").associate {
            val split = it.split(":", limit = 2)
            split[0] to split[1]
        }

        val episodeNum = parts["ep"] ?: throw Exception("Missing episode number")

        val (streamType, animeId) = when {
            parts.containsKey("imdb") -> {
                val season = parts["season"] ?: "1"
                val epInSeason = parts["epInSeason"] ?: episodeNum
                "series" to "${parts["imdb"]}:$season:$epInSeason"
            }
            parts.containsKey("mal") -> "anime" to "mal:${parts["mal"]}:$episodeNum"
            parts.containsKey("kitsu") -> "anime" to "kitsu:${parts["kitsu"]}:$episodeNum"
            parts.containsKey("anilist") -> "anime" to "anilist:${parts["anilist"]}:$episodeNum"
            else -> throw Exception("No anime ID found")
        }

        return GET(config.buildStreamUrl(streamType, animeId))
    }

    override fun videoListParse(response: Response): List<Video> {
        val json = JSONObject(response.body.string())

        if (json.has("error")) {
            throw Exception("AIOStreams error: ${json.optString("error", "Unknown error")}")
        }

        if (!json.has("streams")) {
            throw Exception("No streams available for this episode")
        }

        val streams = json.getJSONArray("streams")
        val videoList = mutableListOf<Video>()

        for (i in 0 until streams.length()) {
            val stream = streams.getJSONObject(i)

            // Skip statistics/debug streams
            val isStatistic = stream.optJSONObject("streamData")?.optString("type") == "statistic"
            if (isStatistic) continue

            val name = stream.optString("name", "Unknown Quality")
            val description = stream.optString("description", "")

            // Check if this is a P2P/torrent stream with infoHash
            val isP2PStream = stream.has("infoHash")
            
            // Skip P2P streams if the preference is disabled
            if (isP2PStream && !preferences.getBoolean(PREF_SHOW_P2P, PREF_SHOW_P2P_DEFAULT)) {
                continue
            }

            val url = if (isP2PStream) {
                // build magnet link
                val infoHash = stream.getString("infoHash")
                val fileIdx = stream.optInt("fileIdx", 0)
            
                val trackers = if (stream.has("sources")) {
                    val sources = stream.getJSONArray("sources")
                    buildList {
                        for (j in 0 until sources.length()) {
                            val source = sources.getString(j)
                            if (source.startsWith("tracker:")) {
                                add(source.substring(8)) // Remove "tracker:" prefix
                            }
                        }
                    }
                } else {
                    // Default anime trackers
                    getDefaultAnimeTrackers()
                }
                
                buildMagnetLink(infoHash, fileIdx, trackers)
            } else {
                // normal debrid stream
                val streamUrl = stream.optString("url", "")
                if (streamUrl.isEmpty()) continue
                streamUrl
            }

            val quality = if (description.isNotEmpty()) {
                "$name\n$description"
            } else {
                name
            }

            videoList.add(
                Video(
                    url = url,
                    quality = quality,
                    videoUrl = url,
                ),
            )
        }

        if (videoList.isEmpty()) {
            throw Exception("No playable streams found")
        }

        return videoList
    }

    private fun buildMagnetLink(infoHash: String, fileIdx: Int, trackers: List<String>): String {
        val trackerParams = trackers.joinToString("&tr=")
        return "magnet:?xt=urn:btih:$infoHash&dn=$infoHash&tr=$trackerParams&index=$fileIdx"
    }

    private fun getDefaultAnimeTrackers(): List<String> {
        return listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonoid.ch:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "http://nyaa.tracker.wf:7777/announce",
            "http://anidex.moe:6969/announce",
            "http://tracker.anirena.com:80/announce",
            "udp://tracker.uw0.xyz:6969/announce",
            "http://share.camoe.cn:8080/announce",
            "http://t.nyaatracker.com:80/announce",
        )
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_MANIFEST_URL
            title = "AIOStreams Manifest URL"
            summary = "Get from https://aiostreamsfortheweak.nhyira.dev/stremio/configure or any other AIOStreams instance"
            dialogTitle = "AIOStreams Setup"
            dialogMessage = """
                1. Visit https://aiostreamsfortheweak.nhyira.dev/stremio/configure
                2. Add your debrid services (TorBox, RealDebrid, etc.)
                3. Enable anime addons (Comet, MediaFusion, etc.)
                4. Click "Create" and copy the manifest URL
                5. Paste here
            """.trimIndent()

            setOnPreferenceChangeListener { _, newValue ->
                val isValid = AIOStreamsConfig.fromManifestUrl(newValue as String) != null
                if (!isValid) {
                    android.widget.Toast.makeText(
                        screen.context,
                        "Invalid manifest URL format",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                isValid
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_P2P
            title = "Show P2P/Torrent Streams"
            summary = "Enable this ONLY if using Aniyomi forks that support P2P streaming (e.g., Anikku, Kuukiyomi). Regular Aniyomi does NOT support magnet links."
            setDefaultValue(PREF_SHOW_P2P_DEFAULT)
            
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    android.widget.Toast.makeText(
                        screen.context,
                        "⚠️ P2P streams require Aniyomi forks like Anikku or Kuukiyomi!",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                true
            }
        }.also(screen::addPreference)
    }

    // =============================== Helpers ==============================

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
        val userId: String,
        val encryptedConfig: String,
    ) {
        companion object {
            fun fromManifestUrl(url: String): AIOStreamsConfig? {
                val regex = Regex("(https://[^/]+)/stremio/([^/]+)/([^/]+)/manifest\\.json")
                val match = regex.find(url) ?: return null

                return AIOStreamsConfig(
                    baseUrl = match.groupValues[1],
                    userId = match.groupValues[2],
                    encryptedConfig = match.groupValues[3],
                )
            }
        }

        fun buildStreamUrl(type: String, id: String): String {
            return "$baseUrl/stremio/$userId/$encryptedConfig/stream/$type/$id.json"
        }
    }

    // ============================== Constants =============================

    companion object {
        private const val PREF_MANIFEST_URL = "manifest_url"
        private const val PREF_SHOW_P2P = "show_p2p_streams"
        private const val PREF_SHOW_P2P_DEFAULT = false
    }
}
