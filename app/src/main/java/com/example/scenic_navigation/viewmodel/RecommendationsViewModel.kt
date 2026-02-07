package com.example.scenic_navigation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.ml.PoiReranker
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
<<<<<<< Updated upstream

class RecommendationsViewModel(application: Application) : AndroidViewModel(application) {
    private val poiReranker = PoiReranker(application.applicationContext)
=======
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
>>>>>>> Stashed changes

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _recommendations = MutableLiveData<List<Poi>>(emptyList())
    val recommendations: LiveData<List<Poi>> = _recommendations

<<<<<<< Updated upstream
=======
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

>>>>>>> Stashed changes
    // Simple CSV parser to handle quoted fields
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString().trim())
        return result
    }

<<<<<<< Updated upstream
    fun fetchRecommendations() {
=======
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
            val assetManager = getApplication<Application>().assets
            val inputStream = assetManager.open("datasets/luzon_dataset.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLine() // skip header
            reader.forEachLine { line ->
                val parts = parseCsvLine(line)
                if (parts.size >= 6) {
                    val poi = Poi(
                        name = parts[0].trim().removeSurrounding("\"") ,
                        category = parts[1].trim().removeSurrounding("\"") ,
                        description = parts[5].trim().removeSurrounding("\"") ,
                        municipality = parts[2].trim().removeSurrounding("\"") ,
                        lat = parts[3].trim().removeSurrounding("\"").toDoubleOrNull(),
                        lon = parts[4].trim().removeSurrounding("\"").toDoubleOrNull()
                    )
                    if (poi.name.isNotBlank() && poi.lat != null && poi.lon != null) {
                        allPois.add(poi)
                    }
                }
            }
            reader.close()
            inputStream.close()
            Log.i("RecommendationsVM", "Loaded ${allPois.size} POIs from luzon_dataset.csv")
        } catch (e: Exception) {
            Log.w("RecommendationsVM", "Could not load luzon_dataset.csv from assets: ${e.message}")
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
        return@withContext allPois
    }

    // New overload: accept user location, max distance (km), and preferred categories which will be used for boosting
    fun fetchRecommendations(userLat: Double = 14.5995, userLon: Double = 120.9842, maxDistanceKm: Double = 50.0, preferredCategories: Set<String> = emptySet()) {
>>>>>>> Stashed changes
        viewModelScope.launch {
            try {
<<<<<<< Updated upstream
                _isLoading.value = true

                // Load POIs from CSV dataset
                val assetManager = getApplication<Application>().assets
                val inputStream = assetManager.open("datasets/luzon_dataset.csv")
                val reader = BufferedReader(InputStreamReader(inputStream))
                val allPois = mutableListOf<Poi>()

                reader.readLine() // Skip header
                reader.forEachLine { line ->
                    val parts = parseCsvLine(line)
                    if (parts.size >= 6) {
                        val poi = Poi(
                            name = parts[0].trim().removeSurrounding("\""),
                            category = parts[1].trim().removeSurrounding("\""),
                            description = parts[5].trim().removeSurrounding("\""),
                            municipality = parts[2].trim().removeSurrounding("\""),
                            lat = parts[3].trim().removeSurrounding("\"").toDoubleOrNull(),
                            lon = parts[4].trim().removeSurrounding("\"").toDoubleOrNull()
                        )
                        if (poi.name.isNotBlank() && poi.lat != null && poi.lon != null) {
                            allPois.add(poi)
                        }
                    }
                }
                reader.close()
                inputStream.close()

                // Remove duplicates based on name and location
                val uniquePois = allPois.distinctBy {
                    "${it.name}_${it.lat?.toString()?.take(6)}_${it.lon?.toString()?.take(6)}"
                }
=======
                // Load candidates via cached loader
                val allPois = loadCandidates().toMutableList()
>>>>>>> Stashed changes

                // Sort by category priority and take top recommendations
                val sortedPois = uniquePois.sortedWith(
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
                ).take(50)

<<<<<<< Updated upstream
                // Apply ML reranker on the top candidates (run on IO thread via viewModelScope)
                val center = GeoPoint(14.5995, 120.9842) // Manila as center
                val reranked = poiReranker.rerank(sortedPois, center.latitude, center.longitude, System.currentTimeMillis())
=======
                // Choose a reasonable center for ML features (use provided location as primary)
                val centerLat = sortedPois.getOrNull(0)?.lat ?: userLat
                val centerLon = sortedPois.getOrNull(0)?.lon ?: userLon

                val reranked = try {
                    poiReranker.rerank(sortedPois, centerLat, centerLon, System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.w("RecommendationsVM", "ML reranker failed, using sorted list: ${e.message}")
                    sortedPois
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
                finalList = finalList.filter { poi ->
                    if (poi.lat == null || poi.lon == null) return@filter false
                    val dMeters = GeoUtils.haversine(userLat, userLon, poi.lat, poi.lon)
                    dMeters <= (maxDistanceKm * 1000.0)
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

                _recommendations.value = finalList.take(20)
>>>>>>> Stashed changes

                // Take final top 20 for UI
                _recommendations.value = reranked.take(20)
            } catch (e: Exception) {
                // Fallback to some default recommendations if API fails
                _recommendations.value = listOf(
                    Poi(
                        name = "Banaue Rice Terraces",
                        category = "mountain",
                        description = "2,000-year-old terraces carved into mountains",
                        municipality = "Banaue",
                        lat = 16.9269,
                        lon = 121.0583
                    ),
                    Poi(
                        name = "El Nido",
                        category = "coastal",
                        description = "Stunning limestone cliffs and pristine beaches",
                        municipality = "El Nido",
                        lat = 11.1949,
                        lon = 119.4013
                    ),
                    Poi(
                        name = "Vigan Heritage Village",
                        category = "historic",
                        description = "Well-preserved Spanish colonial town",
                        municipality = "Vigan",
                        lat = 17.5747,
                        lon = 120.3869
                    ),
                    Poi(
                        name = "Chocolate Hills",
                        category = "scenic",
                        description = "Unique geological formations in Bohol",
                        municipality = "Bohol",
                        lat = 9.8167,
                        lon = 124.1694
                    ),
                    Poi(
                        name = "Mayon Volcano",
                        category = "mountain",
                        description = "Perfect cone-shaped active volcano",
                        municipality = "Albay",
                        lat = 13.2572,
                        lon = 123.6856
                    ),
                    Poi(
                        name = "Tubbataha Reefs Natural Park",
                        category = "coastal",
                        description = "UNESCO World Heritage marine sanctuary",
                        municipality = "Palawan",
                        lat = 8.8333,
                        lon = 119.8333
                    ),
                    Poi(
                        name = "Intramuros",
                        category = "historic",
                        description = "Historic walled city in Manila",
                        municipality = "Manila",
                        lat = 14.5900,
                        lon = 120.9750
                    ),
                    Poi(
                        name = "Boracay White Beach",
                        category = "coastal",
                        description = "World-famous white sand beach",
                        municipality = "Malay",
                        lat = 11.9674,
                        lon = 121.9248
                    ),
                    Poi(
                        name = "Taal Volcano",
                        category = "mountain",
                        description = "Volcano within a lake on an island",
                        municipality = "Batangas",
                        lat = 14.0023,
                        lon = 120.9933
                    ),
                    Poi(
                        name = "Siargao Island",
                        category = "coastal",
                        description = "Surfing capital of the Philippines",
                        municipality = "Surigao del Norte",
                        lat = 9.8500,
                        lon = 126.0500
                    ),
                    Poi(
                        name = "Sagada Hanging Coffins",
                        category = "historic",
                        description = "Ancient burial tradition site",
                        municipality = "Sagada",
                        lat = 17.0833,
                        lon = 120.9000
                    ),
                    Poi(
                        name = "Coron Island",
                        category = "scenic",
                        description = "Crystal-clear lakes and shipwreck diving",
                        municipality = "Coron",
                        lat = 11.9964,
                        lon = 120.2072
                    ),
                    Poi(
                        name = "Puerto Princesa Underground River",
                        category = "natural",
                        description = "UNESCO World Heritage subterranean river",
                        municipality = "Puerto Princesa",
                        lat = 10.1697,
                        lon = 118.9238
                    ),
                    Poi(
                        name = "Mount Pulag",
                        category = "mountain",
                        description = "Third highest peak, famous for sea of clouds",
                        municipality = "Benguet",
                        lat = 16.5967,
                        lon = 120.8869
                    ),
                    Poi(
                        name = "Hundred Islands National Park",
                        category = "coastal",
                        description = "124 islands and islets at low tide",
                        municipality = "Alaminos",
                        lat = 16.1903,
                        lon = 120.0247
                    ),
                    Poi(
                        name = "Malapascua Island",
                        category = "coastal",
                        description = "Famous for thresher shark diving",
                        municipality = "Daanbantayan",
                        lat = 11.3333,
                        lon = 124.1167
                    ),
                    Poi(
                        name = "Kawasan Falls",
                        category = "natural",
                        description = "Multi-tiered turquoise waterfalls",
                        municipality = "Badian",
                        lat = 9.6111,
                        lon = 123.7000
                    ),
                    Poi(
                        name = "Pagsanjan Falls",
                        category = "natural",
                        description = "Famous waterfall with boat ride",
                        municipality = "Pagsanjan",
                        lat = 14.0528,
                        lon = 121.5695
                    ),
                    Poi(
                        name = "Enchanted River",
                        category = "natural",
                        description = "Mystical river with blue water",
                        municipality = "Hinatuan",
                        lat = 8.4333,
                        lon = 126.1611
                    ),
                    Poi(
                        name = "Siquijor Island",
                        category = "scenic",
                        description = "Mystical island with beautiful beaches",
                        municipality = "Siquijor",
                        lat = 9.2145,
                        lon = 123.6156
                    ),
                    Poi(
                        name = "Camiguin Island",
                        category = "scenic",
                        description = "Island born of fire with hot springs",
                        municipality = "Camiguin",
                        lat = 9.2519,
                        lon = 125.1215
                    ),
                    Poi(
                        name = "Malabon Fish Market",
                        category = "cultural",
                        description = "Experience local seafood and market life",
                        municipality = "Malabon",
                        lat = 14.6500,
                        lon = 120.9658
                    ),
                    Poi(
                        name = "Caramoan Islands",
                        category = "scenic",
                        description = "Remote paradise with limestone formations",
                        municipality = "Caramoan",
                        lat = 13.7667,
                        lon = 123.8667
                    ),
                    Poi(
                        name = "Batad Rice Terraces",
                        category = "mountain",
                        description = "Amphitheater-like terraces in Ifugao",
                        municipality = "Ifugao",
                        lat = 16.8833,
                        lon = 121.0500
                    ),
                    Poi(
                        name = "Nacpan Beach",
                        category = "coastal",
                        description = "4km stretch of golden sand beach",
                        municipality = "El Nido",
                        lat = 11.2833,
                        lon = 119.4667
                    )
                )
            } finally {
                _isLoading.value = false
            }
        }
    }
<<<<<<< Updated upstream
=======

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
>>>>>>> Stashed changes
}
