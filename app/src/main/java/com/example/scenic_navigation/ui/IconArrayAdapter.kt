package com.example.scenic_navigation.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.example.scenic_navigation.R

data class IconItem(val text: String, val iconResId: Int)

class IconArrayAdapter(
    context: Context,
    private val items: List<IconItem>
) : ArrayAdapter<IconItem>(context, R.layout.item_dropdown_icon, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent)
    }

    private fun createView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_dropdown_icon, parent, false)

        val item = getItem(position)
        val iconView = view.findViewById<ImageView>(R.id.iv_icon)
        val textView = view.findViewById<TextView>(R.id.tv_text)

        item?.let {
            iconView.setImageResource(it.iconResId)
            textView.text = it.text
        }

        return view
    }
}
