package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("""(?:video|videoembed)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""(\d{8,})""").find(url)?.groupValues?.get(1)
            ?: return

        OkRuHelper.resolveStreams(videoId, name, callback)
    }
}

object OkRuHelper {
    // Nếu bạn có Cookie tài khoản OK.ru, hãy dán vào đây để mở khóa video 18+ và video nhóm kín
    var USER_COOKIE: String = ""

    val headers: Map<String, String>
        get() {
            val map = mutableMapOf(
                "User-Agent" to "okhttp/4.12.0",
                "Referer" to "https://ok.ru/"
            )
            if (USER_COOKIE.isNotBlank()) {
                map["Cookie"] = USER_COOKIE
            }
            return map
        }

    suspend fun resolveStreams(videoId: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
        val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
        val response = try {
            app.post(apiUrl, headers = headers).text
        } catch (e: Exception) {
            return
        }

        val json = try {
            JSONObject(response)
        } catch (e: Exception) {
            return
        }

        // 1. Luồng HLS Master tự động thích ứng băng thông
        val hlsUrl = json.optString("hlsManifestUrl")
        if (hlsUrl.isNotBlank()) {
            callback(
                newExtractorLink(
                    source = sourceName,
                    name = "$sourceName Auto (HLS)",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://ok.ru/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }

        // 2. Các luồng MP4 trực tiếp chất lượng cao
        val videos = json.optJSONArray("videos")
        if (videos != null) {
            val qualityOrder = listOf("ultra", "quad", "full", "hd", "sd", "low", "lowest", "mobile")
            val videoList = mutableListOf<JSONObject>()
            for (i in 0 until videos.length()) {
                videos.optJSONObject(i)?.let { videoList.add(it) }
            }

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

                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = "$sourceName $label",
                        url = vidUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = quality
                        this.headers = headers
                    }
                )
            }
        }
    }
}

/**
 * MainAPI cho OK.ru: Giao diện mượt mà, tải nhanh tức thì và không bị treo
 */
class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OK.ru"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others, TvType.Movie)

    private val mobileHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
        "Accept-Language" to "vi,en-US;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "1" to "🔥 Thịnh hành hôm nay",
        "2" to "⚡ Video nổi bật",
        "3" to "🌟 Xem nhiều nhất"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val basePageOffset = request.data.toIntOrNull() ?: 1
        val actualPage = (basePageOffset - 1) * 2 + page
        val targetUrl = "https://m.ok.ru/video?st.page=$actualPage"

        val homeItems = mutableListOf<SearchResponse>()
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

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems)),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val searchResults = mutableListOf<SearchResponse>()

        // 1. Dán trực tiếp link hoặc ID video: Tìm kiếm và hiển thị ngay
        val directMatch = Regex("""(?:video|videoembed)/(\d+)""").find(cleanQuery)
            ?: Regex("""^(\d{8,})$""").find(cleanQuery)

        if (directMatch != null) {
            val id = directMatch.groupValues[1]
            val videoUrl = "https://ok.ru/video/$id"

            var title = "OK.ru Video #$id"
            var poster: String? = null
            try {
                val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
                val res = app.post(apiUrl, headers = OkRuHelper.headers).text
                val json = JSONObject(res)
                val movie = json.optJSONObject("movie")
                movie?.optString("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                movie?.optString("poster")?.takeIf { it.isNotBlank() }?.let { poster = it }
            } catch (ignored: Exception) {}

            return listOf(
                newMovieSearchResponse(title, videoUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }

        // 2. Tìm kiếm qua API chính thức của OK.ru
        val searchUrl = "https://ok.ru/video/search/${cleanQuery.encodeUrl()}"
        try {
            val response = app.get(searchUrl, headers = OkRuHelper.headers).text

            val match = Regex("""data-props="([^"]*&quot;videos&quot;[^"]*)"""").find(response)?.groupValues?.get(1)
            if (match != null) {
                val decoded = match
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&#39;", "'")
                val json = JSONObject(decoded)
                val list = json.optJSONObject("videos")?.optJSONArray("list")
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val movie = item.optJSONObject("movie")
                        val id = movie?.optString("id") ?: item.optString("id")
                        if (id.isBlank()) continue

                        val title = item.optString("name").ifBlank {
                            movie?.optString("title") ?: "Video $id"
                        }
                        val poster = item.optString("imageUrl").ifBlank {
                            movie?.optString("poster") ?: ""
                        }

                        val videoUrl = "https://ok.ru/video/$id"
                        if (searchResults.none { it.url == videoUrl }) {
                            searchResults.add(
                                newMovieSearchResponse(title.trim(), videoUrl, TvType.Movie) {
                                    this.posterUrl = poster.takeIf { it.isNotBlank() }
                                }
                            )
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = Regex("""(?:video|videoembed)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""(\d{8,})""").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Link video OK.ru không hợp lệ!")

        val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
        val response = app.post(apiUrl, headers = OkRuHelper.headers).text
        val json = JSONObject(response)

        val movie = json.optJSONObject("movie")
        val title = movie?.optString("title")?.takeIf { it.isNotBlank() } ?: "OK.ru Video #$videoId"
        val poster = movie?.optString("poster")?.takeIf { it.isNotBlank() }
        val plot = movie?.optString("description")?.takeIf { it.isNotBlank() }
        val dur = movie?.optInt("duration", 0) ?: 0
        val duration = if (dur > 0) dur / 60 else null

        // Truyền videoId vào data của response để loadLinks sử dụng trực tiếp
        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
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
        val videoId = Regex("""(?:video|videoembed)/(\d+)""").find(data)?.groupValues?.get(1)
            ?: Regex("""(\d{8,})""").find(data)?.groupValues?.get(1)
            ?: data

        OkRuHelper.resolveStreams(videoId, name, callback)
        return true
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")
}
