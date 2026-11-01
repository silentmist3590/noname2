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

@CloudstreamPlugin
class OkRuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OkRuProvider())
    }
}

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

    // Chỉ dùng 6 seed đã xác nhận hoạt động (tất cả từ kênh oknutye)
    private val seeds = listOf(
        "7475662490142", "5686752709150", "7407305296414",
        "5367224338974", "5465143118366", "5402777684510"
    )

    // =================================================================
    // TRANG CHỦ — per-section dedup, hasNext theo seed
    // =================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Mỗi page dùng 2 seed (thay vì 3) để đảm bảo luôn có 2 section
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

                // Per-section dedup: mỗi section có Set riêng
                val sectionSeen = mutableSetOf<String>()
                val items = mutableListOf<SearchResponse>()

                doc.select("a[href*=/video/]").forEach { a ->
                    val href = a.attr("href").trim()
                    val m = Regex("""/video/(\d+)""").find(href) ?: return@forEach
                    val vid = m.groupValues[1]
                    if (vid == seedId) return@forEach
                    if (!sectionSeen.add(vid)) return@forEach  // Chỉ dedup trong section

                    val title = extractTitle(a) ?: return@forEach
                    val (poster, headers) = extractPoster(a)

                    items.add(
                        newMovieSearchResponse(title, "$mainUrl/video/$vid", TvType.Movie) {
                            this.posterUrl = poster
                            this.posterHeaders = headers
                        }
                    )
                }

                android.util.Log.d("OkRuDebug",
                    "MainPage p$page seed=$seedId → ${items.size} items")

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
        android.util.Log.d("OkRuDebug",
            "MainPage p$page: sections=${sections.size}, hasNext=$hasNext")
        return newHomePageResponse(sections, hasNext)
    }

    // =================================================================
    // TÌM KIẾM — 2 URL đúng, bỏ JSON layer gây nhiễu
    // =================================================================
    override suspend fun search(query: String): List<SearchResponse>? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // Xử lý URL/ID trực tiếp
        val directMatch = Regex("""(?:/video/)?(\d{6,})""").find(trimmed)
        if (directMatch != null &&
            (trimmed.contains("/video/") || trimmed.matches(Regex("""\d{6,}""")))) {
            return directVideoSearch(directMatch.groupValues[1])
        }

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val all = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (page in 1..5) {
            var addedThisPage = 0
            var workedUrl: String? = null

            // Thử 2 URL đúng của ok.ru
            val urls = listOf(
                "$mainUrl/search/video?st.query=$encoded&st.page=$page",
                "$mainUrl/search?st.query=$encoded&st.mode=Video&st.page=$page"
            )

            for (url in urls) {
                try {
                    val items = scrapeSearchPage(url)
                    android.util.Log.d("OkRuDebug",
                        "Search p$page $url → ${items.size}")

                    for (item in items) {
                        if (seen.add(item.url)) {
                            all.add(item)
                            addedThisPage++
                        }
                    }
                    if (items.isNotEmpty()) {
                        workedUrl = url
                        break  // URL này hoạt động, dùng cho các page sau
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OkRuDebug", "Search URL fail: $url", e)
                }
            }

            if (addedThisPage == 0) {
                android.util.Log.d("OkRuDebug", "Search p$page: no new items, stop")
                break
            }
            android.util.Log.d("OkRuDebug",
                "Search p$page added=$addedThisPage, url=$workedUrl")
        }

        android.util.Log.d("OkRuDebug", "Search '$trimmed' total=${all.size}")
        return all
    }

    private suspend fun scrapeSearchPage(url: String): List<SearchResponse> {
        val doc = app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
            )
        ).document

        // Chỉ trả về nếu HTML thực sự có link video
        val videoLinks = doc.select("a[href*=/video/]")
        if (videoLinks.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        videoLinks.forEach { a ->
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
