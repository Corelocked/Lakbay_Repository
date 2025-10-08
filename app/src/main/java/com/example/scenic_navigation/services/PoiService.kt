package com.example.scenic_navigation.services

import android.util.Log
import com.example.scenic_navigation.models.Poi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * Service for fetching Points of Interest from Overpass API
 */
class PoiService {
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }
    private var lastOverpassCallTime = 0L
    private val OVERPASS_MIN_INTERVAL_MS = 1000L

    suspend fun fetchPoisNearLocation(
        center: GeoPoint,
        radiusMeters: Int = 5000,
        packageName: String
    ): List<Poi> = withContext(Dispatchers.IO) {
        val lat = center.latitude
        val lon = center.longitude

        val ql = """
[out:json][timeout:15];
(
  node(around:$radiusMeters,$lat,$lon)[tourism];
  way(around:$radiusMeters,$lat,$lon)[tourism];
  relation(around:$radiusMeters,$lat,$lon)[tourism];
  node(around:$radiusMeters,$lat,$lon)[amenity];
  way(around:$radiusMeters,$lat,$lon)[amenity];
  relation(around:$radiusMeters,$lat,$lon)[amenity];
  node(around:$radiusMeters,$lat,$lon)[historic];
  way(around:$radiusMeters,$lat,$lon)[historic];
  relation(around:$radiusMeters,$lat,$lon)[historic];
  node(around:$radiusMeters,$lat,$lon)[leisure];
  way(around:$radiusMeters,$lat,$lon)[leisure];
  relation(around:$radiusMeters,$lat,$lon)[leisure];
  node(around:$radiusMeters,$lat,$lon)[man_made];
  way(around:$radiusMeters,$lat,$lon)[man_made];
  relation(around:$radiusMeters,$lat,$lon)[man_made];
  node(around:$radiusMeters,$lat,$lon)[shop~"gift|souvenir|art|craft|bakery|deli|cheese|wine|farm|seafood"];
  way(around:$radiusMeters,$lat,$lon)[shop~"gift|souvenir|art|craft|bakery|deli|cheese|wine|farm|seafood"];
);
out center 50;
""".trimIndent()

        return@withContext fetchPois(ql, packageName)
    }

    suspend fun fetchPoisAlongRoute(
        routePoints: List<GeoPoint>,
        sampleDistMeters: Int = 800,
        radiusMeters: Int = 500,
        maxSamples: Int = 25,
        maxDistToRouteMeters: Int = 150,
        packageName: String
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext emptyList()

        // Sample points along the route
        val samples = sampleRoute(routePoints, sampleDistMeters, maxSamples)

        // Build Overpass query
        val sb = StringBuilder()
        sb.append("[out:json][timeout:25];\n(")
        for (s in samples) {
            val lat = s.latitude
            val lon = s.longitude
            sb.append("node(around:$radiusMeters,$lat,$lon)[amenity];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[amenity];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[amenity];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[tourism];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[tourism];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[tourism];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[historic];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[historic];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[historic];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[leisure];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[leisure];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[leisure];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[man_made];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[man_made];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[man_made];\n")
        }
        sb.append(")\nout center 200;")

        val pois = fetchPois(sb.toString(), packageName)

        // Filter by distance to route
        return@withContext pois
            .map { poi -> Pair(poi, minDistToRoute(poi, routePoints)) }
            .filter { it.second <= maxDistToRouteMeters }
            .sortedBy { it.second }
            .map { it.first }
            .take(50)
    }

    private suspend fun fetchPois(ql: String, packageName: String): List<Poi> {
        ensureRateLimit()

        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
            .header("Accept", "application/json")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d("PoiService", "Overpass response code: ${response.code}")
                response.close()
                return emptyList()
            }

            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return emptyList()

            val pois = mutableListOf<Poi>()
            val seen = mutableSetOf<Long>()

            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val id = el.optLong("id", -1L)
                if (id == -1L || seen.contains(id)) continue
                seen.add(id)

                var elLat = el.optDouble("lat", Double.NaN)
                var elLon = el.optDouble("lon", Double.NaN)

                if (elLat.isNaN() || elLon.isNaN()) {
                    val center = el.optJSONObject("center")
                    if (center != null) {
                        elLat = center.optDouble("lat", Double.NaN)
                        elLon = center.optDouble("lon", Double.NaN)
                    }
                }

                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name") ?: tags?.optString("official_name") ?: "Unknown"
                val category = deriveCategory(tags)
                val desc = tags?.optString("description") ?: tags?.optString("operator") ?: ""

                val nameTrim = name.trim()
                if (nameTrim.isBlank() || nameTrim.equals("Unknown", ignoreCase = true)) continue
                if (isSurveillance(tags)) continue
                if (isUnwantedAmenity(tags)) continue

                if (!elLat.isNaN() && !elLon.isNaN()) {
                    pois.add(Poi(nameTrim, category, desc, elLat, elLon))
                }
            }

            Log.d("PoiService", "Returned ${pois.size} POIs")
            return pois
        } catch (e: Exception) {
            Log.d("PoiService", "Overpass error: ${e.message}")
            return emptyList()
        }
    }

    private fun sampleRoute(
        routePoints: List<GeoPoint>,
        sampleDistMeters: Int,
        maxSamples: Int
    ): List<GeoPoint> {
        val samples = mutableListOf<GeoPoint>()
        samples.add(routePoints.first())
        var acc = 0.0

        for (i in 1 until routePoints.size) {
            val a = routePoints[i - 1]
            val b = routePoints[i]
            val seg = haversine(a.latitude, a.longitude, b.latitude, b.longitude)
            acc += seg
            if (acc >= sampleDistMeters) {
                samples.add(b)
                acc = 0.0
            }
        }
        samples.add(routePoints.last())

        if (samples.size > maxSamples) {
            val down = mutableListOf<GeoPoint>()
            val step = samples.size.toDouble() / maxSamples.toDouble()
            var idx = 0.0
            repeat(maxSamples) {
                val pick = samples[minOf(samples.size - 1, idx.toInt())]
                down.add(pick)
                idx += step
            }
            if (down.last() != samples.last()) down[down.size - 1] = samples.last()
            return down
        }

        return samples
    }

    private fun minDistToRoute(poi: Poi, routePoints: List<GeoPoint>): Double {
        var minD = Double.MAX_VALUE
        for (rp in routePoints) {
            val d = haversine(poi.lat ?: 0.0, poi.lon ?: 0.0, rp.latitude, rp.longitude)
            if (d < minD) minD = d
        }
        return minD
    }

    private fun deriveCategory(tags: org.json.JSONObject?): String {
        if (tags == null) return "POI"
        val historic = tags.optString("historic").ifBlank { "" }
        val manMade = tags.optString("man_made").ifBlank { "" }
        val tourism = tags.optString("tourism").ifBlank { "" }
        val amenity = tags.optString("amenity").ifBlank { "" }
        val leisure = tags.optString("leisure").ifBlank { "" }
        val shop = tags.optString("shop").ifBlank { "" }

        // Historic sites and monuments
        if (historic.isNotBlank()) {
            return when (historic.lowercase()) {
                "memorial" -> "Memorial"
                "monument" -> "Monument"
                "archaeological_site" -> "Archaeological Site"
                "ruins" -> "Historic Ruins"
                "castle" -> "Castle"
                "fort" -> "Fort"
                "palace" -> "Palace"
                "church" -> "Historic Church"
                "cathedral" -> "Cathedral"
                "monastery" -> "Monastery"
                "museum" -> "Museum"
                "battlefield" -> "Historic Battlefield"
                "city_gate" -> "Historic City Gate"
                "tower" -> "Historic Tower"
                else -> "Historical: ${historic.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }}"
            }
        }

        // Tourism attractions
        if (tourism.isNotBlank()) {
            return when (tourism.lowercase()) {
                "museum" -> "Museum"
                "gallery" -> "Art Gallery"
                "artwork" -> "Public Artwork"
                "attraction" -> "Tourist Attraction"
                "viewpoint" -> "Scenic Viewpoint"
                "picnic_site" -> "Picnic Area"
                "theme_park" -> "Theme Park"
                "zoo" -> "Zoo"
                "aquarium" -> "Aquarium"
                "information" -> "Tourist Information"
                "hotel" -> "Hotel"
                "guest_house" -> "Guest House"
                "hostel" -> "Hostel"
                else -> tourism.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }
            }
        }

        // Restaurants and food
        if (amenity.isNotBlank()) {
            return when (amenity.lowercase()) {
                "restaurant" -> "Restaurant"
                "cafe" -> "Café"
                "fast_food" -> "Fast Food"
                "bar" -> "Bar"
                "pub" -> "Pub"
                "food_court" -> "Food Court"
                "ice_cream" -> "Ice Cream Shop"
                "biergarten" -> "Beer Garden"
                "parking" -> "Parking"
                "fuel" -> "Gas Station"
                "toilets" -> "Public Restroom"
                "drinking_water" -> "Drinking Water"
                "marketplace" -> "Local Market"
                "place_of_worship" -> "Place of Worship"
                "theatre" -> "Theatre"
                "cinema" -> "Cinema"
                "library" -> "Library"
                "community_centre" -> "Community Centre"
                else -> amenity.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }
            }
        }

        // Shops and markets
        if (shop.isNotBlank()) {
            return when (shop.lowercase()) {
                "gift" -> "Gift Shop"
                "souvenir" -> "Souvenir Shop"
                "art" -> "Art Shop"
                "craft" -> "Craft Shop"
                "department_store" -> "Department Store"
                "convenience" -> "Convenience Store"
                "supermarket" -> "Supermarket"
                "bakery" -> "Bakery"
                "butcher" -> "Butcher"
                "greengrocer" -> "Greengrocer"
                "seafood" -> "Seafood Market"
                "farm" -> "Farm Shop"
                "deli" -> "Delicatessen"
                "cheese" -> "Cheese Shop"
                "wine" -> "Wine Shop"
                "books" -> "Bookshop"
                "clothes" -> "Clothing Store"
                "jewelry" -> "Jewelry Store"
                "antiques" -> "Antique Shop"
                else -> "Shop: ${shop.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }}"
            }
        }

        // Leisure activities
        if (leisure.isNotBlank()) {
            return when (leisure.lowercase()) {
                "park" -> "Park"
                "garden" -> "Garden"
                "playground" -> "Playground"
                "marina" -> "Marina"
                "beach_resort" -> "Beach Resort"
                "sports_centre" -> "Sports Centre"
                "swimming_pool" -> "Swimming Pool"
                else -> leisure.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }
            }
        }

        // Landmarks
        if (manMade.isNotBlank()) {
            return when (manMade.lowercase()) {
                "lighthouse" -> "Lighthouse"
                "tower" -> "Tower"
                "windmill" -> "Windmill"
                "bridge" -> "Notable Bridge"
                "observation" -> "Observation Point"
                else -> "Landmark (${manMade.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }})"
            }
        }

        return "POI"
    }

    private fun isSurveillance(tags: org.json.JSONObject?): Boolean {
        if (tags == null) return false
        val checkKeys = listOf("surveillance", "camera", "monitoring", "security", "man_made")
        for (k in checkKeys) {
            val v = tags.optString(k).ifBlank { "" }.lowercase()
            if (v.contains("camera") || v.contains("cctv") || v.contains("surveillance") || v.contains("monitor")) {
                return true
            }
        }
        return false
    }

    private fun isUnwantedAmenity(tags: org.json.JSONObject?): Boolean {
        if (tags == null) return false

        val amenity = tags.optString("amenity", "").lowercase()
        val shop = tags.optString("shop", "").lowercase()
        val office = tags.optString("office", "").lowercase()

        // List of unwanted amenities that are not tourist attractions
        val unwantedAmenities = listOf(
            "vehicle_inspection", "car_wash", "car_repair", "charging_station",
            "atm", "bank", "post_office", "police", "fire_station",
            "waste_disposal", "recycling", "taxi", "car_rental",
            "veterinary", "pharmacy", "clinic", "doctors", "dentist", "hospital",
            "fuel", "parking", "toilets", "telephone", "post_box",
            "bench", "waste_basket", "vending_machine"
        )

        val unwantedShops = listOf(
            "car", "car_repair", "car_parts", "tyres", "motorcycle",
            "chemist", "medical_supply", "hardware", "doityourself",
            "trade"
        )

        val unwantedOffices = listOf(
            "government", "insurance", "lawyer", "accountant", "estate_agent",
            "employment_agency", "tax_advisor"
        )

        // Check if it's an unwanted amenity
        if (unwantedAmenities.any { amenity.contains(it) }) return true
        if (unwantedShops.any { shop.contains(it) }) return true
        if (unwantedOffices.any { office.contains(it) }) return true

        return false
    }

    private suspend fun ensureRateLimit() {
        val waitFor = lastOverpassCallTime + OVERPASS_MIN_INTERVAL_MS - System.currentTimeMillis()
        if (waitFor > 0) kotlinx.coroutines.delay(waitFor)
        lastOverpassCallTime = System.currentTimeMillis()
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}
