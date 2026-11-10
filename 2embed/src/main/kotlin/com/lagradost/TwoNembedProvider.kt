@file:Suppress("DEPRECATION", "UNUSED_VARIABLE")
package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// =====================================================================================
// 1. CẤU HÌNH API VÀ ĐỊNH DANH
// =====================================================================================
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
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
// 2. DỊCH VỤ HỖ TRỢ (Trakt Scrobble)
// =====================================================================================
object TwoNembedApiServices {
    suspend fun sendTraktScrobble(action: String, type: String, tmdbId: Int, progress: Float, season: Int? = null, episode: Int? = null) {
        if (TRAKT_ACCESS_TOKEN.isEmpty()) return
        val url = "https://api.trakt.tv/scrobble/$action"
        val headers = mapOf(
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID,
            "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
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
        } catch (t: Throwable) {
            ClipboardLogger.log("Trakt Error", t.message ?: "Unknown")
        }
    }
}

// =====================================================================================
// 3. PROVIDER CHÍNH (TwoNembedProvider)
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

    private val tmdbHosts = listOf("https://api.themoviedb.org/3", "https://api.tmdb.org/3")

    private suspend fun tmdbGet(path: String): String {
        var last: Throwable? = null
        val sep = if (path.contains("?")) "&" else "?"
        for (host in tmdbHosts) {
            try {
                val r = app.get("$host$path${sep}api_key=$TMDB_API_KEY")
                if (r.code == 200 && r.text.trimStart().startsWith("{")) return r.text
                last = Exception("HTTP ${r.code} @ $host")
            } catch (t: Throwable) {
                if (t is java.util.concurrent.CancellationException) throw t
                last = t
            }
        }
        throw last ?: Exception("TMDB kết nối thất bại")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = tmdbGet("${request.data}?page=$page")
            val json = JSONObject(response)
            val results = json.optJSONArray("results") ?: throw Exception("TMDB không có kết quả")
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
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val response = tmdbGet("/search/multi?query=$encodedQuery")
            val results = JSONObject(response).optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                list.add(newMovieSearchResponse(title, "2embed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            list
        } catch (t: Throwable) {
            if (t is java.util.concurrent.CancellationException) throw t
            ClipboardLogger.logAndCopy("search Error", t.message ?: "Unknown")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ClipboardLogger.log("Load Start", "Đang nạp chi tiết: $url")
        val ref = parseRef(url) ?: throw Exception("URL 2embed không đúng định dạng: $url")
        val type = ref.type
        val tmdbId = ref.id

        val detailsJson = JSONObject(tmdbGet("/$type/$tmdbId?append_to_response=external_ids,credits"))
        val title = detailsJson.optString("title", detailsJson.optString("name", "Unknown"))
        val posterPath = poster(detailsJson.optString("poster_path"))
        val plot = detailsJson.optString("overview")
        val year = detailsJson.optString("release_date", detailsJson.optString("first_air_date")).take(4).toIntOrNull()

        return if (type == "movie") {
            newMovieLoadResponse(title, url, TvType.Movie, "2embed:movie:$tmdbId") {
                this.posterUrl = posterPath
                this.plot = plot
                this.year = year
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasonsCount = detailsJson.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val sJson = JSONObject(tmdbGet("/tv/$tmdbId/season/$s"))
                    val eps = sJson.optJSONArray("episodes") ?: continue
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        val eNum = ep.optInt("episode_number")
                        val epName = ep.optString("name", "Episode $eNum")
                        episodes.add(
                            newEpisode("2embed:tv:$tmdbId:$s:$eNum") {
                                this.name = epName
                                this.season = s
                                this.episode = eNum
                                this.posterUrl = poster(ep.optString("still_path"))
                                this.description = ep.optString("overview")
                            }
                        )
                    }
                } catch (t: Throwable) {
                    ClipboardLogger.log("Season $s Error", t.message ?: "")
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterPath
                this.plot = plot
                this.year = year
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun extractDirectSubtitleUrl(refToken: String): String? {
        return try {
            val parts = refToken.split(".")
            if (parts.isNotEmpty()) {
                var payload = parts[0]
                val rem = payload.length % 4
                if (rem > 0) payload += "=".repeat(4 - rem)
                val jsonStr = String(Base64.decode(payload))
                val json = JSONObject(jsonStr)
                val u = json.optString("u")
                if (u.isNotEmpty() && u.startsWith("http")) u else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    // =====================================================================================
    // 4. LOAD LINKS: BẮT LINK VÀ GẮN BROWSER HEADERS CHỐNG LỖI 2004/2001
    // =====================================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.clear()
        ClipboardLogger.log("1. Khởi động", "Bắt đầu lấy link cho data: $data")

        val ref = parseRef(data)
        if (ref == null) {
            ClipboardLogger.logAndCopy("Lỗi", "Không thể phân tích ID: $data")
            return false
        }

        val type = ref.type
        val tmdbId = ref.id
        val season = ref.season
        val episode = ref.episode

        val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        var linkCount = 0
        var subCount = 0

        // =================================================================================
        // NGUỒN 1 & 2: vidsrc.buzz và videm.buzz
        // =================================================================================
        val embedDomains = listOf("https://vidsrc.buzz", "https://videm.buzz")

        for ((index, pDomain) in embedDomains.withIndex()) {
            val playerUrl = if (type == "movie") {
                "$pDomain/embed/movie/$tmdbId"
            } else {
                "$pDomain/embed/tv/$tmdbId/$season/$episode"
            }

            ClipboardLogger.log("2. Kết nối nguồn [${index + 1}/${embedDomains.size}]", "Đang tải: $playerUrl")

            try {
                val playerDoc = app.get(
                    playerUrl,
                    headers = mapOf(
                        "User-Agent" to browserUa,
                        "Referer" to "https://www.2embed.cc/",
                        "Origin" to "https://www.2embed.cc"
                    )
                )

                if (playerDoc.code != 200 || playerDoc.text.isEmpty()) {
                    ClipboardLogger.log("3. Bỏ qua", "$playerUrl trả về mã: ${playerDoc.code}")
                    continue
                }

                val html = playerDoc.text
                val qMatch = Regex("""var\s+Q\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (qMatch == null) {
                    ClipboardLogger.log("4. Không tìm thấy Q", "Không thể trích xuất token Q từ $playerUrl")
                    continue
                }

                val qJson = JSONObject(qMatch.groupValues[1])
                val token = qJson.optString("t")
                val ssr = qJson.optJSONObject("ssr")
                val servers = ssr?.optJSONArray("servers") ?: continue

                if (servers.length() == 0) continue

                val serverNames = (0 until servers.length()).map { servers.getJSONObject(it).optString("name", "Server $it") }
                ClipboardLogger.log("5. Cấu hình Server", "Tìm thấy ${servers.length()} máy chủ: $serverNames")

                // Lấy phụ đề
                try {
                    val qs = if (type == "movie") "type=movie&id=${qJson.optString("id")}" else "type=tv&id=${qJson.optString("id")}&s=$season&e=$episode"
                    val subsRes = app.get(
                        "$pDomain/pl/api.php?a=subs&$qs",
                        headers = mapOf(
                            "User-Agent" to browserUa,
                            "Referer" to playerUrl,
                            "Origin" to pDomain
                        )
                    ).text

                    if (subsRes.trimStart().startsWith("{")) {
                        val subsArray = JSONObject(subsRes).optJSONArray("subtitles") ?: JSONObject(subsRes).optJSONArray("subs")
                        if (subsArray != null) {
                            for (i in 0 until subsArray.length()) {
                                val sub = subsArray.getJSONObject(i)
                                val label = sub.optString("label", sub.optString("lang", "Phụ đề"))
                                val subRef = sub.optString("ref")
                                val directUrl = extractDirectSubtitleUrl(subRef)
                                val finalSubUrl = directUrl ?: sub.optString("url").takeIf { it.isNotEmpty() }
                                ?: "$pDomain/pl/api.php?a=sub&ref=${URLEncoder.encode(subRef, "UTF-8")}"
                                subtitleCallback.invoke(SubtitleFile(label, finalSubUrl))
                                subCount++
                            }
                            ClipboardLogger.log("6. Phụ đề", "Đã nạp $subCount phụ đề từ $pDomain")
                        }
                    }
                } catch (t: Throwable) {
                    ClipboardLogger.log("Lỗi phụ đề", t.message ?: "")
                }

                // Gắn đầy đủ browser headers - CHỐNG TRIỆT ĐỂ LỖI 2004 (403 Forbidden)
                val streamHeaders = mapOf(
                    "User-Agent" to browserUa,
                    "Origin" to pDomain,
                    "Referer" to "$pDomain/",
                    "Accept" to "*/*"
                )

                // Lấy stream từng server
                for (i in 0 until servers.length()) {
                    try {
                        val serverObj = servers.getJSONObject(i)
                        val sName = serverObj.optString("name", "Server ${i + 1}")
                        val sRef = serverObj.optString("ref")

                        val playApiUrl = "$pDomain/pl/api.php?a=play&ref=${URLEncoder.encode(sRef, "UTF-8")}&t=${URLEncoder.encode(token, "UTF-8")}"
                        val playRes = app.get(
                            playApiUrl,
                            headers = mapOf(
                                "User-Agent" to browserUa,
                                "Referer" to playerUrl,
                                "Origin" to pDomain,
                                "Accept" to "application/json"
                            )
                        ).text

                        if (!playRes.trimStart().startsWith("{")) {
                            continue
                        }

                        val playJson = JSONObject(playRes)
                        var streamUrl = playJson.optString("url")
                        if (streamUrl.isEmpty()) {
                            ClipboardLogger.log("7. Trống", "$sName trả về url rỗng: $playRes")
                            continue
                        }

                        if (streamUrl.startsWith("/")) {
                            streamUrl = "$pDomain$streamUrl"
                        }

                        ClipboardLogger.log("8. Thành công", "$sName -> $streamUrl")

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "2embed - $sName",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$pDomain/"
                                this.quality = Qualities.P1080.value
                                this.headers = streamHeaders
                            }
                        )
                        linkCount++
                    } catch (t: Throwable) {
                        ClipboardLogger.log("Lỗi Server", t.message ?: "")
                    }
                }
            } catch (t: Throwable) {
                ClipboardLogger.log("Lỗi Nguồn $pDomain", t.message ?: "")
            }
        }

        // =================================================================================
        // NGUỒN 3: NontonGo Mirror (Dự phòng trực tiếp)
        // =================================================================================
        try {
            val nontonEmbed = if (type == "movie") {
                "https://www.nontongo.win/embed/movie/$tmdbId"
            } else {
                "https://www.nontongo.win/embed/tv/$tmdbId/$season/$episode"
            }
            ClipboardLogger.log("9. Dự phòng NontonGo", "Đang nạp: $nontonEmbed")

            val nontonDoc = app.get(nontonEmbed, headers = mapOf("User-Agent" to browserUa, "Referer" to "https://www.nontongo.win/"))
            if (nontonDoc.code == 200 && nontonDoc.text.isNotEmpty()) {
                val cfgMatch = Regex("""const\s+cfg\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(nontonDoc.text)
                if (cfgMatch != null) {
                    val cfgJson = JSONObject(cfgMatch.groupValues[1])
                    val nServers = cfgJson.optJSONArray("servers")
                    if (nServers != null) {
                        for (i in 0 until nServers.length()) {
                            val s = nServers.getJSONObject(i)
                            val sName = s.optString("name", "NontonGo Server")
                            val sUrl = if (type == "movie") s.optString("movie_url") else s.optString("tv_url")
                            if (sUrl.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "2embed - $sName (Mirror)",
                                        url = sUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://www.nontongo.win/"
                                        this.quality = Qualities.P1080.value
                                        this.headers = mapOf(
                                            "User-Agent" to browserUa,
                                            "Referer" to "https://www.nontongo.win/"
                                        )
                                    }
                                )
                                linkCount++
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            ClipboardLogger.log("Lỗi NontonGo Mirror", t.message ?: "")
        }

        val success = linkCount > 0
        ClipboardLogger.logAndCopy(
            if (success) "THÀNH CÔNG" else "THẤT BẠI",
            """
            Phim TMDB ID: $tmdbId
            Đã bắt được: $linkCount luồng phát video (Đã kèm headers chống 2004/2001)
            Số phụ đề: $subCount
            """.trimIndent()
        )

        if (success) {
            TwoNembedApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)
        }
        return success
    }
}

typealias TwoEmbedProvider = TwoNembedProvider
