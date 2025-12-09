package com.example.scenic_navigation.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.scenic_navigation.R
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.FavoriteStore
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar

class POIDetailBottomSheet(private val poi: Poi) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_poi_detail_bottom_sheet, container, false)

        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tv_poi_title)
        val tvCategory = view.findViewById<android.widget.TextView>(R.id.tv_poi_category)
        val tvDescription = view.findViewById<android.widget.TextView>(R.id.tv_poi_description)
        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save)
        val btnNavigate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_navigate)

        tvTitle.text = poi.name
        tvCategory.text = poi.category
        tvDescription.text = poi.description

        btnSave.setOnClickListener {
            val key = "${poi.name}_${poi.lat}_${poi.lon}"
            if (FavoriteStore.isFavorite(key)) {
                FavoriteStore.removeFavorite(key)
                Snackbar.make(requireView(), "Removed from favorites", Snackbar.LENGTH_SHORT).show()
                btnSave.text = "Save"
            } else {
                FavoriteStore.addFavorite(key, poi)
                Snackbar.make(requireView(), "Saved '${poi.name}'", Snackbar.LENGTH_SHORT).show()
                btnSave.text = "Saved"
            }
        }

        btnNavigate.setOnClickListener {
            // Open external maps app as a quick navigation action
            val lat = poi.lat
            val lon = poi.lon
            if (lat != null && lon != null) {
                val gmmIntentUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(poi.name)})")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                if (mapIntent.resolveActivity(requireActivity().packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    // Fallback to generic intent
                    startActivity(Intent(Intent.ACTION_VIEW, gmmIntentUri))
                }
            } else {
                Snackbar.make(requireView(), "Location not available for this POI", Snackbar.LENGTH_SHORT).show()
            }
        }

        return view
    }
}
