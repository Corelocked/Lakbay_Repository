package com.example.scenic_navigation.utils

import android.location.Location
import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Off-route detector: monitors distance from the driver's current location to the active route polyline.
 * Triggers a callback when the distance exceeds a threshold for a few consecutive updates, with a cooldown.
 */
class OffRouteDetector(
    routePoints: List<GeoPoint>,
    private var thresholdMeters: Float = 35f,
    private var returnThresholdMeters: Float = 20f,
    private var requiredConsecutive: Int = 3,
    private var cooldownMs: Long = 15000L,
    private val onOffRoute: (Location) -> Unit
) {
    private var route: List<GeoPoint> = routePoints
    private var offCount: Int = 0
    private var lastTriggerAt: Long = 0L

    fun updateRoute(newRoute: List<GeoPoint>) {
        route = newRoute
        offCount = 0
    }

    fun updateThreshold(threshold: Float, returnThreshold: Float = threshold * 0.6f) {
        thresholdMeters = threshold
        returnThresholdMeters = returnThreshold
    }

    fun updateLocation(loc: Location) {
        if (route.size < 2) return
        val d = minDistanceToRouteMeters(loc.latitude, loc.longitude, route)
        if (d > thresholdMeters) {
            offCount++
            val now = System.currentTimeMillis()
            if (offCount >= requiredConsecutive && (now - lastTriggerAt) > cooldownMs) {
                lastTriggerAt = now
                onOffRoute(loc)
            }
        } else if (d < returnThresholdMeters) {
            // back on route, reset counter
            offCount = 0
        }
    }

    /**
     * Compute minimum distance from a point to a polyline in meters, using a local equirectangular projection.
     */
    private fun minDistanceToRouteMeters(lat: Double, lon: Double, poly: List<GeoPoint>): Float {
        var minD = Float.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            val a = poly[i]
            val b = poly[i + 1]
            val d = pointToSegmentDistanceMeters(lat, lon, a.latitude, a.longitude, b.latitude, b.longitude)
            if (d < minD) minD = d
        }
        return minD
    }

    /**
     * Distance from point P to segment AB in meters.
     * Uses equirectangular approximation to project lat/lon to a local 2D plane near segment midpoint.
     */
    private fun pointToSegmentDistanceMeters(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Float {
        if (aLat == bLat && aLon == bLon) {
            return haversineMeters(pLat, pLon, aLat, aLon).toFloat()
        }
        val lat0 = ((aLat + bLat) / 2.0).toRadians()
        val metersPerDegLat = 111132.0
        val metersPerDegLon = 111320.0 * cos(lat0)

        fun toXY(lat: Double, lon: Double): Pair<Double, Double> {
            val x = (lon - aLon) * metersPerDegLon
            val y = (lat - aLat) * metersPerDegLat
            return Pair(x, y)
        }

        val (ax, ay) = 0.0 to 0.0
        val (bx, by) = toXY(bLat, bLon)
        val (px, py) = toXY(pLat, pLon)

        val vx = bx - ax
        val vy = by - ay
        val wx = px - ax
        val wy = py - ay
        val c2 = vx * vx + vy * vy
        val t = if (c2 <= 0.0) 0.0 else ((vx * wx + vy * wy) / c2).coerceIn(0.0, 1.0)
        val projX = ax + t * vx
        val projY = ay + t * vy
        val dx = px - projX
        val dy = py - projY
        return hypot(dx, dy).toFloat()
    }

    private fun Double.toRadians() = Math.toRadians(this)

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}

