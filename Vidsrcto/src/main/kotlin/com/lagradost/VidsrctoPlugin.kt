package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidsrctoPlugin : Plugin() {
    override fun load() {
        registerMainAPI(VidsrctoProvider())
    }
}
