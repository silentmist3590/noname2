package com.moviecat3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Moviecat3Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Moviecat3Provider())
    }
}
