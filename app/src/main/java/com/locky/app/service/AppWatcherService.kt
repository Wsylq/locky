package com.locky.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.locky.app.LockyApp
import com.locky.app.data.AppRepository
import com.locky.app.data.InstalledAppsLoader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
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

    /**
     * Background work for the protected-app cache.
     *
     * The exception handler is not optional here. This scope runs in the
     * accessibility service's process, so an uncaught failure would kill the
     * process and take the service down with it — which presents to the user as
     * "the service is enabled but not locking anything", indistinguishable from
     * never having enabled it. Swallowing and logging keeps a bad app from
     * turning into a dead lock.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "watcher background work failed", throwable)
            LockyRuntime.onWatcherError("The lock service hit an internal error")
        },
    )

    private lateinit var repository: AppRepository
    private lateinit var unlockState: UnlockState
    private lateinit var appsLoader: InstalledAppsLoader

    /**
     * The overlay window for this service instance, or null when there is none.
     *
     * Nullable rather than `lateinit` on purpose. It genuinely does not exist when
     * the overlay permission is refused or the window cannot be added, and the
     * event callback still runs in that case — a `lateinit` field would throw
     * `UninitializedPropertyAccessException` there, and Android swallows exceptions
     * from `onAccessibilityEvent`, so the lock would silently gate nothing while
     * reporting itself as healthy. Every use below therefore null-checks.
     */
    @Volatile
    private var overlay: LockOverlayController? = null

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
     * Stops the lock re-gating the app it just unlocked.
     *
     * Process-wide rather than per-instance because the unlock happens in the
     * overlay or the biometric host while the check happens here, and both have to
     * be talking about the same thing.
     */
    private val gateSuppression = GateSuppression()

    /**
     * Count of consecutive foreground apps that were not protected.
     *
     * Only used for diagnostics: a high value alongside an empty cache is the
     * signature of "the lock is installed but protecting nothing".
     */
    private var unprotectedForeground = 0

    private val isReady: Boolean
        get() = this::repository.isInitialized && this::unlockState.isInitialized

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Reported before anything that can fail. The service genuinely is
        // connected at this point, so if a later step throws the UI must say
        // "running" rather than blaming the user for not having enabled it.
        LockyRuntime.onServiceConnected(overlayAttached = false)
        Log.i(TAG, "service connected")

        // Without the database there is no protected set, so there is nothing to
        // gate. Say so rather than silently locking nothing.
        repository = runCatching { LockyApp.from(this).repository }.getOrElse { e ->
            Log.e(TAG, "cannot open the protected-app database", e)
            LockyRuntime.onWatcherError("Could not read the protected app list")
            return
        }
        unlockState = runCatching { UnlockState.get(this) }.getOrElse { e ->
            Log.e(TAG, "cannot open the unlock state store", e)
            LockyRuntime.onWatcherError("Could not read the unlock state")
            return
        }
        appsLoader = InstalledAppsLoader(this)

        // Assigned to both the instance field the event callback reads and the
        // companion field the biometric host reads. They must be the same object.
        val created = runCatching {
            LockOverlayController(this).also { it.attach() }
        }.getOrElse { e ->
            Log.e(TAG, "overlay could not be created; using the full screen gate", e)
            null
        }
        overlay = created
        activeOverlay = created
        val overlayAttached = created?.isAttached == true
        Log.i(TAG, "overlay attached=$overlayAttached")
        LockyRuntime.onServiceConnected(overlayAttached = overlayAttached)

        // Mirror the database into memory, resolving each label off the main
        // thread so a package with fifty installed apps cannot stall the event
        // thread. collect() rather than collectLatest(): if a second emission
        // arrives while this one is still running, collectLatest would cancel
        // mid-build and leave the cache permanently empty.
        scope.launch {
            repository.observeLockedApps().collect { apps ->
                // Built to completion before anything is swapped in, so a
                // partially resolved map can never replace a good one.
                val resolved = HashMap<String, ProtectedApp>(apps.size)
                for (entity in apps) {
                    val label = runCatching { appsLoader.labelFor(entity.packageName) }
                        .getOrDefault(entity.packageName)
                    resolved[entity.packageName] = ProtectedApp(entity.packageName, label)
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

        LockyRuntime.onForegroundEvent(packageName)

        // Never gate our own windows: the overlay changing state would otherwise
        // re-trigger itself in a loop.
        if (packageName == this.packageName) return

        // Dismissing the lock hands focus back to the app that was locked, so the
        // event that arrives next is about an app that was just unlocked. Gating on
        // it would make the lock unsatisfiable whenever the grace period is short.
        if (!gateSuppression.shouldGate(packageName)) return

        val target = protectedApps[packageName]
        val window = overlay

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
            //
            // Hiding it must also release the challenge. The overlay claims one
            // when it goes up, and the only thing that gives it back is this code
            // path; without the release the flag stays set and claimChallenge()
            // fails for every launch afterwards, so a single trip through the
            // launcher would leave Locky permanently unable to gate anything
            // again with no symptom other than the lock quietly not appearing.
            if (window?.isShowing == true) {
                window.hide()
                releaseChallenge()
            }
            if (fallbackActive) {
                fallbackActive = false
                releaseChallenge()
            }
            return
        }

        unprotectedForeground = 0

        if (unlockState.isUnlocked(packageName)) {
            Log.i(TAG, "$packageName already unlocked, letting it through")
            // This app is inside its grace period, so nothing should be covering
            // it. Reaching here with the overlay up means it is still showing for
            // a different app, and the user would be stuck staring at that app's
            // lock with no way to dismiss it.
            if (window?.isShowing == true) {
                window.hide()
                releaseChallenge()
            }
            return
        }

        // Already gating this app: just retitle for the new foreground app rather
        // than stacking a second challenge.
        if (window?.isShowing == true) {
            window.show(target.packageName, target.label)
            return
        }

        if (!claimChallenge()) return

        // Re-checked here rather than reusing `window`, because the overlay
        // permission can be granted while the service is already running. Doing
        // this at the point of gating rather than only at connect time is what
        // makes the app switch to the instant lock without the user having to
        // toggle the accessibility service off and on again first.
        val gate = overlayForGating()
        if (gate?.isAttached == true) {
            // The fast path: the window already exists and is attached, so this
            // is only a visibility change.
            gate.show(target.packageName, target.label)
        } else {
            // No overlay permission. Fall back to a real activity so the app is
            // still locked, accepting the slower transition.
            launchFallback(target)
        }
    }

    /**
     * The overlay to gate with, building it if it is not there yet.
     *
     * Normally this is the window created once at service connect, and the call
     * returns it without doing any work — one field read on the path between an
     * app appearing and the lock covering it. Only when there is no window does
     * it retry, which is the case where the overlay permission was granted after
     * the service connected. Retrying there costs one Settings read and, at most,
     * one view inflation, and it happens on a path that would otherwise be using
     * the slow activity gate anyway.
     */
    private fun overlayForGating(): LockOverlayController? {
        overlay?.let { return it }
        if (!isReady) return null

        val created = runCatching {
            LockOverlayController(this).also { it.attach() }
        }.getOrElse { e ->
            Log.e(TAG, "overlay could not be created; using the full screen gate", e)
            null
        } ?: return null

        if (!created.isAttached) return null

        overlay = created
        activeOverlay = created
        LockyRuntime.onOverlayAttached()
        Log.i(TAG, "overlay attached on demand")
        return created
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
        overlay?.detach()
        overlay = null
        activeOverlay = null
        releaseChallenge()
        scope.cancel()
        LockyRuntime.onServiceDisconnected()
        Log.i(TAG, "service disconnected")
    }

    /**
     * A protected app with everything needed to gate it already resolved.
     *
     * Deliberately no icon: the lock screen does not display one, and
     * `PackageManager.getApplicationIcon` is one of the more failure-prone calls
     * in the platform. Loading them for a UI that never drew them was a crash
     * risk on the accessibility service's critical path for no benefit.
     */
    private data class ProtectedApp(
        val packageName: String,
        val label: String,
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

        /**
         * Process-wide because the unlock is performed by the overlay or the
         * biometric host while the check that has to know about it happens in the
         * service's event callback. Separate from the service instance because
         * those two live in the same process but not in the same object.
         */
        private val gateSuppression = GateSuppression()

        fun claimChallenge(): Boolean = challengeInProgress.compareAndSet(false, true)

        fun releaseChallenge() {
            challengeInProgress.set(false)
        }

        /**
         * Records that [packageName] has just been unlocked.
         *
         * Every unlock path calls this alongside releasing the challenge. It is
         * what stops the window change caused by the lock dismissing itself from
         * immediately gating the same app again.
         */
        fun onGateSatisfied(packageName: String) {
            if (packageName.isNotEmpty()) gateSuppression.onSatisfied(packageName)
        }

        /**
         * Drops any suppression immediately.
         *
         * For "lock now": the user has asked for the next protected app to be
         * gated, so an app unlocked a moment ago must not be quietly let through.
         */
        fun clearGateSuppression() {
            gateSuppression.clear()
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
