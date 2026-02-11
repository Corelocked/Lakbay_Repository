package com.example.scenic_navigation.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.R
import android.util.Log
import androidx.core.graphics.drawable.DrawableCompat

object MapIconUtils {
    fun getCategoryColor(category: String?): Int {
        val cat = category?.lowercase() ?: ""
        return when {
            cat.contains("beach") || cat.contains("coast") || cat.contains("ocean") || cat.contains("sea") -> android.graphics.Color.parseColor("#0288D1") // blue
            cat.contains("mount") || cat.contains("hike") || cat.contains("view") -> android.graphics.Color.parseColor("#2E7D32") // green
            cat.contains("historic") || cat.contains("church") || cat.contains("monument") -> android.graphics.Color.parseColor("#FFA000") // amber
            cat.contains("restaurant") || cat.contains("food") || cat.contains("shop") -> android.graphics.Color.parseColor("#FF6F00") // orange
            else -> android.graphics.Color.parseColor("#1976D2") // default primary
        }
    }

    private fun getCategoryIconRes(category: String?): Int {
        val cat = category?.lowercase() ?: ""
        return when {
            cat.contains("beach") || cat.contains("coast") || cat.contains("ocean") || cat.contains("sea") -> R.drawable.ic_oceanic_view
            cat.contains("mount") || cat.contains("hike") || cat.contains("view") -> R.drawable.ic_mountain_ranges
            cat.contains("restaurant") || cat.contains("food") || cat.contains("cafe") || cat.contains("bakery") || cat.contains("deli") -> R.drawable.ic_food_marker
            cat.contains("shop") || cat.contains("mall") || cat.contains("store") || cat.contains("market") -> R.drawable.ic_shop_and_dine
            cat.contains("historic") || cat.contains("museum") || cat.contains("heritage") || cat.contains("monument") -> R.drawable.ic_historic_marker
            cat.contains("monument") -> R.drawable.ic_monument
            cat.contains("adventure") || cat.contains("trail") || cat.contains("waterfall") || cat.contains("hike") -> R.drawable.ic_adventure_hiking
            cat.contains("relax") || cat.contains("wellness") || cat.contains("spa") -> R.drawable.ic_relaxation_wellness
            cat.contains("family") || cat.contains("zoo") || cat.contains("park") || cat.contains("playground") -> R.drawable.ic_family_friendly
            cat.contains("romantic") || cat.contains("sunset") || cat.contains("viewpoint") -> R.drawable.ic_romantic_getaway
            cat.contains("sight") || cat.contains("scenic") || cat.contains("attraction") -> R.drawable.ic_sight_seeing
            cat.contains("fuel") || cat.contains("gas") -> R.drawable.ic_gas
            cat.contains("bank") || cat.contains("atm") -> R.drawable.ic_bank
            cat.contains("medical") || cat.contains("hospital") || cat.contains("clinic") -> R.drawable.ic_medical
            cat.contains("train") || cat.contains("station") -> R.drawable.ic_train
            cat.contains("plane") || cat.contains("airport") -> R.drawable.ic_plane
            cat.contains("pet") || cat.contains("paw") -> R.drawable.ic_paw
            else -> R.drawable.ic_scenic_marker
        }
    }

