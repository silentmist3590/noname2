package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import java.net.URLEncoder

// =====================================================================================
// 1. CẤU HÌNH TMDB VÀ PROXY ẢNH (GIỮ NGUYÊN)
// =====================================================================================
private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

object TmdbConfig {
    val apiKeys = listOf(
        "e716f19ab4d25edc5247239a8f3494f8",
        "8476a7ab80ad76f0936744df0430e67c",
        "439c478a771f35c05022f9feabcca01c",
        "b9e1ff7ff423790bf7df202e661377ca"
    )
    val directDomains = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3",
        "https://tmdb.demojameson.de5.net/3",
        "https://daili.smdhb.com/3"
    )
    val corsProxies: List<(String) -> String> = listOf(
        { url -> "https://api.codetabs.com/v1/proxy/?quest=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://api.allorigins.win/raw?url=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://cors.eu.org/$url" },
        { url -> "https://corsproxy.io/?url=${URLEncoder.encode(url, "UTF-8")}" }
    )
    private const val IMG_PROXY = "https://wsrv.nl/?url="

    suspend fun getTmdbData(pathWithQuery: String): JSONObject? {
        val path = if (pathWithQuery.startsWith("/")) pathWithQuery else "/$pathWithQuery"
        for (key in apiKeys) {
            for (domain in directDomains) {
                try {
                    val sep = if (path.contains("?")) "&" else "?"
                    val res = app.get("$domain$path${sep}api_key=$key").text
                    if (res.startsWith("{") && !res.contains("Invalid API key")) {
                        val json = JSONObject(res)
                        if (json.has("results") || json.has("id") || json.has("success")) return json
                    }
                } catch (e: Exception) {}
            }
            for (proxy in corsProxies) {
                try {
                    val sep = if (path.contains("?")) "&" else "?"
                    val res = app.get(proxy("https://api.themoviedb.org/3$path${sep}api_key=$key")).text
                    if (res.startsWith("{") && !res.contains("Invalid API key")) {
                        val json = JSONObject(res)
                        if (json.has("results") || json.has("id") || json.has("success")) return json
                    }
                } catch (e: Exception) {}
            }
        }
        return null
    }
    fun safeImageUrl(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        return "$IMG_PROXY${URLEncoder.encode("https://image.tmdb.org/t/p/w500$path", "UTF-8")}"
    }
}

// =====================================================================================
// 2. API HỖ TRỢ (GIỮ NGUYÊN)
// =====================================================================================
object ApiServices {
    suspend fun getOmdbData(imdbId: String): String? {
        return try {
            val res = app.get("https://www.omdbapi.com/?apikey=$OMDB_API_KEY&i=$imdbId").text
            val json = JSONObject(res)
            val rating = json.optString("imdbRating")
            if (rating.isNotEmpty() && rating != "N/A") rating else null
        } catch (e: Exception) { null }
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
            val movie = JSONObject(); val ids = JSONObject(); ids.put("tmdb", tmdbId); movie.put("ids", ids); body.put("movie", movie)
        } else {
            val episodeObj = JSONObject(); episodeObj.put("season", season ?: 1); episodeObj.put("number", episode ?: 1); body.put("episode", episodeObj)
        }
        try { app.post(url, headers = headers, json = body).text } catch (e: Exception) {}
    }
}

