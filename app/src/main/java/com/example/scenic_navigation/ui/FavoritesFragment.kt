package com.example.scenic_navigation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.scenic_navigation.FavoriteStore
import com.example.scenic_navigation.FavoritesAdapter

class FavoritesFragment : Fragment() {
    private var _binding: com.example.scenic_navigation.databinding.FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = com.example.scenic_navigation.databinding.FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvFavorites.layoutManager = LinearLayoutManager(requireContext())
        FavoriteStore.init(requireContext())
        val adapter = FavoritesAdapter(mutableListOf()) { poi ->
            val bottom = POIDetailBottomSheet(poi)
            bottom.show(parentFragmentManager, "poi_detail")
        }
        binding.rvFavorites.adapter = adapter
        FavoriteStore.observeAllFavorites().observe(viewLifecycleOwner) { favorites ->
            adapter.replaceItems(favorites)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
