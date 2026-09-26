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

    fun reset() {
        onSuccess()
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_LOCKOUT_MILLIS = 30_000L
    }
}
