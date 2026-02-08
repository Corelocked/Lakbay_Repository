package com.example.scenic_navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View

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
import com.example.scenic_navigation.viewmodel.RouteViewModel
import kotlinx.coroutines.launch
import android.widget.Toast
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.Color
import android.view.ViewGroup
import android.widget.CompoundButton
import android.view.MotionEvent
import com.example.scenic_navigation.utils.GeoUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.scenic_navigation.MainActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import android.widget.LinearLayout
import com.example.scenic_navigation.FavoriteStore

class RecommendationsFragment : Fragment() {
    private var _binding: FragmentRecommendationsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationsViewModel by viewModels()
    private val sharedViewModel: SharedRouteViewModel by activityViewModels()
    private val routeViewModel: RouteViewModel by activityViewModels()
    private lateinit var adapter: RecommendationsAdapter
    private lateinit var locationService: LocationService

    // We no longer show the interim towns list — always display POIs directly
    private var showingTowns = false
    private var forceShowPois = false
    private var selectedTown: String? = null
    private var allPois: List<Poi> = emptyList()
    private var filteredPois: List<Poi> = emptyList()
    private var townsListView: ListView? = null

    // Filter state
    private var userLat: Double = 14.5995 // Default Manila
    private var userLon: Double = 120.9842
    private var maxDistance: Float = 50f // km
    private var selectedCategories = mutableSetOf<String>()
    private var currentSeeingSelection: String = "Oceanic View"
    // Multi-select activity labels (the UI chips)
    private val selectedActivityLabels = mutableSetOf<String>()
    // Make sortBy nullable so the user can clear sorting by unchecking chips
    // Start with no active sort by default to make deselection intuitive
    private var sortBy: SortOption? = null
    private var filterCollapsed = false

    // Track last effective categories used for a fetch so we can detect overly-strict filters
    private var lastEffectiveCategories: Set<String> = emptySet()
    private var didRelaxOnce = false
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
        // Personalization is now handled in Settings; no in-UI switch here.

        getUserLocation()

        setupRecyclerView()
        setupTownsList()
        setupFilters()
        observeViewModel()

