package com.example.scenic_navigation.utils

import org.osmdroid.util.GeoPoint

/**
 * Utility class for geographic calculations
 */
object GeoUtils {

    /**
     * Calculate distance between two geographic points using Haversine formula
     * @return distance in meters
     */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    /**
     * Calculate total length of a route
     * @return route length in meters
     */
    fun computeRouteLength(route: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            total += haversine(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return total
    }

    /**
     * Sample points along a route at regular intervals
     */
    fun sampleRoutePoints(
        route: List<GeoPoint>,
        spacingMeters: Double,
        maxSamples: Int
    ): List<GeoPoint> {
        if (route.size <= 2) return route
        val samples = mutableListOf<GeoPoint>()
        samples.add(route.first())
        var acc = 0.0

        for (i in 1 until route.size) {
            val prev = route[i - 1]
            val cur = route[i]
            val d = haversine(prev.latitude, prev.longitude, cur.latitude, cur.longitude)
            acc += d
            if (acc >= spacingMeters) {
                samples.add(cur)
                acc = 0.0
                if (samples.size >= maxSamples - 1) break
            }
        }

        if (samples.last() != route.last()) samples.add(route.last())
        return samples
    }

    /**
     * Parse latitude/longitude from string input (e.g., "40.7128,-74.0060")
     */
    fun parseLatLon(input: String): GeoPoint? {
        val parts = input.split(",")
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon)
    }

    /**
     * Calculate perpendicular distance from a point to a line defined by two points
     * @return distance in meters
     */
    fun distanceToLine(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val A = px - ax
        val B = py - ay
        val C = bx - ax
        val D = by - ay

        val dot = A * C + B * D
        val lenSq = C * C + D * D
        val param = if (lenSq != 0.0) dot / lenSq else -1.0

        val xx: Double
        val yy: Double
        if (param < 0) {
            xx = ax
            yy = ay
        } else if (param > 1) {
            xx = bx
            yy = by
        } else {
            xx = ax + param * C
            yy = ay + param * D
        }

        return haversine(px, py, xx, yy)
    }
}
