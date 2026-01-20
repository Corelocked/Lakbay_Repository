package com.example.scenic_navigation.ml

import com.example.scenic_navigation.models.Poi
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * Builds normalized feature vectors for Poi objects and basic route context.
 * Normalization constants should be kept in `assets/feature_stats.json` produced during training.
 */
class MlFeatureExtractor {

    /**
     * Build features for a `Poi` given user location and a precomputed scenic score.
     * This mirrors the `FEATURE_ORDER` expected by `MlInferenceEngine`.
     */
    fun buildPoiFeatures(poi: Poi, userLat: Double, userLon: Double, timeMillis: Long, scenicScore: Float = 0f): FloatArray {
        val distance = haversineMeters(userLat, userLon, poi.lat ?: userLat, poi.lon ?: userLon)
        val maxDist = 50_000.0 // normalization cap — keep consistent with training
        val distNorm = (distance / maxDist).coerceIn(0.0, 1.0).toFloat()

        val hour = ((timeMillis / 1000) / 3600) % 24
        val angle = (2.0 * Math.PI * hour) / 24.0
        val timeSin = sin(angle).toFloat()
        val timeCos = cos(angle).toFloat()

        val catFood = if (poi.category.equals("food", ignoreCase = true)) 1f else 0f
        val catSight = if (poi.category.equals("sight", ignoreCase = true) || poi.category.equals("viewpoint", ignoreCase = true)) 1f else 0f

        val hasMunicipality = if (!poi.municipality.isNullOrBlank()) 1f else 0f

        return floatArrayOf(distNorm, timeSin, timeCos, catFood, catSight, scenicScore, hasMunicipality)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
