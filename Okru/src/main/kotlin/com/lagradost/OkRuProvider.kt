package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OK.ru"
    override val hasMainPage = true
    override var lang = "ru"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        // Để trống mặc định vì OK.ru phát công khai hoàn toàn mượt mà không cần tài khoản/cookie
        private const val USER_COOKIE = ""
    }

    override val mainPage = mainPageOf(
        "video/top" to "Thịnh hành (Top Videos)",
        "video/popular" to "Phổ biến",
        "video/movies" to "Phim & Hoạt hình",
        "video/live" to "Trực tiếp (Live)"
    )

    // -------------------------------------------------------------------------
    // 1. TRANG CHÍNH (MAIN PAGE)
    // -------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim().trimStart('/')
        val url = if (page <= 1) {
            "https://m.ok.ru/$path"
        } else {
            val sep = if (path.contains("?")) "&" else "?"
            "https://m.ok.ru/$path${sep}st.page=$page"
        }
        val items = fetchVideoList(url)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // 2. TÌM KIẾM (SEARCH)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()
        val url = "https://m.ok.ru/video/search?cmd=Search&st.query=$q"
        return fetchVideoList(url)
    }

    // -------------------------------------------------------------------------
    // 3. CHI TIẾT VIDEO (LOAD)
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val videoId = extractVideoId(url)
        if (videoId.isBlank()) return null

        val embedUrl = "https://ok.ru/videoembed/$videoId"
        val res = app.get(embedUrl, headers = getHeaders("https://ok.ru/"))
        val html = res.text

        val match = Regex("""data-options=["']([^"']+)["']""").find(html) ?: return null
        val rawJson = unescapeHtml(match.groupValues[1])
        val rootObj = JSONObject(rawJson)
        val flashvars = rootObj.optJSONObject("flashvars") ?: JSONObject()

        val metadataObj = flashvars.optJSONObject("metadata")
            ?: flashvars.optString("metadata").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            ?: JSONObject()

        val movieObj = metadataObj.optJSONObject("movie") ?: JSONObject()

        val title = movieObj.optString("title").takeIf { it.isNotBlank() }
            ?: rootObj.optString("movieTitle").takeIf { it.isNotBlank() }
            ?: "Video $videoId"

        var poster = movieObj.optString("poster").takeIf { it.isNotBlank() }
            ?: rootObj.optString("poster").takeIf { it.isNotBlank() }
        poster = poster?.let { formatUrl(it) }

        val durationSec = movieObj.optInt("duration", 0).takeIf { it > 0 }
        val author = metadataObj.optJSONObject("author")?.optString("name")
            ?: movieObj.optString("author")

        val pageUrl = "https://ok.ru/video/$videoId"

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, videoId) {
            this.posterUrl = poster
            this.duration = durationSec
            this.plot = author.takeIf { !it.isNullOrBlank() }?.let { "Kênh / Người đăng: $it" }
        }
    }

    // -------------------------------------------------------------------------
    // 4. TRÍCH XUẤT LUỒNG PHÁT (LOAD LINKS) - KHÔNG CRASH, KHÔNG TREO
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = extractVideoId(data)
        if (videoId.isBlank()) return false

        val embedUrl = "https://ok.ru/videoembed/$videoId"
        val res = app.get(embedUrl, headers = getHeaders("https://ok.ru/"))
        val html = res.text

        val match = Regex("""data-options=["']([^"']+)["']""").find(html) ?: return false
        val rawJson = unescapeHtml(match.groupValues[1])
        val rootObj = JSONObject(rawJson)
        val flashvars = rootObj.optJSONObject("flashvars") ?: JSONObject()

        val metadataObj = flashvars.optJSONObject("metadata")
            ?: flashvars.optString("metadata").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            ?: JSONObject()

        val movieObj = metadataObj.optJSONObject("movie") ?: JSONObject()
        var foundLinks = false

        // Phụ đề: Bọc try-catch và chuẩn hóa https:// để tuyệt đối không làm crash ExoPlayer
        val subtitlesArr = movieObj.optJSONArray("subtitleTracks")
            ?: metadataObj.optJSONArray("subtitleTracks")
        if (subtitlesArr != null) {
            for (i in 0 until subtitlesArr.length()) {
                try {
                    val subObj = subtitlesArr.optJSONObject(i) ?: continue
                    var subUrl = subObj.optString("url", "").trim()
                    if (subUrl.isBlank()) continue
                    if (subUrl.startsWith("//")) subUrl = "https:$subUrl"
                    if (!subUrl.startsWith("http")) continue
                    val subTitle = subObj.optString("title").takeIf { it.isNotBlank() }
                        ?: subObj.optString("manifestName").takeIf { it.isNotBlank() }
                        ?: subObj.optString("language", "Phụ đề")
                    subtitleCallback(SubtitleFile(subTitle, subUrl))
                } catch (_: Throwable) {}
            }
        }

        // Ưu tiên số 1: Direct MP4 Video Streams (Phát trực tiếp cực nhanh, tua mượt, không lỗi)
        val videosArr = metadataObj.optJSONArray("videos")
        if (videosArr != null && videosArr.length() > 0) {
            data class VideoStream(val url: String, val quality: Int, val displayName: String)
            val streamList = mutableListOf<VideoStream>()

            for (i in 0 until videosArr.length()) {
                val vObj = videosArr.optJSONObject(i) ?: continue
                var vUrl = vObj.optString("url", "").trim()
                if (vUrl.isBlank()) continue
                if (vUrl.startsWith("//")) vUrl = "https:$vUrl"
                if (!vUrl.startsWith("http")) continue

                val vName = vObj.optString("name", "").lowercase().trim()
                val (qVal, dName) = when (vName) {
                    "ultra" -> Qualities.P2160.value to "OK.ru • 4K Ultra"
                    "quad" -> Qualities.P1080.value to "OK.ru • 2K Quad"
                    "full" -> Qualities.P1080.value to "OK.ru • 1080p FHD"
                    "hd" -> Qualities.P720.value to "OK.ru • 720p HD"
                    "sd" -> Qualities.P480.value to "OK.ru • 480p SD"
                    "low" -> Qualities.P360.value to "OK.ru • 360p"
                    "lowest" -> Qualities.P360.value to "OK.ru • 240p"
                    "mobile" -> Qualities.Unknown.value to "OK.ru • 144p Mobile"
                    else -> Qualities.Unknown.value to "OK.ru • $vName"
                }
                streamList.add(VideoStream(vUrl, qVal, dName))
            }

            // Sắp xếp giảm dần theo chất lượng (1080p -> 720p -> 480p -> 360p)
            streamList.sortByDescending { it.quality }

            for (stream in streamList) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = stream.displayName,
                        url = stream.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = stream.quality
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://ok.ru/"
                        )
                    }
                )
                foundLinks = true
            }
        }

        // Ưu tiên số 2: HLS Stream (M3U8) dự phòng
        val hlsUrl = metadataObj.optString("hlsManifestUrl", "").trim()
        if (hlsUrl.isNotBlank()) {
            val fixedHls = if (hlsUrl.startsWith("//")) "https:$hlsUrl" else hlsUrl
            if (fixedHls.startsWith("http")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "OK.ru • Tự động thích ứng (HLS)",
                        url = fixedHls,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://ok.ru/"
                        )
                    }
                )
                foundLinks = true
            }
        }

        return foundLinks
    }

    // -------------------------------------------------------------------------
    // CÁC HÀM TIỆN ÍCH TRỢ GIÚP (HELPERS)
    // -------------------------------------------------------------------------
    private fun extractVideoId(input: String): String {
        val clean = input.trim()
        val match = Regex("""(?:video(?:embed)?/)?(\d{5,})""").find(clean)
        return match?.groupValues?.get(1) ?: clean.filter { it.isDigit() }
    }

    private fun formatUrl(url: String): String {
        var clean = url.trim()
        if (clean.startsWith("//")) {
            clean = "https:$clean"
        }
        return clean
    }

    private fun getPosterUrl(item: JSONObject): String? {
        item.optJSONObject("thumbnails")?.let { thumbs ->
            val keys = listOf(
                "STANDARD", "FULL", "FULL_HD_NO_CROP", "HD_NO_CROP", "HD",
                "HIGH", "MEDIUM", "SMALL", "COLLAGE"
            )
            for (k in keys) {
                val v = thumbs.optString(k, "").trim()
                if (v.isNotBlank()) return formatUrl(v)
            }
        }
        item.optJSONObject("thumbnailsObjects")?.let { tObjs ->
            val keys = listOf("STANDARD", "FULL", "HD", "HIGH")
            for (k in keys) {
                val v = tObjs.optJSONObject(k)?.optString("thumbnail", "")?.trim()
                if (!v.isNullOrBlank()) return formatUrl(v)
            }
        }
        val direct = item.optString("imageUrl", "").takeIf { it.isNotBlank() }
            ?: item.optString("poster", "").takeIf { it.isNotBlank() }
            ?: item.optString("thumbnail", "").takeIf { it.isNotBlank() }
            ?: item.optJSONObject("collage")?.optString("url", "")?.takeIf { it.isNotBlank() }

        return direct?.let { formatUrl(it) }
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

    private fun getHeaders(referer: String = "https://ok.ru/"): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "*/*"
        )
        if (USER_COOKIE.isNotBlank()) {
            val sanitized = USER_COOKIE.filter { it.code in 32..126 }.trim()
            if (sanitized.isNotBlank()) {
                map["Cookie"] = sanitized
            }
        }
        return map
    }

    private suspend fun fetchVideoList(url: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        try {
            val res = app.get(
                url,
                headers = getHeaders("https://m.ok.ru/")
            )
            val html = res.text
            val match = Regex("""videos=["']([^"']+)["']""").find(html)
            if (match != null) {
                val unescaped = unescapeHtml(match.groupValues[1])
                val root = JSONObject(unescaped)
                val dataArray = root.optJSONObject("table")?.optJSONArray("data")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("videos")

                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.optJSONObject(i) ?: continue
                        val id = item.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                        val name = item.optString("name", "").takeIf { it.isNotBlank() }
                            ?: item.optString("title", "").takeIf { it.isNotBlank() }
                            ?: "Video $id"

                        val thumb = getPosterUrl(item)
                        val videoPageUrl = "https://ok.ru/video/$id"

                        list.add(
                            newMovieSearchResponse(name, videoPageUrl, TvType.Movie) {
                                this.posterUrl = thumb
                            }
                        )
                    }
                }
            }
        } catch (_: Throwable) {}
        return list
    }
}
