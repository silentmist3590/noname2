package com.cat3movie

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Cat3moviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cat3movieProvider())
    }
}
