package com.clubcat3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Clubcat3Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Clubcat3Provider())
    }
}
