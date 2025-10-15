package com.example.scenic_navigation

import com.example.scenic_navigation.models.Poi

// Data class for scenic municipalities
data class ScenicMunicipality(
    val name: String,
    val type: String, // "coastal", "mountain", etc.
    val lat: Double,
    val lon: Double,
    val population: Int?,
    val elevation: Double?,
    val score: Int
)

// Sealed class for recommendation items (POI or Municipality)
sealed class RecommendationItem {
    data class PoiItem(val poi: Poi) : RecommendationItem()
    data class MunicipalityItem(val municipality: ScenicMunicipality) : RecommendationItem()
}
