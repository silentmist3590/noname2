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
        @JsonProperty("type") val type: String, // "movie" hoặc "tv"
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    // ==========================================
    // KHỐI CODE BẮT LINK VÀ GIẢ LẬP TOUCH BẠN YÊU CẦU
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

    // ==========================================
    // TRÍCH XUẤT STREAM & PHỤ ĐỀ (loadLinks)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seen = mutableListOf<String>()

        // 1. Phân tích endpoint từ data
        val targetUrls = mutableListOf<String>()
        val referer = "https://www.nontongo.win/"

        if (data.startsWith("http://") || data.startsWith("https://")) {
            targetUrls.add(data)
        } else {
            try {
                val ep = mapper.readValue<EpisodeData>(data)
                if (ep.type == "movie") {
                    targetUrls.add("https://www.nontongo.win/embed/movie/${ep.imdbId}")
                } else {
                    targetUrls.add("https://www.nontongo.win/embed/tv/${ep.imdbId}/${ep.season ?: 1}/${ep.episode ?: 1}")
                    targetUrls.add("https://www.nontongo.win/embed/tv/?id=${ep.imdbId}&s=${ep.season ?: 1}&e=${ep.episode ?: 1}")
                }
            } catch (_: Exception) {
                // Fallback nếu data là imdbId thuần
                targetUrls.add("https://www.nontongo.win/embed/movie/$data")
            }
        }

        var foundStream = false

        for (pageUrl in targetUrls) {
            ClipboardLogger.logAndCopy("NontonGo Sniff Start", "Đang phân tích link: $pageUrl")

            // Trích xuất phụ đề tĩnh từ HTML trước nếu có
            try {
                val html = app.get(pageUrl, referer = referer, headers = mapOf("User-Agent" to userAgent)).text
                extractSubtitlesFromHtml(html, subtitleCallback)
            } catch (e: Exception) {
                ClipboardLogger.log("NontonGo Sub Parse", "Lỗi tải HTML tĩnh: ${e.message}")
            }

            // Gọi phương thức sniffStream với WebView ẩn và chạm tự động
            val streamUrl = sniffStream(pageUrl, referer, userAgent, seen)

            if (!streamUrl.isNullOrEmpty()) {
                foundStream = true
                ClipboardLogger.logAndCopy(
                    "NontonGo Stream Found",
                    "Thành công bắt được stream:\n$streamUrl\n\nCác link chặn/ghi nhận (${seen.size}):\n" + seen.takeLast(10).joinToString("\n")
                )

                if (streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("master.txt", ignoreCase = true)) {
                    // Trích xuất toàn bộ các độ phân giải từ master m3u8
                    val links = try {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = streamUrl,
                            referer = pageUrl,
                            headers = mapOf(
                                "Referer" to pageUrl,
                                "User-Agent" to userAgent,
                                "Origin" to "https://www.nontongo.win"
                            )
                        )
                    } catch (e: Exception) {
                        ClipboardLogger.log("M3U8 Generator", "Lỗi phân tích đa độ phân giải: ${e.message}")
                        emptyList()
                    }

                    if (links.isNotEmpty()) {
                        ClipboardLogger.logAndCopy("NontonGo Qualities", "Đã tìm thấy ${links.size} chất lượng phát (1080p, 720p, 480p...)")
                        links.forEach(callback)
                    } else {
                        // Fallback luồng đơn nếu danh sách trả về rỗng
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name Auto HLS",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = pageUrl
                                this.headers = mapOf("Referer" to pageUrl, "User-Agent" to userAgent)
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                } else if (streamUrl.contains(".mpd", ignoreCase = true)) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name DASH Stream",
                            url = streamUrl,
                            type = ExtractorLinkType.DASH
                        ) {
                            this.referer = pageUrl
                            this.headers = mapOf("Referer" to pageUrl, "User-Agent" to userAgent)
                            this.quality = Qualities.P1080.value
                        }
                    )
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name Direct MP4",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = pageUrl
                            this.headers = mapOf("Referer" to pageUrl, "User-Agent" to userAgent)
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
                break // Đã bắt được stream thành công, kết thúc vòng lặp
            }
        }

        if (!foundStream) {
            ClipboardLogger.logAndCopy(
                "NontonGo Sniff Failed",
                "Không bắt được stream sau khi thử hết endpoint.\nSeen URLs (${seen.size}):\n" + seen.takeLast(15).joinToString("\n")
            )
        }

        return foundStream
    }

    private fun extractSubtitlesFromHtml(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val subRegex = Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+\.(?:vtt|srt)[^"]*)"[^{}]*?"label"\s*:\s*"([^"]+)"[^{}]*?\}""", RegexOption.IGNORE_CASE)
        val trackRegex = Regex("""<track[^>]+src=["']([^"']+\.(?:vtt|srt)[^"']*)["'][^>]+label=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        val found = mutableSetOf<String>()

        subRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2]
            if (found.add(url)) {
                subtitleCallback.invoke(SubtitleFile(lang = label, url = url))
                ClipboardLogger.log("NontonGo Sub", "$label -> $url")
            }
        }

        trackRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2]
            if (found.add(url)) {
                subtitleCallback.invoke(SubtitleFile(lang = label, url = url))
                ClipboardLogger.log("NontonGo Sub Track", "$label -> $url")
            }
        }
    }

    // ==========================================
    // TÌM KIẾM VÀ TRANG CHỦ TMDB -> NontonGo
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
            // Cho phép tìm kiếm trực tiếp bằng mã IMDB ID (ví dụ: tt1375666)
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
        // Nếu người dùng nhập trực tiếp URL nontongo.win
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
                        val dataObj = EpisodeData(imdbId = imdbId, type = "tv", season = s, episode = epNum, title = epName)
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
            val dataObj = EpisodeData(imdbId = imdbId, type = "movie")
            return newMovieLoadResponse(title, url, TvType.Movie, mapper.writeValueAsString(dataObj)) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }
}
