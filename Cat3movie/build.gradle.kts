// use an integer for version numbers
version = 5

cloudstream {
    description = "Cat3movie - cat3movie.org (18+). Chỉ dành cho người từ 18 tuổi trở lên."
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie","Tvseries","NSFW")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.cat3movie"
}
dependencies {
    val cloudstream by configurations
    val implementation by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
