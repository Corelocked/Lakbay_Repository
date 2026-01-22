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
                boosts["historic"] = boosts.getOrDefault("historic", 0.0) + 0.5
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + 0.3
                boosts["monument"] = boosts.getOrDefault("monument", 0.0) + 0.4
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.3
                boosts["nature"] = boosts.getOrDefault("nature", 0.0) + 0.4
                filters.addAll(listOf(
                    OsmPredicate("tourism", "viewpoint"),
                    OsmPredicate("tourism", "attraction"),
                    OsmPredicate("leisure", "park"),
                    OsmPredicate("historic", ""),
                    OsmPredicate("tourism", "museum")
                ))
            }
            com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE -> {
                boosts["restaurant"] = boosts.getOrDefault("restaurant", 0.0) + 1.0
                boosts["cafe"] = boosts.getOrDefault("cafe", 0.0) + 0.9
                boosts["food"] = boosts.getOrDefault("food", 0.0) + 0.8
                boosts["shop"] = boosts.getOrDefault("shop", 0.0) + 0.7
                boosts["mall"] = boosts.getOrDefault("mall", 0.0) + 0.6
                boosts["market"] = boosts.getOrDefault("market", 0.0) + 0.5
                filters.addAll(listOf(
                    OsmPredicate("amenity", "restaurant"),
                    OsmPredicate("amenity", "cafe"),
                    OsmPredicate("amenity", "fast_food"),
                    OsmPredicate("shop", "~gift|souvenir|art|craft|bakery|deli|cheese|wine|farm|seafood|books|clothes|supermarket")
                ))
            }
            com.example.scenic_navigation.models.ActivityType.CULTURAL -> {
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + 0.8
                boosts["historic"] = boosts.getOrDefault("historic", 0.0) + 0.7
                boosts["theatre"] = boosts.getOrDefault("theatre", 0.0) + 0.6
                boosts["gallery"] = boosts.getOrDefault("gallery", 0.0) + 0.5
                boosts["church"] = boosts.getOrDefault("church", 0.0) + 0.4
                boosts["heritage"] = boosts.getOrDefault("heritage", 0.0) + 0.6
                filters.addAll(listOf(
                    OsmPredicate("tourism", "museum"),
                    OsmPredicate("tourism", "gallery"),
                    OsmPredicate("historic", ""),
                    OsmPredicate("amenity", "theatre"),
                    OsmPredicate("amenity", "place_of_worship")
                ))
            }
            com.example.scenic_navigation.models.ActivityType.ADVENTURE -> {
                boosts["peak"] = boosts.getOrDefault("peak", 0.0) + 0.8
                boosts["waterfall"] = boosts.getOrDefault("waterfall", 0.0) + 0.7
                boosts["hiking"] = boosts.getOrDefault("hiking", 0.0) + 0.9
                boosts["climbing"] = boosts.getOrDefault("climbing", 0.0) + 0.8
                boosts["adventure"] = boosts.getOrDefault("adventure", 0.0) + 0.6
                boosts["sport"] = boosts.getOrDefault("sport", 0.0) + 0.5
                filters.addAll(listOf(
                    OsmPredicate("natural", "peak"),
                    OsmPredicate("waterway", "waterfall"),
                    OsmPredicate("route", "hiking"),
                    OsmPredicate("sport", "climbing"),
                    OsmPredicate("tourism", "alpine_hut"),
                    OsmPredicate("highway", "path")
                ))
            }
            com.example.scenic_navigation.models.ActivityType.RELAXATION -> {
                boosts["beach"] = boosts.getOrDefault("beach", 0.0) + 0.8
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.7
                boosts["spa"] = boosts.getOrDefault("spa", 0.0) + 0.9
                boosts["resort"] = boosts.getOrDefault("resort", 0.0) + 0.6
                boosts["relax"] = boosts.getOrDefault("relax", 0.0) + 0.5
                boosts["nature"] = boosts.getOrDefault("nature", 0.0) + 0.4
                filters.addAll(listOf(
                    OsmPredicate("natural", "beach"),
                    OsmPredicate("leisure", "park"),
                    OsmPredicate("leisure", "beach_resort"),
                    OsmPredicate("tourism", "hotel"),
                    OsmPredicate("amenity", "bench")
                ))
            }
            com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY -> {
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.7
                boosts["playground"] = boosts.getOrDefault("playground", 0.0) + 0.8
                boosts["zoo"] = boosts.getOrDefault("zoo", 0.0) + 0.6
                boosts["museum"] = boosts.getOrDefault("museum", 0.0) + 0.5
                boosts["picnic"] = boosts.getOrDefault("picnic", 0.0) + 0.6
                boosts["family"] = boosts.getOrDefault("family", 0.0) + 0.4
                filters.addAll(listOf(
                    OsmPredicate("leisure", "park"),
                    OsmPredicate("leisure", "playground"),
                    OsmPredicate("tourism", "zoo"),
                    OsmPredicate("tourism", "museum"),
                    OsmPredicate("tourism", "picnic_site"),
                    OsmPredicate("amenity", "restaurant")
                ))
            }
            com.example.scenic_navigation.models.ActivityType.ROMANTIC -> {
                boosts["view"] = boosts.getOrDefault("view", 0.0) + 0.8
                boosts["restaurant"] = boosts.getOrDefault("restaurant", 0.0) + 0.7
                boosts["park"] = boosts.getOrDefault("park", 0.0) + 0.6
                boosts["beach"] = boosts.getOrDefault("beach", 0.0) + 0.5
                boosts["sunset"] = boosts.getOrDefault("sunset", 0.0) + 0.9
                boosts["romantic"] = boosts.getOrDefault("romantic", 0.0) + 0.6
                filters.addAll(listOf(
                    OsmPredicate("tourism", "viewpoint"),
                    OsmPredicate("natural", "beach"),
                    OsmPredicate("leisure", "park"),
                    OsmPredicate("amenity", "restaurant"),
                    OsmPredicate("leisure", "picnic_site")
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
