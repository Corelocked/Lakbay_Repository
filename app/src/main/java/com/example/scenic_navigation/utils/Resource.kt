package com.example.scenic_navigation.utils

/**
 * A generic class that holds a value with its loading status.
 * Useful for representing network-bound resources.
 */
sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String, val exception: Exception? = null) : Resource<Nothing>()
    object Loading : Resource<Nothing>()
    object Idle : Resource<Nothing>()
}

