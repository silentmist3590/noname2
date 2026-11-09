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
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NontonGoProvider : MainAPI() {
    override var mainUrl = "https://www.nontongo.win"
    override var name = "NontonGo"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val mapper = jacksonObjectMapper()

    // TMDB Hosts chống DNS ::1
    private val tmdbBase = "https://api.tmdb.org/3"
    private val tmdbApiKey = "8476a7ab80ad76f0936744df0430e67c"

    data class EpisodeData(
        @JsonProperty("imdbId") val imdbId: String,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("type") val type: String, // "movie" hoặc "tv"
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    // ==========================================
    // KHỐI BẮT LINK BẰNG WEBVIEW (ĐÃ ĐƯỢC VÁ LỖI CHẶN QUẢNG CÁO)
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

                    // Mở rộng regex để bắt được svsoapp và /bl/ ngay cả khi không có đuôi .m3u8
                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt|/bl/|svsoapp\.nontongo\.win""", RegexOption.IGNORE_CASE)
                    
                    // Regex chặn triệt để domain quảng cáo làm lag/lừa click
                    val adDomainsRegex = Regex("""ay267\.com|pyppo\.com|dtscout\.com|llvpn\.com|accountut\.com|histats\.com|whomeetstare\.com|popads\.net""", RegexOption.IGNORE_CASE)
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

                    // Vô hiệu hóa mở cửa sổ quảng cáo
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                    }

                    // Script tiêu diệt lớp phủ clickjacking và click đúng nút Play của NontonGo
                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){" +
                        "try{document.querySelectorAll('iframe:not(#playerFrame):not(#player):not(#streamFrame),div[style*=\"z-index: 999999\"],div[style*=\"z-index: 2147483647\"]').forEach(function(e){e.remove();});}catch(e){}" +
                        "function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('playBtn'));" +
                        "fire(document.querySelector('.play-btn,.video-play-button,#playBtn'));" +
                        "fire(document.getElementById('srvBtn'));" +
                        "fire(document.querySelector('.srv-menu-item'));" +
                        "fire(document.querySelector('.dropdown-item'));" +
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

                            // Chặn đứng hoàn toàn request quảng cáo ngay tại mạng
                            if (adDomainsRegex.containsMatchIn(u)) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!junkRegex.containsMatchIn(u) && seen.size < 80) seen.add(u.take(220))
                            
                            // Bắt link stream
                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/") && !u.contains("view1.php")) {
                                handler.post { finish(u) }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (adDomainsRegex.containsMatchIn(u) || u.startsWith("intent://")) {
                                seen.add("BLOCKED AD: " + u.take(160))
                                return true
                            }
                            val ok = u.contains("nontongo.win") || u.contains("svsoapp")
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
                    handler.postDelayed({ finish(null) }, 35_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // ==========================================
    // TRÍCH XUẤT STREAM & PHỤ ĐỀ (loadLinks)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var imdbId = ""
        var tmdbId = 0
        var isTv = false
        var season = 1
        var episode = 1

        if (data.startsWith("http://") || data.startsWith("https://")) {
            if (data.contains("/tv/")) {
                isTv = true
                val parts = data.substringAfter("/tv/").split("/")
                imdbId = parts.getOrNull(0) ?: ""
                season = parts.getOrNull(1)?.toIntOrNull() ?: 1
                episode = parts.getOrNull(2)?.toIntOrNull() ?: 1
            } else {
                imdbId = data.substringAfter("/movie/").substringBefore("?").trim('/')
            }
        } else {
            try {
                val ep = mapper.readValue<EpisodeData>(data)
                imdbId = ep.imdbId
                tmdbId = ep.tmdbId ?: 0
                isTv = ep.type == "tv"
                season = ep.season ?: 1
                episode = ep.episode ?: 1
            } catch (_: Exception) {
                imdbId = data.trim()
            }
        }

        ClipboardLogger.logAndCopy("NontonGo Load", "Bắt đầu lấy stream: ID=$imdbId, TMDB=$tmdbId, TV=$isTv (S$season E$episode)")

        // ----------------------------------------------------
        // BƯỚC 1: TRÍCH XUẤT TRỰC TIẾP TỪ PLAYER NỘI BỘ (0.3 GIÂY, SẠCH QUẢNG CÁO 100%)
        // ----------------------------------------------------
        val playerDirectUrls = mutableListOf<String>()
        if (isTv) {
            val tvId = if (tmdbId > 0) tmdbId.toString() else imdbId
            playerDirectUrls.add("https://nontongo.win/01aus/player.php?id=$tvId&type=tv&season=$season&episode=$episode&autoplay=true")
            playerDirectUrls.add("https://nontongo.win/01aus/player.php?id=$imdbId&type=tv&season=$season&episode=$episode&autoplay=true")
        } else {
            playerDirectUrls.add("https://nontongo.win/01aus/player.php?id=$imdbId&type=movie&autoplay=true")
            if (tmdbId > 0) {
                playerDirectUrls.add("https://nontongo.win/01aus/player.php?id=$tmdbId&type=movie&autoplay=true")
            }
        }

        var foundDirectStream = false

        for (directUrl in playerDirectUrls) {
            try {
                val playerHtml = app.get(
                    directUrl,
                    referer = "https://nontongo.win/",
                    headers = mapOf("User-Agent" to userAgent)
                ).text

                // 1. Quét phụ đề (.vtt, .srt)
                extractSubtitlesFromHtml(playerHtml, subtitleCallback)

                // 2. Quét tất cả các server stream data-url="https://svsoapp.nontongo.win/bl/...#file.m3u8"
                val streamUrlRegex = Regex("""data-url=["']([^"']+)["']""")
                val matches = streamUrlRegex.findAll(playerHtml).map { it.groupValues[1] }.toList()

                for (matched in matches) {
                    val cleanUrl = matched.substringBefore("#").trim()
                    if (cleanUrl.startsWith("http") && (cleanUrl.contains("svsoapp") || cleanUrl.contains("/bl/") || cleanUrl.contains(".m3u8"))) {
                        foundDirectStream = true
                        emitStream(cleanUrl, directUrl, callback)
                    }
                }

                if (foundDirectStream) {
                    ClipboardLogger.logAndCopy("NontonGo Success", "Đã lấy thành công stream trực tiếp từ $directUrl")
                    return true
                }
            } catch (e: Exception) {
                ClipboardLogger.log("NontonGo Direct Error", "${e.message}")
            }
        }

        // ----------------------------------------------------
        // BƯỚC 2: NẾU BƯỚC 1 CHƯA RA, CHẠY SNIFFSTREAM WEBVIEW (ĐÃ CHẶN QUẢNG CÁO)
        // ----------------------------------------------------
        val seen = mutableListOf<String>()
        val fallbackPageUrl = if (isTv) {
            "https://www.nontongo.win/embed/tv/$imdbId/$season/$episode"
        } else {
            "https://www.nontongo.win/embed/movie/$imdbId"
        }

        val sniffedUrl = sniffStream(fallbackPageUrl, "https://www.nontongo.win/", userAgent, seen)
        if (!sniffedUrl.isNullOrEmpty()) {
            val cleanStream = sniffedUrl.substringBefore("#")
            emitStream(cleanStream, fallbackPageUrl, callback)
            ClipboardLogger.logAndCopy("NontonGo Sniff Success", "WebView đã bắt được stream:\n$cleanStream")
            return true
        }

        ClipboardLogger.logAndCopy(
            "NontonGo Failed",
            "Không lấy được stream.\nSeen URLs (${seen.size}):\n" + seen.takeLast(10).joinToString("\n")
        )
        return false
    }

    private suspend fun emitStream(streamUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Referer" to "https://nontongo.win/",
            "User-Agent" to userAgent,
            "Origin" to "https://nontongo.win"
        )

        // Phân tích HLS đa chất lượng
        val m3u8Links = try {
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = streamUrl,
                referer = "https://nontongo.win/",
                headers = headers
            )
        } catch (_: Exception) {
            emptyList()
        }

        if (m3u8Links.isNotEmpty()) {
            ClipboardLogger.logAndCopy("NontonGo Multi-Qualities", "Bắt được ${m3u8Links.size} chất lượng phát (1080p, 720p, 480p...)")
            m3u8Links.forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Auto HLS",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://nontongo.win/"
                    this.headers = headers
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    private fun extractSubtitlesFromHtml(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val found = mutableSetOf<String>()

        // Bắt data-src="..." label="..."
        val subRegex = Regex("""data-src=["']([^"']+\.(?:vtt|srt)[^"']*)["']\s+label=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        subRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2]
            if (found.add(url)) {
                subtitleCallback.invoke(SubtitleFile(lang = label, url = url))
                ClipboardLogger.log("NontonGo Sub", "$label -> $url")
            }
        }

        // Bắt { "file": "...", "label": "..." }
        val jsonSubRegex = Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+\.(?:vtt|srt)[^"]*)"[^{}]*?"label"\s*:\s*"([^"]+)"[^{}]*?\}""", RegexOption.IGNORE_CASE)
        jsonSubRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2]
            if (found.add(url)) {
                subtitleCallback.invoke(SubtitleFile(lang = label, url = url))
                ClipboardLogger.log("NontonGo Sub", "$label -> $url")
            }
        }
    }

    // ==========================================
    // TRANG CHỦ & TÌM KIẾM TMDB
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$tmdbBase/trending/all/day?api_key=$tmdbApiKey&page=$page"
        val response = app.get(url).text
        val results = mapper.readTree(response).get("results")

        val list = mutableListOf<SearchResponse>()
        results?.forEach { item ->
            val mediaType = item.get("media_type")?.asText() ?: "movie"
            val title = item.get("title")?.asText() ?: item.get("name")?.asText() ?: return@forEach
            val id = item.get("id")?.asInt() ?: return@forEach
            val poster = item.get("poster_path")?.asText()?.let { "https://image.tmdb.org/t/p/w500$it" }

            if (mediaType == "tv") {
                list.add(newTvSeriesSearchResponse(title, "$tmdbBase/tv/$id?api_key=$tmdbApiKey", TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } else {
                list.add(newMovieSearchResponse(title, "$tmdbBase/movie/$id?api_key=$tmdbApiKey", TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }

        return newHomePageResponse(
            list = HomePageList(name = "NontonGo Trending", list = list),
            hasNext = list.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.startsWith("tt")) {
            return listOf(
                newMovieSearchResponse("IMDb $query", "https://www.nontongo.win/embed/movie/$query", TvType.Movie)
            )
        }

        val url = "$tmdbBase/search/multi?api_key=$tmdbApiKey&query=$query"
        val response = app.get(url).text
        val results = mapper.readTree(response).get("results")
        val list = mutableListOf<SearchResponse>()

        results?.forEach { item ->
            val mediaType = item.get("media_type")?.asText() ?: return@forEach
            val title = item.get("title")?.asText() ?: item.get("name")?.asText() ?: return@forEach
            val id = item.get("id")?.asInt() ?: return@forEach
            val poster = item.get("poster_path")?.asText()?.let { "https://image.tmdb.org/t/p/w500$it" }

            if (mediaType == "tv") {
                list.add(newTvSeriesSearchResponse(title, "$tmdbBase/tv/$id?api_key=$tmdbApiKey", TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } else if (mediaType == "movie") {
                list.add(newMovieSearchResponse(title, "$tmdbBase/movie/$id?api_key=$tmdbApiKey", TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("nontongo.win/embed/movie/")) {
            val imdbId = url.substringAfter("nontongo.win/embed/movie/").substringBefore("?").trim('/')
            return newMovieLoadResponse(imdbId, url, TvType.Movie, url)
        }

        val isTv = url.contains("/tv/")
        val json = app.get(url).text
        val tree = mapper.readTree(json)
        val tmdbId = tree.get("id")?.asInt() ?: 0
        val title = tree.get("title")?.asText() ?: tree.get("name")?.asText() ?: "Unknown"
        val poster = tree.get("poster_path")?.asText()?.let { "https://image.tmdb.org/t/p/w500$it" }
        val plot = tree.get("overview")?.asText()

        // Lấy IMDB ID thông qua external_ids
        val extUrl = "$tmdbBase/${if (isTv) "tv" else "movie"}/$tmdbId/external_ids?api_key=$tmdbApiKey"
        val extJson = app.get(extUrl).text
        val imdbId = mapper.readTree(extJson).get("imdb_id")?.asText() ?: "tt$tmdbId"

        if (isTv) {
            val seasonsCount = tree.get("number_of_seasons")?.asInt() ?: 1
            val episodes = mutableListOf<Episode>()

            for (s in 1..seasonsCount) {
                val sUrl = "$tmdbBase/tv/$tmdbId/season/$s?api_key=$tmdbApiKey"
                try {
                    val sJson = app.get(sUrl).text
                    val epList = mapper.readTree(sJson).get("episodes")
                    epList?.forEach { epNode ->
                        val epNum = epNode.get("episode_number")?.asInt() ?: 1
                        val epName = epNode.get("name")?.asText() ?: "Tập $epNum"
                        val dataObj = EpisodeData(imdbId = imdbId, tmdbId = tmdbId, type = "tv", season = s, episode = epNum, title = epName)
                        episodes.add(
                            newEpisode(mapper.writeValueAsString(dataObj)) {
                                this.name = epName
                                this.season = s
                                this.episode = epNum
                            }
                        )
                    }
                } catch (_: Exception) {
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val dataObj = EpisodeData(imdbId = imdbId, tmdbId = tmdbId, type = "movie")
            return newMovieLoadResponse(title, url, TvType.Movie, mapper.writeValueAsString(dataObj)) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }
}
