package com.locky.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for the protected-app list.
 *
 * The Room table holds only protected apps, so the full device list is combined
 * with it in memory to produce the toggle state the UI needs.
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = LockyDatabase.get(appContext).lockedAppDao()
    private val loader = InstalledAppsLoader(appContext)

    /** The persisted set of protected apps. */
    fun observeLockedApps(): Flow<List<LockedAppEntity>> = dao.observeAll()

    /**
     * Resolves [packageName] against the persisted set.
     */
    suspend fun isLocked(packageName: String): Boolean = dao.isLocked(packageName)

    suspend fun lock(packageName: String) {
        if (isLocked(packageName)) return
        dao.upsert(
            LockedAppEntity(
                packageName = packageName,
                label = loader.labelFor(packageName),
            ),
        )
    }

    suspend fun unlock(packageName: String) = dao.delete(packageName)

    suspend fun setLocked(packageName: String, locked: Boolean) {
        if (locked) lock(packageName) else unlock(packageName)
    }

    /**
     * Drops rows for apps that are no longer installed.
     *
     * Takes the installed package names rather than resolving them again. The
     * caller has just loaded the full app list to render the screen, and asking
     * for it a second time doubled the slowest operation in the app on every
     * single resume.
     */
    suspend fun pruneMissingApps(installedPackages: Set<String>) {
        dao.getAll()
            .filterNot { it.packageName in installedPackages }
            .forEach { dao.delete(it.packageName) }
    }

    companion object {
        @Volatile
        private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context).also { instance = it }
            }
    }
}
