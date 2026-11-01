package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OK.ru"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "/video/top" to "Video Thịnh Hành",
        "/video/live" to "Trực Tiếp (Live)",
        "/video/channels" to "Kênh Phổ Biến"
    )

    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$mainUrl/"
        )
    }

    // =========================================================================
    // 1. TRANG CHỦ (MAIN PAGE) - SỬ DỤNG JSOUP SELECTOR AN TOÀN
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = "$mainUrl${request.data}"
        val response = runCatching {
            app.get(targetUrl, headers = getHeaders(), timeout = 15L).text
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val document = Jsoup.parse(response)
        val videos = mutableListOf<SearchResponse>()

        // Quét các thẻ bao bọc class video phổ biến trên cấu trúc DOM của OK.ru
        document.select("div.video-card, div.vid-card, complex-video-card, div.ugc-grid_i").forEach { element ->
            val anchor = element.select("a[href*=/video/]").firstOrNull() ?: return@forEach
            val href = anchor.attr("href")
            val id = href.substringAfter("/video/").substringBefore("?").substringBefore("/")
            
            if (id.isNotBlank() && !id.contains("search") && !id.contains("top")) {
                val title = element.select(".video-card_n, .vid-card_n, .video-card_title, a.textWrap").text().takeIf { it.isNotBlank() }
                    ?: anchor.text().takeIf { it.isNotBlank() }
                    ?: "Video $id"

                var thumb = element.select("img.video-card_img, img.vid-card_img, img").attr("src")
                if (thumb.startsWith("//")) {
                    thumb = "https:$thumb"
                }

                videos.add(
                    newMovieSearchResponse(
                        name = title,
                        url = "$mainUrl/video/$id",
                        type = TvType.Movie
                    ) {
                        this.posterUrl = thumb
                    }
                )
            }
        }

        return newHomePageResponse(request.name, videos.distinctBy { it.url }, hasNext = false)
    }

    // =========================================================================
    // 2. TÌM KIẾM (SEARCH)
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/video/search/$encodedQuery"

        val response = runCatching {
            app.get(searchUrl, headers = getHeaders(), timeout = 15L).text
        }.getOrNull() ?: return emptyList()

        val document = Jsoup.parse(response)
        val results = mutableListOf<SearchResponse>()

        document.select("div.video-card, div.vid-card, complex-video-card").forEach { element ->
            val anchor = element.select("a[href*=/video/]").firstOrNull() ?: return@forEach
            val href = anchor.attr("href")
            val id = href.substringAfter("/video/").substringBefore("?").substringBefore("/")

            if (id.isNotBlank()) {
                val title = element.select(".video-card_n, .vid-card_n, .video-card_title").text().takeIf { it.isNotBlank() }
                    ?: anchor.text()
                var thumb = element.select("img").attr("src")
                if (thumb.startsWith("//")) {
                    thumb = "https:$thumb"
                }

                results.add(
                    newMovieSearchResponse(
                        name = title,
                        url = "$mainUrl/video/$id",
                        type = TvType.Movie
                    ) {
                        this.posterUrl = thumb
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    // =========================================================================
    // 3. CHI TIẾT VIDEO (LOAD INFO)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfter("/video/").substringBefore("?").substringBefore("/")
        
        val response = app.get(url, headers = getHeaders()).text
        val document = Jsoup.parse(response)
        
        val title = document.select("h1.video-layer_hd, .vp-layer_info_title, title").text()
            .substringBefore(" •").replace(" - OK.RU", "").trim()
            
        val thumb = document.select("meta[property=og:image]").attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = thumb
            this.plot = "Video ID: $id phân phối trên nền tảng OK.ru"
        }
    }

    // =========================================================================
    // 4. TRÍCH XUẤT LUỒNG PHÁT VIDEO (LOAD LINKS) - ĐÃ CẬP NHẬT SIGNATURE
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isDataJob: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(data, headers = getHeaders()).text
        }.getOrNull() ?: return false

        // Tìm thuộc tính mã hóa dữ liệu phát JSON ẩn trong thẻ div đặc trưng của OK.ru
        val match = Regex("""data-options="([^"]+)"""").find(response) ?: return false
        val unescapedJson = match.groupValues[1]
            .replace("&quot;", "\"")
            .replace("&amp;", "&")

        return runCatching {
            val json = JSONObject(unescapedJson)
            val flashvars = json.optJSONObject("flashvars") ?: return false
            val metadataUrl = flashvars.optString("metadataUrl")

            if (metadataUrl.isNotBlank()) {
                // Gọi API lấy dữ liệu luồng stream từ phân vùng cấu hình hệ thống
                val metaResponse = app.post(
                    metadataUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to data
                    )
                ).text

                val metaJson = JSONObject(metaResponse)
                val videosArray = metaJson.optJSONArray("videos") ?: return false

                for (i in 0 until videosArray.length()) {
                    val videoObj = videosArray.optJSONObject(i) ?: continue
                    val streamUrl = videoObj.optString("url")
                    val nameSetting = videoObj.optString("name") // mobile, lowest, low, medium, high, highest

                    if (streamUrl.isNotBlank()) {
                        val quality = when (nameSetting.lowercase()) {
                            "mobile" -> Qualities.P240.value
                            "lowest" -> Qualities.P360.value
                            "low" -> Qualities.P480.value
                            "medium" -> Qualities.P720.value
                            "high" -> Qualities.P1080.value
                            "highest" -> Qualities.P1440.value
                            else -> Qualities.Unknown.value
                        }

                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "${this.name} - ${nameSetting.uppercase()}",
                                url = streamUrl,
                                referer = "$mainUrl/",
                                quality = quality,
                                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
                return true
            }
            false
        }.getOrDefault(false)
    }
}
