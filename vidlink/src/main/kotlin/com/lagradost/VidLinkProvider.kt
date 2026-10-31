package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ PROXY
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "b9e1ff7ff423790bf7df202e661377ca"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""


// =====================================================================================
// 2. DATA CLASSES CHO CÁC API
// =====================================================================================
data class TvMazeShow(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String?
)

data class TvMazeEpisode(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("season") val season: Int,
    @SerializedName("number") val number: Int?,
    @SerializedName("summary") val summary: String?
)

data class OmdbResponse(
    @SerializedName("imdbRating") val imdbRating: String?,
    @SerializedName("imdbVotes") val imdbVotes: String?,
    @SerializedName("Awards") val awards: String?,
    @SerializedName("Response") val response: String?
)

data class TraktScrobbleRequest(
    @SerializedName("movie") val movie: TraktMovie? = null,
    @SerializedName("episode") val episode: TraktEpisode? = null,
    @SerializedName("progress") val progress: Float
)
data class TraktMovie(@SerializedName("ids") val ids: TraktIds)
data class TraktEpisode(
    @SerializedName("season") val season: Int,
    @SerializedName("number") val number: Int
)
data class TraktIds(@SerializedName("tmdb") val tmdb: Int?)


// =====================================================================================
// 3. CÁC HÀM GỌI API
// =====================================================================================
object ApiServices {
    private const val TVMAZE_BASE = "https://api.tvmaze.com"

    suspend fun searchTvMazeShow(showName: String): TvMazeShow? {
        return try {
            val res = app.get("$TVMAZE_BASE/search/shows?q=$showName").text
            Gson().fromJson(res, Array<TvMazeShow>::class.java).firstOrNull()
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("TVMaze Search Error", e.message ?: "Unknown")
            null
        }
    }

    suspend fun getTvMazeEpisodes(showId: Int): List<TvMazeEpisode> {
        return try {
            val res = app.get("$TVMAZE_BASE/shows/$showId/episodes").text
            Gson().fromJson(res, Array<TvMazeEpisode>::class.java).toList()
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("TVMaze Episodes Error", e.message ?: "Unknown")
            emptyList()
        }
    }

    suspend fun getOmdbData(imdbId: String): OmdbResponse? {
        return try {
            val res = app.get("https://www.omdbapi.com/?apikey=$OMDB_API_KEY&i=$imdbId").text
            Gson().fromJson(res, OmdbResponse::class.java)
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("OMDb Error", e.message ?: "Unknown")
            null
        }
    }

    suspend fun sendTraktScrobble(action: String, type: String, tmdbId: Int, progress: Float, season: Int? = null, episode: Int? = null) {
        if (TRAKT_ACCESS_TOKEN.isEmpty()) return
        val url = "https://api.trakt.tv/scrobble/$action"
        val headers = mapOf(
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID,
            "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
        )
        val body = if (type == "movie") {
            TraktScrobbleRequest(movie = TraktMovie(TraktIds(tmdbId)), progress = progress)
        } else {
            TraktScrobbleRequest(episode = TraktEpisode(season ?: 1, episode ?: 1), progress = progress)
        }
        try {
            app.post(url, headers = headers, json = body).text
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Trakt Scrobble Error", e.message ?: "Unknown")
        }
    }
}


