// vidlink/build.gradle.kts
cloudstream {
    description = "VidLink API provider with TMDB, OMDb, TVMaze and Trakt integration"
    authors = listOf("silentmist3590")
    status = 1
    version = 11
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}

dependencies {
    // Thư viện mã hóa
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    // Thư viện JSON (nếu chưa có)
    implementation("com.google.code.gson:gson:2.10.1")
}
