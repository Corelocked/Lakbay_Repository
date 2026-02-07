package com.example.scenic_navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View

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
<<<<<<< Updated upstream
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt
=======
import com.example.scenic_navigation.viewmodel.RouteViewModel
import kotlinx.coroutines.launch
import android.widget.Toast
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt
import android.content.Context
import android.graphics.Color
import com.example.scenic_navigation.utils.GeoUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.scenic_navigation.MainActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import android.widget.LinearLayout
import android.view.ViewGroup
import android.widget.CompoundButton
>>>>>>> Stashed changes

// RecommendationsFragment is deprecated and no longer used
@Deprecated("Discover page is no longer used", ReplaceWith(""))
class RecommendationsFragment : Fragment() {
    private var _binding: FragmentRecommendationsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationsViewModel by viewModels()
    private val sharedViewModel: SharedRouteViewModel by activityViewModels()
    private val routeViewModel: RouteViewModel by activityViewModels()
    private lateinit var adapter: RecommendationsAdapter
    private lateinit var locationService: LocationService

    private var showingTowns = true
    private var selectedTown: String? = null
    private var allPois: List<Poi> = emptyList()
    private var filteredPois: List<Poi> = emptyList()
    private lateinit var townsListView: ListView

    // Filter state
    private var userLat: Double = 14.5995 // Default Manila
    private var userLon: Double = 120.9842
    private var maxDistance: Float = 50f // km
    private var selectedCategories = mutableSetOf<String>()
    private var currentSeeingSelection: String = "Oceanic View"
    // Multi-select activity labels (the UI chips)
    private val selectedActivityLabels = mutableSetOf<String>()
    private var sortBy: SortOption = SortOption.DISTANCE

    // Suppress programmatic chip events to avoid recursive listener triggers and UI flicker
    private var suppressFilterEvents = false

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
<<<<<<< Updated upstream
                if (!showingTowns) {
                    showTownsList(allPois)
                } else {
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
=======
                // With towns view removed, just perform normal back navigation
                isEnabled = false
                // Dispatch a back press via the OnBackPressedDispatcher instead of the deprecated API
                requireActivity().onBackPressedDispatcher.onBackPressed()
>>>>>>> Stashed changes
            }
        })

        // Check if we have route recommendations from shared ViewModel first
        if (sharedViewModel.hasRecommendations()) {
            // Use recommendations from the route
            val recommendations = sharedViewModel.recommendations.value ?: emptyList()
            updateRecommendationsList(recommendations)
        } else {
            // Fetch general recommendations if no route exists
            viewModel.fetchRecommendations(userLat, userLon, maxDistance.toDouble(), selectedCategories)
        }
