package eu.kanade.tachiyomi.animeextension.all.aiostreams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AniZip Response - https://api.ani.zip
 * Provides episode metadata and ID mappings (accepts kitsu_id, anilist_id, mal_id)
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
    val theMovieDbId: Long? = null,
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
