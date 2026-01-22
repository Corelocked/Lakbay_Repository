package com.example.scenic_navigation.services

import android.util.Log
import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import com.example.scenic_navigation.models.Poi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.scenic_navigation.config.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.osmdroid.util.GeoPoint
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * Service for fetching Points of Interest from Overpass API
 */
class PoiService(private val context: Context? = null) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }
    private var lastOverpassCallTime = 0L
    private val OVERPASS_MIN_INTERVAL_MS = 1000L
    // Simple LRU cache for Overpass query results keyed by a short hash of the query
    private val overpassCache: MutableMap<String, List<com.example.scenic_navigation.models.Poi>> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, List<com.example.scenic_navigation.models.Poi>>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<com.example.scenic_navigation.models.Poi>>?): Boolean {
                    return size > 200
                }
            }
        )

    // Local dataset loaded from assets (optional)
    private val localPois: MutableList<com.example.scenic_navigation.models.Poi> = mutableListOf()

    // Web search service for enhanced POI fetching with semantic analysis
    private var webSearchService: WebSearchService? = null

    fun setWebSearchService(service: WebSearchService) {
        webSearchService = service
    }

    init {
        try {
            context?.let { ctx ->
                // Try to initialize Web Search service first
                try {
                    webSearchService = WebSearchService()
                    Log.d("PoiService", "Web Search service initialized")
                } catch (e: Exception) {
                    Log.d("PoiService", "Failed to initialize Web Search service: ${e.message}")
                }

                val am = ctx.assets
                val datasetPath = "datasets"
                val files = am.list(datasetPath) ?: emptyArray()
                if (files.contains("luzon_dataset.csv")) {
                    val path = "$datasetPath/luzon_dataset.csv"
                    am.open(path).use { stream ->
                        BufferedReader(InputStreamReader(stream)).use { br ->
                            // Skip header
                            var line = br.readLine()
                            val csvSplit = Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
                            while (true) {
                                line = br.readLine() ?: break
                                if (line.isBlank()) continue
                                val parts = line.split(csvSplit)
                                if (parts.size >= 6) {
                                    val name = parts[0].trim().trim('"')
                                    val category = parts[1].trim().trim('"')
                                    val location = parts[2].trim().trim('"')
                                    val lat = parts[3].trim().toDoubleOrNull()
                                    val lon = parts[4].trim().toDoubleOrNull()
                                    val description = parts[5].trim().trim('"')
                                    if (lat != null && lon != null) {
                                        // Extract municipality from location (e.g., "Tagaytay, Cavite" -> "Tagaytay")
                                        val municipality = location.split(",")[0].trim()
                                        localPois.add(com.example.scenic_navigation.models.Poi(name, category, description, municipality, lat, lon))
                                    }
                                }
                            }
                        }
                    }
                    Log.d("PoiService", "Loaded ${localPois.size} local dataset POIs")
                }
            }
        } catch (e: Exception) {
            Log.d("PoiService", "Failed to load local dataset: ${e.message}")
        }
    }

    suspend fun fetchPoisNearLocation(
        center: GeoPoint,
        radiusMeters: Int = 5000,
        packageName: String
    ): List<Poi> = withContext(Dispatchers.IO) {
        val lat = center.latitude
        val lon = center.longitude
        
        // If Overpass queries are disabled, return local dataset matches only
        if (Config.DISABLE_OVERPASS) {
            val localMatches = localPoisNear(lat, lon, radiusMeters)
            return@withContext localMatches
        }

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

        val remote = fetchPois(ql, packageName)
        // Merge with any local dataset POIs within radius
        val localMatches = localPoisNear(lat, lon, radiusMeters)
        val combined = (remote + localMatches).distinctBy { poi ->
            String.format(Locale.US, "%s_%.5f_%.5f", poi.name, poi.lat ?: 0.0, poi.lon ?: 0.0)
        }
        return@withContext combined
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

        // If Web Search service is available, use it for enhanced POI fetching with reviews
        webSearchService?.let { webService ->
            val webPois = mutableListOf<Poi>()
            // Limit to 5 samples to avoid rate limits
            val limitedSamples = samples.take(5)
            for (sample in limitedSamples) {
                val pois = webService.searchAttractionsNearLocation(sample, (radiusMeters / 1000.0).toInt())
                webPois.addAll(pois)
            }
            // Also include local dataset POIs
            val localMatches = samples.flatMap { s -> localPoisNear(s.latitude, s.longitude, radiusMeters) }
            val combined = (webPois + localMatches).distinctBy { poi ->
                String.format(Locale.US, "%s_%.5f_%.5f", poi.name, poi.lat ?: 0.0, poi.lon ?: 0.0)
            }
            // Filter by distance to route
            return@withContext combined
                .map { poi -> Pair(poi, minDistToRoute(poi, routePoints)) }
                .filter { it.second <= maxDistToRouteMeters }
                .sortedBy { it.second }
                .map { it.first }
                .take(50)
        }

        // Fallback to Overpass if Web Search not available
        // If Overpass is disabled for testing, return only local POIs near the samples
        if (Config.DISABLE_OVERPASS) {
            val localMatches = samples.flatMap { s -> localPoisNear(s.latitude, s.longitude, radiusMeters) }
            val combined = localMatches.distinctBy { poi ->
                String.format(Locale.US, "%s_%.5f_%.5f", poi.name, poi.lat ?: 0.0, poi.lon ?: 0.0)
            }
            return@withContext combined
        }

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

        val remotePois = fetchPois(sb.toString(), packageName)

        // Also include local dataset POIs that are near any sample point
        val localMatches = samples.flatMap { s -> localPoisNear(s.latitude, s.longitude, radiusMeters) }

        val combined = (remotePois + localMatches).distinctBy { poi ->
            String.format(Locale.US, "%s_%.5f_%.5f", poi.name, poi.lat ?: 0.0, poi.lon ?: 0.0)
        }

        // Filter by distance to route
        return@withContext combined
            .map { poi -> Pair(poi, minDistToRoute(poi, routePoints)) }
            .filter { it.second <= maxDistToRouteMeters }
            .sortedBy { it.second }
            .map { it.first }
            .take(50)
    }

    private suspend fun fetchPois(ql: String, packageName: String): List<Poi> {
        // If disabled, do not perform network Overpass requests
        if (Config.DISABLE_OVERPASS) {
            Log.d("PoiService", "Overpass disabled via Config; returning empty remote list")
            return emptyList()
        }
        // Try cache first
        val key = ql.hashCode().toString()
        val cached = overpassCache[key]
        if (cached != null) return cached

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
                    // Try to get municipality from tags, fallback to "Unknown"
                    val municipality = tags?.optString("addr:city") ?: tags?.optString("addr:town") ?: tags?.optString("addr:municipality") ?: "Unknown"
                    pois.add(Poi(nameTrim, category, desc, municipality, elLat, elLon))
                }
            }

            // Sanitize and dedupe POIs before caching/returning
            val cleaned = sanitizeAndDedupePois(pois)
            Log.d("PoiService", "Returned ${cleaned.size} POIs (raw ${pois.size})")
            overpassCache[key] = cleaned
            return cleaned
        } catch (e: Exception) {
            Log.d("PoiService", "Overpass error: ${e.message}")
            return emptyList()
        }
    }

    // ---------- Cleaning & dedupe helpers ----------
    private fun sanitizeAndDedupePois(pois: List<Poi>): List<Poi> {
        // First sanitize fields
        val sanitized = pois.map { poi ->
            poi.copy(
                name = sanitizeName(poi.name),
                category = sanitizeCategory(poi.category),
                description = sanitizeString(poi.description),
                municipality = sanitizeString(poi.municipality)
            )
        }.filter { poi -> poi.name.isNotBlank() && isValidCoordinate(poi.lat, poi.lon) }

        return dedupePois(sanitized)
    }

    private fun sanitizeString(s: String?): String {
        if (s == null) return ""
        var out = s.trim()
        // Normalize unicode to NFC
        out = Normalizer.normalize(out, Normalizer.Form.NFC)
        // Collapse multiple spaces
        out = out.replace(Regex("\\s+"), " ")
        // Remove control chars
        out = out.replace(Regex("[\\p{Cntrl}]"), "")
        // Remove common markers like (closed)
        out = out.replace(Regex("\\(?closed\\)?", RegexOption.IGNORE_CASE), "")
        return out.trim()
    }

    private fun sanitizeName(name: String): String {
        var n = sanitizeString(name)
        // Remove wrapping quotes
        n = n.trim('"', '\'')
        // Common placeholders => empty
        if (n.equals("unknown", ignoreCase = true) || n.equals("unnamed", ignoreCase = true)) return ""
        return n
    }

    private fun sanitizeCategory(cat: String): String {
        val c = cat.trim().lowercase()
        return when {
            c.contains("rest|food|restaurant|cafe") -> "food"
            c.contains("view|viewpoint|scenic|sight") -> "sight"
            c.contains("museum|gallery") -> "museum"
            c.contains("park|garden") -> "park"
            else -> cat
        }
    }

    private fun isValidCoordinate(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        if (lat < -90.0 || lat > 90.0) return false
        if (lon < -180.0 || lon > 180.0) return false
        return true
    }

    private fun levenshtein(a: String, b: String): Int {
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val dp = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) dp[i][0] = i
        for (j in 0..lb) dp[0][j] = j
        for (i in 1..la) {
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[la][lb]
    }

    private fun nameSimilarity(a: String, b: String): Double {
        val aa = a.lowercase()
        val bb = b.lowercase()
        val max = maxOf(aa.length, bb.length)
        if (max == 0) return 1.0
        val dist = levenshtein(aa, bb)
        return 1.0 - (dist.toDouble() / max.toDouble())
    }

    private fun preferRicherPoi(a: Poi, b: Poi): Poi {
        fun score(p: Poi): Int {
            var s = 0
            if (p.description.isNotBlank()) s += 2
            if (p.municipality.isNotBlank()) s += 2
            if (p.category.isNotBlank()) s += 1
            return s
        }
        return if (score(a) >= score(b)) a else b
    }

    private fun dedupePois(pois: List<Poi>): List<Poi> {
        if (pois.isEmpty()) return pois
        val used = BooleanArray(pois.size)
        val out = mutableListOf<Poi>()
        for (i in pois.indices) {
            if (used[i]) continue
            var best = pois[i]
            used[i] = true
            for (j in i + 1 until pois.size) {
                if (used[j]) continue
                val p1 = pois[i]
                val p2 = pois[j]
                // proximity check (within 25 meters)
                val d = haversine(p1.lat ?: 0.0, p1.lon ?: 0.0, p2.lat ?: 0.0, p2.lon ?: 0.0)
                val nameSim = nameSimilarity(p1.name, p2.name)
                if (d <= 25.0 || nameSim >= 0.8) {
                    best = preferRicherPoi(best, pois[j])
                    used[j] = true
                }
            }
            out.add(best)
        }
        return out
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

    private fun localPoisNear(lat: Double, lon: Double, radiusMeters: Int): List<Poi> {
        if (localPois.isEmpty()) return emptyList()
        return localPois.filter { lp ->
            val d = haversine(lat, lon, lp.lat ?: 0.0, lp.lon ?: 0.0)
            d <= radiusMeters.toDouble()
        }
    }

    /**
     * Public helper to get local dataset POIs that are near any point on the route.
     * Returns deduplicated list keyed by name+coords.
     */
    fun getLocalPoisNearRoute(routePoints: List<GeoPoint>, radiusMeters: Int = 2000): List<Poi> {
        if (localPois.isEmpty() || routePoints.isEmpty()) return emptyList()
        // Sample the route points to reduce repeated work when routes are dense
        val samples = com.example.scenic_navigation.utils.GeoUtils.sampleRoutePoints(routePoints, spacingMeters = radiusMeters.toDouble(), maxSamples = 60)
        val matches = mutableListOf<Poi>()
        for (rp in samples) {
            matches.addAll(localPoisNear(rp.latitude, rp.longitude, radiusMeters))
        }
        return matches.distinctBy { poi -> String.format(Locale.US, "%s_%.5f_%.5f", poi.name, poi.lat ?: 0.0, poi.lon ?: 0.0) }
    }

    fun getLocalPois(): List<Poi> = localPois
}
