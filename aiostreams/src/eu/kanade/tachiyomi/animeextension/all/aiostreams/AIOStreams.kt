package eu.kanade.tachiyomi.animeextension.all.aiostreams

import android.app.Application
import android.content.SharedPreferences
import android.os.Environment
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.io.File
import java.util.Locale

class AIOStreams : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AIOStreams"

    override val baseUrl = KitsuApi.BASE_URL

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Kitsu relation roles that count as "seasons"
    private val seasonRoles = listOf(
        "sequel", "prequel", "side_story", "parent_story", "spinoff",
        "alternative_version", "alternative_setting",
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        currentListingTrending = false
        return GET(KitsuApi.popularUrl(page), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = KitsuApi.parseAnimePage(response.body.string(), currentListingTrending)
            ?: return AnimesPage(emptyList(), false)

        val animeList = parsed.anime.map { (id, attributes) ->
            SAnime.create().apply {
                title = pickTitle(attributes)
                thumbnail_url = bestPoster(attributes)
                url = "kitsu:$id"
                description = attributes.synopsis.orEmpty()
                genre = "" // genres require the categories include; filled in details
                status = parseKitsuStatus(attributes.status)
            }
        }

        return AnimesPage(animeList, parsed.hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        currentListingTrending = false
        return GET(KitsuApi.latestUrl(page), headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =============================== Search ===============================

    // Whether the in-flight listing is the trending endpoint (affects hasNextPage)
    private var currentListingTrending = false

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = mutableMapOf<String, String>()
        var sort: String? = null
        var trending = false

        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> when (filter.name) {
                    "Sort" -> {
                        val value = SORT_SELECT_VALUES.getOrNull(filter.state).orEmpty()
                        if (value == "trending") trending = true else sort = value
                    }
                    "Season" -> params["filter[season]"] = SEASON_SELECT_VALUES.getOrNull(filter.state).orEmpty()
                    "Format" -> params["filter[subtype]"] = FORMAT_SELECT_VALUES.getOrNull(filter.state).orEmpty()
                    "Status" -> params["filter[status]"] = STATUS_SELECT_VALUES.getOrNull(filter.state).orEmpty()
                    "Age rating" -> params["filter[ageRating]"] = AGE_SELECT_VALUES.getOrNull(filter.state).orEmpty()
                }
                is TextFilter -> {
                    if (filter.name == "Year" && filter.state.isNotBlank()) {
                        filter.state.trim().toIntOrNull()?.let { params["filter[season_year]"] = it.toString() }
                    }
                }
                is GenreGroup -> {
                    val genreSlugByName = GENRE_NAMES.zip(GENRE_VALUES).toMap()
                    val selected = filter.state.filter { it.state }
                        .mapNotNull { genreSlugByName[it.name] }
                    if (selected.isNotEmpty()) params["filter[categories]"] = selected.joinToString(",")
                }
                else -> {}
            }
        }

        currentListingTrending = trending
        val url = if (trending) KitsuApi.trendingUrl(page) else KitsuApi.searchUrl(page, query, params, sort)
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply to search"),
        SelectFilter("Sort", SORT_NAMES),
        SelectFilter("Season", SEASON_NAMES),
        TextFilter("Year"),
        SelectFilter("Format", FORMAT_NAMES),
        SelectFilter("Status", STATUS_NAMES),
        SelectFilter("Age rating", AGE_NAMES),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Genres (any match)"),
        GenreGroup("Genres", GENRE_NAMES.map { CheckBoxFilter(it) }),
    )

    // Concrete filter types — the lib's AnimeFilter variants are abstract.

    private class SelectFilter(name: String, values: Array<String>) :
        AnimeFilter.Select<String>(name, values)

    private class TextFilter(name: String) : AnimeFilter.Text(name)

    private class CheckBoxFilter(name: String) : AnimeFilter.CheckBox(name)

    private class GenreGroup(name: String, state: List<AnimeFilter.CheckBox>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, state)

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val kitsuId = resolveKitsuId(anime.url)
            ?: throw Exception("Could not resolve anime id from '${anime.url}'")
        return GET(KitsuApi.animeDetailsUrl(kitsuId), headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val parsed = KitsuApi.parseAnimeResponse(response.body.string())
            ?: throw Exception("Failed to parse Kitsu anime details")

        val anime = parsed.anime
        val useSeasons = preferences.getBoolean(PREF_USE_SEASONS, PREF_USE_SEASONS_DEFAULT)
        val animeTitle = pickTitle(anime)

        return SAnime.create().apply {
            title = animeTitle
            thumbnail_url = bestPoster(anime)
            background_url = bestCover(anime)
            // Include title in URL for filler/local-file lookup
            url = "kitsu:${parsed.id}|title:${animeTitle.replace("|", " ")}"
            description = buildString {
                anime.synopsis?.takeIf { it.isNotBlank() }?.let { append("$it\n\n") }

                anime.averageRating?.toDoubleOrNull()?.let { score ->
                    if (score > 0) append("★ Score: ${"%.2f".format(score)}/100\n")
                }

                anime.subtype?.let { append("Format: ${it.uppercase(Locale.US)}\n") }
                anime.episodeCount?.let { append("Episodes: $it\n") }
                if (!anime.startDate.isNullOrBlank() || !anime.endDate.isNullOrBlank()) {
                    append("Aired: ${anime.startDate ?: "?"} → ${anime.endDate ?: "?"}\n")
                }
                anime.episodeLength?.let { append("Episode length: $it min\n") }
            }.trim()

            genre = parsed.categories.joinToString(", ")
            status = parseKitsuStatus(anime.status)

            // Seasons mode is decided here: the app merges this SAnime into the
            // browse entry, so fetch_type routes the episode fetch afterwards.
            if (useSeasons && KitsuApi.fetchRelations(client, parsed.id).any { it.role in seasonRoles }) {
                fetch_type = FetchType.Seasons
            }
        }
    }

    // ============================== Seasons ===============================

    // Base anime id for the season list being parsed (the relations payload
    // only contains related entries, not the anime itself)
    private var currentSeasonBaseId: String? = null

    override fun seasonListRequest(anime: SAnime): Request {
        val kitsuId = resolveKitsuId(anime.url)
            ?: throw Exception("Could not resolve anime id from '${anime.url}'")
        currentSeasonBaseId = kitsuId
        return GET(KitsuApi.relationsUrl(kitsuId), headers)
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        val relations = KitsuApi.parseRelations(response.body.string())

        val baseId = currentSeasonBaseId
        val main = baseId?.let { KitsuApi.fetchAnime(client, it) } ?: return emptyList()
        if (currentAnimeTitle.isBlank()) {
            currentAnimeTitle = pickTitle(main.anime)
        }

        val seasonList = mutableListOf<SAnime>()

        // The main anime as "Season 1"
        seasonList.add(SAnime.create().apply {
            title = pickTitle(main.anime)
            thumbnail_url = bestPoster(main.anime)
            url = "kitsu:${main.id}|season:1"
            description = main.anime.synopsis.orEmpty()
            genre = main.categories.joinToString(", ")
            status = parseKitsuStatus(main.anime.status)
            fetch_type = FetchType.Episodes
            season_number = 1.0
        })

        // Related anime as further seasons
        val seenIds = mutableSetOf(main.id)
        var seasonNum = 2
        relations.filter { it.role in seasonRoles }
            .sortedWith(
                compareBy<KitsuApi.RelatedAnime> { edge ->
                    // Group by relation type: PREQUEL/PARENT first, then SEQUEL, then others
                    when (edge.role) {
                        "prequel", "parent_story" -> 0
                        "sequel" -> 1
                        "side_story" -> 2
                        "spinoff" -> 3
                        else -> 4
                    }
                }.thenBy { edge ->
                    // Within each group, sort by Kitsu ID (lower = older = earlier season)
                    edge.id.toLongOrNull() ?: Long.MAX_VALUE
                },
            ).forEach { edge ->
                if (!seenIds.add(edge.id)) return@forEach
                val relTitle = pickTitle(edge.anime)

                if (relTitle.isNotBlank()) {
                    seasonList.add(SAnime.create().apply {
                        title = relTitle
                        thumbnail_url = bestPoster(edge.anime)
                        url = "kitsu:${edge.id}|season:$seasonNum"
                        description = "Related as: ${edge.role}"
                        status = parseKitsuStatus(edge.anime.status)
                        fetch_type = FetchType.Episodes
                        season_number = seasonNum.toDouble()
                    })
                    seasonNum++
                }
            }

        return seasonList.sortedBy { it.season_number }
    }

    // ============================== Episodes ==============================

    // Store anime title for filler/local lookup (passed via URL encoding)
    private var currentAnimeTitle: String = ""
    // Store ID mappings from AniZip for streaming
    private var currentIdMappings: AniZipMappings? = null
    // Store AniZip episodes for metadata (English titles, images, descriptions)
    private var currentAniZipEpisodes: Map<String, AniZipEpisode?> = emptyMap()
    // Kitsu episodes (fallback when AniZip has no data)
    private var currentKitsuEpisodes: Map<Int, KitsuApi.KitsuEpisode> = emptyMap()
    // TVDB episodes (optional enrichment when an API key is configured)
    private var currentTvdbEpisodes: Map<String, TvDbApi.EpisodeData> = emptyMap()
    // Kitsu id for the episode list being built
    private var currentKitsuId: String? = null
    // Cache of resolved legacy AniList ids -> Kitsu ids
    private val kitsuIdByAnilist = mutableMapOf<Int, String>()

    override fun episodeListRequest(anime: SAnime): Request {
        // Extract base ID and title if encoded in URL
        val parts = anime.url.split("|")
        val base = parts.first()
        parts.forEach { part ->
            if (part.startsWith("title:")) {
                currentAnimeTitle = part.removePrefix("title:")
            }
        }

        var kitsuId: String? = null
        var anizip: AniZipResponse? = null

        if (base.startsWith("kitsu:")) {
            kitsuId = base.removePrefix("kitsu:")
            anizip = fetchAniZip("kitsu_id=$kitsuId")
        } else {
            // Legacy URL: a bare AniList id from an older version of this extension
            base.toIntOrNull()?.let { anilistId ->
                anizip = fetchAniZip("anilist_id=$anilistId")
                kitsuId = anizip?.mappings?.kitsuId?.toString()?.also {
                    kitsuIdByAnilist[anilistId] = it
                } ?: kitsuIdByAnilist[anilistId]
            }
        }

        currentKitsuId = kitsuId
        currentIdMappings = anizip?.mappings
        currentAniZipEpisodes = anizip?.episodes ?: emptyMap()

        currentKitsuId?.let { id ->
            return GET(KitsuApi.animeDetailsUrl(id), headers)
        }
        // No Kitsu id resolvable — fall back to AniZip-only parsing
        return GET("https://api.ani.zip/mappings", headers)
    }

    /**
     * Fetch AniZip data by query parameter (kitsu_id= / anilist_id= / mal_id=).
     * AniZip provides the cross-ID mappings for streaming plus an episode
     * list with English titles, images and overviews in a single request.
     */
    private fun fetchAniZip(param: String): AniZipResponse? = try {
        client.newCall(GET("https://api.ani.zip/mappings?$param")).execute().use { response ->
            if (response.isSuccessful) json.decodeFromString<AniZipResponse>(response.body.string()) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Resolve the Kitsu id for an entry URL. New URLs are "kitsu:{id}|...";
     * legacy library entries may hold a bare AniList id, which is translated
     * through AniZip.
     */
    private fun resolveKitsuId(url: String): String? {
        val base = url.split("|").first()
        if (base.startsWith("kitsu:")) return base.removePrefix("kitsu:")

        val anilistId = base.toIntOrNull() ?: return null
        kitsuIdByAnilist[anilistId]?.let { return it }

        val anizip = fetchAniZip("anilist_id=$anilistId")
        return anizip?.mappings?.kitsuId?.toString()?.also {
            kitsuIdByAnilist[anilistId] = it
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val kitsuId = currentKitsuId ?: return buildFallbackEpisodeList()
        val parsed = KitsuApi.parseAnimeResponse(response.body.string(), fallbackId = kitsuId)
            ?: return buildFallbackEpisodeList()

        if (currentAnimeTitle.isBlank()) {
            currentAnimeTitle = pickTitle(parsed.anime)
        }

        // Kitsu episode fallback when AniZip has no episode data
        if (currentAniZipEpisodes.isEmpty()) {
            currentKitsuEpisodes = KitsuApi.fetchEpisodes(client, kitsuId)
        }

        // Optional TVDB enrichment (requires user API key; id needs no key)
        loadTvdbEpisodes(parsed)

        return buildEpisodeList(parsed)
    }

    /**
     * Populate TVDB episode data when a key is configured and a TVDB series id
     * is available from AniZip/Kitsu mappings. Fills gaps AniZip/Kitsu leave
     * (titles, overviews, thumbnails) — English by default.
     */
    private fun loadTvdbEpisodes(details: KitsuApi.AnimeDetails) {
        currentTvdbEpisodes = emptyMap()
        val tvdbKey = preferences.getString(PREF_TVDB_KEY, "").orEmpty()
        if (tvdbKey.isBlank()) return

        val tvdbId = currentIdMappings?.theTvDbId
            ?: details.mappings["thetvdb/series"]?.toLongOrNull()
            ?: details.mappings["thetvdb"]?.substringBefore("/")?.toLongOrNull()
        if (tvdbId == null || tvdbId <= 0) return

        val episodes = TvDbApi.getAllEpisodes(client, tvdbKey, tvdbId)
        if (episodes.isNotEmpty()) {
            currentTvdbEpisodes = TvDbApi.episodesToMap(episodes)
        }
    }

    private fun buildEpisodeList(details: KitsuApi.AnimeDetails): List<SEpisode> {
        val anime = details.anime
        val totalEpisodes = anime.episodeCount ?: 0
        val isMovie = anime.subtype == "movie"

        val anizipEpisodes = currentAniZipEpisodes
        val kitsuEpisodes = currentKitsuEpisodes
        val tvdbEpisodes = currentTvdbEpisodes

        // AniDB titles fill whatever is still missing after the other sources
        val anidbTitles = fetchAniDbTitlesIfNeeded(details)

        val metadataStatus = buildList {
            if (anizipEpisodes.isNotEmpty()) add("AniZip: ${anizipEpisodes.size}")
            if (kitsuEpisodes.isNotEmpty()) add("Kitsu: ${kitsuEpisodes.size}")
            if (tvdbEpisodes.isNotEmpty()) add("TVDB: ${tvdbEpisodes.size}")
            if (anidbTitles.isNotEmpty()) add("AniDB: ${anidbTitles.size}")
        }.joinToString(", ").ifBlank { "No episode metadata" }

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
        val kitsuPart = (currentKitsuId ?: details.id).let { "kitsu:$it" }
        val malPart = mappings?.myAnimeListId?.let { "mal:$it" } ?: ""
        val anilistPart = mappings?.aniListId?.let { "anilist:$it" } ?: ""

        if (isMovie) {
            val epTitle = episodeTitle(1).ifBlank { "Movie" }
            episodeList.add(
                SEpisode.create().apply {
                    episode_number = 1.0F
                    name = epTitle
                    date_upload = episodeAirDate(1)
                    summary = episodeOverview(1)
                    preview_url = episodeImage(1)
                    scanlator = metadataStatus
                    url = buildEpisodeUrl("movie", 0, 0, imdbPart, tmdbPart, kitsuPart, malPart, anilistPart)
                },
            )
        } else {
            val maxEpisodes = when {
                anizipEpisodes.isNotEmpty() -> anizipEpisodes.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: totalEpisodes
                kitsuEpisodes.isNotEmpty() -> kitsuEpisodes.keys.maxOrNull() ?: totalEpisodes
                totalEpisodes > 0 -> totalEpisodes
                else -> 0
            }

            for (epNum in 1..maxEpisodes) {
                val airDate = episodeAirDate(epNum)

                if (airDate > 0 && airDate > now) continue

                val epTitle = episodeTitle(epNum)
                val isFiller = fillerEpisodes.contains(epNum)

                if (!hasEpisodeData(epNum) && totalEpisodes > 0 && epNum > totalEpisodes) break

                val seasonNum = anizipEpisodes[epNum.toString()]?.seasonNumber
                    ?: kitsuEpisodes[epNum]?.seasonNumber ?: 1
                val epInSeason = anizipEpisodes[epNum.toString()]?.episodeNumber
                    ?: kitsuEpisodes[epNum]?.number ?: epNum

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
                        summary = episodeOverview(epNum)
                        preview_url = episodeImage(epNum)
                        fillermark = isFiller
                        if (epNum == 1 && metadataStatus.isNotBlank()) {
                            scanlator = metadataStatus
                        }
                        url = buildEpisodeUrl(epNum.toString(), seasonNum, epInSeason, imdbPart, tmdbPart, kitsuPart, malPart, anilistPart)
                    },
                )
            }
        }

        return episodeList.sortedByDescending { it.episode_number }
    }

    // ---- per-episode metadata merge: AniZip -> Kitsu -> TVDB -> AniDB ----

    private fun hasEpisodeData(epNum: Int): Boolean =
        currentAniZipEpisodes.containsKey(epNum.toString()) ||
            currentKitsuEpisodes.containsKey(epNum) ||
            currentTvdbEpisodes.containsKey(epNum.toString())

    private fun episodeTitle(epNum: Int): String {
        currentAniZipEpisodes[epNum.toString()]?.title?.let { titles ->
            (titles["en"] ?: titles["en_jp"] ?: titles["ja"])
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        currentKitsuEpisodes[epNum]?.titles?.let { titles ->
            (titles.enUs ?: titles.en ?: titles.enJp ?: titles.jaJp)
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        currentTvdbEpisodes[epNum.toString()]?.name
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return anidbTitleCache[epNum.toString()].orEmpty()
    }

    private fun episodeAirDate(epNum: Int): Long {
        currentAniZipEpisodes[epNum.toString()]?.airDate?.let { return parseDate(it) }
        currentKitsuEpisodes[epNum]?.airDate?.let { return parseDate(it) }
        currentTvdbEpisodes[epNum.toString()]?.airDate?.let { return parseDate(it) }
        return 0L
    }

    private fun episodeOverview(epNum: Int): String? {
        currentAniZipEpisodes[epNum.toString()]?.overview
            ?.takeIf { it.isNotBlank() }?.let { return it }
        currentTvdbEpisodes[epNum.toString()]?.overview
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun episodeImage(epNum: Int): String? {
        currentAniZipEpisodes[epNum.toString()]?.image
            ?.takeIf { it.isNotBlank() }?.let { return it }
        currentKitsuEpisodes[epNum]?.thumbnail?.original
            ?.takeIf { it.isNotBlank() }?.let { return it }
        currentTvdbEpisodes[epNum.toString()]?.imageUrl
            ?.takeIf { it.isNotBlank() }?.let { return buildTvdbImageUrl(it) }
        return null
    }

    // AniDB episode titles (filled lazily; cleared per episode-list build)
    private var anidbTitleCache: Map<String, String> = emptyMap()

    /**
     * AniDB lookup only when enabled, an id is known, and the other sources
     * left titles missing. AniDB is heavily rate limited, so this is a
     * last-resort fill.
     */
    private fun fetchAniDbTitlesIfNeeded(details: KitsuApi.AnimeDetails): Map<String, String> {
        anidbTitleCache = emptyMap()
        if (!preferences.getBoolean(PREF_USE_ANIDB, PREF_USE_ANIDB_DEFAULT)) return emptyMap()

        val missingTitles = (1..(details.anime.episodeCount ?: 0)).count { episodeTitle(it).isBlank() }
        if (missingTitles == 0) return emptyMap()

        val anidbId = currentIdMappings?.aniDbId
            ?: details.mappings["anidb"]?.toLongOrNull()
        if (anidbId == null || anidbId <= 0) return emptyMap()

        return try {
            runBlocking { AniDbApi.getEpisodeTitles(client, anidbId) }.also {
                anidbTitleCache = it
            }
        } catch (e: Exception) {
            emptyMap()
        }
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
        val kitsuPart = currentKitsuId?.let { "kitsu:$it" } ?: ""
        val malPart = mappings?.myAnimeListId?.let { "mal:$it" } ?: ""
        val anilistPart = mappings?.aniListId?.let { "anilist:$it" } ?: ""

        return buildList {
            for (epNum in 1..episodeCount) {
                val anizipEp = anizipEpisodes[epNum.toString()]
                val airDate = parseDate(anizipEp?.airDate ?: "")
                if (airDate > 0 && airDate > now) continue

                val epTitle = anizipEp?.title?.entries
                    ?.firstOrNull { it.key == "en" && !it.value.isNullOrBlank() }?.value
                    ?: anizipEp?.title?.entries?.firstOrNull { it.key == "ja" && !it.value.isNullOrBlank() }?.value
                    ?: ""

                add(
                    SEpisode.create().apply {
                        episode_number = epNum.toFloat()
                        name = if (epTitle.isNotBlank()) "Episode $epNum: $epTitle" else "Episode $epNum"
                        date_upload = airDate
                        summary = anizipEp?.overview?.takeIf { it.isNotBlank() }
                        preview_url = anizipEp?.image?.takeIf { it.isNotBlank() }
                        scanlator = if (epNum == 1) metadataStatus else null
                        url = buildEpisodeUrl(epNum.toString(), anizipEp?.seasonNumber ?: 1, anizipEp?.episodeNumber ?: epNum, imdbPart, tmdbPart, kitsuPart, malPart, anilistPart)
                    },
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
        malPart: String,
        anilistPart: String = "",
    ): String {
        val parts = mutableListOf<String>()
        if (anilistPart.isNotBlank()) parts.add(anilistPart)
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

    /** Picks the display title according to the Title language preference. */
    private fun pickTitle(anime: KitsuApi.KitsuAnime): String {
        val titles = anime.titles
        return when (preferences.getString(PREF_TITLE_LANG, PREF_TITLE_LANG_DEFAULT)) {
            "romaji" -> titles?.enJp?.takeIf { it.isNotBlank() }
            "native" -> titles?.jaJp?.takeIf { it.isNotBlank() }
            else -> titles?.en?.takeIf { it.isNotBlank() }
                ?: titles?.enUs?.takeIf { it.isNotBlank() }
        } ?: anime.canonicalTitle.orEmpty()
    }

    private fun bestPoster(anime: KitsuApi.KitsuAnime): String =
        anime.posterImage?.original?.takeIf { it.isNotBlank() }
            ?: anime.posterImage?.large.orEmpty()

    private fun bestCover(anime: KitsuApi.KitsuAnime): String? =
        anime.coverImage?.original?.takeIf { it.isNotBlank() }
            ?: anime.coverImage?.large?.takeIf { it.isNotBlank() }

    private fun parseKitsuStatus(status: String?): Int = when (status) {
        "current" -> SAnime.ONGOING
        "finished" -> SAnime.COMPLETED
        "upcoming", "unreleased" -> SAnime.LICENSED
        "cancelled" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    /** Accepts "yyyy-MM-dd" and ISO datetimes ("yyyy-MM-ddTHH:mm:ssZ"). */
    private fun parseDate(dateStr: String): Long {
        if (dateStr.length < 10) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr.substring(0, 10))?.time ?: 0L
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

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG
            title = "Title Language"
            summary = "Which title Kitsu displays for anime and seasons."
            entries = arrayOf("English", "Romaji", "Native (Japanese)")
            entryValues = arrayOf("english", "romaji", "native")
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
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
            summary = "Fill missing episode titles from AniDB. Only fires when other sources lack titles. May slow down episode loading (AniDB rate limits)."
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

        EditTextPreference(screen.context).apply {
            key = PREF_TVDB_KEY
            title = "TVDB API Key (optional)"
            summary = "Kitsu + AniZip already provide episode metadata. Add a free thetvdb.com API key to also fill gaps (titles/overviews/thumbnails) from TVDB."
            setDefaultValue("")
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
    }

    companion object {
        private const val PREF_MANIFEST_URL = "manifest_url"
        private const val PREF_TITLE_LANG = "title_language"
        private const val PREF_TITLE_LANG_DEFAULT = "english"
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
        private const val PREF_TVDB_KEY = "tvdb_api_key"
        private const val PREF_LOCAL_OVERRIDE = "local_override"
        private const val PREF_LOCAL_OVERRIDE_DEFAULT = true
        private const val PREF_LOCAL_ANIME_DIR = "local_anime_dir"
        private const val PREF_LOCAL_ANIME_DIR_DEFAULT = "AniMiru/localanime"
        private const val PREF_LOCAL_DEBUG = "local_debug"
        private const val PREF_LOCAL_DEBUG_DEFAULT = false

        // Filter option tables (index-aligned with the *NAMES arrays; "" = no filter)

        private val SORT_NAMES = arrayOf(
            "Relevance", "Popularity", "Rating", "Newest", "Trending",
        )
        private val SORT_SELECT_VALUES = arrayOf(
            "", "-userCount", "-averageRating", "-startDate", "trending",
        )

        private val SEASON_NAMES = arrayOf(
            "Any", "Winter", "Spring", "Summer", "Fall",
        )
        private val SEASON_SELECT_VALUES = arrayOf(
            "", "winter", "spring", "summer", "fall",
        )

        private val FORMAT_NAMES = arrayOf(
            "Any", "TV", "Movie", "OVA", "ONA", "Special", "Music",
        )
        private val FORMAT_SELECT_VALUES = arrayOf(
            "", "tv", "movie", "ova", "ona", "special", "music",
        )

        private val STATUS_NAMES = arrayOf(
            "Any", "Airing", "Finished", "Upcoming", "Unreleased", "Cancelled",
        )
        private val STATUS_SELECT_VALUES = arrayOf(
            "", "current", "finished", "upcoming", "unreleased", "cancelled",
        )

        private val AGE_NAMES = arrayOf(
            "Any", "G", "PG", "R", "R18",
        )
        private val AGE_SELECT_VALUES = arrayOf(
            "", "G", "PG", "R", "R18",
        )

        private val GENRE_NAMES = arrayOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Sci Fi",
            "Romance", "Slice of Life", "Horror", "Thriller", "Mystery",
            "Psychological", "Mecha", "Sports", "Music", "Isekai",
            "Supernatural", "Magic", "Historical", "Military", "School",
            "Shounen", "Shoujo", "Seinen", "Josei",
        )
        private val GENRE_VALUES = arrayOf(
            "action", "adventure", "comedy", "drama", "fantasy", "sci-fi",
            "romance", "slice-of-life", "horror", "thriller", "mystery",
            "psychological", "mecha", "sports", "music", "isekai",
            "supernatural", "magic", "historical", "military", "school",
            "shounen", "shoujo", "seinen", "josei",
        )
    }
}
