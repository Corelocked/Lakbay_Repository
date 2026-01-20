package com.example.scenic_navigation.services

import android.util.Log
import com.example.scenic_navigation.models.Poi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * Service for searching the web for POIs and analyzing content for scenic quality
 */
    class WebSearchService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Search for tourist attractions near a location using web search
     */
    suspend fun searchAttractionsNearLocation(
        location: GeoPoint,
        radiusKm: Int = 5
    ): List<Poi> = withContext(Dispatchers.IO) {
        // Use Wikipedia geosearch API (free, no API key required)
        return@withContext searchWithWikipediaGeosearch(location, radiusKm)
    }

    /**
     * Use Wikipedia geosearch to find attractions and fetch page extracts for better analysis
     */
    private suspend fun searchWithWikipediaGeosearch(
        location: GeoPoint,
        radiusKm: Int
    ): List<Poi> = withContext(Dispatchers.IO) {
        val url = "https://en.wikipedia.org/w/api.php?" +
                "action=query" +
                "&list=geosearch" +
                "&gscoord=${location.latitude}|${location.longitude}" +
                "&gsradius=${radiusKm * 1000}" + // Radius in meters
                "&gslimit=15" + // Increased limit for better selection
                "&format=json" +
                "&origin=*"

        return@withContext try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")

            if (json.optString("error") != null) {
                Log.e("WebSearchService", "Search API error: ${json.optJSONObject("error")?.optString("message")}")
                return@withContext emptyList()
            }

            val pages = json.optJSONObject("query")?.optJSONArray("geosearch") ?: return@withContext emptyList()
            val pois = mutableListOf<Poi>()

            // Get page IDs for extract and category fetching
            val pageIds = mutableListOf<String>()
            val pageTitles = mutableListOf<String>()
            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val pageId = page.optString("pageid", "")
                val title = page.optString("title", "")
                if (pageId.isNotEmpty()) {
                    pageIds.add(pageId)
                    pageTitles.add(title)
                }
            }

            // Fetch page extracts and categories for richer analysis
            val extracts = if (pageIds.isNotEmpty()) {
                fetchWikipediaExtracts(pageIds.joinToString("|"))
            } else {
                emptyMap()
            }

            val categories = if (pageIds.isNotEmpty()) {
                fetchWikipediaCategories(pageIds.joinToString("|"))
            } else {
                emptyMap()
            }

            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val title = page.optString("title", "")
                val lat = page.optDouble("lat", 0.0)
                val lon = page.optDouble("lon", 0.0)
                val pageId = page.optString("pageid", "")

                // Skip if coordinates are invalid
                if (lat == 0.0 && lon == 0.0) continue

                // Use page extract if available, otherwise fall back to title
                val content = extracts[pageId] ?: title
                val pageCategories = categories[pageId] ?: emptyList()

                // Enhanced curation: check categories and content quality
                val isTouristAttraction = isTouristAttraction(title, pageCategories)
                val contentQuality = assessContentQuality(content)
                val distance = calculateDistance(location.latitude, location.longitude, lat, lon)

                if (!isTouristAttraction && contentQuality < 0.3f) continue

                val scenicScore = analyzeContentForScenicScore(content, pageCategories)

                // Distance penalty: closer locations get higher priority
                val distancePenalty = if (distance > radiusKm * 1000) 0.5f else 1.0f
                val finalScore = scenicScore * distancePenalty * contentQuality

                if (finalScore > 15f) { // Adjusted threshold
                    val description = buildDescription(title, lat, lon, pageCategories, content)
                    pois.add(Poi(
                        name = title.take(50),
                        category = determineCategory(title, pageCategories),
                        description = description.take(150),
                        municipality = "",
                        lat = lat,
                        lon = lon,
                        scenicScore = finalScore
                    ))
                }
            }

            // Sort by scenic score and take top 10
            pois.sortedByDescending { it.scenicScore }.take(10)
        } catch (e: Exception) {
            Log.e("WebSearchService", "Error searching web: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch Wikipedia page extracts for richer content analysis
     */
    private suspend fun fetchWikipediaExtracts(pageIds: String): Map<String, String> = withContext(Dispatchers.IO) {
        val url = "https://en.wikipedia.org/w/api.php?" +
                "action=query" +
                "&pageids=$pageIds" +
                "&prop=extracts" +
                "&exintro=true" +
                "&explaintext=true" +
                "&exsectionformat=plain" +
                "&format=json" +
                "&origin=*"

        return@withContext try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")

            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext emptyMap()
            val extracts = mutableMapOf<String, String>()

            pages.keys().forEach { pageId ->
                val page = pages.optJSONObject(pageId.toString())
                val extract = page?.optString("extract", "") ?: ""
                if (extract.isNotEmpty()) {
                    extracts[pageId] = extract.take(500) // Limit extract length
                }
            }

            extracts
        } catch (e: Exception) {
            Log.e("WebSearchService", "Error fetching extracts: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Fetch Wikipedia page categories for better classification
     */
    private suspend fun fetchWikipediaCategories(pageIds: String): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val url = "https://en.wikipedia.org/w/api.php?" +
                "action=query" +
                "&pageids=$pageIds" +
                "&prop=categories" +
                "&cllimit=20" +
                "&format=json" +
                "&origin=*"

        return@withContext try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")

            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext emptyMap()
            val categoryMap = mutableMapOf<String, List<String>>()

            pages.keys().forEach { pageId ->
                val page = pages.optJSONObject(pageId.toString())
                val categories = page?.optJSONArray("categories")
                val categoryList = mutableListOf<String>()

                if (categories != null) {
                    for (j in 0 until categories.length()) {
                        val category = categories.optJSONObject(j)
                        val title = category?.optString("title", "") ?: ""
                        if (title.isNotEmpty()) {
                            categoryList.add(title.removePrefix("Category:"))
                        }
                    }
                }

                categoryMap[pageId] = categoryList
            }

            categoryMap
        } catch (e: Exception) {
            Log.e("WebSearchService", "Error fetching categories: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Check if a page represents a tourist attraction based on title and categories
     */
    private fun isTouristAttraction(title: String, categories: List<String>): Boolean {
        val touristKeywords = listOf(
            "tourist attraction", "landmark", "monument", "museum", "park", "garden",
            "castle", "palace", "temple", "church", "cathedral", "mosque", "synagogue",
            "beach", "mountain", "lake", "river", "waterfall", "canyon", "valley",
            "national park", "nature reserve", "historical site", "archaeological site"
        )

        val touristCategories = listOf(
            "Tourist attractions", "Landmarks", "Museums", "Parks", "Gardens",
            "Castles", "Palaces", "Religious buildings", "Beaches", "Mountains",
            "Lakes", "Rivers", "Waterfalls", "National parks", "Nature reserves",
            "Historical sites", "Archaeological sites", "World Heritage Sites"
        )

        val titleLower = title.lowercase()
        val hasTouristKeyword = touristKeywords.any { titleLower.contains(it) }
        val hasTouristCategory = categories.any { cat ->
            touristCategories.any { cat.contains(it, ignoreCase = true) }
        }

        return hasTouristKeyword || hasTouristCategory
    }

    /**
     * Assess content quality based on length and information density
     */
    private fun assessContentQuality(content: String): Float {
        if (content.isEmpty()) return 0f

        val wordCount = content.split("\\s+".toRegex()).size
        val sentenceCount = content.split("[.!?]+".toRegex()).size

        // Quality factors
        val lengthScore = when {
            wordCount < 10 -> 0.2f
            wordCount < 50 -> 0.5f
            wordCount < 100 -> 0.8f
            else -> 1.0f
        }

        val densityScore = if (sentenceCount > 0) {
            (wordCount.toFloat() / sentenceCount).coerceIn(5f, 25f) / 25f
        } else 0.5f

        return (lengthScore + densityScore) / 2f
    }

    /**
     * Calculate distance between two points using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    /**
     * Build a descriptive string for the POI
     */
    private fun buildDescription(title: String, lat: Double, lon: Double, categories: List<String>, content: String): String {
        val location = "Located at ${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
        val relevantCategories = categories.filter { cat ->
            !cat.contains("articles", ignoreCase = true) &&
            !cat.contains("wikipedia", ignoreCase = true) &&
            !cat.contains("coordinates", ignoreCase = true)
        }.take(2)

        val categoryInfo = if (relevantCategories.isNotEmpty()) {
            " (${relevantCategories.joinToString(", ")})"
        } else ""

        val excerpt = if (content.length > title.length) {
            content.take(100).removePrefix(title).trim()
        } else ""

        return "$location. $title$categoryInfo${if (excerpt.isNotEmpty()) ". $excerpt" else ""}"
    }

    /**
     * Determine the best category for the POI
     */
    private fun determineCategory(title: String, categories: List<String>): String {
        // Priority order for category determination
        val categoryMappings = mapOf(
            "Museums" to "Museum",
            "Parks" to "Park",
            "Gardens" to "Garden",
            "Castles" to "Castle",
            "Palaces" to "Palace",
            "Religious buildings" to "Religious Site",
            "Beaches" to "Beach",
            "Mountains" to "Mountain",
            "Lakes" to "Lake",
            "Rivers" to "River",
            "Waterfalls" to "Waterfall",
            "National parks" to "National Park",
            "Historical sites" to "Historical Site",
            "Archaeological sites" to "Archaeological Site",
            "World Heritage Sites" to "World Heritage Site"
        )

        for ((categoryPattern, poiCategory) in categoryMappings) {
            if (categories.any { it.contains(categoryPattern, ignoreCase = true) }) {
                return poiCategory
            }
        }

        // Fallback based on title keywords
        val titleLower = title.lowercase()
        return when {
            titleLower.contains("museum") -> "Museum"
            titleLower.contains("park") -> "Park"
            titleLower.contains("garden") -> "Garden"
            titleLower.contains("castle") -> "Castle"
            titleLower.contains("palace") -> "Palace"
            titleLower.contains("temple") || titleLower.contains("church") || titleLower.contains("mosque") -> "Religious Site"
            titleLower.contains("beach") -> "Beach"
            titleLower.contains("mountain") -> "Mountain"
            titleLower.contains("lake") -> "Lake"
            titleLower.contains("river") -> "River"
            titleLower.contains("waterfall") -> "Waterfall"
            else -> "Tourist Attraction"
        }
    }

    /**
     * Enhanced content analysis for scenic quality with category consideration
     */
    private fun analyzeContentForScenicScore(content: String, categories: List<String> = emptyList()): Float {
        val text = content.lowercase()
        val scenicKeywords = listOf(
            "scenic", "beautiful", "view", "amazing", "stunning", "breathtaking",
            "panorama", "landscape", "mountain", "ocean", "sea", "beach",
            "sunset", "sunrise", "nature", "park", "garden", "hill", "valley",
            "picturesque", "spectacular", "majestic", "serene", "peaceful",
            "charming", "idyllic", "pristine", "lush", "vibrant", "breathtaking",
            "spectacular", "magnificent", "gorgeous", "splendid", "superb"
        )

        val negativeKeywords = listOf(
            "crowded", "busy", "commercial", "industrial", "construction",
            "under construction", "closed", "dangerous", "unsafe", "polluted",
            "damaged", "ruined", "abandoned", "derelict"
        )

        var score = 0f
        var keywordMatches = 0
        var negativeMatches = 0

        scenicKeywords.forEach { keyword ->
            val count = text.split(keyword).size - 1
            keywordMatches += count
        }

        negativeKeywords.forEach { keyword ->
            if (text.contains(keyword)) negativeMatches++
        }

        // Base score from keywords (0-70 points)
        score += (keywordMatches.toFloat() / scenicKeywords.size) * 70f

        // Category bonus (0-20 points)
        val natureCategories = listOf("parks", "mountains", "lakes", "rivers", "beaches", "nature")
        val hasNatureCategory = categories.any { cat ->
            natureCategories.any { cat.contains(it, ignoreCase = true) }
        }
        if (hasNatureCategory) score += 15f

        // Length bonus for detailed content (0-10 points)
        if (text.length > 200) score += 5f
        if (text.length > 500) score += 5f

        // Negative keyword penalty
        score -= negativeMatches * 10f

        // Bonus for multiple keyword mentions
        if (keywordMatches >= 2) score += 5f
        if (keywordMatches >= 4) score += 5f
        if (keywordMatches >= 6) score += 5f

        return score.coerceIn(0f, 100f)
    }
}
