package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

// =============================================================================
// CẤU HÌNH TÙY CHỌN (OPTIONAL)
// =============================================================================
// Nếu bạn có tài khoản OK.ru và muốn xem các video 18+ hoặc video riêng tư,
// bạn có thể dán Cookie trình duyệt vào đây (ví dụ: "AUTHCODE=...; JSESSIONID=...").
// Nếu để trống, plugin vẫn phát bình thường tất cả các video công khai.
private const val USER_COOKIE: String = "_gid=GA1.2.408718373.1793368896; _hd=h; tmr_lvid=5725d3bd97cffc921fbe188528bf5db1; tmr_lvidTS=1793368897153; _ym_uid=1793368898600613063; _ym_d=1793368898; __pwa=0; __dzbd=false; _touch=false; _hover=true; ok_dpi=240; ok_w=1920; ok_h=1080; _breakpoint=XL; cudr=0; klos=0; _gcl_au=1.1.440008412.1793411949; viewport=680; _ga=GA1.1.1067751352.1793368896; _ym_isad=2; mdd=0; LASTSRV=ok.ru; _ga_GEGXS3ELM6=GS2.1.s1793458060$o5$g0$t1793458069$j51$l0$h0; TZ=14; CDN=; domain_sid=TQZ8IR0LUqJvX5x9SBQSm%3A1793458092109; tmr_detect=0%7C1793458094507; TZD=14.643; TD=643; __last_online=1793458456832"

