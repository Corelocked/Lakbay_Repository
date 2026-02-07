package com.example.scenic_navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.example.scenic_navigation.R
import com.example.scenic_navigation.databinding.FragmentRecommendationsBinding
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.services.LocationService
import com.example.scenic_navigation.viewmodel.RecommendationsViewModel
import com.example.scenic_navigation.viewmodel.SharedRouteViewModel
import kotlinx.coroutines.launch
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

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
        // Personalization is now handled in Settings; no in-UI switch here.

        getUserLocation()

        setupRecyclerView()
        setupTownsList()
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

        // Swipe to refresh triggers re-fetch
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetchRecommendations()
        }

        // Populate compact model metrics text from model_metadata.json in assets
        try {
            val metaStream = requireContext().assets.open("models/model_metadata.json")
            val content = metaStream.bufferedReader().use { it.readText() }
            val j = org.json.JSONObject(content)
            val auc = j.optJSONObject("metrics")?.optDouble("auc", Double.NaN)
            val n = j.optInt("n_samples", -1)
            if (auc != null && !auc.isNaN()) {
                binding.root.findViewById<android.widget.TextView>(R.id.tv_model_metrics)?.text = "Model AUC: ${String.format("%.3f", auc)} • samples: ${if (n > 0) n else "n/a"}"
            }
        } catch (_: Exception) {
            // ignore — metrics view will stay empty
        }
    }

    private fun setupRecyclerView() {
        adapter = RecommendationsAdapter()
        // Use view binding to access recycler view
        binding.rvRecommendations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecommendations.adapter = adapter
    }

    private fun setupTownsList() {
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
            binding.tvDistanceValue.text = getString(R.string.distance_value_fmt, value.roundToInt())
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
                        // Update adapter with new location and refresh the currently shown list
                        adapter.updateUserLocation(userLat, userLon)
                        adapter.submitList(filteredPois.ifEmpty { allPois })
                    }
                } catch (_: Exception) {
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
            binding.emptyStateMessage.text = getString(R.string.no_scenic_destinations)
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
                getString(R.string.no_pois_match_filters)
            } else if (selectedTown != null) {
                getString(R.string.no_pois_found_in_town, selectedTown)
            } else {
                getString(R.string.no_recommendations_available)
            }
             binding.emptyStateMessage.text = emptyMessage
        } else {
            binding.emptyState.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            adapter.updateUserLocation(userLat, userLon)
            adapter.submitList(recommendations)
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
                // stop the swipe refresh when loading is done
                if (!loading) binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun getModelAuc(): String {
        // read metadata file and return auc if present
        return try {
            val metaStream = requireContext().assets.open("models/model_metadata.json")
            val content = metaStream.bufferedReader().use { it.readText() }
            val j = org.json.JSONObject(content)
            val metrics = j.optJSONObject("metrics")
            val auc = metrics?.optDouble("auc", Double.NaN)
            if (auc != null && !auc.isNaN()) "AUC: ${String.format("%.3f", auc)}" else "AUC: n/a"
        } catch (e: Exception) {
            "AUC: n/a"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class RecommendationsAdapter : ListAdapter<Poi, RecommendationsAdapter.ViewHolder>(DIFF) {
    var userLat: Double = 14.5995
    var userLon: Double = 120.9842
    private val PAYLOAD_USER_LOCATION = "payload_user_location"

    class ViewHolder(val binding: com.example.scenic_navigation.databinding.PoiItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.example.scenic_navigation.databinding.PoiItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    // Full bind used when payloads are empty
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        bindFull(holder, item)
    }

    // Partial bind for payload updates (e.g., only user location changed)
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = getItem(position)
        // If payload indicates user location update, update only distance text/views
        if (payloads.any { it == PAYLOAD_USER_LOCATION }) {
            with(holder.binding) {
                if (item.lat != null && item.lon != null) {
                    val distanceMeters = haversine(userLat, userLon, item.lat!!, item.lon!!)
                    val distanceKm = distanceMeters / 1000.0
                    tvDistance.text = when {
                        distanceKm < 1.0 -> String.format(Locale.ROOT, "%d m", distanceMeters.toInt())
                        distanceKm < 10.0 -> String.format(Locale.ROOT, "%.1f km", distanceKm)
                        else -> String.format(Locale.ROOT, "%.0f km", distanceKm)
                    }
                    tvDistance.visibility = View.VISIBLE
                    categoryBadge.visibility = View.VISIBLE
                } else {
                    tvDistance.visibility = View.GONE
                    categoryBadge.visibility = View.GONE
                }
            }
        } else {
            // Fallback to full bind
            bindFull(holder, item)
        }
    }

    private fun bindFull(holder: ViewHolder, item: Poi) {
        with(holder.binding) {
            tvName.text = item.name
            tvCategory.text = item.category?.uppercase() ?: "POI"
            tvDescription.text = item.description

            if (item.lat != null && item.lon != null) {
                val distanceMeters = haversine(userLat, userLon, item.lat!!, item.lon!!)
                val distanceKm = distanceMeters / 1000.0
                tvDistance.text = when {
                    distanceKm < 1.0 -> String.format(Locale.ROOT, "%d m", distanceMeters.toInt())
                    distanceKm < 10.0 -> String.format(Locale.ROOT, "%.1f km", distanceKm)
                    else -> String.format(Locale.ROOT, "%.0f km", distanceKm)
                }
                tvDistance.visibility = View.VISIBLE
                categoryBadge.visibility = View.VISIBLE
            } else {
                tvDistance.visibility = View.GONE
                categoryBadge.visibility = View.GONE
            }
        }
    }

    fun updateUserLocation(lat: Double, lon: Double) {
        userLat = lat
        userLon = lon
        // Only notify that user location changed so onBindViewHolder can update distances only
        val count = currentList.size
        if (count > 0) notifyItemRangeChanged(0, count, PAYLOAD_USER_LOCATION)
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

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Poi>() {
            override fun areItemsTheSame(oldItem: Poi, newItem: Poi): Boolean {
                // Best-effort: use name + municipality as stable identifier
                return oldItem.name == newItem.name && oldItem.municipality == newItem.municipality
            }

            override fun areContentsTheSame(oldItem: Poi, newItem: Poi): Boolean {
                return oldItem == newItem
            }
        }
    }
}
