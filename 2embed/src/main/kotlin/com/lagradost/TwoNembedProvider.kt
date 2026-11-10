package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.regex.Pattern

// =====================================================================================
// STUB LOGGER (nếu project đã có ClipboardLogger ở file khác → XÓA object này đi)
// =====================================================================================
object ClipboardLogger {
    fun logAndCopy(tag: String, message: String) {
        android.util.Log.d("2embed", "[$tag] $message")
    }
}

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
            val response = app.get("$TMDB_BASE_URL/$mediaType/$tmdbIdInt?api_key=$TMDB_API_KEY").text
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
                        val seasonJson = JSONObject(
                            app.get("$TMDB_BASE_URL/tv/$tmdbIdInt/season/$s?api_key=$TMDB_API_KEY").text
                        )
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
        val finalUrl = if (isM3u8 && !url.contains(".m3u8")) "$url#file.m3u8" else url

        return newExtractorLink(
            source = source,
            name = name,
            url = finalUrl,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = headers
        }
    }

    // =====================================================================================
    // 5. VIDEO INTERCEPTOR
    // =====================================================================================
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
            val url = request.url.toString()

            if (url.contains("tiktokcdn.com") || url.contains(".image")) {
                val body = response.body
                if (body != null) {
                    val bytes = body.bytes()
                    if (bytes.size > 70 &&
                        bytes[0] == 0x89.toByte() &&
                        bytes[1] == 0x50.toByte() &&
                        bytes[70] == 0x47.toByte()
                    ) {
                        val stripped = bytes.copyOfRange(70, bytes.size)
                        val mediaType = "video/mp2t".toMediaType()
                        return@Interceptor response.newBuilder()
                            .body(stripped.toResponseBody(mediaType))
                            .header("Content-Type", "video/mp2t")
                            .build()
                    } else {
                        val mediaType = body.contentType()
                        return@Interceptor response.newBuilder()
                            .body(bytes.toResponseBody(mediaType))
                            .build()
                    }
                }
            }
            response
        }
    }

    // =====================================================================================
    // 6. LOAD LINKS
    // =====================================================================================
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

        // ---------------------------------------------------------------------------------
        // NGUỒN 1: NontonGo Direct Player (svsoapp)
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

            // 1. Phụ đề từ <track> — dùng Pattern.CASE_INSENSITIVE (không thể shadow)
            val trackPattern = Pattern.compile(
                """<track[^>]+(?:data-src|src)=["']([^"']+)["'][^>]*label=["']([^"']+)["']|<track[^>]+label=["']([^"']+)["'][^>]+(?:data-src|src)=["']([^"']+)["']""",
                Pattern.CASE_INSENSITIVE
            )
            val trackMatcher = trackPattern.matcher(playerHtml)
            while (trackMatcher.find()) {
                val subUrl = (trackMatcher.group(1) ?: "").ifEmpty { trackMatcher.group(4) ?: "" }
                val label = (trackMatcher.group(2) ?: "").ifEmpty { trackMatcher.group(3) ?: "" }
                if (subUrl.isNotEmpty() && !registeredSubs.contains(subUrl)) {
                    registeredSubs.add(subUrl)
                    subtitleCallback.invoke(SubtitleFile(label, subUrl))
                    ClipboardLogger.logAndCopy("Sub Added", "$label -> $subUrl")
                }
            }

            // 2. Stream từ nút button — dùng Pattern.DOTALL (không thể shadow)
            val buttonPattern = Pattern.compile(
                """<button[^>]*data-url=["']([^"']+)["'][^>]*>(.*?)</button>""",
                Pattern.DOTALL
            )
            val buttonMatcher = buttonPattern.matcher(playerHtml)
            while (buttonMatcher.find()) {
                val rawUrl = (buttonMatcher.group(1) ?: "").replace("&amp;", "&")
                val labelHtml = buttonMatcher.group(2) ?: ""
                val serverName = labelHtml.replace(Regex("<[^>]+>"), "").trim()
                    .ifEmpty { "Fast Server" }

                if (rawUrl.contains("svsoapp") && rawUrl.contains(".m3u8")) {
                    val link = safeCreateExtractorLink(
                        source = this.name,
                        name = "2embed - $serverName",
                        url = rawUrl,
                        referer = "https://nontongo.win/",
                        quality = if (serverName.contains("HD", true))
                            Qualities.P1080.value else Qualities.P720.value,
                        isM3u8 = true,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to "https://nontongo.win/",
                            "Origin" to "https://nontongo.win"
                        )
                    )
                    callback.invoke(link)
                    foundLink = true
                    ClipboardLogger.logAndCopy("Stream Added", "Server: $serverName (svsoapp)")
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("NontonGo Error", e.message ?: "")
        }

        // ---------------------------------------------------------------------------------
        // NGUỒN 2: vidsrc.buzz / videm.buzz
        // ---------------------------------------------------------------------------------
        val embedCandidates = mutableListOf<String>()
        if (type == "movie") {
            embedCandidates.add("https://vidsrc.buzz/embed/movie/$tmdbId")
            embedCandidates.add("https://videm.buzz/embed/movie/$tmdbId")
        } else {
            embedCandidates.add("https://vidsrc.buzz/embed/tv/$tmdbId/${season ?: 1}/${episode ?: 1}")
            embedCandidates.add("https://videm.buzz/embed/tv/$tmdbId/${season ?: 1}/${episode ?: 1}")
        }

        for (playerUrl in embedCandidates) {
            try {
                val pDomainMatch = Pattern.compile("""^(https?://[^/]+)""").matcher(playerUrl)
                val pDomain = if (pDomainMatch.find()) pDomainMatch.group(1) else "https://vidsrc.buzz"
                val pHtml = app.get(
                    playerUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to "https://www.2embed.cc/"
                    )
                ).text

                // Pattern.DOTALL — không thể shadow
                val qPattern = Pattern.compile("""var Q\s*=\s*(\{.*?\});""", Pattern.DOTALL)
                val qMatcher = qPattern.matcher(pHtml)
                if (!qMatcher.find()) continue
                val qJson = JSONObject(qMatcher.group(1))
                val token = qJson.optString("t")
                val qType = qJson.optString("type", type)
                val qId = qJson.optString("id", tmdbId.toString())
                val qS = qJson.optInt("s", season ?: 0)
                val qE = qJson.optInt("e", episode ?: 0)

                // Phụ đề OpenSubtitles
                try {
                    val subsUrl = "$pDomain/pl/api.php?a=subs&type=$qType&id=$qId&s=$qS&e=$qE" +
                            "&t=${URLEncoder.encode(token, "UTF-8")}"
                    val subsRes = app.get(
                        subsUrl,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to playerUrl,
                            "Origin" to pDomain,
                            "Accept" to "application/json"
                        )
                    ).text

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
                                        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                                        val decodedBytes = Base64.getUrlDecoder().decode(padded)
                                        val decodedJson = JSONObject(String(decodedBytes))
                                        directUrl = decodedJson.optString("u")
                                    } catch (_: Exception) { }
                                }

                                val finalSubUrl = if (directUrl.isNotEmpty()) directUrl
                                else "$pDomain/pl/api.php?a=sub&ref=${URLEncoder.encode(subRef, "UTF-8")}"

                                if (!registeredSubs.contains(finalSubUrl)) {
                                    registeredSubs.add(finalSubUrl)
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

                // Các luồng máy chủ vidsrc
                val servers = qJson.optJSONObject("ssr")?.optJSONArray("servers")
                if (servers != null) {
                    for (i in 0 until servers.length()) {
                        try {
                            val serverObj = servers.getJSONObject(i)
                            val sName = serverObj.optString("name", "Server ${i + 1}")
                            val sRef = serverObj.optString("ref")

                            val playApiUrl = "$pDomain/pl/api.php?a=play" +
                                    "&ref=${URLEncoder.encode(sRef, "UTF-8")}" +
                                    "&t=${URLEncoder.encode(token, "UTF-8")}"
                            val playRes = app.get(
                                playApiUrl,
                                headers = mapOf(
                                    "User-Agent" to ua,
                                    "Referer" to playerUrl,
                                    "Origin" to pDomain,
                                    "Accept" to "application/json"
                                )
                            ).text

                            val playJson = JSONObject(playRes)
                            var streamUrl = playJson.optString("url")
                            if (streamUrl.isEmpty()) continue

                            if (streamUrl.startsWith("/")) {
                                streamUrl = if (streamUrl.startsWith("/pl/"))
                                    "$pDomain$streamUrl"
                                else "$pDomain/pl$streamUrl"
                            }

                            val link = safeCreateExtractorLink(
                                source = this.name,
                                name = "2embed - $sName",
                                url = streamUrl,
                                referer = "$pDomain/",
                                quality = Qualities.P1080.value,
                                isM3u8 = true,
                                headers = mapOf(
                                    "Origin" to pDomain,
                                    "Referer" to "$pDomain/",
                                    "User-Agent" to ua
                                )
                            )
                            callback.invoke(link)
                            foundLink = true
                            ClipboardLogger.logAndCopy("Stream Added", "Server: $sName (vidsrc)")
                        } catch (e: Exception) {
                            if (e is java.util.concurrent.CancellationException) throw e
                            ClipboardLogger.logAndCopy("Server Play Error", e.message ?: "")
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
