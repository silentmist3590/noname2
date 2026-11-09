// use an integer for version numbers


cloudstream {
    description = "Vidapi.ru - The Movie and TVserie"
    authors = listOf("silentmist3590")
    status = 1
    version = 3
    tvTypes = listOf("Movie","TVSeries")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.lagradost"
}
dependencies {
    // Thư viện coroutines bắt buộc cho WebView Extractor
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
