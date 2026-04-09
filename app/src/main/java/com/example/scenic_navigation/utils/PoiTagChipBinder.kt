package com.example.scenic_navigation.utils

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.scenic_navigation.R
import com.example.scenic_navigation.models.Poi

object PoiTagChipBinder {
    fun bind(container: LinearLayout, poi: Poi, maxTags: Int = 3, preferredTokens: Set<String> = emptySet()) {
        container.removeAllViews()
        val context = container.context
        val tags = PoiTagFormatter.formatTagList(poi, maxTags, preferredTokens)
        if (tags.isEmpty()) {
            container.visibility = android.view.View.GONE
            return
        }

        container.visibility = android.view.View.VISIBLE
        tags.forEachIndexed { index, tag ->
            container.addView(createTagView(context, tag, index > 0))
        }
    }

    private fun createTagView(context: Context, tag: String, addStartMargin: Boolean): TextView {
        return TextView(context).apply {
            text = tag.uppercase()
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(context, R.drawable.editorial_tag_background)
            val horizontal = dp(context, 12)
            val vertical = dp(context, 6)
            setPadding(horizontal, vertical, horizontal, vertical)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (addStartMargin) marginStart = dp(context, 8)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
