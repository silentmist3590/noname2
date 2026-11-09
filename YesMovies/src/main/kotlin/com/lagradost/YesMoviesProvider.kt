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
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class YesMoviesProvider : MainAPI() {
    override var mainUrl = "https://yesmovies.press"
    override var name = "YesMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val mapper = jacksonObjectMapper()

    // TMDB Hosts dự phòng chống chặn DNS ::1
    private val tmdbBase = "https://api.tmdb.org/3"
    private val tmdbApiKey = "8476a7ab80ad76f0936744df0430e67c"

    override val mainPage = mainPageOf(
        "featured-movies" to "Featured Movies",
        "popular-movies" to "Popular Movies",
        "top-rated-movies" to "Top Rated Movies",
        "upcoming-movies" to "Upcoming Movies",
        "popular-tv-shows" to "Popular TV Shows",
        "airing-today" to "Airing Today",
        "top-rated" to "Top Rated TV Series"
    )

    data class PlayData(
        @JsonProperty("playUrl") val playUrl: String,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("type") val type: String, // "movie" hoặc "tv"
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    // ==========================================
    // KHỐI BẮT LINK SNIFFSTREAM VỚI WEBVIEW ẨN
    // ==========================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, referer: String, ua: String, seen: MutableList<String>): String? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val appCtx: Context? = ClipboardLogger.appContext()
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

                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt|\.mp4\?|svsoapp\.nontongo\.win|/bl/""", RegexOption.IGNORE_CASE)
                    val adDomainsRegex = Regex("""talcoseliukiu\.com|createlouisville\.org|ay267\.com|pyppo\.com|dtscout\.com|histats\.com|llvpn\.com|popads\.net""", RegexOption.IGNORE_CASE)
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

                    // Chặn popup quảng cáo mở cửa sổ mới
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                    }

                    // Vô hiệu hóa popup và tự động click vào video/player
                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){" +
                        "try{document.querySelectorAll('iframe:not(#my-video):not(#playerFrame),div[style*=\"z-index: 999999\"]').forEach(function(e){e.remove();});}catch(e){}" +
                        "function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.querySelector('.vjs-big-play-button'));" +
                        "fire(document.querySelector('.play-btn,#playBtn'));" +
                        "fire(document.getElementById('my-video'));" +
                        "var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
                        "})();"

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
                        if (taps < 20) handler.postDelayed({ tap() }, 1500)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null

                            // Chặn đứng các request quảng cáo
                            if (adDomainsRegex.containsMatchIn(u)) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!junkRegex.containsMatchIn(u) && seen.size < 80) seen.add(u.take(220))

                            // Bắt link stream video
                            if (streamRegex.containsMatchIn(u) && !u.contains("loading.gif")) {
                                handler.post { finish(u) }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (adDomainsRegex.containsMatchIn(u) || u.startsWith("intent://") || u.contains("/register")) {
                                seen.add("BLOCKED AD: " + u.take(160))
                                return true
                            }
                            val ok = u.contains("yesmovies.press") || u.contains("vidplayz") || u.contains("nontongo")
                            if (!ok) seen.add("BLOCKED NAV: " + u.take(160))
                            return !ok
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
                        }
                    }

                    // Gắn vào Activity để WebView nhận dispatch touch
                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try {
                        act?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) {
                    }
                    handler.postDelayed({ finish(null) }, 30_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // ==========================================
    // TRANG CHỦ & PHÂN TRANG (getMainPage)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val items = doc.select("li.TPostMv, div.TPost").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        val title = this.selectFirst("h2.Title, .Title, a.Title")?.text()?.trim() ?: aTag.attr("title").ifEmpty { null } ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        })
        val isTv = href.contains("/serie/")
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    // ==========================================
    // TÌM KIẾM (search)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        return doc.select("li.TPostMv, div.TPost").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    // ==========================================
    // TẢI CHI TIẾT PHIM & TẬP PHIM (load)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = doc.selectFirst("h1.Title, h1 a, meta[property=og:title]")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image], div.Image img")?.let {
            it.attr("content").ifEmpty { it.attr("data-src").ifEmpty { it.attr("src") } }
        })
        val plot = doc.selectFirst("div.Description p, meta[property=og:description]")?.text()?.trim()

        val isTv = url.contains("/serie/")

        // Trích xuất TMDB ID từ URL:
        // Movie: /movie/the-dark-knight-2008-W155Zx/ -> 155
        // Series: /serie/1877/phineas-and-ferb/ -> 1877
        val tmdbId = if (isTv) {
            Regex("""/serie/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        } else {
            Regex("""-W(\d+)Zx""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        }

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val seriesId = tmdbId ?: 0

            // Lấy danh sách mùa từ trang TMDB hoặc các options trên YesMovies
            if (seriesId > 0) {
                try {
                    val tmdbJson = app.get("$tmdbBase/tv/$seriesId?api_key=$tmdbApiKey").text
                    val tree = mapper.readTree(tmdbJson)
                    val seasonCount = tree.get("number_of_seasons")?.asInt() ?: 1

                    for (s in 1..seasonCount) {
                        val sUrl = "$tmdbBase/tv/$seriesId/season/$s?api_key=$tmdbApiKey"
                        val sJson = app.get(sUrl).text
                        val epList = mapper.readTree(sJson).get("episodes")

                        epList?.forEach { epNode ->
                            val epNum = epNode.get("episode_number")?.asInt() ?: 1
                            val epName = epNode.get("name")?.asText() ?: "Tập $epNum"
                            val playUrl = "$url/play/#video-player"
                            val pData = PlayData(
                                playUrl = playUrl,
                                tmdbId = seriesId,
                                type = "tv",
                                season = s,
                                episode = epNum,
                                title = epName
                            )

                            // Sử dụng newEpisode chuẩn của CloudStream v4.5.2
                            episodes.add(
                                newEpisode(mapper.writeValueAsString(pData)) {
                                    this.name = epName
                                    this.season = s
                                    this.episode = epNum
                                }
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // Fallback nếu không lấy được từ TMDB
            if (episodes.isEmpty()) {
                val fallbackData = PlayData(playUrl = "$url/play/#video-player", tmdbId = tmdbId, type = "tv", season = 1, episode = 1)
                episodes.add(
                    newEpisode(mapper.writeValueAsString(fallbackData)) {
                        this.name = "Tập 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val playUrl = if (url.endsWith("/play/")) url else "${url.trimEnd('/')}/play/#video-player"
            val pData = PlayData(playUrl = playUrl, tmdbId = tmdbId, type = "movie")

            return newMovieLoadResponse(title, url, TvType.Movie, mapper.writeValueAsString(pData)) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ==========================================
    // TRÍCH XUẤT NGUỒN PHÁT (loadLinks)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var playUrl = ""
        var tmdbId: Int? = null
        var isTv = false
        var season = 1
        var episode = 1

        if (data.startsWith("http://") || data.startsWith("https://")) {
            playUrl = data
        } else {
            try {
                val p = mapper.readValue<PlayData>(data)
                playUrl = p.playUrl
                tmdbId = p.tmdbId
                isTv = p.type == "tv"
                season = p.season ?: 1
                episode = p.episode ?: 1
            } catch (_: Exception) {
                playUrl = data
            }
        }

        ClipboardLogger.logAndCopy("YesMovies Load", "Bắt đầu trích xuất: $playUrl (TMDB=$tmdbId, TV=$isTv, S$season E$episode)")

        var foundStream = false

        // ----------------------------------------------------
        // BƯỚC 1: TRÍCH XUẤT TĨNH TỪ YESMOVIES PLAY PAGE (<source src="...">)
        // ----------------------------------------------------
        try {
            val playHtml = app.get(playUrl, headers = mapOf("User-Agent" to userAgent)).text
            val sourceRegex = Regex("""<source[^>]+src=["']([^"']+)["'][^>]*label=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val matches = sourceRegex.findAll(playHtml).toList()

            for (m in matches) {
                val src = m.groupValues[1]
                val label = m.groupValues[2]
                if (src.startsWith("http") && !src.contains("raw.githubusercontent.com")) {
                    foundStream = true
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name $label",
                            url = src,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = playUrl
                            this.quality = if (label.contains("1080")) Qualities.P1080.value else if (label.contains("720")) Qualities.P720.value else Qualities.P480.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            ClipboardLogger.log("YesMovies Static Error", "${e.message}")
        }

        // ----------------------------------------------------
        // BƯỚC 2: GỌI NGUỒN SERVER HD (Sử dụng TMDB ID để lấy link HLS 1080p + 30 phụ đề)
        // ----------------------------------------------------
        if (tmdbId != null && tmdbId > 0) {
            val directUrls = mutableListOf<String>()
            if (isTv) {
                directUrls.add("https://nontongo.win/01aus/player.php?id=$tmdbId&type=tv&season=$season&episode=$episode&autoplay=true")
            } else {
                directUrls.add("https://nontongo.win/01aus/player.php?id=$tmdbId&type=movie&autoplay=true")
            }

            for (directUrl in directUrls) {
                try {
                    val pResponse = app.get(directUrl, referer = "https://nontongo.win/", headers = mapOf("User-Agent" to userAgent)).text
                    extractSubtitles(pResponse, subtitleCallback)

                    val streamMatches = Regex("""data-url=["']([^"']+)["']""").findAll(pResponse).map { it.groupValues[1] }.toList()
                    for (mUrl in streamMatches) {
                        val cleanUrl = mUrl.substringBefore("#").trim()
                        if (cleanUrl.startsWith("http") && (cleanUrl.contains("svsoapp") || cleanUrl.contains("/bl/") || cleanUrl.contains(".m3u8"))) {
                            foundStream = true
                            emitStream(cleanUrl, directUrl, callback)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // ----------------------------------------------------
        // BƯỚC 3: DÙNG SNIFFSTREAM BẮT QUA WEBVIEW NẾU CHƯA CÓ STREAM
        // ----------------------------------------------------
        if (!foundStream) {
            val seen = mutableListOf<String>()
            val sniffedUrl = sniffStream(playUrl, mainUrl, userAgent, seen)
            if (!sniffedUrl.isNullOrEmpty()) {
                foundStream = true
                val clean = sniffedUrl.substringBefore("#")
                emitStream(clean, playUrl, callback)
                ClipboardLogger.logAndCopy("YesMovies Sniff Success", "Bắt được stream qua WebView:\n$clean")
            } else {
                ClipboardLogger.logAndCopy(
                    "YesMovies Failed",
                    "Không bắt được stream.\nSeen URLs (${seen.size}):\n" + seen.takeLast(10).joinToString("\n")
                )
            }
        }

        return foundStream
    }

    private suspend fun emitStream(streamUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to userAgent
        )

        val m3u8Links = try {
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = streamUrl,
                referer = referer,
                headers = headers
            )
        } catch (_: Exception) {
            emptyList()
        }

        if (m3u8Links.isNotEmpty()) {
            ClipboardLogger.logAndCopy("YesMovies Multi-Qualities", "Bắt được ${m3u8Links.size} chất lượng stream (1080p, 720p, 480p...)")
            m3u8Links.forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name HD",
                    url = streamUrl,
                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    private fun extractSubtitles(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val found = mutableSetOf<String>()

        val subRegex = Regex("""data-src=["']([^"']+\.(?:vtt|srt)[^"']*)["']\s+label=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        subRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2]
            if (found.add(url)) {
                subtitleCallback.invoke(SubtitleFile(lang = label, url = url))
                ClipboardLogger.log("YesMovies Sub", "$label -> $url")
            }
        }

        val jsonRegex = Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+\.(?:vtt|srt)[^"]*)"[^{}]*?"label"\s*:\s*"([^"]+)"[^{}]*?\}""", RegexOption.IGNORE_CASE)
        jsonRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2]
            if (found.add(url)) {
                subtitleCallback.invoke(SubtitleFile(lang = label, url = url))
                ClipboardLogger.log("YesMovies Sub", "$label -> $url")
            }
        }
    }
}
