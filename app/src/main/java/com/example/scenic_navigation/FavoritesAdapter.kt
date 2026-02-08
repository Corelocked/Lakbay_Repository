package com.example.scenic_navigation

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.databinding.ItemFavoriteBinding
import com.example.scenic_navigation.models.Poi

class FavoritesAdapter(
    private val items: List<Poi>,
    private val onClick: (Poi) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFavoriteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val poi = items[position]
        with(holder.binding) {
            tvPoiName.text = poi.name
            tvPoiCategory.text = poi.category
            root.setOnClickListener { onClick(poi) }
            
            val key = "${poi.name}_${poi.lat}_${poi.lon}"
            val isFav = try { FavoriteStore.isFavorite(key) } catch (e: Exception) { false }
            ivFavorite.setImageResource(if (isFav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            ivFavorite.setOnClickListener {
                val wasFav = FavoriteStore.isFavorite(key)
                if (wasFav) {
                    ivFavorite.animate().scaleX(0.8f).scaleY(0.8f).setDuration(140).withEndAction {
                        FavoriteStore.removeFavorite(key)
                        ivFavorite.setImageResource(android.R.drawable.btn_star_big_off)
                        ivFavorite.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }.start()
                } else {
                    ivFavorite.animate().scaleX(1.3f).scaleY(1.3f).setDuration(140).withEndAction {
                        FavoriteStore.addFavorite(key, poi)
                        ivFavorite.setImageResource(android.R.drawable.btn_star_big_on)
                        ivFavorite.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }.start()
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
