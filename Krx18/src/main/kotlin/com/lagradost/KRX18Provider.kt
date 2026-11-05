package com.lagradost

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class KRX18Provider : MainAPI() {
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
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url).document
        val home = document.select("#archive-content article, div.items.normal article, div.card.border-0, article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 a, .title a, h3, .title, .entry-title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("h3 a, .title a, a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-lazy-src").ifEmpty { img.attr("src") } }
        })
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("#archive-content article, div.items.normal article, div.result-item article, article.item, div.card.border-0")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.data h1, h1.entry-title, h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.poster img")?.attr("src")
        )
        val tags = document.select("div.sgeneros a, div.video-details__item_links a, div.wp-content a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val description = document.selectFirst("div.wp-content p, div#info p, div.data p")?.text()?.trim() ?: ""
        val recommendations = document.select("#archive-content article, div.items.normal article, div.card.border-0, article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var hasFoundLinks = false

        // 1. Quét các tùy chọn player của Dooplay
        val playerOptions = document.select("li.dooplay_player_option, ul#playeroptionsul li, .player_options li")
            .mapNotNull { li ->
                val post = li.attr("data-post").trim().ifEmpty { null }
                val nume = li.attr("data-nume").trim().ifEmpty { null }
                val type = li.attr("data-type").trim().ifEmpty { "movie" }
                if (post != null && nume != null && !nume.contains("trailer", ignoreCase = true)) {
                    Triple(post, nume, type)
                } else null
            }.distinct()

        playerOptions.amap { (post, nume, type) ->
            try {
                val response = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<ResponseHash>()

                var embedUrl = response?.embed_url?.trim()
                if (!embedUrl.isNullOrEmpty()) {
                    if (embedUrl.startsWith("//")) embedUrl = "https:$embedUrl"
                    if (embedUrl.contains("<iframe", ignoreCase = true)) {
                        embedUrl = Regex("""src=["'](.*?)["']""", RegexOption.IGNORE_CASE)
                            .find(embedUrl)?.groupValues?.get(1) ?: embedUrl
                    }

                    // Thử với extractor mặc định trước nếu có
                    val loaded = loadExtractor(embedUrl, subtitleCallback, callback)
                    if (!loaded) {
                        val serverLabel = if (nume == "1") "Server 1" else "Server 2"
                        val streamUrl = sniffStream(embedUrl, mainUrl)
                        if (!streamUrl.isNullOrEmpty()) {
                            hasFoundLinks = true
                            emitStreamLink(streamUrl, serverLabel, embedUrl, callback)
                        }
                    } else {
                        hasFoundLinks = true
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Dự phòng: Quét các thẻ iframe trực tiếp nếu player_ajax rỗng
        if (!hasFoundLinks) {
            document.select("div.play iframe, div.source-box iframe, div.movieplay iframe").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotEmpty()) {
                    val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                    val loaded = loadExtractor(fixedSrc, subtitleCallback, callback)
                    if (!loaded) {
                        val streamUrl = sniffStream(fixedSrc, mainUrl)
                        if (!streamUrl.isNullOrEmpty()) {
                            hasFoundLinks = true
                            emitStreamLink(streamUrl, "Direct", fixedSrc, callback)
                        }
                    } else {
                        hasFoundLinks = true
                    }
                }
            }
        }

        return hasFoundLinks
    }

    private suspend fun emitStreamLink(
        streamUrl: String,
        serverLabel: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        var finalUrl = streamUrl.trim()
        if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"

        if (finalUrl.contains(".m3u8", ignoreCase = true)) {
            val m3u8List = try {
                M3u8Helper.generateM3u8(
                    source = "$name ($serverLabel)",
                    streamUrl = finalUrl,
                    referer = referer,
                    headers = mapOf("Referer" to referer, "Origin" to referer)
                )
            } catch (_: Exception) {
                emptyList()
            }

            if (m3u8List.isNotEmpty()) {
                m3u8List.forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = "$name ($serverLabel)",
                        name = "$name ($serverLabel) HLS",
                        url = finalUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = referer
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } else {
            callback.invoke(
                newExtractorLink(
                    source = "$name ($serverLabel)",
                    name = "$name ($serverLabel) MP4",
                    url = finalUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String? = null,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    companion object {
        @SuppressLint("SetJavaScriptEnabled")
        fun sniffStream(url: String, referer: String): String? {
            val handler = Handler(Looper.getMainLooper())
            val latch = CountDownLatch(1)
            var capturedUrl: String? = null
            var webView: WebView? = null

            // Kịch bản JS: Chặn redirect Abyss + Hook hàm phát video ngay tức thì
            val injectionScript = """
                (function() {
                    // 1. Chống chuyển hướng chống-iframe của Abyss (mov18plus) về abyss.to
                    try {
                        Object.defineProperty(window, 'top', {
                            get: function() {
                                return {
                                    location: {
                                        href: 'https://krx18.com/',
                                        hostname: 'krx18.com'
                                    }
                                };
                            },
                            configurable: true
                        });
                    } catch(e) {}

                    function notifyUrl(s) {
                        if (!s || typeof s !== 'string') return;
                        if (s.indexOf('cloudstream://') === 0) return;
                        var clean = s.trim();
                        if (clean.indexOf('//') === 0) clean = 'https:' + clean;
                        var noParams = clean.split('?')[0].split('#')[0].toLowerCase();
                        if (noParams.indexOf('.m3u8') !== -1 || noParams.indexOf('.mp4') !== -1 || noParams.indexOf('/sora/') !== -1) {
                            window.location.href = 'cloudstream://stream?url=' + encodeURIComponent(clean);
                        }
                    }

                    // 2. Hook trực tiếp player_9stream của PlayKrx18 Server 1
                    try {
                        var orig9 = window.player_9stream;
                        window.player_9stream = function(playlist, verfile) {
                            notifyUrl(playlist);
                            if (typeof orig9 === 'function') orig9.apply(this, arguments);
                        };
                    } catch(e) {}

                    // 3. Hook HTMLMediaElement.prototype.src cho video tag
                    try {
                        var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                        if (desc && desc.set) {
                            Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                                set: function(val) {
                                    notifyUrl(val);
                                    return desc.set.apply(this, arguments);
                                },
                                get: desc.get,
                                configurable: true
                            });
                        }
                    } catch(e) {}

                    // 4. Hook JWPlayer setup của cả 2 server
                    try {
                        var origJw = window.jwplayer;
                        window.jwplayer = function() {
                            var inst = origJw ? origJw.apply(this, arguments) : {};
                            if (inst && !inst._csHooked) {
                                inst._csHooked = true;
                                var origSetup = inst.setup;
                                if (typeof origSetup === 'function') {
                                    inst.setup = function(cfg) {
                                        if (cfg && cfg.sources && cfg.sources.length) {
                                            for (var i = 0; i < cfg.sources.length; i++) {
                                                if (cfg.sources[i].file) notifyUrl(cfg.sources[i].file);
                                            }
                                        }
                                        return origSetup.apply(this, arguments);
                                    };
                                }
                            }
                            return inst;
                        };
                        if (origJw && origJw.key) window.jwplayer.key = origJw.key;
                    } catch(e) {}

                    // 5. Polling tự kích hoạt LoadPlay() nếu sự kiện DOMContentLoaded bị lỡ
                    var interval = setInterval(function() {
                        if (typeof window.LoadPlay === 'function' && !window._csLoadPlayCalled) {
                            window._csLoadPlayCalled = true;
                            try { window.LoadPlay(); } catch(e) {}
                        }
                        if (typeof window.jwplayer === 'function') {
                            try {
                                var p = window.jwplayer();
                                if (p && typeof p.getPlaylist === 'function') {
                                    var pl = p.getPlaylist();
                                    if (pl && pl[0] && pl[0].file) notifyUrl(pl[0].file);
                                }
                            } catch(e) {}
                        }
                        var videos = document.getElementsByTagName('video');
                        for (var i = 0; i < videos.length; i++) {
                            if (videos[i].src) notifyUrl(videos[i].src);
                            var sources = videos[i].getElementsByTagName('source');
                            for (var j = 0; j < sources.length; j++) {
                                if (sources[j].src) notifyUrl(sources[j].src);
                            }
                        }
                    }, 400);

                    setTimeout(function() { clearInterval(interval); }, 12000);
                })();
            """.trimIndent()

            handler.post {
                try {
                    val wv = WebView(app.context)
                    webView = wv
                    val settings = wv.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    try {
                        cookieManager.setAcceptThirdPartyCookies(wv, true)
                    } catch (_: Exception) {}

                    fun checkUrl(targetUrl: String?): Boolean {
                        if (targetUrl.isNullOrEmpty()) return false
                        if (isValidStreamUrl(targetUrl)) {
                            capturedUrl = targetUrl
                            latch.countDown()
                            return true
                        }
                        return false
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            if (newProgress >= 30) {
                                view?.evaluateJavascript(injectionScript, null)
                            }
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(injectionScript, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(injectionScript, null)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val reqUrl = request?.url?.toString() ?: return false
                            if (reqUrl.startsWith("cloudstream://stream?url=")) {
                                val stream = try {
                                    URLDecoder.decode(reqUrl.substringAfter("cloudstream://stream?url="), "UTF-8")
                                } catch (_: Exception) {
                                    reqUrl.substringAfter("cloudstream://stream?url=")
                                }
                                return checkUrl(stream)
                            }
                            return false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val s = url ?: return false
                            if (s.startsWith("cloudstream://stream?url=")) {
                                val stream = try {
                                    URLDecoder.decode(s.substringAfter("cloudstream://stream?url="), "UTF-8")
                                } catch (_: Exception) {
                                    s.substringAfter("cloudstream://stream?url=")
                                }
                                return checkUrl(stream)
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString()
                            checkUrl(reqUrl)
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            checkUrl(url)
                            super.onLoadResource(view, url)
                        }
                    }

                    val headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    wv.loadUrl(url, headers)
                } catch (_: Exception) {
                    latch.countDown()
                }
            }

            try {
                latch.await(15, TimeUnit.SECONDS)
            } catch (_: Exception) {}

            handler.post {
                try {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.destroy()
                } catch (_: Exception) {}
            }

            return capturedUrl
        }

        /**
         * Kiểm tra cực kỳ nghiêm ngặt để loại bỏ 100% URL rác, quảng cáo, beacon gây lỗi 3003
         */
        fun isValidStreamUrl(rawUrl: String): Boolean {
            var url = rawUrl.trim()
            if (url.startsWith("//")) url = "https:$url"
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return false
            }

            val clean = url.substringBefore("?").substringBefore("#").lowercase()
            val full = url.lowercase()

            val blacklisted = listOf(
                "beacon", "pixel", "analytics", "stat", "view/", "views.",
                "track", "collect", "doubleclick", "googletag", "googleads",
                "cloudflareinsights", "crumpet", "fuckadblock", "adblock",
                "adsterra", "popcash", "banner", "telemetry", "counter",
                ".js", ".css", ".png", ".jpg", ".jpeg", ".svg", ".gif", ".webp",
                ".html", ".htm", ".php", "abyss.to/?"
            )

            if (blacklisted.any { clean.contains(it) || full.contains(it) }) {
                return false
            }

            val isM3u8 = clean.endsWith(".m3u8") || clean.contains("/master.m3u8") || clean.contains("/playlist.m3u8")
            val isMp4 = clean.endsWith(".mp4") || clean.endsWith(".m4v")
            val isSora = clean.contains("/sora/")
            val isChunk = clean.contains("/chunk/")

            return isM3u8 || isMp4 || isSora || isChunk
        }
    }
}

class PlayKrx18 : ExtractorApi() {
    override val name = "PlayKrx18"
    override val mainUrl = "https://play.playkrx18.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val stream = KRX18Provider.sniffStream(url, referer ?: "$mainUrl/")
        if (!stream.isNullOrEmpty()) {
            var finalUrl = stream.trim()
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            if (finalUrl.contains(".m3u8", ignoreCase = true)) {
                val m3u8 = try {
                    M3u8Helper.generateM3u8(
                        name,
                        finalUrl,
                        referer ?: "$mainUrl/",
                        headers = mapOf("Referer" to (referer ?: "$mainUrl/"))
                    )
                } catch (_: Exception) {
                    emptyList()
                }
                if (m3u8.isNotEmpty()) {
                    m3u8.forEach(callback)
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name HLS",
                            url = finalUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = referer ?: "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name MP4",
                        url = finalUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = referer ?: "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }
    }
}

typealias KRX18 = KRX18Provider
