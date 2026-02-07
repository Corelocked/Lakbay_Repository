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
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt
import android.content.Context
import com.example.scenic_navigation.utils.GeoUtils

class RecommendationsFragment : Fragment() {
    private var _binding: FragmentRecommendationsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationsViewModel by viewModels()
    private val sharedViewModel: SharedRouteViewModel by activityViewModels()
    private lateinit var adapter: RecommendationsAdapter
    private lateinit var locationService: LocationService

    // We no longer show the interim towns list — always display POIs directly
    private var showingTowns = false
    private var forceShowPois = false
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
    private var filterCollapsed = false

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
        setupFilterCollapse()
        observeViewModel()

        // Back press handling for navigation between towns and POIs
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // With towns view removed, just perform normal back navigation
                isEnabled = false
                requireActivity().onBackPressed()
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
        binding.swipeRefresh?.setOnRefreshListener {
            viewModel.fetchRecommendations()
        }

        // FAB and empty-state button trigger curation flow (open a lightweight curator — here we just show a toast)
        binding.fabCurate.setOnClickListener {
            // Provide quick feedback and trigger a fresh recommendation fetch.
            Log.i("RecommendationsFrag", "FAB clicked — preparing UI and triggering fetchRecommendations()")
            // Set UI to expect POIs first to avoid a race where the ViewModel emits before we flip the flag.
            showingTowns = false
            forceShowPois = true
            // Hide towns list and empty state so the POI list (or loading) becomes visible immediately.
            try { townsListView.visibility = View.GONE } catch (_: Exception) {}
            binding.emptyState.visibility = View.GONE
            binding.rvRecommendations.visibility = View.VISIBLE
            // Clear shared recommendations so local recommendations take precedence
            sharedViewModel.updateRecommendations(emptyList())
            Toast.makeText(requireContext(), getString(R.string.curate_now), Toast.LENGTH_SHORT).show()
            // Trigger the ViewModel to (re)fetch recommendations — this updates the observed LiveData
            // and will update the UI when results arrive.
            viewModel.fetchRecommendations()
        }
        binding.btnCurateEmpty?.setOnClickListener {
            binding.fabCurate.performClick()
        }

