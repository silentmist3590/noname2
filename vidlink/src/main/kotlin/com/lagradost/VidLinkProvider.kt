package com.lagradost // Đảm bảo đúng package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject

class VidLinkProvider : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "VidLink"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // TODO: Thay thế bằng API Key của bạn
    private val tmdbApiKey = "b9e1ff7ff423790bf7df202e661377ca"
    private val omdbApiKey = "a116862a"

    override val mainPage = mainPageOf(
        "$mainUrl/trending/all/day?api_key=$tmdbApiKey" to "Trending Today",
        "$mainUrl/movie/popular?api_key=$tmdbApiKey" to "Popular Movies",
        "$mainUrl/tv/popular?api_key=$tmdbApiKey" to "Popular TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val response = app.get(url).text
        val json = JSONObject(response)
        val results = json.getJSONArray("results")
        val home = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = item.optString("poster_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w500$it" else null }
            
            home.add(
                newMovieSearchResponse(title, "tmdb:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }
        return newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/multi?api_key=$tmdbApiKey&query=$query"
        val response = app.get(url).text
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
            
            searchResults.add(
                newMovieSearchResponse(title, "tmdb:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split(":")
        if (parts.size < 3) return null
        val mediaType = parts[1]
        val tmdbId = parts[2]

        val detailsUrl = "$mainUrl/$mediaType/$tmdbId?api_key=$tmdbApiKey"
        val response = app.get(detailsUrl).text
        val json = JSONObject(response)

        val title = json.optString("title", json.optString("name"))
        val description = json.optString("overview")
        val poster = json.optString("poster_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w500$it" else null }
        val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
        val imdbId = json.optString("imdb_id")

        var imdbScore: Int? = null
        if (imdbId.isNotEmpty() && omdbApiKey != "YOUR_OMDB_API_KEY") {
            try {
                val omdbUrl = "https://www.omdbapi.com/?apikey=$omdbApiKey&i=$imdbId"
                val omdbRes = JSONObject(app.get(omdbUrl).text)
                val ratingStr = omdbRes.optString("imdbRating")
                if (ratingStr.isNotEmpty() && ratingStr != "N/A") {
                    // Đổi điểm IMDb (thang 10) sang thang 100 của Cloudstream
                    imdbScore = (ratingStr.toDouble() * 10).toInt()
                }
                ClipboardLogger.logAndCopy("OMDb Data", "IMDb Score: $imdbScore")
            } catch (e: Exception) {
                ClipboardLogger.logAndCopy("OMDb Error", e.message ?: "Unknown error")
            }
        }

        val episodes = mutableListOf<Episode>()
        if (mediaType == "tv") {
            val seasonsCount = json.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val seasonRes = app.get("$mainUrl/tv/$tmdbId/season/$s?api_key=$tmdbApiKey").text
                    val seasonJson = JSONObject(seasonRes)
                    val eps = seasonJson.getJSONArray("episodes")
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        episodes.add(
                            newEpisode("vidlink:tv:$tmdbId:${ep.getInt("season_number")}:${ep.getInt("episode_number")}") {
                                this.name = ep.optString("name")
                                this.episode = ep.getInt("episode_number")
                                this.season = ep.getInt("season_number")
                                this.description = ep.optString("overview")
                                this.posterUrl = ep.optString("still_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w300$it" else null }
                            }
                        )
                    }
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Season Load Error", "Season $s: ${e.message}")
                }
            }
        }

        // Sửa lỗi: Phân tách rõ ràng giữa Movie và TVSeries
        if (mediaType == "tv") {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, "vidlink:tv:$tmdbId") {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore // Sử dụng score thay vì rating
                this.episodes = episodes
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "vidlink:movie:$tmdbId") {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore // Sử dụng score thay vì rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        
        val parts = data.split(":")
        if (parts.size < 3) {
            ClipboardLogger.logAndCopy("Error", "Invalid data format: $data")
            return false
        }

        val type = parts[1]
        val tmdbId = parts[2]
        
        val token = CryptoUtils.encryptToken(tmdbId)
        ClipboardLogger.logAndCopy("Token Generated", "TMDB ID: $tmdbId\nToken: $token")

        val apiUrl = if (type == "movie") {
            "https://vidlink.pro/api/b/movie/$token?multiLang=1"
        } else {
            if (parts.size < 5) {
                ClipboardLogger.logAndCopy("Error", "Missing season/episode: $data")
                return false
            }
            "https://vidlink.pro/api/b/tv/$token/${parts[3]}/${parts[4]}?multiLang=1"
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://vidlink.pro",
            "Referer" to "https://vidlink.pro/"
        )

        try {
            ClipboardLogger.logAndCopy("API Request", "URL: $apiUrl")
            val response = app.get(apiUrl, headers = headers).text
            ClipboardLogger.logAndCopy("API Response", response)

            val jsonObject = JSONObject(response)
            val streamObject = jsonObject.optJSONObject("stream")
            
            if (streamObject == null) {
                ClipboardLogger.logAndCopy("Error", "No 'stream' object found in response")
                return false
            }

            val playlistUrl = streamObject.optString("playlist")
            if (playlistUrl.isEmpty()) {
                ClipboardLogger.logAndCopy("Error", "No 'playlist' URL found in stream object")
                return false
            }

            ClipboardLogger.logAndCopy("Success", "Stream URL: $playlistUrl")

            val captionsArray = streamObject.optJSONArray("captions")
            if (captionsArray != null) {
                for (i in 0 until captionsArray.length()) {
                    val caption = captionsArray.getJSONObject(i)
                    val captionUrl = caption.optString("url")
                    val captionLang = caption.optString("language")
                    if (captionUrl.isNotEmpty()) {
                        subtitleCallback.invoke(SubtitleFile(captionLang, captionUrl))
                    }
                }
            }

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "VidLink",
                    url = playlistUrl,
                    referer = "https://vidlink.pro/",
                    quality = Qualities.P1080.value,
                    type = if (playlistUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4
                )
            )
            return true

        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
