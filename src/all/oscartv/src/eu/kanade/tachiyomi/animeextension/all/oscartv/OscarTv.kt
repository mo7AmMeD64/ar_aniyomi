package eu.kanade.tachiyomi.animeextension.all.oscartv

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
import extensions.utils.parallelMap
import extensions.utils.parseAs
import extensions.utils.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl

class OscarTv : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "Oscar TV"

    override val lang = "all"

    override val baseUrl = "https://ostv-11-11.xyz"

    override val supportsLatest = false

    private val step1 = "https://flech.tn/scrapefinal/dramabox/step1_prepare_oscar.php"

    private val playbackHeaders = Headers.headersOf(
        "User-Agent",
        "TDMuaHLS99",
    )

    /** Browse sections: (key shown in filter, api path, paged). */
    private val sections = listOf(
        "أفلام" to ("api/movies/?page=%d&limit=20" to true),
        "مسلسلات" to ("api/series/?page=%d&limit=20" to true),
        "أنمي" to ("api/anime/?page=%d&limit=20&anime_type=tv,ova,ona,special,movie" to true),
        "قنوات مباشرة" to ("api/channels/?page=%d&limit=20" to true),
        "أحدث الأفلام العربية" to ("armovies" to false),
        "أحدث الإضافات العربية" to ("arseries" to false),
    )

    // =============================== Json ================================

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = (this[k] as? JsonPrimitive)?.contentOrNull
            if (!v.isNullOrBlank() && v != "null") return v
        }
        return null
    }

    private fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()

    /** CDN guard redirects playback to a 1-min "تنوية" notice without this cookie (from deep_link) */
    private fun JsonObject.playCookie(): String? = try {
        str("deep_link")?.substringAfter("cookies=", "")?.takeIf { it.isNotBlank() }
            ?.substringBefore("&")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

    private fun JsonObject.data(): JsonObject? = this["data"] as? JsonObject

    private fun JsonObject.dataArr(): List<JsonObject> = (this["data"] as? JsonArray)?.objects() ?: emptyList()

    @Serializable
    private data class Step1Dto(val headers: Map<String, String> = emptyMap())

    /** flech.tn computes fresh X-Iron-* anti-bot headers per URL -> needed for every API call. */
    private suspend fun call(path: String): JsonObject? = try {
        val url = "$baseUrl/$path"
        val res = client.post(
            step1,
            Headers.headersOf(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                "Content-Type",
                "application/x-www-form-urlencoded",
            ),
            FormBody.Builder().add("url", url).build(),
        ).parseAs<Step1Dto>()

        // Verbatim Host/Connection from step1 point at old domains -> Cloudflare 403.
        val pairs = res.headers.entries
            .filter { it.key !in setOf("Host", "Connection") }
            .flatMap { listOf(it.key, it.value) }
        client.get(url.toHttpUrl(), Headers.headersOf(*pairs.toTypedArray())).parseAs<JsonObject>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // =============================== Cards ================================

    private fun JsonObject.poster(): String? = str("poster")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

    private fun JsonObject.toSAnime(kind: String): SAnime? {
        val id = int("id") ?: return null
        val title = str("title_ar", "title_en", "name") ?: return null
        return SAnime.create().apply {
            url = "$kind/$id"
            this.title = title
            thumbnail_url = poster() ?: str("logo")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
        }
    }

    // ============================== Popular ==============================

    /** Popular = a merged movies/series/anime catalog page. */
    override suspend fun getPopularAnime(page: Int): AnimesPage = allKindsPage(page)

    private suspend fun allKindsPage(page: Int, search: String = ""): AnimesPage {
        val q = if (search.isBlank()) "" else "&search=${encode(search)}"
        val kinds = listOf(
            "api/movies/?page=$page&limit=20$q" to "movie",
            "api/series/?page=$page&limit=20$q" to "serie",
            "api/anime/?page=$page&limit=20&anime_type=tv,ova,ona,special,movie$q" to "anime",
        )
        val results = kinds.parallelMap { (path, kind) ->
            val d = call(path)
            val items = d?.dataArr()?.mapNotNull { it.toSAnime(kind) } ?: emptyList()
            val totalPages = d?.obj("pagination")?.int("total_pages") ?: 1
            items to totalPages
        }
        return AnimesPage(results.flatMap { it.first }, results.any { page < it.second })
    }

    // =============================== Search ===============================

    private class SectionFilter(values: Array<String>) : AnimeFilter.Select<String>("القسم", values)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SectionFilter(sections.map { it.first }.toTypedArray()),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) {
            // Browsing: the section filter decides what to list.
            val select = filters.filterIsInstance<SectionFilter>().firstOrNull()
            return sectionPage(sections.getOrNull(select?.state ?: 0) ?: sections.first(), page)
        }

        // Searching: hit every kind in parallel — the filter only applies to browsing.
        return allKindsPage(page, q)
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun sectionPage(section: Pair<String, Pair<String, Boolean>>, page: Int): AnimesPage {
        val (pathTemplate, paged) = section.second

        if (!paged) {
            // Home rails (أحدث الأفلام/الإضافات العربية) — page 1 only.
            if (page > 1) return AnimesPage(emptyList(), false)
            val home = call("api/v2/home.php?app_version=15")
                ?: return AnimesPage(emptyList(), false)
            val sectionType = if (section.first.contains("أفلام")) "movies" else "latest_additions"
            val wantCategory = if (section.first.contains("أفلام")) 3 else 1
            val sec = home.obj("data")?.arr("sections")?.objects()
                ?.firstOrNull {
                    it.str("section_type") == sectionType &&
                        (
                            (it.obj("content_filters")?.int("category_id") == wantCategory) ||
                                it.str("title_ar") == section.first
                            )
                } ?: return AnimesPage(emptyList(), false)
            val items = sec.arr("items")?.objects()?.mapNotNull { o ->
                val id = o.int("item_id") ?: o.int("id") ?: return@mapNotNull null
                val title = o.str("title_ar", "title_en") ?: return@mapNotNull null
                SAnime.create().apply {
                    url = if (sectionType == "movies") "movie/$id" else "serie/$id"
                    this.title = title
                    thumbnail_url = o.str("poster", "image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
                }
            } ?: emptyList()
            return AnimesPage(items, false)
        }

        val path = pathTemplate.format(page)
        val d = call(path) ?: return AnimesPage(emptyList(), false)
        val kind = when {
            path.contains("api/movies") -> "movie"
            path.contains("api/series") -> "serie"
            path.contains("api/anime") -> "anime"
            else -> "channel"
        }
        val items = d.dataArr().mapNotNull { it.toSAnime(kind) }
        val totalPages = d.obj("pagination")?.int("total_pages") ?: 1
        return AnimesPage(items, page < totalPages)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (kind, id) = anime.url.split("/", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val d = when (kind) {
            "channel" -> call("api/channels/show.php?id=$id")?.data()
            "movie" -> call("api/movies/show.php?id=$id")?.data() ?: fallbackAsSerie(id)
            "anime" -> call("api/anime/show.php?id=$id")?.data() ?: call("api/series/show.php?id=$id")?.data()
            else -> call("api/series/show.php?id=$id")?.data() ?: call("api/movies/show.php?id=$id")?.data()
        } ?: throw Exception("Show not found")

        return anime.apply {
            title = d.str("title_ar", "title_en", "name") ?: title
            description = d.str("story", "description")
            genre = d.arr("genres")?.objects()?.mapNotNull { it.str("name") }?.joinToString(", ")
            thumbnail_url = d.poster() ?: d.str("logo")?.let { if (it.startsWith("http")) it else "$baseUrl$it" } ?: thumbnail_url
        }
    }

    private suspend fun fallbackAsSerie(id: String): JsonObject? = call("api/series/show.php?id=$id")?.data()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (kind, id) = anime.url.split("/", limit = 2).let { it[0] to it.getOrElse(1) { "" } }

        when (kind) {
            "channel" -> {
                val d = call("api/channels/show.php?id=$id")?.data() ?: return emptyList()
                return listOf(
                    SEpisode.create().apply {
                        url = "ch/$id"
                        name = d.str("name") ?: "بث مباشر"
                        episode_number = 1f
                    },
                )
            }

            "movie" -> {
                val d = call("api/movies/show.php?id=$id")?.data()
                    ?: call("api/series/show.php?id=$id")?.data()
                    ?: return emptyList()
                return listOf(
                    SEpisode.create().apply {
                        url = "mov/$id"
                        name = d.str("title_ar", "title_en") ?: "فيلم"
                        episode_number = 1f
                    },
                )
            }
        }

        val isAnime = kind == "anime"
        val apiBase = if (isAnime) "api/anime" else "api/series"
        val d = call("$apiBase/show.php?id=$id")?.data()
            ?: (if (!isAnime) call("api/movies/show.php?id=$id")?.data() else null)
            ?: return emptyList()

        val seasons = d.arr("seasons")?.objects() ?: emptyList()

        val episodes = mutableListOf<SEpisode>()
        for (s in seasons) {
            val sid = s.int("season_id") ?: s.int("id") ?: continue
            val sn = s.int("season_number") ?: 1
            val epPath = if (isAnime) {
                "api/anime/episodes/?season_id=$sid"
            } else {
                "api/episodes/?season_id=$sid"
            }
            val eps = call(epPath)?.dataArr() ?: emptyList()
            eps.forEachIndexed { index, e ->
                val epId = e.int("id") ?: return@forEachIndexed
                val epNum = e.int("episode_number") ?: (index + 1)
                episodes += SEpisode.create().apply {
                    url = "ep/$isAnime/$epId"
                    name = if (seasons.size > 1) "موسم $sn — الحلقة $epNum" else "الحلقة $epNum"
                    episode_number = (episodes.size + 1).toFloat()
                    preview_url = e.str("thumbnail")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
                }
            }
        }

        // Single-season shows have no seasons array — use current season episodes.
        if (episodes.isEmpty() && d.str("current_season_id") != null) {
            val sid = d.str("current_season_id")!!
            val epPath = if (isAnime) {
                "api/anime/episodes/?season_id=$sid"
            } else {
                "api/episodes/?season_id=$sid"
            }
            call(epPath)?.dataArr()?.forEachIndexed { index, e ->
                val epId = e.int("id") ?: return@forEachIndexed
                val epNum = e.int("episode_number") ?: (index + 1)
                episodes += SEpisode.create().apply {
                    url = "ep/$isAnime/$epId"
                    name = "الحلقة $epNum"
                    episode_number = (episodes.size + 1).toFloat()
                    preview_url = e.str("thumbnail")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
                }
            }
        }

        return episodes
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterUrl = episode.url, hosterName = "Oscar TV"))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val payload = hoster.hosterUrl
        val d = when {
            payload.startsWith("mov/") -> {
                val id = payload.substringAfter("mov/")
                call("api/movies/show.php?id=$id")?.data() ?: return emptyList()
            }

            payload.startsWith("ep/") -> {
                val rest = payload.substringAfter("ep/")
                val isAnime = rest.substringBefore("/") == "true"
                val id = rest.substringAfter("/")
                val apiBase = if (isAnime) "api/anime/episodes" else "api/episodes"
                call("$apiBase/show.php?id=$id")?.data() ?: return emptyList()
            }

            payload.startsWith("ch/") -> {
                val id = payload.substringAfter("ch/")
                call("api/channels/show.php?id=$id")?.data() ?: return emptyList()
            }

            else -> return emptyList()
        }

        if (payload.startsWith("ch/")) {
            return d.arr("streams")?.objects()?.mapNotNull { st ->
                val u = st.str("stream_url")?.substringBefore("#") ?: return@mapNotNull null
                val q = st.str("quality") ?: ""
                Video(
                    videoUrl = u,
                    videoTitle = "Oscar TV $q",
                    resolution = q.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.toIntOrNull(),
                    headers = playbackHeaders,
                )
            } ?: emptyList()
        }

        val seen = mutableSetOf<String>()
        val videos = mutableListOf<Video>()

        for (link in d.arr("watch_links")?.objects().orEmpty() + d.arr("download_links")?.objects().orEmpty()) {
            val u = link.str("url") ?: continue
            val q = link.str("quality") ?: ""
            val name = link.str("server_name") ?: "Oscar TV"

            var url = u
            // YNMov download links are Yandex Disk wrapper pages -> resolve the iframe.
            if (url.contains("newcdn.seriesmp4.com")) {
                url = try {
                    val body = client.get(url.toHttpUrl(), playbackHeaders).body.string()
                    val m = Regex("src=\"(https://downloader\\.disk\\.yandex\\.ru[^\"]+)\"").find(body)
                    m?.groupValues?.get(1)?.replace("&" + "amp;", "&") ?: continue
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    continue
                }
            }

            if (!seen.add(url)) continue
            val cookie = link.playCookie()
            videos += Video(
                videoUrl = url,
                videoTitle = "$name $q",
                resolution = q.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.toIntOrNull(),
                headers = if (cookie.isNullOrBlank()) {
                    playbackHeaders
                } else {
                    Headers.headersOf("User-Agent", "TDMuaHLS99", "Cookie", cookie)
                },
            )
        }

        return videos
    }
}
