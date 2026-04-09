package com.example.scenic_navigation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.ml.PoiReranker
import com.example.scenic_navigation.ml.MlInferenceEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Log
import com.example.scenic_navigation.services.ScenicRoutePlanner
import com.example.scenic_navigation.services.PoiService
import com.example.scenic_navigation.services.LocationService
import com.example.scenic_navigation.services.UserPreferenceStore
import com.example.scenic_navigation.services.SettingsStore
import android.content.Context
import android.content.SharedPreferences
import com.example.scenic_navigation.utils.GeoUtils
import com.example.scenic_navigation.FavoriteStore

class RecommendationsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application.applicationContext)
    private val prefStore = UserPreferenceStore(application.applicationContext)
    // Service instances used by the ViewModel — create once from application context
    private val locationService: LocationService = LocationService(application.applicationContext)
    private val scenicPlanner: ScenicRoutePlanner = ScenicRoutePlanner(application.applicationContext)
    private val poiService: PoiService = PoiService(application.applicationContext)
    // Make the reranker swappable so we can enable/disable personalization at runtime
    private var poiReranker: PoiReranker
    private val sharedPrefs: SharedPreferences = application.applicationContext.getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
    // Simple in-memory cache for candidate POIs to avoid re-reading the CSV on every small filter change
    private var cachedCandidates: List<Poi>? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "personalization_enabled") {
            // Re-create the reranker to pick up personalization toggle
            val enabled = prefs.getBoolean(key, true)
            poiReranker = if (enabled) {
                PoiReranker(MlInferenceEngine(application.applicationContext, "models/poi_reranker_from_luzon.tflite"), prefStore)
            } else {
                PoiReranker(MlInferenceEngine(application.applicationContext, "models/poi_reranker_from_luzon.tflite"), null)
            }
            Log.i("RecommendationsVM", "Personalization toggled: $enabled — reranker reconfigured")
        }
    }

    init {
        // initialize reranker based on current setting
        poiReranker = if (settingsStore.isPersonalizationEnabled()) {
            PoiReranker(MlInferenceEngine(application.applicationContext, "models/poi_reranker_from_luzon.tflite"), prefStore)
        } else {
            PoiReranker(MlInferenceEngine(application.applicationContext, "models/poi_reranker_from_luzon.tflite"), null)
        }
        // register listener so changes in Settings take effect immediately
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        // Ensure FavoriteStore is initialized so we can persist favorites when users like POIs
        try {
            FavoriteStore.init(application.applicationContext)
        } catch (_: Exception) {}
    }

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _recommendations = MutableLiveData<List<Poi>>(emptyList())
    val recommendations: LiveData<List<Poi>> = _recommendations

    // Keep a small curated list (by POI name) persisted so users can curate POIs they like
    private val CURATED_KEY = "curated_pois"

    // Build canonical curated key in format: name,category,lat,lon
    private fun canonicalCuratedKey(poi: Poi): String {
        val n = poi.name.trim().replace(",", " ")
        val c = (poi.category ?: "").trim().replace(",", " ")
        val lat = poi.lat?.toString() ?: "0.0"
        val lon = poi.lon?.toString() ?: "0.0"
        return "${n},${c},${lat},${lon}"
    }

    // Public API to check/add/remove curated POIs using canonical keys
    fun isCurated(poi: Poi): Boolean {
        return try {
            val set = sharedPrefs.getStringSet(CURATED_KEY, emptySet()) ?: emptySet()
            set.contains(canonicalCuratedKey(poi))
        } catch (_: Exception) {
            false
        }
    }

    fun addCurated(poi: Poi) {
        try {
            val key = canonicalCuratedKey(poi)
            val set = sharedPrefs.getStringSet(CURATED_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (set.add(key)) {
                sharedPrefs.edit().putStringSet(CURATED_KEY, set).apply()
            }
            // also increment category preference
            val cat = poi.category ?: "unknown"
            prefStore.incrementCategory(cat)
            // Add to FavoriteStore as a persisted favorite
            try { FavoriteStore.addFavorite(key, poi) } catch (_: Exception) {}
            Log.i("RecommendationsVM", "Added curated key: $key")
            viewModelScope.launch { fetchRecommendations() }
        } catch (e: Exception) {
            Log.w("RecommendationsVM", "Failed to add curated POI: ${e.message}")
        }
    }

    fun removeCurated(poi: Poi) {
        try {
            val key = canonicalCuratedKey(poi)
            val set = sharedPrefs.getStringSet(CURATED_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (set.remove(key)) {
                sharedPrefs.edit().putStringSet(CURATED_KEY, set).apply()
                Log.i("RecommendationsVM", "Removed curated key: $key")
                // Remove from FavoriteStore as well to keep favorites in sync
                try { FavoriteStore.removeFavorite(key) } catch (_: Exception) {}
                viewModelScope.launch { fetchRecommendations() }
            }
        } catch (e: Exception) {
            Log.w("RecommendationsVM", "Failed to remove curated POI: ${e.message}")
        }
    }

    /**
     * Allow user to 'like' or curate a POI. This increments the category count (used by personalization)
     * and persists the POI name in a curated set so future suggestions can prioritize it.
     */
    fun likePoi(poi: Poi) {
        // Delegate to canonical addCurated so we keep a single canonical storage format
        addCurated(poi)
    }

    fun removeCuratedPoiName(name: String) {
        try {
            // Remove any canonical curated keys where the stored name portion matches the provided name (case-insensitive)
            val normalized = name.trim().lowercase()
            val set = sharedPrefs.getStringSet(CURATED_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            val toRemove = set.filter { key ->
                val storedName = key.split(',').firstOrNull()?.trim()?.lowercase() ?: ""
                storedName == normalized
            }
            if (toRemove.isNotEmpty()) {
                toRemove.forEach { set.remove(it) }
                sharedPrefs.edit().putStringSet(CURATED_KEY, set).apply()
                Log.i("RecommendationsVM", "Removed curated entries for name=$name, removed=${toRemove.size}")
                viewModelScope.launch { fetchRecommendations() }
            }
        } catch (e: Exception) {
            Log.w("RecommendationsVM", "Failed to remove curated POI name: ${e.message}")
        }
    }

    // Load candidate POIs once and cache them. This centralizes CSV/service fallbacks.
    private suspend fun loadCandidates(): List<Poi> = withContext(Dispatchers.IO) {
        // Return cached copy if available
        cachedCandidates?.let { return@withContext it }

        val allPois = mutableListOf<Poi>()
        try {
            allPois.addAll(poiService.getDatabasePois())
            Log.i("RecommendationsVM", "Loaded ${allPois.size} POIs from Room database")
            if (allPois.isNotEmpty()) {
                val sample = allPois.take(5).map { it.name }
                Log.d("RecommendationsVM", "Sample loaded POIs: $sample")
            } else {
                Log.w("RecommendationsVM", "No POIs found in the local Room database")
            }
        } catch (e: Exception) {
            Log.w("RecommendationsVM", "Could not load POIs from the local Room database: ${e.message}")
        }

        // Fallbacks only executed if CSV returned nothing
        if (allPois.isEmpty()) {
            try {
                val packageName = getApplication<Application>().packageName ?: ""
                // locationService.getCurrentLocation() is suspend — allowed here because we're in a suspend context
                val userLoc = locationService.getCurrentLocation()
                val center = userLoc ?: GeoPoint(14.5995, 120.9842)
                val nearby = listOf(center, GeoPoint(center.latitude + 0.05, center.longitude + 0.05))
                // scenicPlanner.fetchScenicPois is suspend — allowed in this suspend context
                val scenicPois = scenicPlanner.fetchScenicPois(nearby, packageName, "generic", null)
                if (scenicPois.isNotEmpty()) {
                    val converted = scenicPois.map { sp: com.example.scenic_navigation.models.ScenicPoi ->
                        Poi(
                            name = sp.name,
                            category = sp.type,
                            description = sp.description,
                            municipality = sp.municipality ?: "",
                            lat = sp.lat,
                            lon = sp.lon
                        )
                    }
                    allPois.addAll(converted)
                    Log.i("RecommendationsVM", "Fetched ${converted.size} scenic POIs via ScenicRoutePlanner (seed=${center.latitude},${center.longitude})")
                    Log.d("RecommendationsVM", "Sample scenic POIs: ${converted.take(5).map { it.name }}")
                }
            } catch (e: Exception) {
                Log.w("RecommendationsVM", "ScenicRoutePlanner fallback failed: ${e.message}")
            }
        }

        if (allPois.isEmpty()) {
            val local = poiService.getLocalPois()
            if (local.isNotEmpty()) {
                allPois.addAll(local)
                Log.i("RecommendationsVM", "Using ${local.size} local POIs from PoiService as fallback")
            }
        }

        // No hardcoded fallback: cache whatever we found (possibly empty) and return it.
        cachedCandidates = allPois
        Log.i("RecommendationsVM", "loadCandidates returning ${allPois.size} candidates")
        return@withContext allPois
    }

    // New overload: accept user location, max distance (km), and preferred categories which will be used for boosting
    fun fetchRecommendations(userLat: Double = 14.5995, userLon: Double = 120.9842, maxDistanceKm: Double = 50.0, preferredCategories: Set<String> = emptySet()) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                Log.d("RecommendationsVM", "fetchRecommendations invoked: userLat=$userLat userLon=$userLon maxDistance=$maxDistanceKm preferredCategories=$preferredCategories")
                val queriedCandidates = if (preferredCategories.isEmpty()) {
                    poiService.searchDatabasePois(userLat, userLon, maxDistanceKm)
                } else {
                    poiService.searchDatabasePois(userLat, userLon, maxDistanceKm, preferredCategories)
                }
                val allPois = if (queriedCandidates.isNotEmpty()) {
                    queriedCandidates.toMutableList()
                } else {
                    loadCandidates().toMutableList()
                }
                Log.i("RecommendationsVM", "Candidates after load: ${allPois.size}")

                // 5) Sort by simple category priority then rerank with ML (if available)
                val sortedPois = allPois.sortedWith(
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
                )

                // Choose a reasonable center for ML features (use provided location as primary)
                val centerLat = sortedPois.getOrNull(0)?.lat ?: userLat
                val centerLon = sortedPois.getOrNull(0)?.lon ?: userLon

                var reranked = try {
                    val r = poiReranker.rerank(sortedPois, centerLat, centerLon, System.currentTimeMillis())
                    Log.i("RecommendationsVM", "Reranker produced ${r.size} items")
                    if (r.isNotEmpty()) {
                        Log.d("RecommendationsVM", "Sample reranked POIs: ${r.take(5).map { it.name }}")
                    }
                    r
                } catch (e: Exception) {
                    Log.w("RecommendationsVM", "ML reranker failed, using sorted list: ${e.message}")
                    sortedPois
                }
                // If reranker returned an empty list for some reason, fall back to the sorted list
                if (reranked.isEmpty()) {
                    Log.w("RecommendationsVM", "Reranker returned 0 items — falling back to sortedPois (size=${sortedPois.size})")
                    reranked = sortedPois
                }

                // Boost curated POIs (if user has curated any) by moving them to the top while preserving rerank order
                val curatedSet = sharedPrefs.getStringSet(CURATED_KEY, emptySet()) ?: emptySet()
                var finalList = reranked
                if (curatedSet.isNotEmpty()) {
                    // Extract stored names from canonical keys (format name,category,lat,lon)
                    val storedNames = curatedSet.mapNotNull { key ->
                        key.split(',').firstOrNull()?.trim()?.lowercase()
                    }.toSet()
                    val (curated, others) = reranked.partition { poi ->
                        val n = poi.name.trim().lowercase()
                        storedNames.contains(n)
                    }
                    Log.i("RecommendationsVM", "Curated set size=${curatedSet.size}, curatedMatched=${curated.size}")
                    finalList = curated + others
                }

                // Filter by distance (radius) from provided user location
                val beforeDistance = finalList.size
                finalList = finalList.filter { poi ->
                    if (poi.lat == null || poi.lon == null) return@filter false
                    val dMeters = GeoUtils.haversine(userLat, userLon, poi.lat, poi.lon)
                    dMeters <= (maxDistanceKm * 1000.0)
                }
                Log.i("RecommendationsVM", "After distance filter (maxKm=$maxDistanceKm): before=$beforeDistance after=${finalList.size}")
                // If distance filter removed everything (common with narrow radii), relax distance constraint
                if (finalList.isEmpty() && preferredCategories.isNotEmpty()) {
                    Log.i("RecommendationsVM", "Distance filter yielded 0 results; relaxing distance constraint for preferred categories")
                    val relaxed = reranked.filter { poi -> poi.lat != null && poi.lon != null }
                    Log.i("RecommendationsVM", "Relaxed candidate count=${relaxed.size}")
                    finalList = relaxed
                }

                // If user has preferred categories, boost those while preserving order — preferred items first
                if (preferredCategories.isNotEmpty()) {
                    // Map UI-selected category tokens to planner-style tag filters and boosts
                    val (poiBoosts, tagFilters) = mapCategoriesToPlannerFilters(preferredCategories)

                    // Partition into preferred (matches any tagFilter) and others
                    val lowerTags = tagFilters.map { it.lowercase() }
                    val (preferred, others) = finalList.partition { poi ->
                        val cat = poi.category?.lowercase() ?: ""
                        lowerTags.any { t -> cat.contains(t) || poi.name.lowercase().contains(t) }
                    }
                    Log.i("RecommendationsVM", "Preferred partition: preferred=${preferred.size} others=${others.size} (preferredTags=${tagFilters})")

                    // Within preferred, sort by how many boost tokens they match (higher first) then by name
                    val preferredSorted = preferred.sortedWith(compareByDescending<Poi> { poi ->
                        val cat = poi.category?.lowercase() ?: ""
                        var score = 0.0
                        for ((k, v) in poiBoosts) {
                            if (cat.contains(k) || poi.name.lowercase().contains(k)) score += v
                        }
                        score
                    }.thenBy { it.name })

                    finalList = preferredSorted + others
                }

                Log.i("RecommendationsVM", "Final recommendations count=${finalList.size}")
                if (finalList.isNotEmpty()) Log.d("RecommendationsVM", "Sample final POIs=${finalList.take(5).map { it.name }}")
                _recommendations.value = finalList.take(20)

                // Telemetry: log recommendation impression snapshot (non-blocking)
                try {
                    val reqId = java.util.UUID.randomUUID().toString()
                    val topKeys = finalList.take(10).map { poi ->
                        try { com.example.scenic_navigation.ui.RecommendationsAdapter.canonicalKey(poi) } catch (_: Exception) { poi.name }
                    }
                    com.example.scenic_navigation.services.Telemetry.logRecommendationImpression(reqId, topKeys, settingsStore.isPersonalizationEnabled(), /* modelVersion */ null)
                } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.e("RecommendationsVM", "Unexpected error fetching recommendations: ${e.message}")
                // keep UI stable — produce empty list
                _recommendations.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Keep legacy parameterless fetchRecommendations() calling the new overload with defaults
    fun fetchRecommendations() {
        // try to use last-known location if available
        viewModelScope.launch {
            try {
                val loc = locationService.getCurrentLocation()
                if (loc != null) {
                    fetchRecommendations(loc.latitude, loc.longitude, 50.0, emptySet())
                } else {
                    fetchRecommendations(14.5995, 120.9842, 50.0, emptySet())
                }
            } catch (e: Exception) {
                fetchRecommendations(14.5995, 120.9842, 50.0, emptySet())
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {}
    }

    // Map a set of selected category tokens (from UI chips) to planner-friendly tag filters and per-tag boosts
    private fun mapCategoriesToPlannerFilters(selected: Set<String>): Pair<Map<String, Double>, List<String>> {
        val boosts = mutableMapOf<String, Double>()
        val filters = mutableListOf<String>()

        for (s in selected) {
            val t = s.lowercase()
            when {
                t.contains("scenic") -> {
                    filters += listOf("viewpoint", "attraction", "park")
                    boosts.putAll(listOf("viewpoint" to 0.6, "attraction" to 0.4, "park" to 0.3))
                }
                t.contains("natural") || t.contains("nature") -> {
                    filters += listOf("nature park", "park", "waterfall", "viewpoint")
                    boosts.putAll(listOf("nature park" to 0.6, "waterfall" to 0.5, "park" to 0.3))
                }
                t.contains("tourism") -> {
                    filters += listOf("tourist attraction", "attraction", "viewpoint")
                    boosts.putAll(listOf("tourist attraction" to 0.5, "attraction" to 0.4))
                }
                t.contains("historic") || t.contains("landmark") -> {
                    filters += listOf("museum", "historical site", "monument", "heritage", "historic")
                    boosts.putAll(listOf("museum" to 0.7, "historical site" to 0.6, "monument" to 0.5))
                }
                t.contains("cultur") -> {
                    filters += listOf("museum", "historical site", "cultural spot")
                    boosts.putAll(listOf("museum" to 0.6, "historical site" to 0.5, "cultural spot" to 0.4))
                }
                t.contains("food") || t.contains("restaurant") || t.contains("cafe") -> {
                    filters += listOf("restaurant", "cafe", "food")
                    boosts.putAll(listOf("restaurant" to 0.5, "cafe" to 0.4, "food" to 0.3))
                }
                t.contains("shop") || t.contains("market") -> {
                    filters += listOf("shop", "market", "souvenir")
                    boosts.putAll(listOf("shop" to 0.4, "market" to 0.5, "souvenir" to 0.4))
                }
                t.contains("coast") || t.contains("beach") || t.contains("ocean") -> {
                    filters += listOf("beach", "bay", "coast", "cape")
                    boosts.putAll(listOf("beach" to 0.7, "coast" to 0.5, "bay" to 0.4))
                }
                t.contains("mountain") || t.contains("mount") || t.contains("peak") -> {
                    filters += listOf("peak", "mountain", "volcano", "ridge")
                    boosts.putAll(listOf("peak" to 0.7, "volcano" to 0.6, "ridge" to 0.4))
                }
                else -> {
                    // generic token fallback
                    val token = t.trim()
                    if (token.isNotEmpty()) {
                        filters += token
                        boosts[token] = 0.3
                    }
                }
            }
        }

        // Deduplicate filters maintaining order
        val uniqFilters = filters.fold(mutableListOf<String>()) { acc, v -> if (!acc.contains(v)) acc.apply { add(v) } else acc }
        return Pair(boosts, uniqFilters)
    }
}
