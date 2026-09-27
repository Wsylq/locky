package com.locky.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/** A launchable app, with the icon the list needs to draw. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/**
 * Reads the set of launchable apps installed on the device.
 *
 * This is the one genuinely slow call in the app (it hits the package manager for
 * every installed app), so it runs on [Dispatchers.IO] and its result is cached by
 * the caller.
 */
class InstalledAppsLoader(private val context: Context) {

    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        }

        // Several activities in one package are common (e.g. browser shortcuts),
        // so collapse to one entry per package, keeping the first match.
        val seen = HashSet<String>(resolved.size)
        val apps = ArrayList<InstalledApp>(resolved.size)

        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue

            apps += InstalledApp(
                packageName = pkg,
                label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                    .ifEmpty { pkg },
                icon = try {
                    info.loadIcon(pm)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                },
            )
        }

        // Sort with a collator so accented and non-Latin names land in the
        // position a reader expects, not wherever their code points fall.
        val collator = Collator.getInstance(Locale.getDefault())
        apps.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** Loads the label for a single package, falling back to the package name. */
    suspend fun labelFor(packageName: String): String = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
