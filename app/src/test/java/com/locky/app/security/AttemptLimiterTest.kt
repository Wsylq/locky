package com.locky.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock-out policy, exercised against a fake clock so the timing behaviour can
 * be asserted without any real waiting.
 */
class AttemptLimiterTest {

    private var now = 1_000L
    private val clock = { now }

    @Test
    fun `allows attempts before the limit is reached`() {
        val limiter = AttemptLimiter(maxAttempts = 3, lockoutMillis = 30_000, clock = clock)

        limiter.onFailure()
        limiter.onFailure()

        assertFalse(limiter.isLockedOut)
        assertEquals(1, limiter.remainingAttempts)
    }

    @Test
    fun `locks out once the limit is reached`() {
        val limiter = AttemptLimiter(maxAttempts = 3, lockoutMillis = 30_000, clock = clock)

        repeat(3) { limiter.onFailure() }

        assertTrue(limiter.isLockedOut)
        assertEquals(30_000L, limiter.lockoutRemainingMillis)
    }

    @Test
    fun `expires the lock-out once the clock passes the deadline`() {
        val limiter = AttemptLimiter(maxAttempts = 1, lockoutMillis = 30_000, clock = clock)
        limiter.onFailure()
        assertTrue(limiter.isLockedOut)

        now += 29_999
        assertTrue(limiter.isLockedOut)

        now += 2
        assertFalse(limiter.isLockedOut)
    }

    @Test
    fun `a success clears the failure count`() {
        val limiter = AttemptLimiter(maxAttempts = 3, lockoutMillis = 30_000, clock = clock)

        limiter.onFailure()
        limiter.onFailure()
        limiter.onSuccess()

        assertEquals(3, limiter.remainingAttempts)
        assertFalse(limiter.isLockedOut)
    }

    @Test
    fun `a success also clears an active lock-out`() {
        val limiter = AttemptLimiter(maxAttempts = 1, lockoutMillis = 30_000, clock = clock)
        limiter.onFailure()
        assertTrue(limiter.isLockedOut)

        limiter.onSuccess()

        assertFalse(limiter.isLockedOut)
    }
}
