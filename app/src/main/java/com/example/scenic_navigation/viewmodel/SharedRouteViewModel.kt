package com.example.scenic_navigation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.models.Town
import org.osmdroid.util.GeoPoint

/**
 * Shared ViewModel across fragments to maintain route and recommendations data
 */
class SharedRouteViewModel(application: Application) : AndroidViewModel(application) {

    // Route data
    private val _routePoints = MutableLiveData<List<GeoPoint>>(emptyList())
    val routePoints: LiveData<List<GeoPoint>> = _routePoints

    private val _routePois = MutableLiveData<List<Poi>>(emptyList())
    val routePois: LiveData<List<Poi>> = _routePois

    private val _routeTowns = MutableLiveData<List<Town>>(emptyList())
    val routeTowns: LiveData<List<Town>> = _routeTowns

    // Recommendations data
    private val _recommendations = MutableLiveData<List<Poi>>(emptyList())
    val recommendations: LiveData<List<Poi>> = _recommendations

    // Loading state
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Status message
    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    // Map center and zoom
    private val _mapCenter = MutableLiveData<GeoPoint>(GeoPoint(14.5995, 120.9842)) // Default to Manila
    val mapCenter: LiveData<GeoPoint> = _mapCenter

    private val _mapZoom = MutableLiveData<Double>(10.0)
    val mapZoom: LiveData<Double> = _mapZoom

    // Update methods
    fun updateRouteData(points: List<GeoPoint>, pois: List<Poi>, towns: List<Town> = emptyList()) {
        _routePoints.value = points
        _routePois.value = pois
        _routeTowns.value = towns

        // Also update recommendations with POIs
        _recommendations.value = pois

        // Update map center to route midpoint
        if (points.isNotEmpty()) {
            _mapCenter.value = points[points.size / 2]
            _mapZoom.value = 10.0
        }
    }

    fun updateRecommendations(pois: List<Poi>) {
        _recommendations.value = pois
    }

    fun updateMapPosition(center: GeoPoint, zoom: Double) {
        _mapCenter.value = center
        _mapZoom.value = zoom
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setStatusMessage(message: String?) {
        _statusMessage.value = message
    }

    fun clearRoute() {
        _routePoints.value = emptyList()
        _routePois.value = emptyList()
        _routeTowns.value = emptyList()
    }

    fun hasRoute(): Boolean {
        return _routePoints.value?.isNotEmpty() == true
    }

    fun hasRecommendations(): Boolean {
        return _recommendations.value?.isNotEmpty() == true
    }
}

