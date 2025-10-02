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

                        // Add a click listener to show POI details in a dialog
                        marker.setOnMarkerClickListener { m, _ ->
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(m.title)
                                .setMessage(m.subDescription)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                            true // true indicates that we have handled the event
                        }
                        
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
  