        // Back press handling for navigation between towns and POIs
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // With towns view removed, just perform normal back navigation
                isEnabled = false
                // Dispatch a back press via the OnBackPressedDispatcher instead of the deprecated API
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        })

        // Check if we have route recommendations from shared ViewModel first
        if (sharedViewModel.hasRecommendations()) {
            // Use recommendations from the route
            val recommendations = sharedViewModel.recommendations.value ?: emptyList()
            updateRecommendationsList(recommendations)
        } else {
            // Fetch general recommendations if no route exists (use unified fetch that computes effective categories)
            fetchWithCurrentFilters()
        }

        // Swipe to refresh triggers re-fetch
        binding.swipeRefresh?.setOnRefreshListener {
            fetchWithCurrentFilters()
        }

        // FAB and empty-state button trigger curation flow (open a lightweight curator — here we just show a toast)
        binding.fabCurate.setOnClickListener {
            // Provide quick feedback and trigger a fresh recommendation fetch.
            Log.i("RecommendationsFrag", "FAB clicked — preparing UI and triggering fetchRecommendations()")
            // Set UI to expect POIs first to avoid a race where the ViewModel emits before we flip the flag.
            showingTowns = false
            forceShowPois = true
            // Hide towns list and empty state so the POI list (or loading) becomes visible immediately.
            try { townsListView?.visibility = View.GONE } catch (_: Exception) {}
            binding.emptyState.visibility = View.GONE
            binding.rvRecommendations.visibility = View.VISIBLE
            // Clear shared recommendations so local recommendations take precedence
            sharedViewModel.updateRecommendations(emptyList())
            Toast.makeText(requireContext(), getString(R.string.curate_now), Toast.LENGTH_SHORT).show()
            // Trigger the ViewModel to (re)fetch recommendations using current UI filters
            fetchWithCurrentFilters()
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
            Log.d("RecommendationsFrag", "onLikeClick invoked for ${poi.name}")
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
            Log.d("RecommendationsFrag", "onPoiClick invoked for ${poi.name}")
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
        // Try to find lv_towns by id at runtime (may not exist in the redesigned layout)
        val id = resources.getIdentifier("lv_towns", "id", requireContext().packageName)
        if (id != 0) {
            try {
                val lv = binding.root.findViewById<ListView?>(id)
                lv?.visibility = View.GONE
                townsListView = lv
            } catch (e: Exception) {
                townsListView = null
            }
        } else {
            townsListView = null
        }
    }

    private fun setupFilters() {

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
                 Log.d("RecommendationsFrag", "Seeing selected: ${seeingOptions[position]}")
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
                 chip.isChecked = selectedActivityLabels.contains(label)
                 chip.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                     Log.d("RecommendationsFrag", "Activity chip '$label' checked=$checked")
                     if (checked) selectedActivityLabels.add(label) else selectedActivityLabels.remove(label)
                     // Let applyFilters() merge activity-derived tokens with explicit category chips —
                     // do NOT overwrite selectedCategories here.
                     applyFilters()
                 }
                 chipGroup.addView(chip)
             }
             binding.filterCollapsibleContent.addView(chipGroup)
         } catch (_: Exception) {}

         // Distance slider
         binding.sliderDistance.addOnChangeListener { _, value, _ ->
            Log.d("RecommendationsFrag", "Distance slider changed: $value")
             if (suppressFilterEvents) return@addOnChangeListener
             maxDistance = value
             binding.tvDistanceValue.text = getString(R.string.distance_value_fmt, value.roundToInt())
             applyFilters()
         }

        // Ensure chip group allows zero selection at runtime (some themes default selectionRequired=true)
        try {
            binding.chipGroupSort.isSingleSelection = false
            binding.chipGroupSort.isSelectionRequired = false
            binding.chipSortDistance.isCheckable = true
            binding.chipSortScenic.isCheckable = true
            binding.chipSortName.isCheckable = true
            // Clear any initial selection and ensure internal state consistent
            suppressFilterEvents = true
            binding.chipSortDistance.isChecked = false
            binding.chipSortScenic.isChecked = false
            binding.chipSortName.isChecked = false
            suppressFilterEvents = false
            sortBy = null
        } catch (_: Exception) {}

        // Record pre-touch checked state on ACTION_DOWN and handle selection/deselection in OnClick.
        // This avoids fighting the Chip's default toggle behavior and works even when themes
        // attempt to enforce single-selection/selectionRequired.
        try {
            var lastTouchedChipId = -1
            var lastTouchedWasChecked = false

            val recordTouch = View.OnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    lastTouchedChipId = v.id
                    lastTouchedWasChecked = when (v.id) {
                        binding.chipSortDistance.id -> binding.chipSortDistance.isChecked
                        binding.chipSortScenic.id -> binding.chipSortScenic.isChecked
                        binding.chipSortName.id -> binding.chipSortName.isChecked
                        else -> false
                    }
                }
                // Do not consume; let the default toggle behavior continue.
                return@OnTouchListener false
            }

            binding.chipSortDistance.setOnTouchListener(recordTouch)
            binding.chipSortScenic.setOnTouchListener(recordTouch)
            binding.chipSortName.setOnTouchListener(recordTouch)

            binding.chipSortDistance.setOnClickListener { v ->
                if (suppressFilterEvents) return@setOnClickListener
                try {
                    if (lastTouchedChipId == v.id && lastTouchedWasChecked) {
                        // User tapped an already-checked chip -> clear all selection
                        suppressFilterEvents = true
                        try { binding.chipGroupSort.clearCheck() } catch (_: Exception) {}
                        suppressFilterEvents = false
                        sortBy = null
                        Log.i("RecommendationsFrag", "Sort cleared (Distance click)")
                    } else {
                        // Chip was toggled on: enforce mutual exclusion programmatically
                        suppressFilterEvents = true
                        binding.chipSortScenic.isChecked = false
                        binding.chipSortName.isChecked = false
                        suppressFilterEvents = false
                        sortBy = SortOption.DISTANCE
                        Log.i("RecommendationsFrag", "Sort set to DISTANCE (Distance click)")
                    }
                } finally {
                    try { updateRecommendationsList(adapter.currentList) } catch (_: Exception) {}
                }
            }

            binding.chipSortScenic.setOnClickListener { v ->
                if (suppressFilterEvents) return@setOnClickListener
                try {
                    if (lastTouchedChipId == v.id && lastTouchedWasChecked) {
                        suppressFilterEvents = true
                        try { binding.chipGroupSort.clearCheck() } catch (_: Exception) {}
                        suppressFilterEvents = false
                        sortBy = null
                        Log.i("RecommendationsFrag", "Sort cleared (Scenic click)")
                    } else {
                        suppressFilterEvents = true
                        binding.chipSortDistance.isChecked = false
                        binding.chipSortName.isChecked = false
                        suppressFilterEvents = false
                        sortBy = SortOption.SCENIC_SCORE
                        Log.i("RecommendationsFrag", "Sort set to SCENIC (Scenic click)")
                    }
                } finally {
                    try { updateRecommendationsList(adapter.currentList) } catch (_: Exception) {}
                }
            }

            binding.chipSortName.setOnClickListener { v ->
                if (suppressFilterEvents) return@setOnClickListener
                try {
                    if (lastTouchedChipId == v.id && lastTouchedWasChecked) {
                        suppressFilterEvents = true
                        try { binding.chipGroupSort.clearCheck() } catch (_: Exception) {}
                        suppressFilterEvents = false
                        sortBy = null
                        Log.i("RecommendationsFrag", "Sort cleared (Name click)")
                    } else {
                        suppressFilterEvents = true
                        binding.chipSortDistance.isChecked = false
                        binding.chipSortScenic.isChecked = false
                        suppressFilterEvents = false
                        sortBy = SortOption.NAME
                        Log.i("RecommendationsFrag", "Sort set to NAME (Name click)")
                    }
                } finally {
                    try { updateRecommendationsList(adapter.currentList) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

         // Wire the header collapse button to toggle the filter content using view binding.
         try {
             val btn = binding.btnFilterCollapse
             val content = binding.filterCollapsibleContent
             // initialize state and ensure touch blocking matches initial visibility
             content.visibility = if (filterCollapsed) View.GONE else View.VISIBLE
             content.alpha = if (filterCollapsed) 0f else 1f
             // Ensure the card is above other content at runtime
             try { binding.cardFilter.bringToFront() } catch (_: Exception) {}
             // rely on XML layering and clickable attribute to block empty-area taps

             btn.setOnClickListener {
                 filterCollapsed = !filterCollapsed
                 if (filterCollapsed) {
                     // hide with fade
                     content.animate().alpha(0f).setDuration(180).withEndAction {
                         content.visibility = View.GONE
                         // disable clickable when hidden to keep behavior explicit
                         try { content.isClickable = false } catch (_: Exception) {}
                     }.start()
                     btn.setText(R.string.expand_arrow) // ▲
                 } else {
                     content.visibility = View.VISIBLE
                     content.alpha = 0f
                     // enable touch blocking immediately so touches don't pass through during animation
                     // ensure filter card stays on top while expanded
                     try { binding.cardFilter.bringToFront() } catch (_: Exception) {}
                     try { content.isClickable = true } catch (_: Exception) {}
                     content.animate().alpha(1f).setDuration(180).start()
                     btn.setText(R.string.collapse_arrow) // ▼
                 }
             }
         } catch (_: Exception) {
             // binding may not be available in rare cases; ignore safely
         }
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
                        // Sort the list consistent with current sort selection
                        adapter.submitList(sortPois(filteredPois.ifEmpty { allPois }))
                        // Fetch recommendations now that we have the user's accurate location
                        fetchWithCurrentFilters()
                    }
                } catch (_: Exception) {
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
        Log.d("RecommendationsFrag", "applyFilters called: seeing=$currentSeeingSelection activities=${selectedActivityLabels.joinToString()} maxDistance=$maxDistance selectedCategories=$selectedCategories")
        // Merge activity-derived tokens with explicit category chip selections.
        // If "All" is checked, we treat it as no category filter (empty set).
        val activityPrefs = recomputeSelectedCategoriesFromUI()
        // Effective categories = explicit selectedCategories (from chips) union activity-derived tokens
        val effective = mutableSetOf<String>()
        effective.addAll(selectedCategories)
        effective.addAll(activityPrefs)

        // Do not overwrite selectedCategories here; keep explicit chip state separate from effective filter tokens.
        Log.d("RecommendationsFrag", "applyFilters: effectiveCategories=$effective")
        // Reset relaxation marker when user changes filters
        didRelaxOnce = false
        // Use central fetch to ensure all callers compute the same effective categories
        fetchWithCurrentFilters(effective)
    }

    // Centralized fetch that accepts an optional effectiveCategories set. If not provided, it
    // computes the effective set from current UI state (activities + seeing + explicit categories).
    private fun fetchWithCurrentFilters(effectiveCategories: Set<String>? = null) {
         val effective = effectiveCategories ?: run {
                val activityPrefs = recomputeSelectedCategoriesFromUI()
                val eff = mutableSetOf<String>()
                eff.addAll(selectedCategories)
                eff.addAll(activityPrefs)
                eff
        }
        Log.d("RecommendationsFrag", "fetchWithCurrentFilters: calling ViewModel with effective=$effective")
        lastEffectiveCategories = effective
        viewModel.fetchRecommendations(userLat, userLon, maxDistance.toDouble(), effective)
    }

    private fun showTownsList(pois: List<Poi>) {
        // Towns list UI removed — directly show POIs instead
        updateRecommendationsList(pois)
    }

    private fun updateRecommendationsList(recommendations: List<Poi>) {
         val recyclerView = binding.rvRecommendations
         Log.i("RecommendationsFrag", "updateRecommendationsList called: recommendations.size=${recommendations.size}")
         Log.d("RecommendationsFrag", "Current sortBy=$sortBy")
         if (recommendations.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            // also hide towns list when showing empty POI results
            townsListView?.visibility = View.GONE
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
             townsListView?.visibility = View.GONE
             recyclerView.visibility = View.VISIBLE
             adapter.updateUserLocation(userLat, userLon)
             // Sort according to current sort selection before submitting
             val sorted = sortPois(recommendations)
             adapter.submitList(sorted)
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
                // If we had active category filters and this is the first empty result, retry without category filters
                if (lastEffectiveCategories.isNotEmpty() && !didRelaxOnce) {
                    didRelaxOnce = true
                    Log.i("RecommendationsFrag", "Empty results with active filters — retrying without category filters to avoid empty UI")
                    Toast.makeText(requireContext(), "No POIs matched filters — showing relaxed results", Toast.LENGTH_SHORT).show()
                    fetchWithCurrentFilters(emptySet())
                    return@observe
                }
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

    // New helper to sort POIs according to the selected sort option.
    private fun sortPois(list: List<Poi>): List<Poi> {
        val opt = sortBy ?: return list
        return when (opt) {
            SortOption.DISTANCE -> list.sortedWith(compareBy { poi ->
                if (poi.lat != null && poi.lon != null) GeoUtils.haversine(userLat, userLon, poi.lat!!, poi.lon!!) else Double.MAX_VALUE
            })
            SortOption.SCENIC_SCORE -> list.sortedWith(compareByDescending<Poi> { it.scenicScore ?: 0f }.thenBy { it.name ?: "" })
            SortOption.NAME -> list.sortedBy { it.name?.lowercase(Locale.ROOT) ?: "" }
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
    var onPoiClick: ((Poi) -> Unit)? = null
    private val PAYLOAD_USER_LOCATION = "payload_user_location"

    class ViewHolder(val binding: com.example.scenic_navigation.databinding.PoiItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.example.scenic_navigation.databinding.PoiItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

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

            // Like button handling — the fragment will set onLikeClick and maintain curated state
            btnLike.setOnClickListener {
                Log.d("RecommendationsFrag", "btnLike clicked for ${item.name}")
                onLikeClick?.invoke(item)
            }

            // POI item click handling — the fragment will set onPoiClick to plan routes
            root.setOnClickListener {
                Log.d("RecommendationsFrag", "POI item clicked for ${item.name}")
                onPoiClick?.invoke(item)
            }

            // Like button state: use project drawables and apply tint
            try {
                val key = RecommendationsAdapter.canonicalKey(item)
                val isFav = try { FavoriteStore.isFavorite(key) } catch (_: Exception) { false }
                // Use our project's star vector assets for consistent UI
                val res = btnLike.context.resources
                if (isFav) {
                    btnLike.setImageResource(R.drawable.ic_star_filled)
                    btnLike.imageTintList = android.content.res.ColorStateList.valueOf(res.getColor(R.color.lakbay_yellow, null))
                    btnLike.contentDescription = btnLike.context.getString(R.string.unlike_poi)
                } else {
                    btnLike.setImageResource(R.drawable.ic_star_outline)
                    btnLike.imageTintList = android.content.res.ColorStateList.valueOf(res.getColor(R.color.text_secondary, null))
                    btnLike.contentDescription = btnLike.context.getString(R.string.like_poi)
                }
                btnLike.isClickable = true
                btnLike.isFocusable = true
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