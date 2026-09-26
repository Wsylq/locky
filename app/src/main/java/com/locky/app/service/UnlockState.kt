package com.locky.app.service

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which apps are currently unlocked, and for how long.
 *
 * An app lock is only tolerable if you are not re-entering the PIN every time you
 * switch away and back, so a successful unlock grants a short grace period during
 * which the same app opens freely.
 *
 * The grace period is an absolute deadline rather than a sliding one, so idling
 * inside an app does not keep extending it indefinitely.
 */
class UnlockState(
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val deadlines = ConcurrentHashMap<String, Long>()

    /** Records a successful unlock of [packageName], starting its grace period. */
    fun grant(packageName: String) {
        deadlines[packageName] = clock() + graceMillis
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

        fun get(context: Context): UnlockState =
            instance ?: synchronized(this) {
                instance ?: UnlockState().also { instance = it }
            }
    }
}
