package eu.kanade.tachiyomi.animeextension.ar.animewitcher

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class AnimeWitcher : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "AnimeWitcher"

    override val lang = "ar"

    override val baseUrl = "https://animewitcher.com"

    override val supportsLatest = true

    private val firebaseProject = "animewitcher-1c66d"
    private val firebaseApiKey = "AIzaSyAcbWRwfFNnCpoydDXlEALWnM_TYVcJOMU"

    @Volatile
    private var algoliaAppId = "QVHT7NPEJG"

    @Volatile
    private var algoliaApiKey = "ce13098070fa521b536571bafbcfc083"

    private val serverWordsCache = ConcurrentHashMap<String, JsonObject?>()

    // =============================== Json ================================

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private suspend fun algoliaQuery(index: String, params: String): JsonObject? = try {
        algoliaQueryInner(index, params)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        refreshAlgoliaKeys()
        try {
            algoliaQueryInner(index, params)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun algoliaQueryInner(index: String, params: String): JsonObject {
        val headers = Headers.headersOf(
            "X-Algolia-Application-Id",
            algoliaAppId,
            "X-Algolia-API-Key",
            algoliaApiKey,
            "User-Agent",
            "Algolia for Android (3.27.0); Android (16)",
            "Content-Type",
            "application/json; charset=UTF-8",
        )
        val body = buildJsonObject { put("params", params) }.toString().toJsonBody()
        return client.post("https://$algoliaAppId-dsn.algolia.net/1/indexes/$index/query", headers, body)
            .parseAs<JsonObject>()
    }

    private suspend fun refreshAlgoliaKeys() {
        try {
            val res = firestoreGet("Settings/constants") ?: return
            val fields = res.obj("fields") ?: return

            fields.obj("search_settings")?.obj("mapValue")?.obj("fields")?.let { s ->
                val newAppId = s.str("app_id_v3") ?: s.str("app_id") ?: s.str("app_id_v2")
                val newApiKey = s.str("api_key")
                if (!newAppId.isNullOrBlank() && !newApiKey.isNullOrBlank()) {
                    algoliaAppId = newAppId
                    algoliaApiKey = newApiKey
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun firestoreDocUrl(path: String): String = "https://firestore.googleapis.com/v1/projects/$firebaseProject" +
        "/databases/(default)/documents/$path?key=$firebaseApiKey"

    private suspend fun firestoreGet(path: String): JsonObject? = try {
        client.get(firestoreDocUrl(path), Headers.headersOf()).parseAs<JsonObject>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun encode(path: String): String = URLEncoder.encode(path, "utf-8").replace("+", "%20")

    private fun qualityInt(quality: String?): Int = quality?.filter { it.isDigit() }?.toIntOrNull() ?: 0

    // =============================== Cards ================================

    private fun JsonObject.toSAnime(): SAnime? {
        val title = str("name") ?: return null
        val id = str("anime_id") ?: str("objectID") ?: return null
        return SAnime.create().apply {
            url = id
            this.title = title
            thumbnail_url = str("poster_uri")
        }
    }

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val pageParam = (page - 1).coerceAtLeast(0)
        val attributes =
            URLEncoder.encode(
                "[\"name\",\"anime_id\",\"objectID\",\"poster_uri\",\"episode_id\",\"episode_name\"]",
                "utf-8",
            )
        val res = algoliaQuery("recent", "attributesToRetrieve=$attributes&hitsPerPage=30&page=$pageParam&query=")
            ?: return AnimesPage(emptyList(), false)

        val hits = res.arr("hits") ?: return AnimesPage(emptyList(), false)
        val items = hits.mapNotNull { (it as? JsonObject)?.toSAnime() }
        val nbPages = res["nbPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: pageParam + 1
        return AnimesPage(items, pageParam < nbPages - 1)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val attributes =
            URLEncoder.encode("[\"objectID\",\"name\",\"poster_uri\",\"anime_id\"]", "utf-8")
        val res = algoliaQuery("series", "attributesToRetrieve=$attributes&hitsPerPage=50&page=0&query=$q")
            ?: return AnimesPage(emptyList(), false)

        val hits = res.arr("hits") ?: return AnimesPage(emptyList(), false)
        return AnimesPage(hits.mapNotNull { (it as? JsonObject)?.toSAnime() }, false)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeId = anime.url
        val escaped = animeId.replace("\"", "\\\"")
        val animeJson = algoliaQuery("series", "filters=objectID:\"$escaped\"")
            ?.arr("hits")?.mapNotNull { it as? JsonObject }?.firstOrNull()
            ?: throw Exception("Anime not found")

        val details = animeJson.obj("details")

        return anime.apply {
            title = animeJson.str("name") ?: title
            description = animeJson.str("story")
            genre = animeJson.arr("tags")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(", ")
            thumbnail_url = animeJson.str("poster_uri") ?: thumbnail_url
            status = if (details?.str("state") == "مكتمل") SAnime.COMPLETED else SAnime.ONGOING
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url
        val escaped = animeId.replace("\"", "\\\"")
        val animeJson = algoliaQuery("series", "filters=objectID:\"$escaped\"")
            ?.arr("hits")?.mapNotNull { it as? JsonObject }?.firstOrNull()
            ?: return emptyList()

        val docRef = animeJson.str("doc_ref") ?: animeJson.str("path") ?: "anime_list/$animeId"
        val cleanPath = docRef.substringAfter("anime_list/").removePrefix("/")

        val summary = firestoreGet("anime_list/${encode(cleanPath)}/episodes_summery/summery")
            ?.obj("fields")?.obj("episodes")?.obj("arrayValue")?.arr("values")
            ?: return emptyList()

        return summary.mapNotNull { v ->
            val epFields = (v as? JsonObject)?.obj("mapValue")?.obj("fields") ?: return@mapNotNull null
            val docId = epFields.str("doc_id") ?: return@mapNotNull null
            val translated = epFields.obj("title_translated")?.obj("mapValue")?.obj("fields")
            val epName = translated?.str("ar") ?: epFields.str("name") ?: "الحلقة $docId"
            val numberField = epFields.obj("number")
            val numberStr = numberField?.str("integerValue") ?: numberField?.str("stringValue")

            SEpisode.create().apply {
                url = "$cleanPath|$docId"
                name = epName
                episode_number = (numberStr?.toIntOrNull() ?: 1).toFloat()
                preview_url = epFields.str("thumb_uri") ?: animeJson.str("poster_uri")
            }
        }.sortedBy { it.episode_number }
    }

    // =============================== Videos ===============================

    private data class ServerModel(
        val name: String,
        val link: String,
        val quality: String?,
        val originalLink: String?,
    )

    private suspend fun fetchServers(animePath: String, episodeId: String): List<ServerModel> {
        val path = "anime_list/${encode(animePath)}/episodes/${encode(episodeId)}/servers2/all_servers"
        firestoreGet(path)?.obj("fields")?.obj("servers")?.obj("arrayValue")?.arr("values")
            ?.let { values ->
                val list = values.mapNotNull { v ->
                    val f = (v as? JsonObject)?.obj("mapValue")?.obj("fields") ?: return@mapNotNull null
                    val n = f.str("name") ?: return@mapNotNull null
                    val l = f.str("link") ?: return@mapNotNull null
                    ServerModel(n, l, f.str("quality"), f.str("original_link"))
                }
                if (list.isNotEmpty()) return list.sortedByDescending { qualityInt(it.quality) }
            }

        // Fallback: the per-server collection.
        val coll = "anime_list/${encode(animePath)}/episodes/${encode(episodeId)}/servers"
        firestoreGet(coll)?.arr("documents")?.let { docs ->
            return docs.mapNotNull { d ->
                val f = (d as? JsonObject)?.obj("fields") ?: return@mapNotNull null
                if (f.str("visible") == "false") return@mapNotNull null
                val n = f.str("name") ?: return@mapNotNull null
                val l = f.str("link") ?: return@mapNotNull null
                ServerModel(n, l, f.str("quality"), f.str("original_link"))
            }.sortedByDescending { qualityInt(it.quality) }
        }
        return emptyList()
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        if (parts.size < 2) return emptyList()
        val servers = fetchServers(parts[0], parts[1])

        return servers.map { s ->
            Hoster(
                hosterUrl = s.link,
                hosterName = listOf(s.name, s.quality).filterNotNull().joinToString(" "),
                memo = buildJsonObject {
                    put("path", parts[0])
                    put("ep", parts[1])
                    put("name", s.name)
                    put("orig", s.originalLink ?: "")
                },
            )
        }
    }

    private fun String.htmlDecode(): String = replace("&" + "amp;", "&")

    private suspend fun serverWords(serverName: String): JsonObject? {
        serverWordsCache[serverName]?.let { return it }
        val fields = firestoreGet("Settings/servers/servers/${encode(serverName)}")?.obj("fields")
        serverWordsCache[serverName] = fields
        return fields
    }

    private suspend fun resolveServer(name: String, link: String, originalLink: String?): String? {
        val words = serverWords(name)
        val w1 = words?.str("word1")
        val w2 = words?.str("word2")

        suspend fun pageBody(url: String): String = try {
            client.get(url.toHttpUrl(), Headers.headersOf("User-Agent", "Mozilla/5.0")).body.string()
        } catch (_: Throwable) {
            ""
        }

        return when (name.uppercase()) {
            // MediaFire: scrape the direct download link off the file page.
            "MF" -> {
                val html = pageBody(link)
                Regex("""https?://download[\\w.-]*\\.mediafire\\.com/[^"'\\s<>]+""").find(html)?.value ?: link
            }

            // StreamTape: the video url is assembled from two script fragments.
            "ST" -> {
                val html = pageBody(link)
                val robot = Regex(
                    """robotlink'\\)\\.innerHTML\\s*=\\s*'([^']+)'\\s*\\+\\s*'([^']+)'""",
                ).find(html)
                when {
                    robot != null -> {
                        val u = robot.groupValues[1] + robot.groupValues[2]
                        when {
                            u.startsWith("//") -> "https:$u"
                            u.startsWith("http") -> u
                            else -> "https://streamtape.com/$u"
                        }
                    }

                    else -> Regex("""id="videolink"[^>]*>([^<]+)<""").find(html)?.groupValues?.get(1)
                        ?.let {
                            when {
                                it.startsWith("//") -> "https:$it"
                                it.startsWith("http") -> it
                                else -> null
                            }
                        }
                }
            }

            "MG", "BU" -> link

            "QI" -> originalLink ?: link

            "PD" -> {
                // pixeldrain: /u/{id} plays directly through the api endpoint
                val html = pageBody(link)
                val og = Regex("""<meta property="og:video" content="([^"]+)""").find(html)?.groupValues?.get(1)
                if (!og.isNullOrEmpty()) return og.htmlDecode()
                val uid = Regex("pixeldrain\\.com/(?:u|l)/([A-Za-z0-9_-]+)").find(link)?.groupValues?.get(1)
                    ?: Regex("(/u/[A-Za-z0-9_-]+)").find(html)?.groupValues?.get(1)?.substringAfter("/")
                    ?: return link
                "https://pixeldrain.com/api/video/$uid"
            }

            "VT" -> {
                val w3 = words?.str("word3")
                val w4 = words?.str("word4")
                if (w1 == null || w2 == null || w3 == null || w4 == null) return null
                val res1 = pageBody(link)
                if (res1.isBlank()) return null
                val part1 = res1.substringAfter(w1).substringBefore(w2).replace("\">", "").trim()
                val res2 = pageBody("https://vidtube.one$part1")
                if (res2.isBlank()) return null
                "https://" + res2.substringAfter(w3).substringBefore(w4)
            }

            "AR" -> try {
                client.get(link.toHttpUrl(), Headers.headersOf()).request.url.toString()
            } catch (_: Throwable) {
                null
            }

            "WC" -> try {
                val noRedirect = client.newBuilder().followRedirects(false).build()
                noRedirect.get(link.toHttpUrl(), Headers.headersOf()).header("Location") ?: link
            } catch (_: Throwable) {
                null
            }

            else -> {
                // KF + unknown servers scrape their direct link between two marker words
                if (w1 == null || w2 == null) return null
                val res = pageBody(link)
                if (res.isBlank()) return null
                val extracted = res.substringAfter(w1).substringBefore(w2).replace("amp;", "")
                when {
                    extracted.startsWith("http") -> extracted
                    extracted.isBlank() -> null
                    else -> "https://$extracted"
                }
            }
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val memo = hoster.memo
        val name = memo["name"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val path = memo["path"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val ep = memo["ep"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val originalLink = memo["orig"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val url = resolveServer(name, hoster.hosterUrl, originalLink)
            ?.takeIf { it.isNotBlank() && !it.startsWith("<") }
            ?: return emptyList()

        val headers = if (name.uppercase() == "KF") {
            Headers.headersOf("Referer", "https://krakenfiles.com/", "User-Agent", "Mozilla/5.0")
        } else {
            Headers.headersOf("User-Agent", "Mozilla/5.0")
        }

        return listOf(
            Video(
                videoUrl = url,
                videoTitle = hoster.hosterName,
                resolution = hoster.hosterName.filter { it.isDigit() }
                    .takeIf { it.isNotBlank() }
                    ?.toIntOrNull(),
                headers = headers,
            ),
        )
    }
}
