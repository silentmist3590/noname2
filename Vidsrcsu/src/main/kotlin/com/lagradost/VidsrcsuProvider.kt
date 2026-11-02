package com.lagradost

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

// =====================================================================================
// 1. CẤU HÌNH TMDB ĐA TẦNG
// =====================================================================================
private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
private const val OMDB_API_KEY = "a116862a"
private const val TRAKT_CLIENT_ID = "gMysuKTAakLkXGoR9aMnwJLcffCwNWvKLM8MNNBs9m0"
private const val TRAKT_ACCESS_TOKEN = ""

object TmdbConfig {
    val apiKeys = listOf(
        "e716f19ab4d25edc5247239a8f3494f8",
        "8476a7ab80ad76f0936744df0430e67c",
        "439c478a771f35c05022f9feabcca01c",
        "b9e1ff7ff423790bf7df202e661377ca"
    )
    val directDomains = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3",
        "https://tmdb.demojameson.de5.net/3",
        "https://daili.smdhb.com/3"
    )
    val corsProxies: List<(String) -> String> = listOf(
        { url -> "https://api.codetabs.com/v1/proxy/?quest=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://api.allorigins.win/raw?url=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://cors.eu.org/$url" },
        { url -> "https://corsproxy.io/?url=${URLEncoder.encode(url, "UTF-8")}" }
    )
    private const val IMG_PROXY = "https://wsrv.nl/?url="

    suspend fun getTmdbData(pathWithQuery: String): JSONObject? {
        val path = if (pathWithQuery.startsWith("/")) pathWithQuery else "/$pathWithQuery"
        for (key in apiKeys) {
            for (domain in directDomains) {
                try {
                    val sep = if (path.contains("?")) "&" else "?"
                    val res = app.get("$domain$path${sep}api_key=$key").text
                    if (res.startsWith("{") && !res.contains("Invalid API key")) {
                        val json = JSONObject(res)
                        if (json.has("results") || json.has("id") || json.has("success")) return json
                    }
                } catch (e: Exception) { }
            }
            for (proxy in corsProxies) {
                try {
                    val sep = if (path.contains("?")) "&" else "?"
                    val res = app.get(proxy("https://api.themoviedb.org/3$path${sep}api_key=$key")).text
                    if (res.startsWith("{") && !res.contains("Invalid API key")) {
                        val json = JSONObject(res)
                        if (json.has("results") || json.has("id") || json.has("success")) return json
                    }
                } catch (e: Exception) { }
            }
        }
        return null
    }
    fun safeImageUrl(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        return "$IMG_PROXY${URLEncoder.encode("https://image.tmdb.org/t/p/w500$path", "UTF-8")}"
    }
}

// =====================================================================================
// 2. API HỖ TRỢ (OMDb & Trakt)
// =====================================================================================
object ApiServices {
    suspend fun getOmdbData(imdbId: String): String? {
        return try {
            val res = app.get("https://www.omdbapi.com/?apikey=$OMDB_API_KEY&i=$imdbId").text
            val json = JSONObject(res)
            val rating = json.optString("imdbRating")
            if (rating.isNotEmpty() && rating != "N/A") rating else null
        } catch (e: Exception) { null }
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
            val movie = JSONObject(); val ids = JSONObject(); ids.put("tmdb", tmdbId); movie.put("ids", ids); body.put("movie", movie)
        } else {
            val episodeObj = JSONObject(); episodeObj.put("season", season ?: 1); episodeObj.put("number", episode ?: 1); body.put("episode", episodeObj)
        }
        try { app.post(url, headers = headers, json = body).text } catch (e: Exception) {}
    }
}

