package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
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
import java.util.Base64

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ PROXY
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

// Định danh nội bộ: "vidscmov:movie:<id>" / "vidscmov:tv:<id>" / "vidscmov:tv:<id>:<s>:<e>".
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
object ApiServices {
    suspend fun getOmdbData(imdbId: String): String? {
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
class VidscmovProvider : MainAPI() {
    // mainUrl KHÔNG được là địa chỉ proxy TMDB: CloudStream ghép mainUrl vào trước mọi url không bắt đầu bằng http
    // (vd "vidscmov:tv:1" -> "<proxy>/3/vidscmov:tv:1") làm load() phân tích sai và báo "This provider does not exist".
    override var mainUrl = "https://vidsrc.mov"
    override var name = "Vidscmov"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$TMDB_BASE_URL/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$TMDB_BASE_URL/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$TMDB_BASE_URL/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    private fun poster(path: String?) = path?.takeIf { it.isNotEmpty() && it != "null" }?.let { "https://image.tmdb.org/t/p/w500$it" }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = app.get("${request.data}&page=$page").text
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
                home.add(newMovieSearchResponse(title, "vidscmov:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
            val json = JSONObject(app.get("$TMDB_BASE_URL/search/multi?api_key=$TMDB_API_KEY&query=$q").text)
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                out.add(newMovieSearchResponse(title, "vidscmov:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
            val response = app.get("$TMDB_BASE_URL/$mediaType/$tmdbIdInt?api_key=$TMDB_API_KEY").text
            val json = JSONObject(response)
            if (json.has("status_code")) throw Exception("TMDB: ${json.optString("status_message")}")
            val title = json.optString("title", json.optString("name"))
            val description = json.optString("overview")
            val poster = poster(json.optString("poster_path"))
            val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
            val imdbId = json.optString("imdb_id")

            var imdbScore: Score? = null
            if (imdbId.isNotEmpty()) {
                ApiServices.getOmdbData(imdbId)?.let { ratingStr ->
                    ratingStr.toDoubleOrNull()?.let { imdbScore = Score.from10(it) }
                }
            }

            val canonical = "vidscmov:$mediaType:$tmdbIdInt"
            if (mediaType == "tv") {
                val episodes = mutableListOf<Episode>()
                val seasonsCount = json.optInt("number_of_seasons", 1)
                for (s in 1..seasonsCount) {
                    try {
                        val seasonJson = JSONObject(app.get("$TMDB_BASE_URL/tv/$tmdbIdInt/season/$s?api_key=$TMDB_API_KEY").text)
                        val eps = seasonJson.optJSONArray("episodes") ?: continue
                        for (e in 0 until eps.length()) {
                            val ep = eps.getJSONObject(e)
                            val sn = ep.optInt("season_number", s)
                            val en = ep.optInt("episode_number", e + 1)
                            episodes.add(newEpisode("vidscmov:tv:$tmdbIdInt:$sn:$en") {
                                this.name = ep.optString("name")
                                this.episode = en
                                this.season = sn
                                this.description = ep.optString("overview")
                                this.posterUrl = ep.optString("still_path").let { if (it.isNotEmpty() && it != "null") "https://image.tmdb.org/t/p/w300$it" else null }
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
    // CÁC HÀM GIẢI MÃ (DECRYPTORS) TỪ VIDSRC
    // =====================================================================================
    private fun decodeUrl(encType: String, url: String): String? {
        return when (encType) {
            "NdonQLf1Tzyx7bMG" -> bMGyx71TzQLfdonN(url)
            "sXnL9MQIry" -> Iry9MQXnLs(url)
            "IhWrImMIGL" -> IGLImMhWrI(url)
            "xTyBxQyGTA" -> GTAxQyTyBx(url)
            "ux8qjPHC66" -> C66jPHx8qu(url)
            "eSfH1IRMyL" -> MyL1IRSfHe(url)
            "KJHidj7det" -> detdj7JHiK(url)
            "o2VSUnjnZl" -> nZlUnj2VSo(url)
            "Oi3v1dAlaM" -> laM1dAi3vO(url)
            "TsA2KGDGux" -> GuxKGDsA2T(url)
            "JoAHUMCLXV" -> LXVUMCoAHJ(url)
            else -> null
        }
    }

    private fun bMGyx71TzQLfdonN(a: String): String {
        val b = 3; val c = mutableListOf<String>(); var d = 0
        while (d < a.length) { c.add(a.substring(d, minOf(d + b, a.length))); d += b }
        return c.reversed().joinToString("")
    }

    private fun Iry9MQXnLs(a: String): String {
        val b = "pWB9V)[*4I`nJpp?ozyB~dbr9yt!_n4u"
        val d = a.chunked(2).map { Char(it.toInt(16)) }.joinToString("")
        var c = ""
        for (e in d.indices) { c += Char(d[e].code xor b[e % b.length].code) }
        var e = ""
        for (ch in c) { e += Char(ch.code - 3) }
        return String(Base64.getDecoder().decode(e))
    }

    private fun IGLImMhWrI(a: String): String {
        val b = a.reversed()
        val c = b.map { when (it) { in 'a'..'m', in 'A'..'M' -> it + 13; in 'n'..'z', in 'N'..'Z' -> it - 13; else -> it } }.joinToString("")
        val d = c.reversed()
        return String(Base64.getDecoder().decode(d))
    }

    private fun GTAxQyTyBx(a: String): String {
        val b = a.reversed()
        val c = b.filterIndexed { index, _ -> index % 2 == 0 }
        return String(Base64.getDecoder().decode(c))
    }

    private fun C66jPHx8qu(a: String): String {
        val b = a.reversed()
        val c = "X9a(O;FMV2-7VO5x;Ao:dN1NoFs?j,"
        val d = b.chunked(2).map { Char(it.toInt(16)) }.joinToString("")
        var e = ""
        for (i in d.indices) { e += Char(d[i].code xor c[i % c.length].code) }
        return e
    }

    private fun MyL1IRSfHe(a: String): String {
        val b = a.reversed()
        val c = b.map { Char(it.code - 1) }.joinToString("")
        return c.chunked(2).map { Char(it.toInt(16)) }.joinToString("")
    }

    private fun detdj7JHiK(a: String): String {
        val b = a.substring(10, a.length - 16)
        val c = "3SAY~#%Y(V%>5d/Yg\"\$G[Lh1rK4a;7ok"
        val d = String(Base64.getDecoder().decode(b))
        val e = c.repeat((d.length + c.length - 1) / c.length).substring(0, d.length)
        var f = ""
        for (i in d.indices) { f += Char(d[i].code xor e[i].code) }
        return f
    }

    private fun nZlUnj2VSo(a: String): String {
        val b = mapOf('x' to 'a', 'y' to 'b', 'z' to 'c', 'a' to 'd', 'b' to 'e', 'c' to 'f', 'd' to 'g', 'e' to 'h', 'f' to 'i', 'g' to 'j', 'h' to 'k', 'i' to 'l', 'j' to 'm', 'k' to 'n', 'l' to 'o', 'm' to 'p', 'n' to 'q', 'o' to 'r', 'p' to 's', 'q' to 't', 'r' to 'u', 's' to 'v', 't' to 'w', 'u' to 'x', 'v' to 'y', 'w' to 'z', 'X' to 'A', 'Y' to 'B', 'Z' to 'C', 'A' to 'D', 'B' to 'E', 'C' to 'F', 'D' to 'G', 'E' to 'H', 'F' to 'I', 'G' to 'J', 'H' to 'K', 'I' to 'L', 'J' to 'M', 'K' to 'N', 'L' to 'O', 'M' to 'P', 'N' to 'Q', 'O' to 'R', 'P' to 'S', 'Q' to 'T', 'R' to 'U', 'S' to 'V', 'T' to 'W', 'U' to 'X', 'V' to 'Y', 'W' to 'Z')
        return a.map { b[it] ?: it }.joinToString("")
    }

    private fun laM1dAi3vO(a: String): String {
        val b = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.getDecoder().decode(b))
        var e = ""
        for (ch in d) { e += Char(ch.code - 5) }
        return e
    }

    private fun GuxKGDsA2T(a: String): String {
        val b = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.getDecoder().decode(b))
        var e = ""
        for (ch in d) { e += Char(ch.code - 7) }
        return e
    }

    private fun LXVUMCoAHJ(a: String): String {
        val b = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.getDecoder().decode(b))
        var e = ""
        for (ch in d) { e += Char(ch.code - 3) }
        return e
    }


    // =====================================================================================
    // WebView riêng: tải trang player, chặn mọi popup, gửi chạm THẬT (MotionEvent) vào giữa màn hình để bấm Play
    // và bắt URL .m3u8/.mpd đầu tiên mà trang gọi.
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, referer: String, ua: String, seen: MutableList<String>): String? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val appCtx: Context? = CloudStreamApp.context
                val act = CommonActivity.activity
                val ctx: Context? = act ?: appCtx
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Sniff", "Không có Context để tạo WebView")
                    cont.resume(null)
                } else try {
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
                        } catch (_: Exception) {
                        }
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
                    // Popup quảng cáo: không mở cửa sổ mới
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
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle(); u.recycle()
                        } catch (_: Exception) {
                        }
                        if (taps < 25) handler.postDelayed({ tap() }, 1500)
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!junkRegex.containsMatchIn(u) && seen.size < 80) seen.add(u.take(220))
                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                handler.post { finish(u) }
                            }
                            return null
                        }

                        // chặn mọi chuyển hướng sang trang quảng cáo
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val ok = u.startsWith(pageUrl.substringBefore("?")) || u.contains("stellarconductornexus.com")
                            if (!ok) seen.add("BLOCKED NAV: " + u.take(160))
                            return !ok
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
                        }
                    }
                    // Gắn vào cửa sổ (ngoài màn hình) để WebView nhận chạm và chạy như bình thường
                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try {
                        act?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) {
                    }
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
    // HÀM CHÍNH: LOAD LINKS
    // vidsrc.mov hiện chạy theo chuỗi: vidsrc.mov -> vidsrc.sh -> stellarconductornexus.com (embed) -> player.
    // Dữ liệu stream được giải mã bằng WebAssembly trong trình duyệt nên plugin dùng WebView để bắt link .m3u8.
    // =====================================================================================
    private fun cleanJsUrl(s: String) = s.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    private fun absUrl(base: String, u: String): String {
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: return u
        return if (u.startsWith("/")) host + u else "$host/$u"
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ClipboardLogger.reset()
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

        val embedUrl = if (type == "movie") {
            "https://vidsrc.mov/embed/movie/$tmdbId"
        } else {
            if (season == null || episode == null) {
                ClipboardLogger.logAndCopy("Error", "Thiếu season/episode: $data")
                return false
            }
            "https://vidsrc.mov/embed/tv/$tmdbId/$season/$episode"
        }

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        try {
            // Bước 1: vidsrc.mov -> iframe vidsrc.sh (chỉ để log/kiểm tra)
            val shEmbed = if (type == "movie") "https://vidsrc.sh/embed/movie/$tmdbId/" else "https://vidsrc.sh/embed/tv/$tmdbId/$season/$episode/"
            ClipboardLogger.logAndCopy("Step1", "Embed vidsrc.sh: $shEmbed")

            // Bước 2: API vidsrc.sh/vs_src.php trả JSON {"src":"https://stellarconductornexus.com/embed/...?vs=TOKEN"} (token hết hạn rất nhanh)
            val srcApi = if (type == "movie") "https://vidsrc.sh/vs_src.php?type=movie&id=$tmdbId"
            else "https://vidsrc.sh/vs_src.php?type=tv&id=$tmdbId&season=$season&episode=$episode"
            val r2 = app.get(srcApi, headers = mapOf("User-Agent" to ua, "Accept" to "application/json", "Referer" to shEmbed, "Origin" to "https://vidsrc.sh"))
            ClipboardLogger.logAndCopy("Step2 vs_src.php", "HTTP ${r2.code}\n${r2.text.take(500)}")
            val if2 = Regex("\"src\"\\s*:\\s*\"([^\"]+)\"").find(r2.text)?.groupValues?.get(1)?.let { cleanJsUrl(it) }
            ClipboardLogger.logAndCopy("Step2 src", "$if2")

            // Bước 3: trang landing của stellarconductornexus có CFG.playerUrl
            var playerPage: String? = null
            if (if2 != null) {
                val r3 = app.get(if2, headers = mapOf("User-Agent" to ua, "Referer" to "https://vidsrc.sh/"))
                ClipboardLogger.logAndCopy("Step3 landing", "HTTP ${r3.code}, ${r3.text.length} bytes\n" + r3.text.take(300))
                val pu = Regex("\"playerUrl\"\\s*:\\s*\"([^\"]+)\"").find(r3.text)?.groupValues?.get(1)
                ClipboardLogger.logAndCopy("Step3 playerUrl", "$pu")
                if (pu != null) playerPage = absUrl(if2, cleanJsUrl(pu))
            }
            val if1: String? = shEmbed

            // Bước 4: mở trang player trong WebView, tự bấm Play, ghi lại mọi request và bắt link m3u8/mpd/mp4
            val target = playerPage ?: if2 ?: if1 ?: embedUrl
            val refererForWv = if2 ?: if1 ?: "https://vidsrc.mov/"
            ClipboardLogger.logAndCopy("Step4 WebView", "Mở: $target\nReferer: $refererForWv")
            val seen = mutableListOf<String>()
            val hit: String? = sniffStream(target, refererForWv, ua, seen)
            val finalUrl: String? = hit
            ClipboardLogger.logAndCopy("Step4 requests (${seen.size})", seen.joinToString("\n"))
            ClipboardLogger.logAndCopy("Step4 kết quả", "url=$finalUrl")

            if (finalUrl.isNullOrEmpty()) {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được link stream (xem danh sách request ở trên).")
                return false
            }

            ApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)
            val origin = Regex("""^(https?://[^/]+)""").find(refererForWv)?.groupValues?.get(1) ?: "https://vidsrc.mov"
            callback.invoke(
                newExtractorLink(this.name, "Vidscmov", finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to ua)
                }
            )
            return true
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString().take(1500)}")
            return false
        }
    }
}
