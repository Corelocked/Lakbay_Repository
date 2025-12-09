package com.example.scenic_navigation.ml

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import org.tensorflow.lite.Interpreter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * ML inference wrapper that uses TensorFlow Lite Interpreter. If the Interpreter fails to initialize
 * the class falls back to a deterministic heuristic so the app still functions without a model.
 */
class MlInferenceEngine(private val context: Context, private val modelAssetPath: String = "models/poi_reranker.tflite") {
    private var interpreter: Interpreter? = null
    private var hasInterpreter: Boolean = false
    private val interpLock = Any()
    private val defaultNumThreads = 4

    init {
        try {
            val modelBuffer = loadModelFile(context, modelAssetPath)
            val options = Interpreter.Options().apply { setNumThreads(defaultNumThreads) }
            interpreter = Interpreter(modelBuffer, options)
            hasInterpreter = true
        } catch (t: Throwable) {
            // Interpreter initialization failed — fall back to heuristic
            hasInterpreter = false
            interpreter = null
        }
        // Attempt flexible fallback if the direct load failed or if the provided
        // asset path is not present in packaged assets.
        tryInitFlexible(context)
    }

    // Try to be flexible: if the specified asset path doesn't exist, look for any
    // .tflite model file under the `assets/models` folder and use the first one.
    private fun tryInitFlexible(context: Context) {
        if (hasInterpreter) return
        try {
            val modelBuffer = loadModelFile(context, modelAssetPath)
            val options = Interpreter.Options().apply { setNumThreads(defaultNumThreads) }
            interpreter = Interpreter(modelBuffer, options)
            hasInterpreter = true
            return
        } catch (_: Throwable) {
            // try fallback
        }

        try {
            val assets = context.assets.list("models") ?: emptyArray()
            val tflite = assets.firstOrNull { it.endsWith(".tflite") }
            if (tflite != null) {
                val candidate = "models/" + tflite
                val modelBuffer = loadModelFile(context, candidate)
                val options = Interpreter.Options().apply { setNumThreads(defaultNumThreads) }
                interpreter = Interpreter(modelBuffer, options)
                hasInterpreter = true
                Log.i("MlInferenceEngine", "Loaded fallback model asset: $candidate")
                return
            }
        } catch (t: Throwable) {
            Log.w("MlInferenceEngine", "No fallback model found in assets/models/", t)
        }
        hasInterpreter = false
        interpreter = null
    }

    private fun loadModelFile(context: Context, assetPath: String): MappedByteBuffer {
        val fd: AssetFileDescriptor = context.assets.openFd(assetPath)
        FileInputStream(fd.fileDescriptor).use { input ->
            val channel: FileChannel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /**
     * Run inference on a single feature vector.
     * Expects features array of shape [featureCount]. Model input should be [1, featureCount].
     */
    fun predictScore(features: FloatArray): Float {
        if (hasInterpreter && interpreter != null) {
            try {
                val input = arrayOf(features)
                val output = Array(1) { FloatArray(1) }
                interpreter!!.run(input, output)
                return output[0][0]
            } catch (_: Throwable) {
                // fall through to heuristic
            }
        }
        return heuristicScore(features)
    }

    /**
     * Coroutine-friendly single prediction. Runs inference on Dispatchers.Default
     * and synchronizes access to the interpreter. Returns heuristic fallback on error.
     */
    suspend fun predictScoreAsync(features: FloatArray): Float = withContext(Dispatchers.Default) {
        synchronized(interpLock) {
            if (hasInterpreter && interpreter != null) {
                try {
                    val input = arrayOf(features)
                    val output = Array(1) { FloatArray(1) }
                    interpreter!!.run(input, output)
                    return@synchronized output[0][0]
                } catch (_: Throwable) {
                    // fall through to heuristic
                }
            }
            heuristicScore(features)
        }
    }

    /**
     * Batch-predict scores for multiple feature rows. This reduces interpreter overhead
     * by running a single inference with a batch input.
     */
    fun predictScoresBatch(featuresBatch: List<FloatArray>): FloatArray {
        if (featuresBatch.isEmpty()) return FloatArray(0)
        synchronized(interpLock) {
            if (hasInterpreter && interpreter != null) {
                try {
                    val input = featuresBatch.toTypedArray()
                    val output = Array(featuresBatch.size) { FloatArray(1) }
                    interpreter!!.run(input, output)
                    return FloatArray(featuresBatch.size) { i -> output[i][0] }
                } catch (_: Throwable) {
                    // fall through and compute heuristic per-row
                }
            }
            return FloatArray(featuresBatch.size) { i -> heuristicScore(featuresBatch[i]) }
        }
    }

    private fun heuristicScore(features: FloatArray): Float {
        // A simple deterministic linear fallback that loosely mirrors expected feature semantics.
        // Assumed feature indexes (see FEATURE_ORDER):
        // 0: distNorm (0..1, smaller better), 1: timeSin, 2: timeCos,
        // 3: cat_food (0/1), 4: cat_sight (0/1), 5: scenicScore (0..1), 6: hasMunicipality (0/1)
        val dist = if (features.size > 0) features[0] else 1f
        val scenic = if (features.size > 5) features[5] else 0f
        val catFood = if (features.size > 3) features[3] else 0f
        val catSight = if (features.size > 4) features[4] else 0f

        // Score higher for closer, scenic, and sight categories (tweakable weights)
        val s = (1f - dist) * 0.5f + scenic * 0.35f + (catSight * 0.1f + catFood * 0.05f)
        // clamp
        return max(0f, minOf(1f, s))
    }

    fun close() {
        try {
            interpreter?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        /**
         * Human-readable description of the feature order the app uses when calling predictScore.
         * Keep this in sync with `feature_stats.json` in assets and with any offline training.
         */
        val FEATURE_ORDER = listOf(
            "distNorm",
            "timeSin",
            "timeCos",
            "cat_food",
            "cat_sight",
            "scenicScore",
            "hasMunicipality"
        )
    }
}
