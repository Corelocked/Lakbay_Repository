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
            // Perform a one-time migration to normalize any legacy keys into canonical keys
            try {
                val orig = readMap()
                val newObj = JSONObject()
                val keys = orig.keys()
                var changed = false
                while (keys.hasNext()) {
                    val k = keys.next()
                    try {
                        val o = orig.getJSONObject(k)
                        val name = o.optString("name", "").trim()
                        val category = o.optString("category", "").trim()
                        val lat = if (o.has("lat")) o.optDouble("lat") else null
                        val lon = if (o.has("lon")) o.optDouble("lon") else null
                        val poi = com.example.scenic_navigation.models.Poi(
                            name = name,
                            category = category,
                            description = o.optString("description", ""),
                            municipality = o.optString("municipality", ""),
                            lat = lat,
                            lon = lon,
                            scenicScore = if (o.has("scenicScore")) o.optDouble("scenicScore").toFloat() else null
                        )
                        val ck = canonicalKey(poi)
                        if (!newObj.has(ck)) {
                            newObj.put(ck, o)
                            if (ck != k) changed = true
                        } else {
                            // Duplicate detected: prefer existing canonical entry, mark changed if keys differ
                            if (ck != k) changed = true
                        }
                    } catch (_: Exception) {}
                }
                if (changed) writeMap(newObj)
            } catch (_: Exception) {}
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

    // Public helper so the whole app can compute favorite keys consistently.
    // This matches the canonical key used by RecommendationsViewModel and RecommendationsAdapter:
    // name,category,lat,lon (commas used, commas inside name/category replaced by spaces)
    fun canonicalKey(poi: com.example.scenic_navigation.models.Poi): String {
        val n = poi.name.trim().replace(",", " ")
        val c = (poi.category ?: "").trim().replace(",", " ")
        val lat = poi.lat?.toString() ?: "0.0"
        val lon = poi.lon?.toString() ?: "0.0"
        return "${n},${c},${lat},${lon}"
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

    // Backward-compatible key check
    fun isFavorite(key: String): Boolean {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        val obj = readMap()
        return obj.has(key)
    }

    // New: robust check by Poi (handles legacy/duplicate keys)
    fun isFavorite(poi: com.example.scenic_navigation.models.Poi): Boolean {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        val obj = readMap()
        // Quick check using canonical key
        val ck = canonicalKey(poi)
        if (obj.has(ck)) return true
        // Fallback: scan entries for matching name and coordinates
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            try {
                val o = obj.getJSONObject(k)
                val storedName = o.optString("name", "").trim()
                val storedLat = if (o.has("lat")) o.optDouble("lat") else Double.NaN
                val storedLon = if (o.has("lon")) o.optDouble("lon") else Double.NaN
                val nameMatches = storedName.equals(poi.name.trim(), ignoreCase = true) || storedName.lowercase().contains(poi.name.trim().lowercase())
                val coordsMatch = if (poi.lat != null && poi.lon != null && !storedLat.isNaN() && !storedLon.isNaN()) {
                    Math.abs(storedLat - poi.lat!!) < 0.0005 && Math.abs(storedLon - poi.lon!!) < 0.0005
                } else false
                if (nameMatches && (coordsMatch || poi.lat == null || poi.lon == null)) return true
            } catch (_: Exception) {}
        }
        return false
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

    // New: add using canonical key and remove any legacy duplicates first
    fun addOrReplaceFavorite(poi: com.example.scenic_navigation.models.Poi) {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        val obj = readMap()
        // remove any existing entries that match this poi (by name/coords)
        val keys = obj.keys().asSequence().toList()
        var removedAny = false
        for (k in keys) {
            try {
                val o = obj.getJSONObject(k)
                val storedName = o.optString("name", "").trim()
                val storedLat = if (o.has("lat")) o.optDouble("lat") else Double.NaN
                val storedLon = if (o.has("lon")) o.optDouble("lon") else Double.NaN
                val nameMatches = storedName.equals(poi.name.trim(), ignoreCase = true) || storedName.lowercase().contains(poi.name.trim().lowercase())
                val coordsMatch = if (poi.lat != null && poi.lon != null && !storedLat.isNaN() && !storedLon.isNaN()) {
                    Math.abs(storedLat - poi.lat!!) < 0.0005 && Math.abs(storedLon - poi.lon!!) < 0.0005
                } else false
                if (nameMatches && (coordsMatch || poi.lat == null || poi.lon == null)) {
                    obj.remove(k)
                    removedAny = true
                }
            } catch (_: Exception) {}
        }
        if (removedAny) writeMap(obj)
        // Now write canonical key
        val key = canonicalKey(poi)
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
        // If exact key exists, remove it immediately
        if (obj.has(key)) {
            obj.remove(key)
            writeMap(obj)
            return
        }

        // Otherwise attempt to find matching entries by comparing stored POI fields
        // This makes removal resilient to legacy key formats (e.g., name_lat_lon)
        try {
            // Parse candidate target name/lat/lon from provided key (try canonical comma format first)
            var targetName: String? = null
            var targetLat: Double? = null
            var targetLon: Double? = null

            if (key.contains(",")) {
                val parts = key.split(',')
                if (parts.isNotEmpty()) targetName = parts[0].trim()
                if (parts.size >= 3) {
                    targetLat = parts[2].trim().toDoubleOrNull()
                    targetLon = parts.getOrNull(3)?.trim()?.toDoubleOrNull()
                }
            } else if (key.contains("_")) {
                // legacy: name_lat_lon
                val parts = key.split('_')
                if (parts.isNotEmpty()) targetName = parts[0].trim()
                // lat is last-2 and lon is last-1 if pattern matches
                if (parts.size >= 3) {
                    targetLat = parts[parts.size - 2].trim().toDoubleOrNull()
                    targetLon = parts[parts.size - 1].trim().toDoubleOrNull()
                }
            }

            // If we couldn't parse anything, fall back to no-op
            if (targetName == null && targetLat == null && targetLon == null) {
                // nothing to match; perform no-op
                return
            }

            val keys = obj.keys().asSequence().toList()
            var removedAny = false
            for (k in keys) {
                try {
                    val o = obj.getJSONObject(k)
                    val storedName = o.optString("name", "").trim()
                    val storedLat = if (o.has("lat")) o.optDouble("lat") else Double.NaN
                    val storedLon = if (o.has("lon")) o.optDouble("lon") else Double.NaN

                    var nameMatches = false
                    var coordsMatch = false

                    if (!targetName.isNullOrBlank()) {
                        nameMatches = storedName.equals(targetName, ignoreCase = true) || storedName.lowercase().contains(targetName.lowercase())
                    }
                    if (targetLat != null && targetLon != null && !storedLat.isNaN() && !storedLon.isNaN()) {
                        val latDiff = Math.abs(storedLat - targetLat)
                        val lonDiff = Math.abs(storedLon - targetLon)
                        coordsMatch = latDiff < 0.0001 && lonDiff < 0.0001
                    }

                    // Remove if both name matches and coords match (if coords provided),
                    // or if name matches and coords not provided.
                    if (nameMatches && (coordsMatch || (targetLat == null || targetLon == null))) {
                        obj.remove(k)
                        removedAny = true
                    }
                } catch (_: Exception) {}
            }

            if (removedAny) writeMap(obj)
        } catch (_: Exception) {
            // ignore failures here
        }
    }

    // New: remove by Poi (robust)
    fun removeByPoi(poi: com.example.scenic_navigation.models.Poi) {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        val obj = readMap()
        val keys = obj.keys().asSequence().toList()
        var removedAny = false
        for (k in keys) {
            try {
                val o = obj.getJSONObject(k)
                val storedName = o.optString("name", "").trim()
                val storedLat = if (o.has("lat")) o.optDouble("lat") else Double.NaN
                val storedLon = if (o.has("lon")) o.optDouble("lon") else Double.NaN
                val nameMatches = storedName.equals(poi.name.trim(), ignoreCase = true) || storedName.lowercase().contains(poi.name.trim().lowercase())
                val coordsMatch = if (poi.lat != null && poi.lon != null && !storedLat.isNaN() && !storedLon.isNaN()) {
                    Math.abs(storedLat - poi.lat!!) < 0.0005 && Math.abs(storedLon - poi.lon!!) < 0.0005
                } else false
                if (nameMatches && (coordsMatch || poi.lat == null || poi.lon == null)) {
                    obj.remove(k)
                    removedAny = true
                }
            } catch (_: Exception) {}
        }
        if (removedAny) writeMap(obj)
    }

    fun getFavoriteKeys(): Set<String> {
        val obj = readMap()
        val s = mutableSetOf<String>()
        val keys = obj.keys()
        while (keys.hasNext()) s.add(keys.next())
        return s
    }
}
