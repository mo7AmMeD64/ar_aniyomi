package eu.kanade.tachiyomi.animeextension.ar.cimacloud

import android.util.Base64
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
import extensions.utils.post
import extensions.utils.toJsonBody
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import java.net.URI
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import androidx.preference.PreferenceScreen

class CimaCloud : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================ Properties ==============================

    override val name = "CimaCloud"

    override val lang = "ar"

    override val baseUrl = "https://1654865.xyz"

    override val supportsLatest = true

    private val api = "/v1.3/api"

    private val configUrl = "https://cloud-day.online/v1.3/CloudDay/uploads/config_v1.3.json"

    private val extractorUrl = "https://cloud-day.online/cimacloud/extractor.php?url="

    private val uaOkhttp = "okhttp/4.10.0"

    private val uaChrome =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.77 Safari/537.36"

    private val secureRandom = SecureRandom()

    private val deviceId: String = buildString { repeat(16) { append("0123456789abcdef"[secureRandom.nextInt(16)]) } }

    // ------------------------------------------------------------ attestation

    private val attestAppId = "21406135"

    private val attestCertPath =
        "021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7" +
            "|/data/app/com.app.cimacloud-1/base.apk"

    private val rsaPubB64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+6" +
            "+BtHI/Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3Q" +
            "Ch65Nbi4mKRFFJyDkJVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9F" +
            "tgJkuz17n265rLB6ruUUrHQVeEDl8ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvs" +
            "q4Lhuthcts6gtVq3uPExSKdLBpbqbwQNvhRgB3IEYV47OyddG32qbriJbxdvW+n5OzowiYQr" +
            "sC8hG65GPBGCuj3zO7wIDAQAB"

    // -------------------------------------------------------- servers crypto

    private val keyH0 = byteArrayOf(
        0x1d, 0x51, 0x45, 0x47, 0x04, 0x5d, 0x08, 0x23,
        0x6a, 0x7b, 0x1d, 0x1d, 0x46, 0x5e, 0x20, 0x7e,
    )

    private val keyI0 = byteArrayOf(0x5a, 0x3c, 0x71, 0x2e, 0x68, 0x1d, 0x4f, 0x13)

    private val keyBase: String = buildString {
        for (i in keyH0.indices) {
            append(((keyH0[i].toInt() xor keyI0[i % keyI0.size].toInt()) and 0xFF).toChar())
        }
    }

    // ------------------------------------------------------------ JW domains

    private val jwEmbedDomains = listOf(
        "egybestvid", "vidspeed", "anamov", "anafast", "aflam", "aflaam",
        "extreamnow", "mp4plus", "vidoba", "mirvad", "mwdy", "vidmoly",
        "vinovo", "fastvid", "vids.st",
    )

    private val extractorPhpDomains = listOf(
        "vidtube", "fasel", "ukrcdn", "upns", "uns.bio", "videoland",
        "rpmhub", "lalalala", "web51018x", "web71512x",
    )

    // =============================== Sections ==============================

    private data class Section(
        val title: String,
        val type: String,
        val category: String? = null,
    )

    private val sections = listOf(
        Section("أحدث الإضافات", "0", null),
        Section("أحدث الأفلام", "1", null),
        Section("أحدث المسلسلات", "2", null),
        Section("أنمي وكرتون", "2", "5"),
        Section("أفلام أجنبية", "1", "4"),
        Section("مسلسلات أجنبية", "2", "3"),
        Section("أفلام تركية", "1", "2"),
        Section("مسلسلات تركية", "2", "1"),
        Section("أفلام كورية", "1", "8"),
        Section("مسلسلات كورية", "2", "7"),
        Section("أفلام هندية", "1", "10"),
        Section("مسلسلات هندية", "2", "9"),
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

    private fun JsonObject.tags(): String? = arr("genres")
        .mapNotNull { it.str("name")?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

    private fun JsonObject.cats(): List<Int> = arr("categories").mapNotNull { it.int("id") }

    private fun hostOf(url: String): String? = try { URI(url).host?.lowercase() } catch (_: Throwable) { null }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    // =============================== Crypto ================================

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun makeFirebaseId(): String {
        val payload = "{\"a\":\"$attestAppId\",\"b\":\"$attestCertPath\"," +
            "\"c\":\"false\",\"d\":\"empty\",\"e\":\"empty\",\"f\":\"false\"," +
            "\"g\":\"false\",\"h\":\"false\",\"t\":${System.currentTimeMillis() / 1000}," +
            "\"n\":\"${UUID.randomUUID().toString().replace("-", "").substring(0, 16)}\"}"
        val aesKey = ByteArray(32).also(secureRandom::nextBytes)
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = gcm.doFinal(payload.toByteArray(Charsets.UTF_8))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(
            Cipher.ENCRYPT_MODE,
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(rsaPubB64, Base64.NO_WRAP))),
        )
        val wrappedKey = rsa.doFinal(aesKey)
        return Base64.encodeToString(wrappedKey, Base64.NO_WRAP) + "." +
            Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun makeCloudflareId(): String =
        buildString {
            repeat(15) { append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[secureRandom.nextInt(62)]) }
            append(deviceId)
        }

    private fun decryptServers(b64Body: String): List<String>? = try {
        val ciphertext = Base64.decode(b64Body.trim(), Base64.DEFAULT)
        val key = md5Hex(keyBase + deviceId).toByteArray(Charsets.UTF_8)
        val iv = md5Hex(deviceId + keyBase).substring(0, 16).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            String(cipher.doFinal(ciphertext), Charsets.UTF_8),
        ).let { it as? JsonObject } ?: return null
        val servers = json["servers"] as? JsonArray ?: return null
        servers.mapNotNull { el ->
            (el as? JsonObject)?.str("link")?.takeIf { it.startsWith("http") }
        }.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    // =============================== Network ==============================

    private var apiBaseCache: String? = null

    private suspend fun apiBase(): String {
        apiBaseCache?.let { return it }
        return try {
            val cfg = client.get(configUrl.toHttpUrl(), Headers.headersOf("User-Agent", uaOkhttp))
                .parseAs<JsonObject>()
            val base = cfg.arr("data").firstOrNull()
                ?.str("cloudday")?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
            (base ?: baseUrl).also { apiBaseCache = it }
        } catch (_: Throwable) {
            baseUrl
        }
    }

    private suspend fun apiGet(path: String, extraHeaders: Map<String, String> = emptyMap()): JsonObject? = try {
        val headers = Headers.headersOf(
            "User-Agent",
            uaOkhttp,
            *extraHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray(),
        )
        client.get("${apiBase()}$api/$path".toHttpUrl(), headers).parseAs<JsonObject>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun apiGetRaw(path: String, extraHeaders: Map<String, String> = emptyMap()): String? = try {
        val headers = Headers.headersOf(
            "User-Agent",
            uaOkhttp,
            *extraHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray(),
        )
        client.get("${apiBase()}$api/$path".toHttpUrl(), headers).body.string()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun apiPostForm(path: String, form: Map<String, String>): JsonObject? = try {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        client.post(
            "${apiBase()}$api/$path".toHttpUrl(),
            Headers.headersOf("User-Agent", uaOkhttp),
            body,
        ).parseAs<JsonObject>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // =============================== Cards ================================

    private fun JsonObject.toSAnime(): SAnime? {
        val id = str("id") ?: return null
        val title = str("name")?.trim() ?: return null
        val poster = str("poster")?.takeIf { it.startsWith("http") }
        val dub = str("dub")?.takeIf { it != "false" }
        return SAnime.create().apply {
            url = "${str("type")?.lowercase()}/$id"
            this.title = if (dub != null) "$title ($dub)" else title
            thumbnail_url = poster
        }
    }

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        browse(sections[1], page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        browse(sections[0], page)

    // =============================== Search ===============================

    private class SectionFilter(values: Array<String>) : AnimeFilter.Select<String>("القسم", values)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SectionFilter(sections.map { it.title }.toTypedArray()),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val select = filters.filterIsInstance<SectionFilter>().firstOrNull()
        val section = sections.getOrNull(select?.state ?: 0) ?: sections[0]
        val q = query.trim()

        if (q.isEmpty()) return browse(section, page)

        val res = apiPostForm(
            "search",
            mapOf("title" to q, "type" to "0", "sort" to "1", "page" to page.toString()),
        ) ?: return AnimesPage(emptyList(), false)
        val items = res.arr("results").mapNotNull { it.toSAnime() }
        val totalPages = res.int("total_pages") ?: 1
        return AnimesPage(items, page < totalPages)
    }

    private suspend fun browse(section: Section, page: Int): AnimesPage {
        val form = mutableMapOf(
            "type" to section.type,
            "sort" to "1",
            "page" to page.toString(),
        )
        section.category?.let { form["category"] = it }
        val res = apiPostForm("all", form) ?: return AnimesPage(emptyList(), false)
        val items = res.arr("results").mapNotNull { it.toSAnime() }
        val totalPages = res.int("total_pages") ?: 1
        return AnimesPage(items, page < totalPages)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parts = anime.url.split("/", limit = 2)
        val kind = parts[0]
        val id = parts.getOrNull(1) ?: throw Exception("CimaCloud: bad url")

        val res = apiGet("$kind/$id", mapOf("firebase_id" to makeFirebaseId()))
            ?: throw Exception("CimaCloud: not found")
        val data = res.obj(kind) ?: res.obj("movie") ?: res.obj("series")
            ?: throw Exception("CimaCloud: no data")

        return anime.apply {
            title = data.str("name")?.trim() ?: title
            description = data.str("overview")?.trim()
            genre = data.tags()
            thumbnail_url = data.str("poster") ?: data.str("cover") ?: thumbnail_url
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parts = anime.url.split("/", limit = 2)
        val kind = parts[0]
        val id = parts.getOrNull(1) ?: return emptyList()

        if (kind == "movie") {
            return listOf(
                SEpisode.create().apply {
                    url = "movie/$id"
                    name = "فيلم"
                    episode_number = 1f
                },
            )
        }

        val res = apiGet("series/$id/episodes") ?: return emptyList()
        val seasons = res.arr("seasons")
        val episodes = mutableListOf<SEpisode>()
        for ((seasonIdx, season) in seasons.withIndex()) {
            val seasonNum = season.int("season_number") ?: (seasonIdx + 1)
            for ((epIdx, ep) in season.arr("episodes").withIndex()) {
                val epId = ep.str("id") ?: continue
                val epNum = Regex("""\d+""").find(ep.str("title") ?: "")?.value?.toIntOrNull()
                    ?: (epIdx + 1)
                episodes += SEpisode.create().apply {
                    url = "ep/$epId"
                    name = ep.str("title")?.trim()?.takeIf { it.isNotBlank() } ?: "الحلقة ${epIdx + 1}"
                    episode_number = epNum.toFloat()
                    if (seasons.size > 1 && seasonNum > 1) name = "موسم $seasonNum — $name"
                    preview_url = ep.str("cover") ?: ep.str("image")
                }
            }
        }
        return episodes
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("/", limit = 2)
        val kind = parts[0]
        val id = parts.getOrNull(1) ?: return emptyList()

        val path = when (kind) {
            "movie" -> "movie/$id/servers"
            "ep" -> "episode/$id/servers"
            else -> return emptyList()
        }

        val raw = apiGetRaw(
            path,
            mapOf(
                "firebase_id" to makeFirebaseId(),
                "cloudflare-id" to makeCloudflareId(),
            ),
        ) ?: return emptyList()

        val links = decryptServers(raw)
            ?: return emptyList()

        return links.map { link ->
            val host = hostOf(link) ?: "server"
            Hoster(
                hosterUrl = link,
                hosterName = "سيرفر ${host.removePrefix("www.").removePrefix("s1.").substringBefore('.')}",
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val link = hoster.hosterUrl
        val host = hostOf(link) ?: return emptyList()

        // Direct media files
        val clean = link.substringBefore("?").lowercase()
        if (clean.endsWith(".mp4") || clean.endsWith(".m3u8")) {
            return listOf(
                Video(
                    videoUrl = link,
                    videoTitle = hoster.hosterName,
                    headers = Headers.headersOf("User-Agent", uaChrome),
                ),
            )
        }

        // Developer's extractor.php
        if (extractorPhpDomains.any { host.contains(it) }) {
            resolveExtractorPhp(link, hoster.hosterName)?.let { return it }
        }

        // JW embeds (egybestvid, vidspeed, etc.)
        if (jwEmbedDomains.any { host.contains(it) }) {
            resolveJwEmbed(link, hoster.hosterName)?.let { return it }
        }

        // Generic fallback: try extracting video URLs from the page
        return resolveGeneric(link, hoster.hosterName) ?: emptyList()
    }

    // ------------------------- extractor.php ------------------------------

    private suspend fun resolveExtractorPhp(link: String, label: String): List<Video>? {
        val res = try {
            client.get(
                (extractorUrl + encode(link)).toHttpUrl(),
                Headers.headersOf("User-Agent", uaOkhttp),
            ).parseAs<JsonObject>()
        } catch (_: Throwable) {
            return null
        }
        val servers = res.arr("servers")
        if (servers.isEmpty()) return null
        return servers.mapNotNull { srv ->
            val url = srv.str("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val name = srv.str("name")?.trim()?.takeIf { it.isNotBlank() } ?: label
            Video(
                videoUrl = url,
                videoTitle = name,
                resolution = Regex("""(\d{3,4})p""").find(name)?.groupValues?.get(1)?.toIntOrNull(),
                headers = Headers.headersOf(
                    "User-Agent",
                    uaChrome,
                    "Referer",
                    srv.str("referer") ?: "",
                ),
            )
        }
    }

    // ---------------------------- JW embeds ------------------------------

    private val sourcesRegex = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""")
    private val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")

    private suspend fun resolveJwEmbed(embedUrl: String, label: String): List<Video>? {
        val page = try {
            client.get(
                embedUrl.toHttpUrl(),
                Headers.headersOf("User-Agent", uaChrome, "Referer", embedUrl),
            ).body.string()
        } catch (_: Throwable) {
            return null
        }

        var stream = sourcesRegex.find(page)?.groupValues?.get(1)
        if (stream == null) {
            val unpacked = unpackPackedJs(page)
            stream = unpacked?.let { fileRegex.find(it)?.groupValues?.get(1) }
                ?: fileRegex.find(page)?.groupValues?.get(1)
        }
        val url = stream
            ?.replace("""\/""", "/")
            ?.takeIf { it.startsWith("http") }
            ?: return null

        return listOf(
            Video(
                videoUrl = url,
                videoTitle = label,
                headers = Headers.headersOf("User-Agent", uaChrome, "Referer", embedUrl),
            ),
        )
    }

    // -------------------------- generic fallback -------------------------

    private suspend fun resolveGeneric(link: String, label: String): List<Video>? {
        val page = try {
            client.get(link.toHttpUrl(), Headers.headersOf("User-Agent", uaChrome)).body.string()
        } catch (_: Throwable) {
            return null
        }
        val patterns = listOf(
            sourcesRegex,
            fileRegex,
            Regex("""["'](https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)["']"""),
        )
        for (p in patterns) {
            val url = p.find(page)?.groupValues?.get(1)?.takeIf { it.startsWith("http") } ?: continue
            return listOf(
                Video(
                    videoUrl = url,
                    videoTitle = label,
                    headers = Headers.headersOf("User-Agent", uaChrome, "Referer", link),
                ),
            )
        }
        return null
    }

    // ------------------------ packed JS deobfuscation --------------------

    private fun unpackPackedJs(page: String): String? {
        val evalMatch = Regex(
            """eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(page) ?: return null
        val params = evalMatch.groupValues.getOrNull(1) ?: return null
        val paramMatch = Regex(
            """['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(params) ?: return null
        val (packed, baseStr, countStr, dictStr) = paramMatch.destructured
        return deobfuscate(packed, baseStr.toInt(), countStr.toInt(), dictStr.split('|'))
    }

    private fun deobfuscate(p: String, a: Int, c: Int, k: List<String>): String {
        fun toBase(num: Int, radix: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            if (num == 0) return "0"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(chars[n % radix])
                n /= radix
            }
            return sb.reverse().toString()
        }
        val replaceMap = mutableMapOf<String, String>()
        for (i in 0 until c) {
            val keyword = k.getOrNull(i)
            if (!keyword.isNullOrEmpty()) replaceMap[toBase(i, a)] = keyword
        }
        return Regex("""\b\w+\b""").replace(p) { m -> replaceMap[m.value] ?: m.value }
    }
}
