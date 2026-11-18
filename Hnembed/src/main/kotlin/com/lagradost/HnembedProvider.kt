package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

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

// Compatibility with old SearchResponse URLs that were incorrectly fixed by CloudStream.
private val HN_LEGACY_PATH_REGEX =
    Regex("""^/hnembed:(movie|tv):(\d+)(?::(\d+):(\d+))?/?$""")

/**
 * Accepts all supported data forms:
 *   hnembed:movie:123
 *   hnembed:tv:123:1:2
 *   https://hnembed.cc/embed/movie/123
 *   https://hnembed.cc/embed/tv/123/1/2
 *   https://hnembed.cc/hnembed:movie:123 (legacy broken route)
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

/** Return an absolute URL for CloudStream's SearchResponse/LoadResponse.url. */
private fun hnDetailUrl(type: String, id: Int): String {
    return if (type == "movie") {
        "https://hnembed.cc/embed/movie/$id"
    } else {
        // HNEmbed's documented TV embed format requires season and episode.
        // This URL is only the stable detail-page identifier; individual
        // episode playback uses Episode.data below.
        "https://hnembed.cc/embed/tv/$id/1/1"
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
                    if (body.trimStart().startsWith("{")) {
                        return JSONObject(body)
                    }
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

        // IMPORTANT: url is a real absolute HTTP URL, not "hnembed:movie:id".
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
     * HNEmbed's documented embed URL formats:
     *   /embed/movie/{IMDb-or-TMDB-ID}
     *   /embed/tv/{IMDb-or-TMDB-ID}/{season}/{episode}
     *
     * The provider first delegates the official embed URL to CloudStream's
     * registered extractors. If the embed HTML exposes normal iframe/video
     * URLs, those URLs are passed to the matching extractor as a fallback.
     * A page URL is never falsely reported as a playable video URL.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.clear()
        ClipboardLogger.logAndCopy(
            "BUILD MARKER",
            "HNEMBED-URL-FIX-2026-11-18"
        )
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

        val mirrors = listOf("https://hnembed.cc", "https://hnembed.net")
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

            // First try the installed CloudStream extractor for the official embed URL.
            try {
                loadExtractor(embedUrl, "$mirror/", subtitleCallback) { link ->
                    callback.invoke(link)
                    emitted++
                    ClipboardLogger.logAndCopy(
                        "Extractor link",
                        "${link.name} | ${link.url.take(180)}"
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

            // Inspect ordinary public embed markup only. No player token or
            // protected media request is reconstructed here.
            try {
                val response = app.get(
                    embedUrl,
                    headers = headers,
                    referer = "$mirror/"
                )
                ClipboardLogger.logAndCopy(
                    "Embed response",
                    "HTTP ${response.code}; final=${response.url}; bytes=${response.text.length}"
                )
                if (response.code !in 200..299) continue

                val html = response.text
                val tagRegex = Regex("""(?is)<(?:iframe|video|source)\b[^>]*>""")
                val attrRegex = Regex("""(?is)(?:src|data-src)\s*=\s*["']([^"']+)["']""")

                for (tag in tagRegex.findAll(html)) {
                    val raw = attrRegex.find(tag.value)?.groupValues?.getOrNull(1)
                        ?: continue

                    val candidate = try {
                        URI(embedUrl).resolve(
                            raw.replace("&amp;", "&")
                                .replace("\\/", "/")
                                .trim()
                        ).toString()
                    } catch (_: Exception) {
                        continue
                    }

                    if (!candidate.startsWith("http") || !tried.add(candidate)) {
                        continue
                    }

                    ClipboardLogger.logAndCopy("Embed candidate", candidate.take(300))

                    val lower = candidate.lowercase()
                    val directType = when {
                        ".m3u8" in lower -> ExtractorLinkType.M3U8
                        ".mpd" in lower -> ExtractorLinkType.DASH
                        ".mp4" in lower -> ExtractorLinkType.VIDEO
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
                                referer = embedUrl
                                quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to embedUrl
                                )
                            }
                        )
                        emitted++
                        continue
                    }

                    try {
                        loadExtractor(candidate, embedUrl, subtitleCallback) { link ->
                            callback.invoke(link)
                            emitted++
                            ClipboardLogger.logAndCopy(
                                "Extractor link",
                                "${link.name} | ${link.url.take(180)}"
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        ClipboardLogger.logAndCopy(
                            "Candidate extractor",
                            "${candidate.take(180)} | ${e.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ClipboardLogger.logAndCopy(
                    "Embed fetch",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }

            if (emitted > 0) return true
        }

        ClipboardLogger.logAndCopy(
            "No playable source",
            "HNEmbed returned no URL handled by the installed CloudStream extractors. " +
                "The embed page itself is not returned as a video link."
        )
        return false
    }
}
