package com.example.scenic_navigation.services

import com.example.scenic_navigation.models.ScenicPoi
import com.example.scenic_navigation.models.Waypoint
import com.example.scenic_navigation.models.RoadTripSegment
import com.example.scenic_navigation.models.RoadTripPlan
import com.example.scenic_navigation.utils.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Service for planning optimal road trips with multiple waypoints
 */
class RoadTripPlanner(
    private val routingService: RoutingService,
    private val scenicRoutePlanner: ScenicRoutePlanner
) {

    /**
     * Plan an optimal road trip with scenic waypoints
     */
    suspend fun planOptimalRoadTrip(
        start: GeoPoint,
        destination: GeoPoint,
        interestingPois: List<ScenicPoi>,
        maxDetourDistance: Double = 50000.0,
        maxWaypoints: Int = 8,
        preferScenicRoutes: Boolean = true,
        packageName: String,
        onStatusUpdate: ((String) -> Unit)? = null
    ): RoadTripPlan = withContext(Dispatchers.IO) {

        onStatusUpdate?.invoke("Planning optimal road trip...")

        // Step 1: Filter POIs that are reasonable detours
        // val directRoute = routingService.fetchRoute(start, destination, packageName)

        val candidateWaypoints = interestingPois
            .filter { poi ->
                val detourDistance = calculateDetourDistance(start, destination, GeoPoint(poi.lat, poi.lon))
                detourDistance <= maxDetourDistance
            }
            .map { poi ->
                Waypoint(
                    geoPoint = GeoPoint(poi.lat, poi.lon),
                    name = poi.name,
                    priority = (poi.score / 25).coerceIn(1, 5),
                    category = poi.type,
                    isOptional = poi.score < 60
                )
            }
            .sortedByDescending { it.priority }
            .take(maxWaypoints * 2)

        onStatusUpdate?.invoke("Optimizing waypoint sequence...")

        // Step 2: Optimize waypoint ordering
        val optimalWaypoints = optimizeWaypointSequence(start, destination, candidateWaypoints, maxWaypoints)

        onStatusUpdate?.invoke("Computing route segments...")

        // Step 3: Calculate route segments
        val segments = mutableListOf<RoadTripSegment>()
        val allWaypoints = listOf(
            Waypoint(start, "Start", 0L, 5)
        ) + optimalWaypoints + listOf(
            Waypoint(destination, "Destination", 0L, 5)
        )

        for (i in 0 until allWaypoints.size - 1) {
            val from = allWaypoints[i]
            val to = allWaypoints[i + 1]

            val segmentRoute = if (preferScenicRoutes) {
                val alternatives = fetchRouteAlternativesLocal(from.geoPoint, to.geoPoint, packageName)
                if (alternatives.isNotEmpty()) {
                    val result = scenicRoutePlanner.selectMostScenicRoute(alternatives, packageName)
                    val bestRoute = result.first
                    bestRoute ?: routingService.fetchRoute(from.geoPoint, to.geoPoint, packageName)
                } else {
                    routingService.fetchRoute(from.geoPoint, to.geoPoint, packageName)
                }
            } else {
                routingService.fetchRoute(from.geoPoint, to.geoPoint, packageName)
            }

            val distance = GeoUtils.computeRouteLength(segmentRoute)
            val estimatedDuration = estimateDrivingTime(distance)
            val scenicScore = if (preferScenicRoutes) {
                val nearbyPois = findNearbyPois(segmentRoute, interestingPois)
                nearbyPois.sumOf { it.score.toDouble() }
            } else 0.0

            segments.add(
                RoadTripSegment(
                    from = from,
                    to = to,
                    route = segmentRoute,
                    distanceMeters = distance,
                    estimatedDurationMs = estimatedDuration,
                    scenicScore = scenicScore
                )
            )
        }

        // Step 4: Calculate totals
        val totalDistance = segments.sumOf { it.distanceMeters }
        val totalDrivingTime = segments.sumOf { it.estimatedDurationMs }
        val totalStayTime = optimalWaypoints.sumOf { it.estimatedStayDuration }
        val totalScenicScore = segments.sumOf { it.scenicScore }

        RoadTripPlan(
            waypoints = optimalWaypoints,
            segments = segments,
            totalDistanceMeters = totalDistance,
            totalDurationMs = totalDrivingTime + totalStayTime,
            totalScenicScore = totalScenicScore
        )
    }

    private fun calculateDetourDistance(start: GeoPoint, end: GeoPoint, waypoint: GeoPoint): Double {
        val directDistance = GeoUtils.haversine(start.latitude, start.longitude, end.latitude, end.longitude)
        val viaDistance = GeoUtils.haversine(start.latitude, start.longitude, waypoint.latitude, waypoint.longitude) +
                         GeoUtils.haversine(waypoint.latitude, waypoint.longitude, end.latitude, end.longitude)
        return viaDistance - directDistance
    }

    private fun optimizeWaypointSequence(
        start: GeoPoint,
        end: GeoPoint,
        candidates: List<Waypoint>,
        maxWaypoints: Int
    ): List<Waypoint> {
        if (candidates.size <= maxWaypoints) return candidates

        val selected = mutableListOf<Waypoint>()
        val remaining = candidates.toMutableList()
        var currentPoint = start

        repeat(maxWaypoints.coerceAtMost(candidates.size)) {
            if (remaining.isEmpty()) return@repeat

            val nextWaypoint = remaining.maxByOrNull { waypoint ->
                val distance = GeoUtils.haversine(
                    currentPoint.latitude, currentPoint.longitude,
                    waypoint.geoPoint.latitude, waypoint.geoPoint.longitude
                )
                val distanceScore = 1.0 / (distance / 1000.0 + 1.0)
                val priorityScore = waypoint.priority.toDouble()
                (priorityScore * 0.7) + (distanceScore * 0.3)
            }

            if (nextWaypoint != null) {
                selected.add(nextWaypoint)
                remaining.remove(nextWaypoint)
                currentPoint = nextWaypoint.geoPoint
            }
        }

        return optimizeWaypointOrder(start, end, selected)
    }

    private fun optimizeWaypointOrder(start: GeoPoint, end: GeoPoint, waypoints: List<Waypoint>): List<Waypoint> {
        if (waypoints.size <= 2) return waypoints

        var bestOrder = waypoints.toList()
        var bestDistance = calculateTotalDistance(start, end, bestOrder)

        if (waypoints.size <= 6) {
            val orders = listOf(
                waypoints.sortedBy { GeoUtils.haversine(start.latitude, start.longitude, it.geoPoint.latitude, it.geoPoint.longitude) },
                waypoints.sortedByDescending { it.priority },
                waypoints.reversed()
            )

            for (order in orders) {
                val distance = calculateTotalDistance(start, end, order)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestOrder = order
                }
            }
        }

        return bestOrder
    }

    private fun calculateTotalDistance(start: GeoPoint, end: GeoPoint, waypoints: List<Waypoint>): Double {
        if (waypoints.isEmpty()) {
            return GeoUtils.haversine(start.latitude, start.longitude, end.latitude, end.longitude)
        }

        var total = GeoUtils.haversine(
            start.latitude, start.longitude,
            waypoints.first().geoPoint.latitude, waypoints.first().geoPoint.longitude
        )

        for (i in 0 until waypoints.size - 1) {
            val current = waypoints[i].geoPoint
            val next = waypoints[i + 1].geoPoint
            total += GeoUtils.haversine(current.latitude, current.longitude, next.latitude, next.longitude)
        }

        total += GeoUtils.haversine(
            waypoints.last().geoPoint.latitude, waypoints.last().geoPoint.longitude,
            end.latitude, end.longitude
        )

        return total
    }

    private fun estimateDrivingTime(distanceMeters: Double): Long {
        val speedKmh = 60.0
        val timeHours = (distanceMeters / 1000.0) / speedKmh
        return (timeHours * 60 * 60 * 1000).toLong()
    }

    private fun findNearbyPois(route: List<GeoPoint>, allPois: List<ScenicPoi>): List<ScenicPoi> {
        return allPois.filter { poi ->
            route.any { routePoint ->
                val distance = GeoUtils.haversine(
                    routePoint.latitude, routePoint.longitude,
                    poi.lat, poi.lon
                )
                distance <= 2000.0
            }
        }
    }

    private suspend fun fetchRouteAlternativesLocal(start: GeoPoint, destination: GeoPoint, packageName: String): List<List<GeoPoint>> = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&alternatives=true"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", packageName)
            .header("Accept", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val routes = json.optJSONArray("routes") ?: return@withContext emptyList()
            val alternatives = mutableListOf<List<GeoPoint>>()
            for (i in 0 until routes.length()) {
                val routeObj = routes.getJSONObject(i)
                val geometry = routeObj.optJSONObject("geometry") ?: continue
                val coords = geometry.optJSONArray("coordinates") ?: continue
                val routePoints = mutableListOf<GeoPoint>()
                for (j in 0 until coords.length()) {
                    val pt = coords.getJSONArray(j)
                    routePoints.add(GeoPoint(pt.getDouble(1), pt.getDouble(0)))
                }
                alternatives.add(routePoints)
            }
            return@withContext alternatives
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }
}
