package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"

private data class HnMediaRef(
    val type: String,
    val id: Int,
    val season: Int? = null,
    val episode: Int? = null
)

private val HN_REF_REGEX =
    Regex("""^hnembed:(movie|tv):(\d+)(?::(\d+):(\d+))?$""")

private val HN_EMBED_PATH_REGEX =
    Regex("""^/embed/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?/?$""")

private val HN_LEGACY_PATH_REGEX =
    Regex("""^/hnembed:(movie|tv):(\d+)(?::(\d+):(\d+))?/?$""")

private val HN_STATIC_PL_REGEX =
    Regex("""/pl/([A-Za-z0-9_-]{30,})(?:/master\.m3u8)?/?(?:$|\?|#)""", RegexOption.IGNORE_CASE)

private val HN_ANY_PL_REGEX =
    Regex("""/pl[ci]?/[A-Za-z0-9_-]{30,}(?:/master\.m3u8)?/?(?:$|\?|#)""", RegexOption.IGNORE_CASE)

private fun rethrowIfCancellation(t: Throwable) {
    var cur: Throwable? = t
    while (cur != null) {
        if (cur.javaClass.name.endsWith("CancellationException")) {
            throw t
        }
        cur = cur.cause
    }
}

private fun parseHnRef(value: String): HnMediaRef? {
    val input = value.trim()
    val path = try {
        URI(input).path ?: input
    } catch (_: Exception) {
        input
    }

    val match = HN_REF_REGEX.matchEntire(input)
        ?: HN_EMBED_PATH_REGEX.matchEntire(path)
        ?: HN_LEGACY_PATH_REGEX.matchEntire(path)
        ?: return null

    return HnMediaRef(
        type = match.groupValues[1],
        id = match.groupValues[2].toIntOrNull() ?: return null,
        season = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull(),
        episode = match.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull()
    )
}

private fun hnDetailUrl(type: String, id: Int): String {
    return if (type == "movie") {
        "https://hnembed.cc/embed/movie/$id"
    } else {
        "https://hnembed.cc/embed/tv/$id/1/1"
    }
}

private fun resolveHttpUrl(baseUrl: String, raw: String?): String? {
    val value = raw
        ?.trim()
        ?.replace("&amp;", "&")
        ?.replace("\\u0026", "&")
        ?.replace("\\/", "/")
        ?.takeIf { it.isNotBlank() }
        ?: return null

    if (value.startsWith("javascript:", ignoreCase = true) ||
        value.startsWith("data:", ignoreCase = true) ||
        value.startsWith("blob:", ignoreCase = true)
    ) return null

    return try {
        val resolved = URI(baseUrl).resolve(value).toString()
        resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    } catch (_: Exception) {
        null
    }
}

private fun originOfUrl(rawUrl: String): String {
    return try {
        val u = URI(rawUrl)
        if (u.scheme != null && u.host != null) {
            val portPart = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme}://${u.host}$portPart"
        } else {
            ""
        }
    } catch (_: Exception) {
        ""
    }
}

private fun extractQueryParam(url: String, key: String): String? {
    val qIdx = url.indexOf('?')
    if (qIdx < 0) return null
    val query = url.substring(qIdx + 1).substringBefore('#')
    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        if (eq > 0 && part.substring(0, eq) == key) {
            return part.substring(eq + 1)
        }
    }
    return null
}

private fun appendTokenParam(url: String, tokenParamName: String, tokenValue: String): String {
    if (tokenValue.isBlank()) return url
    if (url.contains("__TOKEN__")) {
        return url.replace("__TOKEN__", tokenValue)
    }
    if (Regex("""[?&]${Regex.escape(tokenParamName)}=""").containsMatchIn(url)) {
        return url
    }
    val hashIdx = url.indexOf('#')
    val base = if (hashIdx >= 0) url.substring(0, hashIdx) else url
    val hash = if (hashIdx >= 0) url.substring(hashIdx) else ""
    val sep = if (base.contains('?')) "&" else "?"
    return "$base$sep$tokenParamName=$tokenValue$hash"
}

/**
 * Local loopback M3U8 proxy for HNEmbed static `/pl/` playlists when `/pli/` is unavailable:
 * stamps `?token=` (or `?cast_token=`) onto every child variant playlist and segment URI so
 * CloudStream ExoPlayer never hits HTTP 403 on child playlists or `.ts` chunks.
 */
private object HnHlsTokenLoopbackProxy {
    data class RouteEntry(
        val targetUrl: String,
        val origin: String,
        val tokenName: String,
        val tokenValue: String,
        val referer: String,
        val userAgent: String
    )

    private var serverSocket: ServerSocket? = null
    private var port: Int = -1
    private val seq = AtomicInteger(0)
    private val entries = ConcurrentHashMap<String, RouteEntry>()
    private val keyByParams = ConcurrentHashMap<String, String>()

