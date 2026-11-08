package com.lagradost

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
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ ĐỊNH DANH
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

// Định danh an toàn bằng Regex để tránh lỗi CloudStream fixUrl nối mainUrl vào trước
private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")

private class MediaRef(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): MediaRef? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return MediaRef(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

// =====================================================================================
// 2. CÁC HÀM GỌI API HỖ TRỢ (OMDb, Trakt)
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
class HnembedProvider : MainAPI() {
    override var mainUrl = "https://hnembed.cc"
    override var name = "Hnembed"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    // Ảnh TMDB qua CDN wsrv.nl để không bị nhà mạng chặn tại VN
    private fun img(path: String?, size: String = "w500") =
        path?.takeIf { it.isNotEmpty() && it != "null" }?.let { "https://wsrv.nl/?url=image.tmdb.org/t/p/$size$it" }

    private fun poster(path: String?) = img(path, "w500")

    // Danh sách host TMDB fallback
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
                home.add(newMovieSearchResponse(title, "hnembed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
                out.add(newMovieSearchResponse(title, "hnembed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
            val posterUrl = poster(json.optString("poster_path"))
            val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
            
            // Đã xóa hoàn toàn phần Score ở đây
            val canonical = "hnembed:$mediaType:$tmdbIdInt"

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
                            episodes.add(newEpisode("hnembed:tv:$tmdbIdInt:$sn:$en") {
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
                return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.year = year
                    // Đã xóa this.score
                }
            } else {
                return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.year = year
                    // Đã xóa this.score
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("load Error", "$url -> ${e.message}")
            return null
        }
    }

    // =====================================================================================
    // 4. BẮT LINK BẰNG WEBVIEW ẨN (SNIFF STREAM)
    // Chạy ngầm player, tự click play, bắt link .m3u8 và phụ đề tự động
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
        referer: String,
        ua: String,
        seen: MutableList<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val appCtx: Context? = try {
                    CloudStreamApp.context
                } catch (_: Throwable) {
                    try {
                        val activityThread = Class.forName("android.app.ActivityThread")
                        val method = activityThread.getMethod("currentApplication")
                        method.invoke(null) as? Context
                    } catch (_: Throwable) {
                        null
                    }
                }
                val act = try { CommonActivity.activity } catch (_: Throwable) { null }
                val ctx: Context? = act ?: appCtx
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Sniff", "Không có Context để khởi tạo WebView")
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
                            d.recycle()
                            u.recycle()
                        } catch (_: Exception) {}
                        if (taps < 25) handler.postDelayed({ tap() }, 1500)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!junkRegex.containsMatchIn(u) && seen.size < 80) seen.add(u.take(220))

                            // Bắt link stream video
                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                handler.post { finish(u) }
                            }

                            // Bắt link phụ đề
                            if ((u.contains(".vtt", ignoreCase = true) || u.contains(".srt", ignoreCase = true)) && !u.contains("blank.vtt")) {
                                val lang = Regex("""[?&]lang=([^&]+)""").find(u)?.groupValues?.get(1) ?: "Sub"
                                try { subtitleCallback.invoke(SubtitleFile(lang, u)) } catch (_: Exception) {}
                            }

                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val ok = u.startsWith(pageUrl.substringBefore("?")) || u.contains("stellarconductornexus.com")
                            if (!ok) seen.add("BLOCKED: " + u.take(150))
                            return !ok
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
                        }
                    }

                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try { act?.addContentView(wv, ViewGroup.LayoutParams(w, h)) } catch (_: Exception) {}

                    handler.postDelayed({ finish(null) }, 35_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    private fun cleanJsUrl(s: String) = s.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    private fun absUrl(base: String, u: String): String {
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: return u
        return if (u.startsWith("/")) host + u else "$host/$u"
    }

    // =====================================================================================
    // 5. LOAD LINKS: KẾT HỢP DỰ PHÒNG CÁC HOST (hnembed.cc -> vidsrc.sh -> vidsrc.io)
    // =====================================================================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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
        if (type == "tv" && (season == null || episode == null)) {
            ClipboardLogger.logAndCopy("Error", "Thiếu season/episode: $data")
            return false
        }

        ApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        
        // Danh sách các mirror cung cấp vs_src.php
        val mirrors = listOf("https://hnembed.cc", "https://vidsrc.sh", "https://vidsrc.io")
        var finalUrl: String? = null
        var refererForWv = "https://stellarconductornexus.com/"

        for (mirror in mirrors) {
            val embedUrl = if (type == "movie") "$mirror/embed/movie/$tmdbId/" else "$mirror/embed/tv/$tmdbId/$season/$episode/"
            val srcApi = if (type == "movie") "$mirror/vs_src.php?type=movie&id=$tmdbId"
            else "$mirror/vs_src.php?type=tv&id=$tmdbId&season=$season&episode=$episode"

            for (attempt in 1..2) {
                try {
                    val r2 = app.get(srcApi, headers = mapOf(
                        "User-Agent" to ua,
                        "Accept" to "application/json",
                        "Referer" to embedUrl,
                        "Origin" to mirror
                    ))
                    val if2 = Regex("\"src\"\\s*:\\s*\"([^\"]+)\"").find(r2.text)?.groupValues?.get(1)?.let { cleanJsUrl(it) }
                    if (if2 == null) {
                        ClipboardLogger.logAndCopy("Notice", "Mirror $mirror lần $attempt: HTTP ${r2.code}: ${r2.text.take(150)}")
                        continue
                    }

                    val r3 = app.get(if2, headers = mapOf("User-Agent" to ua, "Referer" to "$mirror/"))
                    val pu = Regex("\"playerUrl\"\\s*:\\s*\"([^\"]+)\"").find(r3.text)?.groupValues?.get(1)
                    val target = pu?.let { absUrl(if2, cleanJsUrl(it)) } ?: if2
                    refererForWv = if2

                    ClipboardLogger.logAndCopy("Target", "Sniffing $target")
                    val seen = mutableListOf<String>()
                    finalUrl = sniffStream(target, refererForWv, ua, seen, subtitleCallback)
                    if (finalUrl != null) break
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    ClipboardLogger.logAndCopy("Exception", "Mirror $mirror lần $attempt: ${e.message}")
                }
            }
            if (finalUrl != null) break
        }

        val url = finalUrl
        if (url.isNullOrEmpty()) {
            ClipboardLogger.logAndCopy("Error", "Không lấy được link stream sau khi thử các mirror.")
            return false
        }

        ApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)
        val origin = Regex("""^(https?://[^/]+)""").find(refererForWv)?.groupValues?.get(1) ?: "https://stellarconductornexus.com"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Hnembed",
                url = url,
                referer = "$origin/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to ua)
            )
        )
        return true
    }
}
