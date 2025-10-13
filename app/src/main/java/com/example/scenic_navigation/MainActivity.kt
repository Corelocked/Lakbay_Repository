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
import com.google.android.material.switchmaterial.SwitchMaterial

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

// Import models from the centralized models package
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.models.ScenicPoi
import com.example.scenic_navigation.models.RecommendationItem
import com.example.scenic_navigation.models.GeocodeResult
import com.example.scenic_navigation.models.Town
import com.example.scenic_navigation.services.TownService
import com.example.scenic_navigation.services.ScenicRoutePlanner
import com.example.scenic_navigation.utils.GeoUtils
import com.example.scenic_navigation.utils.OffRouteDetector
import android.location.LocationListener
import android.os.Looper
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.bottomsheet.BottomSheetBehavior

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
    private val currentRoutePolylines = mutableListOf<Polyline>()
    private val currentRouteMarkers = mutableListOf<Marker>()
    // Keep an explicit copy of the active route points for off-route detection
    private var currentRoutePoints: List<GeoPoint> = emptyList()

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

    // Town service for fetching towns along route
    private val townService: TownService by lazy { TownService() }
    private var currentRouteTowns = listOf<Town>()

    // Off-route detection components
    private var offRouteDetector: OffRouteDetector? = null
    private var isRerouting: Boolean = false
    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { loc ->
        lastLocation = loc
        offRouteDetector?.updateLocation(loc)
    }

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
        val switchOceanicRoute = findViewById<SwitchMaterial>(R.id.switch_oceanic_route)
        val switchMountainRoute = findViewById<SwitchMaterial>(R.id.switch_mountain_route)
        val btnPlan = findViewById<Button>(R.id.btn_plan)
        // Inline (hidden) RecyclerView - kept as fallback
        val rvRecs = findViewById<RecyclerView>(R.id.rv_recommendations)
        // The sheet recycler view where recommendations are shown in the modal bottom sheet
        val sheetRv = findViewById<RecyclerView>(R.id.sheet_rv_recommendations)
        val progress = findViewById<ProgressBar>(R.id.progress_geocoding)
        val progressOverlay = findViewById<View>(R.id.progress_overlay)
        @Suppress("UNUSED_VARIABLE")
        val statusCard = findViewById<View>(R.id.card_status)
        statusView = findViewById(R.id.tv_status)

        // Set default states: current location and scenic route enabled by default
        switchUseCurrent.isChecked = true
        switchOceanicRoute.isChecked = true
        switchMountainRoute.isChecked = true

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
            },
            onTownClick = { town ->
                // When a town is clicked, show must-visit POIs in that town
                lifecycleScope.launch {
                    updateStatus("Loading attractions in ${town.name}...")
                    val townService = TownService()
                    val townPois = townService.getMustVisitPoisInTown(town, packageName)

                    withContext(Dispatchers.Main) {
                        if (townPois.isNotEmpty()) {
                            // Update recommendations with POIs from this town
                            val poiItems = townPois.map { poi ->
                                RecommendationItem.PoiItem(poi)
                            }
                            poiAdapter.updateItems(poiItems)

                            // Center map on town
                            map?.controller?.setCenter(GeoPoint(town.lat, town.lon))
                            map?.controller?.setZoom(14.0)

                            // Add markers for town POIs
                            currentRouteMarkers.forEach { map?.overlays?.remove(it) }
                            currentRouteMarkers.clear()

                            for (poi in townPois) {
                                if (poi.lat != null && poi.lon != null) {
                                    val marker = Marker(map)
                                    marker.position = GeoPoint(poi.lat, poi.lon)
                                    marker.title = poi.name
                                    marker.subDescription = poi.description
                                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    val iconRes = when {
                                        poi.category.contains("restaurant", ignoreCase = true) -> R.drawable.ic_food_marker
                                        poi.category.contains("museum", ignoreCase = true) -> R.drawable.ic_monument
                                        else -> R.drawable.ic_scenic_marker
                                    }
                                    marker.icon = ResourcesCompat.getDrawable(resources, iconRes, theme)
                                    map?.overlays?.add(marker)
                                    currentRouteMarkers.add(marker)
                                }
                            }
                            map?.invalidate()

                            updateStatus("Found ${townPois.size} attractions in ${town.name}")
                            clearStatusDelayed(2000)
                        } else {
                            updateStatus("No attractions found in ${town.name}")
                            clearStatusDelayed(2000)
                        }
                    }
                }
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
        myLocationOverlay?.enableMyLocation()
        myLocationOverlay?.enableFollowLocation()
        myLocationOverlay?.setDrawAccuracyEnabled(true)

        // Initialize LocationManager for off-route detection updates
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

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
            val useOceanic = switchOceanicRoute.isChecked
            val useMountain = switchMountainRoute.isChecked

            // Validate start location first
            if (useCurrent && lastLocation == null) {
                // Try to fetch location one more time
                if (hasLocationPermission()) {
                    lastLocation = fetchLastKnownLocation()
                }

                if (lastLocation == null) {
                    Snackbar.make(
                        findViewById(R.id.main),
                        "Current location unavailable. Please enter a start address or wait for GPS.",
                        Snackbar.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
            }

            val startInput = if (useCurrent) {
                lastLocation?.let { "${it.latitude},${it.longitude}" } ?: etStart.text.toString()
            } else {
                etStart.text.toString()
            }

            // Validate start input
            if (startInput.isBlank()) {
                Snackbar.make(
                    findViewById(R.id.main),
                    "Please enter a start location or enable 'Use Current Location'",
                    Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
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
                switchOceanicRoute.isEnabled = false
                switchMountainRoute.isEnabled = false
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

                // Additional validation: ensure we have a start point before continuing
                if (startPoint == null) {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(
                            findViewById(R.id.main),
                            "Could not determine start location. Please try again.",
                            Snackbar.LENGTH_LONG
                        ).show()
                        // cleanup and re-enable UI
                        progress.visibility = View.GONE
                        btnCancel.visibility = View.GONE
                        btnPlan.isEnabled = true
                        etDestination.isEnabled = true
                        etStart.isEnabled = true
                        switchUseCurrent.isEnabled = true
                        switchOceanicRoute.isEnabled = true
                        switchMountainRoute.isEnabled = true
                        geocodeJob = null
                    }
                    return@launch
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
                    switchOceanicRoute.isEnabled = true
                    switchMountainRoute.isEnabled = true
                    geocodeJob = null
                    return@launch
                }
                // Scenic route logic
                // Determine which scenic route type to use
                val routeType = when {
                    useOceanic && useMountain -> "oceanic" // Both checked: prioritize oceanic
                    useOceanic -> "oceanic"
                    useMountain -> "mountain"
                    else -> null // Neither checked: use default route
                }

                if (routeType != null) {
                    updateStatus("Fetching $routeType scenic alternatives…")
                    // Try to fetch multiple route alternatives (uses OSRM alternatives=true)
                    Log.d("PolylineDebug", "[Scenic] Requesting route alternatives for $routeType scenic mode")
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

                    // For mountain routes, try to generate mountain route via waypoints if we don't have enough alternatives
                    if (routeType == "mountain" && alternatives.size <= 1 && startPoint != null) {
                        Log.d("MountainRouting", "Only ${alternatives.size} alternatives from OSRM, generating mountain route via waypoints")
                        val mountainRoute = generateMountainRouteViaWaypoints(startPoint, destPoint)
                        if (mountainRoute.isNotEmpty()) {
                            alternatives.add(mountainRoute)
                            Log.d("MountainRouting", "Added mountain route alternative with ${mountainRoute.size} points")
                        }
                    }

                    // For oceanic routes, try to generate coastal route via waypoints if we don't have enough alternatives
                    if (routeType == "oceanic" && alternatives.size <= 1 && startPoint != null) {
                        Log.d("CoastalRouting", "Only ${alternatives.size} alternatives from OSRM, generating coastal route via waypoints")
                        val coastalRoute = generateCoastalRouteViaWaypoints(startPoint, destPoint)
                        if (coastalRoute.isNotEmpty()) {
                            alternatives.add(coastalRoute)
                            Log.d("CoastalRouting", "Added coastal route alternative with ${coastalRoute.size} points")
                        }
                    }

                    if (alternatives.isNotEmpty()) {
                        Log.d(
                            "PolylineDebug",
                            "[Scenic] Planning $routeType scenic route with ${alternatives.size} alternatives"
                        )
                        planScenicRoute(alternatives, routeType)
                        clearStatusDelayed()
                        // Hide progress and re-enable inputs
                        progress.visibility = View.GONE
                        btnCancel.visibility = View.GONE
                        btnPlan.isEnabled = true
                        etDestination.isEnabled = true
                        etStart.isEnabled = true
                        switchUseCurrent.isEnabled = true
                        switchOceanicRoute.isEnabled = true
                        switchMountainRoute.isEnabled = true
                        geocodeJob = null
                        return@launch
                    } else {
                        Log.d(
                            "PolylineDebug",
                            "[Scenic] Still no alternatives to plan $routeType scenic route, falling back to default"
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

                // Fetch towns along the route in order
                updateStatus("Fetching towns along route…")
                val towns = townService.getTownsAlongRoute(routePoints, packageName, maxDistanceFromRoute = 5000.0)
                currentRouteTowns = towns
                Log.d("MainActivity", "Found ${towns.size} towns along the route")

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

                // Simplified road trip plan - just use the recommendations directly
                Log.d("RoadTrip", "Created road trip with ${recommendations.size} POI recommendations")

                withContext(Dispatchers.Main) {
                    Snackbar.make(
                        findViewById(R.id.main),
                        "Found ${recommendations.size} recommended stops along route",
                        Snackbar.LENGTH_LONG
                    ).show()
                }

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

                    // Draw route (no ferry segments in simple mode for now)
                    val ferrySegments = emptyList<Pair<Int, Int>>()
                    drawRouteWithFerrySegments(routePoints, ferrySegments)

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
                    // Show towns in recommendations list (in order along route)
                    val recommendationItems = towns.map { town ->
                        RecommendationItem.TownItem(town)
                    }
                    poiAdapter.updateItems(recommendationItems)

                    // Show snackbar with town count
                    Snackbar.make(
                        findViewById(R.id.main),
                        "Route passes through ${towns.size} towns/cities",
                        Snackbar.LENGTH_LONG
                    ).show()

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
                switchOceanicRoute.isEnabled = true
                switchMountainRoute.isEnabled = true
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
        // Start off-route monitoring
        startLocationUpdatesForOffRoute()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdatesForOffRoute()
    }

    private fun startLocationUpdatesForOffRoute() {
        if (!hasLocationPermission()) return
        try {
            // Request GPS and network for better coverage
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener, Looper.getMainLooper())
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, locationListener, Looper.getMainLooper())
        } catch (_: SecurityException) { }
    }

    private fun stopLocationUpdatesForOffRoute() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) { }
    }

    private fun ensureOffRouteDetector(routePoints: List<GeoPoint>) {
        if (routePoints.size < 2) return
        if (offRouteDetector == null) {
            offRouteDetector = OffRouteDetector(
                routePoints = routePoints,
                thresholdMeters = 45f,
                returnThresholdMeters = 25f,
                requiredConsecutive = 2,
                cooldownMs = 15000L
            ) { loc ->
                onOffRouteTriggered(loc)
            }
        } else {
            offRouteDetector?.updateRoute(routePoints)
        }
    }

    private fun onOffRouteTriggered(loc: Location) {
        if (isRerouting) return
        val dest = originalDestination ?: return
        isRerouting = true
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                Snackbar.make(findViewById(R.id.main), "You went off-route. Recalculating…", Snackbar.LENGTH_SHORT).show()
            }
            val startPoint = GeoPoint(loc.latitude, loc.longitude)
            val (newRoute, _) = fetchRouteAndPois(startPoint, dest)
            if (newRoute.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    try {
                        // Keep overlays but replace current route line
                        currentRoutePolyline?.let { map?.overlays?.remove(it) }
                        val line = Polyline().apply {
                            setPoints(newRoute)
                            outlinePaint.color = Color.argb(0xA0, 0x00, 0x40, 0xFF)
                            outlinePaint.strokeWidth = 10f
                        }
                        map?.overlays?.add(line)
                        currentRoutePolyline = line
                        currentRoutePoints = newRoute
                        map?.invalidate()
                    } catch (_: Exception) { }
                }
                // Update detector with the new route
                ensureOffRouteDetector(newRoute)
            }
            isRerouting = false
        }
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
                startLocationUpdatesForOffRoute()
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
        if (start == null) {
            Log.e("MainActivity", "fetchRouteAndPois: start point is null")
            return@withContext Pair(emptyList(), emptyList())
        }

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
                Log.d("MainActivity", "fetchRouteAndPois parsed: route=${route.size} points, pois=${pois.size}")
                return@withContext Pair(route, pois)
            } else {
                val errorBody = response.body?.string() ?: "no error body"
                Log.e("MainActivity", "OSRM request failed with code ${response.code}: $errorBody")
                return@withContext Pair(emptyList(), emptyList())
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "OSRM error: ${e.message}", e)
            return@withContext Pair(emptyList(), emptyList())
        }
    }

    private suspend fun fetchRouteAlternatives(
        start: GeoPoint?,
        dest: GeoPoint
    ): List<List<GeoPoint>> = withContext(Dispatchers.IO) {
        if (start == null) {
            Log.e("MainActivity", "fetchRouteAlternatives: start point is null")
            return@withContext emptyList()
        }

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
                Log.d("MainActivity", "fetchRouteAlternatives parsed: ${alternatives.size} routes")

                // Return alternatives without auto-generating routes
                // Route-specific generation (coastal/mountain) is handled by the caller
                return@withContext alternatives
            } else {
                val errorBody = response.body?.string() ?: "no error body"
                Log.e("MainActivity", "OSRM alternatives request failed with code ${response.code}: $errorBody")
                return@withContext emptyList()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "OSRM alternatives error: ${e.message}", e)
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
            // Northern Luzon routes - Manila/NCR to Ilocos/Cagayan (going north from Manila area)
            // Expanded longitude range to cover routes to Aparri and eastern Cagayan (up to 122.0)
            startLat > 13.5 && startLat < 15.5 && destLat > 16.0 && destLon >= 120.0 && destLon <= 122.0 -> {
                Log.d("CoastalRouting", "Detected Manila to Northern Luzon route - using Ilocos coastal waypoints")
                coastalWaypoints["luzon_north"]
            }
            // Manila to Bicol - Eastern Luzon Pacific coast
            startLat > 14.0 && destLat < 14.0 && destLon > 123.0 -> {
                Log.d("CoastalRouting", "Detected Manila to Bicol (East) route - using Pacific coastal waypoints")
                coastalWaypoints["luzon_bicol_east"]
            }
            // Manila to Bicol - Western approach (going south from Manila to Bicol via Batangas)
            startLat > 14.0 && destLat < 13.5 && destLon > 122.0 && destLon < 123.0 -> {
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

    // Generate a mountain route by routing through strategic mountain waypoints
    private suspend fun generateMountainRouteViaWaypoints(start: GeoPoint, dest: GeoPoint): List<GeoPoint> = withContext(Dispatchers.IO) {
        // Define strategic mountain waypoints for major Philippine mountain routes
        val mountainWaypoints = mapOf(
            // LUZON MOUNTAIN ROUTES
            // Manila to Baguio - Cordillera highlands
            "luzon_baguio" to listOf(
                GeoPoint(15.2000, 120.6000), // Tarlac highlands
                GeoPoint(15.8000, 120.5700), // Kennon Road scenic viewpoint
                GeoPoint(16.4000, 120.5900)  // Baguio City
            ),
            // Baguio to Sagada - Mountain Province
            "luzon_sagada" to listOf(
                GeoPoint(16.6000, 120.7000), // Bontoc
                GeoPoint(17.0800, 120.9000)  // Sagada
            ),
            // Manila to Tagaytay - Taal highlands
            "luzon_tagaytay" to listOf(
                GeoPoint(14.1650, 121.0500), // Cavite highlands
                GeoPoint(14.1100, 120.9600)  // Tagaytay Ridge
            ),
            // Banaue Rice Terraces route
            "luzon_banaue" to listOf(
                GeoPoint(15.8000, 120.5700), // Baguio
                GeoPoint(16.6000, 120.7000), // Bontoc
                GeoPoint(16.9270, 121.0560)  // Banaue
            ),
            // Mount Pulag approach
            "luzon_pulag" to listOf(
                GeoPoint(16.4000, 120.5900), // Baguio
                GeoPoint(16.5500, 120.7500), // La Trinidad
                GeoPoint(16.6000, 120.8800)  // Pulag area
            ),

            // VISAYAS MOUNTAIN ROUTES
            // Cebu mountain circuit (Tops, Busay)
            "visayas_cebu_mountain" to listOf(
                GeoPoint(10.3500, 123.8500), // Busay highlands
                GeoPoint(10.3700, 123.8700)  // Tops viewpoint
            ),
            // Negros highlands (Canlaon area)
            "visayas_canlaon" to listOf(
                GeoPoint(10.4000, 123.1000), // Negros highlands
                GeoPoint(10.4120, 123.1320)  // Canlaon Volcano area
            ),
            // Antique highlands (Panay)
            "visayas_antique" to listOf(
                GeoPoint(11.3000, 122.0500), // Antique highlands
                GeoPoint(11.5000, 122.1000)  // Mountain villages
            ),

            // MINDANAO MOUNTAIN ROUTES
            // Bukidnon highlands (Dahilayan, Del Monte)
            "mindanao_bukidnon" to listOf(
                GeoPoint(8.4800, 124.6500), // Cagayan de Oro
                GeoPoint(8.2500, 125.0000), // Bukidnon highlands
                GeoPoint(7.9000, 125.1000)  // Malaybalay area
            ),
            // Davao highlands (Eden area)
            "mindanao_davao_highlands" to listOf(
                GeoPoint(7.1900, 125.4550), // Davao City
                GeoPoint(7.3000, 125.3000), // Toril highlands
                GeoPoint(7.4000, 125.2500)  // Eden/Malagos highlands
            ),
            // Mount Apo approach
            "mindanao_apo" to listOf(
                GeoPoint(7.1900, 125.4550), // Davao
                GeoPoint(7.0000, 125.2700), // Digos highlands
                GeoPoint(6.9870, 125.2726)  // Mount Apo area
            ),
            // Lake Sebu highlands (South Cotabato)
            "mindanao_lake_sebu" to listOf(
                GeoPoint(6.1100, 125.1700), // General Santos
                GeoPoint(6.2000, 124.7000), // Surallah
                GeoPoint(6.2100, 124.7040)  // Lake Sebu
            ),
            // Kitanglad Range (Bukidnon)
            "mindanao_kitanglad" to listOf(
                GeoPoint(8.2500, 125.0000), // Bukidnon lowlands
                GeoPoint(8.1500, 124.9500), // Kitanglad foothills
                GeoPoint(8.1300, 124.9000)  // Kitanglad range
            ),

            // TRANS-REGIONAL MOUNTAIN ROUTES
            // Cordillera circuit
            "luzon_cordillera_circuit" to listOf(
                GeoPoint(16.4000, 120.5900), // Baguio
                GeoPoint(16.8000, 120.7500), // Mountain Province
                GeoPoint(17.0000, 121.0000), // Ifugao highlands
                GeoPoint(16.9270, 121.0560)  // Banaue
            )
        )

        // Determine which waypoint set to use based on start/destination
        val startLat = start.latitude
        val destLat = dest.latitude
        val startLon = start.longitude
        val destLon = dest.longitude

        val waypoints = when {
            // LUZON MOUNTAIN ROUTES
            // Manila to Baguio/Benguet
            startLat < 15.0 && destLat in 16.0..17.0 && destLon in 120.5..121.0 -> {
                Log.d("MountainRouting", "Detected Manila to Baguio route - using Cordillera waypoints")
                mountainWaypoints["luzon_baguio"]
            }
            // Baguio to Sagada
            startLat in 16.0..17.0 && destLat in 16.8..17.2 && destLon in 120.8..121.0 -> {
                Log.d("MountainRouting", "Detected Baguio to Sagada route - using Mountain Province waypoints")
                mountainWaypoints["luzon_sagada"]
            }
            // Manila to Tagaytay
            startLat > 14.4 && destLat in 14.0..14.2 && destLon in 120.9..121.1 -> {
                Log.d("MountainRouting", "Detected Manila to Tagaytay route - using Tagaytay Ridge waypoints")
                mountainWaypoints["luzon_tagaytay"]
            }
            // Banaue Rice Terraces route
            startLat in 15.0..17.0 && destLat in 16.8..17.0 && destLon in 121.0..121.2 -> {
                Log.d("MountainRouting", "Detected route to Banaue - using rice terraces waypoints")
                mountainWaypoints["luzon_banaue"]
            }

            // VISAYAS MOUNTAIN ROUTES
            // Cebu mountain areas
            startLat in 10.2..10.4 && destLat in 10.2..10.4 && startLon in 123.7..124.0 && destLon in 123.8..124.0 -> {
                Log.d("MountainRouting", "Detected Cebu mountain route - using highlands waypoints")
                mountainWaypoints["visayas_cebu_mountain"]
            }
            // Negros Canlaon area
            startLat in 10.2..10.6 && destLat in 10.2..10.6 && startLon in 122.8..123.2 && destLon in 122.8..123.2 -> {
                Log.d("MountainRouting", "Detected Negros highlands route - using Canlaon waypoints")
                mountainWaypoints["visayas_canlaon"]
            }

            // MINDANAO MOUNTAIN ROUTES
            // Bukidnon highlands
            startLat in 7.5..8.5 && destLat in 7.5..8.5 && startLon in 124.5..125.5 && destLon in 124.5..125.5 -> {
                Log.d("MountainRouting", "Detected Bukidnon highlands route - using mountain waypoints")
                mountainWaypoints["mindanao_bukidnon"]
            }
            // Davao highlands
            startLat in 7.0..7.5 && destLat in 7.0..7.5 && startLon in 125.0..125.5 && destLon in 125.0..125.5 -> {
                Log.d("MountainRouting", "Detected Davao highlands route - using Eden waypoints")
                mountainWaypoints["mindanao_davao_highlands"]
            }
            // Lake Sebu
            startLat in 6.0..6.5 && destLat in 6.0..6.5 && startLon in 124.5..125.2 && destLon in 124.5..125.2 -> {
                Log.d("MountainRouting", "Detected Lake Sebu route - using highlands waypoints")
                mountainWaypoints["mindanao_lake_sebu"]
            }

            else -> null
        }

        if (waypoints == null) {
            Log.d("MountainRouting", "No mountain waypoints defined for this route (${startLat},${startLon} to ${destLat},${destLon})")
            return@withContext emptyList()
        }

        // Build route through waypoints
        val fullRoute = mutableListOf<GeoPoint>()
        val allPoints = listOf(start) + waypoints + listOf(dest)

        for ( i in 0 until allPoints.size - 1) {
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
                Log.d("MountainRouting", "Error fetching segment $i: ${e.message}")
            }
        }

        Log.d("MountainRouting", "Generated mountain route with ${fullRoute.size} points through ${waypoints.size} waypoints")
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
                    // Update off-route detector with new combined route
                    currentRoutePoints = combined
                    ensureOffRouteDetector(combined)
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

            // Check for OSRM error response
            val code = json.optString("code", "")
            if (code != "Ok") {
                val message = json.optString("message", "Unknown error")
                Log.e("MainActivity", "OSRM error response: code=$code, message=$message")
                Log.e("MainActivity", "Full OSRM response: $body")
                return Pair(emptyList(), emptyList())
            }

            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                Log.e("MainActivity", "OSRM response has no routes")
                Log.e("MainActivity", "Full OSRM response: $body")
                return Pair(emptyList(), emptyList())
            }

            val routeObj = routes.getJSONObject(0)
            val geometry = routeObj.optJSONObject("geometry")
            if (geometry == null) {
                Log.e("MainActivity", "OSRM route has no geometry")
                return Pair(emptyList(), emptyList())
            }

            val coords = geometry.optJSONArray("coordinates")
            if (coords == null) {
                Log.e("MainActivity", "OSRM geometry has no coordinates")
                return Pair(emptyList(), emptyList())
            }

            val routePoints = mutableListOf<GeoPoint>()
            for (i in 0 until coords.length()) {
                val point = coords.getJSONArray(i)
                routePoints.add(GeoPoint(point.getDouble(1), point.getDouble(0)))
            }

            Log.d("MainActivity", "Successfully parsed OSRM route with ${routePoints.size} points")
            return Pair(routePoints, emptyList())
        } catch (e: Exception) {
            Log.e("MainActivity", "Error parsing OSRM response: ${e.message}", e)
            Log.e("MainActivity", "Response body: $body")
            return Pair(emptyList(), emptyList())
        }
    }

    /**
     * Parse OSRM response with ferry detection
     * Returns triple: (routePoints, ferrySegments, pois)
     * ferrySegments is a list of pairs (startIndex, endIndex) marking ferry route segments
     */
    private fun parseOsrmResponseWithFerries(body: String): Triple<List<GeoPoint>, List<Pair<Int, Int>>, List<Poi>> {
        try {
            val json = org.json.JSONObject(body)
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return Triple(emptyList(), emptyList(), emptyList())

            val routeObj = routes.getJSONObject(0)
            val geometry = routeObj.getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")

            val routePoints = mutableListOf<GeoPoint>()
            for (i in 0 until coords.length()) {
                val point = coords.getJSONArray(i)
                routePoints.add(GeoPoint(point.getDouble(1), point.getDouble(0)))
            }

            val ferrySegments = mutableListOf<Pair<Int, Int>>()

            // Try to parse legs to detect ferry routes
            val legs = routeObj.optJSONArray("legs")
            if (legs != null) {
                var pointIndex = 0

                for (i in 0 until legs.length()) {
                    val leg = legs.getJSONObject(i)
                    val steps = leg.optJSONArray("steps")

                    if (steps != null) {
                        for (j in 0 until steps.length()) {
                            val step = steps.getJSONObject(j)
                            val mode = step.optString("mode", "")
                            val stepGeometry = step.optJSONObject("geometry")

                            if (stepGeometry != null) {
                                val stepCoords = stepGeometry.optJSONArray("coordinates")
                                val stepPointCount = stepCoords?.length() ?: 0

                                // Check if this is a ferry segment
                                if (mode == "ferry" || step.optString("maneuver.type", "").contains("ferry")) {
                                    val startIdx = pointIndex
                                    val endIdx = pointIndex + stepPointCount - 1
                                    if (endIdx < routePoints.size) {
                                        ferrySegments.add(Pair(startIdx, endIdx))
                                        Log.d("FerryDetection", "Found ferry segment: $startIdx to $endIdx")
                                    }
                                }

                                pointIndex += maxOf(0, stepPointCount - 1)
                            }
                        }
                    }
                }
            }

            Log.d("FerryDetection", "Detected ${ferrySegments.size} ferry segments in route")
            return Triple(routePoints, ferrySegments, emptyList())

        } catch (e: org.json.JSONException) {
            Log.e("FerryDetection", "Error parsing OSRM response: ${e.message}")
            return Triple(emptyList(), emptyList(), emptyList())
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
            sb.append("node(around:$radiusMeters,$lat,$lon)[leisure];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[leisure];\n")
            sb.append("node(around:$radiusMeters,$lat,$lon)[man_made];\n")
            sb.append("way(around:$radiusMeters,$lat,$lon)[man_made];\n")
        }
        sb.append(");\nout center;\n")
        val ql = sb.toString().trim()
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = ql.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(requestBody)
            .header("User-Agent", "${packageName}/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
            .header("Accept", "application/json")
            .build()
        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val json = org.json.JSONObject(body)
            val elements = json.optJSONArray("elements") ?: return@withContext emptyList()
            val pois = mutableListOf<Poi>()
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
            return@withContext pois
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    // Compute route length in meters
    private fun computeRouteLength(route: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            total += haversine(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return total
    }

    // Haversine formula to calculate distance between two lat/lon points in meters
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c // Distance in meters
    }

    // Check if a POI is a surveillance camera (based on heuristics)
    private fun isSurveillance(tags: org.json.JSONObject?): Boolean {
        if (tags == null) return false
        val cameraTypes = listOf("surveillance", "camera", "cctv", "security")
        for (type in cameraTypes) {
            if (tags.optString("amenity", "").contains(type, ignoreCase = true) ||
                tags.optString("tourism", "").contains(type, ignoreCase = true) ||
                tags.optString("man_made", "").contains(type, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    // Debugging: run a quick Overpass query at the destination to test connectivity and surface logs
    private suspend fun debugOverpassAt(center: GeoPoint?, radiusMeters: Int = 1000) {
        withContext(Dispatchers.IO) {
            if (center == null) return@withContext
            val lat = center.latitude
            val lon = center.longitude
            val ql = """
                [out:json][timeout:25];
                (
                  node(around:$radiusMeters,$lat,$lon);
                  way(around:$radiusMeters,$lat,$lon);
                  relation(around:$radiusMeters,$lat,$lon);
                );
                out body;
                >;
                out skel qt;
            """.trimIndent()
            val mediaType = "text/plain; charset=utf-8".toMediaType()
            val requestBody = ql.toRequestBody(mediaType)
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(requestBody)
                .header("User-Agent", "${packageName}/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
                .header("Accept", "application/json")
                .build()
            try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    Log.d("OverpassDebug", "Response: $body")
                } else {
                    Log.d("OverpassDebug", "Error: ${response.code}")
                }
            } catch (e: Exception) {
                Log.d("OverpassDebug", "Exception: ${e.message}")
            }
        }
    }

    // Status update helpers
    private fun updateStatus(message: String) {
        statusView?.text = message
        findViewById<View>(R.id.card_status)?.visibility = View.VISIBLE
    }

    private fun clearStatus() {
        findViewById<View>(R.id.card_status)?.visibility = View.GONE
    }

    private fun clearStatusDelayed(delayMs: Long = 3000L) {
        lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            clearStatus()
        }
    }

    // Initialize clusters (placeholder for marker clustering)
    private fun initClusters() {
        poiMarkers.clear()
        scenicMarkers.clear()
    }

    // POI filtering helper
    private fun keepPoi(poi: Poi): Boolean {
        // Filter out surveillance cameras and other unwanted POIs
        val name = poi.name.lowercase(Locale.getDefault())
        val category = poi.category.lowercase(Locale.getDefault())

        // Skip surveillance, CCTV, etc.
        if (name.contains("surveillance") || name.contains("cctv") ||
            name.contains("camera") || category.contains("surveillance")) {
            return false
        }

        // Skip generic/unknown names
        if (name.isBlank() || name == "unknown") {
            return false
        }

        return true
    }

    // Generate unique key for POI
    private fun poiKey(poi: Poi): String {
        return "${poi.name}_${poi.lat}_${poi.lon}"
    }

    // Create road trip plan from route and POIs (placeholder implementation)
    private fun createRoadTripPlan(
        routePoints: List<GeoPoint>,
        pois: List<Poi>,
        start: GeoPoint?,
        dest: GeoPoint
    ): Any {
        // Simple implementation - just return a data structure
        // This would be enhanced with actual RoadTripPlan model
        return object {
            val waypoints = pois.map { poi ->
                object {
                    val name = poi.name
                    val category = poi.category
                    val lat = poi.lat
                    val lon = poi.lon
                }
            }
        }
    }

    // Draw route with ferry segments highlighted
    private fun drawRouteWithFerrySegments(routePoints: List<GeoPoint>, ferrySegments: List<Pair<Int, Int>>) {
        // Draw main route
        val line = Polyline()
        line.setPoints(routePoints)
        line.outlinePaint.color = Color.BLUE
        line.outlinePaint.strokeWidth = 10.0f
        map?.overlays?.add(line)
        currentRoutePolyline = line
        // Update active route points for off-route detector
        currentRoutePoints = routePoints
        ensureOffRouteDetector(routePoints)

        // Draw ferry segments in different color
        for (segment in ferrySegments) {
            val ferryPoints = routePoints.subList(segment.first, segment.second + 1)
            val ferryLine = Polyline()
            ferryLine.setPoints(ferryPoints)
            ferryLine.outlinePaint.color = Color.CYAN
            ferryLine.outlinePaint.strokeWidth = 12.0f
            map?.overlays?.add(ferryLine)
            currentRoutePolylines.add(ferryLine)
        }

        map?.invalidate()
    }

    // Parse OSRM alternatives response
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

                if (routePoints.isNotEmpty()) {
                    alternatives.add(routePoints)
                }
            }

            return alternatives
        } catch (_: org.json.JSONException) {
            return emptyList()
        }
    }

    // Plan scenic route based on route type (oceanic or mountain)
    private suspend fun planScenicRoute(
        alternatives: List<List<GeoPoint>>,
        routeType: String = "oceanic"
    ) = withContext(Dispatchers.Main) {
        if (alternatives.isEmpty()) {
            updateStatus("No route alternatives available")
            return@withContext
        }

        // Use ScenicRoutePlanner to find the best scenic route
        val planner = ScenicRoutePlanner()

        updateStatus("Analyzing scenic routes (${routeType})...")

        val (bestRoute, scenicPois) = withContext(Dispatchers.IO) {
            planner.selectMostScenicRoute(
                alternatives,
                packageName,
                routeType
            ) { status ->
                lifecycleScope.launch(Dispatchers.Main) {
                    updateStatus("$status (${routeType})")
                }
            }
        }

        if (bestRoute == null || bestRoute.isEmpty()) {
            updateStatus("Could not find scenic route")
            return@withContext
        }

        // Filter scenic POIs based on route type
        val filteredPois = when (routeType) {
            "oceanic" -> scenicPois.filter { poi ->
                val type = poi.type.lowercase(Locale.getDefault())
                type.contains("beach") ||
                type.contains("coast") ||
                type.contains("bay") ||
                type.contains("sea") ||
                type.contains("ocean") ||
                type.contains("viewpoint") ||
                type.contains("nature_reserve") ||
                poi.name.lowercase(Locale.getDefault()).let { name ->
                    name.contains("beach") ||
                    name.contains("coast") ||
                    name.contains("bay") ||
                    name.contains("sea")
                }
            }
            "mountain" -> scenicPois.filter { poi ->
                val type = poi.type.lowercase(Locale.getDefault())
                type.contains("peak") ||
                type.contains("mountain") ||
                type.contains("hill") ||
                type.contains("viewpoint") ||
                type.contains("nature_reserve") ||
                type.contains("wood") ||
                poi.name.lowercase(Locale.getDefault()).let { name ->
                    name.contains("mountain") ||
                    name.contains("peak") ||
                    name.contains("hill") ||
                    name.contains("summit")
                }
            }
            else -> scenicPois
        }

        // Draw the route
        currentRoutePolylines.forEach { map?.overlays?.remove(it) }
        currentRoutePolylines.clear()
        currentRouteMarkers.forEach { map?.overlays?.remove(it) }
        currentRouteMarkers.clear()
        currentRoutePolyline?.let { map?.overlays?.remove(it) }

        val line = Polyline()
        line.setPoints(bestRoute)
        line.outlinePaint.color = when (routeType) {
            "oceanic" -> Color.argb(200, 0, 120, 215)  // Ocean blue
            "mountain" -> Color.argb(200, 76, 175, 80)  // Mountain green
            else -> Color.MAGENTA
        }
        line.outlinePaint.strokeWidth = 14.0f
        map?.overlays?.add(line)
        currentRoutePolyline = line
        currentRoutePolylines.add(line)
        // Update route points for off-route detector
        currentRoutePoints = bestRoute
        ensureOffRouteDetector(bestRoute)

        // Add scenic POI markers
        for (poi in filteredPois) {
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
        if (bestRoute.isNotEmpty()) {
            val mid = bestRoute[bestRoute.size / 2]
            map?.controller?.setCenter(mid)
            map?.controller?.setZoom(10.0)
        }

        map?.invalidate()

        // Fetch towns along the scenic route
        updateStatus("Fetching towns along ${routeType} route…")
        val towns = townService.getTownsAlongRoute(bestRoute, packageName, maxDistanceFromRoute = 5000.0)
        currentRouteTowns = towns
        Log.d("MainActivity", "Found ${towns.size} towns along the ${routeType} route")

        // Fetch general POIs along the scenic route for recommendations
        updateStatus("Fetching recommendations along ${routeType} route…")
        val routePois = withContext(Dispatchers.IO) {
            val pois = mutableListOf<Poi>()

            // Try route-based POI search
            val searchConfigs = listOf(
                Triple(800, 150, 150),
                Triple(800, 250, 200),
                Triple(800, 400, 250)
            )

            for ((sampleDist, overpassRadius, maxDistRoute) in searchConfigs) {
                val fetched = generateRecommendationsAlongRoute(
                    routePoints = bestRoute,
                    sampleDistMeters = sampleDist,
                    radiusMeters = overpassRadius,
                    maxDistToRouteMeters = maxDistRoute
                )
                if (fetched.isNotEmpty()) {
                    pois.addAll(fetched.filter { keepPoi(it) })
                    break
                }
            }

            // If no POIs found along route, try destination-centered search
            if (pois.isEmpty() && bestRoute.isNotEmpty()) {
                val destPoint = bestRoute.last()
                val fetched = generateRecommendations(destPoint, 5000)
                pois.addAll(fetched.filter { keepPoi(it) })
            }

            pois
        }

        Log.d("MainActivity", "Found ${routePois.size} general POIs along ${routeType} route")

        // Add POI markers to the map
        for (poi in routePois) {
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
                    cat.contains("park") || cat.contains("tree") || cat.contains("natural") || cat.contains("leisure") || cat.contains("playground") -> R.drawable.ic_tree
                    cat.contains("medical") || cat.contains("hospital") || cat.contains("clinic") || cat.contains("pharmacy") -> R.drawable.ic_medical
                    cat.contains("gas") || cat.contains("fuel") || cat.contains("petrol") || cat.contains("service_station") -> R.drawable.ic_gas
                    cat.contains("train") || cat.contains("station") -> R.drawable.ic_train
                    cat.contains("airport") || cat.contains("plane") || cat.contains("airfield") -> R.drawable.ic_plane
                    cat.contains("grocery") || cat.contains("mall") || cat.contains("supermarket") || cat.contains("shopping") || cat.contains("store") -> R.drawable.ic_cart
                    cat.contains("veterinary") || cat.contains("vet") || cat.contains("animal") || cat.contains("pet") -> R.drawable.ic_paw
                    cat.contains("scenic") || cat.contains("viewpoint") || cat.contains("landmark") || cat.contains("tourism") || cat.contains("attraction") || cat.contains("hotel") -> R.drawable.ic_scenic_marker
                    else -> R.drawable.ic_launcher_foreground
                }
                marker.icon = ResourcesCompat.getDrawable(resources, iconRes, theme)
                map?.overlays?.add(marker)
                currentRouteMarkers.add(marker)
                poiMarkerMap[poiKey(poi)] = marker
            }
        }

        // Update recommendations list - show towns first, then scenic POIs, then general POIs
        val recommendationItems = mutableListOf<RecommendationItem>()

        // Add towns
        recommendationItems.addAll(towns.map { town ->
            RecommendationItem.TownItem(town)
        })

        // Add scenic POIs (beaches, viewpoints, etc.)
        val scenicItems = filteredPois.take(15).map { poi ->
            RecommendationItem.ScenicItem(poi)
        }
        recommendationItems.addAll(scenicItems)

        // Add general POIs (restaurants, gas stations, etc.)
        val poiItems = routePois.take(20).map { poi ->
            RecommendationItem.PoiItem(poi)
        }
        recommendationItems.addAll(poiItems)

        poiAdapter.updateItems(recommendationItems)

        // Show summary snackbar
        withContext(Dispatchers.Main) {
            Snackbar.make(
                findViewById(R.id.main),
                "Route: ${towns.size} towns, ${filteredPois.size} scenic points, ${routePois.size} POIs",
                Snackbar.LENGTH_LONG
            ).show()
        }

        // Open the recommendations sheet
        try {
            if (::sheetBehavior.isInitialized) {
                sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        } catch (_: Exception) {}

        val routeLength = GeoUtils.computeRouteLength(bestRoute)
        val lengthKm = routeLength / 1000.0
        updateStatus("${routeType.replaceFirstChar { it.uppercase() }} scenic route: ${"%.1f".format(lengthKm)} km")
        clearStatusDelayed(5000)
    }
}

