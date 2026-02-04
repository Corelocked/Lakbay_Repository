package com.example.scenic_navigation.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.*

object LocaleHelper {
    private const val PREF_LANGUAGE = "language"

    fun setLocale(context: Context, language: String): Context {
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }

        return context.createConfigurationContext(config)
    }

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_LANGUAGE, "en") ?: "en"
    }

    fun setLanguage(context: Context, language: String) {
        val prefs = context.getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LANGUAGE, language).apply()
    }
}
