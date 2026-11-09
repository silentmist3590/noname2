package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder

// =====================================================================================
// 1. CẤU HÌNH
// =====================================================================================
private const val BASE_URL = "https://vidapi.ru"
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

// =====================================================================================
// 2. API SERVICES (Giữ nguyên)
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
            val headers = mapOf("User-Agent" to "trailers.to-UA", "X-User-Agent" to "trailers.to-UA")
            val languages = listOf("vie", "eng") 
            
            for (lang in languages) {
                try {
                    val url = "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$lang"
                    val res = app.get(url, headers = headers).text
                    if (!res.trim().startsWith("[")) continue
                    val arr = JSONArray(res)
                    for (i in 0 until minOf(arr.length(), 5)) {
                        val item = arr.getJSONObject(i)
                        val subUrl = item.optString("SubDownloadLink")
                        val subLang = item.optString("SubLanguageID", lang)
                        if (subUrl.isNotEmpty()) callback.invoke(SubtitleFile(subLang, subUrl))
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
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
    // LOAD LINKS: gọi thẳng API stream của player (streamdata.vaplayer.ru); WebView chỉ là dự phòng
    // =====================================================================================
    private val playerOrigin = "https://nextgencloudfabric.com"

    /** Phát 1 master playlist: tách từng độ phân giải nếu có, ngược lại phát nguyên link. */
    private suspend fun emitMaster(m3u8Url: String, label: String, ua: String, callback: (ExtractorLink) -> Unit) {
        val reqHeaders = mapOf("User-Agent" to ua, "Referer" to "$playerOrigin/", "Origin" to playerOrigin)
        val content = try { app.get(m3u8Url, headers = reqHeaders).text } catch (_: Throwable) { "" }
        val matches = Regex("""#EXT-X-STREAM-INF:.*RESOLUTION=(\d+)x(\d+).*\n(.*)""").findAll(content).toList()
        ClipboardLogger.logAndCopy("Master", "$label $m3u8Url\nvariants=${matches.size}")
        if (matches.isNotEmpty()) {
            matches.forEach { match ->
                val height = match.groupValues[2].toIntOrNull() ?: 0
                val subUrl = match.groupValues[3].trim()
                val full = if (subUrl.startsWith("http")) subUrl else m3u8Url.substringBeforeLast("/") + "/" + subUrl
                val q = when {
                    height >= 1080 -> Qualities.P1080.value
                    height >= 720 -> Qualities.P720.value
                    height >= 480 -> Qualities.P480.value
                    else -> Qualities.P360.value
                }
                callback.invoke(
                    newExtractorLink(this.name, "${this.name} $label ${height}p", full, ExtractorLinkType.M3U8) {
                        this.referer = "$playerOrigin/"
                        this.quality = q
                        this.headers = reqHeaders
                    }
                )
            }
        } else {
            callback.invoke(
                newExtractorLink(this.name, "${this.name} $label", m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "$playerOrigin/"
                    this.quality = Qualities.Unknown.value
                    this.headers = reqHeaders
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks", "Data: $data")

        val ref = parseRef(data) ?: return false
        val embedUrl = if (ref.type == "movie") "$BASE_URL/embed/movie/${ref.id}"
                       else "$BASE_URL/embed/tv/${ref.id}/${ref.season}/${ref.episode}"
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        try {
            val imdbId = imdbCache[ref.id] ?: ""
            ApiServices.fetchSubtitles(imdbId, subtitleCallback)

            // BƯỚC 1: gọi thẳng API stream (cùng API mà player.min.js dùng)
            val apiUrl = "https://streamdata.vaplayer.ru/api.php?tmdb=${ref.id}&type=${ref.type}" +
                if (ref.type == "tv") "&season=${ref.season}&episode=${ref.episode}" else ""
            var found = false
            try {
                val r = app.get(apiUrl, headers = mapOf("User-Agent" to ua, "Referer" to "$playerOrigin/", "Origin" to playerOrigin, "Accept" to "application/json"))
                ClipboardLogger.logAndCopy("Step 1 API", "url=$apiUrl\nHTTP ${r.code}\n${r.text.take(400)}")
                if (r.text.trimStart().startsWith("{")) {
                    val root = JSONObject(r.text)
                    val d = root.optJSONObject("data")
                    val urls = d?.optJSONArray("stream_urls")
                    if (urls != null) {
                        for (i in 0 until urls.length()) {
                            val u = urls.optString(i).replace("\\/", "/")
                            if (u.startsWith("http")) {
                                emitMaster(u, "S${i + 1}", ua, callback)
                                found = true
                            }
                        }
                    }
                    // Phụ đề mặc định của player (nếu có): mảng các object {file/url, label/lang}
                    val subs = root.optJSONArray("default_subs") ?: d?.optJSONArray("default_subs")
                    if (subs != null) {
                        for (i in 0 until subs.length()) {
                            val o = subs.optJSONObject(i) ?: continue
                            val f = o.optString("file").ifEmpty { o.optString("url") }
                            if (f.startsWith("http")) subtitleCallback.invoke(SubtitleFile(o.optString("label").ifEmpty { o.optString("lang", "Sub") }, f))
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Step 1 API Exception", e.message ?: "")
            }
            if (found) return true

            // BƯỚC 2 (dự phòng): WebView tùy chỉnh trên trang embed
            ClipboardLogger.logAndCopy("Step 2", "API không có link -> mở WebView: $embedUrl")
            val sniffedResult = sniffWithCustomWebView(embedUrl, "$BASE_URL/", ua)
            if (sniffedResult == null) {
                ClipboardLogger.logAndCopy("Error", "WebView tùy chỉnh không bắt được link m3u8.")
                return false
            }
            ClipboardLogger.logAndCopy("Step 2", "Bắt được M3U8: ${sniffedResult.first}")
            emitMaster(sniffedResult.first, "WV", ua, callback)
            return true
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", e.message ?: "")
            return false
        }
    }

    // =====================================================================================
    // HÀM WEBVIEW TÙY CHỈNH: Vượt Cloudflare và tương tác với iframe
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffWithCustomWebView(pageUrl: String, referer: String, ua: String): Pair<String, List<SubtitleFile>>? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity ?: ClipboardLogger.appContext()
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("CustomWebView", "Không có Context")
                    cont.resume(null)
                    return@post
                }
                
                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    val seenReq = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val collectedSubtitles = mutableListOf<SubtitleFile>()

                    fun finish(res: Pair<String, List<SubtitleFile>>?) {
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
                    val subRegex = Regex("""\.(vtt|srt)""", RegexOption.IGNORE_CASE)
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
                    
                    // Script mạnh mẽ: xóa dấu vết webdriver, chặn popup, tự động click
                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};alert=function(){};}catch(e){}"
                    val clickJs = "(function(){" +
                        "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});" +
                        "Object.defineProperty(navigator, 'languages', {get: () => ['vi-VN', 'vi', 'en']});" +
                        "Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]});" +
                        "window.chrome = { runtime: {} };" +
                        "function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-bigplay,[aria-label=Play],.vjs-big-play-button,.play-btn'));" +
                        "var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
                        "})();"
                    
                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(fakePopup + clickJs, null)
                            
                            // Gửi MotionEvent "chạm" thật
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle()
                            u.recycle()
                        } catch (_: Exception) {}
                        if (taps < 60) handler.postDelayed({ tap() }, 1500)
                    }
                    
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (seenReq.size < 80) seenReq.add(u)
                            
                            val adDomains = listOf("doubleclick", "googlesyndication", "popads", "onclickads", "adsterra", "propellerads")
                            if (adDomains.any { u.contains(it, true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                            
                            if (subRegex.containsMatchIn(u)) {
                                collectedSubtitles.add(SubtitleFile(u.substringAfterLast("/").take(20), u))
                            }

                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                ClipboardLogger.logAndCopy("CustomWebView HIT", "URL: $u")
                                handler.post { finish(Pair(u, collectedSubtitles)) }
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
                        }
                    }
                    
                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try { CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h)) } catch (_: Exception) {}
                    
                    handler.postDelayed({ ClipboardLogger.logAndCopy("CustomWebView timeout", "requests(${seenReq.size}):\n" + seenReq.joinToString("\n")); finish(null) }, 120_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                    
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("CustomWebView Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }
}
