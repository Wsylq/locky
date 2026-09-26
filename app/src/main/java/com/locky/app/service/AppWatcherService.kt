package com.locky.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.locky.app.LockyApp
import com.locky.app.data.AppRepository
import com.locky.app.data.InstalledAppsLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches which app moves to the foreground and raises the lock screen when that
 * app is protected.
 *
 * Android exposes no public API for foreground-app detection, which is why an
 * accessibility service is the mechanism every app lock uses. This one
 * deliberately asks for the minimum: `typeWindowStateChanged` events with
 * `canRetrieveWindowContent="false"`, so it learns package names and nothing else.
 */
class AppWatcherService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var repository: AppRepository
    private lateinit var unlockState: UnlockState
    private lateinit var appsLoader: InstalledAppsLoader

    /** Protected packages, mirrored in memory so each event is a set lookup. */
    private val lockedPackages = mutableSetOf<String>()

    private val isReady: Boolean
        get() = this::repository.isInitialized

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = LockyApp.from(this)
        repository = app.repository
        unlockState = UnlockState.get(this)
        appsLoader = InstalledAppsLoader(this)

        // Mirror the database into memory: it is the source of truth, but it is a
        // Flow API and this callback must not block the event thread.
        scope.launch {
            repository.observeLockedApps().collectLatest { apps ->
                synchronized(lockedPackages) {
                    lockedPackages.clear()
                    lockedPackages += apps.map { it.packageName }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isReady) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (shouldIgnore(packageName)) return
        if (unlockState.isUnlocked(packageName)) return
        if (!isLocked(packageName)) return
        if (!claimChallenge()) return

        try {
            startActivity(
                Intent(this, LockScreenActivity::class.java)
                    .putExtra(LockScreenActivity.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(LockScreenActivity.EXTRA_LABEL, appsLoader.peekLabel(packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // A background activity start can be refused by the system. Failing
            // open is the lesser evil here: the user reaches their app rather
            // than sitting on a lock screen with no way past it.
            releaseChallenge()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        scope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** True when [packageName] is one of the protected apps. */
    private fun isLocked(packageName: String): Boolean =
        synchronized(lockedPackages) { packageName in lockedPackages }

    /**
     * Packages that must never trigger the lock screen.
     *
     * Locky's own package is excluded so that dismissing the lock screen does not
     * immediately re-challenge it. System packages are excluded because their
     * window changes fire constantly (recents, shade, permission dialogs) and
     * none of them are app launches.
     */
    private fun shouldIgnore(packageName: String): Boolean =
        packageName == this.packageName || packageName in SYSTEM_PACKAGES

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SYSTEM_PERMISSION_CONTROLLER = "com.android.permissioncontroller"
        private const val SYSTEM_LAUNCHER = "com.android.launcher3"
        private const val SYSTEM_SETTINGS = "com.android.settings"

        private val SYSTEM_PACKAGES = setOf(
            SYSTEM_UI,
            SYSTEM_PERMISSION_CONTROLLER,
            SYSTEM_LAUNCHER,
            SYSTEM_SETTINGS,
        )

        /**
         * True while a lock screen is on its way up or on display.
         *
         * Held in the companion because the service instance is process-scoped and
         * the lock screen — a separate component — has to be able to release it.
         */
        private val challengeInProgress = AtomicBoolean(false)

        /** Returns true if the caller took ownership of the pending challenge. */
        fun claimChallenge(): Boolean = challengeInProgress.compareAndSet(false, true)

        /** Releases ownership so the next app launch can be challenged again. */
        fun releaseChallenge() {
            challengeInProgress.set(false)
        }
    }
}
