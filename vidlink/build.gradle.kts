// vidlink/build.gradle.kts
cloudstream {
    description = "VidLink API provider with TMDB metadata and auto-copy debug logs"
    authors = listOf("silentmist3590")
    version = 2
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}

android {
    namespace = "com.lagradost" // Đã sửa lại cho khớp với package
}

dependencies {
    // BẮT BUỘC phải có dòng này để mã hóa token hoạt động
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
}
