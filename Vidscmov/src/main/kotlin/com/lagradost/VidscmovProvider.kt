package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import java.util.Base64

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ PROXY
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

// Định danh nội bộ: "vidscmov:movie:<id>" / "vidscmov:tv:<id>" / "vidscmov:tv:<id>:<s>:<e>".
// CloudStream có thể tự thêm mainUrl vào trước chuỗi này (fixUrl) nên LUÔN phân tích bằng regex.
private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")

private class Ref(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): Ref? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return Ref(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

// =====================================================================================
// 2. CÁC HÀM GỌI API HỖ TRỢ
// =====================================================================================
object ApiServices {
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

    suspend fun sendTraktScrobble(action: String, type: String, tmdbId: Int, progress: Float, season: Int? = null, episode: Int? = null) {
        if (TRAKT_ACCESS_TOKEN.isEmpty()) return
        val url = "https://api.trakt.tv/scrobble/$action"
        val headers = mapOf(
            "Content-Type" to "application/json", "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID, "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
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
class VidscmovProvider : MainAPI() {
    // mainUrl KHÔNG được là địa chỉ proxy TMDB: CloudStream ghép mainUrl vào trước mọi url không bắt đầu bằng http
    // (vd "vidscmov:tv:1" -> "<proxy>/3/vidscmov:tv:1") làm load() phân tích sai và báo "This provider does not exist".
    override var mainUrl = "https://vidsrc.mov"
    override var name = "Vidscmov"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$TMDB_BASE_URL/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$TMDB_BASE_URL/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$TMDB_BASE_URL/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    private fun poster(path: String?) = path?.takeIf { it.isNotEmpty() && it != "null" }?.let { "https://image.tmdb.org/t/p/w500$it" }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = app.get("${request.data}&page=$page").text
            val json = JSONObject(response)
            val results = json.optJSONArray("results") ?: throw Exception(json.optString("status_message", "TMDB không trả results"))
            val home = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                home.add(newMovieSearchResponse(title, "vidscmov:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
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
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(app.get("$TMDB_BASE_URL/search/multi?api_key=$TMDB_API_KEY&query=$q").text)
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                out.add(newMovieSearchResponse(title, "vidscmov:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
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
            val poster = poster(json.optString("poster_path"))
            val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
            val imdbId = json.optString("imdb_id")

            var imdbScore: Score? = null
            if (imdbId.isNotEmpty()) {
                ApiServices.getOmdbData(imdbId)?.let { ratingStr ->
                    ratingStr.toDoubleOrNull()?.let { imdbScore = Score.from10(it) }
                }
            }

            val canonical = "vidscmov:$mediaType:$tmdbIdInt"
            if (mediaType == "tv") {
                val episodes = mutableListOf<Episode>()
                val seasonsCount = json.optInt("number_of_seasons", 1)
                for (s in 1..seasonsCount) {
                    try {
                        val seasonJson = JSONObject(app.get("$TMDB_BASE_URL/tv/$tmdbIdInt/season/$s?api_key=$TMDB_API_KEY").text)
                        val eps = seasonJson.optJSONArray("episodes") ?: continue
                        for (e in 0 until eps.length()) {
                            val ep = eps.getJSONObject(e)
                            val sn = ep.optInt("season_number", s)
                            val en = ep.optInt("episode_number", e + 1)
                            episodes.add(newEpisode("vidscmov:tv:$tmdbIdInt:$sn:$en") {
                                this.name = ep.optString("name")
                                this.episode = en
                                this.season = sn
                                this.description = ep.optString("overview")
                                this.posterUrl = ep.optString("still_path").let { if (it.isNotEmpty() && it != "null") "https://image.tmdb.org/t/p/w300$it" else null }
                            })
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.logAndCopy("Season Load Error", "Season $s: ${e.message}")
                    }
                }
                return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                    this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
                }
            } else {
                return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                    this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("load Error", "$url -> ${e.message}")
            return null
        }
    }

    // =====================================================================================
    // CÁC HÀM GIẢI MÃ (DECRYPTORS) TỪ VIDSRC
    // =====================================================================================
    private fun decodeUrl(encType: String, url: String): String? {
        return when (encType) {
            "NdonQLf1Tzyx7bMG" -> bMGyx71TzQLfdonN(url)
            "sXnL9MQIry" -> Iry9MQXnLs(url)
            "IhWrImMIGL" -> IGLImMhWrI(url)
            "xTyBxQyGTA" -> GTAxQyTyBx(url)
            "ux8qjPHC66" -> C66jPHx8qu(url)
            "eSfH1IRMyL" -> MyL1IRSfHe(url)
            "KJHidj7det" -> detdj7JHiK(url)
            "o2VSUnjnZl" -> nZlUnj2VSo(url)
            "Oi3v1dAlaM" -> laM1dAi3vO(url)
            "TsA2KGDGux" -> GuxKGDsA2T(url)
            "JoAHUMCLXV" -> LXVUMCoAHJ(url)
            else -> null
        }
    }

    private fun bMGyx71TzQLfdonN(a: String): String {
        val b = 3; val c = mutableListOf<String>(); var d = 0
        while (d < a.length) { c.add(a.substring(d, minOf(d + b, a.length))); d += b }
        return c.reversed().joinToString("")
    }

    private fun Iry9MQXnLs(a: String): String {
        val b = "pWB9V)[*4I`nJpp?ozyB~dbr9yt!_n4u"
        val d = a.chunked(2).map { Char(it.toInt(16)) }.joinToString("")
        var c = ""
        for (e in d.indices) { c += Char(d[e].code xor b[e % b.length].code) }
        var e = ""
        for (ch in c) { e += Char(ch.code - 3) }
        return String(Base64.getDecoder().decode(e))
    }

    private fun IGLImMhWrI(a: String): String {
        val b = a.reversed()
        val c = b.map { when (it) { in 'a'..'m', in 'A'..'M' -> it + 13; in 'n'..'z', in 'N'..'Z' -> it - 13; else -> it } }.joinToString("")
        val d = c.reversed()
        return String(Base64.getDecoder().decode(d))
    }

    private fun GTAxQyTyBx(a: String): String {
        val b = a.reversed()
        val c = b.filterIndexed { index, _ -> index % 2 == 0 }
        return String(Base64.getDecoder().decode(c))
    }

    private fun C66jPHx8qu(a: String): String {
        val b = a.reversed()
        val c = "X9a(O;FMV2-7VO5x;Ao:dN1NoFs?j,"
        val d = b.chunked(2).map { Char(it.toInt(16)) }.joinToString("")
        var e = ""
        for (i in d.indices) { e += Char(d[i].code xor c[i % c.length].code) }
        return e
    }

    private fun MyL1IRSfHe(a: String): String {
        val b = a.reversed()
        val c = b.map { Char(it.code - 1) }.joinToString("")
        return c.chunked(2).map { Char(it.toInt(16)) }.joinToString("")
    }

    private fun detdj7JHiK(a: String): String {
        val b = a.substring(10, a.length - 16)
        val c = "3SAY~#%Y(V%>5d/Yg\"\$G[Lh1rK4a;7ok"
        val d = String(Base64.getDecoder().decode(b))
        val e = c.repeat((d.length + c.length - 1) / c.length).substring(0, d.length)
        var f = ""
        for (i in d.indices) { f += Char(d[i].code xor e[i].code) }
        return f
    }

    private fun nZlUnj2VSo(a: String): String {
        val b = mapOf('x' to 'a', 'y' to 'b', 'z' to 'c', 'a' to 'd', 'b' to 'e', 'c' to 'f', 'd' to 'g', 'e' to 'h', 'f' to 'i', 'g' to 'j', 'h' to 'k', 'i' to 'l', 'j' to 'm', 'k' to 'n', 'l' to 'o', 'm' to 'p', 'n' to 'q', 'o' to 'r', 'p' to 's', 'q' to 't', 'r' to 'u', 's' to 'v', 't' to 'w', 'u' to 'x', 'v' to 'y', 'w' to 'z', 'X' to 'A', 'Y' to 'B', 'Z' to 'C', 'A' to 'D', 'B' to 'E', 'C' to 'F', 'D' to 'G', 'E' to 'H', 'F' to 'I', 'G' to 'J', 'H' to 'K', 'I' to 'L', 'J' to 'M', 'K' to 'N', 'L' to 'O', 'M' to 'P', 'N' to 'Q', 'O' to 'R', 'P' to 'S', 'Q' to 'T', 'R' to 'U', 'S' to 'V', 'T' to 'W', 'U' to 'X', 'V' to 'Y', 'W' to 'Z')
        return a.map { b[it] ?: it }.joinToString("")
    }

    private fun laM1dAi3vO(a: String): String {
        val b = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.getDecoder().decode(b))
        var e = ""
        for (ch in d) { e += Char(ch.code - 5) }
        return e
    }

    private fun GuxKGDsA2T(a: String): String {
        val b = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.getDecoder().decode(b))
        var e = ""
        for (ch in d) { e += Char(ch.code - 7) }
        return e
    }

    private fun LXVUMCoAHJ(a: String): String {
        val b = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.getDecoder().decode(b))
        var e = ""
        for (ch in d) { e += Char(ch.code - 3) }
        return e
    }

    // =====================================================================================
    // HÀM CHÍNH: LOAD LINKS
    // =====================================================================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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

        ApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val embedUrl = if (type == "movie") {
            "https://vidsrc.mov/embed/movie/$tmdbId"
        } else {
            if (season == null || episode == null) {
                ClipboardLogger.logAndCopy("Error", "Thiếu season/episode: $data")
                return false
            }
            "https://vidsrc.mov/embed/tv/$tmdbId/$season/$episode"
        }

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        val headers = mapOf("User-Agent" to ua, "Referer" to "https://vidsrc.mov/", "Origin" to "https://vidsrc.mov")

        try {
            ClipboardLogger.logAndCopy("API Request", "Embed URL: $embedUrl")
            val embedResponse = app.get(embedUrl, headers = headers).text
            ClipboardLogger.logAndCopy("Embed Response", embedResponse.take(1500))

            val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            var iframeUrl = iframeRegex.find(embedResponse)?.groupValues?.get(1)
            if (iframeUrl == null) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy iframe trong vidsrc.mov.")
                return false
            }
            if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
            ClipboardLogger.logAndCopy("Iframe Found", iframeUrl)

            val rcpResponse = app.get(iframeUrl, headers = headers)
            val iframeResponse = rcpResponse.text
            ClipboardLogger.logAndCopy("Iframe Response", iframeResponse.take(1500))

            var finalUrl: String? = null
            val encodedElement = rcpResponse.document.select("div#reporting_content+div")
            if (encodedElement.isNotEmpty()) {
                val encType = encodedElement.attr("id")
                val encodedUrl = encodedElement.text()
                ClipboardLogger.logAndCopy("Decode Info", "Type: $encType, URL: ${encodedUrl.take(80)}")
                finalUrl = try { decodeUrl(encType, encodedUrl) } catch (e: Exception) { null }
            }

            if (finalUrl.isNullOrEmpty()) {
                val m3u8Regex = Regex("""(https?://[^"']*\.m3u8[^"']*)""", RegexOption.IGNORE_CASE)
                finalUrl = m3u8Regex.find(iframeResponse)?.groupValues?.get(1)
                    ?: m3u8Regex.find(embedResponse)?.groupValues?.get(1)
            }

            if (finalUrl.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("Error", "Không thể giải mã hoặc tìm thấy link stream cuối cùng.")
                return false
            }

            ClipboardLogger.logAndCopy("Success", "Final Stream URL: $finalUrl")
            ApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)

            val subtitleRegex = Regex("""(https?://[^"']*\.(?:vtt|srt))""", RegexOption.IGNORE_CASE)
            subtitleRegex.findAll(iframeResponse).forEach { match ->
                val subUrl = match.groupValues[1]
                if (subUrl.isNotEmpty()) subtitleCallback.invoke(SubtitleFile("Unknown", subUrl))
            }

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Vidscmov",
                    url = finalUrl,
                    referer = "https://stellarconductornexus.com/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "Origin" to "https://stellarconductornexus.com",
                        "Referer" to "https://stellarconductornexus.com/"
                    )
                )
            )
            return true
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
