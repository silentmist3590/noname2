// Hnembed/build.gradle.kts
cloudstream {
    description = "Hnembed provider with TMDB Proxy, OMDb, Trakt integration and auto-copy debug logs"
    authors = listOf("silentmist3590")
    status = 1
    version = 3
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}
