package com.example.scenic_navigation.data.local

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FavoritePoiDao {
    @Query("SELECT * FROM favorite_pois ORDER BY name COLLATE NOCASE")
    fun observeAll(): LiveData<List<FavoritePoiEntity>>

    @Query("SELECT * FROM favorite_pois ORDER BY name COLLATE NOCASE")
    fun getAll(): List<FavoritePoiEntity>

    @Query("SELECT favoriteKey FROM favorite_pois")
    fun getAllKeys(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_pois WHERE favoriteKey = :key)")
    fun exists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: FavoritePoiEntity)

    @Query("DELETE FROM favorite_pois WHERE favoriteKey = :key")
    fun deleteByKey(key: String)

    @Query("DELETE FROM favorite_pois WHERE name = :name COLLATE NOCASE")
    fun deleteByName(name: String)
}
