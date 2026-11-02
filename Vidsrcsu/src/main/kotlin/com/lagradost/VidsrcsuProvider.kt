package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

// ... (Giữ nguyên phần TmdbConfig và ApiServices như cũ) ...

class VidsrcsuProvider : MainAPI() {
    // ... (Giữ nguyên phần mainPage, search, load) ...

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")
        
        val idMatch = Regex("""vidsrcsu:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(data)
        if (idMatch == null) {
            ClipboardLogger.logAndCopy("Error", "Invalid data format: $data")
            return false
        }
        val type = idMatch.groupValues[1]
        val tmdbId = idMatch.groupValues[2].toIntOrNull() ?: return false
        val season = idMatch.groupValues.getOrNull(3)?.toIntOrNull()
        val episode = idMatch.groupValues.getOrNull(4)?.toIntOrNull()

        ApiServices.sendTraktScrobble("start", type, tmdbId, 0f, season, episode)

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to "https://vidsrc.su/",
            "Origin" to "https://vidsrc.su"
        )

        try {
            val embedUrl = if (type == "movie") {
                "https://vidsrc.su/embed/movie/$tmdbId"
            } else {
                if (season == null || episode == null) return false
                "https://vidsrc.su/embed/tv/$tmdbId/$season/$episode"
            }
            
            ClipboardLogger.logAndCopy("API Request", "Embed URL: $embedUrl")
            val embedResponse = app.get(embedUrl, headers = headers).text
            ClipboardLogger.logAndCopy("Embed Response", embedResponse)

            // BƯỚC 1: Tìm iframe trong trang embed (thường trỏ đến cloudnestra.com)
            val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val iframeUrl = iframeRegex.find(embedResponse)?.groupValues?.get(1)
            
            if (iframeUrl == null) {
                ClipboardLogger.logAndCopy("Error", "Không tìm thấy iframe trong trang embed của Vidsrc.su.")
                return false
            }
            ClipboardLogger.logAndCopy("Iframe Found", iframeUrl)

            // BƯỚC 2: Gọi iframe để lấy hash rcp (thường nằm trong một thẻ div hoặc script)
            val iframeResponse = app.get(iframeUrl, headers = headers).text
            ClipboardLogger.logAndCopy("Iframe Response", iframeResponse)

            // Tìm hash rcp (có thể nằm trong thẻ div#reporting_content+div hoặc một biến JS)
            var rcpHash: String? = null
            // Thử cách 1: Tìm trong thẻ div (như code cũ)
            val divRegex = Regex("""<div[^>]*id=["']reporting_content["'][^>]*>.*?<div[^>]*id=["']([^"']+)["'][^>]*>([^<]+)</div>""", RegexOption.DOT_MATCHES_ALL)
            val divMatch = divRegex.find(iframeResponse)
            if (divMatch != null) {
                val encType = divMatch.groupValues[1]
                val encodedUrl = divMatch.groupValues[2]
                // Cần hàm giải mã ở đây (như code cũ)
                // finalUrl = decodeUrl(encType, encodedUrl)
            }

            // Thử cách 2: Tìm trực tiếp link prorcp trong iframe response
            val prorcpRegex = Regex("""(https?://[^"']*\/prorcp\/[^"']+)""", RegexOption.IGNORE_CASE)
            val prorcpUrl = prorcpRegex.find(iframeResponse)?.groupValues?.get(1)
            
            if (prorcpUrl != null) {
                ClipboardLogger.logAndCopy("Prorcp URL Found", prorcpUrl)
                // Gọi prorcp để lấy link m3u8
                val prorcpResponse = app.get(prorcpUrl, headers = headers).text
                ClipboardLogger.logAndCopy("Prorcp Response", prorcpResponse)
                
                // Tìm link m3u8 trong prorcp response
                val m3u8Regex = Regex("""(https?://[^"']*\.m3u8[^"']*)""", RegexOption.IGNORE_CASE)
                val m3u8Url = m3u8Regex.find(prorcpResponse)?.groupValues?.get(1)
                
                if (m3u8Url != null) {
                    ClipboardLogger.logAndCopy("Success", "M3U8 URL: $m3u8Url")
                    // Gửi link đến trình phát
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = "Vidsrcsu",
                            url = m3u8Url,
                            referer = "https://cloudnestra.com/", // Referer thường được yêu cầu
                            quality = Qualities.P1080.value,
                            type = ExtractorLinkType.M3U8,
                            headers = mapOf("Referer" to "https://cloudnestra.com/")
                        )
                    )
                    return true
                }
            }

            ClipboardLogger.logAndCopy("Error", "Không thể tìm thấy link m3u8 theo cấu trúc mới của Vidsrc.su.")
            return false
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString()}")
            return false
        }
    }
}