// =====================================================================================
// 3. LỚP PROVIDER CHÍNH
// =====================================================================================
class VidsrcsuProvider : MainAPI() {
    override var mainUrl = "https://vidsrc.su"
    override var name = "Vidsrcsu"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = TmdbConfig.getTmdbData("${request.data}?page=$page")
        if (json == null || !json.has("results")) return newHomePageResponse(request.name, emptyList())
        val results = json.getJSONArray("results")
        val home = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = TmdbConfig.safeImageUrl(item.optString("poster_path"))
            home.add(newMovieSearchResponse(title, "vidsrcsu:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = TmdbConfig.getTmdbData("/search/multi?query=${URLEncoder.encode(query, "UTF-8")}") ?: return emptyList()
        if (!json.has("results")) return emptyList()
        val results = json.getJSONArray("results")
        val searchResults = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = TmdbConfig.safeImageUrl(item.optString("poster_path"))
            searchResults.add(newMovieSearchResponse(title, "vidsrcsu:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val idMatch = Regex("""vidsrcsu:(movie|tv):(\d+)""").find(url) ?: return null
        val mediaType = idMatch.groupValues[1]
        val tmdbIdInt = idMatch.groupValues[2].toIntOrNull() ?: return null
        val json = TmdbConfig.getTmdbData("/$mediaType/$tmdbIdInt") ?: return null
        val title = json.optString("title", json.optString("name"))
        val description = json.optString("overview")
        val poster = TmdbConfig.safeImageUrl(json.optString("poster_path"))
        val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
        val imdbId = json.optString("imdb_id")
        var imdbScore: Score? = null
        if (imdbId.isNotEmpty()) {
            ApiServices.getOmdbData(imdbId)?.let { imdbScore = Score.from10(it.toDouble()) }
        }
        val episodes = mutableListOf<Episode>()
        if (mediaType == "tv") {
            val seasonsCount = json.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val seasonJson = TmdbConfig.getTmdbData("/tv/$tmdbIdInt/season/$s") ?: continue
                    val eps = seasonJson.getJSONArray("episodes")
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        episodes.add(newEpisode("vidsrcsu:tv:$tmdbIdInt:${ep.getInt("season_number")}:${ep.getInt("episode_number")}") {
                            this.name = ep.optString("name")
                            this.episode = ep.getInt("episode_number")
                            this.season = ep.getInt("season_number")
                            this.description = ep.optString("overview")
                            this.posterUrl = TmdbConfig.safeImageUrl(ep.optString("still_path"))
                        })
                    }
                } catch (e: Exception) {}
            }
            return newTvSeriesLoadResponse(title, "vidsrcsu:tv:$tmdbIdInt", TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "vidsrcsu:movie:$tmdbIdInt") {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        
        val idMatch = Regex("""vidsrcsu:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(data) ?: return false
        val type = idMatch.groupValues[1]
        val tmdbId = idMatch.groupValues[2].toIntOrNull() ?: return false
        val season = idMatch.groupValues.getOrNull(3)?.toIntOrNull()
        val episode = idMatch.groupValues.getOrNull(4)?.toIntOrNull()

        ApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to "https://vidsrc.su/",
            "Origin" to "https://vidsrc.su"
        )

        try {
            // BƯỚC 1: Gọi trang /movie/{id} hoặc /tv/{id}/{s}/{e} (KHÔNG DÙNG /embed/)
            val pageUrl = if (type == "movie") {
                "https://vidsrc.su/movie/$tmdbId"
            } else {
                if (season == null || episode == null) return false
                "https://vidsrc.su/tv/$tmdbId/$season/$episode"
            }
            
            ClipboardLogger.logAndCopy("API Request", "Page URL: $pageUrl")
            val pageResponse = app.get(pageUrl, headers = headers).text
            ClipboardLogger.logAndCopy("Page Response", pageResponse)

            // BƯỚC 2: Tìm link m3u8 trong HTML
            var finalUrl: String? = null
            val m3u8Regex = Regex("""(https?://[^"']*\.m3u8[^"']*)""", RegexOption.IGNORE_CASE)
            finalUrl = m3u8Regex.find(pageResponse)?.groupValues?.get(1)

            // BƯỚC 3: Nếu không có, thử gọi API nội bộ (thử một số endpoint phổ biến)
            if (finalUrl.isNullOrEmpty()) {
                val apiEndpoints = listOf(
                    "https://vidsrc.su/rcp/movie/$tmdbId",
                    "https://vidsrc.su/api/movie/$tmdbId",
                    "https://vidsrc.su/rcp/tv/$tmdbId/$season/$episode",
                    "https://vidsrc.su/api/tv/$tmdbId/$season/$episode"
                )
                for (api in apiEndpoints) {
                    try {
                        val apiRes = app.get(api, headers = headers).text
                        ClipboardLogger.logAndCopy("API Try", "URL: $api\nResponse: $apiRes")
                        val match = m3u8Regex.find(apiRes)?.groupValues?.get(1)
                        if (match != null) {
                            finalUrl = match
                            break
                        }
                    } catch (e: Exception) {}
                }
            }

            // BƯỚC 4: Nếu vẫn không có, thử tìm trong script của trang
            if (finalUrl.isNullOrEmpty()) {
                val scriptRegex = Regex("""(?:file|src|source):\s*["'](.*?m3u8.*?)["']""", RegexOption.IGNORE_CASE)
                finalUrl = scriptRegex.find(pageResponse)?.groupValues?.get(1)
            }

            if (finalUrl.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy link m3u8 trong trang hoặc API của Vidsrc.su.")
                return false
            }
            
            ClipboardLogger.logAndCopy("Success", "Final Stream URL: $finalUrl")

            ApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)

            // Xử lý phụ đề
            val subtitleRegex = Regex("""(https?://[^"']*\.(?:vtt|srt))""", RegexOption.IGNORE_CASE)
            subtitleRegex.findAll(pageResponse).forEach { match ->
                val subUrl = match.groupValues[1]
                if (subUrl.isNotEmpty()) {
                    subtitleCallback.invoke(SubtitleFile("Unknown", subUrl))
                }
            }

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Vidsrcsu",
                    url = finalUrl,
                    referer = "https://vidsrc.su/", // Referer bắt buộc từ log sniff
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf("Referer" to "https://vidsrc.su/", "Origin" to "https://vidsrc.su")
                )
            )
            return true
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
