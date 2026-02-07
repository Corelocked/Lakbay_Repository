package com.example.scenic_navigation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.models.Town
import com.example.scenic_navigation.ml.PoiReranker
import com.example.scenic_navigation.ml.MlInferenceEngine
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

    // ML reranker (lazy initialized)
    private val poiReranker: PoiReranker by lazy { PoiReranker(MlInferenceEngine(getApplication(), "models/poi_reranker_from_luzon.tflite")) }

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

    // Control whether the UI should auto-recenter on the user's current location
    private val _autoRecenter = MutableLiveData<Boolean>(false)
    val autoRecenter: LiveData<Boolean> = _autoRecenter

    // Curation intent provided by the curation UI flow (nullable)
    private val _curationIntent = MutableLiveData<com.example.scenic_navigation.models.CurationIntent?>(null)
    val curationIntent: LiveData<com.example.scenic_navigation.models.CurationIntent?> = _curationIntent

    // Optional extras attached by the curation UI (forced coastal key, subtypes)
    private val _curationExtras = MutableLiveData<com.example.scenic_navigation.models.CurationIntentExtras?>(null)
    val curationExtras: LiveData<com.example.scenic_navigation.models.CurationIntentExtras?> = _curationExtras

    fun setCurationIntent(intent: com.example.scenic_navigation.models.CurationIntent?) {
        _curationIntent.value = intent
    }

    fun setCurationExtras(extras: com.example.scenic_navigation.models.CurationIntentExtras?) {
        _curationExtras.value = extras
    }

    fun setAutoRecenter(enabled: Boolean) {
        _autoRecenter.value = enabled
    }

    // Update methods
    fun updateRouteData(points: List<GeoPoint>, pois: List<Poi>, towns: List<Town> = emptyList()) {
        _routePoints.value = points
        _routePois.value = pois
        _routeTowns.value = towns

        // Also update recommendations with POIs — apply ML reranker if possible
        try {
            val center = if (points.isNotEmpty()) points[points.size / 2] else GeoPoint(14.5995, 120.9842)
            val reranked = poiReranker.rerank(pois, center.latitude, center.longitude, System.currentTimeMillis())
            _recommendations.value = reranked
        } catch (t: Throwable) {
            // Fall back to raw POIs if reranker fails
            _recommendations.value = pois
        }

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
