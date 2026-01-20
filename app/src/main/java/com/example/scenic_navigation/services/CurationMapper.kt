package com.example.scenic_navigation.services

import com.example.scenic_navigation.models.CurationIntent

data class OsmPredicate(val key: String, val value: String)

data class PlannerCurationConfig(
    val routeType: String,
    val poiBoosts: Map<String, Double>,
    val tagFilters: Set<OsmPredicate>
)

/**
 * Map a user `CurationIntent` to planner inputs: routeType, per-category boosts and tag filters.
 */
object CurationMapper {
    fun map(intent: CurationIntent?, locale: String? = null): PlannerCurationConfig {
        if (intent == null) return PlannerCurationConfig("generic", emptyMap(), emptySet())

        val boosts = mutableMapOf<String, Double>()
        val filters = mutableSetOf<OsmPredicate>()

        when (intent.seeing) {
            com.example.scenic_navigation.models.SeeingType.OCEANIC -> {
                filters.addAll(listOf(
                    OsmPredicate("natural", "beach"),
                    OsmPredicate("natural", "coastline"),
                    OsmPredicate("waterway", "bay"),
                    OsmPredicate("natural", "cape"),
                    OsmPredicate("tourism", "viewpoint"),
                    OsmPredicate("leisure", "beach_resort"),
                    OsmPredicate("leisure", "marina"),
                    OsmPredicate("natural", "sand"),
                    OsmPredicate("waterway", "river"),
                    // Lakeside support: include general water/lake predicates
                    OsmPredicate("natural", "water"),
                    OsmPredicate("water", "lake"),
                    OsmPredicate("leisure", "lakeside"),
                    // Include historical sites with high scenic scores
                    OsmPredicate("tourism", "museum"),
                    OsmPredicate("historic", "monument"),
                    OsmPredicate("historic", "castle"),
                    OsmPredicate("historic", "archaeological_site"),
                    OsmPredicate("historic", "ruins"),
                    OsmPredicate("tourism", "gallery"),
                    OsmPredicate("historic", "memorial"),
                    OsmPredicate("historic", "city_gate"),
                    OsmPredicate("historic", "shrine"),
                    OsmPredicate("amenity", "place_of_worship"),
                    OsmPredicate("tourism", "heritage_site")
                ))
                boosts["beach"] = 1.0
                boosts["coast"] = 0.6
                boosts["bay"] = 0.5
                boosts["cape"] = 0.5
                boosts["view"] = 0.5
                // Boost lakes/lakeside to prefer lakefront stops when oceanic/lakeside intent
                boosts["lake"] = 0.8
                boosts["lakeside"] = 0.6
                // Lower boosts for historical sites so they appear if scenic score is high
                boosts["historic"] = 0.5
                boosts["museum"] = 0.2
                boosts["shrine"] = 0.2
                boosts["heritage"] = 0.4
                boosts["monument"] = 0.3
                boosts["church"] = 0.2
            }
            com.example.scenic_navigation.models.SeeingType.MOUNTAIN -> {
                filters.addAll(listOf(
                    OsmPredicate("natural", "peak"),
                    OsmPredicate("natural", "ridge"),
                    OsmPredicate("natural", "waterfall"),
                    OsmPredicate("tourism", "viewpoint"),
                    OsmPredicate("natural", "saddle"),
                    OsmPredicate("natural", "glacier"),
                    OsmPredicate("natural", "scrub"),
                    // Forest and wildlife support
                    OsmPredicate("natural", "wood"),
                    OsmPredicate("boundary", "protected_area"),
                    OsmPredicate("leisure", "nature_reserve"),
                    // Prefer provincial/secondary roads and agricultural/farmland corridors
                    OsmPredicate("highway", "secondary"),
                    OsmPredicate("highway", "tertiary"),
                    OsmPredicate("highway", "unclassified"),
                    OsmPredicate("highway", "track"),
                    OsmPredicate("landuse", "farmland"),
                    OsmPredicate("landuse", "meadow"),
                    OsmPredicate("landuse", "orchard"),
                    OsmPredicate("landuse", "agriculture"),
                    // Include historical sites with high scenic scores
                    OsmPredicate("tourism", "museum"),
                    OsmPredicate("historic", "monument"),
                    OsmPredicate("historic", "castle"),
                    OsmPredicate("historic", "archaeological_site"),
                    OsmPredicate("historic", "ruins"),
                    OsmPredicate("tourism", "gallery"),
                    OsmPredicate("historic", "memorial"),
                    OsmPredicate("historic", "city_gate"),
                    OsmPredicate("historic", "shrine"),
                    OsmPredicate("amenity", "place_of_worship"),
                    OsmPredicate("tourism", "heritage_site")
                ))
                boosts["peak"] = 1.0
                boosts["ridge"] = 0.6
                boosts["view"] = 0.5
                boosts["waterfall"] = 0.4
                // Boost forest and wildlife related categories
                boosts["forest"] = 0.8
                boosts["wildlife"] = 0.9
                // Boost for scenic provincial roads and rural farmland experience
                boosts["provincial_road"] = boosts.getOrDefault("provincial_road", 0.0) + 0.8
                boosts["farmland"] = boosts.getOrDefault("farmland", 0.0) + 0.7
                // Lower boosts for historical sites so they appear if scenic score is high
                boosts["historic"] = 0.5
                boosts["museum"] = 0.2
                boosts["shrine"] = 0.2
                boosts["heritage"] = 0.4
                boosts["monument"] = 0.3
                boosts["church"] = 0.2
            }
        }

        when (intent.activity) {
            com.example.scenic_navigation.models.ActivityType.SIGHTSEEING -> {
                boosts["view"] = boosts.getOrDefault("view", 0.0) + 0.6
                boosts["attraction"] = boosts.getOrDefault("attraction", 0.0) + 0.4
                // also favor explicit viewpoint tag
                filters.add(OsmPredicate("tourism", "viewpoint"))
                // Relaxation focus: prefer restful spots (parks, benches, calm beaches)
                boosts["relax"] = boosts.getOrDefault("relax", 0.0) + 0.5
                filters.addAll(listOf(
                    OsmPredicate("leisure", "park"),
                    OsmPredicate("amenity", "bench"),
                    OsmPredicate("natural", "beach")
                ))
                // Include historic sites prominently in sightseeing
                boosts["historic"] = boosts.getOrDefault("historic", 0.0) + 0.6
            }
            com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE -> {
                boosts["restaurant"] = boosts.getOrDefault("restaurant", 0.0) + 0.9
                boosts["shop"] = boosts.getOrDefault("shop", 0.0) + 0.7
                boosts["mall"] = boosts.getOrDefault("mall", 0.0) + 0.6
                // Use a regex predicate for common shop categories to narrow Overpass results
                filters.addAll(listOf(
                    OsmPredicate("amenity", "restaurant"),
                    OsmPredicate("shop", "~gift|souvenir|art|craft|bakery|deli|cheese|wine|farm|seafood|books|clothes")
                ))
            }
            com.example.scenic_navigation.models.ActivityType.CULTURAL -> {
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + 0.8
                boosts["theatre"] = boosts.getOrDefault("theatre", 0.0) + 0.6
                boosts["historic"] = boosts.getOrDefault("historic", 0.0) + 0.6
                filters.addAll(listOf(
                    OsmPredicate("tourism", "museum"),
                    OsmPredicate("tourism", "gallery"),
                    OsmPredicate("historic", "church")
                ))
                // Cultural sub-activities: hiking/adventure-oriented cultural experiences
                boosts["hiking"] = boosts.getOrDefault("hiking", 0.0) + 0.9
                boosts["adventure"] = boosts.getOrDefault("adventure", 0.0) + 0.8
                filters.addAll(listOf(
                    OsmPredicate("route", "hiking"),
                    OsmPredicate("highway", "path"),
                    OsmPredicate("sac_scale", "hiking"),
                    OsmPredicate("tourism", "alpine_hut"),
                    OsmPredicate("sport", "climbing")
                ))
            }
        }

        // Add Philippines-specific extras only (keep predicates focused on PH)
        val lang = locale?.split('-', limit = 2)?.getOrNull(0)?.lowercase()
        if (lang == "ph") {
            filters.addAll(listOf(
                OsmPredicate("natural", "island"),
                OsmPredicate("historic", "shrine")
            ))
        }

        val routeType = when (intent.seeing) {
            com.example.scenic_navigation.models.SeeingType.OCEANIC -> "oceanic"
            com.example.scenic_navigation.models.SeeingType.MOUNTAIN -> "mountain"
        }

        return PlannerCurationConfig(routeType, boosts.toMap(), filters.toSet())
    }
}
