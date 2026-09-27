package com.locky.app.service

import android.content.Context
import com.locky.app.security.ReLockPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which apps are currently unlocked, and for how long.
 *
 * An app lock is only tolerable if you are not re-entering the PIN every time you
 * switch away and back, so a successful unlock grants a grace period during which
 * the same app opens freely.
 *
 * The grace period is an absolute deadline rather than a sliding one, so idling
 * inside an app does not keep extending it indefinitely.
 *
 * The period comes from a function rather than a value because the user can change
 * it. The watcher outlives any one app launch, so a period captured when this was
 * constructed would stay at the old setting until the service happened to be
 * restarted, and shortening it would appear to do nothing.
 */
class UnlockState(
    private val graceMillis: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Fixed-period overload, for tests and for callers that know their own value. */
    constructor(
        graceMillis: Long,
        clock: () -> Long = System::currentTimeMillis,
    ) : this({ graceMillis }, clock)

    private val deadlines = ConcurrentHashMap<String, Long>()

    /** Records a successful unlock of [packageName], starting its grace period. */
    fun grant(packageName: String) {
        deadlines[packageName] = clock() + graceMillis()
    }

    /** True when [packageName] is inside its grace period right now. */
    fun isUnlocked(packageName: String): Boolean {
        val until = deadlines[packageName] ?: return false
        if (clock() >= until) {
            deadlines.remove(packageName)
            return false
        }
        return true
    }

    /** Ends any grace period for [packageName], e.g. when the user locks now. */
    fun revoke(packageName: String) {
        deadlines.remove(packageName)
    }

    fun revokeAll() = deadlines.clear()

    /** Drops expired entries so the map does not grow without bound. */
    fun prune() {
        val now = clock()
        deadlines.entries.removeAll { it.value <= now }
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 60_000L

        @Volatile
        private var instance: UnlockState? = null

        /**
         * The shared instance, reading the user's re-lock setting on every grant.
         *
         * The context is captured only to reach the preference file and is reduced
         * to the application context first, because this object lives for as long
         * as the process does.
         */
        fun get(context: Context): UnlockState =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    // Named, not a trailing lambda: a trailing one would bind to
                    // `clock`, the last parameter, and leave the period unset.
                    UnlockState(
                        graceMillis = { ReLockPolicy.graceMillis(appContext) },
                    ).also { instance = it }
                }
            }
    }
}
