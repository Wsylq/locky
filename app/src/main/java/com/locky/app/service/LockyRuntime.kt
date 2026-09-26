package com.locky.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Live status of the watcher, so the app can say whether it is actually running.
 *
 * The accessibility service is a black box from the outside: the Settings screen
 * can show it as enabled while the process has been killed, and nothing in the UI
 * distinguishes "Locky is watching" from "Locky is installed". When the lock fails
 * to appear, this is the first question worth answering, so it is surfaced in the
 * app rather than left to logcat.
 *
 * The event counters exist to separate the two ways detection can fail. If no
 * window-state events are arriving the service is not seeing app launches at all;
 * if they are arriving and nothing is gated, the failure is downstream in the
 * lookup or the overlay. That distinction is not visible from the outside.
 */
object LockyRuntime {

    data class Status(
        /** True between [AppWatcherService.onServiceConnected] and its teardown. */
        val isServiceConnected: Boolean = false,
        /** How many protected apps are cached and ready to gate. */
        val protectedAppCount: Int = 0,
        /** True when the fast overlay window is usable. */
        val isOverlayAttached: Boolean = false,
        /** Window-state events seen since the service connected. */
        val foregroundEventCount: Long = 0L,
        /** The last package that came to the foreground, for spot-checking. */
        val lastForegroundPackage: String? = null,
    ) {
        /**
         * The reason Locky is not protecting anything, or null if it is.
         *
         * Ordered from most to least fundamental so the message shown names the
         * actual problem rather than a downstream symptom of it.
         */
        val problem: Problem?
            get() = when {
                !isServiceConnected -> Problem.SERVICE_STOPPED
                protectedAppCount == 0 -> Problem.NO_PROTECTED_APPS
                else -> null
            }
    }

    enum class Problem {
        /** The service is enabled in Settings but not actually running. */
        SERVICE_STOPPED,

        /** The service is running but nothing is marked as protected. */
        NO_PROTECTED_APPS,
    }

    private val _status = MutableStateFlow(Status())

    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * Event counter, incremented from the accessibility callback.
     *
     * Atomic because the accessibility thread and the flow collector that writes
     * [Status] do not necessarily agree on which thread they are on.
     */
    private val eventCount = AtomicLong(0)

    internal fun onServiceConnected(overlayAttached: Boolean) {
        eventCount.set(0)
        _status.value = Status(
            isServiceConnected = true,
            isOverlayAttached = overlayAttached,
        )
    }

    internal fun onProtectedAppsChanged(count: Int) {
        _status.value = _status.value.copy(protectedAppCount = count)
    }

    internal fun onForegroundEvent(packageName: String) {
        _status.value = _status.value.copy(
            foregroundEventCount = eventCount.incrementAndGet(),
            lastForegroundPackage = packageName,
        )
    }

    internal fun onServiceDisconnected() {
        eventCount.set(0)
        _status.value = Status()
    }
}