<<<<<<< Updated upstream
    }

    private fun setupRecyclerView() {
        adapter = RecommendationsAdapter(emptyList(), userLat, userLon)
        // Use view binding to access recycler view
        binding.rvRecommendations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecommendations.adapter = adapter
    }

    private fun setupTownsList(view: View) {
        // Use the ListView defined in layout and initialize it
=======

        // Swipe to refresh triggers re-fetch
        binding.swipeRefresh?.setOnRefreshListener {
            viewModel.fetchRecommendations(userLat, userLon, maxDistance.toDouble(), selectedCategories)
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
            viewModel.fetchRecommendations(userLat, userLon, maxDistance.toDouble(), selectedCategories)
        }
        binding.btnCurateEmpty?.setOnClickListener {
            binding.fabCurate.performClick()
        }
    }

    private fun setupRecyclerView() {
         adapter = RecommendationsAdapter()
         // Enable stable ids on the adapter before attaching it to the RecyclerView
         adapter.setHasStableIds(true)
         // Use view binding to access recycler view
         binding.rvRecommendations.layoutManager = LinearLayoutManager(requireContext())
         // Performance: fixed size
         binding.rvRecommendations.setHasFixedSize(true)
         binding.rvRecommendations.adapter = adapter
         // Small item decoration for vertical spacing
         val spacing = (requireContext().resources.displayMetrics.density * 6).toInt()
         binding.rvRecommendations.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
             override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                 outRect.set(0, spacing, 0, spacing)
             }
         })

         // Handle like clicks from adapter
         adapter.onLikeClick = { poi ->
            try {
                // Toggle curated state via ViewModel using canonical key storage
                if (!viewModel.isCurated(poi)) {
                    viewModel.addCurated(poi)
                    Toast.makeText(requireContext(), getString(R.string.poi_liked, poi.name), Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.removeCurated(poi)
                    Toast.makeText(requireContext(), getString(R.string.poi_unliked, poi.name), Toast.LENGTH_SHORT).show()
                }
                // immediate UI refresh for the single item
                val pos = adapter.currentList.indexOfFirst { it.name == poi.name && it.municipality == poi.municipality }
                if (pos >= 0) adapter.notifyItemChanged(pos)
            } catch (e: Exception) {
                Log.w("RecommendationsFrag", "Failed to update curated set: ${e.message}")
            }
         }

         // Handle POI taps: plan a curated route to the POI using current filter selections
         adapter.onPoiClick = { poi ->
            try {
                // Map current seeing/activity to enums (fallbacks included)
                val seeing = when (currentSeeingSelection.lowercase()) {
                    "oceanic view", "oceanic" -> com.example.scenic_navigation.models.SeeingType.OCEANIC
                    "mountain view", "mountain" -> com.example.scenic_navigation.models.SeeingType.MOUNTAIN
                    else -> com.example.scenic_navigation.models.SeeingType.OCEANIC
                }
                val activity = mapSelectedActivitiesToActivityType()

                val destQuery = if (poi.lat != null && poi.lon != null) "${poi.lat},${poi.lon}" else poi.name
                val extras = com.example.scenic_navigation.models.CurationIntentExtras(subtypes = selectedCategories.toSet())
                // Update shared curation and plan route
                sharedViewModel.setCurationIntent(com.example.scenic_navigation.models.CurationIntent(destinationQuery = destQuery, seeing = seeing, activity = activity))
                sharedViewModel.setCurationExtras(extras)
                Log.i("RecommendationsFrag", "Invoking planRouteCurated for dest=$destQuery, seeing=$seeing, activity=$activity")
                routeViewModel.planRouteCurated(destQuery, seeing, activity, extras)
                Toast.makeText(requireContext(), getString(R.string.planning_your_scenic_route), Toast.LENGTH_SHORT).show()
                 // Switch to Route tab so the route UI is displayed to the user
                 try {
                     val act = requireActivity()
                     val bottom = act.findViewById<BottomNavigationView?>(R.id.bottom_navigation)
                     bottom?.selectedItemId = R.id.nav_route
                     // Also explicitly replace fragment to ensure RouteFragment is active immediately
                     try {
                         if (act is MainActivity) {
                             act.supportFragmentManager.beginTransaction().replace(R.id.fragment_container, com.example.scenic_navigation.ui.RouteFragment()).commitNowAllowingStateLoss()
                         }
                     } catch (_: Exception) {}
                 } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w("RecommendationsFrag", "Failed to plan route to POI: ${e.message}")
            }
         }
     }

    private fun setupTownsList() {
        // Keep the view in the layout for legacy reasons but hide it — we don't show towns anymore.
>>>>>>> Stashed changes
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
<<<<<<< Updated upstream
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
=======
         // Hide existing chips (we use spinners instead)
         try {
             binding.chipAll.visibility = View.GONE
             binding.chipScenic.visibility = View.GONE
             binding.chipCoastal.visibility = View.GONE
             binding.chipMountain.visibility = View.GONE
             binding.chipHistoric.visibility = View.GONE
             binding.chipFood.visibility = View.GONE
             binding.chipNature.visibility = View.GONE
             binding.chipCulture.visibility = View.GONE
             binding.chipShopping.visibility = View.GONE
             binding.chipTourism.visibility = View.GONE
         } catch (_: Exception) {}

         // Wire the exposed dropdown (AutoCompleteTextView) for Seeing selection
         try {
             val actv = binding.root.findViewById<android.widget.AutoCompleteTextView>(R.id.actv_seeing)
             val seeingOptions = listOf("Oceanic View", "Mountain View", "Generic")
             val adapterSeeing = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, seeingOptions)
             actv?.setAdapter(adapterSeeing)
             // Initialize selection to the currentSeeingSelection if present
             actv?.setText(currentSeeingSelection, false)
             actv?.setOnItemClickListener { _, _, position, _ ->
                 currentSeeingSelection = seeingOptions[position]
                 applyFilters()
             }
         } catch (_: Exception) {}

         // Activity chips (multi-select) — lets the user mix & match tags
         try {
             val activityOptions = listOf("Sight seeing", "Shop and Dine", "Cultural activities", "Historic landmarks", "Adventure & Hiking", "Relaxation & Wellness", "Family Friendly", "Romantic Getaway")
             val chipGroup = ChipGroup(requireContext())
             chipGroup.isSingleSelection = false
             chipGroup.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
             for (label in activityOptions) {
                 val chip = Chip(requireContext())
                 chip.text = label
                 chip.isCheckable = true
                 chip.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                    if (checked) selectedActivityLabels.add(label) else selectedActivityLabels.remove(label)
                     // Recompute selectedCategories from activity labels + seeing selection
                     val tokens = mutableSetOf<String>()
                     for (l in selectedActivityLabels) tokens.addAll(mapActivityLabelToTags(l))
                     // also include seeing-derived tags
                     val seeingTokens = when (currentSeeingSelection.lowercase()) {
                         "oceanic view", "oceanic" -> setOf("coastal", "beach", "bay", "ocean")
                         "mountain view", "mountain" -> setOf("mountain", "peak", "volcano", "view")
                         else -> emptySet()
                     }
                     tokens.addAll(seeingTokens)
                     selectedCategories.clear()
                     selectedCategories.addAll(tokens)
                     applyFilters()
                 }
                 chipGroup.addView(chip)
             }
             binding.filterCollapsibleContent.addView(chipGroup)
         } catch (_: Exception) {}

         // Distance slider
         binding.sliderDistance.addOnChangeListener { _, value, _ ->
             if (suppressFilterEvents) return@addOnChangeListener
             maxDistance = value
             binding.tvDistanceValue.text = getString(R.string.distance_value_fmt, value.roundToInt())
             applyFilters()
         }

         // Sort chips
         binding.chipSortDistance.setOnCheckedChangeListener { _, isChecked ->
             if (suppressFilterEvents) return@setOnCheckedChangeListener
             if (isChecked) {
                 sortBy = SortOption.DISTANCE
                 Log.i("RecommendationsFrag", "Sort changed to DISTANCE")
                 // ensure mutual exclusivity
                 suppressFilterEvents = true
                 binding.chipSortScenic.isChecked = false
                 binding.chipSortName.isChecked = false
                 suppressFilterEvents = false
                 applyFilters()
             }
         }

         binding.chipSortScenic.setOnCheckedChangeListener { _, isChecked ->
             if (suppressFilterEvents) return@setOnCheckedChangeListener
             if (isChecked) {
                 sortBy = SortOption.SCENIC_SCORE
                 Log.i("RecommendationsFrag", "Sort changed to SCENIC_SCORE")
                 suppressFilterEvents = true
                 binding.chipSortDistance.isChecked = false
                 binding.chipSortName.isChecked = false
                 suppressFilterEvents = false
                 applyFilters()
             }
         }

         binding.chipSortName.setOnCheckedChangeListener { _, isChecked ->
             if (suppressFilterEvents) return@setOnCheckedChangeListener
             if (isChecked) {
                 sortBy = SortOption.NAME
                 Log.i("RecommendationsFrag", "Sort changed to NAME")
                 suppressFilterEvents = true
                 binding.chipSortDistance.isChecked = false
                 binding.chipSortScenic.isChecked = false
                 suppressFilterEvents = false
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
                    btn.setText(R.string.expand_arrow) // ▲
                } else {
                    content.visibility = View.VISIBLE
                    content.alpha = 0f
                    content.animate().alpha(1f).setDuration(180).start()
                    btn.setText(R.string.collapse_arrow) // ▼
                }
>>>>>>> Stashed changes
            }
        }
    }

    private fun handleCategoryChip(category: String, isChecked: Boolean) {
        if (suppressFilterEvents) return
        if (isChecked) {
            selectedCategories.add(category)
            // turn off "All" without triggering its listener
            suppressFilterEvents = true
            binding.chipAll.isChecked = false
            suppressFilterEvents = false
        } else {
            selectedCategories.remove(category)
            if (selectedCategories.isEmpty()) {
                suppressFilterEvents = true
                binding.chipAll.isChecked = true
                suppressFilterEvents = false
            }
        }
        applyFilters()
    }

    private fun uncheckOtherCategoryChips() {
        // Uncheck programmatically while suppressing events
        suppressFilterEvents = true
        binding.chipScenic.isChecked = false
        binding.chipCoastal.isChecked = false
        binding.chipMountain.isChecked = false
        binding.chipHistoric.isChecked = false
        binding.chipFood.isChecked = false
        binding.chipNature.isChecked = false
<<<<<<< Updated upstream
=======
        binding.chipCulture.isChecked = false
        binding.chipShopping.isChecked = false
        binding.chipTourism.isChecked = false
        suppressFilterEvents = false
>>>>>>> Stashed changes
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
<<<<<<< Updated upstream
                        // Update adapter with new location
                        adapter.updateData(filteredPois.ifEmpty { allPois }, userLat, userLon)
=======
                        // Update adapter with new location and refresh the currently shown list
                        adapter.updateUserLocation(userLat, userLon)
                        adapter.submitList(filteredPois.ifEmpty { allPois })
                        // Fetch recommendations now that we have the user's accurate location
                        viewModel.fetchRecommendations(userLat, userLon, maxDistance.toDouble(), selectedCategories)
>>>>>>> Stashed changes
                    }
                } catch (e: Exception) {
                    // Use default location
                }
            }
        }
    }

    // Map activity labels (UI text) to dataset/planner tags using the tag lists from ScenicRoutePlanner
    private fun mapActivityLabelToTags(label: String): Set<String> {
        val t = label.lowercase()
        return when {
            t.contains("shop") || t.contains("dine") -> setOf("shop", "mall", "market", "restaurant", "food", "cafe", "bakery", "deli", "wine")
            t.contains("cultural") -> setOf("museum", "historic", "theatre", "gallery", "church", "heritage", "monument", "art", "camp")
            t.contains("historic") || t.contains("landmark") -> setOf("historic", "historical site", "monument", "heritage", "church", "castle", "archaeological")
            t.contains("adventure") || t.contains("hiking") -> setOf("peak", "waterfall", "hiking", "climbing", "adventure", "sport", "hiking trail", "trail", "cave", "hike", "view")
            t.contains("relax") || t.contains("wellness") -> setOf("beach", "park", "spa", "resort", "relax", "nature", "pool")
            t.contains("family") -> setOf("park", "playground", "zoo", "museum", "picnic", "family", "camp", "nature", "view")
            t.contains("romantic") -> setOf("view", "restaurant", "park", "beach", "sunset", "romantic", "cafe", "wine")
            t.contains("sight") || t.contains("sightseeing") -> setOf("viewpoint", "attraction", "scenic", "tourism", "historic", "museum")
            else -> setOf(t)
        }
    }

    // Recompute selectedCategories from the activity chips and seeing spinner
    private fun recomputeSelectedCategoriesFromUI(): Set<String> {
        val tokens = mutableSetOf<String>()
        for (label in selectedActivityLabels) tokens.addAll(mapActivityLabelToTags(label))
        when (currentSeeingSelection.lowercase()) {
            "oceanic view", "oceanic" -> tokens.addAll(listOf("coastal", "beach", "bay", "ocean"))
            "mountain view", "mountain" -> tokens.addAll(listOf("mountain", "peak", "volcano", "view"))
        }
        return tokens
    }

    // Map the currently selected activity labels to a single ActivityType for planner APIs
    private fun mapSelectedActivitiesToActivityType(): com.example.scenic_navigation.models.ActivityType {
        // Priority: Shop & Dine -> Cultural -> Adventure -> Relaxation -> Family -> Romantic -> Sightseeing
        val lowered = selectedActivityLabels.joinToString(" ") { it.lowercase() }
        return when {
            lowered.contains("shop") || lowered.contains("dine") -> com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE
            lowered.contains("cultural") -> com.example.scenic_navigation.models.ActivityType.CULTURAL
            lowered.contains("adventure") || lowered.contains("hiking") -> com.example.scenic_navigation.models.ActivityType.ADVENTURE
            lowered.contains("relax") || lowered.contains("wellness") -> com.example.scenic_navigation.models.ActivityType.RELAXATION
            lowered.contains("family") -> com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY
            lowered.contains("romantic") -> com.example.scenic_navigation.models.ActivityType.ROMANTIC
            lowered.contains("sight") || lowered.contains("sightseeing") -> com.example.scenic_navigation.models.ActivityType.SIGHTSEEING
            else -> com.example.scenic_navigation.models.ActivityType.SIGHTSEEING
        }
    }

    private fun applyFilters() {
<<<<<<< Updated upstream
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
=======
        // Recompute selectedCategories from UI (activity chips + seeing) before delegating
        val prefs = recomputeSelectedCategoriesFromUI()
        selectedCategories.clear()
        selectedCategories.addAll(prefs)
        viewModel.fetchRecommendations(userLat, userLon, maxDistance.toDouble(), selectedCategories)
     }
