package com.example.scenic_navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.example.scenic_navigation.R
import com.example.scenic_navigation.databinding.FragmentRecommendationsBinding
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.services.LocationService
import com.example.scenic_navigation.viewmodel.RecommendationsViewModel
import com.example.scenic_navigation.viewmodel.SharedRouteViewModel
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

// RecommendationsFragment is deprecated and no longer used
@Deprecated("Discover page is no longer used", ReplaceWith(""))
class RecommendationsFragment : Fragment() {
    private var _binding: FragmentRecommendationsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationsViewModel by viewModels()
    private val sharedViewModel: SharedRouteViewModel by activityViewModels()
    private lateinit var adapter: RecommendationsAdapter
    private lateinit var locationService: LocationService

    private var showingTowns = true
    private var selectedTown: String? = null
    private var allPois: List<Poi> = emptyList()
    private var filteredPois: List<Poi> = emptyList()
    private lateinit var townsAdapter: ArrayAdapter<String>
    private lateinit var townsListView: ListView

    // Filter state
    private var userLat: Double = 14.5995 // Default Manila
    private var userLon: Double = 120.9842
    private var maxDistance: Float = 50f // km
    private var selectedCategories = mutableSetOf<String>()
    private var sortBy: SortOption = SortOption.DISTANCE

    enum class SortOption {
        DISTANCE, SCENIC_SCORE, NAME
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecommendationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        locationService = LocationService(requireContext())
        getUserLocation()

        setupRecyclerView()
        setupTownsList(view)
        setupFilters()
        observeViewModel()

