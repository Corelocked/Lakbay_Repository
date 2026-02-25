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
            // perform a best-effort dedupe on any existing favorites stored under legacy or duplicate keys
            try { dedupeExistingFavorites() } catch (_: Exception) {}
        }
    }

    // Best-effort pass over stored favorites to deduplicate entries referring to the same POI
    private fun dedupeExistingFavorites() {
        val obj = readMap()
        val keys = obj.keys().asSequence().toList()
        val seen = mutableListOf<Pair<Double, Double>>() // lat/lon pairs seen
        val namesSeen = mutableSetOf<String>()
        val toRemove = mutableListOf<String>()
        for (k in keys) {
            try {
                val o = obj.optJSONObject(k) ?: continue
                val lat = if (o.has("lat")) o.optDouble("lat") else Double.NaN
                val lon = if (o.has("lon")) o.optDouble("lon") else Double.NaN
                if (!lat.isNaN() && !lon.isNaN()) {
                    val found = seen.find { kotlin.math.abs(it.first - lat) < 1e-6 && kotlin.math.abs(it.second - lon) < 1e-6 }
                    if (found != null) {
                        toRemove.add(k)
                        continue
                    } else {
                        seen.add(Pair(lat, lon))
                    }
                } else {
                    val name = o.optString("name", "").trim().lowercase()
                    if (name.isNotBlank()) {
                        if (namesSeen.contains(name)) {
                            toRemove.add(k)
                            continue
                        } else namesSeen.add(name)
                    }
                }
            } catch (_: Exception) {}
        }
        if (toRemove.isNotEmpty()) {
            for (k in toRemove) obj.remove(k)
            writeMap(obj)
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

    // Helper to compute canonical key in the same format used elsewhere: name,category,lat,lon
    private fun canonicalKeyForPoi(poiName: String, category: String?, lat: Double?, lon: Double?): String {
        val n = poiName.trim().replace(",", " ")
        val c = (category ?: "").trim().replace(",", " ")
        val latS = lat?.toString() ?: "0.0"
        val lonS = lon?.toString() ?: "0.0"
        return "${n},${c},${latS},${lonS}"
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
        // Normalize to canonical key based on POI fields to avoid multiple representations of same POI
        val obj = readMap()
        val storeKey = try { com.example.scenic_navigation.ui.RecommendationsAdapter.canonicalKey(poi) } catch (_: Exception) {
            canonicalKeyForPoi(poi.name, poi.category, poi.lat, poi.lon)
        }

        // Remove any existing entries that appear to refer to the same POI (match by lat/lon if present)
        try {
            val keys = obj.keys().asSequence().toList()
            for (k in keys) {
                try {
                    val o = obj.optJSONObject(k) ?: continue
                    val storedLat = if (o.has("lat")) o.optDouble("lat") else Double.NaN
                    val storedLon = if (o.has("lon")) o.optDouble("lon") else Double.NaN
                    if (!storedLat.isNaN() && !storedLon.isNaN() && poi.lat != null && poi.lon != null) {
                        val eps = 1e-6
                        if (k != storeKey && kotlin.math.abs(storedLat - poi.lat) < eps && kotlin.math.abs(storedLon - poi.lon) < eps) {
                            obj.remove(k)
                        }
                    } else {
                        // Fallback: compare normalized names
                        val storedName = o.optString("name", "").trim().lowercase()
                        if (storedName.isNotBlank() && storedName == poi.name.trim().lowercase() && k != storeKey) {
                            obj.remove(k)
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        val p = JSONObject()
        p.put("name", poi.name)
        p.put("category", poi.category)
        p.put("description", poi.description)
        p.put("municipality", poi.municipality)
        poi.lat?.let { p.put("lat", it) }
        poi.lon?.let { p.put("lon", it) }
        poi.scenicScore?.let { p.put("scenicScore", it) }
        obj.put(storeKey, p)
        writeMap(obj)
    }

    fun removeFavorite(key: String) {
        val obj = readMap()
        if (obj.has(key)) {
            obj.remove(key)
            writeMap(obj)
            return
        }

        // If exact key not present, try to parse lat/lon from the provided key and remove any matching entries
        try {
            val numberRegex = Regex("-?\\d+\\.?\\d*(?:[eE][+-]?\\d+)?")
            val found = numberRegex.findAll(key).map { it.value }.toList()
            if (found.size >= 2) {
                val lat = found[found.size - 2].toDoubleOrNull()
                val lon = found[found.size - 1].toDoubleOrNull()
                if (lat != null && lon != null) {
                    val keys = obj.keys().asSequence().toList()
                    val eps = 1e-6
                    for (k in keys) {
                        try {
                            val o = obj.optJSONObject(k) ?: continue
                            val storedLat = if (o.has("lat")) o.optDouble("lat") else Double.NaN
                            val storedLon = if (o.has("lon")) o.optDouble("lon") else Double.NaN
                            if (!storedLat.isNaN() && !storedLon.isNaN()) {
                                if (k != key && kotlin.math.abs(storedLat - lat) < eps && kotlin.math.abs(storedLon - lon) < eps) {
                                    obj.remove(k)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    writeMap(obj)
                    return
                }
            }
        } catch (_: Exception) {}

        // As a last resort, try removing entries with matching normalized name
        try {
            val namePart = key.split(',').firstOrNull()?.trim()?.lowercase() ?: key.trim().lowercase()
            val keys = obj.keys().asSequence().toList()
            for (k in keys) {
                try {
                    val o = obj.optJSONObject(k) ?: continue
                    val storedName = o.optString("name", "").trim().lowercase()
                    if (storedName == namePart) obj.remove(k)
                } catch (_: Exception) {}
            }
            writeMap(obj)
        } catch (_: Exception) {}
    }

    fun getFavoriteKeys(): Set<String> {
        val obj = readMap()
        val s = mutableSetOf<String>()
        val keys = obj.keys()
        while (keys.hasNext()) s.add(keys.next())
        return s
    }
}
