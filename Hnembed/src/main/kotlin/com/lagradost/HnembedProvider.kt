package com.lagradost

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"

private data class HnMediaRef(
    val type: String,
    val id: Int,
    val season: Int? = null,
    val episode: Int? = null
)

private val HN_REF_REGEX =
    Regex("""^hnembed:(movie|tv):(\d+)(?::(\d+):(\d+))?$""")

private val HN_EMBED_PATH_REGEX =
    Regex("""^/embed/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?/?$""")

// Backward compatibility for old SearchResponse URLs that were incorrectly fixed by CloudStream.
private val HN_LEGACY_PATH_REGEX =
    Regex("""^/hnembed:(movie|tv):(\d+)(?::(\d+):(\d+))?/?$""")

/**
 * Accepted forms:
 *   hnembed:movie:123
 *   hnembed:tv:123:1:2
 *   https://hnembed.cc/embed/movie/123
 *   https://hnembed.cc/embed/tv/123/1/2
 *   https://hnembed.cc/hnembed:movie:123 (legacy broken URL)
 */
private fun parseHnRef(value: String): HnMediaRef? {
    val input = value.trim()
    val path = try {
        URI(input).path ?: input
    } catch (_: Exception) {
        input
    }

    val match = HN_REF_REGEX.matchEntire(input)
        ?: HN_EMBED_PATH_REGEX.matchEntire(path)
        ?: HN_LEGACY_PATH_REGEX.matchEntire(path)
        ?: return null

    return HnMediaRef(
        type = match.groupValues[1],
        id = match.groupValues[2].toIntOrNull() ?: return null,
        season = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull(),
        episode = match.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull()
    )
}

/** URL shown to CloudStream's UI must be an absolute HTTP URL. */
private fun hnDetailUrl(type: String, id: Int): String {
    return if (type == "movie") {
        "https://hnembed.cc/embed/movie/$id"
    } else {
        // Series detail URL. Playback data for each episode remains separate.
        "https://hnembed.cc/embed/tv/$id/1/1"
    }
}

/** Resolve relative and protocol-relative URLs against the final embed page. */
private fun resolveHttpUrl(baseUrl: String, raw: String?): String? {
    val value = raw
        ?.trim()
        ?.replace("&amp;", "&")
        ?.replace("\\/", "/")
        ?.takeIf { it.isNotBlank() }
        ?: return null

    if (value.startsWith("javascript:", ignoreCase = true) ||
        value.startsWith("data:", ignoreCase = true) ||
        value.startsWith("blob:", ignoreCase = true)
    ) return null

    return try {
        val resolved = URI(baseUrl).resolve(value).toString()
        resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    } catch (_: Exception) {
        null
    }
}

private fun Element.attributeCandidate(attribute: String): String? =
    if (hasAttr(attribute)) attr(attribute).trim().takeIf { it.isNotBlank() } else null

/**
 * Sends a lightweight HEAD request to a rendered /pl/ source URL.
 * This is diagnostic only: it does not download the stream or assume its format.
 * Cookies are read from Android's WebView cookie store because the source URL
 * may be session-bound to the player page.
 */
private suspend fun inspectPlayerSource(
    sourceUrl: String,
    playerUrl: String,
    userAgent: String
) {
    try {
        val cookieHeader = try {
            CookieManager.getInstance().getCookie(sourceUrl)
        } catch (_: Exception) {
            null
        }

        val requestHeaders = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*"
        )
        if (!cookieHeader.isNullOrBlank()) {
            requestHeaders["Cookie"] = cookieHeader
        }

        val response = app.head(
            sourceUrl,
            headers = requestHeaders,
            referer = playerUrl,
            timeout = 12L
        )

        val contentType = response.headers["Content-Type"].orEmpty()
        val contentLength = response.headers["Content-Length"].orEmpty()
        val acceptRanges = response.headers["Accept-Ranges"].orEmpty()
        val contentRange = response.headers["Content-Range"].orEmpty()

        ClipboardLogger.logAndCopy(
            "PLAYER SOURCE HEAD",
            "HTTP=${response.code}\n" +
                "finalUrl=${response.url}\n" +
                "contentType=$contentType\n" +
                "contentLength=$contentLength\n" +
                "acceptRanges=$acceptRanges\n" +
                "contentRange=$contentRange\n" +
                "referer=$playerUrl\n" +
                "cookieSent=${!cookieHeader.isNullOrBlank()}"
        )
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        ClipboardLogger.logAndCopy(
            "PLAYER SOURCE HEAD ERROR",
            "${e.javaClass.simpleName}: ${e.message}"
        )
    }
}

/**
 * Loads either the public HNEmbed wrapper or the resolved player page in WebView.
 * It reads the rendered DOM and observable network metadata after page scripts run.
 * It does not synthesize clicks, bypass challenges, or decode protected media payloads.
 */
