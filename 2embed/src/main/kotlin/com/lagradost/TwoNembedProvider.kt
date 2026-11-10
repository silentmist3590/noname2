package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

// =====================================================================================
// 1. CẤU HÌNH API KEY VÀ ĐỊNH DANH
// =====================================================================================
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")

private class MediaRef(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): MediaRef? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return MediaRef(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

// =====================================================================================
// 2. CÁC HÀM GỌI API HỖ TRỢ (OMDb, Trakt)
// =====================================================================================
object TwoNembedApiServices {
    suspend fun getOmdbData(imdbId: String): String? {
        return try {
            val res = app.get("https://www.omdbapi.com/?apikey=$OMDB_API_KEY&i=$imdbId").text
            val json = JSONObject(res)
            val rating = json.optString("imdbRating")
            if (rating.isNotEmpty() && rating != "N/A") rating else null
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun sendTraktScrobble(action: String, type: String, tmdbId: Int, progress: Float, season: Int? = null, episode: Int? = null) {
        if (TRAKT_ACCESS_TOKEN.isEmpty()) return
        val url = "https://api.trakt.tv/scrobble/$action"
        val headers = mapOf(
            "Content-Type" to "application/json", "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID, "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
        )
        val body = JSONObject()
        body.put("progress", progress)
        if (type == "movie") {
            val movie = JSONObject()
            val ids = JSONObject()
            ids.put("tmdb", tmdbId)
            movie.put("ids", ids)
            body.put("movie", movie)
        } else {
            val episodeObj = JSONObject()
            episodeObj.put("season", season ?: 1)
            episodeObj.put("number", episode ?: 1)
            body.put("episode", episodeObj)
        }
        try {
            app.post(url, headers = headers, json = body).text
        } catch (_: Throwable) {
        }
    }
}

// =====================================================================================
// 3. LỚP PROVIDER CHÍNH (TwoNembedProvider)
// =====================================================================================
class TwoNembedProvider : MainAPI() {
    override var mainUrl = "https://www.2embed.cc"
    override var name = "2embed"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    private fun img(path: String?, size: String = "w500") =
        path?.takeIf { it.isNotEmpty() && it != "null" }?.let { "https://wsrv.nl/?url=image.tmdb.org/t/p/$size$it" }

    private fun poster(path: String?) = img(path, "w500")

    private val tmdbHosts = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )

    private suspend fun tmdbGet(path: String): String {
        var last: Throwable? = null
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val sep = if (cleanPath.contains("?")) "&" else "?"
        for (host in tmdbHosts) {
            try {
                val r = app.get("$host$cleanPath${sep}api_key=$TMDB_API_KEY")
                if (r.code == 200 && r.text.trimStart().startsWith("{")) return r.text
                last = Exception("HTTP ${r.code} @ $host")
            } catch (t: Throwable) {
                if (t is java.util.concurrent.CancellationException) throw t
                last = t
            }
        }
        ClipboardLogger.logAndCopy("TMDB Error", "${last?.message} (path=$path)")
        throw (last ?: Exception("TMDB failed"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = tmdbGet("${request.data}?page=$page")
            val json = JSONObject(response)
            val results = json.optJSONArray("results") ?: throw Exception(json.optString("status_message", "TMDB không trả results"))
            val home = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                home.add(newMovieSearchResponse(title, "2embed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
        } catch (t: Throwable) {
            if (t is java.util.concurrent.CancellationException) throw t
            ClipboardLogger.logAndCopy("getMainPage Error", t.message ?: "Unknown")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(tmdbGet("/search/multi?query=$q"))
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                out.add(newMovieSearchResponse(title, "2embed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            out
        } catch (t: Throwable) {
            if (t is java.util.concurrent.CancellationException) throw t
            ClipboardLogger.logAndCopy("Search Error", t.message ?: "Unknown")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url)
        if (ref == null) {
            ClipboardLogger.logAndCopy("load Error", "Không đọc được id từ url: $url")
            return null
        }
        val mediaType = ref.type
        val tmdbIdInt = ref.id

        try {
            val response = tmdbGet("/$mediaType/$tmdbIdInt")
            val json = JSONObject(response)
            if (json.has("status_code")) throw Exception("TMDB: ${json.optString("status_message")}")

            val title = json.optString("title", json.optString("name"))
            val description = json.optString("overview")
            val posterUrl = poster(json.optString("poster_path"))
            val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()

            val canonical = "2embed:$mediaType:$tmdbIdInt"

            if (mediaType == "tv") {
                val episodes = mutableListOf<Episode>()
                val seasonsCount = json.optInt("number_of_seasons", 1)
                for (s in 1..seasonsCount) {
                    try {
                        val seasonJson = JSONObject(tmdbGet("/tv/$tmdbIdInt/season/$s"))
                        val eps = seasonJson.optJSONArray("episodes") ?: continue
                        for (e in 0 until eps.length()) {
                            val ep = eps.getJSONObject(e)
                            val sn = ep.optInt("season_number", s)
                            val en = ep.optInt("episode_number", e + 1)
                            episodes.add(newEpisode("2embed:tv:$tmdbIdInt:$sn:$en") {
                                this.name = ep.optString("name")
                                this.episode = en
                                this.season = sn
                                this.description = ep.optString("overview")
                                this.posterUrl = poster(ep.optString("still_path"))
                            })
                        }
                    } catch (t: Throwable) {
                        if (t is java.util.concurrent.CancellationException) throw t
                        ClipboardLogger.logAndCopy("Season Load Error", "Season $s: ${t.message}")
                    }
                }
                return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.year = year
                }
            } else {
                return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.year = year
                }
            }
        } catch (t: Throwable) {
            if (t is java.util.concurrent.CancellationException) throw t
            ClipboardLogger.logAndCopy("load Error", "$url -> ${t.message}")
            return null
        }
    }

    // =====================================================================================
    // 4. LOAD LINKS: GHI NHẬT KÝ CHI TIẾT TỪNG BƯỚC VÀ TỰ ĐỘNG SAO CHÉP CLIPBOARD
    // =====================================================================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ClipboardLogger.clear()
        ClipboardLogger.log("1. Khởi động", "Bắt đầu lấy link cho data: $data")

        val ref = parseRef(data)
        if (ref == null) {
            ClipboardLogger.logAndCopy("Lỗi", "Định dạng data không hợp lệ: $data")
            return false
        }
        val type = ref.type
        val tmdbId = ref.id
        val season = ref.season ?: 1
        val episode = ref.episode ?: 1

        TwoNembedApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        var foundLink = false
        var streamCount = 0
        var subCount = 0

        // Danh sách các máy chủ phát trực tiếp
        val playerUrls = mutableListOf<String>()
        if (type == "movie") {
            playerUrls.add("https://vidsrc.buzz/embed/movie/$tmdbId")
            playerUrls.add("https://videm.buzz/embed/movie/$tmdbId")
            playerUrls.add("https://www.2embed.cc/embed/$tmdbId")
        } else {
            playerUrls.add("https://vidsrc.buzz/embed/tv/$tmdbId/$season/$episode")
            playerUrls.add("https://videm.buzz/embed/tv/$tmdbId/$season/$episode")
            playerUrls.add("https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode")
        }

        ClipboardLogger.log("2. Danh sách nguồn", "Sẽ thử ${playerUrls.size} nguồn theo thứ tự")

        for ((index, playerUrl) in playerUrls.withIndex()) {
            ClipboardLogger.log("3. Kết nối nguồn [${index + 1}/${playerUrls.size}]", "Đang tải: $playerUrl")
            try {
                val pDomain = Regex("""^(https?://[^/]+)""").find(playerUrl)?.groupValues?.get(1) ?: "https://vidsrc.buzz"
                val res = app.get(playerUrl, headers = mapOf("User-Agent" to ua, "Referer" to "https://www.2embed.cc/"))
                val pHtml = res.text

                ClipboardLogger.log("4. Phản hồi", "$playerUrl trả về mã ${res.code}, độ dài ${pHtml.length} ký tự")

                // Nếu là trang trung gian 2embed.cc, trích xuất tiếp các nút bấm onclick="go(...)"
                if (playerUrl.contains("2embed.cc")) {
                    val subLinks = Regex("""onclick=["']go\(['"]([^'"]+)['"]\)["']""").findAll(pHtml).map { it.groupValues[1] }.toList()
                    ClipboardLogger.log("4.1. 2embed Sub-links", "Tìm thấy ${subLinks.size} link chuyển tiếp: $subLinks")
                    subLinks.forEach { subUrl ->
                        if (!playerUrls.contains(subUrl)) playerUrls.add(subUrl)
                    }
                    continue
                }

                val qMatch = Regex("""var Q\s*=\s*(\{.*?\});""").find(pHtml)
                if (qMatch == null) {
                    ClipboardLogger.log("4.2. Cảnh báo", "$playerUrl không chứa cấu hình 'var Q', bỏ qua server này")
                    continue
                }

                val qJson = JSONObject(qMatch.groupValues[1])
                val token = qJson.optString("t")
                val qType = qJson.optString("type", type)
                val qId = qJson.optString("id")
                val qS = qJson.optInt("s", season)
                val qE = qJson.optInt("e", episode)
                val servers = qJson.optJSONObject("ssr")?.optJSONArray("servers")

                if (servers == null || servers.length() == 0) {
                    ClipboardLogger.log("4.3. Cảnh báo", "Không có server nào trong 'var Q' của $playerUrl")
                    continue
                }

                val serverNames = (0 until servers.length()).map { servers.getJSONObject(it).optString("name") }
                ClipboardLogger.log("5. Cấu hình Server", "Tìm thấy ${servers.length()} máy chủ phát: $serverNames")

                // 1. TẢI VÀ GIẢI MÃ PHỤ ĐỀ TRỰC TIẾP TỪ BASE64
                try {
                    val subsUrl = "$pDomain/pl/api.php?a=subs&type=$qType&id=$qId&s=$qS&e=$qE&t=${URLEncoder.encode(token, "UTF-8")}"
                    val subsRes = app.get(subsUrl, headers = mapOf("User-Agent" to ua, "Referer" to playerUrl, "Origin" to pDomain)).text
                    val subsJson = JSONObject(subsRes)
                    val subsArray = subsJson.optJSONArray("subtitles") ?: subsJson.optJSONArray("subs")
                    if (subsArray != null) {
                        for (i in 0 until subsArray.length()) {
                            val sub = subsArray.getJSONObject(i)
                            val label = sub.optString("label", sub.optString("lang", "Subtitle"))
                            var finalSubUrl = sub.optString("url")
                            val subRef = sub.optString("ref")

                            if (finalSubUrl.isEmpty() && subRef.isNotEmpty()) {
                                try {
                                    val payload = subRef.substringBefore(".")
                                    val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                                    val decodedBytes = Base64.decode(padded, Base64.DEFAULT)
                                    val subObj = JSONObject(String(decodedBytes, Charsets.UTF_8))
                                    finalSubUrl = subObj.optString("u")
                                } catch (_: Throwable) {
                                }
                            }

                            if (finalSubUrl.isNotEmpty()) {
                                subtitleCallback.invoke(SubtitleFile(label, finalSubUrl))
                                subCount++
                            }
                        }
                        ClipboardLogger.log("6. Phụ đề", "Đã nạp thành công $subCount phụ đề cho phim")
                    }
                } catch (t: Throwable) {
                    ClipboardLogger.log("6. Lỗi phụ đề", "Không tải được phụ đề: ${t.message}")
                }

                // 2. TẢI LUỒNG STREAM TỪNG SERVER
                for (i in 0 until servers.length()) {
                    try {
                        val serverObj = servers.getJSONObject(i)
                        val sName = serverObj.optString("name", "Server ${i + 1}")
                        val sRef = serverObj.optString("ref")

                        ClipboardLogger.log("7. Bắt luồng [$sName]", "Đang gọi API lấy link phát cho $sName...")
                        val playApiUrl = "$pDomain/pl/api.php?a=play&ref=${URLEncoder.encode(sRef, "UTF-8")}&t=${URLEncoder.encode(token, "UTF-8")}"
                        val playRes = app.get(playApiUrl, headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to playerUrl,
                            "Origin" to pDomain,
                            "Accept" to "application/json"
                        )).text

                        val playJson = JSONObject(playRes)
                        val rawStreamUrl = playJson.optString("url")
                        if (rawStreamUrl.isEmpty()) {
                            ClipboardLogger.log("7.1. Trống", "$sName trả về url rỗng: $playRes")
                            continue
                        }

                        // SỬA LỖI 404: Nối thẳng domain gốc pDomain (KHÔNG được chèn /pl)
                        val streamUrl = if (rawStreamUrl.startsWith("/")) "$pDomain$rawStreamUrl" else rawStreamUrl
                        ClipboardLogger.log("7.2. Thành công", "$sName -> $streamUrl")

                        val headers = mapOf(
                            "Origin" to pDomain,
                            "Referer" to "$pDomain/",
                            "User-Agent" to ua
                        )

                        // Phân tách đa chất lượng (1080p, 720p, 480p...)
                        val m3u8Links = try {
                            M3u8Helper.generateM3u8(
                                source = this.name,
                                streamUrl = streamUrl,
                                referer = "$pDomain/",
                                headers = headers
                            )
                        } catch (t: Throwable) {
                            ClipboardLogger.log("7.3. M3U8 parse", "Không tách được chất lượng m3u8: ${t.message}")
                            emptyList()
                        }

                        if (m3u8Links.isNotEmpty()) {
                            m3u8Links.forEach { link ->
                                callback.invoke(link)
                                streamCount++
                            }
                            ClipboardLogger.log("8. Đa chất lượng", "$sName: Phân tách được ${m3u8Links.size} chất lượng stream")
                            foundLink = true
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "2embed - $sName",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$pDomain/"
                                    this.headers = headers
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            streamCount++
                            foundLink = true
                        }
                    } catch (t: Throwable) {
                        ClipboardLogger.log("7. Lỗi Server Play", "$playerUrl server $i lỗi: ${t.message}")
                    }
                }

                if (foundLink) {
                    ClipboardLogger.log("9. Hoàn tất nguồn", "Đã lấy đủ luồng tốt từ $playerUrl, không cần thử nguồn tiếp theo")
                    break
                }
            } catch (t: Throwable) {
                ClipboardLogger.log("3.1. Lỗi kết nối", "$playerUrl: ${t.message}")
            }
        }

        // 3. MÁY CHỦ DỰ PHÒNG TỐC ĐỘ CAO (NẾU 2EMBED BỊ NGHẼN)
        if (!foundLink) {
            ClipboardLogger.log("10. Dự phòng", "Các server chính 2embed chưa có link, đang kích hoạt Server Dự Phòng HD...")
            try {
                val backupUrl = if (type == "tv") {
                    "https://nontongo.win/01aus/player.php?id=$tmdbId&type=tv&season=$season&episode=$episode&autoplay=true"
                } else {
                    "https://nontongo.win/01aus/player.php?id=$tmdbId&type=movie&autoplay=true"
                }
                val pHtml = app.get(backupUrl, referer = "https://nontongo.win/", headers = mapOf("User-Agent" to ua)).text

                // Trích xuất phụ đề dự phòng
                val subRegex = Regex("""data-src=["']([^"']+\.(?:vtt|srt)[^"']*)["']\s+label=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                for (m in subRegex.findAll(pHtml)) {
                    subtitleCallback.invoke(SubtitleFile(m.groupValues[2], m.groupValues[1]))
                    subCount++
                }

                val streamUrlRegex = Regex("""data-url=["']([^"']+)["']""")
                for (m in streamUrlRegex.findAll(pHtml)) {
                    val sUrl = m.groupValues[1].substringBefore("#").trim()
                    if (sUrl.startsWith("http")) {
                        val m3u8 = try {
                            M3u8Helper.generateM3u8(
                                source = this.name,
                                streamUrl = sUrl,
                                referer = "https://nontongo.win/",
                                headers = mapOf("Referer" to "https://nontongo.win/", "User-Agent" to ua)
                            )
                        } catch (_: Throwable) {
                            emptyList()
                        }

                        if (m3u8.isNotEmpty()) {
                            m3u8.forEach { link ->
                                callback.invoke(link)
                                streamCount++
                            }
                            foundLink = true
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "2embed - Server Dự Phòng HD",
                                    url = sUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://nontongo.win/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            streamCount++
                            foundLink = true
                        }
                    }
                }
                if (foundLink) {
                    ClipboardLogger.log("10.1. Dự phòng thành công", "Đã lấy thành công luồng phát từ Server Dự Phòng")
                }
            } catch (t: Throwable) {
                ClipboardLogger.log("10.2. Lỗi dự phòng", "Server dự phòng lỗi: ${t.message}")
            }
        }

        // TỔNG KẾT VÀ TỰ ĐỘNG COPY TOÀN BỘ BÁO CÁO VÀO CLIPBOARD
        if (foundLink) {
            TwoNembedApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)
            ClipboardLogger.logAndCopy(
                "THÀNH CÔNG",
                "Phim TMDB ID: $tmdbId\nĐã bắt được: $streamCount luồng phát video\nSố phụ đề: $subCount"
            )
        } else {
            ClipboardLogger.logAndCopy(
                "THẤT BẠI",
                "Không thể trích xuất link nào cho TMDB ID: $tmdbId sau khi thử toàn bộ máy chủ."
            )
        }

        return foundLink
    }
}

// Typealias để tương thích cả 2 cách gọi
typealias TwoEmbedProvider = TwoNembedProvider
