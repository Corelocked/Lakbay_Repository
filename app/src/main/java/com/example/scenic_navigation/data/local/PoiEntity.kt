package com.example.scenic_navigation.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.scenic_navigation.models.Poi

@Entity(tableName = "pois")
data class PoiEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val category: String,
    val description: String,
    val location: String,
    val municipality: String,
    val province: String,
    val lat: Double,
    val lon: Double,
    val scenicScore: Float? = null,
    val tags: List<String> = emptyList(),
    val photoHint: String = "",
    val imageUrl: String = "" // URL to the POI image
) {
    fun toPoi(): Poi {
        return Poi(
            name = name,
            category = category,
            description = description,
            municipality = municipality,
            lat = lat,
            lon = lon,
            scenicScore = scenicScore,
            province = province,
            tags = tags,
            photoHint = photoHint,
            imageUrl = imageUrl
        )
    }

    companion object {
        fun fromPoi(poi: Poi, location: String): PoiEntity {
            return PoiEntity(
                name = poi.name,
                category = poi.category,
                description = poi.description,
                location = location,
                municipality = poi.municipality,
                province = poi.province,
                lat = poi.lat ?: 0.0,
                lon = poi.lon ?: 0.0,
                scenicScore = poi.scenicScore,
                tags = poi.tags,
                photoHint = poi.photoHint,
                imageUrl = poi.imageUrl
            )
        }
    }
}
