package com.example.scenic_navigation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.ml.PoiReranker
import com.example.scenic_navigation.services.WebSearchService
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader

class RecommendationsViewModel(application: Application) : AndroidViewModel(application) {
    private val poiReranker = PoiReranker(application.applicationContext)
    private val webSearchService = WebSearchService()

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _recommendations = MutableLiveData<List<Poi>>(emptyList())
    val recommendations: LiveData<List<Poi>> = _recommendations

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

    fun fetchRecommendations() {
        viewModelScope.launch {
            try {
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

                // Enhance descriptions with Wikipedia if empty
                for (i in allPois.indices) {
                    val poi = allPois[i]
                    if (poi.description.isBlank()) {
                        val wikiDesc = webSearchService.getDescriptionForPoi(poi.name)
                        if (wikiDesc != null) {
                            allPois[i] = poi.copy(description = wikiDesc)
                        }
                    }
                }

                // Remove duplicates based on name and location
                val uniquePois = allPois.distinctBy {
                    "${it.name}_${it.lat?.toString()?.take(6)}_${it.lon?.toString()?.take(6)}"
                }

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

                // Apply ML reranker on the top candidates (run on IO thread via viewModelScope)
                val center = GeoPoint(14.5995, 120.9842) // Manila as center
                val reranked = poiReranker.rerank(sortedPois, center.latitude, center.longitude, System.currentTimeMillis())

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
}
