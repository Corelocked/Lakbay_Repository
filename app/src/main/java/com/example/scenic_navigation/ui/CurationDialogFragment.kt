package com.example.scenic_navigation.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.example.scenic_navigation.databinding.DialogCurationBinding
import com.example.scenic_navigation.models.ActivityType
import com.example.scenic_navigation.models.CurationIntent
// No placeholder types
import com.example.scenic_navigation.models.SeeingType
import com.example.scenic_navigation.viewmodel.SharedRouteViewModel

/**
 * Dialog to collect curation choices: destination, seeing type, and activity.
 */
class CurationDialogFragment : DialogFragment() {
    private var _binding: DialogCurationBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedRouteViewModel by activityViewModels()

    private var destPlaceAdapter: PlaceSuggestionAdapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogCurationBinding.inflate(layoutInflater)

        // Setup autocomplete for destination input
        val packageName = requireContext().packageName
        destPlaceAdapter = PlaceSuggestionAdapter(requireContext(), packageName)
        binding.etCurDestination.setAdapter(destPlaceAdapter)
        binding.etCurDestination.threshold = 2

        // Handle item selection
        binding.etCurDestination.setOnItemClickListener { _, _, position, _ ->
            val selected = destPlaceAdapter?.getItem(position)
            binding.etCurDestination.setText(selected)
        }

        // Populate coastal spinner with keys from RoutingService
        val routingService = com.example.scenic_navigation.services.RoutingService()
        val coastalKeys = routingService.getCoastalWaypointKeys()
        val spinnerAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, coastalKeys)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCoastalSets.adapter = spinnerAdapter

        // Setup advanced options toggle
        setupAdvancedOptionsToggle()

        val builder = AlertDialog.Builder(requireContext())
            .setTitle("Plan a curated trip")
            .setView(binding.root)
            .setPositiveButton("Plan") { _, _ ->
                val dest = binding.etCurDestination.text.toString().trim()
                if (dest.isEmpty()) {
                    // nothing set: just dismiss
                    sharedViewModel.setCurationIntent(null)
                    return@setPositiveButton
                }

                val seeing = when (binding.rgSeeing.checkedRadioButtonId) {
                    binding.rbOceanic.id -> com.example.scenic_navigation.models.SeeingType.OCEANIC
                    binding.rbMountain.id -> com.example.scenic_navigation.models.SeeingType.MOUNTAIN
                    else -> com.example.scenic_navigation.models.SeeingType.OCEANIC
                }

                val activity = when (binding.rgActivity.checkedRadioButtonId) {
                    binding.rbSightseeing.id -> com.example.scenic_navigation.models.ActivityType.SIGHTSEEING
                    binding.rbShopDine.id -> com.example.scenic_navigation.models.ActivityType.SHOP_AND_DINE
                    binding.rbCultural.id -> com.example.scenic_navigation.models.ActivityType.CULTURAL
                    binding.rbAdventure.id -> com.example.scenic_navigation.models.ActivityType.ADVENTURE
                    binding.rbRelaxation.id -> com.example.scenic_navigation.models.ActivityType.RELAXATION
                    binding.rbFamilyFriendly.id -> com.example.scenic_navigation.models.ActivityType.FAMILY_FRIENDLY
                    binding.rbRomantic.id -> com.example.scenic_navigation.models.ActivityType.ROMANTIC
                    else -> com.example.scenic_navigation.models.ActivityType.SIGHTSEEING
                }

                // Collect subtype flags
                val selectedSubtypes = mutableSetOf<String>()
                if (binding.cbLakeside.isChecked) selectedSubtypes.add("lakeside")
                if (binding.cbForest.isChecked) selectedSubtypes.add("forest")
                if (binding.cbHiking.isChecked) selectedSubtypes.add("hiking")
                if (binding.cbAdventure.isChecked) selectedSubtypes.add("adventure")
                if (binding.cbRelax.isChecked) selectedSubtypes.add("relax")

                // Coastal forcing options
                val forceCoastal = binding.switchForceCoastal.isChecked
                val coastalKey = if (forceCoastal && binding.spinnerCoastalSets.selectedItem != null) binding.spinnerCoastalSets.selectedItem as String else null

                // Set the curation intent and extras on the shared view model
                sharedViewModel.setCurationIntent(
                    CurationIntent(
                        destinationQuery = dest,
                        seeing = seeing,
                        activity = activity
                    )
                )
                // Attach extras as a separate LiveData-friendly payload via view model methods
                // Store extras properly in SharedRouteViewModel so RouteFragment/RouteViewModel can use them
                val extras = com.example.scenic_navigation.models.CurationIntentExtras(coastalKey, forceCoastal, selectedSubtypes)
                sharedViewModel.setCurationExtras(extras)
            }
            .setNegativeButton("Cancel") { _, _ ->
                sharedViewModel.setCurationIntent(null)
            }

        return builder.create()
    }

    private fun setupAdvancedOptionsToggle() {
        var isExpanded = false

        binding.advancedOptionsHeader.setOnClickListener {
            isExpanded = !isExpanded

            if (isExpanded) {
                // Expand
                binding.advancedOptionsContent.visibility = android.view.View.VISIBLE
                binding.advancedOptionsArrow.animate()
                    .rotation(180f)
                    .setDuration(200)
                    .start()
            } else {
                // Collapse
                binding.advancedOptionsContent.visibility = android.view.View.GONE
                binding.advancedOptionsArrow.animate()
                    .rotation(0f)
                    .setDuration(200)
                    .start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        destPlaceAdapter?.cleanup()
        destPlaceAdapter = null
        _binding = null
    }
}
