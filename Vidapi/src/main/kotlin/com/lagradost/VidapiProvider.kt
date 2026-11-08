package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink // ⭐ Thêm import này
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
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder

// =====================================================================================
// 1. CẤU HÌNH
// =====================================================================================
private const val BASE_URL = "https://vidapi.ru"
private const val EMBED_BASE_URL = "https://vaplayer.ru"
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

private data class SniffResult(
    val url: String,
    val headers: Map<String, String>,
    val subtitles: List<SubtitleFile> = emptyList()
)

// Chặn quảng cáo
private val AD_DOMAINS = listOf(
    "rapidadexchange.com", "youradexchange.com", "adexchangerapid.com",
    "popads.net", "onclickads.net", "propellerads.com", "popcash.net",
    "adsterra.com", "adcash.com", "doubleclick.net", "googlesyndication",
    "googleadservices", "googletagmanager", "googletagservices"
)

// =====================================================================================
// 2. API SERVICES
// =====================================================================================
object ApiServices {
    suspend fun getTmdbDetails(type: String, tmdbId: Int): JSONObject? {
        return try {
            val url = "$TMDB_BASE_URL/$type/$tmdbId?api_key=$TMDB_API_KEY"
            JSONObject(app.get(url).text)
        } catch (e: Exception) { null }
    }

    suspend fun fetchSubtitles(imdbId: String, callback: (SubtitleFile) -> Unit) {
        if (imdbId.isEmpty()) return
        try {
            val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId }
            val url = "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-eng,vie"
            val headers = mapOf("User-Agent" to "trailers.to-UA", "X-User-Agent" to "trailers.to-UA")
            val res = app.get(url, headers = headers).text
            val arr = JSONArray(res)
            for (i in 0 until minOf(arr.length(), 10)) {
                val item = arr.getJSONObject(i)
                val subUrl = item.optString("SubDownloadLink")
                val lang = item.optString("SubLanguageID", "eng")
                if (subUrl.isNotEmpty()) callback.invoke(SubtitleFile(lang, subUrl))
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Sub Error", e.message ?: "")
        }
    }
}

