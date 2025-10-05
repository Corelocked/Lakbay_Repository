package com.example.scenic_navigation

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
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
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
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
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.osmdroid.util.BoundingBox

// Import models from the centralized models package
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.models.ScenicPoi
import com.example.scenic_navigation.models.ScenicMunicipality
import com.example.scenic_navigation.models.RecommendationItem
import com.example.scenic_navigation.models.GeocodeResult
import com.example.scenic_navigation.models.CoastalSegment
import com.example.scenic_navigation.models.Waypoint
import com.example.scenic_navigation.models.RoadTripSegment
import com.example.scenic_navigation.models.RoadTripPlan

// Material components used in bottom sheet
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton

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

    // Track current route polyline and markers
    private var currentRoutePolyline: Polyline? = null
    private val currentRouteMarkers = mutableListOf<Marker>()

    // Store the original destination when planning the route
    private var originalDestination: GeoPoint? = null
    // Adapter promoted to property so other functions (scenic planning) can update it
    private lateinit var poiAdapter: PoiAdapter
    // Status / progress view
    private var statusView: TextView? = null
    // Bottom sheet behavior reference (used to hide sheet on selection)
    private lateinit var sheetBehavior: BottomSheetBehavior<View>
    // Overpass rate limiting
    private var lastOverpassCallTime = 0L
    private val OVERPASS_MIN_INTERVAL_MS = 1000L
    // Clustering overlays - simplified custom implementation
    private val poiMarkers = mutableListOf<Marker>()
    private val scenicMarkers = mutableListOf<Marker>()

    // Data classes for road trip planning
    data class Waypoint(
        val geoPoint: GeoPoint,
        val name: String,
        val estimatedStayDuration: Long = 30 * 60 * 1000L, // 30 minutes in milliseconds
        val priority: Int = 1, // 1-5, with 5 being highest priority
        val category: String = "poi",
        val openingHours: String? = null,
        val isOptional: Boolean = false
    )

    data class RoadTripSegment(
        val from: Waypoint,
        val to: Waypoint,
        val route: List<GeoPoint>,
        val distanceMeters: Double,
        val estimatedDurationMs: Long,
        val scenicScore: Double = 0.0
    )

    data class RoadTripPlan(
        val waypoints: List<Waypoint>,
        val segments: List<RoadTripSegment>,
        val totalDistanceMeters: Double,
        val totalDurationMs: Long,
        val totalScenicScore: Double,
        val startTime: Long? = null,
        val endTime: Long? = null
    )


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
        val switchScenicRoute = findViewById<SwitchCompat>(R.id.switch_scenic_route)
        val btnPlan = findViewById<Button>(R.id.btn_plan)
        // Inline (hidden) RecyclerView - kept as fallback
        val rvRecs = findViewById<RecyclerView>(R.id.rv_recommendations)
        // The sheet recycler view where recommendations are shown in the modal bottom sheet
        val sheetRv = findViewById<RecyclerView>(R.id.sheet_rv_recommendations)
        val progress = findViewById<ProgressBar>(R.id.progress_geocoding)
        val progressOverlay = findViewById<View>(R.id.progress_overlay)
        val statusCard = findViewById<View>(R.id.card_status)
        statusView = findViewById(R.id.tv_status)

        // Set default states: current location and scenic route enabled by default
        switchUseCurrent.isChecked = true
        switchScenicRoute.isChecked = true

        // Use the sheet RecyclerView for the adapter so recommendations show in the modal
        sheetRv.layoutManager = LinearLayoutManager(this)
        rvRecs.layoutManager = LinearLayoutManager(this)
        // hide the inline recommendations container (kept in layout for fallback)
        findViewById<View>(R.id.recs_container).visibility = View.GONE

        // Create a single adapter instance that can be updated later (prevents adapter recreation issues)
        poiAdapter = PoiAdapter(mutableListOf(),
            onPoiClick = { poi -> rerouteToPoi(poi) },
            onMunicipalityClick = { municipality ->
                val geo = GeoPoint(municipality.lat, municipality.lon)
                map?.controller?.setCenter(geo)
                map?.controller?.setZoom(14.0)
                // Optionally show a marker/info window for the municipality
                val marker = Marker(map)
                marker.position = geo
                marker.title = municipality.name
                marker.subDescription = "${municipality.type.replaceFirstChar { it.uppercase() }} Town\n" +
                    (municipality.population?.let { "Population: $it\n" } ?: "") +
                    (municipality.elevation?.let { "Elevation: ${"%.0f".format(it)}m" } ?: "")
                marker.icon = ResourcesCompat.getDrawable(resources,
                    when (municipality.type) {
                        "coastal" -> R.drawable.ic_coastal_town
                        "mountain" -> R.drawable.ic_mountain_town
                        else -> R.drawable.ic_town
                    }, theme)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map?.overlays?.add(marker)
                marker.showInfoWindow()
                map?.invalidate()
            }
        )

        // Attach adapter to the sheet RecyclerView (modal) so it behaves like a modal list
        sheetRv.adapter = poiAdapter
        rvRecs.adapter = poiAdapter // keep attached as fallback but hidden

        initClusters()

        // initialize MapView
        map = findViewById(R.id.map)
        map?.setTileSource(TileSourceFactory.MAPNIK)
        map?.setMultiTouchControls(true)

        // Prevent NestedScrollView from intercepting map touch events
        map?.setOnTouchListener { v, event ->
            // Request parent to not intercept touch events while we're touching the map
            v.parent.requestDisallowInterceptTouchEvent(true)

            // Handle the map touch normally
            false
        }

        val mapController = map?.controller
        mapController?.setZoom(10.0)
        // default center: if we already have location permission, use last-known location; otherwise fallback to (0,0)
        if (hasLocationPermission()) {
            lastLocation = fetchLastKnownLocation()
            if (lastLocation != null) {
                mapController?.setCenter(
                    GeoPoint(
                        lastLocation!!.latitude,
                        lastLocation!!.longitude
                    )
                )
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
            Snackbar.make(
                findViewById(R.id.main),
                "Allow location access to center the map and suggest nearby places",
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction("Allow") {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQ_LOCATION
                    )
                }
                .show()
        }

        // setup my-location overlay (we'll enable when permission exists)
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        // Convert Drawable to Bitmap for user location icon
        val userDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_user_arrow, theme)
        // Drawable may be nullable; only create bitmap and set person icon if available
        if (userDrawable != null) {
            val userBitmap = android.graphics.Bitmap.createBitmap(
                userDrawable.intrinsicWidth,
                userDrawable.intrinsicHeight,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(userBitmap)
            userDrawable.setBounds(0, 0, canvas.width, canvas.height)
            userDrawable.draw(canvas)
            myLocationOverlay?.setPersonIcon(userBitmap)
        }

        // Add CompassOverlay to the map
        val compassOverlay = CompassOverlay(this, InternalCompassOrientationProvider(this), map)
        compassOverlay.enableCompass()
        map?.overlays?.add(compassOverlay)

        switchUseCurrent.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // request location permission and fetch last known
                if (hasLocationPermission()) {
                    lastLocation = fetchLastKnownLocation()
                    if (lastLocation != null) {
                        etStart.setText(
                            String.format(
                                Locale.US,
                                "Current location (%.6f, %.6f)",
                                lastLocation!!.latitude,
                                lastLocation!!.longitude
                            )
                        )
                        // center map on current location
                        mapController?.setCenter(
                            GeoPoint(
                                lastLocation!!.latitude,
                                lastLocation!!.longitude
                            )
                        )
                        mapController?.setZoom(12.0)
                    } else {
                        etStart.setText(getString(R.string.current_location))
                    }
                    enableLocationOverlayIfPermitted()
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQ_LOCATION
                    )
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
            clearStatus()
            Snackbar.make(
                findViewById(R.id.main),
                getString(R.string.geocode_cancelled),
                Snackbar.LENGTH_SHORT
            ).show()
        }

        btnPlan.setOnClickListener {
            val useCurrent = switchUseCurrent.isChecked
            val useScenic = switchScenicRoute.isChecked
            val startInput = if (useCurrent) {
                lastLocation?.let { "${it.latitude},${it.longitude}" } ?: etStart.text.toString()
            } else {
                etStart.text.toString()
            }
            val destInput = etDestination.text.toString()
            if (destInput.isBlank()) {
                Snackbar.make(
                    findViewById(R.id.main),
                    getString(R.string.enter_destination),
                    Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            geocodeJob = lifecycleScope.launch {
                updateStatus("Planning route…")
                // show progress and disable inputs
                progress.visibility = View.VISIBLE
                btnPlan.isEnabled = false
                etDestination.isEnabled = false
                etStart.isEnabled = false
                switchUseCurrent.isEnabled = false
                switchScenicRoute.isEnabled = false
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
                            Snackbar.make(
                                findViewById(R.id.main),
                                "No geocoding results for start input (check network or spelling)",
                                Snackbar.LENGTH_LONG
                            ).show()
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
                            Snackbar.make(
                                findViewById(R.id.main),
                                "No geocoding results for destination (check network or spelling)",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                // Set original destination for rerouting
                originalDestination = destPoint

                if (destPoint == null) {
                    Snackbar.make(
                        findViewById(R.id.main),
                        "Could not geocode destination",
                        Snackbar.LENGTH_LONG
                    ).show()
                    // cleanup and re-enable UI
                    progress.visibility = View.GONE
                    btnCancel.visibility = View.GONE
                    btnPlan.isEnabled = true
                    etDestination.isEnabled = true
                    etStart.isEnabled = true
                    switchUseCurrent.isEnabled = true
                    switchScenicRoute.isEnabled = true
                    geocodeJob = null
                    return@launch
                }
                // Scenic route logic
                if (useScenic) {
                    updateStatus("Fetching scenic alternatives…")
                    // Try to fetch multiple route alternatives (uses OSRM alternatives=true)
                    Log.d("PolylineDebug", "[Scenic] Requesting route alternatives for scenic mode")
                    val alternatives = fetchRouteAlternatives(startPoint, destPoint).toMutableList()
                    Log.d(
                        "PolylineDebug",
                        "[Scenic] fetchRouteAlternatives returned ${alternatives.size} alternatives"
                    )
                    // If OSRM alternatives endpoint didn't return anything useful, fall back to single route
                    if (alternatives.isEmpty()) {
                        Log.d(
                            "PolylineDebug",
                            "[Scenic] No alternatives from OSRM, falling back to single route fetch"
                        )
                        val routeAndPois = fetchRouteAndPois(startPoint, destPoint)
                        if (routeAndPois.first.isNotEmpty()) {
                            alternatives.add(routeAndPois.first)
                        }
                    }

                    if (alternatives.isNotEmpty()) {
                        Log.d(
                            "PolylineDebug",
                            "[Scenic] Planning scenic route with ${alternatives.size} alternatives"
                        )
                        planScenicRoute(alternatives)
                        clearStatusDelayed()
                        // Hide progress and re-enable inputs
                        progress.visibility = View.GONE
                        btnCancel.visibility = View.GONE
                        btnPlan.isEnabled = true
                        etDestination.isEnabled = true
                        etStart.isEnabled = true
                        switchUseCurrent.isEnabled = true
                        switchScenicRoute.isEnabled = true
                        geocodeJob = null
                        return@launch
                    } else {
                        Log.d(
                            "PolylineDebug",
                            "[Scenic] Still no alternatives to plan scenic route"
                        )
                    }
                }
                // Default shortest route logic (existing)
                // Fetch route and POIs along the route (route-aware recommendations)
                Log.d("PolylineDebug", "Starting route calculation and polyline drawing...")
                updateStatus("Fetching base route…")
                val routeAndPois = fetchRouteAndPois(startPoint, destPoint)
                Log.d(
                    "PolylineDebug",
                    "fetchRouteAndPois returned: routePoints size=${routeAndPois.first.size}, routePois size=${routeAndPois.second.size}"
                )
                val routePoints = routeAndPois.first
                val routePois = routeAndPois.second
                if (routePoints.isNotEmpty()) {
                    Log.d(
                        "PolylineDebug",
                        "Drawing polyline with points: ${routePoints.map { "(${it.latitude},${it.longitude})" }}"
                    )
                    val line = Polyline()
                    line.setPoints(routePoints)
                    line.outlinePaint.color = Color.MAGENTA
                    line.outlinePaint.strokeWidth = 14.0f
                    map?.overlays?.add(line)
                    currentRoutePolyline = line
                    val mid = routePoints[routePoints.size / 2]
                    mapController?.setCenter(mid)
                    mapController?.setZoom(13.0)
                    map?.invalidate()
                    Log.d("PolylineDebug", "Polyline drawn and map invalidated.")
                } else {
                    updateStatus("Base route empty — fallback centering…")
                    Log.d(
                        "PolylineDebug",
                        "routePoints is empty, polyline not drawn. Logging OSRM response."
                    )
                    val routeAndPoisDebug = fetchRouteAndPois(startPoint, destPoint)
                    Log.d("PolylineDebug", "OSRM routeAndPois.first: ${routeAndPoisDebug.first}")
                    Log.d("PolylineDebug", "OSRM routeAndPois.second: ${routeAndPoisDebug.second}")
                    if (startPoint != null) {
                        mapController?.setCenter(startPoint)
                        mapController?.setZoom(8.0)
                    } else {
                        mapController?.setCenter(destPoint)
                        mapController?.setZoom(10.0)
                    }
                }

                // Merge route POIs into recommendations (route POIs first)
                val recommendations = mutableListOf<Poi>()
                for (p in routePois) if (keepPoi(p)) recommendations.add(p)
                // Fetch additional recommendations: prefer POIs along/near the route; fall back to destination-centered search
                var fetched = emptyList<Poi>()
                if (routePoints.isNotEmpty()) {
                    val searchConfigs = listOf(
                        Triple(800, 150, 150),
                        Triple(800, 250, 200),
                        Triple(800, 400, 250)
                    )
                    var attempt = 1
                    for ((sampleDist, overpassRadius, maxDistRoute) in searchConfigs) {
                        updateStatus("Along-route POIs attempt $attempt (${overpassRadius}m radius)…")
                        fetched = generateRecommendationsAlongRoute(
                            routePoints = routePoints,
                            sampleDistMeters = sampleDist,
                            radiusMeters = overpassRadius,
                            maxDistToRouteMeters = maxDistRoute
                        )
                        if (fetched.isNotEmpty()) break
                        attempt++
                    }
                    if (fetched.isEmpty()) {
                        // Extra expansion attempt (Feature C)
                        updateStatus("Expanding search radius (600m)…")
                        fetched = generateRecommendationsAlongRoute(
                            routePoints = routePoints,
                            sampleDistMeters = 800,
                            radiusMeters = 600,
                            maxSamples = 25,
                            maxDistToRouteMeters = 350
                        )
                    }
                    if (fetched.isEmpty()) {
                        updateStatus("Falling back to destination POIs…")
                        fetched = generateRecommendations(destPoint, 5000)
                    }
                } else {
                    updateStatus("Destination-centered POIs…")
                    fetched = generateRecommendations(destPoint, 5000)
                }

                // If route-based fetch returned none, try a destination-centered fallback with larger radius
                if (fetched.isEmpty() && routePoints.isNotEmpty()) {
                    Log.d(
                        "MainActivity",
                        "Route-based POIs empty, falling back to destination-centered search"
                    )
                    val fallback = generateRecommendations(destPoint, 10000)
                    if (fallback.isNotEmpty()) {
                        fetched = fallback
                        Log.d("MainActivity", "Fallback returned ${fetched.size} POIs")
                        withContext(Dispatchers.Main) {
                            Snackbar.make(
                                findViewById(R.id.main),
                                "No POIs found along route — showing ${fetched.size} near destination",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                Log.d("MainActivity", "routePois=${routePois.size}, fetched=${fetched.size}")
                if (fetched.isNotEmpty()) {
                    updateStatus("Integrating ${fetched.size} POIs…")
                    // Log first few names for easier debugging in Logcat
                    val firstNames = fetched.take(5).joinToString(", ") { it.name }
                    Log.d("MainActivity", "Sample POIs: $firstNames")
                    withContext(Dispatchers.Main) {
                        Snackbar.make(
                            findViewById(R.id.main),
                            "Found ${fetched.size} POIs (route: ${routePois.size})",
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                } else {
                    updateStatus("No POIs found after retries")
                    withContext(Dispatchers.Main) {
                        Snackbar.make(
                            findViewById(R.id.main),
                            "No POIs found (route:${routePois.size}). Try increasing radius or check network.",
                            Snackbar.LENGTH_LONG
                        ).show()
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
                initClusters()

                // Add POI markers for route POIs (and for mocked ones included later)
                for (poi in recommendations) {
                    if (poi.lat != null && poi.lon != null) {
                        val marker = Marker(map)
                        marker.position = GeoPoint(poi.lat, poi.lon)
                        marker.title = poi.name
                        marker.subDescription = poi.description
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        val cat = poi.category.lowercase(Locale.ROOT)
                        val iconRes = when {
                            cat.contains("restaurant") || cat.contains("cafe") || cat.contains("food") || cat.contains("bar") || cat.contains("pub") || cat.contains("bakery") -> R.drawable.ic_food_marker
                            cat.contains("bank") || cat.contains("atm") || cat.contains("cash") -> R.drawable.ic_bank
                            cat.contains("monument") || cat.contains("memorial") || cat.contains("castle") || cat.contains("museum") || cat.contains("archaeological") || cat.contains("ruins") || cat.contains("fort") -> R.drawable.ic_monument
                            cat.contains("destination") -> R.drawable.ic_pin_red
                            cat.contains("selected") -> R.drawable.ic_pin_green
                            cat.contains("park") || cat.contains("tree") || cat.contains("natural") || cat.contains("leisure") || cat.contains("playground") -> R.drawable.ic_tree
                            cat.contains("medical") || cat.contains("hospital") || cat.contains("clinic") || cat.contains("pharmacy") -> R.drawable.ic_medical
                            cat.contains("gas") || cat.contains("fuel") || cat.contains("petrol") || cat.contains("service_station") -> R.drawable.ic_gas
                            cat.contains("train") || cat.contains("station") -> R.drawable.ic_train
                            cat.contains("airport") || cat.contains("plane") || cat.contains("airfield") -> R.drawable.ic_plane
                            cat.contains("grocery") || cat.contains("mall") || cat.contains("supermarket") || cat.contains("shopping") || cat.contains("store") -> R.drawable.ic_cart
                            cat.contains("veterinary") || cat.contains("vet") || cat.contains("animal") || cat.contains("pet") -> R.drawable.ic_paw
                            cat.contains("scenic") || cat.contains("viewpoint") || cat.contains("landmark") || cat.contains("tourism") || cat.contains("attraction") || cat.contains("hotel") -> R.drawable.ic_scenic_marker
                            cat.contains("user") || cat.contains("arrow") -> R.drawable.ic_user_arrow
                            else -> R.drawable.ic_launcher_foreground
                        }
                        marker.icon = ResourcesCompat.getDrawable(resources, iconRes, theme)
                        poiMarkers.add(marker)
                        poiMarkerMap[poiKey(poi)] = marker
                        currentRouteMarkers.add(marker)
                    }
                }

                // Draw route polyline if available
                if (routePoints.isNotEmpty()) {
                    Log.d("PolylineDebug", "Drawing polyline with ${routePoints.size} points.")
                    val line = Polyline()
                    line.setPoints(routePoints)
                    line.outlinePaint.color = Color.MAGENTA // Even more visible
                    line.outlinePaint.strokeWidth = 14.0f // Very thick line
                    map?.overlays?.add(line)
                    currentRoutePolyline = line // Track polyline for removal

                    // center to middle of route
                    val mid = routePoints[routePoints.size / 2]
                    mapController?.setCenter(mid)
                    mapController?.setZoom(13.0) // Zoom in more
                    map?.invalidate()
                } else {
                    Log.d("PolylineDebug", "routePoints is empty, polyline not drawn.")
                    if (startPoint != null) {
                        mapController?.setCenter(startPoint)
                        mapController?.setZoom(8.0)
                    } else {
                        mapController?.setCenter(destPoint)
                        mapController?.setZoom(10.0)
                    }
                }

                // Now that markers are present, update adapter so clicks can show corresponding marker info
                // Previously we recreated the adapter here; now update the single adapter instance created above
                withContext(Dispatchers.Main) {
                    // Convert POI list to RecommendationItem list
                    val recommendationItems = recommendations.map { poi ->
                        RecommendationItem.PoiItem(poi)
                    }
                    poiAdapter.updateItems(recommendationItems)
                    // Open the recommendations sheet so the user sees the modal list
                    try { if (::sheetBehavior.isInitialized) sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED } catch (_: Exception) {}
                }

                // hide progress and re-enable inputs
                progress.visibility = View.GONE
                btnCancel.visibility = View.GONE
                btnPlan.isEnabled = true
                etDestination.isEnabled = true
                etStart.isEnabled = true
                switchUseCurrent.isEnabled = true
                switchScenicRoute.isEnabled = true
                geocodeJob = null
            }
        }

        // Removed obsolete legend handling (poi_legend was deleted)

        // Bottom sheet behavior setup: toggleable modal recommendations
        val bottomSheetView = findViewById<View>(R.id.bottom_sheet)
        val nestedScroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.nested_scroll)
        sheetBehavior = BottomSheetBehavior.from(bottomSheetView)
        sheetBehavior.isHideable = true
        sheetBehavior.peekHeight = 0
        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        // FAB toggle
        val fab = findViewById<FloatingActionButton>(R.id.fab_toggle_recs)
        fab.setOnClickListener {
            if (sheetBehavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            }
        }

        // Close button in the sheet
        val btnClose = findViewById<Button>(R.id.btn_close_sheet)
        btnClose.setOnClickListener { sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN }

        // Monitor sheet state to enable/disable map and scrolling when sheet is visible
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED,
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        // Disable interactions with the map and background scrolling
                        try { map?.setMultiTouchControls(false) } catch (_: Exception) {}
                        map?.isEnabled = false
                        map?.isClickable = false
                        nestedScroll.isEnabled = false
                    }
                    BottomSheetBehavior.STATE_HIDDEN,
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        // Re-enable map interactions and background scrolling
                        try { map?.setMultiTouchControls(true) } catch (_: Exception) {}
                        map?.isEnabled = true
                        map?.isClickable = true
                        nestedScroll.isEnabled = true
                    }
                    else -> { /* no-op */ }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // Optionally dim the map or animate elements based on slide offset
                try {
                    progressOverlay.alpha = (0.0f + (0.35f * slideOffset))
                    progressOverlay.visibility = if (slideOffset > 0f) View.VISIBLE else View.GONE
                } catch (_: Exception) { }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Always enable and add MyLocationNewOverlay if permitted
        enableLocationOverlayIfPermitted()
    }

    private fun enableLocationOverlayIfPermitted() {
        if (hasLocationPermission()) {
            myLocationOverlay?.enableMyLocation()
            map?.overlays?.let { overlayList ->
                if (myLocationOverlay != null && !overlayList.contains(myLocationOverlay)) {
                    overlayList.add(myLocationOverlay)
                }
            }
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
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun fetchLastKnownLocation(): Location? {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val l = lm.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        return bestLocation
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                lastLocation = fetchLastKnownLocation()
                // update start EditText if available
                val etStart = findViewById<EditText>(R.id.et_start)
                if (lastLocation != null) {
                    etStart.setText(
                        String.format(
                            Locale.US,
                            getString(R.string.current_location_fmt),
                            lastLocation!!.latitude,
                            lastLocation!!.longitude
                        )
                    )
                    // center map
                    map?.controller?.setCenter(
                        GeoPoint(
                            lastLocation!!.latitude,
                            lastLocation!!.longitude
                        )
                    )
                    map?.controller?.setZoom(12.0)
                } else {
                    etStart.setText(getString(R.string.current_location))
                }
                enableLocationOverlayIfPermitted()
            } else {
                Snackbar.make(
                    findViewById(R.id.main),
                    getString(R.string.location_permission_denied),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Fetch POIs near a center point using Overpass API. Returns empty list if center is null or on error.
    private suspend fun generateRecommendations(
        center: GeoPoint?,
        radiusMeters: Int = 5000
    ): List<Poi> = withContext(Dispatchers.IO) {
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
                Snackbar.make(
                    findViewById(R.id.main),
                    "Failed to fetch POIs (network)",
                    Snackbar.LENGTH_LONG
                ).show()
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
                else -> "Historical: ${
                    historic.replace('_', ' ')
                        .replaceFirstChar { it.uppercase(Locale.getDefault()) }
                }"
            }
        }
        if (manMade.isNotBlank()) {
            return "Landmark (${
                manMade.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.getDefault()) }
            })"
        }
        if (tourism.isNotBlank()) return tourism.replace('_', ' ')
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        if (amenity.isNotBlank()) return amenity.replace('_', ' ')
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        if (leisure.isNotBlank()) return leisure.replace('_', ' ')
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        return "POI"
    }

    private suspend fun selectGeocodeResult(
        results: List<GeocodeResult>,
        title: String
    ): GeocodeResult? = suspendCancellableCoroutine { continuation ->
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(results.map { it.displayName }.toTypedArray()) { _, which ->
                continuation.resume(results[which])
            }
            .setOnCancelListener { continuation.resume(null) }
            .show()
    }

    private suspend fun fetchRouteAndPois(
        start: GeoPoint?,
        dest: GeoPoint
    ): Pair<List<GeoPoint>, List<Poi>> = withContext(Dispatchers.IO) {
        if (start == null) return@withContext Pair(emptyList(), emptyList())

        // Using OSRM demo server for routing. Replace with your own instance for production.
        val url =
            "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}?overview=full&geometries=geojson&alternatives=false"

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

    private suspend fun fetchRouteAlternatives(
        start: GeoPoint?,
        dest: GeoPoint
    ): List<List<GeoPoint>> = withContext(Dispatchers.IO) {
        if (start == null) return@withContext emptyList()
        val url =
            "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}?overview=full&geometries=geojson&alternatives=true"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", packageName)
            .header("Accept", "application/json")
            .build()
        try {
            Log.d("MainActivity", "OSRM request (alternatives): $url")
            val response = httpClient.newCall(request).execute()
            Log.d("MainActivity", "OSRM response code: ${response.code}")
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val truncated = if (body.length > 200) body.substring(0, 200) + "..." else body
                Log.d("MainActivity", "OSRM response body (truncated): $truncated")
                val alternatives = parseOsrmAlternatives(body).toMutableList()

                // If we only got 1 alternative, try to generate coastal alternatives via waypoints
                if (alternatives.size <= 1) {
                    Log.d("CoastalRouting", "Only ${alternatives.size} alternatives from OSRM, generating coastal route via waypoints")
                    val coastalRoute = generateCoastalRouteViaWaypoints(start, dest)
                    if (coastalRoute.isNotEmpty()) {
                        alternatives.add(coastalRoute)
                        Log.d("CoastalRouting", "Added coastal route alternative with ${coastalRoute.size} points")
                    }
                }

                return@withContext alternatives
            } else {
                return@withContext emptyList()
            }
        } catch (e: Exception) {
            Log.d("MainActivity", "OSRM error: ${e.message}")
            return@withContext emptyList()
        }
    }

    // Generate a coastal route by routing through strategic coastal waypoints
    private suspend fun generateCoastalRouteViaWaypoints(start: GeoPoint, dest: GeoPoint): List<GeoPoint> = withContext(Dispatchers.IO) {
        // Define strategic coastal waypoints for major Philippine routes
        val coastalWaypoints = mapOf(
            // LUZON ROUTES
            // Manila/NCR to Northern Luzon (Aparri, Cagayan) - Western Ilocos coastal route
            "luzon_north" to listOf(
                GeoPoint(15.7800, 120.2800), // Zambales coast
                GeoPoint(16.0500, 120.3300), // La Union coast
                GeoPoint(17.5800, 120.3800), // Ilocos Norte coast
                GeoPoint(18.1700, 120.5900)  // Northern tip before going to Aparri
            ),
            // Manila to Bicol - Eastern Luzon Pacific coast
            "luzon_bicol_east" to listOf(
                GeoPoint(14.6000, 121.2000), // Laguna
                GeoPoint(14.1700, 121.6200), // Quezon coast
                GeoPoint(13.6200, 122.0100), // Camarines Norte coast
                GeoPoint(13.4200, 123.4100)  // Camarines Sur/Albay coast
            ),
            // Manila to Bicol - Western route via Batangas
            "luzon_bicol_west" to listOf(
                GeoPoint(14.0100, 120.9800), // Batangas coast
                GeoPoint(13.7500, 120.9500), // Mindoro Strait view
                GeoPoint(13.4200, 123.4100)  // Join at Albay
            ),

            // VISAYAS ROUTES
            // Cebu coastal circuit
            "visayas_cebu_coastal" to listOf(
                GeoPoint(10.3200, 123.7500), // Northern Cebu coast
                GeoPoint(10.6900, 124.0000), // Malapascua area
                GeoPoint(10.3200, 123.7500), // Cebu City
                GeoPoint(9.8600, 123.4000)   // Southern Cebu coast
            ),
            // Panay Island coastal route (Boracay, Iloilo, Aklan)
            "visayas_panay_coastal" to listOf(
                GeoPoint(11.9600, 122.0100), // Aklan/Boracay area
                GeoPoint(11.0000, 122.5500), // Iloilo coast
                GeoPoint(10.6900, 122.5700)  // Guimaras Strait
            ),
            // Bohol coastal route
            "visayas_bohol_coastal" to listOf(
                GeoPoint(9.8500, 124.4300), // Tagbilaran/Panglao
                GeoPoint(10.1500, 124.2000), // Northern Bohol coast
                GeoPoint(9.6500, 124.5000)   // Eastern Bohol coast
            ),
            // Leyte-Samar coastal route
            "visayas_leyte_samar" to listOf(
                GeoPoint(11.2500, 125.0000), // Samar west coast
                GeoPoint(11.0000, 124.9500), // San Juanico Strait
                GeoPoint(10.7200, 124.8400), // Tacloban area
                GeoPoint(10.3900, 125.0100)  // Southern Leyte coast
            ),

            // MINDANAO ROUTES
            // Davao to General Santos - Southern Mindanao Pacific coast
            "mindanao_south_pacific" to listOf(
                GeoPoint(6.9000, 125.6100), // Davao coastal
                GeoPoint(6.5000, 125.2000), // Davao del Sur coast
                GeoPoint(6.1100, 125.1700)  // General Santos coastal approach
            ),
            // Northern Mindanao coastal (Cagayan de Oro to Butuan)
            "mindanao_north_coast" to listOf(
                GeoPoint(8.4800, 124.6500), // Cagayan de Oro coastal
                GeoPoint(8.8000, 125.1000), // Misamis Oriental coast
                GeoPoint(8.9500, 125.5400)  // Butuan Bay approach
            ),
            // Western Mindanao - Zamboanga peninsula coastal
            "mindanao_west_zamboanga" to listOf(
                GeoPoint(8.0000, 123.5000), // Lanao del Norte coast
                GeoPoint(7.8000, 123.2000), // Misamis Occidental coast
                GeoPoint(7.3000, 122.7000), // Zamboanga del Norte coast
                GeoPoint(6.9100, 122.0700)  // Zamboanga City coastal
            ),
            // Surigao coastal route
            "mindanao_surigao_coast" to listOf(
                GeoPoint(9.7800, 125.4900), // Surigao City
                GeoPoint(9.5000, 125.7000), // Eastern Surigao coast
                GeoPoint(9.0000, 126.0000)  // Pacific coast route
            ),

            // INTER-ISLAND ROUTES
            // Manila to Cebu via coastal route
            "luzon_visayas_manila_cebu" to listOf(
                GeoPoint(14.0100, 120.9800), // Batangas coast
                GeoPoint(13.4000, 121.3000), // Mindoro coast
                GeoPoint(12.5000, 121.9000), // Romblon area
                GeoPoint(11.2500, 123.2500), // Negros coast
                GeoPoint(10.3200, 123.7500)  // Cebu
            ),
            // Manila to Davao via coastal route
            "luzon_mindanao_manila_davao" to listOf(
                GeoPoint(13.4200, 123.4100), // Bicol
                GeoPoint(12.5000, 124.0000), // Samar coast
                GeoPoint(11.0000, 125.0000), // Leyte coast
                GeoPoint(10.3900, 125.0100), // Southern Leyte
                GeoPoint(9.0000, 125.5000),  // Surigao coast
                GeoPoint(7.5000, 126.0000),  // Eastern Mindanao coast
                GeoPoint(6.9000, 125.6100)   // Davao
             )
        )

        // Determine which waypoint set to use based on start/destination
        val startLat = start.latitude
        val destLat = dest.latitude
        val startLon = start.longitude
        val destLon = dest.longitude

        val waypoints = when {
            // LUZON ROUTES
            // Northern Luzon routes - Manila/NCR to Ilocos/Cagayan
             startLat < 15.0 && destLat > 17.5 && startLon > 120.5 && destLon > 121.0 -> {
                 Log.d("CoastalRouting", "Detected Manila to Northern Luzon route - using Ilocos coastal waypoints")
                 coastalWaypoints["luzon_north"]
             }
            // Bicol routes - Eastern Pacific coast
            startLat > 13.0 && destLat < 14.0 && destLon > 123.0 -> {
                Log.d("CoastalRouting", "Detected Manila to Bicol (East) route - using Pacific coastal waypoints")
                coastalWaypoints["luzon_bicol_east"]
            }
            // Bicol routes - Western approach
            startLat > 13.0 && destLat < 14.0 && destLon > 122.0 && destLon < 123.0 -> {
                Log.d("CoastalRouting", "Detected Manila to Bicol (West) route - using Batangas coastal waypoints")
                coastalWaypoints["luzon_bicol_west"]
            }

            // VISAYAS ROUTES
            // Cebu area routes
            startLat in 9.5..11.0 && destLat in 9.5..11.0 && startLon in 123.0..124.5 && destLon in 123.0..124.5 -> {
                Log.d("CoastalRouting", "Detected Cebu area route - using Cebu coastal waypoints")
                coastalWaypoints["visayas_cebu_coastal"]
            }
            // Panay/Iloilo/Boracay routes
            startLat in 10.5..12.5 && destLat in 10.5..12.5 && startLon in 121.5..123.0 && destLon in 121.5..123.0 -> {
                Log.d("CoastalRouting", "Detected Panay area route - using Panay coastal waypoints")
                coastalWaypoints["visayas_panay_coastal"]
            }
            // Bohol routes
            startLat in 9.3..10.5 && destLat in 9.3..10.5 && startLon in 123.5..125.0 && destLon in 123.5..125.0 -> {
                Log.d("CoastalRouting", "Detected Bohol area route - using Bohol coastal waypoints")
                coastalWaypoints["visayas_bohol_coastal"]
            }
            // Leyte-Samar routes
            startLat in 10.0..12.0 && destLat in 10.0..12.0 && startLon in 124.5..125.5 && destLon in 124.5..125.5 -> {
                Log.d("CoastalRouting", "Detected Leyte-Samar route - using Leyte coastal waypoints")
                coastalWaypoints["visayas_leyte_samar"]
            }

            // MINDANAO ROUTES
            // Southern Mindanao Pacific coast (Davao to GenSan)
            startLat in 5.9..7.2 && destLat in 5.9..7.2 && startLon > 124.5 -> {
                Log.d("CoastalRouting", "Detected Southern Mindanao route - using Pacific coastal waypoints")
                coastalWaypoints["mindanao_south_pacific"]
            }
            // Northern Mindanao coast (CDO to Butuan)
            startLat in 8.3..9.5 && destLat in 8.3..9.5 && startLon in 124.5..126.0 -> {
                Log.d("CoastalRouting", "Detected Northern Mindanao route - using northern coastal waypoints")
                coastalWaypoints["mindanao_north_coast"]
            }
            // Western Mindanao - Zamboanga peninsula
            startLat in 6.5..8.5 && destLat in 6.5..8.5 && startLon < 123.5 -> {
                Log.d("CoastalRouting", "Detected Western Mindanao route - using Zamboanga coastal waypoints")
                coastalWaypoints["mindanao_west_zamboanga"]
            }
            // Surigao coastal routes
            startLat in 8.8..10.0 && destLat in 8.8..10.0 && startLon > 125.0 -> {
                Log.d("CoastalRouting", "Detected Surigao area route - using Surigao coastal waypoints")
                coastalWaypoints["mindanao_surigao_coast"]
            }

            // INTER-ISLAND ROUTES
            // Manila to Cebu (cross multiple islands)
            startLat > 14.0 && destLat in 9.5..11.0 && destLon in 123.0..124.5 -> {
                Log.d("CoastalRouting", "Detected Manila to Cebu inter-island route - using island-hopping coastal waypoints")
                coastalWaypoints["luzon_visayas_manila_cebu"]
            }
            // Manila to Davao (long-distance north-south)
            startLat > 14.0 && destLat < 8.0 && destLon > 125.0 -> {
                Log.d("CoastalRouting", "Detected Manila to Davao long-distance route - using full coastal waypoints")
                coastalWaypoints["luzon_mindanao_manila_davao"]
            }

             else -> null
         }

        if (waypoints == null) {
            Log.d("CoastalRouting", "No coastal waypoints defined for this route (${startLat},${startLon} to ${destLat},${destLon})")
             return@withContext emptyList()
        }

        // Build route through waypoints
        val fullRoute = mutableListOf<GeoPoint>()
        val allPoints = listOf(start) + waypoints + listOf(dest)

        for (i in 0 until allPoints.size - 1) {
            val from = allPoints[i]
            val to = allPoints[i + 1]

            val segmentUrl = "https://router.project-osrm.org/route/v1/driving/${from.longitude},${from.latitude};${to.longitude},${to.latitude}?overview=full&geometries=geojson"
            val request = Request.Builder()
                .url(segmentUrl)
                .header("User-Agent", packageName)
                .header("Accept", "application/json")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val (segmentRoute, _) = parseOsrmResponse(body)
                    if (segmentRoute.isNotEmpty()) {
                        if (fullRoute.isEmpty()) {
                            fullRoute.addAll(segmentRoute)
                        } else {
                            // Skip first point to avoid duplication
                            fullRoute.addAll(segmentRoute.drop(1))
                        }
                    }
                }
                response.close()
            } catch (e: Exception) {
                Log.d("CoastalRouting", "Error fetching segment $i: ${e.message}")
            }
        }

        Log.d("CoastalRouting", "Generated coastal route with ${fullRoute.size} points through ${waypoints.size} waypoints")
        return@withContext fullRoute
    }

    // Display scenic POIs visually with distinct markers (re-added)
    private fun showScenicPoisOnMap(scenicPois: List<ScenicPoi>) {
        scenicMarkers.clear()
        currentRouteMarkers.clear()
        for (poi in scenicPois) {
            val marker = Marker(map)
            marker.position = GeoPoint(poi.lat, poi.lon)
            marker.title = poi.name
            marker.subDescription = poi.type
            marker.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_scenic_marker, theme)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            scenicMarkers.add(marker)
            currentRouteMarkers.add(marker)
            poiMarkerMap["scenic_${poi.lat}_${poi.lon}_${poi.name}"] = marker
        }
        map?.invalidate()
    }

    // Reroute to a selected POI
    private fun rerouteToPoi(poi: Poi) {
        // If sheet is showing, hide it first to return focus to the map
        try { if (::sheetBehavior.isInitialized) sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN } catch (_: Exception) {}
        val lat = poi.lat ?: return
        val lon = poi.lon ?: return
        map?.controller?.setCenter(GeoPoint(lat, lon))
        map?.controller?.setZoom(15.0)
        poiMarkerMap[poiKey(poi)]?.showInfoWindow()
        val startPoint = lastLocation?.let { GeoPoint(it.latitude, it.longitude) }
        val destPoint = originalDestination
        if (startPoint == null || destPoint == null) {
            map?.invalidate(); return
        }
        val viaPoint = GeoPoint(lat, lon)
        lifecycleScope.launch {
            val leg1 = fetchRouteAndPois(startPoint, viaPoint).first
            val leg2 = fetchRouteAndPois(viaPoint, destPoint).first
            if (leg1.isEmpty() || leg2.isEmpty()) {
                map?.invalidate(); return@launch
            }
            val combined = leg1 + leg2.drop(1)
            withContext(Dispatchers.Main) {
                try {
                    map?.overlays?.clear()
                    poiMarkerMap.clear()
                    myLocationOverlay?.let { map?.overlays?.add(it) }
                    val line = Polyline().apply {
                        setPoints(combined)
                        outlinePaint.color = Color.argb(0xA0, 0x00, 0x40, 0xFF)
                        outlinePaint.strokeWidth = 10f
                    }
                    map?.overlays?.add(line)
                    currentRoutePolyline = line
                    map?.invalidate()
                } catch (_: Exception) { }
            }
        }
    }

    // Scenic scoring: emphasize variety & density, allow long detours (length not penalized)
    private fun scenicScoreForRoute(route: List<GeoPoint>, scenic: List<ScenicPoi>): Double {
        if (route.isEmpty()) return Double.NEGATIVE_INFINITY
        val lengthMeters = computeRouteLength(route).coerceAtLeast(1.0)
        val totalScore = scenic.sumOf { it.score }
        val avgScore = if (scenic.isNotEmpty()) totalScore.toDouble() / scenic.size else 0.0
        val distinctTypes = scenic.map { it.type.lowercase(Locale.getDefault()) }.toSet().size
        val density = scenic.size / (lengthMeters / 1000.0) // POIs per km
        // Weighted formula – intentionally rewards more POIs & diversity even if route is very long
        return (totalScore * 1.2) + (avgScore * 15) + (distinctTypes * 90) + (density * 400) +
                // slight bonus for route length to allow big scenic loops
                Math.log(lengthMeters) * 25
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
            return Pair(routePoints, emptyList())
        } catch (_: org.json.JSONException) {
            return Pair(emptyList(), emptyList())
        }
    }

    private fun parseOsrmAlternatives(body: String): List<List<GeoPoint>> {
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
        } catch (_: org.json.JSONException) {
            return emptyList()
        }
    }

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
                val response = httpClient.newCall(request).execute()
                val code = response.code
                if (code == 403 || code == 429 || code >= 500) {
                    response.close()
                    if (attempt < maxAttempts) {
                        kotlinx.coroutines.delay(backoff)
                        backoff *= 2
                        continue
                    } else {
                        return@withContext emptyList()
                    }
                }
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: ""
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
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(backoff)
                    backoff *= 2
                    continue
                } else {
                    return@withContext emptyList()
                }
            }
        }
        return@withContext emptyList()
    }

    private suspend fun generateRecommendationsAlongRoute(
        routePoints: List<GeoPoint>,
        sampleDistMeters: Int = 800,
        radiusMeters: Int = 500,
        maxSamples: Int = 25,
        maxDistToRouteMeters: Int = 150
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext emptyList()
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
        if (samples.size > maxSamples) {
            val down = mutableListOf<GeoPoint>()
            val step = samples.size.toDouble() / maxSamples.toDouble()
            var idx = 0.0
            repeat(maxSamples) {
                val pick = samples[minOf(samples.size - 1, idx.toInt())]
                down.add(pick)
                idx += step
            }
            if (down.last() != samples.last()) down[down.size - 1] = samples.last()
            samples.clear()
            samples.addAll(down)
        }
        val sb = StringBuilder()
        sb.append("[out:json][timeout:25];\n(")
        for (s in samples) {
            val lat = s.latitude
            val lon = s.longitude
            sb.append("node(around:$radiusMeters,$lat,$lon)[amenity];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[amenity];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[tourism];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[tourism];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[historic];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[historic];\n")
        }
        sb.append(")\nout center 200;")
        val ql = sb.toString()
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0")
            .header("Accept", "application/json")
            .build()
        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return@withContext emptyList() }
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
                val name = tags?.optString("name") ?: "Unknown"
                val category = deriveCategory(tags)
                val desc = tags?.optString("description") ?: ""
                val nameTrim = name.trim()
                if (nameTrim.isBlank() || nameTrim.equals("Unknown", ignoreCase = true)) continue
                if (isSurveillance(tags)) continue
                if (!elLat.isNaN() && !elLon.isNaN()) {
                    pois.add(Poi(nameTrim, category, desc, elLat, elLon))
                }
            }
            fun minDistToRoute(lat: Double, lon: Double): Double {
                var minD = Double.MAX_VALUE
                for (rp in routePoints) {
                    val d = haversine(lat, lon, rp.latitude, rp.longitude)
                    if (d < minD) minD = d
                }
                return minD
            }
            val poisWithDist = pois.map { poi -> Pair(poi, minDistToRoute(poi.lat ?: 0.0, poi.lon ?: 0.0)) }
                .filter { it.second <= maxDistToRouteMeters }
                .sortedBy { it.second }
                .map { it.first }
            val limited = if (poisWithDist.size > 50) poisWithDist.subList(0, 50) else poisWithDist
            return@withContext limited
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    private suspend fun debugOverpassAt(center: GeoPoint, radiusMeters: Int = 1000) = withContext(Dispatchers.IO) {
        // Debug function - implementation can be minimal
    }

    private fun isSurveillance(tags: org.json.JSONObject?): Boolean {
        if (tags == null) return false
        val checkKeys = listOf("surveillance", "camera", "monitoring", "security", "man_made")
        for (k in checkKeys) {
            val v = tags.optString(k).ifBlank { "" }.lowercase()
            if (v.contains("camera") || v.contains("cctv") || v.contains("surveillance") || v.contains("monitor")) return true
        }
        return false
    }

    private fun keepPoi(p: Poi): Boolean {
        val n = p.name.trim()
        if (n.isBlank()) return false
        if (n.equals("Unknown", ignoreCase = true)) return false
        return true
    }

    private fun poiKey(p: Poi): String {
        val lat = p.lat ?: 0.0
        val lon = p.lon ?: 0.0
        return "%f_%f_%s".format(Locale.ROOT, lat, lon, p.name)
    }

    private fun computeRouteLength(route: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            total += haversine(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return total
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    private suspend fun updateStatus(msg: String) {
        withContext(Dispatchers.Main) {
            statusView?.text = msg
            statusView?.visibility = View.VISIBLE
            findViewById<View>(R.id.card_status)?.visibility = View.VISIBLE
        }
    }

    private fun clearStatus() {
        statusView?.visibility = View.GONE
        findViewById<View>(R.id.card_status)?.visibility = View.GONE
    }

    private fun clearStatusDelayed(delayMs: Long = 1800L) {
        lifecycleScope.launch { kotlinx.coroutines.delay(delayMs); clearStatus() }
    }

    private fun initClusters() {
        // No-op: clusters are now managed with simple marker lists
    }

    private fun planScenicRoute(alternatives: List<List<GeoPoint>>) {
        if (alternatives.isEmpty()) return

        lifecycleScope.launch {
            try {
                updateStatus("Evaluating ${alternatives.size} route alternatives for scenic value…")

                // Fetch scenic POIs for each alternative route
                val routeScores = mutableListOf<Triple<List<GeoPoint>, List<ScenicPoi>, Double>>()

                for ((index, route) in alternatives.withIndex()) {
                    updateStatus("Analyzing route ${index + 1}/${alternatives.size} for scenic attractions…")

                    // Fetch scenic POIs along this route
                    val scenicPois = fetchScenicPoisAlongRoute(route, radiusMeters = 300, maxSamples = 30)

                    // Calculate scenic score for this route
                    val score = scenicScoreForRoute(route, scenicPois)

                    routeScores.add(Triple(route, scenicPois, score))

                    Log.d("ScenicRouting", "Route ${index + 1}: ${route.size} points, ${scenicPois.size} scenic POIs, score=${"%.2f".format(score)}")
                }

                // Select the route with the highest scenic score
                val bestRoute = routeScores.maxByOrNull { it.third }

                if (bestRoute == null) {
                    updateStatus("No scenic route found")
                    return@launch
                }

                val (selectedRoute, scenicPois, score) = bestRoute
                val routeIndex = routeScores.indexOf(bestRoute) + 1

                Log.d("ScenicRouting", "Selected route $routeIndex with score ${"%.2f".format(score)} and ${scenicPois.size} scenic POIs")

                withContext(Dispatchers.Main) {
                    try {
                        // Clear existing route
                        currentRoutePolyline?.let { map?.overlays?.remove(it) }
                        currentRouteMarkers.forEach { map?.overlays?.remove(it) }
                        currentRouteMarkers.clear()
                        poiMarkerMap.clear()

                        // Draw the selected scenic route
                        val line = Polyline().apply {
                            setPoints(selectedRoute)
                            outlinePaint.color = Color.BLUE
                            outlinePaint.strokeWidth = 12f
                        }
                        map?.overlays?.add(line)
                        currentRoutePolyline = line

                        // Add scenic POI markers
                        for (poi in scenicPois) {
                            val marker = Marker(map)
                            marker.position = GeoPoint(poi.lat, poi.lon)
                            marker.title = poi.name
                            marker.subDescription = "${poi.type} (Score: ${poi.score})"
                            marker.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_scenic_marker, theme)
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            map?.overlays?.add(marker)
                            currentRouteMarkers.add(marker)
                            poiMarkerMap["scenic_${poi.lat}_${poi.lon}_${poi.name}"] = marker
                        }

                        // Center map on route
                        val mid = selectedRoute[selectedRoute.size / 2]
                        map?.controller?.setCenter(mid)
                        map?.controller?.setZoom(11.0)
                        map?.invalidate()

                        // Update adapter with scenic POIs
                        val poiItems = scenicPois.map { scenicPoi ->
                            RecommendationItem.PoiItem(
                                Poi(
                                    name = scenicPoi.name,
                                    category = scenicPoi.type,
                                    description = "Scenic attraction (Score: ${scenicPoi.score})",
                                    lat = scenicPoi.lat,
                                    lon = scenicPoi.lon
                                )
                            )
                        }
                        poiAdapter.updateItems(poiItems)

                        // Show recommendations sheet
                        try {
                            if (::sheetBehavior.isInitialized) {
                                sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                            }
                        } catch (_: Exception) {}

                        updateStatus("Scenic route selected: ${scenicPois.size} attractions, score ${"%.0f".format(score)}")
                        clearStatusDelayed(3000)

                        // Show snackbar with route info
                        val lengthKm = computeRouteLength(selectedRoute) / 1000.0
                        Snackbar.make(
                            findViewById(R.id.main),
                            "Scenic route $routeIndex: ${"%.1f".format(lengthKm)} km, ${scenicPois.size} attractions",
                            Snackbar.LENGTH_LONG
                        ).show()

                    } catch (e: Exception) {
                        Log.e("ScenicRouting", "Error displaying scenic route: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("ScenicRouting", "Error planning scenic route: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateStatus("Error planning scenic route")
                    clearStatusDelayed()
                }
            }
        }
    }

    // Fetch scenic POIs along a route
    private suspend fun fetchScenicPoisAlongRoute(
        routePoints: List<GeoPoint>,
        radiusMeters: Int = 300,
        maxSamples: Int = 30
    ): List<ScenicPoi> = withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext emptyList()

        // Sample points along the route
        val samples = mutableListOf<GeoPoint>()
        samples.add(routePoints.first())

        val sampleDistMeters = 10000 // Sample every 10km for scenic features
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

        // Limit samples
        if (samples.size > maxSamples) {
            val step = samples.size.toDouble() / maxSamples.toDouble()
            val downsampled = mutableListOf<GeoPoint>()
            var idx = 0.0
            repeat(maxSamples) {
                downsampled.add(samples[minOf(samples.size - 1, idx.toInt())])
                idx += step
            }
            if (downsampled.last() != samples.last()) {
                downsampled[downsampled.size - 1] = samples.last()
            }
            samples.clear()
            samples.addAll(downsampled)
        }

        // Build Overpass query for scenic features
        val sb = StringBuilder()
        sb.append("[out:json][timeout:25];\n(")
        for (s in samples) {
            val lat = s.latitude
            val lon = s.longitude
            // Query scenic features
            sb.append("node(around:$radiusMeters,$lat,$lon)[tourism~\"viewpoint|attraction|museum|artwork\"];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[tourism~\"viewpoint|attraction|museum|artwork\"];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[historic~\"monument|memorial|castle|ruins|archaeological_site\"];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[historic~\"monument|memorial|castle|ruins|archaeological_site\"];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[natural~\"peak|beach|waterfall|spring|cave_entrance\"];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[natural~\"peak|beach|waterfall|spring|cave_entrance\"];\n")
        }
        sb.append(")\nout center 100;")

        val ql = sb.toString()
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "$packageName/1.0")
            .header("Accept", "application/json")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return@withContext emptyList()

            val scenicPois = mutableListOf<ScenicPoi>()
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
                val name = tags?.optString("name") ?: tags?.optString("official_name") ?: continue
                val nameTrim = name.trim()
                if (nameTrim.isBlank() || nameTrim.equals("Unknown", ignoreCase = true)) continue

                // Determine type and score
                val tourism = tags.optString("tourism", "")
                val historic = tags.optString("historic", "")
                val natural = tags.optString("natural", "")

                val type = when {
                    tourism == "viewpoint" -> "Viewpoint"
                    tourism == "attraction" -> "Attraction"
                    tourism == "museum" -> "Museum"
                    tourism == "artwork" -> "Artwork"
                    historic == "monument" -> "Monument"
                    historic == "memorial" -> "Memorial"
                    historic == "castle" -> "Castle"
                    historic == "ruins" -> "Ruins"
                    historic == "archaeological_site" -> "Archaeological Site"
                    natural == "peak" -> "Mountain Peak"
                    natural == "beach" -> "Beach"
                    natural == "waterfall" -> "Waterfall"
                    natural == "spring" -> "Spring"
                    natural == "cave_entrance" -> "Cave"
                    else -> "Scenic"
                }

                // Assign score based on type
                val score = when (type) {
                    "Viewpoint" -> 10
                    "Beach" -> 9
                    "Waterfall" -> 9
                    "Castle" -> 8
                    "Mountain Peak" -> 8
                    "Monument" -> 7
                    "Museum" -> 7
                    "Ruins" -> 7
                    "Archaeological Site" -> 7
                    "Cave" -> 6
                    "Memorial" -> 6
                    "Attraction" -> 6
                    "Spring" -> 5
                    "Artwork" -> 5
                    else -> 4
                }

                if (!elLat.isNaN() && !elLon.isNaN()) {
                    scenicPois.add(ScenicPoi(nameTrim, type, elLat, elLon, score))
                }
            }

            Log.d("ScenicRouting", "Fetched ${scenicPois.size} scenic POIs for route evaluation")
            return@withContext scenicPois

        } catch (e: Exception) {
            Log.e("ScenicRouting", "Error fetching scenic POIs: ${e.message}")
            return@withContext emptyList()
        }
    }
}
