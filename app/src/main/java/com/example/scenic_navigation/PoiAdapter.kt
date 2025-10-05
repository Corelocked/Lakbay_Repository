package com.example.scenic_navigation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.models.ScenicMunicipality
import com.example.scenic_navigation.models.RecommendationItem

class PoiAdapter(
    private val items: MutableList<RecommendationItem>,
    private val onPoiClick: (Poi) -> Unit,
    private val onMunicipalityClick: (ScenicMunicipality) -> Unit
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
                holder.description.text = poi.description
                holder.itemView.setOnClickListener { onPoiClick(poi) }
                val cat = poi.category.lowercase()
                val iconRes = when {
                    cat.contains("food") || cat.contains("restaurant") || cat.contains("cafe") -> R.drawable.ic_food_marker
                    cat.contains("historic") || cat.contains("museum") || cat.contains("monument") -> R.drawable.ic_historic_marker
                    cat.contains("scenic") || cat.contains("viewpoint") || cat.contains("landmark") -> R.drawable.ic_scenic_marker
                    else -> R.drawable.ic_scenic_marker
                }
                holder.icon.setImageResource(iconRes)
            }
            is RecommendationItem.MunicipalityItem -> {
                val m = item.municipality
                holder.name.text = m.name
                holder.category.text = when (m.type) {
                    "coastal" -> "Coastal Town"
                    "mountain" -> "Mountain Town"
                    else -> "Town"
                }
                holder.description.text = listOfNotNull(
                    m.population?.let { "Population: $it" },
                    m.elevation?.let { "Elevation: ${"%.0f".format(it)}m" }
                ).joinToString(" | ")
                holder.itemView.setOnClickListener { onMunicipalityClick(m) }
                val iconRes = when (m.type) {
                    "coastal" -> R.drawable.ic_coastal_town
                    "mountain" -> R.drawable.ic_mountain_town
                    else -> R.drawable.ic_town
                }
                holder.icon.setImageResource(iconRes)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<RecommendationItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: android.widget.ImageView = itemView.findViewById(R.id.iv_icon)
        val name: TextView = itemView.findViewById(R.id.tv_name)
        val category: TextView = itemView.findViewById(R.id.tv_category)
        val description: TextView = itemView.findViewById(R.id.tv_description)
    }
}
