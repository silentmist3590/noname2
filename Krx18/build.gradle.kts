// use an integer for version numbers
version = 1

cloudstream {
    description = "KRX18 - krx18.com (18+). Chỉ dành cho người từ 18 tuổi trở lên."
    authors = listOf("silentmist3590")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie","NSFW")

    requiresResources = false
}

android {
    namespace = "com.krx18"
}
