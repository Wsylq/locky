package com.locky.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LockedAppEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LockyDatabase : RoomDatabase() {

    abstract fun lockedAppDao(): LockedAppDao

    companion object {
        @Volatile
        private var instance: LockyDatabase? = null

        fun get(context: Context): LockyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LockyDatabase::class.java,
                    "locky.db",
                ).build().also { instance = it }
            }
    }
}
