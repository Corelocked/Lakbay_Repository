package com.example.scenic_navigation

// Add optional coordinates to Poi so we can show markers on the map
data class Poi(
    val name: String,
    val category: String,
    val description: String,
    val lat: Double? = null,
    val lon: Double? = null
)
