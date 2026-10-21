package eu.kanade.tachiyomi.animeextension.ar.anime4up

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Anime4Up : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "Anime4Up"

    override val lang = "ar"

    override val baseUrl = "https://w1.anime4up.rest"

    override val supportsLatest = true

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val streamRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)\b[^\s"'<>\\]*""")

    // =============================== Network ==============================

    private fun headersFor(referer: String? = null, cookieUrl: String? = null): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept-Language", "ar,en-US;q=0.9")
        .add("Upgrade-Insecure-Requests", "1")
        .apply {
            referer?.let { add("Referer", it) }
            // The player hosts only serve cleared visitors: reuse the cookies the app's
            // WebView picked up after the Cloudflare challenge.
            webViewCookies(cookieUrl ?: referer ?: baseUrl)?.let { add("Cookie", it) }
        }
        .build()

    private fun webViewCookies(url: String): String? = try {
        android.webkit.CookieManager.getInstance().getCookie(url)
    } catch (_: Throwable) {
        null
    }

    private suspend fun bodyOf(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<String, String>? = try {
        val headers = headersFor(referer, url).newBuilder().apply {
            extraHeaders.forEach { (key, value) -> set(key, value) }
        }.build()
        val response = client.get(url.toHttpUrl(), headers, ensureSuccess = false)
        if (response.isSuccessful) {
            response.body.string() to response.request.url.toString()
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun getDoc(url: String, referer: String? = null): Document? = bodyOf(url, referer)?.let { (body, finalUrl) -> Jsoup.parse(body, finalUrl) }

    // =============================== Cards ================================

    private fun Element.poster(): String? {
        val img = selectFirst("img") ?: return null
        val src = img.attr("data-image")
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("src") }
            .trim()
        if (src.isEmpty()) return null
        return if (src.startsWith("http")) src else "$baseUrl$src"
    }

    private fun Element.toSAnime(): SAnime? {
        val titleLink = selectFirst(".anime-card-title h3 a")
            ?: selectFirst(".anime-card-title a")
            ?: selectFirst("a.overlay")
            ?: return null
        val title = titleLink.text().trim().ifEmpty {
            selectFirst(".anime-card-title")?.attr("title")?.trim().orEmpty()
        }
        if (title.isEmpty()) return null

        val href = titleLink.attr("abs:href").ifBlank {
            selectFirst(".ep_num a")?.attr("abs:href").orEmpty()
        }
        if (href.isEmpty()) return null

        return SAnime.create().apply {
            url = href
            this.title = title
            thumbnail_url = poster()
        }
    }

    // ============================== Popular ==============================

    /** Home page rails, flattened. `تابع المشاهدة` is per-account and always empty here. */
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val doc = getDoc(baseUrl) ?: return AnimesPage(emptyList(), false)

        val items = doc.select(".main-widget")
            .filterNot { it.selectFirst(".main-didget-head h3")?.text()?.contains("تابع المشاهدة") == true }
            .flatMap { it.select(".anime-card-themex") }
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        return AnimesPage(items, false)
    }

    // =============================== Latest ==============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/episode/page/$page/" else "$baseUrl/episode/"
        return pageOf(url)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) return getLatestUpdates(page)

        val term = URLEncoder.encode(q, "UTF-8")
        val url = if (page > 1) "$baseUrl/page/$page/?s=$term" else "$baseUrl/?s=$term"
        return pageOf(url)
    }

    private suspend fun pageOf(url: String): AnimesPage {
        val doc = getDoc(url) ?: return AnimesPage(emptyList(), false)
        val items = doc.select(".anime-card-themex").mapNotNull { it.toSAnime() }
        return AnimesPage(items, doc.selectFirst("a.next.page-numbers") != null)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDoc(anime.url) ?: throw Exception("Anime4Up: page not found")

        val info = doc.select(".anime-info").map { it.text().trim() }.filter { it.isNotEmpty() }

        return anime.apply {
            title = doc.selectFirst("h1.anime-details-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: title
            thumbnail_url = doc.selectFirst(".anime-thumbnail img")?.attr("abs:src")
                ?.takeIf { it.isNotBlank() }
                ?: thumbnail_url
            description = listOfNotNull(
                doc.selectFirst("p.anime-story")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                info.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ).joinToString("\n\n").takeIf { it.isNotEmpty() }
            genre = doc.select("ul.anime-genres li a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
                .takeIf { it.isNotEmpty() }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        var doc = getDoc(anime.url) ?: return emptyList()

        // Episode pages are valid entries too: walk up to the parent anime.
        if (anime.url.contains("/episode/")) {
            val parentUrl = doc.selectFirst("div.anime-page-link a")?.attr("abs:href")
            if (!parentUrl.isNullOrBlank()) doc = getDoc(parentUrl) ?: return emptyList()
        }

        val episodes = mutableListOf<SEpisode>()
        val seen = mutableSetOf<String>()

        fun collect(page: Document) {
            page.select("#episodesList .anime-card-themex").forEach { card ->
                val link = card.selectFirst(".ep_num a") ?: return@forEach
                val url = link.attr("abs:href").ifBlank { link.attr("href") }
                if (url.isEmpty() || !seen.add(url)) return@forEach

                val label = link.text().trim().ifEmpty { "الحلقة" }
                episodes += SEpisode.create().apply {
                    this.url = url
                    name = label
                    episode_number = numberIn(label) ?: (episodes.size + 1).toFloat()
                    preview_url = card.poster()
                }
            }
        }

        collect(doc)

        // Long anime keep older episodes behind a "load more" link.
        var next = doc.selectFirst("a.episodes-load-more")?.attr("abs:href")
        var guard = 0
        while (!next.isNullOrBlank() && guard++ < 50) {
            val page = getDoc(next) ?: break
            collect(page)
            next = page.selectFirst("a.episodes-load-more")?.attr("abs:href")
        }

        return episodes.sortedBy { it.episode_number }
    }

    private fun numberIn(text: String): Float? {
        val ascii = text.map { c ->
            val index = ARABIC_DIGITS.indexOf(c)
            if (index >= 0) '0' + index else c
        }.joinToString("")
        return Regex("""\d+""").find(ascii)?.value?.toFloatOrNull()
    }

    // =============================== Videos ===============================

    private data class StreamLink(val url: String, val label: String, val quality: String? = null)

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = getDoc(episode.url) ?: return emptyList()

        return doc.select("ul#episode-servers li[data-watch], ul#watch-servers li[data-watch]")
            .mapNotNull { li ->
                val watchUrl = li.attr("data-watch").trim()
                if (watchUrl.isEmpty()) return@mapNotNull null

                val serverName = li.selectFirst(".watch-server-name")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "سيرفر"
                val quality = li.selectFirst(".quality")?.text()?.trim()?.takeIf { it.isNotEmpty() }

                Hoster(
                    hosterUrl = watchUrl,
                    hosterName = listOfNotNull(serverName, quality).joinToString(" · "),
                    memo = buildJsonObject { put("ep", episode.url) },
                )
            }
            .distinctBy { it.hosterUrl }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val referer = hoster.memo["ep"]?.jsonPrimitive?.contentOrNull ?: baseUrl
        val watchUrl = hoster.hosterUrl

        val links = if (DIRECT_STREAM.containsMatchIn(watchUrl)) {
            listOf(StreamLink(watchUrl, hoster.hosterName))
        } else {
            resolvePlayer(watchUrl, referer, hoster.hosterName)
        }

        return links.distinctBy { it.url }.map { link ->
            Video(
                videoUrl = link.url,
                videoTitle = link.label,
                resolution = link.quality?.filter { it.isDigit() }?.toIntOrNull(),
                headers = headersFor(referer),
            )
        }
    }

    private suspend fun resolvePlayer(url: String, referer: String, serverName: String): List<StreamLink> {
        val (html, finalUrl) = bodyOf(url, referer) ?: return emptyList()
        val doc = Jsoup.parse(html, finalUrl)

        val links = mutableListOf<StreamLink>()

        // Laravel/Inertia player: the stream list sits behind an X-Inertia request.
        val version = doc.selectFirst("script[data-page=app]")?.html()?.let { props ->
            runCatching { json.parseToJsonElement(props).jsonObject["version"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
        }
        if (!version.isNullOrBlank()) {
            val inertia = bodyOf(
                url,
                referer,
                mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Partial-Component" to "files/mirror/video",
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Inertia-Version" to version,
                    "X-Requested-With" to "XMLHttpRequest",
                ),
            )?.first
            if (inertia != null) links += inertiaStreams(inertia, serverName)
        }

        // Everything else embeds the manifest in the page source.
        if (links.isEmpty()) {
            links += streamRegex.findAll(html.replace("\\/", "/"))
                .map { StreamLink(it.value, "$serverName · مباشر") }
                .toList()
        }

        return links
    }

    private fun inertiaStreams(body: String, serverName: String): List<StreamLink> = try {
        val qualities = json.parseToJsonElement(body).jsonObject["props"]
            ?.jsonObject?.get("streams")
            ?.jsonObject?.get("data")
            ?.jsonArray ?: emptyList()

        qualities.flatMap { quality ->
            val obj: JsonObject = quality.jsonObject
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
            val mirrors: JsonArray = obj["mirrors"]?.jsonArray ?: return@flatMap emptyList()

            mirrors.mapNotNull { mirror ->
                val link = mirror.jsonObject["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                StreamLink(
                    url = if (link.startsWith("//")) "https:$link" else link,
                    label = listOfNotNull(serverName, label).joinToString(" · "),
                    quality = label,
                )
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private companion object {
        private const val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"
        private val DIRECT_STREAM = Regex("""\.(mp4|m3u8)\b""", RegexOption.IGNORE_CASE)
    }
}