// =====================================================================================
// 3. LỚP PROVIDER CHÍNH
// =====================================================================================
class VidsrcsuProvider : MainAPI() {
    override var mainUrl = "https://vidsrc.su"
    override var name = "Vidsrcsu"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = TmdbConfig.getTmdbData("${request.data}?page=$page")
        if (json == null || !json.has("results")) return newHomePageResponse(request.name, emptyList())
        val results = json.getJSONArray("results")
        val home = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = TmdbConfig.safeImageUrl(item.optString("poster_path"))
            home.add(newMovieSearchResponse(title, "vidsrcsu:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = TmdbConfig.getTmdbData("/search/multi?query=${URLEncoder.encode(query, "UTF-8")}") ?: return emptyList()
        if (!json.has("results")) return emptyList()
        val results = json.getJSONArray("results")
        val searchResults = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val id = item.getInt("id").toString()
            val title = item.optString("title", item.optString("name"))
            val poster = TmdbConfig.safeImageUrl(item.optString("poster_path"))
            searchResults.add(newMovieSearchResponse(title, "vidsrcsu:$mediaType:$id", if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val idMatch = Regex("""vidsrcsu:(movie|tv):(\d+)""").find(url) ?: return null
        val mediaType = idMatch.groupValues[1]
        val tmdbIdInt = idMatch.groupValues[2].toIntOrNull() ?: return null
        val json = TmdbConfig.getTmdbData("/$mediaType/$tmdbIdInt") ?: return null
        val title = json.optString("title", json.optString("name"))
        val description = json.optString("overview")
        val poster = TmdbConfig.safeImageUrl(json.optString("poster_path"))
        val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()
        val imdbId = json.optString("imdb_id")
        var imdbScore: Score? = null
        if (imdbId.isNotEmpty()) {
            ApiServices.getOmdbData(imdbId)?.let { imdbScore = Score.from10(it.toDouble()) }
        }
        val episodes = mutableListOf<Episode>()
        if (mediaType == "tv") {
            val seasonsCount = json.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val seasonJson = TmdbConfig.getTmdbData("/tv/$tmdbIdInt/season/$s") ?: continue
                    val eps = seasonJson.getJSONArray("episodes")
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        episodes.add(newEpisode("vidsrcsu:tv:$tmdbIdInt:${ep.getInt("season_number")}:${ep.getInt("episode_number")}") {
                            this.name = ep.optString("name")
                            this.episode = ep.getInt("episode_number")
                            this.season = ep.getInt("season_number")
                            this.description = ep.optString("overview")
                            this.posterUrl = TmdbConfig.safeImageUrl(ep.optString("still_path"))
                        })
                    }
                } catch (e: Exception) {}
            }
            return newTvSeriesLoadResponse(title, "vidsrcsu:tv:$tmdbIdInt", TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "vidsrcsu:movie:$tmdbIdInt") {
                this.posterUrl = poster; this.plot = description; this.year = year; this.score = imdbScore
            }
        }
    }

