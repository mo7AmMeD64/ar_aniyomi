package eu.kanade.tachiyomi.animeextension.ar.anime4up

import android.util.Base64
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.random.Random

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

    private val streamRegex = Regex("""https?://[^\s"'<>\\]+?(?:\.(?:m3u8|mp4)\b|\bmaster\.txt\b)[^\s"'<>\\]*""")

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

    private data class StreamLink(
        val url: String,
        val label: String,
        val quality: String? = null,
        val referer: String? = null,
    )

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

        return resolveLink(hoster.hosterUrl, referer, hoster.hosterName)
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

    private suspend fun resolveLink(url: String, referer: String, serverName: String, depth: Int = 0): List<StreamLink> {
        val link = if (url.startsWith("//")) "https:$url" else url
        if (DIRECT_STREAM.containsMatchIn(link)) return listOf(StreamLink(link, "$serverName · مباشر"))
        if (depth > 2) return emptyList()

        val host = link.toHttpUrlOrNull()?.host.orEmpty()
        return when {
            link.contains("Anime4up-S") || host.contains("aa9o6k9") -> vnx(link, referer, serverName)
            host.contains("share4max") || host.contains("megamax") -> share4max(link, referer, serverName, depth)
            host.contains("voe.") -> voe(link, referer, serverName)
            host.contains("vkvideo.") || host.contains("vk.com") -> vk(link, referer, serverName)
            host.contains("playmogo") || host.contains("dood.") -> dood(link, referer, serverName)
            else -> pageLinks(link, referer, serverName)
        }
    }

    /** VnxPlayer: the site now redirects `4l.*` to the `/Anime4up-Sx/` player, which keeps the HLS URL in `streamUrl`. */
    private suspend fun vnx(url: String, referer: String, serverName: String): List<StreamLink> {
        val (html, finalUrl) = bodyOf(url, referer) ?: return emptyList()
        val stream = VNX_STREAM.find(html)?.groupValues?.get(1) ?: return emptyList()

        return listOf(StreamLink(stream, "$serverName · HLS", referer = finalUrl))
    }

    /** Laravel/Inertia player (`share4max`, `megamax`): the mirrors sit behind an X-Inertia request. */
    private suspend fun share4max(url: String, referer: String, serverName: String, depth: Int): List<StreamLink> {
        val qualities = inertiaQualities(url, referer) ?: return emptyList()

        return qualities.flatMap { (quality, mirrors) ->
            val label = listOfNotNull(serverName, quality).joinToString(" · ")
            val supported = PREFERRED_MIRRORS
                .mapNotNull { prefix -> mirrors.firstOrNull { it.contains(prefix) } }
                .distinct()
            val resolved = supported.firstNotNullOfOrNull { mirror ->
                resolveLink(mirror, referer, label, depth + 1).takeIf { it.isNotEmpty() }
            } ?: return@flatMap emptyList()

            resolved.map { it.copy(quality = quality ?: it.quality) }
        }
    }

    private suspend fun inertiaQualities(url: String, referer: String): List<Pair<String?, List<String>>>? {
        val (html, finalUrl) = bodyOf(url, referer) ?: return null
        val version = Jsoup.parse(html, finalUrl).selectFirst("script[data-page=app]")?.html()?.let {
            runCatching { json.parseToJsonElement(it).jsonObject["version"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        } ?: return null

        val body = bodyOf(
            url,
            referer,
            mapOf(
                "X-Inertia" to "true",
                "X-Inertia-Partial-Component" to "files/mirror/video",
                "X-Inertia-Partial-Data" to "streams",
                "X-Inertia-Version" to version,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )?.first ?: return null

        val data = try {
            json.parseToJsonElement(body).jsonObject["props"]?.jsonObject
                ?.get("streams")?.jsonObject?.get("data")?.jsonArray ?: return null
        } catch (_: Throwable) {
            return null
        }

        return data.mapNotNull { quality ->
            val obj = quality.jsonObject
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
            val mirrors = obj["mirrors"]?.jsonArray?.mapNotNull { mirror ->
                mirror.jsonObject["link"]?.jsonPrimitive?.contentOrNull?.let { if (it.startsWith("//")) "https:$it" else it }
            } ?: emptyList()
            label to mirrors
        }
    }

    private suspend fun voe(url: String, referer: String, serverName: String): List<StreamLink> {
        val first = bodyOf(url, referer)?.first ?: return emptyList()
        val redirect = VO_REDIRECT.find(first)?.groupValues?.get(1)
        val html = if (redirect != null) bodyOf(redirect, url)?.first ?: return emptyList() else first

        val raw = Jsoup.parse(html).selectFirst("script[type=application/json]")?.data()?.trim() ?: return emptyList()
        val encoded = raw.substringAfter("[\"", "").substringBeforeLast("\"]", "")
        val decrypted = decryptVoe(encoded) ?: return emptyList()
        val obj = runCatching { json.parseToJsonElement(decrypted).jsonObject }.getOrNull() ?: return emptyList()

        return listOfNotNull(
            obj["source"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { StreamLink(it, "$serverName · HLS", referer = "https://voe.sx/") },
            obj["direct_access_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { StreamLink(it, "$serverName · MP4", referer = "https://voe.sx/") },
        )
    }

    /** Port of the CloudStream Voe decoder (`decryptF7`). */
    private fun decryptVoe(encoded: String): String? = runCatching {
        var text = encoded.map { c ->
            when {
                c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                else -> c
            }
        }.joinToString("")
        VO_PATTERNS.forEach { text = text.replace(it, "_") }
        text = text.replace("_", "")

        val shifted = String(base64(text), Charsets.ISO_8859_1)
            .map { (it.code - 3).toChar() }
            .joinToString("")
            .reversed()

        String(base64(shifted), Charsets.UTF_8)
    }.getOrNull()

    private fun base64(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return runCatching { Base64.decode(padded, Base64.DEFAULT) }
            .getOrElse { Base64.decode(padded, Base64.URL_SAFE) }
    }

    private suspend fun vk(url: String, referer: String, serverName: String): List<StreamLink> {
        val (html, _) = bodyOf(url.replace("vkvideo.ru", "vk.com"), referer) ?: return emptyList()

        return VK_STREAM.findAll(html).mapNotNull { match ->
            val key = match.groupValues[1]
            val streamUrl = match.groupValues[2].replace("\\/", "/")
            val quality = key.removePrefix("mp4_").takeIf { key != "hls" }
            StreamLink(
                url = streamUrl,
                label = listOfNotNull(serverName, quality?.let { "${it}p" } ?: "HLS").joinToString(" · "),
                quality = quality,
                referer = "https://vk.com/",
            )
        }.toList()
    }

    private suspend fun dood(url: String, referer: String, serverName: String): List<StreamLink> {
        val (html, finalUrl) = bodyOf(url, referer) ?: return emptyList()
        val path = DOOD_PASS.find(html)?.value ?: return emptyList()
        val origin = finalUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return emptyList()

        // The endpoint expects a jQuery XHR from the embed page, not a plain GET.
        val xhrHeaders = mapOf(
            "Accept" to "*/*",
            "Origin" to origin,
            "X-Requested-With" to "XMLHttpRequest",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
        )
        val tokenBody = bodyOf("$origin$path", finalUrl, xhrHeaders)?.first?.trim() ?: return emptyList()
        if (tokenBody.isEmpty() || tokenBody == "RELOAD") return emptyList()

        val token = path.substringAfterLast('/')
        val suffix = (1..10).map { ALNUM[Random.nextInt(ALNUM.length)] }.joinToString("")
        val streamUrl = "$tokenBody$suffix?token=$token&expiry=${System.currentTimeMillis()}"

        return listOf(StreamLink(streamUrl, "$serverName · مباشر", referer = finalUrl))
    }

    /** mp4upload, ruby/morencius (Dean Edwards packed JS) and any player that embeds the manifest. */
    private suspend fun pageLinks(url: String, referer: String, serverName: String): List<StreamLink> {
        val (html, finalUrl) = bodyOf(url, referer) ?: return emptyList()
        val text = unpackJs(html) ?: html
        val pageReferer = if (finalUrl.contains("mp4upload")) "https://mp4upload.com/" else finalUrl

        return streamRegex.findAll(text.replace("\\/", "/"))
            .map { it.value }
            .filterNot { link -> SKIP_HOSTS.any { it in link } }
            .distinct()
            .map { StreamLink(it, "$serverName · مباشر", referer = pageReferer) }
            .toList()
    }

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
        private val DIRECT_STREAM = Regex("""\.(mp4|m3u8)\b|\bmaster\.txt\b""", RegexOption.IGNORE_CASE)
        private val SKIP_HOSTS = listOf("test-videos.co.uk", "acek-cdn.com")
        private val PREFERRED_MIRRORS = listOf("mp4upload.", "voe.sx", "lulustream.", "luluvdo.", "morencius.", "rubyvidhub.", "playmogo.")
        private val VO_REDIRECT = Regex("""window\.location\.href\s*=\s*'([^']+)'""")
        private val VO_PATTERNS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        private val VNX_STREAM = Regex("""let streamUrl = "([^"]+)"""")
        private val VK_STREAM = Regex(""""(mp4_\d{3,4}|hls)":"(https:\\/\\/[^"]+)"""")
        private val DOOD_PASS = Regex("""/pass_md5/[^'"]+""")
        private val PACKED_JS = Regex("""eval\(function\(p,a,c,k,e,[a-z]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
    }
}
