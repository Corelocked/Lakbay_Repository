package com.example.scenic_navigation.utils

import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * View Extensions
 */
fun View.show() {
    visibility = View.VISIBLE
}

fun View.hide() {
    visibility = View.GONE
}

fun View.invisible() {
    visibility = View.INVISIBLE
}

/**
 * Fragment Extensions
 */
fun Fragment.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(requireContext(), message, duration).show()
}

/**
 * String Extensions
 */
fun String.isValidLocation(): Boolean {
    return this.trim().isNotEmpty() && this.length >= 3
}

/**
 * Number Extensions
 */
fun Double.toDistanceString(): String {
    return when {
        this < 1000 -> String.format("%.0f m", this)
        this < 10000 -> String.format("%.1f km", this / 1000)
        else -> String.format("%.0f km", this / 1000)
    }
}

fun Long.toDurationString(): String {
    val hours = this / (1000 * 60 * 60)
    val minutes = (this % (1000 * 60 * 60)) / (1000 * 60)

    return when {
        hours > 0 -> String.format("%d hr %d min", hours, minutes)
        minutes > 0 -> String.format("%d min", minutes)
        else -> "< 1 min"
    }
}

