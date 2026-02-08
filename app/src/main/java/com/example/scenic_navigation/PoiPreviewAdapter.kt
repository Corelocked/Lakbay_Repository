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
                val key = try { com.example.scenic_navigation.ui.RecommendationsAdapter.canonicalKey(poi) } catch (_: Exception) { "${poi.name}_${poi.lat}_${poi.lon}" }
                val isFav = try { FavoriteStore.isFavorite(key) } catch (_: Exception) { false }
                val ctx = iv.context
                val tintOn = androidx.core.content.ContextCompat.getColor(ctx, R.color.lakbay_yellow)
                val tintOff = androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)
                iv.setImageResource(if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                iv.imageTintList = android.content.res.ColorStateList.valueOf(if (isFav) tintOn else tintOff)
                iv.contentDescription = if (isFav) ctx.getString(R.string.poi_unliked, poi.name) else ctx.getString(R.string.like_poi)
                iv.setOnClickListener {
                    val wasFav = try { FavoriteStore.isFavorite(key) } catch (_: Exception) { false }
                    if (wasFav) {
                        iv.animate().scaleX(0.8f).scaleY(0.8f).setDuration(140).withEndAction {
                            FavoriteStore.removeFavorite(key)
                            iv.setImageResource(R.drawable.ic_star_outline)
                            iv.imageTintList = android.content.res.ColorStateList.valueOf(tintOff)
                            iv.contentDescription = ctx.getString(R.string.like_poi)
                            iv.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        }.start()
                    } else {
                        iv.animate().scaleX(1.3f).scaleY(1.3f).setDuration(140).withEndAction {
                            FavoriteStore.addFavorite(key, poi)
                            iv.setImageResource(R.drawable.ic_star_filled)
                            iv.imageTintList = android.content.res.ColorStateList.valueOf(tintOn)
                            iv.contentDescription = ctx.getString(R.string.poi_unliked, poi.name)
                            iv.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        }.start()
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
