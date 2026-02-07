package com.example.scenic_navigation.ml

/**
 * Minimal inference interface used by the app so implementations can be swapped.
 */
interface MlInference {
    fun predictScore(features: FloatArray): Float
    suspend fun predictScoreAsync(features: FloatArray): Float
    fun predictScoresBatch(featuresBatch: List<FloatArray>): FloatArray
    fun close()
}
