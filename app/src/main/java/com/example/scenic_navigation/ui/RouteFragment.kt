package com.example.scenic_navigation.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.scenic_navigation.R
import com.example.scenic_navigation.databinding.FragmentRouteBinding
import com.example.scenic_navigation.models.ActivityType
import com.example.scenic_navigation.models.SeeingType
import com.example.scenic_navigation.models.CurationIntent
import com.example.scenic_navigation.services.LocationService
import com.example.scenic_navigation.utils.MapIconUtils
import com.example.scenic_navigation.utils.OffRouteDetector
import com.example.scenic_navigation.viewmodel.RouteViewModel
import com.example.scenic_navigation.viewmodel.SharedRouteViewModel
import com.example.scenic_navigation.data.FirestoreRepository
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.DrawableCompat
import android.location.LocationManager
import android.net.Uri
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URL
import kotlin.text.compareTo

class RouteFragment : Fragment(), SensorEventListener {
    private var _binding: FragmentRouteBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RouteViewModel by activityViewModels()
    private val sharedViewModel: SharedRouteViewModel by activityViewModels()

    private val routeMarkers = mutableListOf<Marker>()

    private var routePolyline: Polyline? = null
    private var traveledPolyline: Polyline? = null
    private var startMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var isInputCollapsed = false

    // Location tracking
    private lateinit var locationService: LocationService
    private var currentLocationMarker: Marker? = null
    private var offRouteDetector: OffRouteDetector? = null
    private var isNavigating = false

    // Sensor for orientation
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var azimuth: Float = 0f
    // Smoothed azimuth to reduce jitter in direction arrow
    private var smoothedAzimuth: Float? = null
    private val azimuthSmoothingAlpha = 0.12f // lower = smoother (but more lag)
    private val azimuthUpdateThresholdDeg = 3f // minimum change to trigger visual update

    // Auto-follow tracking
    private var lastUserInteractionTime = 0L
    private val autoFollowDelayMs = 5000L // 5 seconds
    private var isUserControllingMap = false
    private val autoFollowHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoFollowRunnable: Runnable? = null

    // Firestore helper
    private val firestoreRepo = FirestoreRepository()

