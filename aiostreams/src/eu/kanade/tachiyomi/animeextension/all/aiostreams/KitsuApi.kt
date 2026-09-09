package eu.kanade.tachiyomi.animeextension.all.aiostreams

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Kitsu API client (https://kitsu.app/api/edge) — JSON:API 1.0, no auth needed.
 *
 * Primary metadata provider: browse, search, details, seasons/relations.
 * Episode-level metadata comes from AniZip (single request) with Kitsu as
 * fallback for entries AniZip doesn't cover.
 */
object KitsuApi {

    const val BASE_URL = "https://kitsu.app"
    private const val API_URL = "$BASE_URL/api/edge"
    private const val PAGE_SIZE = 20 // Kitsu hard-caps page[limit] at 20

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ============================== DTOs ==============================

    @Serializable
    data class Links(
        val first: String? = null,
        val prev: String? = null,
        val next: String? = null,
        val last: String? = null,
    )

    @Serializable
    data class AnimeListResponse(
        val data: List<AnimeResource>? = null,
        val included: List<IncludedResource>? = null,
        val links: Links? = null,
    )

    @Serializable
    data class AnimeResponse(
        val data: AnimeResource? = null,
        val included: List<IncludedResource>? = null,
    )

    @Serializable
    data class AnimeResource(
        val id: String? = null,
        val type: String? = null,
        val attributes: KitsuAnime? = null,
        val relationships: Map<String, RelationshipLink>? = null,
    )

    /** Union of `mappings`, `categories` and `mediaRelationships` included resources. */
    @Serializable
    data class IncludedResource(
        val id: String? = null,
        val type: String? = null,
        val attributes: IncludedAttributes? = null,
        val relationships: Map<String, RelationshipLink>? = null,
    )

    /**
     * A resource linkage entry. `data` is an array for to-many relationships
     * (mediaRelationships) and an object for to-one ones (posterImage,
     * destination) — hence the raw JsonElement.
     */
    @Serializable
    data class RelationshipLink(
        val data: JsonElement? = null,
    ) {
        fun refIds(): List<String> = (data as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            .orEmpty()

        fun singleRefId(): String? =
            (data as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
    }

    @Serializable
    data class IncludedAttributes(
        // mappings
        @SerialName("externalSite") val externalSite: String? = null,
        @SerialName("externalId") val externalId: String? = null,
        // categories
        val title: String? = null,
        val slug: String? = null,
        // media-relationships
        val role: String? = null,
    )

    @Serializable
    data class RelationListResponse(
        val data: List<RelationResource>? = null,
        val included: List<AnimeResource>? = null,
    )

    @Serializable
    data class RelationResource(
        val id: String? = null,
        val attributes: IncludedAttributes? = null,
        val relationships: RelationDestinations? = null,
    )

    @Serializable
    data class RelationDestinations(
        val destination: DestinationLink? = null,
    )

    @Serializable
    data class DestinationLink(
        val data: Ref? = null,
    )

    @Serializable
    data class Ref(
        val id: String? = null,
        val type: String? = null,
    )

    @Serializable
    data class EpisodeListResponse(
        val data: List<EpisodeResource>? = null,
        val links: Links? = null,
    )

    @Serializable
    data class EpisodeResource(
        val id: String? = null,
        val attributes: KitsuEpisode? = null,
    )

    // ============================ Attributes ===========================

    @Serializable
    data class KitsuAnime(
        val canonicalTitle: String? = null,
        val titles: KitsuTitles? = null,
        val synopsis: String? = null,
        val episodeCount: Int? = null,
        val episodeLength: Int? = null,
        val subtype: String? = null,
        val status: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
        val averageRating: String? = null,
        val ageRating: String? = null,
        val ageRatingGuide: String? = null,
        val popularityRank: Int? = null,
        val ratingRank: Int? = null,
        val posterImage: KitsuPoster? = null,
        val coverImage: KitsuCover? = null,
        val nsfw: Boolean? = null,
    )

    @Serializable
    data class KitsuTitles(
        val en: String? = null,
        @SerialName("en_us") val enUs: String? = null,
        @SerialName("en_jp") val enJp: String? = null,
        @SerialName("ja_jp") val jaJp: String? = null,
    )

    @Serializable
    data class KitsuPoster(
        val tiny: String? = null,
        val small: String? = null,
        val medium: String? = null,
        val large: String? = null,
        val original: String? = null,
    )

    @Serializable
    data class KitsuCover(
        val tiny: String? = null,
        val small: String? = null,
        val large: String? = null,
        val original: String? = null,
    )

    @Serializable
    data class KitsuEpisode(
        val number: Int? = null,
        val seasonNumber: Int? = null,
        val titles: KitsuTitles? = null,
        @SerialName("airdate") val airDate: String? = null,
        val thumbnail: KitsuPoster? = null,
        val length: Int? = null,
    )

    // ============================ Result types =========================

    data class AnimePage(
        val anime: List<KitsuListItem>,
        val hasNextPage: Boolean,
    )

    data class KitsuListItem(
        val id: String,
        val attributes: KitsuAnime,
        /** True when any related anime matches the caller's season roles */
        val hasSeasonRelations: Boolean,
    )

    data class AnimeDetails(
        val id: String,
        val anime: KitsuAnime,
        /** externalSite (e.g. "thetvdb/series") to externalId */
        val mappings: Map<String, String>,
        val categories: List<String>,
    )

    data class RelatedAnime(
        val role: String,
        val id: String,
        val anime: KitsuAnime,
    )

    // ============================ URL builders =========================

    // Relations are included on list responses so browse items can carry the
    // seasons fetch_type up front — the app does not propagate fetch_type
    // from animeDetailsParse into its entry, so list time is the only chance.
    private const val INCLUDE_RELATIONS = "mediaRelationships.destination"

    /** Popular = most users; offset-paged, `links.next` marks more pages. */
    fun popularUrl(page: Int): String =
        "$API_URL/anime".toHttpUrl().newBuilder()
            .addQueryParameter("page[limit]", PAGE_SIZE.toString())
            .addQueryParameter("page[offset]", offset(page).toString())
            .addQueryParameter("sort", "-userCount")
            .addQueryParameter("include", INCLUDE_RELATIONS)
            .build().toString()

    /** Latest = currently airing, most recently started first. */
    fun latestUrl(page: Int): String =
        "$API_URL/anime".toHttpUrl().newBuilder()
            .addQueryParameter("page[limit]", PAGE_SIZE.toString())
            .addQueryParameter("page[offset]", offset(page).toString())
            .addQueryParameter("filter[status]", "current")
            .addQueryParameter("sort", "-startDate")
            .addQueryParameter("include", INCLUDE_RELATIONS)
            .build().toString()

    /**
     * Search URL with optional text + filter params (e.g. filter[season]=winter).
     * Blank query with filters set still works (filter-only browsing).
     */
    fun searchUrl(page: Int, query: String, filters: Map<String, String>, sort: String?): String {
        val builder = "$API_URL/anime".toHttpUrl().newBuilder()
            .addQueryParameter("page[limit]", PAGE_SIZE.toString())
            .addQueryParameter("page[offset]", offset(page).toString())
            .addQueryParameter("include", INCLUDE_RELATIONS)
        if (query.isNotBlank()) {
            builder.addQueryParameter("filter[text]", query.trim())
        }
        filters.forEach { (name, value) ->
            if (value.isNotBlank()) builder.addQueryParameter(name, value)
        }
        if (!sort.isNullOrBlank()) builder.addQueryParameter("sort", sort)
        return builder.build().toString()
    }

    /** Single anime with mappings + categories included. */
    fun animeDetailsUrl(kitsuId: String): String =
        "$API_URL/anime/$kitsuId?include=mappings,categories"

    /** Related anime (sequels, prequels, side stories, ...) with destinations included. */
    fun relationsUrl(kitsuId: String): String =
        "$API_URL/anime/$kitsuId/media-relationships?include=destination&page[limit]=20"

    // ============================ Body parsers =========================

    /**
     * List of anime + hasNextPage; null when the body can't be parsed.
     * `seasonRoles` decides which relations mark an entry as multi-season.
     */
    fun parseAnimePage(body: String, seasonRoles: Set<String> = emptySet()): AnimePage? {
        val parsed = runCatching { json.decodeFromString<AnimeListResponse>(body) } ?: return null

        val includedById = parsed.included.orEmpty().filterNotNull()
            .filter { !it.id.isNullOrBlank() }
            .associateBy { it.id!! }

        val anime = parsed.data.orEmpty().mapNotNull { res ->
            val id = res.id ?: return@mapNotNull null
            val attributes = res.attributes ?: return@mapNotNull null
            KitsuListItem(id, attributes, hasRelations(res, includedById, seasonRoles))
        }
        return AnimePage(anime, hasNextPage = parsed.links?.next != null)
    }

    /**
     * True when the anime links to at least one mediaRelationship whose role
     * is a season role and whose destination is another anime (not manga).
     */
    private fun hasRelations(
        res: AnimeResource,
        includedById: Map<String, IncludedResource>,
        seasonRoles: Set<String>,
    ): Boolean {
        if (seasonRoles.isEmpty()) return false

        val relIds = res.relationships?.get("mediaRelationships")?.refIds() ?: return false
        relIds.forEach { relId ->
            val rel = includedById[relId] ?: return@forEach
            val role = rel.attributes?.role ?: return@forEach
            if (role !in seasonRoles) return@forEach

            val destId = rel.relationships?.get("destination")?.singleRefId() ?: return@forEach
            if (includedById[destId]?.type == "anime") return true
        }
        return false
    }

    /** Single anime with extracted mappings + categories; null when unparseable. */
    fun parseAnimeResponse(body: String, fallbackId: String = ""): AnimeDetails? {
        val parsed = runCatching { json.decodeFromString<AnimeResponse>(body) } ?: return null
        val resource = parsed.data ?: return null
        val anime = resource.attributes ?: return null

        val mappings = mutableMapOf<String, String>()
        val categories = mutableListOf<String>()
        parsed.included.orEmpty().forEach { inc ->
            when (inc.type) {
                "mappings" -> {
                    val site = inc.attributes?.externalSite
                    val extId = inc.attributes?.externalId
                    if (!site.isNullOrBlank() && !extId.isNullOrBlank()) {
                        mappings[site] = extId
                    }
                }
                "categories" -> inc.attributes?.title?.let { categories.add(it) }
            }
        }
        return AnimeDetails(resource.id ?: fallbackId, anime, mappings, categories)
    }

    fun parseRelations(body: String): List<RelatedAnime> {
        val parsed = runCatching { json.decodeFromString<RelationListResponse>(body) } ?: return emptyList()
        val destinations = parsed.included.orEmpty().filterNotNull()
            .filter { it.type == "anime" }
            .associateBy { it.id ?: "" }

        return parsed.data.orEmpty().mapNotNull { rel ->
            val role = rel.attributes?.role ?: return@mapNotNull null
            val destId = rel.relationships?.destination?.data?.id ?: return@mapNotNull null
            val dest = destinations[destId]?.attributes ?: return@mapNotNull null
            RelatedAnime(role, destId, dest)
        }
    }

    // ============================== Fetches ============================

    /** Single anime with mappings + categories included (direct execution). */
    fun fetchAnime(client: OkHttpClient, kitsuId: String): AnimeDetails? {
        val body = runCatchingBody(client, animeDetailsUrl(kitsuId)) ?: return null
        return parseAnimeResponse(body, fallbackId = kitsuId)
    }

    /** Related anime (direct execution). */
    fun fetchRelations(client: OkHttpClient, kitsuId: String): List<RelatedAnime> {
        val body = runCatchingBody(client, relationsUrl(kitsuId)) ?: return emptyList()
        return parseRelations(body)
    }

    /**
     * All episodes for an anime, paged through at 20 per request.
     * Only used when AniZip has no episode data.
     */
    fun fetchEpisodes(client: OkHttpClient, kitsuId: String): Map<Int, KitsuEpisode> {
        val byNumber = mutableMapOf<Int, KitsuEpisode>()
        var offset = 0
        var pages = 0
        while (pages < 100) { // hard cap: 100 pages = 2000 episodes
            val url = "$API_URL/anime/$kitsuId/episodes".toHttpUrl().newBuilder()
                .addQueryParameter("page[limit]", PAGE_SIZE.toString())
                .addQueryParameter("page[offset]", offset.toString())
                .build().toString()
            val body = runCatchingBody(client, url) ?: break
            val parsed = runCatching { json.decodeFromString<EpisodeListResponse>(body) } ?: break
            val page = parsed.data.orEmpty()
            if (page.isEmpty()) break
            page.forEach { res ->
                val attrs = res.attributes ?: return@forEach
                val num = attrs.number ?: return@forEach
                byNumber[num] = attrs
            }
            if (parsed.links?.next == null) break
            offset += PAGE_SIZE
            pages++
        }
        return byNumber
    }

    // ============================== Helpers ============================

    private fun offset(page: Int) = ((page.coerceAtLeast(1)) - 1) * PAGE_SIZE

    private fun runCatchingBody(client: OkHttpClient, url: String): String? = try {
        client.newCall(GET(url)).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    } catch (e: Exception) {
        null
    }

    private inline fun <reified T : Any> runCatching(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}
