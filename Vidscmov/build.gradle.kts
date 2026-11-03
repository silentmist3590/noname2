// Vidscmov/build.gradle.kts
cloudstream {
    description = "Vidsrc.mov API provider with TMDB Proxy, OMDb, Trakt integration and auto-copy debug logs"
    authors = listOf("silentmist3590")
    version = 14
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}

dependencies {
    // Các thư viện cơ bản (Gson, OkHttp, Jsoup) đã có sẵn trong Cloudstream SDK.
    // Bạn không cần thêm gì ở đây trừ khi dùng thêm thư viện ngoài.
    
    // NẾU bạn vẫn giữ file CryptoUtils.kt trong cùng thư mục này, hãy bỏ comment dòng dưới:
    // implementation("org.bouncycastle:bcprov-jdk15on:1.70")
}
