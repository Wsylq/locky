package com.locky.app.security

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks consecutive PIN failures and enforces a cool-off after too many.
 *
 * State is held in memory only, so wiping it means rebooting the app, which is
 * also why the cool-off is an explicit [lockoutRemainingMillis] rather than a
 * persisted deadline.
 */
class AttemptLimiter(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val lockoutMillis: Long = DEFAULT_LOCKOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val failures = AtomicInteger(0)

    @Volatile
    private var lockedUntil: Long = 0L

    val remainingAttempts: Int
        get() = (maxAttempts - failures.get()).coerceAtLeast(0)

    val isLockedOut: Boolean
        get() = lockoutRemainingMillis > 0L

    val lockoutRemainingMillis: Long
        get() = (lockedUntil - clock()).coerceAtLeast(0L)

    /** Records a failed attempt, locking out once the limit is passed. */
    fun onFailure() {
        if (failures.incrementAndGet() >= maxAttempts) {
            lockedUntil = clock() + lockoutMillis
            failures.set(0)
        }
    }

    fun onSuccess() {
        failures.set(0)
        lockedUntil = 0L
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_LOCKOUT_MILLIS = 30_000L

        @Volatile
        private var shared: AttemptLimiter? = null

        /**
         * The limiter the real lock screens use.
         *
         * Process-wide rather than per-instance, and the fallback path depends on
         * it: [com.locky.app.service.LockScreenActivity] is created and destroyed
         * every time a protected app is opened, so a per-instance limiter would
         * hand out a fresh set of attempts each time — the cool-off would be
         * trivially bypassed by closing the app and opening it again. The overlay
         * needs the same property for the same reason.
         *
         * The primary constructor stays public so tests can supply their own clock
         * and limits without touching the shared instance.
         */
        fun shared(): AttemptLimiter =
            shared ?: synchronized(this) {
                shared ?: AttemptLimiter().also { shared = it }
            }
    }
}
