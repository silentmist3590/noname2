package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NontonGoPlugin : Plugin() {
    override fun load(context: Context) {
        // Lưu Android Context an toàn cho ClipboardLogger
        NontonGoProvider.appContext = context

        registerMainAPI(NontonGoProvider())
        registerExtractorAPI(NontonGoExtractor())
    }
}
