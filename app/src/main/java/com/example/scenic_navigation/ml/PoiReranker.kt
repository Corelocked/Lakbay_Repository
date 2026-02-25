package com.example.scenic_navigation.ml

import android.content.Context
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.services.UserPreferenceStore

/**
 * Simple POI reranker that uses `MlFeatureExtractor` + an `MlInference` implementation to score POIs and
 * return them sorted by predicted score. This class is intentionally small and synchronous.
 * Consider running it on a background thread when used in the UI.
 */
class PoiReranker(inference: MlInference, private val prefStore: UserPreferenceStore? = null) {
    private val extractor = MlFeatureExtractor()
    private val engine: MlInference = inference
    // Blend factor between ML model score and handcrafted scenic score (0.0 = only scenic, 1.0 = only ML)
    private val ML_BLEND_ALPHA = 0.75f
    private val PREF_WEIGHT = 0.15f // weight to include user preference into final score

    /**
     * Rerank a list of POIs. Returns a new list sorted by descending predicted score.
     * timeMillis should be System.currentTimeMillis() in calling code.
     */
    fun rerank(pois: List<Poi>, userLat: Double, userLon: Double, timeMillis: Long): List<Poi> {
        // Build feature batch and run a single batched inference to reduce overhead.
        val featuresBatch = pois.map { poi -> extractor.buildPoiFeatures(poi, userLat, userLon, timeMillis, scenicScore = poi.scenicScore ?: 0f) }
        val scores = engine.predictScoresBatch(featuresBatch)

        // blend with scenicScore and user preference (normalize scenic before combining)
        val final = pois.indices.map { i ->
            val scenic = pois[i].scenicScore ?: 0f
            val scenicNorm = (scenic / 250f).coerceIn(0f, 1f) // NORMALIZE here for blending
            val mlScore = scores.getOrNull(i) ?: 0f
            val prefScore = prefStore?.getPreferenceScore(pois[i].category) ?: 0f
            val blended = ML_BLEND_ALPHA * mlScore + (1f - ML_BLEND_ALPHA) * scenicNorm
            // include preference as a small additive boost
            val finalScore = blended + PREF_WEIGHT * prefScore
            Pair(pois[i], finalScore)
        }

        return final.sortedByDescending { it.second }.map { it.first }
    }

    fun close() {
        engine.close()
    }

    companion object {
        @JvmStatic
        fun fromContext(context: Context, modelPath: String = "models/poi_reranker_from_luzon.tflite", prefStore: UserPreferenceStore? = null): PoiReranker {
            val engine = MlInferenceEngine(context, modelPath)
            return PoiReranker(engine, prefStore)
        }
    }
}
