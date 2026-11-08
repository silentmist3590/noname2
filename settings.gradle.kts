include(":vidlink")
include(":Vidscmov")
include(":Vidsrcme")
include(":Vidsrcsu")
include(":Vidsrcto")
include(":Hnembed")
include(":2embed")
include(":Okru")
include(":Krx18")
include(":Moviecat3")
include(":Vidrock")
include(":Vidfast")
include(":Vidapi")
include(":Cat3movie")
include(":Clubcat3")

pluginManagement {
    repositories {
        // ⭐ Thêm Aliyun mirror để vượt lỗi 504
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ⭐ Thêm Aliyun mirror để vượt lỗi 504
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        
        google()
        mavenCentral()
    }
}
rootProject.name = "CloudstreamPlugins"
