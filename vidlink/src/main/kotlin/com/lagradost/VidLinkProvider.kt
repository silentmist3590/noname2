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
import java.net.URLEncoder

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ ĐỊNH DANH
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

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
object VidlinkApiServices {
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
class VidlinkProvider : MainAPI() {
    override var mainUrl = "https://vidlink.pro"
    override var name = "VidLink"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    private fun img(path: String?, size: String = "w500") =
        path?.takeIf { it.isNotEmpty() && it != "null" }?.let { "https://wsrv.nl/?url=image.tmdb.org/t/p/$size$it" }

    private fun poster(path: String?) = img(path, "w500")

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
                home.add(newMovieSearchResponse(title, "vidlink:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
                out.add(newMovieSearchResponse(title, "vidlink:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
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
                VidlinkApiServices.getOmdbData(imdbId)?.let { ratingStr ->
                    ratingStr.toDoubleOrNull()?.let { imdbScore = Score.from10(it) }
                }
            }

            val canonical = "vidlink:$mediaType:$tmdbIdInt"
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
                            episodes.add(newEpisode("vidlink:tv:$tmdbIdInt:$sn:$en") {
                                this.name = ep.optString("name")
                                this.episode = en
                                this.season = sn
                                this.description = ep.optString("overview")
                                this.posterUrl = img(ep.optString("still_path"), "w300")
                            })
                        }
                    } catch (e: Exception) {
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
    // 4. BẮT STREAM BẰNG WEBVIEW ẨN (BẮT CẢ .mpd VÀ .m3u8)
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, ua: String, subtitleCallback: (SubtitleFile) -> Unit): String? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val appCtx: Context? = try { CloudStreamApp.context } catch (_: Throwable) { null }
                val act = try { CommonActivity.activity } catch (_: Throwable) { null }
                val ctx: Context? = act ?: appCtx
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Sniff", "Không có Context")
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

                    // Nhận diện cả DASH (.mpd) và HLS (.m3u8)
                    val streamRegex = Regex("""\.mpd|\.m3u8|manifest""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                    }

                    val clickJs = """
                        (function(){
                            try {
                                var btns = document.querySelectorAll('button, video, [role="button"], .play-button, .jw-display-icon-container');
                                btns.forEach(function(b){ b.click(); });
                                var v = document.querySelector('video');
                                if (v) { v.muted = true; v.play(); }
                            } catch(e){}
                        })();
                    """.trimIndent()

                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(clickJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle()
                            u.recycle()
                        } catch (_: Exception) {}
                        if (taps < 20) handler.postDelayed({ tap() }, 1500)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null

                            // Bắt URL stream (Ưu tiên MPD/DASH hoặc M3U8)
                            if (streamRegex.containsMatchIn(u)) {
                                handler.post { finish(u) }
                            }

                            // Bắt phụ đề .vtt hoặc .srt
                            if ((u.contains(".vtt", ignoreCase = true) || u.contains(".srt", ignoreCase = true)) && !u.contains("blank.vtt")) {
                                val lang = Regex("""[?&]lang=([^&]+)""").find(u)?.groupValues?.get(1) ?: "Sub"
                                try { subtitleCallback.invoke(SubtitleFile(lang, u)) } catch (_: Exception) {}
                            }

                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 1000)
                        }
                    }

                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try { act?.addContentView(wv, ViewGroup.LayoutParams(w, h)) } catch (_: Exception) {}

                    handler.postDelayed({ finish(null) }, 30_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to "https://vidlink.pro/"))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.message ?: "")
                    cont.resume(null)
                }
            }
        }
    }

    // =====================================================================================
    // 5. LOAD LINKS: HỖ TRỢ ĐẦY ĐỦ EXTRACTOR LINK DASH VÀ HLS
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

        VidlinkApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        val pageUrl = if (type == "movie") {
            "https://vidlink.pro/movie/$tmdbId?primaryColor=ffffff"
        } else {
            "https://vidlink.pro/tv/$tmdbId/${season ?: 1}/${episode ?: 1}?primaryColor=ffffff"
        }

        ClipboardLogger.logAndCopy("Target", "Sniffing $pageUrl")
        val streamUrl = sniffStream(pageUrl, ua, subtitleCallback)

        if (streamUrl.isNullOrEmpty()) {
            ClipboardLogger.logAndCopy("Error", "Không bắt được link stream từ VidLink")
            return false
        }

        ClipboardLogger.logAndCopy("Success", "Stream URL: $streamUrl")
        VidlinkApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)

        // Phân biệt chính xác giữa DASH (.mpd) và HLS (.m3u8)
        val isDash = streamUrl.contains(".mpd", ignoreCase = true) || streamUrl.contains("dash", ignoreCase = true)

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "VidLink" + if (isDash) " (DASH)" else " (HLS)",
                url = streamUrl,
                referer = "https://vidlink.pro/",
                quality = Qualities.P1080.value,
                type = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8,
                headers = mapOf(
                    "Origin" to "https://vidlink.pro",
                    "Referer" to "https://vidlink.pro/",
                    "User-Agent" to ua
                )
            )
        )
        return true
    }
}

typealias VidLinkProvider = VidlinkProvider
