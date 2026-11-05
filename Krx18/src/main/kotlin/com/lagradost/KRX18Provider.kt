package com.krx18

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KRX18Provider : MainAPI() {
    override var mainUrl = "https://krx18.com"
    override var name = "KRX18"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private fun hdr(ref: String? = null) = mapOf("User-Agent" to ua, "Referer" to (ref ?: "$mainUrl/"))

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Latest 18+",
        "$mainUrl/genre/xxx" to "X Clip",
        "$mainUrl/genre/eng-sub" to "Eng Sub",
        "$mainUrl/genre/japan" to "Japan",
        "$mainUrl/genre/korea" to "Korea",
        "$mainUrl/genre/thailand" to "Thailand",
        "$mainUrl/genre/philippines" to "Philippines",
        "$mainUrl/genre/china" to "China",
        "$mainUrl/genre/usa" to "USA",
        "$mainUrl/genre/uk" to "UK",
        "$mainUrl/genre/france" to "France",
        "$mainUrl/genre/italy" to "Italy"
    )

    // ---------------------------------------------------------------- danh sách / tìm kiếm
    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".data h3 a") ?: selectFirst(".poster a[href]") ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (!href.contains("/movies/")) return null
        val title = selectFirst(".data h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val img = selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src")
        val year = selectFirst(".data span")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
        }
    }

    private fun parseGrid(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        // article.item = lưới chính (bỏ qua widget sidebar "w_item_b")
        val seen = HashSet<String>()
        return doc.select("article.item").mapNotNull { it.toCard() }.filter { seen.add(it.url) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "${request.data}/" else "${request.data}/page/$page/"
        return try {
            val res = app.get(url, headers = hdr())
            val items = parseGrid(res.document)
            ClipboardLogger.logAndCopy("MainPage ${request.name} p$page", "HTTP ${res.code}, items=${items.size}\nposter0=${items.firstOrNull()?.posterUrl}")
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("MainPage Error", "$url -> ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val res = app.get("$mainUrl/?s=$q", headers = hdr())
            val items = parseGrid(res.document).ifEmpty {
                // trang kết quả tìm kiếm cũng có thể dùng widget w_item_b
                res.document.select("article.w_item_b").mapNotNull { it.toCard() }
            }
            ClipboardLogger.logAndCopy("Search", "q=$query HTTP ${res.code}, items=${items.size}")
            items
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Search Error", "$query -> ${e.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------------- chi tiết
    override suspend fun load(url: String): LoadResponse? {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Load", url)
        return try {
            val res = app.get(url, headers = hdr())
            val doc = res.document
            val title = doc.selectFirst("div.sheader h1")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
            val poster = doc.selectFirst("div.sheader div.poster img")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = doc.selectFirst("div.sheader .wp-content")?.text()?.trim()
                ?: doc.selectFirst("div#info .wp-content")?.text()?.trim()
            val year = doc.selectFirst("div.sheader span.date")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            val duration = doc.selectFirst("div.sheader span.runtime")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            val tags = doc.select("div.sgeneros a").map { it.text().trim() }.filter { it.isNotEmpty() }
            val rating = doc.selectFirst("#repimdb strong")?.text()?.toDoubleOrNull()

            // Các server phát (DooPlay): li.dooplay_player_option data-post/data-nume/data-type
            val opts = doc.select("li.dooplay_player_option")
            val post = opts.firstOrNull()?.attr("data-post")?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("meta#dooplay-ajax-counter")?.attr("data-postid") ?: ""
            val type = opts.firstOrNull()?.attr("data-type")?.takeIf { it.isNotEmpty() } ?: "movie"
            val nums = opts.map { it.attr("data-nume") }.filter { it.isNotEmpty() }.ifEmpty { listOf("1") }
            val data = "$post|${nums.joinToString(",")}|$type"
            ClipboardLogger.logAndCopy("Load info", "title=$title\npost=$post nums=$nums type=$type\nposter=$poster")

            val recs = doc.select("div.srelacionados article").mapNotNull { art ->
                val a = art.selectFirst("a[href]") ?: return@mapNotNull null
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val img = art.selectFirst("img")
                val t = img?.attr("alt")?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                newMovieSearchResponse(t, href, TvType.NSFW) { this.posterUrl = fixUrlNull(img?.attr("src")) }
            }

            newMovieLoadResponse(title, url, TvType.NSFW, data) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.duration = duration
                this.tags = tags
                rating?.let { this.score = Score.from10(it) }
                this.recommendations = recs
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Load Error", "$url -> ${e.message}\n${e.stackTraceToString().take(800)}")
            null
        }
    }

    // ---------------------------------------------------------------- phát
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks Start", data)
        val parts = data.split("|")
        val post = parts.getOrNull(0).orEmpty()
        val nums = parts.getOrNull(1).orEmpty().split(",").filter { it.isNotEmpty() }
        val type = parts.getOrNull(2)?.ifEmpty { null } ?: "movie"
        if (post.isEmpty() || nums.isEmpty()) {
            ClipboardLogger.logAndCopy("Error", "Thiếu post/nume: $data")
            return false
        }
        var found = false
        for (n in nums) {
            try {
                val apiUrl = "$mainUrl/wp-json/dooplayer/v2/$post/$type/$n"
                val r = app.get(apiUrl, headers = hdr() + mapOf("Accept" to "application/json"))
                val embed = JSONObject(r.text).optString("embed_url")
                ClipboardLogger.logAndCopy("Server $n", "API HTTP ${r.code}\nembed=$embed")
                if (embed.isEmpty()) continue
                val ok = if (embed.contains("playkrx18.site")) {
                    stream9(embed, "KRX18 S$n", subtitleCallback, callback)
                } else {
                    try {
                        var got = false
                        loadExtractor(embed, "$mainUrl/", subtitleCallback) { got = true; callback(it) }
                        if (!got && !found) {
                            val seen = mutableListOf<String>()
                            val hit = sniffStream(embed, "$mainUrl/", ua, seen, Regex("""\.m3u8|\.mp4(\?|$)|\.mpd|/hls/\d{3,4}/""", RegexOption.IGNORE_CASE), 25_000L)
                            ClipboardLogger.logAndCopy("Server $n WebView", "hit=$hit\nrequests(${seen.size}):\n" + seen.joinToString("\n"))
                            if (hit != null) {
                                val o = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: mainUrl
                                val isM3u8 = !hit.contains(".mp4")
                                callback.invoke(
                                    newExtractorLink(this.name, "KRX18 S$n", hit, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                        this.referer = "$o/"
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf("User-Agent" to ua, "Origin" to o, "Referer" to "$o/")
                                    }
                                )
                                got = true
                            }
                        }
                        got
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.logAndCopy("Server $n Error", "loadExtractor: ${e.message}")
                        false
                    }
                }
                if (ok) found = true
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


    // ---------------------------------------------------------------- player 9stream (playkrx18.site)
    private val keyBase = "7bd30d612d2bb1bda34743f8608e68c"
    private val ivBase = "v3M9zQ2pX7k"

    private fun gcm(mode: Int, step: Int, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            mode,
            SecretKeySpec((keyBase + step).toByteArray(Charsets.UTF_8), "AES"),
            GCMParameterSpec(128, (ivBase + step).toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(input)
    }

    private fun dec(b64: String, step: Int): String =
        String(gcm(Cipher.DECRYPT_MODE, step, Base64.decode(b64, Base64.DEFAULT)), Charsets.UTF_8)

    private fun enc(text: String, step: Int): String =
        Base64.encodeToString(gcm(Cipher.ENCRYPT_MODE, step, text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    private suspend fun stream9(
        embed: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val page = app.get(embed, headers = hdr("$mainUrl/")).text
        val idEnc = Regex("""idfile_enc\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
        val userEnc = Regex("""idUser_enc\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
        val api = Regex("""DOMAIN_API\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
        val subsRaw = Regex("""data_subs\s*=\s*'([^']*)'""").find(page)?.groupValues?.get(1).orEmpty()
        ClipboardLogger.logAndCopy("9stream page", "idfile_enc=${idEnc?.take(20)} idUser_enc=${userEnc?.take(20)}\napi=$api\nsubs=${subsRaw.take(200)}")
        if (idEnc == null || userEnc == null || api == null) {
            ClipboardLogger.logAndCopy("Error", "Không đọc được thông tin player 9stream (trang embed đổi cấu trúc?)\n" + page.take(500))
            return false
        }
        val idFile = dec(idEnc, 1)
        val idUser = dec(userEnc, 2)
        val payload = "{\"idfile\":\"$idFile\",\"iduser\":\"$idUser\",\"domain_play\":\"$mainUrl\",\"unixTimestamp\":${System.currentTimeMillis() / 1000}}"
        val body = enc(payload, 3)
        val r = app.post(
            api,
            data = mapOf("data" to body),
            headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        )
        ClipboardLogger.logAndCopy("9stream getplay", "HTTP ${r.code}\n${r.text.take(600)}")
        val json = JSONObject(r.text)
        var playlist = ""
        if (json.optString("status") != "success") {
            ClipboardLogger.logAndCopy("9stream", "getplay trực tiếp bị từ chối (${r.text.take(120)}) -> thử WebView")
            val seen = mutableListOf<String>()
            val hit = sniffStream(embed, "$mainUrl/", ua, seen, Regex("""/hls/\d{3,4}/|\.m3u8"""), 40_000L)
            ClipboardLogger.logAndCopy("9stream WebView", "hit=$hit\nrequests(${seen.size}):\n" + seen.joinToString("\n"))
            if (hit == null) {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được playlist 9stream")
                return false
            }
            playlist = hit
        } else playlist = json.optString("data")
        if (!playlist.startsWith("http")) {
            // dự phòng: dữ liệu trả về có thể được mã hóa
            playlist = try { dec(playlist, 4) } catch (e: Exception) { playlist }
        }
        if (!playlist.startsWith("http")) {
            ClipboardLogger.logAndCopy("Error", "Playlist không hợp lệ: ${playlist.take(120)}")
            return false
        }

        // Phụ đề (JWPlayer tracks): [{"file": "...", "label": "English", "kind": "captions"}, ...]
        if (subsRaw.isNotBlank()) {
            try {
                val arr = JSONArray(subsRaw)
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val file = t.optString("file").ifEmpty { t.optString("src") }
                    if (file.isEmpty()) continue
                    val kind = t.optString("kind", "captions")
                    if (kind == "thumbnails") continue
                    val lbl = t.optString("label").ifEmpty { "Subtitle ${i + 1}" }
                    subtitleCallback.invoke(SubtitleFile(lbl, fixUrl(file)))
                    ClipboardLogger.logAndCopy("Subtitle", "$lbl -> $file")
                }
            } catch (e: Exception) {
                ClipboardLogger.logAndCopy("Subtitle Error", "${e.message}\n${subsRaw.take(200)}")
            }
        }

        val q = Regex("""/hls/(\d{3,4})/""").find(playlist)?.groupValues?.get(1)?.toIntOrNull()
        ClipboardLogger.logAndCopy("9stream OK", "playlist=$playlist\nquality=$q")
        callback.invoke(
            newExtractorLink(this.name, if (q != null) "$label ${q}p" else label, playlist, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = q ?: Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Origin" to origin, "Referer" to "$origin/")
            }
        )
        return true
    }
}
