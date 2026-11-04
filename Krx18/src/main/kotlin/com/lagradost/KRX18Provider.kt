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
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class KRX18Provider : MainAPI() {
    override var mainUrl = "https://krx18.com"
    override var name = "KRX 18"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "movies" to "Recently added",
        "genre/eng-sub" to "English SUB",
        "genre/korea" to "Korea",
        "genre/china" to "China",
        "genre/japan" to "Japan",
        "genre/thailand" to "Thailand",
        "genre/philippines" to "Philippines",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/?page/$page").document
        val home = document.select("#archive-content article, div.items.normal article")
            .mapNotNull {
                try { it.toSearchResult() } catch (_: Exception) { null }
            }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("h3").text().ifBlank {
            this.select("a").attr("title").ifBlank {
                this.select("img").attr("alt")
            }
        }
        val href = fixUrl(this.select("a").attr("href"))
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("#archive-content article, div.items.normal article, div.result-item article, article")
            .mapNotNull {
                try { it.toSearchResult() } catch (_: Exception) { null }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.data h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: "Unknown"
        val tags = document.select("div:nth-child(2) > div.video-details__item_links a, div.sgeneros a").map { it.text() }
        val description = document.selectFirst("div.wp-content p")?.text()?.trim() ?: "Unknown"
        val recommendations = document.select("#single_relacionados article, div.items.normal article, article")
            .mapNotNull {
                try { it.toSearchResult() } catch (_: Exception) { null }
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
        }
    }

    // =====================================================================================
    // WebView Sniffer: Bắt URL stream (.m3u8 hoặc .mp4) ngầm từ WebView
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
        referer: String
    ): String? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val appCtx: Context? = CloudStreamApp.context
                val act = CommonActivity.activity
                val ctx: Context? = act ?: appCtx
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("KRX18", "Không tìm thấy Context để khởi tạo WebView")
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

                    // Tự động kết thúc sau 20 giây nếu không có kết quả
                    handler.postDelayed({
                        if (!finished) finish(null)
                    }, 20000)

                    val streamRegex = Regex("""\.m3u8|\.mp4""", RegexOption.IGNORE_CASE)
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
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
                            fire('.jw-display-icon-container, .jw-icon-display, .jw-bigplay, [aria-label=Play]');
                            fire('#overlay, #playback, #playback svg, .play-button');
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
                            wv.evaluateJavascript(clickJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle(); u.recycle()
                        } catch (_: Exception) {}

                        if (taps < 15 && !finished) {
                            handler.postDelayed({ tap() }, 1200)
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!junkRegex.containsMatchIn(u) && streamRegex.containsMatchIn(u)) {
                                handler.post {
                                    finish(u)
                                }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val ok = u.contains("playkrx18") || u.contains("mov18plus") || u.contains("str0611")
                            return !ok
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 600)
                        }
                    }

                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    act?.addContentView(wv, ViewGroup.LayoutParams(w, h))

                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("KRX18 Sniff Error", e.message ?: "Unknown")
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
        val document = app.get(data).document
        val options = document.select("ul#playeroptionsul li").mapNotNull { li ->
            val postId = li.attr("data-post").ifEmpty { null }
            val nume = li.attr("data-nume").ifEmpty { null }
            val type = li.attr("data-type").ifEmpty { null }
            if (postId != null && nume != null && type != null && !nume.contains("trailer", ignoreCase = true)) {
                Triple(postId, nume, type)
            } else null
        }

        var anyFound = false

        options.amap { (postId, nume, type) ->
            try {
                val postRes = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                ).text

                val rawUrl = try {
                    JSONObject(postRes).optString("embed_url")
                } catch (_: Exception) {
                    Regex("""embed_url["']?\s*:\s*["']([^"']+)["']""").find(postRes)?.groupValues?.get(1) ?: ""
                }

                val embedUrl = if (rawUrl.contains("<iframe")) {
                    Regex("""src=["']([^"']+)["']""").find(rawUrl)?.groupValues?.get(1) ?: rawUrl
                } else rawUrl

                if (embedUrl.isNotBlank()) {
                    val fixedUrl = fixUrl(embedUrl)
                    val serverTitle = "KRX18 (Server $nume)"

                    // 1. Thử load extractor có sẵn nếu là host thông dụng
                    val loaded = loadExtractor(fixedUrl, subtitleCallback, callback)

                    // 2. Nếu là player riêng của KRX18 (playkrx18 / mov18plus) hoặc extractor chưa bắt được
                    if (!loaded || fixedUrl.contains("playkrx18") || fixedUrl.contains("mov18plus")) {
                        val streamUrl = sniffStream(fixedUrl, data)
                        if (!streamUrl.isNullOrBlank()) {
                            if (streamUrl.contains(".m3u8")) {
                                M3u8Helper.generateM3u8(
                                    source = serverTitle,
                                    streamUrl = streamUrl,
                                    referer = fixedUrl,
                                    headers = mapOf("Referer" to fixedUrl, "Origin" to fixedUrl.substringBeforeLast("/"))
                                ).forEach(callback)
                            } else {
                                callback.invoke(
                                    newExtractorLink(
                                        name = "$serverTitle MP4",
                                        source = serverTitle,
                                        url = streamUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = fixedUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                            anyFound = true
                        }
                    } else {
                        anyFound = true
                    }
                }
            } catch (e: Exception) {
                ClipboardLogger.logAndCopy("KRX18 Ajax Error", e.message ?: "Unknown")
            }
        }

        return anyFound
    }
}

typealias KRX18 = KRX18Provider
