package eu.kanade.tachiyomi.animeextension.ar.animerco

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.get
import extensions.utils.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.random.Random

class Animerco : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "Animerco"

    override val lang = "ar"

    // The site rotates domains; every request follows the redirect to the current one.
    override val baseUrl = "https://gat.animerco.org"

    override val supportsLatest = true

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var resolvedBase: String? = null

    // =============================== Network ==============================

    private fun headersFor(referer: String? = null): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept-Language", "ar,en-US;q=0.9")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .apply { referer?.let { add("Referer", it) } }
        .build()

    private suspend fun base(): String {
        resolvedBase?.let { return it }
        val finalUrl = bodyOf("$baseUrl/")?.second ?: baseUrl
        return finalUrl.trimEnd('/').also { resolvedBase = it }
    }

    private suspend fun bodyOf(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<String, String>? = try {
        val headers = headersFor(referer).newBuilder().apply {
            extraHeaders.forEach { (key, value) -> set(key, value) }
        }.build()
        val response = client.get(url.toHttpUrl(), headers, ensureSuccess = false)
        if (response.isSuccessful) response.body.string() to response.request.url.toString() else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun getDoc(url: String, referer: String? = null): Document? = bodyOf(url, referer)?.let { (body, finalUrl) -> Jsoup.parse(body, finalUrl) }

    private fun absoluteUrl(url: String, base: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        url.startsWith("/") -> base.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}$url" } ?: url
        else -> base.toHttpUrlOrNull()?.resolve(url)?.toString() ?: url
    }

    // =============================== Cards ================================

    private fun Element.poster(): String? {
        val img = selectFirst("a.image[data-src]") ?: selectFirst("img") ?: return null
        return img.attr("data-src")
            .ifBlank { img.attr("abs:src") }
            .ifBlank { img.attr("src") }
            .takeIf { it.isNotBlank() }
    }

    private fun Element.toSAnime(): SAnime? {
        val link = selectFirst("div.info a") ?: selectFirst("a.image") ?: return null
        val href = link.attr("abs:href").ifBlank { link.attr("href") }
        val title = selectFirst("div.info h3")?.text()?.trim().orEmpty()
        if (href.isEmpty() || title.isEmpty()) return null

        return SAnime.create().apply {
            url = href
            this.title = title
            thumbnail_url = poster()
        }
    }

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val doc = getDoc("${base()}/") ?: return AnimesPage(emptyList(), false)

        val items = doc.select("div.anime-card:not(.player)")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        return AnimesPage(items, false)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val pageUrl = if (page > 1) "${base()}/episodes/page/$page/" else "${base()}/episodes/"
        val doc = getDoc(pageUrl) ?: return AnimesPage(emptyList(), false)

        val items = doc.select("div.episode-card").mapNotNull { card ->
            val href = card.selectFirst("a.image")?.attr("abs:href")
                ?.ifBlank { card.selectFirst("a.image")?.attr("href") }
                ?: return@mapNotNull null
            val animeTitle = card.selectFirst("div.info h3")?.text()?.trim().orEmpty()
            val episodeTitle = card.selectFirst("a.episode span")?.text()?.trim().orEmpty()
            val title = listOf(animeTitle, episodeTitle).filter { it.isNotEmpty() }.joinToString(" - ")
            if (href.isEmpty() || title.isEmpty()) return@mapNotNull null

            SAnime.create().apply {
                url = href
                this.title = title
                thumbnail_url = card.poster()
            }
        }.distinctBy { it.url }

        return AnimesPage(items, doc.selectFirst("link[rel=next]") != null)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) return AnimesPage(emptyList(), false)

        val term = URLEncoder.encode(q, "UTF-8")
        val url = if (page > 1) "${base()}/page/$page/?s=$term" else "${base()}/?s=$term"
        val doc = getDoc(url) ?: return AnimesPage(emptyList(), false)

        val items = doc.select("div.search-card")
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }

        return AnimesPage(items, doc.selectFirst("link[rel=next]") != null)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDoc(anime.url) ?: throw Exception("Animerco: page not found")
        val statusText = doc.selectFirst("div.anime-status .status, div.status a")?.text()?.trim()

        return anime.apply {
            title = doc.selectFirst("div.media-title h1")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: title
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("div.anime-card.player div.image")?.attr("data-src")
                ?: thumbnail_url
            description = doc.selectFirst("div.media-story div.content")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
            genre = doc.select("div.genres a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
                .takeIf { it.isNotEmpty() }
            status = when {
                statusText == null -> SAnime.UNKNOWN
                statusText.contains("يعرض") -> SAnime.ONGOING
                statusText.contains("مكتمل") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = getDoc(anime.url) ?: return emptyList()
        val episodes = mutableListOf<SEpisode>()
        val seen = mutableSetOf<String>()

        fun collect(page: Document, season: Int?) {
            page.select("ul.episodes-lists#filter li").forEach { li ->
                val link = li.selectFirst("a.title") ?: return@forEach
                val href = link.attr("abs:href").ifBlank { link.attr("href") }
                if (href.isEmpty() || !seen.add(href)) return@forEach

                val label = link.selectFirst("h3")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: link.text().trim().ifEmpty { "الحلقة" }
                episodes += SEpisode.create().apply {
                    url = href
                    name = if (season != null) "الموسم $season · $label" else label
                    episode_number = li.attr("data-number").toFloatOrNull()
                        ?: numberIn(label)
                        ?: (episodes.size + 1).toFloat()
                    preview_url = li.selectFirst("a.image")?.attr("data-src")
                }
            }
        }

        val seasons = doc.select("div.media-seasons ul.episodes-lists li")
        if (seasons.isNotEmpty()) {
            seasons.forEachIndexed { index, li ->
                val href = li.selectFirst("a.title")?.attr("abs:href") ?: return@forEachIndexed
                val seasonDoc = getDoc(href) ?: return@forEachIndexed
                val season = li.attr("data-number").toIntOrNull() ?: (index + 1)
                collect(seasonDoc, season)
            }
            // Episode numbers repeat across seasons, so number the combined list.
            episodes.forEachIndexed { index, episode -> episode.episode_number = (index + 1).toFloat() }
        } else {
            collect(doc, null)
        }

        if (episodes.isEmpty()) {
            episodes += SEpisode.create().apply {
                url = anime.url
                name = doc.selectFirst("div.media-title h1")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: anime.title
                episode_number = 1f
            }
        }

        return episodes
    }

    private fun numberIn(text: String): Float? {
        val ascii = text.map { c ->
            val index = ARABIC_DIGITS.indexOf(c)
            if (index >= 0) '0' + index else c
        }.joinToString("")
        return Regex("""\d+""").find(ascii)?.value?.toFloatOrNull()
    }

    // =============================== Videos ===============================

    private data class StreamLink(
        val url: String,
        val label: String,
        val quality: String? = null,
        val referer: String? = null,
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = getDoc(episode.url) ?: return emptyList()

        return doc.select("ul.server-list li a.option")
            .mapNotNull { option ->
                val embedUrl = option.attr("data-embed-url").trim()
                val post = option.attr("data-post").trim()
                val nume = option.attr("data-nume").trim()
                if (embedUrl.isEmpty() && (post.isEmpty() || nume.isEmpty())) return@mapNotNull null

                val serverName = option.selectFirst("span.server")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "سيرفر"
                Hoster(
                    hosterUrl = embedUrl,
                    hosterName = serverName,
                    memo = buildJsonObject {
                        put("ep", episode.url)
                        put("post", post)
                        put("nume", nume)
                        put("type", option.attr("data-type").trim())
                        put("security", option.attr("data-nonce").trim())
                    },
                )
            }
            .distinctBy { hoster ->
                listOf(hoster.hosterName, hoster.memo["nume"]?.jsonPrimitive?.contentOrNull).joinToString("|")
            }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val referer = hoster.memo["ep"]?.jsonPrimitive?.contentOrNull ?: base()
        val playerUrl = freshEmbedUrl(hoster, referer) ?: hoster.hosterUrl
        if (playerUrl.isEmpty()) return emptyList()

        return resolvePlayer(playerUrl, referer, hoster.hosterName)
            .distinctBy { it.url }
            .map { link ->
                Video(
                    videoUrl = link.url,
                    videoTitle = link.label,
                    resolution = link.quality?.filter { it.isDigit() }?.toIntOrNull(),
                    headers = headersFor(link.referer ?: referer),
                )
            }
    }

    /** The embed URL printed in the page expires, so ask the player endpoint for a fresh one. */
    private suspend fun freshEmbedUrl(hoster: Hoster, referer: String): String? {
        val memo = hoster.memo
        val post = memo["post"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val nume = memo["nume"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val type = memo["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "tv"
        val security = memo["security"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val form = FormBody.Builder()
            .add("action", "player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .apply { if (security.isNotEmpty()) add("security", security) }
            .build()
        val headers = headersFor(referer).newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        val body = try {
            val response = client.post("${base()}/wp-admin/admin-ajax.php", headers, form, ensureSuccess = false)
            if (response.isSuccessful) response.body.string() else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return null

        return runCatching {
            json.parseToJsonElement(body).jsonObject["embed_url"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: IFRAME.find(body)?.groupValues?.get(1)
    }

    private suspend fun resolvePlayer(url: String, referer: String, serverName: String): List<StreamLink> {
        val (html, finalUrl) = bodyOf(url, referer) ?: return emptyList()

        // The site's own player (Vidrco) lists every quality in `data-variants`.
        variantsOf(html, serverName, referer)?.let { return it }

        val iframe = IFRAME.find(html)?.groupValues?.get(1)
        if (!iframe.isNullOrBlank()) {
            return resolveHost(absoluteUrl(iframe, finalUrl), finalUrl, serverName)
        }

        return extractStreams(html, serverName, finalUrl)
    }

    private fun variantsOf(html: String, serverName: String, referer: String): List<StreamLink>? {
        val raw = Jsoup.parse(html).selectFirst("[data-variants]")?.attr("data-variants") ?: return null
        val variants = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return null

        return variants.mapNotNull { element ->
            val obj = element.jsonObject
            val file = obj["file"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
            StreamLink(
                url = file,
                label = listOfNotNull(serverName, label).joinToString(" · "),
                quality = label?.filter { it.isDigit() },
                referer = referer,
            )
        }.takeIf { it.isNotEmpty() }
    }

    private suspend fun resolveHost(url: String, referer: String, serverName: String): List<StreamLink> {
        val host = url.toHttpUrlOrNull()?.host.orEmpty()

        return when {
            host.contains("dood") || host.contains("playmogo") -> dood(url, referer, serverName)
            host.contains("uqload") -> uqload(url, referer, serverName)
            host.contains("mp4upload") -> pageLinks(url, referer, serverName, "https://mp4upload.com/")
            host.contains("vidmoly") -> pageLinks(url, referer, serverName, "https://vidmoly.biz/")
            host.contains("drive.google") -> drive(url, serverName)
            host.contains("mega.") -> emptyList()
            else -> pageLinks(url, referer, serverName, url)
        }
    }

    /** DoodStream: the real file is built from the `/pass_md5/` token plus a random suffix. */
    private suspend fun dood(url: String, referer: String, serverName: String): List<StreamLink> {
        val response = try {
            client.get(url.toHttpUrl(), headersFor(referer), ensureSuccess = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }
        if (!response.isSuccessful) return emptyList()

        val html = response.body.string()
        val finalUrl = response.request.url.toString()
        val page = unpackJs(html) ?: html
        val path = DOOD_PASS.find(page.replace("\\/", "/"))?.value ?: return emptyList()
        val origin = finalUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return emptyList()

        // The embed page sets a cookie the token endpoint expects.
        val cookie = response.headers.values("Set-Cookie")
            .map { it.substringBefore(';') }
            .filter { it.isNotBlank() }
            .joinToString("; ")
        val xhrHeaders = buildMap {
            put("Accept", "*/*")
            put("Origin", origin)
            put("X-Requested-With", "XMLHttpRequest")
            put("Sec-Fetch-Site", "same-origin")
            put("Sec-Fetch-Mode", "cors")
            put("Sec-Fetch-Dest", "empty")
            if (cookie.isNotEmpty()) put("Cookie", cookie)
        }
        val tokenBody = bodyOf("$origin$path", finalUrl, xhrHeaders)?.first?.trim() ?: return emptyList()
        if (tokenBody.isEmpty() || tokenBody == "RELOAD") return emptyList()

        val token = path.substringAfterLast('/')
        val suffix = (1..10).map { ALNUM[Random.nextInt(ALNUM.length)] }.joinToString("")
        val streamUrl = "$tokenBody$suffix?token=$token&expiry=${System.currentTimeMillis()}"

        return listOf(StreamLink(streamUrl, "$serverName · مباشر", referer = finalUrl))
    }

    /** Uqload: the embed page only has a form, the HLS URL comes back from `POST /dl`. */
    private suspend fun uqload(url: String, referer: String, serverName: String): List<StreamLink> {
        val (_, finalUrl) = bodyOf(url, referer) ?: return emptyList()
        val origin = finalUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return emptyList()
        val code = finalUrl.substringAfterLast("/e/", "")
            .substringBefore("/")
            .substringBefore("?")
            .ifEmpty { finalUrl.substringAfterLast("/").substringBefore(".").substringAfter("-") }
        if (code.isEmpty()) return emptyList()

        val form = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", code)
            .add("auto", "1")
            .add("referer", referer)
            .build()
        val text = try {
            val response = client.post("$origin/dl", headersFor(url), form, ensureSuccess = false)
            if (response.isSuccessful) response.body.string() else ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            ""
        }

        return extractStreams(text, serverName, referer)
    }

    private suspend fun pageLinks(
        url: String,
        referer: String,
        serverName: String,
        videoReferer: String,
    ): List<StreamLink> {
        val (html, _) = bodyOf(url, referer) ?: return emptyList()
        return extractStreams(html, serverName, videoReferer)
    }

    private fun extractStreams(html: String, serverName: String, videoReferer: String): List<StreamLink> {
        // Some players pack a decoy script and leave the manifest in the page itself.
        val text = (html + "\n" + (unpackJs(html) ?: "")).replace("\\/", "/")

        return streamRegex.findAll(text)
            .map { it.value }
            .filterNot { link -> SKIP_HOSTS.any { it in link } }
            .distinct()
            .map { StreamLink(it, "$serverName · مباشر", referer = videoReferer) }
            .toList()
    }

    private fun drive(url: String, serverName: String): List<StreamLink> {
        val fileId = DRIVE_ID.find(url)?.groupValues?.get(1) ?: return emptyList()

        return listOf(
            StreamLink(
                url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                label = "$serverName · مباشر",
                referer = "https://drive.google.com/",
            ),
        )
    }

    /** Dean Edwards packer, used by vidmoly, uqload and most of the smaller hosts. */
    private fun unpackJs(html: String): String? {
        val match = PACKED_JS.find(html) ?: return null
        val payload = unescapeJs(match.groupValues[1])
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val words = match.groupValues[4].split('|')

        var result = payload
        for (index in count - 1 downTo 0) {
            val word = words.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: continue
            result = result.replace(Regex("\\b${Regex.escape(encodeBase(index, radix))}\\b")) { word }
        }
        return result
    }

    private fun unescapeJs(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private fun encodeBase(value: Int, radix: Int): String {
        if (value == 0) return "0"
        var rest = value
        val out = StringBuilder()
        while (rest > 0) {
            out.insert(0, BASE62[rest % radix])
            rest /= radix
        }
        return out.toString()
    }

    private companion object {
        private const val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"
        private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val streamRegex = Regex("""https?://[^\s"'<>\\]+?(?:\.(?:m3u8|mp4)\b|\bmaster\.txt\b)[^\s"'<>\\]*""")
        private val SKIP_HOSTS = listOf("test-videos.co.uk", "acek-cdn.com")
        private val IFRAME = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val DOOD_PASS = Regex("""/pass_md5/[^'"]+""")
        private val DRIVE_ID = Regex("""/file/d/([0-9A-Za-z_-]{10,})""")
        private val PACKED_JS = Regex(
            """eval\(function\(p,a,c,k,e,[a-z]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
