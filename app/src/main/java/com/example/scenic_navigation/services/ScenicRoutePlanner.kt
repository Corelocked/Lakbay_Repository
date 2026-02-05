package com.example.scenic_navigation.services

import android.content.Context
import android.util.Log
import com.example.scenic_navigation.models.ScenicPoi
import com.example.scenic_navigation.utils.GeoUtils
import com.example.scenic_navigation.ml.PoiReranker
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
class ScenicRoutePlanner(
    private val context: Context? = null,
    // optional overrides for clustering params (meters, minPts). If null, use Config defaults.
    clusterEpsMeters: Double? = null,
    clusterMinPts: Int? = null
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }

    // (Curation boosting applied at planner level)

    companion object {
        // Minimum scenic score threshold - filter out POIs below this value
        private const val MIN_SCENIC_SCORE = 45

        // LRU cache for Overpass queries at specific rounded locations
        private val poiQueryCache: MutableMap<String, List<com.example.scenic_navigation.models.ScenicPoi>> =
            java.util.Collections.synchronizedMap(
                object : java.util.LinkedHashMap<String, List<com.example.scenic_navigation.models.ScenicPoi>>(128, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<com.example.scenic_navigation.models.ScenicPoi>>?): Boolean {
                        return size > 300
                    }
                }
            )
        // Smart POI generation config
        // Tags which are allowed to have synthesized names when `name` is missing
        private val ALLOWED_SYNTHESIS_TAGS = setOf(
            "viewpoint",
            "waterfall",
            "peak",
            "saddle",
            "bay",
            "cape",
            "ridge",
            "nature_reserve",
            "park",
            "attraction",
            "historic",
            "museum"
        )

        // Whitelist of primary tags we consider for POIs (anything outside will be skipped)
        private val POI_TAG_WHITELIST = setOf(
            "viewpoint",
            "park",
            "attraction",
            "picnic_site",
            "camp_site",
            "museum",
            "historic",
            "waterfall",
            "peak",
            "bay",
            "cape",
            "coastline",
            "beach",
            "nature_reserve",
            "wood",
            "ridge",
            "restaurant",
            "gift",
            "souvenir",
            "art",
            "craft",
            "bakery",
            "deli",
            "cheese",
            "wine",
            "farm",
            "seafood",
            "books",
            "clothes"
        )

        // Blend weight for generic query contribution (0.0..1.0)
        private const val GENERIC_BLEND_WEIGHT = 0.5
    }

    // Instance-level cluster parameters (resolved from constructor or Config)
    private val clusterEpsMeters: Double = clusterEpsMeters ?: com.example.scenic_navigation.config.Config.DEFAULT_CLUSTER_EPS_METERS
    private val clusterMinPts: Int = clusterMinPts ?: com.example.scenic_navigation.config.Config.DEFAULT_CLUSTER_MIN_PTS

    // PoiService used to access the local dataset (luzon CSV)
    private val poiService: com.example.scenic_navigation.services.PoiService by lazy {
        com.example.scenic_navigation.services.PoiService(context)
    }

    // PoiReranker for ML-based POI re-ranking
    private val poiReranker: PoiReranker by lazy {
        PoiReranker(context!!)
    }

    private fun deviceLocaleTag(): String? {
        return try {
            val cfg = context?.resources?.configuration
            if (cfg == null) return Locale.getDefault().toString()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cfg.locales?.get(0)?.toString() ?: Locale.getDefault().toString()
            } else {
                cfg.locale?.toString() ?: Locale.getDefault().toString()
            }
        } catch (e: Exception) {
            Locale.getDefault().toString()
        }
    }

    private fun computeScoreFromCategory(cat: String?, routeType: String, isNearCoast: Boolean): Int {
        val type = cat?.lowercase(Locale.getDefault()) ?: "unknown"
        return when (routeType) {
            "oceanic" -> if (isNearCoast) when {
                type.contains("beach") -> 120
                type.contains("coast") -> 110
                type.contains("bay") -> 105
                type.contains("cape") -> 100
                type.contains("view") -> 95
                type.contains("nature") -> 85
                type.contains("water") -> 75
                type.contains("waterfall") -> 70
                type.contains("park") -> 60
                type.contains("attraction") -> 50
                type.contains("picnic_site") -> 40
                else -> 20
            } else when {
                type.contains("historic") -> 50
                else -> 20
            }
            "mountain" -> when {
                type.contains("peak") -> 120
                type.contains("volcano") -> 115
                type.contains("saddle") -> 105
                type.contains("view") -> 100
                type.contains("ridge") -> 95
                type.contains("nature") -> 90
                type.contains("wood") -> 80
                type.contains("waterfall") -> 75
                type.contains("park") -> 65
                type.contains("attract") || type.contains("attraction") -> 55
                type.contains("historic") -> 50
                type.contains("adventure") -> 110
                type.contains("hiking trail") -> 105
                type.contains("trail") -> 100
                else -> 20
            }
            else -> when {
                type.contains("view") -> 110
                type.contains("peak") -> 100
                type.contains("nature") -> 95
                type.contains("beach") -> 90
                type.contains("park") -> 85
                type.contains("wood") -> 70
                type.contains("historic") -> 65
                type.contains("museum") -> 60
                type.contains("restaurant") -> 80
                type.contains("cafe") -> 75
                type.contains("food") -> 70
                type.contains("hotel") -> 25
                type.contains("camp") -> 40
                type.contains("picnic") -> 35
                else -> 20
            }
        }
    }

    /**
     * Fetch scenic POIs along a route with dynamic sampling based on route length
     */
    suspend fun fetchScenicPois(
        routePoints: List<GeoPoint>,
        packageName: String,
        routeType: String = "generic",
        curationIntent: com.example.scenic_navigation.models.CurationIntent? = null,
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

        // Create segment centers for batched bbox/around queries so we run fewer Overpass requests
        val segmentLengthMeters = 50_000.0 // target ~50km per segment
        val segments = maxOf(1, (length / segmentLengthMeters).toInt())
        val scenicPois = mutableListOf<ScenicPoi>()
        val semaphore = Semaphore(4) // slightly higher concurrency for segment queries
        val mutex = Mutex()

        // Build list of segment point slices (evenly split by index)
        val segmentsList = mutableListOf<List<GeoPoint>>()
        for (s in 0 until segments) {
            val startIdx = (s * routePoints.size) / segments
            val endIdx = (((s + 1) * routePoints.size) / segments).coerceAtMost(routePoints.size - 1)
            val sub = routePoints.subList(startIdx, endIdx.coerceAtLeast(startIdx))
            if (sub.isNotEmpty()) segmentsList.add(sub)
        }

        supervisorScope {
            segmentsList.forEachIndexed { idx, sub ->
                launch {
                    semaphore.withPermit {
                        try {
                            onStatusUpdate?.invoke("Scenic segment ${idx + 1}/${segmentsList.size}…")
                        } catch (_: Exception) {}

                        // Compute bbox for this segment and add a small padding
                        val latitudes = sub.map { it.latitude }
                        val longitudes = sub.map { it.longitude }
                        val minLat = latitudes.minOrNull() ?: sub.first().latitude
                        val maxLat = latitudes.maxOrNull() ?: sub.first().latitude
                        val minLon = longitudes.minOrNull() ?: sub.first().longitude
                        val maxLon = longitudes.maxOrNull() ?: sub.first().longitude

                        // Padding in degrees (approx): convert ~radius meters to degrees roughly
                        val padDegrees = when {
                            length < 10_000 -> 0.02 // ~2km for very short routes
                            length < 50_000 -> 0.03 // ~3km for short routes
                            else -> 0.05 // ~5km for longer routes
                        }
                        val bboxMinLat = (minLat - padDegrees)
                        val bboxMaxLat = (maxLat + padDegrees)
                        val bboxMinLon = (minLon - padDegrees)
                        val bboxMaxLon = (maxLon + padDegrees)

                        val cacheKey = "bbox_${"%.3f".format(bboxMinLat)}_${"%.3f".format(bboxMinLon)}_${"%.3f".format(bboxMaxLat)}_${"%.3f".format(bboxMaxLon)}_${routeType}"
                        val cached = poiQueryCache[cacheKey]
                        val pois = if (cached != null) cached else {
                            val fresh = fetchScenicPoisInBBox(bboxMinLat, bboxMinLon, bboxMaxLat, bboxMaxLon, packageName, routeType, sub, curationIntent)
                            if (fresh.isNotEmpty()) poiQueryCache[cacheKey] = fresh
                            fresh
                        }

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

        // Filter POIs to only include those within 5km of the route
        val filteredByDistance = scenicPois.filter { poi ->
            val nearest = routePoints.minOfOrNull { p ->
                GeoUtils.haversine(p.latitude, p.longitude, poi.lat, poi.lon)
            } ?: Double.MAX_VALUE
            nearest <= 5000.0 // 5km threshold
        }

        // De-duplicate and sort by score
        val dedup = LinkedHashMap<String, ScenicPoi>()
        for (p in filteredByDistance) {
            val key = "${"%.5f".format(p.lat)}_${"%.5f".format(p.lon)}_${p.name}"
            val existing = dedup[key]
            if (existing == null || p.score > existing.score) {
                dedup[key] = p
            }
        }

        var list = dedup.values.toMutableList()
        list.sortByDescending { it.score }

        // Apply curation-based boosts and filters (if provided)
        try {
            val cfg = com.example.scenic_navigation.services.CurationMapper.map(curationIntent, deviceLocaleTag())
            if (cfg.poiBoosts.isNotEmpty() || cfg.tagFilters.isNotEmpty()) {
                list = list.map { poi ->
                    var totalBoost = 0.0
                    val t = poi.type.lowercase(Locale.getDefault())
                    for ((k, v) in cfg.poiBoosts) {
                        if (t.contains(k)) totalBoost += v
                    }
                    // If tagFilters are present and poi doesn't match any, deprioritize slightly
                    val matchesFilter = if (cfg.tagFilters.isEmpty()) true else cfg.tagFilters.any { filter ->
                        t.contains(filter.lowercase(Locale.getDefault()))
                    }
                    val multiplier = (1.0 + totalBoost).coerceAtMost(3.0) * if (matchesFilter) 1.0 else 0.85
                    poi.copy(score = (poi.score * multiplier).toInt())
                }.toMutableList()
                list.sortByDescending { it.score }
            }
        } catch (_: Exception) {}

        // Apply ML reranker if available
        try {
            val centerLat = routePoints.getOrNull(routePoints.size / 2)?.latitude ?: 14.5995
            val centerLon = routePoints.getOrNull(routePoints.size / 2)?.longitude ?: 120.9842
            val poisForRerank = list.map { poi ->
                com.example.scenic_navigation.models.Poi(
                    name = poi.name,
                    category = poi.type,
                    description = poi.description,
                    municipality = poi.municipality ?: "",
                    lat = poi.lat,
                    lon = poi.lon,
                    scenicScore = poi.score.toFloat()
                )
            }
            val rerankedPois = poiReranker.rerank(poisForRerank, centerLat, centerLon, System.currentTimeMillis())
            list = rerankedPois.map { poi ->
                ScenicPoi(
                    name = poi.name,
                    type = poi.category,
                    lat = poi.lat ?: 0.0,
                    lon = poi.lon ?: 0.0,
                    score = poi.scenicScore?.toInt() ?: 0,
                    municipality = poi.municipality.takeIf { it.isNotBlank() },
                    description = poi.description
                )
            }.toMutableList()
        } catch (e: Exception) {
            Log.w("ScenicRoutePlanner", "ML reranker failed: ${e.message}")
        }

        // Return all processed POIs without capping to allow full scenic discovery
        return@withContext list
    }

    suspend fun suggestViaPointsForCuration(
        startPoint: GeoPoint,
        destPoint: GeoPoint,
        packageName: String,
        curationIntent: com.example.scenic_navigation.models.CurationIntent?
    ): List<GeoPoint> = withContext(Dispatchers.IO) {
        if (curationIntent == null) return@withContext emptyList()

        val poiService = com.example.scenic_navigation.services.PoiService(context)
        val allPois = poiService.getLocalPois()

        // Filter POIs based on curation intent
        val filteredPois = allPois.filter { poi ->
            val cat = poi.category?.lowercase(Locale.getDefault()) ?: ""
            val matchesSeeing = when (curationIntent.seeing) {
                com.example.scenic_navigation.models.SeeingType.OCEANIC -> cat.contains("beach") || cat.contains("coast") || cat.contains("ocean") || cat.contains("sea")
                com.example.scenic_navigation.models.SeeingType.MOUNTAIN -> cat.contains("mount") || cat.contains("hike") || cat.contains("view") || cat.contains("mountain")
            } || cat.contains("historic") || cat.contains("church") || cat.contains("monument") // Always include historical landmarks
            val matchesActivity = when (curationIntent.activity) {
                com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE -> cat.contains("shop") || cat.contains("mall") || cat.contains("market") || cat.contains("restaurant") || cat.contains("food") || cat.contains("cafe")
                com.example.scenic_navigation.models.ActivityType.CULTURAL -> cat.contains("museum") || cat.contains("historic") || cat.contains("theatre") || cat.contains("gallery") || cat.contains("church") || cat.contains("heritage")
                com.example.scenic_navigation.models.ActivityType.ADVENTURE -> cat.contains("peak") || cat.contains("waterfall") || cat.contains("hiking") || cat.contains("climbing") || cat.contains("adventure") || cat.contains("sport") || cat.contains("hiking trail") || cat.contains("trail")
                com.example.scenic_navigation.models.ActivityType.RELAXATION -> cat.contains("beach") || cat.contains("park") || cat.contains("spa") || cat.contains("resort") || cat.contains("relax") || cat.contains("nature")
                com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY -> cat.contains("park") || cat.contains("playground") || cat.contains("zoo") || cat.contains("museum") || cat.contains("picnic") || cat.contains("family")
                com.example.scenic_navigation.models.ActivityType.ROMANTIC -> cat.contains("view") || cat.contains("restaurant") || cat.contains("park") || cat.contains("beach") || cat.contains("sunset") || cat.contains("romantic")
                else -> true // sightseeing, allow all
            }
            matchesSeeing && matchesActivity && poi.lat != null && poi.lon != null
        }

        // Select POIs close to the straight line from start to dest
        val linePois = filteredPois.filter { poi ->
            val dist = com.example.scenic_navigation.utils.GeoUtils.distanceToLine(poi.lat!!, poi.lon!!, startPoint.latitude, startPoint.longitude, destPoint.latitude, destPoint.longitude)
            dist < 3000.0 // within 3km of the line
        }

        // Sort by position along the line (from start to dest)
        val sortedPois = linePois.sortedBy { poi ->
            val distToStart = com.example.scenic_navigation.utils.GeoUtils.haversine(startPoint.latitude, startPoint.longitude, poi.lat!!, poi.lon!!)
            val totalDist = com.example.scenic_navigation.utils.GeoUtils.haversine(startPoint.latitude, startPoint.longitude, destPoint.latitude, destPoint.longitude)
            distToStart / totalDist // fraction along the path
        }

        // Take top 5 via points
        val viaPoints = sortedPois.take(5).map { poi ->
            GeoPoint(poi.lat!!, poi.lon!!)
        }

        return@withContext viaPoints
    }

    private suspend fun fetchScenicPoisInBBox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        packageName: String,
        routeType: String = "generic",
        segmentPoints: List<GeoPoint>,
        curationIntent: com.example.scenic_navigation.models.CurationIntent? = null
    ): List<ScenicPoi> = withContext(Dispatchers.IO) {
        // Get all local POIs
        val allLocalPois = poiService.getLocalPois()

        // Filter POIs within the bbox
        val bboxPois = allLocalPois.filter { poi ->
            val lat = poi.lat ?: return@filter false
            val lon = poi.lon ?: return@filter false
            lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon
        }

        // Convert to ScenicPoi with scores
        val scenicPois = bboxPois.mapNotNull { poi ->
            val lat = poi.lat ?: return@mapNotNull null
            val lon = poi.lon ?: return@mapNotNull null
            val isNearCoast = segmentPoints.any { p ->
                p.distanceToAsDouble(GeoPoint(lat, lon)) < 20000 // 20km threshold
            }
            val score = computeScoreFromCategory(poi.category, routeType, isNearCoast)
            ScenicPoi(
                name = poi.name,
                type = poi.category ?: "unknown",
                lat = lat,
                lon = lon,
                score = score,
                municipality = poi.municipality,
                description = poi.description
            )
        }

        // Apply curation
        try {
            val cfg = com.example.scenic_navigation.services.CurationMapper.map(curationIntent, deviceLocaleTag())
            if (cfg.poiBoosts.isNotEmpty() || cfg.tagFilters.isNotEmpty()) {
                val after = scenicPois.map { poi ->
                    var totalBoost = 0.0
                    val t = poi.type.lowercase(Locale.getDefault())
                    for ((k, v) in cfg.poiBoosts) if (t.contains(k)) totalBoost += v
                    val matchesFilter = if (cfg.tagFilters.isEmpty()) true else cfg.tagFilters.any { filter ->
                        t.contains(filter.lowercase(Locale.getDefault()))
                    }
                    val multiplier = (1.0 + totalBoost).coerceAtMost(3.0) * if (matchesFilter) 1.0 else 0.85
                    poi.copy(score = (poi.score * multiplier).toInt())
                }
                val filtered = after.filter { poi ->
                    poi.score >= MIN_SCENIC_SCORE && poi.name.isNotBlank()
                }
                return@withContext filtered
            }
        } catch (_: Exception) {}

        // Filter
        val filtered = scenicPois.filter { poi ->
            poi.score >= MIN_SCENIC_SCORE && poi.name.isNotBlank()
        }
        return@withContext filtered
    }

    /**
     * Select the most scenic route from a list of alternative routes.
     * Returns a Pair(bestRoute, poisOnBestRoute).
     */
    suspend fun selectMostScenicRoute(
        alternatives: List<List<GeoPoint>>,
        packageName: String,
        routeType: String = "generic",
        curationIntent: com.example.scenic_navigation.models.CurationIntent? = null,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Pair<List<GeoPoint>?, List<ScenicPoi>> = withContext(Dispatchers.IO) {
        if (alternatives.isEmpty()) return@withContext Pair(null, emptyList())

        var mostScenicRoute: List<GeoPoint>? = null
        var mostScenicPois: List<ScenicPoi> = emptyList()
        var highestScore = Double.NEGATIVE_INFINITY

        for ((idx, alt) in alternatives.withIndex()) {
            try {
                onStatusUpdate?.invoke("Scoring alternative ${idx + 1}/${alternatives.size}…")
            } catch (_: Exception) {}

            val pois = try {
                fetchScenicPois(alt, packageName, routeType, curationIntent, onStatusUpdate)
            } catch (e: Exception) {
                Log.w("ScenicRoutePlanner", "Error fetching POIs for alternative: ${e.message}")
                emptyList()
            }

            val score = try {
                calculateScenicScore(alt, pois)
            } catch (e: Exception) {
                Log.w("ScenicRoutePlanner", "Error scoring alternative: ${e.message}")
                Double.NEGATIVE_INFINITY
            }

            if (score > highestScore) {
                highestScore = score
                mostScenicRoute = alt
                mostScenicPois = pois
            }
        }

        return@withContext Pair(mostScenicRoute, mostScenicPois)
    }

    /**
     * Calculate a scenic score for a route based on its POIs.
     * Higher scores indicate more scenic routes.
     */
    private fun calculateScenicScore(routePoints: List<GeoPoint>, pois: List<ScenicPoi>): Double {
        // Basic implementation: sum the scores of all POIs along the route
        // TODO: Enhance with distance decay, POI density, etc.
        return pois.sumOf { it.score }.toDouble()
    }
}


































