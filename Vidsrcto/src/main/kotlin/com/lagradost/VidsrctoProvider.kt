package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject
import java.net.URLEncoder

// =====================================================================================
// 1. CẤU HÌNH
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""
private const val IMG_PROXY = "https://wsrv.nl/?url="

// Domain player + CDN được phép (dùng để xác định Referer và cho phép navigation)
private val PLAYER_HOSTS = listOf(
    "vidsrc.to",
    "vsembed.ru",
    "vidsrc-embed.ru",
    "cloudnestra.com",
    "cloudorchestranova.com",
    "cloudnestra.online"
)

// Chỉ chặn những domain quảng cáo rõ ràng
private val AD_HOSTS = listOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "popads.net",
    "onclickads.net",
    "propellerads.com",
    "popcash.net",
    "adsterra.com",
    "adcash.com",
    "googletagmanager.com",
    "googletagservices.com"
)

private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")

private class Ref(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): Ref? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return Ref(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

// =====================================================================================
// 2. API SERVICES (OMDb + Trakt)
// =====================================================================================
object ApiServices {
    suspend fun getOmdbData(imdbId: String): String? {
        return try {
            val json = JSONObject(app.get("https://www.omdbapi.com/?apikey=$OMDB_API_KEY&i=$imdbId").text)
            val rating = json.optString("imdbRating")
            if (rating.isNotEmpty() && rating != "N/A") rating else null
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("OMDb Error", e.message ?: "Unknown")
            null
        }
    }

    suspend fun sendTraktScrobble(
        action: String, type: String, tmdbId: Int, progress: Float,
        season: Int? = null, episode: Int? = null
    ) {
        if (TRAKT_ACCESS_TOKEN.isEmpty()) return
        val headers = mapOf(
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID,
            "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
        )
        val body = JSONObject()
        body.put("progress", progress)
        if (type == "movie") {
            body.put("movie", JSONObject().put("ids", JSONObject().put("tmdb", tmdbId)))
        } else {
            body.put("episode", JSONObject()
                .put("season", season ?: 1)
                .put("number", episode ?: 1))
        }
        try {
            app.post("https://api.trakt.tv/scrobble/$action", headers = headers, json = body).text
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Trakt Error", e.message ?: "Unknown")
        }
    }
}

// =====================================================================================
// 3. PROVIDER CHÍNH
// =====================================================================================
class VidsrctoProvider : MainAPI() {
    override var mainUrl = TMDB_BASE_URL
    override var name = "Vidsrcto"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$mainUrl/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$mainUrl/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    private fun poster(path: String?, size: String = "w500"): String? {
        if (path.isNullOrEmpty() || path == "null") return null
        val originalUrl = "https://image.tmdb.org/t/p/$size$path"
        return "$IMG_PROXY${URLEncoder.encode(originalUrl, "UTF-8")}"
    }

    // ---------------------------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = app.get("${request.data}&page=$page").text
            val json = JSONObject(response)
            val results = json.optJSONArray("results")
                ?: throw Exception(json.optString("status_message", "TMDB không trả results"))
            val home = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                home.add(newMovieSearchResponse(
                    title,
                    "vidsrcto:$mediaType:$id",
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie
                ) {
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

    // ---------------------------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(app.get("$mainUrl/search/multi?api_key=$TMDB_API_KEY&query=$q").text)
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                out.add(newMovieSearchResponse(
                    title,
                    "vidsrcto:$mediaType:$id",
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie
                ) {
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

    // ---------------------------------------------------------------------------------
    // LOAD (chi tiết phim + danh sách tập)
    // ---------------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: run {
            ClipboardLogger.logAndCopy("load Error", "Không đọc được id từ url: $url")
            return null
        }
        val mediaType = ref.type
        val tmdbIdInt = ref.id

        return try {
            val response = app.get("$TMDB_BASE_URL/$mediaType/$tmdbIdInt?api_key=$TMDB_API_KEY").text
            val json = JSONObject(response)
            if (json.has("status_code")) throw Exception("TMDB: ${json.optString("status_message")}")

            val title = json.optString("title", json.optString("name"))
            val description = json.optString("overview")
            val posterUrl = poster(json.optString("poster_path"))
            val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
            val imdbId = json.optString("imdb_id")

            var imdbScore: Score? = null
            if (imdbId.isNotEmpty()) {
                ApiServices.getOmdbData(imdbId)?.let { ratingStr ->
                    ratingStr.toDoubleOrNull()?.let { imdbScore = Score.from10(it) }
                }
            }

            val canonical = "vidsrcto:$mediaType:$tmdbIdInt"

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
                            episodes.add(newEpisode("vidsrcto:tv:$tmdbIdInt:$sn:$en") {
                                this.name = ep.optString("name")
                                this.episode = en
                                this.season = sn
                                this.description = ep.optString("overview")
                                this.posterUrl = poster(ep.optString("still_path"), "w300")
                            })
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.logAndCopy("Season Load Error", "Season $s: ${e.message}")
                    }
                }
                newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.year = year
                    this.score = imdbScore
                }
            } else {
                newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.year = year
                    this.score = imdbScore
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("load Error", "$url -> ${e.message}")
            null
        }
    }

    // =====================================================================================
    // WEBVIEW SNIFFER — chỉ chặn quảng cáo, cho phép player redirect tự do
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
        referer: String,
        ua: String,
        seen: MutableList<String>
    ): String? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val appCtx: Context? = CloudStreamApp.context
                val act = CommonActivity.activity
                val ctx: Context? = act ?: appCtx
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Sniff", "Không có Context để tạo WebView")
                    cont.resume(null)
                    return@post
                }

                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    var finished = false

                    fun finish(res: String?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) {}
                        cont.resume(res)
                    }

                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt""", RegexOption.IGNORE_CASE)
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // Chặn mở popup window
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean = false
                    }

                    // Override window.open + auto-click play
                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-display-icon-container,.jw-bigplay,[aria-label=Play],button[aria-label=Play]'));" +
                        "var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}})();"

                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(fakePopup + clickJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle()
                            u.recycle()
                        } catch (_: Exception) {}
                        if (taps < 30) handler.postDelayed({ tap() }, 1500)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!junkRegex.containsMatchIn(u) && seen.size < 120) {
                                seen.add(u.take(240))
                            }
                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                ClipboardLogger.logAndCopy("Sniff HIT", u.take(240))
                                handler.post { finish(u) }
                            }
                            return null
                        }

                        // ✅ CHỈ chặn quảng cáo, cho phép player redirect tự do
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val isAd = AD_HOSTS.any { u.contains(it, ignoreCase = true) }
                            if (isAd) {
                                seen.add("BLOCKED AD: " + u.take(180))
                                return true
                            }
                            seen.add("NAV: " + u.take(180))
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
                        }
                    }

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try {
                        act?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) {}

                    handler.postDelayed({ finish(null) }, 45_000L)

                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // =====================================================================================
    // HELPERS
    // =====================================================================================
    private fun cleanJsUrl(s: String): String =
        s.replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

    private fun absUrl(base: String, u: String): String {
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: return u
        return if (u.startsWith("/")) host + u else "$host/$u"
    }

    private fun playerOriginFor(streamUrl: String): String {
        // Xác định Referer đúng dựa trên domain CDN của stream
        return when {
            streamUrl.contains("cloudnestra.com") -> "https://cloudnestra.com"
            streamUrl.contains("cloudorchestranova.com") -> "https://cloudorchestranova.com"
            streamUrl.contains("cloudnestra.online") -> "https://cloudnestra.online"
            streamUrl.contains("vsembed.ru") -> "https://vsembed.ru"
            else -> "https://cloudnestra.com" // fallback theo tài liệu vidsrc-dlp
        }
    }

    // =====================================================================================
    // LOAD LINKS — WebView sniff là chính (giống Vidsrcsu)
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

        ApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        if (type == "tv" && (season == null || episode == null)) {
            ClipboardLogger.logAndCopy("Error", "Thiếu season/episode: $data")
            return false
        }

        val embedUrl = if (type == "movie") {
            "https://vidsrc.to/embed/movie/$tmdbId"
        } else {
            "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode"
        }

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        try {
            // === BƯỚC 1: HTTP chỉ để lấy iframe URL (nhanh, không cần hiểu chain) ===
            var iframeUrl: String? = null
            val httpHeaders = mapOf(
                "User-Agent" to ua,
                "Referer" to "https://vidsrc.to/",
                "Origin" to "https://vidsrc.to"
            )
            try {
                val embedHtml = app.get(embedUrl, headers = httpHeaders).text
                ClipboardLogger.logAndCopy("HTTP embed", "len=${embedHtml.length}")
                iframeUrl = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(embedHtml)?.groupValues?.get(1)
                    ?.let { cleanJsUrl(it) }
                    ?.let { absUrl(embedUrl, it) }
                ClipboardLogger.logAndCopy("HTTP iframe", "$iframeUrl")
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("HTTP Fail", e.message ?: "Unknown")
            }

            // === BƯỚC 2: WebView sniff trên iframe (hoặc embed nếu không có iframe) ===
            val targetPage = iframeUrl ?: embedUrl
            ClipboardLogger.logAndCopy("WebView target", targetPage)

            val seen = mutableListOf<String>()
            val streamUrl = sniffStream(
                pageUrl = targetPage,
                referer = "https://vidsrc.to/",
                ua = ua,
                seen = seen
            )

            ClipboardLogger.logAndCopy(
                "WebView requests (${seen.size})",
                seen.joinToString("\n").take(4500)
            )
            ClipboardLogger.logAndCopy("WebView result", "url=$streamUrl")

            if (streamUrl.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được link stream")
                return false
            }

            // === BƯỚC 3: Clean URL + chọn Referer đúng ===
            val finalUrl = cleanJsUrl(streamUrl)
            val origin = playerOriginFor(finalUrl)

            ApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)

            ClipboardLogger.logAndCopy(
                "Success",
                "URL: $finalUrl\nReferer: $origin/"
            )

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Vidsrcto",
                    url = finalUrl,
                    referer = "$origin/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to "$origin/",
                        "Origin" to origin
                    )
                )
            )
            return true

        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy(
                "Exception",
                "Error: ${e.message}\nStackTrace: ${e.stackTraceToString().take(1500)}"
            )
            return false
        }
    }
}
