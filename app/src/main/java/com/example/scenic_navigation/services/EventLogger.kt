package com.example.scenic_navigation.services

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple NDJSON event logger that writes anonymized interactions to app-private storage.
 * Respects opt-in via Settings (caller should check pref before calling logEvent).
 */
class EventLogger(private val context: Context) {
    private val TAG = "EventLogger"
    private val fileName = "poi_inference_events.ndjson"

    private fun getFile(): File = File(context.filesDir, fileName)

    fun logEvent(eventType: String, payload: Map<String, Any?>) {
        try {
            val obj = JSONObject()
            obj.put("ts", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))
            obj.put("event", eventType)
            payload.forEach { (k, v) ->
                if (v == null) obj.put(k, JSONObject.NULL) else obj.put(k, v)
            }
            val f = getFile()
            FileWriter(f, true).use { w ->
                w.append(obj.toString())
                w.append("\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log event: ${e.message}")
        }
    }

    fun listFile(): File = getFile()

    fun clear() {
        try {
            val f = getFile()
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear log: ${e.message}")
        }
    }
}

