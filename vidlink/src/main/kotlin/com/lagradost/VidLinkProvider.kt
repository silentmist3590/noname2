package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

// =====================================================================================
// 1. CẤU HÌNH API VÀ REGEX
// =====================================================================================
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")

private class Ref(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): Ref? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return Ref(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

// =====================================================================================
// 2. DỊCH VỤ HỖ TRỢ (Trakt Scrobble)
// =====================================================================================
object ApiServices {
    suspend fun sendTraktScrobble(
        action: String,
        type: String,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    ) {
        if (TRAKT_ACCESS_TOKEN.isEmpty()) return
        val url = "https://api.trakt.tv/scrobble/$action"
        val headers = mapOf(
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID,
            "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
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
// 3. PROVIDER CHÍNH: VIDLINK
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
        }
        throw last ?: Exception("TMDB failed")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = tmdbGet("${request.data}?page=$page")
        val json = JSONObject(response)
        val results = json.optJSONArray("results") ?: throw Exception(json.optString("status_message", "TMDB error"))
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
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = tmdbGet("/search/multi?query=$encoded")
        val json = JSONObject(response)
        val results = json.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val id = item.optInt("id", 0)
            if (id == 0) continue
            val title = item.optString("title", item.optString("name"))
            list.add(newMovieSearchResponse(title, "vidlink:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster(item.optString("poster_path"))
            })
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse {
        val ref = parseRef(url) ?: throw Exception("URL không hợp lệ: $url")
        val isMovie = ref.type == "movie"
        val tmdbPath = if (isMovie) "/movie/${ref.id}" else "/tv/${ref.id}"
        val detailJson = JSONObject(tmdbGet(tmdbPath))
        val title = detailJson.optString("title", detailJson.optString("name"))
        val plot = detailJson.optString("overview")
        val posterUrl = poster(detailJson.optString("poster_path"))
        val backdropUrl = img(detailJson.optString("backdrop_path"), "original")
        val releaseDate = detailJson.optString("release_date", detailJson.optString("first_air_date"))
        val year = releaseDate.take(4).toIntOrNull()
        val genres = mutableListOf<String>()
        val genresArr = detailJson.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                genres.add(genresArr.getJSONObject(i).optString("name"))
            }
        }

        if (isMovie) {
            ApiServices.sendTraktScrobble("start", "movie", ref.id, 0.0f)
            return newMovieLoadResponse(title, url, TvType.Movie, "vidlink:movie:${ref.id}") {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = plot
                this.year = year
                this.tags = genres
                // ⭐ Đã xóa hoàn toàn phần Score
            }
        } else {
            ApiServices.sendTraktScrobble("start", "show", ref.id, 0.0f, 1, 1)
            val episodes = mutableListOf<Episode>()
            val seasonsArr = detailJson.optJSONArray("seasons")
            if (seasonsArr != null) {
                for (s in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.getJSONObject(s)
                    val seasonNum = sObj.optInt("season_number", 0)
                    if (seasonNum == 0) continue
                    val sDetail = JSONObject(tmdbGet("/tv/${ref.id}/season/$seasonNum"))
                    val epArr = sDetail.optJSONArray("episodes") ?: continue
                    for (e in 0 until epArr.length()) {
                        val epObj = epArr.getJSONObject(e)
                        val epNum = epObj.optInt("episode_number", 0)
                        val epName = epObj.optString("name")
                        val epData = "vidlink:tv:${ref.id}:$seasonNum:$epNum"
                        episodes.add(newEpisode(epData) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = img(epObj.optString("still_path"), "w300")
                            this.description = epObj.optString("overview")
                        })
                    }
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = plot
                this.year = year
                this.tags = genres
                // ⭐ Đã xóa hoàn toàn phần Score
            }
        }
    }

    // =====================================================================================
    // 4. CẤU TRÚC KẾT QUẢ VÀ HÀM TẠO SURGE PROXY URL
    // =====================================================================================
    data class StreamResult(
        val streamUrl: String,
        val isDash: Boolean = true,
        val subtitles: List<SubtitleFile> = emptyList()
    )

    private fun buildSurgeUrl(playlistUrl: String, cookieHeader: String?): String {
        return try {
            val uri = URI(playlistUrl)
            val host = "${uri.scheme}://${uri.host}"
            val pathname = uri.rawPath
            if (cookieHeader.isNullOrEmpty()) {
                playlistUrl
            } else {
                val sc = Base64.getUrlEncoder().withoutPadding().encodeToString(cookieHeader.toByteArray(Charsets.UTF_8))
                val encodedHost = URLEncoder.encode(host, "UTF-8")
                "https://surge.flooderrr.store/sacdn$pathname?host=$encodedHost&sc=$sc"
            }
        } catch (_: Exception) {
            playlistUrl
        }
    }

    private fun parseStreamJson(jsonStr: String): StreamResult? {
        return try {
            val root = JSONObject(jsonStr)
            val stream = root.optJSONObject("stream") ?: return null
            val playlist = stream.optString("playlist")
            if (playlist.isEmpty()) return null

            val pHeaders = stream.optJSONObject("playlistHeaders")
            val cookie = pHeaders?.optString("Cookie")
            val isDash = stream.optString("type", "dash") == "dash" || playlist.contains(".mpd")
            val finalUrl = if (isDash && !playlist.startsWith("https://surge.flooderrr.store/")) {
                buildSurgeUrl(playlist, cookie)
            } else {
                playlist
            }

            val subs = mutableListOf<SubtitleFile>()
            val captions = stream.optJSONArray("captions")
            if (captions != null) {
                for (i in 0 until captions.length()) {
                    val c = captions.optJSONObject(i) ?: continue
                    val u = c.optString("url")
                    if (u.isNotEmpty()) {
                        val lang = c.optString("language", "Subtitle")
                        subs.add(SubtitleFile(lang, u))
                    }
                }
            }
            StreamResult(finalUrl, isDash, subs)
        } catch (_: Exception) {
            null
        }
    }

    // =====================================================================================
    // 5. SNIFFING NÂNG CAO TRONG WEBVIEW (HOOK FETCH + TOUCH + PLAYER TRIGGER)
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, ua: String, seen: MutableList<String>): StreamResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                // ⭐ SỬA LỖI CONTEXT: Chỉ dùng CommonActivity
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("VidLink", "Không có Context để khởi tạo WebView")
                    cont.resume(null)
                    return@post
                }

                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    var finished = false

                    fun finish(res: StreamResult?) {
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

                    handler.postDelayed({
                        if (!finished) {
                            ClipboardLogger.logAndCopy("VidLink Timeout", "Seen requests:\n" + seen.takeLast(12).joinToString("\n"))
                            finish(null)
                        }
                    }, 25000)

                    val streamRegex = Regex("""\.mpd|\.m3u8""", RegexOption.IGNORE_CASE)
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    val hookScript = """
                        (function() {
                            if (window.__vhooked) return;
                            window.__vhooked = true;
                            var origFetch = window.fetch;
                            window.fetch = async function() {
                                var args = arguments;
                                var r = await origFetch.apply(this, args);
                                try {
                                    var u = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                                    if (u && u.indexOf('/api/b/') !== -1) {
                                        var c = r.clone();
                                        c.text().then(function(t) {
                                            console.log('VIDLINK_API_JSON:' + t);
                                        }).catch(function(){});
                                    }
                                } catch(e) {}
                                return r;
                            };
                        })();
                    """.trimIndent()

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: return super.onConsoleMessage(consoleMessage)
                            if (msg.startsWith("VIDLINK_API_JSON:")) {
                                val json = msg.removePrefix("VIDLINK_API_JSON:")
                                val parsed = parseStreamJson(json)
                                if (parsed != null) {
                                    handler.post { finish(parsed) }
                                    return true
                                }
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    val clickJs = """
                        (function() {
                            function fire(sel) {
                                var els = document.querySelectorAll(sel);
                                for (var i = 0; i < els.length; i++) {
                                    try { els[i].click(); } catch(e) {}
                                }
                            }
                            fire('#bigPlay');
                            fire('.jw-display-icon-container, .jw-icon-display, .jw-bigplay');
                            fire('media-player, media-play-button, [data-media-button], .vds-play-button, button[aria-label="Play"]');
                            var v = document.querySelector('video');
                            if (v) {
                                v.muted = true;
                                var p = v.play();
                                if (p && p.catch) p.catch(function(){});
                            }
                        })();
                    """.trimIndent()

                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(hookScript, null)
                            wv.evaluateJavascript(clickJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle(); u.recycle()
                        } catch (_: Exception) {}

                        if (taps < 20 && !finished) {
                            handler.postDelayed({ tap() }, 1200)
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!junkRegex.containsMatchIn(u) && seen.size < 60) {
                                seen.add(u.take(200))
                            }

                            if (streamRegex.containsMatchIn(u)) {
                                val isDash = u.contains(".mpd")
                                handler.post {
                                    finish(StreamResult(u, isDash))
                                }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val ok = u.startsWith("https://vidlink.pro") || u.contains("surge.flooderrr.store")
                            return !ok
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            wv.evaluateJavascript(hookScript, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            wv.evaluateJavascript(hookScript, null)
                            handler.postDelayed({ tap() }, 600)
                        }
                    }

                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h))

                    val headers = mapOf(
                        "Referer" to "https://vidlink.pro/",
                        "Origin" to "https://vidlink.pro"
                    )
                    wv.loadUrl(pageUrl, headers)
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("VidLink Init Error", e.message ?: "Unknown")
                    cont.resume(null)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = parseRef(data) ?: return false
        val pageUrl = if (ref.type == "movie") {
            "https://vidlink.pro/movie/${ref.id}?autoplay=true&player=jw"
        } else {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "https://vidlink.pro/tv/${ref.id}/$s/$e?autoplay=true&player=jw"
        }

        val ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val seen = mutableListOf<String>()

        ClipboardLogger.logAndCopy("VidLink Start", "Target: $pageUrl")
        val result = sniffStream(pageUrl, ua, seen)

        if (result != null && result.streamUrl.isNotEmpty()) {
            result.subtitles.forEach { sub ->
                subtitleCallback.invoke(sub)
            }

            callback.invoke(
                newExtractorLink(
                    name = if (result.isDash) "VidLink DASH (Surge)" else "VidLink HLS",
                    source = "VidLink",
                    url = result.streamUrl,
                    type = if (result.isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://vidlink.pro/"
                    this.headers = mapOf(
                        "Origin" to "https://vidlink.pro",
                        "Referer" to "https://vidlink.pro/"
                    )
                }
            )
            ClipboardLogger.logAndCopy("VidLink Success", "Stream: ${result.streamUrl}\nSubs: ${result.subtitles.size}")
            return true
        }

        ClipboardLogger.logAndCopy("VidLink Error", "Không bắt được link stream từ VidLink\nSeen:\n" + seen.takeLast(10).joinToString("\n"))
        return false
    }
}

// Hỗ trợ tương thích ngược cho VidlinkPlugin.kt nếu gọi class VidLinkProvider
typealias VidLinkProvider = VidlinkProvider
