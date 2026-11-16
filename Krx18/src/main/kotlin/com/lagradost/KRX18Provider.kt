package com.lagradost

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
                val directOk = if (embed.contains("mov18plus.cloud")) mov18Direct(embed, n, callback) else false
                val ok = if (directOk) true else if (embed.contains("mov18plus.cloud") && mov18Decrypted) false else if (embed.contains("playkrx18.site")) {
                    stream9(embed, "KRX18 S$n", subtitleCallback, callback)
                } else {
                    try {
                        var got = false
                        loadExtractor(embed, "$mainUrl/", subtitleCallback) { got = true; callback(it) }
                        if (!got) {
                            val o0 = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: mainUrl
                            val cands = LinkedHashSet<String>()
                            cands.add(embed)
                            // Thăm dò: lấy trạng thái HTTP, nội dung và iframe/script của trang embed
                            try {
                                val pr = app.get(embed, referer = "$mainUrl/", headers = mapOf("User-Agent" to ua, "Origin" to mainUrl))
                                val body = pr.text
                                ClipboardLogger.logAndCopy("Server $n Probe", "HTTP ${pr.code} len=${body.length}\nfinal=${pr.url}\n" + body.take(1500))
                                Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(body).forEach {
                                    var u = it.groupValues[1].replace("&amp;", "&")
                                    if (u.startsWith("//")) u = "https:$u" else if (u.startsWith("/")) u = o0 + u
                                    if (u.startsWith("http")) cands.add(u)
                                }
                                Regex("""https?:[^"'\s<>\\]+\.(?:m3u8|mp4)[^"'\s<>\\]*""", RegexOption.IGNORE_CASE).find(body)?.let { cands.add(it.value) }
                            } catch (e: Exception) {
                                if (e is java.util.concurrent.CancellationException) throw e
                                ClipboardLogger.logAndCopy("Server $n Probe", "lỗi: ${e.message}")
                            }
                            // Biến thể URL của mov18plus (dạng cũ ?v=ID từng hoạt động)
                            Regex("""mov18plus\.cloud/v/([^/?#]+)""").find(embed)?.groupValues?.get(1)?.let { id ->
                                cands.add("https://mov18plus.cloud/?v=$id")
                                cands.add("https://mov18plus.cloud/v/$id")
                                cands.add("https://mov18plus.cloud/e/$id")
                            }
                            val seen = mutableListOf<String>()
                            var hit: String? = null
                            var idx = 0
                            for (cu in cands.take(5)) {
                                idx++
                                val strict = cu.contains("mov18plus") || cu.contains("loadvid") || cu.contains("helvid")
                                val rx = Regex("""\.m3u8|\.mp4(\?|$)|\.mpd|/hls/\d{3,4}/""", RegexOption.IGNORE_CASE)
                                // player mov18plus/helvid: thử bọc trong iframe trước, rồi mới mở trực tiếp
                                val modes = if (cu.contains("mov18plus") || cu.contains("helvid")) listOf(true, false) else listOf(false)
                                for (wrap in modes) {
                                    if (!wrap && idx > 1 && modes.size > 1) continue
                                    seen.add("--- thử #$idx${if (wrap) " (iframe)" else ""}: $cu")
                                    hit = sniffStream(cu, "$mainUrl/", ua, seen, rx, (if (strict) (if (idx == 1) 50_000L else 30_000L) else 30_000L), cu.contains("loadvid"), wrap)
                                    if (hit != null) break
                                }
                                if (hit != null) break
                            }
                            ClipboardLogger.logAndCopy("Server $n WebView", "hit=$hit\nrequests(${seen.size}):\n" + seen.joinToString("\n"))
                            if (hit != null) {
                                val o = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: mainUrl
                                val inline = hit.trimStart().startsWith("#EXTM3U")
                                val playUrl = if (inline) PlaylistProxy.register(hit, "$o/", "$o/", ua) else hit
                                if (inline) ClipboardLogger.logAndCopy("Server $n inline", "playlist -> $playUrl (${hit.lines().count { !it.startsWith("#") && it.isNotBlank() }} phân đoạn)")
                                val isM3u8 = inline || !hit.contains(".mp4")
                                callback.invoke(
                                    newExtractorLink(this.name, "KRX18 S$n", playUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                        this.referer = "$o/"
                                        this.quality = Qualities.Unknown.value
                                        this.headers = if (inline) emptyMap() else mapOf("User-Agent" to ua, "Origin" to o, "Referer" to "$o/")
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
    // mov18plus.cloud: dữ liệu nguồn nằm trong biến `datas` (base64 -> JSON), trường `media` mã hoá AES-CTR.
    // key = md5("user_id:slug:md5_id") dạng chuỗi hex ASCII (32 byte), counter = 16 byte đầu của key.
    // =====================================================================================
    private suspend fun mov18Direct(embed: String, n: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            mov18Decrypted = false
            val o = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: return false
            val html = app.get(embed, referer = "$mainUrl/", headers = mapOf("User-Agent" to ua, "Origin" to mainUrl)).text
            val b64 = Regex("""datas\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(html)?.groupValues?.get(1)
            if (b64 == null) {
                ClipboardLogger.logAndCopy("Server $n mov18", "không thấy biến datas (len=${html.length})")
                return false
            }
            val j = JSONObject(String(Base64.decode(b64, Base64.DEFAULT), Charsets.ISO_8859_1))
            val keyStr = md5Hex("${j.optLong("user_id")}:${j.optString("slug")}:${j.optLong("md5_id")}")
            val key = keyStr.toByteArray(Charsets.ISO_8859_1)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(key.copyOfRange(0, 16)))
            val plain = String(cipher.doFinal(j.getString("media").toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8)
            val mp4 = JSONObject(plain).optJSONObject("mp4")
            ClipboardLogger.logAndCopy("Server $n mov18", "giải mã OK (${plain.length} ký tự)")
            if (mp4 == null) return false
            mov18Decrypted = true
            val srcs = mp4.optJSONArray("sources") ?: return false
            val domains = mp4.optJSONArray("domains")
            val domList = (0 until (domains?.length() ?: 0)).map { domains!!.getString(it) }
            val mid = j.optLong("md5_id")
            val slug = j.optString("slug")
            var any = false
            var handshakeLogged = false
            val ucUa = "Mozilla/5.0 (Linux; U; Android 11; en-US; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/78.0.3904.108 UCBrowser/13.4.0.1306 Mobile Safari/537.36"
            for (i in 0 until srcs.length()) {
                val sc = srcs.getJSONObject(i)
                if (!sc.optBoolean("status", true)) continue
                val size = sc.optLong("size")
                val res = sc.optInt("res_id")
                val sub = sc.optString("sub")
                val dom = domList.firstOrNull { it.contains(sub) } ?: continue
                val q = when (res) { 3 -> 480; 4 -> 720; 5 -> 1080; else -> 0 }
                // token = btoa(btoa(AES-CTR("/mp4/<md5_id>/<res_id>/<size>?v=<slug>")) bỏ '=') bỏ '='; key = md5 của các CHỮ SỐ của size (giá trị 0..9)
                val token = try {
                    val k = java.security.MessageDigest.getInstance("MD5").digest(size.toString().map { (it - '0').toByte() }.toByteArray())
                        .joinToString("") { b -> "%02x".format(b) }.toByteArray(Charsets.ISO_8859_1)
                    val c = Cipher.getInstance("AES/CTR/NoPadding")
                    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), javax.crypto.spec.IvParameterSpec(k.copyOfRange(0, 16)))
                    val enc = c.doFinal("/mp4/$mid/$res/$size?v=$slug".toByteArray(Charsets.ISO_8859_1))
                    val b1 = Base64.encodeToString(enc, Base64.NO_WRAP).replace("=", "")
                    Base64.encodeToString(b1.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP).replace("=", "")
                } catch (e: Exception) { continue }
                val url = "https://$dom/sora/$size/$token"
                var okFile = false
                var useUa = ucUa
                if (!handshakeLogged) {
                    handshakeLogged = true
                    try {
                        val hreq = okhttp3.Request.Builder().url("https://$dom/?timestamp=${System.currentTimeMillis()}&id=${java.lang.Long.toString(System.nanoTime(), 36).takeLast(11)}").header("User-Agent", ucUa).header("Referer", "$o/").header("Origin", o).build()
                        app.baseClient.newCall(hreq).execute().use { r ->
                            ClipboardLogger.logAndCopy("Server $n mov18 handshake", "HTTP ${r.code} ct=${r.header("Content-Type")} set-cookie=${r.headers("Set-Cookie").size}\n" + (r.body?.string() ?: "").take(300))
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.logAndCopy("Server $n mov18 handshake", "lỗi: ${e.message}")
                    }
                }
                for ((attempt, tryUa) in listOf(ucUa, ua).withIndex()) {
                    try {
                        val req = okhttp3.Request.Builder().url(url).header("User-Agent", tryUa).header("Referer", "$o/").header("Origin", o).header("Range", "bytes=0-31").build()
                        app.baseClient.newCall(req).execute().use { r ->
                            val bytes = r.body?.bytes() ?: ByteArray(0)
                            val head = bytes.take(32).joinToString("") { b -> "%02x".format(b) }
                            val ftyp = bytes.size >= 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp"
                            val ct = r.header("Content-Type") ?: ""
                            okFile = (r.code == 200 || r.code == 206) && !ct.contains("text") && !ct.contains("json") && bytes.isNotEmpty()
                            ClipboardLogger.logAndCopy("Server $n mov18 probe ${q}p #${attempt + 1}", "HTTP ${r.code} ct=$ct range=${r.header("Content-Range")} ftyp=$ftyp\nhead=$head\n$url")
                            if (okFile) useUa = tryUa
                        }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.logAndCopy("Server $n mov18 probe ${q}p #${attempt + 1}", "lỗi: ${e.message}")
                    }
                    if (okFile) break
                }
                if (!okFile) continue
                callback.invoke(
                    newExtractorLink(this.name, "KRX18 S$n ${if (q > 0) "${q}p" else ""}".trim(), url, ExtractorLinkType.VIDEO) {
                        this.referer = "$o/"
                        this.quality = if (q > 0) q else Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to useUa, "Origin" to o, "Referer" to "$o/")
                    }
                )
                any = true
            }
            return any
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Server $n mov18 Error", "${e.message}\n${e.stackTraceToString().take(500)}")
            return false
        }
    }

    @Volatile private var mov18Decrypted = false

    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // =====================================================================================
    // WebView riêng: tải trang player, chặn mọi popup, gửi chạm THẬT (MotionEvent) vào giữa màn hình để bấm Play
    // và bắt URL .m3u8/.mpd đầu tiên mà trang gọi.
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, referer: String, ua: String, seen: MutableList<String>, streamRegex: Regex, timeoutMs: Long = 45_000L, hookFetch: Boolean = false, wrapInFrame: Boolean = false): String? {
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
                    val popups = ArrayList<WebView>()
                    fun finish(res: String?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            popups.toList().forEach { pp -> try { pp.destroy() } catch (_: Exception) {} }
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) {
                        }
                        cont.resume(res)
                    }
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)
                    val strictPlayerEarly = pageUrl.contains("mov18plus") || pageUrl.contains("loadvid") || pageUrl.contains("helvid")
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = strictPlayerEarly
                        setSupportMultipleWindows(strictPlayerEarly)
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    // Popup quảng cáo: không mở cửa sổ mới
                    wv.webChromeClient = object : WebChromeClient() {
                        // mov18plus / loadvid: phải để quảng cáo mở thật và tải xong (giữ 5s) rồi mới đóng, lần bấm Play sau đó mới phát
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                            if (!strictPlayerEarly || finished || popups.size >= 3) return false
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            return try {
                                val pw = WebView(ctx)
                                pw.settings.javaScriptEnabled = true
                                pw.settings.domStorageEnabled = true
                                pw.webViewClient = WebViewClient()
                                pw.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                                pw.layout(0, 0, w, h)
                                transport.webView = pw
                                resultMsg.sendToTarget()
                                popups.add(pw)
                                ClipboardLogger.logAndCopy("Popup", "mở quảng cáo, đóng sau 5s")
                                handler.postDelayed({
                                    try { pw.stopLoading(); pw.destroy() } catch (_: Exception) {}
                                    popups.remove(pw)
                                    ClipboardLogger.logAndCopy("Popup", "đã đóng quảng cáo, bấm Play lại")
                                }, 5000L)
                                true
                            } catch (_: Throwable) { false }
                        }

                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                            val msg = m?.message() ?: return false
                            if (msg.startsWith("KRXJW:")) {
                                val urls = msg.removePrefix("KRXJW:").trim().split(" ").map { it.replace("\\/", "/") }.filter { it.startsWith("http") }
                                ClipboardLogger.logAndCopy("jwplayer sources", urls.joinToString("\n").take(600))
                                val u = urls.firstOrNull { Regex("""\.(m3u8|mp4|mpd)""", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: urls.firstOrNull()
                                if (u != null) handler.post { finish(u) }
                                return true
                            }
                            if (msg.startsWith("KRXDIAG:")) {
                                ClipboardLogger.logAndCopy("Diag", msg.removePrefix("KRXDIAG:").take(900))
                                return true
                            }
                            if (msg.startsWith("KRXRT:")) {
                                val body = msg.removePrefix("KRXRT:").replace("\\/", "/")
                                ClipboardLogger.logAndCopy("resolve-token", body.take(600))
                                if (body.trimStart().startsWith("#EXTM3U")) {
                                    // loadvid trả thẳng nội dung playlist (phân đoạn ngụy trang .png) -> trả nguyên văn, loadLinks sẽ phát qua proxy cục bộ
                                    handler.post { finish(body) }
                                    return true
                                }
                                val u = Regex("""https?://[^"'\s<>\\]+\.(?:m3u8|mp4|mpd)[^"'\s<>\\]*""", RegexOption.IGNORE_CASE).find(body)?.value
                                if (u != null) handler.post { finish(u) }
                            }
                            return true
                        }
                    }
                    // mov18plus.cloud và loadvid kiểm tra window.open/fetch/setTimeout có bị ghi đè (coi là tiện ích chặn quảng cáo) -> không ghi đè
                    val strictPlayer = pageUrl.contains("mov18plus") || pageUrl.contains("loadvid") || pageUrl.contains("helvid")
                    val fakePopup = if (strictPlayer) "" else "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val hookJs = "(function(){if(window.__krxh)return;window.__krxh=1;" +
                        "var of=window.fetch;window.fetch=function(){var a=arguments,u=typeof a[0]==='string'?a[0]:(a[0]&&a[0].url)||'';" +
                        "var p=of.apply(this,a);if(u.indexOf('resolve-token')>=0){p.then(function(r){return r.clone().text()}).then(function(t){console.log('KRXRT:'+t)}).catch(function(){})}return p};" +
                        "var oo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){var x=this;if(u&&String(u).indexOf('resolve-token')>=0){x.addEventListener('load',function(){try{console.log('KRXRT:'+x.responseText)}catch(e){}})}return oo.apply(this,arguments)}})();"
                    val clickJs = "(function(){function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-display-icon-container,.jw-bigplay,[aria-label=Play]'));" +
                        "var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}})();"
                    val jwJs = "(function(){try{if(window.jwplayer){var j=jwplayer();var pl=j.getPlaylist&&j.getPlaylist();if(pl&&pl.length){var f=[];pl.forEach(function(it){(it.sources||[]).forEach(function(s){if(s.file)f.push(s.file)});if(it.file)f.push(it.file)});if(f.length)console.log('KRXJW:'+f.join(' '))}}}catch(e){}})();"
                    val diagJs = "(function(){try{var e=document.elementFromPoint(innerWidth/2,innerHeight/2);var v=document.querySelector('video');" +
                        "var jw='';try{jw=window.jwplayer?jwplayer().getState():'nojw'}catch(x){jw='jwerr'}" +
                        "console.log('KRXDIAG:center='+(e?e.tagName+'#'+e.id+'.'+(typeof e.className==='string'?e.className:'')+' '+(e.outerHTML||'').slice(0,200):'null')+' | video='+(v?('src='+(v.currentSrc||v.src)+' rs='+v.readyState+' paused='+v.paused):'none')+' | jw='+jw+' | iframes='+document.querySelectorAll('iframe').length)}catch(x){console.log('KRXDIAG:err '+x)}})();"
                    var taps = 0
                    fun tap() {
                        if (finished) return
                        if (popups.isNotEmpty()) { handler.postDelayed({ tap() }, 1000); return }
                        taps++
                        try {
                            wv.evaluateJavascript(jwJs, null)
                            if (strictPlayerEarly && (taps == 2 || taps == 8 || taps == 16)) wv.evaluateJavascript(diagJs, null)
                            wv.evaluateJavascript(fakePopup + clickJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle(); u.recycle()
                        } catch (_: Exception) {
                        }
                        if (taps < 40) handler.postDelayed({ tap() }, 1500)
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (hookFetch) view?.evaluateJavascript(hookJs, null)
                        }

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
                            if (!ok) {
                                seen.add("BLOCKED NAV: " + u.take(160))
                                if (strictPlayerEarly && !finished && popups.size < 3 && u.startsWith("http")) {
                                    try {
                                        val pw = WebView(ctx)
                                        pw.settings.javaScriptEnabled = true
                                        pw.settings.domStorageEnabled = true
                                        pw.webViewClient = WebViewClient()
                                        pw.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                                        pw.layout(0, 0, w, h)
                                        popups.add(pw)
                                        pw.loadUrl(u)
                                        ClipboardLogger.logAndCopy("Popup", "quảng cáo điều hướng ${u.take(60)} -> mở nền, đóng sau 5s")
                                        handler.postDelayed({
                                            try { pw.stopLoading(); pw.destroy() } catch (_: Exception) {}
                                            popups.remove(pw)
                                            ClipboardLogger.logAndCopy("Popup", "đã đóng quảng cáo, bấm Play lại")
                                        }, 5000L)
                                    } catch (_: Throwable) {}
                                }
                            }
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
                    if (wrapInFrame) {
                        // Trang player kiểm tra "mở trực tiếp" (không nằm trong iframe / không có referrer) -> bọc trong iframe của trang krx18
                        val base = referer.trimEnd('/') + "/"
                        wv.loadDataWithBaseURL(base, "<html><body style=\"margin:0;background:#000\"><iframe src=\"$pageUrl\" allow=\"autoplay; fullscreen; encrypted-media\" style=\"border:0;width:100%;height:100vh\"></iframe></body></html>", "text/html", "utf-8", null)
                    } else {
                        wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                    }
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

        val hdrs = mapOf("User-Agent" to ua, "Origin" to origin, "Referer" to "$origin/")
        suspend fun emitQ(u: String, q: Int?) {
            callback.invoke(
                newExtractorLink(this.name, if (q != null && q > 0) "$label ${q}p" else label, u, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"
                    this.quality = q ?: Qualities.Unknown.value
                    this.headers = hdrs
                }
            )
        }
        val qRegex = Regex("""/hls/(\d{3,4})/""")
        val cur = qRegex.find(playlist)?.groupValues?.get(1)?.toIntOrNull()
        val emitted = LinkedHashSet<Int>()
        try {
            val body = app.get(playlist, headers = hdrs).text
            if (body.contains("#EXT-X-STREAM-INF")) {
                // Master playlist: liệt kê từng độ phân giải
                val lines = body.lines()
                var n = 0
                for (i in lines.indices) {
                    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                    val h = Regex("""RESOLUTION=\d+x(\d+)""").find(lines[i])?.groupValues?.get(1)?.toIntOrNull()
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
                    val abs = java.net.URI(playlist).resolve(uri).toString()
                    emitQ(abs, h)
                    if (h != null) emitted.add(h)
                    n++
                }
                ClipboardLogger.logAndCopy("9stream master", "variants=$n qualities=$emitted")
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("9stream master Error", e.message ?: "")
        }
        // Luôn phát bản gốc (master hoặc bản đang có)
        emitQ(playlist, cur)
        if (cur != null) emitted.add(cur)
        // Playlist theo từng chất lượng (/hls/720/...): thử các chất lượng anh em
        if (cur != null) {
            for (q in listOf(1080, 720, 480, 360, 240)) {
                if (q == cur || q in emitted) continue
                val alt = playlist.replace("/hls/$cur/", "/hls/$q/")
                try {
                    val r = app.get(alt, headers = hdrs)
                    val ok = r.code == 200 && r.text.trimStart().startsWith("#EXTM3U")
                    ClipboardLogger.logAndCopy("9stream alt ${q}p", "HTTP ${r.code} ok=$ok\n$alt")
                    if (ok) { emitQ(alt, q); emitted.add(q) }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    ClipboardLogger.logAndCopy("9stream alt ${q}p Error", e.message ?: "")
                }
            }
        }
        ClipboardLogger.logAndCopy("9stream OK", "playlist=$playlist\nqualities=$emitted")
        return true
    }
}
