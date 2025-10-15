package com.example.scenic_navigation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.R
import com.example.scenic_navigation.databinding.FragmentRecommendationsBinding
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.viewmodel.RecommendationsViewModel
import com.example.scenic_navigation.viewmodel.SharedRouteViewModel

class RecommendationsFragment : Fragment() {
    private var _binding: FragmentRecommendationsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationsViewModel by viewModels()
    private val sharedViewModel: SharedRouteViewModel by activityViewModels()
    private lateinit var adapter: RecommendationsAdapter

    private var showingTowns = true
    private var selectedTown: String? = null
    private var allPois: List<Poi> = emptyList()
    private lateinit var townsAdapter: ArrayAdapter<String>
    private lateinit var townsListView: ListView

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

        setupRecyclerView()
        setupTownsList(view)
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
        adapter = RecommendationsAdapter(emptyList())
        // Use findViewById as workaround for ViewBinding type resolution issue
        val recyclerView = view?.findViewById<RecyclerView>(R.id.rv_recommendations)
        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.adapter = adapter
    }

    private fun setupTownsList(view: View) {
        townsListView = ListView(requireContext())
        townsListView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        (binding.root as ViewGroup).addView(townsListView, 0)
        townsListView.visibility = View.VISIBLE
        townsListView.setOnItemClickListener { _, _, position, _ ->
            val town = townsAdapter.getItem(position)
            selectedTown = town
            showPoisForTown(town)
        }
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
        val referenceLat = 14.5995 // Manila default
        val referenceLon = 120.9842
        val poisForTown = allPois.filter { it.municipality == town }
            .sortedBy { poi ->
                val lat = poi.lat ?: referenceLat
                val lon = poi.lon ?: referenceLon
                haversine(referenceLat, referenceLon, lat, lon)
            }
        updateRecommendationsList(poisForTown)
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
            val emptyMessage = if (selectedTown != null) {
                "No POIs found in $selectedTown."
            } else {
                "No recommendations available.\nPlan a route first to see recommendations along the way!"
            }
            binding.emptyStateMessage.text = emptyMessage
        } else {
            binding.emptyState.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            adapter.updateData(recommendations)
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

class RecommendationsAdapter(private var items: List<Poi>) :
    RecyclerView.Adapter<RecommendationsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_name)
        val category: TextView = view.findViewById(R.id.tv_category)
        val description: TextView = view.findViewById(R.id.tv_description)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.poi_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.category.text = item.category.uppercase()
        holder.description.text = item.description
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<Poi>) {
        items = newItems
        notifyDataSetChanged()
    }
}
