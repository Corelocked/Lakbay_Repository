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
import android.content.Context
import android.util.Log
import com.example.scenic_navigation.config.Config
import androidx.lifecycle.Observer
import android.content.SharedPreferences
import com.example.scenic_navigation.services.LocationService
import com.example.scenic_navigation.ml.PoiReranker
import com.example.scenic_navigation.ml.MlInferenceEngine
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
class RouteViewModel(application: Application) : AndroidViewModel(application) {
    private val geocodingService = GeocodingService()
    private val routingService = RoutingService()
    private lateinit var scenicRoutePlanner: ScenicRoutePlanner
    private lateinit var prefs: SharedPreferences
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Config.PREF_CLUSTER_EPS_KEY || key == Config.PREF_CLUSTER_MIN_PTS_KEY) {
            refreshPlannerFromPrefs()
        }
    }
    private val locationService = LocationService(application)
    private val packageName = application.packageName
    private var settingsObserver: Observer<Int>? = null
    private val poiReranker = PoiReranker(MlInferenceEngine(application.applicationContext, "models/poi_reranker_from_luzon.tflite"))
    private val poiService = com.example.scenic_navigation.services.PoiService(application.applicationContext)

    // Calculate minimum distance from a point to the route (list of GeoPoints)
    private fun minDistanceToRoute(point: GeoPoint, route: List<GeoPoint>): Double {
        var minDist = Double.MAX_VALUE
        for (routePoint in route) {
            val dist = point.distanceToAsDouble(routePoint)
            if (dist < minDist) minDist = dist
        }
        return minDist
    }

    // Caches for routes and POIs to avoid redundant calculations
    private val routeCache: MutableMap<String, List<GeoPoint>> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, List<GeoPoint>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<GeoPoint>>?): Boolean {
                return size > 32
            }
        }
    )
    private val poiCache: MutableMap<String, List<Poi>> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, List<Poi>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Poi>>?): Boolean {
                return size > 32
            }
        }
    )
    private val distanceCache: MutableMap<String, Double> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Double>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?): Boolean {
                return size > 32
            }
        }
    )
    private val durationCache: MutableMap<String, Long> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 32
            }
        }
    )
    private val scoreCache: MutableMap<String, Float> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Float>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean {
                return size > 32
            }
        }
    )

    init {
        // Initialize prefs and planner from application context
        prefs = getApplication<Application>().getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
        refreshPlannerFromPrefs()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Observe direct settings change notifications so we can refresh immediately
        val obs = Observer<Int> {
            refreshPlannerFromPrefs()
        }
        com.example.scenic_navigation.events.SettingsBus.events.observeForever(obs)
        settingsObserver = obs
    }


    private fun refreshPlannerFromPrefs() {
        val epsStr = prefs.getString(Config.PREF_CLUSTER_EPS_KEY, Config.DEFAULT_CLUSTER_EPS_METERS.toString())
        val eps = epsStr?.toDoubleOrNull()
        // Min pts may be stored as string by the preference screen; accept both forms
        val minPts = try {
            prefs.getInt(Config.PREF_CLUSTER_MIN_PTS_KEY, Config.DEFAULT_CLUSTER_MIN_PTS)
        } catch (e: ClassCastException) {
            val minStr = prefs.getString(Config.PREF_CLUSTER_MIN_PTS_KEY, Config.DEFAULT_CLUSTER_MIN_PTS.toString())
            minStr?.toIntOrNull() ?: Config.DEFAULT_CLUSTER_MIN_PTS
        }
        scenicRoutePlanner = ScenicRoutePlanner(getApplication<Application>().applicationContext, eps, minPts)
    }

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Phased loading flags
    private val _isGeocoding = MutableLiveData<Boolean>(false)
    val isGeocoding: LiveData<Boolean> = _isGeocoding

    private val _isRouting = MutableLiveData<Boolean>(false)
    val isRouting: LiveData<Boolean> = _isRouting

    private val _isFetchingPois = MutableLiveData<Boolean>(false)
    val isFetchingPois: LiveData<Boolean> = _isFetchingPois

    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    private val _routePoints = MutableLiveData<List<GeoPoint>>(emptyList())
    val routePoints: LiveData<List<GeoPoint>> = _routePoints

    private val _routePois = MutableLiveData<List<Poi>>(emptyList())
    val routePois: LiveData<List<Poi>> = _routePois

    // UI summary values: distance (meters), duration (seconds), scenic score (average)
    private val _routeDistanceMeters = MutableLiveData<Double>(0.0)
    val routeDistanceMeters: LiveData<Double> = _routeDistanceMeters

    private val _routeDurationSeconds = MutableLiveData<Long>(0L)
    val routeDurationSeconds: LiveData<Long> = _routeDurationSeconds

    private val _scenicScore = MutableLiveData<Float>(0f)
    val scenicScore: LiveData<Float> = _scenicScore

    // Store destination and routing preferences for recalculation
    private var currentDestination: GeoPoint? = null
    private var currentRoutingMode: String = "default"
    // Last used curation intent (if any) to persist through recalculations
    private var lastCurationIntent: com.example.scenic_navigation.models.CurationIntent? = null

    fun planRoute(
        useCurrent: Boolean,
        useOceanic: Boolean,
        useMountain: Boolean,
        startInput: String,
        destInput: String,
        curationIntent: com.example.scenic_navigation.models.CurationIntent? = null
    ) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.geocoding_status)
                _isGeocoding.value = true

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
                        _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.please_enter_start)
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

                _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.routing_status)
                _isGeocoding.value = false
                _isRouting.value = true

                // Calculate direct distance first to determine routing mode
                val directDistance = startPoint.distanceToAsDouble(destPoint)

                val LONG_OCEANIC_THRESHOLD = 150_000.0 // meters (~150km)

                // Determine routing mode - use oceanic for oceanic routes, default for others
                val routingMode = if (useOceanic) "oceanic" else "default"

                // Store for recalculation
                currentDestination = destPoint
                currentRoutingMode = routingMode

                // Read user preference whether to prefer coastal sets for long oceanic trips
                val preferCoastalPref = prefs.getBoolean(com.example.scenic_navigation.config.Config.PREF_PREFER_COASTAL_LONG_OCEANIC, true)

                // If the UI supplied a forced coastal set via extras, use it directly
                val forcedExtras = pendingCurationExtras
                val forcedKey = forcedExtras?.forcedCoastalKey
                val forcedCoastalWaypoints = if (forcedExtras?.forceCoastal == true && !forcedKey.isNullOrEmpty()) {
                    routingService.getCoastalWaypointSet(forcedKey)
                } else null

                // Suggest via-points based on curation to bias routing (optional)
                // val suggestedVia = scenicRoutePlanner.suggestViaPointsForCuration(startPoint, destPoint, packageName, curationIntent)

                // Calculate direct route first based on scenery mode, then fetch POIs along it to avoid long detours
                val waypointsToPass = when {
                    // If a forced coastal key is present, use that explicit set
                    forcedCoastalWaypoints != null -> forcedCoastalWaypoints
                    // For long oceanic routes to southern destinations, let RoutingService pick its coastal set for scenery
                    routingMode == "oceanic" && directDistance > LONG_OCEANIC_THRESHOLD && preferCoastalPref -> null
                    else -> null // No additional via-points to avoid detours
                }

                // Clear transient extras after consuming
                pendingCurationExtras = null

                // Fetch route using OSRM with mode; pass via-points if provided
                val route = routingService.fetchRoute(startPoint, destPoint, packageName, routingMode, waypointsToPass)

                if (route.isEmpty()) {
                    _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.could_not_calculate_route)
                    _routePoints.value = emptyList()
                    _routePois.value = emptyList()
                    _isLoading.value = false
                    return@launch
                }

                _routePoints.value = route

                _isRouting.value = false

                // Compute approximate route distance (meters) and estimated duration
                try {
                    var totalMeters = 0.0
                    for (i in 0 until route.size - 1) {
                        val a = route[i]
                        val b = route[i + 1]
                        totalMeters += com.example.scenic_navigation.utils.GeoUtils.haversine(a.latitude, a.longitude, b.latitude, b.longitude)
                    }
                    _routeDistanceMeters.value = totalMeters
                    // Estimate duration using average speed 60 km/h -> 16.6667 m/s
                    val avgSpeedMps = 16.6667
                    val seconds = (totalMeters / avgSpeedMps).toLong()
                    _routeDurationSeconds.value = seconds
                } catch (_: Exception) {
                    _routeDistanceMeters.value = 0.0
                    _routeDurationSeconds.value = 0L
                }

                // Fetch scenic POIs along the route
                val routeType = when {
                    useOceanic -> "oceanic"
                    useMountain -> "mountain"
                    else -> "generic"
                }

                _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.finding_pois_status)
                _isFetchingPois.value = true
                // persist last intent so recalculations reuse it
                lastCurationIntent = curationIntent
                val scenicPois = scenicRoutePlanner.fetchScenicPois(route, packageName, routeType, curationIntent) { status ->
                    _statusMessage.postValue(status)
                }

                // Load dataset POIs for enrichment (description, imageUrl, tags, etc.)
                val datasetPois = poiService.getDatabasePois()

                // Convert ScenicPoi to Poi
                val pois = scenicPois.map { scenic ->
                    // Find matching dataset POI
                    val datasetPoi = findBestDatasetPoiForScenic(scenic, datasetPois)
                    val description = datasetPoi?.description ?: scenic.description.ifBlank { "A scenic location along the route." }
                    // Boost scenic score for POIs that came from the dataset
                    val boostedScore = scenic.score.toFloat() + (if (datasetPoi != null) 20f else 0f)
                    Poi(
                        name = scenic.name,
                        category = scenic.type,
                        description = description,
                        municipality = datasetPoi?.municipality ?: scenic.municipality ?: "Unknown",
                        lat = scenic.lat,
                        lon = scenic.lon,
                        scenicScore = boostedScore,
                        province = datasetPoi?.province ?: "",
                        tags = datasetPoi?.tags ?: emptyList(),
                        photoHint = datasetPoi?.photoHint ?: "",
                        imageUrl = datasetPoi?.imageUrl ?: ""
                    )
                }.toMutableList()

                try {
                    val matched = pois.count { it.province.isNotBlank() || it.tags.isNotEmpty() || it.photoHint.isNotBlank() }
                    val withImage = pois.count { it.imageUrl.isNotBlank() }
                    Log.d("RouteViewModel", "Enrichment summary: scenic=${scenicPois.size} matched=$matched withImage=$withImage")
                } catch (_: Exception) {
                }

                // Add dataset POIs directly to routePois without filtering
                _routePois.value = pois

                // Sort by category priority and take top recommendations
                val sortedPois = pois.sortedWith(
                    compareBy<Poi> { poi ->
                        when (poi.category) {
                            "tourism", "scenic" -> 0
                            "historic" -> 1
                            "natural" -> 2
                            "mountain" -> 3
                            "coastal" -> 4
                            else -> 5
                        }
                    }.thenBy { it.name }
                ).take(75)

                // Apply ML reranker on the top candidates
                val reranked = poiReranker.rerank(sortedPois, startPoint.latitude, startPoint.longitude, System.currentTimeMillis())

                // Take final top 20 for UI
                _routePois.value = reranked.take(50)
                // Compute average scenic score
                try {
                    val avgScore = if (scenicPois.isNotEmpty()) scenicPois.map { it.score }.average().toFloat() else 0f
                    _scenicScore.value = avgScore
                } catch (_: Exception) {
                    _scenicScore.value = 0f
                }

                _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.planning_your_scenic_route) + " Found ${scenicPois.size} scenic spots."
                _isFetchingPois.value = false

                // Cache the results for future use
                val cacheKey = "${startPoint.latitude},${startPoint.longitude};${destPoint.latitude},${destPoint.longitude};$routingMode;${curationIntent?.hashCode() ?: 0}"
                routeCache[cacheKey] = route
                distanceCache[cacheKey] = _routeDistanceMeters.value ?: 0.0
                durationCache[cacheKey] = _routeDurationSeconds.value ?: 0L
                scoreCache[cacheKey] = _scenicScore.value ?: 0f
                poiCache[cacheKey] = pois
            } catch (e: Exception) {
                _statusMessage.value = "Error planning route: ${e.message}"
                _routePoints.value = emptyList()
                _routePois.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Plan a route using a curation destination and seeing/activity choices.
     * This lightweight helper uses the current device location as the start point.
     */
    fun planRouteCurated(destinationQuery: String, seeing: com.example.scenic_navigation.models.SeeingType, activity: com.example.scenic_navigation.models.ActivityType, extras: com.example.scenic_navigation.models.CurationIntentExtras? = null) {
        // Map seeing to routing flags
        val useOceanic = seeing == com.example.scenic_navigation.models.SeeingType.OCEANIC
        val useMountain = seeing == com.example.scenic_navigation.models.SeeingType.MOUNTAIN

        // Use current location as start (true) and empty start input
        val intent = com.example.scenic_navigation.models.CurationIntent(destinationQuery, seeing, activity)

        // If extras indicate forcing a coastal set, persist that in lastCurationIntent so planRoute can read it
        if (extras != null) {
            lastCurationIntent = intent
            // store extras in a transient property used during planning
            pendingCurationExtras = extras
        }

        planRoute(true, useOceanic, useMountain, "", destinationQuery, intent)
    }

    // transient extras supplied by the UI when invoking curated planning (cleared after use)
    private var pendingCurationExtras: com.example.scenic_navigation.models.CurationIntentExtras? = null

    fun clearRoute() {
        _routePoints.value = emptyList()
        _routePois.value = emptyList()
        _statusMessage.value = null
        currentDestination = null
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {
            settingsObserver?.let { com.example.scenic_navigation.events.SettingsBus.events.removeObserver(it) }
        } catch (_: Exception) {
        }
    }

    // Planner-level curation boosts are applied inside `ScenicRoutePlanner`; removed redundant ViewModel booster.

    /**
     * Recalculate route from a new start location (used when user goes off route)
     */
    fun recalculateRouteFromLocation(newStart: GeoPoint) {
        val destination = currentDestination ?: return

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.routing_status)
                _isRouting.value = true

                // Fetch new route
                // Reuse last curation intent to suggest via-points for recalculation
                val LONG_OCEANIC_THRESHOLD = 150_000.0 // meters
                val directDistanceRecalc = newStart.distanceToAsDouble(destination)

                // Determine routing mode for recalculation - use the same mode as original route
                val recalcRoutingMode = currentRoutingMode

                val waypointsToPassForRecalc = if (recalcRoutingMode == "oceanic" && directDistanceRecalc > LONG_OCEANIC_THRESHOLD) {
                    null // Let RoutingService pick coastal set for long oceanic routes
                } else {
                    null // No additional via-points to avoid detours
                }

                val route = routingService.fetchRoute(newStart, destination, packageName, recalcRoutingMode, waypointsToPassForRecalc)

                if (route.isEmpty()) {
                    _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.could_not_calculate_route)
                    _isLoading.value = false
                    _isRouting.value = false
                    return@launch
                }

                _routePoints.value = route

                // Compute approximate route distance (meters) and estimated duration
                try {
                    var totalMeters = 0.0
                    for (i in 0 until route.size - 1) {
                        val a = route[i]
                        val b = route[i + 1]
                        totalMeters += com.example.scenic_navigation.utils.GeoUtils.haversine(a.latitude, a.longitude, b.latitude, b.longitude)
                    }
                    _routeDistanceMeters.value = totalMeters
                    // Estimate duration using average speed 60 km/h -> 16.6667 m/s
                    val avgSpeedMps = 16.6667
                    val seconds = (totalMeters / avgSpeedMps).toLong()
                    _routeDurationSeconds.value = seconds
                } catch (_: Exception) {
                    _routeDistanceMeters.value = 0.0
                    _routeDurationSeconds.value = 0L
                }

                // Fetch scenic POIs along the new route
                val routeType = when (currentRoutingMode) {
                    "oceanic" -> "oceanic"
                    "mountain" -> "mountain"
                    else -> "generic"
                }


                _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.finding_pois_status)
                _isRouting.value = false
                _isFetchingPois.value = true
                val scenicPois = scenicRoutePlanner.fetchScenicPois(route, packageName, routeType, lastCurationIntent) { status ->
                    _statusMessage.postValue(status)
                }

                // Load dataset POIs for enrichment (description, imageUrl, tags, etc.)
                val datasetPois = poiService.getDatabasePois()

                // Convert ScenicPoi to Poi
                val pois = scenicPois.map { scenic ->
                    // Find matching dataset POI
                    val datasetPoi = findBestDatasetPoiForScenic(scenic, datasetPois)
                    val description = datasetPoi?.description ?: scenic.description.ifBlank { "A scenic location along the route." }
                    // Boost scenic score for POIs that came from the dataset
                    val boostedScore = scenic.score.toFloat() + (if (datasetPoi != null) 20f else 0f)
                    Poi(
                        name = scenic.name,
                        category = scenic.type,
                        description = description,
                        municipality = datasetPoi?.municipality ?: scenic.municipality ?: "Unknown",
                        lat = scenic.lat,
                        lon = scenic.lon,
                        scenicScore = boostedScore,
                        province = datasetPoi?.province ?: "",
                        tags = datasetPoi?.tags ?: emptyList(),
                        photoHint = datasetPoi?.photoHint ?: "",
                        imageUrl = datasetPoi?.imageUrl ?: ""
                    )
                }.toMutableList()

                try {
                    val matched = pois.count { it.province.isNotBlank() || it.tags.isNotEmpty() || it.photoHint.isNotBlank() }
                    val withImage = pois.count { it.imageUrl.isNotBlank() }
                    Log.d("RouteViewModel", "Recalc enrichment summary: scenic=${scenicPois.size} matched=$matched withImage=$withImage")
                } catch (_: Exception) {
                }

                // Add dataset POIs directly to routePois without filtering
                _routePois.value = pois

                // Compute average scenic score for recalculated route
                try {
                    val avgScore = if (scenicPois.isNotEmpty()) scenicPois.map { it.score }.average().toFloat() else 0f
                    _scenicScore.value = avgScore
                } catch (_: Exception) {
                    _scenicScore.value = 0f
                }
                _statusMessage.value = getApplication<Application>().getString(com.example.scenic_navigation.R.string.planning_your_scenic_route) + " Found ${scenicPois.size} scenic spots."
                _isFetchingPois.value = false

                // Cache the results for future use
                val cacheKey = "${newStart.latitude},${newStart.longitude};${destination.latitude},${destination.longitude};$currentRoutingMode;${lastCurationIntent?.hashCode() ?: 0}"
                routeCache[cacheKey] = route
                distanceCache[cacheKey] = _routeDistanceMeters.value ?: 0.0
                durationCache[cacheKey] = _routeDurationSeconds.value ?: 0L
                scoreCache[cacheKey] = _scenicScore.value ?: 0f
                poiCache[cacheKey] = pois
            } catch (e: Exception) {
                _statusMessage.value = "Error recalculating route: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun findBestDatasetPoiForScenic(
        scenic: com.example.scenic_navigation.models.ScenicPoi,
        datasetPois: List<Poi>
    ): Poi? {
        if (datasetPois.isEmpty()) return null

        val scenicName = normalizePoiName(scenic.name)
        if (scenicName.isBlank()) return null

        // 1) Exact normalized name match
        datasetPois.firstOrNull { normalizePoiName(it.name) == scenicName }?.let { return it }

        // 2) Fuzzy name containment match, choose nearest when coords are available
        val fuzzy = datasetPois.filter {
            val candidate = normalizePoiName(it.name)
            candidate.isNotBlank() && (candidate.contains(scenicName) || scenicName.contains(candidate))
        }
        if (fuzzy.isNotEmpty()) {
            val nearestFuzzy = fuzzy.minByOrNull { candidate ->
                try {
                    com.example.scenic_navigation.utils.GeoUtils.haversine(
                        scenic.lat,
                        scenic.lon,
                        candidate.lat ?: scenic.lat,
                        candidate.lon ?: scenic.lon
                    )
                } catch (_: Exception) {
                    Double.MAX_VALUE
                }
            }
            if (nearestFuzzy != null) {
                val d = try {
                    com.example.scenic_navigation.utils.GeoUtils.haversine(
                        scenic.lat,
                        scenic.lon,
                        nearestFuzzy.lat ?: scenic.lat,
                        nearestFuzzy.lon ?: scenic.lon
                    )
                } catch (_: Exception) {
                    Double.MAX_VALUE
                }
                if (d <= 5000.0) return nearestFuzzy
            }
        }

        // 3) Nearest coordinate fallback for very close points
        val nearest = datasetPois.minByOrNull { candidate ->
            try {
                com.example.scenic_navigation.utils.GeoUtils.haversine(
                    scenic.lat,
                    scenic.lon,
                    candidate.lat ?: scenic.lat,
                    candidate.lon ?: scenic.lon
                )
            } catch (_: Exception) {
                Double.MAX_VALUE
            }
        } ?: return null

        val nearestDist = try {
            com.example.scenic_navigation.utils.GeoUtils.haversine(
                scenic.lat,
                scenic.lon,
                nearest.lat ?: scenic.lat,
                nearest.lon ?: scenic.lon
            )
        } catch (_: Exception) {
            Double.MAX_VALUE
        }

        return if (nearestDist <= 400.0) nearest else null
    }

    private fun normalizePoiName(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
