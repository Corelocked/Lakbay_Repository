package com.example.scenic_navigation.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint

/**
 * Service for fetching routes using OSRM API
 */
class RoutingService {
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    suspend fun fetchRoute(
        start: GeoPoint,
        destination: GeoPoint,
        packageName: String
    ): List<GeoPoint> = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&alternatives=false"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", packageName)
            .header("Accept", "application/json")
            .build()

        try {
            Log.d("RoutingService", "OSRM request: $url")
            val response = httpClient.newCall(request).execute()
            Log.d("RoutingService", "OSRM response code: ${response.code}")

            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                return@withContext parseRoute(body)
            }
        } catch (e: Exception) {
            Log.d("RoutingService", "OSRM error: ${e.message}")
        }

        return@withContext emptyList()
    }

    suspend fun fetchRouteAlternatives(
        start: GeoPoint,
        destination: GeoPoint,
        packageName: String
    ): List<List<GeoPoint>> = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&alternatives=true"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", packageName)
            .header("Accept", "application/json")
            .build()

        try {
            Log.d("RoutingService", "OSRM alternatives request: $url")
            val response = httpClient.newCall(request).execute()
            Log.d("RoutingService", "OSRM response code: ${response.code}")

            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                return@withContext parseAlternatives(body)
            }
        } catch (e: Exception) {
            Log.d("RoutingService", "OSRM error: ${e.message}")
        }

        return@withContext emptyList()
    }

    private fun parseRoute(body: String): List<GeoPoint> {
        try {
            val json = org.json.JSONObject(body)
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return emptyList()

            val routeObj = routes.getJSONObject(0)
            val geometry = routeObj.getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")

            val routePoints = mutableListOf<GeoPoint>()
            for (i in 0 until coords.length()) {
                val point = coords.getJSONArray(i)
                routePoints.add(GeoPoint(point.getDouble(1), point.getDouble(0)))
            }

            return routePoints
        } catch (e: org.json.JSONException) {
            Log.e("RoutingService", "Error parsing route: ${e.message}")
            return emptyList()
        }
    }

    private fun parseAlternatives(body: String): List<List<GeoPoint>> {
        try {
            val json = org.json.JSONObject(body)
            val routes = json.getJSONArray("routes")
            val alternatives = mutableListOf<List<GeoPoint>>()

            for (i in 0 until routes.length()) {
                val routeObj = routes.getJSONObject(i)
                val geometry = routeObj.getJSONObject("geometry")
                val coords = geometry.getJSONArray("coordinates")
                val routePoints = mutableListOf<GeoPoint>()

                for (j in 0 until coords.length()) {
                    val point = coords.getJSONArray(j)
                    routePoints.add(GeoPoint(point.getDouble(1), point.getDouble(0)))
                }

                alternatives.add(routePoints)
            }

            return alternatives
        } catch (e: org.json.JSONException) {
            Log.e("RoutingService", "Error parsing alternatives: ${e.message}")
            return emptyList()
        }
    }
}

