package com.example.scenic_navigation.ml

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import org.tensorflow.lite.Interpreter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import org.json.JSONObject

/**
 * ML inference wrapper that uses TensorFlow Lite Interpreter. If the Interpreter fails to initialize
 * the class falls back to a deterministic heuristic so the app still functions without a model.
 */
class MlInferenceEngine(private val context: Context, private val modelAssetPath: String = "models/poi_reranker_from_luzon.tflite") : MlInference {
    private var interpreter: Interpreter? = null
    private var hasInterpreter: Boolean = false
    private val interpLock = Any()
    private val defaultNumThreads = 4
    private var inputIsQuantized: Boolean = false
    private var inputScale: Float = 1.0f
    private var inputZeroPoint: Int = 0
    private var outputIsQuantized: Boolean = false
    private var outputScale: Float = 1.0f
    private var outputZeroPoint: Int = 0
    private var inputFeatureCount: Int = -1

    init {
        // Log model metadata if present
        try {
            val metaStream = context.assets.open("models/model_metadata.json")
            BufferedReader(InputStreamReader(metaStream)).use { br ->
                val content = br.readText()
                val j = JSONObject(content)
                val name = j.optString("model_name", "unknown")
                val version = j.optString("version", "unknown")
                val trainedOn = j.optString("trained_on", "unknown")
                Log.i("MlInferenceEngine", "Model metadata - name:$name version:$version trained_on:$trainedOn")
                // Log metrics if present
                val metrics = j.optJSONObject("metrics")
                if (metrics != null) {
                    val auc = metrics.optDouble("auc", Double.NaN)
                    val acc = metrics.optDouble("accuracy", Double.NaN)
                    val n = j.optInt("n_samples", -1)
                    Log.i("MlInferenceEngine", "Model metrics - auc:${if (!auc.isNaN()) String.format("%.4f", auc) else "n/a"} accuracy:${if (!acc.isNaN()) String.format("%.3f", acc) else "n/a"} samples:${if (n>=0) n else "n/a"}")
                }
            }
        } catch (e: Exception) {
            Log.i("MlInferenceEngine", "No model metadata found or failed to read it: ${e.message}")
        }

        try {
            val modelBuffer = loadModelFile(context, modelAssetPath)
            val options = Interpreter.Options().apply { setNumThreads(defaultNumThreads) }
            interpreter = Interpreter(modelBuffer, options)
            Log.i("MlInferenceEngine", "Loaded model asset: $modelAssetPath")
            // inspect input/output tensor details for quantization params
            try {
                val inTensor = interpreter!!.getInputTensor(0)
                inputFeatureCount = inTensor.shape().last()
                val inType = inTensor.dataType()
                if (inType == DataType.UINT8 || inType == DataType.INT8) {
                    inputIsQuantized = true
                    val qp = inTensor.quantizationParams()
                    inputScale = qp.scale
                    inputZeroPoint = qp.zeroPoint
                }
                val outTensor = interpreter!!.getOutputTensor(0)
                val outType = outTensor.dataType()
                if (outType == DataType.UINT8 || outType == DataType.INT8) {
                    outputIsQuantized = true
                    val qp2 = outTensor.quantizationParams()
                    outputScale = qp2.scale
                    outputZeroPoint = qp2.zeroPoint
                }
            } catch (_: Throwable) {
                // ignore inspection errors
            }
            hasInterpreter = true
        } catch (t: Throwable) {
            Log.w("MlInferenceEngine", "Failed to load model asset $modelAssetPath, attempting fallback...", t)
            // Fallback: try to load any .tflite in assets/models
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
                    // continue instead of returning; tryInitFlexible below will respect hasInterpreter
                }
            } catch (t: Throwable) {
                Log.w("MlInferenceEngine", "No fallback model found in assets/models/", t)
            }
            hasInterpreter = hasInterpreter && interpreter != null
            if (!hasInterpreter) {
                interpreter = null
            }
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
    override fun predictScore(features: FloatArray): Float {
        if (hasInterpreter && interpreter != null) {
            try {
                if (inputIsQuantized) {
                    // quantize features to int8/uint8 per tensor params
                    val bb = ByteBuffer.allocateDirect(features.size).order(ByteOrder.nativeOrder())
                    for (i in features.indices) {
                        val q = Math.round(features[i] / inputScale) + inputZeroPoint
                        bb.put(q.toByte())
                    }
                    bb.rewind()

                    if (outputIsQuantized) {
                        val outBb = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
                        interpreter!!.run(bb, outBb)
                        outBb.rewind()
                        val qout = outBb.get().toInt()
                        return (qout - outputZeroPoint) * outputScale
                    } else {
                        val output = Array(1) { FloatArray(1) }
                        interpreter!!.run(bb, output)
                        return output[0][0]
                    }
                } else {
                    val input = arrayOf(features)
                    val output = Array(1) { FloatArray(1) }
                    interpreter!!.run(input, output)
                    return output[0][0]
                }
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
    override suspend fun predictScoreAsync(features: FloatArray): Float = withContext(Dispatchers.Default) {
        synchronized(interpLock) {
            if (hasInterpreter && interpreter != null) {
                try {
                    if (inputIsQuantized) {
                        val bb = ByteBuffer.allocateDirect(features.size).order(ByteOrder.nativeOrder())
                        for (i in features.indices) {
                            val q = Math.round(features[i] / inputScale) + inputZeroPoint
                            bb.put(q.toByte())
                        }
                        bb.rewind()
                        if (outputIsQuantized) {
                            val outBb = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
                            interpreter!!.run(bb, outBb)
                            outBb.rewind()
                            val qout = outBb.get().toInt()
                            return@synchronized (qout - outputZeroPoint) * outputScale
                        } else {
                            val output = Array(1) { FloatArray(1) }
                            interpreter!!.run(bb, output)
                            return@synchronized output[0][0]
                        }
                    } else {
                        val input = arrayOf(features)
                        val output = Array(1) { FloatArray(1) }
                        interpreter!!.run(input, output)
                        return@synchronized output[0][0]
                    }
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
    override fun predictScoresBatch(featuresBatch: List<FloatArray>): FloatArray {
        if (featuresBatch.isEmpty()) return FloatArray(0)
        synchronized(interpLock) {
            if (hasInterpreter && interpreter != null) {
                try {
                    if (inputIsQuantized) {
                        val batchSize = featuresBatch.size
                        val bb = ByteBuffer.allocateDirect(batchSize * featuresBatch[0].size).order(ByteOrder.nativeOrder())
                        for (r in 0 until batchSize) {
                            val row = featuresBatch[r]
                            for (i in row.indices) {
                                val q = Math.round(row[i] / inputScale) + inputZeroPoint
                                bb.put(q.toByte())
                            }
                        }
                        bb.rewind()
                        if (outputIsQuantized) {
                            val outBb = ByteBuffer.allocateDirect(batchSize).order(ByteOrder.nativeOrder())
                            interpreter!!.run(bb, outBb)
                            outBb.rewind()
                            val out = FloatArray(batchSize)
                            for (i in 0 until batchSize) {
                                val qout = outBb.get().toInt()
                                out[i] = (qout - outputZeroPoint) * outputScale
                            }
                            return out
                        } else {
                            val output = Array(batchSize) { FloatArray(1) }
                            interpreter!!.run(bb, output)
                            return FloatArray(batchSize) { i -> output[i][0] }
                        }
                    } else {
                        val input = featuresBatch.toTypedArray()
                        val output = Array(featuresBatch.size) { FloatArray(1) }
                        interpreter!!.run(input, output)
                        return FloatArray(featuresBatch.size) { i -> output[i][0] }
                    }
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

    override fun close() {
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
