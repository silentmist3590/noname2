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
private const val TMDB_API_KEY = "b9e1ff7ff423790bf7df202e661377ca"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = "" 

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
    override var mainUrl = TMDB_BASE_URL
    override var name = "Vidscmov"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$mainUrl/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$mainUrl/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("${request.data}&page=$page").text
        val json = JSONObject(response)
        val results = json.getJSONArray("results")
        val home = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = item.optString("poster_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w500$it" else null }
            home.add(newMovieSearchResponse(title, "vidscmov:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/search/multi?api_key=$TMDB_API_KEY&query=$query").text
        val json = JSONObject(response)
        val results = json.getJSONArray("results")
        val searchResults = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = item.optString("poster_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w500$it" else null }
            searchResults.add(newMovieSearchResponse(title, "vidscmov:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split(":")
        if (parts.size < 3) return null
        val mediaType = parts[1]
        val tmdbId = parts[2]

        val response = app.get("$mainUrl/$mediaType/$tmdbId?api_key=$TMDB_API_KEY").text
        val json = JSONObject(response)
        val title = json.optString("title", json.optString("name"))
        val description = json.optString("overview")
        val poster = json.optString("poster_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w500$it" else null }
        val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
        val imdbId = json.optString("imdb_id")

        var imdbScore: Score? = null
        if (imdbId.isNotEmpty()) {
            ApiServices.getOmdbData(imdbId)?.let { ratingStr ->
                imdbScore = Score.from10(ratingStr.toDouble())
            }
        }

        val episodes = mutableListOf<Episode>()
        if (mediaType == "tv") {
            val seasonsCount = json.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val seasonRes = app.get("$mainUrl/tv/$tmdbId/season/$s?api_key=$TMDB_API_KEY").text
                    val seasonJson = JSONObject(seasonRes)
                    val eps = seasonJson.getJSONArray("episodes")
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        episodes.add(newEpisode("vidscmov:tv:$tmdbId:${ep.getInt("season_number")}:${ep.getInt("episode_number")}") {
                            this.name = ep.optString("name")
                            this.episode = ep.getInt("episode_number")
                            this.season = ep.getInt("season_number")
                            this.description = ep.optString("overview")
                            this.posterUrl = ep.optString("still_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w300$it" else null }
                        })
                    }
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Season Load Error", "Season $s: ${e.message}")
                }
            }
            return newTvSeriesLoadResponse(title, "vidscmov:tv:$tmdbId", TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "vidscmov:movie:$tmdbId") {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
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
        val d = a.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        var c = ""
        for (e in d.indices) { c += (d[e].code xor b[e % b.length].code).toChar() }
        var e = ""
        for (ch in c) { e += (ch.code - 3).toChar() }
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
        val d = b.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        var e = ""
        for (i in d.indices) { e += (d[i].code xor c[i % c.length].code).toChar() }
        return e
    }

    private fun MyL1IRSfHe(a: String): String {
        val b = a.reversed()
        val c = b.map { (it.code - 1).toChar() }.joinToString("")
        return c.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
    }

    private fun detdj7JHiK(a: String): String {
        val b = a.substring(10, a.length - 16)
        val c = "3SAY~#%Y(V%>5d/Yg\"\$G[Lh1rK4a;7ok"
        val d = String(Base64.getDecoder().decode(b))
        val e = c.repeat((d.length + c.length - 1) / c.length).substring(0, d.length)
        var f = ""
        for (i in d.indices) { f += (d[i].code xor e[i].code).toChar() }
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
        for (ch in d) { e += (ch.code - 5).toChar() }
        return e
    }

    private fun GuxKGDsA2T(a: String): String {
        val b = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.getDecoder().decode(b))
        var e = ""
        for (ch in d) { e += (ch.code - 7).toChar() }
        return e
    }

    private fun LXVUMCoAHJ(a: String): String {
        val b = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.getDecoder().decode(b))
        var e = ""
        for (ch in d) { e += (ch.code - 3).toChar() }
        return e
    }

    // =====================================================================================
    // HÀM CHÍNH: LOAD LINKS
    // =====================================================================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        val parts = data.split(":")
        if (parts.size < 3) return false
        val type = parts[1]
        val tmdbId = parts[2]

        if (!tmdbId.all { it.isDigit() }) {
            ClipboardLogger.logAndCopy("Error", "TMDB ID không hợp lệ: $tmdbId")
            return false
        }

        ApiServices.sendTraktScrobble("start", type, tmdbId.toIntOrNull() ?: 0, 0f,
            if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
            if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
        )

        val embedUrl = if (type == "movie") {
            "https://vidsrc.mov/embed/movie/$tmdbId"
        } else {
            if (parts.size < 5) return false
            "https://vidsrc.mov/embed/tv/$tmdbId/${parts[3]}/${parts[4]}"
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to "https://vidsrc.mov/",
            "Origin" to "https://vidsrc.mov"
        )

        try {
            ClipboardLogger.logAndCopy("API Request", "Embed URL: $embedUrl")
            val embedResponse = app.get(embedUrl, headers = headers).text
            ClipboardLogger.logAndCopy("Embed Response", embedResponse)

            // Bước 1: Tìm iframe (trỏ đến vidsrc.sh)
            val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val iframeUrl = iframeRegex.find(embedResponse)?.groupValues?.get(1)

            if (iframeUrl == null) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy iframe trong vidsrc.mov.")
                return false
            }
            ClipboardLogger.logAndCopy("Iframe Found", iframeUrl)

            // Bước 2: Fetch trang vidsrc.sh
            val iframeResponse = app.get(iframeUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Referer" to "https://vidsrc.mov/"
            )).text
            ClipboardLogger.logAndCopy("Iframe Response", iframeResponse)

            // Bước 3: Giải mã link từ thẻ div
            val rcpResponse = app.get(iframeUrl, headers = headers)
            var finalUrl: String? = null
            
            val encodedElement = rcpResponse.document.select("div#reporting_content+div")
            if (encodedElement != null) {
                val encType = encodedElement.attr("id")
                val encodedUrl = encodedElement.text()
                ClipboardLogger.logAndCopy("Decode Info", "Type: $encType, URL: $encodedUrl")
                finalUrl = decodeUrl(encType, encodedUrl)
            }

            // Bước 4: Nếu không giải mã được, thử tìm link m3u8 trực tiếp trong HTML
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

            ApiServices.sendTraktScrobble("stop", type, tmdbId.toIntOrNull() ?: 0, 100f,
                if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
                if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
            )

            // Xử lý phụ đề (nếu có)
            val subtitleRegex = Regex("""(https?://[^"']*\.(?:vtt|srt))""", RegexOption.IGNORE_CASE)
            subtitleRegex.findAll(iframeResponse).forEach { match ->
                val subUrl = match.groupValues[1]
                if (subUrl.isNotEmpty()) {
                    subtitleCallback.invoke(SubtitleFile("Unknown", subUrl))
                }
            }

            // Bước 5: Gửi link với Referer chính xác đã bắt được từ Sniff
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Vidscmov",
                    url = finalUrl,
                    referer = "https://stellarconductornexus.com/", // BẮT BUỘC THEO SNIFF
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
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
