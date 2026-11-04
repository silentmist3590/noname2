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
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""
private const val IMG_PROXY = "https://wsrv.nl/?url="

// ==== CHAIN THẬT (đã xác nhận qua network captures) ====
private const val VIDSRC_SH = "https://vidsrc.sh"
private const val SRC_API = "https://vidsrc.sh/vs_src.php"

// Chỉ chặn quảng cáo rõ ràng — từ captures của bạn
private val AD_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "popads.net", "onclickads.net", "propellerads.com", "popcash.net",
    "adsterra.com", "adcash.com", "rapidadexchange.com", "youradexchange.com",
    "adexchangerapid.com", "googletagmanager.com", "googletagservices.com",
    "bufingarribes.com", "meatusbyway.cfd"
)

private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")
private class Ref(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): Ref? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return Ref(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

private data class SniffResult(val url: String, val referer: String, val origin: String)

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
        val headers = mapOf(
            "Content-Type" to "application/json", "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID, "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
        )
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
            ClipboardLogger.logAndCopy("getMainPage", e.message ?: "")
            newHomePageResponse(request.name, emptyList(), false)
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
            if (imdbId.isNotEmpty()) {
                ApiServices.getOmdbData(imdbId)?.toDoubleOrNull()?.let { score = Score.from10(it) }
            }

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
    // WEBVIEW SNIFFER — capture URL + Referer + Origin, timeout 180s, chỉ chặn ads
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
        initialReferer: String,
        ua: String,
        seen: MutableList<String>
    ): SniffResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity ?: CloudStreamApp.context
                if (ctx == null) { cont.resume(null); return@post }
                try {
                    val w = 1080; val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    var lastPlayerUrl: String = initialReferer

                    fun finish(res: SniffResult?) {
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

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: android.os.Message?
                        ): Boolean = false
                    }

                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-display-icon-container,.jw-bigplay,[aria-label=Play],button[aria-label=Play],.vjs-big-play-button,.play-btn'));" +
                        "fire(document.querySelector('[class*=skip],[aria-label*=Skip],[aria-label*=skip]'));" +
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
                        if (taps < 30) handler.postDelayed({ tap() }, 1500)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (url != null && !AD_HOSTS.any { url.contains(it, true) }) {
                                lastPlayerUrl = url
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            val reqHeaders = request?.requestHeaders ?: emptyMap()

                            if (!junkRegex.containsMatchIn(u) && seen.size < 150) {
                                val ref = reqHeaders["Referer"] ?: reqHeaders["referer"] ?: ""
                                seen.add("${u.take(200)} | Ref=${ref.take(60)}")
                            }

                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                // Capture Referer/Origin thật từ request
                                val ref = reqHeaders["Referer"] ?: reqHeaders["referer"]
                                    ?: lastPlayerUrl.ifEmpty { initialReferer }
                                val origin = reqHeaders["Origin"] ?: reqHeaders["origin"]
                                    ?: Regex("""^(https?://[^/]+)""").find(ref)?.groupValues?.get(1)
                                    ?: ref

                                val hit = SniffResult(u, ref, origin)
                                ClipboardLogger.logAndCopy(
                                    "Sniff HIT",
                                    "URL: ${u.take(250)}\nRef: $ref\nOrigin: $origin"
                                )
                                handler.post { finish(hit) }
                            }
                            return null
                        }

                        // Chỉ chặn ad, cho phép mọi redirect player
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val isAd = AD_HOSTS.any { u.contains(it, ignoreCase = true) }
                            if (isAd) {
                                seen.add("BLOCKED AD: ${u.take(180)}")
                                return true
                            }
                            seen.add("NAV: ${u.take(180)}")
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
                    try { CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h)) } catch (_: Exception) {}

                    // 180s — chain vidsrc.sh → stellarconductornexus có thể mất 2-3 phút
                    handler.postDelayed({ finish(null) }, 180_000L)

                    wv.loadUrl(pageUrl, mapOf("Referer" to initialReferer))
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
        s.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    private fun absUrl(base: String, u: String): String {
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: return u
        return if (u.startsWith("/")) host + u else "$host/$u"
    }

    // =====================================================================================
    // LOAD LINKS — chain vidsrc.sh (giống Vidsrcsu đang chạy)
    // =====================================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks", "Data: $data")

        val ref = parseRef(data) ?: return false
        val type = ref.type
        val tmdbId = ref.id

        if (type == "tv" && (ref.season == null || ref.episode == null)) {
            ClipboardLogger.logAndCopy("Error", "Thiếu season/episode")
            return false
        }

        ApiServices.sendTraktScrobble("start", type, tmdbId, 0f, ref.season, ref.episode)

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        try {
            val shEmbed = if (type == "movie")
                "$VIDSRC_SH/embed/movie/$tmdbId/"
            else
                "$VIDSRC_SH/embed/tv/$tmdbId/${ref.season}/${ref.episode}/"

            val srcApi = if (type == "movie")
                "$SRC_API?type=movie&id=$tmdbId"
            else
                "$SRC_API?type=tv&id=$tmdbId&season=${ref.season}&episode=${ref.episode}"

            // === BƯỚC 1: Gọi vs_src.php để lấy src URL ===
            ClipboardLogger.logAndCopy("Step1 vs_src", "URL: $srcApi")
            val r2 = app.get(srcApi, headers = mapOf(
                "User-Agent" to ua,
                "Accept" to "application/json",
                "Referer" to shEmbed,
                "Origin" to VIDSRC_SH
            ))
            ClipboardLogger.logAndCopy("Step1 resp", "HTTP ${r2.code}\n${r2.text.take(500)}")

            val srcUrl = Regex("""\"src\"\s*:\s*\"([^\"]+)\"""")
                .find(r2.text)?.groupValues?.get(1)
                ?.let { cleanJsUrl(it) }
            ClipboardLogger.logAndCopy("Step1 src", "$srcUrl")

            // === BƯỚC 2: Fetch src URL để lấy playerUrl ===
            var target: String? = null
            var wvReferer = "$VIDSRC_SH/"

            if (srcUrl != null) {
                try {
                    val r3 = app.get(srcUrl, headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to "$VIDSRC_SH/"
                    ))
                    ClipboardLogger.logAndCopy("Step2 landing", "HTTP ${r3.code}, ${r3.text.length} bytes")

                    val playerUrl = Regex("""\"playerUrl\"\s*:\s*\"([^\"]+)\"""")
                        .find(r3.text)?.groupValues?.get(1)
                        ?.let { cleanJsUrl(it) }
                    ClipboardLogger.logAndCopy("Step2 playerUrl", "$playerUrl")

                    if (playerUrl != null) {
                        target = absUrl(srcUrl, playerUrl)
                        wvReferer = srcUrl
                    } else {
                        target = srcUrl
                        wvReferer = "$VIDSRC_SH/"
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    ClipboardLogger.logAndCopy("Step2 Fail", e.message ?: "")
                    target = srcUrl
                }
            }

            // Fallback: dùng embed URL
            if (target == null) {
                target = shEmbed
                wvReferer = "$VIDSRC_SH/"
            }

            // === BƯỚC 3: WebView sniff trên player page ===
            ClipboardLogger.logAndCopy("Step3 WebView", "Target: $target\nReferer: $wvReferer")

            val seen = mutableListOf<String>()
            val result = sniffStream(target!!, wvReferer, ua, seen)

            ClipboardLogger.logAndCopy(
                "Seen (${seen.size})",
                seen.joinToString("\n").take(4500)
            )
            ClipboardLogger.logAndCopy(
                "Result",
                if (result == null) "null"
                else "URL=${result.url.take(200)}\nRef=${result.referer}\nOrigin=${result.origin}"
            )

            if (result == null) {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được m3u8")
                return false
            }

            val finalUrl = cleanJsUrl(result.url)
            ApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, ref.season, ref.episode)

            ClipboardLogger.logAndCopy(
                "Success",
                "URL: ${finalUrl.take(300)}\nReferer: ${result.referer}\nOrigin: ${result.origin}"
            )

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Vidsrcto",
                    url = finalUrl,
                    referer = result.referer,
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to result.referer,
                        "Origin" to result.origin,
                        "Accept" to "*/*",
                        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
                    )
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
