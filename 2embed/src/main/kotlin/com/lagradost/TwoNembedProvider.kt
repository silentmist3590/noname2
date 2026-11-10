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
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("OMDb Error", e.message ?: "Unknown")
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
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Trakt Scrobble Error", e.message ?: "Unknown")
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

    // Đưa api.tmdb.org lên hàng đầu, loại bỏ proxy worker bị 403
    private val tmdbHosts = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3",
        "https://tmdb-proxy.silentmist3590.workers.dev/3"
    )

    private suspend fun tmdbGet(path: String): String {
        var last: Exception? = null
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val sep = if (cleanPath.contains("?")) "&" else "?"
        for (host in tmdbHosts) {
            try {
                val r = app.get("$host$cleanPath${sep}api_key=$TMDB_API_KEY")
                if (r.code == 200 && r.text.trimStart().startsWith("{")) return r.text
                last = Exception("HTTP ${r.code} @ $host")
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                last = e
            }
        }
        ClipboardLogger.logAndCopy("TMDB fallback failed", "${last?.message} (path=$path)")
        throw last ?: Exception("TMDB failed")
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
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("getMainPage Error", e.message ?: "Unknown")
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
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Search Error", e.message ?: "Unknown")
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
            // SỬA LỖI 403: Sử dụng tmdbGet thay vì gọi thẳng proxy worker bị chặn
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
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.logAndCopy("Season Load Error", "Season $s: ${e.message}")
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
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("load Error", "$url -> ${e.message}")
            return null
        }
    }

    // =====================================================================================
    // 4. LOAD LINKS: BẮT LINK TRỰC TIẾP TỪ 2EMBED & VIDSRC.BUZZ (ĐÃ FIX ĐƯỜNG DẪN 404)
    // =====================================================================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        val ref = parseRef(data)
        if (ref == null) {
            ClipboardLogger.logAndCopy("Error", "Invalid data format: $data")
            return false
        }
        val type = ref.type
        val tmdbId = ref.id
        val season = ref.season
        val episode = ref.episode

        TwoNembedApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        val embedUrl = if (type == "movie") {
            "https://www.2embed.cc/embed/$tmdbId"
        } else {
            "https://www.2embed.cc/embedtv/$tmdbId&s=${season ?: 1}&e=${episode ?: 1}"
        }

        var foundLink = false

        try {
            val embedHtml = app.get(embedUrl, headers = mapOf("User-Agent" to ua, "Referer" to "https://www.2embed.cc/")).text

            val playerUrls = mutableListOf<String>()
            val dataSrc = Regex("""data-src=["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
            if (dataSrc != null) playerUrls.add(dataSrc)

            val goRegex = Regex("""onclick=["']go\(['"]([^'"]+)['"]\)["']""")
            goRegex.findAll(embedHtml).forEach {
                val u = it.groupValues[1]
                if (!playerUrls.contains(u)) playerUrls.add(u)
            }

            if (playerUrls.isEmpty()) {
                if (type == "movie") {
                    playerUrls.add("https://vidsrc.buzz/embed/movie/$tmdbId")
                    playerUrls.add("https://videm.buzz/embed/movie/$tmdbId")
                } else {
                    playerUrls.add("https://vidsrc.buzz/embed/tv/$tmdbId/${season ?: 1}/${episode ?: 1}")
                    playerUrls.add("https://videm.buzz/embed/tv/$tmdbId/${season ?: 1}/${episode ?: 1}")
                }
            }

            for (playerUrl in playerUrls) {
                try {
                    val pDomain = Regex("""^(https?://[^/]+)""").find(playerUrl)?.groupValues?.get(1) ?: "https://vidsrc.buzz"
                    val pHtml = app.get(playerUrl, headers = mapOf("User-Agent" to ua, "Referer" to embedUrl)).text

                    val qMatch = Regex("""var Q\s*=\s*(\{.*?\});""").find(pHtml) ?: continue
                    val qJson = JSONObject(qMatch.groupValues[1])
                    val token = qJson.optString("t")
                    val qType = qJson.optString("type", type)
                    val qId = qJson.optString("id")
                    val qS = qJson.optInt("s", season ?: 0)
                    val qE = qJson.optInt("e", episode ?: 0)

                    val servers = qJson.optJSONObject("ssr")?.optJSONArray("servers") ?: continue

                    // 1. TẢI VÀ GIẢI MÃ PHỤ ĐỀ TRỰC TIẾP TỪ BASE64 JWT (TRÁNH LỖI 403)
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

                                // Giải mã Base64 ref để lấy link trực tiếp
                                if (finalSubUrl.isEmpty() && subRef.isNotEmpty()) {
                                    try {
                                        val payload = subRef.substringBefore(".")
                                        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                                        val decodedBytes = Base64.decode(padded, Base64.DEFAULT)
                                        val subObj = JSONObject(String(decodedBytes, Charsets.UTF_8))
                                        finalSubUrl = subObj.optString("u")
                                    } catch (_: Exception) {
                                    }
                                }

                                if (finalSubUrl.isNotEmpty()) {
                                    subtitleCallback.invoke(SubtitleFile(label, finalSubUrl))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        ClipboardLogger.logAndCopy("Subs Error", e.message ?: "")
                    }

                    // 2. TẢI VÀ PHÁT LUỒNG STREAM ĐA ĐỘ PHÂN GIẢI
                    for (i in 0 until servers.length()) {
                        try {
                            val serverObj = servers.getJSONObject(i)
                            val sName = serverObj.optString("name", "Server ${i + 1}")
                            val sRef = serverObj.optString("ref")

                            val playApiUrl = "$pDomain/pl/api.php?a=play&ref=${URLEncoder.encode(sRef, "UTF-8")}&t=${URLEncoder.encode(token, "UTF-8")}"
                            val playRes = app.get(playApiUrl, headers = mapOf(
                                "User-Agent" to ua,
                                "Referer" to playerUrl,
                                "Origin" to pDomain,
                                "Accept" to "application/json"
                            )).text

                            val playJson = JSONObject(playRes)
                            var rawStreamUrl = playJson.optString("url")
                            if (rawStreamUrl.isEmpty()) continue

                            // SỬA LỖI 404 TẠI ĐÂY:
                            // Link trả về dạng "/_stream?id=...", chỉ nối thẳng domain gốc pDomain (KHÔNG được chèn thêm /pl)
                            val streamUrl = if (rawStreamUrl.startsWith("/")) "$pDomain$rawStreamUrl" else rawStreamUrl

                            val headers = mapOf(
                                "Origin" to pDomain,
                                "Referer" to "$pDomain/",
                                "User-Agent" to ua
                            )

                            // Tự động phân giải các chất lượng (1080p, 720p, 480p...)
                            val m3u8Links = try {
                                M3u8Helper.generateM3u8(
                                    source = this.name,
                                    streamUrl = streamUrl,
                                    referer = "$pDomain/",
                                    headers = headers
                                )
                            } catch (_: Exception) {
                                emptyList()
                            }

                            if (m3u8Links.isNotEmpty()) {
                                m3u8Links.forEach { link ->
                                    callback.invoke(link)
                                }
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
                                foundLink = true
                            }
                        } catch (e: Exception) {
                            ClipboardLogger.logAndCopy("Server Play Error", e.message ?: "")
                        }
                    }
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Player Scraping Error", e.message ?: "")
                }
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("LoadLinks Error", e.message ?: "")
        }

        // DỰ PHÒNG NGUỒN PHÁT: Nếu 2embed bị nghẽn, tự động kích hoạt máy chủ dự phòng HD
        if (!foundLink) {
            try {
                val backupUrl = if (type == "tv") {
                    "https://nontongo.win/01aus/player.php?id=$tmdbId&type=tv&season=${season ?: 1}&episode=${episode ?: 1}&autoplay=true"
                } else {
                    "https://nontongo.win/01aus/player.php?id=$tmdbId&type=movie&autoplay=true"
                }
                val pHtml = app.get(backupUrl, referer = "https://nontongo.win/", headers = mapOf("User-Agent" to ua)).text
                val streamUrlRegex = Regex("""data-url=["']([^"']+)["']""")
                for (m in streamUrlRegex.findAll(pHtml)) {
                    val sUrl = m.groupValues[1].substringBefore("#")
                    if (sUrl.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "2embed - Backup HD",
                                url = sUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://nontongo.win/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        foundLink = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (foundLink) {
            TwoNembedApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)
        }
        return foundLink
    }
}

// Typealias để tương thích cả 2 cách gọi
typealias TwoEmbedProvider = TwoNembedProvider
