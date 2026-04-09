package com.example.scenic_navigation.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PoiEntity::class, FavoritePoiEntity::class], version = 4, exportSchema = false)
@TypeConverters(PoiTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun poiDao(): PoiDao
    abstract fun favoritePoiDao(): FavoritePoiDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add imageUrl for older v2 installs that predate the POI image URL column.
                val cursor = db.query("PRAGMA table_info(`pois`)")
                var hasImageUrl = false
                cursor.use {
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == "imageUrl") {
                            hasImageUrl = true
                            break
                        }
                    }
                }
                if (!hasImageUrl) {
                    db.execSQL("ALTER TABLE `pois` ADD COLUMN `imageUrl` TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add imageUrl for existing favorites so favorite cards can reuse dataset images.
                val cursor = db.query("PRAGMA table_info(`favorite_pois`)")
                var hasImageUrl = false
                cursor.use {
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == "imageUrl") {
                            hasImageUrl = true
                            break
                        }
                    }
                }
                if (!hasImageUrl) {
                    db.execSQL("ALTER TABLE `favorite_pois` ADD COLUMN `imageUrl` TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scenic_navigation.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
        }
    }
}
