package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.regex.Pattern

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ ĐỊNH DANH
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

private val ID_REGEX = Pattern.compile("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")

private class MediaRef(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): MediaRef? {
    val m = ID_REGEX.matcher(s)
    if (!m.find()) return null
    val id = m.group(2)?.toIntOrNull() ?: return null
    return MediaRef(
        m.group(1) ?: return null,
        id,
        m.group(3)?.toIntOrNull(),
        m.group(4)?.toIntOrNull()
    )
}

// =====================================================================================
// 2. CÁC HÀM GỌI API HỖ TRỢ (OMDb, Trakt)
// =====================================================================================
object TwoNembedApiServices {
    suspend fun getOmdbData(imdbId: String): String? {
        return try {
            val res = app.get("https://www.omdbapi.com/?apikey=$OMDB_API_KEY&i=$imdbId").text
            val json = JSONObject(res)
            val rating = json.optString("imdbRating")
            if (rating.isNotEmpty() && rating != "N/A") rating else null
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("OMDb Error", e.message ?: "Unknown")
            null
        }
    }

    suspend fun sendTraktScrobble(
        action: String,
        type: String,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    ) {
        if (TRAKT_ACCESS_TOKEN.isEmpty()) return
        val url = "https://api.trakt.tv/scrobble/$action"
        val headers = mapOf(
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID,
            "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
        )
        val body = JSONObject()
        body.put("progress", progress)
        if (type == "movie") {
            val movie = JSONObject()
            val ids = JSONObject()
            ids.put("tmdb", tmdbId)
            movie.put("ids", ids)
            body.put("movie", movie)
        } else {
            val episodeObj = JSONObject()
            episodeObj.put("season", season ?: 1)
            episodeObj.put("number", episode ?: 1)
            body.put("episode", episodeObj)
        }
        try {
            app.post(url, headers = headers, json = body).text
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Trakt Scrobble Error", e.message ?: "Unknown")
        }
    }
}

// =====================================================================================
// 3. LỚP PROVIDER CHÍNH
// =====================================================================================
class TwoNembedProvider : MainAPI() {
    override var mainUrl = "https://www.2embed.cc"
    override var name = "2embed"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    private fun img(path: String?, size: String = "w500") =
        path?.takeIf { it.isNotEmpty() && it != "null" }
            ?.let { "https://wsrv.nl/?url=image.tmdb.org/t/p/$size$it" }

    private fun poster(path: String?) = img(path, "w500")

    private val tmdbHosts = listOf(
        TMDB_BASE_URL,
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )

