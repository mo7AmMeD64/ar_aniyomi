package eu.kanade.tachiyomi.animeextension.ar.animeday

import android.net.Uri
import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.get
import extensions.utils.post
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder
import kotlin.random.Random

/**
 * AnimeDay hosters. The API only returns embed links, so each one is resolved here.
 * Ported from the CloudStream extension's extractor suite, with the unavailable
 * CloudStream global extractor replaced by the site's own PHP extraction chain.
 */
internal object AnimeDayExtractors {

    const val UA = "okhttp/4.10.0"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
    private const val PHP_BASE = "https://cloud-day.online/cimacloud"
    private const val HEAD_BYTES = 128 * 1024

    private const val PHOTO_REFERER = "https://photos.google.com/"
    private const val PHOTO_ORIGIN = "https://photos.google.com"
    private const val DASH_OPTION =
        "=mm,dash-vm-vf,dr.sdr,sdrCodec.vp9.h264?alr=true&mpd_version=5&pacing=0"

    private val PHP_ENDPOINTS = listOf(
        "$PHP_BASE/extractor.php",
        "$PHP_BASE/html_extractor.php",
        "$PHP_BASE/advanced_extractor.php",
    )
    private val SEEKABLE = listOf("=m37", "=m22", "=m18")

    private val PW_RE = Regex("""https://lh3\.googleusercontent\.com/pw/[^=?"'\s\\]+""")
    private val HTML_RE = Regex("""data-url="(https://lh3\.googleusercontent\.com/pw/[^\s"?]+)""")
    private val WRAP_RE = Regex("[?&]url=([^&]+)")

