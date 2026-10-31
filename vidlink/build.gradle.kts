// VidLink/build.gradle.kts
cloudstream {
    description = "VidLink API provider with TMDB metadata and auto-copy debug logs"
    version = 1
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}

android {
    namespace = "com.example.vidlink"
}

dependencies {
    // Thư viện mã hóa để tạo token
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
}