    // Permission request launcher
    private val locationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            // Permission granted — start location tracking immediately so the user arrow is visible on launch
            startLocationTracking()
        } else {
            // Permission denied, show explanation
            Snackbar.make(
                binding.root,
                "Location permission is required for navigation features",
                Snackbar.LENGTH_LONG
            ).setAction("Grant") {
                requestLocationPermission()
            }.show()
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        locationService = LocationService(requireContext())

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        setupMap()
        setupMapInteractionListener()
        setupInputs()
        setupCollapseButton()
        setupSettingsButton()
        restoreRouteIfAvailable()
        observeViewModel()
        startClusterPolling()

        // Request location permission on app start
        checkAndRequestLocationPermission()

        // Load remote last selection if available and apply to UI
        loadRemoteSelectionIfAvailable()

        // Setup copyright click listener
        binding.osmCopyright.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://osm.org/copyright"))
            startActivity(intent)
        }

        // Wire up End Route button if present in the layout
        try {
            val endBtn = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton?>(R.id.btn_end_route)
            endBtn?.setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("End route")
                    .setMessage("Are you sure you want to end the current route?")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> endCurrentRoute() }
                    .show()
            }
        } catch (e: Exception) {
            Log.w("RouteFragment", "btn_end_route not found or failed to wire", e)
            // fallback: try to create programmatically
            createEndRouteButton()
        }

        // Initialize button elevations: card starts expanded, so buttons should be behind it
        binding.btnCenter.translationZ = -10f
        view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_end_route)?.translationZ = -10f
    }

    private var clusterPollRunnable: Runnable? = null
    private var lastZoomLevel: Double = -1.0

    private fun startClusterPolling() {
        stopClusterPolling()
        lastZoomLevel = binding.map.zoomLevelDouble
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val zoom = binding.map.zoomLevelDouble
                    if (zoom != lastZoomLevel) {
                        lastZoomLevel = zoom
                        view?.let { v ->
                            viewModel.routePois.value?.let { updateMarkers(it) }
                        }
                    }
                } catch (_: Exception) {
                }
                handler.postDelayed(this, 800)
            }
        }
        clusterPollRunnable = runnable
        handler.postDelayed(runnable, 800)
    }

    private fun stopClusterPolling() {
        clusterPollRunnable = null
    }

    private fun setupSettingsButton() {
        binding.btnSettings.setOnClickListener {
            val ctx = requireContext()
            val intent = android.content.Intent(ctx, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupMap() {
        val prefs = requireContext().getSharedPreferences("scenic_prefs", android.content.Context.MODE_PRIVATE)
        val style = prefs.getString("map_style", "Streets") ?: "Streets"
        val tileSource = when (style) {
            "Streets" -> TileSourceFactory.MAPNIK
            "Satellite" -> TileSourceFactory.USGS_SAT
            "Topo" -> TileSourceFactory.USGS_TOPO
            else -> TileSourceFactory.MAPNIK
        }

        // Default fallback center (Manila)
        val defaultCenter = GeoPoint(14.5995, 120.9842)
        var centerPoint = defaultCenter
        var foundLocation = false

        // If we have permission, try to use the last known location (GPS then NETWORK)
        if (locationService.hasLocationPermission()) {
            try {
                val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                for (p in providers) {
                    try {
                        val loc = lm.getLastKnownLocation(p)
                        if (loc != null) {
                            centerPoint = GeoPoint(loc.latitude, loc.longitude)
                            foundLocation = true
                            break
                        }
                    } catch (_: SecurityException) {
                        // ignore and continue
                    } catch (_: Exception) {
                        // provider may not be available; continue
                    }
                }
            } catch (_: Exception) {
                // fallback to defaultCenter
            }
        }

        binding.map.apply {
            setTileSource(tileSource)
            setMultiTouchControls(true)
            // multi-touch enabled; explicitly hide osmdroid zoom controller at runtime to avoid showing +/− buttons
            try {
                // Use valueOf to avoid compile-time dependency on specific enum constant names across osmdroid versions
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.valueOf("NEVER"))
            } catch (_: Exception) {
                // If the enum constant isn't available or zoomController isn't present, ignore — multi-touch still works
            }
             // Use a slightly higher zoom when centering on current location
             controller.setCenter(centerPoint)
             controller.setZoom(if (foundLocation) 18.0 else 16.0)
         }

        // If we have a last-known location, show the user arrow marker immediately so it appears on app launch
        if (foundLocation) {
            // Create or update the current location marker (arrow)
            if (currentLocationMarker == null) {
                currentLocationMarker = Marker(binding.map).apply {
                    position = centerPoint
                    title = getString(R.string.current_location)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_user_arrow, null)
                    rotation = azimuth
                }
                binding.map.overlays.add(currentLocationMarker)
            } else {
                currentLocationMarker?.position = centerPoint
                currentLocationMarker?.rotation = azimuth
                if (!binding.map.overlays.contains(currentLocationMarker)) {
                    binding.map.overlays.add(currentLocationMarker)
                }
            }
            binding.map.invalidate()
        }
    }

    private fun setupMapInteractionListener() {
        // Ensure center button is visible (do not hide it)
        binding.btnCenter.visibility = View.VISIBLE

        // Clicking the center button recenters the map on the user and hides the button
        binding.btnCenter.setOnClickListener {
            val DEFAULT_USER_ZOOM = 18.0
            val target = currentLocationMarker?.position
            val currentZoom = binding.map.zoomLevelDouble
            if (target != null) {
                // Center on current location and, if zoomed out, set to default zoom
                binding.map.controller.apply {
                    animateTo(target)
                    if (currentZoom < DEFAULT_USER_ZOOM) setZoom(DEFAULT_USER_ZOOM)
                }
            } else {
                 // fallback: try last known location and apply default zoom when zoomed out
                 try {
                     val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                     val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                         ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                     loc?.let {
                         val gp = GeoPoint(it.latitude, it.longitude)
                         binding.map.controller.apply {
                             animateTo(gp)
                             if (currentZoom < DEFAULT_USER_ZOOM) setZoom(DEFAULT_USER_ZOOM)
                         }
                     }
                 } catch (_: Exception) { }
             }
             isUserControllingMap = false
             autoFollowRunnable?.let { autoFollowHandler.removeCallbacks(it) }
             // Do not hide the center button
         }

        binding.map.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    // User is interacting with map
                    isUserControllingMap = true
                    lastUserInteractionTime = System.currentTimeMillis()

                    // Keep the center button visible (don't change visibility)

                    // Cancel any pending auto-follow restoration
                    autoFollowRunnable?.let { autoFollowHandler.removeCallbacks(it) }

                    // Schedule auto-follow restoration after delay and hide the button
                    autoFollowRunnable = Runnable {
                        if (System.currentTimeMillis() - lastUserInteractionTime >= autoFollowDelayMs) {
                            isUserControllingMap = false
                            // keep center button visible
                        }
                    }
                    autoFollowHandler.postDelayed(autoFollowRunnable!!, autoFollowDelayMs)
                }
            }
            false // Don't consume the event
        }
    }

    private lateinit var startPlaceAdapter: PlaceSuggestionAdapter
    private lateinit var destPlaceAdapter: PlaceSuggestionAdapter

    private fun setupInputs() {
        // Start input is now fixed to current location
        binding.etStart.setText(getString(R.string.current_location))
        binding.etStart.isEnabled = false
        binding.tilStart.isEnabled = false

        // Setup autocomplete adapters for place suggestions with typo correction
        val packageName = requireContext().packageName
        startPlaceAdapter = PlaceSuggestionAdapter(requireContext(), packageName)
        destPlaceAdapter = PlaceSuggestionAdapter(requireContext(), packageName)

        binding.etStart.setAdapter(startPlaceAdapter)
        binding.etDestination.setAdapter(destPlaceAdapter)

        // Set threshold for triggering autocomplete (2 characters)
        binding.etStart.threshold = 2
        binding.etDestination.threshold = 2

        // Handle item selection from autocomplete
        binding.etStart.setOnItemClickListener { parent, _, position, _ ->
            val selected = startPlaceAdapter.getItem(position)
            binding.etStart.setText(selected)
            binding.tilStart.error = null
        }

        binding.etDestination.setOnItemClickListener { parent, _, position, _ ->
            val selected = destPlaceAdapter.getItem(position)
            binding.etDestination.setText(selected)
            binding.tilDestination.error = null
        }

        // Setup dropdowns with icons using localized strings
        val seeingStrings = resources.getStringArray(R.array.seeing_options)
        val seeingItems = listOf(
            IconItem(seeingStrings[0], R.drawable.ic_oceanic_view),
            IconItem(seeingStrings[1], R.drawable.ic_mountain_ranges)
        )
        val seeingAdapter = IconArrayAdapter(requireContext(), seeingItems)
        binding.actvSeeing.setAdapter(seeingAdapter)

        val activityStrings = resources.getStringArray(R.array.activity_options)
        val activityItems = listOf(
            IconItem(activityStrings[0], R.drawable.ic_sight_seeing),
            IconItem(activityStrings[1], R.drawable.ic_shop_and_dine),
            IconItem(activityStrings[2], R.drawable.ic_cultural_activities),
            IconItem(activityStrings[3], R.drawable.ic_adventure_hiking),
            IconItem(activityStrings[4], R.drawable.ic_relaxation_wellness),
            IconItem(activityStrings[5], R.drawable.ic_family_friendly),
            IconItem(activityStrings[6], R.drawable.ic_romantic_getaway)
        )
        val activityAdapter = IconArrayAdapter(requireContext(), activityItems)
        binding.actvActivity.setAdapter(activityAdapter)

        // Handle item clicks to display text properly
        binding.actvSeeing.setOnItemClickListener { parent, _, position, _ ->
            val selectedItem = seeingAdapter.getItem(position)
            binding.actvSeeing.setText(selectedItem?.text, false)
            // Show corresponding icon on the TextInputLayout start icon
            selectedItem?.let {
                try {
                    val d = ContextCompat.getDrawable(requireContext(), it.iconResId)
                    d?.let { drawable ->
                        val wrapped = DrawableCompat.wrap(drawable).mutate()
                        DrawableCompat.setTintList(wrapped, null)
                        wrapped.clearColorFilter()
                        binding.tilSeeing.startIconDrawable = wrapped
                        binding.tilSeeing.setStartIconTintList(null)
                        binding.tilSeeing.isStartIconVisible = true
                    }
                } catch (_: Exception) { }
            }
        }

        binding.actvActivity.setOnItemClickListener { parent, _, position, _ ->
            val selectedItem = activityAdapter.getItem(position)
            binding.actvActivity.setText(selectedItem?.text, false)
            // Show corresponding icon on the TextInputLayout start icon
            selectedItem?.let {
                try {
                    val d = ContextCompat.getDrawable(requireContext(), it.iconResId)
                    d?.let { drawable ->
                        val wrapped = DrawableCompat.wrap(drawable).mutate()
                        DrawableCompat.setTintList(wrapped, null)
                        wrapped.clearColorFilter()
                        binding.tilActivity.startIconDrawable = wrapped
                        binding.tilActivity.setStartIconTintList(null)
                        binding.tilActivity.isStartIconVisible = true
                    }
                } catch (_: Exception) { }
            }
        }


        // Plan route button click
        binding.btnPlan.setOnClickListener {
            val destination = binding.etDestination.text.toString()
            if (destination.isBlank()) {
                binding.tilDestination.error = "Please enter a destination"
                return@setOnClickListener
            }

            val seeingString = binding.actvSeeing.text.toString()
            val activityString = binding.actvActivity.text.toString()

            // Get localized strings for comparison
            val seeingStrings = resources.getStringArray(R.array.seeing_options)
            val activityStrings = resources.getStringArray(R.array.activity_options)

            val seeing = when (seeingString) {
                seeingStrings[0] -> SeeingType.OCEANIC  // Oceanic View / Tanawin ng Karagatan
                seeingStrings[1] -> SeeingType.MOUNTAIN  // Mountain Ranges / Mga Bundok
                else -> SeeingType.OCEANIC // Default value
            }

            val activity = when (activityString) {
                activityStrings[0] -> ActivityType.SIGHTSEEING  // Sight seeing / Paglilibot
                activityStrings[1] -> ActivityType.SHOP_AND_DINE  // Shop and Dine / Pamimili at Pagkain
                activityStrings[2] -> ActivityType.CULTURAL  // Cultural activities / Mga Aktibidad sa Kultura
                activityStrings[3] -> ActivityType.ADVENTURE  // Adventure & Hiking
                activityStrings[4] -> ActivityType.RELAXATION  // Relaxation & Wellness / Pamamahinga at Wellness
                activityStrings[5] -> ActivityType.FAMILY_FRIENDLY  // Family Friendly / Pamilya
                activityStrings[6] -> ActivityType.ROMANTIC  // Romantic Getaway / Romantic na Getaway
                else -> ActivityType.SIGHTSEEING // Default value
            }

            viewModel.planRouteCurated(destination, seeing, activity)

            // Save selection to Firestore (and keep local fallback)
            saveSelectionToFirestore(destination, seeing, activity)
        }

        // Advanced options toggle
        /*
        binding.advancedOptionsHeader.setOnClickListener {
            if (binding.advancedOptionsContent.visibility == View.VISIBLE) {
                binding.advancedOptionsContent.visibility = View.GONE
                binding.advancedOptionsArrow.setImageResource(android.R.drawable.arrow_down_float)
            } else {
                binding.advancedOptionsContent.visibility = View.VISIBLE
                binding.advancedOptionsArrow.setImageResource(android.R.drawable.arrow_up_float)
            }
        }
        */

        // Clear errors on text change
        binding.etStart.doOnTextChanged { _, _, _, _ ->
            binding.tilStart.error = null
        }
        binding.etDestination.doOnTextChanged { _, _, _, _ ->
            binding.tilDestination.error = null
        }
    }


    private fun changeTileSource(style: String) {
        val tileSource = when (style) {
            "Streets" -> TileSourceFactory.MAPNIK
            "Satellite" -> TileSourceFactory.USGS_SAT
            "Topo" -> TileSourceFactory.USGS_TOPO
            else -> TileSourceFactory.MAPNIK
        }
        binding.map.setTileSource(tileSource)
        binding.map.invalidate()
    }

    private fun setupCollapseButton() {
        binding.btnCollapse.setOnClickListener {
            toggleInputCollapse()
        }
    }

    private fun toggleInputCollapse() {
        isInputCollapsed = !isInputCollapsed

        if (isInputCollapsed) {
            // Collapse the inputs
            binding.collapsibleContent.visibility = View.GONE
            binding.btnCollapse.text = "▲"

            // Animate the card to be smaller
            binding.cardInput.animate()
                .alpha(0.9f)
                .scaleY(0.95f)
                .setDuration(200)
                .start()

            // Reset button elevation to normal when collapsed
            binding.btnCenter.translationZ = 0f
            view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_end_route)?.translationZ = 0f

            // Show the legend overlay card when the input card is collapsed
            try {
                binding.legendOverlayCard.visibility = View.VISIBLE
            } catch (_: Exception) {}
        } else {
            // Expand the inputs
            binding.collapsibleContent.visibility = View.VISIBLE
            binding.btnCollapse.text = "▼"

            // Animate the card back to normal
            binding.cardInput.animate()
                .alpha(1.0f)
                .scaleY(1.0f)
                .setDuration(200)
                .start()

            // Lower button elevation so they appear behind the expanded card
            binding.btnCenter.translationZ = -10f
            view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_end_route)?.translationZ = -10f

            // Hide the legend overlay card when the input card is expanded
            try {
                binding.legendOverlayCard.visibility = View.GONE
            } catch (_: Exception) {}
        }
    }

    private fun restoreRouteIfAvailable() {
        // Restore route from shared ViewModel if available (only on initial load)
        if (sharedViewModel.hasRoute()) {
            sharedViewModel.routePoints.value?.let { points ->
                updateRoute(points)
            }
            sharedViewModel.routePois.value?.let { pois ->
                updateMarkers(pois)
            }
            sharedViewModel.mapCenter.value?.let { center ->
                sharedViewModel.mapZoom.value?.let { zoom ->
                    binding.map.controller.apply {
                        setCenter(center)
                        setZoom(zoom)
                    }
                }
            }
        }
    }

    private fun observeViewModel() {
        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressGeocoding.visibility = if (loading) View.VISIBLE else View.GONE
            binding.btnPlan.isEnabled = !loading
            binding.cardInput.isEnabled = !loading
            sharedViewModel.setLoading(loading)
            binding.progressOverlay.visibility = if (loading) View.VISIBLE else View.GONE

            // Dim and disable the floating action buttons during loading
            val buttonAlpha = if (loading) 0.3f else 1.0f
            binding.btnCenter.alpha = buttonAlpha
            binding.btnCenter.isEnabled = !loading
            view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_end_route)?.apply {
                alpha = buttonAlpha
                isEnabled = !loading
            }
        }

        // Observe phased loading flags for more granular UI
        viewModel.isGeocoding.observe(viewLifecycleOwner) { g ->
            binding.progressGeocoding.visibility = if (g) View.VISIBLE else View.GONE
            if (g) binding.tvOverlayStatus.text = getString(R.string.geocoding_status)
        }

        viewModel.isRouting.observe(viewLifecycleOwner) { r ->
            binding.progressRouting.visibility = if (r) View.VISIBLE else View.GONE
            if (r) binding.tvOverlayStatus.text = getString(R.string.routing_status)
        }

        viewModel.isFetchingPois.observe(viewLifecycleOwner) { p ->
            binding.progressPoiFetch.visibility = if (p) View.VISIBLE else View.GONE
            if (p) binding.tvOverlayStatus.text = getString(R.string.finding_pois_status)
        }

        // Observe route summary values
        viewModel.routeDistanceMeters.observe(viewLifecycleOwner) { meters ->
            if (meters != null && meters > 0.0) {
                val km = meters / 1000.0
                val distText = if (km >= 1.0) "%.1f km".format(km) else "${meters.toInt()} m"
                binding.tvOverlayDistance.text = distText
            } else {
                binding.tvOverlayDistance.text = getString(R.string.overlay_distance_placeholder)
            }
        }

        viewModel.routeDurationSeconds.observe(viewLifecycleOwner) { secs ->
            if (secs != null && secs > 0L) {
                val hours = secs / 3600
                val mins = (secs % 3600) / 60
                val etaText = if (hours > 0) "${hours}h ${mins}m" else "${mins} min"
                binding.tvOverlayEta.text = etaText
            } else {
                binding.tvOverlayEta.text = getString(R.string.overlay_eta_placeholder)
            }
        }

        viewModel.scenicScore.observe(viewLifecycleOwner) { score ->
            if (score != null && score > 0f) {
            } else {
            }
        }

        // Observe status messages
        viewModel.statusMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = it
                sharedViewModel.setStatusMessage(it)

                // Status messages shown only in overlay, no Snackbar popup
                // update overlay status text if visible
                binding.tvOverlayStatus.text = it
            }
        }

        // Observe route points
        viewModel.routePoints.observe(viewLifecycleOwner) { points ->
            updateRoute(points)
            // Update shared ViewModel only if both points and POIs are ready
            updateSharedViewModelIfReady()

            // Auto-collapse input menu when route is successfully generated
            if (points.isNotEmpty() && !isInputCollapsed) {
                toggleInputCollapse()
            }
        }

        // Observe route POIs
        viewModel.routePois.observe(viewLifecycleOwner) { pois ->
            updateMarkers(pois)
            // Update shared ViewModel only if both points and POIs are ready
            updateSharedViewModelIfReady()
        }
    }

    private fun updateSharedViewModelIfReady() {
        val points = viewModel.routePoints.value ?: emptyList()
        val pois = viewModel.routePois.value ?: emptyList()
        // Only update if we have actual data (not initial empty lists)
        if (points.isNotEmpty() || pois.isNotEmpty()) {
            sharedViewModel.updateRouteData(points, pois)
        }
    }

    /**
     * Start continuous location tracking for navigation
     */
    private fun startLocationTracking() {
        if (!locationService.hasLocationPermission()) {
            Snackbar.make(binding.root, "Location permission needed for navigation", Snackbar.LENGTH_SHORT).show()
            return
        }

        isNavigating = true
        locationService.startLocationTracking(
            onLocationUpdate = { location ->
                updateCurrentLocationOnMap(location)
                offRouteDetector?.updateLocation(location)
            },
            intervalMillis = 3000L // Update every 3 seconds
        )
    }

    /**
     * Stop location tracking
     */
    private fun stopLocationTracking() {
        isNavigating = false
        locationService.stopLocationTracking()
    }

    /**
     * Update current location marker on map and center map on user
     */
    private fun updateCurrentLocationOnMap(location: Location) {
        val userPosition = GeoPoint(location.latitude, location.longitude)

        // Update or create current location marker
        if (currentLocationMarker == null) {
            currentLocationMarker = Marker(binding.map).apply {
                position = userPosition
                title = getString(R.string.current_location)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_user_arrow, null)
                binding.map.overlays.add(this)
            }
        } else {
            currentLocationMarker?.position = userPosition
        }

        // If sensors aren't available or give poor data, optionally use location bearing when moving
        if (rotationVectorSensor == null) {
            // Use bearing only if it's valid and the device is moving
            if (location.hasBearing() && location.speed > 1.0f) {
                val bearing = normalize360(location.bearing)
                // Smooth against previous smoothedAzimuth
                val prev = smoothedAzimuth ?: bearing
                val delta = shortestSignedAngle(normalize360(bearing - prev))
                val next = normalize360(prev + azimuthSmoothingAlpha * delta)
                smoothedAzimuth = next
                val angleDiff = Math.abs(shortestSignedAngle(normalize360(next - (currentLocationMarker?.rotation ?: next))))
                if (angleDiff >= azimuthUpdateThresholdDeg) {
                    currentLocationMarker?.rotation = next
                }
            }
        }

        // Rotation is now handled by sensor in onSensorChanged

        // Update route visualization to show traveled vs remaining path
        updateRouteVisualization(userPosition)

        // Only auto-follow if user hasn't manually moved the map recently
        if (!isUserControllingMap) {
            binding.map.controller.animateTo(userPosition)
        }

        binding.map.invalidate()
    }

    /**
     * Update route visualization to show traveled path in dark gray
     */
    private fun updateRouteVisualization(userPosition: GeoPoint) {
        val routePoints = viewModel.routePoints.value ?: return
        if (routePoints.size < 2) return

        // Find the closest point on the route to the user's current location
        val (closestIndex, _) = findClosestPointOnRoute(userPosition, routePoints)

        // Split route into traveled and remaining parts
        val traveledPoints = routePoints.take(closestIndex + 1)
        val remainingPoints = routePoints.drop(closestIndex)

        // Clear previous traveled polyline
        traveledPolyline?.let { binding.map.overlays.remove(it) }

        // Draw traveled path in dark gray instead of dark blue
        if (traveledPoints.size >= 2) {
            traveledPolyline = Polyline().apply {
                setPoints(traveledPoints)
                outlinePaint.color = android.graphics.Color.parseColor("#424242") // Dark gray
                outlinePaint.strokeWidth = 12.0f  // Match main route thickness
            }
            binding.map.overlays.add(traveledPolyline)
            // Ensure traveled polyline is drawn below the remaining polyline
            traveledPolyline?.let { binding.map.overlays.remove(it) }
            binding.map.overlays.add(0, traveledPolyline!!)
        }

        // Update remaining path (lighter blue, already exists as routePolyline)
        if (remainingPoints.size >= 2) {
            routePolyline?.setPoints(remainingPoints)
        }
    }

    /**
     * Find the closest point on the route to the given position
     * Returns the index of the closest point and the distance
     */
    private fun findClosestPointOnRoute(position: GeoPoint, routePoints: List<GeoPoint>): Pair<Int, Double> {
        var closestIndex = 0
        var minDistance = Double.MAX_VALUE

        for (i in routePoints.indices) {
            val point = routePoints[i]
            val distance = position.distanceToAsDouble(point)
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }

        return Pair(closestIndex, minDistance)
    }

    private fun updateRoute(points: List<GeoPoint>) {
        // Clear previous overlays
        routePolyline?.let { binding.map.overlays.remove(it) }
        traveledPolyline?.let { binding.map.overlays.remove(it) }
        startMarker?.let { binding.map.overlays.remove(it) }
        destinationMarker?.let { binding.map.overlays.remove(it) }

        if (points.isNotEmpty()) {
            val polyline = Polyline().apply {
                setPoints(points)
                outlinePaint.color = android.graphics.Color.parseColor("#2196F3")
                outlinePaint.strokeWidth = 12.0f
            }
            binding.map.overlays.add(polyline)
            routePolyline = polyline

            // Add start and destination markers
            val startPoint = points.first()
            startMarker = Marker(binding.map).apply {
                position = startPoint
                title = "Start"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_pin_green, null)
            }
            binding.map.overlays.add(startMarker)

            val endPoint = points.last()
            destinationMarker = Marker(binding.map).apply {
                position = endPoint
                title = "Destination"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_pin_red, null)
            }
            binding.map.overlays.add(destinationMarker)

            // Only center and zoom if not currently navigating (initial route planning)
            if (!isNavigating) {
                // Prefer centering on the user's current location if we have it (so the user stays in view).
                // Fallback order: currentLocationMarker -> last-known device location -> route start point.
                val preferredCenter = currentLocationMarker?.position ?: getLastKnownGeoPoint() ?: startPoint
                binding.map.controller.apply {
                    setCenter(preferredCenter)
                    setZoom(16.0)  // Increased from 10.0 to 16.0
                }
            }
            binding.map.invalidate()

            // Initialize off-route detector and start location tracking
            initializeOffRouteDetector(points)

            // Start tracking location when route is available
            if (!isNavigating && locationService.hasLocationPermission()) {
                startLocationTracking()
            }

            // Show the End Route button when a route is active (handle both XML and programmatic buttons)
            view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_end_route)?.visibility = View.VISIBLE
            (view as? ViewGroup)?.findViewWithTag<View>("end_route_btn")?.visibility = View.VISIBLE
        } else {
            // Stop tracking when no route
            if (isNavigating) {
                stopLocationTracking()
            }
            offRouteDetector = null

            // Hide the End Route button when no route is active (handle both XML and programmatic buttons)
            view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_end_route)?.visibility = View.GONE
            (view as? ViewGroup)?.findViewWithTag<View>("end_route_btn")?.visibility = View.GONE
        }
    }

    private fun updateMarkers(pois: List<com.example.scenic_navigation.models.Poi>) {
         // Clear previous markers
         routeMarkers.forEach { binding.map.overlays.remove(it) }
         routeMarkers.clear()
         // Read clustering pref (meters). Fall back to default
         val prefs = requireContext().getSharedPreferences("scenic_prefs", android.content.Context.MODE_PRIVATE)
         val epsMeters = prefs.getString(com.example.scenic_navigation.config.Config.PREF_CLUSTER_EPS_KEY, com.example.scenic_navigation.config.Config.DEFAULT_CLUSTER_EPS_METERS.toString())?.toDoubleOrNull()
             ?: com.example.scenic_navigation.config.Config.DEFAULT_CLUSTER_EPS_METERS

        // If user is zoomed in, show individual POIs (decluster) for a better inspection experience.
        val DECLUSTER_ZOOM = 15.0 // zoom levels >= this will disable clustering
        val currentZoom = try { binding.map.zoomLevelDouble } catch (_: Exception) { -1.0 }
        if (currentZoom >= DECLUSTER_ZOOM) {
            // Create a marker per POI (no clustering)
            for (poi in pois) {
                val marker = Marker(binding.map).apply {
                    position = GeoPoint(poi.lat ?: 0.0, poi.lon ?: 0.0)
                    title = poi.name
                    snippet = poi.description
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    try {
                        val bmp = MapIconUtils.createPoiIconPreferDrawable(requireContext(), poi, 88)
                        icon = BitmapDrawable(requireContext().resources, bmp)
                        Log.d("RouteFragment", "Assigned POI icon for '${poi.name}' (bmp ${bmp.width}x${bmp.height}) [declustered]")
                    } catch (e: Exception) {
                        Log.w("RouteFragment", "Failed to create POI icon, using fallback", e)
                        val color = MapIconUtils.getCategoryColor(poi.category)
                        val fallback = createSolidCircleDrawable(color, 72)
                        DrawableCompat.setTintList(fallback, null)
                        fallback.clearColorFilter()
                        icon = fallback
                        Log.d("RouteFragment", "Assigned fallback POI icon for '${poi.name}' [declustered]")
                    }
                    setOnMarkerClickListener { m, _ ->
                        val bottom = POIDetailBottomSheet(poi)
                        bottom.show(parentFragmentManager, "poi_detail")
                        true
                    }
                }
                binding.map.overlays.add(marker)
                routeMarkers.add(marker)
            }

            // Populate horizontal preview RecyclerView with first N POIs (unclustered ordering)
            val rv = binding.rvPoiPreviews
            if (pois.isNotEmpty()) {
                rv.visibility = View.VISIBLE
                val adapter = com.example.scenic_navigation.PoiPreviewAdapter(pois.take(20)) { poi ->
                    // Center map on selected preview and open detail
                    poi.lat?.let { lat ->
                        poi.lon?.let { lon ->
                            binding.map.controller.setCenter(GeoPoint(lat, lon))
                            binding.map.controller.setZoom(14.0)
                            val bottom = POIDetailBottomSheet(poi)
                            bottom.show(parentFragmentManager, "poi_detail")
                        }
                    }
                }
                rv.adapter = adapter
                rv.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
            } else {
                rv.visibility = View.GONE
            }

            binding.map.invalidate()
            return
        }

        // Simple greedy clustering: group POIs within epsMeters of cluster centroid
        data class Cluster(var latSum: Double = 0.0, var lonSum: Double = 0.0, val members: MutableList<com.example.scenic_navigation.models.Poi> = mutableListOf()) {
            fun add(p: com.example.scenic_navigation.models.Poi) {
                members.add(p)
                latSum += p.lat ?: 0.0
                lonSum += p.lon ?: 0.0
            }
            fun centroid(): Pair<Double, Double> {
                val count = members.size
                return if (count == 0) Pair(0.0, 0.0) else Pair(latSum / count, lonSum / count)
            }
        }

        val clusters = mutableListOf<Cluster>()

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return R * c
        }

        for (poi in pois) {
            val plat = poi.lat ?: continue
            val plon = poi.lon ?: continue
            var placed = false
            for (cluster in clusters) {
                val (cx, cy) = cluster.centroid()
                val dist = haversineMeters(plat, plon, cx, cy)
                if (dist <= epsMeters) {
                    cluster.add(poi)
                    placed = true
                    break
                }
            }
            if (!placed) {
                val c = Cluster()
                c.add(poi)
                clusters.add(c)
            }
        }

        // Create markers for clusters
        // Lazy clustering: only cluster POIs inside current map bbox for performance
        val bbox = binding.map.boundingBox
        val visibleClusters = clusters.map { cl ->
            val (lat, lon) = cl.centroid()
            val inView = lat >= bbox.latSouth && lat <= bbox.latNorth && lon >= bbox.lonWest && lon <= bbox.lonEast
            Pair(cl, inView)
        }

        for ((cluster, inView) in visibleClusters) {
            val count = cluster.members.size
            val (clat, clon) = cluster.centroid()
            if (count <= 1) {
                val poi = cluster.members.first()
                val marker = Marker(binding.map).apply {
                    position = GeoPoint(poi.lat ?: 0.0, poi.lon ?: 0.0)
                    title = poi.name
                    snippet = poi.description
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    // Use custom icon based on category and scenic score
                    try {
                        val bmp = MapIconUtils.createPoiIconPreferDrawable(requireContext(), poi, 88)
                        icon = BitmapDrawable(requireContext().resources, bmp)
                        Log.d("RouteFragment", "Assigned POI icon for '${poi.name}' (bmp ${bmp.width}x${bmp.height})")
                    } catch (e: Exception) {
                        Log.w("RouteFragment", "Failed to create POI icon, using fallback", e)
                        val color = MapIconUtils.getCategoryColor(poi.category)
                        val fallback = createSolidCircleDrawable(color, 72)
                        DrawableCompat.setTintList(fallback, null)
                        fallback.clearColorFilter()
                        icon = fallback
                        Log.d("RouteFragment", "Assigned fallback POI icon for '${poi.name}'")
                    }
                    setOnMarkerClickListener { m, _ ->
                        val bottom = POIDetailBottomSheet(poi)
                        bottom.show(parentFragmentManager, "poi_detail")
                        true
                    }
                }
                binding.map.overlays.add(marker)
                routeMarkers.add(marker)
            } else {
                // Create cluster marker with generated icon (styled to theme)
                val membersCopy = cluster.members.toList()
                // Compute average scenic score for cluster (if available)
                val avgScore = try {
                    val scores = cluster.members.mapNotNull { it.scenicScore }
                    if (scores.isNotEmpty()) scores.average().toFloat() else 0f
                } catch (_: Exception) { 0f }

                val marker = Marker(binding.map).apply {
                    position = GeoPoint(clat, clon)
                    title = "$count POIs"
                    snippet = cluster.members.joinToString(", ") { it.name }.take(200)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    try {
                        val bmp = createClusterIcon(count, avgScore)
                        icon = BitmapDrawable(requireContext().resources, bmp)
                        Log.d("RouteFragment", "Assigned cluster icon for count=$count avgScore=$avgScore")
                    } catch (e: Exception) {
                        Log.w("RouteFragment", "Failed to create cluster icon, using fallback", e)
                        // Choose fallback color based on avgScore
                        val fillColor = when {
                            avgScore >= 75f -> android.graphics.Color.parseColor("#2E7D32")
                            avgScore >= 50f -> android.graphics.Color.parseColor("#FFC107")
                            avgScore >= 25f -> android.graphics.Color.parseColor("#FF7043")
                            else -> android.graphics.Color.parseColor("#D32F2F")
                        }
                        val fallbackCluster = createSolidCircleDrawable(fillColor, 120)
                        DrawableCompat.setTintList(fallbackCluster, null)
                        fallbackCluster.clearColorFilter()
                        icon = fallbackCluster
                        Log.d("RouteFragment", "Assigned fallback cluster icon for count=$count avgScore=$avgScore")
                    }
                    setOnMarkerClickListener { m, _ ->
                        // Open a bottom sheet listing cluster members
                        val sheet = com.example.scenic_navigation.ui.ClusterListBottomSheet(membersCopy)
                        sheet.show(parentFragmentManager, "cluster_list")
                        true
                    }
                }
                binding.map.overlays.add(marker)
                routeMarkers.add(marker)
            }
        }
        binding.map.invalidate()
    }

    // Helper: create a simple solid circular BitmapDrawable fallback to use if icon generation fails
    private fun createSolidCircleDrawable(color: Int, diameter: Int): BitmapDrawable {
        val bmp = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(diameter / 2f, diameter / 2f, diameter / 2f - 2f, paint)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        stroke.style = Paint.Style.STROKE
        stroke.color = android.graphics.Color.WHITE
        stroke.strokeWidth = 4f
        canvas.drawCircle(diameter / 2f, diameter / 2f, diameter / 2f - 2f, stroke)
        val drawable = BitmapDrawable(requireContext().resources, bmp)
        drawable.alpha = 255
        drawable.setBounds(0, 0, bmp.width, bmp.height)
        return drawable
    }


    private fun createClusterIcon(count: Int, avgScore: Float): Bitmap {
        val size = 120
        val radius = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.alpha = 255

        // Color by avgScore: green high -> red low
        val fillColor = when {
            avgScore >= 75f -> android.graphics.Color.parseColor("#2E7D32")
            avgScore >= 50f -> android.graphics.Color.parseColor("#FFC107")
            avgScore >= 25f -> android.graphics.Color.parseColor("#FF7043")
            else -> android.graphics.Color.parseColor("#D32F2F")
        }

        paint.color = fillColor
        // draw filled circle
        canvas.drawCircle(radius, radius, radius - 4f, paint)

        // draw outer stroke for contrast
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 6f
        strokePaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(radius, radius, radius - 4f, strokePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = android.graphics.Color.WHITE
        textPaint.textSize = 42f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.isFakeBoldText = true
        val text = count.toString()
        val textWidth = textPaint.measureText(text)
        val x = (size - textWidth) / 2f
        val fm = textPaint.fontMetrics
        val y = (size - fm.ascent - fm.descent) / 2f
        canvas.drawText(text, x, y, textPaint)

        // Debug: log center pixel color for cluster icon
        try {
            val cx = size / 2
            val cy = size / 2
            val centerColor = bmp.getPixel(cx, cy)
            Log.d("RouteFragment", "createClusterIcon centerColor=#${Integer.toHexString(centerColor)} count=$count avgScore=$avgScore")
        } catch (e: Exception) {
            Log.w("RouteFragment", "Failed to read center pixel of cluster icon", e)
        }

        return bmp
    }

    /**
     * Initialize off-route detector when route is available
     */
    private fun initializeOffRouteDetector(routePoints: List<GeoPoint>) {
        if (routePoints.isEmpty()) {
            offRouteDetector = null
            return
        }

        offRouteDetector = OffRouteDetector(
            routePoints = routePoints,
            thresholdMeters = 50f, // 50 meters off-route threshold
            returnThresholdMeters = 30f,
            requiredConsecutive = 2,
            cooldownMs = 20000L // 20 seconds cooldown between reroutes
        ) { location ->
            // Callback when user goes off route
            handleOffRoute(location)
        }
    }

    /**
     * Handle off-route event - recalculate route
     */
    private fun handleOffRoute(location: Location) {
        val newStart = GeoPoint(location.latitude, location.longitude)

        // Recalculate route from current location silently
        viewModel.recalculateRouteFromLocation(newStart)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        setupMap() // Refresh map style in case it changed in settings

        rotationVectorSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()

        sensorManager.unregisterListener(this)

        // Save current map position to shared ViewModel
        val center = binding.map.mapCenter as? GeoPoint
        val zoom = binding.map.zoomLevelDouble
        if (center != null) {
            sharedViewModel.updateMapPosition(center, zoom)
        }
        stopClusterPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationTracking()

        // Clean up auto-follow handler
        autoFollowRunnable?.let { autoFollowHandler.removeCallbacks(it) }

        // Clean up autocomplete adapters
        if (::startPlaceAdapter.isInitialized) {
            startPlaceAdapter.cleanup()
        }
        if (::destPlaceAdapter.isInitialized) {
            destPlaceAdapter.cleanup()
        }

        _binding = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // Handle sensor changes for orientation
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            // Convert rotation vector to orientation angles (azimuth, pitch, roll)
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            val rawAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            // Negate to fix reversed direction and normalize to [0,360)
            val normalizedRaw = normalize360(-rawAzimuth + 360f)

            // Initialize smoothed value if needed
            val prev = smoothedAzimuth
            val next = if (prev == null) {
                normalizedRaw
            } else {
                // Compute shortest signed delta between angles
                val delta = shortestSignedAngle(normalize360(normalizedRaw - prev))
                // Apply exponential smoothing on the signed delta to avoid wrap issues
                val smoothedDelta = azimuthSmoothingAlpha * delta
                normalize360(prev + smoothedDelta)
            }

            // Save both raw and smoothed azimuths
            azimuth = normalizedRaw
            smoothedAzimuth = next

            // Only update the visual rotation if change is noticeable to avoid jitter
            val angleDiff = Math.abs(shortestSignedAngle(normalize360(next - (currentLocationMarker?.rotation ?: next))))
            if (angleDiff >= azimuthUpdateThresholdDeg) {
                currentLocationMarker?.rotation = next
                binding.map.invalidate()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op: keep method to satisfy SensorEventListener contract
    }

    /**
     * Normalize angle to [0,360)
     */
    private fun normalize360(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
    }

    /**
     * Return shortest signed angle in degrees in range [-180, 180]
     */
    private fun shortestSignedAngle(angle: Float): Float {
        var a = angle % 360f
        if (a <= -180f) a += 360f
        if (a > 180f) a -= 360f
        return a
    }

    private fun saveSelectionToFirestore(destination: String, seeing: SeeingType, activity: ActivityType) {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
        val intent = CurationIntent(destinationQuery = destination, seeing = seeing, activity = activity)

        if (uid == null) {
            // User must be signed in. Redirect to LoginActivity to prompt sign-in.
            Snackbar.make(binding.root, "Please sign in to save your selection", Snackbar.LENGTH_LONG).show()
            val loginIntent = android.content.Intent(requireContext(), com.example.scenic_navigation.ui.LoginActivity::class.java)
            startActivity(loginIntent)
            return
        }

        firestoreRepo.saveSelection(uid, intent) { success, error ->
            if (!success) {
                Snackbar.make(binding.root, "Failed to save selection: ${error ?: "unknown"}", Snackbar.LENGTH_LONG).show()
            }
            // Selection saved silently without notification
        }
    }

    private fun loadRemoteSelectionIfAvailable() {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
        if (uid == null) {
            // No signed-in user; do not attempt to read remote data.
            return
        }

        firestoreRepo.loadSelection(uid) { intent ->
            intent?.let {
                // Apply loaded intent to UI fields on main thread
                activity?.runOnUiThread {
                    if (it.destinationQuery.isNotBlank()) {
                        binding.etDestination.setText(it.destinationQuery)
                    }

                    // Get localized strings
                    val seeingStrings = resources.getStringArray(R.array.seeing_options)
                    val activityStrings = resources.getStringArray(R.array.activity_options)

                    binding.actvSeeing.setText(
                         when (it.seeing) {
                            SeeingType.OCEANIC -> seeingStrings[0]  // Oceanic View / Tanawin ng Karagatan
                            SeeingType.MOUNTAIN -> seeingStrings[1]  // Mountain Ranges / Mga Bundok
                        },
                        false
                    )
                    // Set seeing start icon based on restored selection
                    try {
                        val restoredSeeingIcon = if (it.seeing == SeeingType.OCEANIC) R.drawable.ic_oceanic_view else R.drawable.ic_mountain_ranges
                        val d = ContextCompat.getDrawable(requireContext(), restoredSeeingIcon)
                        d?.let { drawable ->
                            val wrapped = DrawableCompat.wrap(drawable).mutate()
                            DrawableCompat.setTintList(wrapped, null)
                            wrapped.clearColorFilter()
                            binding.tilSeeing.startIconDrawable = wrapped
                            binding.tilSeeing.setStartIconTintList(null)
                            binding.tilSeeing.isStartIconVisible = true
                        }
                    } catch (_: Exception) {}

                    // Restore activity selection similarly
                    try {
                        val activityText = when (it.activity) {
                            ActivityType.SIGHTSEEING -> activityStrings[0]
                            ActivityType.SHOP_AND_DINE -> activityStrings[1]
                            ActivityType.CULTURAL -> activityStrings[2]
                            ActivityType.ADVENTURE -> activityStrings[3]
                            ActivityType.RELAXATION -> activityStrings[4]
                            ActivityType.FAMILY_FRIENDLY -> activityStrings[5]
                            ActivityType.ROMANTIC -> activityStrings[6]
                            else -> activityStrings[0]
                        }
                        binding.actvActivity.setText(activityText, false)

                        // Optionally set a start icon for activity (best-effort, ignore failures)
                        val restoredActivityIcon = when (it.activity) {
                            ActivityType.SIGHTSEEING -> R.drawable.ic_sight_seeing
                            ActivityType.SHOP_AND_DINE -> R.drawable.ic_shop_and_dine
                            ActivityType.CULTURAL -> R.drawable.ic_cultural_activities
                            ActivityType.ADVENTURE -> R.drawable.ic_adventure_hiking
                            ActivityType.RELAXATION -> R.drawable.ic_relaxation_wellness
                            ActivityType.FAMILY_FRIENDLY -> R.drawable.ic_family_friendly
                            ActivityType.ROMANTIC -> R.drawable.ic_romantic_getaway
                            else -> R.drawable.ic_sight_seeing
                        }
                        val d2 = ContextCompat.getDrawable(requireContext(), restoredActivityIcon)
                        d2?.let { drawable ->
                            val wrapped2 = DrawableCompat.wrap(drawable).mutate()
                            DrawableCompat.setTintList(wrapped2, null)
                            wrapped2.clearColorFilter()
                            binding.tilActivity.startIconDrawable = wrapped2
                            binding.tilActivity.setStartIconTintList(null)
                            binding.tilActivity.isStartIconVisible = true
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Check current location permission state and request if necessary.
     * If already granted, start location tracking immediately.
     */
    private fun checkAndRequestLocationPermission() {
        if (locationService.hasLocationPermission()) {
            // Already granted — start tracking so user arrow appears
            startLocationTracking()
        } else {
            requestLocationPermission()
        }
    }

    /**
     * Launch the permission request flow for fine/coarse location.
     */
    private fun requestLocationPermission() {
        try {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } catch (ex: Exception) {
            // Fallback: show rationale snackbar directing user to app settings
            Snackbar.make(binding.root, "Please grant location permissions in app settings", Snackbar.LENGTH_LONG)
                .setAction("Settings") {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", requireContext().packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    } catch (_: Exception) { }
                }.show()
        }
    }

    // Return a GeoPoint from last-known location providers (GPS then NETWORK) or null if unavailable
    private fun getLastKnownGeoPoint(): GeoPoint? {
        try {
            if (!::locationService.isInitialized || !locationService.hasLocationPermission()) return null
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                try {
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null) return GeoPoint(loc.latitude, loc.longitude)
                } catch (_: SecurityException) {
                    // permission may be missing for provider; continue
                } catch (_: Exception) {
                    // provider might be unavailable; continue
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    // createEndRouteButton remains for fallback usage but will only be used when xml button wiring fails
    /**
     * Create and add an "End Route" button to the fragment root. The button shows a confirmation dialog
     * before clearing the current route and stopping location tracking.
     */
    private fun createEndRouteButton() {
        try {
            val root = _binding?.root as? android.view.ViewGroup ?: return

            // Avoid adding multiple times by checking tag
            val existing = root.findViewWithTag<View>("end_route_btn")
            if (existing != null) return

            val btn = AppCompatButton(requireContext()).apply {
                // use a stable tag instead of relying on an XML id resource
                tag = "end_route_btn"
                text = "End Route"
                isAllCaps = false
                setPadding(20, 12, 20, 12)
                visibility = View.GONE  // Initially hidden until route is active
                // Style for visibility (use default system button background)
                setBackgroundResource(android.R.drawable.btn_default)
                setOnClickListener {
                    // show confirmation dialog using literal strings to avoid missing resources
                    AlertDialog.Builder(requireContext())
                        .setTitle("End route")
                        .setMessage("Are you sure you want to end the current route?")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _dialog, _which ->
                            endCurrentRoute()
                        }
                        .show()
                }
            }

            // Layout params: bottom-end with margin
            val size = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            val params = android.widget.FrameLayout.LayoutParams(size, size)
            params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            val margin = (16 * resources.displayMetrics.density).toInt()
            params.setMargins(margin, margin, margin, margin + (56 * resources.displayMetrics.density).toInt())
            root.addView(btn, params)
        } catch (e: Exception) {
            Log.w("RouteFragment", "Failed to create End Route button", e)
        }
    }

    // End/cancel the current route: stop tracking, remove overlays, and clear shared route data.
    private fun endCurrentRoute() {
         try {
             // Stop navigation/tracking
             stopLocationTracking()
             offRouteDetector = null
             isNavigating = false

             // Hide the End Route button (handle both XML and programmatic buttons)
             view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_end_route)?.visibility = View.GONE
             (view as? ViewGroup)?.findViewWithTag<View>("end_route_btn")?.visibility = View.GONE

             // Remove route overlays
             try { routePolyline?.let { binding.map.overlays.remove(it); routePolyline = null } } catch (_: Exception) {}
             try { traveledPolyline?.let { binding.map.overlays.remove(it); traveledPolyline = null } } catch (_: Exception) {}
             try { startMarker?.let { binding.map.overlays.remove(it); startMarker = null } } catch (_: Exception) {}
             try { destinationMarker?.let { binding.map.overlays.remove(it); destinationMarker = null } } catch (_: Exception) {}

             // Remove POI markers
             routeMarkers.forEach { try { binding.map.overlays.remove(it) } catch (_: Exception) {} }
             routeMarkers.clear()

             binding.map.invalidate()

             // Clear route data from both ViewModels to prevent re-generation when navigating back
             try {
                 viewModel.clearRoute()
             } catch (_: Exception) {}

             try {
                 sharedViewModel.updateRouteData(emptyList(), emptyList())
             } catch (_: Exception) {}

            // Clear UI inputs and status so the screen looks reset
            try {
                // Reset destination and inputs
                binding.etDestination.setText("")
                binding.tilDestination.error = null

                // Reset start input back to "Current location"
                try { binding.etStart.setText(getString(R.string.current_location)) } catch (_: Exception) { binding.etStart.setText("") }
                try { binding.tilStart.error = null } catch (_: Exception) {}

                // Clear selection dropdowns and their start icons
                try {
                    binding.actvSeeing.setText("", false)
                    binding.tilSeeing.startIconDrawable = null
                    binding.tilSeeing.isStartIconVisible = false
                } catch (_: Exception) {}
                try {
                    binding.actvActivity.setText("", false)
                    binding.tilActivity.startIconDrawable = null
                    binding.tilActivity.isStartIconVisible = false
                } catch (_: Exception) {}

                // Hide POI preview strip
                try { binding.rvPoiPreviews.visibility = View.GONE } catch (_: Exception) {}

                // Clear any status text/overlay
                try { binding.tvStatus.visibility = View.GONE; binding.tvStatus.text = "" } catch (_: Exception) {}
                try { binding.tvOverlayStatus.text = "" } catch (_: Exception) {}
            } catch (_: Exception) {}

             // Expand the curate menu to allow planning a new route
             try {
                 if (isInputCollapsed) {
                     isInputCollapsed = false
                     binding.collapsibleContent.visibility = View.VISIBLE
                     binding.btnCollapse.text = "▼"
                     binding.cardInput.animate()
                         .alpha(1.0f)
                         .scaleY(1.0f)
                         .setDuration(200)
                         .start()
                     // Lower button elevation so they appear behind the expanded card
                     binding.btnCenter.translationZ = -10f
                     view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_end_route)?.translationZ = -10f

                     // Ensure the overlay is hidden when the input is expanded (end of route resets UI)
                     try {
                         binding.legendOverlayCard.visibility = View.GONE
                     } catch (_: Exception) {}
                 }
             } catch (_: Exception) {}
         } catch (e: Exception) {
             Log.w("RouteFragment", "Failed to end route cleanly", e)
             try { Snackbar.make(binding.root, "Failed to end route", Snackbar.LENGTH_SHORT).show() } catch (_: Exception) {}
         }
     }
}
