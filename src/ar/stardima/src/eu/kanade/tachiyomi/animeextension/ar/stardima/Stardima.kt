package eu.kanade.tachiyomi.animeextension.ar.stardima

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl

class Stardima : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "Stardima"

    override val lang = "ar"

    override val baseUrl = "https://app.stardima.com"

    override val supportsLatest = true

    private val api = "$baseUrl/api"

    private val hw = "https://v2.hyperwatching.com"

    private val apiKey = "vGIu8q9aap55zyANSD3jvmbttClhuRmykbQjzsIQxoWloFmp29W2qqdTSrwR"

    private val ua = "jcartoonApp/1.0.7 (Android)"

    private val sections = listOf(
        "series" to "مسلسلات",
        "movies" to "أفلام",
        "last_episodes" to "أحدث الحلقات",
    )

    private val apiHeaders get() = Headers.headersOf(
        "x-api-key",
        apiKey,
        "User-Agent",
        ua,
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

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    // =============================== Cards ================================

    private fun JsonObject.toSAnime(): SAnime? {
        val id = str("id") ?: return null
        val title = str("title") ?: return null
        return SAnime.create().apply {
            url = id
            this.title = title
            thumbnail_url = str("poster_url") ?: str("background_url")
        }
    }

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = browse(sections.first().first, page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = browse(sections.last().first, page)

    // =============================== Search ===============================

    private class TypeFilter(values: Array<String>) : AnimeFilter.Select<String>("النوع", values)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(sections.map { it.second }.toTypedArray()),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val q = query.trim()
        val select = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val type = sections.getOrNull(select?.state ?: 0)?.first ?: sections.first().first

        if (q.isEmpty()) return browse(type, page)

        val j = apiGet("search", mapOf("categories[]" to "all", "query" to q, "language" to "all"))
            ?: return AnimesPage(emptyList(), false)
        val items = j.arr("videos").mapNotNull { it.toSAnime() }
        return AnimesPage(items, false)
    }

    private suspend fun browse(type: String, page: Int): AnimesPage {
        val j = apiGet("searchByOption", mapOf("type" to type, "query" to "", "page" to page.toString()))
            ?: return AnimesPage(emptyList(), false)
        val items = j.arr("videos").mapNotNull { it.toSAnime() }
        val lastPage = j.int("last_page") ?: j.obj("pagination")?.int("last_page") ?: 1
        return AnimesPage(items, page < lastPage)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val j = apiGet("video/${anime.url}") ?: throw Exception("Stardima: video not found")

        return anime.apply {
            title = j.str("title") ?: title
            description = j.str("description")
            genre = j.str("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.joinToString(", ")
            thumbnail_url = j.str("poster_url") ?: thumbnail_url
            status = when (j.str("status_text")) {
                "مكتمل" -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val j = apiGet("video/${anime.url}") ?: return emptyList()

        if (j.str("type") != "series") {
            // Movie: single pseudo-episode
            return listOf(
                SEpisode.create().apply {
                    url = "m/${anime.url}"
                    name = j.str("title") ?: "فيلم"
                    episode_number = 1f
                },
            )
        }

        val seasons = j.arr("seasons")
        val episodes = mutableListOf<SEpisode>()
        for (season in seasons) {
            val seasonId = season.str("id") ?: continue
            val seasonNum = season.int("season_number") ?: 1
            val epData = apiGet("episodes/$seasonId")?.arr("data") ?: emptyList()
            for (e in epData) {
                val watchUrl = e.str("watch_url") ?: continue
                val epNum = e.int("episode_number") ?: (episodes.size + 1)
                episodes += SEpisode.create().apply {
                    url = "e/$watchUrl"
                    name = e.str("title") ?: "الحلقة $epNum"
                    episode_number = epNum.toFloat()
                    if (seasons.size > 1 && seasonNum > 1) name = "موسم $seasonNum — $name"
                }
            }
        }
        return episodes.sortedBy { it.episode_number }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val url = episode.url

        // Movie: fetch servers from the API
        if (url.startsWith("m/")) {
            val id = url.substringAfter("m/")
            val j = apiGet("video/$id") ?: return emptyList()
            val servers = j.arr("servers")
            return servers.mapNotNull { s ->
                val sUrl = s.str("url") ?: return@mapNotNull null
                Hoster(
                    hosterUrl = sUrl,
                    hosterName = s.str("label") ?: "Server",
                    memo = kotlinx.serialization.json.buildJsonObject {
                        put("type", s.str("type") ?: "web")
                        put("id", kotlinx.serialization.json.JsonPrimitive(id))
                    },
                )
            }
        }

        // Episode: resolve through hyperwatching
        if (url.startsWith("e/")) {
            val watchUrl = url.substringAfter("e/")
            val hashid = watchUrl.substringAfterLast("/").substringBefore("?")

            val html = try {
                client.get(
                    watchUrl.toHttpUrl(),
                    Headers.headersOf("User-Agent", ua, "Referer", "$hw/"),
                ).body.string()
            } catch (_: Throwable) {
                return emptyList()
            }

            val pageJson = Regex("data-page=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
                ?: return emptyList()
            val unescaped = pageJson
                .replace("&" + "quot;", "\"")
                .replace("&" + "amp;", "&")
                .replace("&#039;", "'")
                .replace("&" + "lt;", "<")
                .replace("&" + "gt;", ">")
            val video = try {
                val page = kotlinx.serialization.json.Json.parseToJsonElement(unescaped).jsonObject
                page.obj("props")?.obj("video") ?: return emptyList()
            } catch (_: Throwable) {
                return emptyList()
            }

            return video.arr("servers").mapNotNull { s ->
                val serverId = s.str("id") ?: return@mapNotNull null
                // Get the embed URL for this server
                val embedUrl = try {
                    client.get(
                        "$hw/embed/$hashid/server/$serverId/url".toHttpUrl(),
                        Headers.headersOf(
                            "User-Agent",
                            ua,
                            "Accept",
                            "application/json",
                            "Referer",
                            watchUrl,
                            "X-Requested-With",
                            "XMLHttpRequest",
                        ),
                    ).parseAs<JsonObject>().str("watch_url")
                } catch (_: Throwable) {
                    null
                } ?: return@mapNotNull null

                Hoster(
                    hosterUrl = embedUrl,
                    hosterName = s.str("name") ?: "Server",
                )
            }
        }

        return emptyList()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl

        // Direct mp4 (backup type) — playable as-is
        if (hoster.memo["type"]?.jsonPrimitive?.contentOrNull == "backup" ||
            (url.contains(".mp4") && !url.contains("/e/"))
        ) {
            return listOf(
                Video(
                    videoUrl = url,
                    videoTitle = hoster.hosterName,
                    headers = Headers.headersOf("User-Agent", ua),
                ),
            )
        }

        // Hoster embed: try to extract the direct video URL
        val html = try {
            client.get(
                url.toHttpUrl(),
                Headers.headersOf("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"),
            ).body.string()
        } catch (_: Throwable) {
            return emptyList()
        }

        // Common patterns for direct video URLs in hoster embeds
        val patterns = listOf(
            Regex("""sources\s*:\s*\[\{[^}]*src\s*:\s*["']([^"']+)["']"""),
            Regex("""["']file["']\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
            Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']"""),
            Regex("""["'](https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)["']"""),
        )

        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            val videoUrl = match.groupValues[1] ?: continue
            if (videoUrl.startsWith("http")) {
                return listOf(
                    Video(
                        videoUrl = videoUrl,
                        videoTitle = hoster.hosterName,
                        headers = Headers.headersOf(
                            "User-Agent",
                            "Mozilla/5.0",
                            "Referer",
                            url.substringBefore("/e/"),
                        ),
                    ),
                )
            }
        }

        return emptyList()
    }
}
