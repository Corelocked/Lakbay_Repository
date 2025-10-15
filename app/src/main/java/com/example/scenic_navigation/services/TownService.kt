package com.example.scenic_navigation.services

import android.util.Log
import com.example.scenic_navigation.models.Town
import com.example.scenic_navigation.models.Poi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Service for fetching towns along a route
 */
class TownService {
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    /**
     * Fetch towns along the route in order
     */
    suspend fun getTownsAlongRoute(
        routePoints: List<GeoPoint>,
        packageName: String,
        maxDistanceFromRoute: Double = 5000.0 // 5km
    ): List<Town> = withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext emptyList()

        // Sample the route to reduce API load
        val sampledPoints = sampleRoute(routePoints, maxSamples = 50)

        val towns = mutableListOf<Town>()
        val seenTowns = mutableSetOf<String>()

        // Build bounding box for the entire route
        val lats = routePoints.map { it.latitude }
        val lons = routePoints.map { it.longitude }
        val minLat = lats.minOrNull()!! - 0.1
        val maxLat = lats.maxOrNull()!! + 0.1
        val minLon = lons.minOrNull()!! - 0.1
        val maxLon = lons.maxOrNull()!! + 0.1

        // Query Overpass for towns in the bounding box
        val ql = """
[out:json][timeout:25];
(
  node[place~"city|town|village"]($minLat,$minLon,$maxLat,$maxLon);
  way[place~"city|town|village"]($minLat,$minLon,$maxLat,$maxLon);
);
out center 100;
""".trimIndent()

        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0")
            .header("Accept", "application/json")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return@withContext emptyList()

