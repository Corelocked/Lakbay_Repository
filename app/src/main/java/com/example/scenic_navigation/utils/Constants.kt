package com.example.scenic_navigation.utils

/**
 * Application-wide constants
 */
object Constants {
    // Map Configuration
    const val DEFAULT_ZOOM_LEVEL = 13.0
    const val DEFAULT_LATITUDE = 14.5995
    const val DEFAULT_LONGITUDE = 120.9842

    // Route Planning
    const val MIN_ROUTE_POINTS = 2
    const val MAX_ROUTE_DISTANCE_KM = 1000

    // POI Categories
    const val CATEGORY_SCENIC = "scenic"
    const val CATEGORY_COASTAL = "coastal"
    const val CATEGORY_MOUNTAIN = "mountain"
    const val CATEGORY_HISTORIC = "historic"
    const val CATEGORY_FOOD = "food"
    const val CATEGORY_CULTURE = "culture"

    // Preferences Keys
    const val PREF_LAST_LOCATION = "last_location"
    const val PREF_USE_CURRENT_LOCATION = "use_current_location"
    const val PREF_OCEANIC_ROUTE = "oceanic_route"
    const val PREF_MOUNTAIN_ROUTE = "mountain_route"
}

