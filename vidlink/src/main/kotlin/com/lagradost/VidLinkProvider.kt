package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class VidLinkProvider : MainAPI() {
    override var mainUrl = "https://movieshubhd.com" // URL gốc của trang phim
    override var name = "MoviesHubHD"
    override var lang = "en"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(emptyList())
    }

    // Hàm tìm kiếm (Bạn có thể phát triển thêm sau)
    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    // Hàm load thông tin phim.
    // Ở đây ta giả định plugin sẽ nhận TMDB ID hoặc URL của phim.
    override suspend fun load(url: String): LoadResponse {
        // Nếu bạn truyền TMDB ID (ví dụ: "20453")
        val tmdbId = if (url.contains("tmdb/")) url.substringAfter("tmdb/") else url

        return newMovieLoadResponse(
            name = "Movie $tmdbId", // Tên tạm (bạn có thể gọi API TMDB để lấy tên thật)
            url = tmdbId,           // Lưu TMDB ID vào trường url để truyền sang loadLinks
            type = TvType.Movie,
            dataUrl = tmdbId        // Dữ liệu truyền xuống loadLinks
        )
    }

    // HÀM QUAN TRỌNG NHẤT: Khai báo đúng chữ ký để sửa lỗi 'Unresolved reference data'
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data ở đây chính là tmdbId được truyền từ hàm load() phía trên
        val tmdbId = data

        // Danh sách các server từ ảnh bạn gửi
        val embedUrls = listOf(
            "https://vidlink.pro/movie/$tmdbId",
            "https://www.vidking.net/embed/movie/$tmdbId",
            "https://vidsrc-embed.ru/embed/movie/$tmdbId",
            "https://www.2embed.cc/embed/$tmdbId",
            "https://vidsrc.cc/v2/embed/movie/$tmdbId",
            "https://multiembed.mov/?video_id=$tmdbId&tmdb=1",
            "https://vidsrc.me/embed/movie/$tmdbId",
            "https://autoembed.co/movie/tmdb/$tmdbId",
            "https://111movies.com/movie/$tmdbId",
            "https://player.videasy.net/movie/$tmdbId",
            "https://vidfast.vc/movie/$tmdbId"
        )

        // Duyệt qua từng server và nhờ CloudStream tự động bóc tách link
        for (embedUrl in embedUrls) {
            try {
                // Hàm loadExtractor sẽ tự động nhận diện server và lấy link video
                loadExtractor(embedUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                // Bỏ qua nếu server đó lỗi, chuyển sang server tiếp theo
                e.printStackTrace()
            }
        }

        return true
    }
}
