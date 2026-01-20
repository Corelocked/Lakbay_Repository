package com.example.scenic_navigation.ui

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scenic_navigation.R
import com.example.scenic_navigation.databinding.ItemPoiClusterListBinding
import com.example.scenic_navigation.models.Poi
import org.osmdroid.util.GeoPoint

class ClusterListAdapter(
    private val items: List<Poi>,
    private val onOpenDetail: (Poi) -> Unit
) : RecyclerView.Adapter<ClusterListAdapter.Holder>() {

    class Holder(val binding: ItemPoiClusterListBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPoiClusterListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val poi = items[position]
        holder.binding.tvPoiName.text = poi.name
        holder.binding.tvPoiMeta.text = poi.category + (poi.municipality?.let { " • $it" } ?: "")
        holder.binding.tvPoiScore.text = poi.scenicScore?.let { String.format("%.0f", it) } ?: "--"

        // Simple color circle icon using first letter
        try {
            val bmp = android.graphics.Bitmap.createBitmap(56, 56, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = when {
                poi.category.contains("beach", true) || poi.category.contains("coast", true) -> android.graphics.Color.parseColor("#0288D1")
                poi.category.contains("mount", true) || poi.category.contains("hike", true) -> android.graphics.Color.parseColor("#2E7D32")
                poi.category.contains("historic", true) -> android.graphics.Color.parseColor("#FFA000")
                else -> android.graphics.Color.parseColor("#1976D2")
            }
            canvas.drawCircle(28f, 28f, 28f, paint)
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            tp.color = android.graphics.Color.WHITE
            tp.textSize = 24f
            tp.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            val text = poi.name.trim().takeIf { it.isNotEmpty() }?.get(0)?.uppercaseChar() ?: '?'
            val tw = tp.measureText(text.toString())
            val fm = tp.fontMetrics
            val tx = (56 - tw) / 2f
            val ty = (56 - fm.ascent - fm.descent) / 2f
            canvas.drawText(text.toString(), tx, ty, tp)
            holder.binding.ivPoiIcon.setImageBitmap(bmp)
            holder.binding.ivPoiIcon.contentDescription = holder.binding.ivPoiIcon.context.getString(com.example.scenic_navigation.R.string.icon_for, poi.name, poi.category)
        } catch (_: Exception) {
        }

        holder.binding.root.setOnClickListener {
            onOpenDetail(poi)
        }

        // Accessibility: provide description for navigate button
        holder.binding.btnClusterNavigate.contentDescription = holder.binding.btnClusterNavigate.context.getString(com.example.scenic_navigation.R.string.navigate_to, poi.name)

        holder.binding.btnClusterNavigate.setOnClickListener {
            val lat = poi.lat
            val lon = poi.lon
            if (lat != null && lon != null) {
                val gmmIntentUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(poi.name)})")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                try {
                    holder.binding.root.context.startActivity(mapIntent)
                } catch (_: Exception) {
                    holder.binding.root.context.startActivity(Intent(Intent.ACTION_VIEW, gmmIntentUri))
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
