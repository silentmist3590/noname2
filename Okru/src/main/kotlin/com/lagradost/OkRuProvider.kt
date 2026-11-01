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

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val posterHeaders = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "https://ok.ru/"
    )

    // Danh sách seed cho trang chủ (giữ nguyên)
    private val seeds = listOf(
        "7475662490142", "5686752709150", "7407305296414",
        "5367224338974", "5465143118366", "5402777684510"
    )

    // =================================================================
    // TRANG CHỦ — Giữ nguyên logic hiện tại (ưu tiên tìm kiếm)
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
    // TÌM KIẾM — Ưu tiên số 1: Dùng endpoint AJAX nội bộ
    // =================================================================
    override suspend fun search(query: String): List<SearchResponse>? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // Xử lý URL/ID trực tiếp (giữ nguyên)
        val directMatch = Regex("""(?:/video/)?(\d{6,})""").find(trimmed)
        if (directMatch != null &&
            (trimmed.contains("/video/") || trimmed.matches(Regex("""\d{6,}""")))) {
            return directVideoSearch(directMatch.groupValues[1])
        }

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val all = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (page in 1..5) {
            val url = "$mainUrl/dk?st.cmd=videoSearch&st.query=$encoded&st.page=$page"
            try {
                val response = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$mainUrl/video/search?st.query=$encoded",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                    )
                )
                val body = response.text
                android.util.Log.d("OkRuDebug", "Search p$page body length: ${body.length}")

                // Thử parse JSON trước
                val jsonItems = parseSearchJson(body)
                if (jsonItems.isNotEmpty()) {
                    for (item in jsonItems) {
                        if (seen.add(item.url)) all.add(item)
                    }
                    android.util.Log.d("OkRuDebug", "Search p$page JSON → ${jsonItems.size}")
                    continue
                }

                // Fallback scrape HTML
                val htmlItems = scrapeSearchHtml(body)
                for (item in htmlItems) {
                    if (seen.add(item.url)) all.add(item)
                }
                android.util.Log.d("OkRuDebug", "Search p$page HTML → ${htmlItems.size}")

                if (jsonItems.isEmpty() && htmlItems.isEmpty()) break
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Search p$page failed", e)
            }
        }

        android.util.Log.d("OkRuDebug", "Search '$trimmed' total=${all.size}")
        return all
    }

    // =================================================================
    // HỖ TRỢ TÌM KIẾM
    // =================================================================
    private fun parseSearchJson(body: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val start = body.indexOf('[')
            val end = body.lastIndexOf(']')
            if (start == -1 || end == -1 || start >= end) return emptyList()

            val jsonStr = body.substring(start, end + 1)
            val arr = JSONArray(jsonStr)

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() }
                    ?: obj.optString("videoId").takeIf { it.isNotBlank() }
                    ?: obj.optString("movieId").takeIf { it.isNotBlank() }
                    ?: continue
                val title = obj.optString("name").takeIf { it.isNotBlank() }
                    ?: obj.optString("title").takeIf { it.isNotBlank() }
                    ?: "Video $id"
                val poster = obj.optString("imageUrl").takeIf { it.isNotBlank() }
                    ?: obj.optString("poster").takeIf { it.isNotBlank() }
                    ?: obj.optString("thumbnail").takeIf { it.isNotBlank() }

                results.add(
                    newMovieSearchResponse(title, "$mainUrl/video/$id", TvType.Movie) {
                        this.posterUrl = poster
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
        return results
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
    // HELPERS (giữ nguyên)
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
