package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

// ==========================================
// 1. CLIPBOARD LOGGER (Ghi log copy bộ nhớ tạm)
// ==========================================
object ClipboardLogger {
    private val logBuffer = StringBuilder()

    fun log(tag: String, message: String) {
        val entry = "[NontonGo - $tag] $message\n"
        logBuffer.append(entry)
        val fullLog = logBuffer.toString()

        // Ghi ra Android Logcat
        android.util.Log.d("NontonGoLog", entry)

        // Sao chép tự động vào Clipboard của hệ điều hành
        val ctx = NontonGoProvider.appContext ?: try {
            AcraApplication.context
        } catch (_: Exception) { null }

        if (ctx != null) {
            try {
                Handler(Looper.getMainLooper()).post {
                    try {
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("NontonGo_Logs", fullLog)
                        clipboard?.setPrimaryClip(clip)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    fun clear() {
        logBuffer.setLength(0)
    }
}

// ==========================================
// 2. MAIN PROVIDER (NontonGo)
// ==========================================
open class NontonGoProvider : MainAPI() {
    override var mainUrl = "https://www.nontongo.win"
    override var name = "NontonGo"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        var appContext: Context? = null
        private const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
    }

    override val mainPage = mainPageOf(
        "$CINEMETA_URL/catalog/movie/top.json" to "Popular Movies",
        "$CINEMETA_URL/catalog/series/top.json" to "Popular TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ClipboardLogger.log("MainPage", "Loading: ${request.name} (page $page)")
        val url = if (page <= 1) request.data else {
            val skip = (page - 1) * 20
            request.data.replace(".json", "/skip=$skip.json")
        }

        val json = app.get(url).text
        val metas = parseJson<CinemetaCatalog>(json).metas ?: emptyList()

        val list = metas.map { meta ->
            val isMovie = meta.type == "movie"
            newMovieSearchResponse(meta.name ?: "Unknown", meta.id, if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = meta.poster
                this.year = meta.year?.toIntOrNull()
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false
            ),
            hasNext = metas.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ClipboardLogger.log("Search", "Query: $query")
        val cleanQuery = query.trim().replace(" ", "%20")
        val movieSearch = "$CINEMETA_URL/catalog/movie/top/search=$cleanQuery.json"
        val seriesSearch = "$CINEMETA_URL/catalog/series/top/search=$cleanQuery.json"

        val results = mutableListOf<SearchResponse>()
        listOf(movieSearch, seriesSearch).amap { url ->
            try {
                val json = app.get(url).text
                val metas = parseJson<CinemetaCatalog>(json).metas ?: emptyList()
                metas.forEach { meta ->
                    val isMovie = meta.type == "movie"
                    results.add(
                        newMovieSearchResponse(meta.name ?: "Unknown", meta.id, if (isMovie) TvType.Movie else TvType.TvSeries) {
                            this.posterUrl = meta.poster
                            this.year = meta.year?.toIntOrNull()
                        }
                    )
                }
            } catch (e: Exception) {
                ClipboardLogger.log("SearchError", "${e.message}")
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val imdbId = url.substringAfterLast("/").substringBefore("?")
        ClipboardLogger.log("Load", "Loading IMDb ID: $imdbId")

        // Kiểm tra xem là phim lẻ hay phim bộ thông qua Cinemeta
        val metaSeriesUrl = "$CINEMETA_URL/meta/series/$imdbId.json"
        val seriesRes = try {
            app.get(metaSeriesUrl).text
        } catch (_: Exception) { null }

        val seriesMeta = seriesRes?.let {
            try { parseJson<CinemetaDetailResponse>(it).meta } catch (_: Exception) { null }
        }

        if (seriesMeta != null && !seriesMeta.videos.isNullOrEmpty()) {
            val episodes = seriesMeta.videos.map { vid ->
                val epData = EpisodeData(
                    imdbId = imdbId,
                    season = vid.season,
                    episode = vid.number
                )
                newEpisode(epData.toJson()) {
                    this.name = vid.title ?: "Episode ${vid.number}"
                    this.season = vid.season
                    this.episode = vid.number
                }
            }

            return newTvSeriesLoadResponse(seriesMeta.name ?: "Unknown", url, TvType.TvSeries, episodes) {
                this.posterUrl = seriesMeta.poster
                this.plot = seriesMeta.description
                this.year = seriesMeta.year?.toIntOrNull()
            }
        } else {
            // Phim lẻ
            val metaMovieUrl = "$CINEMETA_URL/meta/movie/$imdbId.json"
            val movieJson = app.get(metaMovieUrl).text
            val meta = parseJson<CinemetaDetailResponse>(movieJson).meta
            val title = meta?.name ?: "Unknown"

            val movieData = EpisodeData(imdbId = imdbId, season = null, episode = null)
            return newMovieLoadResponse(title, url, TvType.Movie, movieData.toJson()) {
                this.posterUrl = meta?.poster
                this.plot = meta?.description
                this.year = meta?.year?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.log("LoadLinks", "Start loadLinks with payload: $data")
        val epData = try {
            parseJson<EpisodeData>(data)
        } catch (_: Exception) {
            // Hỗ trợ trường hợp data là link embed trực tiếp
            val isTv = data.contains("/tv/")
            val imdb = if (data.contains("tt")) Regex("""(tt\d+)""").find(data)?.groupValues?.get(1) ?: data else data
            val season = if (isTv) Regex("""/tv/[^/]+/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1 else null
            val episode = if (isTv) Regex("""/tv/[^/]+/\d+/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1 else null
            EpisodeData(imdb, season, episode)
        }

        val embedUrl = if (epData.season != null && epData.episode != null) {
            "https://www.nontongo.win/embed/tv/${epData.imdbId}/${epData.season}/${epData.episode}"
        } else {
            "https://www.nontongo.win/embed/movie/${epData.imdbId}"
        }

        ClipboardLogger.log("LoadLinks", "Embed URL target: $embedUrl")
        var hasLinks = false

        try {
            val embedHtml = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "https://www.nontongo.win/"
                )
            ).text

            // Trích xuất cấu hình servers từ script của NontonGo
            val serversJson = Regex("""\"servers\":\s*(\[.*?\])\s*,\s*\"""", RegexOption.DOT_MATCHES_ALL)
                .find(embedHtml)?.groupValues?.get(1)

            if (!serversJson.isNullOrEmpty()) {
                val servers = parseJson<List<NontonGoServerItem>>(serversJson)
                ClipboardLogger.log("Servers", "Found ${servers.size} servers: ${servers.map { it.name }}")

                servers.amap { srv ->
                    val watchUrl = if (epData.season != null && !srv.tvUrl.isNullOrEmpty()) srv.tvUrl else srv.movieUrl
                    if (!watchUrl.isNullOrEmpty()) {
                        val ok = extractFromWatchUrl(srv.name ?: "NontonGo", watchUrl, embedUrl, subtitleCallback, callback)
                        if (ok) hasLinks = true
                    }
                }
            } else {
                ClipboardLogger.log("Warning", "Could not find servers config in embed page")
            }
        } catch (e: Exception) {
            ClipboardLogger.log("Error", "Embed error: ${e.message}")
        }

        return hasLinks
    }

    private suspend fun extractFromWatchUrl(
        serverName: String,
        watchUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            ClipboardLogger.log("Extract", "Fetching watchUrl: $watchUrl")
            val watchHtml = app.get(watchUrl, referer = referer).text

            // Tìm các iframe (như load.php hoặc player.php)
            val iframes = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(watchHtml).map { it.groupValues[1] }.toList()

            for (iframe in iframes) {
                if (iframe.contains("load.php") || iframe.contains("player.php")) {
                    val playerUrl = if (iframe.contains("load.php")) {
                        iframe.replace("load.php", "player.php") + "&autoplay=true"
                    } else iframe

                    ClipboardLogger.log("Extract", "Target Player URL: $playerUrl")
                    val playerHtml = app.get(playerUrl, referer = watchUrl).text

                    // 1. TRÍCH XUẤT TOÀN BỘ PHỤ ĐỀ (Subtitles)
                    val tracks = Regex("""<track\s+kind=["']captions["']\s+data-src=["']([^"']+)["']\s+label=["']([^"']+)["']""")
                        .findAll(playerHtml)
                    var subCount = 0
                    tracks.forEach { tr ->
                        val subUrl = tr.groupValues[1]
                        val subLabel = tr.groupValues[2]
                        subtitleCallback.invoke(SubtitleFile(subLabel, subUrl))
                        subCount++
                    }
                    ClipboardLogger.log("Subtitles", "Extracted $subCount subtitle tracks")

                    // 2. TRÍCH XUẤT CÁC LUỒNG VIDEO & NHIỀU CHẤT LƯỢNG (Multi-Quality HLS)
                    val buttonMatches = Regex("""<button[^>]*data-url=["']([^"']+)["'][^>]*>(.*?)</button>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(playerHtml)

                    var linkFound = false
                    for (bm in buttonMatches) {
                        var streamUrl = bm.groupValues[1].trim()
                        val btnLabel = Jsoup.parse(bm.groupValues[2]).text().trim()

                        if (streamUrl.contains("#file.m3u8")) {
                            streamUrl = streamUrl.substringBefore("#file.m3u8")
                        }

                        if (streamUrl.startsWith("http")) {
                            ClipboardLogger.log("StreamFound", "[$btnLabel] -> $streamUrl")
                            linkFound = true

                            // Sử dụng M3u8Helper để tự động quét toàn bộ chất lượng (1080p, 720p, 480p)
                            val m3u8Links = try {
                                M3u8Helper.generateM3u8(
                                    source = "$serverName ($btnLabel)",
                                    streamUrl = streamUrl,
                                    referer = "https://nontongo.win/",
                                    headers = mapOf("Referer" to "https://nontongo.win/", "Origin" to "https://nontongo.win/")
                                )
                            } catch (_: Exception) { emptyList() }

                            if (m3u8Links.isNotEmpty()) {
                                ClipboardLogger.log("M3U8", "Generated ${m3u8Links.size} qualities for $btnLabel")
                                m3u8Links.forEach(callback)
                            } else {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "$serverName ($btnLabel)",
                                        name = "$serverName ($btnLabel) HLS",
                                        url = streamUrl,
                                        type = INFER_TYPE
                                    ) {
                                        this.referer = "https://nontongo.win/"
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                            }
                        }
                    }
                    if (linkFound) return true
                }
            }
        } catch (e: Exception) {
            ClipboardLogger.log("ExtractError", "Error: ${e.message}")
        }
        return false
    }

    // Model dữ liệu nội bộ
    data class EpisodeData(
        @JsonProperty("imdbId") val imdbId: String,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
    )

    data class NontonGoServerItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("movie_url") val movieUrl: String? = null,
        @JsonProperty("tv_url") val tvUrl: String? = null,
    )

    data class CinemetaCatalog(
        @JsonProperty("metas") val metas: List<CinemetaMeta>? = null
    )

    data class CinemetaDetailResponse(
        @JsonProperty("meta") val meta: CinemetaMeta? = null
    )

    data class CinemetaMeta(
        @JsonProperty("id") val id: String,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("videos") val videos: List<CinemetaVideo>? = null
    )

    data class CinemetaVideo(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season") val season: Int,
        @JsonProperty("number") val number: Int
    )
}