        // Populate compact model metrics text from model_metadata.json in assets
        try {
            val metaStream = requireContext().assets.open("models/model_metadata.json")
            val content = metaStream.bufferedReader().use { it.readText() }
            val j = org.json.JSONObject(content)
            val auc = j.optJSONObject("metrics")?.optDouble("auc", Double.NaN)
            val n = j.optInt("n_samples", -1)
            if (auc != null && !auc.isNaN()) {
                binding.tvModelMetrics?.text = "Model AUC: ${String.format("%.3f", auc)} • samples: ${if (n > 0) n else "n/a"}"
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
          // When placed inside a NestedScrollView, disable nested scrolling so RecyclerView measures correctly
          binding.rvRecommendations.isNestedScrollingEnabled = false

          // Handle like clicks from adapter
         adapter.onLikeClick = { poi ->
            try {
                val prefs = requireContext().getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
                val set = prefs.getStringSet("curated_pois", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                val added = set.add(poi.name)
                if (!added) {
                    // if already present, remove (toggle behavior)
                    set.remove(poi.name)
                }
                // commit synchronously so subsequent reads immediately reflect change
                prefs.edit().putStringSet("curated_pois", set).commit()
                // immediate UI feedback
                val pos = adapter.currentList.indexOfFirst { it.name == poi.name && it.municipality == poi.municipality }
                if (pos >= 0) adapter.notifyItemChanged(pos)
                Toast.makeText(requireContext(), getString(if (added) R.string.poi_liked else R.string.poi_unliked, poi.name), Toast.LENGTH_SHORT).show()
                // let ViewModel process curation side-effects (increment category & rerank)
                if (added) {
                    viewModel.likePoi(poi)
                } else {
                    viewModel.removeCuratedPoiName(poi.name)
                }
                viewModel.fetchRecommendations()
            } catch (e: Exception) {
                Log.w("RecommendationsFrag", "Failed to update curated set: ${e.message}")
            }
         }

         // Smoothly fade header when the user scrolls to give more space for list items
         binding.rvRecommendations.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            // small threshold to avoid toggling on minor movements
            private val THRESHOLD = 6
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val header = binding.headerCard
                try {
                    if (dy > THRESHOLD) {
                        // scrolling down — hide header smoothly
                        if (header.alpha > 0.05f) header.animate().alpha(0f).setDuration(180).start()
                    } else if (dy < -THRESHOLD) {
                        // scrolling up — show header smoothly
                        if (header.alpha < 0.95f) header.animate().alpha(1f).setDuration(180).start()
                    }
                } catch (_: Exception) {
                    // ignore animation failures
                }
            }
         })
     }

    private fun setupTownsList() {
        // Keep the view in the layout for legacy reasons but hide it — we don't show towns anymore.
        val lv = binding.lvTowns
        lv.visibility = View.GONE
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

         // New category chips
         binding.chipCulture.setOnCheckedChangeListener { _, isChecked ->
             handleCategoryChip("culture", isChecked)
         }

         binding.chipShopping.setOnCheckedChangeListener { _, isChecked ->
             handleCategoryChip("shopping", isChecked)
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

    private fun setupFilterCollapse() {
        // Wire the header collapse button to toggle the filter content using view binding.
        try {
            val btn = binding.btnFilterCollapse
            val content = binding.filterCollapsibleContent
            // initialize state
            content.visibility = if (filterCollapsed) View.GONE else View.VISIBLE
            content.alpha = if (filterCollapsed) 0f else 1f

            btn.setOnClickListener {
                filterCollapsed = !filterCollapsed
                if (filterCollapsed) {
                    // hide with fade
                    content.animate().alpha(0f).setDuration(180).withEndAction { content.visibility = View.GONE }.start()
                    btn.text = "▲"
                } else {
                    content.visibility = View.VISIBLE
                    content.alpha = 0f
                    content.animate().alpha(1f).setDuration(180).start()
                    btn.text = "▼"
                }
            }
        } catch (_: Exception) {
            // binding may not be available in rare cases; ignore safely
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
        binding.chipCulture.isChecked = false
        binding.chipShopping.isChecked = false
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

        // Always show POIs directly (towns list removed)
        updateRecommendationsList(filteredPois)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return GeoUtils.haversine(lat1, lon1, lat2, lon2)
    }

    private fun showTownsList(pois: List<Poi>) {
        // Towns list UI removed — directly show POIs instead
        updateRecommendationsList(pois)
    }

    private fun updateRecommendationsList(recommendations: List<Poi>) {
         val recyclerView = binding.rvRecommendations
         Log.i("RecommendationsFrag", "updateRecommendationsList called: recommendations.size=${recommendations.size}")
         if (recommendations.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            // also hide towns list when showing empty POI results
            townsListView.visibility = View.GONE
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
             // hide towns list when showing POIs
             townsListView.visibility = View.GONE
             recyclerView.visibility = View.VISIBLE
             adapter.updateUserLocation(userLat, userLon)
             adapter.submitList(recommendations)
             // After layout, ensure list scrolls to top so new results are visible
             recyclerView.post {
                 try { if (adapter.itemCount > 0) recyclerView.scrollToPosition(0) } catch (_: Exception) {}
             }
         }
     }

    private fun observeViewModel() {
        // Observe shared ViewModel for route recommendations
        sharedViewModel.recommendations.observe(viewLifecycleOwner) { recommendations ->
            if (recommendations.isNotEmpty()) {
                allPois = recommendations
                // Towns list UI removed — directly show POIs instead
                updateRecommendationsList(recommendations)
            }
        }

        // Fallback to local ViewModel for general recommendations
        viewModel.recommendations.observe(viewLifecycleOwner) { recommendations ->
            Log.i("RecommendationsFrag", "viewModel.recommendations observed: count=${recommendations.size}, showingTowns=$showingTowns, sharedHas=${sharedViewModel.hasRecommendations()}, forceShowPois=$forceShowPois")
            if (recommendations.isNotEmpty()) {
                allPois = recommendations
                // If the user explicitly requested POIs via FAB, force showing POIs and clear the flag.
                if (forceShowPois) {
                    forceShowPois = false
                    Log.i("RecommendationsFrag", "Force-displaying POIs due to user request")
                    updateRecommendationsList(recommendations)
                    return@observe
                }
                // Otherwise follow normal behavior driven by showingTowns
                if (!showingTowns) {
                    Log.i("RecommendationsFrag", "Displaying POIs directly (user expected)")
                    updateRecommendationsList(recommendations)
                } else {
                    Log.i("RecommendationsFrag", "Displaying towns list (default behavior)")
                    showTownsList(recommendations)
                }
            } else {
                Log.i("RecommendationsFrag", "No recommendations in local ViewModel")
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
                if (!loading) binding.swipeRefresh?.isRefreshing = false
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
    var onLikeClick: ((Poi) -> Unit)? = null
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
                    val distanceMeters = GeoUtils.haversine(userLat, userLon, item.lat!!, item.lon!!)
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
             // Prefix description with scenic score when present
             val desc = buildString {
                 val score = item.scenicScore ?: 0f
                 if (score > 0f) {
                     append("Scenic score: ${score.toInt()} • ")
                 }
                 append(item.description)
             }
             tvDescription.text = desc

            // Set like button based on persisted curated POIs stored in shared prefs
            try {
                val prefs = holder.binding.root.context.getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
                val curated = prefs.getStringSet("curated_pois", emptySet()) ?: emptySet()
                if (curated.contains(item.name)) {
                    btnLike.setImageResource(R.drawable.ic_favorite_24)
                } else {
                    btnLike.setImageResource(R.drawable.ic_favorite_border_24)
                }
            } catch (_: Exception) {}

            if (item.lat != null && item.lon != null) {
                val distanceMeters = GeoUtils.haversine(userLat, userLon, item.lat!!, item.lon!!)
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

            // Like button handling — the fragment will set onLikeClick and maintain curated state
            btnLike.setOnClickListener {
                onLikeClick?.invoke(item)
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

    // use GeoUtils.haversine for distance calculations
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
