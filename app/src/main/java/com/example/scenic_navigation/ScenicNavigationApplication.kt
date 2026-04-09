package com.example.scenic_navigation

import android.app.Application
import android.util.Log
import com.mapbox.common.MapboxOptions
import com.example.scenic_navigation.config.Config
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

        // Ensure Mapbox always has a token at runtime.
        try {
            val tokenFromConfig = Config.MAPBOX_ACCESS_TOKEN
            val tokenFromBuild = BuildConfig.MAPBOX_PUBLIC_TOKEN
            val tokenFromRes = getString(R.string.mapbox_access_token)
            val tokenSource = when {
                tokenFromConfig.isNotBlank() -> "Config"
                tokenFromBuild.isNotBlank() -> "BuildConfig"
                tokenFromRes.isNotBlank() -> "resValue"
                else -> "none"
            }
            val effectiveToken = when (tokenSource) {
                "Config" -> tokenFromConfig
                "BuildConfig" -> tokenFromBuild
                "resValue" -> tokenFromRes
                else -> ""
            }
            if (effectiveToken.isNotBlank()) {
                MapboxOptions.accessToken = effectiveToken
                val masked = if (effectiveToken.length >= 12) {
                    "${effectiveToken.take(8)}...${effectiveToken.takeLast(4)}"
                } else {
                    "(too-short)"
                }
                Log.i("ScenicNavigationApp", "Mapbox token source=$tokenSource token=$masked")
            } else {
                Log.e("ScenicNavigationApp", "Mapbox token is blank; map may render black")
            }
        } catch (e: Exception) {
            Log.e("ScenicNavigationApp", "Failed to initialize Mapbox token", e)
        }

        // Ensure osmdroid has a proper user-agent for remote tile requests (MapTiler, etc.)
        try {
            Configuration.getInstance().userAgentValue = this.packageName
        } catch (_: Exception) {
            // ignore if osmdroid not available at build-time in analysis
        }

        // Initialize telemetry (opt-in respects SettingsStore)
        try {
            com.example.scenic_navigation.services.Telemetry.init(this)
        } catch (_: Exception) {}
    }
}
