package com.example.scenic_navigation.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface PoiDao {
    @Query("SELECT * FROM pois")
    fun getAll(): List<PoiEntity>

    @Query("SELECT COUNT(*) FROM pois")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(pois: List<PoiEntity>)

    @Query("DELETE FROM pois")
    fun clear()

    @RawQuery
    fun search(query: SupportSQLiteQuery): List<PoiEntity>
}