// =====================================================================================
// 3. LỚP PROVIDER CHÍNH
// =====================================================================================
class VidapiProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "Vidapi"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$TMDB_BASE_URL/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$TMDB_BASE_URL/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$TMDB_BASE_URL/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    private val imdbCache = mutableMapOf<Int, String>()

    private fun poster(path: String?, size: String = "w500"): String? {
        if (path.isNullOrEmpty() || path == "null") return null
        return "$IMG_PROXY${URLEncoder.encode("https://image.tmdb.org/t/p/$size$path", "UTF-8")}"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val json = JSONObject(app.get("${request.data}&page=$page").text)
            val results = json.optJSONArray("results") ?: throw Exception("No results")
            val home = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                home.add(newMovieSearchResponse(item.optString("title", item.optString("name")), "vidapi:$mediaType:$id", 
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
        } catch (e: Exception) { newHomePageResponse(request.name, emptyList(), false) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(app.get("$TMDB_BASE_URL/search/multi?api_key=$TMDB_API_KEY&query=$q").text)
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                out.add(newMovieSearchResponse(item.optString("title", item.optString("name")), "vidapi:$mediaType:$id",
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: return null
        val json = ApiServices.getTmdbDetails(ref.type, ref.id) ?: return null

        val title = json.optString("title", json.optString("name"))
        val imdbId = json.optString("imdb_id")
        if (imdbId.isNotEmpty()) imdbCache[ref.id] = imdbId

        if (ref.type == "tv") {
            val episodes = mutableListOf<Episode>()
            for (s in 1..json.optInt("number_of_seasons", 1)) {
                try {
                    val seasonJson = JSONObject(app.get("$TMDB_BASE_URL/tv/${ref.id}/season/$s?api_key=$TMDB_API_KEY").text)
                    val eps = seasonJson.optJSONArray("episodes") ?: continue
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        val sn = ep.optInt("season_number", s)
                        val en = ep.optInt("episode_number", e + 1)
                        episodes.add(newEpisode("vidapi:tv:${ref.id}:$sn:$en") {
                            this.name = ep.optString("name"); this.episode = en; this.season = sn
                            this.posterUrl = poster(ep.optString("still_path"), "w300")
                        })
                    }
                } catch (_: Exception) {}
            }
            return newTvSeriesLoadResponse(title, "vidapi:tv:${ref.id}", TvType.TvSeries, episodes) {
                this.posterUrl = poster(json.optString("poster_path")); this.plot = json.optString("overview")
                this.year = json.optString("first_air_date").take(4).toIntOrNull()
            }
        } else {
            return newMovieLoadResponse(title, "vidapi:movie:${ref.id}", TvType.Movie, "vidapi:movie:${ref.id}") {
                this.posterUrl = poster(json.optString("poster_path")); this.plot = json.optString("overview")
                this.year = json.optString("release_date").take(4).toIntOrNull()
            }
        }
    }

    // =====================================================================================
    // WEBVIEW SNIFFER
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
                    val collectedSubtitles = mutableListOf<SubtitleFile>()

                    fun finish(res: SniffResult?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try { wv.stopLoading(); (wv.parent as? ViewGroup)?.removeView(wv); wv.destroy() } catch (_: Exception) {}
                        cont.resume(res)
                    }

                    val streamRegex = Regex("""\.m3u8|\.mpd""", RegexOption.IGNORE_CASE)
                    val subRegex = Regex("""\.(vtt|srt)""", RegexOption.IGNORE_CASE)
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                            val popupView = WebView(ctx)
                            popupView.settings.javaScriptEnabled = true
                            popupView.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    if (AD_DOMAINS.any { request?.url.toString().contains(it, true) }) {
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                                    }
                                    return null
                                }
                            }
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            transport?.webView = popupView
                            resultMsg?.sendToTarget()
                            handler.postDelayed({ try { popupView.destroy() } catch (_: Exception) {} }, 10000)
                            return true
                        }
                    }

                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-bigplay,[aria-label=Play],.vjs-big-play-button,.play-btn'));" +
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
                        if (taps < 100) handler.postDelayed({ tap() }, 1500)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            
                            if (AD_DOMAINS.any { u.contains(it, true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                            if (!junkRegex.containsMatchIn(u) && seen.size < 150) seen.add(u.take(220))

                            if (subRegex.containsMatchIn(u)) {
                                collectedSubtitles.add(SubtitleFile(u.substringAfterLast("/").take(20), u))
                            }

                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                val reqHeaders = request?.requestHeaders ?: emptyMap()
                                val hit = SniffResult(u, reqHeaders, collectedSubtitles)
                                ClipboardLogger.logAndCopy("Sniff HIT", "URL: $u")
                                handler.post { finish(hit) }
                            }
                            return null
                        }
                        override fun onPageFinished(view: WebView?, url: String?) { handler.postDelayed({ tap() }, 800) }
                    }

                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try { CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h)) } catch (_: Exception) {}

                    handler.postDelayed({ finish(null) }, 300_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // =====================================================================================
    // LOAD LINKS (Đã cập nhật newExtractorLink)
    // =====================================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks", "Data: $data")

        val ref = parseRef(data) ?: return false
        val embedUrl = if (ref.type == "movie") "$EMBED_BASE_URL/embed/movie/${ref.id}"
                       else "$EMBED_BASE_URL/embed/tv/${ref.id}/${ref.season}/${ref.episode}"
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        try {
            val imdbId = imdbCache[ref.id] ?: ""
            ApiServices.fetchSubtitles(imdbId, subtitleCallback)

            val seen = mutableListOf<String>()
            val result = sniffStream(embedUrl, "$BASE_URL/", ua, seen)

            if (result == null) {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được link.")
                return false
            }

            result.subtitles.forEach { subtitleCallback.invoke(it) }

            val reqHeaders = mapOf("User-Agent" to ua, "Referer" to "$BASE_URL/", "Origin" to BASE_URL)
            val m3u8Content = runBlocking { try { app.get(result.url, headers = reqHeaders).text } catch (_: Exception) { "" } }

            val qualityRegex = Regex("""#EXT-X-STREAM-INF:.*RESOLUTION=(\d+)x(\d+).*\n(.*)""")
            val matches = qualityRegex.findAll(m3u8Content)

            if (matches.count() > 0) {
                matches.forEach { match ->
                    val height = match.groupValues[2].toIntOrNull() ?: 0
                    val subUrl = match.groupValues[3].trim()
                    val fullSubUrl = if (subUrl.startsWith("http")) subUrl else {
                        val base = result.url.substringBeforeLast("/")
                        "$base/$subUrl"
                    }
                    val q = when {
                        height >= 1080 -> Qualities.P1080.value
                        height >= 720 -> Qualities.P720.value
                        height >= 480 -> Qualities.P480.value
                        else -> Qualities.P360.value
                    }
                    
                    // ⭐ Sử dụng newExtractorLink thay vì constructor ExtractorLink
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} ${height}p",
                            url = fullSubUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$BASE_URL/"
                            this.quality = q
                            this.headers = reqHeaders
                        }
                    )
                }
            } else {
                // ⭐ Sử dụng newExtractorLink cho luồng đơn
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = result.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$BASE_URL/"
                        this.quality = Qualities.Unknown.value
                        this.headers = reqHeaders
                    }
                )
            }
            return true

        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", e.message ?: "")
            return false
        }
    }
}
