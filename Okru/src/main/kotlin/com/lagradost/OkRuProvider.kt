package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup

@CloudstreamPlugin
class OkRuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OkRuProvider())
        registerExtractorAPI(OkRuExtractor())
    }
}

/**
 * Extractor chuyên dụng bóc tách link video từ OK.ru
 */
class OkRuExtractor : ExtractorApi() {
    override val name = "OK.ru"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = true

    companion object {
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://ok.ru/"
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("""(?:video|videoembed)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""(\d{8,})""").find(url)?.groupValues?.get(1)
            ?: return

        val embedUrl = "https://ok.ru/videoembed/$videoId"
        val response = app.get(embedUrl, headers = streamHeaders).text
        val optionsMatch = Regex("""data-options="([^"]+)"""").find(response)?.groupValues?.get(1) ?: return

        val decodedJson = optionsMatch
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        val rootJson = try {
            JSONObject(decodedJson)
        } catch (e: Exception) {
            return
        }

        val flashvars = rootJson.optJSONObject("flashvars") ?: return

        val metaJson: JSONObject? = if (flashvars.has("metadata")) {
            val metaRaw = flashvars.optString("metadata")
            try {
                JSONObject(metaRaw)
            } catch (e: Exception) {
                null
            }
        } else if (flashvars.has("metadataUrl")) {
            val metaUrl = flashvars.optString("metadataUrl")
            if (metaUrl.isNotBlank()) {
                val metaText = app.get(metaUrl, headers = streamHeaders).text
                try {
                    JSONObject(metaText)
                } catch (e: Exception) {
                    null
                }
            } else null
        } else null

        if (metaJson == null) return

        // Danh sách thứ tự ưu tiên độ phân giải cao nhất trước
        val qualityOrder = listOf("ultra", "quad", "full", "hd", "sd", "low", "lowest", "mobile")

        // 1. Ưu tiên phát MP4 trực tiếp chất lượng cao (1080p, 720p, 480p) - phát ngay tức thì không bị lỗi
        val videos = metaJson.optJSONArray("videos")
        if (videos != null) {
            val videoList = mutableListOf<JSONObject>()
            for (i in 0 until videos.length()) {
                videos.optJSONObject(i)?.let { videoList.add(it) }
            }

            // Sắp xếp video từ độ phân giải cao nhất xuống thấp nhất
            videoList.sortBy { item ->
                val qName = item.optString("name", "").lowercase()
                val idx = qualityOrder.indexOf(qName)
                if (idx != -1) idx else 99
            }

            for (vid in videoList) {
                val qualityName = vid.optString("name", "").lowercase()
                val vidUrl = vid.optString("url", "")
                if (vidUrl.isBlank()) continue

                val quality = when (qualityName) {
                    "ultra" -> Qualities.P2160.value
                    "quad" -> Qualities.P1440.value
                    "full" -> Qualities.P1080.value
                    "hd" -> Qualities.P720.value
                    "sd" -> Qualities.P480.value
                    "low" -> Qualities.P360.value
                    "lowest" -> Qualities.P240.value
                    "mobile" -> Qualities.P144.value
                    else -> Qualities.Unknown.value
                }

                val label = when (qualityName) {
                    "ultra" -> "2160p 4K"
                    "quad" -> "1440p 2K"
                    "full" -> "1080p Full HD"
                    "hd" -> "720p HD"
                    "sd" -> "480p SD"
                    "low" -> "360p"
                    "lowest" -> "240p"
                    "mobile" -> "144p"
                    else -> qualityName.uppercase()
                }

                val isM3u8 = vidUrl.contains(".m3u8", ignoreCase = true)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name $label",
                        url = vidUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = quality
                        this.headers = streamHeaders
                    }
                )
            }
        }

        // 2. Link HLS Master Playlist làm nguồn dự phòng ở cuối
        val hlsUrl = metaJson.optString("hlsManifestUrl")
        if (hlsUrl.isNotBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name (Auto HLS)",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://ok.ru/"
                    this.quality = Qualities.Unknown.value
                    this.headers = streamHeaders
                }
            )
        }
    }
}

/**
 * MainAPI cho OK.ru: Màn hình chính nhiều danh mục, xem video và tìm kiếm
 */