>>>>>>> Stashed changes

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
<<<<<<< Updated upstream
        val recyclerView = view?.findViewById<RecyclerView>(R.id.rv_recommendations)
        if (recommendations.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            recyclerView?.visibility = View.GONE
=======
         val recyclerView = binding.rvRecommendations
         Log.i("RecommendationsFrag", "updateRecommendationsList called: recommendations.size=${recommendations.size}")
         var list = recommendations.toList()
         // Apply sorting preference locally (distance, scenic score, name)
         try {
             list = when (sortBy) {
                 SortOption.DISTANCE -> list.sortedBy { poi ->
                     if (poi.lat == null || poi.lon == null) Double.MAX_VALUE else com.example.scenic_navigation.utils.GeoUtils.haversine(userLat, userLon, poi.lat, poi.lon)
                 }
                 SortOption.SCENIC_SCORE -> list.sortedByDescending { it.scenicScore ?: 0f }
                 SortOption.NAME -> list.sortedBy { it.name }
             }
         } catch (_: Exception) {}

         if (list.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            // also hide towns list when showing empty POI results
            try { townsListView.visibility = View.GONE } catch (_: Exception) {}
>>>>>>> Stashed changes
            val emptyMessage = if (selectedCategories.isNotEmpty() || maxDistance < 50f) {
                "No POIs match your filters.\nTry adjusting the distance or category filters."
            } else if (selectedTown != null) {
                "No POIs found in $selectedTown."
            } else {
                "No recommendations available.\nPlan a route first to see recommendations along the way!"
            }
<<<<<<< Updated upstream
            binding.emptyStateMessage.text = emptyMessage
        } else {
            binding.emptyState.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            adapter.updateData(recommendations, userLat, userLon)
        }
    }
