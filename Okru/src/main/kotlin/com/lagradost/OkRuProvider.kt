package com.lagradost

import androidx.core.text.HtmlCompat
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

    companion object {
        // Nếu cần dùng Cookie tài khoản OK.ru, bạn dán chuỗi cookie vào đây.
        // Dùng raw string """ để tránh lỗi Unresolved reference khi cookie có ký tự $
        var USER_AUTH = """oRrnLKkxktrtxRO4HVuvbnuxpWEaMFa3WoA55Jfm7yD0cDWl5z_Q2ZbmlaxQrZLrWhBWhPAMW_NtpcylxxXNo-XGOLMW6TG-28vkWAzBcmX6g_XpG7AI7dVc8g3KoakkdDVGopB4h31qyLGk_g_5""".replace("\$", "$")
    }

    private val uaMobile =
        "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val uaDesktop =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun getHeaders(isMobile: Boolean = true): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to if (isMobile) uaMobile else uaDesktop,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://ok.ru/"
        )
        if (USER_COOKIE.isNotBlank()) {
            map["Cookie"] = USER_COOKIE
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

    private suspend fun fetchVideoList(url: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        try {
            val responseText = app.get(url, headers = getHeaders(isMobile = true)).text

            // OK.ru nhúng bảng video trong thuộc tính videos="..."
            val match = Regex("""videos="([^"]+)"""").find(responseText)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) {
                val unescaped = HtmlCompat.fromHtml(match, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                val dataObj = JSONObject(unescaped)
                val tableData = dataObj.optJSONObject("table")?.optJSONArray("data") ?: JSONArray()
                for (i in 0 until tableData.length()) {
                    val item = tableData.optJSONObject(i) ?: continue
                    parseVideoItem(item)?.let { list.add(it) }
                }
            }

            // Dự phòng: Tìm trực tiếp các link video nếu thuộc tính videos không có
            if (list.isEmpty()) {
                val links = Regex("""/video/(\d{8,})""").findAll(responseText)
                val seen = mutableSetOf<String>()
                for (m in links) {
                    val id = m.groupValues[1]
                    if (seen.add(id)) {
                        list.add(
                            newMovieSearchResponse("Video $id", "$mainUrl/video/$id", TvType.Movie)
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
        val items = fetchVideoList(targetUrl)
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

        // 1. Nếu tìm bằng link hoặc ID
        val idRegex = Regex("""(?:ok\.ru/(?:video|videoembed)/|^)(\d{8,})""")
        val matchedId = idRegex.find(q)?.groupValues?.get(1)
        if (matchedId != null) {
            val videoUrl = "$mainUrl/video/$matchedId"
            val loaded = runCatching {
                val embedUrl = "$mainUrl/videoembed/$matchedId"
                val embedHtml = app.get(embedUrl, headers = mapOf("User-Agent" to uaDesktop)).text
                val optMatch = Regex("""data-options="([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                if (optMatch != null) {
                    val optJson = JSONObject(HtmlCompat.fromHtml(optMatch, HtmlCompat.FROM_HTML_MODE_LEGACY).toString())
                    val meta = optJson.optJSONObject("flashvars")?.optString("metadata")?.let { JSONObject(it) }
                    val movie = meta?.optJSONObject("movie")
                    val title = movie?.optString("title") ?: "OK.ru Video $matchedId"
                    val poster = movie?.optString("poster")?.let { fixUrl(it) }
                    newMovieSearchResponse(title, videoUrl, TvType.Movie) {
                        this.posterUrl = poster
                    }
                } else null
            }.getOrNull()

            return listOf(
                loaded ?: newMovieSearchResponse("Video $matchedId", videoUrl, TvType.Movie)
            )
        }

        // 2. Tìm kiếm theo tên
        val encodedQuery = URLEncoder.encode(q, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/video/search?st.query=$encodedQuery",
            "$mainUrl/video/search?cmd=Search&st.cmd=videoSearch&st.query=$encodedQuery",
            "$mainUrl/search/content?st.query=$encodedQuery"
        )

        for (targetUrl in searchUrls) {
            val list = fetchVideoList(targetUrl)
            for (item in list) {
                if (seenIds.add(item.url)) {
                    results.add(item)
                }
            }
            if (results.isNotEmpty()) break
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
                val optJson = JSONObject(HtmlCompat.fromHtml(optMatch, HtmlCompat.FROM_HTML_MODE_LEGACY).toString())
                val meta = optJson.optJSONObject("flashvars")?.optString("metadata")?.let { JSONObject(it) }
                val movie = meta?.optJSONObject("movie")
                title = movie?.optString("title") ?: title
                poster = movie?.optString("poster")?.let { fixUrl(it) }
                description = movie?.optString("description")
                duration = movie?.optInt("duration", 0)?.takeIf { it > 0 }
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

        val unescaped = HtmlCompat.fromHtml(optMatch, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        val optionsJson = JSONObject(unescaped)
        val flashvars = optionsJson.optJSONObject("flashvars") ?: return false
        val metaStr = flashvars.optString("metadata")
        if (metaStr.isBlank()) return false

        val meta = JSONObject(metaStr)

        // 1. Nguồn phát trực tiếp HLS (M3U8) - Ưu tiên hàng đầu cho CloudStream
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

        // 2. Nguồn phát trực tiếp MP4 theo từng độ phân giải
        val videosArr = meta.optJSONArray("videos") ?: JSONArray()
        for (i in 0 until videosArr.length()) {
            val v = videosArr.optJSONObject(i) ?: continue
            val rawStreamUrl = v.optString("url")
            if (rawStreamUrl.isBlank()) continue

            val fixedUrl = fixUrl(rawStreamUrl) ?: continue
            // Thêm &ext=.mp4 để ExoPlayer nhận diện đúng định dạng MP4 container
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

        // 3. Phụ đề (Subtitles) - Chuẩn hóa link tránh văng crash ExoPlayer
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
