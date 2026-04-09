package com.example.scenic_navigation.services

import android.util.Log
import android.content.Context
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.data.PoiCsvLoader
import com.example.scenic_navigation.data.local.AppDatabase
import com.example.scenic_navigation.data.local.PoiEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.Locale
import kotlin.math.cos

/**
 * Service for fetching Points of Interest from local dataset
 */
class PoiService(private val context: Context? = null) {
    companion object {
        private const val DATASET_PREFS = "poi_dataset_seed"
        private const val DATASET_HASH_KEY = "dataset_hash"
    }

    // Local dataset loaded from assets (optional)
    private val localPois: MutableList<Poi> = mutableListOf()
    private val database = context?.let { AppDatabase.getInstance(it) }

    init {
        try {
            context?.let { ctx ->
                val am = ctx.assets
                val datasetPath = "datasets"
                val files = am.list(datasetPath) ?: emptyArray()
                if (files.contains("luzon_dataset.csv")) {
                    val path = "$datasetPath/luzon_dataset.csv"
                    val poiDao = database?.poiDao()
                    val seedRows = PoiCsvLoader.loadFromAssets(am, path)
                    val datasetHash = buildDatasetHash(seedRows)
                    val seeded = runBlocking(Dispatchers.IO) {
                        if (poiDao != null && shouldRefreshSeed(ctx, poiDao.count(), datasetHash)) {
                            poiDao.clear()
                            poiDao.insertAll(seedRows.map { PoiEntity.fromPoi(it.poi, it.location) })
                            rememberSeedHash(ctx, datasetHash)
                        }
                        poiDao?.getAll()?.map { it.toPoi() } ?: emptyList()
                    }
                    localPois.addAll(seeded)
                    val withImages = localPois.count { it.imageUrl.isNotBlank() }
                    Log.d("PoiService", "Loaded ${localPois.size} local dataset POIs ($withImages with imageUrl)")
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

    fun getDatabasePois(): List<Poi> = runBlocking(Dispatchers.IO) {
        database?.poiDao()?.getAll()?.map { it.toPoi() } ?: localPois.toList()
    }

    fun searchDatabasePois(
        userLat: Double,
        userLon: Double,
        maxDistanceKm: Double,
        preferredCategories: Set<String> = emptySet()
    ): List<Poi> = runBlocking(Dispatchers.IO) {
        val poiDao = database?.poiDao() ?: return@runBlocking localPois.toList()
        val radiusMeters = maxDistanceKm * 1000.0
        val latDelta = radiusMeters / 111_320.0
        val lonDelta = radiusMeters / (111_320.0 * cos(Math.toRadians(userLat)).coerceAtLeast(0.1))
        val args = mutableListOf<Any>(userLat - latDelta, userLat + latDelta, userLon - lonDelta, userLon + lonDelta)
        val where = mutableListOf("lat BETWEEN ? AND ?", "lon BETWEEN ? AND ?")

        val tokens = preferredCategories
            .flatMap { mapCategoryQueryTokens(it) }
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .distinct()

        if (tokens.isNotEmpty()) {
            val tokenClause = tokens.joinToString(" OR ") {
                args += "%$it%"
                args += "%$it%"
                args += "%$it%"
                args += "%$it%"
                "(LOWER(category) LIKE ? OR LOWER(tags) LIKE ? OR LOWER(name) LIKE ? OR LOWER(photoHint) LIKE ?)"
            }
            where += "($tokenClause)"
        }

        val query = SimpleSQLiteQuery(
            "SELECT * FROM pois WHERE ${where.joinToString(" AND ")} ORDER BY name COLLATE NOCASE",
            args.toTypedArray()
        )

        poiDao.search(query).map { it.toPoi() }
    }

    private fun buildDatasetHash(rows: List<PoiCsvLoader.ParsedPoiRow>): String {
        return rows.joinToString(separator = "\n") { row ->
            val poi = row.poi
            listOf(
                poi.name,
                poi.category,
                row.location,
                poi.lat,
                poi.lon,
                poi.description,
                poi.municipality,
                poi.province,
                poi.tags.joinToString("|"),
                poi.photoHint,
                poi.imageUrl
            ).joinToString("|")
        }.hashCode().toString()
    }

    private fun shouldRefreshSeed(context: Context, currentCount: Int, datasetHash: String): Boolean {
        if (currentCount == 0) return true
        val prefs = context.getSharedPreferences(DATASET_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(DATASET_HASH_KEY, null) != datasetHash
    }

    private fun rememberSeedHash(context: Context, datasetHash: String) {
        context.getSharedPreferences(DATASET_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(DATASET_HASH_KEY, datasetHash)
            .apply()
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

    private fun mapCategoryQueryTokens(selection: String): List<String> {
        val value = selection.lowercase(Locale.US)
        return when {
            value.contains("scenic") -> listOf("scenic", "viewpoint", "attraction", "park")
            value.contains("natural") || value.contains("nature") -> listOf("natural", "nature", "waterfall", "park")
            value.contains("tourism") -> listOf("tourism", "tourist", "attraction")
            value.contains("historic") || value.contains("landmark") -> listOf("historic", "historical", "heritage", "museum", "monument")
            value.contains("cultur") -> listOf("cultural", "culture", "museum", "heritage")
            value.contains("food") || value.contains("restaurant") || value.contains("cafe") -> listOf("restaurant", "cafe", "food", "dining")
            value.contains("shop") || value.contains("market") -> listOf("shop", "shopping", "market", "souvenir")
            value.contains("coast") || value.contains("beach") || value.contains("ocean") -> listOf("beach", "coast", "bay", "ocean")
            value.contains("mountain") || value.contains("mount") || value.contains("peak") -> listOf("mountain", "peak", "volcano", "ridge", "hiking")
            else -> listOf(value)
        }
    }
}
