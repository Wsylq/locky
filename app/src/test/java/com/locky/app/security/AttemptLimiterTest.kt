package com.locky.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failed-attempt cool-off.
 *
 * The behaviour worth protecting is the one that was actually wrong: a lock-out
 * that only counted the first few attempts, or that could be reset by reopening
 * the gated app, is not a lock-out at all.
 */
class AttemptLimiterTest {

    private var now = 0L

    private fun limiter(
        maxAttempts: Int = 3,
        lockoutMillis: Long = 30_000L,
    ) = AttemptLimiter(
        maxAttempts = maxAttempts,
        lockoutMillis = lockoutMillis,
        clock = { now },
    )

    @Test
    fun `a fresh limiter has not locked out`() {
        assertFalse(limiter().isLockedOut)
    }

    @Test
    fun `attempts are counted down`() {
        val limiter = limiter(maxAttempts = 3)

        assertEquals(3, limiter.remainingAttempts)
        limiter.onFailure()
        assertEquals(2, limiter.remainingAttempts)
        limiter.onFailure()
        assertEquals(1, limiter.remainingAttempts)
    }

    @Test
    fun `the limit triggers a lock out`() {
        val limiter = limiter(maxAttempts = 3)

        repeat(3) { limiter.onFailure() }

        assertTrue(limiter.isLockedOut)
    }

    @Test
    fun `the lock out expires on its own`() {
        val limiter = limiter(maxAttempts = 1, lockoutMillis = 30_000L)
        limiter.onFailure()
        assertTrue(limiter.isLockedOut)

        now += 29_000
        assertTrue(limiter.isLockedOut)

        now += 2_000
        assertFalse(limiter.isLockedOut)
    }

    @Test
    fun `a success clears the counter`() {
        val limiter = limiter(maxAttempts = 3)
        limiter.onFailure()
        limiter.onFailure()
        limiter.onSuccess()

        assertEquals(3, limiter.remainingAttempts)
        assertFalse(limiter.isLockedOut)
    }

    @Test
    fun `the shared limiter is the same instance every time`() {
        // The fallback lock screen is a new activity on every launch, so this is
        // the property that stops a cool-out being reset by reopening the app.
        assertTrue(AttemptLimiter.shared() === AttemptLimiter.shared())
    }
}
