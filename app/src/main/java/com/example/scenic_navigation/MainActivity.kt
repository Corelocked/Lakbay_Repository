package com.example.scenic_navigation

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.app.AlertDialog
import android.graphics.Color

// osmdroid imports
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

import com.google.android.material.snackbar.Snackbar
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume

import java.util.LinkedHashMap

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private val REQ_LOCATION = 100
    private var lastLocation: Location? = null
    private var map: MapView? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    // map of POI key -> Marker to allow selecting/highlighting from the recommendations list
    private val poiMarkerMap = mutableMapOf<String, Marker>()

    // reuse OkHttp client for geocoding
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    // simple LRU in-memory cache with TTL for geocode queries
    private val GEOCODE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    private val GEOCODE_MAX_ENTRIES = 200
    private data class CacheEntry(val results: List<GeocodeResult>, val timestamp: Long)
    private val geocodeCache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return this.size > GEOCODE_MAX_ENTRIES
        }
    }

    // job reference so we can cancel ongoing geocoding
    private var geocodeJob: Job? = null

    // Simple data holder for geocode results
    data class GeocodeResult(val displayName: String, val lat: Double, val lon: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid configuration: set user agent so tile servers allow requests
        Configuration.getInstance().setUserAgentValue(packageName)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val etStart = findViewById<EditText>(R.id.et_start)
        val etDestination = findViewById<EditText>(R.id.et_destination)
        val switchUseCurrent = findViewById<SwitchCompat>(R.id.switch_use_current)
        val btnPlan = findViewById<Button>(R.id.btn_plan)
        val rvRecs = findViewById<RecyclerView>(R.id.rv_recommendations)
        val progress = findViewById<ProgressBar>(R.id.progress_geocoding)

        rvRecs.layoutManager = LinearLayoutManager(this)

        // initialize MapView
        map = findViewById(R.id.map)
        map?.setTileSource(TileSourceFactory.MAPNIK)
        map?.setMultiTouchControls(true)
        val mapController = map?.controller
        mapController?.setZoom(10.0)
        // default center: if we already have location permission, use last-known location; otherwise fallback to (0,0)
        if (hasLocationPermission()) {
            lastLocation = fetchLastKnownLocation()
            if (lastLocation != null) {
                mapController?.setCenter(GeoPoint(lastLocation!!.latitude, lastLocation!!.longitude))
                mapController?.setZoom(12.0)
            } else {
                mapController?.setCenter(GeoPoint(0.0, 0.0))
            }
            // enable the my-location overlay now that we have permission
            enableLocationOverlayIfPermitted()
        } else {
            mapController?.setCenter(GeoPoint(0.0, 0.0))
        }

        // Proactive permission prompt: ask once on first launch if we don't have location permission
        val prefs = getSharedPreferences("scenic_prefs", MODE_PRIVATE)
        val askedKey = "asked_location"
        val alreadyAsked = prefs.getBoolean(askedKey, false)
        if (!alreadyAsked && !hasLocationPermission()) {
            // mark we've asked so we don't prompt repeatedly
            prefs.edit { putBoolean(askedKey, true) }
            // show rationale snackbar with Allow action
            Snackbar.make(findViewById(R.id.main), "Allow location access to center the map and suggest nearby places", Snackbar.LENGTH_INDEFINITE)
                .setAction("Allow") {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
                }
                .show()
        }

        // setup my-location overlay (we'll enable when permission exists)
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)

        switchUseCurrent.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // request location permission and fetch last known
                if (hasLocationPermission()) {
                    lastLocation = fetchLastKnownLocation()
                    if (lastLocation != null) {
                        etStart.setText(String.format(Locale.US, "Current location (%.6f, %.6f)", lastLocation!!.latitude, lastLocation!!.longitude))
                        // center map on current location
                        mapController?.setCenter(GeoPoint(lastLocation!!.latitude, lastLocation!!.longitude))
                        mapController?.setZoom(12.0)
                    } else {
                        etStart.setText(getString(R.string.current_location))
                    }
                    enableLocationOverlayIfPermitted()
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
                }
            } else {
                etStart.setText("")
            }
        }

        val btnCancel = findViewById<Button>(R.id.btn_cancel_geocode)

        btnCancel.setOnClickListener {
            geocodeJob?.cancel()
            // hide spinner and re-enable UI
            progress.visibility = View.GONE
            btnPlan.isEnabled = true
            etDestination.isEnabled = true
            etStart.isEnabled = true
            switchUseCurrent.isEnabled = true
            btnCancel.visibility = View.GONE
            Snackbar.make(findViewById(R.id.main), getString(R.string.geocode_cancelled), Snackbar.LENGTH_SHORT).show()
        }

        btnPlan.setOnClickListener {
            val useCurrent = switchUseCurrent.isChecked
            val startInput = if (useCurrent) {
                lastLocation?.let { "${it.latitude},${it.longitude}" } ?: etStart.text.toString()
            } else {
                etStart.text.toString()
            }
            val destInput = etDestination.text.toString()
            if (destInput.isBlank()) {
                Snackbar.make(findViewById(R.id.main), getString(R.string.enter_destination), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Use coroutines for geocoding
            geocodeJob = lifecycleScope.launch {
                // show progress and disable inputs
                progress.visibility = View.VISIBLE
                btnPlan.isEnabled = false
                etDestination.isEnabled = false
                etStart.isEnabled = false
                switchUseCurrent.isEnabled = false
                btnCancel.visibility = View.VISIBLE
                var startPoint = parseLatLon(startInput)
                if (startPoint == null && startInput.isNotBlank()) {
                    val startResults = geocodeAddresses(startInput)
                    if (startResults.isNotEmpty()) {
                        startPoint = if (startResults.size == 1) {
                            GeoPoint(startResults[0].lat, startResults[0].lon)
                        } else {
                            val sel = selectGeocodeResult(startResults, "Select start location")
                            sel?.let { GeoPoint(it.lat, it.lon) }
                        }
                    } else {
                        // No results: inform the user (could be network or no matches)
                        withContext(Dispatchers.Main) {
                            Snackbar.make(findViewById(R.id.main), "No geocoding results for start input (check network or spelling)", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
                var destPoint = parseLatLon(destInput)
                if (destPoint == null) {
                    val destResults = geocodeAddresses(destInput)
                    if (destResults.isNotEmpty()) {
                        destPoint = if (destResults.size == 1) {
                            GeoPoint(destResults[0].lat, destResults[0].lon)
                        } else {
                            val sel = selectGeocodeResult(destResults, "Select destination")
                            sel?.let { GeoPoint(it.lat, it.lon) }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Snackbar.make(findViewById(R.id.main), "No geocoding results for destination (check network or spelling)", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }

                if (destPoint == null) {
                    Snackbar.make(findViewById(R.id.main), "Could not geocode destination", Snackbar.LENGTH_LONG).show()
                    // cleanup and re-enable UI
                    progress.visibility = View.GONE
                    btnCancel.visibility = View.GONE
                    btnPlan.isEnabled = true
                    etDestination.isEnabled = true
                    etStart.isEnabled = true
                    switchUseCurrent.isEnabled = true
                    geocodeJob = null
                    return@launch
                }

                // Fetch route and POIs along the route (route-aware recommendations)
                val routeAndPois = fetchRouteAndPois(startPoint, destPoint)
                val routePoints = routeAndPois.first
                val routePois = routeAndPois.second

                // Merge route POIs into recommendations (route POIs first)
                val recommendations = mutableListOf<Poi>()
                for (p in routePois) if (keepPoi(p)) recommendations.add(p)
                // Fetch additional recommendations: prefer POIs along/near the route; fall back to destination-centered search
                var fetched = if (routePoints.isNotEmpty()) {
                    // sample every ~800m along the route and search within 100m
                    generateRecommendationsAlongRoute(routePoints, sampleDistMeters = 800, radiusMeters = 100)
                } else {
                    generateRecommendations(destPoint, 5000)
                }

                // If route-based fetch returned none, try a destination-centered fallback with larger radius
                if (fetched.isEmpty() && routePoints.isNotEmpty()) {
                    Log.d("MainActivity", "Route-based POIs empty, falling back to destination-centered search")
                    val fallback = generateRecommendations(destPoint, 10000)
                    if (fallback.isNotEmpty()) {
                        fetched = fallback
                        Log.d("MainActivity", "Fallback returned ${fetched.size} POIs")
                        withContext(Dispatchers.Main) {
                            Snackbar.make(findViewById(R.id.main), "No POIs found along route — showing ${fetched.size} near destination", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }

                Log.d("MainActivity", "routePois=${routePois.size}, fetched=${fetched.size}")
                if (fetched.isNotEmpty()) {
                    // Log first few names for easier debugging in Logcat
                    val firstNames = fetched.take(5).joinToString(", ") { it.name }
                    Log.d("MainActivity", "Sample POIs: $firstNames")
                    withContext(Dispatchers.Main) {
                        Snackbar.make(findViewById(R.id.main), "Found ${fetched.size} POIs (route: ${routePois.size})", Snackbar.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(findViewById(R.id.main), "No POIs found (route:${routePois.size}). Try increasing radius or check network.", Snackbar.LENGTH_LONG).show()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("No recommendations found")
                            .setMessage("No POIs were found along the route. You can try increasing the search radius or check network/Overpass availability. Run a quick test?")
                            .setPositiveButton("Run test") { _, _ ->
                                val dbgPoint = destPoint
                                lifecycleScope.launch {
                                    // run a small debug Overpass request at destination to surface logs
                                    debugOverpassAt(dbgPoint, 1000)
                                }
                            }
                            .setNegativeButton("OK", null)
                            .show()
                    }
                }
                // filter fetched POIs for blank names / surveillance cameras
                val filteredFetched = fetched.filter { keepPoi(it) }
                recommendations.addAll(filteredFetched)
                // Clear existing overlays and marker map, then re-add myLocationOverlay if present
                val overlays = map?.overlays
                overlays?.clear()
                poiMarkerMap.clear()
                overlays?.let { overlayList ->
                    myLocationOverlay?.let { overlay -> if (!overlayList.contains(overlay)) overlayList.add(overlay) }
                }

                // Add POI markers for route POIs (and for mocked ones included later)
                for (poi in recommendations) {
                    if (poi.lat != null && poi.lon != null) {
                        val marker = Marker(map)
                        marker.position = GeoPoint(poi.lat, poi.lon)
                        marker.title = poi.name
                        marker.subDescription = poi.description
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        overlays?.add(marker)
                        // store marker keyed by POI so list clicks can find it
                        poiMarkerMap[poiKey(poi)] = marker
                    }
                }

                // Draw route polyline if available
                if (routePoints.isNotEmpty()) {
                    val line = Polyline()
                    line.setPoints(routePoints)
                    line.outlinePaint.color = Color.argb(0x80, 0x00, 0x00, 0xFF)
                    line.outlinePaint.strokeWidth = 6.0f
                    overlays?.add(line)

                    // center to middle of route
                    val mid = routePoints[routePoints.size / 2]
                    mapController?.setCenter(mid)
                    mapController?.setZoom(8.0)
                } else if (startPoint != null) {
                    mapController?.setCenter(startPoint)
                    mapController?.setZoom(8.0)
                } else {
                    mapController?.setCenter(destPoint)
                    mapController?.setZoom(10.0)
                }

                map?.invalidate()

                // Now that markers are present, set adapter so clicks can show corresponding marker info
                rvRecs.adapter = PoiAdapter(recommendations) { poi ->
                    if (poi.lat != null && poi.lon != null) {
                        map?.controller?.setCenter(GeoPoint(poi.lat, poi.lon))
                        map?.controller?.setZoom(15.0)
                        val key = poiKey(poi)
                        val marker = poiMarkerMap[key]
                        marker?.showInfoWindow()
                        map?.invalidate()
                    }
                }

                // hide progress and re-enable inputs
                progress.visibility = View.GONE
                btnCancel.visibility = View.GONE
                btnPlan.isEnabled = true
                etDestination.isEnabled = true
                etStart.isEnabled = true
                switchUseCurrent.isEnabled = true
                geocodeJob = null
            }
        }
    }

    private fun enableLocationOverlayIfPermitted() {
        if (hasLocationPermission()) {
            myLocationOverlay?.enableMyLocation()
            map?.overlays?.let { overlayList -> myLocationOverlay?.let { if (!overlayList.contains(it)) overlayList.add(it) } }
        }
    }

    private fun parseLatLon(input: String): GeoPoint? {
        val parts = input.split(",")
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon)
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun fetchLastKnownLocation(): Location? {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            val l = lm.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        return bestLocation
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                lastLocation = fetchLastKnownLocation()
                // update start EditText if available
                val etStart = findViewById<EditText>(R.id.et_start)
                if (lastLocation != null) {
                    etStart.setText(String.format(Locale.US, getString(R.string.current_location_fmt), lastLocation!!.latitude, lastLocation!!.longitude))
                    // center map
                    map?.controller?.setCenter(GeoPoint(lastLocation!!.latitude, lastLocation!!.longitude))
                    map?.controller?.setZoom(12.0)
                } else {
                    etStart.setText(getString(R.string.current_location))
                }
                enableLocationOverlayIfPermitted()
            } else {
                Snackbar.make(findViewById(R.id.main), getString(R.string.location_permission_denied), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // Fetch POIs near a center point using Overpass API. Returns empty list if center is null or on error.
    private suspend fun generateRecommendations(center: GeoPoint?, radiusMeters: Int = 5000): List<Poi> = withContext(Dispatchers.IO) {
        if (center == null) return@withContext emptyList()
        val lat = center.latitude
        val lon = center.longitude

        // Overpass QL: nodes with common POI tags within radius
        val ql = """
+[out:json][timeout:15];
+(
+  node(around:$radiusMeters,$lat,$lon)[tourism];
+  way(around:$radiusMeters,$lat,$lon)[tourism];
+  relation(around:$radiusMeters,$lat,$lon)[tourism];
+  node(around:$radiusMeters,$lat,$lon)[amenity];
+  way(around:$radiusMeters,$lat,$lon)[amenity];
+  relation(around:$radiusMeters,$lat,$lon)[amenity];
+  node(around:$radiusMeters,$lat,$lon)[historic];
+  way(around:$radiusMeters,$lat,$lon)[historic];
+  relation(around:$radiusMeters,$lat,$lon)[historic];
+  node(around:$radiusMeters,$lat,$lon)[leisure];
+  way(around:$radiusMeters,$lat,$lon)[leisure];
+  relation(around:$radiusMeters,$lat,$lon)[leisure];
+  node(around:$radiusMeters,$lat,$lon)[man_made];
+  way(around:$radiusMeters,$lat,$lon)[man_made];
+  relation(around:$radiusMeters,$lat,$lon)[man_made];
+);
+out center 50;
+""".trimIndent()

        // Ensure the QL has no stray leading characters and send as text/plain
        val qlClean = ql.lines().joinToString("\n") { it.trimStart('+') }
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = qlClean.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "${packageName}/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
            .header("Accept", "application/json")
            .build()

        try {
            Log.d("MainActivity", "Overpass request: around=$radiusMeters lat=$lat lon=$lon")
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d("MainActivity", "Overpass response code: ${response.code}")
                response.close()
                return@withContext emptyList()
            }
            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return@withContext emptyList()

            val pois = mutableListOf<Poi>()
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                // try node lat/lon, otherwise check 'center' (for ways/relations)
                var elLat = el.optDouble("lat", Double.NaN)
                var elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) {
                    val center = el.optJSONObject("center")
                    if (center != null) {
                        elLat = center.optDouble("lat", Double.NaN)
                        elLon = center.optDouble("lon", Double.NaN)
                    }
                }
                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name") ?: tags?.optString("official_name") ?: "Unknown"
                val category = deriveCategory(tags)
                val desc = tags?.optString("description") ?: tags?.optString("operator") ?: ""

                // Skip blank/unknown names and surveillance cameras
                val nameTrim = name.trim()
                if (nameTrim.isBlank() || nameTrim.equals("Unknown", ignoreCase = true)) continue
                if (isSurveillance(tags)) continue

                if (!elLat.isNaN() && !elLon.isNaN()) {
                    pois.add(Poi(nameTrim, category, desc, elLat, elLon))
                }
                if (pois.size >= 30) break
            }

            Log.d("MainActivity", "Overpass returned ${pois.size} POIs")
            return@withContext pois
        } catch (e: Exception) {
            Log.d("MainActivity", "Overpass error: ${e.message}")
            withContext(Dispatchers.Main) {
                Snackbar.make(findViewById(R.id.main), "Failed to fetch POIs (network)", Snackbar.LENGTH_LONG).show()
            }
            return@withContext emptyList()
        }
    }

    // Helper: produce a friendly category label for a POI based on tags
    private fun deriveCategory(tags: org.json.JSONObject?): String {
        if (tags == null) return "POI"
        val historic = tags.optString("historic").ifBlank { "" }
        val manMade = tags.optString("man_made").ifBlank { "" }
        val tourism = tags.optString("tourism").ifBlank { "" }
        val amenity = tags.optString("amenity").ifBlank { "" }
        val leisure = tags.optString("leisure").ifBlank { "" }

        if (historic.isNotBlank()) {
            return when (historic.lowercase()) {
                "memorial" -> "Memorial"
                "monument" -> "Monument"
                "archaeological_site" -> "Archaeological site"
                "ruins" -> "Ruins"
                "castle" -> "Castle"
                "fort" -> "Fort"
                "museum" -> "Museum"
                else -> "Historical: ${historic.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }}"
            }
        }
        if (manMade.isNotBlank()) {
            return "Landmark (${manMade.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }})"
        }
        if (tourism.isNotBlank()) return tourism.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }
        if (amenity.isNotBlank()) return amenity.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }
        if (leisure.isNotBlank()) return leisure.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }
        return "POI"
    }

    private suspend fun selectGeocodeResult(results: List<GeocodeResult>, title: String): GeocodeResult? = suspendCancellableCoroutine {
        continuation ->
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(results.map { it.displayName }.toTypedArray()) { _, which ->
                continuation.resume(results[which])
            }
            .setOnCancelListener { continuation.resume(null) }
            .show()
    }

    private suspend fun fetchRouteAndPois(start: GeoPoint?, dest: GeoPoint): Pair<List<GeoPoint>, List<Poi>> = withContext(Dispatchers.IO) {
        if (start == null) return@withContext Pair(emptyList(), emptyList())

        // Using OSRM demo server for routing. Replace with your own instance for production.
        val url = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}?overview=full&geometries=geojson&alternatives=false"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", packageName)
            .header("Accept", "application/json")
            .build()
        try {
            Log.d("MainActivity", "OSRM request: $url")
            val response = httpClient.newCall(request).execute()
            Log.d("MainActivity", "OSRM response code: ${response.code}")
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val truncated = if (body.length > 200) body.substring(0, 200) + "..." else body
                Log.d("MainActivity", "OSRM response body (truncated): $truncated")
                val (route, pois) = parseOsrmResponse(body)
                return@withContext Pair(route, pois)
            } else {
                return@withContext Pair(emptyList(), emptyList())
            }
        } catch (e: Exception) {
            Log.d("MainActivity", "OSRM error: ${e.message}")
            return@withContext Pair(emptyList(), emptyList())
        }
    }

    private fun parseOsrmResponse(body: String): Pair<List<GeoPoint>, List<Poi>> {
        try {
            val json = org.json.JSONObject(body)
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return Pair(emptyList(), emptyList())

            val routeObj = routes.getJSONObject(0)
            val geometry = routeObj.getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")

            val routePoints = mutableListOf<GeoPoint>()
            for (i in 0 until coords.length()) {
                val point = coords.getJSONArray(i)
                routePoints.add(GeoPoint(point.getDouble(1), point.getDouble(0)))
            }

            // For demonstration, we'll mock some POIs found along the route.
            // A real app might get these from the routing service or a separate POI database.
            val pois = mutableListOf<Poi>()
            // No mocked POIs: return only what the routing service provides. POIs along/near the route are fetched from Overpass.
            // Keep pois empty here; callers will merge route POIs (if any) and Overpass results.

            return Pair(routePoints, pois)
        } catch (_: org.json.JSONException) {
            return Pair(emptyList(), emptyList())
        }
    }

    // Geocode using Nominatim; cached with TTL and retry/backoff on transient errors
    private suspend fun geocodeAddresses(query: String): List<GeocodeResult> = withContext(Dispatchers.IO) {
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

                Log.d("MainActivity", "Geocode request (attempt $attempt): $url")
                val response = httpClient.newCall(request).execute()
                Log.d("MainActivity", "Geocode response code: ${response.code}")

                val code = response.code
                val body = response.body?.string() ?: ""
                val truncatedBody = if (body.length > 200) body.substring(0, 200) + "..." else body
                Log.d("MainActivity", "Geocode response body (truncated): $truncatedBody")

                if (code == 403) {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(findViewById(R.id.main), "Geocoding forbidden (invalid User-Agent). Update User-Agent with contact info.", Snackbar.LENGTH_LONG).show()
                    }
                    return@withContext emptyList()
                }

                if (code == 429 || code >= 500) {
                    response.close()
                    if (attempt < maxAttempts) {
                        Log.d("MainActivity", "Geocode will retry after $backoff ms (code $code)")
                        kotlinx.coroutines.delay(backoff)
                        backoff *= 2
                        continue
                    } else {
                        withContext(Dispatchers.Main) {
                            Snackbar.make(findViewById(R.id.main), "Geocoding failed: server returned $code", Snackbar.LENGTH_LONG).show()
                        }
                        return@withContext emptyList()
                    }
                }

                if (!response.isSuccessful) {
                    val rc = response.code
                    response.close()
                    withContext(Dispatchers.Main) {
                        Snackbar.make(findViewById(R.id.main), "Geocoding failed: server returned $rc", Snackbar.LENGTH_LONG).show()
                    }
                    return@withContext emptyList()
                }

                val results = mutableListOf<GeocodeResult>()
                val jsonArray = org.json.JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    results.add(GeocodeResult(
                        displayName = item.optString("display_name", "Unknown place"),
                        lat = item.optDouble("lat", 0.0),
                        lon = item.optDouble("lon", 0.0)
                    ))
                }
                geocodeCache[query] = CacheEntry(results, System.currentTimeMillis())
                return@withContext results

            } catch (e: Exception) {
                Log.d("MainActivity", "Geocode error (attempt $attempt): ${e.message}")
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(backoff)
                    backoff *= 2
                    continue
                } else {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(findViewById(R.id.main), "Geocoding error: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
                    return@withContext emptyList()
                }
            }
        }

        return@withContext emptyList()
    }

    // Fetch POIs along/near a polyline (routePoints).
    // This samples the route every `sampleDistMeters` and queries Overpass around each sample point with `radiusMeters`.
    private suspend fun generateRecommendationsAlongRoute(
        routePoints: List<GeoPoint>,
        sampleDistMeters: Int = 800,
        radiusMeters: Int = 500,
        maxSamples: Int = 25
    ): List<Poi> = withContext(Dispatchers.IO) {
         if (routePoints.isEmpty()) return@withContext emptyList()

         fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6371000.0 // meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return R * c
        }

        // sample points along the polyline approximately every sampleDistMeters
        val samples = mutableListOf<GeoPoint>()
        samples.add(routePoints.first())
        var acc = 0.0
        for (i in 1 until routePoints.size) {
            val a = routePoints[i - 1]
            val b = routePoints[i]
            val seg = haversine(a.latitude, a.longitude, b.latitude, b.longitude)
            acc += seg
            if (acc >= sampleDistMeters) {
                samples.add(b)
                acc = 0.0
            }
        }
        samples.add(routePoints.last())

        // If we have too many samples, downsample evenly to keep the Overpass query reasonable
        if (samples.size > maxSamples) {
            Log.d("MainActivity", "Downsampling samples from ${samples.size} to $maxSamples")
            val down = mutableListOf<GeoPoint>()
            val step = samples.size.toDouble() / maxSamples.toDouble()
            var idx = 0.0
            repeat(maxSamples) {
                val pick = samples[minOf(samples.size - 1, idx.toInt())]
                down.add(pick)
                idx += step
            }
            // ensure last point present
            if (down.last() != samples.last()) down[down.size - 1] = samples.last()
            samples.clear()
            samples.addAll(down)
        }

        // Build Overpass QL with node(around:...) for each sample and tag set
        val sb = StringBuilder()
        sb.append("[out:json][timeout:25];\n(")
        for (s in samples) {
            val lat = s.latitude
            val lon = s.longitude
            sb.append("node(around:$radiusMeters,$lat,$lon)[amenity];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[amenity];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[amenity];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[tourism];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[tourism];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[tourism];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[historic];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[historic];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[historic];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[leisure];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[leisure];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[leisure];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[man_made];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[man_made];\n")
            sb.append("relation(around:$radiusMeters,$lat,$lon)[man_made];\n")
        }
        sb.append(")\nout center 200;")
        val ql = sb.toString()

        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
            .header("Accept", "application/json")
            .build()

        try {
            Log.d("MainActivity", "Overpass route request: samples=${samples.size} radius=$radiusMeters")
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d("MainActivity", "Overpass route response code: ${response.code}")
                response.close()
                return@withContext emptyList()
            }
            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return@withContext emptyList()

            val pois = mutableListOf<Poi>()
            val seen = mutableSetOf<Long>()
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val id = el.optLong("id", -1L)
                if (id == -1L || seen.contains(id)) continue
                seen.add(id)
                var elLat = el.optDouble("lat", Double.NaN)
                var elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) {
                    val center = el.optJSONObject("center")
                    if (center != null) {
                        elLat = center.optDouble("lat", Double.NaN)
                        elLon = center.optDouble("lon", Double.NaN)
                      }
                }
                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name") ?: tags?.optString("official_name") ?: "Unknown"
                val category = deriveCategory(tags)
                val desc = tags?.optString("description") ?: tags?.optString("operator") ?: ""

                // Skip blank/unknown names and surveillance cameras
                val nameTrim = name.trim()
                if (nameTrim.isBlank() || nameTrim.equals("Unknown", ignoreCase = true)) continue
                if (isSurveillance(tags)) continue

                if (!elLat.isNaN() && !elLon.isNaN()) {
                    pois.add(Poi(nameTrim, category, desc, elLat, elLon))
                }
             }

            Log.d("MainActivity", "Overpass route returned ${pois.size} POIs before sorting")

            // compute distance of each POI to the route (min distance to sampled routePoints) and sort
            fun minDistToRoute(lat: Double, lon: Double): Double {
                var minD = Double.MAX_VALUE
                for (rp in routePoints) {
                    val d = haversine(lat, lon, rp.latitude, rp.longitude)
                    if (d < minD) minD = d
                }
                return minD
            }

            // Filter POIs to only those within 100m of the route
            val poisWithDist = pois.map { poi -> Pair(poi, minDistToRoute(poi.lat ?: 0.0, poi.lon ?: 0.0)) }
                .filter { it.second <= 100.0 }
                .sortedBy { it.second }
                .map { it.first }

            val limited = if (poisWithDist.size > 50) poisWithDist.subList(0, 50) else poisWithDist
            Log.d("MainActivity", "Returning ${limited.size} POIs after filtering/sorting/limiting")
            return@withContext limited
         } catch (e: Exception) {
             Log.d("MainActivity", "Overpass route error: ${e.message}")
             withContext(Dispatchers.Main) {
                 Snackbar.make(findViewById(R.id.main), "Failed to fetch POIs along route (network)", Snackbar.LENGTH_LONG).show()
             }
             return@withContext emptyList()
         }
     }

    // Debug helper: run a small Overpass query at a point and log the raw response size and first element names
    private suspend fun debugOverpassAt(center: GeoPoint, radiusMeters: Int = 1000) = withContext(Dispatchers.IO) {
        val lat = center.latitude
        val lon = center.longitude
        val ql = """
+[out:json][timeout:10];
+(
+  node(around:$radiusMeters,$lat,$lon)[amenity];
+  node(around:$radiusMeters,$lat,$lon)[tourism];
+  node(around:$radiusMeters,$lat,$lon)[historic];
+  node(around:$radiusMeters,$lat,$lon)[leisure];
+  node(around:$radiusMeters,$lat,$lon)[man_made];
+);
+out center 20;
+""".trimIndent()
        val qlClean = ql.lines().joinToString("\n") { it.trimStart('+') }
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = qlClean.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
            .header("Accept", "application/json")
            .build()
        try {
            Log.d("MainActivity", "Debug Overpass test: lat=$lat lon=$lon r=$radiusMeters")
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            Log.d("MainActivity", "Debug Overpass response code: ${response.code}, length=${body.length}")
            if (body.isNotBlank()) {
                val json = org.json.JSONObject(body)
                val elements = json.optJSONArray("elements")
                val names = mutableListOf<String>()
                if (elements != null) {
                    for (i in 0 until minOf(5, elements.length())) {
                        val el = elements.getJSONObject(i)
                        val tags = el.optJSONObject("tags")
                        names.add(tags?.optString("name") ?: "<no name>")
                    }
                }
                Log.d("MainActivity", "Debug Overpass sample names: ${names.joinToString(", ")}")
            }
        } catch (e: Exception) {
            Log.d("MainActivity", "Debug Overpass error: ${e.message}")
            withContext(Dispatchers.Main) {
                Snackbar.make(findViewById(R.id.main), "Debug Overpass failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // Helper: detect surveillance/CCTV-like POIs by scanning tags for camera/cctv/surveillance/monitor
    private fun isSurveillance(tags: org.json.JSONObject?): Boolean {
        if (tags == null) return false
        // Check some well-known keys first
        val checkKeys = listOf("surveillance", "camera", "monitoring", "security", "man_made")
        for (k in checkKeys) {
            val v = tags.optString(k).ifBlank { "" }.lowercase()
            if (v.contains("camera") || v.contains("cctv") || v.contains("surveillance") || v.contains("monitor")) return true
        }
        // Fallback: check all tag values
        val names = tags.names()
        if (names != null) {
            for (i in 0 until names.length()) {
                val key = names.getString(i)
                val v = tags.optString(key).ifBlank { "" }.lowercase()
                if (v.contains("camera") || v.contains("cctv") || v.contains("surveillance") || v.contains("monitor")) return true
            }
        }
        return false
    }

    // Helper: basic check for Poi list filtering (blank/unknown names)
    private fun keepPoi(p: Poi): Boolean {
        val n = p.name.trim()
        if (n.isBlank()) return false
        if (n.equals("Unknown", ignoreCase = true)) return false
        return true
    }

    // Helper to create a stable key for a POI used in poiMarkerMap
    private fun poiKey(p: Poi): String {
        val lat = p.lat ?: 0.0
        val lon = p.lon ?: 0.0
        // include the name to distinguish POIs at same coords
        return "%f_%f_%s".format(Locale.ROOT, lat, lon, p.name)
    }
}
