package com.example.scenic_navigation

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.databinding.ItemFavoriteBinding
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.services.PoiImageRepository

class FavoritesAdapter(
    private val items: MutableList<Poi>,
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
            PoiImageRepository.loadInto(ivPoiIcon, poi)
            root.setOnClickListener { onClick(poi) }

            val key = try { com.example.scenic_navigation.ui.RecommendationsAdapter.canonicalKey(poi) } catch (_: Exception) { "${poi.name}_${poi.lat}_${poi.lon}" }
            val isFav = try { FavoriteStore.isFavorite(key) } catch (_: Exception) { false }
            val ctx = ivFavorite.context
            val tintOn = androidx.core.content.ContextCompat.getColor(ctx, R.color.lakbay_yellow)
            val tintOff = androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)
            ivFavorite.setImageResource(if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            ivFavorite.imageTintList = android.content.res.ColorStateList.valueOf(if (isFav) tintOn else tintOff)
            // Use the non-formatted unlike label for contentDescription to avoid missing resource issues
            ivFavorite.contentDescription = if (isFav) ctx.getString(R.string.unlike_poi) else ctx.getString(R.string.like_poi)
            ivFavorite.setOnClickListener {
                val wasFav = try { FavoriteStore.isFavorite(key) } catch (_: Exception) { false }
                if (wasFav) {
                    ivFavorite.animate().scaleX(0.8f).scaleY(0.8f).setDuration(140).withEndAction {
                        FavoriteStore.removeFavorite(key)
                        ivFavorite.setImageResource(R.drawable.ic_star_outline)
                        ivFavorite.imageTintList = android.content.res.ColorStateList.valueOf(tintOff)
                        ivFavorite.contentDescription = ctx.getString(R.string.like_poi)
                        ivFavorite.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }.start()
                } else {
                    ivFavorite.animate().scaleX(1.3f).scaleY(1.3f).setDuration(140).withEndAction {
                        FavoriteStore.addFavorite(key, poi)
                        ivFavorite.setImageResource(R.drawable.ic_star_filled)
                        ivFavorite.imageTintList = android.content.res.ColorStateList.valueOf(tintOn)
                        ivFavorite.contentDescription = ctx.getString(R.string.unlike_poi)
                        ivFavorite.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }.start()
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun replaceItems(updated: List<Poi>) {
        items.clear()
        items.addAll(updated)
        notifyDataSetChanged()
    }
}
