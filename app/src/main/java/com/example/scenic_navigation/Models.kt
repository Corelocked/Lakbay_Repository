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

// NOTE: The sealed class `RecommendationItem` was moved to models/DataModels.kt to avoid duplicate class definitions.
// The original definition is commented out below to prevent duplicate symbol issues during build.

/*
sealed class RecommendationItem {
    data class PoiItem(val poi: Poi) : RecommendationItem()
    data class MunicipalityItem(val municipality: ScenicMunicipality) : RecommendationItem()
    data class TownItem(val town: Town) : RecommendationItem()
    data class ScenicItem(val scenicPoi: ScenicPoi) : RecommendationItem()
}
*/