    private suspend fun tmdbGet(path: String): String {
        var last: Exception? = null
        val sep = if (path.contains("?")) "&" else "?"
        for (host in tmdbHosts) {
            try {
                val r = app.get("$host$path${sep}api_key=$TMDB_API_KEY")
                if (r.code == 200 && r.text.trimStart().startsWith("{")) return r.text
                last = Exception("HTTP ${r.code} @ $host")
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                last = e
            }
            ClipboardLogger.logAndCopy("TMDB fallback", "${last?.message} (path=$path)")
        }
        throw last ?: Exception("TMDB failed")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = tmdbGet("${request.data}?page=$page")
            val json = JSONObject(response)
            val results = json.optJSONArray("results")
                ?: throw Exception(json.optString("status_message", "TMDB không trả results"))
            val home = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString(
                    "media_type",
                    if (request.name.contains("TV")) "tv" else "movie"
                )
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                home.add(
                    newMovieSearchResponse(
                        title,
                        "2embed:$mediaType:$id",
                        if (mediaType == "tv") TvType.TvSeries else TvType.Movie
                    ) {
                        this.posterUrl = poster(item.optString("poster_path"))
                    }
                )
            }
            newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("getMainPage Error", e.message ?: "Unknown")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(tmdbGet("/search/multi?query=$q"))
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                out.add(
                    newMovieSearchResponse(
                        title,
                        "2embed:$mediaType:$id",
                        if (mediaType == "tv") TvType.TvSeries else TvType.Movie
                    ) {
                        this.posterUrl = poster(item.optString("poster_path"))
                    }
                )
            }
            out
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Search Error", e.message ?: "Unknown")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url)
        if (ref == null) {
            ClipboardLogger.logAndCopy("load Error", "Không đọc được id từ url: $url")
            return null
        }
        val mediaType = ref.type
        val tmdbIdInt = ref.id

        try {
            val response = tmdbGet("/$mediaType/$tmdbIdInt")
            val json = JSONObject(response)
            if (json.has("status_code")) throw Exception("TMDB: ${json.optString("status_message")}")

            val title = json.optString("title", json.optString("name"))
            val description = json.optString("overview")
            val posterUrl = poster(json.optString("poster_path"))
            val year = json.optString("release_date", json.optString("first_air_date"))
                .take(4).toIntOrNull()

            val canonical = "2embed:$mediaType:$tmdbIdInt"

            if (mediaType == "tv") {
                val episodes = mutableListOf<Episode>()
                val seasonsCount = json.optInt("number_of_seasons", 1)
                for (s in 1..seasonsCount) {
                    try {
                        val seasonJson = JSONObject(tmdbGet("/tv/$tmdbIdInt/season/$s"))
                        val eps = seasonJson.optJSONArray("episodes") ?: continue
                        for (e in 0 until eps.length()) {
                            val ep = eps.getJSONObject(e)
                            val sn = ep.optInt("season_number", s)
                            val en = ep.optInt("episode_number", e + 1)
                            episodes.add(newEpisode("2embed:tv:$tmdbIdInt:$sn:$en") {
                                this.name = ep.optString("name")
                                this.episode = en
                                this.season = sn
                                this.description = ep.optString("overview")
                                this.posterUrl = poster(ep.optString("still_path"))
                            })
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.logAndCopy("Season Load Error", "Season $s: ${e.message}")
                    }
                }
                return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.year = year
                }
            } else {
                return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.year = year
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("load Error", "$url -> ${e.message}")
            return null
        }
    }

    // =====================================================================================
    // 4. BỘ TẠO LINK AN TOÀN — newExtractorLink (SUSPEND)
    // =====================================================================================
    private suspend fun safeCreateExtractorLink(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int = Qualities.P1080.value,
        isM3u8: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ): ExtractorLink {
        val cleanUrl = url.substringBefore("#")

        return newExtractorLink(
            source = source,
            name = name,
            url = cleanUrl,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = headers
        }
    }

    // =====================================================================================
    // 5. VIDEO INTERCEPTOR (TƯƠNG THÍCH CLOUDSTREAM 4.5.2 & 4.8+)
    // =====================================================================================
    private fun sanitizeHlsPlaylist(text: String): String {
        val lines = text.lines()
        val mapPattern = Pattern.compile("""#EXT-X-MAP:[^\r\n]*URI=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        var mapUri: String? = null
        for (line in lines) {
            val m = mapPattern.matcher(line.trim())
            if (m.find()) {
                mapUri = m.group(1)
                break
            }
        }
        if (mapUri.isNullOrEmpty()) return text
        val hasSameMediaLine = lines.any { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("#") && t == mapUri
        }
        if (!hasSameMediaLine) return text
        return lines.filterNot { it.trim().startsWith("#EXT-X-MAP:", ignoreCase = true) }
            .joinToString("\n")
    }

    private fun findPngPayloadOffset(bytes: ByteArray): Int {
        val limit = minOf(bytes.size - 8, 512)
        for (i in 8..limit) {
            if (bytes[i] == 0x49.toByte() &&
                bytes[i + 1] == 0x45.toByte() &&
                bytes[i + 2] == 0x4E.toByte() &&
                bytes[i + 3] == 0x44.toByte()
            ) {
                val next = i + 8
                if (next < bytes.size) return next
            }
        }
        if (bytes.size > 70 && bytes[70] == 0x47.toByte()) return 70
        return -1
    }

    private fun isMpegTs(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0] != 0x47.toByte()) return false
        return (bytes.size >= 189 && bytes[188] == 0x47.toByte()) || bytes.size == 188
    }

    private fun detectSegmentMime(bytes: ByteArray): String {
        if (isMpegTs(bytes)) return "video/mp2t"
        if (bytes.size >= 8) {
            val box = String(bytes, 4, 4, Charsets.US_ASCII)
            if (box == "ftyp" || box == "styp" || box == "moof" || box == "sidx") {
                return "video/mp4"
            }
        }
        return "video/mp2t"
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (extractorLink.referer.isNotEmpty()) {
                builder.header("Referer", extractorLink.referer)
            }
            extractorLink.headers.forEach { (k, v) ->
                builder.header(k, v)
            }

            val response = chain.proceed(builder.build())
            if (!response.isSuccessful) return@Interceptor response

            val url = request.url.toString()
            val ct = (response.header("Content-Type") ?: "").lowercase()
            val body = response.body ?: return@Interceptor response

            // 1. Sửa M3U8 có #EXT-X-MAP trỏ trùng segment TS đầu (NontonGo Acer/Salix/Cedrus/Mercury...)
            val maybePlaylist = ct.contains("mpegurl") || ct.contains("m3u8") ||
                url.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ||
                url.contains("/_stream") || url.contains("/cdn/")

            if (maybePlaylist) {
                val peekStr = try {
                    response.peekBody(4096).string()
                } catch (_: Exception) {
                    ""
                }
                if (peekStr.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) {
                    if (peekStr.contains("#EXT-X-MAP:")) {
                        val rawText = body.string()
                        val fixedText = sanitizeHlsPlaylist(rawText)
                        val mime = "application/vnd.apple.mpegurl"
                        return@Interceptor response.newBuilder()
                            .removeHeader("Content-Length")
                            .removeHeader("Content-Encoding")
                            .header("Content-Type", mime)
                            .body(fixedText.toResponseBody(mime.toMediaType()))
                            .build()
                    }
                    return@Interceptor response
                }
            }

            // 2. Peek 256 byte đầu để gỡ PNG giả mạo hoặc sửa Content-Type cho TS
            val peek = try {
                response.peekBody(256).bytes()
            } catch (_: Exception) {
                return@Interceptor response
            }

            if (peek.size >= 8 &&
                peek[0] == 0x89.toByte() &&
                peek[1] == 0x50.toByte() &&
                peek[2] == 0x4E.toByte() &&
                peek[3] == 0x47.toByte()
            ) {
                val bytes = body.bytes()
                val offset = findPngPayloadOffset(bytes)
                if (offset in 1 until bytes.size) {
                    val stripped = bytes.copyOfRange(offset, bytes.size)
                    val mime = detectSegmentMime(stripped)
                    return@Interceptor response.newBuilder()
                        .removeHeader("Content-Length")
                        .removeHeader("Content-Encoding")
                        .header("Content-Type", mime)
                        .body(stripped.toResponseBody(mime.toMediaType()))
                        .build()
                }
                val origType = body.contentType()
                return@Interceptor response.newBuilder()
                    .removeHeader("Content-Length")
                    .removeHeader("Content-Encoding")
                    .body(bytes.toResponseBody(origType))
                    .build()
            }

            if (isMpegTs(peek) && (ct.isEmpty() || ct.startsWith("image/") || ct == "video/mp4" || ct == "application/octet-stream" || ct.contains("font"))) {
                return@Interceptor response.newBuilder()
                    .header("Content-Type", "video/mp2t")
                    .build()
            }

            response
        }
    }

    // =====================================================================================
    // 6. LOAD LINKS
    // =====================================================================================
    private data class CandidateButton(val url: String, val name: String)

    private fun parseQuality(label: String): Int {
        return when {
            label.contains("4K", true) || label.contains("2160", true) -> Qualities.P2160.value
            label.contains("1080", true) || label.contains("HD", true) || label.contains("HQ", true) -> Qualities.P1080.value
            label.contains("720", true) -> Qualities.P720.value
            label.contains("480", true) -> Qualities.P480.value
            else -> Qualities.P720.value
        }
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            URI(baseUrl).resolve(relativeOrAbsolute).toString()
        } catch (_: Exception) {
            relativeOrAbsolute
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        val ref = parseRef(data)
        if (ref == null) {
            ClipboardLogger.logAndCopy("Error", "Invalid data format: $data")
            return false
        }
        val type = ref.type
        val tmdbId = ref.id
        val season = ref.season
        val episode = ref.episode

        TwoNembedApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        var foundLink = false
        val registeredSubs = mutableSetOf<String>()
        val seenUrls = mutableSetOf<String>()

        // ---------------------------------------------------------------------------------
        // NGUỒN 1: NontonGo Player (southlong.nontongo.win / svsoapp / backup player)
        // ---------------------------------------------------------------------------------
        val nontongoPlayerUrl = if (type == "movie") {
            "https://nontongo.win/01aus/player.php?id=$tmdbId&type=movie&autoplay=true"
        } else {
            "https://nontongo.win/01aus/player.php?id=$tmdbId" +
                    "&season=${season ?: 1}&episode=${episode ?: 1}&type=tv&autoplay=true"
        }

        try {
            ClipboardLogger.logAndCopy("NontonGo Player", "Fetching: $nontongoPlayerUrl")
            val playerHtml = app.get(
                nontongoPlayerUrl,
                headers = mapOf("User-Agent" to ua, "Referer" to "https://nontongo.win/")
            ).text

            val trackPattern = Pattern.compile(
                """<track[^>]+(?:data-src|src)=["']([^"']+)["'][^>]*label=["']([^"']+)["']|<track[^>]+label=["']([^"']+)["'][^>]+(?:data-src|src)=["']([^"']+)["']""",
                Pattern.CASE_INSENSITIVE
            )
            val trackMatcher = trackPattern.matcher(playerHtml)
            while (trackMatcher.find()) {
                val subUrl = (trackMatcher.group(1) ?: "").ifEmpty { trackMatcher.group(4) ?: "" }
                val label = (trackMatcher.group(2) ?: "").ifEmpty { trackMatcher.group(3) ?: "" }
                if (subUrl.isNotEmpty() && registeredSubs.add(subUrl)) {
                    subtitleCallback.invoke(SubtitleFile(label, subUrl))
                    ClipboardLogger.logAndCopy("Sub Added", "$label -> $subUrl")
                }
            }

            val buttonPattern = Pattern.compile(
                """<button[^>]*data-url=["']([^"']+)["'][^>]*>(.*?)</button>""",
                Pattern.DOTALL
            )
            val streamCandidates = mutableListOf<CandidateButton>()
            val backupPages = mutableListOf<CandidateButton>()

            val buttonMatcher = buttonPattern.matcher(playerHtml)
            while (buttonMatcher.find()) {
                val rawUrl = (buttonMatcher.group(1) ?: "").replace("&amp;", "&").trim()
                val labelHtml = buttonMatcher.group(2) ?: ""
                val serverName = labelHtml.replace(Regex("<[^>]+>"), "").trim()
                    .ifEmpty { "Fast Server" }
                if (rawUrl.isEmpty()) continue

                if (rawUrl.contains("player-backup.php", ignoreCase = true) ||
                    rawUrl.contains("player.php", ignoreCase = true)
                ) {
                    backupPages.add(CandidateButton(resolveUrl(nontongoPlayerUrl, rawUrl), serverName))
                } else {
                    streamCandidates.add(
                        CandidateButton(
                            resolveUrl(nontongoPlayerUrl, rawUrl).substringBefore("#"),
                            serverName
                        )
                    )
                }
            }

            val nontongoHeaders = mapOf(
                "User-Agent" to ua,
                "Referer" to "https://nontongo.win/",
                "Origin" to "https://nontongo.win"
            )

            // Kiểm tra song song để loại bỏ các link chết (502/404)
            val validNontonLinks = streamCandidates
                .distinctBy { it.url }
                .amap { cand ->
                    try {
                        val res = app.get(cand.url, headers = nontongoHeaders)
                        if (res.code in 200..299 && res.text.trimStart().startsWith("#EXTM3U")) {
                            safeCreateExtractorLink(
                                source = this.name,
                                name = "2embed - ${cand.name}",
                                url = cand.url,
                                referer = "https://nontongo.win/",
                                quality = parseQuality(cand.name),
                                isM3u8 = true,
                                headers = nontongoHeaders
                            )
                        } else {
                            ClipboardLogger.log("NontonGo Skip", "${cand.name} HTTP ${res.code}")
                            null
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.log("NontonGo Probe Err", "${cand.name}: ${e.message}")
                        null
                    }
                }
                .filterNotNull()

            for (link in validNontonLinks) {
                if (seenUrls.add(link.url)) {
                    callback.invoke(link)
                    foundLink = true
                    ClipboardLogger.logAndCopy("Stream Added", "${link.name} (NontonGo)")
                }
            }

            // Kiểm tra trang dự phòng MP4 (usa.nontongo.win/01baltic/player-backup.php)
            for (bk in backupPages) {
                try {
                    val bkHtml = app.get(
                        bk.url,
                        headers = mapOf("User-Agent" to ua, "Referer" to "https://nontongo.win/")
                    ).text
                    val bkOriginMatch = Pattern.compile("""^(https?://[^/]+)""").matcher(bk.url)
                    val bkOrigin = if (bkOriginMatch.find()) bkOriginMatch.group(1)!! else "https://usa.nontongo.win"
                    val bkMatcher = buttonPattern.matcher(bkHtml)
                    while (bkMatcher.find()) {
                        val bUrl = (bkMatcher.group(1) ?: "").replace("&amp;", "&").substringBefore("#").trim()
                        val bName = (bkMatcher.group(2) ?: "").replace(Regex("<[^>]+>"), "").trim()
                            .ifEmpty { bk.name }
                        if (bUrl.startsWith("http") && seenUrls.add(bUrl)) {
                            val isHls = bUrl.contains(".m3u8", ignoreCase = true)
                            val link = safeCreateExtractorLink(
                                source = this.name,
                                name = "2embed - $bName (Backup)",
                                url = bUrl,
                                referer = "$bkOrigin/",
                                quality = parseQuality(bName),
                                isM3u8 = isHls,
                                headers = mapOf(
                                    "User-Agent" to ua,
                                    "Referer" to "$bkOrigin/",
                                    "Origin" to bkOrigin
                                )
                            )
                            callback.invoke(link)
                            foundLink = true
                            ClipboardLogger.logAndCopy("Stream Added", "$bName (Backup)")
                        }
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    ClipboardLogger.log("Backup Error", e.message ?: "")
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("NontonGo Error", e.message ?: "")
        }

        // ---------------------------------------------------------------------------------
        // NGUỒN 2: vidsrc.buzz / videm.buzz
        // ---------------------------------------------------------------------------------
        val embedCandidates = if (type == "movie") {
            listOf(
                "https://vidsrc.buzz/embed/movie/$tmdbId",
                "https://videm.buzz/embed/movie/$tmdbId"
            )
        } else {
            listOf(
                "https://vidsrc.buzz/embed/tv/$tmdbId/${season ?: 1}/${episode ?: 1}",
                "https://videm.buzz/embed/tv/$tmdbId/${season ?: 1}/${episode ?: 1}"
            )
        }

        var addedVidsrcServers = 0
        for (playerUrl in embedCandidates) {
            if (addedVidsrcServers > 0) break
            try {
                val pDomainMatch = Pattern.compile("""^(https?://[^/]+)""").matcher(playerUrl)
                val pDomain = if (pDomainMatch.find()) pDomainMatch.group(1)!! else "https://vidsrc.buzz"
                val pHtml = app.get(
                    playerUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to "https://www.2embed.cc/"
                    )
                ).text

                // Đọc <base href="..."> để gọi đúng /pl/api.php (vidsrc.buzz) hoặc /api.php (videm.buzz)
                val baseMatcher = Pattern.compile(
                    """<base[^>]+href=["']([^"']+)["']""",
                    Pattern.CASE_INSENSITIVE
                ).matcher(pHtml)
                val baseHref = if (baseMatcher.find()) baseMatcher.group(1) ?: "/" else "/"
                val apiBaseUrl = resolveUrl(playerUrl, baseHref).trimEnd('/')

                val qPattern = Pattern.compile("""var Q\s*=\s*(\{.*?\});""", Pattern.DOTALL)
                val qMatcher = qPattern.matcher(pHtml)
                if (!qMatcher.find()) continue
                val qJson = JSONObject(qMatcher.group(1)!!)
                val token = qJson.optString("t")
                val qType = qJson.optString("type", type)
                val qId = qJson.optString("id", tmdbId.toString())
                val qS = qJson.optInt("s", season ?: 0)
                val qE = qJson.optInt("e", episode ?: 0)

                val apiHeaders = mapOf(
                    "User-Agent" to ua,
                    "Referer" to playerUrl,
                    "Origin" to pDomain,
                    "Accept" to "application/json"
                )

                // Phụ đề OpenSubtitles (ưu tiên api.php?a=sub trả text/vtt thay cho link .gz)
                try {
                    val subsUrl = "$apiBaseUrl/api.php?a=subs&type=$qType&id=$qId&s=$qS&e=$qE" +
                            "&t=${URLEncoder.encode(token, "UTF-8")}"
                    val subsRes = app.get(subsUrl, headers = apiHeaders).text

                    if (subsRes.trimStart().startsWith("{")) {
                        val subsJson = JSONObject(subsRes)
                        val subsArray = subsJson.optJSONArray("subtitles")
                            ?: subsJson.optJSONArray("subs")
                        if (subsArray != null) {
                            for (i in 0 until subsArray.length()) {
                                val sub = subsArray.getJSONObject(i)
                                val label = sub.optString(
                                    "label",
                                    sub.optString("lang", "Subtitle")
                                )
                                val subRef = sub.optString("ref")
                                var directUrl = sub.optString("url")

                                if (directUrl.isEmpty() && subRef.isNotEmpty()) {
                                    try {
                                        val payload = subRef.substringBefore(".")
                                        val decodedBytes = Base64.decode(
                                            payload,
                                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                                        )
                                        val decodedJson = JSONObject(String(decodedBytes))
                                        directUrl = decodedJson.optString("u")
                                    } catch (_: Exception) { }
                                }

                                val finalSubUrl = if (directUrl.isNotEmpty() && !directUrl.endsWith(".gz", ignoreCase = true)) {
                                    directUrl
                                } else if (subRef.isNotEmpty()) {
                                    "$apiBaseUrl/api.php?a=sub&ref=${URLEncoder.encode(subRef, "UTF-8")}"
                                } else {
                                    directUrl
                                }

                                if (finalSubUrl.isNotEmpty() && registeredSubs.add(finalSubUrl)) {
                                    subtitleCallback.invoke(
                                        SubtitleFile("$label (OpenSubs)", finalSubUrl)
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    ClipboardLogger.logAndCopy("Vidsrc Subs Error", e.message ?: "")
                }

                // Các luồng máy chủ vidsrc / videm
                val servers = qJson.optJSONObject("ssr")?.optJSONArray("servers")
                if (servers != null) {
                    val serverList = (0 until servers.length()).map { servers.getJSONObject(it) }
                    val streamHeaders = mapOf(
                        "Origin" to pDomain,
                        "Referer" to "$pDomain/",
                        "User-Agent" to ua
                    )

                    val resolvedLinks = serverList.amap { serverObj ->
                        val sName = serverObj.optString("name", "Server")
                        val sRef = serverObj.optString("ref")
                        if (sRef.isEmpty()) return@amap null
                        try {
                            val playApiUrl = "$apiBaseUrl/api.php?a=play" +
                                    "&ref=${URLEncoder.encode(sRef, "UTF-8")}" +
                                    "&t=${URLEncoder.encode(token, "UTF-8")}"
                            val playRes = app.get(playApiUrl, headers = apiHeaders).text
                            if (!playRes.trimStart().startsWith("{")) {
                                ClipboardLogger.log("Server Play Skip", "$sName: non-JSON response")
                                return@amap null
                            }

                            val playJson = JSONObject(playRes)
                            val rawStreamUrl = playJson.optString("url")
                            if (rawStreamUrl.isEmpty()) return@amap null

                            // Giữ nguyên root path ("/_stream?id=...") không tự ý ghép "/pl"
                            val resolvedStreamUrl = resolveUrl(playerUrl, rawStreamUrl).substringBefore("#")
                            val isHls = !playJson.optString("type", "hls").equals("mp4", true)

                            if (isHls) {
                                val probe = app.get(resolvedStreamUrl, headers = streamHeaders)
                                if (probe.code !in 200..299 || !probe.text.trimStart().startsWith("#EXTM3U")) {
                                    ClipboardLogger.log("Vidsrc Probe Skip", "$sName HTTP ${probe.code}")
                                    return@amap null
                                }
                            }

                            safeCreateExtractorLink(
                                source = this.name,
                                name = "2embed - $sName",
                                url = resolvedStreamUrl,
                                referer = "$pDomain/",
                                quality = Qualities.P1080.value,
                                isM3u8 = isHls,
                                headers = streamHeaders
                            )
                        } catch (e: Exception) {
                            if (e is java.util.concurrent.CancellationException) throw e
                            ClipboardLogger.logAndCopy("Server Play Error", "$sName: ${e.message ?: ""}")
                            null
                        }
                    }.filterNotNull()

                    for (link in resolvedLinks) {
                        if (seenUrls.add(link.url)) {
                            callback.invoke(link)
                            foundLink = true
                            addedVidsrcServers++
                            ClipboardLogger.logAndCopy("Stream Added", "${link.name} (vidsrc)")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Player Scraping Error", e.message ?: "")
            }
        }

        if (foundLink) {
            TwoNembedApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)
        }
        return foundLink
    }
}

// Typealias
typealias TwoEmbedProvider = TwoNembedProvider
