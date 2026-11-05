// Krx18/build.gradle.kts
cloudstream {
    description = "KRX18 - krx18.com (18+). Chỉ dành cho người từ 18 tuổi trở lên."
    authors = listOf("silentmist3590")
    status = 1
    version = 7
    language = "en"
    tvTypes = listOf("Movie")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}

dependencies {
    // Thư viện coroutines cần thiết cho suspendCancellableCoroutine, isActive, invokeOnCancellation
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Các dependency khác nếu cần (ví dụ: jsoup, jackson) thường đã được cung cấp bởi root project
    // nhưng nếu thiếu, bạn có thể thêm vào đây.
}
