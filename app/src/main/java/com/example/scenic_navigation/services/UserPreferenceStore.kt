package com.example.scenic_navigation.services

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple user preference counter stored in SharedPreferences.
 * Tracks counts per category (e.g. "food", "scenic") and exposes
 * a normalized preference score in [0,1].
 */
class UserPreferenceStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)
    private val KEY_PREFIX = "pref_count_"

    fun incrementCategory(category: String) {
        val key = KEY_PREFIX + category
        val cur = prefs.getInt(key, 0)
        prefs.edit().putInt(key, cur + 1).apply()
    }

    fun getCategoryCount(category: String): Int {
        return prefs.getInt(KEY_PREFIX + category, 0)
    }

    /**
     * Returns a normalized score in [0,1] for the given category.
     * Uses a simple soft normalization: score = count / (count + k)
     */
    fun getPreferenceScore(category: String?, k: Float = 5f): Float {
        if (category.isNullOrBlank()) return 0f
        val count = getCategoryCount(category)
        if (count <= 0) return 0f
        return count / (count + k)
    }

    fun clearPreferences() {
        prefs.edit().clear().apply()
    }
}

