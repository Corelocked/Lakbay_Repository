package com.example.scenic_navigation.config

object Config {
    // Toggle to disable Overpass network queries for testing the local model/dataset
    const val DISABLE_OVERPASS = false
    // Defaults for cluster tuning (meters, min points)
    const val DEFAULT_CLUSTER_EPS_METERS = 2000.0
    const val DEFAULT_CLUSTER_MIN_PTS = 3
    // SharedPreferences keys for tuning
    const val PREF_CLUSTER_EPS_KEY = "pref_cluster_eps_meters"
    const val PREF_CLUSTER_MIN_PTS_KEY = "pref_cluster_min_pts"
    // Preference to let user prefer built-in coastal waypoint sets for long oceanic trips
    const val PREF_PREFER_COASTAL_LONG_OCEANIC = "pref_prefer_coastal_long_oceanic"
}