=======
             binding.emptyStateMessage.text = emptyMessage
         } else {
             binding.emptyState.visibility = View.GONE
             // hide towns list when showing POIs
             try { townsListView.visibility = View.GONE } catch (_: Exception) {}
             recyclerView.visibility = View.VISIBLE
             adapter.updateUserLocation(userLat, userLon)
             adapter.submitList(list)
             // After layout, ensure list scrolls to top so new results are visible
             recyclerView.post {
                 try { if (adapter.itemCount > 0) recyclerView.scrollToPosition(0) } catch (_: Exception) {}
             }
         }
     }
>>>>>>> Stashed changes

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

<<<<<<< Updated upstream
class RecommendationsAdapter(
    private var items: List<Poi>,
    private var userLat: Double = 14.5995,
    private var userLon: Double = 120.9842
) : RecyclerView.Adapter<RecommendationsAdapter.ViewHolder>() {
=======
class RecommendationsAdapter : ListAdapter<Poi, RecommendationsAdapter.ViewHolder>(DIFF) {
    var userLat: Double = 14.5995
    var userLon: Double = 120.9842
    var onLikeClick: ((Poi) -> Unit)? = null
    var onPoiClick: ((Poi) -> Unit)? = null
    private val PAYLOAD_USER_LOCATION = "payload_user_location"
>>>>>>> Stashed changes

    class ViewHolder(val binding: com.example.scenic_navigation.databinding.PoiItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.example.scenic_navigation.databinding.PoiItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

<<<<<<< Updated upstream
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvName.text = item.name
            tvCategory.text = item.category?.uppercase() ?: "POI"
            tvDescription.text = item.description
=======
    override fun getItemId(position: Int): Long {
        val item = getItem(position)
        // Use canonicalKey hash as stable id
        return canonicalKey(item).hashCode().toLong()
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
             // Set scenic score pill if available
             val score = item.scenicScore ?: 0f
             if (score > 0f) {
                 tvScenicScore.visibility = View.VISIBLE
                 tvScenicScore.text = score.toInt().toString()
             } else {
                 tvScenicScore.visibility = View.GONE
             }

             // Prefix description with scenic score when present
             val desc = buildString {
                 val score = item.scenicScore ?: 0f
                 if (score > 0f) {
                     append("Scenic score: ${score.toInt()} • ")
                 }
                 append(item.description)
             }
             tvDescription.text = desc

             // Tint the icon background by category to visually differentiate
             try {
                 val cat = (item.category ?: "").lowercase(Locale.getDefault())
                 val bgColor = when {
                     cat.contains("mount") || cat.contains("peak") -> Color.parseColor("#66BB6A") // lakbay_green
                     cat.contains("beach") || cat.contains("coast") || cat.contains("ocean") -> Color.parseColor("#26C6DA") // lakbay_cyan
                     cat.contains("restaurant") || cat.contains("food") || cat.contains("shop") -> Color.parseColor("#FF6F00") // lakbay_orange
                     cat.contains("historic") || cat.contains("museum") || cat.contains("heritage") -> Color.parseColor("#8E24AA") // lakbay_purple
                     else -> Color.parseColor("#FE4740") // lakbay_red
                 }
                 iconBackground.setBackgroundResource(R.drawable.icon_circle_background)
                 iconBackground.background.setTint(bgColor)
             } catch (_: Exception) {}

            // Set like button based on persisted curated POIs stored in shared prefs using canonical key
            try {
                val prefs = holder.binding.root.context.getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
                val curated = prefs.getStringSet("curated_pois", emptySet()) ?: emptySet()
                val key = RecommendationsAdapter.canonicalKey(item)
                if (curated.contains(key)) {
                    btnLike.setImageResource(R.drawable.ic_favorite_24)
                } else {
                    btnLike.setImageResource(R.drawable.ic_favorite_border_24)
                }
            } catch (_: Exception) {}
>>>>>>> Stashed changes

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
<<<<<<< Updated upstream
=======

            // Like button handling — the fragment will set onLikeClick and maintain curated state
            btnLike.setOnClickListener {
                onLikeClick?.invoke(item)
            }

            // POI item click handling — the fragment will set onPoiClick to plan routes
            root.setOnClickListener {
                onPoiClick?.invoke(item)
            }
>>>>>>> Stashed changes
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
<<<<<<< Updated upstream
        notifyDataSetChanged()
    }
}
=======
        // Only notify that user location changed so onBindViewHolder can update distances only
        val count = currentList.size
        if (count > 0) notifyItemRangeChanged(0, count, PAYLOAD_USER_LOCATION)
    }

    // use GeoUtils.haversine for distance calculations
    companion object {
        // Helper to compute canonical curated key consistently with ViewModel
        fun canonicalKey(poi: Poi): String {
            val n = poi.name.trim().replace(",", " ")
            val c = (poi.category ?: "").trim().replace(",", " ")
            val lat = poi.lat?.toString() ?: "0.0"
            val lon = poi.lon?.toString() ?: "0.0"
            return "${n},${c},${lat},${lon}"
        }
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
>>>>>>> Stashed changes
