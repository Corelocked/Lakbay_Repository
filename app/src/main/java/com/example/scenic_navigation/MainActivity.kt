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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import kotlin.coroutines.resume

import java.util.LinkedHashMap

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private val REQ_LOCATION = 100
    private var lastLocation: Location? = null
    private var map: MapView? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null

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
            prefs.edit().putBoolean(askedKey, true).apply()
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
                for (p in routePois) recommendations.add(p)
                recommendations.addAll(generateRecommendations(startInput, destInput))
                rvRecs.adapter = PoiAdapter(recommendations)

                // Clear existing overlays then re-add myLocationOverlay if present
                val overlays = map?.overlays
                overlays?.clear()
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

    @Suppress("UNUSED_PARAMETER")
    private fun generateRecommendations(start: String, dest: String): List<Poi> {
        // Minimal mocked dataset. In a real app you'd call a routing API and POI service.
        val dataset = listOf(
            Poi("Ocean View Point", "Scenic", "A beautiful overlook with ocean views.", 37.7749, -122.4194),
            Poi("Old Town Market", "Cultural", "Historic market with local crafts and food.", 37.8044, -122.2711),
            Poi("Riverside Inn", "Hotel", "Comfortable stay near the river.", 37.6879, -122.4702),
            Poi("Sunset Diner", "Restaurant", "Local diner known for sunset views.", 37.7599, -122.4148),
            Poi("Artisan Museum", "Cultural", "Small museum showcasing regional art.", 37.8008, -122.4098),
            Poi("Vista Trail", "Scenic", "Short hiking trail with panoramic vistas.", 37.8651, -122.2545)
        )

        // Very simple ranking: mix scenic first, then culture, then food/hotel
        return dataset.sortedWith(compareBy({ it.category != "Scenic" }, { it.category }))
    }

    // Return multiple candidate geocoding results from Nominatim
    private suspend fun geocodeAddresses(address: String): List<GeocodeResult> {
        // check cache first (and TTL)
        synchronized(geocodeCache) {
            geocodeCache[address]?.let { entry ->
                if (System.currentTimeMillis() - entry.timestamp <= GEOCODE_TTL_MS) {
                    return entry.results
                } else {
                    geocodeCache.remove(address)
                }
            }
        }
        val urlString = "https://nominatim.openstreetmap.org/search?format=json&q=${Uri.encode(address)}"
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(urlString)
                    .header("User-Agent", packageName)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val body = resp.body?.string() ?: return@withContext emptyList()
                    val results = JSONArray(body)
                    val out = mutableListOf<GeocodeResult>()
                    for (i in 0 until results.length()) {
                        val obj = results.getJSONObject(i)
                        val lat = obj.getDouble("lat")
                        val lon = obj.getDouble("lon")
                        val display = obj.optString("display_name", "$lat,$lon")
                        out.add(GeocodeResult(display, lat, lon))
                    }
                    // cache the results for this query
                    synchronized(geocodeCache) {
                        geocodeCache[address] = CacheEntry(out, System.currentTimeMillis())
                    }
                    return@withContext out
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext emptyList()
            }
        }
    }

    // Fetch route via OSRM and then POIs along the route via Overpass API
    private suspend fun fetchRouteAndPois(start: GeoPoint?, dest: GeoPoint?): Pair<List<GeoPoint>, List<Poi>> {
        if (dest == null) return Pair(emptyList(), emptyList())
        return withContext(Dispatchers.IO) {
            try {
                val routePoints = mutableListOf<GeoPoint>()
                if (start != null) {
                    // call OSRM public demo server
                    val url = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}?overview=full&geometries=geojson"
                    val req = Request.Builder().url(url).header("User-Agent", packageName).get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string() ?: ""
                            val root = org.json.JSONObject(body)
                            val routes = root.optJSONArray("routes")
                            if (routes != null && routes.length() > 0) {
                                val r0 = routes.getJSONObject(0)
                                val geom = r0.getJSONObject("geometry")
                                val coords = geom.getJSONArray("coordinates")
                                for (i in 0 until coords.length()) {
                                    val pair = coords.getJSONArray(i)
                                    val lon = pair.getDouble(0)
                                    val lat = pair.getDouble(1)
                                    routePoints.add(GeoPoint(lat, lon))
                                }
                            }
                        }
                    }
                }

                // If no start (only dest), we won't request route; but still try to find POIs around dest
                val samplePoints = if (routePoints.isNotEmpty()) {
                    // sample up to 6 points along route evenly
                    val step = Math.max(1, routePoints.size / 6)
                    routePoints.filterIndexed { idx, _ -> idx % step == 0 }
                } else {
                    listOf(dest)
                }

                // Build overpass query by combining around clauses for selected sample points
                val radius = 1000 // meters
                val sb = StringBuilder()
                sb.append("[out:json][timeout:25];(\n")
                for (p in samplePoints) {
                    // scenic / tourism
                    sb.append("node[\"tourism\"~\"viewpoint|attraction|museum\"](around:$radius,${p.latitude},${p.longitude});\n")
                    // historic
                    sb.append("node[\"historic\"](around:$radius,${p.latitude},${p.longitude});\n")
                    // amenities: restaurant, hotel
                    sb.append("node[\"amenity\"~\"restaurant|hotel\"](around:$radius,${p.latitude},${p.longitude});\n")
                }
                sb.append(");out center 200;\n")

                val overpassUrl = "https://overpass-api.de/api/interpreter"
                val body = sb.toString().toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val overpassReq = Request.Builder().url(overpassUrl).post(body).header("User-Agent", packageName).build()
                val pois = mutableListOf<Poi>()
                httpClient.newCall(overpassReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val root = org.json.JSONObject(body)
                        val elements = root.optJSONArray("elements")
                        if (elements != null) {
                            for (i in 0 until elements.length()) {
                                val el = elements.getJSONObject(i)
                                val lat = el.optDouble("lat", Double.NaN)
                                val lon = el.optDouble("lon", Double.NaN)
                                val tags = el.optJSONObject("tags")
                                val name = tags?.optString("name") ?: tags?.optString("description") ?: "POI"
                                var category = "Scenic"
                                if (tags != null) {
                                    when {
                                        tags.has("amenity") -> {
                                            val a = tags.getString("amenity")
                                            category = when (a) {
                                                "restaurant" -> "Restaurant"
                                                "hotel" -> "Hotel"
                                                else -> "Food"
                                            }
                                        }
                                        tags.has("tourism") -> category = "Scenic"
                                        tags.has("historic") -> category = "Cultural"
                                    }
                                }
                                if (!lat.isNaN() && !lon.isNaN()) {
                                    pois.add(Poi(name, category, "From OpenStreetMap", lat, lon))
                                }
                            }
                        }
                    }
                }

                return@withContext Pair(routePoints, pois)
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext Pair(emptyList(), emptyList())
            }
        }
    }

    // Show a blocking (suspend) single-choice dialog on the main thread to select a geocode candidate
    private suspend fun selectGeocodeResult(results: List<GeocodeResult>, title: String): GeocodeResult? {
        return suspendCancellableCoroutine { cont ->
            val items = results.map { it.displayName }.toTypedArray()
            var selectedIndex = 0
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, 0) { _, which -> selectedIndex = which }
                .setPositiveButton("Select") { d, _ ->
                    d.dismiss()
                    if (!cont.isCancelled) cont.resume(results[selectedIndex])
                }
                .setNegativeButton("Cancel") { d, _ ->
                    d.dismiss()
                    if (!cont.isCancelled) cont.resume(null)
                }
                .setOnCancelListener {
                    if (!cont.isCancelled) cont.resume(null)
                }
                .create()

            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    override fun onResume() {
        super.onResume()
        map?.onResume()
    }

    override fun onPause() {
        super.onPause()
        map?.onPause()
    }
}