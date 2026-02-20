package eu.kanade.tachiyomi.animeextension.all.aiostreams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AniZip Response - https://api.ani.zip
 * Provides episode metadata and ID mappings
 */
@Serializable
data class AniZipResponse(
    val titles: Map<String, String?>? = null,
    val episodes: Map<String, AniZipEpisode?>? = null,
    val episodeCount: Int? = null,
    val specialCount: Int? = null,
    val images: List<AniZipImage?>? = null,
    val mappings: AniZipMappings? = null,
)

@Serializable
data class AniZipEpisode(
    val episode: String? = null,
    val episodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val title: Map<String, String?>? = null,
    val length: Int? = null,
    val runtime: Int? = null,
    @SerialName("airdate")
    val airDate: String? = null,
    val rating: String? = null,
    @SerialName("anidbEid")
    val aniDbEpisodeId: Long? = null,
    val tvdbShowId: Long? = null,
    val tvdbId: Long? = null,
    val overview: String? = null,
    val image: String? = null,
)

@Serializable
data class AniZipImage(
    val coverType: String? = null,
    val url: String? = null,
)

@Serializable
data class AniZipMappings(
    @SerialName("animeplanet_id")
    val animePlanetId: String? = null,
    @SerialName("kitsu_id")
    val kitsuId: Long? = null,
    @SerialName("mal_id")
    val myAnimeListId: Long? = null,
    val type: String? = null,
    @SerialName("anilist_id")
    val aniListId: Long? = null,
    @SerialName("anisearch_id")
    val aniSearchId: Long? = null,
    @SerialName("anidb_id")
    val aniDbId: Long? = null,
    @SerialName("notifymoe_id")
    val notifyMoeId: String? = null,
    @SerialName("livechart_id")
    val liveChartId: Long? = null,
    @SerialName("thetvdb_id")
    val theTvDbId: Long? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("themoviedb_id")
    val theMovieDbId: String? = null,
)

/**
 * AniList GraphQL Response structures
 */
@Serializable
data class AniListMediaResponse(
    val data: AniListMediaData? = null,
)

@Serializable
data class AniListMediaData(
    val Media: AniListMedia? = null,
)

@Serializable
data class AniListSearchResponse(
    val data: AniListSearchData? = null,
)

@Serializable
data class AniListSearchData(
    val Page: AniListPage? = null,
)

@Serializable
data class AniListPage(
    val media: List<AniListMedia?>? = null,
    val pageInfo: AniListPageInfo? = null,
)

@Serializable
data class AniListPageInfo(
    val hasNextPage: Boolean? = null,
)

@Serializable
data class AniListMedia(
    val id: Int? = null,
    val title: AniListTitle? = null,
    val coverImage: AniListCover? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val genres: List<String?>? = null,
    val tags: List<AniListTag?>? = null,
    val averageScore: Int? = null,
    val meanScore: Int? = null,
    val popularity: Int? = null,
    val studios: AniListStudioConnection? = null,
    val startDate: AniListFuzzyDate? = null,
    val endDate: AniListFuzzyDate? = null,
    val nextAiringEpisode: AniListAiringSchedule? = null,
    val airingSchedule: AniListAiringScheduleConnection? = null,
    val relations: AniListRelationConnection? = null,
    val recommendations: AniListRecommendationConnection? = null,
    val isAdult: Boolean? = null,
    val countryOfOrigin: String? = null,
)

@Serializable
data class AniListTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AniListCover(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val color: String? = null,
)

@Serializable
data class AniListTag(
    val id: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val rank: Int? = null,
    val isMediaSpoiler: Boolean? = null,
)

@Serializable
data class AniListStudioConnection(
    val nodes: List<AniListStudio?>? = null,
)

@Serializable
data class AniListStudio(
    val id: Int? = null,
    val name: String? = null,
    val isAnimationStudio: Boolean? = null,
)

@Serializable
data class AniListFuzzyDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
)

@Serializable
data class AniListAiringSchedule(
    val id: Int? = null,
    val airingAt: Long? = null,
    val timeUntilAiring: Long? = null,
    val episode: Int? = null,
)

@Serializable
data class AniListAiringScheduleConnection(
    val nodes: List<AniListAiringSchedule?>? = null,
)

@Serializable
data class AniListRelationConnection(
    val edges: List<AniListRelationEdge?>? = null,
)

@Serializable
data class AniListRelationEdge(
    val id: Int? = null,
    val relationType: String? = null,
    val node: AniListMedia? = null,
)

@Serializable
data class AniListRecommendationConnection(
    val nodes: List<AniListRecommendation?>? = null,
)

@Serializable
data class AniListRecommendation(
    val id: Int? = null,
    val rating: Int? = null,
    val mediaRecommendation: AniListMedia? = null,
)

/**
 * AniDB API Response structures
 * AniDB provides detailed episode metadata
 */
@Serializable
data class AniDbAnimeResponse(
    val anime: AniDbAnime? = null,
)

@Serializable
data class AniDbAnime(
    val id: Long? = null,
    val type: String? = null,
    val episodecount: Int? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val titles: List<AniDbTitle>? = null,
    val episodes: List<AniDbEpisode>? = null,
    val tags: List<AniDbTag>? = null,
    val creators: List<AniDbCreator>? = null,
    val ratings: AniDbRatings? = null,
)

@Serializable
data class AniDbTitle(
    val type: String? = null,
    val lang: String? = null,
    val title: String? = null,
)

@Serializable
data class AniDbEpisode(
    val id: Long? = null,
    val epno: String? = null,
    val length: Int? = null,
    val airdate: String? = null,
    val rating: String? = null,
    val title: List<AniDbTitle>? = null,
    val summary: String? = null,
)

@Serializable
data class AniDbTag(
    val id: Long? = null,
    val name: String? = null,
    val weight: Int? = null,
)

@Serializable
data class AniDbCreator(
    val id: Long? = null,
    val type: String? = null,
    val name: String? = null,
)

@Serializable
data class AniDbRatings(
    val permanent: Double? = null,
    val temporary: Double? = null,
    val review: Double? = null,
)

/**
 * Season data for seasons support
 */
@Serializable
data class SeasonInfo(
    val anilistId: Int,
    val seasonNumber: Double,
    val title: String,
    val thumbnailUrl: String? = null,
    val relationType: String? = null,
    val episodeCount: Int? = null,
)