package com.example.scenic_navigation

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.example.scenic_navigation.data.local.AppDatabase
import com.example.scenic_navigation.data.local.FavoritePoiEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Favorites store backed by Room. Public API stays stable so UI callers do not need to know
 * whether favorites are stored in SharedPreferences or the local database.
 */
object FavoriteStore {
    private var initialized = false
    private lateinit var ctx: Context
    private val database get() = AppDatabase.getInstance(ctx)
    @Volatile
    private var favoriteKeysCache: MutableSet<String> = mutableSetOf()

    fun init(context: Context) {
        if (!initialized) {
            ctx = context.applicationContext
            favoriteKeysCache = runBlocking(Dispatchers.IO) {
                database.favoritePoiDao().getAllKeys().toMutableSet()
            }
            initialized = true
        }
    }

    // Helper to compute canonical key in the same format used elsewhere: name,category,lat,lon
    private fun canonicalKeyForPoi(poiName: String, category: String?, lat: Double?, lon: Double?): String {
        val n = poiName.trim().replace(",", " ")
        val c = (category ?: "").trim().replace(",", " ")
        val latS = lat?.toString() ?: "0.0"
        val lonS = lon?.toString() ?: "0.0"
        return "${n},${c},${latS},${lonS}"
    }

    fun getAllFavorites(): List<com.example.scenic_navigation.models.Poi> {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        return runBlocking(Dispatchers.IO) {
            database.favoritePoiDao().getAll().map { it.toPoi() }
        }
    }

    fun observeAllFavorites(): LiveData<List<com.example.scenic_navigation.models.Poi>> {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        return database.favoritePoiDao().observeAll().map { entities ->
            entities.map { it.toPoi() }
        }
    }

    fun isFavorite(key: String): Boolean {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        return favoriteKeysCache.contains(key)
    }

    fun addFavorite(key: String, poi: com.example.scenic_navigation.models.Poi) {
        val storeKey = try { com.example.scenic_navigation.ui.RecommendationsAdapter.canonicalKey(poi) } catch (_: Exception) {
            canonicalKeyForPoi(poi.name, poi.category, poi.lat, poi.lon)
        }
        runBlocking(Dispatchers.IO) {
            val dao = database.favoritePoiDao()
            dao.deleteByName(poi.name.trim())
            dao.upsert(FavoritePoiEntity.fromPoi(storeKey, poi))
        }
        favoriteKeysCache.removeIf { existing ->
            existing.split(',').firstOrNull()?.trim()?.equals(poi.name.trim(), ignoreCase = true) == true
        }
        favoriteKeysCache.add(storeKey)
    }

    fun removeFavorite(key: String) {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        val namePart = key.split(',').firstOrNull()?.trim()
        runBlocking(Dispatchers.IO) {
            val dao = database.favoritePoiDao()
            dao.deleteByKey(key)
            if (!namePart.isNullOrBlank()) {
                dao.deleteByName(namePart)
            }
        }
        favoriteKeysCache.remove(key)
        if (!namePart.isNullOrBlank()) {
            favoriteKeysCache.removeIf { existing ->
                existing.split(',').firstOrNull()?.trim()?.equals(namePart, ignoreCase = true) == true
            }
        }
    }

    fun getFavoriteKeys(): Set<String> {
        if (!initialized) throw IllegalStateException("FavoriteStore not initialized")
        return favoriteKeysCache.toSet()
    }
}