    fun createPoiIcon(context: Context, poi: Poi, size: Int = 72): Bitmap {
        val radius = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Background circle
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        val fill = getCategoryColor(poi.category)
        paint.color = fill
        canvas.drawCircle(radius, radius, radius - 2f, paint)

        // white stroke
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 3f
        stroke.color = android.graphics.Color.WHITE
        canvas.drawCircle(radius, radius, radius - 2f, stroke)

        // Try to draw a category icon centered in the circle
        try {
            val resId = getCategoryIconRes(poi.category)
            Log.d("MapIconUtils", "createPoiIcon: selected resId=$resId for category='${poi.category}' name='${poi.name}'")
            var drawable: Drawable? = null
            try {
                drawable = AppCompatResources.getDrawable(context, resId)
            } catch (_: Exception) {
                // ignore
            }
            if (drawable == null) {
                drawable = ResourcesCompat.getDrawable(context.resources, resId, null)
            }
            if (drawable != null) {
                try {
                    // Draw the drawable as-is (preserve its original colors). Use mutate() to avoid shared state.
                    val d = drawable.mutate()
                    // Scale drawable to fit inside circle (about 60% of circle diameter)
                    val targetSize = (size * 0.60f).toInt()
                    val left = ((size - targetSize) / 2)
                    val top = ((size - targetSize) / 2)
                    d.setBounds(left, top, left + targetSize, top + targetSize)
                    d.draw(canvas)
                    Log.d("MapIconUtils", "createPoiIcon: drew drawable for poi='${poi.name}' (mutated)")
                    return bmp
                } catch (e: Exception) {
                    Log.w("MapIconUtils", "Failed to draw drawable resId=$resId for poi='${poi.name}'", e)
                }
            } else {
                Log.d("MapIconUtils", "Drawable resId=$resId returned null for poi='${poi.name}'")
            }
            // Try the general scenic marker drawable before falling back to a letter
            try {
                val fallbackRes = R.drawable.ic_scenic_marker
                val d2 = AppCompatResources.getDrawable(context, fallbackRes) ?: ResourcesCompat.getDrawable(context.resources, fallbackRes, null)
                d2?.let { dd ->
                    try {
                        val dm = dd.mutate()
                        val targetSize2 = (size * 0.72f).toInt()
                        val left2 = ((size - targetSize2) / 2)
                        val top2 = ((size - targetSize2) / 2)
                        dm.setBounds(left2, top2, left2 + targetSize2, top2 + targetSize2)
                        dm.draw(canvas)
                        Log.d("MapIconUtils", "createPoiIcon: drew fallback scenic marker for poi='${poi.name}'")
                        return bmp
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w("MapIconUtils", "Failed to load/draw category icon for poi='${poi.name}'", e)
            // fall back to letter if drawable fails
        }

        // Fallback: draw initial letter like before
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = android.graphics.Color.WHITE
        textPaint.textSize = 28f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.isFakeBoldText = true
        val letter = poi.name.trim().takeIf { it.isNotEmpty() }?.get(0)?.uppercaseChar() ?: '?'
        val text = letter.toString()
        val textWidth = textPaint.measureText(text)
        val fm = textPaint.fontMetrics
        val x = (size - textWidth) / 2f
        val y = (size - fm.ascent - fm.descent) / 2f
        canvas.drawText(text, x, y, textPaint)

        return bmp
    }

    // Public accessor so callers can request specific category drawable if needed
    fun getCategoryIconResource(category: String?): Int {
        return getCategoryIconRes(category)
    }

    /**
     * Attempt to draw the category drawable forcefully on a circular background. This method
     * draws the background and then the drawable (preserving its colors). If the drawable
     * cannot be loaded, it falls back to createPoiIcon which may draw a letter.
     */
    fun createPoiIconPreferDrawable(context: Context, poi: Poi, size: Int = 88): Bitmap {
        val radius = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = getCategoryColor(poi.category)
        canvas.drawCircle(radius, radius, radius - 2f, paint)

        // white stroke
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 3f
        stroke.color = android.graphics.Color.WHITE
        canvas.drawCircle(radius, radius, radius - 2f, stroke)

        // Try drawable (larger target size for better visibility)
        try {
            val resId = getCategoryIconRes(poi.category)
            var drawable: Drawable? = null
            try { drawable = AppCompatResources.getDrawable(context, resId) } catch (_: Exception) {}
            if (drawable == null) drawable = ResourcesCompat.getDrawable(context.resources, resId, null)
            if (drawable != null) {
                val d = drawable.mutate()
                val targetSize = (size * 0.72f).toInt()
                val left = ((size - targetSize) / 2)
                val top = ((size - targetSize) / 2)
                d.setBounds(left, top, left + targetSize, top + targetSize)
                d.draw(canvas)
                return bmp
            }
        } catch (_: Exception) {
        }

        // fallback
        return createPoiIcon(context, poi, size)
    }
}
