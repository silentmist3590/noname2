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
// CẤU HÌNH
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "b9e1ff7ff423790bf7df202e661377ca"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""
private const val IMG_PROXY = "https://wsrv.nl/?url="

private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")
private class Ref(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): Ref? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return Ref(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

// =====================================================================================
// API SERVICES
// =====================================================================================
object ApiServices {
    suspend fun getOmdbData(imdbId: String): String? = try {
        val json = JSONObject(app.get("https://www.omdbapi.com/?apikey=$OMDB_API_KEY&i=$imdbId").text)
        json.optString("imdbRating").takeIf { it.isNotEmpty() && it != "N/A" }
    } catch (e: Exception) { ClipboardLogger.logAndCopy("OMDb", e.message ?: ""); null }

    suspend fun sendTraktScrobble(action: String, type: String, tmdbId: Int, progress: Float, season: Int? = null, episode: Int? = null) {
        if (TRAKT_ACCESS_TOKEN.isEmpty()) return
        val headers = mapOf("Content-Type" to "application/json", "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID, "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN")
        val body = JSONObject().apply {
            put("progress", progress)
            if (type == "movie") put("movie", JSONObject().put("ids", JSONObject().put("tmdb", tmdbId)))
            else put("episode", JSONObject().put("season", season ?: 1).put("number", episode ?: 1))
        }
        try { app.post("https://api.trakt.tv/scrobble/$action", headers = headers, json = body).text }
        catch (e: Exception) { ClipboardLogger.logAndCopy("Trakt", e.message ?: "") }
    }
}

// =====================================================================================
// PROVIDER
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
        val u = "https://image.tmdb.org/t/p/$size$path"
        return "$IMG_PROXY${URLEncoder.encode(u, "UTF-8")}"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val json = JSONObject(app.get("${request.data}&page=$page").text)
            val results = json.optJSONArray("results")
                ?: throw Exception(json.optString("status_message", "No results"))
            val home = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mt = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
                if (mt != "movie" && mt != "tv") continue
                val id = item.optInt("id", 0); if (id == 0) continue
                home.add(newMovieSearchResponse(
                    item.optString("title", item.optString("name")),
                    "vidsrcto:$mt:$id",
                    if (mt == "tv") TvType.TvSeries else TvType.Movie
                ) { this.posterUrl = poster(item.optString("poster_path")) })
            }
            newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("getMainPage", e.message ?: ""); newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(app.get("$mainUrl/search/multi?api_key=$TMDB_API_KEY&query=$q").text)
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mt = item.optString("media_type"); if (mt != "movie" && mt != "tv") continue
                val id = item.optInt("id", 0); if (id == 0) continue
                out.add(newMovieSearchResponse(
                    item.optString("title", item.optString("name")),
                    "vidsrcto:$mt:$id",
                    if (mt == "tv") TvType.TvSeries else TvType.Movie
                ) { this.posterUrl = poster(item.optString("poster_path")) })
            }
            out
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("search", e.message ?: ""); emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: return null
        val mt = ref.type; val tid = ref.id
        return try {
            val json = JSONObject(app.get("$TMDB_BASE_URL/$mt/$tid?api_key=$TMDB_API_KEY").text)
            if (json.has("status_code")) throw Exception(json.optString("status_message"))
            val title = json.optString("title", json.optString("name"))
            val description = json.optString("overview")
            val posterUrl = poster(json.optString("poster_path"))
            val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
            val imdbId = json.optString("imdb_id")

            var score: Score? = null
            if (imdbId.isNotEmpty()) ApiServices.getOmdbData(imdbId)?.toDoubleOrNull()?.let { score = Score.from10(it) }

            val canonical = "vidsrcto:$mt:$tid"
            if (mt == "tv") {
                val episodes = mutableListOf<Episode>()
                for (s in 1..json.optInt("number_of_seasons", 1)) {
                    try {
                        val sJson = JSONObject(app.get("$TMDB_BASE_URL/tv/$tid/season/$s?api_key=$TMDB_API_KEY").text)
                        val eps = sJson.optJSONArray("episodes") ?: continue
                        for (e in 0 until eps.length()) {
                            val ep = eps.getJSONObject(e)
                            val sn = ep.optInt("season_number", s)
                            val en = ep.optInt("episode_number", e + 1)
                            episodes.add(newEpisode("vidsrcto:tv:$tid:$sn:$en") {
                                this.name = ep.optString("name"); this.episode = en; this.season = sn
                                this.description = ep.optString("overview")
                                this.posterUrl = poster(ep.optString("still_path"), "w300")
                            })
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                    }
                }
                newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl; this.plot = description; this.year = year; this.score = score
                }
            } else {
                newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                    this.posterUrl = posterUrl; this.plot = description; this.year = year; this.score = score
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("load", e.message ?: ""); null
        }
    }

    // =====================================================================================
    // WEBVIEW SNIFFER — đã sửa: KHÔNG block navigation
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, referer: String, ua: String, seen: MutableList<String>): String? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity ?: CloudStreamApp.context
                if (ctx == null) { cont.resume(null); return@post }
                try {
                    val w = 1080; val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    fun finish(res: String?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try { wv.stopLoading(); (wv.parent as? ViewGroup)?.removeView(wv); wv.destroy() } catch (_: Exception) {}
                        cont.resume(res)
                    }
                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt""", RegexOption.IGNORE_CASE)
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)
                    wv.settings.apply {
                        javaScriptEnabled = true; domStorageEnabled = true
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
                        "fire(document.querySelector('.jw-icon-display,.jw-display-icon-container,.jw-bigplay,[aria-label=Play]'));" +
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
                        if (taps < 25) handler.postDelayed({ tap() }, 1500)
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!junkRegex.containsMatchIn(u) && seen.size < 100) seen.add(u.take(220))
                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                handler.post { finish(u) }
                            }
                            return null
                        }
                        // ✅ ĐÃ SỬA: chỉ chặn quảng cáo rõ ràng, KHÔNG chặn CDN lạ
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val adHosts = listOf("doubleclick.net","googlesyndication","googleadservices",
                                "popads.net","onclickads","propellerads","adcash","popcash","adsterra")
                            val isAd = adHosts.any { u.contains(it, true) }
                            if (isAd) seen.add("BLOCKED AD: " + u.take(160))
                            else seen.add("NAV: " + u.take(160))
                            return isAd
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
                        }
                    }
                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try { CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h)) } catch (_: Exception) {}
                    handler.postDelayed({ finish(null) }, 45_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // =====================================================================================
    // LOAD LINKS — FastPath (RCP first) + SlowPath (WebView)
    // =====================================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks", "Data: $data")
        val ref = parseRef(data) ?: return false
        val type = ref.type; val tmdbId = ref.id
        ApiServices.sendTraktScrobble("start", type, tmdbId, 0f, ref.season, ref.episode)
        if (type == "tv" && (ref.season == null || ref.episode == null)) return false

        val embedUrl = if (type == "movie") "https://vidsrc.to/embed/movie/$tmdbId"
                       else "https://vidsrc.to/embed/tv/$tmdbId/${ref.season}/${ref.episode}"
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        val headers = mapOf(
            "User-Agent" to ua,
            "Referer" to "https://vidsrc.to/",
            "Origin" to "https://vidsrc.to"
        )

        try {
            var m3u8Url: String? = null
            val subtitleSources = mutableListOf<String>()

            // ============ FAST PATH: giống hệt bản gốc đang chạy ============
            try {
                val embedResponse = app.get(embedUrl, headers = headers).text
                ClipboardLogger.logAndCopy("Fast/embed", "len=${embedResponse.length}")

                val iframeUrl = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(embedResponse)?.groupValues?.get(1)
                ClipboardLogger.logAndCopy("Fast/iframe", "$iframeUrl")

                if (iframeUrl != null) {
                    val iframeResponse = app.get(iframeUrl, headers = headers).text
                    ClipboardLogger.logAndCopy("Fast/iframeResp", "len=${iframeResponse.length}")

                    // ⭐ RCP TRƯỚC — đây là m3u8 thật
                    val rcpUrl = Regex("""(https?://[^"']*(?:rcp|prorcp)[^"']*)""", RegexOption.IGNORE_CASE)
                        .find(iframeResponse)?.groupValues?.get(1)
                    ClipboardLogger.logAndCopy("Fast/rcpUrl", "$rcpUrl")

                    if (rcpUrl != null) {
                        val rcpResponse = app.get(rcpUrl, headers = headers).text
                        ClipboardLogger.logAndCopy("Fast/rcpResp", "len=${rcpResponse.length}")
                        m3u8Url = Regex("""(https?://[^"']*\.m3u8[^"']*)""", RegexOption.IGNORE_CASE)
                            .find(rcpResponse)?.groupValues?.get(1)
                        Regex("""(https?://[^"']*\.(?:vtt|srt))""", RegexOption.IGNORE_CASE)
                            .findAll(rcpResponse).forEach { subtitleSources.add(it.groupValues[1]) }
                    }

                    // ⭐ IFRAME CHỈ LÀ FALLBACK — khi RCP không có m3u8
                    if (m3u8Url == null) {
                        ClipboardLogger.logAndCopy("Fast/iframeFallback", "RCP không có m3u8, thử iframe")
                        m3u8Url = Regex("""(https?://[^"']*\.m3u8[^"']*)""", RegexOption.IGNORE_CASE)
                            .find(iframeResponse)?.groupValues?.get(1)
                    }
                    if (subtitleSources.isEmpty()) {
                        Regex("""(https?://[^"']*\.(?:vtt|srt))""", RegexOption.IGNORE_CASE)
                            .findAll(iframeResponse).forEach { subtitleSources.add(it.groupValues[1]) }
                    }
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Fast/Fail", e.message ?: "")
            }

            // ============ SLOW PATH: WebView fallback ============
            if (m3u8Url.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("Slow", "Fallback WebView: $embedUrl")
                val seen = mutableListOf<String>()
                val hit = sniffStream(embedUrl, "https://vidsrc.to/", ua, seen)
                ClipboardLogger.logAndCopy("Slow/seen(${seen.size})", seen.joinToString("\n").take(4000))
                m3u8Url = hit?.replace("\\u0026", "&")?.replace("\\/", "/")?.replace("&amp;", "&")
                ClipboardLogger.logAndCopy("Slow/result", "$m3u8Url")
            }

            if (m3u8Url.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("Error", "Không bắt được link stream")
                return false
            }

            ApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, ref.season, ref.episode)
            subtitleSources.distinct().forEach { if (it.isNotEmpty()) subtitleCallback.invoke(SubtitleFile("Vidsrcto", it)) }

            // ⭐ Dùng ExtractorLink trực tiếp (giống bản gốc đang chạy)
            ClipboardLogger.logAndCopy("Success", "M3U8: $m3u8Url")
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Vidsrcto",
                    url = m3u8Url!!,
                    referer = "https://vidsrc.to/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = headers
                )
            )
            return true
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "${e.message}\n${e.stackTraceToString().take(1500)}")
            return false
        }
    }
}
