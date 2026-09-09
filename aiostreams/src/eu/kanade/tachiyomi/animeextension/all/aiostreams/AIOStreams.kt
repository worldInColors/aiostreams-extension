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
import java.util.concurrent.ConcurrentHashMap

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
        return GET(KitsuApi.popularUrl(page), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val useSeasons = preferences.getBoolean(PREF_USE_SEASONS, PREF_USE_SEASONS_DEFAULT)

        val parsed = KitsuApi.parseAnimePage(response.body.string(), seasonRoles.toSet())
            ?: return AnimesPage(emptyList(), false)

        val animeList = parsed.anime.map { item ->
            SAnime.create().apply {
                title = pickTitle(item.attributes)
                thumbnail_url = bestPoster(item.attributes)
                url = "kitsu:${item.id}"
                description = item.attributes.synopsis.orEmpty()
                genre = "" // genres require the categories include; filled in details
                status = parseKitsuStatus(item.attributes.status)
                // Seasons routing must happen at list time: the app does not
                // carry fetch_type over from animeDetailsParse into the entry
                if (useSeasons && item.hasSeasonRelations) {
                    fetch_type = FetchType.Seasons
                }
            }
        }

        return AnimesPage(animeList, parsed.hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(KitsuApi.latestUrl(page), headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = mutableMapOf<String, String>()
        var sort: String? = null

        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> when (filter.name) {
                    "Sort" -> {
                        when (SORT_SELECT_VALUES.getOrNull(filter.state).orEmpty()) {
                            "trending" -> {
                                // Trending ≈ most popular currently airing. Built on the
                                // regular /anime endpoint (unlike Kitsu's trending feed,
                                // which can't page or carry relation includes) so it
                                // scrolls and supports seasons mode like every other list.
                                sort = "-userCount"
                                if (params["filter[status]"].isNullOrBlank()) {
                                    params["filter[status]"] = "current"
                                }
                            }
                            "" -> {}
                            else -> sort = SORT_SELECT_VALUES[filter.state]
                        }
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

        return GET(KitsuApi.searchUrl(page, query, params, sort), headers)
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
        }
    }

    // ============================== Seasons ===============================

    override fun seasonListRequest(anime: SAnime): Request {
        val kitsuId = resolveKitsuId(anime.url)
            ?: throw Exception("Could not resolve anime id from '${anime.url}'")
        return GET(KitsuApi.relationsUrl(kitsuId), headers)
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        val relations = KitsuApi.parseRelations(response.body.string())

        // The relations payload doesn't contain the base anime itself, but its
        // id sits in the request URL path (/anime/{id}/media-relationships)
        val segments = response.request.url.pathSegments
        val baseId = segments.getOrNull(segments.indexOf("anime") + 1)
        val main = baseId?.let { KitsuApi.fetchAnime(client, it) } ?: return emptyList()
        val mainTitle = pickTitle(main.anime)
        if (currentAnimeTitle.isBlank()) {
            currentAnimeTitle = mainTitle
        }
        val titlePart = "title:${mainTitle.replace("|", " ")}"

        val seasonList = mutableListOf<SAnime>()

        // The main anime as "Season 1"
        seasonList.add(SAnime.create().apply {
            title = mainTitle
            thumbnail_url = bestPoster(main.anime)
            url = "kitsu:${main.id}|season:1|$titlePart"
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
                        url = "kitsu:${edge.id}|season:$seasonNum|$titlePart"
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

    // Anime title for the filler/local-file flows. The canonical copy travels
    // inside the episode URL ("title:..."), so hoster fetches re-derive it and
    // stale values from a concurrent library refresh can't leak into playback.
    private var currentAnimeTitle: String = ""

    // Cache of resolved legacy AniList ids -> Kitsu ids
    private val kitsuIdByAnilist = ConcurrentHashMap<Int, String>()

    /**
     * Per-parse episode metadata sources. Kept as locals instead of instance
     * fields so parallel library updates of different entries can't read each
     * other's AniZip/Kitsu/TVDB data (GitHub issue #3: entries showing another
     * show's episode list after a Global Update until manually refreshed).
     */
    private class EpisodeSources(
        val anizip: AniZipResponse?,
        val kitsu: Map<Int, KitsuApi.KitsuEpisode>,
        val tvdb: Map<String, TvDbApi.EpisodeData>,
        val anidb: Map<String, String>,
    ) {
        val anizipEpisodes: Map<String, AniZipEpisode?> get() = anizip?.episodes ?: emptyMap()
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val base = anime.url.split("|").first()
        anime.url.split("|").forEach { part ->
            if (part.startsWith("title:")) currentAnimeTitle = part.removePrefix("title:")
        }

        val kitsuId = resolveKitsuId(anime.url)
        return if (kitsuId != null) {
            GET(KitsuApi.animeDetailsUrl(kitsuId), headers)
        } else {
            // Unresolvable legacy entry — AniZip-only parse, keyed by the id
            // embedded in this very request URL
            val anilistId = base.toIntOrNull() ?: 0
            GET("https://api.ani.zip/mappings?anilist_id=$anilistId", headers)
        }
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
        val body = response.body.string()
        val parsed = KitsuApi.parseAnimeResponse(body)

        // Kitsu path: every side-fetch below is keyed by the id that traveled
        // through this response, so concurrent refreshes stay isolated.
        if (parsed != null) {
            return buildFromKitsu(parsed)
        }

        // Unresolvable legacy entry — the request hit AniZip directly and the
        // body is self-describing
        val anizip = try {
            json.decodeFromString<AniZipResponse>(body)
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        if (currentAnimeTitle.isBlank()) {
            currentAnimeTitle = anizip.titles?.values?.filterNotNull()?.firstOrNull().orEmpty()
        }
        return buildFallbackEpisodeList(anizip)
    }

    private fun buildFromKitsu(details: KitsuApi.AnimeDetails): List<SEpisode> {
        if (currentAnimeTitle.isBlank()) {
            currentAnimeTitle = pickTitle(details.anime)
        }

        val anizip = fetchAniZip("kitsu_id=${details.id}")
        val anizipEpisodes = anizip?.episodes ?: emptyMap()

        // Kitsu is the primary episode-metadata source: its English titles,
        // synopses and thumbnails cover long shows far better than AniZip
        // (AniZip's Naruto entry has no titles at all and synopses only
        // through ep 48). Kitsu pages at 20/request though, so very long
        // shows stay AniZip-primary (AniZip covers them in one request).
        val totalEpisodes = details.anime.episodeCount ?: 0
        val knownEpisodes = maxOf(totalEpisodes, anizipEpisodes.size)
        val kitsuEpisodes = if (
            anizipEpisodes.isEmpty() || knownEpisodes <= KITSU_EPISODE_FETCH_CAP
        ) {
            KitsuApi.fetchEpisodes(client, details.id)
        } else {
            emptyMap()
        }

        val tvdbEpisodes = loadTvdbEpisodes(details, anizip)
        val partial = EpisodeSources(anizip, kitsuEpisodes, tvdbEpisodes, emptyMap())
        val anidbTitles = fetchAniDbTitlesIfNeeded(details, partial)

        return buildEpisodeList(details, EpisodeSources(anizip, kitsuEpisodes, tvdbEpisodes, anidbTitles))
    }

    /**
     * TVDB episode data when a key is configured and a TVDB series id is
     * available from AniZip/Kitsu mappings. Fills gaps AniZip/Kitsu leave
     * (titles, overviews, thumbnails) — English by default.
     */
    private fun loadTvdbEpisodes(details: KitsuApi.AnimeDetails, anizip: AniZipResponse?): Map<String, TvDbApi.EpisodeData> {
        val tvdbKey = preferences.getString(PREF_TVDB_KEY, "").orEmpty()
        if (tvdbKey.isBlank()) return emptyMap()

        val tvdbId = anizip?.mappings?.theTvDbId
            ?: details.mappings["thetvdb/series"]?.toLongOrNull()
            ?: details.mappings["thetvdb"]?.substringBefore("/")?.toLongOrNull()
        if (tvdbId == null || tvdbId <= 0) return emptyMap()

        val episodes = TvDbApi.getAllEpisodes(client, tvdbKey, tvdbId)
        return if (episodes.isNotEmpty()) TvDbApi.episodesToMap(episodes) else emptyMap()
    }

    private fun buildEpisodeList(details: KitsuApi.AnimeDetails, sources: EpisodeSources): List<SEpisode> {
        val anime = details.anime
        val totalEpisodes = anime.episodeCount ?: 0
        val isMovie = anime.subtype == "movie"

        val anizipEpisodes = sources.anizipEpisodes
        val kitsuEpisodes = sources.kitsu
        val tvdbEpisodes = sources.tvdb

        val metadataStatus = buildList {
            if (anizipEpisodes.isNotEmpty()) add("AniZip: ${anizipEpisodes.size}")
            if (kitsuEpisodes.isNotEmpty()) add("Kitsu: ${kitsuEpisodes.size}")
            if (tvdbEpisodes.isNotEmpty()) add("TVDB: ${tvdbEpisodes.size}")
            if (sources.anidb.isNotEmpty()) add("AniDB: ${sources.anidb.size}")
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

        val mappings = sources.anizip?.mappings
        val imdbPart = mappings?.imdbId?.let { "imdb:$it" } ?: ""
        val tmdbPart = mappings?.theMovieDbId?.let { "tmdb:$it" } ?: ""
        val kitsuPart = "kitsu:${details.id}"
        val malPart = mappings?.myAnimeListId?.let { "mal:$it" } ?: ""
        val anilistPart = mappings?.aniListId?.let { "anilist:$it" } ?: ""
        val titlePart = currentAnimeTitle.takeIf { it.isNotBlank() }
            ?.let { "title:${it.replace("|", " ")}" } ?: ""

        if (isMovie) {
            val epTitle = episodeTitle(1, sources).ifBlank { "Movie" }
            episodeList.add(
                SEpisode.create().apply {
                    episode_number = 1.0F
                    name = epTitle
                    date_upload = episodeAirDate(1, sources)
                    summary = episodeOverview(1, sources)
                    preview_url = episodeImage(1, sources)
                    scanlator = metadataStatus
                    url = buildEpisodeUrl("movie", 0, 0, imdbPart, tmdbPart, kitsuPart, malPart, anilistPart, titlePart)
                },
            )
        } else {
            val maxEpisodes = when {
                // Kitsu numbers only aired episodes; AniZip folds specials
                // into the numeric range (Naruto: 246 keys for 220 episodes)
                kitsuEpisodes.isNotEmpty() -> kitsuEpisodes.keys.maxOrNull() ?: totalEpisodes
                anizipEpisodes.isNotEmpty() -> anizipEpisodes.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: totalEpisodes
                totalEpisodes > 0 -> totalEpisodes
                else -> 0
            }

            for (epNum in 1..maxEpisodes) {
                val airDate = episodeAirDate(epNum, sources)

                if (airDate > 0 && airDate > now) continue

                val epTitle = episodeTitle(epNum, sources)
                val isFiller = fillerEpisodes.contains(epNum)

                if (!hasEpisodeData(epNum, sources) && totalEpisodes > 0 && epNum > totalEpisodes) break

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
                        summary = episodeOverview(epNum, sources)
                        preview_url = episodeImage(epNum, sources)
                        fillermark = isFiller
                        if (epNum == 1 && metadataStatus.isNotBlank()) {
                            scanlator = metadataStatus
                        }
                        url = buildEpisodeUrl(epNum.toString(), seasonNum, epInSeason, imdbPart, tmdbPart, kitsuPart, malPart, anilistPart, titlePart)
                    },
                )
            }
        }

        return episodeList.sortedByDescending { it.episode_number }
    }

    // ---- per-episode metadata merge: Kitsu first, AniZip fills gaps, TVDB
    // ---- and AniDB last. Within titles: English before romaji before Japanese.

    private fun hasEpisodeData(epNum: Int, sources: EpisodeSources): Boolean =
        sources.anizipEpisodes.containsKey(epNum.toString()) ||
            sources.kitsu.containsKey(epNum) ||
            sources.tvdb.containsKey(epNum.toString())

    private fun episodeTitle(epNum: Int, sources: EpisodeSources): String {
        val anizipTitles = sources.anizipEpisodes[epNum.toString()]?.title
        val kitsuTitles = sources.kitsu[epNum]?.titles
        val tvdbName = sources.tvdb[epNum.toString()]?.name

        return sequenceOf(
            kitsuTitles?.enUs,
            kitsuTitles?.en,
            anizipTitles?.get("en"),
            tvdbName,
            kitsuTitles?.enJp,
            anizipTitles?.get("en_jp"),
            sources.anidb[epNum.toString()],
            kitsuTitles?.jaJp,
            anizipTitles?.get("ja"),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun episodeAirDate(epNum: Int, sources: EpisodeSources): Long {
        sources.anizipEpisodes[epNum.toString()]?.airDate?.let { return parseDate(it) }
        sources.kitsu[epNum]?.airDate?.let { return parseDate(it) }
        sources.tvdb[epNum.toString()]?.airDate?.let { return parseDate(it) }
        return 0L
    }

    private fun episodeOverview(epNum: Int, sources: EpisodeSources): String? {
        sources.kitsu[epNum]?.synopsis
            ?.takeIf { it.isNotBlank() }?.let { return it }
        sources.anizipEpisodes[epNum.toString()]?.overview
            ?.takeIf { it.isNotBlank() }?.let { return it }
        sources.tvdb[epNum.toString()]?.overview
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun episodeImage(epNum: Int, sources: EpisodeSources): String? {
        sources.kitsu[epNum]?.thumbnail?.original
            ?.takeIf { it.isNotBlank() }?.let { return it }
        sources.anizipEpisodes[epNum.toString()]?.image
            ?.takeIf { it.isNotBlank() }?.let { return it }
        sources.tvdb[epNum.toString()]?.imageUrl
            ?.takeIf { it.isNotBlank() }?.let { return buildTvdbImageUrl(it) }
        return null
    }

    /**
     * AniDB lookup only when enabled, an id is known, and the other sources
     * left titles missing. AniDB is heavily rate limited, so this is a
     * last-resort fill.
     */
    private fun fetchAniDbTitlesIfNeeded(details: KitsuApi.AnimeDetails, partial: EpisodeSources): Map<String, String> {
        if (!preferences.getBoolean(PREF_USE_ANIDB, PREF_USE_ANIDB_DEFAULT)) return emptyMap()

        val expected = maxOf(details.anime.episodeCount ?: 0, partial.anizipEpisodes.size)
        val missingTitles = (1..expected).count { episodeTitle(it, partial).isBlank() }
        if (missingTitles == 0) return emptyMap()

        val anidbId = partial.anizip?.mappings?.aniDbId
            ?: details.mappings["anidb"]?.toLongOrNull()
        if (anidbId == null || anidbId <= 0) return emptyMap()

        return try {
            runBlocking { AniDbApi.getEpisodeTitles(client, anidbId) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun buildFallbackEpisodeList(anizip: AniZipResponse): List<SEpisode> {
        val anizipEpisodes = anizip.episodes ?: emptyMap()
        if (anizipEpisodes.isEmpty()) return emptyList()

        val episodeCount = anizipEpisodes.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: return emptyList()
        val metadataStatus = "AniZip: ${anizipEpisodes.size} eps"
        val now = System.currentTimeMillis()

        val mappings = anizip.mappings
        val imdbPart = mappings?.imdbId?.let { "imdb:$it" } ?: ""
        val tmdbPart = mappings?.theMovieDbId?.let { "tmdb:$it" } ?: ""
        val kitsuPart = mappings?.kitsuId?.let { "kitsu:$it" } ?: ""
        val malPart = mappings?.myAnimeListId?.let { "mal:$it" } ?: ""
        val anilistPart = mappings?.aniListId?.let { "anilist:$it" } ?: ""
        val titlePart = currentAnimeTitle.takeIf { it.isNotBlank() }
            ?.let { "title:${it.replace("|", " ")}" } ?: ""

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
                        url = buildEpisodeUrl(epNum.toString(), anizipEp?.seasonNumber ?: 1, anizipEp?.episodeNumber ?: epNum, imdbPart, tmdbPart, kitsuPart, malPart, anilistPart, titlePart)
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
     * Build episode URL with all ID mappings for streaming. The trailing
     * title part lets the hoster/local flows re-derive the anime title from
     * the URL instead of instance state.
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
        titlePart: String = "",
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
        if (titlePart.isNotBlank()) parts.add(titlePart)
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
        // Title travels in the URL so concurrent browsing can't leak another
        // anime's title into local-file matching or filler lookups
        parts["title"]?.takeIf { it.isNotBlank() }?.let { currentAnimeTitle = it }
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
        // Max episode count for which Kitsu's 20-per-page episode endpoint is
        // paged through when AniZip's English coverage is incomplete
        private const val KITSU_EPISODE_FETCH_CAP = 260

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
