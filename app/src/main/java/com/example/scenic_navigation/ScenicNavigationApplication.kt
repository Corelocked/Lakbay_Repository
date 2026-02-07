package com.example.scenic_navigation

import android.app.Application
import com.example.scenic_navigation.utils.LocaleHelper
import org.osmdroid.config.Configuration

class ScenicNavigationApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        val language = LocaleHelper.getLanguage(base)
        val context = LocaleHelper.setLocale(base, language)
        super.attachBaseContext(context)
    }

    override fun onCreate() {
        super.onCreate()
        // Ensure osmdroid has a proper user-agent for remote tile requests (MapTiler, etc.)
        try {
            Configuration.getInstance().userAgentValue = this.packageName
        } catch (_: Exception) {
            // ignore if osmdroid not available at build-time in analysis
        }
    }
}
