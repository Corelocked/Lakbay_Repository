package com.example.scenic_navigation.models

/**
 * Point of Interest with optional coordinates
 */
data class Poi(
    val name: String,
    val category: String,
    val description: String,
    val municipality: String, // Added for grouping by town/municipality
    val lat: Double? = null,
    val lon: Double? = null
)

/**
 * Town along the route
 */
data class Town(
    val name: String,
    val type: String, // "city", "town", "village"
    val lat: Double,
    val lon: Double,
    val population: Int? = null,
    val elevation: Double? = null, // Elevation in meters (for mountain towns)
    val distanceFromStart: Double = 0.0, // Distance in meters from route start
    val routeIndex: Int = 0 // Index in the route where this town is encountered
)

/**
 * Scenic Point of Interest with scoring for route planning
 */
data class ScenicPoi(
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val score: Int,
    val municipality: String? = null // Added for compatibility with RouteViewModel
)

/**
 * Scenic municipality (coastal or mountain town)
 */
data class ScenicMunicipality(
    val name: String,
    val type: String, // "coastal", "mountain", etc.
    val lat: Double,
    val lon: Double,
    val population: Int?,
    val elevation: Double?,
    val score: Int
)

/**
 * Sealed class for recommendation items (POI, Municipality, or Town)
 */
sealed class RecommendationItem {
    data class PoiItem(val poi: Poi) : RecommendationItem()
    data class MunicipalityItem(val municipality: ScenicMunicipality) : RecommendationItem()
    data class TownItem(val town: Town) : RecommendationItem()
    data class ScenicItem(val scenicPoi: ScenicPoi) : RecommendationItem()
}

/**
 * Result from geocoding service
 */
data class GeocodeResult(
    val displayName: String,
    val lat: Double,
    val lon: Double
)

/**
 * Coastal proximity analysis data
 */
data class CoastalSegment(
    val startIdx: Int,
    val endIdx: Int,
    val avgDistanceToCoast: Double,
    val coastalLength: Double
)