// =====================================================================================
// 4. LỚP PROVIDER CHÍNH
// =====================================================================================
class VidLinkProvider : MainAPI() {
    override var mainUrl = TMDB_BASE_URL
    override var name = "VidLink (All-in-One)"
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
            home.add(newMovieSearchResponse(title, "tmdb:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
            searchResults.add(newMovieSearchResponse(title, "tmdb:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
            ApiServices.getOmdbData(imdbId)?.let { omdb ->
                val ratingStr = omdb.imdbRating
                if (!ratingStr.isNullOrEmpty() && ratingStr != "N/A") {
                    imdbScore = Score.from10(ratingStr.toDouble())
                }
            }
        }

        val episodes = mutableListOf<Episode>()
        if (mediaType == "tv") {
            val tvMazeShow = ApiServices.searchTvMazeShow(title)
            if (tvMazeShow != null) {
                val tvMazeEpisodes = ApiServices.getTvMazeEpisodes(tvMazeShow.id)
                tvMazeEpisodes.forEach { ep ->
                    episodes.add(newEpisode("vidlink:tv:$tmdbId:${ep.season}:${ep.number}") {
                        this.name = ep.name
                        this.season = ep.season
                        this.episode = ep.number ?: 0
                        this.description = ep.summary
                    })
                }
            } else {
                val seasonsCount = json.optInt("number_of_seasons", 1)
                for (s in 1..seasonsCount) {
                    try {
                        val seasonRes = app.get("$mainUrl/tv/$tmdbId/season/$s?api_key=$TMDB_API_KEY").text
                        val seasonJson = JSONObject(seasonRes)
                        val eps = seasonJson.getJSONArray("episodes")
                        for (e in 0 until eps.length()) {
                            val ep = eps.getJSONObject(e)
                            episodes.add(newEpisode("vidlink:tv:$tmdbId:${ep.getInt("season_number")}:${ep.getInt("episode_number")}") {
                                this.name = ep.optString("name")
                                this.episode = ep.getInt("episode_number")
                                this.season = ep.getInt("season_number")
                                this.description = ep.optString("overview")
                            })
                        }
                    } catch (e: Exception) { }
                }
            }
            return newTvSeriesLoadResponse(title, "vidlink:tv:$tmdbId", TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "vidlink:movie:$tmdbId") {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        }
    }

    // Hàm đệ quy để tìm link stream trong bất kỳ cấu trúc JSON nào
    private fun findStreamUrl(json: Any?): String? {
        when (json) {
            is JSONObject -> {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.get(key)
                    if (value is String && (value.contains(".m3u8") || value.contains(".mp4"))) {
                        return value
                    } else {
                        val nested = findStreamUrl(value)
                        if (nested != null) return nested
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    val nested = findStreamUrl(json.get(i))
                    if (nested != null) return nested
                }
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
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

        val token = CryptoUtils.encryptToken(tmdbId)
        val apiUrl = if (type == "movie") {
            "https://vidlink.pro/api/b/movie/$token?multiLang=1"
        } else {
            if (parts.size < 5) return false
            "https://vidlink.pro/api/b/tv/$token/${parts[3]}/${parts[4]}?multiLang=1"
        }

        // Cập nhật Headers giả lập trình duyệt Chrome cực mạnh
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://vidlink.pro",
            "Referer" to "https://vidlink.pro/",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9,vi;q=0.8",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site"
        )

        try {
            ClipboardLogger.logAndCopy("API Request", "URL: $apiUrl")
            val response = app.get(apiUrl, headers = headers).text
            ClipboardLogger.logAndCopy("API Response", response)

            // Kiểm tra nếu bị Cloudflare chặn trả về HTML
            if (response.trim().startsWith("<!DOCTYPE") || response.trim().startsWith("<html")) {
                ClipboardLogger.logAndCopy("Error", "Bị Cloudflare chặn (Trả về HTML).")
                return false
            }

            val jsonObject = JSONObject(response)
            
            var playlistUrl: String? = null

            // Cách 1: Thử lấy theo cấu trúc chuẩn
            val streamObject = jsonObject.optJSONObject("stream")
            if (streamObject != null) {
                playlistUrl = streamObject.optString("playlist")
                if (playlistUrl.isEmpty()) playlistUrl = streamObject.optString("url")
                if (playlistUrl.isEmpty()) playlistUrl = streamObject.optString("file")
            }

            // Cách 2: Nếu cách 1 thất bại, quét toàn bộ JSON để tìm link .m3u8 hoặc .mp4
            if (playlistUrl.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("JSON Scan", "Không tìm thấy ở cấu trúc chuẩn, đang quét toàn bộ JSON...")
                playlistUrl = findStreamUrl(jsonObject)
            }

            if (playlistUrl.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy URL stream trong toàn bộ JSON.")
                return false
            }

            ClipboardLogger.logAndCopy("Success", "Stream URL: $playlistUrl")

            ApiServices.sendTraktScrobble("stop", type, tmdbId.toIntOrNull() ?: 0, 100f,
                if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
                if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
            )

            val captionsArray = streamObject?.optJSONArray("captions")
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
                    referer = "https://vidlink.pro/", // Rất quan trọng để player không bị chặn
                    quality = Qualities.P1080.value,
                    type = if (playlistUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    headers = headers // Truyền headers vào trình phát video
                )
            )
            return true

        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
