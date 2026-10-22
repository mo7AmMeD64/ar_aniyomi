package eu.kanade.tachiyomi.animeextension.ar.animeday

import android.annotation.SuppressLint
import android.provider.Settings
import androidx.preference.PreferenceScreen
import com.anime.day.Utils.NativeLib
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.get
import extensions.utils.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

@SuppressLint("HardwareIds")
class AnimeDay : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "AnimeDay"

    override val lang = "ar"

    override val baseUrl = DEFAULT_API

    override val supportsLatest = true

    @Volatile
    private var resolvedApi: String? = null

    // =============================== Network ==============================

    private suspend fun api(): String {
        resolvedApi?.let { return it }
        val fetched = tryFetchCloudday()
        return (fetched ?: DEFAULT_API).also { resolvedApi = it }
    }

    private suspend fun tryFetchCloudday(): String? = try {
        val response = client.get(CONFIG_API.toHttpUrl(), apiHeaders(), ensureSuccess = false)
        val body = if (response.isSuccessful) response.use { it.body.string() } else null
        val json = body?.let { runCatching { JSONObject(it.trim()) }.getOrNull() }
        val first = json?.optJSONArray("data")?.optJSONObject(0)
            ?: json?.optJSONObject("data")
        val base = first?.optString("cloudday").orEmpty().trim()
        base.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.trimEnd('/')
            ?.let { "$it/v3.8/api" }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun apiHeaders(): Headers = Headers.Builder()
        .add("User-Agent", AnimeDayExtractors.UA)
        .add("Accept", "application/json, text/plain, */*")
        .build()

    private fun androidId(): String {
        val id = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) {
            null
        }
        return if (id.isNullOrEmpty()) randomString(16) else id
    }

    private fun freshToken(): String? = try {
        NativeLib.secureId(context)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /** `cloudflare-id` is 15 random chars plus the Android ID; the native decrypt key is that ID. */
    private fun serverHeaders(): Pair<Headers, String> {
        val cf = randomString(15) + androidId()
        val builder = Headers.Builder()
            .add("User-Agent", AnimeDayExtractors.UA)
            .add("Accept", "application/json, text/plain, */*")
            .add("cloudflare-id", cf)
        freshToken()?.let { builder.add("firebase_id", it) }
        return builder.build() to cf
    }

    private suspend fun fetchJson(url: String, withToken: Boolean): JSONObject? = try {
        val headers = if (withToken) serverHeaders().first else apiHeaders()
        val response = client.get(url.toHttpUrl(), headers, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            null
        } else {
            val body = response.use { it.body.string() }
            JSONObject(body.trim()).takeIf { !it.optBoolean("blocked", false) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // ================================ Cards ================================

    private suspend fun card(item: JSONObject): SAnime? {
        val type = item.optString("type", "movie")
        val id = item.optString("id")
        if (id.isEmpty()) return null
        val isEpisode = type == "episode"
        val showId = if (isEpisode) item.optString("tv_show_id") else id
        if (showId.isEmpty()) return null

        val title = when {
            isEpisode && item.optString("tv_show_name").isNotEmpty() -> item.optString("tv_show_name")
            item.optString("name").isNotEmpty() -> item.optString("name")
            item.optString("tv_show_name").isNotEmpty() -> item.optString("tv_show_name")
            else -> return null
        }
        val url = if (isEpisode) {
            "${api()}/series/$showId"
        } else {
            detailUrl(type, id)
        }

        return SAnime.create().apply {
            this.url = url
            this.title = title
            thumbnail_url = item.optString("poster")
                .ifEmpty { item.optString("image") }
                .ifEmpty { item.optString("cover") }
                .ifEmpty { null }
        }
    }

    private suspend fun detailUrl(type: String, id: String): String = if (type == "movie" || type == "film") "${api()}/movie/$id" else "${api()}/series/$id"

    private suspend fun homeCards(json: JSONObject, latestOnly: Boolean): List<SAnime> {
        val out = LinkedHashMap<String, SAnime>()
        val sections = json.optJSONArray("sections") ?: JSONArray()
        for (s in 0 until sections.length()) {
            val section = sections.optJSONObject(s) ?: continue
            val items = section.optJSONArray("section_items") ?: continue
            if (latestOnly) {
                var hasEpisode = false
                for (i in 0 until items.length()) {
                    if (items.optJSONObject(i)?.optString("type") == "episode") {
                        hasEpisode = true
                        break
                    }
                }
                if (!hasEpisode) continue
            }
            for (i in 0 until items.length()) {
                val anime = card(items.optJSONObject(i) ?: continue) ?: continue
                out.putIfAbsent(anime.url, anime)
            }
        }
        return out.values.toList()
    }

    // ================================ Catalog ================================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val json = fetchJson("${api()}/home", withToken = false)
            ?: return AnimesPage(emptyList(), false)
        return AnimesPage(homeCards(json, latestOnly = false), false)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val json = fetchJson("${api()}/home", withToken = false)
            ?: return AnimesPage(emptyList(), false)
        return AnimesPage(homeCards(json, latestOnly = true), false)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList): AnimesPage {
        val title = query.trim()
        if (title.isEmpty()) return AnimesPage(emptyList(), false)

        val form = FormBody.Builder()
            .add("title", title)
            .add("type", "0")
            .add("sort", "1")
            .add("page", page.toString())
            .build()
        val json = try {
            val response = client.post("${api()}/search".toHttpUrl(), apiHeaders(), form, ensureSuccess = false)
            if (!response.isSuccessful) {
                response.close()
                null
            } else {
                JSONObject(response.use { it.body.string() }.trim())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return AnimesPage(emptyList(), false)

        val results = json.optJSONArray("results") ?: JSONArray()
        val animes = (0 until results.length()).mapNotNull { i ->
            card(results.optJSONObject(i) ?: return@mapNotNull null)
        }.distinctBy { it.url }

        val lastPage = json.optInt("last_page", json.optInt("pages", page))
        return AnimesPage(animes, lastPage > page)
    }

    // ================================ Details ================================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val isMovie = anime.url.contains("/movie/")
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        val url = if (isMovie) "${api()}/movie/$id" else "${api()}/series/$id"
        val json = fetchJson(url, withToken = true) ?: return anime

        val obj = json.optJSONObject(if (isMovie) "movie" else "series")
            ?: json.optJSONObject("data")
            ?: json
        val statusValue = firstString(obj, "status", "status_text", "state")

        return anime.apply {
            this.url = url
            title = firstString(obj, "name", "title", "tv_show_name").ifEmpty { title }
            thumbnail_url = firstString(obj, "poster", "image", "cover", "backdrop")
                .ifEmpty { thumbnail_url }
            background_url = firstString(obj, "backdrop", "background")
            description = firstString(obj, "overview", "description", "story", "plot")
            genre = genres(obj)
            status = when {
                statusValue.contains("مكتمل") || statusValue.contains("منتهي") -> SAnime.COMPLETED
                statusValue.contains("يعرض") || statusValue.contains("مستمر") -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key)
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun genres(obj: JSONObject): String {
        val array = obj.optJSONArray("genres") ?: return obj.optString("genre")
        return (0 until array.length()).mapNotNull { i ->
            when (val item = array.opt(i)) {
                is String -> item
                is JSONObject -> item.optString("name")
                else -> null
            }?.takeIf { it.isNotEmpty() }
        }.joinToString(", ")
    }

    // ================================ Episodes ================================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url.contains("/movie/")) {
            val id = anime.url.substringAfterLast("/").substringBefore("?")
            return listOf(
                SEpisode.create().apply {
                    url = "${api()}/movie/$id/servers"
                    name = anime.title
                    episode_number = 1f
                    preview_url = anime.thumbnail_url
                },
            )
        }

        val id = anime.url.substringAfter("/series/").substringBefore("/").substringBefore("?")
        val json = fetchJson("${api()}/series/$id/episodes", withToken = false)
            ?: return emptyList()
        val seasons = json.optJSONArray("seasons") ?: JSONArray()
        val multipleSeasons = seasons.length() > 1
        val episodes = mutableListOf<SEpisode>()

        for (s in 0 until seasons.length()) {
            val season = seasons.optJSONObject(s) ?: continue
            val seasonNumber = season.optString("season_number", (s + 1).toString()).toIntOrNull() ?: (s + 1)
            val items = season.optJSONArray("episodes") ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val episodeId = item.optString("id")
                if (episodeId.isEmpty()) continue
                val title = firstString(item, "title", "name").ifEmpty { "الحلقة ${i + 1}" }
                val number = item.optString("episode_number").toIntOrNull()?.takeIf { it > 0 }
                    ?: episodeNumber(title, i + 1)
                episodes += SEpisode.create().apply {
                    url = "${api()}/episode/$episodeId/servers"
                    name = if (multipleSeasons) "الموسم $seasonNumber · $title" else title
                    episode_number = number.toFloat()
                    preview_url = firstString(item, "image", "cover", "poster").ifEmpty { null }
                }
            }
        }
        return episodes
    }

    private fun episodeNumber(title: String, fallback: Int): Int {
        val ascii = title.map { char ->
            when (char) {
                in '\u0660'..'\u0669' -> '0' + (char - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (char - '\u06F0')
                else -> char
            }
        }.joinToString("")
        return Regex("""\d{1,4}""").find(ascii)?.value?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: fallback
    }

    // ================================ Videos ================================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (headers, cf) = serverHeaders()
        val raw = try {
            val response = client.get(episode.url.toHttpUrl(), headers, ensureSuccess = false)
            if (response.isSuccessful) response.use { it.body.string() } else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        val decrypted = if (raw.trim().startsWith("{") || raw.trim().startsWith("[")) {
            raw
        } else {
            NativeLib.decrypt(context, raw.trim(), cf.substring(15)) ?: return emptyList()
        }
        val json = runCatching { JSONObject(decrypted.trim()) }.getOrNull() ?: return emptyList()
        val servers = json.optJSONArray("servers") ?: return emptyList()

        return (0 until servers.length()).mapNotNull { i ->
            val server = servers.optJSONObject(i) ?: return@mapNotNull null
            val rawLink = server.optString("link").trim().replace("\\s+".toRegex(), "")
            if (rawLink.isEmpty()) return@mapNotNull null
            val link = if (rawLink.startsWith("http")) {
                rawLink
            } else {
                REFERER.trimEnd('/') + "/" + rawLink.trimStart('/')
            }
            val referer = firstString(server, "referer", "origin").ifEmpty { REFERER }
            val label = firstString(server, "title", "name").ifEmpty { "سيرفر ${i + 1}" }
            val quality = server.optString("height").toIntOrNull()
                ?: server.optString("quality").filter { it.isDigit() }.toIntOrNull()

            Hoster(
                hosterUrl = link,
                hosterName = label,
                memo = buildJsonObject {
                    put("link", link)
                    put("referer", referer)
                    put("label", label)
                    if (quality != null) put("quality", quality)
                },
            )
        }.distinctBy { it.hosterUrl }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val link = hoster.memo["link"]?.jsonPrimitive?.contentOrNull ?: hoster.hosterUrl
        if (link.isBlank()) return emptyList()
        val referer = hoster.memo["referer"]?.jsonPrimitive?.contentOrNull ?: REFERER
        val label = hoster.memo["label"]?.jsonPrimitive?.contentOrNull ?: hoster.hosterName
        val quality = hoster.memo["quality"]?.jsonPrimitive?.intOrNull

        return withTimeoutOrNull(25_000L) {
            AnimeDayExtractors.resolve(client, link, referer, label, quality)
        } ?: emptyList()
    }

    private companion object {
        const val DEFAULT_API = "https://20042026.site/v3.8/api"
        const val CONFIG_API = "https://backup-animeday.animegateshorts.workers.dev"
        const val REFERER = "https://www.anime-day.com/"
        const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun randomString(length: Int): String = buildString {
            repeat(length) { append(ALPHANUM[Random.nextInt(ALPHANUM.length)]) }
        }
    }
}
