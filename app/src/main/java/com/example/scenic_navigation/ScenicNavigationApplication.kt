package com.example.scenic_navigation

import android.app.Application
import com.example.scenic_navigation.utils.LocaleHelper

class ScenicNavigationApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        val language = LocaleHelper.getLanguage(base)
        val context = LocaleHelper.setLocale(base, language)
        super.attachBaseContext(context)
    }

    override fun onCreate() {
        super.onCreate()
        // Remove the locale setting from here - we'll handle it in activities
    }
}
