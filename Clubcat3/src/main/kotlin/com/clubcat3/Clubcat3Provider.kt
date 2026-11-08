package com.clubcat3

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
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class Clubcat3Provider : MainAPI() {
    override var mainUrl = "https://www.cat3movie.club"
    override var name = "Clubcat3"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val ua = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val hdr get() = mapOf("User-Agent" to ua, "Accept" to "application/json", "Referer" to "$mainUrl/")

    // Vivamax (2862) và sextop (2865) chỉ có link ngoài (kisskh...), không có video -> bỏ khỏi "Latest"
    override val mainPage = mainPageOf(
        "categories_exclude=2862,2865" to "Latest",
        "categories=1" to "Korean",
        "categories=5" to "USA",
        "categories=3" to "Japan",
        "categories=4" to "China",
        "categories=14" to "Thai",
        "categories=2867" to "TBONX"
    )

    private val fields = "_embed=wp:featuredmedia&_fields=link,title,content,_embedded"
    private val perPage = 20
    private val imgRe = Regex("""image=.\s*(https?:[^\s'’′"\]]+)""")
    private val srcRe = Regex("""\[jwplayer\s+id=.\s*(https?:[^\s'’′"\]]+)""")
    private val directRe = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*""")

    private fun clean(s: String) = Jsoup.parse(s).text().trim()

    private fun poster(o: JSONObject, content: String): String? {
        val emb = o.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)?.optString("source_url")
        return emb?.ifBlank { null } ?: imgRe.find(content)?.groupValues?.get(1)?.trim()
    }

    private fun toCard(o: JSONObject): SearchResponse? {
        val link = o.optString("link").ifBlank { return null }
        val title = clean(o.optJSONObject("title")?.optString("rendered") ?: "").ifBlank { return null }
        val content = o.optJSONObject("content")?.optString("rendered") ?: ""
        val p = poster(o, content)
        return newMovieSearchResponse(title, link, TvType.NSFW) { this.posterUrl = p }
    }

    private suspend fun fetchList(query: String, page: Int): List<SearchResponse> {
        val url = "$mainUrl/wp-json/wp/v2/posts?$query&per_page=$perPage&page=$page&$fields"
        return try {
            val r = app.get(url, headers = hdr)
            val arr = JSONArray(r.text)
            val items = (0 until arr.length()).mapNotNull { toCard(arr.getJSONObject(it)) }
            ClipboardLogger.logAndCopy("List", "url=$url HTTP ${r.code} items=${items.size}")
            items
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("List Error", "url=$url\n${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ClipboardLogger.reset()
        val items = fetchList(request.data, page)
        return newHomePageResponse(request.name, items, hasNext = items.size >= perPage)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        fetchList("search=${URLEncoder.encode(query, "UTF-8")}&categories_exclude=2862,2865", 1)

    /** Lấy mọi nguồn phát trong nội dung bài: shortcode [jwplayer id='URL'], iframe, nút server-list. */
    private fun extractSources(content: String): List<String> {
        val out = LinkedHashSet<String>()
        srcRe.findAll(content).forEach { out.add(it.groupValues[1].trim()) }
        val doc = Jsoup.parse(content)
        doc.select("iframe[src]").forEach { out.add(it.attr("src").trim()) }
        doc.select("#server-list a[href], a[target=search_iframe][href]").forEach { out.add(it.attr("href").trim()) }
        if (out.isEmpty()) directRe.findAll(content).forEach { out.add(it.value) }
        return out.filter { it.startsWith("http") }
    }

    override suspend fun load(url: String): LoadResponse {
        ClipboardLogger.reset()
        val slug = url.trimEnd('/').substringAfterLast('/')
        val api = "$mainUrl/wp-json/wp/v2/posts?slug=$slug&$fields"
        val r = app.get(api, headers = hdr)
        val arr = JSONArray(r.text)
        val o = arr.getJSONObject(0)
        val content = o.optJSONObject("content")?.optString("rendered") ?: ""
        val title = clean(o.optJSONObject("title")?.optString("rendered") ?: slug)
        val sources = extractSources(content)
        ClipboardLogger.logAndCopy("load", "api=$api HTTP ${r.code}\ntitle=$title\nsources(${sources.size}):\n" + sources.joinToString("\n"))
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.NSFW, sources.joinToString("|||")) {
            this.posterUrl = poster(o, content)
            this.year = year
            if (sources.isEmpty()) this.plot = "Bài này không có nguồn phát trực tiếp (chỉ có link ra trang khác)."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        val list = data.split("|||").map { it.trim() }.filter { it.startsWith("http") }
        ClipboardLogger.logAndCopy("loadLinks", "sources=$list")
        var found = false
        list.forEachIndexed { i, u ->
            val n = i + 1
            try {
                val isM3u8 = u.contains(".m3u8", true)
                val isMp4 = Regex("""\.mp4(\?|$|/)""", RegexOption.IGNORE_CASE).containsMatchIn(u)
                if (isM3u8 || isMp4) {
                    callback.invoke(
                        newExtractorLink(this.name, "Clubcat3 S$n", u, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
                        }
                    )
                    ClipboardLogger.logAndCopy("Source $n", "direct ${if (isM3u8) "m3u8" else "mp4"}: $u")
                    found = true
                    return@forEachIndexed
                }
                // Iframe / trang embed
                val ok = try { loadExtractor(u, "$mainUrl/", subtitleCallback, callback) } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e; false
                }
                ClipboardLogger.logAndCopy("Source $n", "embed $u loadExtractor=$ok")
                if (ok) { found = true; return@forEachIndexed }
                val seen = mutableListOf<String>()
                val hit = sniffStream(u, "$mainUrl/", ua, seen, Regex("""\.m3u8|\.mpd|\.mp4(\?|$)""", RegexOption.IGNORE_CASE), 35_000L)
                ClipboardLogger.logAndCopy("Source $n WebView", "hit=$hit\nrequests(${seen.size}):\n" + seen.joinToString("\n"))
                if (hit != null) {
                    val o = Regex("""^(https?://[^/]+)""").find(u)?.groupValues?.get(1) ?: mainUrl
                    val m = hit.contains(".mp4", true) && !hit.contains(".m3u8", true)
                    callback.invoke(
                        newExtractorLink(this.name, "Clubcat3 S$n", hit, if (m) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                            this.referer = "$o/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to ua, "Origin" to o, "Referer" to "$o/")
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Source $n Exception", "${e.message}\n${e.stackTraceToString().take(500)}")
            }
        }
        if (!found) ClipboardLogger.logAndCopy("Error", "Không có nguồn nào phát được.")
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
