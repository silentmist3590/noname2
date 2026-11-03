package com.lagradost

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

@CloudstreamPlugin
class HnembedPlugin : Plugin() {
    override fun load(context: Context) {
        HnembedProvider.pluginContext = context
        registerMainAPI(HnembedProvider())
    }
}

object ApiServices {
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
            "Content-Type" to "application/json",
            "trakt-api-version" to "2",
            "trakt-api-key" to TRAKT_CLIENT_ID,
            "Authorization" to "Bearer $TRAKT_ACCESS_TOKEN"
        )
        val body = JSONObject().apply {
            put("progress", progress)
            if (type == "movie") {
                val movie = JSONObject()
                val ids = JSONObject()
                ids.put("tmdb", tmdbId)
                movie.put("ids", ids)
                put("movie", movie)
            } else {
                val episodeObj = JSONObject()
                episodeObj.put("season", season ?: 1)
                episodeObj.put("number", episode ?: 1)
                put("episode", episodeObj)
            }
        }
        try {
            app.post(url, headers = headers, json = body).text
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Trakt Scrobble Error", e.message ?: "Unknown")
        }
    }
}

class HnembedProvider : MainAPI() {
    override var mainUrl = TMDB_BASE_URL
    override var name = "Hnembed"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        var pluginContext: Context? = null
    }

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$mainUrl/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$mainUrl/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("${request.data}&page=$page").text
        val json = JSONObject(response)
        val results = json.getJSONArray("results")
        val home = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = item.optString("poster_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w500$it" else null }
            home.add(newMovieSearchResponse(title, "hnembed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/search/multi?api_key=$TMDB_API_KEY&query=$query").text
        val json = JSONObject(response)
        val results = json.getJSONArray("results")
        val searchResults = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = item.optString("poster_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w500$it" else null }
            searchResults.add(newMovieSearchResponse(title, "hnembed:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split(":")
        if (parts.size < 3) return null
        val mediaType = parts[1]
        val tmdbId = parts[2]

        val response = app.get("$mainUrl/$mediaType/$tmdbId?api_key=$TMDB_API_KEY").text
        val json = JSONObject(response)
        val title = json.optString("title", json.optString("name"))
        val description = json.optString("overview")
        val poster = json.optString("poster_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w500$it" else null }
        val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
        val imdbId = json.optString("imdb_id")

        var imdbScore: Score? = null
        if (imdbId.isNotEmpty()) {
            ApiServices.getOmdbData(imdbId)?.let { ratingStr ->
                imdbScore = Score.from10(ratingStr.toDouble())
            }
        }

        val episodes = mutableListOf<Episode>()
        if (mediaType == "tv") {
            val seasonsCount = json.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val seasonRes = app.get("$mainUrl/tv/$tmdbId/season/$s?api_key=$TMDB_API_KEY").text
                    val seasonJson = JSONObject(seasonRes)
                    val eps = seasonJson.getJSONArray("episodes")
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        episodes.add(newEpisode("hnembed:tv:$tmdbId:${ep.getInt("season_number")}:${ep.getInt("episode_number")}") {
                            this.name = ep.optString("name")
                            this.episode = ep.getInt("episode_number")
                            this.season = ep.getInt("season_number")
                            this.description = ep.optString("overview")
                            this.posterUrl = ep.optString("still_path").let { if (it.isNotEmpty()) "https://image.tmdb.org/t/p/w300$it" else null }
                        })
                    }
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Season Load Error", "Season $s: ${e.message}")
                }
            }
            return newTvSeriesLoadResponse(title, "hnembed:tv:$tmdbId", TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "hnembed:movie:$tmdbId") {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore
            }
        }
    }

    /**
     * Giải mã chuỗi stream_urls qua module WebAssembly (vsdec.js) bằng WebView ngầm của Android
     */
    private suspend fun decryptWasmInWebView(wasmBytes: ByteArray, encB64: String): String? {
        val ctx = pluginContext ?: runCatching {
            Class.forName("com.lagradost.cloudstream3.AcraApplication")
                .getDeclaredField("context")
                .apply { isAccessible = true }
                .get(null) as? Context
        }.getOrNull() ?: return null

        val wasmBase64 = Base64.encodeToString(wasmBytes, Base64.NO_WRAP)

        return withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        val webView = WebView(ctx)
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true

                        fun cleanup() {
                            runCatching {
                                webView.stopLoading()
                                webView.removeJavascriptInterface("Bridge")
                                webView.destroy()
                            }
                        }

                        webView.addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onSuccess(txt: String) {
                                if (cont.isActive) cont.resume(txt)
                                cleanup()
                            }

                            @JavascriptInterface
                            fun onError(err: String) {
                                if (cont.isActive) cont.resumeWithException(Exception(err))
                                cleanup()
                            }
                        }, "Bridge")

                        val html = """
                            <!DOCTYPE html><html><head></head><body><script>
                            (async function() {
                                try {
                                    var binW = atob("$wasmBase64");
                                    var wBytes = new Uint8Array(binW.length);
                                    for (var i = 0; i < binW.length; i++) wBytes[i] = binW.charCodeAt(i);
                                    var mod = await WebAssembly.compile(wBytes);
                                    var inst = await WebAssembly.instantiate(mod, {});
                                    var ex = inst.exports;
                                    var binE = atob("$encB64");
                                    var eBytes = new Uint8Array(binE.length);
                                    for (var i = 0; i < binE.length; i++) eBytes[i] = binE.charCodeAt(i);
                                    var ptr = ex.alloc(eBytes.length);
                                    new Uint8Array(ex.memory.buffer, ptr, eBytes.length).set(eBytes);
                                    var outLen = ex.decrypt(ptr, eBytes.length);
                                    var txt = new TextDecoder().decode(new Uint8Array(ex.memory.buffer, ptr + 12, outLen));
                                    Bridge.onSuccess(txt);
                                } catch(e) {
                                    Bridge.onError(e.message || String(e));
                                }
                            })();
                            </script></body></html>
                        """.trimIndent()

                        webView.loadDataWithBaseURL("https://stellarconductornexus.com/", html, "text/html", "UTF-8", null)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        val parts = data.split(":")
        if (parts.size < 3) return false
        val type = parts[1]
        val tmdbId = parts[2]

        ApiServices.sendTraktScrobble(
            "start", type, tmdbId.toIntOrNull() ?: 0, 0f,
            if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
            if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
        )

        try {
            // Bước 1: Gọi vs_src.php từ hnembed.cc để lấy URL Player đích
            val vsSrcUrl = if (type == "movie") {
                "https://hnembed.cc/vs_src.php?type=movie&id=$tmdbId"
            } else {
                if (parts.size < 5) return false
                "https://hnembed.cc/vs_src.php?type=tv&id=$tmdbId&season=${parts[3]}&episode=${parts[4]}"
            }

            val hnHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://hnembed.cc/"
            )

            ClipboardLogger.logAndCopy("API Request", "Calling vs_src: $vsSrcUrl")
            val vsSrcResp = app.get(vsSrcUrl, headers = hnHeaders).text
            val embedSrc = JSONObject(vsSrcResp).optString("src")
            if (embedSrc.isBlank()) {
                ClipboardLogger.logAndCopy("Error", "Không nhận được embed src từ vs_src.php")
                return false
            }

            // Bước 2: Tải trang embed để lấy playerUrl
            val embedHtml = app.get(embedSrc, headers = hnHeaders).text
            val playerPathMatch = Regex("""["']playerUrl["']\s*:\s*["']([^"']+)["']""").find(embedHtml)
            val playerPath = playerPathMatch?.groupValues?.get(1)?.replace("\\u0026", "&")
            if (playerPath.isNullOrBlank()) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy playerUrl trong trang embed")
                return false
            }

            val playerHost = "https://stellarconductornexus.com"
            val playerFullUrl = if (playerPath.startsWith("http")) playerPath else "$playerHost$playerPath"

            // Bước 3: Tải trang Player để lấy CONFIG (chứa apiToken dùng một lần và URL stream)
            val playerHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to embedSrc
            )
            val playerHtml = app.get(playerFullUrl, headers = playerHeaders).text
            val configMatch = Regex("""window\.CONFIG\s*=\s*(\{.*?\});""").find(playerHtml)
            if (configMatch == null) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy window.CONFIG trong Player HTML")
                return false
            }

            val configJson = JSONObject(configMatch.groupValues[1].replace("\\u0026", "&"))
            val streamBaseApi = configJson.getString("api")
            val apiToken = configJson.optString("apiToken")

            // Bước 4: Gọi API stream_urls có gắn api_token
            val streamApiUrl = "$streamBaseApi&api_token=" + URLEncoder.encode(apiToken, "UTF-8")
            val streamDataHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$playerHost/"
            )
            val streamDataResp = app.get(streamApiUrl, headers = streamDataHeaders).text
            val streamJson = JSONObject(streamDataResp)

            val dataObj = streamJson.optJSONObject("data")
            val encStreamUrls = dataObj?.optString("stream_urls")
            val wasmUrl = streamJson.optJSONObject("vs")?.optString("wasm_url")

            if (encStreamUrls.isNullOrBlank() || wasmUrl.isNullOrBlank()) {
                ClipboardLogger.logAndCopy("Error", "Không nhận được stream_urls mã hóa hoặc wasm_url")
                return false
            }

            // Bước 5: Tải file WebAssembly (.wasm) về
            val wasmBytes = app.get(wasmUrl, headers = streamDataHeaders).bytes

            // Bước 6: Giải mã chuỗi stream_urls qua WebAssembly
            val decryptedUrlsText = decryptWasmInWebView(wasmBytes, encStreamUrls)
            if (decryptedUrlsText.isNullOrBlank()) {
                ClipboardLogger.logAndCopy("Error", "Giải mã WebAssembly thất bại hoặc trả về rỗng")
                return false
            }

            val rawStreamList = decryptedUrlsText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            if (rawStreamList.isEmpty()) {
                ClipboardLogger.logAndCopy("Error", "Danh sách URL sau khi giải mã rỗng")
                return false
            }

            // Bước 7: Với mỗi luồng m3u8, gọi generate.php từ host đó để lấy Token JWT và đóng dấu
            var addedLinkCount = 0
            for ((index, rawM3u8Url) in rawStreamList.withIndex()) {
                val streamOrigin = try {
                    val u = URI(rawM3u8Url)
                    "${u.scheme}://${u.host}"
                } catch (e: Exception) {
                    "https://birchandbloom.space"
                }

                // Gọi generate.php lấy token hợp lệ cho IP hiện tại
                val token = runCatching {
                    app.get(
                        "$streamOrigin/generate.php",
                        headers = mapOf(
                            "Referer" to "$playerHost/",
                            "Origin" to playerHost
                        )
                    ).text.trim()
                }.getOrNull() ?: ""

                val playableM3u8 = if (token.isNotBlank()) {
                    if (rawM3u8Url.contains("?")) "$rawM3u8Url&token=$token" else "$rawM3u8Url?token=$token"
                } else {
                    rawM3u8Url
                }

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Hnembed - Server ${index + 1}",
                        url = playableM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "Referer" to "$playerHost/",
                            "Origin" to playerHost,
                            "User-Agent" to userAgent
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                addedLinkCount++
            }

            ApiServices.sendTraktScrobble(
                "stop", type, tmdbId.toIntOrNull() ?: 0, 100f,
                if (type == "tv" && parts.size >= 5) parts[3].toIntOrNull() else null,
                if (type == "tv" && parts.size >= 5) parts[4].toIntOrNull() else null
            )

            ClipboardLogger.logAndCopy("Success", "Đã nạp thành công $addedLinkCount nguồn phát HLS.")
            return addedLinkCount > 0

        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
