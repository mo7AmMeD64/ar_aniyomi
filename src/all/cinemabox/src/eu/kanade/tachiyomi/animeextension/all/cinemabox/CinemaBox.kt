package eu.kanade.tachiyomi.animeextension.all.cinemabox

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.get
import extensions.utils.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

class CinemaBox : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "CinemaBox"

    override val lang = "all"

    override val baseUrl = "https://cinema.albox.co"

    override val supportsLatest = false

    private val api: String get() = "$baseUrl/api/v4"

    private val categories = listOf(
        "" to "الكل",
        "18" to "أفلام",
        "20" to "مسلسلات",
        "8" to "أنمي",
        "9" to "برامج تلفزيونية",
        "39" to "كرتون",
        "50" to "رمضان",
    )

    // Random per-install id, the API only uses it to tell devices apart.
    private val deviceId: String = (1..16).map { "0123456789abcdef".random() }.joinToString("")

    private val baseHeaders: Map<String, String> = mapOf(
        "Accept-Language" to "en",
        "Device-Id" to deviceId,
        "Device-Model" to "Xiaomi 23043RP34G",
        "Device-OS-Version" to "14",
        "Device-Store" to "googleplay",
        "App-Version" to "4.6.12",
        "X-Hide-Sensitive-Content" to "true",
        "X-Local-Before" to "true",
        "X-ISP-ID" to "1",
    )

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .apply { baseHeaders.forEach { (k, v) -> set(k, v) } }

    // =============================== Json ================================

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

    private fun JsonObject.firstNumber(key: String): Int? =
        str(key)?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    private suspend fun apiGet(path: String): JsonObject? = try {
        client.get("$api/$path".toHttpUrl(), headers).parseAs<JsonObject>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        searchPage(page, "", null)

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Select<String>(
            "القسم",
            categories.map { it.second }.toTypedArray(),
        ),
    )

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val select = filters.firstOrNull() as? AnimeFilter.Select<*>
        val category = categories.getOrNull(select?.state ?: 0)?.first
        return searchPage(page, query, category)
    }

    private suspend fun searchPage(page: Int, term: String, category: String?): AnimesPage {
        val q = URLEncoder.encode(term.trim(), "UTF-8")
        val cat = if (category.isNullOrBlank()) "" else "&category_id=$category"
        val d = apiGet("search?page_size=25&page_number=$page&term=$q$cat")
            ?: return AnimesPage(emptyList(), false)

        val items = d.arr("results")?.objects()?.mapNotNull { it.toSAnime() } ?: emptyList()
        val totalPages = d.obj("pagination")?.str("total_pages")?.toIntOrNull() ?: 1
        return AnimesPage(items, page < totalPages)
    }

    private fun JsonObject.toSAnime(): SAnime? {
        val id = str("id") ?: return null
        val title = str("title")?.trim() ?: return null
        val type = str("type")?.uppercase()
        if (type != "MOVIE" && type != "SERIES") return null

        return SAnime.create().apply {
            url = id
            this.title = title
            thumbnail_url = obj("style")?.str("image")
        }
    }

    // ============================== Details ===============================

    private suspend fun details(showId: String): JsonObject? {
        apiGet("shows/shows/dynamic/$showId")?.let { if (it.obj("post_info") != null) return it }
        return apiGet("shows/shows/dynamic/$showId?season_id=0")
            ?.takeIf { it.obj("post_info") != null }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val d = details(anime.url) ?: throw Exception("Show not found")
        val info = d.obj("post_info") ?: throw Exception("Show not found")

        val rating = info.obj("rating")
        val description = listOfNotNull(
            rating?.str("value")?.let { "IMDb $it" },
            rating?.str("age"),
            info.str("description")?.trim(),
        ).joinToString("\n\n")

        return anime.apply {
            title = info.str("title")?.trim() ?: title
            this.description = description
            genre = info.arr("genres")
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() }
                ?.joinToString(", ")
            thumbnail_url = info.str("image") ?: thumbnail_url
            background_url = info.str("background_image")
            status = when (info.str("status")?.lowercase()) {
                "ongoing", "returning series" -> SAnime.ONGOING
                "ended", "completed", "canceled" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfterLast("/")
        val d = details(id) ?: return emptyList()
        val info = d.obj("post_info") ?: return emptyList()
        val sections = d.arr("sections")?.objects() ?: emptyList()

        // Movies: a single pseudo-episode. The player id lives in episode_id.
        if (info.str("type")?.uppercase() == "MOVIE") {
            return listOf(
                SEpisode.create().apply {
                    url = info.str("episode_id") ?: id
                    name = info.str("title")?.trim() ?: anime.title
                    episode_number = 1f
                },
            )
        }

        val currentSeasonId = info.str("current_season_id")
        val seasonCards = sections
            .filter { it.str("section_type") == "normalPoster" }
            .flatMap { it.arr("data")?.objects() ?: emptyList() }
            .filter {
                it.str("type")?.lowercase() == "season" ||
                    it.str("card_type")?.equals("seasonPoster", ignoreCase = true) == true
            }

        // (season number, season id) pairs — site card titles can hold duplicates.
        val seasons = mutableListOf<Pair<Int, String>>()
        val usedNumbers = mutableSetOf<Int>()
        seasonCards.forEachIndexed { index, card ->
            val sid = card.str("id") ?: return@forEachIndexed
            if (seasons.any { it.second == sid }) return@forEachIndexed
            var number = card.firstNumber("title") ?: (index + 1)
            if (!usedNumbers.add(number)) {
                number = (usedNumbers.maxOrNull() ?: 0) + 1
                usedNumbers.add(number)
            }
            seasons.add(number to sid)
        }
        if (currentSeasonId != null && seasons.none { it.second == currentSeasonId }) {
            seasons.add(
                (info.str("season_number")?.toDoubleOrNull()?.toInt() ?: 1) to currentSeasonId,
            )
        }

        val episodes = mutableListOf<SEpisode>()
        seasons.forEach { (seasonNumber, seasonId) ->
            seasonEpisodes(id, seasonId, seasonNumber).forEach { ep ->
                // Aniyomi expects one flat, uniquely numbered list.
                ep.episode_number = (episodes.size + 1).toFloat()
                if (seasons.size > 1) ep.name = "موسم $seasonNumber — ${ep.name}"
                episodes += ep
            }
        }

        // Single-season shows have no season cards — episodes are in the details response.
        if (episodes.isEmpty()) {
            episodes += episodesOf(
                sections,
                info.str("season_number")?.toDoubleOrNull()?.toInt() ?: 1,
            )
        }

        return episodes
    }

    private fun episodesOf(sections: List<JsonObject>, seasonNumber: Int): List<SEpisode> {
        val cards = sections.firstOrNull { it.str("section_type") == "episodes" }
            ?.arr("data")?.objects() ?: return emptyList()
        return cards.mapIndexedNotNull { index, ep ->
            val epId = ep.str("id") ?: return@mapIndexedNotNull null
            val epNumber = ep.str("description")?.toIntOrNull()
                ?: ep.firstNumber("title")
                ?: (index + 1)
            SEpisode.create().apply {
                url = epId
                name = "الحلقة $epNumber"
                episode_number = epNumber.toFloat()
                preview_url = ep.obj("style")?.str("image")
                if (seasonNumber > 1) name = "موسم $seasonNumber — $name"
            }
        }
    }

    private suspend fun seasonEpisodes(
        showId: String,
        seasonId: String,
        seasonNumber: Int,
    ): List<SEpisode> {
        // The details endpoint with ?season_id= returns that season's episodes.
        // current_season_id is checked so a season is never mixed up.
        apiGet("shows/shows/dynamic/$showId?season_id=$seasonId")?.let { d ->
            if (d.obj("post_info")?.str("current_season_id") == seasonId) {
                val eps = episodesOf(d.arr("sections")?.objects() ?: emptyList(), seasonNumber)
                if (eps.isNotEmpty()) return eps
            }
        }

        // Fallback: the dedicated season endpoint.
        val list = apiGet("shows/seasons/player/$seasonId")
            ?.arr("episodes")?.objects() ?: return emptyList()
        return list.mapIndexedNotNull { index, ep ->
            val epId = ep.str("id") ?: return@mapIndexedNotNull null
            val epNumber = ep.str("episode_number")?.toIntOrNull() ?: (index + 1)
            SEpisode.create().apply {
                url = epId
                name = "الحلقة $epNumber"
                episode_number = epNumber.toFloat()
                preview_url = ep.str("image")
                if (seasonNumber > 1) name = "موسم $seasonNumber — $name"
            }
        }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterUrl = "$api/shows/episodes/player/${episode.url}",
            hosterName = "CinemaBox",
        ),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val playerId = hoster.hosterUrl.substringAfterLast("/")
        val d = apiGet("shows/episodes/player/$playerId") ?: return emptyList()

        val subs = d.arr("subtitles")?.objects()?.mapNotNull { s ->
            val url = s.str("vtt") ?: s.str("srt") ?: return@mapNotNull null
            Track(url, s.str("language") ?: "ar")
        } ?: emptyList()

        val playbackHeaders = Headers.headersOf(
            "User-Agent",
            "Dalvik/2.1.0 (Linux; U; Android 14; 23043RP34G Build/UKQ1.240624.001)",
        )

        return d.arr("videos")?.objects()?.mapNotNull { v ->
            val url = v.str("url") ?: return@mapNotNull null
            val quality = v.str("quality") ?: ""
            Video(
                videoUrl = url,
                videoTitle = "CinemaBox $quality",
                resolution = quality.filter { it.isDigit() }
                    .takeIf { it.isNotBlank() }
                    ?.toIntOrNull(),
                headers = playbackHeaders,
                subtitleTracks = subs,
            )
        } ?: emptyList()
    }
}