private suspend fun inspectRenderedIframeUrls(
    pageUrl: String,
    userAgent: String,
    initialReferer: String = "https://hnembed.cc/"
): List<String> {
    return suspendCoroutine<List<String>> { continuation ->
    val activity = try {
        CommonActivity.activity
    } catch (_: Throwable) {
        null
    }

    if (activity == null) {
        ClipboardLogger.logAndCopy(
            "Rendered iframe inspection",
            "Skipped: CommonActivity.activity is null"
        )
        continuation.resume(emptyList())
        return@suspendCoroutine
    }

    val mainHandler = Handler(Looper.getMainLooper())
    mainHandler.post {
        val webView = try {
            WebView(activity)
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy(
                "Rendered iframe inspection",
                "WebView creation failed: ${e.message}"
            )
            continuation.resume(emptyList())
            return@post
        }

        var finished = false
        var pageFinished = false
        var pollCount = 0
        var lastSnapshot = ""
        val iframeUrls = linkedSetOf<String>()
        val subframeRequests = linkedSetOf<String>()
        val resourceUrls = linkedSetOf<String>()
        val exposedMediaUrls = linkedSetOf<String>()
        val observedRequests = linkedSetOf<String>()
        val exposedApiEndpoints = linkedSetOf<String>()
        val networkErrors = linkedSetOf<String>()
        val consoleMessages = linkedSetOf<String>()

        val mediaUrlPattern = Regex(
            """(?i)(\.(m3u8|mpd|mp4|m4s|ts|m4a|aac|mp3|webm)(?:$|[?#])|/segment(?:/|[?#])|/chunk(?:/|[?#])|videoplayback)"""
        )

        fun recordRequest(kind: String, url: String) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            synchronized(observedRequests) {
                if (observedRequests.size < 180) observedRequests.add("$kind $url")
            }
            if (mediaUrlPattern.containsMatchIn(url)) {
                synchronized(resourceUrls) {
                    if (resourceUrls.size < 100) resourceUrls.add(url)
                }
            }
        }

        fun recordNetworkError(value: String) {
            synchronized(networkErrors) {
                if (networkErrors.size < 80) networkErrors.add(value)
            }
        }

        var pollTask: Runnable? = null
        var timeoutTask: Runnable? = null

        fun finish() {
            if (finished) return
            finished = true
            pollTask?.let(mainHandler::removeCallbacks)
            timeoutTask?.let(mainHandler::removeCallbacks)

            // Only iframe destinations and media-like resources are extractor candidates.
            // Keep the complete subresource list diagnostic-only (never feed ads/scripts to extractors).
            val result = linkedSetOf<String>()
            result.addAll(iframeUrls)
            synchronized(resourceUrls) { result.addAll(resourceUrls) }
            synchronized(exposedMediaUrls) { result.addAll(exposedMediaUrls) }

            val requestSnapshot = synchronized(observedRequests) { observedRequests.toList() }
            val apiSnapshot = synchronized(exposedApiEndpoints) { exposedApiEndpoints.toList() }
            val errorSnapshot = synchronized(networkErrors) { networkErrors.toList() }
            val consoleSnapshot = synchronized(consoleMessages) { consoleMessages.toList() }

            ClipboardLogger.logAndCopy(
                "Rendered iframe inspection summary",
                "pageFinished=$pageFinished; polls=$pollCount; " +
                    "iframeUrls=${iframeUrls.size}; subframeRequests=${subframeRequests.size}; " +
                    "mediaResources=${resourceUrls.size}; videoElementUrls=${exposedMediaUrls.size}; " +
                    "observedRequests=${requestSnapshot.size}; " +
                    "apiEndpoints=${apiSnapshot.size}; networkErrors=${errorSnapshot.size}"
            )
            ClipboardLogger.logAndCopy(
                "Rendered iframe candidates",
                result.joinToString("\n").ifBlank {
                    "No public iframe/player/media URL observed after JavaScript execution"
                }
            )
            ClipboardLogger.logAndCopy(
                "Exposed player API attributes",
                apiSnapshot.joinToString("\n").ifBlank { "No data-api URL attribute found" }
            )
            ClipboardLogger.logAndCopy(
                "Observed WebView network requests",
                requestSnapshot.joinToString("\n").ifBlank { "No WebView requests captured" }
            )
            ClipboardLogger.logAndCopy(
                "WebView network errors",
                errorSnapshot.joinToString("\n").ifBlank { "No HTTP/network errors reported by WebView" }
            )
            ClipboardLogger.logAndCopy(
                "Filtered WebView console",
                consoleSnapshot.joinToString("\n").ifBlank { "No console warnings/errors captured" }
            )

            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Exception) {
                // Best-effort WebView cleanup.
            }
            continuation.resume(result.toList())
        }

        timeoutTask = Runnable {
            ClipboardLogger.logAndCopy(
                "Rendered iframe inspection",
                "Timed out after 26 seconds waiting for the rendered iframe/player URL"
            )
            finish()
        }

        pollTask = Runnable {
            if (finished) return@Runnable
            pollCount++

            val javascript = """
                (function() {
                  try {
                    var frames = Array.prototype.slice.call(
                      document.querySelectorAll('iframe,frame')
                    ).map(function(f) {
                      var attrs = Array.prototype.slice.call(f.attributes || [])
                        .map(function(a) { return {name:a.name, value:a.value}; });
                      return {
                        id: f.id || '',
                        name: f.name || '',
                        srcAttr: f.getAttribute('src') || '',
                        src: f.src || '',
                        srcdoc: (f.getAttribute('srcdoc') || '').slice(0,5000),
                        attrs: attrs,
                        outerHTML: (f.outerHTML || '').slice(0,3000)
                      };
                    });
                    var media = Array.prototype.slice.call(
                      document.querySelectorAll('video,source')
                    ).map(function(v) {
                      var video = v.tagName === 'VIDEO';
                      return {
                        tag: v.tagName,
                        srcAttr: v.getAttribute('src') || '',
                        src: v.src || '',
                        currentSrc: video ? (v.currentSrc || '') : '',
                        type: v.getAttribute('type') || '',
                        paused: video ? v.paused : null,
                        readyState: video ? v.readyState : null,
                        networkState: video ? v.networkState : null,
                        videoWidth: video ? v.videoWidth : null,
                        videoHeight: video ? v.videoHeight : null,
                        duration: video && isFinite(v.duration) ? v.duration : null,
                        error: video && v.error ? {
                          code: v.error.code,
                          message: v.error.message || ''
                        } : null
                      };
                    });
                    var bodyText = (document.body && document.body.innerText || '').slice(0,1800);
                    var challengeLike = /captcha|verify you are human|checking your browser|are you a robot|security challenge/i.test(bodyText);
                    var resources = performance.getEntriesByType('resource')
                      .map(function(e) { return e.name; })
                      .filter(function(u) {
                        return /\.m3u8(?:$|[?#])|\.mpd(?:$|[?#])|\.mp4(?:$|[?#])|\/embed\/player\/|\/player\//i.test(u);
                      }).slice(-100);
                    return JSON.stringify({
                      page: location.href,
                      readyState: document.readyState,
                      frameCount: frames.length,
                      frames: frames,
                      media: media,
                      resources: resources,
                      title: document.title || '',
                      bodyText: bodyText,
                      challengeLike: challengeLike
                    });
                  } catch (e) {
                    return JSON.stringify({error:String(e)});
                  }
                })();
            """.trimIndent()

            webView.evaluateJavascript(javascript) { rawResult ->
                if (finished) return@evaluateJavascript

                try {
                    val decoded = JSONTokener(rawResult ?: "null").nextValue() as? String
                    if (!decoded.isNullOrBlank() && decoded != lastSnapshot) {
                        lastSnapshot = decoded
                        val snapshot = JSONObject(decoded)
                        val frameLines = mutableListOf<String>()
                        val frames = snapshot.optJSONArray("frames")

                        if (frames != null) {
                            for (i in 0 until frames.length()) {
                                val frame = frames.optJSONObject(i) ?: continue
                                val attrs = frame.optJSONArray("attrs")
                                val values = linkedSetOf<String>()
                                values.add(frame.optString("srcAttr"))
                                values.add(frame.optString("src"))

                                if (attrs != null) {
                                    for (j in 0 until attrs.length()) {
                                        val attr = attrs.optJSONObject(j) ?: continue
                                        val attrName = attr.optString("name").lowercase()
                                        val attrValue = attr.optString("value")
                                        when (attrName) {
                                            "src", "data-src", "data-url", "data-embed",
                                            "data-player", "data-file", "data-href", "href" -> {
                                                values.add(attrValue)
                                            }
                                            "data-api" -> {
                                                val apiUrl = resolveHttpUrl(pageUrl, attrValue)
                                                if (apiUrl != null) {
                                                    synchronized(exposedApiEndpoints) {
                                                        exposedApiEndpoints.add(apiUrl)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                for (value in values) {
                                    val candidate = resolveHttpUrl(pageUrl, value) ?: continue
                                    if (candidate != pageUrl) iframeUrls.add(candidate)
                                }

                                val srcdoc = frame.optString("srcdoc")
                                if (srcdoc.isNotBlank()) {
                                    val srcdocDoc = Jsoup.parse(srcdoc, pageUrl)
                                    for (nested in srcdocDoc.select("iframe[src],frame[src]")) {
                                        resolveHttpUrl(pageUrl, nested.attr("src"))
                                            ?.let(iframeUrls::add)
                                    }
                                }

                                frameLines.add(
                                    "iframe[$i] id=${frame.optString("id")} " +
                                        "name=${frame.optString("name")} " +
                                        "srcAttr=${frame.optString("srcAttr")} " +
                                        "src=${frame.optString("src")} " +
                                        "srcdoc=${srcdoc.take(500)} " +
                                        "outerHTML=${frame.optString("outerHTML").take(1800)}"
                                )
                            }
                        }

                        ClipboardLogger.logAndCopy(
                            "Rendered iframe DOM poll $pollCount",
                            "page=${snapshot.optString("page")}\n" +
                                "readyState=${snapshot.optString("readyState")}\n" +
                                "frameCount=${snapshot.optInt("frameCount")}\n" +
                                frameLines.joinToString("\n\n").ifBlank { "No iframe nodes" }
                        )

                        val media = snapshot.optJSONArray("media")
                        val mediaStateLines = mutableListOf<String>()
                        if (media != null && media.length() > 0) {
                            for (i in 0 until media.length()) {
                                val item = media.optJSONObject(i) ?: continue
                                val raw = item.optString("currentSrc")
                                    .ifBlank { item.optString("srcAttr") }
                                    .ifBlank { item.optString("src") }

                                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                                    synchronized(exposedMediaUrls) {
                                        if (exposedMediaUrls.size < 40) exposedMediaUrls.add(raw)
                                    }
                                }

                                mediaStateLines.add(
                                    "tag=${item.optString("tag")}; src=${item.optString("src").take(260)}; " +
                                        "currentSrc=${item.optString("currentSrc").take(260)}; " +
                                        "paused=${item.opt("paused")}; readyState=${item.opt("readyState")}; " +
                                        "networkState=${item.opt("networkState")}; " +
                                        "videoSize=${item.opt("videoWidth")}x${item.opt("videoHeight")}; " +
                                        "duration=${item.opt("duration")}; error=${item.opt("error")}"
                                )
                            }
                        }

                        ClipboardLogger.logAndCopy(
                            "Direct player state poll $pollCount",
                            "title=${snapshot.optString("title")}; " +
                                "challengeLike=${snapshot.optBoolean("challengeLike")}\n" +
                                "body=${snapshot.optString("bodyText").take(900)}\n" +
                                mediaStateLines.joinToString("\n").ifBlank {
                                    "No video/source element visible in the top-level player document"
                                }
                        )

                        val resources = snapshot.optJSONArray("resources")
                        if (resources != null) {
                            for (i in 0 until resources.length()) {
                                val raw = resources.optString(i)
                                val resolved = resolveHttpUrl(pageUrl, raw) ?: continue
                                val lower = resolved.lowercase()
                                if (Regex("\\.(m3u8|mpd|mp4)(?:$|[?#])")
                                        .containsMatchIn(lower) ||
                                    lower.contains("/embed/player/") ||
                                    lower.contains("/player/")) {
                                    synchronized(resourceUrls) {
                                        resourceUrls.add(resolved)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy(
                        "Rendered iframe inspection error",
                        e.stackTraceToString()
                    )
                }

                // Poll repeatedly: the wrapper may assign iframe.src asynchronously
                // after sbx.js finishes initializing the page.
                if (pollCount < 20) {
                    pollTask?.let { mainHandler.postDelayed(it, 650L) }
                } else {
                    finish()
                }
            }
        }

        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                userAgentString = userAgent
                // The player landing page explicitly advertises autoStart=true.
                // This only honors its own autoplay setting; no synthetic clicks are sent.
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    if (message != null) {
                        val level = message.messageLevel().toString()
                        if (level == "ERROR" || level == "WARNING") {
                            val text = "$level: ${message.message()} " +
                                "(${message.sourceId()}:${message.lineNumber()})"
                            synchronized(consoleMessages) {
                                if (consoleMessages.size < 60) consoleMessages.add(text)
                            }
                        }
                    }
                    return true
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    ClipboardLogger.logAndCopy(
                        "Rendered WebView page started",
                        url ?: "<null>"
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!pageFinished) {
                        pageFinished = true
                        ClipboardLogger.logAndCopy(
                            "Rendered WebView page finished",
                            url ?: "<null>"
                        )
                        pollTask?.let { mainHandler.postDelayed(it, 800L) }
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request != null) {
                        val url = request.url.toString()
                        val kind = if (request.isForMainFrame) "MAIN" else "SUB"
                        recordRequest("$kind ${request.method}", url)

                        if (!request.isForMainFrame) {
                            synchronized(subframeRequests) {
                                if (subframeRequests.size < 120) subframeRequests.add(url)
                            }
                        }
                    }
                    return null
                }

                override fun onLoadResource(view: WebView?, url: String?) {
                    super.onLoadResource(view, url)
                    if (!url.isNullOrBlank()) recordRequest("LOAD", url)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request != null && errorResponse != null) {
                        recordNetworkError(
                            "HTTP ${errorResponse.statusCode} " +
                                "${if (request.isForMainFrame) "MAIN" else "SUB"} ${request.url}"
                        )
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request != null && error != null) {
                        recordNetworkError(
                            "WebError ${error.errorCode}: ${error.description} " +
                                "${if (request.isForMainFrame) "MAIN" else "SUB"} ${request.url}"
                        )
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    recordNetworkError("SSL error: ${error?.toString() ?: "unknown"}")
                    handler?.cancel()
                }
            }


            timeoutTask?.let { mainHandler.postDelayed(it, 26_000L) }
            ClipboardLogger.logAndCopy(
                "Rendered iframe inspection",
                "Loading HNEmbed wrapper and polling the rendered iframe DOM for up to 13 seconds: $pageUrl"
            )
            ClipboardLogger.logAndCopy(
                "Direct player top-level navigation",
                "url=$pageUrl; referer=$initialReferer; " +
                    "standalone=${pageUrl.contains("/embed/player/")}"
            )
            webView.loadUrl(
                pageUrl,
                mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to initialReferer
                )
            )
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy(
                "Rendered iframe inspection error",
                e.stackTraceToString()
            )
            finish()
        }
    }
    }
}

class HnembedProvider : MainAPI() {
    override var mainUrl = "https://hnembed.cc"
    override var name = "Hnembed"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    private val tmdbHosts = listOf(
        TMDB_BASE_URL,
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun imageUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank() || path == "null") return null
        return "https://wsrv.nl/?url=image.tmdb.org/t/p/$size$path"
    }

    private suspend fun tmdbGet(path: String): JSONObject {
        var lastError: Exception? = null
        val separator = if (path.contains("?")) "&" else "?"

        for (host in tmdbHosts) {
            try {
                val response = app.get(
                    "$host$path${separator}api_key=$TMDB_API_KEY",
                    headers = mapOf("User-Agent" to userAgent)
                )

                if (response.code == 200) {
                    val body = response.text
                    if (body.trimStart().startsWith("{")) return JSONObject(body)
                }

                lastError = IllegalStateException("TMDB HTTP ${response.code}: $host")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("TMDB request failed")
    }

    private fun searchResponse(
        item: JSONObject,
        forcedType: String? = null
    ): SearchResponse? {
        val type = forcedType ?: item.optString("media_type")
        if (type != "movie" && type != "tv") return null

        val id = item.optInt("id", 0)
        if (id <= 0) return null

        val title = if (type == "movie") {
            item.optString("title", item.optString("original_title"))
        } else {
            item.optString("name", item.optString("original_name"))
        }.trim()

        if (title.isBlank()) return null

        // Do not pass hnembed:movie:id as a SearchResponse URL. CloudStream may
        // treat it as a relative URL and prepend mainUrl to it.
        return newMovieSearchResponse(
            title,
            hnDetailUrl(type, id),
            if (type == "tv") TvType.TvSeries else TvType.Movie
        ) {
            posterUrl = imageUrl(item.optString("poster_path"))
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val json = tmdbGet("${request.data}?page=$page")
            val results = json.optJSONArray("results")
                ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

            val fallbackType = when {
                request.data.startsWith("/movie") -> "movie"
                request.data.startsWith("/tv") -> "tv"
                else -> null
            }

            val items = ArrayList<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                searchResponse(item, fallbackType)?.let(items::add)
            }

            newHomePageResponse(
                request.name,
                items.distinctBy { it.url },
                hasNext = page < json.optInt("total_pages", 1)
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ClipboardLogger.logAndCopy("getMainPage", e.stackTraceToString())
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = tmdbGet("/search/multi?query=$encoded")
            val results = json.optJSONArray("results") ?: return emptyList()

            val output = ArrayList<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                searchResponse(item)?.let(output::add)
            }

            output.distinctBy { it.url }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ClipboardLogger.logAndCopy("search", e.stackTraceToString())
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseHnRef(url) ?: run {
            ClipboardLogger.logAndCopy("load", "Invalid provider URL/data: $url")
            return null
        }

        return try {
            val json = tmdbGet("/${ref.type}/${ref.id}")
            if (json.has("status_code")) {
                throw IllegalStateException(json.optString("status_message"))
            }

            val title = if (ref.type == "movie") {
                json.optString("title", json.optString("original_title"))
            } else {
                json.optString("name", json.optString("original_name"))
            }.ifBlank { "Unknown title" }

            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val poster = imageUrl(json.optString("poster_path"))
            val date = if (ref.type == "movie") {
                json.optString("release_date")
            } else {
                json.optString("first_air_date")
            }
            val year = date.take(4).toIntOrNull()
            val canonicalUrl = hnDetailUrl(ref.type, ref.id)

            if (ref.type == "movie") {
                newMovieLoadResponse(
                    title,
                    canonicalUrl,
                    TvType.Movie,
                    "hnembed:movie:${ref.id}"
                ) {
                    posterUrl = poster
                    plot = overview
                    this.year = year
                }
            } else {
                val episodes = ArrayList<Episode>()
                val seasonCount = json.optInt("number_of_seasons", 0)

                for (seasonNumber in 1..seasonCount) {
                    try {
                        val seasonJson = tmdbGet("/tv/${ref.id}/season/$seasonNumber")
                        val episodeArray = seasonJson.optJSONArray("episodes") ?: continue

                        for (i in 0 until episodeArray.length()) {
                            val episodeJson = episodeArray.optJSONObject(i) ?: continue
                            val season = episodeJson.optInt("season_number", seasonNumber)
                            val episode = episodeJson.optInt("episode_number", i + 1)
                            val episodeName = episodeJson.optString("name")
                                .ifBlank { "Episode $episode" }

                            episodes.add(
                                newEpisode("hnembed:tv:${ref.id}:$season:$episode") {
                                    name = episodeName
                                    this.season = season
                                    this.episode = episode
                                    description = episodeJson.optString("overview")
                                        .takeIf { it.isNotBlank() }
                                    posterUrl = imageUrl(
                                        episodeJson.optString("still_path"),
                                        "w500"
                                    )
                                }
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        ClipboardLogger.logAndCopy(
                            "Season $seasonNumber",
                            e.message ?: "Unable to load season"
                        )
                    }
                }

                newTvSeriesLoadResponse(
                    title,
                    canonicalUrl,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                    plot = overview
                    this.year = year
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ClipboardLogger.logAndCopy("load detail", e.stackTraceToString())
            null
        }
    }

    /**
     * Extract only URLs exposed in the public embed markup and delegate player
     * hosts to CloudStream's registered extractors. This method does not try to
     * reverse proprietary player tokens or protected media delivery.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.clear()
        ClipboardLogger.logAndCopy("BUILD MARKER", "HNEMBED-RETURN-FIX-2026-11-24-V15")
        ClipboardLogger.logAndCopy(
            "loadLinks START",
            "data=$data; provider=$name; casting=$isCasting"
        )

        val ref = parseHnRef(data) ?: run {
            ClipboardLogger.logAndCopy("loadLinks", "Invalid data: $data")
            return false
        }

        if (ref.type == "tv" && (ref.season == null || ref.episode == null)) {
            ClipboardLogger.logAndCopy(
                "loadLinks",
                "TV playback requires season and episode: $data"
            )
            return false
        }

        // hnembed.net failed DNS resolution in the user's test; use the working .cc host.
        val mirrors = listOf("https://hnembed.cc")
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        var emitted = 0
        val tried = linkedSetOf<String>()

        for (mirror in mirrors) {
            val embedUrl = if (ref.type == "movie") {
                "$mirror/embed/movie/${ref.id}"
            } else {
                "$mirror/embed/tv/${ref.id}/${ref.season}/${ref.episode}"
            }

            ClipboardLogger.logAndCopy("HNEmbed target", embedUrl)

            // First ask CloudStream whether an installed extractor supports HNEmbed directly.
            try {
                loadExtractor(embedUrl, "$mirror/", subtitleCallback) { link ->
                    callback.invoke(link)
                    emitted++
                    ClipboardLogger.logAndCopy(
                        "Extractor link",
                        "${link.name} | ${link.url.take(250)}"
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ClipboardLogger.logAndCopy(
                    "HNEmbed extractor",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }

            if (emitted > 0) return true

            try {
                val response = app.get(
                    embedUrl,
                    headers = headers,
                    referer = "$mirror/"
                )
                val html = response.text
                val finalUrl = response.url.toString()

                ClipboardLogger.logAndCopy(
                    "Embed response",
                    "HTTP ${response.code}; final=$finalUrl; bytes=${html.length}"
                )
                if (response.code !in 200..299) continue

                val doc = Jsoup.parse(html, finalUrl)
                val iframeElements = doc.select("iframe, frame")
                val videoElements = doc.select("video, source")
                val embedElements = doc.select("embed, object")
                val scriptElements = doc.select("script")

                ClipboardLogger.logAndCopy(
                    "HTML scan",
                    "title=${doc.title()}\n" +
                        "finalUrl=$finalUrl\n" +
                        "htmlBytes=${html.length}\n" +
                        "iframeCount=${iframeElements.size}\n" +
                        "videoSourceCount=${videoElements.size}\n" +
                        "embedObjectCount=${embedElements.size}\n" +
                        "scriptCount=${scriptElements.size}"
                )

                val staticIframeDetails = iframeElements.mapIndexed { index, iframe ->
                    val attrs = iframe.attributes().asList().joinToString("; ") {
                        "${it.key}=${it.value}"
                    }
                    "iframe[$index] id=${iframe.id()} name=${iframe.attr("name")} attrs={$attrs} outer=${iframe.outerHtml().take(2200)}"
                }
                ClipboardLogger.logAndCopy(
                    "Static iframe details",
                    staticIframeDetails.joinToString("\n\n").ifBlank { "No iframe elements" }
                )

                val externalScripts = scriptElements
                    .filter { it.hasAttr("src") }
                    .map { it.absUrl("src") }
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .distinct()
                    .take(25)

                ClipboardLogger.logAndCopy(
                    "External scripts",
                    externalScripts.joinToString("\n").ifBlank { "No external scripts found" }
                )

                // Only inspect likely player/media elements and data-* attributes.
                val scanElements = doc.select(
                    "iframe, frame, video, source, embed, object, " +
                        "[data-src], [data-lazy-src], [data-url], [data-embed], " +
                        "[data-player], [data-video], [data-file], [data-href], [data-link]"
                )
                val attributeNames = listOf(
                    "src",
                    "data-src",
                    "data-lazy-src",
                    "data-url",
                    "data-embed",
                    "data-player",
                    "data-video",
                    "data-file",
                    "data-href",
                    "data-link",
                    "href",
                    "data"
                )

                // Keep URL plus the HTML element/attribute that exposed it.
                val candidates = linkedMapOf<String, String>()
                var directPlayerPageUrl: String? = null
                var directPlayerReferer: String = finalUrl

                /*
                 * HNEmbed's public wrapper exposes its source endpoint on the
                 * player iframe as data-api. Use that exact DOM attribute rather
                 * than hard-coding an undocumented URL pattern for every host.
                 * This step only reads the public API response and its returned
                 * iframe/player URL; it does not bypass CAPTCHA or decode media.
                 */
                val playerIframe = iframeElements.firstOrNull {
                    it.id() == "player_iframe"
                } ?: iframeElements.firstOrNull {
                    it.hasAttr("data-api")
                }

                val apiRaw = playerIframe?.attr("data-api")
                    ?.takeIf { it.isNotBlank() }
                val apiUrl = resolveHttpUrl(finalUrl, apiRaw)

                ClipboardLogger.logAndCopy(
                    "HNEmbed data-api",
                    apiUrl ?: "The player iframe did not expose a usable data-api attribute"
                )

                if (apiUrl != null) {
                    try {
                        val apiResponse = app.get(
                            apiUrl,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Accept" to "application/json,text/plain,*/*",
                                "Referer" to finalUrl,
                                "Origin" to mirror
                            )
                        )
                        val apiText = apiResponse.text

                        ClipboardLogger.logAndCopy(
                            "HNEmbed vs_src response",
                            "HTTP ${apiResponse.code}; final=${apiResponse.url}; " +
                                "bytes=${apiText.length}\n${apiText.take(1800)}"
                        )

                        val srcFromJson = try {
                            JSONObject(apiText).optString("src")
                                .takeIf { it.isNotBlank() && it != "null" }
                        } catch (_: Exception) {
                            null
                        }
                        val srcFromRegex = Regex(
                            """\"src\"\s*:\s*\"([^\"]+)\""""
                        ).find(apiText)?.groupValues?.getOrNull(1)

                        val srcRaw = (srcFromJson ?: srcFromRegex)
                            ?.replace("\\/", "/")
                            ?.replace("\\u0026", "&")
                        val srcUrl = resolveHttpUrl(apiUrl, srcRaw)

                        ClipboardLogger.logAndCopy(
                            "HNEmbed src result",
                            srcUrl ?: "No valid HTTP src field was returned"
                        )

                        if (srcUrl != null) {
                            candidates[srcUrl] = "HNEmbed data-api src"

                            // Inspect the public landing response for a playerUrl
                            // field. This is diagnostic; the normal extractor loop
                            // below will handle the returned URL if one is present.
                            try {
                                val landing = app.get(
                                    srcUrl,
                                    headers = mapOf(
                                        "User-Agent" to userAgent,
                                        "Accept" to "text/html,application/json,*/*",
                                        "Referer" to "$mirror/"
                                    )
                                )
                                val landingText = landing.text

                                ClipboardLogger.logAndCopy(
                                    "HNEmbed src landing",
                                    "HTTP ${landing.code}; final=${landing.url}; " +
                                        "bytes=${landingText.length}\n${landingText.take(1800)}"
                                )

                                val playerFromJson = try {
                                    JSONObject(landingText).optString("playerUrl")
                                        .takeIf { it.isNotBlank() && it != "null" }
                                } catch (_: Exception) {
                                    null
                                }
                                val playerFromRegex = Regex(
                                    """\"playerUrl\"\s*:\s*\"([^\"]+)\""""
                                ).find(landingText)?.groupValues?.getOrNull(1)

                                val playerRaw = (playerFromJson ?: playerFromRegex)
                                    ?.replace("\\/", "/")
                                    ?.replace("\\u0026", "&")
                                val playerUrl = resolveHttpUrl(srcUrl, playerRaw)

                                ClipboardLogger.logAndCopy(
                                    "HNEmbed resolved playerUrl",
                                    playerUrl ?: "No playerUrl field found in the src landing page"
                                )

                                if (playerUrl != null) {
                                    candidates[playerUrl] = "HNEmbed src landing playerUrl"
                                    directPlayerPageUrl = playerUrl
                                    directPlayerReferer = srcUrl
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                ClipboardLogger.logAndCopy(
                                    "HNEmbed src landing error",
                                    "${e.javaClass.simpleName}: ${e.message}"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        ClipboardLogger.logAndCopy(
                            "HNEmbed vs_src error",
                            "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }

                for (element in scanElements) {
                    for (attribute in attributeNames) {
                        val raw = element.attributeCandidate(attribute) ?: continue
                        val candidate = resolveHttpUrl(finalUrl, raw) ?: continue
                        if (!candidates.containsKey(candidate)) {
                            candidates[candidate] = "<${element.tagName()}> @$attribute"
                        }
                    }
                }

                // Some players publish their URL in OpenGraph/Twitter player metadata.
                val playerMeta = doc.select(
                    "meta[property=og:video], meta[property=og:video:url], " +
                        "meta[name=twitter:player], meta[name=twitter:player:stream]"
                )
                for (meta in playerMeta) {
                    val raw = meta.attr("content")
                    val candidate = resolveHttpUrl(finalUrl, raw) ?: continue
                    if (!candidates.containsKey(candidate)) {
                        candidates[candidate] = "<meta ${meta.attr("property").ifBlank { meta.attr("name") }}> @content"
                    }
                }

                ClipboardLogger.logAndCopy(
                    "HTML candidates",
                    candidates.entries.take(60).joinToString("\n") {
                        "${it.value} -> ${it.key.take(450)}"
                    }.ifBlank { "No iframe/video/source/data-* candidates in static HTML" }
                )

                // Capture short surrounding snippets for useful inline-JS diagnostics.
                // URLs in scripts are only promoted when they clearly look like player URLs.
                val keywordRegex = Regex(
                    "(?i)(iframe|player|embed|video|source|stream|manifest|m3u8|mpd)"
                )
                val inlineScriptHints = ArrayList<String>()
                val scriptUrlRegex = Regex("""https?://[^\"'\s<>]+""")
                for (script in scriptElements.filter { !it.hasAttr("src") }) {
                    val content = script.data().ifBlank { script.html() }
                        .replace("\\/", "/")
                    val keyMatch = keywordRegex.find(content)
                    if (keyMatch != null && inlineScriptHints.size < 10) {
                        val start = (keyMatch.range.first - 180).coerceAtLeast(0)
                        val end = (keyMatch.range.last + 350).coerceAtMost(content.length)
                        inlineScriptHints.add(content.substring(start, end))
                    }

                    for (urlMatch in scriptUrlRegex.findAll(content)) {
                        val raw = urlMatch.value.trimEnd(')', ']', '}', ',', ';')
                        val lower = raw.lowercase()
                        val looksLikePlayer = listOf(
                            "/embed/", "/player", "video", "stream", ".m3u8", ".mpd", ".mp4"
                        ).any { lower.contains(it) }
                        if (!looksLikePlayer) continue

                        val candidate = resolveHttpUrl(finalUrl, raw) ?: continue
                        if (!candidates.containsKey(candidate)) {
                            candidates[candidate] = "inline-script URL"
                        }
                    }
                }

                ClipboardLogger.logAndCopy(
                    "Inline script hints",
                    inlineScriptHints.joinToString("\n\n--- HINT ---\n\n").ifBlank {
                        "No relevant inline player script found"
                    }
                )

                ClipboardLogger.logAndCopy(
                    "All candidates",
                    candidates.entries.take(80).joinToString("\n") {
                        "${it.value} -> ${it.key.take(450)}"
                    }.ifBlank { "No candidates found" }
                )

                for ((candidate, sourceDescription) in candidates) {
                    if (!tried.add(candidate)) continue

                    ClipboardLogger.logAndCopy(
                        "Embed candidate",
                        "$sourceDescription -> ${candidate.take(450)}"
                    )

                    val directType = when {
                        Regex("\\.m3u8(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(candidate) ->
                            ExtractorLinkType.M3U8
                        Regex("\\.mpd(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(candidate) ->
                            ExtractorLinkType.DASH
                        Regex("\\.mp4(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(candidate) ->
                            ExtractorLinkType.VIDEO
                        else -> null
                    }

                    if (directType != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "HNEmbed",
                                url = candidate,
                                type = directType
                            ) {
                                referer = finalUrl
                                quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to finalUrl
                                )
                            }
                        )
                        emitted++
                        continue
                    }

                    val beforeCandidate = emitted
                    ClipboardLogger.logAndCopy(
                        "Extractor attempt",
                        "start=$candidate; referer=$finalUrl"
                    )
                    try {
                        loadExtractor(candidate, finalUrl, subtitleCallback) { link ->
                            callback.invoke(link)
                            emitted++
                            ClipboardLogger.logAndCopy(
                                "Extractor link",
                                "${link.name} | ${link.url.take(250)}"
                            )
                        }
                        ClipboardLogger.logAndCopy(
                            "Extractor result",
                            "candidate=$candidate; callbacks=${emitted - beforeCandidate}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        ClipboardLogger.logAndCopy(
                            "Candidate extractor",
                            "${candidate.take(250)} | ${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }

                // Prefer loading the returned playerUrl as the WebView's top-level page.
                // This removes the cross-origin HNEmbed wrapper from the frame ancestry,
                // allowing us to diagnose the player without trying to access another origin.
                if (emitted == 0 && directPlayerPageUrl != null) {
                    val playerPage = directPlayerPageUrl!!
                    ClipboardLogger.logAndCopy(
                        "Direct player inspection start",
                        "url=$playerPage; referer=$directPlayerReferer"
                    )

                    val renderedCandidates = inspectRenderedIframeUrls(
                        playerPage,
                        userAgent,
                        directPlayerReferer
                    )

                    ClipboardLogger.logAndCopy(
                        "Direct player inspection candidates",
                        renderedCandidates.joinToString("\n").ifBlank {
                            "No public iframe or media URL exposed by the direct player page"
                        }
                    )

                    for (candidate in renderedCandidates) {
                        if (!tried.add(candidate)) continue

                        ClipboardLogger.logAndCopy(
                            "Direct player candidate",
                            candidate.take(450)
                        )

                        // The V10 log showed a rendered <source> URL under /pl/.
                        // Inspect its response headers before deciding whether it is
                        // HLS, DASH, MP4, or a custom/HTML endpoint.
                        if (candidate.contains("/pl/", ignoreCase = true)) {
                            inspectPlayerSource(candidate, playerPage, userAgent)
                        }

                        val directType = when {
                            Regex("\\.m3u8(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(candidate) ->
                                ExtractorLinkType.M3U8
                            Regex("\\.mpd(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(candidate) ->
                                ExtractorLinkType.DASH
                            Regex("\\.mp4(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(candidate) ->
                                ExtractorLinkType.VIDEO
                            else -> null
                        }

                        if (directType != null) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "HNEmbed",
                                    url = candidate,
                                    type = directType
                                ) {
                                    referer = playerPage
                                    quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "User-Agent" to userAgent,
                                        "Referer" to playerPage
                                    )
                                }
                            )
                            emitted++
                            continue
                        }

                        val beforeCandidate = emitted
                        try {
                            loadExtractor(candidate, playerPage, subtitleCallback) { link ->
                                callback.invoke(link)
                                emitted++
                                ClipboardLogger.logAndCopy(
                                    "Direct player extractor link",
                                    "${link.name} | ${link.url.take(250)}"
                                )
                            }
                            ClipboardLogger.logAndCopy(
                                "Direct player extractor result",
                                "candidate=$candidate; callbacks=${emitted - beforeCandidate}"
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            ClipboardLogger.logAndCopy(
                                "Direct player extractor error",
                                "${candidate.take(250)} | ${e.javaClass.simpleName}: ${e.message}"
                            )
                        }
                    }
                } else if (emitted == 0 && iframeElements.isNotEmpty()) {
                    // Fallback only if data-api/playerUrl was not exposed.
                    val renderedCandidates = inspectRenderedIframeUrls(
                        finalUrl,
                        userAgent,
                        "$mirror/"
                    )

                    for (candidate in renderedCandidates) {
                        if (!tried.add(candidate)) continue

                        ClipboardLogger.logAndCopy(
                            "Rendered embed candidate",
                            candidate.take(450)
                        )

                        val beforeCandidate = emitted
                        try {
                            loadExtractor(candidate, finalUrl, subtitleCallback) { link ->
                                callback.invoke(link)
                                emitted++
                                ClipboardLogger.logAndCopy(
                                    "Rendered extractor link",
                                    "${link.name} | ${link.url.take(250)}"
                                )
                            }
                            ClipboardLogger.logAndCopy(
                                "Rendered extractor result",
                                "candidate=$candidate; callbacks=${emitted - beforeCandidate}"
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            ClipboardLogger.logAndCopy(
                                "Rendered candidate extractor",
                                "${candidate.take(250)} | ${e.javaClass.simpleName}: ${e.message}"
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ClipboardLogger.logAndCopy(
                    "Embed fetch/parse",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }

            if (emitted > 0) return true
        }

        ClipboardLogger.logAndCopy(
            "No playable source",
            "HNEmbed returned no public media URL handled by installed CloudStream extractors. " +
                "Check Direct player state polls, Observed WebView network requests and WebView network errors. " +
                "The HTML player page is not reported as a video link." 
        )
        return false
    }
}
