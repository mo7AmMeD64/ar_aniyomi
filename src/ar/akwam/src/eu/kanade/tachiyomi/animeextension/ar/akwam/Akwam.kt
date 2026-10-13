package eu.kanade.tachiyomi.animeextension.ar.akwam

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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Akwam : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "Akwam"

    override val lang = "ar"

    override val baseUrl = "https://akwam.ss"

    override val supportsLatest = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.77 Safari/537.36"

    // =============================== Network ==============================

    private suspend fun getDoc(url: String): Document? = try {
        val response = client.get(url.toHttpUrl(), Headers.headersOf("User-Agent", userAgent))
        Jsoup.parse(response.body.string(), response.request.url.toString())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // =============================== Cards ================================

    private fun Element.poster(): String? =
        selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.startsWith("http") }

    private fun Element.toSAnime(): SAnime? {
        val a = selectFirst("h3.entry-title a") ?: return null
        val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return null
        val href = selectFirst("a")?.attr("abs:href") ?: a.attr("abs:href") ?: return null
        return SAnime.create().apply {
            url = href
            this.title = title
            thumbnail_url = poster()
        }
    }

    private fun Document.cards(): List<SAnime> =
        select("div.col-lg-auto.col-md-4.col-6").mapNotNull { it.toSAnime() }

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        browse("movies", page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        browse("series", page)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) return getPopularAnime(page)

        val url = "$baseUrl/search?q=${URLEncoder.encode(q, "UTF-8")}" +
            if (page > 1) "&page=$page" else ""
        val doc = getDoc(url) ?: return AnimesPage(emptyList(), false)
        val items = doc.cards()
        return AnimesPage(items, items.size >= 30)
    }

    private suspend fun browse(path: String, page: Int): AnimesPage {
        val url = "$baseUrl/$path" + if (page > 1) "?page=$page" else ""
        val doc = getDoc(url) ?: return AnimesPage(emptyList(), false)
        val items = doc.cards()
        return AnimesPage(items, items.size >= 24)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDoc(anime.url) ?: throw Exception("Akwam: page not found")

        return anime.apply {
            title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: title
            description = doc.selectFirst("h2:contains(قصة المسلسل) + div > p")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            genre = doc.select("div.font-size-16.text-white a[href*='/genre/'], div.font-size-16.text-white a[href*='/category/']")
                .map { it.text() }
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
            thumbnail_url = doc.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.takeIf { it.startsWith("http") } ?: thumbnail_url
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = getDoc(anime.url) ?: return emptyList()

        // Movie: single pseudo-episode
        val directEpisodes = doc.select("div#series-episodes div[class*='col-']")
        if (directEpisodes.isEmpty() && doc.selectFirst("a.link-show") != null) {
            return listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = "فيلم"
                    episode_number = 1f
                },
            )
        }

        if (directEpisodes.isEmpty()) return emptyList()

        // Series: collect episodes from all seasons
        val episodes = mutableListOf<SEpisode>()
        val seen = mutableSetOf<String>()

        for (el in directEpisodes) {
            val link = el.selectFirst("a[href*='/episode/']") ?: continue
            val epUrl = link.attr("abs:href")
            if (epUrl.isBlank() || !seen.add(epUrl)) continue
            val epName = link.selectFirst("h2")?.text()?.trim() ?: link.text().trim()
            val epNum = Regex("""\d+""").findAll(epName).lastOrNull()?.value?.toIntOrNull()
                ?: (episodes.size + 1)

            episodes += SEpisode.create().apply {
                url = epUrl
                name = epName
                episode_number = epNum.toFloat()
                preview_url = el.poster()
            }
        }

        return episodes.sortedBy { it.episode_number }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
        listOf(Hoster(hosterUrl = episode.url, hosterName = "Akwam"))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeUrl = hoster.hosterUrl

        // Step 1: episode page -> find watch link
        val step1 = getDoc(episodeUrl) ?: return emptyList()
        val watchHref = step1.selectFirst("a.link-show")?.attr("abs:href")
            ?: step1.selectFirst("a.link-show")?.attr("href")
            ?: return emptyList()

        val watchUrl = if (watchHref.startsWith("http")) watchHref else "$baseUrl/$watchHref"

        // Step 2: watch page -> source[src] elements
        val step2 = getDoc(watchUrl) ?: return emptyList()
        val sources = step2.select("source[src]")
        if (sources.isEmpty()) return emptyList()

        val seen = mutableSetOf<String>()
        val videos = mutableListOf<Video>()
        for (src in sources) {
            val url = src.attr("abs:src").ifBlank { src.attr("src") }.trim()
                .replace(" ", "%20")
                .replace("https://", "http://")
            if (url.isBlank() || !seen.add(url)) continue

            val qualityAttr = src.attr("size").ifBlank { src.attr("label") }.ifBlank { "direct" }
            videos += Video(
                videoUrl = url,
                videoTitle = "Akwam $qualityAttr",
                resolution = qualityAttr.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.toIntOrNull(),
                headers = Headers.headersOf(
                    "User-Agent",
                    userAgent,
                    "Referer",
                    episodeUrl,
                ),
            )
        }
        return videos
    }
}
