package com.example.scenic_navigation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.R
import com.example.scenic_navigation.models.Poi
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ClusterListBottomSheet(private val pois: List<Poi>) : BottomSheetDialogFragment() {

    private var _binding: com.example.scenic_navigation.databinding.FragmentClusterListBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = com.example.scenic_navigation.databinding.FragmentClusterListBottomSheetBinding.inflate(inflater, container, false)
        val rv = binding.rvClusterMembers
        rv.layoutManager = LinearLayoutManager(requireContext())
        val adapter = ClusterListAdapter(pois) { poi ->
            // Open POI detail and keep sheet open for user to return
            val bottom = POIDetailBottomSheet(poi)
            bottom.show(parentFragmentManager, "poi_detail")
        }
        rv.adapter = adapter
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onStart() {
        super.onStart()
        // subtle expand animation when the bottom sheet appears
        dialog?.window?.decorView?.let { decor ->
            decor.scaleX = 0.96f
            decor.scaleY = 0.96f
            decor.alpha = 0.0f
            decor.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
        }
    }
}
