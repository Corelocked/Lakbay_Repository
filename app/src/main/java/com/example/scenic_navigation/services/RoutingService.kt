package com.example.scenic_navigation.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint

/**
 * Service for fetching routes using OSRM API
 */
class RoutingService {
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

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

    suspend fun fetchRoute(
        start: GeoPoint,
        destination: GeoPoint,
        packageName: String,
        mode: String = "default"
    ): List<GeoPoint> = withContext(Dispatchers.IO) {
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

        val routeMidPoint = GeoPoint(
            (start.latitude + dest.latitude) / 2.0,
            (start.longitude + dest.longitude) / 2.0
        )

        for ((key, waypoints) in allWaypointSets) {
            if (waypoints.isEmpty()) continue

            val avgDistance = waypoints.sumOf { it.distanceToAsDouble(routeMidPoint) } / waypoints.size

            if (avgDistance < minAvgDistance) {
                minAvgDistance = avgDistance
                bestSet = waypoints
                Log.d("RoutingService", "New best waypoint set: $key with avg distance $avgDistance")
            }
        }

        val threshold = if (isLongDistanceOceanic) 300_000 else 50_000

        // If the closest waypoint set is still on average > threshold, it's probably not relevant.
        if (minAvgDistance > threshold) {
            Log.w("RoutingService", "No relevant waypoint sets found within the ${threshold/1000}km threshold.")
            return emptyList()
        }

        return bestSet
    }

    private suspend fun generateRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String, waypoints: List<GeoPoint>): List<GeoPoint> {
        val allPoints = listOf(start) + waypoints + listOf(dest)
        val fullRoute = mutableListOf<GeoPoint>()
        for (i in 0 until allPoints.size - 1) {
            val from = allPoints[i]
            val to = allPoints[i + 1]
            val segmentUrl = "https://router.project-osrm.org/route/v1/driving/${from.longitude},${from.latitude};${to.longitude},${to.latitude}?overview=full&geometries=geojson"
            val request = Request.Builder()
                .url(segmentUrl)
                .header("User-Agent", packageName)
                .header("Accept", "application/json")
                .build()
            try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val segmentRoute = parseRouteFromOsrmResponse(body)
                    if (segmentRoute.isNotEmpty()) {
                        if (fullRoute.isEmpty()) {
                            fullRoute.addAll(segmentRoute)
                        } else {
                            fullRoute.addAll(segmentRoute.drop(1))
                        }
                    }
                }
                response.close()
            } catch (e: Exception) {
                Log.e("RoutingService", "Error fetching route segment: ${e.message}")
            }
        }
        return fullRoute
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

        Log.d("RoutingService", "Using coastal waypoints. Count: ${bestWaypoints.size}")
        return generateRouteViaWaypoints(start, dest, packageName, bestWaypoints)
    }

    private suspend fun generateMountainRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String): List<GeoPoint> {
        Log.d("RoutingService", "Finding best mountain route...")
        val bestWaypoints = findBestWaypointSet(start, dest, mountainWaypoints, false)

        if (bestWaypoints.isEmpty()) {
            Log.w("RoutingService", "No suitable mountain waypoints found. Falling back to direct route.")
            return fetchRoute(start, dest, packageName, "default")
        }

        Log.d("RoutingService", "Using mountain waypoints. Count: ${bestWaypoints.size}")
        return generateRouteViaWaypoints(start, dest, packageName, bestWaypoints)
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
