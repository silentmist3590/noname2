package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidLinkPlugin : Plugin() {
    override fun load(context: Context) {
        // Đăng ký nhà cung cấp phim của bạn với CloudStream
        registerMainAPI(VidLinkProvider())
    }
}
