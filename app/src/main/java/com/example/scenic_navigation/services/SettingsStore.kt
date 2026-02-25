package com.example.scenic_navigation.services

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    // Use the same shared preferences name as SettingsFragment
    private val prefs: SharedPreferences = context.getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
    private val KEY_TELEMETRY = "telemetry_enabled"
    private val KEY_PERSONALIZE = "personalization_enabled"

    fun isTelemetryEnabled(): Boolean {
        return prefs.getBoolean(KEY_TELEMETRY, false)
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEMETRY, enabled).apply()
    }

    fun isPersonalizationEnabled(): Boolean {
        return prefs.getBoolean(KEY_PERSONALIZE, true)
    }

    fun setPersonalizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSONALIZE, enabled).apply()
    }
}
