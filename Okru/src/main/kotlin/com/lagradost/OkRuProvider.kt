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

    // Danh sách seed mở rộng để hỗ trợ phân trang
    private val seeds = listOf(
        "7475662490142", "5686752709150", "7407305296414",
        "5367224338974", "5465143118366", "5402777684510",
        "7475662490150", "7475662490151", "7475662490152"
    )

    // =================================================================
    // TRANG CHỦ — Hỗ trợ tải thêm (hasNext)
    // =================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Mỗi page lấy 3 seed khác nhau
        val seedsPerPage = 3
        val startIdx = (page - 1) * seedsPerPage
        val endIdx = minOf(startIdx + seedsPerPage, seeds.size)
        if (startIdx >= seeds.size) return null

        val seedSlice = seeds.subList(startIdx, endIdx)
        val seen = mutableSetOf<String>()
        val sections = mutableListOf<HomePageList>()

        for (seedId in seedSlice) {
            try {
                val doc = app.get(
                    "$mainUrl/video/$seedId",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to mainUrl,
                        "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                    )
                ).document

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

                android.util.Log.d("OkRuDebug", "MainPage page=$page seed=$seedId → ${items.size}")
                if (items.isNotEmpty()) {
                    val label = when (page) {
                        1 -> "Đề xuất cho bạn"
                        2 -> "Phổ biến"
                        3 -> "Mới nhất"
                        else -> "Khám phá"
                    }
                    sections.add(HomePageList(label, items))
                }
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Seed $seedId failed", e)
            }
        }

        if (sections.isEmpty()) return null

        // hasNext = true nếu còn seed chưa dùng
        val hasNext = endIdx < seeds.size
        android.util.Log.d("OkRuDebug", "MainPage page=$page done, hasNext=$hasNext, sections=${sections.size}")
        return newHomePageResponse(sections, hasNext)
    }

    // =================================================================
    // TÌM KIẾM — Kết hợp 3 lớp + lặp trang
    // =================================================================
    override suspend fun search(query: String): List<SearchResponse>? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // Lớp 0: Nếu là URL/ID video → xử lý trực tiếp
        val directMatch = Regex("""(?:/video/)?(\d{6,})""").find(trimmed)
        if (directMatch != null &&
            (trimmed.contains("/video/") || trimmed.matches(Regex("""\d{6,}""")))) {
            val videoId = directMatch.groupValues[1]
            android.util.Log.d("OkRuDebug", "Search: direct video ID $videoId")
            return directVideoSearch(videoId)
        }

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val all = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // Lặp 5 trang
        for (page in 1..5) {
            var addedThisPage = 0

            // Lớp 1: JSON nhúng từ m.ok.ru
            val jsonUrl = "https://m.ok.ru/video/search?cmd=Search&st.query=$encoded&st.page=$page"
            try {
                val jsonItems = fetchFromJsonPage(jsonUrl)
                for (item in jsonItems) {
                    if (seen.add(item.url)) {
                        all.add(item)
                        addedThisPage++
                    }
                }
                if (jsonItems.isNotEmpty()) {
                    android.util.Log.d("OkRuDebug", "Search p$page JSON → ${jsonItems.size}")
                    continue
                }
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Search p$page JSON failed", e)
            }

            // Lớp 2: Scrape HTML desktop
            val htmlUrl = "$mainUrl/video/search?st.query=$encoded&st.page=$page"
            try {
                val htmlItems = scrapeSearchPage(htmlUrl)
                for (item in htmlItems) {
                    if (seen.add(item.url)) {
                        all.add(item)
                        addedThisPage++
                    }
                }
                android.util.Log.d("OkRuDebug", "Search p$page HTML → ${htmlItems.size}")
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Search p$page HTML failed", e)
            }

            // Nếu cả 2 lớp đều không thêm gì → hết dữ liệu
            if (addedThisPage == 0) break
        }

        android.util.Log.d("OkRuDebug", "Search '$trimmed' total=${all.size}")
        return all
    }

    /**
     * Lớp 1: Tìm JSON nhúng `videos=["..."]` trong HTML mobile.
     */
    private suspend fun fetchFromJsonPage(url: String): List<SearchResponse> {
        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://m.ok.ru/",
                "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
            )
        ).text

        val match = Regex("""videos=(['"])(.+?)\1""").find(html) ?: return emptyList()
        val unescaped = unescapeHtml(match.groupValues[2])
        val root = JSONObject(unescaped)

        val dataArray = root.optJSONObject("table")?.optJSONArray("data")
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("videos")
            ?: return emptyList()

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
    }

    /**
     * Lớp 2: Scrape HTML desktop.
     */
    private suspend fun scrapeSearchPage(url: String): List<SearchResponse> {
        val doc = app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl,
                "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
            )
        ).document

        val results = mutableListOf<SearchResponse>()
        doc.select("a[href*=/video/]").forEach { a ->
            val href = a.attr("href").trim()
            val m = Regex("""/video/(\d+)""").find(href) ?: return@forEach
            val vid = m.groupValues[1]
            val title = extractTitle(a) ?: return@forEach
            val (poster, headers) = extractPoster(a)
            results.add(
                newMovieSearchResponse(title, "$mainUrl/video/$vid", TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = headers
                }
            )
        }
        return results.distinctBy { it.url }
    }

    private suspend fun directVideoSearch(videoId: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                "$mainUrl/video/$videoId",
                headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
            ).document
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() } ?: "Video $videoId"
            val poster = normalizePosterUrl(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
            )
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
    // LẤY LINK STREAM — GIỮ NGUYÊN (đang hoạt động)
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
                android.util.Log.d("OkRuDebug", "Method 1 (data-options) OK")
                return Pair(metadata, hls)
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Method 1 failed: ${e.message}")
            }
        }

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
                android.util.Log.d("OkRuDebug", "Method 2 (script) OK")
                return Pair(metadata, hls)
            } catch (e: Exception) { /* ignore */ }
        }

        try {
            val unescaped = Parser.unescapeEntities(html, true)
            val m = Regex("""https://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(unescaped)
            if (m != null) {
                android.util.Log.d("OkRuDebug", "Method 3 (regex m3u8) OK")
                return Pair(null, m.value)
            }
        } catch (e: Exception) { /* ignore */ }

        return Pair(null, null)
    }

    // =================================================================
    // POSTER EXTRACTION
    // =================================================================
    private fun extractPoster(anchor: Element): Pair<String?, Map<String, String>?> {
        var img = anchor.selectFirst("img")
        if (img == null) img = anchor.parent()?.selectFirst("img")
        if (img == null) img = anchor.parent()?.parent()?.selectFirst("img")
        if (img == null) return Pair(null, null)

        val rawUrl = img.attr("src").ifEmpty {
            img.attr("data-src").ifEmpty { img.attr("data-original").ifEmpty { "" } }
        }
        val normalized = normalizePosterUrl(rawUrl) ?: return Pair(null, null)
        return Pair(normalized, posterHeaders)
    }

    private fun getPosterFromJson(item: JSONObject): String? {
        item.optJSONObject("thumbnails")?.let { thumbs ->
            for (k in listOf("STANDARD", "FULL", "FULL_HD_NO_CROP", "HD_NO_CROP",
                "HD", "HIGH", "MEDIUM", "SMALL")) {
                val v = thumbs.optString(k, "").trim()
                if (v.isNotBlank()) return normalizePosterUrl(v)
            }
        }
        item.optJSONObject("thumbnailsObjects")?.let { tObjs ->
            for (k in listOf("STANDARD", "FULL", "HD", "HIGH")) {
                val v = tObjs.optJSONObject(k)?.optString("thumbnail", "")?.trim()
                if (!v.isNullOrBlank()) return normalizePosterUrl(v)
            }
        }
        val direct = item.optString("imageUrl").takeIf { it.isNotBlank() }
            ?: item.optString("poster").takeIf { it.isNotBlank() }
            ?: item.optString("thumbnail").takeIf { it.isNotBlank() }
        return normalizePosterUrl(direct)
    }

    private fun normalizePosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = url.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
        if (clean.startsWith("//")) clean = "https:$clean"
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
