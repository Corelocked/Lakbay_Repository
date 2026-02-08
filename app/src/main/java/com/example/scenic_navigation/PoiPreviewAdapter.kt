package com.example.scenic_navigation

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.databinding.ItemPoiPreviewHorizontalBinding
import com.example.scenic_navigation.models.Poi

class PoiPreviewAdapter(
    private val items: List<Poi>,
    private val onClick: (Poi) -> Unit
) : RecyclerView.Adapter<PoiPreviewAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPoiPreviewHorizontalBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPoiPreviewHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val poi = items[position]
        with(holder.binding) {
            tvPreviewName.text = poi.name
            tvPreviewCategory.text = poi.category
            root.setOnClickListener { onClick(poi) }
            // Accessibility: content description for card
            root.contentDescription = root.context.getString(com.example.scenic_navigation.R.string.poi_card_desc, poi.name, poi.category)

            ivFavorite?.let { iv ->
                val isFav = try { FavoriteStore.isFavorite(poi) } catch (e: Exception) { false }
                iv.setImageResource(if (isFav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
                iv.setOnClickListener {
                    val wasFav = FavoriteStore.isFavorite(poi)
                    if (wasFav) {
                        iv.animate().scaleX(0.8f).scaleY(0.8f).setDuration(140).withEndAction {
                            FavoriteStore.removeByPoi(poi)
                            iv.setImageResource(android.R.drawable.btn_star_big_off)
                            iv.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        }.start()
                    } else {
                        iv.animate().scaleX(1.3f).scaleY(1.3f).setDuration(140).withEndAction {
                            FavoriteStore.addOrReplaceFavorite(poi)
                            iv.setImageResource(android.R.drawable.btn_star_big_on)
                            iv.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        }.start()
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
