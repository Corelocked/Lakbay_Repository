package com.example.scenic_navigation.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.scenic_navigation.models.Poi

@Entity(tableName = "favorite_pois")
data class FavoritePoiEntity(
    @PrimaryKey
    val favoriteKey: String,
    val name: String,
    val category: String,
    val description: String,
    val municipality: String,
    val province: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val scenicScore: Float? = null,
    val tags: List<String> = emptyList(),
    val photoHint: String = "",
    val imageUrl: String = ""
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
        fun fromPoi(favoriteKey: String, poi: Poi): FavoritePoiEntity {
            return FavoritePoiEntity(
                favoriteKey = favoriteKey,
                name = poi.name,
                category = poi.category,
                description = poi.description,
                municipality = poi.municipality,
                province = poi.province,
                lat = poi.lat,
                lon = poi.lon,
                scenicScore = poi.scenicScore,
                tags = poi.tags,
                photoHint = poi.photoHint,
                imageUrl = poi.imageUrl
            )
        }
    }
}