        // Back press handling for navigation between towns and POIs
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!showingTowns) {
                    showTownsList(allPois)
                } else {
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
            }
        })

        // Check if we have route recommendations from shared ViewModel first
        if (sharedViewModel.hasRecommendations()) {
            // Use recommendations from the route
            val recommendations = sharedViewModel.recommendations.value ?: emptyList()
            updateRecommendationsList(recommendations)
        } else {
            // Fetch general recommendations if no route exists
            viewModel.fetchRecommendations()
        }
    }

    private fun setupRecyclerView() {
        adapter = RecommendationsAdapter(emptyList(), userLat, userLon)
        // Use view binding to access recycler view
        binding.rvRecommendations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecommendations.adapter = adapter
    }

    private fun setupTownsList(view: View) {
        // Use the ListView defined in layout and initialize it
        val lv = binding.lvTowns
        lv.visibility = View.VISIBLE
        lv.setOnItemClickListener { _, _, position, _ ->
            val town = townsAdapter.getItem(position)
            selectedTown = town
            showPoisForTown(town)
        }
        townsListView = lv
    }

    private fun setupFilters() {
        // Distance slider
        binding.sliderDistance.addOnChangeListener { _, value, _ ->
            maxDistance = value
            binding.tvDistanceValue.text = "${value.roundToInt()} km"
            applyFilters()
        }

        // Category chips
        binding.chipAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedCategories.clear()
                uncheckOtherCategoryChips()
            }
            applyFilters()
        }

        binding.chipScenic.setOnCheckedChangeListener { _, isChecked ->
            handleCategoryChip("scenic", isChecked)
        }

        binding.chipCoastal.setOnCheckedChangeListener { _, isChecked ->
            handleCategoryChip("coastal", isChecked)
        }

        binding.chipMountain.setOnCheckedChangeListener { _, isChecked ->
            handleCategoryChip("mountain", isChecked)
        }

        binding.chipHistoric.setOnCheckedChangeListener { _, isChecked ->
            handleCategoryChip("historic", isChecked)
        }

        binding.chipFood.setOnCheckedChangeListener { _, isChecked ->
            handleCategoryChip("food", isChecked)
        }

        binding.chipNature.setOnCheckedChangeListener { _, isChecked ->
            handleCategoryChip("nature", isChecked)
        }

        // Sort chips
        binding.chipSortDistance.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sortBy = SortOption.DISTANCE
                applyFilters()
            }
        }

        binding.chipSortScenic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sortBy = SortOption.SCENIC_SCORE
                applyFilters()
            }
        }

        binding.chipSortName.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sortBy = SortOption.NAME
                applyFilters()
            }
        }
    }

    private fun handleCategoryChip(category: String, isChecked: Boolean) {
        if (isChecked) {
            selectedCategories.add(category)
            binding.chipAll.isChecked = false
        } else {
            selectedCategories.remove(category)
            if (selectedCategories.isEmpty()) {
                binding.chipAll.isChecked = true
            }
        }
        applyFilters()
    }

    private fun uncheckOtherCategoryChips() {
        binding.chipScenic.isChecked = false
        binding.chipCoastal.isChecked = false
        binding.chipMountain.isChecked = false
        binding.chipHistoric.isChecked = false
        binding.chipFood.isChecked = false
        binding.chipNature.isChecked = false
    }

    private fun getUserLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val location = locationService.getCurrentLocation()
                    location?.let {
                        userLat = it.latitude
                        userLon = it.longitude
                        // Update adapter with new location
                        adapter.updateData(filteredPois.ifEmpty { allPois }, userLat, userLon)
                    }
                } catch (e: Exception) {
                    // Use default location
                }
            }
        }
    }

    private fun applyFilters() {
        if (allPois.isEmpty()) return

        // Filter by distance
        var filtered = allPois.filter { poi ->
            poi.lat != null && poi.lon != null &&
            calculateDistance(userLat, userLon, poi.lat!!, poi.lon!!) <= maxDistance * 1000
        }

        // Filter by category
        if (selectedCategories.isNotEmpty()) {
            filtered = filtered.filter { poi ->
                selectedCategories.any { category ->
                    poi.category?.lowercase()?.contains(category) == true ||
                    poi.name.lowercase().contains(category)
                }
            }
        }

        // Sort
        filtered = when (sortBy) {
            SortOption.DISTANCE -> filtered.sortedBy { poi ->
                calculateDistance(userLat, userLon, poi.lat ?: userLat, poi.lon ?: userLon)
            }
            SortOption.SCENIC_SCORE -> filtered.sortedByDescending { it.scenicScore }
            SortOption.NAME -> filtered.sortedBy { it.name }
        }

        filteredPois = filtered

        if (!showingTowns) {
            updateRecommendationsList(filteredPois)
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return haversine(lat1, lon1, lat2, lon2)
    }

    private fun showTownsList(pois: List<Poi>) {
        showingTowns = true
        val towns = pois.map { it.municipality }.filter { it.isNotBlank() }.distinct().sorted()
        if (towns.isEmpty()) {
            townsListView.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.emptyStateMessage.text = "No scenic destinations found. Try planning a route or check your connection."
            view?.findViewById<RecyclerView>(R.id.rv_recommendations)?.visibility = View.GONE
            return
        }
        townsListView.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        view?.findViewById<RecyclerView>(R.id.rv_recommendations)?.visibility = View.GONE
        townsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, towns)
        townsListView.adapter = townsAdapter
        // If only one town, show POIs directly
        if (towns.size == 1) {
            showPoisForTown(towns.first())
        }
    }

    private fun showPoisForTown(town: String?) {
        showingTowns = false
        townsListView.visibility = View.GONE
        selectedTown = town

        // Filter POIs for this town and apply other filters
        allPois = allPois.filter { it.municipality == town }
        applyFilters()
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    private fun updateRecommendationsList(recommendations: List<Poi>) {
        val recyclerView = view?.findViewById<RecyclerView>(R.id.rv_recommendations)
        if (recommendations.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            recyclerView?.visibility = View.GONE
            val emptyMessage = if (selectedCategories.isNotEmpty() || maxDistance < 50f) {
                "No POIs match your filters.\nTry adjusting the distance or category filters."
            } else if (selectedTown != null) {
                "No POIs found in $selectedTown."
            } else {
                "No recommendations available.\nPlan a route first to see recommendations along the way!"
            }
            binding.emptyStateMessage.text = emptyMessage
        } else {
            binding.emptyState.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            adapter.updateData(recommendations, userLat, userLon)
        }
    }

    private fun observeViewModel() {
        // Observe shared ViewModel for route recommendations
        sharedViewModel.recommendations.observe(viewLifecycleOwner) { recommendations ->
            if (recommendations.isNotEmpty()) {
                allPois = recommendations
                showTownsList(recommendations)
            }
        }

        // Fallback to local ViewModel for general recommendations
        viewModel.recommendations.observe(viewLifecycleOwner) { recommendations ->
            if (!sharedViewModel.hasRecommendations() && recommendations.isNotEmpty()) {
                allPois = recommendations
                showTownsList(recommendations)
            }
        }

        // Observe loading state from shared ViewModel
        sharedViewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }

        // Fallback to local ViewModel for general recommendations
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (!sharedViewModel.hasRecommendations()) {
                binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class RecommendationsAdapter(
    private var items: List<Poi>,
    private var userLat: Double = 14.5995,
    private var userLon: Double = 120.9842
) : RecyclerView.Adapter<RecommendationsAdapter.ViewHolder>() {

    class ViewHolder(val binding: com.example.scenic_navigation.databinding.PoiItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.example.scenic_navigation.databinding.PoiItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvName.text = item.name
            tvCategory.text = item.category?.uppercase() ?: "POI"
            tvDescription.text = item.description

            // Calculate and show distance
            if (item.lat != null && item.lon != null) {
                val distanceMeters = haversine(userLat, userLon, item.lat!!, item.lon!!)
                val distanceKm = distanceMeters / 1000.0
                tvDistance.text = when {
                    distanceKm < 1.0 -> "${(distanceMeters).toInt()} m"
                    distanceKm < 10.0 -> String.format("%.1f km", distanceKm)
                    else -> String.format("%.0f km", distanceKm)
                }
                tvDistance.visibility = View.VISIBLE
                categoryBadge.visibility = View.VISIBLE
            } else {
                tvDistance.visibility = View.GONE
                categoryBadge.visibility = View.GONE
            }
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<Poi>, lat: Double = userLat, lon: Double = userLon) {
        items = newItems
        userLat = lat
        userLon = lon
        notifyDataSetChanged()
    }
}
