package com.example.scenic_navigation

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.databinding.ItemPoiPreviewHorizontalBinding
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.services.PoiImageRepository
import com.example.scenic_navigation.utils.PoiTagChipBinder

class PoiPreviewAdapter(
    initialItems: List<Poi>,
    private val onClick: (Poi) -> Unit,
    private val relevantTagTokens: Set<String> = emptySet()
) : RecyclerView.Adapter<PoiPreviewAdapter.ViewHolder>() {

    private val items = mutableListOf<Poi>()

    init {
        items.addAll(initialItems)
    }

    class ViewHolder(val binding: ItemPoiPreviewHorizontalBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPoiPreviewHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val poi = items[position]
        with(holder.binding) {
            tvPreviewName.text = poi.name
            PoiTagChipBinder.bind(previewTagContainer, poi, maxTags = 2, preferredTokens = relevantTagTokens)
            ivPreviewImage?.let { PoiImageRepository.loadInto(it, poi) }
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

    fun submitItems(newItems: List<Poi>) {
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return stableKey(oldItems[oldItemPosition]) == stableKey(newItems[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    private fun stableKey(poi: Poi): String {
        val lat = poi.lat?.toString() ?: "na"
        val lon = poi.lon?.toString() ?: "na"
        return "${poi.name}|$lat|$lon"
    }
}
