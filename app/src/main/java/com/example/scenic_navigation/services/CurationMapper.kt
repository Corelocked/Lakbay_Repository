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
                    "tourist attraction",
                    "beach",
                    "coast",
                    "bay",
                    "cape",
                    "museum",
                    "restaurant",
                    "cafe",
                    "pasalubong store"
                ))
                boosts["beach"] = 1.0
                boosts["coast"] = 0.6
                boosts["bay"] = 0.5
                boosts["cape"] = 0.5
                boosts["view"] = 0.5
                boosts["nature park"] = 0.8
                boosts["park"] = 0.7
                boosts["historical site"] = 0.5
                boosts["tourist attraction"] = 0.6
                boosts["museum"] = 0.5
                boosts["restaurant"] = 0.4
            }
            com.example.scenic_navigation.models.SeeingType.MOUNTAIN -> {
                filters.addAll(listOf(
                    "nature park",
                    "historical site",
                    "peak",
                    "volcano",
                    "viewpoint",
                    "waterfall",
                    "ridge",
                    "tourist attraction",
                    "museum"
                ))
                boosts["peak"] = 1.0
                boosts["ridge"] = 0.6
                boosts["view"] = 0.5
                boosts["waterfall"] = 0.4
                boosts["nature park"] = 0.8
                boosts["historical site"] = 0.5
                boosts["museum"] = 0.4
            }
        }

        // Category baselines: one canonical boost per activity/category. Tags under each activity
        // inherit the baseline multiplied by a small tag-specific factor so we can tune relative weight.
        val categoryBaseline = mapOf(
            com.example.scenic_navigation.models.ActivityType.SIGHTSEEING to 0.6,
            com.example.scenic_navigation.models.ActivityType.CULTURAL to 0.7,
            // Make SHOP_AND_DINE use the same baseline as CULTURAL (historical landmarks)
            com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE to 0.7,
            com.example.scenic_navigation.models.ActivityType.ADVENTURE to 0.6,
            com.example.scenic_navigation.models.ActivityType.RELAXATION to 0.6,
            com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY to 0.6,
            com.example.scenic_navigation.models.ActivityType.ROMANTIC to 0.7
        )

        // Activity -> tag list map used to detect tag overlap between categories. Keep in sync with
        // the tags used in the when block below; this map drives uniqueness boosting.
        val activityTagMap: Map<com.example.scenic_navigation.models.ActivityType, List<String>> = mapOf(
            com.example.scenic_navigation.models.ActivityType.SIGHTSEEING to listOf("viewpoint", "attraction", "historical site", "museum", "park", "nature park"),
            com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE to listOf("restaurant", "cafe", "pasalubong store", "food", "drink", "bar", "pub"),
            com.example.scenic_navigation.models.ActivityType.CULTURAL to listOf("museum", "historical site", "cultural spot", "culture"),
            com.example.scenic_navigation.models.ActivityType.ADVENTURE to listOf("nature park", "historical site", "peak", "waterfall", "park", "viewpoint", "adventure", "hiking", "camping"),
            com.example.scenic_navigation.models.ActivityType.RELAXATION to listOf("nature park", "park", "beach", "relaxation", "swimming", "water park"),
            com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY to listOf("park", "nature park", "museum", "historical site", "family"),
            com.example.scenic_navigation.models.ActivityType.ROMANTIC to listOf("viewpoint", "restaurant", "park", "nature park", "sunset", "view", "beach", "attraction", "resort")
        )

        when (intent.activity) {
            com.example.scenic_navigation.models.ActivityType.SIGHTSEEING -> {
                val base = categoryBaseline[com.example.scenic_navigation.models.ActivityType.SIGHTSEEING] ?: 0.6
                boosts["viewpoint"] = boosts.getOrDefault("viewpoint", 0.0) + base * 1.0
                boosts["attraction"] = boosts.getOrDefault("attraction", 0.0) + base * 0.6667
                boosts["historical site"] = boosts.getOrDefault("historical site", 0.0) + base * 0.8333
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + base * 0.5
                boosts["park"] = boosts.getOrDefault("park", 0.0) + base * 0.5
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + base * 0.6667
                filters.addAll(listOf(
                    "viewpoint",
                    "attraction",
                    "park",
                    "nature park",
                    "historical site",
                    "museum",
                ))
            }
            com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE -> {
                val base = categoryBaseline[com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE] ?: 0.7
                // Per your request, treat shop-and-dine tags with the same baseline as historical landmarks
                boosts["restaurant"] = boosts.getOrDefault("restaurant", 0.0) + base * 1.0
                boosts["cafe"] = boosts.getOrDefault("cafe", 0.0) + base * 1.0
                boosts["pasalubong store"] = boosts.getOrDefault("pasalubong store", 0.0) + base * 1.0
                filters.addAll(listOf(
                    "restaurant",
                    "cafe",
                    "pasalubong store",
                    "food",
                    "drink",
                    "bar",
                    "pub"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.CULTURAL -> {
                val base = categoryBaseline[com.example.scenic_navigation.models.ActivityType.CULTURAL] ?: 0.7
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + base * (0.8 / 0.7)
                boosts["historical site"] = boosts.getOrDefault("historical site", 0.0) + base * 1.0
                boosts["cultural spot"] = boosts.getOrDefault("cultural spot", 0.0) + base * (0.6 / 0.7)
                filters.addAll(listOf(
                    "museum",
                    "historical site",
                    "cultural spot",
                    "culture"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.ADVENTURE -> {
                val base = categoryBaseline[com.example.scenic_navigation.models.ActivityType.ADVENTURE] ?: 0.6
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + base * (0.8 / 0.6)
                boosts["historical site"] = boosts.getOrDefault("historical site", 0.0) + base * (0.7 / 0.6)
                boosts["peak"] = boosts.getOrDefault("peak", 0.0) + base * 1.0
                boosts["waterfall"] = boosts.getOrDefault("waterfall", 0.0) + base * (0.5 / 0.6)
                boosts["park"] = boosts.getOrDefault("park", 0.0) + base * (0.4 / 0.6)
                boosts["viewpoint"] = boosts.getOrDefault("viewpoint", 0.0) + base * (0.3 / 0.6)
                filters.addAll(listOf(
                    "nature park",
                    "historical site",
                    "peak",
                    "waterfall",
                    "park",
                    "viewpoint",
                    "adventure",
                    "hiking",
                    "camping",
                ))
            }
            com.example.scenic_navigation.models.ActivityType.RELAXATION -> {
                val base = categoryBaseline[com.example.scenic_navigation.models.ActivityType.RELAXATION] ?: 0.6
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + base * (0.8 / 0.6)
                boosts["park"] = boosts.getOrDefault("park", 0.0) + base * (0.7 / 0.6)
                boosts["beach"] = boosts.getOrDefault("beach", 0.0) + base * 1.0
                filters.addAll(listOf(
                    "nature park",
                    "park",
                    "beach",
                    "relaxation",
                    "swimming",
                    "water park"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY -> {
                val base = categoryBaseline[com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY] ?: 0.6
                boosts["park"] = boosts.getOrDefault("park", 0.0) + base * (0.7 / 0.6)
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + base * 1.0
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + base * (0.5 / 0.6)
                boosts["historical site"] = boosts.getOrDefault("historical site", 0.0) + base * (0.4 / 0.6)
                filters.addAll(listOf(
                    "park",
                    "nature park",
                    "museum",
                    "historical site",
                    "family"
                ))
            }
            com.example.scenic_navigation.models.ActivityType.ROMANTIC -> {
                val base = categoryBaseline[com.example.scenic_navigation.models.ActivityType.ROMANTIC] ?: 0.7
                boosts["viewpoint"] = boosts.getOrDefault("viewpoint", 0.0) + base * (0.8 / 0.7)
                boosts["restaurant"] = boosts.getOrDefault("restaurant", 0.0) + base * 1.0
                boosts["park"] = boosts.getOrDefault("park", 0.0) + base * (0.6 / 0.7)
                boosts["nature park"] = boosts.getOrDefault("nature park", 0.0) + base * (0.5 / 0.7)
                filters.addAll(listOf(
                    "viewpoint",
                    "restaurant",
                    "park",
                    "nature park",
                    "sunset",
                    "view",
                    "beach",
                    "attraction",
                    "resort"
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

        // Post-process: boost tags that are unique to the selected activity so switching activity
        // produces noticeably different POI lists. Tags that are shared across categories are
        // not boosted (or could be slightly penalized) so unique tags stand out.
        try {
            val selectedActivity = intent.activity
            val allTags = activityTagMap.values.flatten()
            val tagFrequency = allTags.groupingBy { it }.eachCount()

            // Uniqueness boost factor — fraction of the selected category baseline to add for unique tags
            val uniquenessBonusFactor = 0.25
            val baseForSelected = categoryBaseline[selectedActivity] ?: 0.6

            val selectedTags = activityTagMap[selectedActivity] ?: emptyList()
            for (tag in selectedTags) {
                val freq = tagFrequency.getOrDefault(tag, 0)
                if (freq == 1) {
                    // Tag is unique to this category: add bonus equal to a fraction of the base
                    boosts[tag] = boosts.getOrDefault(tag, 0.0) + (baseForSelected * uniquenessBonusFactor)
                } else {
                    // Optional: small de-emphasis for shared tags so unique tags have more relative weight
                    // Commented out by default; uncomment to slightly penalize shared tags.
                    // boosts[tag] = boosts.getOrDefault(tag, 0.0) - (baseForSelected * 0.03)
                }
            }
        } catch (_: Exception) {
            // non-fatal — accept existing boosts if uniqueness processing fails
        }

        return PlannerCurationConfig(routeType, boosts.toMap(), filters.toList())
    }
}