    @Synchronized
    fun ensureStarted(): Int {
        if (port > 0 && serverSocket?.isClosed == false) return port
        return runCatching {
            val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            Thread {
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        Thread { handleClient(client) }.apply { isDaemon = true }.start()
                    } catch (_: Exception) {
                        if (ss.isClosed) break
                    }
                }
            }.apply { isDaemon = true }.start()
            port
        }.getOrDefault(-1)
    }

    fun register(
        targetUrl: String,
        origin: String,
        tokenName: String,
        tokenValue: String,
        referer: String,
        userAgent: String
    ): String? {
        val p = ensureStarted()
        if (p <= 0) return null
        val cacheKey = "$targetUrl|$tokenName=$tokenValue"
        keyByParams[cacheKey]?.let { existingId ->
            if (entries.containsKey(existingId)) {
                return "http://127.0.0.1:$p/hn_pl/$existingId.m3u8"
            }
        }
        if (entries.size > 400) {
            entries.clear()
            keyByParams.clear()
        }
        val id = seq.incrementAndGet().toString()
        entries[id] = RouteEntry(
            targetUrl = targetUrl,
            origin = origin,
            tokenName = tokenName,
            tokenValue = tokenValue,
            referer = referer,
            userAgent = userAgent
        )
        keyByParams[cacheKey] = id
        return "http://127.0.0.1:$p/hn_pl/$id.m3u8"
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val reqLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val path = parts.getOrNull(1) ?: ""
            val id = Regex("""/hn_pl/(\d+)\.m3u8""").find(path)?.groupValues?.getOrNull(1)
            val entry = id?.let { entries[it] }

            val out = socket.getOutputStream()
            if (id == null || entry == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val fetched = fetchPlaylistBlocking(entry)
            if (fetched == null) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val (effectiveUrl, rawBody) = fetched
            val rewritten = rewriteM3u8(rawBody, effectiveUrl, entry)
            val bytes = rewritten.toByteArray(Charsets.UTF_8)

            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            if (method != "HEAD") {
                out.write(bytes)
            }
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun fetchPlaylistBlocking(entry: RouteEntry): Pair<String, String>? {
        var current = entry.targetUrl
        for (hop in 0..3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", entry.userAgent)
                    setRequestProperty("Accept", "*/*")
                    if (entry.referer.isNotBlank()) {
                        setRequestProperty("Referer", entry.referer)
                        val refOrig = originOfUrl(entry.referer)
                        if (refOrig.isNotBlank()) setRequestProperty("Origin", refOrig)
                    }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")?.trim()
                    if (!loc.isNullOrBlank()) {
                        current = resolveHttpUrl(current, loc) ?: loc
                        continue
                    }
                }
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    return current to body
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun rewriteChildUrl(resolvedUrl: String, entry: RouteEntry, forcePlaylistRoute: Boolean): String {
        val withTok = if (entry.tokenValue.isNotBlank() && originOfUrl(resolvedUrl) == entry.origin) {
            appendTokenParam(resolvedUrl, entry.tokenName, entry.tokenValue)
        } else {
            resolvedUrl
        }

        val lower = resolvedUrl.lowercase()
        val isSubPlaylist = forcePlaylistRoute ||
            lower.contains(".m3u8") ||
            HN_ANY_PL_REGEX.containsMatchIn(resolvedUrl)

        return if (isSubPlaylist) {
            register(
                targetUrl = withTok,
                origin = entry.origin,
                tokenName = entry.tokenName,
                tokenValue = entry.tokenValue,
                referer = entry.referer,
                userAgent = entry.userAgent
            ) ?: withTok
        } else {
            withTok
        }
    }

    private fun rewriteM3u8(body: String, baseUrl: String, entry: RouteEntry): String {
        val out = StringBuilder(body.length + 512)
        val uriAttrRegex = Regex("URI=\"([^\"]+)\"")
        var nextIsStreamVariant = false

        val lines = body.replace("\r\n", "\n").split('\n')
        for ((idx, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (idx < lines.lastIndex) out.append('\n')
                continue
            }

            if (line.startsWith("#")) {
                val rewrittenTag = if (line.contains("URI=\"")) {
                    val isPlaylistTag = line.startsWith("#EXT-X-MEDIA", ignoreCase = true) ||
                        line.startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true)
                    uriAttrRegex.replace(line) { m ->
                        val resolved = resolveHttpUrl(baseUrl, m.groupValues[1])
                        if (resolved != null) {
                            "URI=\"${rewriteChildUrl(resolved, entry, isPlaylistTag)}\""
                        } else {
                            m.value
                        }
                    }
                } else {
                    line
                }
                out.append(rewrittenTag)
                nextIsStreamVariant = line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)
            } else {
                val resolved = resolveHttpUrl(baseUrl, line)
                if (resolved != null) {
                    out.append(rewriteChildUrl(resolved, entry, nextIsStreamVariant))
                } else {
                    out.append(line)
                }
                nextIsStreamVariant = false
            }

            if (idx < lines.lastIndex) out.append('\n')
        }
        return out.toString()
    }
}

