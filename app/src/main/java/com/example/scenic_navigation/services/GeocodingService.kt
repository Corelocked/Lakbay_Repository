package com.example.scenic_navigation.services

import android.net.Uri
import android.util.Log
import com.example.scenic_navigation.models.GeocodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.LinkedHashMap

/**
 * Service for geocoding addresses to coordinates using Nominatim API
 */
class GeocodingService {
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    // LRU cache with TTL for geocode queries
    private val GEOCODE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    private val GEOCODE_MAX_ENTRIES = 200

    private data class CacheEntry(val results: List<GeocodeResult>, val timestamp: Long)

    private val geocodeCache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return this.size > GEOCODE_MAX_ENTRIES
        }
    }

    suspend fun geocodeAddress(
        query: String,
        packageName: String,
        onError: ((String) -> Unit)? = null
    ): List<GeocodeResult> = withContext(Dispatchers.IO) {
        // Check cache first
        val cached = geocodeCache[query]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp < GEOCODE_TTL_MS)) {
            return@withContext cached.results
        }

        val url = "https://nominatim.openstreetmap.org/search?q=${Uri.encode(query)}&format=json&limit=5"
        val userAgent = "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)"
        val maxAttempts = 3
        var backoff = 500L

        for (attempt in 1..maxAttempts) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()

                Log.d("GeocodingService", "Geocode request (attempt $attempt): $url")
                val response = httpClient.newCall(request).execute()
                val code = response.code
                val body = response.body?.string() ?: ""

                Log.d("GeocodingService", "Geocode response code: $code")

                when {
                    code == 403 -> {
                        onError?.invoke("Geocoding forbidden. Check User-Agent configuration.")
                        return@withContext emptyList()
                    }
                    code == 429 || code >= 500 -> {
                        response.close()
                        if (attempt < maxAttempts) {
                            Log.d("GeocodingService", "Retry after $backoff ms (code $code)")
                            kotlinx.coroutines.delay(backoff)
                            backoff *= 2
                            continue
                        } else {
                            onError?.invoke("Geocoding failed: server returned $code")
                            return@withContext emptyList()
                        }
                    }
                    !response.isSuccessful -> {
                        response.close()
                        onError?.invoke("Geocoding failed: server returned $code")
                        return@withContext emptyList()
                    }
                }

                val results = mutableListOf<GeocodeResult>()
                val jsonArray = org.json.JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    results.add(
                        GeocodeResult(
                            displayName = item.optString("display_name", "Unknown place"),
                            lat = item.optDouble("lat", 0.0),
                            lon = item.optDouble("lon", 0.0)
                        )
                    )
                }

                geocodeCache[query] = CacheEntry(results, System.currentTimeMillis())
                return@withContext results

            } catch (e: Exception) {
                Log.d("GeocodingService", "Geocode error (attempt $attempt): ${e.message}")
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(backoff)
                    backoff *= 2
                    continue
                } else {
                    onError?.invoke("Geocoding error: ${e.message}")
                    return@withContext emptyList()
                }
            }
        }

        return@withContext emptyList()
    }
}
