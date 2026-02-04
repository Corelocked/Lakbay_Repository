package com.example.scenic_navigation.services

import android.util.Log
import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import com.example.scenic_navigation.models.Poi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * Service for fetching Points of Interest from local dataset
 */
class PoiService(private val context: Context? = null) {
    // Local dataset loaded from assets (optional)
    private val localPois: MutableList<Poi> = mutableListOf()

    init {
        try {
            context?.let { ctx ->
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
                                        localPois.add(Poi(name, category, description, municipality, lat, lon))
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
        radiusMeters: Int = 5000
    ): List<Poi> = withContext(Dispatchers.IO) {
        val lat = center.latitude
        val lon = center.longitude
        
        // Return only local dataset matches
        return@withContext localPoisNear(lat, lon, radiusMeters)
    }

    suspend fun fetchPoisAlongRoute(
        routePoints: List<GeoPoint>,
        sampleDistMeters: Int = 800,
        radiusMeters: Int = 500,
        maxSamples: Int = 25,
        maxDistToRouteMeters: Int = 150
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext emptyList()

        // Sample points along the route
        val samples = sampleRoute(routePoints, sampleDistMeters, maxSamples)

        // Return only local dataset POIs near the samples
        val localMatches = samples.flatMap { s -> localPoisNear(s.latitude, s.longitude, radiusMeters) }
        val combined = localMatches.distinctBy { poi ->
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
}