class HnembedProvider : MainAPI() {
    override var mainUrl = "https://hnembed.cc"
    override var name = "Hnembed"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    private val tmdbHosts = listOf(
        TMDB_BASE_URL,
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val imdbCache = ConcurrentHashMap<Int, String>()

    private val blockedWebViewDomains = listOf(
        "disable-devtool",
        "acscdn.com",
        "rapidadexchange.com",
        "usrpubtrck.com",
        "histats.com",
        "static.cloudflareinsights.com",
        "googletagmanager.com",
        "google-analytics.com",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "popads.net",
        "onclickads.net",
        "propellerads.com",
        "popcash.net",
        "adsterra.com",
        "adcash.com",
        "rtmark.net"
    )

    private fun imageUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank() || path == "null") return null
        return "https://wsrv.nl/?url=image.tmdb.org/t/p/$size$path"
    }

    private suspend fun tmdbGet(path: String): JSONObject {
        var lastError: Exception? = null
        val separator = if (path.contains("?")) "&" else "?"

        for (host in tmdbHosts) {
            try {
                val response = app.get(
                    "$host$path${separator}api_key=$TMDB_API_KEY",
                    headers = mapOf("User-Agent" to userAgent),
                    timeout = 10L
                )

                if (response.code == 200) {
                    val body = response.text
                    if (body.trimStart().startsWith("{")) return JSONObject(body)
                }

                lastError = IllegalStateException("TMDB HTTP ${response.code}: $host")
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("TMDB request failed")
    }

    private fun searchResponse(
        item: JSONObject,
        forcedType: String? = null
    ): SearchResponse? {
        val type = forcedType ?: item.optString("media_type")
        if (type != "movie" && type != "tv") return null

        val id = item.optInt("id", 0)
        if (id <= 0) return null

        val title = if (type == "movie") {
            item.optString("title", item.optString("original_title"))
        } else {
            item.optString("name", item.optString("original_name"))
        }.trim()

        if (title.isBlank()) return null

        return newMovieSearchResponse(
            title,
            hnDetailUrl(type, id),
            if (type == "tv") TvType.TvSeries else TvType.Movie
        ) {
            posterUrl = imageUrl(item.optString("poster_path"))
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val json = tmdbGet("${request.data}?page=$page")
            val results = json.optJSONArray("results")
                ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

            val fallbackType = when {
                request.data.startsWith("/movie") -> "movie"
                request.data.startsWith("/tv") -> "tv"
                else -> null
            }

            val items = ArrayList<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                searchResponse(item, fallbackType)?.let(items::add)
            }

            newHomePageResponse(
                request.name,
                items.distinctBy { it.url },
                hasNext = page < json.optInt("total_pages", 1)
            )
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            ClipboardLogger.logAndCopy("getMainPage", e.stackTraceToString())
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = tmdbGet("/search/multi?query=$encoded")
            val results = json.optJSONArray("results") ?: return emptyList()

            val output = ArrayList<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                searchResponse(item)?.let(output::add)
            }

            output.distinctBy { it.url }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            ClipboardLogger.logAndCopy("search", e.stackTraceToString())
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseHnRef(url) ?: run {
            ClipboardLogger.logAndCopy("load", "Invalid provider URL/data: $url")
            return null
        }

        return try {
            val json = tmdbGet("/${ref.type}/${ref.id}?append_to_response=external_ids")
            if (json.has("status_code")) {
                throw IllegalStateException(json.optString("status_message"))
            }

            val imdbId = json.optString("imdb_id", "").ifBlank {
                json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
            }
            if (imdbId.startsWith("tt")) {
                imdbCache[ref.id] = imdbId
            }

            val title = if (ref.type == "movie") {
                json.optString("title", json.optString("original_title"))
            } else {
                json.optString("name", json.optString("original_name"))
            }.ifBlank { "Unknown title" }

            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val poster = imageUrl(json.optString("poster_path"))
            val date = if (ref.type == "movie") {
                json.optString("release_date")
            } else {
                json.optString("first_air_date")
            }
            val year = date.take(4).toIntOrNull()
            val canonicalUrl = hnDetailUrl(ref.type, ref.id)

            if (ref.type == "movie") {
                newMovieLoadResponse(
                    title,
                    canonicalUrl,
                    TvType.Movie,
                    "hnembed:movie:${ref.id}"
                ) {
                    posterUrl = poster
                    plot = overview
                    this.year = year
                }
            } else {
                val episodes = ArrayList<Episode>()
                val seasonCount = json.optInt("number_of_seasons", 0)

                for (seasonNumber in 1..seasonCount) {
                    try {
                        val seasonJson = tmdbGet("/tv/${ref.id}/season/$seasonNumber")
                        val episodeArray = seasonJson.optJSONArray("episodes") ?: continue

                        for (i in 0 until episodeArray.length()) {
                            val episodeJson = episodeArray.optJSONObject(i) ?: continue
                            val season = episodeJson.optInt("season_number", seasonNumber)
                            val episode = episodeJson.optInt("episode_number", i + 1)
                            val episodeName = episodeJson.optString("name")
                                .ifBlank { "Episode $episode" }

                            episodes.add(
                                newEpisode("hnembed:tv:${ref.id}:$season:$episode") {
                                    name = episodeName
                                    this.season = season
                                    this.episode = episode
                                    description = episodeJson.optString("overview")
                                        .takeIf { it.isNotBlank() }
                                    posterUrl = imageUrl(
                                        episodeJson.optString("still_path"),
                                        "w500"
                                    )
                                }
                            )
                        }
                    } catch (e: Exception) {
                        rethrowIfCancellation(e)
                        ClipboardLogger.logAndCopy(
                            "Season $seasonNumber",
                            e.message ?: "Unable to load season"
                        )
                    }
                }

                newTvSeriesLoadResponse(
                    title,
                    canonicalUrl,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                    plot = overview
                    this.year = year
                }
            }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            ClipboardLogger.logAndCopy("load detail", e.stackTraceToString())
            null
        }
    }

    // =========================================================================
    // Subtitles (OpenSubtitles matching HNEmbed subtitles.js + Stremio fallback)
    // =========================================================================

    private fun appendSubExtFragment(url: String, formatHint: String = "srt"): String {
        val lower = url.lowercase()
        if (lower.contains(".srt") || lower.contains(".vtt") || lower.contains(".ass")) {
            return url
        }
        val cleanExt = if (formatHint.equals("vtt", ignoreCase = true) || formatHint.equals("webvtt", ignoreCase = true)) {
            "vtt"
        } else {
            "srt"
        }
        return "$url#file.$cleanExt"
    }

    private suspend fun loadOpenSubtitles(
        ref: HnMediaRef,
        imdbId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        seenSubs: HashSet<String>
    ): Int {
        if (imdbId.isBlank()) return 0
        var added = 0
        val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId.removePrefix("tt") }
        val osHeaders = mapOf(
            "User-Agent" to "trailers.to-UA",
            "X-User-Agent" to "trailers.to-UA"
        )

        val candidates = mutableListOf<Triple<String, String, String>>() // langLabel, release, url

        // 1. OpenSubtitles REST API
        for ((langCode, langLabel) in listOf("vie" to "Vietnamese", "eng" to "English")) {
            runCatching {
                val url = if (ref.type == "tv" && ref.season != null && ref.episode != null) {
                    "https://rest.opensubtitles.org/search/episode-${ref.episode}/imdbid-$cleanId/season-${ref.season}/sublanguageid-$langCode"
                } else {
                    "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$langCode"
                }
                val res = app.get(url, headers = osHeaders, timeout = 8L).text.trim()
                if (!res.startsWith("[")) return@runCatching
                val arr = JSONArray(res)
                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .sortedBy { obj ->
                        when (obj.optString("SubFormat", "").lowercase()) {
                            "srt", "vtt" -> 0
                            else -> 1
                        }
                    }
                for (item in items.take(5)) {
                    val rawLink = item.optString("SubDownloadLink", "").trim()
                    if (!rawLink.startsWith("http")) continue
                    val direct = rawLink
                        .replace("/filead/", "/subencoding-utf8/filead/")
                        .replace("/file/", "/subencoding-utf8/file/")
                        .removeSuffix(".gz")
                    val fmt = item.optString("SubFormat", "srt")
                    val rel = item.optString("MovieReleaseName", "").trim().take(28)
                    val lName = item.optString("LanguageName", langLabel).trim()
                    candidates.add(Triple(lName, rel, appendSubExtFragment(direct, fmt)))
                }
            }.onFailure { rethrowIfCancellation(it) }
        }

        // 2. OpenSubtitles Stremio V3 fallback
        runCatching {
            val stremioUrl = if (ref.type == "tv" && ref.season != null && ref.episode != null) {
                "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:${ref.season}:${ref.episode}.json"
            } else {
                "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
            }
            val body = app.get(stremioUrl, headers = mapOf("User-Agent" to userAgent), timeout = 8L).text.trim()
            if (!body.startsWith("{")) return@runCatching
            val arr = JSONObject(body).optJSONArray("subtitles") ?: return@runCatching
            var viCount = 0
            var enCount = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val subUrl = o.optString("url", "").trim()
                if (!subUrl.startsWith("http")) continue
                val l = o.optString("lang", "").lowercase()
                val rel = o.optString("releaseGroup", "").trim().take(28)
                when {
                    (l == "vie" || l == "vi") && viCount < 4 -> {
                        candidates.add(Triple("Vietnamese", rel, appendSubExtFragment(subUrl, "srt")))
                        viCount++
                    }
                    (l == "eng" || l == "en") && enCount < 4 -> {
                        candidates.add(Triple("English", rel, appendSubExtFragment(subUrl, "srt")))
                        enCount++
                    }
                }
            }
        }.onFailure { rethrowIfCancellation(it) }

        for ((langLbl, rel, url) in candidates) {
            if (!seenSubs.add(url)) continue
            val label = if (rel.isNotBlank()) "$langLbl (OpenSubtitles: $rel)" else "$langLbl (OpenSubtitles)"
            subtitleCallback.invoke(SubtitleFile(label, url))
            added++
        }
        return added
    }

    // =========================================================================
    // Attached Off-Screen WebView Sniffer for HNEmbed Player
    // =========================================================================

    private data class HnSniffResult(
        val capturedStreams: List<String>,
        val apiStreamUrls: List<String>,
        val apiToken: String,
        val capturedSubs: List<String>,
        val observedRequests: List<String>,
        val networkErrors: List<String>
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffHnembedPlayer(
        playerPageUrl: String,
        initialReferer: String,
        timeoutMs: Long = 40_000L
    ): HnSniffResult {
        return suspendCoroutine { continuation ->
            val activity = try {
                CommonActivity.activity
            } catch (_: Throwable) {
                null
            }
            val appCtx = activity?.applicationContext ?: activity ?: runCatching {
                val at = Class.forName("android.app.ActivityThread")
                at.getMethod("currentApplication").invoke(null) as? Context
            }.getOrNull()

            if (appCtx == null) {
                ClipboardLogger.logAndCopy("WebView Sniff", "Skipped: Context is null")
                continuation.resume(
                    HnSniffResult(emptyList(), emptyList(), "", emptyList(), emptyList(), emptyList())
                )
                return@suspendCoroutine
            }

            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val webView = try {
                    WebView(appCtx)
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("WebView Sniff", "Creation failed: ${e.message}")
                    continuation.resume(
                        HnSniffResult(emptyList(), emptyList(), "", emptyList(), emptyList(), emptyList())
                    )
                    return@post
                }

                var finished = false
                var graceScheduled = false
                val w = 1080
                val h = 1920

                val capturedStreams = linkedSetOf<String>()
                val capturedSubs = linkedSetOf<String>()
                val observedRequests = linkedSetOf<String>()
                val networkErrors = linkedSetOf<String>()

                val directStreamRegex = Regex(
                    """(?i)(/pl[ci]?/[A-Za-z0-9_-]{30,}(?:/master\.m3u8)?(?:\?|$)|\.m3u8(?:$|[?#])|\.mpd(?:$|[?#])|\.mp4(?:$|[?#])|\.mkv(?:$|[?#])|\.webm(?:$|[?#])|master\.txt|playManifest)"""
                )
                val assetIgnoreRegex = Regex(
                    """(?i)(\.(?:css|js|png|jpe?g|gif|svg|ico|woff2?|ttf|wasm|ts|m4s)(?:$|[?#])|/generate\.php|/api\.php|/verify\.php|/cdn-cgi/)"""
                )
                val subRegex = Regex("""(?i)\.(?:vtt|srt)(?:$|[?#])""")

                var timeoutTask: Runnable? = null
                var graceTask: Runnable? = null
                var pollTask: Runnable? = null

                fun readHookState(onReady: (List<String>, String, List<String>) -> Unit) {
                    val js = """
                        (function(){
                            try{
                                var d = window.__hnCapture || {};
                                var cfg = window.CONFIG || {};
                                var v = document.querySelector('video');
                                var srcs = [];
                                if (v) {
                                    if (v.src && /^https?:/i.test(v.src)) srcs.push(v.src);
                                    if (v.currentSrc && /^https?:/i.test(v.currentSrc)) srcs.push(v.currentSrc);
                                    Array.prototype.slice.call(v.querySelectorAll('source')).forEach(function(s){
                                        if (s.src && /^https?:/i.test(s.src)) srcs.push(s.src);
                                    });
                                }
                                return JSON.stringify({
                                    streams: d.streams || [],
                                    token: (cfg.apiToken || d.token || ''),
                                    domSrcs: srcs
                                });
                            }catch(e){
                                return '{"streams":[],"token":"","domSrcs":[]}';
                            }
                        })();
                    """.trimIndent()
                    try {
                        webView.evaluateJavascript(js) { raw ->
                            val decoded = runCatching {
                                val str = org.json.JSONTokener(raw ?: "\"{}\"").nextValue() as? String ?: "{}"
                                JSONObject(str)
                            }.getOrNull()
                            val streams = mutableListOf<String>()
                            val domSrcs = mutableListOf<String>()
                            var tok = ""
                            if (decoded != null) {
                                tok = decoded.optString("token", "").trim()
                                val arr = decoded.optJSONArray("streams")
                                if (arr != null) {
                                    for (i in 0 until arr.length()) {
                                        arr.optString(i, "").trim().takeIf { it.startsWith("http") }?.let(streams::add)
                                    }
                                }
                                val dArr = decoded.optJSONArray("domSrcs")
                                if (dArr != null) {
                                    for (i in 0 until dArr.length()) {
                                        dArr.optString(i, "").trim().takeIf { it.startsWith("http") }?.let(domSrcs::add)
                                    }
                                }
                            }
                            onReady(streams, tok, domSrcs)
                        }
                    } catch (_: Exception) {
                        onReady(emptyList(), "", emptyList())
                    }
                }

                fun completeWithState(apiStreams: List<String>, apiToken: String, domSrcs: List<String>) {
                    if (finished) return
                    finished = true
                    timeoutTask?.let(mainHandler::removeCallbacks)
                    graceTask?.let(mainHandler::removeCallbacks)
                    pollTask?.let(mainHandler::removeCallbacks)

                    val streamList = synchronized(capturedStreams) {
                        val combined = linkedSetOf<String>()
                        combined.addAll(capturedStreams)
                        combined.addAll(domSrcs)
                        combined.toList()
                    }
                    val subList = synchronized(capturedSubs) { capturedSubs.toList() }
                    val reqList = synchronized(observedRequests) { observedRequests.toList() }
                    val errList = synchronized(networkErrors) { networkErrors.toList() }

                    try {
                        webView.stopLoading()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    } catch (_: Exception) { }

                    continuation.resume(
                        HnSniffResult(
                            capturedStreams = streamList,
                            apiStreamUrls = apiStreams,
                            apiToken = apiToken,
                            capturedSubs = subList,
                            observedRequests = reqList,
                            networkErrors = errList
                        )
                    )
                }

                fun finishSoon() {
                    if (finished) return
                    readHookState { apiStreams, apiToken, domSrcs ->
                        completeWithState(apiStreams, apiToken, domSrcs)
                    }
                }

                fun scheduleGraceFinish(delayMs: Long = 1600L) {
                    if (finished || graceScheduled) return
                    graceScheduled = true
                    graceTask = Runnable { finishSoon() }
                    mainHandler.postDelayed(graceTask!!, delayMs)
                }

                val hookScript = """
                    (function(){
                        try { window.open = function(){ return {closed:false,focus:function(){},close:function(){},blur:function(){}}; }; } catch(e) {}
                        if (!window.__hnCapture) {
                            window.__hnCapture = { streams: [], token: '' };
                        }
                        if (!window.__hnHooked && typeof window.vsFetchJSON === 'function') {
                            window.__hnHooked = true;
                            var orig = window.vsFetchJSON;
                            window.vsFetchJSON = function(url, opts) {
                                return orig.apply(this, arguments).then(function(res){
                                    try {
                                        if (res && res.data && Array.isArray(res.data.stream_urls)) {
                                            res.data.stream_urls.forEach(function(u){
                                                if (typeof u === 'string' && /^https?:/i.test(u) && window.__hnCapture.streams.indexOf(u) === -1) {
                                                    window.__hnCapture.streams.push(u);
                                                }
                                            });
                                        }
                                        if (window.CONFIG && window.CONFIG.apiToken) {
                                            window.__hnCapture.token = window.CONFIG.apiToken;
                                        }
                                    } catch(e) {}
                                    return res;
                                });
                            };
                        }
                    })();
                """.trimIndent()

                pollTask = object : Runnable {
                    override fun run() {
                        if (finished) return
                        try {
                            webView.evaluateJavascript(hookScript, null)
                        } catch (_: Exception) { }
                        readHookState { apiStreams, _, domSrcs ->
                            if (!finished) {
                                val hasAny = synchronized(capturedStreams) { capturedStreams.isNotEmpty() } ||
                                    apiStreams.isNotEmpty() || domSrcs.isNotEmpty()
                                if (hasAny) {
                                    scheduleGraceFinish(1800L)
                                } else {
                                    mainHandler.postDelayed(this, 800L)
                                }
                            }
                        }
                    }
                }

                timeoutTask = Runnable { finishSoon() }

                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = userAgent
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean = false

                        override fun onConsoleMessage(cm: ConsoleMessage?): Boolean = true
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!u.startsWith("http://") && !u.startsWith("https://")) return null

                            if (blockedWebViewDomains.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            val isMedia = !assetIgnoreRegex.containsMatchIn(u) && directStreamRegex.containsMatchIn(u)
                            if (isMedia || !u.endsWith(".ts", ignoreCase = true)) {
                                synchronized(observedRequests) {
                                    if (observedRequests.size < 100) {
                                        observedRequests.add("${request?.method ?: "GET"} ${u.take(200)}")
                                    }
                                }
                            }

                            if (subRegex.containsMatchIn(u) &&
                                !u.contains("thumbnails.vtt", ignoreCase = true) &&
                                !u.contains("/thumbs", ignoreCase = true)
                            ) {
                                synchronized(capturedSubs) {
                                    if (capturedSubs.size < 20) capturedSubs.add(u)
                                }
                            }

                            if (isMedia) {
                                synchronized(capturedStreams) {
                                    if (capturedStreams.size < 20) capturedStreams.add(u)
                                }
                                mainHandler.post { scheduleGraceFinish(1600L) }
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            try {
                                webView.evaluateJavascript(hookScript, null)
                            } catch (_: Exception) { }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            try {
                                webView.evaluateJavascript(hookScript, null)
                            } catch (_: Exception) { }
                            pollTask?.let { mainHandler.postDelayed(it, 600L) }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            if (request != null && errorResponse != null) {
                                synchronized(networkErrors) {
                                    if (networkErrors.size < 40) {
                                        networkErrors.add("HTTP ${errorResponse.statusCode} ${request.url.toString().take(180)}")
                                    }
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request != null && error != null) {
                                synchronized(networkErrors) {
                                    if (networkErrors.size < 40) {
                                        networkErrors.add("Err ${error.errorCode}: ${error.description} ${request.url.toString().take(150)}")
                                    }
                                }
                            }
                        }
                    }

                    // Attach off-screen with real 1080x1920 dimensions so Turnstile & player.js run normally
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, w, h)
                    webView.translationX = -4000f
                    try {
                        activity?.addContentView(webView, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) { }

                    timeoutTask?.let { mainHandler.postDelayed(it, timeoutMs) }
                    webView.loadUrl(
                        playerPageUrl,
                        mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to initialReferer
                        )
                    )
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("WebView Err", e.stackTraceToString().take(600))
                    try {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    } catch (_: Exception) { }
                    continuation.resume(
                        HnSniffResult(emptyList(), emptyList(), "", emptyList(), emptyList(), emptyList())
                    )
                }
            }
        }
    }

    // =========================================================================
    // Stream Token Generation, /pli/ Conversion & ExoPlayer Verification
    // =========================================================================

    private fun parseTokenResponse(rawText: String): String {
        val t = rawText.trim()
        if (t.isBlank()) return ""
        if (t.startsWith("{") || t.startsWith("[")) {
            val parsed = runCatching {
                if (t.startsWith("{")) {
                    val j = JSONObject(t)
                    j.optString("token")
                        .ifBlank { j.optString("data") }
                        .ifBlank { j.optString("string") }
                        .ifBlank { j.optString("result") }
                } else {
                    ""
                }
            }.getOrNull()
            if (!parsed.isNullOrBlank()) return parsed.trim()
        }
        return if (t.startsWith("<") || t.contains("error", ignoreCase = true) && t.length > 180) "" else t
    }

    private suspend fun fetchHostToken(
        origin: String,
        apiToken: String,
        playerReferer: String,
        tokenCache: MutableMap<String, String>
    ): String {
        if (origin.isBlank()) return ""
        tokenCache[origin]?.let { return it }

        val genUrl = if (apiToken.isNotBlank()) {
            "$origin/generate.php?api_token=${URLEncoder.encode(apiToken, "UTF-8")}"
        } else {
            "$origin/generate.php"
        }

        val resp = runCatching {
            app.get(
                genUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to playerReferer,
                    "Origin" to originOfUrl(playerReferer),
                    "Accept" to "*/*"
                ),
                timeout = 8L
            )
        }.onFailure { rethrowIfCancellation(it) }.getOrNull() ?: return ""

        if (resp.code !in 200..299) return ""
        val tk = parseTokenResponse(resp.text)
        if (tk.isNotBlank()) {
            tokenCache[origin] = tk
        }
        return tk
    }

    private suspend fun probeAndEmitHnStream(
        rawUrl: String,
        serverIndex: Int,
        playerPage: String,
        emittedUrls: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerOrigin = originOfUrl(playerPage)
        val reqHeaders = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Referer" to playerPage
        )
        if (playerOrigin.isNotBlank()) {
            reqHeaders["Origin"] = playerOrigin
        }

        val isMp4 = Regex("""\.(mp4|mkv|webm)(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(rawUrl)
        if (isMp4) {
            val probeResp = runCatching {
                app.get(rawUrl, headers = reqHeaders + ("Range" to "bytes=0-1023"), timeout = 10L)
            }.onFailure { rethrowIfCancellation(it) }.getOrNull()

            if (probeResp != null && probeResp.code in 200..299) {
                val head = probeResp.text.take(200).trimStart()
                if (!head.startsWith("<!doctype", ignoreCase = true) && !head.startsWith("<html", ignoreCase = true)) {
                    if (emittedUrls.add(rawUrl)) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Hnembed • Server $serverIndex (MP4)",
                                url = rawUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = playerPage
                                this.quality = Qualities.P1080.value
                                this.headers = reqHeaders
                            }
                        )
                        ClipboardLogger.logAndCopy("Live (Server $serverIndex)", "MP4 HTTP ${probeResp.code} -> ${rawUrl.take(150)}")
                        return true
                    }
                }
            }
            return false
        }

        // 1. If HNEmbed /pl/<P>, prefer HNEmbed's native /pli/<P> (all child variant & segment URLs include token)
        val plMatch = HN_STATIC_PL_REGEX.find(rawUrl)
        if (plMatch != null && !rawUrl.contains("/pli/", ignoreCase = true) && !rawUrl.contains("/plc/", ignoreCase = true)) {
            val pliUrl = rawUrl.replaceFirst("/pl/", "/pli/", ignoreCase = true)
            val pliResp = runCatching {
                app.get(pliUrl, headers = reqHeaders, timeout = 10L)
            }.onFailure { rethrowIfCancellation(it) }.getOrNull()

            if (pliResp != null && pliResp.code in 200..299 && pliResp.text.contains("#EXTM3U")) {
                val childHasToken = pliResp.text.contains("token=") || pliResp.text.contains("cast_token=")
                if (childHasToken) {
                    val outUrl = if (pliUrl.contains(".m3u8", ignoreCase = true)) pliUrl else "$pliUrl#file.m3u8"
                    if (emittedUrls.add(outUrl)) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Hnembed • Server $serverIndex (HLS)",
                                url = outUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = playerPage
                                this.quality = Qualities.P1080.value
                                this.headers = reqHeaders
                            }
                        )
                        ClipboardLogger.logAndCopy("Live (Server $serverIndex /pli/)", "HLS HTTP ${pliResp.code} -> ${outUrl.take(150)}")
                        return true
                    }
                }
            }
        }

        // 2. Fetch raw playlist (direct .m3u8 / /pli/ / /pl/)
        val resp = runCatching {
            app.get(rawUrl, headers = reqHeaders, timeout = 10L)
        }.onFailure { rethrowIfCancellation(it) }.getOrNull() ?: run {
            ClipboardLogger.logAndCopy("Dead (Server $serverIndex)", "Request failed -> ${rawUrl.take(150)}")
            return false
        }

        if (resp.code !in 200..299) {
            ClipboardLogger.logAndCopy("Dead (Server $serverIndex)", "HTTP ${resp.code} -> ${rawUrl.take(150)}")
            return false
        }

        val body = resp.text
        if (body.contains("<MPD", ignoreCase = true) || rawUrl.contains(".mpd", ignoreCase = true)) {
            val outUrl = if (rawUrl.contains(".mpd", ignoreCase = true)) rawUrl else "$rawUrl#file.mpd"
            if (emittedUrls.add(outUrl)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Hnembed • Server $serverIndex (DASH)",
                        url = outUrl,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.referer = playerPage
                        this.quality = Qualities.P1080.value
                        this.headers = reqHeaders
                    }
                )
                ClipboardLogger.logAndCopy("Live (Server $serverIndex)", "DASH HTTP ${resp.code} -> ${outUrl.take(150)}")
                return true
            }
            return false
        }

        if (!body.contains("#EXTM3U")) {
            ClipboardLogger.logAndCopy("Dead (Server $serverIndex)", "Non-M3U8 HTTP ${resp.code} -> ${rawUrl.take(150)}")
            return false
        }

        // Check whether child playlist / segment lines already carry token= or need loopback token stamping
        val tok = extractQueryParam(rawUrl, "token")
        val castTok = extractQueryParam(rawUrl, "cast_token")
        val tokenName = if (!castTok.isNullOrBlank()) "cast_token" else "token"
        val tokenValue = castTok?.takeIf { it.isNotBlank() } ?: tok.orEmpty()
        val needsTokenStamp = tokenValue.isNotBlank() &&
            !body.contains("token=") &&
            !body.contains("cast_token=")

        val playableUrl = if (needsTokenStamp) {
            HnHlsTokenLoopbackProxy.register(
                targetUrl = rawUrl,
                origin = originOfUrl(rawUrl),
                tokenName = tokenName,
                tokenValue = tokenValue,
                referer = playerPage,
                userAgent = userAgent
            ) ?: rawUrl
        } else {
            if (rawUrl.contains(".m3u8", ignoreCase = true)) rawUrl else "$rawUrl#file.m3u8"
        }

        if (emittedUrls.add(playableUrl)) {
            val effectiveHeaders = if (playableUrl.startsWith("http://127.0.0.1")) {
                mapOf("User-Agent" to userAgent)
            } else {
                reqHeaders
            }
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Hnembed • Server $serverIndex (HLS)",
                    url = playableUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = playerPage
                    this.quality = Qualities.P1080.value
                    this.headers = effectiveHeaders
                }
            )
            val modeTag = if (needsTokenStamp) "HLS-Proxy" else "HLS"
            ClipboardLogger.logAndCopy("Live (Server $serverIndex)", "$modeTag HTTP ${resp.code} -> ${playableUrl.take(150)}")
            return true
        }
        return false
    }

    // =========================================================================
    // loadLinks (Strict HNEmbed-only, no external backup providers)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startMs = SystemClock.elapsedRealtime()
        ClipboardLogger.clear()
        ClipboardLogger.logAndCopy("Start", "Hnembed loadLinks: $data (casting=$isCasting)")

        val ref = parseHnRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Dữ liệu không hợp lệ: $data")
            return false
        }

        if (ref.type == "tv" && (ref.season == null || ref.episode == null)) {
            ClipboardLogger.logAndCopy("Error", "TV series cần season và episode: $data")
            return false
        }

        var imdbId = imdbCache[ref.id] ?: ""
        if (imdbId.isBlank()) {
            runCatching {
                val json = tmdbGet("/${ref.type}/${ref.id}?append_to_response=external_ids")
                imdbId = json.optString("imdb_id", "").ifBlank {
                    json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
                }
                if (imdbId.startsWith("tt")) {
                    imdbCache[ref.id] = imdbId
                }
            }.onFailure { rethrowIfCancellation(it) }
        }

        val seenSubs = HashSet<String>()
        var subCount = loadOpenSubtitles(ref, imdbId, subtitleCallback, seenSubs)
        if (subCount > 0) {
            ClipboardLogger.logAndCopy("Subtitles", "Đã nạp $subCount phụ đề OpenSubtitles")
        }

        val mirror = "https://hnembed.cc"
        val embedUrl = if (ref.type == "movie") {
            "$mirror/embed/movie/${ref.id}"
        } else {
            "$mirror/embed/tv/${ref.id}/${ref.season}/${ref.episode}"
        }
        ClipboardLogger.logAndCopy("HNEmbed Target", embedUrl)

        var emitted = 0
        val emittedUrls = HashSet<String>()

        try {
            val response = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "$mirror/"
                ),
                timeout = 12L
            )
            val html = response.text
            val finalUrl = response.url.toString()
            ClipboardLogger.logAndCopy("Embed Response", "HTTP ${response.code}; final=$finalUrl; bytes=${html.length}")
            if (response.code !in 200..299) {
                ClipboardLogger.logAndCopy("Failed", "HNEmbed embed trả về HTTP ${response.code}")
                return false
            }

            val doc = Jsoup.parse(html, finalUrl)
            val playerIframe = doc.selectFirst("iframe#player_iframe")
                ?: doc.selectFirst("iframe[data-api]")
            val apiUrl = resolveHttpUrl(finalUrl, playerIframe?.attr("data-api"))

            if (apiUrl == null) {
                // Fallback only if Hnembed embed directly exposed a non-API iframe src
                val directIframe = resolveHttpUrl(finalUrl, playerIframe?.attr("src"))
                if (directIframe != null) {
                    loadExtractor(directIframe, finalUrl, subtitleCallback) { link ->
                        if (emittedUrls.add(link.url)) {
                            callback.invoke(link)
                            emitted++
                        }
                    }
                }
                ClipboardLogger.logAndCopy(
                    if (emitted > 0) "Done" else "Failed",
                    "Không tìm thấy data-api trên trang embed HNEmbed (emitted=$emitted)"
                )
                return emitted > 0
            }

            ClipboardLogger.logAndCopy("HNEmbed data-api", apiUrl)

            val apiResp = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json,text/plain,*/*",
                    "Referer" to finalUrl
                ),
                timeout = 12L
            )
            val apiText = apiResp.text
            val srcFromJson = runCatching {
                JSONObject(apiText).optString("src").takeIf { it.isNotBlank() && it != "null" }
            }.getOrNull()
            val srcFromRegex = Regex("\"src\"\\s*:\\s*\"([^\"]+)\"").find(apiText)?.groupValues?.getOrNull(1)
            val srcUrl = resolveHttpUrl(apiUrl, srcFromJson ?: srcFromRegex)

            if (srcUrl == null) {
                ClipboardLogger.logAndCopy("Failed", "vs_src HTTP ${apiResp.code} không có src: ${apiText.take(250)}")
                return false
            }
            ClipboardLogger.logAndCopy("HNEmbed src", srcUrl)

            val landingResp = app.get(
                srcUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/json,*/*",
                    "Referer" to "$mirror/"
                ),
                timeout = 12L
            )
            val landingText = landingResp.text
            val playerFromJson = runCatching {
                JSONObject(landingText).optString("playerUrl").takeIf { it.isNotBlank() && it != "null" }
            }.getOrNull()
            val playerFromRegex = Regex("\"playerUrl\"\\s*:\\s*\"([^\"]+)\"").find(landingText)?.groupValues?.getOrNull(1)
            val playerUrl = resolveHttpUrl(srcUrl, playerFromJson ?: playerFromRegex)

            if (playerUrl == null) {
                ClipboardLogger.logAndCopy("Failed", "Trang src landing HTTP ${landingResp.code} không có playerUrl")
                return false
            }
            ClipboardLogger.logAndCopy("Player Target", playerUrl)

            // Fetch metaApi for default_subs or missing imdb_id
            val metaApiRaw = Regex("\"metaApi\"\\s*:\\s*\"([^\"]+)\"").find(landingText)?.groupValues?.getOrNull(1)
            val metaApiUrl = resolveHttpUrl(srcUrl, metaApiRaw)
            if (metaApiUrl != null) {
                runCatching {
                    val mJson = JSONObject(
                        app.get(
                            metaApiUrl,
                            headers = mapOf("User-Agent" to userAgent, "Referer" to playerUrl),
                            timeout = 8L
                        ).text
                    )
                    val metaImdb = mJson.optJSONObject("data")?.optString("imdb_id", "").orEmpty()
                    if (imdbId.isBlank() && metaImdb.startsWith("tt")) {
                        imdbId = metaImdb
                        imdbCache[ref.id] = imdbId
                        subCount += loadOpenSubtitles(ref, imdbId, subtitleCallback, seenSubs)
                    }
                    val defSubs = mJson.optJSONArray("default_subs")
                        ?: mJson.optJSONObject("data")?.optJSONArray("default_subs")
                    if (defSubs != null) {
                        for (i in 0 until defSubs.length()) {
                            val sObj = defSubs.optJSONObject(i) ?: continue
                            val sUrl = resolveHttpUrl(metaApiUrl, sObj.optString("url").ifBlank { sObj.optString("file") })
                                ?: continue
                            val sLbl = sObj.optString("label").ifBlank { sObj.optString("lang", "Subtitle") }
                            val normSub = appendSubExtFragment(sUrl, "vtt")
                            if (seenSubs.add(normSub)) {
                                subtitleCallback.invoke(SubtitleFile("Hnembed • $sLbl", normSub))
                                subCount++
                            }
                        }
                    }
                }.onFailure { rethrowIfCancellation(it) }
            }

            // Sniff player page in attached off-screen WebView
            val remWaitMs = (95_000L - (SystemClock.elapsedRealtime() - startMs)).coerceIn(20_000L, 42_000L)
            ClipboardLogger.logAndCopy("WebView Sniff", "Đang sniff (${remWaitMs / 1000}s): $playerUrl")

            val sniff = sniffHnembedPlayer(
                playerPageUrl = playerUrl,
                initialReferer = srcUrl,
                timeoutMs = remWaitMs
            )

            for (subUrl in sniff.capturedSubs) {
                val normSub = appendSubExtFragment(subUrl, "vtt")
                if (seenSubs.add(normSub)) {
                    subtitleCallback.invoke(SubtitleFile("Hnembed • WebVTT", normSub))
                    subCount++
                }
            }

            // Build candidate stream list: live captured streams from shouldInterceptRequest first,
            // followed by any extra Hnembed servers decoded from api.php (tokenized via /generate.php)
            val orderedStreams = linkedSetOf<String>()
            orderedStreams.addAll(sniff.capturedStreams)

            val tokenCache = HashMap<String, String>()
            for (rawCaptured in sniff.capturedStreams) {
                val orig = originOfUrl(rawCaptured)
                val tk = extractQueryParam(rawCaptured, "token")
                if (orig.isNotBlank() && !tk.isNullOrBlank()) {
                    tokenCache[orig] = tk
                }
            }

            for (rawApiStream in sniff.apiStreamUrls) {
                val hasTok = rawApiStream.contains("token=") || rawApiStream.contains("cast_token=")
                if (hasTok) {
                    orderedStreams.add(rawApiStream)
                    continue
                }
                val streamOrigin = originOfUrl(rawApiStream)
                val tk = fetchHostToken(streamOrigin, sniff.apiToken, playerUrl, tokenCache)
                val tokenized = if (tk.isNotBlank()) appendTokenParam(rawApiStream, "token", tk) else rawApiStream
                orderedStreams.add(tokenized)
            }

            if (orderedStreams.isEmpty()) {
                ClipboardLogger.logAndCopy(
                    "WebView Miss",
                    "Không bắt được luồng (${sniff.observedRequests.size} reqs, ${sniff.networkErrors.size} errs)\n" +
                        sniff.observedRequests.take(30).joinToString("\n") +
                        if (sniff.networkErrors.isNotEmpty()) "\n--- ERRORS ---\n${sniff.networkErrors.take(15).joinToString("\n")}" else ""
                )
            } else {
                var srvIdx = 1
                for (candUrl in orderedStreams) {
                    if (emitted >= 3) break
                    if (probeAndEmitHnStream(candUrl, srvIdx, playerUrl, emittedUrls, callback)) {
                        emitted++
                        srvIdx++
                    }
                }
            }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            ClipboardLogger.logAndCopy("Exception", "${e.javaClass.simpleName}: ${e.message}")
        }

        ClipboardLogger.logAndCopy(
            if (emitted > 0) "Done" else "Failed",
            "Đã nạp $emitted luồng phát & $subCount phụ đề từ Hnembed."
        )
        return emitted > 0
    }
}
