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
        // Đăng ký cả Provider (giao diện xem/tìm kiếm) và Extractor (bóc tách link)
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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("""(?:video|videoembed)/(\d+)""").find(url)?.groupValues?.get(1) ?: return
        val embedUrl = "https://ok.ru/videoembed/$videoId"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://ok.ru/"
        )

        val response = app.get(embedUrl, headers = headers).text
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
                val metaText = app.get(metaUrl, headers = headers).text
                try {
                    JSONObject(metaText)
                } catch (e: Exception) {
                    null
                }
            } else null
        } else null

        if (metaJson == null) return

        // 1. Lấy link HLS Master Playlist (m3u8) nếu có
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
                }
            )
        }

        // 2. Lấy danh sách video MP4 theo từng độ phân giải
        val videos = metaJson.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val vid = videos.optJSONObject(i) ?: continue
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
                    }
                )
            }
        }
    }
}

/**
 * MainAPI cho OK.ru: Hỗ trợ tìm kiếm, duyệt video và dán link trực tiếp
 */
class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OK.ru"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others, TvType.Movie)

    private val extractor = OkRuExtractor()

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://ok.ru/"
    )

    private val mobileHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
        "Accept-Language" to "vi,en-US;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "https://m.ok.ru/video" to "Thịnh hành / Mới nhất"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = mobileHeaders).document
        val homeItems = mutableListOf<SearchResponse>()

        document.select("a[href*=/video/]").forEach { element ->
            val href = element.attr("href")
            val idMatch = Regex("""/video/(\d+)""").find(href)?.groupValues?.get(1) ?: return@forEach
            val videoUrl = "https://ok.ru/video/$idMatch"

            val title = element.select("div.va-title, div.ellip, span.video-card_n, img[alt]").text().ifBlank {
                element.attr("title").ifBlank { "OK.ru Video #$idMatch" }
            }
            val poster = element.select("img").attr("src").ifBlank {
                element.select("img").attr("data-src")
            }

            if (homeItems.none { it.url == videoUrl }) {
                homeItems.add(
                    newMovieSearchResponse(title, videoUrl, TvType.Movie) {
                        this.posterUrl = poster.ifBlank { null }
                    }
                )
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()

        // Hỗ trợ dán trực tiếp link ok.ru vào ô tìm kiếm: ok.ru/video/123456...
        val directMatch = Regex("""(?:video|videoembed)/(\d+)""").find(cleanQuery)
        if (directMatch != null) {
            val id = directMatch.groupValues[1]
            val url = "https://ok.ru/video/$id"
            return listOf(
                newMovieSearchResponse("OK.ru Video: $id", url, TvType.Movie)
            )
        }

        val searchUrl = "https://m.ok.ru/video/search?query=${cleanQuery.encodeUrl()}"
        val response = app.get(searchUrl, headers = mobileHeaders).text
        val document = Jsoup.parse(response)
        val searchResults = mutableListOf<SearchResponse>()

        document.select("a[href*=/video/]").forEach { element ->
            val href = element.attr("href")
            val id = Regex("""/video/(\d+)""").find(href)?.groupValues?.get(1) ?: return@forEach
            val videoUrl = "https://ok.ru/video/$id"

            val title = element.select(".va-title, .ellip, .video-card_n, img[alt]").firstOrNull()?.text()
                ?: element.attr("title").ifBlank { "Video $id" }

            val poster = element.select("img").firstOrNull()?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }

            if (searchResults.none { it.url == videoUrl }) {
                searchResults.add(
                    newMovieSearchResponse(title.trim(), videoUrl, TvType.Movie) {
                        this.posterUrl = poster?.takeIf { it.isNotBlank() }
                    }
                )
            }
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = Regex("""(?:video|videoembed)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Link video OK.ru không hợp lệ!")

        val embedUrl = "https://ok.ru/videoembed/$videoId"
        val response = app.get(embedUrl, headers = defaultHeaders).text

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
                            JSONObject(app.get(metaUrl, headers = defaultHeaders).text)
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

        // Fallback lấy tiêu đề và ảnh bìa từ HTML nếu không có trong metadata
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
