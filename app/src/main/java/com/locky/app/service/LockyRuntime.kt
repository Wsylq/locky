package com.locky.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live status of the watcher, so the app can say whether it is actually running.
 *
 * The accessibility service is a black box from the outside: the Settings screen
 * can show it as enabled while the process has been killed, and nothing in the UI
 * distinguishes "Locky is watching" from "Locky is installed". When the lock fails
 * to appear, this is the first question worth answering, so it is surfaced in the
 * app rather than left to logcat.
 */
object LockyRuntime {

    data class Status(
        /** True between [AppWatcherService.onServiceConnected] and its teardown. */
        val isServiceConnected: Boolean = false,
        /** How many protected apps are cached and ready to gate. */
        val protectedAppCount: Int = 0,
        /** True when the fast overlay window is usable. */
        val isOverlayAttached: Boolean = false,
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

    internal fun onServiceConnected(overlayAttached: Boolean) {
        _status.value = _status.value.copy(
            isServiceConnected = true,
            isOverlayAttached = overlayAttached,
        )
    }

    internal fun onProtectedAppsChanged(count: Int) {
        _status.value = _status.value.copy(protectedAppCount = count)
    }

    internal fun onServiceDisconnected() {
        _status.value = Status()
    }
}
