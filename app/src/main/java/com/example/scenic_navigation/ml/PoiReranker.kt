package com.example.scenic_navigation.ml

import android.content.Context
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.services.UserPreferenceStore
import kotlin.math.max

/**
 * Simple POI reranker that uses `MlFeatureExtractor` + an `MlInference` implementation to score POIs and
 * return them sorted by predicted score. This class is intentionally small and synchronous.
 * Consider running it on a background thread when used in the UI.
 */
class PoiReranker(private val inference: MlInference, private val prefStore: UserPreferenceStore? = null) {
    constructor(context: Context, modelPath: String = "models/poi_reranker_from_luzon.tflite") : this(MlInferenceEngine(context, modelPath))

    private val extractor = MlFeatureExtractor()
    private val engine = inference
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
        // Blend ML score with normalized handcrafted scenicScore (if available). This keeps the ML model
        // in the loop while respecting our curated scenic signal.
        val scored = pois.mapIndexed { idx, poi ->
            val ml = scores.getOrElse(idx) { 0f }
            val scenicNorm = (poi.scenicScore ?: 0f) / 250f // normalize based on ScenicRoutePlanner cap
            val final = ML_BLEND_ALPHA * ml + (1f - ML_BLEND_ALPHA) * scenicNorm
            Pair(poi, final)
        }

        // blend with scenicScore and user preference
        val final = pois.indices.map { i ->
            val scenic = pois[i].scenicScore?.toFloat() ?: 0f
            val mlScore = scores.getOrNull(i) ?: 0f
            val prefScore = prefStore?.getPreferenceScore(pois[i].category) ?: 0f
            val blended = ML_BLEND_ALPHA * mlScore + (1f - ML_BLEND_ALPHA) * scenic
            // include preference multiplicatively (boost) - small factor
            val finalScore = blended + PREF_WEIGHT * prefScore
            Pair(pois[i], finalScore)
        }

        return final.sortedByDescending { it.second }.map { it.first }
    }

    fun close() {
        engine.close()
    }
}
