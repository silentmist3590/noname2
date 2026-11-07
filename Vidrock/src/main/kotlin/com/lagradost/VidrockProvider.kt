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
private const val BASE_URL = "https://vidrock.to"
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val IMG_PROXY = "https://wsrv.nl/?url="

private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")
private class Ref(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): Ref? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return Ref(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

// Data class để lưu URL và Headers của request m3u8
private data class SniffResult(val url: String, val headers: Map<String, String>)

// =====================================================================================
// 2. API SERVICES (TMDB)
// =====================================================================================
object ApiServices {
    suspend fun getTmdbDetails(type: String, tmdbId: Int): JSONObject? {
        return try {
            val url = "$TMDB_BASE_URL/$type/$tmdbId?api_key=$TMDB_API_KEY"
            JSONObject(app.get(url).text)
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("TMDB Error", e.message ?: "Unknown")
            null
        }
    }
}

// =====================================================================================
// 3. LỚP PROVIDER CHÍNH
// =====================================================================================
class VidrockProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "Vidrock"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$TMDB_BASE_URL/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$TMDB_BASE_URL/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$TMDB_BASE_URL/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    private fun poster(path: String?, size: String = "w500"): String? {
        if (path.isNullOrEmpty() || path == "null") return null
        val originalUrl = "https://image.tmdb.org/t/p/$size$path"
        return "$IMG_PROXY${URLEncoder.encode(originalUrl, "UTF-8")}"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = app.get("${request.data}&page=$page").text
            val json = JSONObject(response)
            val results = json.optJSONArray("results") ?: throw Exception("No results")
            val home = mutableListOf<SearchResponse>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                
                val title = item.optString("title", item.optString("name"))
                home.add(newMovieSearchResponse(title, "vidrock:$mediaType:$id", 
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "$TMDB_BASE_URL/search/multi?api_key=$TMDB_API_KEY&query=$q"
            val json = JSONObject(app.get(url).text)
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                out.add(newMovieSearchResponse(item.optString("title", item.optString("name")), "vidrock:$mediaType:$id",
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            out
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: return null
        val tmdbId = ref.id
        val mediaType = ref.type

        val json = ApiServices.getTmdbDetails(mediaType, tmdbId) ?: return null

        val title = json.optString("title", json.optString("name"))
        val description = json.optString("overview")
        val posterUrl = poster(json.optString("poster_path"))
        val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
        val canonical = "vidrock:$mediaType:$tmdbId"

        if (mediaType == "tv") {
            val episodes = mutableListOf<Episode>()
            val seasonsCount = json.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val seasonUrl = "$TMDB_BASE_URL/tv/$tmdbId/season/$s?api_key=$TMDB_API_KEY"
                    val seasonJson = JSONObject(app.get(seasonUrl).text)
                    val eps = seasonJson.optJSONArray("episodes") ?: continue
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        val sn = ep.optInt("season_number", s)
                        val en = ep.optInt("episode_number", e + 1)
                        episodes.add(newEpisode("vidrock:tv:$tmdbId:$sn:$en") {
                            this.name = ep.optString("name")
                            this.episode = en
                            this.season = sn
                            this.posterUrl = poster(ep.optString("still_path"), "w300")
                        })
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                }
            }
            return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl; this.plot = description; this.year = year
            }
        } else {
            return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                this.posterUrl = posterUrl; this.plot = description; this.year = year
            }
        }
    }

    // =====================================================================================
    // WEBVIEW SNIFFER (Đã tối ưu)
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, referer: String, ua: String, seen: MutableList<String>): SniffResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity ?: CloudStreamApp.context
                if (ctx == null) { cont.resume(null); return@post }
                try {
                    val w = 1080; val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    fun finish(res: SniffResult?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try { wv.stopLoading(); (wv.parent as? ViewGroup)?.removeView(wv); wv.destroy() } catch (_: Exception) {}
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

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                    }

                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-display-icon-container,.jw-bigplay,[aria-label=Play],.vjs-big-play-button'));" +
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
                            wv.dispatchTouchEvent(d); wv.dispatchTouchEvent(u)
                            d.recycle(); u.recycle()
                        } catch (_: Exception) {}
                        if (taps < 40) handler.postDelayed({ tap() }, 1500) // Tăng lên 40 lần click
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            
                            if (!junkRegex.containsMatchIn(u) && seen.size < 150) {
                                seen.add(u.take(220))
                            }
                            
                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                // Lấy toàn bộ headers của request m3u8 (Referer, Origin, Cookie...)
                                val reqHeaders = request?.requestHeaders ?: emptyMap()
                                val hit = SniffResult(u, reqHeaders)
                                
                                val headerLog = reqHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                                ClipboardLogger.logAndCopy("Sniff HIT", "URL: $u\nHeaders:\n$headerLog")
                                
                                handler.post { finish(hit) }
                            }
                            return null
                        }

                        // CHỈ chặn quảng cáo, không chặn bất kỳ redirect nào khác (cho phép vercetti.org)
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val adHosts = listOf("doubleclick", "googlesyndication", "popads", "onclickads", "adsterra", "propellerads")
                            val isAd = adHosts.any { u.contains(it, true) }
                            if (isAd) {
                                seen.add("BLOCKED AD: " + u.take(160))
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
                        }
                    }

                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try { CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h)) } catch (_: Exception) {}

                    // TĂNG TIMEOUT LÊN 180 GIÂY (3 phút) để đợi quảng cáo chạy xong
                    handler.postDelayed({ finish(null) }, 180_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // =====================================================================================
    // LOAD LINKS
    // =====================================================================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        
        val ref = parseRef(data) ?: return false
        val type = ref.type
        val tmdbId = ref.id
        val season = ref.season
        val episode = ref.episode

        val embedUrl = if (type == "movie") {
            "$BASE_URL/movie/$tmdbId"
        } else {
            if (season == null || episode == null) return false
            "$BASE_URL/tv/$tmdbId/$season/$episode"
        }

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        try {
            ClipboardLogger.logAndCopy("Embed URL", embedUrl)
            val seen = mutableListOf<String>()
            val result = sniffStream(embedUrl, "$BASE_URL/", ua, seen)
            
            ClipboardLogger.logAndCopy("Requests Seen", seen.joinToString("\n"))
            ClipboardLogger.logAndCopy("Result URL", if (result == null) "null" else result.url)

            if (result == null) {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được link stream.")
                return false
            }

            // ⭐ Lấy headers thật từ WebView (để vượt qua CDN vercetti.org)
            val finalHeaders = result.headers.toMutableMap()
            
            // Nếu WebView không gửi Referer, ta phải tự thêm vào
            if (finalHeaders.keys.none { it.equals("Referer", true) }) {
                finalHeaders["Referer"] = "$BASE_URL/"
            }
            // Đảm bảo có Origin
            if (finalHeaders.keys.none { it.equals("Origin", true) }) {
                finalHeaders["Origin"] = BASE_URL
            }
            // Đảm bảo có User-Agent
            if (finalHeaders.keys.none { it.equals("User-Agent", true) }) {
                finalHeaders["User-Agent"] = ua
            }

            callback.invoke(
                ExtractorLink(
                    source = this.name, 
                    name = "Vidrock", 
                    url = result.url,
                    referer = finalHeaders["Referer"] ?: "$BASE_URL/", 
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = finalHeaders
                )
            )
            return true
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString().take(1500)}")
            return false
        }
    }
}
