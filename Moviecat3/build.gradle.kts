import kotlinx.coroutines.delay
// use an integer for version numbers


cloudstream {
    description = "Moviecat3 - moviecat3.com (18+). Chỉ dành cho người từ 18 tuổi trở lên."
    authors = listOf("silentmist3590")
    status = 1
    version = 5
    tvTypes = listOf("Movie","TVSeries","NSFW")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.moviecat3"
}
subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    android {
        namespace = "com.lagradost"
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))

        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}
