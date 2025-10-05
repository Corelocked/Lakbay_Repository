package com.example.scenic_navigation.services

import android.util.Log
import com.example.scenic_navigation.models.ScenicPoi
import com.example.scenic_navigation.utils.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.osmdroid.util.GeoPoint
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Service for scenic route planning and scoring
 */
class ScenicRoutePlanner {
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    /**
     * Fetch scenic POIs along a route with dynamic sampling based on route length
     */
    suspend fun fetchScenicPois(
        routePoints: List<GeoPoint>,
        packageName: String,
        onStatusUpdate: ((String) -> Unit)? = null
    ): List<ScenicPoi> = withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext emptyList()

        val length = GeoUtils.computeRouteLength(routePoints)

        // Determine sampling parameters based on route length
        val spacing = when {
            length > 400_000 -> 20_000.0
            length > 300_000 -> 15_000.0
            length > 200_000 -> 12_000.0
            length > 120_000 -> 10_000.0
            length > 80_000 -> 8_000.0
            length > 40_000 -> 6_000.0
            else -> 4_000.0
        }

        val maxSamples = when {
            length > 400_000 -> 40
            length > 200_000 -> 32
            length > 120_000 -> 28
            else -> 20
        }

        val samples = GeoUtils.sampleRoutePoints(routePoints, spacing, maxSamples)

        // Parallel sampling with limited concurrency
        val semaphore = Semaphore(3)
        val scenicPois = mutableListOf<ScenicPoi>()
        val mutex = Mutex()

        supervisorScope {
            samples.forEachIndexed { idx, sample ->
                launch {
                    semaphore.withPermit {
                        try {
                            onStatusUpdate?.invoke("Scenic sampling ${idx + 1}/${samples.size}…")
                        } catch (_: Exception) {}

                        val radius = when {
                            length > 300_000 -> 2000
                            length > 120_000 -> 1500
                            length > 80_000 -> 1200
                            else -> 1000
                        }

                        val pois = fetchScenicPoisAtPoint(sample, radius, packageName)

                        if (pois.isNotEmpty()) {
                            mutex.lock()
                            try {
                                scenicPois.addAll(pois)
                            } finally {
                                mutex.unlock()
                            }
                        }
                    }
                }
            }
        }

        // De-duplicate and sort by score
        val dedup = LinkedHashMap<String, ScenicPoi>()
        for (p in scenicPois) {
            val key = "${"%.5f".format(p.lat)}_${"%.5f".format(p.lon)}_${p.name}"
            val existing = dedup[key]
            if (existing == null || p.score > existing.score) {
                dedup[key] = p
            }
        }

        val list = dedup.values.toMutableList()
        list.sortByDescending { it.score }

        return@withContext if (list.size > 200) list.subList(0, 200) else list
    }

    private suspend fun fetchScenicPoisAtPoint(
        point: GeoPoint,
        radius: Int,
        packageName: String
    ): List<ScenicPoi> = withContext(Dispatchers.IO) {
        val lat = point.latitude
        val lon = point.longitude

        val ql = """
[out:json][timeout:25];
(
  node(around:$radius,$lat,$lon)[tourism=viewpoint];
  node(around:$radius,$lat,$lon)[leisure=park];
  node(around:$radius,$lat,$lon)[natural=peak];
  node(around:$radius,$lat,$lon)[natural=wood];
  node(around:$radius,$lat,$lon)[tourism=attraction];
  node(around:$radius,$lat,$lon)[natural=beach];
  node(around:$radius,$lat,$lon)[leisure=nature_reserve];
  node(around:$radius,$lat,$lon)[tourism=picnic_site];
  node(around:$radius,$lat,$lon)[tourism=camp_site];
  node(around:$radius,$lat,$lon)[tourism=hotel];
  node(around:$radius,$lat,$lon)[tourism=museum];
  node(around:$radius,$lat,$lon)[historic];
);
out center 60;
""".trimIndent()

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
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: ""
            response.close()

            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return@withContext emptyList()

            val local = mutableListOf<ScenicPoi>()
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

                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name") ?: "Scenic Location"
                val type = tags?.optString("tourism")
                    ?: tags?.optString("leisure")
                    ?: tags?.optString("natural")
                    ?: tags?.optString("historic")
                    ?: "unknown"

                if (!elLat.isNaN() && !elLon.isNaN()) {
                    val score = when {
                        type.equals("viewpoint", true) -> 110
                        type.equals("peak", true) -> 100
                        type.equals("nature_reserve", true) -> 95
                        type.equals("beach", true) -> 90
                        type.equals("park", true) -> 85
                        type.equals("wood", true) -> 70
                        type.equals("historic", true) -> 65
                        type.equals("attraction", true) -> 60
                        type.equals("museum", true) -> 45
                        type.equals("hotel", true) -> 25
                        type.equals("camp_site", true) -> 40
                        type.equals("picnic_site", true) -> 35
                        else -> 20
                    }
                    local.add(ScenicPoi(name, type, elLat, elLon, score))
                }
            }

            return@withContext local
        } catch (e: Exception) {
            Log.e("ScenicRoutePlanner", "Error fetching scenic POIs: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Calculate scenic score for a route based on POIs
     */
    fun calculateScenicScore(route: List<GeoPoint>, scenicPois: List<ScenicPoi>): Double {
        if (route.isEmpty()) return Double.NEGATIVE_INFINITY

        val lengthMeters = GeoUtils.computeRouteLength(route).coerceAtLeast(1.0)
        val totalScore = scenicPois.sumOf { it.score }
        val avgScore = if (scenicPois.isNotEmpty()) totalScore.toDouble() / scenicPois.size else 0.0
        val distinctTypes = scenicPois.map { it.type.lowercase(Locale.getDefault()) }.toSet().size
        val density = scenicPois.size / (lengthMeters / 1000.0)

        return (totalScore * 1.2) + (avgScore * 15) + (distinctTypes * 90) + (density * 400) +
                Math.log(lengthMeters) * 25
    }

    /**
     * Select the most scenic route from alternatives
     */
    suspend fun selectMostScenicRoute(
        alternatives: List<List<GeoPoint>>,
        packageName: String,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Pair<List<GeoPoint>?, List<ScenicPoi>> = withContext(Dispatchers.IO) {
        if (alternatives.isEmpty()) return@withContext Pair(null, emptyList())

        var mostScenicRoute: List<GeoPoint>? = null
        var mostScenicPois: List<ScenicPoi> = emptyList()
        var highestScore = Double.NEGATIVE_INFINITY

        for (alt in alternatives) {
            val pois = fetchScenicPois(alt, packageName, onStatusUpdate)
            val score = calculateScenicScore(alt, pois)

            Log.d("ScenicRoutePlanner",
                "Route length=${GeoUtils.computeRouteLength(alt).toInt()}m " +
                "scenicCount=${pois.size} score=$score")

            if (score > highestScore) {
                highestScore = score
                mostScenicRoute = alt
                mostScenicPois = pois
            }
        }

        return@withContext Pair(mostScenicRoute, mostScenicPois)
    }
}
