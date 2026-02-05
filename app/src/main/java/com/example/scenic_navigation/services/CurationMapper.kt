package com.example.scenic_navigation.services

import com.example.scenic_navigation.models.CurationIntent

data class PlannerCurationConfig(
    val routeType: String,
    val poiBoosts: Map<String, Double>,
    val tagFilters: List<String>
)

/**
 * Map a user `CurationIntent` to planner inputs: routeType, per-category boosts and tag filters for local dataset categories.
 */
object CurationMapper {
    fun map(intent: CurationIntent?, locale: String? = null): PlannerCurationConfig {
        if (intent == null) return PlannerCurationConfig("generic", emptyMap(), emptyList())

        val boosts = mutableMapOf<String, Double>()
        val filters = mutableListOf<String>()

        when (intent.seeing) {
            com.example.scenic_navigation.models.SeeingType.OCEANIC -> {
                filters.addAll(listOf(
                    "nature park",
                    "historical site",
                    "viewpoint",
                    "beach",
                    "coast",
                    "bay",
                    "cape"
                ))
                boosts["beach"] = 1.0
                boosts["coast"] = 0.6
                boosts["bay"] = 0.5
                boosts["cape"] = 0.5
                boosts["view"] = 0.5
                boosts["nature park"] = 0.8
                boosts["park"] = 0.7
                boosts["historical site"] = 0.5
            }
            com.example.scenic_navigation.models.SeeingType.MOUNTAIN -> {
                filters.addAll(listOf(
                    "nature park",
                    "historical site",
                    "peak",
                    "volcano",
                    "viewpoint",
                    "waterfall",
                    "ridge"
                ))
                boosts["peak"] = 1.0
                boosts["ridge"] = 0.6
                boosts["view"] = 0.5
                boosts["waterfall"] = 0.4
                boosts["nature park"] = 0.8
                boosts["historical site"] = 0.5
            }
        }

        when (intent.activity) {
            com.example.scenic_navigation.models.ActivityType.SIGHTSEEING -> {
                boosts["viewpoint"] = boosts.getOrDefault("viewpoint", 0.0) + 0.6
                boosts["attraction"] = boosts.getOrDefault("attraction", 0.0) + 0.4
                boosts["historical site"] = boosts.getOrDefault("historical site", 0.0) + 0.5
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + 0.3
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.3
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + 0.4
                filters.addAll(listOf(
                    "viewpoint",
                    "attraction",
                    "park",
                    "nature park",
                    "historical site",
                    "museum"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE -> {
                boosts["restaurant"] = boosts.getOrDefault("restaurant", 0.0) + 1.0
                boosts["cafe"] = boosts.getOrDefault("cafe", 0.0) + 0.9
                boosts["pasalubong store"] = boosts.getOrDefault("pasalubong store", 0.0) + 0.8
                filters.addAll(listOf(
                    "restaurant",
                    "cafe",
                    "pasalubong store"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.CULTURAL -> {
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + 0.8
                boosts["historical site"] = boosts.getOrDefault("historical site", 0.0) + 0.7
                boosts["cultural spot"] = boosts.getOrDefault("cultural spot", 0.0) + 0.6
                filters.addAll(listOf(
                    "museum",
                    "historical site",
                    "cultural spot"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.ADVENTURE -> {
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + 0.8
                boosts["historical site"] = boosts.getOrDefault("historical site", 0.0) + 0.7
                boosts["peak"] = boosts.getOrDefault("peak", 0.0) + 0.6
                boosts["waterfall"] = boosts.getOrDefault("waterfall", 0.0) + 0.5
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.4
                boosts["viewpoint"] = boosts.getOrDefault("viewpoint", 0.0) + 0.3
                filters.addAll(listOf(
                    "nature park",
                    "historical site",
                    "peak",
                    "waterfall",
                    "park",
                    "viewpoint"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.RELAXATION -> {
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + 0.8
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.7
                boosts["beach"] = boosts.getOrDefault("beach", 0.0) + 0.6
                filters.addAll(listOf(
                    "nature park",
                    "park",
                    "beach"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY -> {
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.7
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + 0.6
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + 0.5
                boosts["historical site"] = boosts.getOrDefault("historical site", 0.0) + 0.4
                filters.addAll(listOf(
                    "park",
                    "nature park",
                    "museum",
                    "historical site"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.ROMANTIC -> {
                boosts["viewpoint"] = boosts.getOrDefault("viewpoint", 0.0) + 0.8
                boosts["restaurant"] = boosts.getOrDefault("restaurant", 0.0) + 0.7
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.6
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + 0.5
                filters.addAll(listOf(
                    "viewpoint",
                    "restaurant",
                    "park",
                    "nature park"
                ))
            }
        }

        // Add Philippines-specific extras only (keep predicates focused on PH)
        val lang = locale?.split('-', limit = 2)?.getOrNull(0)?.lowercase()
        if (lang == "ph") {
            filters.addAll(listOf(
                "natural=island",
                "historic=shrine"
            ))
        }

        val routeType = when (intent.seeing) {
            com.example.scenic_navigation.models.SeeingType.OCEANIC -> "oceanic"
            com.example.scenic_navigation.models.SeeingType.MOUNTAIN -> "mountain"
        }

        return PlannerCurationConfig(routeType, boosts.toMap(), filters.toList())
    }
}
