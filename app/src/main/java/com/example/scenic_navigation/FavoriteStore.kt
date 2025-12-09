package com.example.scenic_navigation

import android.content.Context
import org.json.JSONObject

/**
 * Simple favorites store that persists full POI JSON under a single SharedPreferences string.
 * Keys are computed externally (e.g. name_lat_lon). Stored value is JSON with POI fields.
 */
object FavoriteStore {
    private const val PREFS = "scenic_favorites"
    private const val KEY_MAP = "favorites_map"
    private var initialized = false
    private lateinit var ctx: Context

    fun init(context: Context) {
        if (!initialized) {
            ctx = context.applicationContext
            initialized = true
        }
    }

    private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readMap(): JSONObject {
        val raw = prefs().getString(KEY_MAP, null) ?: return JSONObject()
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private fun writeMap(obj: JSONObject) {
        prefs().edit().putString(KEY_MAP, obj.toString()).apply()
    }

    fun getAllFavorites(): List<com.example.scenic_navigation.models.Poi> {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        val obj = readMap()
        val list = mutableListOf<com.example.scenic_navigation.models.Poi>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            try {
                val o = obj.getJSONObject(k)
                val poi = com.example.scenic_navigation.models.Poi(
                    name = o.optString("name", "Unknown"),
                    category = o.optString("category", ""),
                    description = o.optString("description", ""),
                    municipality = o.optString("municipality", ""),
                    lat = if (o.has("lat")) o.optDouble("lat") else null,
                    lon = if (o.has("lon")) o.optDouble("lon") else null,
                    scenicScore = if (o.has("scenicScore")) o.optDouble("scenicScore").toFloat() else null
                )
                list.add(poi)
            } catch (_: Exception) {}
        }
        return list
    }

    fun isFavorite(key: String): Boolean {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        val obj = readMap()
        return obj.has(key)
    }

    fun addFavorite(key: String, poi: com.example.scenic_navigation.models.Poi) {
        val obj = readMap()
        val p = JSONObject()
        p.put("name", poi.name)
        p.put("category", poi.category)
        p.put("description", poi.description)
        p.put("municipality", poi.municipality)
        poi.lat?.let { p.put("lat", it) }
        poi.lon?.let { p.put("lon", it) }
        poi.scenicScore?.let { p.put("scenicScore", it) }
        obj.put(key, p)
        writeMap(obj)
    }

    fun removeFavorite(key: String) {
        val obj = readMap()
        obj.remove(key)
        writeMap(obj)
    }

    fun getFavoriteKeys(): Set<String> {
        val obj = readMap()
        val s = mutableSetOf<String>()
        val keys = obj.keys()
        while (keys.hasNext()) s.add(keys.next())
        return s
    }
}
