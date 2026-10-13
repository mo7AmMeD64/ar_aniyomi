package eu.kanade.tachiyomi.animeextension.ar.animeslayer

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
import extensions.utils.post
import extensions.utils.toJsonBody
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

class AnimeSlayer : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "AnimeSlayer"

    override val lang = "ar"

    override val baseUrl = "https://anslayer.com"

    override val supportsLatest = true

    private val api = "$baseUrl/anime/public"

    private val ua = "okhttp/5.3.2"

    private val apiHeaders get() = Headers.headersOf(
        "client-id",
        "android-app2",
        "client-secret",
        "7befba6263cc14c90d2f1d6da2c5cf9b251bfbbd",
        "User-Agent",
        ua,
        "Accept",
        "application/*+json",
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

    private fun JsonObject.arr(key: String): List<JsonObject> =
        ((this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }) ?: emptyList()

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // =============================== Network ==============================

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): JsonObject? = try {
        val url = buildString {
            append("$api/$path")
            if (params.isNotEmpty()) {
                append("?")
                append(params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" })
            }
        }
        client.get(url.toHttpUrl(), apiHeaders).parseAs<JsonObject>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // =============================== Cards ================================

    private fun JsonObject.toSAnime(): SAnime? {
        val id = str("anime_id") ?: return null
        val title = str("anime_name") ?: return null
        return SAnime.create().apply {
            url = id
            this.title = title
            thumbnail_url = str("anime_cover_image_url")
        }
    }

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = browse("anime_list", page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = browse("latest_updated_episode_new", page)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) return getPopularAnime(page)

        val offset = (page - 1) * 30
        val listJson = """{"_offset":$offset,"_limit":30,"_order_by":"latest_first","list_type":"filter","anime_name":"$q","just_info":"Yes"}"""
        val j = apiGet("animes/get-published-animes", mapOf("json" to listJson))
            ?: return AnimesPage(emptyList(), false)
        val items = j.obj("response")?.arr("data")?.mapNotNull { it.toSAnime() } ?: emptyList()
        return AnimesPage(items, items.size >= 30)
    }

    private suspend fun browse(listType: String, page: Int): AnimesPage {
        val offset = (page - 1) * 30
        val listJson =
            """{"_offset":$offset,"_limit":30,"_order_by":"latest_first","list_type":"$listType","just_info":"Yes"}"""
        val j = apiGet("animes/get-published-animes", mapOf("json" to listJson))
            ?: return AnimesPage(emptyList(), false)
        val items = j.obj("response")?.arr("data")?.mapNotNull { it.toSAnime() } ?: emptyList()
        return AnimesPage(items, items.size >= 30)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val j = apiGet(
            "anime/get-anime-details",
            mapOf("anime_id" to anime.url, "fetch_episodes" to "Yes", "more_info" to "No"),
        )?.obj("response") ?: throw Exception("AnimeSlayer: not found")

        return anime.apply {
            title = j.str("anime_name") ?: title
            description = j.str("anime_description")
            genre = j.str("anime_genres")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.joinToString(", ")
            thumbnail_url = j.str("anime_cover_image_url") ?: thumbnail_url
            status = when (j.str("anime_status")) {
                "Finished Airing" -> SAnime.COMPLETED
                "Currently Airing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val j = apiGet(
            "anime/get-anime-details",
            mapOf("anime_id" to anime.url, "fetch_episodes" to "Yes", "more_info" to "No"),
        )?.obj("response") ?: return emptyList()

        val epList = j.obj("episodes")?.arr("data") ?: emptyList()
        return epList.mapIndexedNotNull { index, e ->
            val epNum = e.int("episode_number") ?: (index + 1)
            val urls = e.arr("episode_urls")
            val muilt = urls.firstOrNull { it.str("episode_server_name") == "muilt" }?.str("episode_url")
                ?: return@mapIndexedNotNull null

            SEpisode.create().apply {
                url = muilt
                name = e.str("episode_name") ?: "الحلقة $epNum"
                episode_number = epNum.toFloat()
            }
        }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterUrl = episode.url, hosterName = "AnimeSlayer"),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val f2Url = hoster.hosterUrl
        val urls = try {
            val r = client.get(f2Url.toHttpUrl(), Headers.headersOf("User-Agent", ua))
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(r.body.string())
            (parsed as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

        val videos = mutableListOf<Video>()
        for (url in urls) {
            when {
                url.contains("mediafire.com") -> {
                    resolveMediafire(url, hoster.hosterName)?.let { videos.add(it) }
                }

                url.contains("playmate.to") -> {
                    resolvePlaymate(url, hoster.hosterName)?.let { videos.add(it) }
                }

                url.endsWith(".mp4") || url.endsWith(".m3u8") -> {
                    videos += Video(
                        videoUrl = url,
                        videoTitle = hoster.hosterName,
                        headers = Headers.headersOf("User-Agent", ua),
                    )
                }
            }
        }
        return videos
    }

    private suspend fun resolveMediafire(url: String, label: String): Video? {
        return try {
            val html = client.get(url.toHttpUrl(), Headers.headersOf("User-Agent", "Mozilla/5.0")).body.string()
            val dl = Regex("href=\"(https://download[^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: return null
            Video(
                videoUrl = dl,
                videoTitle = "Mediafire $label",
                headers = Headers.headersOf(
                    "User-Agent",
                    "Mozilla/5.0",
                    "Referer",
                    "https://www.mediafire.com/",
                ),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun resolvePlaymate(url: String, label: String): Video? {
        return try {
            val id = url.substringAfterLast("/").substringBefore("?")
            val body = kotlinx.serialization.json.buildJsonObject {
                put("c", kotlinx.serialization.json.JsonPrimitive(id))
                put("d", kotlinx.serialization.json.JsonPrimitive("web"))
            }
            val r = client.post(
                "https://playmate.to/api/s".toHttpUrl(),
                Headers.headersOf(
                    "User-Agent",
                    ua,
                    "Referer",
                    "https://playmate.to/",
                    "Content-Type",
                    "application/json",
                ),
                body.toString().toJsonBody(),
            ).parseAs<JsonObject>()
            val sx = r.str("sx") ?: return null
            Video(
                videoUrl = sx,
                videoTitle = "Playmate $label",
                headers = Headers.headersOf("User-Agent", ua, "Referer", "https://playmate.to/"),
            )
        } catch (_: Throwable) {
            null
        }
    }
}
