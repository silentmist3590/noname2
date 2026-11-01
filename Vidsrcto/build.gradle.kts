// Vidsrcto/build.gradle.kts
cloudstream {
    description = "Vidsrc.to provider with TMDB Proxy, OMDb, Trakt integration and auto-copy debug logs"
    authors = listOf("silentmist3590")
    version = 1
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}

dependencies {
    // Các thư viện cơ bản (OkHttp, Gson, Jsoup) đã có sẵn trong Cloudstream SDK.
    // BouncyCastle chỉ cần nếu bạn dùng CryptoUtils.kt (không cần cho plugin này).
}
