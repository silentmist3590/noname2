package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ PROXY
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "b9e1ff7ff423790bf7df202e661377ca"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = "" // Để trống nếu không dùng Trakt


// =====================================================================================
// 2. CÁC HÀM GỌI API HỖ TRỢ (Đã bỏ Gson)
// =====================================================================================
object ApiServices {

    // --- OMDb ---
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

    // --- Trakt ---
    suspend fun sendTraktScrobble(
        action: String, type: String, tmdbId: Int, progress: Float, season: Int? = null, episode: Int? = null
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
    override var mainUrl = TMDB_BASE_URL
    override var name = "2nembed"
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
            home.add(newMovieSearchResponse(title, "2nembed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
            searchResults.add(newMovieSearchResponse(title, "2nembed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
                        episodes.add(newEpisode("2nembed:tv:$tmdbId:${ep.getInt("season_number")}:${ep.getInt("episode_number")}") {
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
            return newTvSeriesLoadResponse(title, "2nembed:tv:$tmdbId", TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "2nembed:movie:$tmdbId") {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        val parts = data.split(":")
        if (parts.size < 3) return false
        val type = parts[1]
        val tmdbId = parts[2]

        ApiServices.sendTraktScrobble("start", type, tmdbId.toIntOrNull() ?: 0, 0f,
            if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
            if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
        )

        val embedUrl = if (type == "movie") {
            "https://www.2embed.cc/embed/$tmdbId"
        } else {
            if (parts.size < 5) return false
            "https://www.2embed.cc/embedtv/$tmdbId&s=${parts[3]}&e=${parts[4]}"
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to "https://www.2embed.cc/",
            "Origin" to "https://www.2embed.cc"
        )

        try {
            ClipboardLogger.logAndCopy("API Request", "Embed URL: $embedUrl")
            var currentHtml = app.get(embedUrl, headers = headers).text
            ClipboardLogger.logAndCopy("Embed Response", currentHtml)

            fun findIframe(html: String): String? {
                val idRegex = Regex("""<iframe[^>]*id=["'](iframesrc|framesrc)["'][^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                idRegex.find(html)?.let { return it.groupValues[2] }
                val generalRegex = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                return generalRegex.find(html)?.groupValues?.get(1)
            }

            var m3u8Url: String? = null
            var depth = 0
            val maxDepth = 5

            while (depth < maxDepth) {
                val m3u8Regex = Regex("""file:\s*["'](.*?m3u8.*?)["']""", RegexOption.IGNORE_CASE)
                m3u8Url = m3u8Regex.find(currentHtml)?.groupValues?.get(1)
                    ?: Regex("""(https?://[^"']*\.m3u8[^"']*)""", RegexOption.IGNORE_CASE).find(currentHtml)?.groupValues?.get(1)

                if (m3u8Url != null) {
                    ClipboardLogger.logAndCopy("Success", "M3U8 URL found at depth $depth: $m3u8Url")
                    break
                }

                val nextIframe = findIframe(currentHtml)
                if (nextIframe == null) {
                    ClipboardLogger.logAndCopy("Info", "Không tìm thấy iframe ở depth $depth.")
                    break
                }

                val absoluteIframeUrl = if (nextIframe.startsWith("http")) {
                    nextIframe
                } else {
                    val baseUrl = when {
                        currentHtml.contains("2embed.skin") -> "https://www.2embed.skin"
                        currentHtml.contains("movieuniverse.skin") -> "https://movieuniverse.skin"
                        else -> "https://www.2embed.cc"
                    }
                    if (nextIframe.startsWith("/")) "$baseUrl$nextIframe" else "$baseUrl/$nextIframe"
                }

                ClipboardLogger.logAndCopy("Iframe Found (depth $depth)", absoluteIframeUrl)
                currentHtml = app.get(absoluteIframeUrl, headers = headers).text
                ClipboardLogger.logAndCopy("Iframe Response (depth $depth)", currentHtml)
                depth++
            }

            if (m3u8Url.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("Error", "Không thể tìm thấy link m3u8 sau $depth lớp iframe.")
                return false
            }

            ApiServices.sendTraktScrobble("stop", type, tmdbId.toIntOrNull() ?: 0, 100f,
                if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
                if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
            )

            val subtitleRegex = Regex("""(https?://[^"']*\.(?:vtt|srt))""", RegexOption.IGNORE_CASE)
            subtitleRegex.findAll(currentHtml).forEach { match ->
                val subUrl = match.groupValues[1]
                if (subUrl.isNotEmpty()) {
                    subtitleCallback.invoke(SubtitleFile("Unknown", subUrl))
                }
            }

            callback.invoke(
                ExtractorLink(
                    source = this.name, name = "2nembed", url = m3u8Url, referer = "https://www.2embed.cc/",
                    quality = Qualities.P1080.value, type = ExtractorLinkType.M3U8, headers = headers
                )
            )
            return true
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
