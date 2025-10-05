package com.example.scenic_navigation.models

import org.osmdroid.util.GeoPoint

/**
 * Waypoint in a road trip
 */
data class Waypoint(
    val geoPoint: GeoPoint,
    val name: String,
    val estimatedStayDuration: Long = 30 * 60 * 1000L, // 30 minutes in milliseconds
    val priority: Int = 1, // 1-5, with 5 being highest priority
    val category: String = "poi",
    val openingHours: String? = null,
    val isOptional: Boolean = false
)

/**
 * Road trip segment between two waypoints
 */
data class RoadTripSegment(
    val from: Waypoint,
    val to: Waypoint,
    val route: List<GeoPoint>,
    val distanceMeters: Double,
    val estimatedDurationMs: Long,
    val scenicScore: Double = 0.0
)

/**
 * Complete road trip plan with all waypoints and segments
 */
data class RoadTripPlan(
    val waypoints: List<Waypoint>,
    val segments: List<RoadTripSegment>,
    val totalDistanceMeters: Double,
    val totalDurationMs: Long,
    val totalScenicScore: Double,
    val startTime: Long? = null,
    val endTime: Long? = null
)

