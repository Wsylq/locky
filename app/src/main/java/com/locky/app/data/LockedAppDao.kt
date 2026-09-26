package com.locky.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LockedAppDao {

    @Query("SELECT * FROM locked_apps ORDER BY label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<LockedAppEntity>>

    @Query("SELECT * FROM locked_apps")
    suspend fun getAll(): List<LockedAppEntity>

    @Query("SELECT packageName FROM locked_apps")
    fun observeLockedPackages(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM locked_apps WHERE packageName = :packageName)")
    fun observeIsLocked(packageName: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM locked_apps WHERE packageName = :packageName)")
    suspend fun isLocked(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: LockedAppEntity)

    @Query("DELETE FROM locked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM locked_apps")
    suspend fun clear()
}
