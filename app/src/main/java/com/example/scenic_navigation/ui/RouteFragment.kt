package com.example.scenic_navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.scenic_navigation.databinding.FragmentRouteBinding
import com.example.scenic_navigation.viewmodel.RouteViewModel
import com.example.scenic_navigation.viewmodel.SharedRouteViewModel
import com.google.android.material.snackbar.Snackbar
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class RouteFragment : Fragment() {
    private var _binding: FragmentRouteBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RouteViewModel by activityViewModels()
    private val sharedViewModel: SharedRouteViewModel by activityViewModels()

    private val routeMarkers = mutableListOf<Marker>()
    private var routePolyline: Polyline? = null
    private var isInputCollapsed = false

    // Location permission launcher
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            Snackbar.make(binding.root, "Location permission granted", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, "Location permission denied. Will use default location.", Snackbar.LENGTH_LONG).show()
            // Optionally turn off the "Use Current Location" switch
            binding.switchUseCurrent.isChecked = false
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

        setupMap()
        setupInputs()
        setupCollapseButton()
        restoreRouteIfAvailable()
        observeViewModel()
    }

    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            // Set default location (Philippines)
            controller.setCenter(GeoPoint(14.5995, 120.9842))
        }
    }

    private fun setupInputs() {
        // Set initial state - start input disabled since "Use Current Location" is checked by default
        binding.etStart.isEnabled = false
        binding.tilStart.isEnabled = false

        // Check location permission on start if toggle is on
        if (binding.switchUseCurrent.isChecked) {
            checkAndRequestLocationPermission()
        }

        // Enable/disable start input based on "Use Current Location" switch
        binding.switchUseCurrent.setOnCheckedChangeListener { _, isChecked ->
            binding.etStart.isEnabled = !isChecked
            binding.tilStart.isEnabled = !isChecked
            if (isChecked) {
                binding.etStart.setText("")
                binding.tilStart.error = null
                // Request location permission when switch is turned on
                checkAndRequestLocationPermission()
            }
        }

        // Plan route button click
        binding.btnPlan.setOnClickListener {
            val useCurrent = binding.switchUseCurrent.isChecked
            val useOceanic = binding.switchOceanicRoute.isChecked
            val useMountain = binding.switchMountainRoute.isChecked
            val startInput = binding.etStart.text.toString().trim()
            val destInput = binding.etDestination.text.toString().trim()

            // Validation - only check start input if not using current location
            if (!useCurrent && startInput.isEmpty()) {
                binding.tilStart.error = "Please enter a start location"
                return@setOnClickListener
            }
            if (destInput.isEmpty()) {
                binding.tilDestination.error = "Please enter a destination"
                return@setOnClickListener
            }

            // Clear errors
            binding.tilStart.error = null
            binding.tilDestination.error = null

            // Plan route - useCurrent flag takes priority over startInput
            viewModel.planRoute(useCurrent, useOceanic, useMountain, startInput, destInput)
        }

        // Clear errors on text change
        binding.etStart.doOnTextChanged { _, _, _, _ ->
            binding.tilStart.error = null
        }
        binding.etDestination.doOnTextChanged { _, _, _, _ ->
            binding.tilDestination.error = null
        }
    }

    private fun checkAndRequestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                // Show rationale and request permission
                Snackbar.make(
                    binding.root,
                    "Location permission needed to use current location",
                    Snackbar.LENGTH_LONG
                ).setAction("Grant") {
                    requestLocationPermission()
                }.show()
            }
            else -> {
                // Request permission directly
                requestLocationPermission()
            }
        }
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
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

    private fun updateRoute(points: List<GeoPoint>) {
        // Clear previous polyline
        routePolyline?.let { binding.map.overlays.remove(it) }

        if (points.isNotEmpty()) {
            val polyline = Polyline().apply {
                setPoints(points)
                outlinePaint.color = android.graphics.Color.parseColor("#2196F3")
                outlinePaint.strokeWidth = 12.0f
            }
            binding.map.overlays.add(polyline)
            routePolyline = polyline

            // Center map on route
            binding.map.controller.apply {
                setCenter(points[points.size / 2])
                setZoom(10.0)
            }
            binding.map.invalidate()
        }
    }

    private fun updateMarkers(pois: List<com.example.scenic_navigation.models.Poi>) {
        // Clear previous markers
        routeMarkers.forEach { binding.map.overlays.remove(it) }
        routeMarkers.clear()

        pois.forEach { poi ->
            val marker = Marker(binding.map).apply {
                position = GeoPoint(poi.lat ?: 0.0, poi.lon ?: 0.0)
                title = poi.name
                snippet = poi.description
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.map.overlays.add(marker)
            routeMarkers.add(marker)
        }
        binding.map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()

        // Save current map position to shared ViewModel
        val center = binding.map.mapCenter as? GeoPoint
        val zoom = binding.map.zoomLevelDouble
        if (center != null) {
            sharedViewModel.updateMapPosition(center, zoom)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
