package eu.kanade.tachiyomi.animeextension.all.anime3rb

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anime3rb : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "Anime3rb"

    override val lang = "ar"

    override val baseUrl = "https://anime3rb.com"

    override val supportsLatest = true

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val nonDigits = Regex("[^0-9]")

    private val titleEpisodeRegex = Regex("الحلقة \\d+")

    private var savedCookies: String = ""

    // ============================== Network ==============================

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }

    private suspend fun getDocument(url: String): Document? = try {
        val response = client.get(url, Headers.headersOf("User-Agent", userAgent))
        response.header("set-cookie")?.let { savedCookies = it }
        Jsoup.parse(response.body.string())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // =============================== Utils ===============================

    private fun cleanTitle(text: String): String = text
        .replace(Regex("بترجمة.*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    // ============================== Popular ==============================

    /** Series cards of "آخر الأنميات المضافة" on the home page. */
    private fun Document.addedCards(): List<Element> = select("h3:contains(آخر الأنميات المضافة)").firstOrNull()
        ?.parents()?.getOrNull(2)
        ?.select(".glide__slide:not(.glide__slide--clone) a[href*=/titles/]")
        ?: emptyList()

    private fun Element.toSAnime(): SAnime? {
        val href = attr("href")
        if (href.isBlank()) return null
        val title = cleanTitle(select("h3.title-name, h4").firstOrNull()?.text() ?: "")
        if (title.isBlank()) return null
        return SAnime.create().apply {
            url = absolute(href)
            this.title = title
            thumbnail_url = select("img").attr("src").takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val doc = getDocument(baseUrl) ?: return AnimesPage(emptyList(), false)
        val items = doc.addedCards().mapNotNull { it.toSAnime() }
        return AnimesPage(items, false)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) return AnimesPage(emptyList(), false)
        if (page > 1) return AnimesPage(emptyList(), false)

        val home = getDocument(baseUrl) ?: return AnimesPage(emptyList(), false)

        val csrf = home.selectFirst("script[src*=livewire.min.js]")?.attr("data-csrf")
            ?: return AnimesPage(emptyList(), false)
        val snapshot = home.selectFirst("form[wire:id]")?.attr("wire:snapshot")
            ?: return AnimesPage(emptyList(), false)

        val headers = Headers.headersOf(
            "User-Agent", userAgent,
            "Accept", "*/*",
            "Content-Type", "application/json",
            "Origin", baseUrl,
            "Referer", "$baseUrl/",
        ).newBuilder().apply { if (savedCookies.isNotBlank()) add("Cookie", savedCookies) }.build()

        val payload = buildJsonObject {
            put("_token", csrf)
            put(
                "components",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("snapshot", snapshot)
                            put(
                                "updates",
                                buildJsonObject {
                                    put("query", query)
                                },
                            )
                            put("calls", buildJsonArray { })
                        },
                    )
                },
            )
        }

        val items = try {
            val response = client.post("$baseUrl/livewire/update", headers, payload.toString().toJsonBody())
            val html = response.parseAs<JsonObject>()["components"]!!.jsonArray[0]
                .jsonObject["effects"]!!.jsonObject["html"]!!.jsonPrimitive.content
            Jsoup.parse(html).select("a.simple-title-card").mapNotNull { item ->
                val title = cleanTitle(item.selectFirst("h4")?.text() ?: "")
                if (title.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    url = absolute(item.attr("href"))
                    this.title = title
                    thumbnail_url = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

        return AnimesPage(items, false)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDocument(anime.url) ?: throw Exception("Anime not found")

        return anime.apply {
            title = titleEpisodeRegex.replace(doc.selectFirst("h1")?.text() ?: "", "").trim().ifBlank { title }
            description = doc.selectFirst("p.synopsis")?.text()
                ?: doc.selectFirst("meta[name='description']")?.attr("content")
            thumbnail_url = doc.selectFirst("img[alt*='بوستر']")?.attr("src")?.takeIf { it.isNotBlank() } ?: thumbnail_url
            genre = doc.select("a[href*=/genre/]").eachText().joinToString(", ")
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = getDocument(anime.url) ?: return emptyList()

        return doc.select(".video-list a").reversed().mapIndexedNotNull { index, element ->
            val href = absolute(element.attr("href"))
            if (href.isBlank()) return@mapIndexedNotNull null
            val videoData = element.selectFirst(".video-data")
            val epText = videoData?.child(0)?.text() ?: ""
            val epName = videoData?.child(1)?.text() ?: ""
            val epNumber = nonDigits.replace(epText, "").toIntOrNull() ?: (index + 1)

            SEpisode.create().apply {
                url = href
                name = epName.ifBlank { epText }
                episode_number = epNumber.toFloat()
                preview_url = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            }
        }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterUrl = episode.url, hosterName = "Anime3rb"))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeUrl = hoster.hosterUrl
        val html = try {
            client.get(episodeUrl, Headers.headersOf("User-Agent", userAgent)).body.string()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }

        // The page embeds the player url entity-escaped inside a wire:snapshot.
        val decoded = html
            .replace("&" + "quot;", "\"")
            .replace("&" + "amp;", "&")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
        val playerUrl = Regex("https://video\\.vid3rb\\.com/player/[^\"'\\s]+").find(decoded)?.value
            ?: return emptyList()

        val playerPage = try {
            client.get(
                playerUrl.toHttpUrl(),
                Headers.headersOf(
                    "User-Agent", userAgent,
                    "Referer", "$baseUrl/",
                    "Accept", "*/*",
                    "Sec-Fetch-Dest", "iframe",
                    "Sec-Fetch-Site", "cross-site",
                ),
            ).body.string()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }

        // The page declares video_sources twice — first empty, then the real one.
        val sourcesJson = Regex("var\\s+video_sources\\s*=\\s*(\\[.*?\\]);")
            .findAll(playerPage)
            .map { it.groupValues[1] }
            .lastOrNull { it.length > 10 }
            ?: return emptyList()

        return try {
            val array = Json.parseToJsonElement(sourcesJson).jsonArray
            array.mapNotNull { element ->
                val obj = element.jsonObject
                val src = obj["src"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (obj["premium"]?.jsonPrimitive?.content == "true") return@mapNotNull null
                val label = obj["label"]?.jsonPrimitive?.content ?: "Unknown"
                Video(
                    videoUrl = src,
                    videoTitle = "Anime3rb $label",
                    resolution = nonDigits.replace(label, "").takeIf { it.isNotBlank() }?.toIntOrNull(),
                    headers = Headers.headersOf("User-Agent", userAgent),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
