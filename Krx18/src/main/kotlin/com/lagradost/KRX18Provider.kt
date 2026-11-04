package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KRX18Provider : MainAPI() {
    override var mainUrl = "https://krx18.com"
    override var name = "KRX18"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private fun hdr(ref: String? = null) = mapOf("User-Agent" to ua, "Referer" to (ref ?: "$mainUrl/"))

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Latest 18+",
        "$mainUrl/genre/xxx" to "X Clip",
        "$mainUrl/genre/eng-sub" to "Eng Sub",
        "$mainUrl/genre/japan" to "Japan",
        "$mainUrl/genre/korea" to "Korea",
        "$mainUrl/genre/thailand" to "Thailand",
        "$mainUrl/genre/philippines" to "Philippines",
        "$mainUrl/genre/china" to "China",
        "$mainUrl/genre/usa" to "USA",
        "$mainUrl/genre/uk" to "UK",
        "$mainUrl/genre/france" to "France",
        "$mainUrl/genre/italy" to "Italy"
    )

    // ---------------------------------------------------------------- danh sách / tìm kiếm
    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".data h3 a") ?: selectFirst(".poster a[href]") ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (!href.contains("/movies/")) return null
        val title = selectFirst(".data h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val img = selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src")
        val year = selectFirst(".data span")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
        }
    }

    private fun parseGrid(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        // article.item = lưới chính (bỏ qua widget sidebar "w_item_b")
        val seen = HashSet<String>()
        return doc.select("article.item").mapNotNull { it.toCard() }.filter { seen.add(it.url) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "${request.data}/" else "${request.data}/page/$page/"
        return try {
            val res = app.get(url, headers = hdr())
            val items = parseGrid(res.document)
            ClipboardLogger.logAndCopy("MainPage ${request.name} p$page", "HTTP ${res.code}, items=${items.size}\nposter0=${items.firstOrNull()?.posterUrl}")
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("MainPage Error", "$url -> ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val res = app.get("$mainUrl/?s=$q", headers = hdr())
            val items = parseGrid(res.document).ifEmpty {
                // trang kết quả tìm kiếm cũng có thể dùng widget w_item_b
                res.document.select("article.w_item_b").mapNotNull { it.toCard() }
            }
            ClipboardLogger.logAndCopy("Search", "q=$query HTTP ${res.code}, items=${items.size}")
            items
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Search Error", "$query -> ${e.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------------- chi tiết
    override suspend fun load(url: String): LoadResponse? {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Load", url)
        return try {
            val res = app.get(url, headers = hdr())
            val doc = res.document
            val title = doc.selectFirst("div.sheader h1")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
            val poster = doc.selectFirst("div.sheader div.poster img")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = doc.selectFirst("div.sheader .wp-content")?.text()?.trim()
                ?: doc.selectFirst("div#info .wp-content")?.text()?.trim()
            val year = doc.selectFirst("div.sheader span.date")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            val duration = doc.selectFirst("div.sheader span.runtime")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            val tags = doc.select("div.sgeneros a").map { it.text().trim() }.filter { it.isNotEmpty() }
            val rating = doc.selectFirst("#repimdb strong")?.text()?.toDoubleOrNull()

            // Các server phát (DooPlay): li.dooplay_player_option data-post/data-nume/data-type
            val opts = doc.select("li.dooplay_player_option")
            val post = opts.firstOrNull()?.attr("data-post")?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("meta#dooplay-ajax-counter")?.attr("data-postid") ?: ""
            val type = opts.firstOrNull()?.attr("data-type")?.takeIf { it.isNotEmpty() } ?: "movie"
            val nums = opts.map { it.attr("data-nume") }.filter { it.isNotEmpty() }.ifEmpty { listOf("1") }
            val data = "$post|${nums.joinToString(",")}|$type"
            ClipboardLogger.logAndCopy("Load info", "title=$title\npost=$post nums=$nums type=$type\nposter=$poster")

            val recs = doc.select("div.srelacionados article").mapNotNull { art ->
                val a = art.selectFirst("a[href]") ?: return@mapNotNull null
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val img = art.selectFirst("img")
                val t = img?.attr("alt")?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                newMovieSearchResponse(t, href, TvType.NSFW) { this.posterUrl = fixUrlNull(img?.attr("src")) }
            }

            newMovieLoadResponse(title, url, TvType.NSFW, data) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.duration = duration
                this.tags = tags
                rating?.let { this.score = Score.from10(it) }
                this.recommendations = recs
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Load Error", "$url -> ${e.message}\n${e.stackTraceToString().take(800)}")
            null
        }
    }

    // ---------------------------------------------------------------- phát
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks Start", data)
        val parts = data.split("|")
        val post = parts.getOrNull(0).orEmpty()
        val nums = parts.getOrNull(1).orEmpty().split(",").filter { it.isNotEmpty() }
        val type = parts.getOrNull(2)?.ifEmpty { null } ?: "movie"
        if (post.isEmpty() || nums.isEmpty()) {
            ClipboardLogger.logAndCopy("Error", "Thiếu post/nume: $data")
            return false
        }
        var found = false
        for (n in nums) {
            try {
                val apiUrl = "$mainUrl/wp-json/dooplayer/v2/$post/$type/$n"
                val r = app.get(apiUrl, headers = hdr() + mapOf("Accept" to "application/json"))
                val embed = JSONObject(r.text).optString("embed_url")
                ClipboardLogger.logAndCopy("Server $n", "API HTTP ${r.code}\nembed=$embed")
                if (embed.isEmpty()) continue
                val ok = if (embed.contains("playkrx18.site")) {
                    stream9(embed, "KRX18 S$n", subtitleCallback, callback)
                } else {
                    try {
                        loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ClipboardLogger.logAndCopy("Server $n Error", "loadExtractor: ${e.message}")
                        false
                    }
                }
                if (ok) found = true
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Server $n Exception", "${e.message}\n${e.stackTraceToString().take(600)}")
            }
        }
        if (!found) ClipboardLogger.logAndCopy("Error", "Không có server nào phát được.")
        return found
    }

    // ---------------------------------------------------------------- player 9stream (playkrx18.site)
    private val keyBase = "7bd30d612d2bb1bda34743f8608e68c"
    private val ivBase = "v3M9zQ2pX7k"

    private fun gcm(mode: Int, step: Int, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            mode,
            SecretKeySpec((keyBase + step).toByteArray(Charsets.UTF_8), "AES"),
            GCMParameterSpec(128, (ivBase + step).toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(input)
    }

    private fun dec(b64: String, step: Int): String =
        String(gcm(Cipher.DECRYPT_MODE, step, Base64.decode(b64, Base64.DEFAULT)), Charsets.UTF_8)

    private fun enc(text: String, step: Int): String =
        Base64.encodeToString(gcm(Cipher.ENCRYPT_MODE, step, text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    private suspend fun stream9(
        embed: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val page = app.get(embed, headers = hdr("$mainUrl/")).text
        val idEnc = Regex("""idfile_enc\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
        val userEnc = Regex("""idUser_enc\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
        val api = Regex("""DOMAIN_API\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
        val subsRaw = Regex("""data_subs\s*=\s*'([^']*)'""").find(page)?.groupValues?.get(1).orEmpty()
        ClipboardLogger.logAndCopy("9stream page", "idfile_enc=${idEnc?.take(20)} idUser_enc=${userEnc?.take(20)}\napi=$api\nsubs=${subsRaw.take(200)}")
        if (idEnc == null || userEnc == null || api == null) {
            ClipboardLogger.logAndCopy("Error", "Không đọc được thông tin player 9stream (trang embed đổi cấu trúc?)\n" + page.take(500))
            return false
        }
        val idFile = dec(idEnc, 1)
        val idUser = dec(userEnc, 2)
        val payload = "{\"idfile\":\"$idFile\",\"iduser\":\"$idUser\",\"domain_play\":\"$mainUrl\",\"unixTimestamp\":${System.currentTimeMillis() / 1000}}"
        val body = enc(payload, 3)
        val r = app.post(
            api,
            data = mapOf("data" to body),
            headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        )
        ClipboardLogger.logAndCopy("9stream getplay", "HTTP ${r.code}\n${r.text.take(600)}")
        val json = JSONObject(r.text)
        if (json.optString("status") != "success") {
            ClipboardLogger.logAndCopy("Error", "getplay không thành công: ${r.text.take(300)}")
            return false
        }
        var playlist = json.optString("data")
        if (!playlist.startsWith("http")) {
            // dự phòng: dữ liệu trả về có thể được mã hóa
            playlist = try { dec(playlist, 4) } catch (e: Exception) { playlist }
        }
        if (!playlist.startsWith("http")) {
            ClipboardLogger.logAndCopy("Error", "Playlist không hợp lệ: ${playlist.take(120)}")
            return false
        }

        // Phụ đề (JWPlayer tracks): [{"file": "...", "label": "English", "kind": "captions"}, ...]
        if (subsRaw.isNotBlank()) {
            try {
                val arr = JSONArray(subsRaw)
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val file = t.optString("file").ifEmpty { t.optString("src") }
                    if (file.isEmpty()) continue
                    val kind = t.optString("kind", "captions")
                    if (kind == "thumbnails") continue
                    val lbl = t.optString("label").ifEmpty { "Subtitle ${i + 1}" }
                    subtitleCallback.invoke(SubtitleFile(lbl, fixUrl(file)))
                    ClipboardLogger.logAndCopy("Subtitle", "$lbl -> $file")
                }
            } catch (e: Exception) {
                ClipboardLogger.logAndCopy("Subtitle Error", "${e.message}\n${subsRaw.take(200)}")
            }
        }

        val q = Regex("""/hls/(\d{3,4})/""").find(playlist)?.groupValues?.get(1)?.toIntOrNull()
        ClipboardLogger.logAndCopy("9stream OK", "playlist=$playlist\nquality=$q")
        callback.invoke(
            newExtractorLink(this.name, if (q != null) "$label ${q}p" else label, playlist, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = q ?: Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Origin" to origin, "Referer" to "$origin/")
            }
        )
        return true
    }
}
