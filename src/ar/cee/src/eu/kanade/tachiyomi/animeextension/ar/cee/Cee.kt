package eu.kanade.tachiyomi.animeextension.ar.cee

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
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

class Cee : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "Cee"

    override val lang = "ar"

    override val baseUrl = "https://cee.buzz"

    override val supportsLatest = true

    private val api = "$baseUrl/api/android"

    private val ua = "okhttp/4.10.0"

    private val baseHeaders get() = Headers.headersOf(
        "User-Agent",
        ua,
        "Referer",
        baseUrl,
        "Accept",
        "application/json",
    )

    // =============================== Json ================================

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            val c = v.contentOrNull ?: continue
            if (c.isNotBlank() && c != "null") return c
        }
        return null
    }

    private fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonObject> = ((this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }) ?: emptyList()

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // =============================== Network ==============================

    private suspend fun apiArr(path: String): List<JsonObject> = try {
        client.get("$api/$path".toHttpUrl(), baseHeaders).parseAs<JsonArray>()
            .mapNotNull { it as? JsonObject }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }

    private suspend fun apiObj(path: String): JsonObject? = try {
        client.get("$api/$path".toHttpUrl(), baseHeaders).parseAs<JsonObject>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // =============================== Cards ================================

    private fun JsonObject.toSAnime(): SAnime? {
        val nb = str("nb") ?: return null
        val title = str("en_title") ?: str("ar_title") ?: return null
        val isSeries = str("kind")?.toIntOrNull() == 2
        return SAnime.create().apply {
            url = nb
            this.title = title
            thumbnail_url = str("imgObjUrl") ?: str("img")
        }
    }

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val zeroPage = (page - 1).coerceAtLeast(0)
        return browse("video/V/2?videoKind=1&itemsPerPage=30&pageNumber=$zeroPage&sortParam=desc")
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = browse("newlyVideosItems/level/0/offset/12/page/$page", page)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) return getPopularAnime(page)

        val zeroPage = (page - 1).coerceAtLeast(0)
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val items = apiArr(
            "AdvancedSearch?level=0&videoTitle=${encode(q)}" +
                "&staffTitle=${encode(q)}&year=1900,$year" +
                "&page=$zeroPage&type=all&itemsPerPage=30",
        ).mapNotNull { it.toSAnime() }
        return AnimesPage(items, items.size >= 30)
    }

    private suspend fun browse(path: String, page: Int): AnimesPage {
        val items = apiArr(path).mapNotNull { it.toSAnime() }
        return AnimesPage(items, items.size >= 24)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val j = apiObj("allVideoInfo/id/${anime.url}")
            ?: throw Exception("Cee: not found")

        return anime.apply {
            title = j.str("en_title") ?: j.str("ar_title") ?: title
            description = j.str("ar_content") ?: j.str("en_content")
            genre = (j["categories"] as? JsonArray)
                ?.mapNotNull { c ->
                    (c as? JsonObject)?.let { it.str("en_title") ?: it.str("ar_title") }
                }
                ?.filter { it.isNotBlank() }
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }
            thumbnail_url = j.str("imgObjUrl") ?: j.str("img") ?: thumbnail_url
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val j = apiObj("allVideoInfo/id/${anime.url}") ?: return emptyList()
        val kind = j.str("kind")?.toIntOrNull()

        if (kind != 2) {
            // Movie: single pseudo-episode
            return listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = "فيلم"
                    episode_number = 1f
                },
            )
        }

        // Series: fetch all episodes
        val eps = apiArr("videoSeason/id/${anime.url}")
        return eps.mapIndexedNotNull { index, ep ->
            val epId = ep.str("nb") ?: return@mapIndexedNotNull null
            val epNum = ep.str("episodeNummer")?.toIntOrNull() ?: (index + 1)
            val seasonNum = ep.str("season")?.toIntOrNull() ?: 1

            SEpisode.create().apply {
                url = epId
                name = "موسم $seasonNum - الحلقة $epNum"
                episode_number = (seasonNum * 10000 + epNum).toFloat()
                preview_url = ep.str("imgObjUrl")
            }
        }.sortedBy { it.episode_number }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterUrl = episode.url, hosterName = "Cee"),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val id = hoster.hosterUrl
        val videos = apiArr("transcoddedFiles/id/$id")
        if (videos.isEmpty()) return emptyList()

        // reversed = highest quality first
        return videos.reversed().mapNotNull { v ->
            val url = v.str("videoUrl") ?: return@mapNotNull null
            val resolution = v.str("resolution") ?: "Default"
            Video(
                videoUrl = url,
                videoTitle = resolution,
                resolution = resolution.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.toIntOrNull(),
                headers = Headers.headersOf("User-Agent", ua, "Referer", baseUrl),
            )
        }
    }
}
