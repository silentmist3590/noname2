// Vidsrcsu/build.gradle.kts
cloudstream {
    description = "Vidsrcsu provider with TMDB Proxy, OMDb, Trakt integration and auto-copy debug logs"
    authors = listOf("silentmist3590")
    status = 1
    version = 12
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}
dependencies {
    // Thư viện coroutines bắt buộc cho WebView Extractor
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
