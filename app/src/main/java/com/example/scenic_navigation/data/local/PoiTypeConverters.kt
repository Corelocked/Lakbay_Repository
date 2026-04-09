package com.example.scenic_navigation.data.local

import androidx.room.TypeConverter

class PoiTypeConverters {
    @TypeConverter
    fun fromTags(value: List<String>): String {
        return value.joinToString("|")
    }

    @TypeConverter
    fun toTags(value: String): List<String> {
        return value.split("|").map { it.trim() }.filter { it.isNotBlank() }
    }
}
