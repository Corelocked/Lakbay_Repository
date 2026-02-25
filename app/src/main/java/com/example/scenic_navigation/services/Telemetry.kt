package com.example.scenic_navigation.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Minimal, opt-in Telemetry helper.
 * - Respects SettingsStore.isTelemetryEnabled()
 * - If not initialized, calls are no-op (safe for unit tests)
 * - Appends JSON lines to filesDir/telemetry/events.log for later upload/inspection
 */
object Telemetry {
    private const val TAG = "Telemetry"
    private var initialized = false
    private var filesDirPath: String? = null
    @Volatile private var enabled: Boolean = false
    private val telemetryDirName = "telemetry"
    private val eventsFileName = "events.log"
    private var prefs: SharedPreferences? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // Rotate logs when they exceed this size (5 MB default)
    private const val MAX_FILE_BYTES: Long = 5L * 1024L * 1024L
    private const val ROTATE_KEEP = 3 // keep up to events.log.1 .. events.log.3

    fun init(context: Context) {
        try {
            // Do not hold a Context reference to avoid leaks; store filesDir path only
            filesDirPath = context.applicationContext.filesDir.absolutePath

            // Read initial telemetry enabled state and register listener for changes
            val settings = SettingsStore(context.applicationContext)
            enabled = settings.isTelemetryEnabled()
            prefs = context.applicationContext.getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
                if (key == "telemetry_enabled") {
                    enabled = shared.getBoolean(key, false)
                    Log.i(TAG, "Telemetry enabled changed: $enabled")
                }
            }
            prefs?.registerOnSharedPreferenceChangeListener(prefsListener)

            // ensure telemetry directory exists
            val d = File(filesDirPath, telemetryDirName)
            if (!d.exists()) d.mkdirs()
            initialized = true
            Log.i(TAG, "Telemetry initialized; enabled=$enabled")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to init telemetry: ${t.message}")
            initialized = false
        }
    }

    private fun isEnabled(): Boolean {
        return initialized && enabled
    }

    private fun rotateIfNeeded(basePath: String) {
        try {
            val d = File(basePath, telemetryDirName)
            if (!d.exists()) return
            val f = File(d, eventsFileName)
            if (!f.exists()) return
            if (f.length() <= MAX_FILE_BYTES) return

            // delete oldest if exists
            val oldest = File(d, "$eventsFileName.${ROTATE_KEEP}")
            if (oldest.exists()) oldest.delete()
            // shift files up: events.log.(n-1) -> events.log.n
            for (i in ROTATE_KEEP - 1 downTo 1) {
                val src = File(d, "$eventsFileName.$i")
                if (src.exists()) {
                    val dst = File(d, "$eventsFileName.${i + 1}")
                    src.renameTo(dst)
                }
            }
            // move current to events.log.1
            val first = File(d, "$eventsFileName.1")
            f.renameTo(first)
            // create a new empty events.log
            File(d, eventsFileName).createNewFile()
            Log.i(TAG, "Rotated telemetry logs; kept up to $ROTATE_KEEP files")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to rotate telemetry logs: ${t.message}")
        }
    }

    /**
     * Log a structured event. Safe to call from any thread.
     * Simple types and nested Lists/Maps (String keys) are supported.
     */
    fun logEvent(eventType: String, params: Map<String, Any?> = emptyMap()) {
        if (!initialized) {
            // Safe no-op if telemetry not initialized (e.g., tests)
            Log.d(TAG, "Telemetry not initialized; skipping event=$eventType")
            return
        }
        if (!isEnabled()) {
            Log.d(TAG, "Telemetry disabled; skipping event=$eventType")
            return
        }
        try {
            val root = JSONObject()
            root.put("event_type", eventType)
            root.put("event_time", System.currentTimeMillis())
            // Add params
            val p = JSONObject()
            for ((k, v) in params) {
                when (v) {
                    null -> p.put(k, JSONObject.NULL)
                    is Number -> p.put(k, v)
                    is Boolean -> p.put(k, v)
                    is String -> p.put(k, v)
                    is Map<*, *> -> {
                        val sub = JSONObject()
                        for ((sk, sv) in v) if (sk is String) sub.put(sk, sv)
                        p.put(k, sub)
                    }
                    is List<*> -> {
                        val arr = JSONArray()
                        for (item in v) arr.put(item)
                        p.put(k, arr)
                    }
                    else -> p.put(k, v.toString())
                }
            }
            root.put("params", p)

            // Append to file in append mode using stored filesDirPath
            val basePath = filesDirPath ?: return
            // rotate if file too large
            rotateIfNeeded(basePath)

            val d = File(basePath, telemetryDirName)
            if (!d.exists()) d.mkdirs()
            val f = File(d, eventsFileName)
            FileOutputStream(f, true).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.append(root.toString())
                    writer.append('\n')
                    writer.flush()
                }
            }

            Log.i(TAG, "Logged telemetry event=$eventType")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to log telemetry event=$eventType: ${t.message}")
        }
    }

    /**
     * Convenience: publish a recommendation impression snapshot
     */
    fun logRecommendationImpression(requestId: String, candidateNames: List<String>, personalizationEnabled: Boolean, modelVersion: String? = null) {
        val params = mutableMapOf<String, Any?>()
        params["request_id"] = requestId
        params["candidates"] = candidateNames.take(20)
        params["personalization_enabled"] = personalizationEnabled
        params["model_version"] = modelVersion
        logEvent("recommendation_impression", params)
    }

    fun logPoiOpen(poiName: String, category: String?, municipality: String?) {
        val params = mapOf<String, Any?>(
            "poi_name" to poiName,
            "category" to category,
            "municipality" to municipality
        )
        logEvent("poi_detail_open", params)
    }

    fun logFavoriteAdded(poiName: String, key: String) {
        logEvent("favorite_added", mapOf("poi_name" to poiName, "key" to key))
    }

    fun logFavoriteRemoved(poiName: String, key: String) {
        logEvent("favorite_removed", mapOf("poi_name" to poiName, "key" to key))
    }

    // TODO: implement upload/flush logic to send batched events to server or analytics platform
}
