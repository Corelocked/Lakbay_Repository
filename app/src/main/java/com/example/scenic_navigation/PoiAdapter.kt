package com.example.scenic_navigation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.models.ScenicMunicipality
import com.example.scenic_navigation.models.Town
import com.example.scenic_navigation.models.RecommendationItem
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

class PoiAdapter(
    private val items: MutableList<RecommendationItem>,
    private val onPoiClick: (Poi) -> Unit,
    private val onMunicipalityClick: (ScenicMunicipality) -> Unit,
    private val onTownClick: (Town) -> Unit
) : RecyclerView.Adapter<PoiAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.poi_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RecommendationItem.PoiItem -> {
                val poi = item.poi
                holder.name.text = poi.name
                holder.category.text = poi.category
                holder.description.text = poi.description.ifBlank { "Tap to view on map" }
                holder.itemView.setOnClickListener { onPoiClick(poi) }

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

                holder.icon.setImageResource(iconRes)
                setIconBackground(holder.iconBackground, colorRes, holder.itemView.context)
            }
            is RecommendationItem.MunicipalityItem -> {
                val m = item.municipality
                holder.name.text = m.name
                holder.category.text = when (m.type) {
                    "coastal" -> "Coastal Town"
                    "mountain" -> "Mountain Town"
                    else -> "Town"
                }
                holder.description.text = buildString {
                    append("Scenic destination")
                    m.population?.let { append(" • Population: ${formatNumber(it)}") }
                    m.elevation?.let { append(" • ${formatElevation(it)}") }
                }
                holder.itemView.setOnClickListener { onMunicipalityClick(m) }

                val (iconRes, colorRes) = when (m.type) {
                    "coastal" -> R.drawable.ic_coastal_town to R.color.category_coastal
                    "mountain" -> R.drawable.ic_mountain_town to R.color.category_mountain
                    else -> R.drawable.ic_town to R.color.category_default
                }

                holder.icon.setImageResource(iconRes)
                setIconBackground(holder.iconBackground, colorRes, holder.itemView.context)
            }
            is RecommendationItem.TownItem -> {
                val town = item.town
                holder.name.text = town.name
                holder.category.text = when (town.type) {
                    "city" -> "City"
                    "town" -> "Town"
                    "village" -> "Village"
                    else -> "Town"
                }

                val distanceKm = town.distanceFromStart / 1000.0
                holder.description.text = buildString {
                    append("${formatDistance(distanceKm)} from start")
                    town.population?.let { append(" • Pop: ${formatNumber(it)}") }
                    town.elevation?.let { append(" • ${formatElevation(it)}") }
                }
                holder.itemView.setOnClickListener { onTownClick(town) }

                val (iconRes, colorRes) = when (town.type) {
                    "city" -> R.drawable.ic_town to R.color.category_city
                    "village" -> R.drawable.ic_town to R.color.category_village
                    else -> R.drawable.ic_town to R.color.category_town
                }

                holder.icon.setImageResource(iconRes)
                setIconBackground(holder.iconBackground, colorRes, holder.itemView.context)
            }
            is RecommendationItem.ScenicItem -> {
                val scenicPoi = item.scenicPoi
                holder.name.text = scenicPoi.name
                holder.category.text = scenicPoi.type.replaceFirstChar { it.uppercase() }
                holder.description.text = "Scenic score: ${scenicPoi.score}/100 • Highly recommended"
                holder.itemView.setOnClickListener { /* ScenicPoi doesn't have full details to show */ }

                holder.icon.setImageResource(R.drawable.ic_scenic_marker)
                setIconBackground(holder.iconBackground, R.color.category_scenic, holder.itemView.context)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<RecommendationItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
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

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iconBackground: View = itemView.findViewById(R.id.icon_background)
        val icon: android.widget.ImageView = itemView.findViewById(R.id.iv_icon)
        val name: TextView = itemView.findViewById(R.id.tv_name)
        val category: TextView = itemView.findViewById(R.id.tv_category)
        val description: TextView = itemView.findViewById(R.id.tv_description)
    }
}
