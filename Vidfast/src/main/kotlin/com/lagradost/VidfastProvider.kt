package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ PROXY
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val IMG_PROXY = "https://wsrv.nl/?url="
private const val TRAKT_CLIENT_ID = "YOUR_TRAKT_CLIENT_ID"
private const val TRAKT_ACCESS_TOKEN = ""

// Định danh nội bộ: "vidfast:movie:<id>" / "vidfast:tv:<id>" / "vidfast:tv:<id>:<s>:<e>".
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
private object VidfastApiServices {
    suspend fun getOmdbData(imdbId: String): String? {
        if (OMDB_API_KEY.startsWith("YOUR_")) return null
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
class VidfastProvider : MainAPI() {
    // mainUrl KHÔNG được là địa chỉ proxy TMDB: CloudStream ghép mainUrl vào trước mọi url không bắt đầu bằng http
    // (vd "vidfast:tv:1" -> "<proxy>/3/vidfast:tv:1") làm load() phân tích sai và báo "This provider does not exist".
    override var mainUrl = "https://vidfast.vc"
    override var name = "VidFast"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    // Ảnh TMDB qua wsrv.nl (CDN Cloudflare) vì image.tmdb.org hay bị chặn/chập chờn ở Việt Nam
    private fun img(path: String?, size: String = "w500") =
        path?.takeIf { it.isNotEmpty() && it != "null" }?.let { "https://wsrv.nl/?url=image.tmdb.org/t/p/$size$it" }

    private fun poster(path: String?) = img(path, "w500")

    // TMDB: thử proxy Cloudflare của bạn trước, rồi tới các host chính thức
    private val tmdbHosts = listOf(TMDB_BASE_URL, "https://api.tmdb.org/3", "https://api.themoviedb.org/3")

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
            val results = json.optJSONArray("results") ?: throw Exception(json.optString("status_message", "TMDB không trả results"))
            val home = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                home.add(newMovieSearchResponse(title, "vidfast:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            ClipboardLogger.logAndCopy("MainPage ${request.name}", "items=${home.size}, poster0=${home.firstOrNull()?.posterUrl}")
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
                out.add(newMovieSearchResponse(title, "vidfast:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
            val response = tmdbGet(if (mediaType == "tv") "/tv/$tmdbIdInt?append_to_response=external_ids" else "/movie/$tmdbIdInt")
            val json = JSONObject(response)
            if (json.has("status_code")) throw Exception("TMDB: ${json.optString("status_message")}")
            val title = json.optString("title", json.optString("name"))
            val description = json.optString("overview")
            val poster = poster(json.optString("poster_path"))
            val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
            val imdbId = json.optString("imdb_id").ifEmpty { json.optJSONObject("external_ids")?.optString("imdb_id") ?: "" }

            var imdbScore: Score? = null
            if (imdbId.isNotEmpty()) {
                VidfastApiServices.getOmdbData(imdbId)?.let { ratingStr ->
                    ratingStr.toDoubleOrNull()?.let { imdbScore = Score.from10(it) }
                }
            }

            val canonical = "vidfast:$mediaType:$tmdbIdInt"
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
                            episodes.add(newEpisode("vidfast:tv:$tmdbIdInt:$sn:$en") {
                                this.name = ep.optString("name")
                                this.episode = en
                                this.season = sn
                                this.description = ep.optString("overview")
                                this.posterUrl = img(ep.optString("still_path"), "w300")
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
    // VIDFAST WEBVIEW
    // Loads the official player page. The player itself performs its normal requests;
    // this code only observes the HLS manifest URL requested by that page.
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
        userAgent: String,
        seen: MutableList<String>
    ): String? {
        val activity = CommonActivity.activity
        if (activity == null) {
            ClipboardLogger.logAndCopy("VidFast WebView", "CommonActivity chưa sẵn sàng")
            return null
        }

        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                var completed = false
                val webView = WebView(activity)

                fun finish(result: String?) {
                    if (completed) return
                    completed = true
                    handler.removeCallbacksAndMessages(null)
                    try {
                        webView.stopLoading()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    } catch (_: Exception) {
                    }
                    cont.resume(result)
                }

                try {
                    val width = activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                    val height = activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
                    val manifestRegex = Regex("""(?i)\.m3u8$""")

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = userAgent
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }

                    // Không mở popup/cửa sổ quảng cáo.
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean = false
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val uri = request?.url ?: return null
                            val host = uri.host.orEmpty()
                            val path = uri.encodedPath.orEmpty()

                            // Ghi nhận host + path, không ghi query token vào ClipboardLogger.
                            if (seen.size < 80 && host.isNotEmpty()) {
                                val item = "${uri.scheme}://$host$path"
                                if (seen.lastOrNull() != item) seen.add(item)
                            }

                            if (manifestRegex.containsMatchIn(path)) {
                                val manifestUrl = uri.toString()
                                handler.post { finish(manifestUrl) }
                            }
                            return null // Để WebView tiếp tục tải request bình thường.
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                ClipboardLogger.logAndCopy(
                                    "VidFast page error",
                                    "${request.url.host}: ${error?.errorCode} ${error?.description}"
                                )
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true) {
                                ClipboardLogger.logAndCopy(
                                    "VidFast HTTP error",
                                    "${request.url.host}: HTTP ${errorResponse?.statusCode}"
                                )
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Chỉ yêu cầu player bắt đầu phát theo cơ chế thông thường của trang.
                            view?.evaluateJavascript(
                                """(function(){
                                    var v=document.querySelector('video');
                                    if(v){var p=v.play();if(p&&p.catch)p.catch(function(){});}
                                    var b=document.querySelector('[aria-label*=Play],.jw-display-icon-container,.vjs-big-play-button');
                                    if(b){try{b.click();}catch(e){}}
                                })();""",
                                null
                            )
                        }
                    }

                    // WebView được gắn vào Activity nhưng đặt ngoài vùng hiển thị.
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, width, height)
                    webView.translationX = -(width * 2f)
                    activity.addContentView(webView, ViewGroup.LayoutParams(width, height))

                    handler.postDelayed({ finish(null) }, 60_000L)
                    webView.loadUrl(pageUrl, mapOf("Referer" to "https://vidfast.vc/"))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("VidFast WebView exception", e.stackTraceToString().take(800))
                    finish(null)
                }
            }
        }
    }

    // =====================================================================================
    // LOAD LINKS - VidFast player page
    // Movies: https://vidfast.vc/movie/{tmdbId}
    // TV:     https://vidfast.vc/tv/{tmdbId}/{season}/{episode}
    // =====================================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("VidFast LoadLinks", "Data: $data")

        val ref = parseRef(data) ?: run {
            ClipboardLogger.logAndCopy("VidFast error", "Không phân tích được media id")
            return false
        }
        if (ref.type == "tv" && (ref.season == null || ref.episode == null)) {
            ClipboardLogger.logAndCopy("VidFast error", "Thiếu season/episode")
            return false
        }

        val pageUrl = if (ref.type == "movie") {
            "https://vidfast.vc/movie/${ref.id}"
        } else {
            "https://vidfast.vc/tv/${ref.id}/${ref.season}/${ref.episode}"
        }
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        var streamUrl: String? = null
        repeat(2) { attempt ->
            if (streamUrl != null) return@repeat
            val seen = mutableListOf<String>()
            ClipboardLogger.logAndCopy("VidFast attempt ${attempt + 1}", "Load player: $pageUrl")
            streamUrl = try {
                sniffStream(pageUrl, userAgent, seen)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy(
                    "VidFast attempt error",
                    "${e.message}\n${e.stackTraceToString().take(500)}"
                )
                null
            }
            if (streamUrl == null) {
                ClipboardLogger.logAndCopy(
                    "VidFast no manifest",
                    "Lần ${attempt + 1}: không quan sát được playlist HLS. Requests:\n" +
                        seen.take(40).joinToString("\n")
                )
            }
        }

        val manifest = streamUrl
        if (manifest.isNullOrBlank()) {
            ClipboardLogger.logAndCopy("VidFast result", "Không tìm thấy playlist HLS sau 2 lần thử")
            return false
        }

        val origin = "https://vidfast.vc"
        callback.invoke(
            newExtractorLink(this.name, "VidFast HLS", manifest, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Origin" to origin,
                    "Referer" to "$origin/",
                    "User-Agent" to userAgent
                )
            }
        )
        ClipboardLogger.logAndCopy("VidFast result", "Đã nhận diện playlist HLS từ player")
        return true
    }
}
