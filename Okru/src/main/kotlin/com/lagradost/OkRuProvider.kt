package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

@CloudstreamPlugin
class OkRuPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(OkRuProvider())
    }
}

class OkRuProvider : MainAPI() {
    override var mainUrl = "https://m.ok.ru"
    override var name = "OK.ru"
    override val hasMainPage = true
    override var lang = "ru"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Dán Cookie hoặc giá trị AUTHCOOKIE vào đây nếu có (dùng 3 dấu nháy kép """ để không bị lỗi ký tự $)
    var userCookie: String = """"""

    private val uaMobile =
        "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val uaDesktop =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#34;", "\"")
            .replace("&#38;", "&")
    }

    private fun getHeaders(isMobile: Boolean = true): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to if (isMobile) uaMobile else uaDesktop,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://ok.ru/"
        )
        if (userCookie.isNotBlank()) {
            val cookieValue = userCookie.trim()
            map["Cookie"] = if (cookieValue.startsWith("AUTHCOOKIE=")) cookieValue else "AUTHCOOKIE=$cookieValue"
        }
        return map
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url == "null") return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://ok.ru$url"
            else -> url
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/video?st.page=1" to "Xu Hướng Nổi Bật",
        "$mainUrl/video?st.page=2" to "Phim & Video Phổ Biến",
        "$mainUrl/video?st.page=3" to "Video Mới Nhất",
        "$mainUrl/video?st.page=4" to "Khám Phá Thêm"
    )

    /**
     * Tra cứu nhanh tiêu đề gốc và ảnh bìa từ trang Embed của OK.ru
     */
    private suspend fun fetchVideoMetadata(id: String): Pair<String, String?> {
        return runCatching {
            val embedUrl = "https://ok.ru/videoembed/$id"
            val html = app.get(embedUrl, headers = mapOf("User-Agent" to uaDesktop)).text
            val optMatch = Regex("""data-options="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (optMatch != null) {
                val optJson = JSONObject(unescapeHtml(optMatch))
                val flashvars = optJson.optJSONObject("flashvars")
                val metaStr = flashvars?.optString("metadata")
                if (!metaStr.isNullOrBlank()) {
                    val meta = JSONObject(metaStr)
                    val movie = meta.optJSONObject("movie")
                    val title = movie?.optString("title")
                    val poster = movie?.optString("poster")?.let { fixUrl(it) }
                    if (!title.isNullOrBlank()) {
                        return@runCatching Pair(title, poster)
                    }
                }
            }
            Pair("OK.ru Video $id", null)
        }.getOrDefault(Pair("OK.ru Video $id", null))
    }

    private fun parseVideoItem(item: JSONObject): SearchResponse? {
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = item.optString("name").takeIf { it.isNotBlank() }
            ?: item.optString("title").takeIf { it.isNotBlank() }
            ?: "Video $id"

        var poster: String? = null
        val thumbs = item.optJSONObject("thumbnails")
        if (thumbs != null) {
            poster = thumbs.optString("STANDARD")
                .ifBlank { thumbs.optString("FULL") }
                .ifBlank { thumbs.optString("HD") }
                .ifBlank { thumbs.optString("LOW") }
        }
        if (poster.isNullOrBlank()) {
            val thumbsObj = item.optJSONObject("thumbnailsObjects")
            poster = thumbsObj?.optJSONObject("STANDARD")?.optString("thumbnail")
                ?: thumbsObj?.optJSONObject("FULL")?.optString("thumbnail")
        }
        if (poster.isNullOrBlank()) {
            poster = item.optString("thumbnail")
        }

        val videoUrl = "$mainUrl/video/$id"
        return newMovieSearchResponse(title, videoUrl, TvType.Movie) {
            this.posterUrl = fixUrl(poster)
        }
    }

    /**
     * Bóc tách danh sách video hỗ trợ đồng thời cả Mobile JSON và Desktop HTML
     */
    private suspend fun fetchVideoList(url: String, isMobile: Boolean = true): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()

        try {
            val responseText = app.get(url, headers = getHeaders(isMobile = isMobile)).text

            // 1. Tầng 1: Đọc bảng JSON trong thuộc tính videos="..." (thường có trên Mobile)
            val match = Regex("""videos="([^"]+)"""").find(responseText)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) {
                runCatching {
                    val unescaped = unescapeHtml(match)
                    val dataObj = JSONObject(unescaped)
                    val tableData = dataObj.optJSONObject("table")?.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until tableData.length()) {
                        val item = tableData.optJSONObject(i) ?: continue
                        parseVideoItem(item)?.let {
                            if (seenIds.add(it.url)) list.add(it)
                        }
                    }
                }
            }

            // 2. Tầng 2: Bóc tách thẻ HTML Video Card (Desktop & Search page)
            // Lấy chính xác Video ID, Poster và Tên Phim thật từ thuộc tính alt của ảnh
            val cardRegex = Regex(
                """href=["']/video/(\d{8,})[^"']*["'][^>]*>\s*<img[^>]+(?:src=["']([^"']+)["'][^>]+alt=["']([^"']+)["']|alt=["']([^"']+)["'][^>]+src=["']([^"']+)["'])""",
                RegexOption.IGNORE_CASE
            )
            for (m in cardRegex.findAll(responseText)) {
                val id = m.groupValues[1]
                val posterRaw = m.groupValues[2].ifBlank { m.groupValues[5] }
                val titleRaw = m.groupValues[3].ifBlank { m.groupValues[4] }
                val title = unescapeHtml(titleRaw).trim()

                if (title.isNotBlank() && !title.startsWith("http") && seenIds.add("$mainUrl/video/$id")) {
                    list.add(
                        newMovieSearchResponse(title, "$mainUrl/video/$id", TvType.Movie) {
                            this.posterUrl = fixUrl(unescapeHtml(posterRaw))
                        }
                    )
                }
            }

            // 3. Tầng 3: Quét qua thẻ có thuộc tính title="..."
            val titleAttrRegex = Regex("""href=["']/video/(\d{8,})[^"']*["'][^>]*?title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            for (m in titleAttrRegex.findAll(responseText)) {
                val id = m.groupValues[1]
                val title = unescapeHtml(m.groupValues[2]).trim()
                if (title.isNotBlank() && !title.startsWith("http") && seenIds.add("$mainUrl/video/$id")) {
                    list.add(newMovieSearchResponse(title, "$mainUrl/video/$id", TvType.Movie))
                }
            }

            // 4. Tầng 4: Nếu chỉ quét được ID số thô, tự động gọi Metadata để lấy tên gốc
            if (list.isEmpty()) {
                val rawIdRegex = Regex("""/video/(\d{8,})""")
                val foundIds = rawIdRegex.findAll(responseText)
                    .map { it.groupValues[1] }
                    .distinct()
                    .take(8)
                    .toList()

                for (id in foundIds) {
                    if (seenIds.add("$mainUrl/video/$id")) {
                        val (realTitle, realPoster) = fetchVideoMetadata(id)
                        list.add(
                            newMovieSearchResponse(realTitle, "$mainUrl/video/$id", TvType.Movie) {
                                this.posterUrl = realPoster
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) { }

        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data.contains("page=")) {
            request.data.replace(Regex("""st\.page=\d+"""), "st.page=$page")
        } else {
            "${request.data}&st.page=$page"
        }
        val items = fetchVideoList(targetUrl, isMobile = true)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty() && page < 10
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()

        // 1. Trường hợp tìm bằng đường link trực tiếp hoặc dãy số ID
        val idRegex = Regex("""(?:ok\.ru/(?:video|videoembed)/|^)(\d{8,})""")
        val matchedId = idRegex.find(q)?.groupValues?.get(1)
        if (matchedId != null) {
            val (realTitle, realPoster) = fetchVideoMetadata(matchedId)
            return listOf(
                newMovieSearchResponse(realTitle, "$mainUrl/video/$matchedId", TvType.Movie) {
                    this.posterUrl = realPoster
                }
            )
        }

        // 2. Tìm kiếm theo tên từ khóa
        val encodedQuery = URLEncoder.encode(q, "UTF-8")

        // Ưu tiên 1: Tìm qua bản Desktop (chứa đầy đủ thẻ Card HTML có Tên và Ảnh)
        val desktopSearchUrl = "https://ok.ru/video/search?st.query=$encodedQuery"
        val desktopItems = fetchVideoList(desktopSearchUrl, isMobile = false)
        for (item in desktopItems) {
            if (seenIds.add(item.url)) {
                results.add(item)
            }
        }

        // Ưu tiên 2: Nếu desktop chưa có, thử tiếp qua Mobile endpoint
        if (results.isEmpty()) {
            val mobileSearchUrl = "$mainUrl/video/search?st.query=$encodedQuery"
            val mobileItems = fetchVideoList(mobileSearchUrl, isMobile = true)
            for (item in mobileItems) {
                if (seenIds.add(item.url)) {
                    results.add(item)
                }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val idRegex = Regex("""(?:/video(?:embed)?/|^)(\d{8,})""")
        val id = idRegex.find(url)?.groupValues?.get(1) ?: url

        val embedUrl = "https://ok.ru/videoembed/$id"
        var title = "OK.ru Video $id"
        var poster: String? = null
        var description: String? = null
        var duration: Int? = null

        runCatching {
            val embedHtml = app.get(embedUrl, headers = mapOf("User-Agent" to uaDesktop)).text
            val optMatch = Regex("""data-options="([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
            if (optMatch != null) {
                val optJson = JSONObject(unescapeHtml(optMatch))
                val flashvars = optJson.optJSONObject("flashvars")
                val metaStr = flashvars?.optString("metadata")
                if (!metaStr.isNullOrBlank()) {
                    val meta = JSONObject(metaStr)
                    val movie = meta.optJSONObject("movie")
                    title = movie?.optString("title") ?: title
                    poster = movie?.optString("poster")?.let { fixUrl(it) }
                    description = movie?.optString("description")
                    duration = movie?.optInt("duration", 0)?.takeIf { it > 0 }
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val idRegex = Regex("""(?:/video(?:embed)?/|^)(\d{8,})""")
        val id = idRegex.find(data)?.groupValues?.get(1) ?: data

        val embedUrl = "https://ok.ru/videoembed/$id"
        val embedHtml = app.get(embedUrl, headers = mapOf("User-Agent" to uaDesktop)).text

        val optMatch = Regex("""data-options="([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
            ?: return false

        val unescaped = unescapeHtml(optMatch)
        val optionsJson = JSONObject(unescaped)
        val flashvars = optionsJson.optJSONObject("flashvars") ?: return false
        val metaStr = flashvars.optString("metadata")
        if (metaStr.isBlank()) return false

        val meta = JSONObject(metaStr)

        // 1. Nguồn phát trực tiếp HLS (M3U8)
        val hlsUrl = meta.optString("hlsManifestUrl")
            .ifBlank { meta.optString("hlsMasterPlaylistUrl") }
        if (hlsUrl.isNotBlank()) {
            val fixedHls = fixUrl(hlsUrl)
            if (fixedHls != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - HLS Tự Động",
                        url = fixedHls,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to "https://ok.ru/")
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }

        // 2. Nguồn phát trực tiếp MP4 (thêm &ext=.mp4 để ExoPlayer nhận diện định dạng)
        val videosArr = meta.optJSONArray("videos") ?: JSONArray()
        for (i in 0 until videosArr.length()) {
            val v = videosArr.optJSONObject(i) ?: continue
            val rawStreamUrl = v.optString("url")
            if (rawStreamUrl.isBlank()) continue

            val fixedUrl = fixUrl(rawStreamUrl) ?: continue
            val playableUrl = if (!fixedUrl.contains(".mp4") && !fixedUrl.contains("ext=.mp4")) {
                if (fixedUrl.contains("?")) "$fixedUrl&ext=.mp4" else "$fixedUrl?ext=.mp4"
            } else {
                fixedUrl
            }

            val qualityName = v.optString("name").lowercase()
            val (qualityLabel, qualityInt) = when (qualityName) {
                "full" -> "1080p (Full)" to Qualities.P1080.value
                "hd" -> "720p (HD)" to Qualities.P720.value
                "sd" -> "480p (SD)" to Qualities.P480.value
                "low" -> "360p (Low)" to Qualities.P360.value
                "lowest" -> "240p (Lowest)" to Qualities.P240.value
                "mobile" -> "144p (Mobile)" to Qualities.P144.value
                else -> qualityName.uppercase() to Qualities.Unknown.value
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $qualityLabel",
                    url = playableUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf("Referer" to "https://ok.ru/")
                    this.quality = qualityInt
                }
            )
        }

        // 3. Phụ đề (Subtitles)
        runCatching {
            val subsArr = meta.optJSONObject("movie")?.optJSONArray("subtitles") ?: JSONArray()
            for (i in 0 until subsArr.length()) {
                val sub = subsArr.optJSONObject(i) ?: continue
                val rawSubUrl = sub.optString("url")
                val lang = sub.optString("name", "Phụ đề")
                val cleanSubUrl = fixUrl(rawSubUrl)
                if (!cleanSubUrl.isNullOrBlank()) {
                    subtitleCallback(SubtitleFile(lang, cleanSubUrl))
                }
            }
        }

        return true
    }
}
