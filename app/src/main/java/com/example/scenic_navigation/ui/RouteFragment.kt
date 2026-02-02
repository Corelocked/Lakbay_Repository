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
import com.example.scenic_navigation.services.LocationService
import com.example.scenic_navigation.utils.OffRouteDetector
import com.example.scenic_navigation.viewmodel.RouteViewModel
import com.example.scenic_navigation.viewmodel.SharedRouteViewModel
import com.google.android.material.snackbar.Snackbar
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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
        setupInputs()
        setupCollapseButton()
        setupSettingsButton()
        restoreRouteIfAvailable()
        observeViewModel()
        startClusterPolling()

        // Setup copyright click listener
        binding.osmCopyright.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://osm.org/copyright"))
            startActivity(intent)
        }
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
        binding.map.apply {
            setTileSource(tileSource)
            setMultiTouchControls(true)
            controller.setZoom(15.0)  // Increased from 13.0 for larger street text visibility
            // Set default location (Philippines)
            controller.setCenter(GeoPoint(14.5995, 120.9842))
        }
    }

    private lateinit var startPlaceAdapter: PlaceSuggestionAdapter
    private lateinit var destPlaceAdapter: PlaceSuggestionAdapter

    private fun setupInputs() {
        // Start input is now fixed to current location
        binding.etStart.setText("Current Location")
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

        // Setup dropdowns
        val seeingAdapter = ArrayAdapter.createFromResource(requireContext(), R.array.seeing_options, android.R.layout.simple_dropdown_item_1line)
        binding.actvSeeing.setAdapter(seeingAdapter)

        val activityAdapter = ArrayAdapter.createFromResource(requireContext(), R.array.activity_options, android.R.layout.simple_dropdown_item_1line)
        binding.actvActivity.setAdapter(activityAdapter)


        // Plan route button click
        binding.btnPlan.setOnClickListener {
            val destination = binding.etDestination.text.toString()
            if (destination.isBlank()) {
                binding.tilDestination.error = "Please enter a destination"
                return@setOnClickListener
            }

            val seeingString = binding.actvSeeing.text.toString()
            val activityString = binding.actvActivity.text.toString()

            val seeing = when (seeingString) {
                "🌊 Oceanic View" -> SeeingType.OCEANIC
                "⛰️ Mountain Ranges" -> SeeingType.MOUNTAIN
                else -> SeeingType.OCEANIC // Default value
            }

            val activity = when (activityString) {
                "👀 Sight seeing" -> ActivityType.SIGHTSEEING
                "🍽️ Shop and Dine" -> ActivityType.SHOP_AND_DINE
                "🎭 Cultural activities" -> ActivityType.CULTURAL
                "🏔️ Adventure & Hiking" -> ActivityType.ADVENTURE
                "🧘 Relaxation & Wellness" -> ActivityType.RELAXATION
                "👨‍👩‍👧‍👦 Family Friendly" -> ActivityType.FAMILY_FRIENDLY
                "💕 Romantic Getaway" -> ActivityType.ROMANTIC
                else -> ActivityType.SIGHTSEEING // Default value
            }

            viewModel.planRouteCurated(destination, seeing, activity)
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
            } else {
            }
        }

        viewModel.routeDurationSeconds.observe(viewLifecycleOwner) { secs ->
            if (secs != null && secs > 0L) {
                val hours = secs / 3600
                val mins = (secs % 3600) / 60
            } else {
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

                // Show as Snackbar for important messages
                if (!viewModel.isLoading.value!!) {
                    Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
                }
                // update overlay status text if visible
                binding.tvOverlayStatus.text = it
            }
        }

        // Observe route points
        viewModel.routePoints.observe(viewLifecycleOwner) { points ->
            updateRoute(points)
            // Update shared ViewModel only if both points and POIs are ready
            updateSharedViewModelIfReady()
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
                title = "Current Location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_user_arrow, null)
                binding.map.overlays.add(this)
            }
        } else {
            currentLocationMarker?.position = userPosition
        }

        // Rotation is now handled by sensor in onSensorChanged

        // Update route visualization to show traveled vs remaining path
        updateRouteVisualization(userPosition)

        // Center map on user location smoothly only if auto-recenter is enabled
        if (sharedViewModel.autoRecenter.value == true) {
            binding.map.controller.animateTo(userPosition)
        }
        binding.map.invalidate()
    }

    /**
     * Update route visualization to show traveled path in darker blue
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

        // Draw traveled path in darker blue
        if (traveledPoints.size >= 2) {
            traveledPolyline = Polyline().apply {
                setPoints(traveledPoints)
                outlinePaint.color = android.graphics.Color.parseColor("#0D47A1") // Darker blue
                outlinePaint.strokeWidth = 14.0f // Slightly thicker
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
        // Clear previous route overlays
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

            // Center map on route
            binding.map.controller.apply {
                setCenter(points[points.size / 2])
                setZoom(10.0)
            }
            binding.map.invalidate()

            // Initialize off-route detector and start location tracking
            initializeOffRouteDetector(points)

            // Start tracking location when route is available
            if (!isNavigating && locationService.hasLocationPermission()) {
                startLocationTracking()
            }
        } else {
            // Stop tracking when no route
            if (isNavigating) {
                stopLocationTracking()
            }
            offRouteDetector = null
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
                        icon = android.graphics.drawable.BitmapDrawable(resources, createPoiIcon(poi))
                    } catch (_: Exception) {
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
                    icon = BitmapDrawable(resources, createClusterIcon(count, avgScore))
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
    }

    private fun createClusterIcon(count: Int): Bitmap {
        val size = 110
        val radius = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Try to resolve Material token `colorPrimaryContainer` from theme, fall back to app color
        var fillColor = android.graphics.Color.parseColor("#1976D2")
        try {
            val typedValue = TypedValue()
            val theme = requireContext().theme
            val resolved = theme.resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true)
            if (resolved) {
                // typedValue may carry color in data or a resourceId
                fillColor = if (typedValue.resourceId != 0) {
                    ContextCompat.getColor(requireContext(), typedValue.resourceId)
                } else {
                    typedValue.data
                }
            } else {
                // fallback to app resource color if attr not found
                fillColor = ContextCompat.getColor(requireContext(), R.color.primary_dark)
            }
        } catch (_: Exception) {
            // keep fallback
        }

        paint.color = fillColor
        // draw outer stroke for contrast
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 6f
        strokePaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(radius, radius, radius - 3f, paint)
        canvas.drawCircle(radius, radius, radius - 3f, strokePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = android.graphics.Color.WHITE
        textPaint.textSize = 40f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val text = count.toString()
        val textWidth = textPaint.measureText(text)
        val x = (size - textWidth) / 2f
        val fm = textPaint.fontMetrics
        val y = (size - fm.ascent - fm.descent) / 2f
        canvas.drawText(text, x, y, textPaint)
        return bmp
    }

    private fun getCategoryColor(category: String?): Int {
        val cat = category?.lowercase() ?: ""
        return when {
            cat.contains("beach") || cat.contains("coast") || cat.contains("ocean") || cat.contains("sea") -> android.graphics.Color.parseColor("#0288D1") // blue
            cat.contains("mount") || cat.contains("hike") || cat.contains("view") -> android.graphics.Color.parseColor("#2E7D32") // green
            cat.contains("historic") || cat.contains("church") || cat.contains("monument") -> android.graphics.Color.parseColor("#FFA000") // amber
            else -> android.graphics.Color.parseColor("#1976D2") // default primary
        }
    }

    // Create a small circular icon for a POI with the initial letter and color by category
    private fun createPoiIcon(poi: com.example.scenic_navigation.models.Poi): Bitmap {
        val size = 72
        val radius = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val fill = getCategoryColor(poi.category)
        paint.color = fill
        canvas.drawCircle(radius, radius, radius - 2f, paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = android.graphics.Color.WHITE
        textPaint.textSize = 28f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val letter = poi.name.trim().takeIf { it.isNotEmpty() }?.get(0)?.uppercaseChar() ?: '?'
        val text = letter.toString()
        val textWidth = textPaint.measureText(text)
        val fm = textPaint.fontMetrics
        val x = (size - textWidth) / 2f
        val y = (size - fm.ascent - fm.descent) / 2f
        canvas.drawText(text, x, y, textPaint)

        return bmp
    }

    private fun createClusterIcon(count: Int, avgScore: Float): Bitmap {
        val size = 120
        val radius = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Color by avgScore: green high -> red low
        val fillColor = when {
            avgScore >= 75f -> android.graphics.Color.parseColor("#2E7D32")
            avgScore >= 50f -> android.graphics.Color.parseColor("#FFC107")
            avgScore >= 25f -> android.graphics.Color.parseColor("#FF7043")
            else -> android.graphics.Color.parseColor("#D32F2F")
        }

        paint.color = fillColor
        // draw outer stroke for contrast
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 6f
        strokePaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(radius, radius, radius - 4f, paint)
        canvas.drawCircle(radius, radius, radius - 4f, strokePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = android.graphics.Color.WHITE
        textPaint.textSize = 42f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val text = count.toString()
        val textWidth = textPaint.measureText(text)
        val x = (size - textWidth) / 2f
        val fm = textPaint.fontMetrics
        val y = (size - fm.ascent - fm.descent) / 2f
        canvas.drawText(text, x, y, textPaint)

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

        Snackbar.make(
            binding.root,
            "You're off route! Recalculating...",
            Snackbar.LENGTH_LONG
        ).show()

        // Recalculate route from current location
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

            // Azimuth is the angle around the Z axis (rotation about the vertical axis)
            azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            azimuth = (azimuth + 360) % 360

            // Update the marker rotation
            currentLocationMarker?.rotation = azimuth
            binding.map.invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
