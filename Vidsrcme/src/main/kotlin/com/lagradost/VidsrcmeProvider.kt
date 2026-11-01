package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
// 2. DATA CLASSES CHO CÁC API
// =====================================================================================
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
// 3. CÁC HÀM GỌI API HỖ TRỢ
// =====================================================================================
object ApiServices {

    // --- OMDb ---
    suspend fun getOmdbData(imdbId: String): OmdbResponse? {
        return try {
            val res = app.get("https://www.omdbapi.com/?apikey=$OMDB_API_KEY&i=$imdbId").text
            Gson().fromJson(res, OmdbResponse::class.java)
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("OMDb Error", e.message ?: "Unknown")
            null
        }
    }

    // --- Trakt ---
    suspend fun sendTraktScrobble(
        action: String, // "start" hoặc "stop"
        type: String,   // "movie" hoặc "tv"
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
class VidsrcmeProvider : MainAPI() {
    override var mainUrl = TMDB_BASE_URL
    override var name = "Vidsrcme"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$mainUrl/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$mainUrl/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    // ---------------------------------------------------------------------------------
    // LẤY TRANG CHỦ
    // ---------------------------------------------------------------------------------
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
            home.add(newMovieSearchResponse(title, "vidsrcme:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
    }

    // ---------------------------------------------------------------------------------
    // TÌM KIẾM
    // ---------------------------------------------------------------------------------
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
            searchResults.add(newMovieSearchResponse(title, "vidsrcme:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return searchResults
    }

    // ---------------------------------------------------------------------------------
    // TẢI CHI TIẾT PHIM / TV
    // ---------------------------------------------------------------------------------
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
            val seasonsCount = json.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val seasonRes = app.get("$mainUrl/tv/$tmdbId/season/$s?api_key=$TMDB_API_KEY").text
                    val seasonJson = JSONObject(seasonRes)
                    val eps = seasonJson.getJSONArray("episodes")
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        episodes.add(newEpisode("vidsrcme:tv:$tmdbId:${ep.getInt("season_number")}:${ep.getInt("episode_number")}") {
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
            return newTvSeriesLoadResponse(title, "vidsrcme:tv:$tmdbId", TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "vidsrcme:movie:$tmdbId") {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // TRÍCH XUẤT LINK STREAM TỪ VIDSRC.SH
    // ---------------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        val parts = data.split(":")
        if (parts.size < 3) return false
        val type = parts[1] // "movie" hoặc "tv"
        val tmdbId = parts[2]

        ApiServices.sendTraktScrobble("start", type, tmdbId.toIntOrNull() ?: 0, 0f,
            if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
            if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
        )

        // Xây dựng URL nhúng của VidSrc.sh
        val embedUrl = if (type == "movie") {
            "https://vidsrc.sh/embed/movie/$tmdbId"
        } else {
            if (parts.size < 5) return false
            "https://vidsrc.sh/embed/tv/$tmdbId/${parts[3]}/${parts[4]}"
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to "https://vidsrc.sh/",
            "Origin" to "https://vidsrc.sh"
        )

        try {
            // BƯỚC 0: Kiểm tra API /info/ xem phim có sẵn không (dựa trên tài liệu bạn cung cấp)
            val infoCheckUrl = if (type == "movie") {
                "https://vidsrc.sh/info/movie/$tmdbId.json"
            } else {
                "https://vidsrc.sh/info/tv/$tmdbId/${parts[3]}/${parts[4]}.json"
            }
            val infoCheckRes = app.get(infoCheckUrl, headers = headers)
            if (infoCheckRes.code == 404) {
                ClipboardLogger.logAndCopy("Error", "Phim này không có sẵn trên Vidsrc (API trả về 404).")
                return false
            }

            ClipboardLogger.logAndCopy("API Request", "Embed URL: $embedUrl")

            // Bước 1: Lấy trang nhúng
            val embedResponse = app.get(embedUrl, headers = headers).text
            ClipboardLogger.logAndCopy("Embed Response", embedResponse)

            // Bước 2: Tìm iframe
            val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val iframeUrl = iframeRegex.find(embedResponse)?.groupValues?.get(1)

            if (iframeUrl == null) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy iframe trong trang embed.")
                return false
            }
            ClipboardLogger.logAndCopy("Iframe Found", iframeUrl)

            // Bước 3: Lấy trang iframe
            val iframeResponse = app.get(iframeUrl, headers = headers).text
            ClipboardLogger.logAndCopy("Iframe Response", iframeResponse)

            // Bước 4: Tìm link RCP hoặc prorcp trong iframe
            val rcpRegex = Regex("""(https?://[^"']*(?:rcp|prorcp)[^"']*)""", RegexOption.IGNORE_CASE)
            val rcpUrl = rcpRegex.find(iframeResponse)?.groupValues?.get(1)
                ?: rcpRegex.find(embedResponse)?.groupValues?.get(1)

            if (rcpUrl == null) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy link RCP.")
                return false
            }
            ClipboardLogger.logAndCopy("RCP URL Found", rcpUrl)

            // Bước 5: Lấy trang RCP
            val rcpResponse = app.get(rcpUrl, headers = headers).text
            ClipboardLogger.logAndCopy("RCP Response", rcpResponse)

            // Bước 6: Tìm link m3u8 trong response
            val m3u8Regex = Regex("""(https?://[^"']*\.m3u8[^"']*)""", RegexOption.IGNORE_CASE)
            val m3u8Url = m3u8Regex.find(rcpResponse)?.groupValues?.get(1)
                ?: m3u8Regex.find(iframeResponse)?.groupValues?.get(1)
                ?: m3u8Regex.find(embedResponse)?.groupValues?.get(1)

            if (m3u8Url == null) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy link m3u8 trong các response.")
                return false
            }
            ClipboardLogger.logAndCopy("Success", "M3U8 URL: $m3u8Url")

            ApiServices.sendTraktScrobble("stop", type, tmdbId.toIntOrNull() ?: 0, 100f,
                if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
                if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
            )

            // Xử lý phụ đề (nếu có trong response)
            val subtitleRegex = Regex("""(https?://[^"']*\.(?:vtt|srt))""", RegexOption.IGNORE_CASE)
            subtitleRegex.findAll(rcpResponse).forEach { match ->
                val subUrl = match.groupValues[1]
                if (subUrl.isNotEmpty()) {
                    subtitleCallback.invoke(SubtitleFile("Unknown", subUrl))
                }
            }

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Vidsrcme",
                    url = m3u8Url,
                    referer = "https://vidsrc.sh/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = headers
                )
            )
            return true

        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
