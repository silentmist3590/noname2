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

// ... (TmdbConfig and ApiServices remain the same) ...

class VidsrcsuProvider : MainAPI() {
    // ... (mainPage, search, load remain the same) ...

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
                val js = """
                    (function() {
                        window.__captured_m3u8 = null;

                        // Hook fetch
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

                        // Hook XHR
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

                        // Hook WebSocket
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

                        // PerformanceObserver for WASM
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

                        // Auto play
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

        val finalUrl = if (url.contains("?")) "$url&autoplay=true" else "$url?autoplay=true"
        val extraHeaders = mapOf("Referer" to referer)
        webView.loadUrl(finalUrl, extraHeaders)

        val result = withTimeoutOrNull(45000) { // Increased timeout to 45s
            while (isActive) {
                delay(1000)
                var foundUrl: String? = null
                val jsResult = CompletableDeferred<String>()
                webView.evaluateJavascript("window.__captured_m3u8") { value ->
                    jsResult.complete(value)
                }
                val value = jsResult.await()
                if (value != null && value != "null" && value.length > 2) {
                    foundUrl = value.substring(1, value.length - 1)
                }

                if (foundUrl != null) {
                    return@withTimeoutOrNull foundUrl
                }
                
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

    // ... (loadLinks remains the same) ...
}