private const val USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OK.ru"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    // Bốn danh mục trang chủ đa dạng
    override val mainPage = mainPageOf(
        "/video" to "Video thịnh hành",
        "/video/top" to "Video xem nhiều (Top)",
        "/video/movies" to "Phim & Video dài",
        "/video/live" to "Trực tiếp (Live)"
    )

    private fun getHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://ok.ru/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        if (USER_COOKIE.isNotBlank()) {
            headers["Cookie"] = USER_COOKIE
        }
        return headers
    }

    // =========================================================================
    // 1. TRANG CHỦ (MAIN PAGE)
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val targetUrl = "https://m.ok.ru$path"

        val res = runCatching {
            app.get(targetUrl, headers = getHeaders(), timeout = 10L).text
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val videos = mutableListOf<SearchResponse>()
        val match = Regex("""videos="([^"]+)"""").find(res)
        if (match != null) {
            val unescaped = match.groupValues[1]
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#39;", "'")

            runCatching {
                val json = JSONObject(unescaped)
                val table = json.optJSONObject("table")
                val data = table?.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val title = item.optString("title")
                        val thumb = item.optString("thumbnail")
                        if (id.isNotBlank() && title.isNotBlank()) {
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
                }
            }
        }

        return newHomePageResponse(request.name, videos, hasNext = false)
    }

    // =========================================================================
    // 2. TÌM KIẾM (SEARCH)
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/video/search/$encodedQuery"

        val res = runCatching {
            app.get(searchUrl, headers = getHeaders(), timeout = 10L).text
        }.getOrNull() ?: return emptyList()

        val results = mutableListOf<SearchResponse>()
        val match = Regex("""data-props="([^"]*&quot;videos&quot;[^"]*)"""").find(res)
        if (match != null) {
            val unescaped = match.groupValues[1]
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#39;", "'")

            runCatching {
                val json = JSONObject(unescaped)
                val vidsObj = json.optJSONObject("videos")
                val list = vidsObj?.optJSONArray("list")
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val movie = item.optJSONObject("movie")
                        val id = movie?.optString("id")
                            ?: item.optString("openMovieLink").substringAfter("/video/").substringBefore("?")
                        val title = item.optString("name").takeIf { it.isNotBlank() }
                            ?: movie?.optString("title")
                        val poster = item.optString("imageUrl").takeIf { it.isNotBlank() }
                            ?: movie?.optJSONObject("thumbnail")?.optString("big")

                        if (!id.isNullOrBlank() && !title.isNullOrBlank()) {
                            results.add(
                                newMovieSearchResponse(
                                    name = title,
                                    url = "$mainUrl/video/$id",
                                    type = TvType.Movie
                                ) {
                                    this.posterUrl = poster
                                }
                            )
                        }
                    }
                }
            }
        }

        return results
    }

    // =========================================================================
    // 3. CHI TIẾT VIDEO (LOAD)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        val videoId = if (url.contains("/video/")) {
            url.substringAfter("/video/").substringBefore("?").substringBefore("/")
        } else {
            url
        }

        // Lấy thông tin metadata trực tiếp từ trang embed (cực nhanh, không bao giờ bị redirect login)
        val embedUrl = "$mainUrl/videoembed/$videoId"
        val res = runCatching {
            app.get(embedUrl, headers = getHeaders(), timeout = 10L).text
        }.getOrNull() ?: ""

        val rawDataOptions = Regex("""data-options="([^"]+)"""").find(res)?.groupValues?.get(1)
            ?.replace("&quot;", "\"")
            ?.replace("&amp;", "&")
            ?.replace("&#39;", "'")

        var title = "Video $videoId"
        var poster: String? = null
        var durationSec = 0L
        var plot: String? = null

        if (!rawDataOptions.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(rawDataOptions)
                val flashvars = json.optJSONObject("flashvars")
                val metadata = flashvars?.optJSONObject("metadata")
                    ?: flashvars?.optString("metadata")?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }

                val movie = metadata?.optJSONObject("movie")
                title = movie?.optString("title")?.takeIf { it.isNotBlank() }
                    ?: json.optString("title").takeIf { it.isNotBlank() }
                    ?: title
                poster = movie?.optString("poster")?.takeIf { it.isNotBlank() }
                    ?: json.optString("poster").takeIf { it.isNotBlank() }
                durationSec = movie?.optLong("duration", 0L) ?: 0L
                plot = movie?.optString("description")
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
            this.plot = plot
            if (durationSec > 0) {
                this.duration = (durationSec / 60).toInt()
            }
        }
    }

    // =========================================================================
    // 4. PHÁT VIDEO & TRÍCH XUẤT LUỒNG (LOAD LINKS)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = if (data.contains("/video/")) {
            data.substringAfter("/video/").substringBefore("?").substringBefore("/")
        } else {
            data
        }

        var totalLinks = 0
        val streamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://ok.ru/"
        )

        // BƯỚC 1: Lấy link từ videoembed (Có đầy đủ HLS và MP4)
        runCatching {
            val embedUrl = "$mainUrl/videoembed/$videoId"
            val html = app.get(embedUrl, headers = getHeaders(), timeout = 10L).text
            val rawDataOptions = Regex("""data-options="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?.replace("&quot;", "\"")
                ?.replace("&amp;", "&")
                ?.replace("&#39;", "'")

            if (!rawDataOptions.isNullOrBlank()) {
                val json = JSONObject(rawDataOptions)
                val flashvars = json.optJSONObject("flashvars")
                val metadata = flashvars?.optJSONObject("metadata")
                    ?: flashvars?.optString("metadata")?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }

                if (metadata != null) {
                    totalLinks += emitStreams(metadata, streamHeaders, subtitleCallback, callback)
                }
            }
        }

        // BƯỚC 2: Dự phòng nếu videoembed chưa có link -> Gọi API videoPlayerMetadata
        if (totalLinks == 0) {
            runCatching {
                val apiUrl = "https://ok.ru/dk"
                val postResp = app.post(
                    apiUrl,
                    headers = getHeaders(),
                    data = mapOf(
                        "cmd" to "videoPlayerMetadata",
                        "mid" to videoId
                    ),
                    timeout = 10L
                ).text

                if (postResp.startsWith("{")) {
                    val metadata = JSONObject(postResp)
                    totalLinks += emitStreams(metadata, streamHeaders, subtitleCallback, callback)
                }
            }
        }

        return totalLinks > 0
    }

    private fun emitStreams(
        metadata: JSONObject,
        streamHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0

        // 1. Luồng HLS (Chất lượng tự động thích ứng / Auto Adaptive)
        val hlsUrl = metadata.optString("hlsManifestUrl", "")
        if (hlsUrl.isNotBlank() && hlsUrl.startsWith("http")) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • HLS (Tự động thích ứng)",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://ok.ru/"
                    this.quality = Qualities.P1080.value
                    this.headers = streamHeaders
                }
            )
            count++
        }

        // 2. Các luồng MP4 trực tiếp (từ 144p đến 4K)
        val videos = metadata.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val vUrl = v.optString("url", "")
                if (vUrl.isBlank() || !vUrl.startsWith("http")) continue

                val qName = v.optString("name", "sd")
                val (qVal, label) = when (qName.lowercase()) {
                    "ultra" -> Qualities.P2160.value to "4K Ultra"
                    "quad" -> Qualities.P1440.value to "2K Quad"
                    "full" -> Qualities.P1080.value to "1080p FHD"
                    "hd" -> Qualities.P720.value to "720p HD"
                    "sd" -> Qualities.P480.value to "480p SD"
                    "low" -> Qualities.P360.value to "360p Low"
                    "lowest" -> Qualities.P240.value to "240p Lowest"
                    "mobile" -> Qualities.P144.value to "144p Mobile"
                    else -> Qualities.Unknown.value to qName.uppercase()
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name • MP4 ($label)",
                        url = vUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = qVal
                        this.headers = streamHeaders
                    }
                )
                count++
            }
        }

        // 3. Phụ đề nếu video có sẵn
        val movie = metadata.optJSONObject("movie")
        val subs = movie?.optJSONArray("subtitleTracks")
        if (subs != null) {
            for (s in 0 until subs.length()) {
                val sub = subs.optJSONObject(s) ?: continue
                var sUrl = sub.optString("url", "")
                if (sUrl.startsWith("//")) sUrl = "https:$sUrl"
                val sLang = sub.optString("title", sub.optString("language", "Phụ đề"))
                if (sUrl.startsWith("http")) {
                    subtitleCallback(SubtitleFile(sLang, sUrl))
                }
            }
        }

        return count
    }
}

// =============================================================================
// PLUGIN ENTRY POINT
// =============================================================================
@CloudstreamPlugin
class OkRuPlugin : Plugin() {
    override fun load(context: Context) {
        // Chỉ đăng ký MainAPI duy nhất, tránh chạy xung đột extractor kép
        registerMainAPI(OkRuProvider())
    }
}
