package com.dramaclub
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramaClubPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramaClub())
    }
}