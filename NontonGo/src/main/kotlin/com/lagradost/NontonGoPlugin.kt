package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NontonGoPlugin : Plugin() {
    override fun load(context: Context) {
        // Khởi tạo Context cho ClipboardLogger và WebView
        ClipboardLogger.init(context)
        registerMainAPI(NontonGoProvider())
    }
}
