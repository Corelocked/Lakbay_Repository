package com.example.scenic_navigation.models

/**
 * Point of Interest with optional coordinates
 */
data class Poi(
    val name: String,
    val category: String,
    val description: String,
    val lat: Double? = null,
    val lon: Double? = null
)

/**
 * Scenic Point of Interest with scoring for route planning
 */
data class ScenicPoi(
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val score: Int
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
 * Sealed class for recommendation items (POI or Municipality)
 */
sealed class RecommendationItem {
    data class PoiItem(val poi: Poi) : RecommendationItem()
    data class MunicipalityItem(val municipality: ScenicMunicipality) : RecommendationItem()
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