class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OK.ru"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others, TvType.Movie)

    private val extractor = OkRuExtractor()

    private val desktopHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://ok.ru/video"
    )

    private val mobileHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
        "Accept-Language" to "vi,en-US;q=0.9,en;q=0.8"
    )

    // Khai báo 6 hàng chuyên mục phong phú
    override val mainPage = mainPageOf(
        "1" to "🔥 Thịnh hành hôm nay",
        "2" to "⚡ Video nổi bật",
        "3" to "🌟 Xem nhiều nhất",
        "4" to "🎬 Khám phá video mới",
        "5" to "📺 Kênh phổ biến",
        "6" to "✨ Đề xuất cho bạn"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val basePageOffset = request.data.toIntOrNull() ?: 1
        val actualPage = (basePageOffset - 1) * 2 + page

        val homeItems = mutableListOf<SearchResponse>()

        // Tải 2 trang liên tiếp để mỗi danh mục có từ 15 đến 20 video
        for (p in actualPage..(actualPage + 1)) {
            val targetUrl = "https://m.ok.ru/video?st.page=$p"
            try {
                val response = app.get(targetUrl, headers = mobileHeaders).text
                val videosAttrMatch = Regex("""videos="([^"]+)"""").find(response)?.groupValues?.get(1)
                if (videosAttrMatch != null) {
                    val decoded = videosAttrMatch
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&#39;", "'")
                    val rootJson = JSONObject(decoded)
                    val data = rootJson.optJSONObject("table")?.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            val id = item.optString("id")
                            if (id.isBlank()) continue
                            val videoUrl = "https://ok.ru/video/$id"
                            val title = item.optString("name").ifBlank { "OK.ru Video #$id" }

                            val thumbs = item.optJSONObject("thumbnails")
                            val poster = thumbs?.optString("STANDARD")?.takeIf { it.isNotBlank() }
                                ?: thumbs?.optString("HIGH")?.takeIf { it.isNotBlank() }
                                ?: thumbs?.optString("MEDIUM")?.takeIf { it.isNotBlank() }
                                ?: thumbs?.optString("UPLOADED_IMAGE")?.takeIf { it.isNotBlank() }
                                ?: thumbs?.optString("SMALL")

                            if (homeItems.none { it.url == videoUrl }) {
                                homeItems.add(
                                    newMovieSearchResponse(title, videoUrl, TvType.Movie) {
                                        this.posterUrl = poster
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems)),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val searchResults = mutableListOf<SearchResponse>()

        // 1. Hỗ trợ người dùng dán trực tiếp link hoặc ID video
        val directMatch = Regex("""(?:video|videoembed)/(\d+)""").find(cleanQuery)
            ?: Regex("""^(\d{8,})$""").find(cleanQuery)

        if (directMatch != null) {
            val id = directMatch.groupValues[1]
            val videoUrl = "https://ok.ru/video/$id"

            var title = "OK.ru Video #$id"
            var poster: String? = null
            try {
                val embedUrl = "https://ok.ru/videoembed/$id"
                val embedRes = app.get(embedUrl, headers = desktopHeaders).text
                val optMatch = Regex("""data-options="([^"]+)"""").find(embedRes)?.groupValues?.get(1)
                if (optMatch != null) {
                    val dec = optMatch.replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
                    val root = JSONObject(dec)
                    val flashvars = root.optJSONObject("flashvars")
                    if (flashvars?.has("metadata") == true) {
                        val meta = JSONObject(flashvars.optString("metadata"))
                        meta.optJSONObject("movie")?.let { m ->
                            m.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
                            m.optString("poster").takeIf { it.isNotBlank() }?.let { poster = it }
                        }
                    }
                }
            } catch (ignored: Exception) {}

            return listOf(
                newMovieSearchResponse(title, videoUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }

        // 2. Tìm kiếm qua giao diện Desktop của OK.ru
        val searchUrl = "https://ok.ru/video/search?st.query=${cleanQuery.encodeUrl()}"
        try {
            val response = app.get(searchUrl, headers = desktopHeaders).text

            // Bóc tách thẻ video: data-id và img
            val cardRegex = Regex("""<div[^>]*class="[^"]*video-card[^"]*"[^>]*data-id="(\d+)"[\s\S]*?<img[^>]*src="([^"]+)"[^>]*alt="([^"]*)"""")
            val matches = cardRegex.findAll(response)

            for (m in matches) {
                val id = m.groupValues[1]
                val posterRaw = m.groupValues[2].replace("&amp;", "&")
                val titleRaw = m.groupValues[3].ifBlank { "OK.ru Video #$id" }
                val videoUrl = "https://ok.ru/video/$id"

                if (searchResults.none { it.url == videoUrl }) {
                    searchResults.add(
                        newMovieSearchResponse(titleRaw.trim(), videoUrl, TvType.Movie) {
                            this.posterUrl = posterRaw
                        }
                    )
                }
            }
        } catch (ignored: Exception) {}

        // 3. Dự phòng qua giao diện Mobile nếu Desktop chưa có kết quả
        if (searchResults.isEmpty()) {
            try {
                val mobUrl = "https://m.ok.ru/video?st.page=1"
                val mobRes = app.get(mobUrl, headers = mobileHeaders).text
                val vMatch = Regex("""videos="([^"]+)"""").find(mobRes)?.groupValues?.get(1)
                if (vMatch != null) {
                    val dec = vMatch.replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
                    val rootJson = JSONObject(dec)
                    val data = rootJson.optJSONObject("table")?.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            val title = item.optString("name", "")
                            val id = item.optString("id", "")
                            if (id.isBlank()) continue

                            val thumbs = item.optJSONObject("thumbnails")
                            val poster = thumbs?.optString("STANDARD") ?: thumbs?.optString("MEDIUM")

                            val videoUrl = "https://ok.ru/video/$id"
                            if (searchResults.none { it.url == videoUrl }) {
                                searchResults.add(
                                    newMovieSearchResponse(title.ifBlank { "Video $id" }, videoUrl, TvType.Movie) {
                                        this.posterUrl = poster
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = Regex("""(?:video|videoembed)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""(\d{8,})""").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Link video OK.ru không hợp lệ!")

        val embedUrl = "https://ok.ru/videoembed/$videoId"
        val response = app.get(embedUrl, headers = desktopHeaders).text

        var title = "OK.ru Video #$videoId"
        var poster: String? = null
        var plot: String? = null
        var duration: Int? = null

        val optionsMatch = Regex("""data-options="([^"]+)"""").find(response)?.groupValues?.get(1)
        if (optionsMatch != null) {
            val decoded = optionsMatch
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#39;", "'")

            try {
                val root = JSONObject(decoded)
                val flashvars = root.optJSONObject("flashvars")
                if (flashvars != null) {
                    val metaJson = if (flashvars.has("metadata")) {
                        JSONObject(flashvars.optString("metadata"))
                    } else if (flashvars.has("metadataUrl")) {
                        val metaUrl = flashvars.optString("metadataUrl")
                        if (metaUrl.isNotBlank()) {
                            JSONObject(app.get(metaUrl, headers = desktopHeaders).text)
                        } else null
                    } else null

                    if (metaJson != null) {
                        val movie = metaJson.optJSONObject("movie")
                        if (movie != null) {
                            movie.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
                            movie.optString("poster").takeIf { it.isNotBlank() }?.let { poster = it }
                            movie.optString("description").takeIf { it.isNotBlank() }?.let { plot = it }
                            val dur = movie.optInt("duration", 0)
                            if (dur > 0) duration = dur / 60
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        // Fallback lấy tiêu đề và poster từ thẻ meta HTML
        if (title == "OK.ru Video #$videoId") {
            val doc = Jsoup.parse(response)
            val docTitle = doc.select("meta[property=og:title], meta[name=title]").attr("content")
            if (docTitle.isNotBlank()) title = docTitle
            if (poster == null) {
                val docPoster = doc.select("meta[property=og:image], link[rel=image_src]").attr("content").ifBlank {
                    doc.select("link[rel=image_src]").attr("href")
                }
                if (docPoster.isNotBlank()) poster = docPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        extractor.getUrl(data, mainUrl, subtitleCallback, callback)
        return true
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
