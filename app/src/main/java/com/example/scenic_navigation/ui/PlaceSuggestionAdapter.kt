package com.example.scenic_navigation.ui

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import com.example.scenic_navigation.R
import com.example.scenic_navigation.models.GeocodeResult
import com.example.scenic_navigation.services.GeocodingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for AutoCompleteTextView that provides place suggestions with typo correction
 */
class PlaceSuggestionAdapter(
    context: Context,
    private val packageName: String
) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line), Filterable {

    private val geocodingService = GeocodingService()
    private var suggestions = mutableListOf<GeocodeResult>()
    private val adapterScope = CoroutineScope(Dispatchers.Main)
    private var searchJob: Job? = null

    // Philippine cities and landmarks for offline fallback and typo correction
    private val philippinePlaces = listOf(
        // Major Cities
        "Manila", "Quezon City", "Makati", "Pasig", "Taguig", "Mandaluyong",
        "Pasay", "Parañaque", "Las Piñas", "Muntinlupa", "Caloocan", "Malabon",
        "Navotas", "Valenzuela", "Marikina", "San Juan",
        "Cebu City", "Davao City", "Baguio", "Iloilo City", "Bacolod",
        "Cagayan de Oro", "General Santos", "Zamboanga City", "Tacloban",
        "Naga City", "Legazpi", "Butuan", "Iligan", "Cotabato City",
        "Puerto Princesa", "Tagaytay", "Angeles City", "Olongapo",

        // Provinces
        "Batangas", "Cavite", "Laguna", "Rizal", "Bulacan", "Pampanga",
        "Tarlac", "Nueva Ecija", "Zambales", "Pangasinan", "La Union",
        "Ilocos Norte", "Ilocos Sur", "Benguet", "Albay", "Camarines Sur",
        "Sorsogon", "Palawan", "Bohol", "Negros Occidental", "Negros Oriental",

        // Tourist Destinations
        "Boracay", "El Nido", "Coron", "Siargao", "Bohol", "Sagada",
        "Vigan", "Intramuros", "Banaue", "Batanes", "Camiguin",
        "Hundred Islands", "Chocolate Hills", "Mayon Volcano"
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_dropdown_item_1line, parent, false)

        val textView = view.findViewById<TextView>(android.R.id.text1)

        if (position < suggestions.size) {
            val result = suggestions[position]
            textView.text = result.displayName
            textView.textSize = 14f
        }

        return view
    }

    override fun getCount(): Int = suggestions.size

    override fun getItem(position: Int): String? {
        return if (position < suggestions.size) {
            suggestions[position].displayName
        } else null
    }

    fun getGeocodeResult(position: Int): GeocodeResult? {
        return if (position < suggestions.size) suggestions[position] else null
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()

                if (constraint.isNullOrBlank() || constraint.length < 2) {
                    results.values = emptyList<GeocodeResult>()
                    results.count = 0
                    return results
                }

                val query = constraint.toString().trim()

                // First, try to find matching Philippine places for quick suggestions
                val localMatches = findLocalMatches(query)

                // Return local matches immediately while we fetch from API
                results.values = localMatches
                results.count = localMatches.size

                return results
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                suggestions.clear()

                if (results != null && results.count > 0) {
                    @Suppress("UNCHECKED_CAST")
                    val resultList = results.values as? List<GeocodeResult> ?: emptyList()
                    suggestions.addAll(resultList)
                    notifyDataSetChanged()
                }

                // Fetch from API with debouncing
                if (!constraint.isNullOrBlank() && constraint.length >= 2) {
                    searchJob?.cancel()
                    searchJob = adapterScope.launch {
                        delay(400) // Debounce delay
                        fetchSuggestionsFromAPI(constraint.toString().trim())
                    }
                }
            }
        }
    }

    /**
     * Find local matches from Philippine places list with fuzzy matching for typo correction
     */
    private fun findLocalMatches(query: String): List<GeocodeResult> {
        val queryLower = query.lowercase()
        val matches = mutableListOf<Pair<String, Int>>()

        for (place in philippinePlaces) {
            val placeLower = place.lowercase()

            // Exact match (highest priority)
            if (placeLower == queryLower) {
                matches.add(Pair(place, 100))
                continue
            }

            // Starts with (high priority)
            if (placeLower.startsWith(queryLower)) {
                matches.add(Pair(place, 90))
                continue
            }

            // Contains (medium priority)
            if (placeLower.contains(queryLower)) {
                matches.add(Pair(place, 70))
                continue
            }

            // Fuzzy match with Levenshtein distance (for typo correction)
            val distance = levenshteinDistance(queryLower, placeLower)
            val maxLength = maxOf(queryLower.length, placeLower.length)
            val similarity = ((maxLength - distance).toFloat() / maxLength * 100).toInt()

            // Accept if similarity > 60% (good for typo correction)
            if (similarity > 60) {
                matches.add(Pair(place, similarity))
            }
        }

        // Sort by score (highest first) and take top 5
        return matches
            .sortedByDescending { it.second }
            .take(5)
            .map { GeocodeResult(it.first, 0.0, 0.0) }
    }

    /**
     * Calculate Levenshtein distance for typo correction
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * Fetch suggestions from Nominatim API
     */
    private suspend fun fetchSuggestionsFromAPI(query: String) {
        try {
            val results = withContext(Dispatchers.IO) {
                geocodingService.geocodeAddress(query, packageName) { error ->
                    Log.e("PlaceSuggestionAdapter", "Geocoding error: $error")
                }
            }

            if (results.isNotEmpty()) {
                suggestions.clear()
                suggestions.addAll(results)
                notifyDataSetChanged()
            }
        } catch (e: Exception) {
            Log.e("PlaceSuggestionAdapter", "Error fetching suggestions: ${e.message}")
        }
    }

    fun cleanup() {
        searchJob?.cancel()
        adapterScope.cancel()
    }
}

