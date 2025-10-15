package com.example.scenic_navigation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.services.GeocodingService
import com.example.scenic_navigation.services.RoutingService
import com.example.scenic_navigation.services.ScenicRoutePlanner
import com.example.scenic_navigation.services.LocationService
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

class RouteViewModel(application: Application) : AndroidViewModel(application) {
    private val geocodingService = GeocodingService()
    private val routingService = RoutingService()
    private val scenicRoutePlanner = ScenicRoutePlanner()
    private val locationService = LocationService(application)
    private val packageName = application.packageName

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    private val _routePoints = MutableLiveData<List<GeoPoint>>(emptyList())
    val routePoints: LiveData<List<GeoPoint>> = _routePoints

    private val _routePois = MutableLiveData<List<Poi>>(emptyList())
    val routePois: LiveData<List<Poi>> = _routePois

    fun planRoute(
        useCurrent: Boolean,
        useOceanic: Boolean,
        useMountain: Boolean,
        startInput: String,
        destInput: String
    ) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _statusMessage.value = "Geocoding addresses..."

                // Get start point - useCurrent takes absolute priority
                val startPoint = if (useCurrent) {
                    // Use actual GPS location
                    _statusMessage.value = "Getting current location..."
                    val location = locationService.getCurrentLocation()

                    if (location != null) {
                        _statusMessage.value = "Using current location as start point..."
                        location
                    } else {
                        // Fallback to Manila if GPS unavailable
                        _statusMessage.value = "Could not get GPS location, using default (Manila)..."
                        GeoPoint(14.5995, 120.9842)
                    }
                } else {
                    // Only use startInput if useCurrent is false
                    if (startInput.isEmpty()) {
                        _statusMessage.value = "Please enter a start location"
                        _routePoints.value = emptyList()
                        _routePois.value = emptyList()
                        _isLoading.value = false
                        return@launch
                    }
                    // Try parsing as lat/lon first, otherwise geocode
                    com.example.scenic_navigation.utils.GeoUtils.parseLatLon(startInput)
                        ?: run {
                            val results = geocodingService.geocodeAddress(startInput, packageName) { error ->
                                _statusMessage.postValue(error)
                            }
                            if (results.isEmpty()) {
                                _statusMessage.value = "Could not find start location: $startInput"
                                _routePoints.value = emptyList()
                                _routePois.value = emptyList()
                                _isLoading.value = false
                                return@launch
                            }
                            GeoPoint(results[0].lat, results[0].lon)
                        }
                }

                // Get destination point
                val destPoint = com.example.scenic_navigation.utils.GeoUtils.parseLatLon(destInput)
                    ?: run {
                        val results = geocodingService.geocodeAddress(destInput, packageName) { error ->
                            _statusMessage.postValue(error)
                        }
                        if (results.isEmpty()) {
                            _statusMessage.value = "Could not find destination: $destInput"
                            _routePoints.value = emptyList()
                            _routePois.value = emptyList()
                            _isLoading.value = false
                            return@launch
                        }
                        GeoPoint(results[0].lat, results[0].lon)
                    }

                _statusMessage.value = "Calculating route..."

                // Determine routing mode
                val routingMode = when {
                    useOceanic -> "oceanic"
                    useMountain -> "mountain"
                    else -> "default"
                }

                // Fetch route using OSRM with mode
                val route = routingService.fetchRoute(startPoint, destPoint, packageName, routingMode)

                if (route.isEmpty()) {
                    _statusMessage.value = "Could not calculate route"
                    _routePoints.value = emptyList()
                    _routePois.value = emptyList()
                    _isLoading.value = false
                    return@launch
                }

                _routePoints.value = route

                // Fetch scenic POIs along the route
                val routeType = when {
                    useOceanic -> "oceanic"
                    useMountain -> "mountain"
                    else -> "generic"
                }

                _statusMessage.value = "Finding scenic spots..."
                val scenicPois = scenicRoutePlanner.fetchScenicPois(route, packageName, routeType) { status ->
                    _statusMessage.postValue(status)
                }

                // Convert ScenicPoi to Poi
                val pois = scenicPois.map { scenic ->
                    Poi(
                        name = scenic.name,
                        category = scenic.type,
                        description = "Scenic score: ${scenic.score}",
                        municipality = scenic.municipality ?: "Unknown",
                        lat = scenic.lat,
                        lon = scenic.lon
                    )
                }

                _routePois.value = pois
                _statusMessage.value = "Route planned successfully! Found ${scenicPois.size} scenic spots."
            } catch (e: Exception) {
                _statusMessage.value = "Error planning route: ${e.message}"
                _routePoints.value = emptyList()
                _routePois.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearRoute() {
        _routePoints.value = emptyList()
        _routePois.value = emptyList()
        _statusMessage.value = null
    }
}
