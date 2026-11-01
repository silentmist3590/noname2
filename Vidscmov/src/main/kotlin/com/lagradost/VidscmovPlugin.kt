package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidscmovPlugin : Plugin() { // Đổi tên class Plugin nếu cần
    override fun load() {
        // Đăng ký Provider mới
        registerMainAPI(VidscmovProvider()) 
    }
}