    private val DIRECT_RE = Regex("""(?i)\.(?:m3u8|mp4|mkv|mpd|webm)(?:[?#]|$)|\bmaster\.txt\b""")
    private val STREAM_RE =
        Regex("""https?://[^\s"'<>\\]+?(?:\.(?:m3u8|mp4|mkv|mpd)\b|\bmaster\.txt\b)[^\s"'<>\\]*""")
    private val IFRAME_RE = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val PACKER_EVAL_RE = Regex(
        """(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
    )
    private val PACKER_WORD_RE = Regex("""\b[a-zA-Z0-9_]+\b""")
    private val PACKER_STREAM_RE =
        Regex("""[:=]\s*"([^"\s]+(?:\.m3u8|master\.txt)[^"\s]*)""")
    private val UQLOAD_CODE_RE = Regex("""/(?:e|d|embed)/([A-Za-z0-9]+)""")
    private val DOOD_PASS_RE = Regex("""/pass_md5/[^'"]+""")
    private val VK_STREAM_RE = Regex(""""(mp4_\d{3,4}|hls)":"(https:\\/\\/[^"]+)"""")
    private val MIXDROP_WURL_RE = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""")
    private val VO_REDIRECT_RE = Regex("""window\.location\.href\s*=\s*'([^']+)'""")
    private val VO_JSON_RE = Regex("""(?is)<script[^>]+type=["']application/json["'][^>]*>(.*?)</script>""")
    private val INERTIA_PAGE_RE = Regex("""(?is)<script[^>]*data-page=["']app["'][^>]*>(.*?)</script>""")
    private val RESOLUTION_RE = Regex("""(\d{3,4})p""")
    private val SKIP_HOSTS = listOf("test-videos.co.uk", "acek-cdn.com")
    private val VOE_PATTERNS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    private val FHD_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/"
    private const val FHD_KEY1 = "V2@%YSU2B]G~"
    private const val FHD_KEY2 = "bv0fim4qf17"

    private data class Page(val body: String, val url: String, val cookies: List<String> = emptyList())

    private fun isGooglePhotosLink(link: String): Boolean = link.contains("photos.google.com") || link.contains("photos.app.goo.gl") ||
        link.contains("googleusercontent.com") || link.contains("googlefinal") ||
        link.contains("hrrejhp")

    suspend fun resolve(
        client: OkHttpClient,
        link: String,
        referer: String,
        label: String,
        quality: Int?,
    ): List<Video> {
        val clean = link.trim().replace("\\s+".toRegex(), "")
        if (clean.isEmpty()) return emptyList()
        return try {
            resolveAt(client, clean, referer, label, quality, 0).distinctBy { it.videoUrl }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun resolveAt(
        client: OkHttpClient,
        rawUrl: String,
        referer: String,
        label: String,
        quality: Int?,
        depth: Int,
    ): List<Video> {
        if (depth > 2) return emptyList()
        val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
        val host = url.toHttpUrlOrNull()?.host.orEmpty()

        if (isGooglePhotosLink(url)) return googlePhotos(client, url, label, quality)
        // These hosts put a .mp4-looking path on an embed page, not a media file.
        if (DIRECT_RE.containsMatchIn(url) && !host.contains("bysesukior") && !host.contains("byseraguci")) {
            return listOf(video(url, label, quality, referer))
        }

        return when {
            host.contains("uqload") || host.contains("rubyvidhub") || host.contains("savefiles") ->
                uqload(client, url, referer, label)

            host.contains("dood") || host.contains("playmogo") ||
                host.contains("ds2play") || host.contains("goodstream") ->
                dood(client, url, referer, label)

            host.contains("mixdrop") || host.contains("mxdrop") -> mixdrop(client, url, referer, label)

            host.contains("voe.") -> voe(client, url, referer, label)

            host.contains("megamax") || host.contains("share4max") ->
                share4max(client, url, referer, label, depth)

            host.contains("vidspeed") -> packer(client, url, referer, label)

            host.contains("fasel-hd") -> faselHd(client, url, referer, label, depth)

            host.contains("vkvideo") || host == "vk.com" || host.endsWith(".vk.com") ->
                vk(client, url, referer, label)

            host.contains("test-stream") || host.contains("developer-pro.workers.dev") ->
                jsonQualities(client, url, referer, label)

            host.contains("lulustream") || host.contains("luluvdo") ||
                host.contains("1vid") || host.contains("hgplaycdn") ||
                host.contains("vidmoly") || host.contains("ok.ru") ||
                host.contains("bysesukior") || host.contains("byseraguci") ->
                pageStreams(client, url, referer, label, depth)

            else -> unknown(client, url, referer, label, quality, depth)
        }
    }

    private suspend fun unknown(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
        quality: Int?,
        depth: Int,
    ): List<Video> {
        val php = phpChain(client, url, referer, label, quality, depth)
        if (php.isNotEmpty()) return php
        return pageStreams(client, url, referer, label, depth)
    }

    // ------------------------------------------------------------------ pages

    private suspend fun fetch(
        client: OkHttpClient,
        url: String,
        headers: Headers,
    ): Page? = try {
        val response = client.get(url.toHttpUrl(), headers, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            null
        } else {
            val finalUrl = response.request.url.toString()
            val cookies = response.headers.values("Set-Cookie").map { it.substringBefore(';') }
            val body = response.use { it.body.string() }
            Page(body, finalUrl, cookies.filter { it.isNotBlank() })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun fetchText(client: OkHttpClient, url: String, headers: Headers): String? {
        val page = fetch(client, url, headers) ?: return null
        return page.body
    }

    private suspend fun postForm(
        client: OkHttpClient,
        url: String,
        headers: Headers,
        form: FormBody,
    ): String? = try {
        val response = client.post(url.toHttpUrl(), headers, form, ensureSuccess = false)
        if (response.isSuccessful) {
            response.use { it.body.string() }
        } else {
            response.close()
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun headers(referer: String?, extra: Map<String, String> = emptyMap()): Headers {
        val builder = Headers.Builder().add("User-Agent", BROWSER_UA)
        if (!referer.isNullOrBlank()) builder.add("Referer", referer)
        extra.forEach { (key, value) -> builder.set(key, value) }
        return builder.build()
    }

    private fun pageHeaders(referer: String?): Headers = headers(
        referer,
        mapOf(
            "Accept-Language" to "ar,en;q=0.9",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        ),
    )

    private fun absolute(url: String, base: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> base.toHttpUrlOrNull()?.resolve(url)?.toString() ?: url
    }

    private fun video(url: String, title: String, quality: Int?, referer: String?): Video = Video(
        videoUrl = url,
        videoTitle = title,
        resolution = quality,
        headers = headers(referer),
    )

    private suspend fun pageStreams(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
        depth: Int,
    ): List<Video> {
        val page = fetch(client, url, pageHeaders(referer)) ?: return emptyList()
        jsonQualitiesFrom(page.body, label, page.url)?.let { if (it.isNotEmpty()) return it }
        val streams = streamsFrom(page.body, label, page.url)
        if (streams.isNotEmpty()) return streams

        val iframe = IFRAME_RE.find(page.body)?.groupValues?.get(1) ?: return emptyList()
        return resolveAt(client, absolute(iframe, page.url), page.url, label, null, depth + 1)
    }

    /** Some hosters return a small JSON manifest instead of an embed page. */
    private suspend fun jsonQualities(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
    ): List<Video> {
        val page = fetch(client, url, pageHeaders(referer)) ?: return emptyList()
        return jsonQualitiesFrom(page.body, label, page.url).orEmpty()
    }

    private fun jsonQualitiesFrom(body: String, label: String, pageUrl: String): List<Video>? {
        val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
        val qualities = json.optJSONArray("availableQualities") ?: return null
        val found = mutableListOf<Video>()
        for (i in 0 until qualities.length()) {
            val item = qualities.optJSONObject(i) ?: continue
            val streamUrl = item.optString("url").trim()
            if (streamUrl.isEmpty()) continue
            val qualityLabel = item.optString("quality").ifBlank { item.optString("label") }.ifBlank { "تلقائي" }
            val resolution = RESOLUTION_RE.find(qualityLabel)?.groupValues?.get(1)?.toIntOrNull()
            found += video(streamUrl, "$label · $qualityLabel", resolution, pageUrl)
        }
        return found
    }

    private fun streamsFrom(html: String, label: String, referer: String): List<Video> {
        val text = (html + "\n" + (unPacker(html) ?: ""))
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
        return STREAM_RE.findAll(text)
            .map { it.value }
            .filterNot { link -> SKIP_HOSTS.any { it in link } }
            .distinct()
            .map { video(it, "$label · مباشر", null, referer) }
            .toList()
    }

    // ------------------------------------------------------------------ packer / uqload

    private fun unPacker(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val match = PACKER_EVAL_RE.find(html) ?: return null
        val payload = match.groupValues[1].replace("\\'", "'")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val symtab = match.groupValues[4].split("|")
        if (symtab.size != count) return null
        val decoded = StringBuilder(payload)
        var offset = 0
        PACKER_WORD_RE.findAll(payload).forEach { word ->
            val x = runCatching { word.value.toInt(radix) }.getOrNull() ?: return@forEach
            val value = symtab.getOrNull(x).takeIf { !it.isNullOrEmpty() } ?: return@forEach
            decoded.setRange(word.range.first + offset, word.range.last + 1 + offset, value)
            offset += value.length - word.value.length
        }
        return decoded.toString()
    }

    private fun packerStream(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val decoded = unPacker(html) ?: html
        return PACKER_STREAM_RE.find(decoded)?.groupValues?.get(1)
            ?: STREAM_RE.find(decoded.replace("\\/", "/"))?.value
    }

    private suspend fun packer(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
    ): List<Video> {
        val page = fetch(client, url, pageHeaders(referer)) ?: return emptyList()
        val stream = packerStream(page.body) ?: return emptyList()
        return listOf(video(stream, "$label · HLS", null, page.url))
    }

    private suspend fun uqload(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
    ): List<Video> {
        val page = fetch(client, url, pageHeaders(referer)) ?: return emptyList()
        val code = UQLOAD_CODE_RE.find(page.url)?.groupValues?.get(1)
            ?: page.url.substringAfterLast("/").substringBefore("?")
                .substringBefore(".").substringAfter("-").takeIf { it.isNotBlank() }
            ?: return emptyList()
        val origin = page.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return emptyList()

        val form = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", code)
            .add("auto", "1")
            .add("referer", referer)
            .build()
        val requestHeaders = pageHeaders(page.url).newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        val body = postForm(client, "$origin/dl", requestHeaders, form)
            ?: return packer(client, url, referer, label)
        val streams = streamsFrom(body, label, page.url)
        return streams.ifEmpty { packer(client, url, referer, label) }
    }

    // ------------------------------------------------------------------ DoodStream

    private suspend fun dood(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
    ): List<Video> {
        val page = fetch(client, url, pageHeaders(referer)) ?: return unknown(client, url, referer, label, null, 1)
        val path = DOOD_PASS_RE.find(page.body)?.value
            ?: return pageStreams(client, url, referer, label, 1)
        val origin = page.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return emptyList()
        val cookie = page.cookies.joinToString("; ")
        val tokenHeaders = headers(page.url).newBuilder()
            .add("Accept", "*/*")
            .add("Origin", origin)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .apply { if (cookie.isNotEmpty()) add("Cookie", cookie) }
            .build()
        val tokenBody = fetchText(client, "$origin$path", tokenHeaders)?.trim().orEmpty()
        if (tokenBody.isEmpty() || tokenBody == "RELOAD") return emptyList()

        val token = path.substringAfterLast('/')
        val suffix = (1..10).map { ALNUM[Random.nextInt(ALNUM.length)] }.joinToString("")
        val streamUrl = "$tokenBody$suffix?token=$token&expiry=${System.currentTimeMillis()}"
        return listOf(video(streamUrl, "$label · مباشر", null, page.url))
    }

    // ------------------------------------------------------------------ MixDrop / Voe

    private suspend fun mixdrop(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
    ): List<Video> {
        val page = fetch(client, url, pageHeaders(referer)) ?: return emptyList()
        val text = (unPacker(page.body) ?: page.body).replace("\\/", "/")
        val raw = MIXDROP_WURL_RE.find(text)?.groupValues?.get(1) ?: return emptyList()
        val streamUrl = absolute(raw.trim(), page.url)
        return if (DIRECT_RE.containsMatchIn(streamUrl)) {
            listOf(video(streamUrl, "$label · MP4", null, page.url))
        } else {
            emptyList()
        }
    }

    private suspend fun voe(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
    ): List<Video> {
        val first = fetch(client, url, pageHeaders(referer)) ?: return emptyList()
        val redirect = VO_REDIRECT_RE.find(first.body)?.groupValues?.get(1)
        val page = if (redirect != null) {
            fetch(client, absolute(redirect, first.url), pageHeaders(first.url)) ?: return emptyList()
        } else {
            first
        }

        val raw = VO_JSON_RE.find(page.body)?.groupValues?.get(1)?.trim() ?: return emptyList()
        val encoded = raw.substringAfter("[\"", raw).substringBeforeLast("\"]", raw)
        val decrypted = decryptVoe(encoded) ?: return emptyList()
        val json = runCatching { JSONObject(decrypted) }.getOrNull() ?: return emptyList()
        val voeReferer = "https://voe.sx/"

        return listOfNotNull(
            json.optString("source").takeIf { it.isNotBlank() }
                ?.let { video(it, "$label · HLS", null, voeReferer) },
            json.optString("direct_access_url").takeIf { it.isNotBlank() }
                ?.let { video(it, "$label · MP4", null, voeReferer) },
        )
    }

    private fun decryptVoe(encoded: String): String? = runCatching {
        var text = encoded.map { c ->
            when {
                c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                else -> c
            }
        }.joinToString("")
        VOE_PATTERNS.forEach { text = text.replace(it, "_") }
        text = text.replace("_", "")

        val shifted = String(voeBase64(text), Charsets.ISO_8859_1)
            .map { (it.code - 3).toChar() }
            .joinToString("")
            .reversed()

        String(voeBase64(shifted), Charsets.UTF_8)
    }.getOrNull()

    private fun voeBase64(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return runCatching { Base64.decode(padded, Base64.DEFAULT) }
            .getOrElse { Base64.decode(padded, Base64.URL_SAFE) }
    }

    // ------------------------------------------------------------------ Megamax / Share4Max

    private val MIRROR_ORDER = listOf(
        "voe", "lulustream", "mixdrop", "doodstream", "dood", "ds2play",
        "savefiles", "streamtape", "mp4upload", "rubyvidhub", "playmogo",
    )

    private fun mirrorRank(driver: String): Int = MIRROR_ORDER.indexOf(driver).let { if (it < 0) MIRROR_ORDER.size else it }

    private suspend fun share4max(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
        depth: Int,
    ): List<Video> {
        val page = fetch(client, url, pageHeaders(referer)) ?: return emptyList()
        val data = INERTIA_PAGE_RE.find(page.body)?.groupValues?.get(1) ?: return emptyList()
        val pageJson = runCatching { JSONObject(data) }.getOrNull() ?: return emptyList()
        val version = pageJson.optString("version")
        val requestHeaders = pageHeaders(page.url).newBuilder()
            .add("X-Inertia", "true")
            .add("X-Inertia-Partial-Component", "files/mirror/video")
            .add("X-Inertia-Partial-Data", "streams")
            .add("X-Inertia-Version", version)
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/plain, */*")
            .build()
        val body = fetchText(client, page.url, requestHeaders) ?: return emptyList()
        val streams = runCatching {
            JSONObject(body).optJSONObject("props")
                ?.optJSONObject("streams")
                ?.optJSONArray("data")
        }.getOrNull() ?: return emptyList()

        val found = mutableListOf<Video>()
        for (i in 0 until streams.length()) {
            val item = streams.optJSONObject(i) ?: continue
            val qualityLabel = item.optString("label").ifBlank { item.optString("resolution") }
            val resolution = item.optString("resolution").substringAfter("x", "").toIntOrNull()
                ?: RESOLUTION_RE.find(qualityLabel)?.groupValues?.get(1)?.toIntOrNull()
            val mirrors = item.optJSONArray("mirrors") ?: continue
            val ordered = (0 until mirrors.length()).mapNotNull { index ->
                val mirror = mirrors.optJSONObject(index) ?: return@mapNotNull null
                val link = mirror.optString("link").trim()
                if (link.isEmpty()) null else mirror.optString("driver") to absolute(link, page.url)
            }.sortedBy { mirrorRank(it.first) }.take(6)
            val sourceLabel = if (qualityLabel.isBlank()) label else "$label · $qualityLabel"

            for ((_, mirrorUrl) in ordered) {
                val resolved = resolveAt(client, mirrorUrl, page.url, sourceLabel, resolution, depth + 1)
                if (resolved.isNotEmpty()) {
                    found += resolved
                    break
                }
            }
        }
        return found
    }

    // ------------------------------------------------------------------ VK video

    private suspend fun vk(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
    ): List<Video> {
        val page = fetch(client, url.replace("vkvideo.ru", "vk.com"), pageHeaders(referer))
            ?: return emptyList()
        return VK_STREAM_RE.findAll(page.body).mapNotNull { match ->
            val key = match.groupValues[1]
            val quality = key.removePrefix("mp4_").takeIf { key != "hls" }?.toIntOrNull()
            video(
                match.groupValues[2].replace("\\/", "/"),
                "$label · ${quality?.let { "${it}p" } ?: "HLS"}",
                quality,
                "https://vk.com/",
            )
        }.toList()
    }

    // ------------------------------------------------------------------ Google Photos

    private suspend fun googlePhotos(
        client: OkHttpClient,
        link: String,
        label: String,
        quality: Int?,
    ): List<Video> {
        val base = resolveGphotoBase(client, link, 0) ?: return emptyList()
        val photoHeaders = mapOf(
            "Origin" to PHOTO_ORIGIN,
            "Referer" to PHOTO_REFERER,
            "User-Agent" to BROWSER_UA,
        )
        val found = mutableListOf<Video>()

        for (suffix in SEEKABLE) {
            val url = base + suffix
            val size = probe(client, url) ?: continue
            found += Video(
                videoUrl = url,
                videoTitle = "$label · MP4 ${size.second}p",
                resolution = size.second,
                headers = headers(PHOTO_REFERER, photoHeaders),
            )
        }

        if (found.isEmpty()) {
            val adaptive = base + DASH_OPTION + "&_=" + System.currentTimeMillis().toString(36)
            found += Video(
                videoUrl = adaptive,
                videoTitle = "$label · DASH تلقائي",
                resolution = quality,
                headers = headers(PHOTO_REFERER, photoHeaders),
            )
        }
        return found
    }

    private fun gphotoBase(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return PW_RE.find(url.trim())?.value
    }

    private suspend fun resolveGphotoBase(
        client: OkHttpClient,
        raw: String,
        depth: Int,
    ): String? {
        if (depth > 2) return null
        val url = raw.trim()
        gphotoBase(url)?.let { return it }

        WRAP_RE.find(url)?.groupValues?.get(1)
            ?.let { runCatching { Uri.decode(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != url }
            ?.let { return resolveGphotoBase(client, it, depth + 1) }

        if (url.contains("photos.google.com") || url.contains("photos.app.goo.gl")) {
            val html = fetchText(client, url, pageHeaders(null)) ?: return null
            val nested = HTML_RE.find(html)?.groupValues?.get(1) ?: return null
            return gphotoBase(nested)
        }
        return null
    }

    private suspend fun probe(client: OkHttpClient, url: String): Pair<Int, Int>? = try {
        val requestHeaders = Headers.Builder()
            .add("User-Agent", BROWSER_UA)
            .add("Accept", "*/*")
            .add("Range", "bytes=0-$HEAD_BYTES")
            .add("Referer", PHOTO_REFERER)
            .add("Origin", PHOTO_ORIGIN)
            .build()
        val response = client.get(url.toHttpUrl(), requestHeaders, ensureSuccess = false)
        if (response.code != 200 && response.code != 206) {
            response.close()
            null
        } else {
            val bytes = response.use { it.body.byteStream().use { input -> readCapped(input, HEAD_BYTES) } }
            fromTkhd(bytes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val read = input.read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun fromTkhd(data: ByteArray?): Pair<Int, Int>? {
        if (data == null || data.size < 108) return null
        val needle = byteArrayOf('t'.code.toByte(), 'k'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())
        var index = indexOf(data, needle)
        while (index != -1) {
            val version = data[index + 4].toInt() and 0xFF
            val widthOffset = if (version == 1) index + 92 else index + 80
            val heightOffset = widthOffset + 4
            if (heightOffset + 4 <= data.size) {
                val width = readUInt32(data, widthOffset) ushr 16
                val height = readUInt32(data, heightOffset) ushr 16
                if (width in 16..7680 && height in 16..4320) return width to height
            }
            index = indexOf(data, needle, index + 1)
        }
        return null
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..(data.size - needle.size)) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------ FaselHD

    private fun faselIndex(char: Char): Int = FHD_ALPHABET.indexOf(char).let { if (it < 0) 0 else it }

    private fun faselDecrypt(value: String, key: String): String {
        val out = StringBuilder(value.length)
        for (i in value.indices) {
            var diff = faselIndex(value[i]) - faselIndex(key[i % (key.length - 1)])
            if (diff < 0) diff += 64
            out.append(FHD_ALPHABET[diff])
        }
        return out.toString()
    }

    private fun faselUrl(enc: String): String = faselDecrypt(faselDecrypt(enc.removePrefix("enc:"), FHD_KEY2), FHD_KEY1)

    private fun faselPlayerUrls(html: String): List<String> {
        val out = linkedSetOf<String>()
        val blocked = listOf("recaptcha", "google.com/ads", "googlesyndication.com", "googletagmanager.com")
        fun add(value: String) {
            val clean = value.replace("&amp;", "&").trim()
            if (clean.isNotBlank() && blocked.none { clean.contains(it) }) out.add(clean)
        }
        Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .forEach { add(it.groupValues[1]) }
        Regex("""(?i)<iframe\b[^>]*\b(?:src|data-src)\s*=\s*["']([^"']+)["'][^>]*>""")
            .findAll(html)
            .forEach {
                val src = it.groupValues[1]
                if (src.contains("video_player") || src.contains("embed") || src.contains("player")) add(src)
            }
        return out.toList()
    }

    private suspend fun faselHd(
        client: OkHttpClient,
        pageUrl: String,
        referer: String,
        label: String,
        depth: Int,
    ): List<Video> {
        val clean = if (!pageUrl.contains("?") && !pageUrl.endsWith("/") && !pageUrl.substringAfterLast("/").contains(".")) {
            "$pageUrl/"
        } else {
            pageUrl
        }
        val page = fetch(client, clean, pageHeaders(referer)) ?: return emptyList()
        val found = mutableListOf<Video>()
        for (raw in faselPlayerUrls(page.body)) {
            val playerUrl = raw.replace("&amp;", "&")
            if (playerUrl.toHttpUrlOrNull()?.host?.contains("fasel-hd") == true) {
                val player = fetch(client, playerUrl, pageHeaders(pageUrl)) ?: continue
                val enc = Regex("""enc:[A-Za-z0-9+/=_%]+""").find(player.body)?.value
                val direct = enc?.let { faselUrl(it) }?.takeIf { it.startsWith("http") }
                    ?: Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
                        .find(player.body)?.value?.replace("&amp;", "&")
                if (!direct.isNullOrBlank()) found += video(direct, "$label (FaselHD)", null, playerUrl)
            } else {
                found += resolveAt(client, playerUrl, pageUrl, label, null, depth + 1)
            }
        }
        return found
    }

    // ------------------------------------------------------------------ PHP extractor chain

    private suspend fun phpChain(
        client: OkHttpClient,
        pageUrl: String,
        referer: String,
        label: String,
        quality: Int?,
        depth: Int,
    ): List<Video> {
        val out = mutableListOf<Video>()
        val encoded = URLEncoder.encode(pageUrl, "UTF-8")
        for (endpoint in PHP_ENDPOINTS) {
            val body = fetchText(
                client,
                "$endpoint?url=$encoded",
                headers(referer).newBuilder().add("Accept-Language", "ar,en;q=0.9").build(),
            ) ?: continue
            val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: continue
            val servers = json.optJSONArray("servers") ?: continue

            for (i in 0 until servers.length()) {
                val server = servers.optJSONObject(i) ?: continue
                val url = server.optString("url").trim()
                val serverReferer = server.optString("referer")
                    .ifEmpty { server.optString("origin") }
                    .ifEmpty { referer }
                val serverLabel = server.optString("name").ifEmpty { label }
                val serverQuality = server.optString("height").toIntOrNull() ?: quality

                if (url.isNotEmpty()) {
                    when {
                        isGooglePhotosLink(url) -> out += googlePhotos(client, url, serverLabel, serverQuality)

                        DIRECT_RE.containsMatchIn(url) ->
                            out += video(url, serverLabel, serverQuality, serverReferer)

                        url.toHttpUrlOrNull()?.host?.contains("vidspeed") == true ->
                            out += packer(client, url, serverReferer, serverLabel)

                        url.toHttpUrlOrNull()?.host?.contains("fasel-hd") == true ->
                            out += faselHd(client, url, serverReferer, serverLabel, depth + 1)

                        else -> out += video(url, serverLabel, serverQuality, serverReferer)
                    }
                }

                val download = server.optString("url_download").trim()
                if (download.isNotEmpty()) {
                    out += video(download, "$serverLabel (تحميل)", serverQuality, serverReferer)
                }
            }
        }
        return out
    }

    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
}