    // =====================================================================================
    // HÀM WEBVIEW NÂNG CAO: PERFORMANCE OBSERVER + JS HOOKS
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractWithWebView(url: String, referer: String): String? = withContext(Dispatchers.Main) {
        val context = CloudStreamApp.context ?: return@withContext null
        val deferredUrl = CompletableDeferred<String?>()
        val webView = WebView(context)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url.toString()
                if (reqUrl.contains(".m3u8", ignoreCase = true) || reqUrl.contains(".mp4", ignoreCase = true)) {
                    if (!deferredUrl.isCompleted) {
                        deferredUrl.complete(reqUrl)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject mã JavaScript nâng cao
                val js = """
                    (function() {
                        window.__captured_m3u8 = null;

                        // 1. Hook Fetch
                        const originalFetch = window.fetch;
                        window.fetch = async function(...args) {
                            try {
                                const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                                if (url && (url.includes('.m3u8') || url.includes('.mp4'))) {
                                    window.__captured_m3u8 = url;
                                }
                                const response = await originalFetch.apply(this, args);
                                return response;
                            } catch (e) { console.error(e); }
                        };

                        // 2. Hook XHR
                        const originalXHR = window.XMLHttpRequest;
                        window.XMLHttpRequest = function() {
                            const xhr = new originalXHR();
                            xhr.addEventListener('load', function() {
                                if (xhr.responseURL && (xhr.responseURL.includes('.m3u8') || xhr.responseURL.includes('.mp4'))) {
                                    window.__captured_m3u8 = xhr.responseURL;
                                }
                            });
                            return xhr;
                        };

                        // 3. Hook WebSocket
                        const originalWebSocket = window.WebSocket;
                        window.WebSocket = function(...args) {
                            const ws = new originalWebSocket(...args);
                            ws.addEventListener('message', function(event) {
                                if (typeof event.data === 'string' && (event.data.includes('.m3u8') || event.data.includes('.mp4'))) {
                                    const match = event.data.match(/(https?:\/\/[^\s"']+\.(?:m3u8|mp4)[^\s"']*)/);
                                    if (match) window.__captured_m3u8 = match[1];
                                }
                            });
                            return ws;
                        };

                        // 4. PerformanceObserver (Bắt request từ WASM)
                        if (window.PerformanceObserver) {
                            const observer = new PerformanceObserver((list) => {
                                for (const entry of list.getEntries()) {
                                    if (entry.name && (entry.name.includes('.m3u8') || entry.name.includes('.mp4'))) {
                                        window.__captured_m3u8 = entry.name;
                                    }
                                }
                            });
                            observer.observe({ entryTypes: ['resource'] });
                        }

                        // 5. Tự động phát / click play
                        setInterval(function() {
                            var videoEl = document.querySelector('video');
                            if (videoEl && videoEl.paused) {
                                videoEl.play().catch(function(){});
                                if (videoEl.paused) {
                                    videoEl.muted = true;
                                    videoEl.play().catch(function(){});
                                }
                            }
                            var playBtn = document.querySelector('.jw-display-icon-display, .jw-icon-playback, .vjs-big-play-button, button[aria-label="Play"], .play-button, .vjs-play-control');
                            if (playBtn) { playBtn.click(); }
                            var buttons = document.querySelectorAll('button');
                            for (var i=0; i<buttons.length; i++) {
                                if (buttons[i].textContent.includes('Play') || buttons[i].textContent.includes('play')) {
                                    buttons[i].click();
                                }
                            }
                        }, 2000);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }
        }

        // Thêm &autoplay=true vào URL để kích hoạt phát tự động
        val finalUrl = if (url.contains("?")) "$url&autoplay=true" else "$url?autoplay=true"
        val extraHeaders = mapOf("Referer" to referer)
        webView.loadUrl(finalUrl, extraHeaders)

        // Polling kiểm tra link mỗi 1 giây, tối đa 45 giây
        val result = withTimeoutOrNull(45000) {
            while (isActive) {
                delay(1000)
                var foundUrl: String? = null
                val jsResult = CompletableDeferred<String>()
                webView.evaluateJavascript("window.__captured_m3u8") { value ->
                    jsResult.complete(value)
                }
                val value = jsResult.await()
                if (value != null && value != "null" && value.length > 2) {
                    // Bỏ dấu ngoặc kép ở đầu và cuối
                    foundUrl = value.substring(1, value.length - 1)
                }

                if (foundUrl != null) {
                    return@withTimeoutOrNull foundUrl
                }
                
                // Fallback: Kiểm tra xem có request nào bị chặn không
                if (deferredUrl.isCompleted) {
                    return@withTimeoutOrNull deferredUrl.await()
                }
            }
            null
        }

        webView.stopLoading()
        webView.destroy()
        
        result
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        
        val idMatch = Regex("""vidsrcsu:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(data) ?: return false
        val type = idMatch.groupValues[1]
        val tmdbId = idMatch.groupValues[2].toIntOrNull() ?: return false
        val season = idMatch.groupValues.getOrNull(3)?.toIntOrNull()
        val episode = idMatch.groupValues.getOrNull(4)?.toIntOrNull()

        ApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to "https://vidsrc.sh/",
            "Origin" to "https://vidsrc.sh"
        )

        try {
            val apiUrl = if (type == "movie") {
                "https://vidsrc.sh/vs_src.php?type=movie&id=$tmdbId"
            } else {
                if (season == null || episode == null) return false
                "https://vidsrc.sh/vs_src.php?type=tv&id=$tmdbId&season=$season&episode=$episode"
            }
            
            ClipboardLogger.logAndCopy("API Request", "vs_src URL: $apiUrl")
            val apiRes = app.get(apiUrl, headers = headers).text
            ClipboardLogger.logAndCopy("vs_src Response", apiRes)

            val srcUrl = JSONObject(apiRes).optString("src")
            if (srcUrl.isEmpty()) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy 'src' trong response.")
                return false
            }

            ClipboardLogger.logAndCopy("WebView", "Đang dùng WebView để bắt m3u8...")
            val finalUrl = extractWithWebView(srcUrl, "https://stellarconductornexus.com/")

            if (finalUrl != null) {
                ClipboardLogger.logAndCopy("Success", "Final URL: $finalUrl")
                ApiServices.sendTraktScrobble("stop", type, tmdbId, 100f, season, episode)
                
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "Vidsrcsu",
                        url = finalUrl,
                        referer = "https://stellarconductornexus.com/",
                        quality = Qualities.P1080.value,
                        type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        headers = mapOf("Origin" to "https://stellarconductornexus.com")
                    )
                )
                return true
            } else {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được link m3u8.")
                return false
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
