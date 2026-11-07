package com.cat3movie

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
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Cat3movieProvider : MainAPI() {
    // >>> ĐỔI thành URL Cloudflare Worker của bạn (không có dấu / ở cuối). Xem hướng dẫn worker. <<<
    private val workerUrl = "https://cat3movie-proxy.silentmist3590.workers.dev"
    // Site thật: dùng làm Referer cho các server video (hlsfree/hlsfast cần Referer là site gốc)
    private val site = "https://cat3movie.org"
    override var mainUrl = workerUrl
    override var name = "Cat3movie"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val ua = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val hdr get() = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        "$mainUrl/asian-erotica" to "Asian Erotica",
        "$mainUrl/sex-education" to "Sex Education",
        "$mainUrl/classic-erotica" to "Classic Erotica",
        "$mainUrl/classic-porn" to "Classic Porn",
        "$mainUrl/newage-porn" to "New Age Porn",
        "$mainUrl/asian" to "Asian"
    )

    private fun pageUrl(base: String, page: Int): String {
        val b = base.trimEnd('/')
        return if (page <= 1) base else "$b/page/$page"
    }

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("a.halim-thumb") ?: selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = (selectFirst("h2.entry-title")?.text() ?: a.attr("title")).trim()
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster?.let { fixUrlNull(it) }
        }
    }

    private fun parseGrid(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        var items = doc.select("article.thumb.grid-item, article.grid-item, article.thumb").mapNotNull { it.toCard() }
        if (items.isEmpty()) {
            items = doc.select("a.halim-thumb").mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                val t = a.attr("title").trim()
                if (t.isBlank()) null else {
                    val img = a.selectFirst("img")
                    val po = img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
                    newMovieSearchResponse(t, href, TvType.NSFW) { this.posterUrl = po?.let { fixUrlNull(it) } }
                }
            }
        }
        return items.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ClipboardLogger.reset()
        val url = pageUrl(request.data, page)
        return try {
            val r = getRetry(url, hdr)
            val doc = r.document
            val items = parseGrid(doc)
            ClipboardLogger.logAndCopy("MainPage", "url=$url HTTP ${r.code} htmlLen=${r.text.length} items=${items.size}" +
                if (items.isEmpty()) "\n" + r.text.take(400) else "")
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("MainPage Error", "url=$url\n${e.message}\n${e.stackTraceToString().take(500)}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}", headers = hdr).document
        return parseGrid(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        ClipboardLogger.reset()
        val doc = app.get(url, headers = hdr).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: "Cat3movie"
        val poster = doc.selectFirst("img.movie-thumb")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot = doc.select(".entry-content .video-item article p, .entry-content p").joinToString("\n") { it.text() }.trim()
        val tags = doc.select("p.category a").map { it.text() }
        val actors = doc.select("p.actors a").map { it.text() }
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: doc.selectFirst("p.released a")?.text()?.toIntOrNull()
        val recs = doc.select("section.related-movies article").mapNotNull { it.toCard() }

        // Tất cả server: link trang watch
        val watch = LinkedHashSet<String>()
        doc.select("li.halim-episode-item[data-href], [data-href*=/watch-]").forEach {
            val h = it.attr("data-href"); if (h.isNotBlank()) watch.add(fixUrl(h))
        }
        doc.select("a[href*=/watch-]").forEach { watch.add(fixUrl(it.attr("href"))) }
        Regex("""https?:\\?/\\?/[^/"'\s<>\\]+\\?/watch-[^"'\s<>\\]+?\.html""").findAll(doc.html()).forEach {
            watch.add(it.value.replace("\\/", "/"))
        }
        ClipboardLogger.logAndCopy("load", "url=$url\ntitle=$title\nwatch(${watch.size}):\n" + watch.joinToString("\n"))

        return newMovieLoadResponse(title, url, TvType.NSFW, watch.joinToString("|")) {
            this.posterUrl = poster?.let { fixUrlNull(it) }
            this.plot = plot.ifBlank { null }
            this.year = year
            this.tags = tags
            this.actors = actors.map { ActorData(Actor(it)) }
            this.recommendations = recs
        }
    }

    private val m3u8Regex = Regex("""defaultHlsUrl\s*=\s*["']([^"']+)["']""")
    private val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val embedRegex = Regex("""https?:\\?/\\?/[^"'\s<>\\]+\\?/embed\\?/[^"'\s<>\\]+""")
    private val subRegex = Regex("""https?:\\?/\\?/[^"'\s<>\\]+\.(?:vtt|srt)(?:\?[^"'\s<>\\]*)?""")

    /** GET có thử lại khi site trả 5xx (Cloudflare 521/522...) hoặc trang lỗi. */
    private suspend fun getRetry(url: String, headers: Map<String, String>, params: Map<String, String> = emptyMap(), tries: Int = 4): com.lagradost.nicehttp.NiceResponse {
        var r = app.get(url, headers = headers, params = params)
        var i = 1
        while (r.code >= 500 && i < tries) {
            kotlinx.coroutines.delay(1500L * i)
            r = app.get(url, headers = headers, params = params)
            i++
        }
        return r
    }

    private suspend fun emit(callback: (ExtractorLink) -> Unit, label: String, url: String, ref: String, type: ExtractorLinkType) {
        val o = Regex("""^(https?://[^/]+)""").find(ref)?.groupValues?.get(1) ?: mainUrl
        callback.invoke(
            newExtractorLink(this.name, label, url, type) {
                this.referer = "$o/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Origin" to o, "Referer" to "$o/")
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        var found = false
        val pages = data.split("|").filter { it.startsWith("http") }
        ClipboardLogger.logAndCopy("loadLinks", "pages=$pages")
        pages.forEachIndexed { i, wp ->
            val n = i + 1
            try {
                val html = getRetry(wp, hdr).text
                val findEmbed = { t: String ->
                    (iframeRegex.findAll(t).map { it.groupValues[1].replace("\\/", "/") }
                        .firstOrNull { !it.contains("facebook") && !it.contains("ads") }
                        ?: Regex("""https?:\\?/\\?/hlsfree\.com[^"'\s<>\\]*""").find(t)?.value?.replace("\\/", "/")
                        ?: Regex("""(?:src|data-embed|data-src)=["']([^"']*/embed/[^"']+)["']""").find(t)?.groupValues?.get(1)
                        ?: embedRegex.find(t)?.value?.replace("\\/", "/"))
                        ?.let { if (it.startsWith("//")) "https:$it" else fixUrl(it) }
                }
                var embed = findEmbed(html)
                if (embed == null) {
                    // Player nạp bằng AJAX (player.php) giống JS của theme HaLim
                    fun cfg(k: String) = Regex(""""$k"\s*:\s*"?([^",}]*)"?""").find(html)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                    val purl = cfg("player_url").ifBlank { "$mainUrl/wp-content/themes/halimmovies/player.php" }
                    val nonce = Regex("""data-nonce=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: ""
                    val r = getRetry(
                        purl,
                        headers = hdr + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to wp),
                        params = mapOf(
                            "episode_slug" to cfg("episode_slug").ifBlank { "full" },
                            "server_id" to cfg("server").ifBlank { "${n}" },
                            "subsv_id" to "",
                            "post_id" to cfg("post_id"),
                            "nonce" to nonce,
                            "custom_var" to cfg("custom_var")
                        )
                    )
                    ClipboardLogger.logAndCopy("Server $n player.php", "url=$purl nonce=$nonce HTTP ${r.code}\n${r.text.take(700)}")
                    embed = findEmbed(r.text)
                    if (embed == null) {
                        val m = m3u8Regex.find(r.text)?.groupValues?.get(1)
                        if (m != null) { emit(callback, "Cat3movie S$n", m, "https://hlsfree.com/", ExtractorLinkType.M3U8); found = true; return@forEachIndexed }
                    }
                }
                if (embed == null) {
                    // Cuối cùng: mở thẳng trang watch trong WebView và bắt m3u8
                    val seen = mutableListOf<String>()
                    val hit = sniffStream(wp, "$mainUrl/", ua, seen, Regex("""\.m3u8|\.mpd|/api/hls/serve""", RegexOption.IGNORE_CASE), 40_000L)
                    ClipboardLogger.logAndCopy("Server $n WebView(watch)", "hit=$hit\nrequests(${seen.size}):\n" + seen.joinToString("\n"))
                    if (hit != null) {
                        emit(callback, "Cat3movie S$n", hit, if (hit.contains("hlsfree.com")) "https://hlsfree.com/" else "$site/", ExtractorLinkType.M3U8)
                        found = true
                    }
                    return@forEachIndexed
                }
                if (embed == null) return@forEachIndexed

                // hlsfast.com/#id -> API resolve -> trang player thật (vd turboembed.com/xxxx)
                if (embed.contains("hlsfast.com") && embed.contains("#")) {
                    val id = embed.substringAfter("#")
                    val base = Regex("""^(https?://[^/]+)""").find(embed)!!.groupValues[1]
                    val rr = app.get("$base/api/public/resolve?file1=$id", headers = mapOf("User-Agent" to ua, "Referer" to "$site/"))
                    ClipboardLogger.logAndCopy("Server $n resolve", "HTTP ${rr.code}\n${rr.text.take(300)}")
                    val u = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(rr.text)?.groupValues?.get(1)?.replace("\\/", "/")
                    if (u != null) embed = u
                }
                val ep = app.get(embed, headers = mapOf("User-Agent" to ua, "Referer" to "$site/")).text
                subRegex.findAll(ep).map { it.value.replace("\\/", "/") }.distinct().forEach {
                    subtitleCallback.invoke(SubtitleFile("English", it))
                }
                val m3 = m3u8Regex.find(ep)?.groupValues?.get(1)?.replace("\\/", "/")
                ClipboardLogger.logAndCopy("Server $n embed", "len=${ep.length} m3u8=$m3\n" + if (m3 == null) ep.take(500) else "")
                if (m3 != null) {
                    emit(callback, "Cat3movie S$n", m3, embed, ExtractorLinkType.M3U8)
                    found = true
                    return@forEachIndexed
                }
                // Fallback 1: extractor có sẵn
                val ok = try { loadExtractor(embed, "$site/", subtitleCallback, callback) } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e; false
                }
                if (ok) { found = true; return@forEachIndexed }
                // Fallback 2: WebView
                val seen = mutableListOf<String>()
                val hit = sniffStream(embed, "$site/", ua, seen, Regex("""\.m3u8|\.mpd|\.mp4(\?|$)|/api/hls/serve""", RegexOption.IGNORE_CASE), 40_000L)
                ClipboardLogger.logAndCopy("Server $n WebView", "hit=$hit\nrequests(${seen.size}):\n" + seen.joinToString("\n"))
                if (hit != null) {
                    emit(callback, "Cat3movie S$n", hit, embed, if (hit.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8)
                    found = true
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Server $n Exception", "${e.message}\n${e.stackTraceToString().take(600)}")
            }
        }
        if (!found) ClipboardLogger.logAndCopy("Error", "Không có server nào phát được.")
        return found
    }

    // =====================================================================================
    // WebView riêng: tải trang player, chặn mọi popup, gửi chạm THẬT (MotionEvent) vào giữa màn hình để bấm Play
    // và bắt URL .m3u8/.mpd đầu tiên mà trang gọi.
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, referer: String, ua: String, seen: MutableList<String>, streamRegex: Regex, timeoutMs: Long = 45_000L): String? {
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
                            if (streamRegex.containsMatchIn(u)) {
                                handler.post { finish(u) }
                            }
                            return null
                        }

                        // chặn mọi chuyển hướng sang trang quảng cáo
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val ok = u.startsWith(pageUrl.substringBefore("?")) || u.startsWith(Regex("^https?://[^/]+").find(pageUrl)?.value ?: pageUrl)
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
                    handler.postDelayed({ finish(null) }, timeoutMs)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }


}
