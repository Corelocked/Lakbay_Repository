package com.example.scenic_navigation.services

import android.util.Log
import com.example.scenic_navigation.models.ScenicMunicipality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.osmdroid.util.GeoPoint

/**
 * Service for fetching scenic municipalities (coastal and mountain towns)
 */
class MunicipalityService {
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    suspend fun fetchScenicMunicipalities(
        routePoints: List<GeoPoint>,
        includeCoastal: Boolean = true,
        includeMountain: Boolean = true,
        packageName: String
    ): List<ScenicMunicipality> = withContext(Dispatchers.IO) {
        routePoints.chunked(100).flatMap { chunk ->
            fetchMunicipalitiesChunk(chunk, includeCoastal, includeMountain, packageName)
        }
    }

    private suspend fun fetchMunicipalitiesChunk(
        routePoints: List<GeoPoint>,
        includeCoastal: Boolean,
        includeMountain: Boolean,
        packageName: String
    ): List<ScenicMunicipality> {
        if (routePoints.isEmpty()) return emptyList()

        val lats = routePoints.map { it.latitude }
        val lons = routePoints.map { it.longitude }
        val minLat = lats.minOrNull()!! - 0.3
        val maxLat = lats.maxOrNull()!! + 0.3
        val minLon = lons.minOrNull()!! - 0.3
        val maxLon = lons.maxOrNull()!! + 0.3

        val ql = """
[out:json][timeout:30];
(
  node[place~"city|town|village"]($minLat,$minLon,$maxLat,$maxLon);
  way[place~"city|town|village"]($minLat,$minLon,$maxLat,$maxLon);
  relation[place~"city|town|village"]($minLat,$minLon,$maxLat,$maxLon);
);
out center tags;
""".trimIndent()

        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
            .header("Accept", "application/json")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: ""
                response.close()

                val municipalities = mutableListOf<ScenicMunicipality>()
                val json = org.json.JSONObject(body)
                val elements = json.optJSONArray("elements") ?: return@withContext emptyList()

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    var elLat = el.optDouble("lat", Double.NaN)
                    var elLon = el.optDouble("lon", Double.NaN)

                    if (elLat.isNaN() || elLon.isNaN()) {
                        val center = el.optJSONObject("center")
                        if (center != null) {
                            elLat = center.optDouble("lat", Double.NaN)
                            elLon = center.optDouble("lon", Double.NaN)
                        }
                    }

                    val tags = el.optJSONObject("tags") ?: continue
                    val name = tags.optString("name")
                    if (name.isBlank()) continue
                    val population = tags.optString("population").toIntOrNull()
                    val elevation = tags.optString("ele").toDoubleOrNull()
                    val isCoastal = tags.has("coastline") || tags.has("beach")
                    val isMountain = elevation != null && elevation > 500

                    val type = when {
                        isCoastal && includeCoastal -> "coastal"
                        isMountain && includeMountain -> "mountain"
                        else -> "other"
                    }

                    val score = when (type) {
                        "coastal" -> 90
                        "mountain" -> 85
                        else -> 50
                    }

                    if ((type == "coastal" && includeCoastal) || (type == "mountain" && includeMountain)) {
                        if (!elLat.isNaN() && !elLon.isNaN()) {
                            municipalities.add(
                                ScenicMunicipality(
                                    name = name,
                                    type = type,
                                    lat = elLat,
                                    lon = elLon,
                                    population = population,
                                    elevation = elevation,
                                    score = score
                                )
                            )
                        }
                    }
                }

                municipalities.sortedByDescending { it.score }
            } catch (e: Exception) {
                Log.d("MunicipalityService", "Error fetching municipalities: ${e.message}")
                emptyList()
            }
        }
    }
}
