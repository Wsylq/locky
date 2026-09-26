package com.locky.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches which app moves to the foreground and gates the protected ones.
 *
 * Android exposes no public API for foreground-app detection, which is why an
 * accessibility service is the mechanism every app lock uses. This one
 * deliberately asks for the minimum: `typeWindowStateChanged` events with
 * `canRetrieveWindowContent="false"`, so it learns package names and nothing else.
 *
 * ### Why this is fast
 *
 * The event callback runs on the main thread, so everything it does is on the
 * critical path between the incoming app appearing and the user seeing it. To keep
 * that path as short as possible:
 *
 * - The lock is a pre-built [LockOverlayController] window, toggled rather than
 *   created. There is no activity launch, no inflation, no measure/layout.
 * - Every protected app's label and icon is resolved in advance and cached, so
 *   the callback makes no binder calls to the package manager.
 * - The protected set is a [ConcurrentHashMap], so the lookup is a single hash
 *   get rather than a database query.
 *
 * What is left in the callback is: a string hash lookup and a visibility change.
 */
class AppWatcherService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var repository: AppRepository
    private lateinit var unlockState: UnlockState
    private lateinit var overlay: LockOverlayController
    private lateinit var appsLoader: InstalledAppsLoader

    /**
     * Protected apps with their labels and icons already resolved.
     *
     * Everything the callback needs is here, so gating a launch costs one map
     * lookup. Only apps the user actually protected are ever loaded, which keeps
     * this to a handful of entries.
     */
    private val protectedApps = ConcurrentHashMap<String, ProtectedApp>()

    /** True while the activity-based fallback gate is on screen. */
    @Volatile
    private var fallbackActive = false

    /**
     * Count of consecutive foreground apps that were not protected.
     *
     * Only used for diagnostics: a high value alongside an empty cache is the
     * signature of "the lock is installed but protecting nothing".
     */
    private var unprotectedForeground = 0

    private val isReady: Boolean
        get() = this::repository.isInitialized

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = LockyApp.from(this)
        repository = app.repository
        unlockState = UnlockState.get(this)
        appsLoader = InstalledAppsLoader(this)

        activeOverlay = LockOverlayController(this).also { it.attach() }
        Log.i(
            TAG,
            "service connected, overlay attached=${activeOverlay?.isAttached}",
        )
        LockyRuntime.onServiceConnected(overlayAttached = activeOverlay?.isAttached == true)

        // Mirror the database into memory, resolving each app's label and icon
        // off the main thread. This runs off [Dispatchers.Default] via the scope,
        // so a package with fifty installed apps cannot stall the event thread.
        scope.launch {
            repository.observeLockedApps().collectLatest { apps ->
                val resolved = apps.associate { entity ->
                    entity.packageName to ProtectedApp(
                        packageName = entity.packageName,
                        label = appsLoader.labelFor(entity.packageName),
                        icon = appsLoader.iconFor(entity.packageName),
                    )
                }
                protectedApps.clear()
                protectedApps.putAll(resolved)
                Log.i(TAG, "cached ${resolved.size} protected app(s)")
                LockyRuntime.onProtectedAppsChanged(resolved.size)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isReady) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Never gate our own windows: the overlay changing state would otherwise
        // re-trigger itself in a loop.
        if (packageName == this.packageName) return

        val target = protectedApps[packageName]

        if (target == null) {
            // Nothing to gate. This is the normal case for the launcher, the
            // shade, and Settings, but it is also what happens when the cache is
            // empty and every app looks unprotected, so it is worth counting.
            unprotectedForeground++
            if (unprotectedForeground == UNPROTECTED_LOG_THRESHOLD) {
                Log.w(
                    TAG,
                    "$unprotectedForeground foreground apps, none protected " +
                        "(cached=${protectedApps.size}). Lock is not active.",
                )
            }

            // The overlay is a system window and does not follow the foreground app
            // on its own, so it has to be dismissed explicitly or Locky ends up
            // covering the home screen.
            if (overlay.isShowing) overlay.hide()
            if (fallbackActive) {
                fallbackActive = false
                releaseChallenge()
            }
            return
        }

        unprotectedForeground = 0

        if (unlockState.isUnlocked(packageName)) {
            Log.i(TAG, "$packageName already unlocked, letting it through")
            return
        }

        // Already gating this app: just retitle for the new foreground app rather
        // than stacking a second challenge.
        if (overlay.isShowing) {
            overlay.show(target.packageName, target.label)
            return
        }

        if (!claimChallenge()) return

        if (overlay.isAttached) {
            // The fast path: the window already exists and is attached, so this
            // is only a visibility change.
            overlay.show(target.packageName, target.label)
        } else {
            // No overlay permission. Fall back to a real activity so the app is
            // still locked, accepting the slower transition.
            launchFallback(target)
        }
    }

    /** Starts the activity-based gate for when the overlay cannot be used. */
    private fun launchFallback(target: ProtectedApp) {
        try {
            startActivity(
                Intent(this, LockScreenActivity::class.java)
                    .putExtra(LockScreenActivity.EXTRA_PACKAGE_NAME, target.packageName)
                    .putExtra(LockScreenActivity.EXTRA_LABEL, target.label)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            fallbackActive = true
        } catch (e: Exception) {
            Log.e(TAG, "fallback activity failed to start", e)
            releaseChallenge()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /**
     * Releases the overlay.
     *
     * Essential rather than tidiness: the overlay is focusable, so if the service
     * is turned off while it is up, the user would be left staring at a lock
     * screen whose owner no longer exists.
     */
    private fun teardown() {
        if (this::overlay.isInitialized) {
            activeOverlay = null
            overlay.detach()
        }
        releaseChallenge()
        scope.cancel()
        LockyRuntime.onServiceDisconnected()
        Log.i(TAG, "service disconnected")
    }

    /** A protected app with everything needed to gate it already resolved. */
    private data class ProtectedApp(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    companion object {
        private const val TAG = "LockyWatcher"

        /** How many unprotected launches in a row before warning. */
        private const val UNPROTECTED_LOG_THRESHOLD = 5

        /**
         * True while a challenge is up.
         *
         * Held in the companion because the overlay outlives any single service
         * instance and the biometric host has to be able to release it.
         */
        private val challengeInProgress = AtomicBoolean(false)

        @Volatile
        private var activeOverlay: LockOverlayController? = null

        fun claimChallenge(): Boolean = challengeInProgress.compareAndSet(false, true)

        fun releaseChallenge() {
            challengeInProgress.set(false)
        }

        /**
         * The live overlay, if the service is connected.
         *
         * Exposed so the biometric host can dismiss the lock after a successful
         * system prompt.
         */
        fun overlay(): LockOverlayController? = activeOverlay
    }
}