            val candidateTowns = mutableListOf<Town>()

            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)

                var elLat = el.optDouble("lat", Double.NaN)
                var elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) {
                    val center = el.optJSONObject("center")
                    if (center != null) {
                        elLat = center.optDouble("lat", Double.NaN)
                        elLon = center.optDouble("lon", Double.NaN)
                    }
                }

                if (elLat.isNaN() || elLon.isNaN()) continue

                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name")?.trim() ?: ""
                if (name.isBlank() || name.equals("Unknown", ignoreCase = true)) continue

                val placeType = tags.optString("place", "town")
                val population = tags.optString("population").toIntOrNull()

                // Calculate distance to nearest point on route
                val distanceToRoute = routePoints.minOfOrNull { routePoint ->
                    haversine(elLat, elLon, routePoint.latitude, routePoint.longitude)
                } ?: Double.MAX_VALUE

                // Only include towns within maxDistance of route
                if (distanceToRoute <= maxDistanceFromRoute) {
                    // Find closest point index and distance from start
                    var closestIdx = 0
                    var minDist = Double.MAX_VALUE
                    for (j in routePoints.indices) {
                        val dist = haversine(elLat, elLon, routePoints[j].latitude, routePoints[j].longitude)
                        if (dist < minDist) {
                            minDist = dist
                            closestIdx = j
                        }
                    }

                    val distanceFromStart = calculateDistanceFromStart(routePoints, closestIdx)

                    candidateTowns.add(Town(
                        name = name,
                        type = placeType,
                        lat = elLat,
                        lon = elLon,
                        population = population,
                        distanceFromStart = distanceFromStart,
                        routeIndex = closestIdx
                    ))
                }
            }

            // Sort by distance from start and remove duplicates
            candidateTowns
                .sortedBy { it.distanceFromStart }
                .forEach { town ->
                    if (!seenTowns.contains(town.name)) {
                        towns.add(town)
                        seenTowns.add(town.name)
                    }
                }

            Log.d("TownService", "Found ${towns.size} towns along route")
            return@withContext towns

        } catch (e: Exception) {
            Log.e("TownService", "Error fetching towns: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Fetch must-visit POIs in a specific town
     */
    suspend fun getMustVisitPoisInTown(
        town: Town,
        packageName: String,
        radiusMeters: Int = 3000
    ): List<Poi> = withContext(Dispatchers.IO) {
        val lat = town.lat
        val lon = town.lon

        val ql = """
[out:json][timeout:15];
(
  node(around:$radiusMeters,$lat,$lon)[tourism~"attraction|museum|viewpoint|artwork|gallery|information"];
  way(around:$radiusMeters,$lat,$lon)[tourism~"attraction|museum|viewpoint|artwork|gallery|information"];
  node(around:$radiusMeters,$lat,$lon)[historic~"monument|memorial|castle|ruins|palace|cathedral|church|monastery|fort|archaeological_site"];
  way(around:$radiusMeters,$lat,$lon)[historic~"monument|memorial|castle|ruins|palace|cathedral|church|monastery|fort|archaeological_site"];
  node(around:$radiusMeters,$lat,$lon)[amenity~"restaurant|cafe|bar|pub|marketplace|theatre|cinema|library"];
  way(around:$radiusMeters,$lat,$lon)[amenity~"restaurant|cafe|bar|pub|marketplace|theatre|cinema|library"];
  node(around:$radiusMeters,$lat,$lon)[shop~"gift|souvenir|art|craft|bakery|deli|cheese|wine|farm|seafood|antiques|books"];
  way(around:$radiusMeters,$lat,$lon)[shop~"gift|souvenir|art|craft|bakery|deli|cheese|wine|farm|seafood|antiques|books"];
  node(around:$radiusMeters,$lat,$lon)[leisure~"park|garden|marina"];
  way(around:$radiusMeters,$lat,$lon)[leisure~"park|garden|marina"];
);
out center 50;
""".trimIndent()

        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0")
            .header("Accept", "application/json")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return@withContext emptyList()

            val pois = mutableListOf<Poi>()

            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)

                var elLat = el.optDouble("lat", Double.NaN)
                var elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) {
                    val center = el.optJSONObject("center")
                    if (center != null) {
                        elLat = center.optDouble("lat", Double.NaN)
                        elLon = center.optDouble("lon", Double.NaN)
                    }
                }

                if (elLat.isNaN() || elLon.isNaN()) continue

                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name")?.trim() ?: ""
                if (name.isBlank() || name.equals("Unknown", ignoreCase = true)) continue

                // Skip unwanted amenities
                if (isUnwantedAmenity(tags)) continue

                val tourism = tags.optString("tourism", "")
                val historic = tags.optString("historic", "")
                val amenity = tags.optString("amenity", "")
                val shop = tags.optString("shop", "")
                val leisure = tags.optString("leisure", "")

                val category = when {
                    historic.isNotEmpty() -> when (historic.lowercase()) {
                        "monument" -> "Monument"
                        "memorial" -> "Memorial"
                        "castle" -> "Castle"
                        "ruins" -> "Historic Ruins"
                        "palace" -> "Palace"
                        "cathedral" -> "Cathedral"
                        "church" -> "Historic Church"
                        "monastery" -> "Monastery"
                        "fort" -> "Fort"
                        "archaeological_site" -> "Archaeological Site"
                        else -> historic.replaceFirstChar { it.uppercase() }
                    }
                    tourism.isNotEmpty() -> when (tourism.lowercase()) {
                        "museum" -> "Museum"
                        "gallery" -> "Art Gallery"
                        "attraction" -> "Tourist Attraction"
                        "viewpoint" -> "Scenic Viewpoint"
                        "artwork" -> "Public Artwork"
                        "information" -> "Tourist Information"
                        else -> tourism.replaceFirstChar { it.uppercase() }
                    }
                    amenity.isNotEmpty() -> when (amenity.lowercase()) {
                        "restaurant" -> "Restaurant"
                        "cafe" -> "Café"
                        "bar" -> "Bar"
                        "pub" -> "Pub"
                        "marketplace" -> "Local Market"
                        "theatre" -> "Theatre"
                        "cinema" -> "Cinema"
                        "library" -> "Library"
                        else -> amenity.replaceFirstChar { it.uppercase() }
                    }
                    shop.isNotEmpty() -> when (shop.lowercase()) {
                        "gift" -> "Gift Shop"
                        "souvenir" -> "Souvenir Shop"
                        "art" -> "Art Shop"
                        "craft" -> "Craft Shop"
                        "bakery" -> "Bakery"
                        "deli" -> "Delicatessen"
                        "cheese" -> "Cheese Shop"
                        "wine" -> "Wine Shop"
                        "farm" -> "Farm Shop"
                        "seafood" -> "Seafood Market"
                        "antiques" -> "Antique Shop"
                        "books" -> "Bookshop"
                        else -> "Shop"
                    }
                    leisure.isNotEmpty() -> when (leisure.lowercase()) {
                        "park" -> "Park"
                        "garden" -> "Garden"
                        "marina" -> "Marina"
                        else -> leisure.replaceFirstChar { it.uppercase() }
                    }
                    else -> "POI"
                }

                val description = tags.optString("description", "") ?: ""

                pois.add(Poi(
                    name = name,
                    category = category,
                    description = description,
                    municipality = town.name,
                    lat = elLat,
                    lon = elLon
                ))

                if (pois.size >= 50) break
            }

            Log.d("TownService", "Found ${pois.size} POIs in ${town.name}")
            return@withContext pois

        } catch (e: Exception) {
            Log.e("TownService", "Error fetching POIs for town: ${e.message}")
            return@withContext emptyList()
        }
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

    /**
     * Fetch mountain towns along the route in order
     * Focuses on towns with significant elevation and mountain characteristics
     */
    suspend fun getMountainTownsAlongRoute(
        routePoints: List<GeoPoint>,
        packageName: String,
        maxDistanceFromRoute: Double = 5000.0, // 5km
        minElevation: Double = 300.0 // minimum 300m elevation
    ): List<Town> = withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext emptyList()

        val towns = mutableListOf<Town>()
        val seenTowns = mutableSetOf<String>()

        // Build bounding box for the entire route
        val lats = routePoints.map { it.latitude }
        val lons = routePoints.map { it.longitude }
        val minLat = lats.minOrNull()!! - 0.1
        val maxLat = lats.maxOrNull()!! + 0.1
        val minLon = lons.minOrNull()!! - 0.1
        val maxLon = lons.maxOrNull()!! + 0.1

        // Query Overpass for towns with elevation data in the bounding box
        val ql = """
[out:json][timeout:25];
(
  node[place~"city|town|village"]($minLat,$minLon,$maxLat,$maxLon);
  way[place~"city|town|village"]($minLat,$minLon,$maxLat,$maxLon);
);
out center 100;
""".trimIndent()

        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0")
            .header("Accept", "application/json")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return@withContext emptyList()

            val candidateTowns = mutableListOf<Town>()

            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)

                var elLat = el.optDouble("lat", Double.NaN)
                var elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) {
                    val center = el.optJSONObject("center")
                    if (center != null) {
                        elLat = center.optDouble("lat", Double.NaN)
                        elLon = center.optDouble("lon", Double.NaN)
                    }
                }

                if (elLat.isNaN() || elLon.isNaN()) continue

                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name")?.trim() ?: continue
                if (name.isBlank()) continue

                // Get elevation if available
                val elevationStr = tags.optString("ele", "")
                val elevation = elevationStr.toDoubleOrNull()

                // Filter for mountain towns (with elevation data or mountain-related names)
                val isMountainTown = elevation != null && elevation >= minElevation ||
                        name.contains("Mountain", ignoreCase = true) ||
                        name.contains("Highland", ignoreCase = true) ||
                        name.contains("Hill", ignoreCase = true) ||
                        tags.optString("natural", "").contains("peak", ignoreCase = true)

                if (!isMountainTown) continue

                val placeType = tags.optString("place", "town")
                val population = tags.optString("population").toIntOrNull()

                // Calculate distance to nearest point on route
                val distanceToRoute = routePoints.minOfOrNull { routePoint ->
                    haversine(elLat, elLon, routePoint.latitude, routePoint.longitude)
                } ?: Double.MAX_VALUE

                // Only include towns within maxDistance of route
                if (distanceToRoute <= maxDistanceFromRoute) {
                    // Find closest point index and distance from start
                    var closestIdx = 0
                    var minDist = Double.MAX_VALUE
                    for (j in routePoints.indices) {
                        val dist = haversine(elLat, elLon, routePoints[j].latitude, routePoints[j].longitude)
                        if (dist < minDist) {
                            minDist = dist
                            closestIdx = j
                        }
                    }

                    val distanceFromStart = calculateDistanceFromStart(routePoints, closestIdx)

                    candidateTowns.add(Town(
                        name = name,
                        type = "mountain",
                        lat = elLat,
                        lon = elLon,
                        population = population,
                        elevation = elevation,
                        distanceFromStart = distanceFromStart,
                        routeIndex = closestIdx
                    ))
                }
            }

            // Sort by distance from start and remove duplicates
            candidateTowns
                .sortedBy { it.distanceFromStart }
                .forEach { town ->
                    if (!seenTowns.contains(town.name)) {
                        towns.add(town)
                        seenTowns.add(town.name)
                    }
                }

            Log.d("TownService", "Found ${towns.size} mountain towns along route")
            return@withContext towns

        } catch (e: Exception) {
            Log.e("TownService", "Error fetching mountain towns: ${e.message}")
            return@withContext emptyList()
        }
    }

    private fun sampleRoute(routePoints: List<GeoPoint>, maxSamples: Int): List<GeoPoint> {
        if (routePoints.size <= maxSamples) return routePoints

        val step = routePoints.size.toDouble() / maxSamples.toDouble()
        val sampled = mutableListOf<GeoPoint>()

        var idx = 0.0
        repeat(maxSamples) {
            sampled.add(routePoints[minOf(routePoints.size - 1, idx.toInt())])
            idx += step
        }

        return sampled
    }

    private fun calculateDistanceFromStart(routePoints: List<GeoPoint>, targetIndex: Int): Double {
        var distance = 0.0
        for (i in 1..targetIndex) {
            distance += haversine(
                routePoints[i - 1].latitude, routePoints[i - 1].longitude,
                routePoints[i].latitude, routePoints[i].longitude
            )
        }
        return distance
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
