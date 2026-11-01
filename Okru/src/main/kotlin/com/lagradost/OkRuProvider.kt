package com.okru

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

// =====================================================================
// ENTRY POINT
// =====================================================================
@CloudstreamPlugin
class OkRuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OkRuProvider())
    }
}

// =====================================================================
// PROVIDER
// =====================================================================
class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OkRu"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasQuickSearch = true
    override val hasMainPage = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val posterHeaders = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "https://ok.ru/"
    )

    // =================================================================
    // TRANG CHỦ — Scrape recommendations từ video seed
    // =================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val seeds = listOf(
            "7475662490142" to "Đề xuất cho bạn",
            "5686752709150" to "Phổ biến",
            "7407305296414" to "Khác"
        )
        val seen = mutableSetOf<String>()
        val sections = mutableListOf<HomePageList>()

        for ((seedId, label) in seeds) {
            try {
                val doc = app.get(
                    "$mainUrl/video/$seedId",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to mainUrl,
                        "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                    )
                ).document

                val linkCount = doc.select("a[href*=/video/]").size
                android.util.Log.d("OkRuDebug", "Seed $seedId: $linkCount video links")

                val items = mutableListOf<SearchResponse>()
                doc.select("a[href*=/video/]").forEach { a ->
                    val href = a.attr("href").trim()
                    val m = Regex("""/video/(\d+)""").find(href) ?: return@forEach
                    val vid = m.groupValues[1]
                    if (vid == seedId) return@forEach
                    if (!seen.add(vid)) return@forEach

                    val title = extractTitle(a) ?: return@forEach
                    val (poster, headers) = extractPoster(a)

                    items.add(
                        newMovieSearchResponse(title, "$mainUrl/video/$vid", TvType.Movie) {
                            this.posterUrl = poster
                            this.posterHeaders = headers
                        }
                    )
                }

                android.util.Log.d("OkRuDebug", "Seed $seedId: parsed ${items.size} items")
                if (items.isNotEmpty()) sections.add(HomePageList(label, items))
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Seed $seedId failed", e)
            }
        }

        if (sections.isEmpty()) return null
        return newHomePageResponse(sections)
    }

    // =================================================================
    // TÌM KIẾM — Khôi phục logic fetchVideoList từ bản trước
    // =================================================================
    override suspend fun search(query: String): List<SearchResponse>? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // Ưu tiên 1: Nếu user paste URL hoặc ID video → xử lý trực tiếp
        val directMatch = Regex("""(?:/video/)?(\d{6,})""").find(trimmed)
        if (directMatch != null &&
            (trimmed.contains("/video/") || trimmed.matches(Regex("""\d{6,}""")))) {
            val videoId = directMatch.groupValues[1]
            android.util.Log.d("OkRuDebug", "Search: direct video ID $videoId")
            return directVideoSearch(videoId)
        }

        // Ưu tiên 2: Khôi phục fetchVideoList từ bản trước
        android.util.Log.d("OkRuDebug", "Search: trying fetchVideoList for '$trimmed'")
        val fromJson = fetchVideoList(trimmed)
        if (fromJson.isNotEmpty()) {
            android.util.Log.d("OkRuDebug", "Search: fetchVideoList returned ${fromJson.size} items")
            return fromJson
        }

        // Ưu tiên 3: Fallback scrape HTML từ trang search
        android.util.Log.d("OkRuDebug", "Search: fetchVideoList empty, trying HTML scrape")
        return scrapeSearchHtml(trimmed)
    }

    /**
     * Khôi phục logic từ bản trước: tìm JSON nhúng dạng videos=[...]
     * trong HTML của trang search di động.
     */
    private suspend fun fetchVideoList(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://m.ok.ru/video/search?cmd=Search&st.query=$encoded"

        try {
            val html = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://m.ok.ru/",
                    "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                )
            ).text

            android.util.Log.d("OkRuDebug", "fetchVideoList: fetched ${html.length} chars")

            // Tìm mẫu videos=["..."] hoặc videos='...'
            val match = Regex("""videos=(['"])(.+?)\1""").find(html) ?: run {
                android.util.Log.d("OkRuDebug", "fetchVideoList: no 'videos=' pattern")
                return emptyList()
            }

            val unescaped = unescapeHtml(match.groupValues[2])
            val root = JSONObject(unescaped)

            val dataArray = root.optJSONObject("table")?.optJSONArray("data")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("videos")
                ?: run {
                    android.util.Log.d("OkRuDebug", "fetchVideoList: no data array in JSON")
                    return emptyList()
                }

            val results = mutableListOf<SearchResponse>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = item.optString("name").takeIf { it.isNotBlank() }
                    ?: item.optString("title").takeIf { it.isNotBlank() }
                    ?: "Video $id"

                val poster = getPosterFromJson(item)
                results.add(
                    newMovieSearchResponse(title, "$mainUrl/video/$id", TvType.Movie) {
                        this.posterUrl = poster
                        this.posterHeaders = posterHeaders
                    }
                )
            }
            return results
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "fetchVideoList failed", e)
            return emptyList()
        }
    }

    /**
     * Fallback: scrape HTML trực tiếp từ trang search nếu fetchVideoList thất bại.
     */
    private suspend fun scrapeSearchHtml(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/search/video?st.query=$encoded",
            "$mainUrl/search?st.query=$encoded&st.mode=Video"
        )

        for (url in urls) {
            try {
                val doc = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to mainUrl,
                        "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                    )
                ).document

                val links = doc.select("a[href*=/video/]")
                android.util.Log.d("OkRuDebug", "scrapeSearchHtml: $url → ${links.size} links")
                if (links.isEmpty()) continue

                val results = mutableListOf<SearchResponse>()
                val seen = mutableSetOf<String>()

                links.forEach { a ->
                    val href = a.attr("href").trim()
                    val m = Regex("""/video/(\d+)""").find(href) ?: return@forEach
                    val vid = m.groupValues[1]
                    if (!seen.add(vid)) return@forEach

                    val title = extractTitle(a) ?: return@forEach
                    val (poster, headers) = extractPoster(a)

                    results.add(
                        newMovieSearchResponse(title, "$mainUrl/video/$vid", TvType.Movie) {
                            this.posterUrl = poster
                            this.posterHeaders = headers
                        }
                    )
                }

                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "scrapeSearchHtml failed: $url", e)
            }
        }
        return emptyList()
    }

    private suspend fun directVideoSearch(videoId: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                "$mainUrl/video/$videoId",
                headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
            ).document

            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() } ?: "Video $videoId"
            var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
            poster = normalizePosterUrl(poster)

            listOf(
                newMovieSearchResponse(title, "$mainUrl/video/$videoId", TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = posterHeaders
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "directVideoSearch failed", e)
            emptyList()
        }
    }

    // =================================================================
    // LOAD CHI TIẾT
    // =================================================================
    override suspend fun load(url: String): LoadResponse? {
        val videoId = extractVideoId(url) ?: return null
        var title = "Video $videoId"
        var poster: String? = null

        try {
            val doc = app.get(
                "$mainUrl/video/$videoId",
                headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
            ).document
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            poster = normalizePosterUrl(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
            )
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "load failed: ${e.message}")
        }

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
            this.posterHeaders = posterHeaders
        }
    }

    // =================================================================
    // LẤY LINK STREAM — GIỮ NGUYÊN (đã hoạt động)
    // =================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = if (data.matches(Regex("""\d+"""))) data else extractVideoId(data)
        if (videoId.isNullOrBlank()) return false
        val referer = "$mainUrl/video/$videoId"

        try {
            val html = app.get(
                referer,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                )
            ).text

            val doc = Jsoup.parse(html)
            val (metadata, hlsFromRegex) = extractMetadata(doc, html)
            android.util.Log.d("OkRuDebug",
                "loadLinks: metadata=${metadata != null}, hlsRegex=${hlsFromRegex != null}")

            var success = false

            // 1. HLS (ưu tiên)
            val hlsUrl = metadata?.optString("hlsManifestUrl")?.takeIf { it.isNotBlank() }
                ?: hlsFromRegex
            if (!hlsUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(name, "HLS (tự động)", hlsUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = referer
                    }
                )
                success = true
                android.util.Log.d("OkRuDebug", "Emitted HLS OK")
            }

            // 2. MP4 fallback
            metadata?.optJSONArray("videos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val url = v.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val label = v.optString("name", "")
                    callback(
                        newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
                            this.quality = mapQuality(label)
                            this.referer = referer
                        }
                    )
                    success = true
                }
            }

            return success
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "loadLinks failed", e)
        }
        return false
    }

    // =================================================================
    // METADATA EXTRACTION
    // =================================================================
    private fun extractMetadata(doc: Document, html: String): Pair<JSONObject?, String?> {
        // Method 1: data-options
        for (el in doc.select("[data-options]")) {
            val raw = el.attr("data-options")
            if (!raw.contains("flashvars") && !raw.contains("metadata")) continue
            try {
                val unescaped = Parser.unescapeEntities(raw, true)
                val outer = JSONObject(unescaped)
                val metaStr = outer.optJSONObject("flashvars")
                    ?.optString("metadata")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val metadata = JSONObject(metaStr)
                val hls = metadata.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
                return Pair(metadata, hls)
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Method 1 failed: ${e.message}")
            }
        }

        // Method 2: OKVideo.start({...})
        for (script in doc.select("script")) {
            val data = script.data()
            val m = Regex("""OKVideo\.start\((\{.+?\})\)""").find(data) ?: continue
            try {
                val outer = JSONObject(m.groupValues[1])
                val metaStr = outer.optJSONObject("flashvars")
                    ?.optString("metadata")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val metadata = JSONObject(metaStr)
                val hls = metadata.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
                return Pair(metadata, hls)
            } catch (e: Exception) { /* ignore */ }
        }

        // Method 3: regex trên HTML
        try {
            val unescaped = Parser.unescapeEntities(html, true)
            val m = Regex("""https://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(unescaped)
            if (m != null) return Pair(null, m.value)
        } catch (e: Exception) { /* ignore */ }

        return Pair(null, null)
    }

    // =================================================================
    // POSTER EXTRACTION — Sửa lỗi ảnh không hiển thị
    // =================================================================

    /**
     * Tìm poster từ thẻ <a> và các container cha.
     * Thử lần lượt: img trong <a> → img trong parent → img trong grandparent.
     */
    private fun extractPoster(anchor: Element): Pair<String?, Map<String, String>?> {
        // Thử tìm img trong chính thẻ <a>
        var img = anchor.selectFirst("img")

        // Nếu không có, thử tìm trong parent
        if (img == null) {
            img = anchor.parent()?.selectFirst("img")
        }

        // Nếu vẫn không có, thử grandparent
        if (img == null) {
            img = anchor.parent()?.parent()?.selectFirst("img")
        }

        if (img == null) return Pair(null, null)

        val rawUrl = img.attr("src").ifEmpty {
            img.attr("data-src").ifEmpty {
                img.attr("data-original").ifEmpty { "" }
            }
        }

        val normalized = normalizePosterUrl(rawUrl) ?: return Pair(null, null)

        // Thêm header Referer để CDN ok.ru chấp nhận
        return Pair(normalized, posterHeaders)
    }

    /**
     * Lấy poster từ JSON item (dùng cho fetchVideoList).
     * Cấu trúc: thumbnails{STANDARD, FULL, ...} hoặc thumbnailsObjects{...}
     */
    private fun getPosterFromJson(item: JSONObject): String? {
        // Thử thumbnails
        item.optJSONObject("thumbnails")?.let { thumbs ->
            val keys = listOf(
                "STANDARD", "FULL", "FULL_HD_NO_CROP", "HD_NO_CROP", "HD",
                "HIGH", "MEDIUM", "SMALL"
            )
            for (k in keys) {
                val v = thumbs.optString(k, "").trim()
                if (v.isNotBlank()) return normalizePosterUrl(v)
            }
        }

        // Thử thumbnailsObjects
        item.optJSONObject("thumbnailsObjects")?.let { tObjs ->
            for (k in listOf("STANDARD", "FULL", "HD", "HIGH")) {
                val v = tObjs.optJSONObject(k)?.optString("thumbnail", "")?.trim()
                if (!v.isNullOrBlank()) return normalizePosterUrl(v)
            }
        }

        // Fallback các trường trực tiếp
        val direct = item.optString("imageUrl").takeIf { it.isNotBlank() }
            ?: item.optString("poster").takeIf { it.isNotBlank() }
            ?: item.optString("thumbnail").takeIf { it.isNotBlank() }

        return normalizePosterUrl(direct)
    }

    /**
     * Chuẩn hóa URL poster:
     * - Thêm "https:" nếu bắt đầu bằng "//"
     * - Thay `&amp;` bằng `&`
     * - Trả về null nếu rỗng
     */
    private fun normalizePosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = url.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (clean.startsWith("//")) {
            clean = "https:$clean"
        }

        return clean.takeIf { it.startsWith("http") }
    }

    // =================================================================
    // HELPERS
    // =================================================================
    private fun extractTitle(anchor: Element): String? {
        val title = anchor.attr("data-video-title").trim()
            .ifEmpty { anchor.attr("title").trim() }
            .ifEmpty { anchor.text().trim() }
            .ifEmpty {
                anchor.selectFirst("div.video-card_n, span.video-card_n")
                    ?.text()?.trim() ?: ""
            }
        return title.takeIf { it.isNotBlank() && it.length <= 200 }
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#34;", "\"")
            .replace("&#38;", "&")
            .replace("\\\"", "\"")
    }

    private fun mapQuality(label: String): Int = when {
        label.equals("mobile", true) -> Qualities.P144.value
        label.equals("lowest", true) -> Qualities.P240.value
        label.equals("low", true) -> Qualities.P360.value
        label.equals("sd", true) -> Qualities.P480.value
        label.equals("hd", true) -> Qualities.P720.value
        label.equals("full", true) -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }

    private fun extractVideoId(url: String): String? =
        Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)
}
