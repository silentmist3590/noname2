package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TwoNembedPlugin : Plugin() {
    override fun load(context: Context) {
        // Truyền context chuẩn từ CloudStream vào ClipboardLogger ngay khi nạp plugin
        ClipboardLogger.init(context)
        registerMainAPI(TwoNembedProvider())
    }
}
