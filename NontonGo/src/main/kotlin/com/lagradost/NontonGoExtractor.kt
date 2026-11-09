package com.lagradost

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

class NontonGoExtractor : ExtractorApi() {
    override val name = "NontonGo"
    override val mainUrl = "https://www.nontongo.win"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val provider = NontonGoProvider()
        provider.loadLinks(url, isCasting = false, subtitleCallback = subtitleCallback, callback = callback)
    }
}
