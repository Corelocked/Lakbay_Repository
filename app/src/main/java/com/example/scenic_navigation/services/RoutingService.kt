package com.example.scenic_navigation.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint

/**
 * Service for fetching routes using OSRM API
 */
class RoutingService {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }
    // Cache for segment routes: key is "fromLon,fromLat;toLon,toLat"
    private val segmentCache: MutableMap<String, List<org.osmdroid.util.GeoPoint>> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, List<org.osmdroid.util.GeoPoint>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<org.osmdroid.util.GeoPoint>>?): Boolean {
                return size > 200
            }
        }
    )

    // Hardcoded Philippine mountain waypoints
    private val mountainWaypoints = mapOf(
        // LUZON MOUNTAIN ROUTES
        "luzon_baguio" to listOf(
            GeoPoint(15.2000, 120.6000), // Tarlac highlands
            GeoPoint(15.8000, 120.5700), // Kennon Road scenic viewpoint
            GeoPoint(16.4000, 120.5900)  // Baguio City
        ),
        "luzon_sagada" to listOf(
            GeoPoint(16.6000, 120.7000), // Bontoc
            GeoPoint(17.0800, 120.9000)  // Sagada
        ),
        "luzon_tagaytay" to listOf(
            GeoPoint(14.1650, 121.0500), // Cavite highlands
            GeoPoint(14.1100, 120.9600)  // Tagaytay Ridge
        ),
        "luzon_banaue" to listOf(
            GeoPoint(15.8000, 120.5700), // Baguio
            GeoPoint(16.6000, 120.7000), // Bontoc
            GeoPoint(16.9270, 121.0560)  // Banaue
        ),
        "luzon_pulag" to listOf(
            GeoPoint(16.4000, 120.5900), // Baguio
            GeoPoint(16.5500, 120.7500), // La Trinidad
            GeoPoint(16.6000, 120.8800)  // Pulag area
        ),
        // VISAYAS MOUNTAIN ROUTES
        "visayas_cebu_mountain" to listOf(
            GeoPoint(10.3500, 123.8500), // Busay highlands
            GeoPoint(10.3700, 123.8700)  // Tops viewpoint
        ),
        "visayas_canlaon" to listOf(
            GeoPoint(10.4000, 123.1000), // Negros highlands
            GeoPoint(10.4120, 123.1320)  // Canlaon Volcano area
        ),
        // MINDANAO MOUNTAIN ROUTES
        "mindanao_bukidnon" to listOf(
            GeoPoint(8.4800, 124.6500), // Cagayan de Oro
            GeoPoint(8.2500, 125.0000), // Bukidnon highlands
            GeoPoint(7.9000, 125.1000)  // Malaybalay area
        ),
        "mindanao_davao_highlands" to listOf(
            GeoPoint(7.1900, 125.4550), // Davao City
            GeoPoint(7.3000, 125.3000), // Toril highlands
            GeoPoint(7.4000, 125.2500)  // Eden/Malagos highlands
        ),
        "mindanao_lake_sebu" to listOf(
            GeoPoint(6.1100, 125.1700), // General Santos
            GeoPoint(6.2000, 124.7000), // Surallah
            GeoPoint(6.2100, 124.7040)  // Lake Sebu
        )
    )
    private val coastalWaypoints = mapOf(
        // LUZON ROUTES
        "luzon_north" to listOf(
            GeoPoint(15.7800, 120.2800), // Zambales coast
            GeoPoint(16.0500, 120.3300), // La Union coast
            GeoPoint(17.5800, 120.3800), // Ilocos Norte coast
            GeoPoint(18.1700, 120.5900)  // Northern tip before going to Aparri
        ),
        "luzon_bicol_east" to listOf(
            GeoPoint(14.6000, 121.2000), // Laguna
            GeoPoint(14.1700, 121.6200), // Quezon coast
            GeoPoint(13.6200, 122.0100), // Camarines Norte coast
            GeoPoint(13.4200, 123.4100)  // Camarines Sur/Albay coast
        ),
        "luzon_bicol_west" to listOf(
            GeoPoint(14.0100, 120.9800), // Batangas coast
            GeoPoint(13.7500, 120.9500), // Mindoro Strait view
            GeoPoint(13.4200, 123.4100)  // Join at Albay
        ),
        "luzon_south" to listOf(
            GeoPoint(14.4800, 120.8800), // Cavite coast near Manila
            GeoPoint(14.0100, 120.9800), // Batangas coast
            GeoPoint(13.7500, 120.9500)  // Southern Batangas coast
        ),
        // VISAYAS ROUTES
        "visayas_cebu_coastal" to listOf(
            GeoPoint(10.3200, 123.7500), // Northern Cebu coast
            GeoPoint(10.6900, 124.0000), // Malapascua area
            GeoPoint(10.3200, 123.7500), // Cebu City
            GeoPoint(9.8600, 123.4000)   // Southern Cebu coast
        ),
        "visayas_panay_coastal" to listOf(
            GeoPoint(11.9600, 122.0100), // Aklan/Boracay area
            GeoPoint(11.0000, 122.5500), // Iloilo coast
            GeoPoint(10.6900, 122.5700)  // Guimaras Strait
        ),
        "visayas_bohol_coastal" to listOf(
            GeoPoint(9.8500, 124.4300), // Tagbilaran/Panglao
            GeoPoint(10.1500, 124.2000), // Northern Bohol coast
            GeoPoint(9.6500, 124.5000)   // Eastern Bohol coast
        ),
        "visayas_leyte_samar" to listOf(
            GeoPoint(11.2500, 125.0000), // Samar west coast
            GeoPoint(11.0000, 124.9500), // San Juanico Strait
            GeoPoint(10.7200, 124.8400), // Tacloban area
            GeoPoint(10.3900, 125.0100)  // Southern Leyte coast
        ),
        // MINDANAO ROUTES
        "mindanao_south_pacific" to listOf(
            GeoPoint(6.9000, 125.6100), // Davao coastal
            GeoPoint(6.5000, 125.2000), // Davao del Sur coast
            GeoPoint(6.1100, 125.1700)  // General Santos coastal approach
        ),
        "mindanao_north_coast" to listOf(
            GeoPoint(8.4800, 124.6500), // Cagayan de Oro coastal
            GeoPoint(8.8000, 125.1000), // Misamis Oriental coast
            GeoPoint(8.9500, 125.5400)  // Butuan Bay approach
        ),
        "mindanao_west_zamboanga" to listOf(
            GeoPoint(8.0000, 123.5000), // Lanao del Norte coast
            GeoPoint(7.8000, 123.2000), // Misamis Occidental coast
            GeoPoint(7.3000, 122.7000), // Zamboanga del Norte coast
            GeoPoint(6.9100, 122.0700)  // Zamboanga City coastal
        ),
        "mindanao_surigao_coast" to listOf(
            GeoPoint(9.7800, 125.4900), // Surigao City
            GeoPoint(9.5000, 125.7000), // Eastern Surigao coast
            GeoPoint(9.0000, 126.0000)  // Pacific coast route
        ),
        // INTER-ISLAND ROUTES
        "luzon_visayas_manila_cebu" to listOf(
            GeoPoint(14.0100, 120.9800), // Batangas coast
            GeoPoint(13.4000, 121.3000), // Mindoro coast
            GeoPoint(12.5000, 121.9000), // Romblon area
            GeoPoint(11.2500, 123.2500), // Negros coast
            GeoPoint(10.3200, 123.7500)  // Cebu
        ),
        "luzon_mindanao_manila_davao" to listOf(
            GeoPoint(13.4200, 123.4100), // Bicol
            GeoPoint(12.5000, 124.0000), // Samar coast
            GeoPoint(11.0000, 125.0000), // Leyte coast
            GeoPoint(10.3900, 125.0100), // Southern Leyte
            GeoPoint(9.0000, 125.5000),  // Surigao coast
            GeoPoint(7.5000, 126.0000),  // Eastern Mindanao coast
            GeoPoint(6.9000, 125.6100)   // Davao
        )
    )

    // Expose a read-only view of known coastal waypoint keys for UI selection
    fun getCoastalWaypointKeys(): List<String> = coastalWaypoints.keys.toList()

    // Return a coastal waypoint set for a given key, or null if not found
    fun getCoastalWaypointSet(key: String?): List<GeoPoint>? = key?.let { coastalWaypoints[it] }

    suspend fun fetchRoute(
        start: GeoPoint,
        destination: GeoPoint,
        packageName: String,
        mode: String = "default",
        waypoints: List<GeoPoint>? = null
    ): List<GeoPoint> = withContext(Dispatchers.IO) {
        // If explicit waypoints are provided, use them to generate a via-waypoint route
        if (waypoints != null && waypoints.isNotEmpty()) {
            return@withContext generateRouteViaWaypoints(start, destination, packageName, waypoints)
        }

        when (mode) {
            "oceanic" -> generateCoastalRouteViaWaypoints(start, destination, packageName)
            "mountain" -> generateMountainRouteViaWaypoints(start, destination, packageName)
            else -> {
                // Default: direct route
                val waypointsStr = "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}"
                val url = "https://router.project-osrm.org/route/v1/driving/" +
                        waypointsStr +
                        "?overview=full&geometries=geojson&alternatives=false"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", packageName)
                    .header("Accept", "application/json")
                    .build()
                try {
                    Log.d("RoutingService", "OSRM request: $url")
                    val response = httpClient.newCall(request).execute()
                    Log.d("RoutingService", "OSRM response code: ${response.code}")
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        return@withContext parseRouteFromOsrmResponse(body)
                    }
                } catch (e: Exception) {
                    Log.d("RoutingService", "OSRM error: ${e.message}")
                }
                return@withContext emptyList()
            }
        }
    }

    private fun findBestWaypointSet(start: GeoPoint, dest: GeoPoint, allWaypointSets: Map<String, List<GeoPoint>>, isLongDistanceOceanic: Boolean = false): List<GeoPoint> {
        if (allWaypointSets.isEmpty()) return emptyList()

        var bestSet: List<GeoPoint> = emptyList()
        var minAvgDistance = Double.MAX_VALUE
        // Compute distances of each waypoint to the projected point on the line segment start->dest.
        // This avoids misleading average distances to the route midpoint when the route is long or
        // when coastal waypoints are spread along the coastline.
        val sx = start.longitude
        val sy = start.latitude
        val dx = dest.longitude - sx
        val dy = dest.latitude - sy
        val denom = dx * dx + dy * dy

        for ((key, waypoints) in allWaypointSets) {
            if (waypoints.isEmpty()) continue

            val avgDistance = if (denom == 0.0) {
                // fallback to midpoint distance if start==dest
                val routeMidPoint = GeoPoint(
                    (start.latitude + dest.latitude) / 2.0,
                    (start.longitude + dest.longitude) / 2.0
                )
                waypoints.sumOf { it.distanceToAsDouble(routeMidPoint) } / waypoints.size
            } else {
                waypoints.sumOf { wp ->
                    val wx = wp.longitude - sx
                    val wy = wp.latitude - sy
                    var t = (wx * dx + wy * dy) / denom
                    if (t < 0.0) t = 0.0
                    if (t > 1.0) t = 1.0
                    val projLon = sx + t * dx
                    val projLat = sy + t * dy
                    try {
                        com.example.scenic_navigation.utils.GeoUtils.haversine(wp.latitude, wp.longitude, projLat, projLon)
                    } catch (_: Exception) {
                        wp.distanceToAsDouble(GeoPoint(projLat, projLon))
                    }
                } / waypoints.size
            }

            if (avgDistance < minAvgDistance) {
                minAvgDistance = avgDistance
                bestSet = waypoints
                Log.d("RoutingService", "New best waypoint set: $key with avg projected distance=${"%.0f".format(avgDistance)}m")
            }
        }

        // Relaxed thresholds: coastal/marine routes can be more spread out; allow more permissive matching.
        val threshold = if (isLongDistanceOceanic) 400_000 else 100_000

        if (minAvgDistance > threshold) {
            Log.w("RoutingService", "Closest waypoint set avg distance ${"%.0f".format(minAvgDistance)}m exceeds threshold ${threshold}m. Falling back to nearest set as a best-effort.")
            // Return the nearest set anyway as a fallback so oceanic/mountain flows still get waypoints.
            return bestSet
        }

        return bestSet
    }

    /**
     * Sort waypoints to follow a logical path from start to destination
     * This prevents routing loops by ordering waypoints by their distance along the start->dest vector
     * Also filters out waypoints that are too far from the direct path to prevent branching
     */
    private fun sortWaypointsAlongPath(start: GeoPoint, dest: GeoPoint, waypoints: List<GeoPoint>): List<GeoPoint> {
        if (waypoints.isEmpty()) return waypoints

        // Calculate the direction vector from start to dest
        val dx = dest.longitude - start.longitude
        val dy = dest.latitude - start.latitude
        val denom = dx * dx + dy * dy

        if (denom == 0.0) {
            // Start and dest are the same, just return waypoints as-is
            return waypoints
        }

        // Calculate the direct distance for threshold calculation
        val directDistance = try {
            com.example.scenic_navigation.utils.GeoUtils.haversine(
                start.latitude, start.longitude,
                dest.latitude, dest.longitude
            )
        } catch (_: Exception) {
            start.distanceToAsDouble(dest)
        }

        // Set maximum perpendicular distance threshold based on route length
        // For short routes (<50km), allow max 2.5km deviation to avoid unnecessary detours
        // For longer routes, allow up to 15% deviation but cap at 50km
        val maxPerpendicularDistance = when {
            directDistance < 50_000 -> 2_500.0 // 5km for short routes
            directDistance < 200_000 -> directDistance * 0.15 // 15% for medium routes
            else -> 50_000.0 // 50km max for long routes
        }

        // For each waypoint, calculate its projection onto the start->dest line
        // and its perpendicular distance from the line
        val waypointsWithMetrics = waypoints.mapNotNull { wp ->
            val wx = wp.longitude - start.longitude
            val wy = wp.latitude - start.latitude

            // Project waypoint onto start->dest vector (0.0 = at start, 1.0 = at dest)
            val t = (wx * dx + wy * dy) / denom

            // Calculate perpendicular distance from the line
            val projLon = start.longitude + t * dx
            val projLat = start.latitude + t * dy
            val perpDistance = try {
                com.example.scenic_navigation.utils.GeoUtils.haversine(
                    wp.latitude, wp.longitude,
                    projLat, projLon
                )
            } catch (_: Exception) {
                wp.distanceToAsDouble(GeoPoint(projLat, projLon))
            }

            // Filter out waypoints that are too far from the path or outside the start-dest range
            // Allow some flexibility with t (e.g., -0.1 to 1.0) for waypoints slightly outside
            if (perpDistance <= maxPerpendicularDistance && t >= -0.1 && t <= 1.0) {
                Triple(wp, t, perpDistance)
            } else {
                Log.d("RoutingService", "Filtering out waypoint at (${wp.latitude},${wp.longitude}): " +
                    "perpDist=${"%.0f".format(perpDistance)}m (max=${"%.0f".format(maxPerpendicularDistance)}m), t=${"%.2f".format(t)}")
                null
            }
        }

        if (waypointsWithMetrics.isEmpty()) {
            Log.w("RoutingService", "All waypoints filtered out due to distance from path. Using original waypoints.")
            // Fallback: just sort by projection without filtering
            return waypoints.map { wp ->
                val wx = wp.longitude - start.longitude
                val wy = wp.latitude - start.latitude
                val t = (wx * dx + wy * dy) / denom
                Pair(wp, t)
            }.sortedBy { it.second }.map { it.first }
        }

        // Sort by projection value to get waypoints in order from start to dest
        return waypointsWithMetrics
            .sortedBy { it.second }
            .map { it.first }
    }

    private suspend fun generateRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String, waypoints: List<GeoPoint>): List<GeoPoint> {
        // Sort and filter waypoints to follow logical path from start to dest
        val sortedWaypoints = sortWaypointsAlongPath(start, dest, waypoints)

        // Limit waypoints to prevent overly complex routes
        // More waypoints = more chances for branching and routing issues
        // Keep it at 3 to match mountain routes for consistency
        val maxWaypoints = 3
        val limitedWaypoints = if (sortedWaypoints.size > maxWaypoints) {
            Log.d("RoutingService", "Limiting waypoints from ${sortedWaypoints.size} to $maxWaypoints to prevent route complexity")
            // Take evenly distributed waypoints
            val step = sortedWaypoints.size.toDouble() / maxWaypoints
            (0 until maxWaypoints).map { i ->
                sortedWaypoints[(i * step).toInt()]
            }
        } else {
            sortedWaypoints
        }

        val allPoints = listOf(start) + limitedWaypoints + listOf(dest)
        // Fetch segments in parallel with limited concurrency
        val semaphore = kotlinx.coroutines.sync.Semaphore(4)
        val deferred = mutableListOf<kotlinx.coroutines.Deferred<Pair<Int, List<GeoPoint>>>>()

        return kotlinx.coroutines.coroutineScope {
            for (i in 0 until allPoints.size - 1) {
                val idx = i
                val from = allPoints[i]
                val to = allPoints[i + 1]
                val key = "${from.longitude},${from.latitude};${to.longitude},${to.latitude}"

                // If cached, return immediately
                val cached = segmentCache[key]
                if (cached != null) {
                    deferred.add(async { Pair(idx, cached) })
                    continue
                }

                deferred.add(async {
                    semaphore.withPermit {
                        try {
                            val segmentUrl = "https://router.project-osrm.org/route/v1/driving/${from.longitude},${from.latitude};${to.longitude},${to.latitude}?overview=full&geometries=geojson"
                            Log.d("RoutingService", "Fetching segment[$idx]: $segmentUrl")
                            val request = Request.Builder()
                                .url(segmentUrl)
                                .header("User-Agent", packageName)
                                .header("Accept", "application/json")
                                .build()
                            val response = httpClient.newCall(request).execute()
                            Log.d("RoutingService", "Segment[$idx] response code: ${response.code}")
                            val segmentRoute = if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                parseRouteFromOsrmResponse(body)
                            } else emptyList()
                            response.close()
                            // cache route
                            if (segmentRoute.isNotEmpty()) segmentCache[key] = segmentRoute
                            Pair(idx, segmentRoute)
                        } catch (e: Exception) {
                            Log.e("RoutingService", "Error fetching route segment: ${e.message}")
                            Pair(idx, emptyList<GeoPoint>())
                        }
                    }
                })
            }

            // Wait and collect in order
            val segments = deferred.map { it.await() }.sortedBy { it.first }.map { it.second }

            // Assemble full route while avoiding exact/near-duplicate consecutive points
            val fullRoute = mutableListOf<GeoPoint>()
            for (segment in segments) {
                if (segment.isEmpty()) continue
                if (fullRoute.isEmpty()) {
                    fullRoute.addAll(segment)
                } else {
                    // Append segment but avoid repeating the first point if it's the same
                    // as the last point we already have (or extremely close).
                    val lastExisting = fullRoute.last()
                    val firstOfSegment = segment.first()
                    val isSame = try {
                        // Use a small distance threshold (1 meter) to collapse near-duplicates
                        val d = com.example.scenic_navigation.utils.GeoUtils.haversine(
                            lastExisting.latitude, lastExisting.longitude,
                            firstOfSegment.latitude, firstOfSegment.longitude
                        )
                        d < 1.0
                    } catch (_: Exception) {
                        // Fallback to exact coordinate compare
                        lastExisting.latitude == firstOfSegment.latitude && lastExisting.longitude == firstOfSegment.longitude
                    }

                    if (isSame) {
                        fullRoute.addAll(segment.drop(1))
                    } else {
                        fullRoute.addAll(segment)
                    }
                }
            }

            if (fullRoute.isEmpty()) {
                Log.w("RoutingService", "Assembled full route is empty after fetching all segments. This may indicate OSRM failed to return segment geometries for the provided waypoints.")
            }

            // Final pass: remove any remaining consecutive near-duplicates due to OSRM noise
            val deduped = mutableListOf<GeoPoint>()
            for (p in fullRoute) {
                if (deduped.isEmpty()) {
                    deduped.add(p)
                } else {
                    val last = deduped.last()
                    val dist = try {
                        com.example.scenic_navigation.utils.GeoUtils.haversine(last.latitude, last.longitude, p.latitude, p.longitude)
                    } catch (_: Exception) {
                        0.0
                    }
                    if (dist >= 0.5) { // keep points that are at least 0.5m apart
                        deduped.add(p)
                    }
                }
            }

            deduped
        }
    }

    private suspend fun generateCoastalRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String): List<GeoPoint> {
        Log.d("RoutingService", "Finding best coastal route...")
        val directDistance = start.distanceToAsDouble(dest)
        val isLongDistance = directDistance > 300_000

        val bestWaypoints = findBestWaypointSet(start, dest, coastalWaypoints, isLongDistance)

        if (bestWaypoints.isEmpty()) {
            Log.w("RoutingService", "No suitable coastal waypoints found. Falling back to direct route.")
            return fetchRoute(start, dest, packageName, "default")
        }

        // Order the waypoint set along the route direction then sample down to a few representative points
        val ordered = orderWaypointsAlongRoute(start, dest, bestWaypoints)
        val sampled = sampleWaypoints(ordered, maxPoints = if (isLongDistance) 5 else 1)
        Log.d("RoutingService", "Using coastal waypoints. original=${bestWaypoints.size} ordered=${ordered.size} sampled=${sampled.size}")
        try {
            Log.d("RoutingService", "Coastal ordered coords: ${ordered.joinToString(";") { "${it.latitude},${it.longitude}" }}")
            Log.d("RoutingService", "Coastal sampled coords: ${sampled.joinToString(";") { "${it.latitude},${it.longitude}" }}")
        } catch (_: Exception) {}
        return generateRouteViaWaypoints(start, dest, packageName, sampled)
    }

    private suspend fun generateMountainRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String): List<GeoPoint> {
        Log.d("RoutingService", "Finding best mountain route...")
        val bestWaypoints = findBestWaypointSet(start, dest, mountainWaypoints, false)

        if (bestWaypoints.isEmpty()) {
            Log.w("RoutingService", "No suitable mountain waypoints found. Falling back to direct route.")
            return fetchRoute(start, dest, packageName, "default")
        }

        // Order mountain waypoints along route then sample down to a small number to avoid creating many segments
        val ordered = orderWaypointsAlongRoute(start, dest, bestWaypoints)
        val sampled = sampleWaypoints(ordered, maxPoints = 3)
        Log.d("RoutingService", "Using mountain waypoints. original=${bestWaypoints.size} ordered=${ordered.size} sampled=${sampled.size}")
        try {
            Log.d("RoutingService", "Mountain ordered coords: ${ordered.joinToString(";") { "${it.latitude},${it.longitude}" }}")
            Log.d("RoutingService", "Mountain sampled coords: ${sampled.joinToString(";") { "${it.latitude},${it.longitude}" }}")
        } catch (_: Exception) {}
        return generateRouteViaWaypoints(start, dest, packageName, sampled)
    }

    // Helper: sample a list of GeoPoints evenly up to maxPoints
    private fun sampleWaypoints(all: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
        if (all.isEmpty()) return emptyList()
        if (maxPoints <= 0) return listOf(all.first())
        if (all.size <= maxPoints) return all
        val out = mutableListOf<GeoPoint>()
        val n = all.size
        for (i in 0 until maxPoints) {
            val idx = ((i.toDouble() / (maxPoints - 1).coerceAtLeast(1)) * (n - 1)).toInt()
            out.add(all[idx])
        }
        // Ensure we don't accidentally include duplicates if indices round the same
        val distinct = out.distinctBy { Pair(it.latitude, it.longitude) }
        if (distinct.isEmpty()) {
            // Fallback: include the first point
            return listOf(all.first())
        }
        return distinct
    }

    // Helper: order a set of waypoints along the straight line projection from start->dest
    // This prevents injected waypoint sets from causing back-and-forth routing when used as ordered via-points.
    private fun orderWaypointsAlongRoute(start: GeoPoint, dest: GeoPoint, waypoints: List<GeoPoint>): List<GeoPoint> {
        if (waypoints.isEmpty()) return waypoints
        val sx = start.longitude
        val sy = start.latitude
        val dx = dest.longitude - sx
        val dy = dest.latitude - sy
        val denom = dx * dx + dy * dy
        if (denom == 0.0) return waypoints

        return waypoints.map { wp ->
            val wx = wp.longitude - sx
            val wy = wp.latitude - sy
            val t = (wx * dx + wy * dy) / denom
            Pair(wp, t)
        }.sortedBy { it.second }
            .map { it.first }
    }

    private fun parseRouteFromOsrmResponse(body: String): List<GeoPoint> {
        return try {
            val json = org.json.JSONObject(body)

            val code = json.optString("code", "")
            if (code != "Ok") {
                Log.e("RoutingService", "OSRM error: $code")
                return emptyList()
            }

            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                return emptyList()
            }

            val routeObj = routes.getJSONObject(0)
            val geometry = routeObj.optJSONObject("geometry") ?: return emptyList()
            val coords = geometry.optJSONArray("coordinates") ?: return emptyList()

            val routePoints = mutableListOf<GeoPoint>()
            for (i in 0 until coords.length()) {
                val point = coords.getJSONArray(i)
                routePoints.add(GeoPoint(point.getDouble(1), point.getDouble(0)))
            }

            routePoints
        } catch (e: Exception) {
            Log.e("RoutingService", "Error parsing OSRM response: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchRouteAlternatives(
        start: GeoPoint,
        destination: GeoPoint,
        packageName: String
    ): List<List<GeoPoint>> = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&alternatives=true"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", packageName)
            .header("Accept", "application/json")
            .build()

        try {
            Log.d("RoutingService", "OSRM alternatives request: $url")
            val response = httpClient.newCall(request).execute()
            Log.d("RoutingService", "OSRM response code: ${response.code}")

            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                return@withContext parseAlternatives(body)
            }
        } catch (e: Exception) {
            Log.d("RoutingService", "OSRM error: ${e.message}")
        }

        return@withContext emptyList()
    }

    private fun parseAlternatives(body: String): List<List<GeoPoint>> {
        try {
            val json = org.json.JSONObject(body)
            val routes = json.getJSONArray("routes")
            val alternatives = mutableListOf<List<GeoPoint>>()

            for (i in 0 until routes.length()) {
                val routeObj = routes.getJSONObject(i)
                val geometry = routeObj.getJSONObject("geometry")
                val coords = geometry.getJSONArray("coordinates")
                val routePoints = mutableListOf<GeoPoint>()

                for (j in 0 until coords.length()) {
                    val point = coords.getJSONArray(j)
                    routePoints.add(GeoPoint(point.getDouble(1), point.getDouble(0)))
                }

                alternatives.add(routePoints)
            }

            return alternatives
        } catch (e: org.json.JSONException) {
            Log.e("RoutingService", "Error parsing alternatives: ${e.message}")
            return emptyList()
        }
    }
}
