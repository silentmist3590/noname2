package com.okru

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
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

    // =================================================================
    // ⚠️ DÁN AUTHCODE CỦA BẠN VÀO ĐÂY
    // =================================================================
    private val authCode = "oRrnLKkxktrtxRO4HVuvbnuxpWEaMFa3WoA55Jfm7yD0cDWl5z_Q2ZbmlaxQrZLrWhBWhPAMW_NtpcylxxXNo-XGOLMW6TG-28vkWAzBcmX6g_XpG7AI7dVc8g3KoakkdDVGopB4h31qyLGk_g_5"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val posterHeaders = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "https://ok.ru/"
    )

    private val seeds = listOf(
        "7475662490142", "5686752709150", "7407305296414",
        "5367224338974", "5465143118366", "5402777684510"
    )

    // =================================================================
    // TRANG CHỦ — Giữ nguyên
    // =================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val seedsPerPage = 2
        val startIdx = (page - 1) * seedsPerPage
        val endIdx = minOf(startIdx + seedsPerPage, seeds.size)
        if (startIdx >= seeds.size) return null

        val seedSlice = seeds.subList(startIdx, endIdx)
        val sections = mutableListOf<HomePageList>()

        for ((offset, seedId) in seedSlice.withIndex()) {
            try {
                val doc = app.get(
                    "$mainUrl/video/$seedId",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to mainUrl,
                        "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                    )
                ).document

                val sectionSeen = mutableSetOf<String>()
                val items = mutableListOf<SearchResponse>()

                doc.select("a[href*=/video/]").forEach { a ->
                    val href = a.attr("href").trim()
                    val m = Regex("""/video/(\d+)""").find(href) ?: return@forEach
                    val vid = m.groupValues[1]
                    if (vid == seedId) return@forEach
                    if (!sectionSeen.add(vid)) return@forEach

                    val title = extractTitle(a) ?: return@forEach
                    val (poster, headers) = extractPoster(a)

                    items.add(
                        newMovieSearchResponse(title, "$mainUrl/video/$vid", TvType.Movie) {
                            this.posterUrl = poster
                            this.posterHeaders = headers
                        }
                    )
                }

                if (items.isNotEmpty()) {
                    val pageBase = (page - 1) * seedsPerPage
                    val sectionIdx = pageBase + offset
                    val label = when (sectionIdx) {
                        0 -> "Đề xuất cho bạn"
                        1 -> "Phổ biến"
                        2 -> "Mới cập nhật"
                        3 -> "Được xem nhiều"
                        else -> "Khám phá ${sectionIdx + 1}"
                    }
                    sections.add(HomePageList(label, items))
                }
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Seed $seedId failed", e)
            }
        }

        if (sections.isEmpty()) return null
        val hasNext = endIdx < seeds.size
        return newHomePageResponse(sections, hasNext)
    }

    // =================================================================
    // TÌM KIẾM — Dùng endpoint AJAX + cookie AUTHCODE
    // =================================================================
    override suspend fun search(query: String): List<SearchResponse>? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // Kiểm tra AUTHCODE đã được cấu hình chưa
        if (authCode == "DÁN_AUTHCODE_CỦA_BẠN_VÀO_ĐÂY" || authCode.isBlank()) {
            android.util.Log.e("OkRuDebug", "AUTHCODE chưa được cấu hình!")
        }

        // Xử lý URL/ID video trực tiếp
        val directMatch = Regex("""(?:/video/)?(\d{6,})""").find(trimmed)
        if (directMatch != null &&
            (trimmed.contains("/video/") || trimmed.matches(Regex("""\d{6,}""")))) {
            return directVideoSearch(directMatch.groupValues[1])
        }

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val all = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // Lặp qua nhiều trang
        for (page in 1..5) {
            val url = "$mainUrl/dk?st.cmd=videoSearch&st.query=$encoded&st.page=$page"
            var addedThisPage = 0

            try {
                val response = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$mainUrl/video/search?st.query=$encoded",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Cookie" to "AUTHCODE=$authCode",   // ← Cookie xác thực
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                    )
                )

                val body = response.text
                android.util.Log.d("OkRuDebug",
                    "Search p$page: body length=${body.length}, preview=${body.take(200)}")

                if (body.isBlank()) {
                    android.util.Log.e("OkRuDebug", "Search p$page: empty body → stop")
                    break
                }

                // Ưu tiên 1: Parse JSON
                val jsonItems = parseSearchJson(body)
                if (jsonItems.isNotEmpty()) {
                    for (item in jsonItems) {
                        if (seen.add(item.url)) {
                            all.add(item)
                            addedThisPage++
                        }
                    }
                    android.util.Log.d("OkRuDebug", "Search p$page: JSON → $addedThisPage new")
                }

                // Ưu tiên 2: Fallback scrape HTML (nếu JSON không có)
                if (jsonItems.isEmpty()) {
                    val htmlItems = scrapeSearchHtml(body)
                    for (item in htmlItems) {
                        if (seen.add(item.url)) {
                            all.add(item)
                            addedThisPage++
                        }
                    }
                    android.util.Log.d("OkRuDebug", "Search p$page: HTML → $addedThisPage new")
                }

                if (addedThisPage == 0) {
                    android.util.Log.d("OkRuDebug", "Search p$page: no new items → stop")
                    break
                }
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Search p$page failed", e)
                break
            }
        }

        android.util.Log.d("OkRuDebug", "Search '$trimmed' total=${all.size}")
        return all
    }

    /**
     * Parse JSON từ endpoint AJAX.
     * Cấu trúc có thể là:
     *   - JSON array trực tiếp: [{...}, {...}]
     *   - Object có key "videos": {"videos": [...]}
     *   - Object có key "data": {"data": [...]}
     */
    private fun parseSearchJson(body: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            // Cắt lấy phần JSON nếu body có prefix/suffix
            val startArr = body.indexOf('[')
            val startObj = body.indexOf('{')
            val start = when {
                startArr == -1 -> startObj
                startObj == -1 -> startArr
                else -> minOf(startArr, startObj)
            }
            if (start == -1) return emptyList()

            val endArr = body.lastIndexOf(']')
            val endObj = body.lastIndexOf('}')
            val end = maxOf(endArr, endObj)
            if (end <= start) return emptyList()

            val jsonStr = body.substring(start, end + 1)
            android.util.Log.d("OkRuDebug", "parseSearchJson: ${jsonStr.take(100)}")

            val array: JSONArray? = when {
                jsonStr.startsWith("[") -> try { JSONArray(jsonStr) } catch (e: Exception) { null }
                jsonStr.startsWith("{") -> {
                    val obj = JSONObject(jsonStr)
                    obj.optJSONArray("videos")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONObject("videos")?.optJSONArray("videos")
                        ?: obj.optJSONArray("list")
                }
                else -> null
            }

            if (array == null) {
                android.util.Log.d("OkRuDebug", "parseSearchJson: no array found in JSON")
                return emptyList()
            }

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue

                val id = obj.optString("id").takeIf { it.isNotBlank() }
                    ?: obj.optString("movieId").takeIf { it.isNotBlank() }
                    ?: obj.optString("videoId").takeIf { it.isNotBlank() }
                    ?: obj.optString("vid").takeIf { it.isNotBlank() }
                    ?: continue

                val title = obj.optString("name").takeIf { it.isNotBlank() }
                    ?: obj.optString("title").takeIf { it.isNotBlank() }
                    ?: obj.optString("movieTitle").takeIf { it.isNotBlank() }
                    ?: "Video $id"

                val poster = obj.optString("imageUrl").takeIf { it.isNotBlank() }
                    ?: obj.optString("poster").takeIf { it.isNotBlank() }
                    ?: obj.optString("thumbnail").takeIf { it.isNotBlank() }
                    ?: obj.optJSONObject("thumbnails")?.optString("STANDARD")?.takeIf { it.isNotBlank() }

                results.add(
                    newMovieSearchResponse(title, "$mainUrl/video/$id", TvType.Movie) {
                        this.posterUrl = normalizePosterUrl(poster)
                        this.posterHeaders = posterHeaders
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "parseSearchJson failed: ${e.message}")
        }
        return results
    }

    private fun scrapeSearchHtml(html: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val doc = Jsoup.parse(html)
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
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "scrapeSearchHtml failed", e)
        }
        return results.distinctBy { it.url }
    }

    private suspend fun directVideoSearch(videoId: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                "$mainUrl/video/$videoId",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to mainUrl,
                    "Cookie" to "AUTHCODE=$authCode"
                )
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
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to mainUrl,
                    "Cookie" to "AUTHCODE=$authCode"
                )
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
    // LẤY LINK STREAM — Giữ nguyên (đang hoạt động)
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
                    "Cookie" to "AUTHCODE=$authCode",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                )
            ).text

            val doc = Jsoup.parse(html)
            val (metadata, hlsFromRegex) = extractMetadata(doc, html)
            var success = false

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
            }

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
                return Pair(metadata, hls)
            } catch (e: Exception) { /* ignore */ }
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
                return Pair(metadata, hls)
            } catch (e: Exception) { /* ignore */ }
        }
        try {
            val unescaped = Parser.unescapeEntities(html, true)
            val m = Regex("""https://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(unescaped)
            if (m != null) return Pair(null, m.value)
        } catch (e: Exception) { /* ignore */ }
        return Pair(null, null)
    }

    // =================================================================
    // HELPERS
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

    private fun normalizePosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = url.trim().replace("&amp;", "&").replace("\\/", "/")
        if (clean.startsWith("//")) clean = "https:$clean"
        return clean.takeIf { it.startsWith("http") }
    }

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
