package com.example.scenic_navigation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.databinding.PoiItemBinding
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.models.ScenicMunicipality
import com.example.scenic_navigation.models.Town
import com.example.scenic_navigation.models.RecommendationItem
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import com.example.scenic_navigation.services.UserPreferenceStore
import com.example.scenic_navigation.services.EventLogger
import com.example.scenic_navigation.services.SettingsStore
import com.example.scenic_navigation.utils.PoiTagChipBinder
import com.example.scenic_navigation.utils.PoiTagFormatter
import org.json.JSONObject

class PoiAdapter(
    private val items: MutableList<RecommendationItem>,
    private val onPoiClick: (Poi) -> Unit,
    private val onMunicipalityClick: (ScenicMunicipality) -> Unit,
    private val onTownClick: (Town) -> Unit,
    private val prefStore: UserPreferenceStore? = null,
    private val eventLogger: EventLogger? = null,
    private val settingsStore: SettingsStore? = null
) : RecyclerView.Adapter<PoiAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = PoiItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RecommendationItem.PoiItem -> {
                val poi = item.poi
                with(holder.binding) {
                    tvName.text = poi.name
                    tagScroll.visibility = View.VISIBLE
                    tvCategory.visibility = View.GONE
                    PoiTagChipBinder.bind(tagContainer, poi)
                    tvDescription.text = poi.description.ifBlank { "Tap to view on map" }
                    root.setOnClickListener {
                        // record preference by category
                        prefStore?.incrementCategory(poi.category ?: "")
                        // log event if user opted-in
                        val opted = settingsStore?.isTelemetryEnabled() ?: false
                        if (opted) {
                            eventLogger?.logEvent("poi_tap", mapOf(
                                "poi_name" to poi.name,
                                "category" to (poi.category ?: ""),
                                "municipality" to (poi.municipality ?: ""),
                                "lat" to (poi.lat ?: JSONObject.NULL),
                                "lon" to (poi.lon ?: JSONObject.NULL)
                            ))
                        }
                        onPoiClick(poi)
                    }
                }

                val cat = poi.category.lowercase()
                val (iconRes, colorRes) = when {
                    cat.contains("restaurant") || cat.contains("café") || cat.contains("cafe") ->
                        R.drawable.ic_food_marker to R.color.category_food
                    cat.contains("museum") || cat.contains("gallery") ->
                        R.drawable.ic_monument to R.color.category_culture
                    cat.contains("monument") || cat.contains("memorial") || cat.contains("historic") ->
                        R.drawable.ic_monument to R.color.category_historic
                    cat.contains("shop") || cat.contains("souvenir") || cat.contains("gift") || cat.contains("market") ->
                        R.drawable.ic_cart to R.color.category_shopping
                    cat.contains("park") || cat.contains("garden") ->
                        R.drawable.ic_tree to R.color.category_nature
                    cat.contains("scenic") || cat.contains("viewpoint") || cat.contains("attraction") ->
                        R.drawable.ic_scenic_marker to R.color.category_scenic
                    else -> R.drawable.ic_scenic_marker to R.color.category_default
                }

                holder.binding.ivIcon.setImageResource(iconRes)
                setIconBackground(holder.binding.iconBackground, colorRes, holder.itemView.context)
                holder.binding.ivIcon.contentDescription = holder.binding.ivIcon.context.getString(com.example.scenic_navigation.R.string.icon_for, poi.name, poi.category)
            }
            is RecommendationItem.MunicipalityItem -> {
                val m = item.municipality
                with(holder.binding) {
                    tvName.text = m.name
                    tagScroll.visibility = View.GONE
                    tvCategory.visibility = View.VISIBLE
                    tvCategory.text = when (m.type) {
                        "coastal" -> "Coastal Town"
                        "mountain" -> "Mountain Town"
                        else -> "Town"
                    }
                    tvDescription.text = buildString {
                        append("Scenic destination")
                        m.population?.let { append(" • Population: ${formatNumber(it)}") }
                        m.elevation?.let { append(" • ${formatElevation(it)}") }
                    }
                    root.setOnClickListener { onMunicipalityClick(m) }
                }

                val (iconRes, colorRes) = when (m.type) {
                    "coastal" -> R.drawable.ic_coastal_town to R.color.category_coastal
                    "mountain" -> R.drawable.ic_mountain_town to R.color.category_mountain
                    else -> R.drawable.ic_town to R.color.category_default
                }

                holder.binding.ivIcon.setImageResource(iconRes)
                setIconBackground(holder.binding.iconBackground, colorRes, holder.itemView.context)
                holder.binding.ivIcon.contentDescription = holder.binding.ivIcon.context.getString(com.example.scenic_navigation.R.string.icon_for, m.name, m.type)
            }
            is RecommendationItem.TownItem -> {
                val town = item.town
                with(holder.binding) {
                    tvName.text = town.name
                    tagScroll.visibility = View.GONE
                    tvCategory.visibility = View.VISIBLE
                    tvCategory.text = when (town.type) {
                        "city" -> "City"
                        "town" -> "Town"
                        "village" -> "Village"
                        else -> "Town"
                    }

                    val distanceKm = town.distanceFromStart / 1000.0
                    tvDescription.text = buildString {
                        append("${formatDistance(distanceKm)} from start")
                        town.population?.let { append(" • Pop: ${formatNumber(it)}") }
                        town.elevation?.let { append(" • ${formatElevation(it)}") }
                    }
                    root.setOnClickListener { onTownClick(town) }
                }

                val (iconRes, colorRes) = when (town.type) {
                    "city" -> R.drawable.ic_town to R.color.category_city
                    "village" -> R.drawable.ic_town to R.color.category_village
                    else -> R.drawable.ic_town to R.color.category_town
                }

                holder.binding.ivIcon.setImageResource(iconRes)
                setIconBackground(holder.binding.iconBackground, colorRes, holder.itemView.context)
                holder.binding.ivIcon.contentDescription = holder.binding.ivIcon.context.getString(com.example.scenic_navigation.R.string.icon_for, town.name, town.type)
            }
            is RecommendationItem.ScenicItem -> {
                val scenicPoi = item.scenicPoi
                with(holder.binding) {
                    tvName.text = scenicPoi.name
                    tagScroll.visibility = View.GONE
                    tvCategory.visibility = View.VISIBLE
                    tvCategory.text = scenicPoi.type.replaceFirstChar { it.uppercase() }
                    tvDescription.text = "Scenic score: ${scenicPoi.score}/100 • Highly recommended"
                    root.setOnClickListener { /* ScenicPoi doesn't have full details to show */ }
                }

                holder.binding.ivIcon.setImageResource(R.drawable.ic_scenic_marker)
                setIconBackground(holder.binding.iconBackground, R.color.category_scenic, holder.itemView.context)
                holder.binding.ivIcon.contentDescription = holder.binding.ivIcon.context.getString(com.example.scenic_navigation.R.string.scenic_icon_for, scenicPoi.name)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<RecommendationItem>) {
        // Use DiffUtil to compute minimal updates when possible. For simplicity,
        // if types/ordering drastically change, fallback to full replace.
        val old = ArrayList(items)
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = old[oldItemPosition]
                val n = newItems[newItemPosition]
                return when {
                    o is RecommendationItem.PoiItem && n is RecommendationItem.PoiItem -> o.poi.name == n.poi.name && o.poi.municipality == n.poi.municipality
                    o is RecommendationItem.MunicipalityItem && n is RecommendationItem.MunicipalityItem -> o.municipality.name == n.municipality.name
                    o is RecommendationItem.TownItem && n is RecommendationItem.TownItem -> o.town.name == n.town.name
                    o is RecommendationItem.ScenicItem && n is RecommendationItem.ScenicItem -> o.scenicPoi.name == n.scenicPoi.name
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return old[oldItemPosition] == newItems[newItemPosition]
            }
        })

        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    private fun setIconBackground(view: View, colorRes: Int, context: android.content.Context) {
        val color = ContextCompat.getColor(context, colorRes)
        val drawable = view.background as? GradientDrawable
        drawable?.setColor(color)
    }

    private fun formatNumber(num: Int): String {
        return when {
            num >= 1_000_000 -> "${num / 1_000_000}M"
            num >= 1_000 -> "${num / 1_000}K"
            else -> num.toString()
        }
    }

    private fun formatDistance(km: Double): String {
        return when {
            km < 1.0 -> "${(km * 1000).toInt()}m"
            km < 10.0 -> "${"%.1f".format(km)} km"
            else -> "${"%.0f".format(km)} km"
        }
    }

    private fun formatElevation(meters: Double): String {
        return "${meters.toInt()}m elevation"
    }

    class ViewHolder(val binding: PoiItemBinding) : RecyclerView.ViewHolder(binding.root)
}
