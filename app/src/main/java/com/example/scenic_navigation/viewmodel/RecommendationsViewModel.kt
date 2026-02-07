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
    }

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _recommendations = MutableLiveData<List<Poi>>(emptyList())
    val recommendations: LiveData<List<Poi>> = _recommendations

    // Keep a small curated list (by POI name) persisted so users can curate POIs they like
    private val CURATED_KEY = "curated_pois"

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

    /**
     * Allow user to 'like' or curate a POI. This increments the category count (used by personalization)
     * and persists the POI name in a curated set so future suggestions can prioritize it.
     */
    fun likePoi(poi: Poi) {
        viewModelScope.launch {
            try {
                val cat = poi.category ?: "unknown"
                prefStore.incrementCategory(cat)
                // persist curated name
                val set = sharedPrefs.getStringSet(CURATED_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                set.add(poi.name)
                sharedPrefs.edit().putStringSet(CURATED_KEY, set).apply()
                Log.i("RecommendationsVM", "Liked POI: ${poi.name} (category=$cat)")
            } catch (e: Exception) {
                Log.w("RecommendationsVM", "Failed to like POI: ${e.message}")
            }
        }
    }

    fun removeCuratedPoiName(name: String) {
        viewModelScope.launch {
            val set = sharedPrefs.getStringSet(CURATED_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (set.remove(name)) {
                sharedPrefs.edit().putStringSet(CURATED_KEY, set).apply()
            }
        }
    }

    private fun isCurated(poi: Poi): Boolean {
        val set = sharedPrefs.getStringSet(CURATED_KEY, emptySet()) ?: emptySet()
        return set.contains(poi.name)
    }

    fun fetchRecommendations() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val packageName = getApplication<Application>().packageName ?: ""

                // 1) Try to load curated candidates from CSV dataset in assets
                val assetManager = getApplication<Application>().assets
                val allPois = mutableListOf<Poi>()
                try {
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
                                lat = parts[3].trim().removeSurrounding("\"" ).toDoubleOrNull(),
                                lon = parts[4].trim().removeSurrounding("\"" ).toDoubleOrNull()
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

                // 2) If CSV missing or empty, fall back to ScenicRoutePlanner to discover POIs near a default region (Manila)
                if (allPois.isEmpty()) {
                    Log.i("RecommendationsVM", "CSV empty — falling back to ScenicRoutePlanner to fetch candidates")
                    try {
                        // try to get user's last-known location first
                        val userLoc = locationService.getCurrentLocation()
                        val center = userLoc ?: GeoPoint(14.5995, 120.9842)
                        val nearby = listOf(center, GeoPoint(center.latitude + 0.05, center.longitude + 0.05))
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

                // 3) If still empty, fall back to PoiService local POIs
                if (allPois.isEmpty()) {
                    val local = poiService.getLocalPois()
                    if (local.isNotEmpty()) {
                        allPois.addAll(local)
                        Log.i("RecommendationsVM", "Using ${local.size} local POIs from PoiService as fallback")
                    }
                }

                // 4) If still empty, use existing hardcoded fallback
                if (allPois.isEmpty()) {
                    Log.w("RecommendationsVM", "No candidate POIs found — using hardcoded defaults")
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
                    return@launch
                }

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

                // Choose a reasonable center for ML features (Manila as default or first POI)
                val centerLat = sortedPois.getOrNull(0)?.lat ?: 14.5995
                val centerLon = sortedPois.getOrNull(0)?.lon ?: 120.9842

                val reranked = try {
                    poiReranker.rerank(sortedPois, centerLat, centerLon, System.currentTimeMillis())
                } catch (e: Exception) {
                     Log.w("RecommendationsVM", "ML reranker failed, using sorted list: ${e.message}")
                     sortedPois
                 }

                // Boost curated POIs (if user has curated any) by moving them to the top
                val curatedSet = sharedPrefs.getStringSet(CURATED_KEY, emptySet()) ?: emptySet()
                val finalList = if (curatedSet.isNotEmpty()) {
                    val (curated, others) = reranked.partition { curatedSet.contains(it.name) }
                    curated + others
                } else {
                    reranked
                }

                _recommendations.value = finalList.take(20)

            } catch (e: Exception) {
                Log.e("RecommendationsVM", "Unexpected error fetching recommendations: ${e.message}")
                // keep UI stable — produce empty list
                _recommendations.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {}
    }
}
